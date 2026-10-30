package com.accessibilitymanager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.LinkedHashSet;
import java.util.Set;

/** LSPosed 自动恢复模块的配置通道（基于系统广播直达 system_server + Settings.Secure 单一真实数据源） */
public final class ModuleConfig {

    public static final String KEY_ENABLE = "a11y_module_enable";
    public static final String KEY_SERVICES = "a11y_module_services";
    public static final String KEY_DRY_RUN = "a11y_module_dry_run";
    public static final String KEY_SCAN = "a11y_module_scan";
    public static final String KEY_LOADED = "a11y_module_loaded";
    public static final String KEY_EVENTS = "a11y_module_events";
    public static final String KEY_DEBUG = "a11y_module_debug";
    public static final String KEY_DEBUG_KILL = "a11y_module_debug_kill";
    public static final String KEY_CMD_ACK = "a11y_module_cmd_ack";

    // 系统广播通信管道（直达 system_server，零延迟、免 Root/免 Shizuku、永不断联）
    public static final String ACTION_SERVICE_CMD = "com.accessibilitymanager.action.SERVICE_CMD";
    public static final String PERMISSION_SEND_CMD = "com.accessibilitymanager.permission.SEND_MODULE_CMD";
    public static final String EXTRA_PAYLOAD = "payload";
    public static final String EXTRA_TIME = "time";

    private static final Object sLogLock = new Object();
    private static ContentObserver sLogObserver;
    private static Context sLogContext;
    private static volatile int sPingAckBootCount = Integer.MIN_VALUE;

    private ModuleConfig() {
    }

    /** 模块会在本次开机加载后写 bootCount|version；若 ping 广播收到实时回执同样认定已加载 */
    public static boolean isModuleLoaded(Context context) {
        if (context == null) {
            return false;
        }
        try {
            int currentBoot = Settings.Global.getInt(
                    context.getContentResolver(), "boot_count", -1);
            if (sPingAckBootCount != Integer.MIN_VALUE && sPingAckBootCount == currentBoot) {
                return true;
            }
            String value = Settings.Secure.getString(
                    context.getContentResolver(), KEY_LOADED);
            return isLoadedValueValid(value, currentBoot);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isLoadedValueValid(String value, int currentBoot) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            int separator = value.indexOf('|');
            String bootValue = separator > 0 ? value.substring(0, separator) : value;
            int moduleBoot = Integer.parseInt(bootValue);
            // 部分 ROM 没有 boot_count，退化为“有心跳即加载”
            return currentBoot < 0 || moduleBoot == currentBoot;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 若当前心跳未对齐（如刚重启），向 system_server 模块发送轻量 ping 触发立即补写心跳与实时回执校验 */
    public static void requestModuleHeartbeat(Context context) {
        requestModuleHeartbeat(context, null);
    }

    public static void requestModuleHeartbeat(Context context, Runnable onVerified) {
        if (context == null || isModuleLoaded(context)) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final String beforeAck = get(appCtx, KEY_CMD_ACK);
        sendServiceCommandPayload(appCtx, "ping");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                String afterAck = get(appCtx, KEY_CMD_ACK);
                if (afterAck != null && !afterAck.isEmpty() && !afterAck.equals(beforeAck)) {
                    int currentBoot = Settings.Global.getInt(
                            appCtx.getContentResolver(), "boot_count", -1);
                    if (sPingAckBootCount != currentBoot) {
                        sPingAckBootCount = currentBoot;
                        LogUtil.log(appCtx, "[模块] 收到系统进程 ping 实时回执, bootCount=" + currentBoot);
                    }
                    if (onVerified != null) {
                        onVerified.run();
                    }
                } else if (isModuleLoaded(appCtx) && onVerified != null) {
                    onVerified.run();
                }
            } catch (Throwable ignored) {
            }
        }, 250L);
    }

    public static boolean setEnabled(Context context, boolean enabled) {
        put(context, KEY_ENABLE, enabled ? "1" : "0");
        return sendServiceCommandPayload(context, "enable_module:" + (enabled ? "1" : "0"));
    }

    public static boolean isRemoteModuleEnabled(Context context) {
        return "1".equals(get(context, KEY_ENABLE));
    }

    public static boolean setDryRun(Context context, boolean dryRun) {
        put(context, KEY_DRY_RUN, dryRun ? "1" : "0");
        return sendServiceCommandPayload(context, "dry_run:" + (dryRun ? "1" : "0"));
    }

    public static boolean triggerScan(Context context) {
        put(context, KEY_SCAN, String.valueOf(System.currentTimeMillis()));
        return sendServiceCommandPayload(context, "scan");
    }

    public static boolean setDebugEnabled(Context context, boolean enabled) {
        put(context, KEY_DEBUG, enabled ? "1" : "0");
        return sendServiceCommandPayload(context, "debug:" + (enabled ? "1" : "0"));
    }

    /** 让模块代为开启/关闭无障碍服务（方案 A：广播直达 system_server，无需 WRITE_SECURE_SETTINGS） */
    public static boolean sendServiceCommand(Context context, boolean enable, String component) {
        if (component == null || component.isEmpty()) {
            return false;
        }
        return sendServiceCommandPayload(context, (enable ? "enable:" : "disable:") + component);
    }

    public static boolean sendServiceCommandPayload(Context context, String payload) {
        if (context == null || payload == null || payload.isEmpty()) {
            return false;
        }
        try {
            Intent intent = new Intent(ACTION_SERVICE_CMD);
            intent.putExtra(EXTRA_PAYLOAD, payload);
            intent.putExtra(EXTRA_TIME, System.currentTimeMillis());
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            LogUtil.log(context, "[模块] 发送广播指令失败: " + t);
            return false;
        }
    }

    /** 让模块杀掉目标组件所属应用的进程，模拟一次真实崩溃 */
    public static boolean triggerDebugKill(Context context, String component) {
        if (component == null || component.isEmpty()) {
            return false;
        }
        put(context, KEY_DEBUG_KILL,
                component + "#" + System.currentTimeMillis());
        return sendServiceCommandPayload(context, "debug_kill:" + component);
    }

    public static final String KEY_FIX_MODE = "a11y_module_fix_mode";
    public static final String KEY_FIX_DISABLED = "a11y_module_fix_disabled";
    public static final String KEY_CIRCUIT_CONFIG = "a11y_module_circuit_config";

    public static final String SP_UNSTABLE_THRESHOLD = "lsposed_unstable_threshold";
    public static final String SP_UNSTABLE_COOLDOWN = "lsposed_unstable_cooldown";
    public static final String SP_MAX_ATTEMPTS = "lsposed_max_attempts";
    public static final int DEFAULT_UNSTABLE_THRESHOLD = 3;
    public static final int DEFAULT_UNSTABLE_COOLDOWN = 10;
    public static final int DEFAULT_MAX_ATTEMPTS = 2;

    private static volatile String sLastSyncedServices = null;

    public static boolean setFixMode(Context context, boolean fixMode, String disabledServices) {
        put(context, KEY_FIX_MODE, fixMode ? "1" : "0");
        put(context, KEY_FIX_DISABLED, disabledServices != null ? disabledServices : "");
        return sendServiceCommandPayload(context, "sync_fixmode:" + (fixMode ? "1" : "0") + "#"
                + (disabledServices != null ? disabledServices : ""));
    }

    public static boolean syncCircuitConfig(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences("data", 0);
        int threshold = prefs.getInt(SP_UNSTABLE_THRESHOLD, DEFAULT_UNSTABLE_THRESHOLD);
        int cooldownMin = prefs.getInt(SP_UNSTABLE_COOLDOWN, DEFAULT_UNSTABLE_COOLDOWN);
        int maxAttempts = prefs.getInt(SP_MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS);
        return syncCircuitConfig(context, threshold, cooldownMin, maxAttempts);
    }

    public static boolean syncCircuitConfig(Context context, int threshold, int cooldownMin, int maxAttempts) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences("data", 0);
        prefs.edit()
                .putInt(SP_UNSTABLE_THRESHOLD, threshold)
                .putInt(SP_UNSTABLE_COOLDOWN, cooldownMin)
                .putInt(SP_MAX_ATTEMPTS, maxAttempts)
                .apply();
        String value = threshold + "|" + cooldownMin + "|" + maxAttempts;
        put(context, KEY_CIRCUIT_CONFIG, value);
        return sendServiceCommandPayload(context, "sync_circuit:" + value);
    }

    public static boolean resetCircuitBreaker(Context context) {
        if (context == null) {
            return false;
        }
        LogUtil.log(context, "[模块] 下发恢复暂停修复服务指令");
        return sendServiceCommandPayload(context, "reset_circuit");
    }

    /** 解析熔断与重试配置字符串 "threshold|cooldownMin|maxAttempts" */
    public static int[] parseCircuitConfig(String raw) {
        int threshold = DEFAULT_UNSTABLE_THRESHOLD;
        int cooldownMin = DEFAULT_UNSTABLE_COOLDOWN;
        int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        if (raw != null && !raw.isEmpty()) {
            String[] parts = raw.split("\\|");
            if (parts.length >= 1) {
                try {
                    threshold = Math.max(1, Math.min(20, Integer.parseInt(parts[0].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
            if (parts.length >= 2) {
                try {
                    cooldownMin = Math.max(0, Math.min(1440, Integer.parseInt(parts[1].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
            if (parts.length >= 3) {
                try {
                    maxAttempts = Math.max(1, Math.min(10, Integer.parseInt(parts[2].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new int[] { threshold, cooldownMin, maxAttempts };
    }

    public static void syncManagedServices(Context context, String daemon,
            String whitelist, boolean globalWhitelist) {
        syncManagedServices(context, daemon, whitelist, globalWhitelist, null);
    }

    public static void syncManagedServices(Context context, String daemon,
            String whitelist, boolean globalWhitelist, String reason) {
        String desired = buildManaged(daemon, whitelist, globalWhitelist);
        String existing = getManagedServices(context);
        boolean changed = !desired.equals(sLastSyncedServices) && !desired.equals(existing);
        sLastSyncedServices = desired;
        if (changed) {
            String reasonSuffix = (reason != null && !reason.isEmpty()) ? " (" + reason + ")" : "";
            LogUtil.log(context, "[模块] 下发锁定同步指令" + reasonSuffix + (desired.isEmpty() ? "" : ": " + desired));
        }
        // 方案 A：系统广播通道直达模块，确保无论是否拥有 WRITE_SECURE_SETTINGS 都能瞬间生效
        sendServiceCommandPayload(context, "sync_services:" + desired);
        put(context, KEY_SERVICES, desired);
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
        if (context == null) {
            return null;
        }
        try {
            return Settings.Secure.getString(context.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean put(Context context, String key, String value) {
        if (context == null) {
            return false;
        }
        try {
            return Settings.Secure.putString(context.getContentResolver(), key, value);
        } catch (Throwable ignored) {
            // 未授予 WRITE_SECURE_SETTINGS 时由广播通道通知 system_server 模块代写，无需报错刷屏
            return false;
        }
    }

    /** 弹出 LSPosed 模块未激活提示与引导对话框 */
    public static void showLsposedNotActivatedDialog(Context context, Runnable onFallbackToNormal) {
        if (context == null) return;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle("LSPosed 模块未激活")
                .setMessage("当前尚未检测到本模块在 LSPosed 中激活或生效。\n\n"
                        + "激活步骤：\n"
                        + "1. 打开【LSPosed 管理器】（如从系统通知栏或拨号盘暗码进入）\n"
                        + "2. 在模块列表中找到并勾选【无障碍管理器】\n"
                        + "3. 勾选作用域：系统框架 (Android 系统)\n"
                        + "4. 重启手机（或执行软重启）使模块生效\n\n"
                        + "激活生效后即可享受系统底层被动实时监听与 0 轮询保活。")
                .setPositiveButton("知道了", null);

        if (onFallbackToNormal != null) {
            builder.setNeutralButton("改用普通模式", (dialog, which) -> onFallbackToNormal.run());
        }
        builder.show();
    }

    /** 已开启 LSPosed 模式但重启后检测到模块未生效时的回退提示弹窗 */
    public static void showLsposedInactiveFallbackDialog(Context context, Runnable onSwitchToNormal) {
        if (context == null) return;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle("LSPosed 模块未生效")
                .setMessage("检测到当前已开启「LSPosed 模块模式」，但模块在本次开机中未加载生效（可能已在 LSPosed 中取消勾选或尚未重启生效）。\n\n"
                        + "当前所有加锁服务处于暂停守护状态，建议立即切回普通模式，或在 LSPosed 中重新勾选本模块并重启手机。")
                .setNegativeButton("暂不切换", null);
        if (onSwitchToNormal != null) {
            builder.setPositiveButton("切回普通模式", (dialog, which) -> onSwitchToNormal.run());
        }
        builder.show();
    }
}
