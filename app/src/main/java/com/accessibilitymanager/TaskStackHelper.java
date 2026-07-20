package com.accessibilitymanager;

import android.app.ActivityManager;
import android.app.IActivityTaskManager;
import android.app.ITaskStackListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * 利用 Shizuku + IActivityTaskManager + ITaskStackListener，
 * 在不开无障碍的情况下实时监听前台应用。
 *
 * 设计参考 GKD 项目 shizuku/TaskStackListener.kt 与 shizuku/ActivityTaskManager.kt。
 * 仅支持 Android Q (10)+，与 GKD 一致。
 */
public class TaskStackHelper {

    private static final String TAG = "TaskStackHelper";

    /** "activity_task" 服务名（Android Q+ 引入，对应 ContextHidden.ACTIVITY_TASK_SERVICE） */
    private static final String ACTIVITY_TASK_SERVICE = "activity_task";

    // ── 单例 ──────────────────────────────────────────────────────────
    private static volatile TaskStackHelper sInstance;

    public static synchronized TaskStackHelper getInstance() {
        if (sInstance == null) {
            sInstance = new TaskStackHelper();
        }
        return sInstance;
    }

    // ── 状态 ──────────────────────────────────────────────────────────
    private volatile boolean mRunning = false;
    private IActivityTaskManager mAtm;
    private FixedTaskStackListener mListener;
    private Context mContext;

    /** 上一次回调的包名，用于去重 */
    private volatile String mLastPkg = "";

    // ── 回调接口 ──────────────────────────────────────────────────────
    public interface Callback {
        /**
         * 前台应用发生变化时回调（子线程，binder 线程池）。
         * @param packageName 新前台应用的包名
         */
        void onForegroundAppChanged(String packageName);
    }

    private Callback mCallback;

    // ── 公开 API ──────────────────────────────────────────────────────

    /**
     * 启动监听。
     * 要求：Shizuku 已授权，Android Q+。
     *
     * @param context  Context（推荐传 Service.this，用于 LogUtil）
     * @param callback 前台应用变化回调
     * @return true = 启动成功，false = 条件不满足
     */
    public synchronized boolean start(Context context, Callback callback) {
        if (mRunning) return true;

        // 仅支持 Android Q+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            LogUtil.log(context, "[TaskStack] 不支持 Android Q 以下，跳过");
            return false;
        }

        // 检查 Shizuku 授权
        if (!isShizukuGranted()) {
            LogUtil.log(context, "[TaskStack] Shizuku 未授权，跳过");
            return false;
        }

        mContext  = context;
        mCallback = callback;

        try {
            // 1. 通过 Shizuku 获取 IActivityTaskManager binder
            IBinder binder = SystemServiceHelper.getSystemService(ACTIVITY_TASK_SERVICE);
            if (binder == null) {
                LogUtil.log(context, "[TaskStack] getSystemService(activity_task) 返回 null");
                return false;
            }
            IBinder wrappedBinder = new ShizukuBinderWrapper(binder);
            mAtm = IActivityTaskManager.Stub.asInterface(wrappedBinder);

            // 2. 初始化时主动查询一次当前前台
            queryAndDispatchCurrent();

            // 3. 注册持续监听
            mListener = new FixedTaskStackListener();
            mAtm.registerTaskStackListener(mListener);

            mRunning = true;
            LogUtil.log(context, "[TaskStack] 启动成功，开始监听前台应用");
            return true;

        } catch (Throwable e) {
            Log.e(TAG, "start failed", e);
            LogUtil.log(context, "[TaskStack] 启动失败: " + e.getMessage());
            mAtm = null;
            mListener = null;
            return false;
        }
    }

    /**
     * 停止监听，注销 ITaskStackListener。
     */
    public synchronized void stop(Context context) {
        if (!mRunning) return;
        mRunning = false;

        if (mAtm != null && mListener != null) {
            try {
                mAtm.unregisterTaskStackListener(mListener);
                LogUtil.log(context, "[TaskStack] 已停止监听");
            } catch (Throwable e) {
                Log.e(TAG, "stop failed", e);
            }
        }
        mAtm = null;
        mListener = null;
        mCallback = null;
        mContext  = null;
    }

    public boolean isRunning() {
        return mRunning;
    }

    // ── 内部实现 ──────────────────────────────────────────────────────

    /** 立即查询当前前台任务并分发 */
    private void queryAndDispatchCurrent() {
        try {
            List<ActivityManager.RunningTaskInfo> tasks = mAtm.getTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                ActivityManager.RunningTaskInfo top = tasks.get(0);
                if (top.topActivity != null) {
                    dispatchForegroundChange(top.topActivity.getPackageName(), "初始查询");
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "queryAndDispatchCurrent failed", e);
        }
    }

    /** 分发前台变化（去重：相同包名连续多次不重复回调） */
    private void dispatchForegroundChange(String pkg, String source) {
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(mLastPkg)) return; // 同一个 app，去重

        mLastPkg = pkg;

        // 记日志
        if (mContext != null) {
            String appLabel = getAppLabel(pkg);
            LogUtil.log(mContext, "[TaskStack] 前台切换(" + source + "): "
                    + pkg + (appLabel != null ? "  (" + appLabel + ")" : ""));
        }

        // 回调给外部
        if (mCallback != null) {
            try {
                mCallback.onForegroundAppChanged(pkg);
            } catch (Throwable ignored) {}
        }
    }

    /** 尝试获取 App 显示名，失败返回 null */
    private String getAppLabel(String pkg) {
        if (mContext == null) return null;
        try {
            android.content.pm.ApplicationInfo ai =
                    mContext.getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = ai.loadLabel(mContext.getPackageManager());
            return label != null ? label.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isShizukuGranted() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ── ITaskStackListener 实现 ────────────────────────────────────────
    /**
     * GKD 同款：重写 onTransact 吞掉旧版/新版 binder 接口不匹配的异常，
     * 保证任意 Android 版本的 ITaskStackListener binder 调用都不 crash。
     */
    private class FixedTaskStackListener extends ITaskStackListener.Stub {

        // 防崩：吞掉 binder 接口版本不匹配抛出的任何异常
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable t) {
                Log.w(TAG, "onTransact swallowed", t);
                return true; // 告诉系统"已处理"，防止 crash
            }
        }

        // ── 任务栈变化（补充监听，应用→桌面不触发时的兜底）──
        @Override
        public void onTaskStackChanged() {
            try {
                IActivityTaskManager atm = mAtm;
                if (atm == null) return;
                List<ActivityManager.RunningTaskInfo> tasks = atm.getTasks(1);
                if (tasks == null || tasks.isEmpty()) return;
                ActivityManager.RunningTaskInfo top = tasks.get(0);
                if (top.topActivity != null) {
                    dispatchForegroundChange(top.topActivity.getPackageName(), "onTaskStackChanged");
                }
            } catch (Throwable t) {
                Log.w(TAG, "onTaskStackChanged", t);
            }
        }

        // ── Android 10+：切换到前台（最精确的回调）──
        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            try {
                if (taskInfo == null || taskInfo.topActivity == null) return;
                dispatchForegroundChange(taskInfo.topActivity.getPackageName(), "onTaskMovedToFront");
            } catch (Throwable t) {
                Log.w(TAG, "onTaskMovedToFront(taskInfo)", t);
            }
        }

        // ── Android 8~9（我们只支持Q+，这里留空防止 binder 崩溃）──
        @Override
        public void onTaskMovedToFront(int taskId) {
            // Q+ 不走此分支，保留空实现防止 binder 接口缺失报错
        }
    }
}
