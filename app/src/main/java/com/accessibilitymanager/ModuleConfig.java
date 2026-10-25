package com.accessibilitymanager;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.LinkedHashSet;
import java.util.Set;

import io.github.libxposed.service.XposedService;

/** LSPosed 自动恢复模块的配置通道（优先 Remote Preferences，回退 Settings.Secure） */
public final class ModuleConfig {

    private static final String REMOTE_GROUP = "a11y_config";
    private static volatile XposedService sXposedService;

    public static final String KEY_ENABLE = "a11y_module_enable";
    public static final String KEY_SERVICES = "a11y_module_services";
    public static final String KEY_DRY_RUN = "a11y_module_dry_run";
    public static final String KEY_SCAN = "a11y_module_scan";
    public static final String KEY_LOADED = "a11y_module_loaded";
    public static final String KEY_EVENTS = "a11y_module_events";
    public static final String KEY_DEBUG = "a11y_module_debug";
    public static final String KEY_DEBUG_KILL = "a11y_module_debug_kill";
    // 服务开关命令队列：App 递增序号写 payload，模块按序处理并写回执
    public static final String KEY_CMD_SEQ = "a11y_cmd_seq";
    public static final String KEY_CMD_PREFIX = "a11y_cmd_";
    public static final String KEY_CMD_ACK = "a11y_module_cmd_ack";

    private static final Object sLogLock = new Object();
    private static final Object sCmdLock = new Object();
    private static ContentObserver sLogObserver;
    private static Context sLogContext;

    private ModuleConfig() {
    }

    public static void onXposedServiceBind(Context context, XposedService service) {
        sXposedService = service;
        boolean remote = isRemoteConfigAvailable();
        LogUtil.log(context, "[模块] XposedService 已连接: "
                + (service == null ? "null" : service.getFrameworkName()
                        + " " + service.getFrameworkVersion())
                + ", RemotePreferences=" + remote);
        if (remote && context != null) {
            try {
                SharedPreferences sp = context.getSharedPreferences("data", 0);
                if (sp.getBoolean("lsposed_mode", false)) {
                    setEnabled(context, true);
                    setDryRun(context, false);
                    syncManagedServices(context,
                            sp.getString("daemon", ""),
                            sp.getString("whitelist_services", ""),
                            sp.getBoolean("whitelist_global_enable", false));
                } else if (isRemoteModuleEnabled(context)) {
                    // 已切回普通模式：确保模块侧停止动作
                    setEnabled(context, false);
                }
            } catch (Throwable t) {
                LogUtil.log(context, "[模块] 绑定后自动同步失败: " + t);
            }
        }
    }

    public static void onXposedServiceDied(XposedService service) {
        if (sXposedService == service) {
            sXposedService = null;
        }
    }

    public static boolean isRemoteConfigAvailable() {
        XposedService service = sXposedService;
        if (service == null) {
            return false;
        }
        try {
            return (service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 模块会在本次开机加载后写 bootCount|version */
    public static boolean isModuleLoaded(Context context) {

        try {
            String value = Settings.Secure.getString(
                    context.getContentResolver(), KEY_LOADED);
            if (value == null || value.isEmpty()) {
                return false;
            }
            int separator = value.indexOf('|');
            String bootValue = separator > 0 ? value.substring(0, separator) : value;
            int moduleBoot = Integer.parseInt(bootValue);
            int currentBoot = Settings.Global.getInt(
                    context.getContentResolver(), "boot_count", -1);
            // 部分 ROM 没有 boot_count，退化为“有心跳即加载”
            return currentBoot < 0 || moduleBoot == currentBoot;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean setEnabled(Context context, boolean enabled) {
        return put(context, KEY_ENABLE, enabled ? "1" : "0");
    }

    public static boolean isRemoteModuleEnabled(Context context) {
        return "1".equals(get(context, KEY_ENABLE));
    }

    public static boolean setDryRun(Context context, boolean dryRun) {
        return put(context, KEY_DRY_RUN, dryRun ? "1" : "0");
    }

    public static boolean triggerScan(Context context) {
        return put(context, KEY_SCAN, String.valueOf(System.currentTimeMillis()));
    }

    public static boolean setDebugEnabled(Context context, boolean enabled) {
        return put(context, KEY_DEBUG, enabled ? "1" : "0");
    }

    /** 让模块代为开启/关闭无障碍服务（LSPosed 模式无需 WRITE_SECURE_SETTINGS） */
    public static boolean sendServiceCommand(Context context, boolean enable, String component) {
        if (context == null || component == null || component.isEmpty()
                || !isRemoteConfigAvailable()) {
            return false;
        }
        synchronized (sCmdLock) {
            try {
                SharedPreferences prefs = sXposedService.getRemotePreferences(REMOTE_GROUP);
                long seq = 0;
                try {
                    seq = Long.parseLong(prefs.getString(KEY_CMD_SEQ, "0"));
                } catch (Throwable ignored) {
                }
                seq++;
                String payload = (enable ? "enable:" : "disable:") + component;
                // payload 与 seq 放在同一次 commit，避免应用进程中途退出留下孤立的 payload
                return prefs.edit()
                        .putString(KEY_CMD_PREFIX + seq, payload)
                        .putString(KEY_CMD_SEQ, String.valueOf(seq))
                        .commit();
            } catch (Throwable t) {
                LogUtil.log(context, "[模块] 发送服务命令失败: " + t);
                return false;
            }
        }
    }

    /** 让模块杀掉目标组件所属应用的进程，模拟一次真实崩溃 */
    public static boolean triggerDebugKill(Context context, String component) {
        if (component == null || component.isEmpty()) {
            return false;
        }
        return put(context, KEY_DEBUG_KILL,
                component + "#" + System.currentTimeMillis());
    }

    public static void syncManagedServices(Context context, String daemon,
            String whitelist, boolean globalWhitelist) {
        String desired = buildManaged(daemon, whitelist, globalWhitelist);
        if (put(context, KEY_SERVICES, desired)) {
            LogUtil.log(context, "[模块] 锁定服务已同步: " + desired);
        } else {
            LogUtil.log(context, "[模块] 锁定服务写入失败（模块未连接且未授予 WRITE_SECURE_SETTINGS）");
        }
    }

    public static String getManagedServices(Context context) {
        String value = get(context, KEY_SERVICES);
        return value == null ? "" : value;
    }

    public static boolean isServiceManaged(Context context, String component) {
        ComponentName target = ComponentName.unflattenFromString(component);
        if (target == null) {
            return false;
        }
        String list = getManagedServices(context);
        if (list.isEmpty()) {
            return false;
        }
        for (String raw : list.split(":")) {
            ComponentName item = ComponentName.unflattenFromString(raw);
            if (target.equals(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 监听模块的事件缓冲，把配置/检测/修复/失败事件写进管理器日志。
     * 只注册一次；模块进程被杀期间的事件会留在缓冲里，下次启动补读。
     */
    public static void startLogSync(Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        sLogContext = app;
        if (sLogObserver != null) {
            drainLogs(app);
            return;
        }
        try {
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    Context ctx = sLogContext;
                    if (ctx != null) {
                        drainLogs(ctx);
                    }
                }
            };
            // 观察父 URI 并接收后代变化：部分 ROM 的 SettingsProvider 只通知父 URI
            app.getContentResolver().registerContentObserver(
                    Settings.Secure.CONTENT_URI, true, observer);
            sLogObserver = observer;
            LogUtil.log(app, "[模块] 日志同步已启动");
        } catch (Throwable t) {
            LogUtil.log(app, "[模块] 日志同步注册失败: " + t);
        }
        drainLogs(app);
    }

    /** 把模块缓冲中尚未写入管理器日志的事件补上 */
    public static void drainLogs(Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        synchronized (sLogLock) {
            try {
                String raw = Settings.Secure.getString(
                        app.getContentResolver(), KEY_EVENTS);
                if (raw == null || raw.isEmpty()) {
                    return;
                }
                SharedPreferences prefs = app.getSharedPreferences("data", 0);
                String lastSession = prefs.getString("module_log_session", "");
                long lastSeq = prefs.getLong("module_log_seq", 0L);
                String session = lastSession;
                long maxSeq = lastSeq;

                for (String line : raw.split("\n")) {
                    String[] parts = line.split("\\|", 4);
                    if (parts.length < 4) {
                        continue;
                    }
                    long seq;
                    long time;
                    try {
                        seq = Long.parseLong(parts[1]);
                        time = Long.parseLong(parts[2]);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (!parts[0].equals(session)) {
                        session = parts[0];
                        maxSeq = 0L;
                    }
                    if (seq <= maxSeq) {
                        continue;
                    }
                    maxSeq = seq;
                    String message = parts[3];
                    // 使用事件原始时间，避免“日志时间 + 模块时间”重复
                    LogUtil.logAt(app, time, "[模块] " + message);
                }

                if (!session.equals(lastSession) || maxSeq != lastSeq) {
                    prefs.edit()
                            .putString("module_log_session", session)
                            .putLong("module_log_seq", maxSeq)
                            .apply();
                }
            } catch (Throwable t) {
                LogUtil.log(app, "[模块] 日志解析失败: " + t);
            }
        }
    }

    private static String buildManaged(String daemon, String whitelist,
            boolean globalWhitelist) {
        Set<String> services = new LinkedHashSet<>();
        addAll(services, daemon);
        if (globalWhitelist) {
            addAll(services, whitelist);
        }
        StringBuilder result = new StringBuilder();
        for (String service : services) {
            if (result.length() > 0) {
                result.append(':');
            }
            result.append(service);
        }
        return result.toString();
    }

    private static void addAll(Set<String> services, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        for (String service : value.split(":")) {
            if (!service.isEmpty()) {
                services.add(service);
            }
        }
    }

    private static String get(Context context, String key) {
        if (isRemoteConfigAvailable()) {
            try {
                SharedPreferences prefs = sXposedService.getRemotePreferences(REMOTE_GROUP);
                return prefs.getString(key, null);
            } catch (Throwable t) {
                LogUtil.log(context, "[模块] 读 RemotePreferences 失败: " + t);
                return null;
            }
        }
        try {
            return Settings.Secure.getString(context.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean put(Context context, String key, String value) {
        if (isRemoteConfigAvailable()) {
            // Remote Preferences 可用时以它为准，避免与 Settings.Secure 出现新旧不一致
            try {
                SharedPreferences prefs = sXposedService.getRemotePreferences(REMOTE_GROUP);
                boolean ok = prefs.edit().putString(key, value).commit();
                if (ok && context != null) {
                    try {
                        Settings.Secure.putString(context.getContentResolver(), key, value);
                    } catch (Throwable ignored) {
                    }
                }
                return ok;
            } catch (Throwable t) {
                LogUtil.log(context, "[模块] RemotePreferences 写入异常: " + t);
                return false;
            }
        }
        try {
            return Settings.Secure.putString(context.getContentResolver(), key, value);
        } catch (Throwable t) {
            LogUtil.log(context, "[模块] 写入 " + key + " 失败: " + t);
            return false;
        }
    }
}
