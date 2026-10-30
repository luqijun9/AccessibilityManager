package com.accessibilitymanager;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

public class NotificationMonitorService extends NotificationListenerService {

    public static final String KEY_NOTIFY_CRASH_CHECK = "notify_crash_check";
    public static final String KEY_NOTIFY_WATCH_PACKAGES = "notify_watch_packages";
    private static final long DEBOUNCE_DELAY_MS = 1500L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> mPendingPackageChecks = new HashMap<>();

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        final String pkgName = sbn.getPackageName();
        if (pkgName == null || pkgName.isEmpty()) {
            return;
        }

        SharedPreferences sp = getSharedPreferences("data", Context.MODE_PRIVATE);
        boolean lsposedMode = sp.getBoolean("lsposed_mode", false);
        boolean crashFixEnabled = sp.getBoolean("crashfix", false);
        boolean notifyCrashCheckEnabled = sp.getBoolean(KEY_NOTIFY_CRASH_CHECK, false);
        String watchedPackages = sp.getString(KEY_NOTIFY_WATCH_PACKAGES, "");

        if (!shouldTriggerCrashCheck(lsposedMode, crashFixEnabled, notifyCrashCheckEnabled, watchedPackages, pkgName)) {
            return;
        }

        scheduleDebouncedCheck(pkgName);
    }

    private void scheduleDebouncedCheck(final String pkgName) {
        Runnable existing = mPendingPackageChecks.remove(pkgName);
        if (existing != null) {
            mHandler.removeCallbacks(existing);
        }
        final Context appContext = getApplicationContext();
        Runnable task = () -> {
            mPendingPackageChecks.remove(pkgName);
            daemonService.notifyNotificationRemoved(appContext, pkgName);
        };
        mPendingPackageChecks.put(pkgName, task);
        mHandler.postDelayed(task, DEBOUNCE_DELAY_MS);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        for (Runnable r : mPendingPackageChecks.values()) {
            mHandler.removeCallbacks(r);
        }
        mPendingPackageChecks.clear();
    }

    /**
     * 判断通知被清除时是否应触发崩溃检测
     */
    public static boolean shouldTriggerCrashCheck(
            boolean lsposedMode,
            boolean crashFixEnabled,
            boolean notifyCrashCheckEnabled,
            String watchedPackagesStr,
            String removedPkg) {
        if (lsposedMode || !crashFixEnabled || !notifyCrashCheckEnabled) {
            return false;
        }
        if (removedPkg == null || removedPkg.isEmpty()) {
            return false;
        }
        return isPackageWatched(watchedPackagesStr, removedPkg);
    }

    /**
     * 判断主界面顶部栏是否需要显示“通知监听失效ⓘ”警告
     */
    public static boolean isNotifyMonitorWarningNeeded(
            boolean lsposedMode,
            boolean crashFixEnabled,
            boolean notifyCrashCheckEnabled,
            boolean listenerPermissionGranted) {
        return !lsposedMode && crashFixEnabled && notifyCrashCheckEnabled && !listenerPermissionGranted;
    }

    /**
     * 检查目标包名是否在以冒号分隔的监听包名列表中
     */
    public static boolean isPackageWatched(String watchedPackagesStr, String targetPkg) {
        if (watchedPackagesStr == null || watchedPackagesStr.isEmpty()
                || targetPkg == null || targetPkg.isEmpty()) {
            return false;
        }
        String[] pkgs = watchedPackagesStr.split(":");
        for (String pkg : pkgs) {
            if (targetPkg.equals(pkg.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 统计当前已勾选监听的应用数量
     */
    public static int countWatchedPackages(String watchedPackagesStr) {
        if (watchedPackagesStr == null || watchedPackagesStr.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String pkg : watchedPackagesStr.split(":")) {
            if (!pkg.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断系统 enabled_notification_listeners 字符串中是否包含指定的通知监听服务组件
     */
    public static boolean isComponentInEnabledListeners(
            String enabledListeners, String packageName, String serviceClassName) {
        if (enabledListeners == null || enabledListeners.isEmpty()
                || packageName == null || packageName.isEmpty()
                || serviceClassName == null || serviceClassName.isEmpty()) {
            return false;
        }
        String fullFlat = packageName + "/" + serviceClassName;
        String shortFlat = serviceClassName.startsWith(packageName + ".")
                ? packageName + "/" + serviceClassName.substring(packageName.length())
                : null;

        for (String entry : enabledListeners.split(":")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equals(fullFlat) || (shortFlat != null && trimmed.equals(shortFlat))) {
                return true;
            }
            if (trimmed.startsWith(packageName + "/")) {
                String cls = trimmed.substring(packageName.length() + 1);
                if (cls.startsWith(".")) {
                    cls = packageName + cls;
                }
                if (serviceClassName.equals(cls)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查本应用的通知使用权是否已开启
     */
    public static boolean isNotificationListenerEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            String enabledListeners = Settings.Secure.getString(
                    context.getContentResolver(), "enabled_notification_listeners");
            return isComponentInEnabledListeners(
                    enabledListeners,
                    context.getPackageName(),
                    NotificationMonitorService.class.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }
}

