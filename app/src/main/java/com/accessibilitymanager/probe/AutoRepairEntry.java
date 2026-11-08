package com.accessibilitymanager.probe;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 无障碍服务崩溃自动恢复模块（基于 Modern Xposed API 102，运行在 system_server 内）。
 */
public class AutoRepairEntry extends XposedModule {

    private static final String TAG = "A11yRepair";
    private static final String CONNECTION_CLASS =
            "com.android.server.accessibility.AccessibilityServiceConnection";
    static final String HOOK_ID_BINDER_DIED = "a11y_repair_binder_died";
    static final String HOOK_ID_INIT_SERVICE = "a11y_repair_init_service";

    // 热重载跨代状态键（仅使用 JDK/Android 标准类型，跨 ClassLoader 安全传递）
    private static final String STATE_CONNECTIONS = "connections";
    private static final String STATE_MODULE_START_AT = "module_start_at";
    private static final String STATE_FIRST_CONNECT_AT = "first_connect_at";
    private static final String STATE_BLOCKED_UNTIL = "blocked_until";
    private static final String STATE_ATTEMPTS = "attempts";
    private static final String STATE_LAST_ATTEMPT_AT = "last_attempt_at";
    private static final String STATE_LAST_PKG_KILL_AT = "last_pkg_kill_at";
    private static final String STATE_EVENT_LINES = "event_lines";
    private static final String STATE_RAW_TRACE_LINES = "raw_trace_lines";
    private static final String STATE_EVENT_SEQ = "event_seq";
    private static final String STATE_HOT_RELOAD_COUNT = "hot_reload_count";
    private static final String STATE_CONTEXT = "context";
    private static final String STATE_CLASS_LOADER = "class_loader";

    // 配置项（Settings.Secure，由管理器写入）
    private static final String KEY_ENABLE = "a11y_module_enable";
    private static final String KEY_SERVICES = "a11y_module_services";
    private static final String KEY_DRY_RUN = "a11y_module_dry_run";
    private static final String KEY_SCAN = "a11y_module_scan";
    private static final String KEY_DEBUG = "a11y_module_debug";
    private static final String KEY_DEBUG_KILL = "a11y_module_debug_kill";
    private static final String KEY_FIX_MODE = "a11y_module_fix_mode";
    private static final String KEY_FIX_DISABLED = "a11y_module_fix_disabled";
    private static final String KEY_CIRCUIT_CONFIG = "a11y_module_circuit_config";
    private static final String KEY_CMD_ACK = "a11y_module_cmd_ack";
    private static final String KEY_LOADED = "a11y_module_loaded";
    private static final String KEY_EVENTS = "a11y_module_events";
    // system_server 视角的诊断快照，供管理器在无 DUMP/Root/Shizuku 权限时导出日志
    private static final String KEY_DUMP = "a11y_module_dump";
    // 持久事件通道容量：噪音聚合后 120 条约可覆盖数天；Settings.Secure 单值不宜过大
    private static final int EVENT_LIMIT = 120;
    // 非保活服务（便签输入法等）连接日志聚合窗口：本身不重要，1 小时一条；明细仍全部写 logcat
    private static final long NON_MANAGED_EVENT_WINDOW_MS = 60 * 60 * 1000L;
    // 跳过路径聚合窗口：原因有诊断价值，保留 10 分钟粒度
    private static final long SKIP_EVENT_WINDOW_MS = 10 * 60 * 1000L;
    private static final String SETTING_ENABLED =
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    private static final String SETTING_BOOT_COUNT = "boot_count";

    // 方案 A：系统广播通信管道（直达 system_server，免 Root/免 Shizuku、零延迟）
    public static final String ACTION_SERVICE_CMD =
            "com.accessibilitymanager.action.SERVICE_CMD";
    public static final String PERMISSION_SEND_CMD =
            "com.accessibilitymanager.permission.SEND_MODULE_CMD";
    public static final String EXTRA_PAYLOAD = "payload";

    private static final long DEDUPE_MS = 1500L;
    private static final long SETTLE_MS = 3000L;
    private static final long TOGGLE_OFF_MS = 500L;
    private static final long VERIFY_MS = 3500L;
    private static final long SUPPRESS_MS = 10000L;
    private static final long RETRY_COOLDOWN_MS = 20000L;
    private static final int DEFAULT_MAX_ATTEMPTS = 2;
    private static final long RESET_AFTER_MS = 5 * 60 * 1000L;
    private static final long UNSTABLE_WINDOW_MS = 2 * 60 * 1000L;
    private static final int DEFAULT_UNSTABLE_THRESHOLD = 3;
    private static final int DEFAULT_UNSTABLE_COOLDOWN_MIN = 10;
    private static final long REPAIR_STUCK_MS = 15000L;
    private static final long SCAN_INIT_DELAY_MS = 10000L;
    private static final long SCAN_CONFIG_DELAY_MS = 1500L;

    // 关闭补回配置
    private static final long CLOSE_MIN_INTERVAL_MS = 250L;
    private static final long RECONCILE_CONFIG_DELAY_MS = 500L;
    // 补回写入后延迟检查是否真正绑定（force-stop 后可能“列表已恢复但未绑定”）
    private static final long BIND_VERIFY_DELAY_MS = 2000L;
    private static final long REPAIR_REMOVE_WINDOW_MS = TOGGLE_OFF_MS + 1500L;
    static final long BOOT_GRACE_MS = RepairPolicy.BOOT_GRACE_MS;
    static final long BOOT_FIRST_CONNECT_WINDOW_MS = RepairPolicy.BOOT_FIRST_CONNECT_WINDOW_MS;
    static final long BOOT_WAVE_GRACE_MS = RepairPolicy.BOOT_WAVE_GRACE_MS;
    static final long PACKAGE_KILL_COOLDOWN_MS = RepairPolicy.PACKAGE_KILL_COOLDOWN_MS;

    private static volatile AutoRepairEntry sModuleInstance;
    private static volatile Context sContext;
    private static volatile ClassLoader sClassLoader;
    private static volatile HandlerThread sHandlerThread;
    private static Handler sHandler;
    private static boolean sInstalled;
    private static volatile String sProcessName;
    private static volatile boolean sIsSystemServer;
    private static volatile int sHotReloadCount;
    private static volatile long sLastHotReloadAt;
    private static volatile ContentObserver sObserver;
    private static int sObserverAttempts;
    private static boolean sScanPending;
    // 开机早期初始化与心跳写入重试上限保护（system_server 启动 ContentService 约需 15~45s）
    static final int MAX_INIT_RETRY = 15;
    private static final long INIT_RETRY_DELAY_MS = 5000L;
    static final int MAX_HEARTBEAT_ATTEMPTS = 8;
    private static final long HEARTBEAT_RETRY_DELAY_MS = 5000L;
    private static final HeartbeatTracker sHeartbeatTracker =
            new HeartbeatTracker(MAX_HEARTBEAT_ATTEMPTS);
    private static boolean sHeartbeatRetryPending;
    private static volatile BroadcastReceiver sCommandReceiver;
    private static int sContextRetryCount;

    // 事件缓冲
    private static final int RAW_TRACE_LIMIT = 200;
    private static final Object sEventLock = new Object();
    private static final Deque<String> sEventLines = new ArrayDeque<>();
    private static final Deque<String> sRawTraceLines = new ArrayDeque<>();
    private static final String sEventSession =
            Long.toHexString(System.currentTimeMillis());
    private static long sEventSeq;
    private static final EventThrottle sNonManagedThrottle =
            new EventThrottle(NON_MANAGED_EVENT_WINDOW_MS);
    private static final EventThrottle sSkipThrottle =
            new EventThrottle(SKIP_EVENT_WINDOW_MS);

    // 状态缓存（仅在 sHandler 线程读写）
    private static boolean sRepairInProgress;
    private static long sRepairStartedAt;
    private static String sRepairKey;
    private static final Map<String, Long> sLastDied = new HashMap<>();
    private static final Map<String, Long> sLastInit = new HashMap<>();
    private static final Map<String, Runnable> sPendingCheck = new HashMap<>();
    private static final Map<String, Runnable> sVerifyRun = new HashMap<>();
    private static final Map<String, Long> sSuppressUntil = new HashMap<>();
    private static final Map<String, Long> sBlockedUntil = new HashMap<>();
    private static final Map<String, Integer> sAttempts = new HashMap<>();
    private static final Map<String, Long> sLastAttemptAt = new HashMap<>();
    private static final Map<String, Deque<Long>> sCrashHistory = new HashMap<>();
    private static final Map<String, Object> sConnections = new HashMap<>();
    private static boolean sBoundCheckAvailable;
    private static boolean sBoundCheckErrorLogged;

    private static boolean sReconcilePending;
    private static boolean sReconcileRetryPending;
    private static boolean sBindVerifyPending;
    private static long sLastReconcileWriteAt;
    private static long sModuleStartAt = SystemClock.elapsedRealtime();
    private static long sFirstServiceConnectAt;
    private static Runnable sPostBootCheckRunnable;
    private static final Map<String, Long> sLastPackageKillAt = new HashMap<>();
    private static final Set<String> sPendingVerifyKeys = new LinkedHashSet<>();
    private static final Map<String, Long> sSelfRemovedUntil = new HashMap<>();
    private static final Map<String, Long> sManualHiddenUntil = new HashMap<>();
    private static volatile String sModuleEnabledCache;
    private static volatile String sManagedServicesCache;
    private static volatile String sFixModeCache;
    private static volatile String sFixDisabledCache;
    private static volatile String sCircuitConfigCache;
    private static volatile String sDryRunCache;
    private static volatile String sDebugCache;
    // 启用列表读取为 null 时的原因：非 null 表示读取异常，null 表示设置项不存在（全新设备或被 ROM 清空）
    private static volatile Throwable sLastEnabledReadError;

    // 反射缓存
    private static final Map<String, Field> sFieldCache = new ConcurrentHashMap<>();
    private static final Map<String, Method> sMethodCache = new ConcurrentHashMap<>();

    public AutoRepairEntry() {
        sModuleInstance = this;
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sModuleInstance = this;
        sProcessName = param.getProcessName();
        sIsSystemServer = param.isSystemServer();
        log("Modern API " + safeApiVersion() + " loaded in " + sProcessName
                + " (isSystemServer=" + sIsSystemServer + ")");
    }

    private int safeApiVersion() {
        try {
            return getApiVersion();
        } catch (Throwable ignored) {
            return 102;
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        sIsSystemServer = true;
        initAndInstall(param.getClassLoader(), false);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if ("android".equals(param.getPackageName()) || "system".equals(param.getPackageName())) {
            sIsSystemServer = true;
            initAndInstall(param.getClassLoader(), false);
        }
    }

    /**
     * 热重载准备阶段（运行在旧代实例中）：
     * 1. 将跨代状态（已绑定连接、开机首连时间戳、熔断与重试计数、事件缓冲、Context/ClassLoader）
     *    封装为纯 JDK/Android 框架类型 Map 保存；
     * 2. 注销旧代注册的 ContentObserver 与 BroadcastReceiver，并安全退出旧代 HandlerThread，
     *    彻底杜绝热重载后新旧两代实例在 system_server 内双倍响应或线程泄漏。
     */
    @Override
    public synchronized boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        if (!sIsSystemServer
                && sProcessName != null
                && !"system".equals(sProcessName)
                && !"android".equals(sProcessName)
                && !"system_server".equals(sProcessName)) {
            log("skipping hot reload in non-system process: " + sProcessName);
            return false;
        }
        log("onHotReloading in " + (sProcessName != null ? sProcessName : "system_server")
                + " (connections=" + sConnections.size() + ", hotReloadCount=" + sHotReloadCount + ")");

        // 1. 先停止旧代后台线程与系统监听器，确保后续状态快照无并发竞争
        stopActiveResourcesForHotReload();

        // 2. 保存跨 ClassLoader 安全的状态快照
        try {
            Map<String, Object> state = new HashMap<>();
            state.put(STATE_CONNECTIONS, new HashMap<>(sConnections));
            state.put(STATE_MODULE_START_AT, sModuleStartAt);
            state.put(STATE_FIRST_CONNECT_AT, sFirstServiceConnectAt);
            state.put(STATE_BLOCKED_UNTIL, new HashMap<>(sBlockedUntil));
            state.put(STATE_ATTEMPTS, new HashMap<>(sAttempts));
            state.put(STATE_LAST_ATTEMPT_AT, new HashMap<>(sLastAttemptAt));
            state.put(STATE_LAST_PKG_KILL_AT, new HashMap<>(sLastPackageKillAt));
            state.put(STATE_HOT_RELOAD_COUNT, sHotReloadCount);
            if (sContext != null) {
                state.put(STATE_CONTEXT, sContext);
            }
            if (sClassLoader != null) {
                state.put(STATE_CLASS_LOADER, sClassLoader);
            }
            synchronized (sEventLock) {
                state.put(STATE_EVENT_LINES, new ArrayList<>(sEventLines));
                state.put(STATE_RAW_TRACE_LINES, new ArrayList<>(sRawTraceLines));
                state.put(STATE_EVENT_SEQ, sEventSeq);
            }
            param.setSavedInstanceState(state);
        } catch (Throwable t) {
            log("onHotReloading state save failed: " + t);
        }

        // 3. 清理旧代内存引用
        clearInMemoryStateForHotReload();
        return true;
    }

    /**
     * 热重载完成阶段（运行在新代实例中）：
     * 1. 恢复上一代传承的连接状态、事件缓冲与熔断计数；
     * 2. 提取 system_server ClassLoader，通过相同 Hook ID (setId) 原子替换旧 Hook；
     * 3. 立即重新注册广播与配置监听（无需像开机冷启动那样等待 10s）。
     */
    @Override
    public synchronized void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        sModuleInstance = this;
        sProcessName = param.getProcessName();
        sIsSystemServer = param.isSystemServer();
        int oldHookCount = param.getOldHookHandles() != null ? param.getOldHookHandles().size() : 0;

        restoreStateFromHotReload(param.getSavedInstanceState());
        sHotReloadCount++;
        sLastHotReloadAt = System.currentTimeMillis();

        ClassLoader targetLoader = resolveHotReloadClassLoader(param);
        sInstalled = false;
        initAndInstall(targetLoader, true);

        event("配置", "模块已热重载生效 (第" + sHotReloadCount + "次, 继承连接="
                + sConnections.size() + ", 原子替换Hook=" + oldHookCount + ")",
                "action=hot_reloaded count=" + sHotReloadCount
                        + " inheritedConns=" + sConnections.size()
                        + " oldHooks=" + oldHookCount);
    }

    private static void stopActiveResourcesForHotReload() {
        Handler handler = sHandler;
        sHandler = null;
        if (handler != null) {
            try {
                handler.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {
            }
        }
        HandlerThread thread = sHandlerThread;
        sHandlerThread = null;
        if (thread != null) {
            try {
                thread.quitSafely();
            } catch (Throwable ignored) {
            }
        }
        Context ctx = sContext;
        ContentObserver observer = sObserver;
        sObserver = null;
        if (observer != null && ctx != null) {
            try {
                ctx.getContentResolver().unregisterContentObserver(observer);
            } catch (Throwable t) {
                log("unregister observer on hot reload failed: " + t);
            }
        }
        BroadcastReceiver receiver = sCommandReceiver;
        sCommandReceiver = null;
        if (receiver != null && ctx != null) {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (Throwable t) {
                log("unregister receiver on hot reload failed: " + t);
            }
        }
    }

    private static void clearInMemoryStateForHotReload() {
        sInstalled = false;
        sScanPending = false;
        sReconcilePending = false;
        sReconcileRetryPending = false;
        sBindVerifyPending = false;
        sHeartbeatRetryPending = false;
        sRepairInProgress = false;
        sRepairKey = null;
        sPostBootCheckRunnable = null;
        sConnections.clear();
        sPendingCheck.clear();
        sVerifyRun.clear();
        sLastDied.clear();
        sLastInit.clear();
        sSuppressUntil.clear();
        sBlockedUntil.clear();
        sAttempts.clear();
        sLastAttemptAt.clear();
        sLastPackageKillAt.clear();
        sCrashHistory.clear();
        sPendingVerifyKeys.clear();
        sSelfRemovedUntil.clear();
        sManualHiddenUntil.clear();
        sFieldCache.clear();
        sMethodCache.clear();
    }

    @SuppressWarnings("unchecked")
    private static void restoreStateFromHotReload(Object savedObj) {
        if (!(savedObj instanceof Map)) {
            return;
        }
        Map<?, ?> state = (Map<?, ?>) savedObj;
        try {
            Object ctx = state.get(STATE_CONTEXT);
            if (ctx instanceof Context) {
                sContext = (Context) ctx;
            }
            Object loader = state.get(STATE_CLASS_LOADER);
            if (loader instanceof ClassLoader) {
                sClassLoader = (ClassLoader) loader;
            }
            Object firstConnect = state.get(STATE_FIRST_CONNECT_AT);
            if (firstConnect instanceof Long) {
                sFirstServiceConnectAt = (Long) firstConnect;
            }
            Object moduleStart = state.get(STATE_MODULE_START_AT);
            if (moduleStart instanceof Long && (Long) moduleStart > 0L) {
                sModuleStartAt = (Long) moduleStart;
            } else if (sFirstServiceConnectAt > 0L) {
                sModuleStartAt = Math.max(1L, sFirstServiceConnectAt - BOOT_GRACE_MS);
            }
            Object reloadCount = state.get(STATE_HOT_RELOAD_COUNT);
            if (reloadCount instanceof Integer) {
                sHotReloadCount = (Integer) reloadCount;
            }
            Object conns = state.get(STATE_CONNECTIONS);
            if (conns instanceof Map) {
                sConnections.clear();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) conns).entrySet()) {
                    if (entry.getKey() instanceof String && entry.getValue() != null) {
                        sConnections.put((String) entry.getKey(), entry.getValue());
                    }
                }
            }
            Object blocked = state.get(STATE_BLOCKED_UNTIL);
            if (blocked instanceof Map) {
                sBlockedUntil.clear();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) blocked).entrySet()) {
                    if (entry.getKey() instanceof String && entry.getValue() instanceof Long) {
                        sBlockedUntil.put((String) entry.getKey(), (Long) entry.getValue());
                    }
                }
            }
            Object attempts = state.get(STATE_ATTEMPTS);
            if (attempts instanceof Map) {
                sAttempts.clear();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) attempts).entrySet()) {
                    if (entry.getKey() instanceof String && entry.getValue() instanceof Integer) {
                        sAttempts.put((String) entry.getKey(), (Integer) entry.getValue());
                    }
                }
            }
            Object lastAttempt = state.get(STATE_LAST_ATTEMPT_AT);
            if (lastAttempt instanceof Map) {
                sLastAttemptAt.clear();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) lastAttempt).entrySet()) {
                    if (entry.getKey() instanceof String && entry.getValue() instanceof Long) {
                        sLastAttemptAt.put((String) entry.getKey(), (Long) entry.getValue());
                    }
                }
            }
            Object lastPkgKill = state.get(STATE_LAST_PKG_KILL_AT);
            if (lastPkgKill instanceof Map) {
                sLastPackageKillAt.clear();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) lastPkgKill).entrySet()) {
                    if (entry.getKey() instanceof String && entry.getValue() instanceof Long) {
                        sLastPackageKillAt.put((String) entry.getKey(), (Long) entry.getValue());
                    }
                }
            }
            synchronized (sEventLock) {
                Object seq = state.get(STATE_EVENT_SEQ);
                if (seq instanceof Long) {
                    sEventSeq = (Long) seq;
                }
                Object lines = state.get(STATE_EVENT_LINES);
                if (lines instanceof List) {
                    sEventLines.clear();
                    for (Object item : (List<?>) lines) {
                        if (item instanceof String) {
                            sEventLines.addLast((String) item);
                        }
                    }
                }
                Object traces = state.get(STATE_RAW_TRACE_LINES);
                if (traces instanceof List) {
                    sRawTraceLines.clear();
                    for (Object item : (List<?>) traces) {
                        if (item instanceof String) {
                            sRawTraceLines.addLast((String) item);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("restoreStateFromHotReload error: " + t);
        }
    }

    private static ClassLoader resolveHotReloadClassLoader(
            XposedModuleInterface.HotReloadedParam param) {
        if (param != null && param.getOldHookHandles() != null) {
            for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
                if (handle != null && handle.getExecutable() != null) {
                    ClassLoader cl = handle.getExecutable().getDeclaringClass().getClassLoader();
                    if (cl != null) {
                        return cl;
                    }
                }
            }
        }
        if (sClassLoader != null) {
            return sClassLoader;
        }
        for (Object conn : sConnections.values()) {
            if (conn != null && conn.getClass().getClassLoader() != null) {
                return conn.getClass().getClassLoader();
            }
        }
        ClassLoader ctxLoader = Thread.currentThread().getContextClassLoader();
        return ctxLoader != null ? ctxLoader : ClassLoader.getSystemClassLoader();
    }

    private synchronized void initAndInstall(ClassLoader classLoader, boolean isHotReload) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        sModuleInstance = this;
        if (classLoader != null) {
            sClassLoader = classLoader;
        }
        try {
            installHooks(classLoader, isHotReload);
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    private void installHooks(ClassLoader classLoader, boolean isHotReload) {
        try {
            HandlerThread thread = new HandlerThread("A11yAutoRepair");
            thread.start();
            sHandlerThread = thread;
            if (thread.getLooper() != null) {
                sHandler = new Handler(thread.getLooper());
                if (isHotReload) {
                    // 热重载时 system_server 早已就绪，立即恢复广播与配置监听
                    sHandler.post(AutoRepairEntry::scheduleReceiverInit);
                } else {
                    // onSystemServerStarting 时 ActivityManagerService/ContentService 尚未就绪，延迟启动轮询避免早期空指针刷屏
                    sHandler.postDelayed(AutoRepairEntry::scheduleReceiverInit, 10000L);
                }
            }
        } catch (Throwable t) {
            log("handler thread init skipped or failed: " + t);
        }

        if (classLoader == null) {
            return;
        }

        try {
            Class<?> connClass = classLoader.loadClass(CONNECTION_CLASS);

            Method binderDiedMethod = findMethod(connClass, "binderDied");
            if (binderDiedMethod != null) {
                hook(binderDiedMethod)
                        .setId(HOOK_ID_BINDER_DIED)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            postEvent(true, chain.getThisObject());
                            return result;
                        });
            } else {
                event("失败", "未找到 binderDied 方法");
            }

            Method initServiceMethod = findMethod(connClass, "initializeService");
            if (initServiceMethod != null) {
                hook(initServiceMethod)
                        .setId(HOOK_ID_INIT_SERVICE)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            postEvent(false, chain.getThisObject());
                            return result;
                        });
            } else {
                event("失败", "未找到 initializeService 方法");
            }

            if (!isHotReload) {
                event("配置", "Modern API 102 模块已加载");
            }
        } catch (Throwable t) {
            if (!isHotReload) {
                event("失败", "Hook 挂载异常: " + t);
            } else {
                log("hook re-bind skipped or failed during hot reload: " + t);
            }
        }
    }

    private static void event(String category, String message) {
        event(category, message, null);
    }

    private static void event(String category, String message, String rawMeta) {
        long sinceStart = Math.max(0L, SystemClock.elapsedRealtime() - sModuleStartAt);
        String line = RepairPolicy.formatEventLine(category, message, rawMeta, sinceStart);
        log(line);
        appendEvent(line);
    }

    private static String connId(Object connection) {
        return connection == null
                ? "null"
                : ("@" + Integer.toHexString(System.identityHashCode(connection)));
    }

    /** 非保活服务的连接/断开：仅记录到底层诊断轨迹与 logcat，不再写入持久主日志列表干扰用户 */
    private static void eventNonManaged(String key, String summary) {
        log("[监听] " + summary + ": " + key);
    }

    /** 高频跳过路径降噪：同一原因 10 分钟内只写一条持久事件，明细仍写 logcat */
    private static void eventSkip(String key, String category, String message) {
        EventThrottle.Decision decision = sSkipThrottle.onEvent(key, SystemClock.elapsedRealtime());
        if (!decision.emit) {
            log("[" + category + "] " + message);
            return;
        }
        String suffix = decision.suppressed > 0
                ? "（窗口内已省略 " + decision.suppressed + " 条）" : "";
        event(category, message + suffix);
    }

    private static void appendEvent(String message) {
        Handler handler = sHandler;
        if (handler != null) {
            handler.post(() -> appendEventNow(message));
        } else {
            synchronized (sEventLock) {
                addEventLineLocked(message);
                flushEventsLocked();
            }
        }
    }

    private static void appendEventNow(String message) {
        synchronized (sEventLock) {
            addEventLineLocked(message);
            flushEventsLocked();
        }
    }

    private static void addEventLineLocked(String message) {
        sEventSeq++;
        StringBuilder line = new StringBuilder();
        line.append(sEventSession).append('|').append(sEventSeq).append('|')
                .append(System.currentTimeMillis()).append('|')
                .append(message.replace('\n', ' '));
        sEventLines.addLast(line.toString());
        while (sEventLines.size() > EVENT_LIMIT) {
            sEventLines.removeFirst();
        }
    }

    private static void flushEvents() {
        synchronized (sEventLock) {
            flushEventsLocked();
        }
    }

    private static void flushEventsLocked() {
        Context context = sContext;
        if (context == null || sEventLines.isEmpty()) {
            return;
        }
        StringBuilder raw = new StringBuilder();
        for (String line : sEventLines) {
            if (raw.length() > 0) {
                raw.append('\n');
            }
            raw.append(line);
        }
        try {
            Settings.Secure.putString(context.getContentResolver(), KEY_EVENTS,
                    raw.toString());
        } catch (Throwable t) {
            log("event write failed: " + t);
        }
    }

    private static void postEvent(boolean died, Object connection) {
        if (connection == null) {
            return;
        }
        try {
            ComponentName component = getComponentName(connection);
            if (component == null) {
                return;
            }
            Object info = getFieldValue(connection, "mAccessibilityServiceInfo");
            boolean crashed = false;
            if (info != null) {
                Object crashedVal = getFieldValue(info, "crashed");
                if (crashedVal instanceof Boolean) {
                    crashed = (Boolean) crashedVal;
                }
            }
            boolean hasInterface = getFieldValue(connection, "mServiceInterface") != null;
            Object ctxObj = getFieldValue(connection, "mContext");
            Context context = ctxObj instanceof Context ? (Context) ctxObj : null;

            Handler handler = sHandler;
            if (context != null && (sContext == null || sObserver == null || sCommandReceiver == null)) {
                final boolean changed = sContext != context;
                sContext = context;
                if (handler != null) {
                    handler.post(() -> {
                        if (changed) {
                            sObserverAttempts = 0;
                        }
                        ensureObserver();
                        ensureCommandReceiver();
                    });
                }
            }
            if (handler == null) {
                return;
            }
            final boolean finalCrashed = crashed;
            handler.post(() -> {
                try {
                    long now = SystemClock.elapsedRealtime();
                    if (!died && sFirstServiceConnectAt == 0L) {
                        sFirstServiceConnectAt = now;
                        long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
                        if (remainingBoot > 0L) {
                            schedulePostBootCheck(remainingBoot + 1000L);
                        }
                    }
                    String connKey = component.flattenToString();
                    if (!died) {
                        sConnections.put(connKey, connection);
                        onInitializeService(component, finalCrashed, hasInterface, connection);
                    } else {
                        Object existing = sConnections.get(connKey);
                        if (existing == null || existing == connection || !isConnectionBound(existing)) {
                            sConnections.put(connKey, connection);
                        }
                        onBinderDied(component, finalCrashed, hasInterface, connection);
                    }
                } catch (Throwable t) {
                    event("失败", "事件处理异常: " + t);
                }
            });
        } catch (Throwable t) {
            event("失败", "事件读取失败: " + t);
        }
    }

    private static void writeHeartbeatOnce() {
        writeHeartbeatOnce("auto");
    }

    private static void writeHeartbeatOnce(String reason) {
        Context context = sContext;
        if (context == null) {
            context = getSystemContext();
        }
        if (context == null) {
            if (sHeartbeatTracker.onWriteFailure()) {
                scheduleHeartbeatRetry();
            } else {
                log("heartbeat write deferred (context null, retry limit reached)");
            }
            return;
        }
        try {
            int bootCount = Settings.Global.getInt(
                    context.getContentResolver(), SETTING_BOOT_COUNT, -1);
            if (!sHeartbeatTracker.shouldWrite(bootCount)) {
                return;
            }
            boolean ok = Settings.Secure.putString(context.getContentResolver(), KEY_LOADED,
                    bootCount + "|1");
            if (ok) {
                sHeartbeatTracker.onWriteSuccess(bootCount);
                log("[配置] 心跳已写入, bootCount=" + bootCount + " (" + reason + ")");
            } else {
                if (sHeartbeatTracker.onWriteFailure()) {
                    scheduleHeartbeatRetry();
                } else {
                    log("heartbeat write returned false, retry limit reached");
                }
            }
        } catch (Throwable t) {
            if (sHeartbeatTracker.onWriteFailure()) {
                log("heartbeat write deferred (system not ready): " + t);
                scheduleHeartbeatRetry();
            } else {
                log("heartbeat auto-retry exhausted, waiting for passive/broadcast trigger: " + t);
            }
        }
    }

    private static void scheduleHeartbeatRetry() {
        if (sHeartbeatRetryPending || sHandler == null) {
            return;
        }
        sHeartbeatRetryPending = true;
        sHandler.postDelayed(() -> {
            sHeartbeatRetryPending = false;
            writeHeartbeatOnce("retry");
        }, HEARTBEAT_RETRY_DELAY_MS);
    }

    private static void ensureObserver() {
        if (sContext == null || sHandler == null) {
            return;
        }
        writeHeartbeatOnce("observer_check");
        flushEvents();
        ensureCommandReceiver();
        if (sObserver != null) {
            return;
        }
        try {
            ContentResolver resolver = sContext.getContentResolver();
            sObserver = new ContentObserver(new Handler(sHandler.getLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    String key = uri == null ? null : uri.getLastPathSegment();
                    if (SETTING_BOOT_COUNT.equals(key)) {
                        writeHeartbeatOnce("boot_count变更");
                        return;
                    }
                    if (SETTING_ENABLED.equals(key)) {
                        onEnabledListChanged();
                        return;
                    }
                    if (KEY_DEBUG_KILL.equals(key)) {
                        scheduleDebugKill();
                        return;
                    }
                    if (KEY_SCAN.equals(key)) {
                        scheduleScan(0L, "manual scan");
                        return;
                    }
                    if (KEY_DRY_RUN.equals(key)) {
                        sDryRunCache = readSecure(KEY_DRY_RUN);
                        return;
                    }
                    if (KEY_DEBUG.equals(key)) {
                        sDebugCache = readSecure(KEY_DEBUG);
                        return;
                    }
                    if (KEY_CIRCUIT_CONFIG.equals(key)) {
                        sCircuitConfigCache = readSecure(KEY_CIRCUIT_CONFIG);
                        return;
                    }
                    if (KEY_ENABLE.equals(key)) {
                        sModuleEnabledCache = readSecure(KEY_ENABLE);
                        if (isModuleEnabled()) {
                            scheduleReconcile("enable", RECONCILE_CONFIG_DELAY_MS);
                        }
                    } else if (KEY_SERVICES.equals(key)) {
                        sManagedServicesCache = readSecure(KEY_SERVICES);
                        scheduleReconcile("services", RECONCILE_CONFIG_DELAY_MS);
                        scheduleScan(SCAN_CONFIG_DELAY_MS, "services changed");
                    }
                }
            };
            resolver.registerContentObserver(Settings.Global.getUriFor(SETTING_BOOT_COUNT),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_ENABLE),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_SERVICES),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_SCAN),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_DRY_RUN),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_DEBUG),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_DEBUG_KILL),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_CIRCUIT_CONFIG),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_ENABLED),
                    false, sObserver);
            sObserverAttempts = 0;
            log("[配置] 配置监听已注册 (含 boot_count 实时同步)");
            writeHeartbeatOnce("observer_ready");
            long sinceBoot = SystemClock.elapsedRealtime();
            long remainingBoot = getRemainingBootGraceMs(sinceBoot, sFirstServiceConnectAt);
            if (remainingBoot > 0L) {
                schedulePostBootCheck(remainingBoot + 1000L);
            } else {
                scheduleScan(SCAN_INIT_DELAY_MS, "init");
                scheduleReconcile("init", SCAN_INIT_DELAY_MS);
            }
        } catch (Throwable t) {
            sObserver = null;
            sObserverAttempts++;
            log("register observer deferred (" + sObserverAttempts + "): " + t);
            if (sContextRetryCount >= MAX_INIT_RETRY && sObserverAttempts <= 3) {
                sHandler.postDelayed(AutoRepairEntry::ensureObserver, 5000L);
            } else if (sContextRetryCount >= MAX_INIT_RETRY) {
                event("失败", "配置监听注册失败 (" + sObserverAttempts + "): " + t);
            }
        }
    }

    /**
     * 计算开机冷却期截止时间（elapsedRealtime 毫秒）：
     * 基础冷却 BOOT_GRACE_MS（90s）；若在开机 180s 内首次收到无障碍服务连接（用户解锁后系统开始批量绑定），
     * 则从首个服务连接起至少给予 BOOT_WAVE_GRACE_MS（30s）的缓冲期，等待系统将队列中的慢启动服务自然绑定完毕。
     */
    static long getBootGraceDeadlineMs(long firstConnectAt) {
        return RepairPolicy.getBootGraceDeadlineMs(firstConnectAt);
    }

    static long getRemainingBootGraceMs(long now, long firstConnectAt) {
        return RepairPolicy.getRemainingBootGraceMs(now, sModuleStartAt, firstConnectAt);
    }

    private static void schedulePostBootCheck(long delayMs) {
        if (sHandler == null) {
            return;
        }
        if (sPostBootCheckRunnable != null) {
            sHandler.removeCallbacks(sPostBootCheckRunnable);
        }
        sPostBootCheckRunnable = () -> {
            sPostBootCheckRunnable = null;
            long now = SystemClock.elapsedRealtime();
            long remaining = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
            if (remaining > 0L) {
                schedulePostBootCheck(remaining + 500L);
                return;
            }
            try {
                reconcileMissing("boot_ready");
                runScan("boot_ready");
            } catch (Throwable t) {
                event("失败", "开机巡检异常: " + t);
            }
        };
        sHandler.postDelayed(sPostBootCheckRunnable, delayMs);
        log("post-boot check scheduled in " + delayMs + "ms");
    }

    private static void scheduleScan(long delayMs, String reason) {
        if (sScanPending) {
            return;
        }
        sScanPending = true;
        sHandler.postDelayed(() -> {
            sScanPending = false;
            try {
                runScan(reason);
            } catch (Throwable t) {
                event("失败", "扫描异常: " + t);
            }
        }, delayMs);
        log("scan scheduled in " + delayMs + "ms (" + reason + ")");
    }

    private static void runScan(String reason) {
        if (sContext == null) {
            return;
        }
        if (!isModuleEnabled()) {
            eventSkip("scan:disabled", "扫描", "跳过 (" + reason + ")：模块已停用");
            return;
        }
        boolean isManual = "manual scan".equals(reason) || "reset_circuit".equals(reason);
        if (!isManual) {
            long now = SystemClock.elapsedRealtime();
            long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
            if (remainingBoot > 0L) {
                eventSkip("scan:boot-grace", "扫描", "跳过扫描 (" + reason + ")：开机冷却期内");
                schedulePostBootCheck(remainingBoot + 1000L);
                return;
            }
        }
        String managed = readConfig(KEY_SERVICES);
        if (managed == null || managed.isEmpty()) {
            eventSkip("scan:managed-empty", "扫描", "跳过 (" + reason + ")：锁定列表为空");
            return;
        }
        String enabled = readEnabled();
        if (enabled == null) {
            eventSkip("scan:no-enabled", sLastEnabledReadError != null ? "失败" : "扫描",
                    "扫描跳过 (" + reason + ")：" + enabledReadSkipReason());
            return;
        }
        if (enabled.isEmpty()) {
            // 设置项存在但为空：没有任何已启用服务，无可扫描对象
            if ("manual scan".equals(reason)) {
                event("扫描", "扫描完成 (" + reason + ")，发现 0 个异常服务",
                        "action=scan_done reason=" + reason + " unbound=0");
            }
            return;
        }
        if (!hasConnectionState()) {
            eventSkip("scan:no-conn", "失败", "扫描跳过 (" + reason + ")：没有可用的连接状态");
            return;
        }

        int candidates = 0;
        for (String raw : managed.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(raw);
            if (component == null) {
                continue;
            }
            String key = component.flattenToString();
            if (!containsComponent(enabled, component)) {
                // 已关闭的服务不属于异常崩溃，无需打印跳过日志
                continue;
            }
            Object boundConnection = findBoundConnection(component);
            if (boundConnection != null) {
                clearCrashedMarker(boundConnection, component);
                continue;
            }
            if (!sBoundCheckAvailable) {
                eventSkip("scan:no-bound-check", "失败",
                        "扫描中止 (" + reason + ")：无法读取绑定状态: " + key);
                return;
            }
            candidates++;
            event("检测", "扫描发现未绑定服务: " + key,
                    "action=scan_hit reason=" + reason + " lastConn=" + connId(sConnections.get(key))
                            + " crashedMarker=" + getCrashedMarker(component));
            scheduleCheck(component, key, SETTLE_MS);
        }
        if (candidates > 0 || "manual scan".equals(reason)) {
            event("扫描", "扫描完成 (" + reason + ")，发现 " + candidates + " 个异常服务",
                    "action=scan_done reason=" + reason + " unbound=" + candidates);
        }
    }

    private static void onEnabledListChanged() {
        reconcileMissing("enabled change");
    }

    private static void reconcileMissing(String reason) {
        if (sContext == null) {
            return;
        }
        if (!isModuleEnabled()) {
            eventSkip("reconcile:disabled", "保活", "跳过补回 (" + reason + ")：模块已停用");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
        if (remainingBoot > 0L) {
            eventSkip("reconcile:boot-grace", "保活", "跳过补回 (" + reason + ")：开机保护期内");
            schedulePostBootCheck(remainingBoot + 1000L);
            return;
        }
        String managed = readConfig(KEY_SERVICES);
        String enabled = readEnabled();
        if (enabled == null) {
            eventSkip("reconcile:no-enabled", sLastEnabledReadError != null ? "失败" : "保活",
                    "补回跳过 (" + reason + ")：" + enabledReadSkipReason());
            return;
        }
        if (managed == null || managed.isEmpty()) {
            log("reconcile skipped (managed empty): " + reason);
            return;
        }

        String desired = enabled;
        StringBuilder added = new StringBuilder();
        Set<String> newlyAddedKeys = new LinkedHashSet<>();
        int count = 0;
        for (String raw : managed.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(raw);
            if (component == null) {
                continue;
            }
            String key = component.flattenToString();
            if (containsComponent(desired, component)) {
                continue;
            }
            Long own = sSelfRemovedUntil.get(key);
            if (own != null) {
                if (now < own) {
                    continue;
                }
                sSelfRemovedUntil.remove(key);
            }
            Long manual = sManualHiddenUntil.get(key);
            if (manual != null) {
                if (now < manual) {
                    continue;
                }
                sManualHiddenUntil.remove(key);
            }
            if (!isPackageInstalled(component.getPackageName())) {
                event("保活", "跳过补回 (" + reason + ")：应用已卸载: " + key);
                continue;
            }
            desired = addComponent(desired, component);
            if (added.length() > 0) {
                added.append(' ');
            }
            added.append(key);
            newlyAddedKeys.add(key);
            count++;
        }
        if (count == 0) {
            return;
        }
        if (isDryRun()) {
            event("保活", "演练模式：将补回 " + count + " 个: " + added + "（不实际写入）");
            return;
        }

        if (now - sLastReconcileWriteAt < CLOSE_MIN_INTERVAL_MS) {
            if (!sReconcileRetryPending) {
                sReconcileRetryPending = true;
                long delay = CLOSE_MIN_INTERVAL_MS - (now - sLastReconcileWriteAt);
                sHandler.postDelayed(() -> {
                    sReconcileRetryPending = false;
                    try {
                        reconcileMissing(reason + " retry");
                    } catch (Throwable t) {
                        event("失败", "补回重试异常: " + t);
                    }
                }, delay);
            }
            return;
        }

        sLastReconcileWriteAt = now;
        event("保活", "补回已关闭服务 (" + count + "): " + added,
                "action=reconcile reason=" + reason + " count=" + count);
        if (!writeEnabled(desired)) {
            event("失败", "补回写入失败: " + added, "action=reconcile_write_fail reason=" + reason);
            return;
        }
        sPendingVerifyKeys.addAll(newlyAddedKeys);
        scheduleBindVerify(reason);
    }

    /** 补回后等待 AMS 绑定，仅校验本次实际补回的服务是否起来了，避免误伤其他正常服务 */
    private static void scheduleBindVerify(String reason) {
        if (sBindVerifyPending) {
            return;
        }
        sBindVerifyPending = true;
        sHandler.postDelayed(() -> {
            sBindVerifyPending = false;
            try {
                verifyBindings(reason);
            } catch (Throwable t) {
                log("bind verify error: " + t);
            }
        }, BIND_VERIFY_DELAY_MS);
    }

    private static void verifyBindings(String reason) {
        if (sPendingVerifyKeys.isEmpty()) {
            return;
        }
        Set<String> targets = new LinkedHashSet<>(sPendingVerifyKeys);
        sPendingVerifyKeys.clear();
        if (sContext == null) {
            return;
        }
        if (!isModuleEnabled()) {
            eventSkip("verify:disabled", "保活", "跳过补回后校验 (" + reason + ")：模块已停用");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
        if (remainingBoot > 0L) {
            eventSkip("verify:boot-grace", "保活", "跳过补回后校验 (" + reason + ")：开机冷却期内");
            schedulePostBootCheck(remainingBoot + 1000L);
            return;
        }
        if (!hasConnectionState()) {
            eventSkip("verify:no-conn", "失败", "补回后校验跳过 (" + reason + ")：没有可用的连接状态");
            return;
        }
        String enabled = readEnabled();
        if (enabled == null) {
            eventSkip("verify:no-enabled", sLastEnabledReadError != null ? "失败" : "保活",
                    "补回后校验跳过 (" + reason + ")：" + enabledReadSkipReason());
            return;
        }
        int unbound = 0;
        for (String raw : targets) {
            ComponentName component = ComponentName.unflattenFromString(raw);
            if (component == null || !isManaged(component)) {
                continue;
            }
            String key = component.flattenToString();
            if (!containsComponent(enabled, component)) {
                continue; // 已被关闭，交给对账路径
            }
            Long own = sSelfRemovedUntil.get(key);
            if (own != null && now < own) {
                continue; // 崩溃修复自己的临时移除
            }
            Long manual = sManualHiddenUntil.get(key);
            if (manual != null && now < manual) {
                continue; // 用户手动关闭后的短暂豁免
            }
            if (findBoundConnection(component) != null) {
                continue;
            }
            if (!sBoundCheckAvailable) {
                eventSkip("verify:no-bound-check", "失败",
                        "补回后校验中止 (" + reason + ")：无法读取绑定状态: " + key);
                return;
            }
            unbound++;
            scheduleCheck(component, key, SETTLE_MS);
        }
        if (unbound > 0) {
            event("检测", "补回后仍未绑定 " + unbound + " 个，准备修复",
                    "action=verify_bindings reason=" + reason + " unbound=" + unbound
                            + " checked=" + targets.size());
        }
    }

    private static void scheduleReconcile(String reason, long delayMs) {
        if (sReconcilePending) {
            return;
        }
        sReconcilePending = true;
        sHandler.postDelayed(() -> {
            sReconcilePending = false;
            try {
                reconcileMissing(reason);
            } catch (Throwable t) {
                event("失败", "关闭巡检异常: " + t);
            }
        }, delayMs);
        log("reconcile scheduled in " + delayMs + "ms (" + reason + ")");
    }

    private static boolean isPackageInstalled(String pkg) {
        Context context = sContext;
        if (context == null) {
            return false;
        }
        try {
            context.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Context getSystemContext() {
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
            log("getSystemContext error: " + t);
        }
        return null;
    }

    private static synchronized void ensureCommandReceiver() {
        if (sCommandReceiver != null) {
            return;
        }
        Context ctx = sContext;
        if (ctx == null) {
            ctx = getSystemContext();
        }
        if (ctx == null) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (!ACTION_SERVICE_CMD.equals(action)) return;
                    String payload = intent.getStringExtra(EXTRA_PAYLOAD);
                    if (payload == null || payload.isEmpty()) return;
                    log("received broadcast cmd: " + payload);
                    Handler handler = sHandler;
                    if (handler != null) {
                        handler.post(() -> {
                            try {
                                applyServiceCommand(payload);
                            } catch (Throwable t) {
                                event("失败", "广播指令执行异常: " + t);
                            }
                        });
                    } else {
                        applyServiceCommand(payload);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_SERVICE_CMD);
            boolean registered = false;
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    ctx.registerReceiver(receiver, filter, PERMISSION_SEND_CMD, sHandler, Context.RECEIVER_EXPORTED);
                    registered = true;
                } catch (Throwable t) {
                    log("registerReceiver with permission failed: " + t + ", retrying without permission");
                }
                if (!registered) {
                    try {
                        ctx.registerReceiver(receiver, filter, null, sHandler, Context.RECEIVER_EXPORTED);
                        registered = true;
                    } catch (Throwable t2) {
                        log("registerReceiver without permission failed: " + t2);
                    }
                }
            } else {
                try {
                    ctx.registerReceiver(receiver, filter, PERMISSION_SEND_CMD, sHandler);
                    registered = true;
                } catch (Throwable t) {
                    log("registerReceiver with permission failed: " + t + ", retrying without permission");
                }
                if (!registered) {
                    try {
                        ctx.registerReceiver(receiver, filter, null, sHandler);
                        registered = true;
                    } catch (Throwable t2) {
                        log("registerReceiver without permission failed: " + t2);
                    }
                }
            }
            if (registered) {
                sCommandReceiver = receiver;
                log("[配置] 系统广播指令监听已就绪 (方案A)");
            }
        } catch (Throwable t) {
            event("失败", "注册广播接收器异常: " + t);
        }
    }

    private static void scheduleReceiverInit() {
        if (sContext == null) {
            sContext = getSystemContext();
        }
        if (sContext != null) {
            ensureCommandReceiver();
            ensureObserver();
        }
        if ((sCommandReceiver == null || sObserver == null) && sContextRetryCount < MAX_INIT_RETRY) {
            sContextRetryCount++;
            if (sHandler != null) {
                sHandler.postDelayed(AutoRepairEntry::scheduleReceiverInit, INIT_RETRY_DELAY_MS);
            }
        }
    }

    private static void notifyCommandAck() {
        Context context = sContext;
        if (context == null) {
            context = getSystemContext();
        }
        if (context == null) {
            return;
        }
        try {
            Settings.Secure.putString(context.getContentResolver(), KEY_CMD_ACK,
                    String.valueOf(SystemClock.elapsedRealtimeNanos()));
        } catch (Throwable t) {
            log("notifyCommandAck failed: " + t);
        }
    }

    private static void applyServiceCommand(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        int hash = value.lastIndexOf('#');
        String body = hash > 0 ? value.substring(0, hash) : value;
        writeHeartbeatOnce("ping".equals(body) ? "ping唤醒" : "cmd:" + body);

        if ("ping".equals(body)) {
            notifyCommandAck();
            return;
        }

        if ("dump_info".equals(body)) {
            writeModuleDump();
            notifyCommandAck();
            return;
        }

        if ("scan".equals(body)) {
            scheduleScan(0L, "manual scan");
            event("扫描", "收到手动扫描指令");
            notifyCommandAck();
            return;
        }

        if ("reset_circuit".equals(body)) {
            int count = sBlockedUntil.size();
            sBlockedUntil.clear();
            sAttempts.clear();
            sLastAttemptAt.clear();
            sLastPackageKillAt.clear();
            sCrashHistory.clear();
            event("配置", "已手动恢复暂停修复的服务 (清除 " + count + " 个暂停记录)");
            scheduleScan(0L, "reset_circuit");
            notifyCommandAck();
            return;
        }

        int colon = body.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String action = body.substring(0, colon);
        String component = body.substring(colon + 1);

        if ("enable_module".equals(action)) {
            boolean enable = "1".equals(component);
            sModuleEnabledCache = enable ? "1" : "0";
            if (sContext != null) {
                try {
                    Settings.Secure.putString(sContext.getContentResolver(), KEY_ENABLE, sModuleEnabledCache);
                } catch (Throwable ignored) {
                }
            }
            if (enable) {
                scheduleReconcile("enable_module", RECONCILE_CONFIG_DELAY_MS);
                scheduleScan(SCAN_CONFIG_DELAY_MS, "enable_module");
                event("配置", "模块已启用 (广播指令)");
            } else {
                event("配置", "模块已停用 (广播指令)");
            }
            notifyCommandAck();
            return;
        }

        if ("dry_run".equals(action)) {
            sDryRunCache = "1".equals(component) ? "1" : "0";
            if (sContext != null) {
                try {
                    Settings.Secure.putString(sContext.getContentResolver(), KEY_DRY_RUN, sDryRunCache);
                } catch (Throwable ignored) {
                }
            }
            notifyCommandAck();
            return;
        }

        if ("debug".equals(action)) {
            sDebugCache = "1".equals(component) ? "1" : "0";
            if (sContext != null) {
                try {
                    Settings.Secure.putString(sContext.getContentResolver(), KEY_DEBUG, sDebugCache);
                } catch (Throwable ignored) {
                }
            }
            notifyCommandAck();
            return;
        }

        if ("sync_services".equals(action)) {
            String prev = sManagedServicesCache != null ? sManagedServicesCache : readConfig(KEY_SERVICES);
            boolean changed = !component.equals(prev);
            sManagedServicesCache = component;
            sManualHiddenUntil.clear();
            if (sContext != null) {
                try {
                    Settings.Secure.putString(sContext.getContentResolver(), KEY_SERVICES, component);
                } catch (Throwable ignored) {
                }
            }
            scheduleReconcile("sync_services cmd", RECONCILE_CONFIG_DELAY_MS);
            scheduleScan(SCAN_CONFIG_DELAY_MS, "sync_services cmd");
            if (changed) {
                event("配置", "模块已生效最新锁定列表: " + component);
            } else {
                log("sync_services unchanged: " + component);
            }
            notifyCommandAck();
            return;
        }

        if ("sync_circuit".equals(action)) {
            String prev = sCircuitConfigCache != null ? sCircuitConfigCache : readConfig(KEY_CIRCUIT_CONFIG);
            boolean changed = !component.equals(prev);
            sCircuitConfigCache = component;
            if (sContext != null) {
                try {
                    Settings.Secure.putString(sContext.getContentResolver(), KEY_CIRCUIT_CONFIG, component);
                } catch (Throwable ignored) {
                }
            }
            if (changed) {
                int[] parsed = parseCircuitConfig(component);
                event("配置", "模块已生效崩溃保护配置: 连续崩溃阈值=" + parsed[0] + "次, 暂停修复="
                        + (parsed[1] == 0 ? "关闭" : (parsed[1] + "分钟")) + ", 最大重试=" + parsed[2] + "次");
            }
            notifyCommandAck();
            return;
        }

        if ("sync_fixmode".equals(action)) {
            int sep = component.indexOf('#');
            String modeStr = sep >= 0 ? component.substring(0, sep) : component;
            String disabled = sep >= 0 ? component.substring(sep + 1) : "";
            sFixModeCache = modeStr;
            sFixDisabledCache = disabled;
            if (sContext != null) {
                try {
                    Settings.Secure.putString(sContext.getContentResolver(), KEY_FIX_MODE, modeStr);
                    Settings.Secure.putString(sContext.getContentResolver(), KEY_FIX_DISABLED, disabled);
                } catch (Throwable ignored) {
                }
            }
            event("配置", "模块已生效强杀配置: fixmode=" + modeStr);
            notifyCommandAck();
            return;
        }

        if ("debug_kill".equals(action)) {
            int slash = component.indexOf('/');
            String pkg = slash > 0 ? component.substring(0, slash) : component;
            String killMeta = killAppProcesses(pkg);
            event("调试", "已触发进程强杀: " + component,
                    "action=debug_kill pkg=" + pkg + " " + killMeta);
            notifyCommandAck();
            return;
        }

        ComponentName target = ComponentName.unflattenFromString(component);
        if (target == null) {
            return;
        }
        boolean enable = "enable".equals(action);
        if (!enable && !"disable".equals(action)) {
            return;
        }
        String targetKey = target.flattenToString();
        if (!enable) {
            // 用户手动关闭：给 KEY_SERVICES 的跨进程同步留出短暂窗口
            sManualHiddenUntil.put(targetKey, SystemClock.elapsedRealtime() + 5000L);
        } else {
            sManualHiddenUntil.remove(targetKey);
        }
        String current = readEnabled();
        if (current == null) {
            if (sLastEnabledReadError != null) {
                // 读取异常时不写入，避免用空列表覆盖掉已有服务
                event("失败", "手动" + (enable ? "开启" : "关闭") + "失败："
                        + enabledReadSkipReason() + ": " + component);
                notifyCommandAck();
                return;
            }
            // 设置项不存在（全新设备，或 ROM 在关闭无障碍总开关后清空该值）等价于空列表。
            // 旧逻辑在此直接返回，导致首次开启服务被静默跳过，管理器侧只能看到“模块响应超时”
            if (enable) {
                event("配置", "启用列表设置项不存在，按空列表处理");
            }
            current = "";
        }
        boolean present = containsComponent(current, target);
        if (enable == present) {
            log("manual command no-op: " + component);
            notifyCommandAck();
            return;
        }
        String updated = enable ? addComponent(current, target) : removeComponent(current, target);
        if (writeEnabled(updated)) {
            if (enable) {
                ensureAccessibilitySwitchOn();
            }
            event("保活", "手动" + (enable ? "开启" : "关闭") + ": " + component);
        } else {
            event("失败", "手动" + (enable ? "开启" : "关闭") + "写入失败: " + component);
        }
        notifyCommandAck();
    }

    /** 开启单个服务时确保无障碍总开关为开启状态，否则只写启用列表不会真正绑定服务 */
    private static void ensureAccessibilitySwitchOn() {
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
                log("enable accessibility switch failed");
                return;
            }
            event("保活", "无障碍总开关未开启，已代为开启");
        } catch (Throwable t) {
            log("enable accessibility switch error: " + t);
        }
    }

    /** 采集 system_server 视角的诊断快照，供管理器在无 DUMP/Root/Shizuku 权限时导出日志 */
    private static void writeModuleDump() {
        Context context = sContext;
        if (context == null) {
            context = getSystemContext();
        }
        if (context == null) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        try {
            String enabled = Settings.Secure.getString(resolver, SETTING_ENABLED);
            int accessibilityEnabled;
            try {
                accessibilityEnabled = Settings.Secure.getInt(resolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED);
            } catch (Throwable t) {
                accessibilityEnabled = -1;
            }
            String heartbeat = readSecure(KEY_LOADED);
            String managed = readConfig(KEY_SERVICES);
            String circuit = readConfig(KEY_CIRCUIT_CONFIG);

            StringBuilder sb = new StringBuilder();
            sb.append("采集时间: ").append(System.currentTimeMillis()).append('\n');
            sb.append("模块心跳: ").append(heartbeat == null ? "不存在" : heartbeat)
                    .append("  bootCount=")
                    .append(Settings.Global.getInt(resolver, SETTING_BOOT_COUNT, -1))
                    .append("  热重载次数=").append(sHotReloadCount);
            if (sLastHotReloadAt > 0L) {
                sb.append(" (最近=").append(sLastHotReloadAt).append(')');
            }
            sb.append('\n');
            sb.append("无障碍总开关: ").append(accessibilityEnabled < 0 ? "不存在" : accessibilityEnabled)
                    .append("  启用列表: ").append(enabled == null ? "不存在"
                            : (enabled.isEmpty() ? "空" : enabled))
                    .append('\n');
            sb.append("绑定状态可读: ").append(hasConnectionState())
                    .append("  已记录连接: ").append(sConnections.size()).append('\n');
            sb.append("模块启用: ").append(isModuleEnabled())
                    .append("  演练模式: ").append(isDryRun())
                    .append("  强杀模式: ").append("1".equals(readConfig(KEY_FIX_MODE)))
                    .append("  崩溃保护: ").append(circuit == null ? "默认" : circuit).append('\n');
            sb.append("锁定列表: ").append(managed == null || managed.isEmpty() ? "空" : managed)
                    .append('\n');
            if (managed != null && !managed.isEmpty()) {
                long now = SystemClock.elapsedRealtime();
                for (String raw : managed.split(":")) {
                    ComponentName component = ComponentName.unflattenFromString(raw);
                    if (component == null) {
                        continue;
                    }
                    String key = component.flattenToString();
                    sb.append("[服务] ").append(key)
                            .append("  在启用列表=").append(containsComponent(enabled, component))
                            .append("  已绑定=").append(findBoundConnection(component) != null)
                            .append("  崩溃标记=").append(getCrashedMarker(component));
                    Integer attempts = sAttempts.get(key);
                    sb.append("  重试=").append(attempts == null ? 0 : attempts)
                            .append('/').append(getMaxAttempts());
                    Long blockedUntil = sBlockedUntil.get(key);
                    long remain = blockedUntil == null ? 0L : Math.max(0L, blockedUntil - now) / 1000L;
                    sb.append("  暂停修复剩余=").append(remain).append("s\n");
                }
            }
            synchronized (sEventLock) {
                if (!sRawTraceLines.isEmpty()) {
                    sb.append("\n--- 模块底层原始轨迹 (最近 ")
                            .append(sRawTraceLines.size()).append(" 条) ---\n");
                    for (String traceLine : sRawTraceLines) {
                        sb.append(traceLine).append('\n');
                    }
                }
            }
            Settings.Secure.putString(resolver, KEY_DUMP, sb.toString());
        } catch (Throwable t) {
            log("dump info failed: " + t);
            try {
                Settings.Secure.putString(resolver, KEY_DUMP, "采集失败: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 读取连接上的崩溃标记；没有连接记录时返回“无记录” */
    private static String getCrashedMarker(ComponentName component) {
        Object connection = sConnections.get(component.flattenToString());
        if (connection == null) {
            return "无记录";
        }
        Object info = getFieldValue(connection, "mAccessibilityServiceInfo");
        Object crashed = info == null ? null : getFieldValue(info, "crashed");
        return crashed instanceof Boolean ? String.valueOf(crashed) : "未知";
    }

    private static void scheduleDebugKill() {
        sHandler.postDelayed(() -> {
            try {
                runDebugKill();
            } catch (Throwable t) {
                log("debug kill error: " + t);
            }
        }, 500L);
    }

    private static void runDebugKill() {
        if (sContext == null) {
            return;
        }
        if (!"1".equals(readConfig(KEY_DEBUG))) {
            log("debug kill skipped (debug disabled)");
            return;
        }
        String value = readConfig(KEY_DEBUG_KILL);
        if (value == null || value.isEmpty()) {
            return;
        }
        int hash = value.indexOf('#');
        String target = hash >= 0 ? value.substring(0, hash) : value;
        int slash = target.indexOf('/');
        String pkg = slash > 0 ? target.substring(0, slash) : target;
        if (pkg.isEmpty()) {
            return;
        }

        final int uid;
        try {
            uid = sContext.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            log("debug kill: package not found: " + pkg);
            return;
        }

        Set<Integer> pids = new LinkedHashSet<>();
        try {
            ActivityManager am =
                    (ActivityManager) sContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> processes =
                        am.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo info : processes) {
                        if (info != null && info.uid == uid) {
                            pids.add(info.pid);
                        }
                    }
                }
                List<ActivityManager.RunningServiceInfo> services =
                        am.getRunningServices(Integer.MAX_VALUE);
                if (services != null) {
                    for (ActivityManager.RunningServiceInfo info : services) {
                        if (info != null && info.uid == uid && info.pid > 0) {
                            pids.add(info.pid);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("debug kill: enumerate failed: " + t);
        }

        if (pids.isEmpty()) {
            log("debug kill: no pid found for " + pkg);
            return;
        }
        log("debug kill: " + pkg + " uid=" + uid + " pids=" + pids);
        killAppProcesses(pkg);
    }

    private static String killAppProcesses(String pkg) {
        if (sContext == null || pkg == null || pkg.isEmpty()) {
            return "uid=-1 killedPids=[]";
        }
        final int uid;
        try {
            uid = sContext.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            log("killAppProcesses: package not found: " + pkg);
            return "uid=not_found killedPids=[]";
        }

        Set<Integer> pids = new LinkedHashSet<>();
        try {
            ActivityManager am =
                    (ActivityManager) sContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> processes =
                        am.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo info : processes) {
                        if (info != null && info.uid == uid) {
                            pids.add(info.pid);
                        }
                    }
                }
                List<ActivityManager.RunningServiceInfo> services =
                        am.getRunningServices(Integer.MAX_VALUE);
                if (services != null) {
                    for (ActivityManager.RunningServiceInfo info : services) {
                        if (info != null && info.uid == uid && info.pid > 0) {
                            pids.add(info.pid);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("killAppProcesses enumerate failed: " + t);
        }

        Set<Integer> killed = new LinkedHashSet<>();
        for (int pid : pids) {
            try {
                Process.killProcess(pid);
                killed.add(pid);
                log("killAppProcesses: killed pid " + pid + " for " + pkg);
            } catch (Throwable t) {
                log("killAppProcesses: kill pid " + pid + " failed: " + t);
            }
        }
        return "uid=" + uid + " killedPids=" + killed;
    }

    private static void onBinderDied(ComponentName component, boolean crashed,
            boolean hasInterface, Object connection) {
        String key = component.flattenToString();
        long now = SystemClock.elapsedRealtime();

        Long last = sLastDied.get(key);
        if (last != null && now - last < DEDUPE_MS) {
            return;
        }
        sLastDied.put(key, now);

        if (!isManaged(component)) {
            eventNonManaged(key, "非保活服务挂掉(忽略) crashed=" + crashed);
            return;
        }
        if (!crashed) {
            log("[监听] 受管服务正常断开(忽略非崩溃): " + key);
            return;
        }
        if (sContext == null) {
            event("失败", "缺少系统 Context，忽略: " + key);
            return;
        }

        long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
        if (remainingBoot > 0L) {
            eventSkip("died:boot-grace:" + key, "检测", "忽略（开机冷却期内）: " + key);
            schedulePostBootCheck(remainingBoot + 1000L);
            return;
        }

        Long suppress = sSuppressUntil.get(key);
        if (suppress != null && now < suppress) {
            eventSkip("died:suppress:" + key, "检测", "忽略（修复抑制窗口）: " + key);
            return;
        }

        // 若新连接已就绪（当前已存在有效绑定），说明收到的是旧连接退役时的延迟 binderDied，直接清理标记并忽略
        Object currentBound = findBoundConnection(component);
        if (currentBound != null) {
            clearCrashedMarker(currentBound, component);
            log("[监听] 忽略旧连接断开事件(当前新连接已正常绑定): " + key
                    + " diedConn=" + connId(connection) + " activeConn=" + connId(currentBound));
            return;
        }

        Long blocked = sBlockedUntil.get(key);
        if (blocked != null) {
            if (!RepairPolicy.isBlockExpired(blocked, now)) {
                long delay = RepairPolicy.getRemainingBlockDelayMs(blocked, now);
                scheduleCheck(component, key, delay);
                eventSkip("died:blocked:" + key, "检测", "忽略（暂停修复中）: " + key);
                return;
            }
            sBlockedUntil.remove(key);
            sAttempts.remove(key);
            sLastAttemptAt.remove(key);
            sCrashHistory.remove(key);
        }

        Long lastAttempt = sLastAttemptAt.get(key);
        if (RepairPolicy.shouldResetAttempts(lastAttempt, now, RESET_AFTER_MS)) {
            sAttempts.remove(key);
            sCrashHistory.remove(key);
        }

        boolean enabled = isModuleEnabled();
        event("检测", "崩溃: " + key,
                "hook=binderDied conn=" + connId(connection) + " crashed=" + crashed
                        + " iface=" + hasInterface + " enabled=" + enabled
                        + " dryRun=" + isDryRun());
        if (!enabled) {
            return;
        }

        recordCrash(component, key, now);
        Long afterRecord = sBlockedUntil.get(key);
        if (afterRecord != null && now < afterRecord) {
            long delay = RepairPolicy.getRemainingBlockDelayMs(afterRecord, now);
            scheduleCheck(component, key, delay);
            event("失败", "连续崩溃，暂停修复跳过（到期将自动复查）: " + key,
                    "action=circuit_pause_skip recheckInMs=" + delay);
            return;
        }
        if (sAttempts.getOrDefault(key, 0) >= getMaxAttempts()) {
            long delay = RepairPolicy.getRemainingResetDelayMs(
                    sLastAttemptAt.get(key), now, RESET_AFTER_MS);
            scheduleCheck(component, key, delay);
            event("失败", "重试次数已用完，等待冷却后自动复查: " + key,
                    "action=max_attempts_wait recheckInMs=" + delay);
            return;
        }

        scheduleCheck(component, key, SETTLE_MS);
    }

    private static void onInitializeService(ComponentName component, boolean crashed,
            boolean hasInterface, Object connection) {
        String key = component.flattenToString();
        long now = SystemClock.elapsedRealtime();
        Long lastInit = sLastInit.get(key);
        if (lastInit != null && now - lastInit < DEDUPE_MS) {
            return;
        }
        sLastInit.put(key, now);
        boolean managed = isManaged(component);

        if (!crashed && hasInterface) {
            Runnable pendingCheck = sPendingCheck.remove(key);
            boolean cancelled = pendingCheck != null;
            if (pendingCheck != null && sHandler != null) {
                sHandler.removeCallbacks(pendingCheck);
            }
            Runnable verify = sVerifyRun.remove(key);
            if (verify != null && sHandler != null) {
                sHandler.removeCallbacks(verify);
            }
            Integer attempts = sAttempts.remove(key);
            boolean wasRepairing = (attempts != null && attempts > 0) || key.equals(sRepairKey);
            if (wasRepairing) {
                // 保留短暂抑制期，防止强杀修复后旧连接的延迟 binderDied 穿透造成假崩溃报警
                sSuppressUntil.put(key, now + SETTLE_MS);
            } else {
                sSuppressUntil.remove(key);
                sLastAttemptAt.remove(key);
            }
            finishRepair(key);
            clearCrashedMarker(connection, component);
            if (managed) {
                String baseMeta = "hook=initializeService conn=" + connId(connection)
                        + " crashed=" + crashed + " iface=" + hasInterface;
                if (cancelled) {
                    event("保活", "服务已恢复就绪（无需额外重启）: " + key,
                            baseMeta + " state=recovered_before_check");
                } else if (wasRepairing) {
                    long costMs = sRepairStartedAt > 0L ? Math.max(0L, now - sRepairStartedAt) : -1L;
                    event("修复", "恢复成功: " + key,
                            baseMeta + " state=repair_ok cost=" + costMs + "ms");
                } else {
                    event("服务", "服务已就绪: " + key,
                            baseMeta + " state=connected");
                }
            } else {
                eventNonManaged(key, "非保活服务连接(忽略)");
            }
            return;
        }

        if (!managed) {
            eventNonManaged(key, "非保活服务重绑失败(忽略) crashed=" + crashed
                    + ", iface=" + hasInterface);
            return;
        }
        event("检测", "重绑失败: " + key,
                "hook=initializeService conn=" + connId(connection)
                        + " crashed=" + crashed + " iface=" + hasInterface);
        if (sContext != null && isModuleEnabled() && !sPendingCheck.containsKey(key)) {
            scheduleCheck(component, key, SETTLE_MS);
        }
    }

    private static void scheduleCheck(ComponentName component, String key, long delayMs) {
        if (sPendingCheck.containsKey(key)) {
            return;
        }
        Runnable runnable = () -> {
            sPendingCheck.remove(key);
            try {
                runCheck(component, key);
            } catch (Throwable t) {
                event("失败", "检查异常: " + t);
            }
        };
        sPendingCheck.put(key, runnable);
        sHandler.postDelayed(runnable, delayMs);
        log("check scheduled in " + delayMs + "ms: " + key);
    }

    private static void runCheck(ComponentName component, String key) {
        long now = SystemClock.elapsedRealtime();

        long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
        if (remainingBoot > 0L) {
            eventSkip("check:boot-grace:" + key, "检测", "跳过检查（开机冷却期内）: " + key);
            schedulePostBootCheck(remainingBoot + 1000L);
            return;
        }

        Long suppress = sSuppressUntil.get(key);
        if (suppress != null && now < suppress) {
            eventSkip("check:suppress:" + key, "检测", "跳过检查：修复抑制窗口内: " + key);
            return;
        }
        if (!isManaged(component)) {
            event("检测", "无需修复（已不在锁定列表）: " + key);
            return;
        }
        if (!isModuleEnabled()) {
            event("检测", "无需修复（模块已关闭）: " + key);
            return;
        }
        if (!containsComponent(readEnabled(), component)) {
            event("检测", "无需修复（服务已被关闭）: " + key);
            return;
        }
        Object boundConnection = findBoundConnection(component);
        if (boundConnection != null) {
            clearCrashedMarker(boundConnection, component);
            sAttempts.remove(key);
            sLastAttemptAt.remove(key);
            sSuppressUntil.remove(key);
            event("保活", "服务已恢复绑定（无需额外重启）: " + key);
            return;
        }
        if (!sBoundCheckAvailable) {
            event("失败", "无法确认绑定状态，跳过修复: " + key);
            return;
        }

        Long blocked = sBlockedUntil.get(key);
        if (blocked != null) {
            if (!RepairPolicy.isBlockExpired(blocked, now)) {
                long delay = RepairPolicy.getRemainingBlockDelayMs(blocked, now);
                scheduleCheck(component, key, delay);
                eventSkip("check:blocked:" + key, "失败", "跳过（暂停修复中，到期自动复查）: " + key);
                return;
            }
            sBlockedUntil.remove(key);
            sAttempts.remove(key);
            sLastAttemptAt.remove(key);
            sCrashHistory.remove(key);
            log("circuit breaker expired in runCheck, resuming repair: " + key);
        }

        Long last = sLastAttemptAt.get(key);
        if (RepairPolicy.shouldResetAttempts(last, now, RESET_AFTER_MS)) {
            sAttempts.remove(key);
            sCrashHistory.remove(key);
            last = null;
            log("retry attempts reset after cooldown window in runCheck: " + key);
        }

        int attempts = sAttempts.getOrDefault(key, 0);
        if (attempts >= getMaxAttempts()) {
            long delay = RepairPolicy.getRemainingResetDelayMs(last, now, RESET_AFTER_MS);
            scheduleCheck(component, key, delay);
            eventSkip("check:max-attempts:" + key, "失败",
                    "跳过（已达单轮重试上限，冷却后自动复查）: " + key);
            return;
        }
        if (last != null && now - last < RETRY_COOLDOWN_MS) {
            long remainCooldown = (RETRY_COOLDOWN_MS - (now - last)) + 200L;
            scheduleCheck(component, key, remainCooldown);
            log("skip check within retry cooldown, rescheduled in " + remainCooldown + "ms: " + key);
            return;
        }

        repair(component, key, attempts);
    }

    static boolean isSelfOrSystemPackage(String pkg, String contextPkg) {
        return RepairPolicy.isSelfOrSystemPackage(pkg, contextPkg);
    }

    static boolean shouldKillPackage(String pkg, String contextPkg, boolean fixMode,
            String disabledList, String componentKey, Long lastPkgKillAt, long now) {
        return RepairPolicy.shouldKillPackage(
                pkg, contextPkg, fixMode, disabledList, componentKey, lastPkgKillAt, now);
    }

    private static void suppressPackageServices(String pkg, long suppressUntilMs) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        String managed = readConfig(KEY_SERVICES);
        if (managed == null || managed.isEmpty()) {
            return;
        }
        for (String raw : managed.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(raw);
            if (cn != null && pkg.equals(cn.getPackageName())) {
                sSuppressUntil.put(cn.flattenToString(), suppressUntilMs);
            }
        }
    }

    private static void repair(ComponentName component, String key, int attempts) {
        long now = SystemClock.elapsedRealtime();
        if (isRepairInProgress(now)) {
            scheduleCheck(component, key, 1500L);
            return;
        }
        if (isDryRun()) {
            event("修复", "演练模式：将关开 " + key + "（不实际执行）");
            return;
        }

        String original = readEnabled();
        if (!containsComponent(original, component)) {
            event("检测", "无需修复（服务已被关闭）: " + key);
            return;
        }
        String removed = removeComponent(original, component);

        int maxAttempts = getMaxAttempts();
        sAttempts.put(key, attempts + 1);
        sLastAttemptAt.put(key, now);
        sSuppressUntil.put(key, now + SUPPRESS_MS);
        sSelfRemovedUntil.put(key, now + REPAIR_REMOVE_WINDOW_MS);
        sRepairStartedAt = now;
        sRepairInProgress = true;
        sRepairKey = key;

        event("修复", "第 " + (attempts + 1) + "/" + maxAttempts
                + " 次关开，先关闭: " + key,
                "action=repair_disable attempt=" + (attempts + 1) + "/" + maxAttempts);
        if (!writeEnabled(removed)) {
            event("失败", "关闭写入失败: " + key, "action=repair_disable_fail");
            finishRepair(key);
            block(component, key, "disable write failed");
            return;
        }

        boolean fixMode = sFixModeCache != null
                ? "1".equals(sFixModeCache)
                : "1".equals(readConfig(KEY_FIX_MODE));
        String disabled = sFixDisabledCache != null
                ? sFixDisabledCache
                : readConfig(KEY_FIX_DISABLED);
        String pkg = component.getPackageName();
        String contextPkg = sContext != null ? sContext.getPackageName() : null;
        Long lastPkgKillAt = sLastPackageKillAt.get(pkg);
        boolean allowKill = shouldKillPackage(
                pkg, contextPkg, fixMode, disabled, key, lastPkgKillAt, now);

        if (allowKill) {
            sLastPackageKillAt.put(pkg, now);
            suppressPackageServices(pkg, now + SUPPRESS_MS);
            String killMeta = killAppProcesses(pkg);
            event("修复", "强杀应用进程后重启: " + pkg,
                    "action=kill_package pkg=" + pkg + " " + killMeta);
        } else if (fixMode && lastPkgKillAt != null && now - lastPkgKillAt < PACKAGE_KILL_COOLDOWN_MS) {
            log("skip duplicate kill for package within cooldown: " + pkg);
        }

        sHandler.postDelayed(() -> {
            try {
                String current = readEnabled();
                if (containsComponent(current, component)) {
                    log("re-add skipped (already present): " + key);
                } else {
                    String readd = addComponent(current == null ? "" : current, component);
                    if (!writeEnabled(readd)) {
                        event("失败", "重新开启写入失败: " + key, "action=repair_enable_fail");
                        finishRepair(key);
                        block(component, key, "enable write failed");
                        return;
                    }
                    event("修复", "已重新开启: " + key,
                            "action=repair_enable toggleOffMs="
                                    + Math.max(0L, SystemClock.elapsedRealtime() - now));
                }
                scheduleVerify(component, key);
            } catch (Throwable t) {
                event("失败", "重新开启异常: " + t);
                finishRepair(key);
            }
        }, TOGGLE_OFF_MS);
    }

    private static void scheduleVerify(ComponentName component, String key) {
        Runnable old = sVerifyRun.remove(key);
        if (old != null) {
            sHandler.removeCallbacks(old);
        }
        Runnable runnable = () -> {
            sVerifyRun.remove(key);
            try {
                long verifyNow = SystemClock.elapsedRealtime();
                long costMs = sRepairStartedAt > 0L ? Math.max(0L, verifyNow - sRepairStartedAt) : -1L;
                Object boundConnection = findBoundConnection(component);
                if (boundConnection != null) {
                    clearCrashedMarker(boundConnection, component);
                    sAttempts.remove(key);
                    sSuppressUntil.put(key, verifyNow + SETTLE_MS);
                    event("修复", "恢复成功: " + key,
                            "action=verify_ok conn=" + connId(boundConnection) + " cost=" + costMs + "ms");
                } else if (!sBoundCheckAvailable) {
                    event("失败", "无法确认绑定状态，结束修复: " + key,
                            "action=verify_unknown cost=" + costMs + "ms");
                } else {
                    int attempts = sAttempts.getOrDefault(key, 0);
                    int maxAttempts = getMaxAttempts();
                    event("失败", "恢复失败 (" + attempts + "/" + maxAttempts
                            + "): " + key,
                            "action=verify_fail attempt=" + attempts + "/" + maxAttempts
                                    + " cost=" + costMs + "ms lastConn=" + connId(sConnections.get(key)));
                    if (attempts < maxAttempts) {
                        scheduleCheck(component, key, RETRY_COOLDOWN_MS);
                    } else {
                        long resetDelay = RepairPolicy.getRemainingResetDelayMs(
                                sLastAttemptAt.get(key), verifyNow, RESET_AFTER_MS);
                        event("失败", "已达单轮重试上限，将在 " + (RESET_AFTER_MS / 60000L)
                                + " 分钟后自动复查: " + key,
                                "action=verify_cooldown_scheduled recheckInMs=" + resetDelay);
                        scheduleCheck(component, key, resetDelay);
                    }
                }
            } catch (Throwable t) {
                event("失败", "恢复校验异常: " + t);
            } finally {
                finishRepair(key);
            }
        };
        sVerifyRun.put(key, runnable);
        sHandler.postDelayed(runnable, VERIFY_MS);
    }

    private static boolean isRepairInProgress(long now) {
        if (sRepairInProgress && now - sRepairStartedAt > REPAIR_STUCK_MS) {
            sRepairInProgress = false;
            sRepairKey = null;
            event("失败", "修复状态超时，已重置");
        }
        return sRepairInProgress;
    }

    private static void finishRepair(String key) {
        if (key == null || key.equals(sRepairKey)) {
            sRepairInProgress = false;
            sRepairKey = null;
        }
    }

    private static void block(ComponentName component, String key, String reason) {
        long cooldownMs = getUnstableCooldownMs();
        if (cooldownMs <= 0L) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long blockedUntil = now + cooldownMs;
        sBlockedUntil.put(key, blockedUntil);
        long delay = RepairPolicy.getRemainingBlockDelayMs(blockedUntil, now);
        if (component != null) {
            scheduleCheck(component, key, delay);
        }
        event("失败", "暂停修复 " + (cooldownMs / 60000L) + " 分钟（到期将自动复查, "
                + reason + "）: " + key,
                "action=block reason=" + reason + " recheckInMs=" + delay);
    }

    private static void recordCrash(ComponentName component, String key, long now) {
        Deque<Long> history = sCrashHistory.get(key);
        if (history == null) {
            history = new ArrayDeque<>();
            sCrashHistory.put(key, history);
        }
        while (!history.isEmpty() && now - history.peekFirst() > UNSTABLE_WINDOW_MS) {
            history.pollFirst();
        }
        history.addLast(now);
        long cooldownMs = getUnstableCooldownMs();
        if (cooldownMs > 0L && history.size() >= getUnstableThreshold()) {
            long blockedUntil = now + cooldownMs;
            sBlockedUntil.put(key, blockedUntil);
            long delay = RepairPolicy.getRemainingBlockDelayMs(blockedUntil, now);
            if (component != null) {
                scheduleCheck(component, key, delay);
            }
            event("失败", "连续 " + history.size() + " 次崩溃（"
                    + (UNSTABLE_WINDOW_MS / 1000) + " 秒内），暂停自动修复 "
                    + (cooldownMs / 60000L) + " 分钟（到期自动复查）: " + key,
                    "action=circuit_open crashes=" + history.size() + " recheckInMs=" + delay);
        }
    }

    /** 解析熔断与重试配置字符串 "threshold|cooldownMin|maxAttempts" */
    public static int[] parseCircuitConfig(String raw) {
        int threshold = DEFAULT_UNSTABLE_THRESHOLD;
        int cooldownMin = DEFAULT_UNSTABLE_COOLDOWN_MIN;
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

    private static int getUnstableThreshold() {
        return parseCircuitConfig(readConfig(KEY_CIRCUIT_CONFIG))[0];
    }

    private static long getUnstableCooldownMs() {
        return parseCircuitConfig(readConfig(KEY_CIRCUIT_CONFIG))[1] * 60_000L;
    }

    private static int getMaxAttempts() {
        return parseCircuitConfig(readConfig(KEY_CIRCUIT_CONFIG))[2];
    }

    private static boolean isManaged(ComponentName target) {
        String list = readConfig(KEY_SERVICES);
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (String raw : list.split(":")) {
            if (matches(raw, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isModuleEnabled() {
        return "1".equals(readConfig(KEY_ENABLE));
    }

    private static boolean isDryRun() {
        String value = readConfig(KEY_DRY_RUN);
        return "1".equals(value);
    }

    private static Object findBoundConnection(ComponentName target) {
        sBoundCheckAvailable = false;
        Object known = sConnections.get(target.flattenToString());
        if (known != null) {
            sBoundCheckAvailable = true;
            if (isConnectionBound(known)) {
                return known;
            }
        }
        for (Object connection : sConnections.values()) {
            Object userState = getUserState(connection);
            if (userState == null) {
                continue;
            }
            try {
                Object boundServices = getFieldValue(userState, "mBoundServices");
                if (boundServices instanceof Iterable) {
                    sBoundCheckAvailable = true;
                    for (Object item : (Iterable<?>) boundServices) {
                        if (item == null) {
                            continue;
                        }
                        ComponentName name = getComponentName(item);
                        if (target.equals(name) && isConnectionBound(item)) {
                            return item;
                        }
                    }
                }
                Object map = getFieldValue(userState, "mComponentNameToServiceMap");
                if (map instanceof Map) {
                    sBoundCheckAvailable = true;
                    Object item = ((Map<?, ?>) map).get(target);
                    if (item != null && isConnectionBound(item)) {
                        return item;
                    }
                }
            } catch (Throwable t) {
                if (!sBoundCheckErrorLogged) {
                    sBoundCheckErrorLogged = true;
                    event("失败", "绑定状态读取失败: " + t);
                }
            }
            break;
        }
        return null;
    }

    private static boolean isConnectionBound(Object connection) {
        if (connection == null) {
            return false;
        }
        try {
            return getFieldValue(connection, "mServiceInterface") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object getUserState(Object connection) {
        if (connection == null) {
            return null;
        }
        try {
            Object ref = getFieldValue(connection, "mUserStateWeakReference");
            if (ref instanceof WeakReference) {
                return ((WeakReference<?>) ref).get();
            }
            return ref;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasConnectionState() {
        if (sConnections.isEmpty()) {
            return false;
        }
        for (Object connection : sConnections.values()) {
            if (getUserState(connection) != null) {
                return true;
            }
        }
        return false;
    }

    private static void clearCrashedMarker(Object connection, ComponentName component) {
        boolean removed = false;
        try {
            Object info = getFieldValue(connection, "mAccessibilityServiceInfo");
            if (info != null) {
                setFieldValue(info, "crashed", false);
            }
        } catch (Throwable t) {
            log("clear crashed flag failed: " + t);
        }
        try {
            Object userState = getUserState(connection);
            if (userState != null) {
                Object crashed = getFieldValue(userState, "mCrashedServices");
                if (crashed instanceof Set) {
                    removed = ((Set<?>) crashed).remove(component);
                }
            }
        } catch (Throwable t) {
            log("clear crashed set failed: " + t);
        }
        if (removed) {
            event("检测", "已清除残留崩溃标记: " + component.flattenToString());
        }
    }

    private static ComponentName getComponentName(Object connection) {
        if (connection == null) {
            return null;
        }
        try {
            Method m = findMethod(connection.getClass(), "getComponentName");
            if (m != null) {
                Object res = m.invoke(connection);
                if (res instanceof ComponentName) {
                    return (ComponentName) res;
                }
            }
        } catch (Throwable ignored) {
        }
        Object obj = getFieldValue(connection, "mComponentName");
        return obj instanceof ComponentName ? (ComponentName) obj : null;
    }

    /** 读取启用列表：返回 null 时原因见 sLastEnabledReadError，避免把“设置项不存在”误判成读取失败 */
    private static String readEnabled() {
        Context context = sContext;
        if (context == null) {
            sLastEnabledReadError = null;
            return null;
        }
        try {
            sLastEnabledReadError = null;
            return Settings.Secure.getString(context.getContentResolver(), SETTING_ENABLED);
        } catch (Throwable t) {
            sLastEnabledReadError = t;
            log("read enabled failed: " + t);
            return null;
        }
    }

    private static String enabledReadSkipReason() {
        return sLastEnabledReadError != null
                ? "启用列表读取异常: " + sLastEnabledReadError
                : "启用列表设置项不存在";
    }

    private static boolean writeEnabled(String value) {
        Context context = sContext;
        if (context == null) {
            return false;
        }
        try {
            return Settings.Secure.putString(
                    context.getContentResolver(), SETTING_ENABLED, value);
        } catch (Throwable t) {
            log("write enabled failed: " + t);
            return false;
        }
    }

    private static String readConfig(String key) {
        if (KEY_ENABLE.equals(key)) {
            if (sModuleEnabledCache != null) return sModuleEnabledCache;
            String secureVal = readSecure(KEY_ENABLE);
            if (secureVal != null) {
                sModuleEnabledCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (KEY_SERVICES.equals(key)) {
            if (sManagedServicesCache != null) {
                return sManagedServicesCache;
            }
            String secureVal = readSecure(KEY_SERVICES);
            if (secureVal != null) {
                sManagedServicesCache = secureVal;
                return secureVal;
            }
            return "";
        }
        if (KEY_FIX_MODE.equals(key)) {
            if (sFixModeCache != null) return sFixModeCache;
            String secureVal = readSecure(KEY_FIX_MODE);
            if (secureVal != null) {
                sFixModeCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (KEY_FIX_DISABLED.equals(key)) {
            if (sFixDisabledCache != null) return sFixDisabledCache;
            String secureVal = readSecure(KEY_FIX_DISABLED);
            if (secureVal != null) {
                sFixDisabledCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (KEY_CIRCUIT_CONFIG.equals(key)) {
            if (sCircuitConfigCache != null) return sCircuitConfigCache;
            String secureVal = readSecure(KEY_CIRCUIT_CONFIG);
            if (secureVal != null) {
                sCircuitConfigCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (KEY_DRY_RUN.equals(key)) {
            if (sDryRunCache != null) return sDryRunCache;
            String secureVal = readSecure(KEY_DRY_RUN);
            if (secureVal != null) {
                sDryRunCache = secureVal;
                return secureVal;
            }
            return null;
        }
        if (KEY_DEBUG.equals(key)) {
            if (sDebugCache != null) return sDebugCache;
            String secureVal = readSecure(KEY_DEBUG);
            if (secureVal != null) {
                sDebugCache = secureVal;
                return secureVal;
            }
            return null;
        }
        return readSecure(key);
    }

    private static String readSecure(String key) {
        Context context = sContext;
        if (context == null) {
            return null;
        }
        try {
            return Settings.Secure.getString(context.getContentResolver(), key);
        } catch (Throwable t) {
            log("read " + key + " failed: " + t);
            return null;
        }
    }

    private static boolean containsComponent(String setting, ComponentName target) {
        if (setting == null || setting.isEmpty()) {
            return false;
        }
        for (String raw : setting.split(":")) {
            if (matches(raw, target)) {
                return true;
            }
        }
        return false;
    }

    private static String removeComponent(String setting, ComponentName target) {
        if (setting == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String raw : setting.split(":")) {
            if (raw.isEmpty() || matches(raw, target)) {
                continue;
            }
            if (result.length() > 0) {
                result.append(':');
            }
            result.append(raw);
        }
        return result.toString();
    }

    private static String addComponent(String setting, ComponentName target) {
        if (containsComponent(setting, target)) {
            return setting;
        }
        String component = target.flattenToString();
        if (setting == null || setting.isEmpty()) {
            return component;
        }
        return setting + ":" + component;
    }

    private static boolean matches(String raw, ComponentName target) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (raw.equals(target.flattenToString()) || raw.equals(target.flattenToShortString())) {
            return true;
        }
        ComponentName parsed = ComponentName.unflattenFromString(raw);
        return parsed != null && parsed.equals(target);
    }

    // 反射辅助方法
    private static Field findField(Class<?> clazz, String name) {
        if (clazz == null) {
            return null;
        }
        String key = clazz.getName() + "#" + name;
        Field cached = sFieldCache.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                sFieldCache.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        if (clazz == null) {
            return null;
        }
        String key = clazz.getName() + "#" + name;
        Method cached = sMethodCache.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                sMethodCache.put(key, m);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Object getFieldValue(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            Field f = findField(target.getClass(), fieldName);
            if (f != null) {
                return f.get(target);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void setFieldValue(Object target, String fieldName, Object value) {
        if (target == null) {
            return;
        }
        try {
            Field f = findField(target.getClass(), fieldName);
            if (f != null) {
                f.set(target, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void log(String message) {
        try {
            synchronized (sEventLock) {
                String trace = System.currentTimeMillis()
                        + " (up=" + RepairPolicy.formatUptimeSeconds(SystemClock.elapsedRealtime())
                        + ") " + message;
                sRawTraceLines.addLast(trace);
                while (sRawTraceLines.size() > RAW_TRACE_LIMIT) {
                    sRawTraceLines.removeFirst();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
        try {
            AutoRepairEntry inst = sModuleInstance;
            if (inst != null) {
                inst.log(Log.INFO, TAG, message);
            }
        } catch (Throwable ignored) {
        }
    }

    // 仅供单元测试验证热重载跨代状态保存与清理
    static void resetForTesting() {
        stopActiveResourcesForHotReload();
        clearInMemoryStateForHotReload();
        sContext = null;
        sClassLoader = null;
        sProcessName = null;
        sIsSystemServer = false;
        sFirstServiceConnectAt = 0L;
        sHotReloadCount = 0;
        sLastHotReloadAt = 0L;
        synchronized (sEventLock) {
            sEventLines.clear();
            sRawTraceLines.clear();
            sEventSeq = 0L;
        }
    }

    static void putConnectionForTesting(String key, Object conn) {
        sConnections.put(key, conn);
    }

    static Map<String, Object> getConnectionsForTesting() {
        return new HashMap<>(sConnections);
    }

    static void setFirstServiceConnectAtForTesting(long firstConnectAt) {
        sFirstServiceConnectAt = firstConnectAt;
    }

    static long getFirstServiceConnectAtForTesting() {
        return sFirstServiceConnectAt;
    }

    static void putRetryStateForTesting(
            String key, int attempts, long blockedUntil, long lastPkgKillAt, String pkg) {
        sAttempts.put(key, attempts);
        sBlockedUntil.put(key, blockedUntil);
        if (pkg != null) {
            sLastPackageKillAt.put(pkg, lastPkgKillAt);
        }
    }

    static int getAttemptsForTesting(String key) {
        Integer val = sAttempts.get(key);
        return val != null ? val : 0;
    }

    static Long getBlockedUntilForTesting(String key) {
        return sBlockedUntil.get(key);
    }

    static Long getLastPackageKillAtForTesting(String pkg) {
        return sLastPackageKillAt.get(pkg);
    }

    static int getHotReloadCountForTesting() {
        return sHotReloadCount;
    }
}
