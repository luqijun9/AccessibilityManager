package com.accessibilitymanager;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** LSPosed 自动恢复模块的配置通道（Settings.Secure） */
public final class ModuleConfig {

    public static final String KEY_ENABLE = "a11y_module_enable";
    public static final String KEY_SERVICES = "a11y_module_services";
    public static final String KEY_DRY_RUN = "a11y_module_dry_run";
    public static final String KEY_SCAN = "a11y_module_scan";
    public static final String KEY_LOADED = "a11y_module_loaded";
    public static final String KEY_EVENTS = "a11y_module_events";
    public static final String KEY_DEBUG = "a11y_module_debug";
    public static final String KEY_DEBUG_KILL = "a11y_module_debug_kill";

    private static final Object sLogLock = new Object();
    private static final SimpleDateFormat EVENT_TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private static ContentObserver sLogObserver;
    private static Context sLogContext;

    private ModuleConfig() {
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

    public static boolean setDryRun(Context context, boolean dryRun) {
        return put(context, KEY_DRY_RUN, dryRun ? "1" : "0");
    }

    public static boolean triggerScan(Context context) {
        return put(context, KEY_SCAN, String.valueOf(System.currentTimeMillis()));
    }

    public static boolean setDebugEnabled(Context context, boolean enabled) {
        return put(context, KEY_DEBUG, enabled ? "1" : "0");
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
        String current = get(context, KEY_SERVICES);
        if (current == null) {
            current = "";
        }
        if (desired.equals(current)) {
            return;
        }
        if (put(context, KEY_SERVICES, desired)) {
            LogUtil.log(context, "[模块] 受管服务已同步: " + desired);
        } else {
            LogUtil.log(context, "[模块] 受管服务写入失败（需要 WRITE_SECURE_SETTINGS）");
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
                    LogUtil.log(app, "[模块] "
                            + EVENT_TIME_FMT.format(new Date(time)) + " " + message);
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
        try {
            return Settings.Secure.getString(context.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean put(Context context, String key, String value) {
        try {
            return Settings.Secure.putString(context.getContentResolver(), key, value);
        } catch (Throwable t) {
            LogUtil.log(context, "[模块] 写入 " + key + " 失败: " + t);
            return false;
        }
    }
}
