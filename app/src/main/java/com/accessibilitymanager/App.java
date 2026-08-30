package com.accessibilitymanager;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.PowerManager;
import com.google.android.material.color.DynamicColors;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        registerActivityLifecycleCallbacks(new ForegroundLifecycleCallback());

        // 检查上次管理器进程被杀/退出的诊断信息
        ProcessExitMonitor.checkProcessExit(this);

        // 全局未捕获异常日志记录
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                LogUtil.log(App.this, "[异常崩溃] 线程: " + t.getName() + ", 错误: " + e.getMessage());
            } catch (Exception ignored) {}
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(t, e);
            }
        });
    }

    private static class ForegroundLifecycleCallback implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            MainActivity.sIsForeground = true;
        }

        @Override
        public void onActivityPaused(Activity activity) {
            PowerManager pm = (PowerManager) activity.getSystemService(POWER_SERVICE);
            boolean interactive = pm != null && pm.isInteractive();
            // 屏幕关闭（锁屏中）不属于真正的后台，保持 sIsForeground = true
            if (pm == null || interactive) {
                MainActivity.sIsForeground = false;
            }
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

        @Override
        public void onActivityStarted(Activity activity) {}

        @Override
        public void onActivityStopped(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

        @Override
        public void onActivityDestroyed(Activity activity) {}
    }
}