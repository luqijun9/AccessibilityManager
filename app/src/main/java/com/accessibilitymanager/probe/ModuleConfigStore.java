package com.accessibilitymanager.probe;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块配置缓存、Settings.Secure 串行读写、包状态三态查询与电源状态诊断摘要。
 */
final class ModuleConfigStore {

    private static volatile Context sContext;
    private static volatile ClassLoader sClassLoader;

    private static volatile String sModuleEnabledCache;
    private static volatile String sManagedServicesCache;
    private static volatile String sFixModeCache;
    private static volatile String sFixDisabledCache;
    private static volatile String sIgnoreSystemCache;
    private static volatile String sDryRunCache;
    private static volatile String sDebugCache;
    private static volatile String sToastCache;
    private static volatile String sDebugPauseUnbindCache;

    private static String sLastObservedEnabled;
    private static int sLastAccessibilityEnabled = -1;
    // 启用列表读取为 null 时的原因：非 null 表示读取异常，null 表示设置项不存在
    private static volatile Throwable sLastEnabledReadError;
    private static SettingsWriter sSettingsWriter;

    private ModuleConfigStore() {
    }

    static Context getContext() {
        return sContext;
    }

    static void setContext(Context context) {
        sContext = context;
    }

    static Context getContextOrSystem() {
        if (sContext != null) {
            return sContext;
        }
        return getSystemContext();
    }

    static ClassLoader getClassLoader() {
        return sClassLoader;
    }

    static void setClassLoader(ClassLoader classLoader) {
        sClassLoader = classLoader;
    }

    static Context getSystemContext() {
        if (sContext != null) {
            return sContext;
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThreadMethod = atClass.getDeclaredMethod("currentActivityThread");
            currentActivityThreadMethod.setAccessible(true);
            Object activityThread = currentActivityThreadMethod.invoke(null);
            if (activityThread != null) {
                Method getSystemContextMethod = atClass.getDeclaredMethod("getSystemContext");
                getSystemContextMethod.setAccessible(true);
                Context ctx = (Context) getSystemContextMethod.invoke(activityThread);
                if (ctx != null) {
                    sContext = ctx;
                    return ctx;
                }
            }
        } catch (Throwable t) {
            EventLogBuffer.log("getSystemContext error: " + t);
        }
        return null;
    }

    static void registerObservers(ContentResolver resolver, ContentObserver observer) {
        resolver.registerContentObserver(Settings.Global.getUriFor(AutoRepairEntry.SETTING_BOOT_COUNT),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.KEY_ENABLE),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.KEY_SERVICES),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.KEY_SCAN),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.KEY_DRY_RUN),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.KEY_DEBUG),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.KEY_TOAST),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.KEY_DEBUG_KILL),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.KEY_FIX_MODE),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.KEY_FIX_DISABLED),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.KEY_IGNORE_SYSTEM),
                false, observer);
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(AutoRepairEntry.KEY_DEBUG_PAUSE_UNBIND),
                false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(AutoRepairEntry.SETTING_ENABLED),
                false, observer);
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(AutoRepairEntry.SETTING_ACCESSIBILITY_ENABLED),
                false, observer);
    }

    /** 监听注册成功后预热内存缓存，保证后续热路径和 AMS 持锁钩子无需再做同步 Settings IPC。 */
    static void warmupCaches() {
        if (sDebugCache == null) {
            sDebugCache = readSecure(AutoRepairEntry.KEY_DEBUG);
        }
        if (sManagedServicesCache == null) {
            String services = readSecure(AutoRepairEntry.KEY_SERVICES);
            if (services != null) {
                sManagedServicesCache = services;
            }
        }
        if (sModuleEnabledCache == null) {
            sModuleEnabledCache = readSecure(AutoRepairEntry.KEY_ENABLE);
        }
        if (sDryRunCache == null) {
            sDryRunCache = readSecure(AutoRepairEntry.KEY_DRY_RUN);
        }
        if (sToastCache == null) {
            sToastCache = readSecure(AutoRepairEntry.KEY_TOAST);
        }
        if (sFixModeCache == null) {
            sFixModeCache = readSecure(AutoRepairEntry.KEY_FIX_MODE);
        }
        if (sFixDisabledCache == null) {
            sFixDisabledCache = readSecure(AutoRepairEntry.KEY_FIX_DISABLED);
        }
        if (sIgnoreSystemCache == null) {
            sIgnoreSystemCache = readSecure(AutoRepairEntry.KEY_IGNORE_SYSTEM);
        }
        if (sDebugPauseUnbindCache == null) {
            sDebugPauseUnbindCache = readSecure(AutoRepairEntry.KEY_DEBUG_PAUSE_UNBIND);
        }
    }

    /**
     * 纯缓存调试开关读取：禁止触发 Settings IPC。
     *
     * <p>1. 解决场景：bindLocked/unbindLocked 钩子运行在 system_server 主线程且持 AMS 锁，
     *    以及 EventLogBuffer.log 在异常补偿日志期间绝不能再次读 Settings。
     * <p>2. 兜底策略：若尚未预热缓存，保守返回 false。
     */
    static boolean isDebugCacheEnabled() {
        return "1".equals(sDebugCache);
    }

    /**
     * 纯缓存锁定列表判定（供 bindLocked/unbindLocked 等 AMS 持锁钩子线程使用）。
     *
     * <p>绝不在持锁钩子中发起 Settings IPC；缓存未初始化时保守返回 false。
     */
    static boolean isManagedCached(ComponentName target) {
        if (target == null) {
            return false;
        }
        String cached = sManagedServicesCache;
        if (cached == null || cached.isEmpty()) {
            return false;
        }
        ComponentKey targetKey = ComponentKey.of(target.getPackageName(), target.getClassName());
        return targetKey != null && EnabledListDelta.contains(cached, targetKey);
    }

    static boolean isDebugPauseUnbind() {
        if (sDebugPauseUnbindCache != null) {
            return "1".equals(sDebugPauseUnbindCache);
        }
        String value = readSecure(AutoRepairEntry.KEY_DEBUG_PAUSE_UNBIND);
        if (value != null) {
            sDebugPauseUnbindCache = value;
        }
        return "1".equals(value);
    }

    static void refreshDebugPauseUnbindCache() {
        sDebugPauseUnbindCache = readSecure(AutoRepairEntry.KEY_DEBUG_PAUSE_UNBIND);
    }

    static void refreshDebugCache() {
        sDebugCache = readSecure(AutoRepairEntry.KEY_DEBUG);
    }

    static void refreshToastCache() {
        sToastCache = readSecure(AutoRepairEntry.KEY_TOAST);
    }

    static void refreshFixModeCache() {
        sFixModeCache = readSecure(AutoRepairEntry.KEY_FIX_MODE);
    }

    static void refreshFixDisabledCache() {
        sFixDisabledCache = readSecure(AutoRepairEntry.KEY_FIX_DISABLED);
    }

    static void refreshIgnoreSystemCache() {
        sIgnoreSystemCache = readSecure(AutoRepairEntry.KEY_IGNORE_SYSTEM);
    }

    static String getModuleEnabledCache() {
        return sModuleEnabledCache;
    }

    static void setModuleEnabledCache(String value) {
        sModuleEnabledCache = value;
    }

    static String getManagedServicesCache() {
        return sManagedServicesCache;
    }

    static void setManagedServicesCache(String value) {
        sManagedServicesCache = value;
    }

    static String getFixModeCache() {
        return sFixModeCache;
    }

    static void setFixModeCache(String value) {
        sFixModeCache = value;
    }

    static String getFixDisabledCache() {
        return sFixDisabledCache;
    }

    static void setFixDisabledCache(String value) {
        sFixDisabledCache = value;
    }

    static String getIgnoreSystemCache() {
        return sIgnoreSystemCache;
    }

    static void setIgnoreSystemCache(String value) {
        sIgnoreSystemCache = value;
    }

    static String getDryRunCache() {
        return sDryRunCache;
    }

    static void setDryRunCache(String value) {
        sDryRunCache = value;
    }

    static void setDebugCache(String value) {
        sDebugCache = value;
    }

    static void setToastCache(String value) {
        sToastCache = value;
    }

    static String getLastObservedEnabled() {
        return sLastObservedEnabled;
    }

    static void setLastObservedEnabled(String value) {
        sLastObservedEnabled = value;
    }

    static int getLastAccessibilityEnabled() {
        return sLastAccessibilityEnabled;
    }

    static void setLastAccessibilityEnabled(int value) {
        sLastAccessibilityEnabled = value;
    }

    static Throwable getLastEnabledReadError() {
        return sLastEnabledReadError;
    }

    /** Settings.Secure 启用列表的唯一写入器：短临界区、按 delta 读写。 */
    static SettingsWriter settingsWriter() {
        if (sSettingsWriter == null) {
            sSettingsWriter = new SettingsWriter(new SettingsWriter.Store() {
                @Override
                public String read() {
                    String value = readEnabled();
                    if (value == null) {
                        if (sLastEnabledReadError != null) {
                            throw new IllegalStateException("enabled read failed",
                                    sLastEnabledReadError);
                        }
                        return "";
                    }
                    return value;
                }

                @Override
                public boolean write(String value) {
                    return writeEnabled(value);
                }
            });
        }
        return sSettingsWriter;
    }

    /** 读取启用列表：返回 null 时原因见 sLastEnabledReadError，避免把“设置项不存在”误判成读取失败。 */
    static String readEnabled() {
        Context context = sContext;
        if (context == null) {
            sLastEnabledReadError = null;
            return null;
        }
        try {
            sLastEnabledReadError = null;
            return Settings.Secure.getString(context.getContentResolver(),
                    AutoRepairEntry.SETTING_ENABLED);
        } catch (Throwable t) {
            sLastEnabledReadError = t;
            EventLogBuffer.log("read enabled failed: " + t);
            return null;
        }
    }

    static String enabledReadSkipReason() {
        return sLastEnabledReadError != null
                ? "启用列表读取异常: " + sLastEnabledReadError
                : "启用列表设置项不存在";
    }

    static boolean writeEnabled(String value) {
        Context context = sContext;
        if (context == null) {
            return false;
        }
        try {
            boolean ok = Settings.Secure.putString(
                    context.getContentResolver(), AutoRepairEntry.SETTING_ENABLED, value);
            if (ok) {
                sLastObservedEnabled = value;
            }
            return ok;
        } catch (Throwable t) {
            EventLogBuffer.log("write enabled failed: " + t);
            return false;
        }
    }

    /** 开启单个服务时确保无障碍总开关为开启状态，否则只写启用列表不会真正绑定服务。 */
    static void ensureAccessibilitySwitchOn() {
        Context context = sContext;
        if (context == null) {
            return;
        }
        try {
            int enabled = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled == 1) {
                return;
            }
            if (!Settings.Secure.putInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1)) {
                EventLogBuffer.log("enable accessibility switch failed");
                return;
            }
            EventLogBuffer.event("保活", "无障碍总开关未开启，已代为开启");
        } catch (Throwable t) {
            EventLogBuffer.log("enable accessibility switch error: " + t);
        }
    }

    static String readConfig(String key) {
        if (AutoRepairEntry.KEY_ENABLE.equals(key)) {
            if (sModuleEnabledCache != null) return sModuleEnabledCache;
            String secureVal = readSecure(AutoRepairEntry.KEY_ENABLE);
            if (secureVal != null) {
                sModuleEnabledCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (AutoRepairEntry.KEY_SERVICES.equals(key)) {
            if (sManagedServicesCache != null) {
                return sManagedServicesCache;
            }
            String secureVal = readSecure(AutoRepairEntry.KEY_SERVICES);
            if (secureVal != null) {
                sManagedServicesCache = secureVal;
                return secureVal;
            }
            return "";
        }
        if (AutoRepairEntry.KEY_FIX_MODE.equals(key)) {
            if (sFixModeCache != null) return sFixModeCache;
            String secureVal = readSecure(AutoRepairEntry.KEY_FIX_MODE);
            if (secureVal != null) {
                sFixModeCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (AutoRepairEntry.KEY_FIX_DISABLED.equals(key)) {
            if (sFixDisabledCache != null) return sFixDisabledCache;
            String secureVal = readSecure(AutoRepairEntry.KEY_FIX_DISABLED);
            if (secureVal != null) {
                sFixDisabledCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (AutoRepairEntry.KEY_IGNORE_SYSTEM.equals(key)) {
            if (sIgnoreSystemCache != null) return sIgnoreSystemCache;
            String secureVal = readSecure(AutoRepairEntry.KEY_IGNORE_SYSTEM);
            if (secureVal != null) {
                sIgnoreSystemCache = secureVal;
                return secureVal;
            }
            return "1";
        }
        if (AutoRepairEntry.KEY_DRY_RUN.equals(key)) {
            if (sDryRunCache != null) return sDryRunCache;
            String secureVal = readSecure(AutoRepairEntry.KEY_DRY_RUN);
            if (secureVal != null) {
                sDryRunCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (AutoRepairEntry.KEY_DEBUG.equals(key)) {
            if (sDebugCache != null) return sDebugCache;
            String secureVal = readSecure(AutoRepairEntry.KEY_DEBUG);
            if (secureVal != null) {
                sDebugCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (AutoRepairEntry.KEY_TOAST.equals(key)) {
            if (sToastCache != null) return sToastCache;
            String secureVal = readSecure(AutoRepairEntry.KEY_TOAST);
            if (secureVal != null) {
                sToastCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (AutoRepairEntry.KEY_DEBUG_PAUSE_UNBIND.equals(key)) {
            if (sDebugPauseUnbindCache != null) return sDebugPauseUnbindCache;
            String secureVal = readSecure(AutoRepairEntry.KEY_DEBUG_PAUSE_UNBIND);
            if (secureVal != null) {
                sDebugPauseUnbindCache = secureVal;
                return secureVal;
            }
            return null;
        }
        return readSecure(key);
    }

    static String readSecure(String key) {
        Context context = sContext;
        if (context == null) {
            return null;
        }
        try {
            return Settings.Secure.getString(context.getContentResolver(), key);
        } catch (Throwable t) {
            EventLogBuffer.log("read " + key + " failed: " + t);
            return null;
        }
    }

    static boolean isIgnoreSystemEnabled() {
        return sIgnoreSystemCache != null
                ? !"0".equals(sIgnoreSystemCache)
                : !"0".equals(readConfig(AutoRepairEntry.KEY_IGNORE_SYSTEM));
    }

    static boolean isSystemPackage(String pkg) {
        if (RepairPolicy.isWellKnownSystemPackage(pkg)) {
            return true;
        }
        if (sContext != null) {
            try {
                ApplicationInfo ai = sContext.getPackageManager().getApplicationInfo(pkg, 0);
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    static boolean shouldIgnoreSystemApp(String pkg) {
        return isIgnoreSystemEnabled() && isSystemPackage(pkg);
    }

    static boolean isManaged(ComponentName target) {
        if (target == null) {
            return false;
        }
        String list = readConfig(AutoRepairEntry.KEY_SERVICES);
        if (list == null || list.isEmpty()) {
            return false;
        }
        ComponentKey targetKey = ComponentKey.of(target.getPackageName(), target.getClassName());
        return targetKey != null && EnabledListDelta.contains(list, targetKey);
    }

    static boolean isModuleEnabled() {
        return "1".equals(readConfig(AutoRepairEntry.KEY_ENABLE));
    }

    static boolean isDryRun() {
        return "1".equals(readConfig(AutoRepairEntry.KEY_DRY_RUN));
    }

    static boolean isToastEnabled() {
        String value = readConfig(AutoRepairEntry.KEY_TOAST);
        return value == null || "1".equals(value);
    }

    /**
     * 读取包三态：未安装 / 冻结禁用 / 正常。
     *
     * <p>边界：sContext 在开机早期未就绪或系统偶发异常时按正常处理，避免误判。
     */
    static int getPackageState(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return RepairPolicy.PKG_STATE_UNINSTALLED;
        }
        Context ctx = sContext != null ? sContext : getSystemContext();
        if (ctx == null) {
            return RepairPolicy.PKG_STATE_ENABLED;
        }
        PackageManager pm = ctx.getPackageManager();
        try {
            pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return RepairPolicy.PKG_STATE_UNINSTALLED;
        } catch (Throwable t) {
            return RepairPolicy.PKG_STATE_ENABLED;
        }
        try {
            return RepairPolicy.classifyPackageState(pm.getApplicationEnabledSetting(pkg));
        } catch (Throwable t) {
            return RepairPolicy.PKG_STATE_ENABLED;
        }
    }

    static boolean isPackageUsable(String pkg) {
        return getPackageState(pkg) == RepairPolicy.PKG_STATE_ENABLED;
    }

    static String getAppLabel(ComponentName component) {
        if (component == null) {
            return "";
        }
        String pkg = component.getPackageName();
        Context ctx = sContext;
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                CharSequence label = info.loadLabel(pm);
                if (label != null && label.length() > 0) {
                    return label.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return pkg;
    }

    static String joinLabels(Set<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (String label : labels) {
            if (label == null || label.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(label);
        }
        return sb.toString();
    }

    static void showModuleToast(String text) {
        if (text == null || text.isEmpty() || !isToastEnabled()) {
            return;
        }
        final Context ctx = sContext != null ? sContext : getSystemContext();
        if (ctx == null) {
            return;
        }
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    EventLogBuffer.log("showModuleToast failed: " + t);
                }
            });
        } catch (Throwable t) {
            EventLogBuffer.log("post showModuleToast failed: " + t);
        }
    }

    /**
     * 采集标准省电、Doze 与亮屏状态摘要（仅通过 PowerManager 读取，不再轮询 OEM Settings 键）。
     */
    static String getPowerStateSummary() {
        Context ctx = sContext;
        if (ctx == null) {
            return "powerSave=unknown";
        }
        StringBuilder sb = new StringBuilder();
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    sb.append("powerSave=").append(pm.isPowerSaveMode());
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append("idle=").append(pm.isDeviceIdleMode());
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append("interactive=").append(pm.isInteractive());
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "powerSave=unknown" : sb.toString();
    }

    static String getPackageProcessSummary(String pkg) {
        Context ctx = sContext;
        if (ctx == null || pkg == null || pkg.isEmpty()) {
            return "runningPids=[]";
        }
        final int uid;
        try {
            uid = ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            return "runningPids=pkg_not_found";
        }
        StringBuilder sb = new StringBuilder("runningPids=[");
        Set<Integer> seen = new LinkedHashSet<>();
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo info : procs) {
                        if (info != null && info.uid == uid) {
                            if (!seen.isEmpty()) sb.append(',');
                            seen.add(info.pid);
                            sb.append(info.pid).append(":imp").append(info.importance);
                        }
                    }
                }
                List<ActivityManager.RunningServiceInfo> svcs = am.getRunningServices(Integer.MAX_VALUE);
                if (svcs != null) {
                    for (ActivityManager.RunningServiceInfo info : svcs) {
                        if (info != null && info.uid == uid && info.pid > 0 && !seen.contains(info.pid)) {
                            if (!seen.isEmpty()) sb.append(',');
                            seen.add(info.pid);
                            sb.append(info.pid).append(":svc");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sb.append(']');
        return sb.toString();
    }

    static String diffComponents(String baseSetting, String compareSetting) {
        if (compareSetting == null || compareSetting.isEmpty()) {
            return "";
        }
        Set<ComponentKey> baseSet = new LinkedHashSet<>(EnabledListDelta.parse(baseSetting));
        StringBuilder diff = new StringBuilder();
        for (ComponentKey item : EnabledListDelta.parse(compareSetting)) {
            if (!baseSet.contains(item)) {
                if (diff.length() > 0) {
                    diff.append(',');
                }
                diff.append(item.flattenShort());
            }
        }
        return diff.toString();
    }

    static void saveHotReloadState(java.util.Map<String, Object> state) {
        if (sContext != null) {
            state.put(AutoRepairEntry.STATE_CONTEXT, sContext);
        }
        if (sClassLoader != null) {
            state.put(AutoRepairEntry.STATE_CLASS_LOADER, sClassLoader);
        }
    }

    static void restoreHotReloadState(java.util.Map<?, ?> state) {
        Object ctx = state.get(AutoRepairEntry.STATE_CONTEXT);
        if (ctx instanceof Context) {
            sContext = (Context) ctx;
        }
        Object loader = state.get(AutoRepairEntry.STATE_CLASS_LOADER);
        if (loader instanceof ClassLoader) {
            sClassLoader = (ClassLoader) loader;
        }
    }

    static void clearForHotReload() {
        sLastObservedEnabled = null;
        sLastAccessibilityEnabled = -1;
        sToastCache = null;
        sDebugPauseUnbindCache = null;
        sSettingsWriter = null;
    }

    static void resetForTesting() {
        clearForHotReload();
        sContext = null;
        sClassLoader = null;
        sModuleEnabledCache = null;
        sManagedServicesCache = null;
        sFixModeCache = null;
        sFixDisabledCache = null;
        sIgnoreSystemCache = null;
        sDryRunCache = null;
        sDebugCache = null;
        sLastEnabledReadError = null;
    }
}
