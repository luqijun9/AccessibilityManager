package com.accessibilitymanager.probe;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
    static final String HOOK_ID_BIND_LOCKED = "a11y_repair_bind_locked";
    static final String HOOK_ID_UNBIND_LOCKED = "a11y_repair_unbind_locked";

    // 热重载跨代状态键（仅使用 JDK/Android 标准类型，跨 ClassLoader 安全传递）
    private static final String STATE_CONNECTIONS = "connections";
    private static final String STATE_MODULE_START_AT = "module_start_at";
    private static final String STATE_FIRST_CONNECT_AT = "first_connect_at";
    private static final String STATE_LAST_PKG_KILL_AT = "last_pkg_kill_at";
    private static final String STATE_EVENT_LINES = "event_lines";
    private static final String STATE_RAW_TRACE_LINES = "raw_trace_lines";
    private static final String STATE_EVENT_SEQ = "event_seq";
    private static final String STATE_EVENT_SESSION = "event_session";
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
    private static final String KEY_IGNORE_SYSTEM = "a11y_module_ignore_system";
    private static final String KEY_CMD_ACK = "a11y_module_cmd_ack";
    private static final String KEY_TOAST = "a11y_module_toast";
    private static final String KEY_LOADED = "a11y_module_loaded";
    private static final String KEY_EVENTS = "a11y_module_events";
    // system_server 视角的诊断快照，供管理器在无 DUMP/Root/Shizuku 权限时导出日志
    private static final String KEY_DUMP = "a11y_module_dump";
    // 模块内存事件缓冲区上限与 Settings.Secure 兜底安全上限（避免超过 32768 字符上限）
    static final int EVENT_LIMIT = 1000;
    static final int SECURE_MAX_CHARS = 20000;
    static final int PULL_BATCH_LINES = 120;
    static final int PULL_BATCH_MAX_CHARS = 64000;
    // 非保活服务（便签输入法等）连接日志聚合窗口：本身不重要，1 小时一条；明细仍全部写 logcat
    private static final long NON_MANAGED_EVENT_WINDOW_MS = 60 * 60 * 1000L;
    // 跳过路径聚合窗口：原因有诊断价值，保留 10 分钟粒度
    private static final long SKIP_EVENT_WINDOW_MS = 10 * 60 * 1000L;
    private static final String SETTING_ENABLED =
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    private static final String SETTING_ACCESSIBILITY_ENABLED =
            Settings.Secure.ACCESSIBILITY_ENABLED;
    private static final String SETTING_BOOT_COUNT = "boot_count";
    // 常见国产 ROM 超级省电/低电量 Settings 键名（用于诊断日志与变更监听）
    private static final String[] OEM_POWER_SAVE_KEYS = {
            "super_powersave_mode_state",
            "super_powersave_launcher_enter",
            "power_save_mode",
            "power_supersave_mode_open",
            "EXTREME_POWER_MODE",
            "bbk_super_save_power_mode",
            "pem_in_sleepmode",
            "SmartModeStatus",
            "low_power"
    };
    private static final String[] OBSERVED_POWER_SAVE_KEYS = {
            "super_powersave_mode_state",
            "super_powersave_launcher_enter",
            "power_supersave_mode_open",
            "low_power"
    };

    // 方案 A：系统广播通信管道（直达 system_server，免 Root/免 Shizuku、零延迟）
    public static final String ACTION_SERVICE_CMD =
            "com.accessibilitymanager.action.SERVICE_CMD";
    public static final String PERMISSION_SEND_CMD =
            "com.accessibilitymanager.permission.SEND_MODULE_CMD";
    public static final String EXTRA_PAYLOAD = "payload";
    public static final String EXTRA_RECEIVER = "receiver";
    public static final String BUNDLE_LINES = "lines";
    public static final String BUNDLE_SESSION = "session";
    public static final String BUNDLE_MAX_SEQ = "max_seq";
    public static final String BUNDLE_HAS_MORE = "has_more";
    public static final String BUNDLE_DUMP = "dump";

    private static final long DEDUPE_MS = 1500L;
    private static final long SETTLE_MS = 3000L;
    private static final long TOGGLE_OFF_MS = 500L;
    private static final long SCAN_INIT_DELAY_MS = 10000L;
    private static final long SCAN_CONFIG_DELAY_MS = 1500L;

    // 关闭补回配置
    private static final long CLOSE_MIN_INTERVAL_MS = 250L;
    private static final long RECONCILE_CONFIG_DELAY_MS = 500L;
    // 补回写入后延迟检查是否真正绑定（force-stop 后可能“列表已恢复但未绑定”）
    private static final long BIND_VERIFY_DELAY_MS = 2000L;
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
    private static volatile BroadcastReceiver sSystemStateReceiver;
    private static int sContextRetryCount;

    // 事件缓冲
    private static final int RAW_TRACE_LIMIT = 1000;
    private static final Object sEventLock = new Object();
    private static final Deque<String> sEventLines = new ArrayDeque<>();
    private static final Deque<String> sRawTraceLines = new ArrayDeque<>();
    private static String sEventSession =
            Long.toHexString(System.currentTimeMillis());
    private static long sEventSeq;
    private static final EventThrottle sNonManagedThrottle =
            new EventThrottle(NON_MANAGED_EVENT_WINDOW_MS);
    private static final EventThrottle sSkipThrottle =
            new EventThrottle(SKIP_EVENT_WINDOW_MS);

    // 状态缓存（仅在 sHandler 线程读写）
    private static final Map<String, Long> sLastDied = new HashMap<>();
    private static final Map<String, Long> sLastInit = new HashMap<>();
    private static final Map<String, Object> sConnections = new HashMap<>();
    private static boolean sBoundCheckAvailable;
    private static boolean sBoundCheckErrorLogged;

    // 重构后的单服务状态机与包级修复协调器（全部只在 sHandler 线程读写，不需要全局保护锁）
    private static final Map<String, ServiceRepairStateMachine> sMachines = new HashMap<>();
    private static final Map<String, Runnable> sMachineTimers = new HashMap<>();
    private static final Map<String, Runnable> sPendingMachineSync = new HashMap<>();
    private static final Set<String> sUserDisabledKeys = new LinkedHashSet<>();
    private static final Map<String, PackageRepairRequest> sPackageRepairRequests = new HashMap<>();
    private static final List<String> sPackageRepairQueue = new ArrayList<>();
    private static final List<String> sActiveRepairBatch = new ArrayList<>();
    private static final Set<String> sRestoreRetryScheduled = new LinkedHashSet<>();
    private static boolean sRepairTransactionActive;
    private static String sRepairTransactionPackage;
    private static long sRepairTransactionGeneration;
    private static SettingsWriter sSettingsWriter;

    /** 包级修复请求：同一包只保留一条，allowKill 取所有请求的“与”，任一成员拒绝强杀就不强杀。 */
    private static final class PackageRepairRequest {
        final String pkg;
        final ComponentName trigger;
        final String reason;
        final long requestedAt;
        boolean allowKill;

        PackageRepairRequest(String pkg, ComponentName trigger, boolean allowKill,
                String reason, long requestedAt) {
            this.pkg = pkg;
            this.trigger = trigger;
            this.allowKill = allowKill;
            this.reason = reason;
            this.requestedAt = requestedAt;
        }
    }

    private static boolean sReconcilePending;
    private static boolean sReconcileRetryPending;
    private static boolean sBindVerifyPending;
    private static long sLastReconcileWriteAt;
    private static long sModuleStartAt = SystemClock.elapsedRealtime();
    private static long sFirstServiceConnectAt;
    private static Runnable sPostBootCheckRunnable;
    private static final Map<String, Long> sLastPackageKillAt = new HashMap<>();
    private static final Set<String> sPendingVerifyKeys = new LinkedHashSet<>();
    private static volatile String sModuleEnabledCache;
    private static volatile String sManagedServicesCache;
    private static volatile String sFixModeCache;
    private static volatile String sFixDisabledCache;
    private static volatile String sIgnoreSystemCache;
    private static volatile String sDryRunCache;
    private static volatile String sDebugCache;
    private static volatile String sToastCache;
    private static String sLastObservedEnabled;
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
                state.put(STATE_EVENT_SESSION, sEventSession);
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
        BroadcastReceiver stateReceiver = sSystemStateReceiver;
        sSystemStateReceiver = null;
        if (stateReceiver != null && ctx != null) {
            try {
                ctx.unregisterReceiver(stateReceiver);
            } catch (Throwable t) {
                log("unregister system state receiver on hot reload failed: " + t);
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
        sPostBootCheckRunnable = null;
        sLastObservedEnabled = null;
        sToastCache = null;
        if (sHandler != null) {
            for (Runnable runnable : sMachineTimers.values()) {
                if (runnable != null) {
                    sHandler.removeCallbacks(runnable);
                }
            }
            for (Runnable runnable : sPendingMachineSync.values()) {
                if (runnable != null) {
                    sHandler.removeCallbacks(runnable);
                }
            }
        }
        sConnections.clear();
        sLastDied.clear();
        sLastInit.clear();
        sLastPackageKillAt.clear();
        sPendingVerifyKeys.clear();
        sMachines.clear();
        sMachineTimers.clear();
        sPendingMachineSync.clear();
        sUserDisabledKeys.clear();
        sPackageRepairRequests.clear();
        sPackageRepairQueue.clear();
        sActiveRepairBatch.clear();
        sRestoreRetryScheduled.clear();
        sRepairTransactionActive = false;
        sRepairTransactionPackage = null;
        sRepairTransactionGeneration = 0L;
        sSettingsWriter = null;
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
                Object sessionObj = state.get(STATE_EVENT_SESSION);
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
                if (sessionObj instanceof String && !((String) sessionObj).isEmpty()) {
                    sEventSession = (String) sessionObj;
                } else if (!sEventLines.isEmpty()) {
                    String candidate = extractSession(sEventLines.peekLast());
                    if (candidate.isEmpty()) {
                        candidate = extractSession(sEventLines.peekFirst());
                    }
                    if (!candidate.isEmpty()) {
                        sEventSession = candidate;
                    }
                }
                normalizeEventLinesSessionLocked(sEventLines, sEventSession);
                while (sEventLines.size() > EVENT_LIMIT) {
                    sEventLines.removeFirst();
                }
                Object traces = state.get(STATE_RAW_TRACE_LINES);
                if (traces instanceof List) {
                    sRawTraceLines.clear();
                    for (Object item : (List<?>) traces) {
                        if (item instanceof String) {
                            sRawTraceLines.addLast((String) item);
                        }
                    }
                    while (sRawTraceLines.size() > RAW_TRACE_LIMIT) {
                        sRawTraceLines.removeFirst();
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

            Method bindLockedMethod = findMethod(connClass, "bindLocked");
            if (bindLockedMethod != null) {
                hook(bindLockedMethod)
                        .setId(HOOK_ID_BIND_LOCKED)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object conn = chain.getThisObject();
                            ComponentName cn = getComponentName(conn);
                            if (isDebugEnabled() && cn != null && isManaged(cn)) {
                                event("监听", "AMS发起绑定: " + cn.flattenToShortString() + " conn=" + connId(conn));
                            }
                            return chain.proceed();
                        });
            }

            Method unbindLockedMethod = findMethod(connClass, "unbindLocked");
            if (unbindLockedMethod != null) {
                hook(unbindLockedMethod)
                        .setId(HOOK_ID_UNBIND_LOCKED)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object conn = chain.getThisObject();
                            ComponentName cn = getComponentName(conn);
                            if (isDebugEnabled() && cn != null && isManaged(cn)) {
                                event("监听", "AMS发起解绑: " + cn.flattenToShortString() + " conn=" + connId(conn));
                            }
                            return chain.proceed();
                        });
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
        String payload = buildTailWithinCharLimit(sEventLines, SECURE_MAX_CHARS);
        try {
            Settings.Secure.putString(context.getContentResolver(), KEY_EVENTS, payload);
        } catch (Throwable t) {
            log("event write failed: " + t);
        }
    }

    private static String extractSession(String line) {
        if (line == null) {
            return "";
        }
        int firstPipe = line.indexOf('|');
        return firstPipe > 0 ? line.substring(0, firstPipe) : "";
    }

    private static long extractSeq(String line) {
        if (line == null) {
            return -1L;
        }
        int firstPipe = line.indexOf('|');
        if (firstPipe < 0) {
            return -1L;
        }
        int secondPipe = line.indexOf('|', firstPipe + 1);
        if (secondPipe <= firstPipe + 1) {
            return -1L;
        }
        try {
            return Long.parseLong(line.substring(firstPipe + 1, secondPipe));
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    static void normalizeEventLinesSessionLocked(Deque<String> lines, String targetSession) {
        if (lines == null || lines.isEmpty() || targetSession == null || targetSession.isEmpty()) {
            return;
        }
        List<String> normalized = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            int firstPipe = line.indexOf('|');
            if (firstPipe >= 0) {
                String oldSession = line.substring(0, firstPipe);
                if (!targetSession.equals(oldSession)) {
                    normalized.add(targetSession + line.substring(firstPipe));
                    continue;
                }
            }
            normalized.add(line);
        }
        lines.clear();
        for (String line : normalized) {
            lines.addLast(line);
        }
    }

    static String buildTailWithinCharLimit(Iterable<String> lines, int maxChars) {
        if (lines == null || maxChars <= 0) {
            return "";
        }
        List<String> list = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isEmpty()) {
                list.add(line);
            }
        }
        if (list.isEmpty()) {
            return "";
        }
        int totalChars = 0;
        int startIdx = list.size();
        for (int i = list.size() - 1; i >= 0; i--) {
            String line = list.get(i);
            int added = line.length() + (startIdx < list.size() ? 1 : 0);
            if (totalChars + added > maxChars) {
                if (startIdx == list.size()) {
                    return line.substring(0, maxChars);
                }
                break;
            }
            totalChars += added;
            startIdx = i;
        }
        StringBuilder sb = new StringBuilder(totalChars);
        for (int i = startIdx; i < list.size(); i++) {
            if (i > startIdx) {
                sb.append('\n');
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    static String truncateToCharLimit(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    static int ackEventsUpToLocked(Deque<String> lines, String currentSession,
            String ackSession, long ackSeq) {
        if (lines == null || lines.size() <= 1 || ackSeq <= 0L
                || ackSession == null || !ackSession.equals(currentSession)) {
            return 0;
        }
        int removed = 0;
        while (lines.size() > 1) {
            String head = lines.peekFirst();
            if (head == null) {
                lines.removeFirst();
                continue;
            }
            String lineSession = extractSession(head);
            long lineSeq = extractSeq(head);
            if (!currentSession.equals(lineSession) || lineSeq < 0L || lineSeq > ackSeq) {
                break;
            }
            lines.removeFirst();
            removed++;
        }
        return removed;
    }

    static List<ArrayList<String>> buildPullBatchesLocked(Iterable<String> lines,
            String currentSession, String clientSession, long clientLastSeq,
            int maxBatchLines, int maxBatchChars) {
        List<ArrayList<String>> batches = new ArrayList<>();
        if (lines == null || currentSession == null || currentSession.isEmpty()) {
            return batches;
        }
        boolean sameSession = currentSession.equals(clientSession);
        int lineLimit = Math.max(1, maxBatchLines);
        int charLimit = Math.max(256, maxBatchChars);

        ArrayList<String> currentBatch = new ArrayList<>();
        int currentChars = 0;
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            if (sameSession) {
                long seq = extractSeq(line);
                if (seq >= 0L && seq <= clientLastSeq) {
                    continue;
                }
            }
            int len = line.length() + 1;
            if (!currentBatch.isEmpty()
                    && (currentBatch.size() >= lineLimit || currentChars + len > charLimit)) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentChars = 0;
            }
            currentBatch.add(line);
            currentChars += len;
        }
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        return batches;
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
            if (context != null && (sContext == null || sObserver == null
                    || sCommandReceiver == null || sSystemStateReceiver == null)) {
                final boolean changed = sContext != context;
                sContext = context;
                if (handler != null) {
                    handler.post(() -> {
                        if (changed) {
                            sObserverAttempts = 0;
                        }
                        ensureObserver();
                        ensureCommandReceiver();
                        ensureSystemStateReceiver();
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
                    String pkg = component.getPackageName();
                    boolean ignoreSystem = shouldIgnoreSystemApp(pkg);
                    if (!died) {
                        Object existing = sConnections.get(connKey);
                        if (!ignoreSystem && existing != null && existing != connection) {
                            // 旧连接已经死亡时 unbindLocked 可能再次扰动 AMS 状态；
                            // 只有旧连接仍然持有服务接口时才需要主动解绑。
                            if (isConnectionBound(existing)) {
                                event("警告", "检测到同名服务存在重复连接，自动解除旧连接: "
                                        + component.flattenToShortString()
                                        + " old=" + connId(existing) + " new=" + connId(connection));
                                unbindConnection(existing, "duplicate_detected");
                            } else {
                                log("stale duplicate connection already dead: "
                                        + component.flattenToShortString()
                                        + " old=" + connId(existing) + " new=" + connId(connection));
                            }
                        }
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
        ensureSystemStateReceiver();
        if (sObserver != null) {
            return;
        }
        try {
            ContentResolver resolver = sContext.getContentResolver();
            if (sLastObservedEnabled == null) {
                sLastObservedEnabled = readEnabled();
            }
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
                    if (SETTING_ACCESSIBILITY_ENABLED.equals(key)) {
                        onAccessibilitySwitchChanged();
                        return;
                    }
                    if (isObservedPowerSaveKey(key)) {
                        event("省电", "省电设置项变更: " + key, getPowerStateSummary());
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
                    if (KEY_TOAST.equals(key)) {
                        sToastCache = readSecure(KEY_TOAST);
                        return;
                    }
                    if (KEY_ENABLE.equals(key)) {
                        sModuleEnabledCache = readSecure(KEY_ENABLE);
                        if (isModuleEnabled()) {
                            scheduleReconcile("enable", RECONCILE_CONFIG_DELAY_MS);
                        }
                        syncMachines("module_enabled_setting");
                    } else if (KEY_SERVICES.equals(key)) {
                        sManagedServicesCache = readSecure(KEY_SERVICES);
                        scheduleReconcile("services", RECONCILE_CONFIG_DELAY_MS);
                        scheduleScan(SCAN_CONFIG_DELAY_MS, "services changed");
                        syncMachines("managed_setting");
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
            resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_TOAST),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(KEY_DEBUG_KILL),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_ENABLED),
                    false, sObserver);
            resolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_ACCESSIBILITY_ENABLED),
                    false, sObserver);
            registerPowerSaveSettingObservers(resolver, sObserver);
            sObserverAttempts = 0;
            log("[配置] 配置监听已注册 (含 boot_count 实时同步与省电状态监听)");
            writeHeartbeatOnce("observer_ready");
            // 热重载/进程重启后状态机内存为空，必须按真实锁定列表与绑定状态重建一次
            syncMachines("observer_ready");
            long sinceBoot = SystemClock.elapsedRealtime();
            long remainingBoot = getRemainingBootGraceMs(sinceBoot, sFirstServiceConnectAt);
            if (remainingBoot > 0L) {
                schedulePostBootCheck(remainingBoot + 1000L);
            } else {
                long delayMs = sHotReloadCount > 0 ? SCAN_CONFIG_DELAY_MS : SCAN_INIT_DELAY_MS;
                String reason = sHotReloadCount > 0 ? "hot_reload_init" : "init";
                scheduleScan(delayMs, reason);
                scheduleReconcile(reason, delayMs);
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
        boolean isManual = "manual scan".equals(reason);
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
            scheduleMachineSync(component, SETTLE_MS, "scan");
        }
        if (candidates > 0 || "manual scan".equals(reason)) {
            event("扫描", "扫描完成 (" + reason + ")，发现 " + candidates + " 个异常服务",
                    "action=scan_done reason=" + reason + " unbound=" + candidates);
        }
    }

    private static void onEnabledListChanged() {
        String current = readEnabled();
        if (current != null) {
            String prev = sLastObservedEnabled;
            if (prev != null && !current.equals(prev)) {
                String removed = diffComponents(current, prev);
                String added = diffComponents(prev, current);
                if (isIgnoreSystemEnabled()) {
                    removed = RepairPolicy.filterOutSystemComponents(removed, true, AutoRepairEntry::isSystemPackage);
                    added = RepairPolicy.filterOutSystemComponents(added, true, AutoRepairEntry::isSystemPackage);
                }
                if (!removed.isEmpty() || !added.isEmpty()) {
                    event("监听", "启用列表变更: 移除=" + (removed.isEmpty() ? "无" : removed)
                                    + " 新增=" + (added.isEmpty() ? "无" : added),
                            getPowerStateSummary());
                }
            }
            sLastObservedEnabled = current;
        }
        // 先把“启用列表变化”投递给每个服务的状态机，让它们决定 RESTORING/BOUND；
        // reconcileMissing 仍作为批量补回兜底，两者都只在 sHandler 线程执行。
        notifyMachinesEnabledListChanged();
        reconcileMissing("enabled change");
    }

    private static void onAccessibilitySwitchChanged() {
        if (sContext == null) {
            return;
        }
        try {
            int enabled = Settings.Secure.getInt(
                    sContext.getContentResolver(), SETTING_ACCESSIBILITY_ENABLED, -1);
            event("监听", "无障碍总开关变更: accessibility_enabled=" + enabled,
                    getPowerStateSummary());
            if (enabled != 1 && isModuleEnabled()) {
                String managed = readConfig(KEY_SERVICES);
                if (managed != null && !managed.isEmpty()) {
                    ensureAccessibilitySwitchOn();
                    scheduleReconcile("accessibility_switch", RECONCILE_CONFIG_DELAY_MS);
                }
            }
            syncMachines("accessibility_switch");
        } catch (Throwable t) {
            log("onAccessibilitySwitchChanged error: " + t);
        }
    }

    private static String diffComponents(String baseSetting, String compareSetting) {
        if (compareSetting == null || compareSetting.isEmpty()) {
            return "";
        }
        StringBuilder diff = new StringBuilder();
        for (String raw : compareSetting.split(":")) {
            if (raw.isEmpty()) {
                continue;
            }
            ComponentName cn = ComponentName.unflattenFromString(raw);
            if (cn == null) {
                continue;
            }
            if (!containsComponent(baseSetting, cn)) {
                if (diff.length() > 0) {
                    diff.append(',');
                }
                diff.append(cn.flattenToShortString());
            }
        }
        return diff.toString();
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
        Set<String> addedLabels = new LinkedHashSet<>();
        Set<String> newlyAddedKeys = new LinkedHashSet<>();
        List<ComponentKey> addKeys = new ArrayList<>();
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
            // 崩溃修复事务正在临时移除该成员时，不要在这里抢着补回，交给包协调器统一重新开启
            if (isRepairingKey(key)) {
                continue;
            }
            // 用户明确关闭的服务不得由补回路径复活
            if (sUserDisabledKeys.contains(key)) {
                continue;
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
            addedLabels.add(getAppLabel(component));
            newlyAddedKeys.add(key);
            addKeys.add(ComponentKey.of(component.getPackageName(), component.getClassName()));
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
                "action=reconcile reason=" + reason + " count=" + count
                        + " " + getPowerStateSummary());
        SettingsWriter.WriteResult writeResult = settingsWriter().applyDelta(
                addKeys, Collections.<ComponentKey>emptyList());
        if (writeResult.isFailure()) {
            event("失败", "补回写入失败: " + added,
                    "action=reconcile_write_fail reason=" + reason
                            + " status=" + writeResult.getStatus()
                            + " " + getPowerStateSummary());
            return;
        }
        ensureAccessibilitySwitchOn();
        showModuleToast("保活 " + joinLabels(addedLabels));
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
            if (isRepairingKey(key)) {
                continue; // 包级修复事务自己的临时移除
            }
            if (sUserDisabledKeys.contains(key)) {
                continue; // 用户手动关闭
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
            scheduleMachineSync(component, SETTLE_MS, "verify_bindings");
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
                    final ResultReceiver receiver = extractResultReceiver(intent);
                    log("received broadcast cmd: " + payload);
                    Handler handler = sHandler;
                    if (handler != null) {
                        handler.post(() -> {
                            try {
                                applyServiceCommand(payload, receiver);
                            } catch (Throwable t) {
                                event("失败", "广播指令执行异常: " + t);
                            }
                        });
                    } else {
                        applyServiceCommand(payload, receiver);
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
            ensureSystemStateReceiver();
            ensureObserver();
        }
        if ((sCommandReceiver == null || sSystemStateReceiver == null || sObserver == null)
                && sContextRetryCount < MAX_INIT_RETRY) {
            sContextRetryCount++;
            if (sHandler != null) {
                sHandler.postDelayed(AutoRepairEntry::scheduleReceiverInit, INIT_RETRY_DELAY_MS);
            }
        }
    }

    private static synchronized void ensureSystemStateReceiver() {
        if (sSystemStateReceiver != null) {
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
                    if (intent == null || intent.getAction() == null) {
                        return;
                    }
                    final String action = intent.getAction();
                    Handler handler = sHandler;
                    if (handler != null) {
                        handler.post(() -> handleSystemStateBroadcast(action));
                    } else {
                        handleSystemStateBroadcast(action);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            }
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, null, sHandler, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter, null, sHandler);
            }
            sSystemStateReceiver = receiver;
            log("[配置] 系统省电与屏幕广播监听已就绪");
        } catch (Throwable t) {
            event("失败", "注册系统省电/屏幕监听失败: " + t);
        }
    }

    private static void handleSystemStateBroadcast(String action) {
        if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action))) {
            String enabled = readEnabled();
            event("省电", "系统省电状态变更: " + shortActionName(action),
                    getPowerStateSummary() + " enabled=" + (enabled == null ? "null" : enabled));
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action) && isModuleEnabled()) {
                scheduleReconcile("power_save_changed", 500L);
                scheduleScan(1500L, "power_save_changed");
            }
            if (isModuleEnabled()) {
                // 超级省电/Doze 切换会批量杀服务，立即让状态机按真实绑定状态重新评估
                syncMachines("power_state:" + shortActionName(action));
            }
            return;
        }
        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            event("监听", "屏幕已熄屏 (SCREEN_OFF)", getPowerStateSummary());
            return;
        }
        if (Intent.ACTION_SCREEN_ON.equals(action)) {
            event("监听", "屏幕已点亮 (SCREEN_ON)", getPowerStateSummary());
            return;
        }
        if (Intent.ACTION_USER_PRESENT.equals(action)) {
            event("监听", "用户解锁屏幕 (USER_PRESENT)", getPowerStateSummary());
            if (isModuleEnabled()) {
                scheduleReconcile("user_present", 500L);
                scheduleScan(1000L, "user_present");
                syncMachines("user_present");
            }
        }
    }

    private static String shortActionName(String action) {
        int lastDot = action.lastIndexOf('.');
        return lastDot >= 0 ? action.substring(lastDot + 1) : action;
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
        applyServiceCommand(value, null);
    }

    private static void applyServiceCommand(String value, ResultReceiver receiver) {
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
            writeModuleDump(receiver);
            notifyCommandAck();
            return;
        }

        if ("scan".equals(body)) {
            scheduleScan(0L, "manual scan");
            event("扫描", "收到手动扫描指令");
            notifyCommandAck();
            return;
        }

        int colon = body.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String action = body.substring(0, colon);
        String component = body.substring(colon + 1);

        if ("pull_logs".equals(action)) {
            handlePullLogsCommand(component, receiver);
            return;
        }

        if ("ack_logs".equals(action)) {
            handleAckLogsCommand(component);
            return;
        }

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
            syncMachines("enable_module cmd");
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

        if ("toast".equals(action)) {
            sToastCache = "1".equals(component) ? "1" : "0";
            if (sContext != null) {
                try {
                    Settings.Secure.putString(sContext.getContentResolver(), KEY_TOAST, sToastCache);
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
            // 锁定列表变化后，已移出列表的用户关闭标记不再有意义；仍在列表中的关闭意图保留
            Set<String> managedKeys = new LinkedHashSet<>();
            if (component != null && !component.isEmpty()) {
                for (String raw : component.split(":")) {
                    ComponentKey key = ComponentKey.parse(raw);
                    if (key != null) {
                        managedKeys.add(key.flatten());
                    }
                }
            }
            sUserDisabledKeys.retainAll(managedKeys);
            if (sContext != null) {
                try {
                    Settings.Secure.putString(sContext.getContentResolver(), KEY_SERVICES, component);
                } catch (Throwable ignored) {
                }
            }
            scheduleReconcile("sync_services cmd", RECONCILE_CONFIG_DELAY_MS);
            scheduleScan(SCAN_CONFIG_DELAY_MS, "sync_services cmd");
            syncMachines("sync_services cmd");
            if (changed) {
                event("配置", "模块已生效最新锁定列表: " + component);
            } else {
                log("sync_services unchanged: " + component);
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

        if ("sync_ignore_system".equals(action)) {
            sIgnoreSystemCache = component;
            if (sContext != null) {
                try {
                    Settings.Secure.putString(sContext.getContentResolver(), KEY_IGNORE_SYSTEM, component);
                } catch (Throwable ignored) {
                }
            }
            event("配置", "模块已生效忽略系统应用配置: ignoreSystem=" + component);
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
            // 用户明确关闭：记录为 DISABLED 意图，后续任何补回/修复都不得复活
            sUserDisabledKeys.add(targetKey);
            handleServiceEvent(target, ServiceRepairStateMachine.Event.userDisable());
            removeFromPackageRepair(target);
        } else {
            sUserDisabledKeys.remove(targetKey);
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
            if (enable) {
                handleServiceEvent(target, ServiceRepairStateMachine.Event.userEnable());
            }
            notifyCommandAck();
            return;
        }
        String updated = enable ? addComponent(current, target) : removeComponent(current, target);
        if (writeEnabled(updated)) {
            if (enable) {
                ensureAccessibilitySwitchOn();
                handleServiceEvent(target, ServiceRepairStateMachine.Event.userEnable());
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

    @SuppressWarnings("deprecation")
    private static ResultReceiver extractResultReceiver(Intent intent) {
        if (intent == null) {
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class);
            }
            return (ResultReceiver) intent.getParcelableExtra(EXTRA_RECEIVER);
        } catch (Throwable t) {
            log("extractResultReceiver failed: " + t);
            return null;
        }
    }

    private static void handlePullLogsCommand(String arg, ResultReceiver receiver) {
        String clientSession = "";
        long clientLastSeq = 0L;
        if (arg != null && !arg.isEmpty()) {
            int sep = arg.indexOf('|');
            if (sep >= 0) {
                clientSession = arg.substring(0, sep);
                try {
                    clientLastSeq = Long.parseLong(arg.substring(sep + 1));
                } catch (Throwable ignored) {
                }
            } else {
                clientSession = arg;
            }
        }

        final String sessionSnapshot;
        final long maxSeqSnapshot;
        final List<ArrayList<String>> batches;
        synchronized (sEventLock) {
            ackEventsUpToLocked(sEventLines, sEventSession, clientSession, clientLastSeq);
            sessionSnapshot = sEventSession;
            maxSeqSnapshot = sEventSeq;
            batches = buildPullBatchesLocked(sEventLines, sEventSession,
                    clientSession, clientLastSeq, PULL_BATCH_LINES, PULL_BATCH_MAX_CHARS);
        }

        if (receiver == null) {
            return;
        }
        try {
            if (batches.isEmpty()) {
                Bundle b = new Bundle();
                b.putString(BUNDLE_SESSION, sessionSnapshot);
                b.putLong(BUNDLE_MAX_SEQ, maxSeqSnapshot);
                b.putBoolean(BUNDLE_HAS_MORE, false);
                b.putStringArrayList(BUNDLE_LINES, new ArrayList<>());
                receiver.send(0, b);
                return;
            }
            for (int i = 0; i < batches.size(); i++) {
                ArrayList<String> batch = batches.get(i);
                long batchMaxSeq = maxSeqSnapshot;
                if (!batch.isEmpty()) {
                    long lastSeqInBatch = extractSeq(batch.get(batch.size() - 1));
                    if (lastSeqInBatch > 0L) {
                        batchMaxSeq = lastSeqInBatch;
                    }
                }
                Bundle b = new Bundle();
                b.putString(BUNDLE_SESSION, sessionSnapshot);
                b.putLong(BUNDLE_MAX_SEQ, batchMaxSeq);
                b.putBoolean(BUNDLE_HAS_MORE, i < batches.size() - 1);
                b.putStringArrayList(BUNDLE_LINES, batch);
                receiver.send(0, b);
            }
        } catch (Throwable t) {
            log("pull_logs ResultReceiver.send failed: " + t);
        }
    }

    private static void handleAckLogsCommand(String arg) {
        if (arg == null || arg.isEmpty()) {
            return;
        }
        int sep = arg.indexOf('|');
        if (sep < 0) {
            return;
        }
        String ackSession = arg.substring(0, sep);
        long ackSeq;
        try {
            ackSeq = Long.parseLong(arg.substring(sep + 1));
        } catch (Throwable ignored) {
            return;
        }
        synchronized (sEventLock) {
            ackEventsUpToLocked(sEventLines, sEventSession, ackSession, ackSeq);
        }
    }

    private static void writeModuleDump() {
        writeModuleDump(null);
    }

    /** 采集 system_server 视角的诊断快照，供管理器在无 DUMP/Root/Shizuku 权限时导出日志 */
    private static void writeModuleDump(ResultReceiver receiver) {
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
                    .append("  忽略系统应用: ").append(isIgnoreSystemEnabled())
                    .append("  Toast提示: ").append(isToastEnabled()).append('\n');
            sb.append("省电与屏幕状态: ").append(getPowerStateSummary()).append('\n');
            sb.append("锁定列表: ").append(managed == null || managed.isEmpty() ? "空" : managed)
                    .append('\n');
            if (managed != null && !managed.isEmpty()) {
                for (String raw : managed.split(":")) {
                    ComponentName component = ComponentName.unflattenFromString(raw);
                    if (component == null) {
                        continue;
                    }
                    String key = component.flattenToString();
                    sb.append("[服务] ").append(key)
                            .append("  在启用列表=").append(containsComponent(enabled, component))
                            .append("  已绑定=").append(findBoundConnection(component) != null)
                            .append("  崩溃标记=").append(getCrashedMarker(component))
                            .append("  ").append(getPackageProcessSummary(component.getPackageName()))
                            .append('\n');
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
            String fullDump = sb.toString();
            if (receiver != null) {
                try {
                    Bundle b = new Bundle();
                    b.putString(BUNDLE_DUMP, fullDump);
                    receiver.send(0, b);
                } catch (Throwable t) {
                    log("dump_info ResultReceiver.send failed: " + t);
                }
            }
            Settings.Secure.putString(resolver, KEY_DUMP,
                    truncateToCharLimit(fullDump, SECURE_MAX_CHARS));
        } catch (Throwable t) {
            log("dump info failed: " + t);
            String err = "采集失败: " + t;
            if (receiver != null) {
                try {
                    Bundle b = new Bundle();
                    b.putString(BUNDLE_DUMP, err);
                    receiver.send(1, b);
                } catch (Throwable ignored) {
                }
            }
            try {
                Settings.Secure.putString(resolver, KEY_DUMP, err);
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

        boolean forceStopCalled = false;
        try {
            ActivityManager am =
                    (ActivityManager) sContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                Method forceStopMethod = am.getClass().getMethod("forceStopPackage", String.class);
                forceStopMethod.invoke(am, pkg);
                forceStopCalled = true;
                log("killAppProcesses: forceStopPackage called for " + pkg);
            }
        } catch (Throwable t) {
            log("killAppProcesses: forceStopPackage reflection failed: " + t);
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
        return "uid=" + uid + " forceStop=" + forceStopCalled + " killedPids=" + killed;
    }

    private static void onBinderDied(ComponentName component, boolean crashed,
            boolean hasInterface, Object connection) {
        String key = component.flattenToString();
        long now = SystemClock.elapsedRealtime();

        if (shouldIgnoreSystemApp(component.getPackageName())) {
            log("ignore binderDied for system app: " + key);
            return;
        }
        if (!isManaged(component)) {
            Long lastNonManaged = sLastDied.get(key);
            if (lastNonManaged != null && now - lastNonManaged < DEDUPE_MS) {
                return;
            }
            sLastDied.put(key, now);
            eventNonManaged(key, "非保活服务挂掉(忽略) crashed=" + crashed);
            return;
        }
        if (sContext == null) {
            event("失败", "缺少系统 Context，忽略: " + key);
            return;
        }

        // 旧连接退役时 AMS 可能已经有新连接：先清掉 crashed 标记，再交给状态机。
        // 只有确认没有新连接时才做去重，避免把“新连接已恢复”的信号误丢掉。
        Object currentBound = findBoundConnection(component);
        boolean newerConnectionBound = currentBound != null;
        if (newerConnectionBound) {
            clearCrashedMarker(currentBound, component);
        } else {
            Long last = sLastDied.get(key);
            if (last != null && now - last < DEDUPE_MS) {
                return;
            }
            sLastDied.put(key, now);
        }

        event("检测", "崩溃/断开: " + key,
                "hook=binderDied conn=" + connId(connection) + " crashed=" + crashed
                        + " iface=" + hasInterface + " enabled=" + isModuleEnabled()
                        + " dryRun=" + isDryRun()
                        + " " + getPackageProcessSummary(component.getPackageName())
                        + " " + getPowerStateSummary());
        handleServiceEvent(component,
                ServiceRepairStateMachine.Event.binderDied(newerConnectionBound, false));
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
        boolean success = !crashed && hasInterface;

        if (!managed) {
            eventNonManaged(key, success
                    ? "非保活服务连接(忽略)"
                    : "非保活服务重绑失败(忽略) crashed=" + crashed + ", iface=" + hasInterface);
            return;
        }

        if (success) {
            log("[服务] initializeService 成功: " + key
                    + " conn=" + connId(connection));
        } else {
            event("检测", "重绑失败: " + key,
                    "hook=initializeService conn=" + connId(connection)
                            + " crashed=" + crashed + " iface=" + hasInterface);
        }
        handleServiceEvent(component,
                ServiceRepairStateMachine.Event.initialized(success, hasInterface));
    }

    /** Settings.Secure 启用列表的唯一写入器：短临界区、按 delta 读写，绝不在 verify 阶段持锁。 */
    private static SettingsWriter settingsWriter() {
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
                        // 设置项不存在等价于空列表；读取异常才抛给 SettingsWriter 处理
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

    /** 为单个服务构造状态机快照；所有字段来自真实系统状态，不允许使用猜测值。 */
    private static ServiceRepairStateMachine.Snapshot buildMachineSnapshot(
            ComponentName component) {
        ServiceRepairStateMachine.Snapshot snapshot = new ServiceRepairStateMachine.Snapshot();
        long now = SystemClock.elapsedRealtime();
        snapshot.now = now;
        snapshot.moduleEnabled = isModuleEnabled();
        snapshot.managed = isManaged(component)
                && !shouldIgnoreSystemApp(component.getPackageName());
        snapshot.userDisabled = sUserDisabledKeys.contains(component.flattenToString());

        String enabled = readEnabled();
        boolean enabledReadFailed = enabled == null;
        // 启用列表读取失败时不能把服务误判成“被移除/被关闭”，先假定仍在列表，
        // 同时用 bindCheckAvailable=false 禁止基于不完整状态发起修复。
        snapshot.inEnabledList = enabledReadFailed || containsComponent(enabled, component);

        Object bound = findBoundConnection(component);
        snapshot.bound = bound != null;
        snapshot.bindCheckAvailable = sBoundCheckAvailable && !enabledReadFailed;
        snapshot.processRunning = isServiceProcessRunning(component);

        long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
        snapshot.bootGraceActive = remainingBoot > 0L;
        snapshot.bootGraceRemainingMs = remainingBoot;
        return snapshot;
    }

    /** 所有 hook/observer/命令/定时器都从这里进入状态机，保证状态只在 sHandler 线程变化。 */
    private static void handleServiceEvent(ComponentName component,
            ServiceRepairStateMachine.Event event) {
        if (component == null || event == null) {
            return;
        }
        String key = component.flattenToString();
        ServiceRepairStateMachine machine = sMachines.get(key);
        ServiceRepairStateMachine.Snapshot snapshot = buildMachineSnapshot(component);
        if (machine == null) {
            if (!snapshot.managed) {
                return;
            }
            machine = new ServiceRepairStateMachine();
            sMachines.put(key, machine);
        }

        ServiceRepairStateMachine.Transition transition;
        try {
            transition = machine.handle(event, snapshot);
        } catch (Throwable t) {
            event("失败", "状态机处理异常: " + key + " " + t);
            return;
        }
        if (transition.isNoOp()) {
            return;
        }
        log("fsm " + key + ": " + transition.getFrom() + " -> " + transition.getTo()
                + " event=" + event.getType() + " effects=" + transition.getEffects().size());
        executeMachineEffects(component, transition);
    }

    private static void executeMachineEffects(ComponentName component,
            ServiceRepairStateMachine.Transition transition) {
        String key = component.flattenToString();
        for (ServiceRepairStateMachine.Effect effect : transition.getEffects()) {
            switch (effect.getType()) {
                case CANCEL_TIMERS:
                    cancelMachineTimer(key);
                    break;
                case SCHEDULE_TIMER:
                    scheduleMachineTimer(component, effect.getTimerType(),
                            effect.getDelayMs(), effect.getGeneration());
                    break;
                case REQUEST_REPAIR:
                    requestPackageRepair(component, effect.isAllowKill(),
                            effect.getReason() == null ? "fsm" : effect.getReason());
                    break;
                case REQUEST_RESTORE:
                    requestRestore(component, effect.getReason());
                    break;
                case CLEAR_CRASHED_MARKER:
                    Object bound = findBoundConnection(component);
                    if (bound != null) {
                        clearCrashedMarker(bound, component);
                    }
                    break;
                case NOTIFY_RECOVERED:
                    notifyMachineRecovered(component, transition.getFrom());
                    break;
                case REMOVE_FROM_BATCH:
                    removeFromPackageRepair(component);
                    break;
                default:
                    break;
            }
        }
    }

    private static void scheduleMachineTimer(ComponentName component,
            ServiceRepairStateMachine.TimerType timerType, long delayMs, long generation) {
        String key = component.flattenToString();
        cancelMachineTimer(key);
        if (sHandler == null) {
            log("machine timer skipped (handler null): " + key);
            return;
        }
        Runnable runnable = () -> {
            sMachineTimers.remove(key);
            handleServiceEvent(component,
                    ServiceRepairStateMachine.Event.timer(timerType, generation));
        };
        sMachineTimers.put(key, runnable);
        sHandler.postDelayed(runnable, Math.max(0L, delayMs));
    }

    private static void cancelMachineTimer(String key) {
        Runnable old = sMachineTimers.remove(key);
        if (old != null && sHandler != null) {
            sHandler.removeCallbacks(old);
        }
    }

    /** 扫描/补回后校验等辅助路径使用：延迟向状态机投递一次 SYNC，而不是旧的 scheduleCheck。 */
    private static void scheduleMachineSync(ComponentName component, long delayMs,
            String reason) {
        if (component == null || sHandler == null) {
            return;
        }
        String key = component.flattenToString();
        Runnable old = sPendingMachineSync.remove(key);
        if (old != null) {
            sHandler.removeCallbacks(old);
        }
        Runnable runnable = () -> {
            sPendingMachineSync.remove(key);
            handleServiceEvent(component, ServiceRepairStateMachine.Event.sync());
        };
        sPendingMachineSync.put(key, runnable);
        sHandler.postDelayed(runnable, Math.max(0L, delayMs));
        log("machine sync scheduled in " + delayMs + "ms (" + reason + "): " + key);
    }

    private static void notifyMachineRecovered(ComponentName component,
            ServiceRepairStateMachine.State from) {
        String key = component.flattenToString();
        if (from == ServiceRepairStateMachine.State.VERIFYING
                || from == ServiceRepairStateMachine.State.REPAIRING) {
            event("修复", "恢复成功: " + key, "action=fsm_verify_ok");
            showModuleToast("保活崩溃服务 " + getAppLabel(component));
        } else {
            event("保活", "服务已恢复就绪（无需额外重启）: " + key,
                    "action=fsm_recovered from=" + from);
        }
    }

    /**
     * RESTORING 状态只做 delta 补回，不加强杀：
     * 复用现有 reconcileMissing 一次恢复所有缺失的受管服务，避免和启用列表观察器互相打架。
     */
    private static void requestRestore(ComponentName component, String reason) {
        if (!isModuleEnabled()) {
            return;
        }
        String key = component.flattenToString();
        if (!isManaged(component) || sUserDisabledKeys.contains(key)) {
            return;
        }
        reconcileMissing("fsm_restore:" + (reason == null ? "" : reason));
        String enabled = readEnabled();
        if (enabled != null && containsComponent(enabled, component)) {
            sRestoreRetryScheduled.remove(key);
            handleServiceEvent(component, ServiceRepairStateMachine.Event.enabledListChanged());
        } else if (sRestoreRetryScheduled.add(key) && sHandler != null) {
            sHandler.postDelayed(() -> {
                sRestoreRetryScheduled.remove(key);
                handleServiceEvent(component, ServiceRepairStateMachine.Event.sync());
            }, 3000L);
        }
    }

    /** 单服务状态机只提出“需要修复”，包级协调器负责合并同包成员、一次关开/强杀。 */
    private static void requestPackageRepair(ComponentName component, boolean allowKill,
            String reason) {
        if (component == null || !isModuleEnabled()) {
            return;
        }
        String key = component.flattenToString();
        if (!isManaged(component) || sUserDisabledKeys.contains(key)) {
            log("skip repair request (not managed/user disabled): " + key);
            return;
        }
        if (!isPackageInstalled(component.getPackageName())) {
            // 应用已卸载时不要再关开/强杀一个不存在的包，等管理器同步锁定列表
            log("skip repair request (package not installed): " + key);
            return;
        }
        if (isDryRun()) {
            event("修复", "演练模式：将修复 " + key + "（不实际执行）",
                    "action=fsm_dry_run reason=" + reason);
            return;
        }

        String pkg = component.getPackageName();
        PackageRepairRequest request = sPackageRepairRequests.get(pkg);
        if (request == null) {
            request = new PackageRepairRequest(pkg, component, allowKill, reason,
                    SystemClock.elapsedRealtime());
            sPackageRepairRequests.put(pkg, request);
            if (!sPackageRepairQueue.contains(pkg)) {
                sPackageRepairQueue.add(pkg);
            }
        } else {
            // 同包任一成员要求“不加强杀”时，整包事务都不加强杀
            request.allowKill = request.allowKill && allowKill;
        }
        startNextPackageRepairIfIdle();
    }

    private static void startNextPackageRepairIfIdle() {
        if (sRepairTransactionActive || sHandler == null) {
            return;
        }
        while (!sPackageRepairQueue.isEmpty()) {
            String pkg = sPackageRepairQueue.remove(0);
            PackageRepairRequest request = sPackageRepairRequests.remove(pkg);
            if (request == null) {
                continue;
            }
            if (!isModuleEnabled()) {
                log("package repair skipped (module disabled): " + pkg);
                continue;
            }
            String managed = readConfig(KEY_SERVICES);
            String enabled = readEnabled();
            if (managed == null || enabled == null) {
                event("失败", "包级修复读取配置失败: " + pkg,
                        "action=fsm_repair_read_fail");
                scheduleMachineSync(request.trigger, 3000L, "repair_read_failed");
                continue;
            }
            ComponentKey trigger = ComponentKey.parse(request.trigger.flattenToString());
            List<ComponentKey> batch = RepairPolicy.planPackageBatch(
                    managed, enabled, pkg, trigger);
            if (batch.isEmpty()) {
                log("package repair batch empty, re-evaluate: " + pkg);
                handleServiceEvent(request.trigger, ServiceRepairStateMachine.Event.sync());
                continue;
            }
            startPackageRepair(request, batch);
            return;
        }
    }

    private static void startPackageRepair(PackageRepairRequest request,
            List<ComponentKey> batch) {
        sRepairTransactionActive = true;
        sRepairTransactionPackage = request.pkg;
        long generation = ++sRepairTransactionGeneration;
        sActiveRepairBatch.clear();
        for (ComponentKey key : batch) {
            sActiveRepairBatch.add(key.flatten());
        }

        // 先让所有批成员进入 REPAIRING，这样我们自己写入的“移除”不会被误判成 OEM/用户关闭
        for (String key : sActiveRepairBatch) {
            ComponentName component = ComponentName.unflattenFromString(key);
            if (component != null) {
                handleServiceEvent(component,
                        ServiceRepairStateMachine.Event.packageRepairStarted());
            }
        }

        SettingsWriter.WriteResult removeResult = settingsWriter().applyDelta(
                Collections.<ComponentKey>emptyList(), batch);
        if (removeResult.isFailure()) {
            event("失败", "包级修复关闭写入失败: " + request.pkg,
                    "action=fsm_repair_disable_fail status=" + removeResult.getStatus());
            failActivePackageRepair("disable_write_failed");
            return;
        }

        boolean fixMode = sFixModeCache != null
                ? "1".equals(sFixModeCache) : "1".equals(readConfig(KEY_FIX_MODE));
        String disabled = sFixDisabledCache != null
                ? sFixDisabledCache : readConfig(KEY_FIX_DISABLED);
        String contextPkg = sContext != null ? sContext.getPackageName() : null;
        Long lastKill = sLastPackageKillAt.get(request.pkg);
        boolean allowKill = request.allowKill && RepairPolicy.shouldKillPackage(
                request.pkg, contextPkg, fixMode, disabled,
                request.trigger.flattenToString(), lastKill,
                SystemClock.elapsedRealtime(), getFastFailCountForPackage(request.pkg));

        if (allowKill) {
            sLastPackageKillAt.put(request.pkg, SystemClock.elapsedRealtime());
            String killMeta = killAppProcesses(request.pkg);
            event("修复", "强杀应用进程后重启: " + request.pkg,
                    "action=fsm_kill_package pkg=" + request.pkg + " " + killMeta);
        }

        event("修复", "包级关开修复: " + request.pkg,
                "action=fsm_repair_start batch=" + sActiveRepairBatch
                        + " allowKill=" + allowKill
                        + " reason=" + request.reason
                        + " " + getPowerStateSummary());
        sHandler.postDelayed(() -> finishPackageRepairReAdd(generation, batch),
                TOGGLE_OFF_MS);
    }

    private static void finishPackageRepairReAdd(long generation, List<ComponentKey> batch) {
        if (generation != sRepairTransactionGeneration || !sRepairTransactionActive) {
            log("stale package repair completion ignored: gen=" + generation);
            return;
        }
        if (!isModuleEnabled()) {
            event("失败", "包级修复中止：模块已停用: " + sRepairTransactionPackage);
            failActivePackageRepair("module_disabled");
            return;
        }

        List<ComponentKey> add = new ArrayList<>();
        for (ComponentKey key : batch) {
            ComponentName component = new ComponentName(key.getPackageName(),
                    key.getClassName());
            if (!isManaged(component)) {
                continue;
            }
            if (sUserDisabledKeys.contains(key.flatten())) {
                continue;
            }
            add.add(key);
        }

        SettingsWriter.WriteResult addResult = settingsWriter().applyDelta(
                add, Collections.<ComponentKey>emptyList());
        if (addResult.isFailure()) {
            event("失败", "包级修复重新开启失败: " + sRepairTransactionPackage,
                    "action=fsm_repair_enable_fail status=" + addResult.getStatus());
            failActivePackageRepair("enable_write_failed");
            return;
        }

        if (!add.isEmpty()) {
            ensureAccessibilitySwitchOn();
        }
        event("修复", "包级修复已重新开启: " + sRepairTransactionPackage,
                "action=fsm_repair_applied add=" + add.size()
                        + " batch=" + batch.size());
        for (ComponentKey key : batch) {
            ComponentName component = new ComponentName(key.getPackageName(),
                    key.getClassName());
            handleServiceEvent(component,
                    ServiceRepairStateMachine.Event.packageRepairApplied());
        }
        finishActivePackageRepair();
    }

    private static void failActivePackageRepair(String reason) {
        List<String> batch = new ArrayList<>(sActiveRepairBatch);
        String pkg = sRepairTransactionPackage;
        for (String key : batch) {
            ComponentName component = ComponentName.unflattenFromString(key);
            if (component != null) {
                handleServiceEvent(component,
                        ServiceRepairStateMachine.Event.packageRepairFailed());
            }
        }
        event("失败", "包级修复失败: " + pkg + " (" + reason + ")",
                "action=fsm_repair_failed reason=" + reason);
        finishActivePackageRepair();
    }

    private static void finishActivePackageRepair() {
        sRepairTransactionActive = false;
        sRepairTransactionPackage = null;
        sActiveRepairBatch.clear();
        startNextPackageRepairIfIdle();
    }

    private static void removeFromPackageRepair(ComponentName component) {
        if (component == null) {
            return;
        }
        String key = component.flattenToString();
        String pkg = component.getPackageName();
        PackageRepairRequest request = sPackageRepairRequests.get(pkg);
        if (request != null && request.trigger.flattenToString().equals(key)) {
            sPackageRepairRequests.remove(pkg);
            sPackageRepairQueue.remove(pkg);
        }
        // 活动批次不在这里改：重新开启时会按 managed/userDisabled 过滤
    }

    private static boolean isRepairingKey(String key) {
        ServiceRepairStateMachine machine = sMachines.get(key);
        if (machine == null) {
            return false;
        }
        ServiceRepairStateMachine.State state = machine.getState();
        return state == ServiceRepairStateMachine.State.REPAIRING
                || state == ServiceRepairStateMachine.State.QUEUED;
    }

    private static int getFastFailCountForPackage(String pkg) {
        int max = 0;
        for (Map.Entry<String, ServiceRepairStateMachine> entry : sMachines.entrySet()) {
            ComponentName component = ComponentName.unflattenFromString(entry.getKey());
            if (component != null && pkg.equals(component.getPackageName())) {
                max = Math.max(max, entry.getValue().getFastFailCount());
            }
        }
        return max;
    }

    /** 重新从锁定列表同步所有状态机；配置/模块启停/热重载后调用。 */
    private static void syncMachines(String reason) {
        if (sContext == null || sHandler == null) {
            return;
        }
        String managed = readConfig(KEY_SERVICES);
        if (managed == null) {
            log("syncMachines skipped (managed unreadable): " + reason);
            return;
        }
        Set<String> managedKeys = new LinkedHashSet<>();
        if (!managed.isEmpty()) {
            for (String raw : managed.split(":")) {
                ComponentKey key = ComponentKey.parse(raw);
                if (key == null) {
                    continue;
                }
                ComponentName component = new ComponentName(key.getPackageName(),
                        key.getClassName());
                managedKeys.add(key.flatten());
                handleServiceEvent(component, ServiceRepairStateMachine.Event.sync());
            }
        }

        List<Map.Entry<String, ServiceRepairStateMachine>> entries =
                new ArrayList<>(sMachines.entrySet());
        for (Map.Entry<String, ServiceRepairStateMachine> entry : entries) {
            if (managedKeys.contains(entry.getKey())) {
                continue;
            }
            ComponentName component = ComponentName.unflattenFromString(entry.getKey());
            if (component != null) {
                handleServiceEvent(component,
                        ServiceRepairStateMachine.Event.managedChanged());
            }
            cancelMachineTimer(entry.getKey());
            Runnable pending = sPendingMachineSync.remove(entry.getKey());
            if (pending != null && sHandler != null) {
                sHandler.removeCallbacks(pending);
            }
            sUserDisabledKeys.remove(entry.getKey());
            sRestoreRetryScheduled.remove(entry.getKey());
            sMachines.remove(entry.getKey());
        }
        log("syncMachines (" + reason + "): managed=" + managedKeys.size());
    }

    /** 把启用列表变化投递给所有已存在的状态机；状态机会按 BOUND/REPAIRING/RESTORING 自行判断。 */
    private static void notifyMachinesEnabledListChanged() {
        for (String key : new ArrayList<>(sMachines.keySet())) {
            ComponentName component = ComponentName.unflattenFromString(key);
            if (component != null) {
                handleServiceEvent(component,
                        ServiceRepairStateMachine.Event.enabledListChanged());
            }
        }
    }

    static boolean isIgnoreSystemEnabled() {
        return sIgnoreSystemCache != null
                ? !"0".equals(sIgnoreSystemCache)
                : !"0".equals(readConfig(KEY_IGNORE_SYSTEM));
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

    static boolean isSelfOrSystemPackage(String pkg, String contextPkg) {
        return RepairPolicy.isSelfOrSystemPackage(pkg, contextPkg);
    }

    static boolean shouldKillPackage(String pkg, String contextPkg, boolean fixMode,
            String disabledList, String componentKey, Long lastPkgKillAt, long now) {
        return RepairPolicy.shouldKillPackage(
                pkg, contextPkg, fixMode, disabledList, componentKey, lastPkgKillAt, now);
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

    private static boolean isToastEnabled() {
        String value = readConfig(KEY_TOAST);
        return value == null || "1".equals(value);
    }

    static boolean isDebugEnabled() {
        return "1".equals(readConfig(KEY_DEBUG));
    }

    private static String getAppLabel(ComponentName component) {
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

    private static String joinLabels(Set<String> labels) {
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

    private static void showModuleToast(String text) {
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
                    log("showModuleToast failed: " + t);
                }
            });
        } catch (Throwable t) {
            log("post showModuleToast failed: " + t);
        }
    }

    private static boolean isObservedPowerSaveKey(String key) {
        if (key == null) {
            return false;
        }
        for (String item : OBSERVED_POWER_SAVE_KEYS) {
            if (item.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static void registerPowerSaveSettingObservers(
            ContentResolver resolver, ContentObserver observer) {
        for (String key : OBSERVED_POWER_SAVE_KEYS) {
            try {
                resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer);
            } catch (Throwable ignored) {
            }
            try {
                resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer);
            } catch (Throwable ignored) {
            }
            try {
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String getPowerStateSummary() {
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
        ContentResolver resolver = ctx.getContentResolver();
        StringBuilder oem = new StringBuilder();
        for (String key : OEM_POWER_SAVE_KEYS) {
            String val = readAnySetting(resolver, key);
            if (val != null && !val.isEmpty()) {
                if (oem.length() > 0) {
                    oem.append(',');
                }
                oem.append(key).append('=').append(val);
            }
        }
        if (oem.length() > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("oemPower={").append(oem).append('}');
        }
        return sb.length() == 0 ? "powerSave=unknown" : sb.toString();
    }

    private static String readAnySetting(ContentResolver resolver, String key) {
        try {
            String val = Settings.Global.getString(resolver, key);
            if (val != null) return val;
        } catch (Throwable ignored) {
        }
        try {
            String val = Settings.System.getString(resolver, key);
            if (val != null) return val;
        } catch (Throwable ignored) {
        }
        try {
            String val = Settings.Secure.getString(resolver, key);
            if (val != null) return val;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getPackageProcessSummary(String pkg) {
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

    /**
     * 只检查目标服务实际所在进程是否存活。
     *
     * <p>为什么不能只看 uid：
     * WebView 沙箱、隔离进程、同包其他子进程都可能共享 uid；旧逻辑会把它们当成“服务进程已启动”，
     * 从而白白消耗两次 2 秒启动宽限，拖慢真正需要修复的服务。
     *
     * <p>优先使用 ServiceInfo.processName 精确匹配；解析失败时回退到整包 uid 检查，保持兼容。
     */
    static boolean isServiceProcessRunning(ComponentName component) {
        if (component == null) {
            return false;
        }
        Context ctx = sContext;
        if (ctx == null) {
            return false;
        }
        String processName = null;
        try {
            ServiceInfo info = ctx.getPackageManager().getServiceInfo(component, 0);
            if (info != null && info.processName != null && !info.processName.isEmpty()) {
                processName = info.processName;
            }
        } catch (Throwable t) {
            log("resolve service process failed: " + component.flattenToShortString() + " " + t);
        }
        if (processName == null) {
            return isPackageProcessRunning(component.getPackageName());
        }
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo info : procs) {
                        if (info != null && processName.equals(info.processName)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("isServiceProcessRunning failed: " + t);
        }
        return false;
    }

    static boolean isPackageProcessRunning(String pkg) {
        Context ctx = sContext;
        if (ctx == null || pkg == null || pkg.isEmpty()) {
            return false;
        }
        final int uid;
        try {
            uid = ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            return false;
        }
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo info : procs) {
                        if (info != null && info.uid == uid) {
                            return true;
                        }
                    }
                }
                List<ActivityManager.RunningServiceInfo> svcs = am.getRunningServices(Integer.MAX_VALUE);
                if (svcs != null) {
                    for (ActivityManager.RunningServiceInfo info : svcs) {
                        if (info != null && info.uid == uid && info.pid > 0) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void unbindConnection(Object connection, String reason) {
        if (connection == null) {
            return;
        }
        try {
            Method unbindMethod = findMethod(connection.getClass(), "unbindLocked");
            if (unbindMethod != null) {
                Object lock = getFieldValue(connection, "mLock");
                if (lock != null) {
                    synchronized (lock) {
                        unbindMethod.invoke(connection);
                    }
                } else {
                    unbindMethod.invoke(connection);
                }
                log("unbindConnection (" + reason + "): successfully unbound " + connId(connection));
            } else {
                log("unbindConnection (" + reason + "): unbindLocked not found on " + connection.getClass());
            }
        } catch (Throwable t) {
            log("unbindConnection (" + reason + ") failed for " + connId(connection) + ": " + t);
        }
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
            // 不 break：工作资料/多用户下不同连接可能持有不同的 UserState，
            // 只看第一个连接会漏掉目标服务的真实绑定状态。
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
        try {
            Object info = getFieldValue(connection, "mAccessibilityServiceInfo");
            if (info != null) {
                setFieldValue(info, "crashed", false);
            }
        } catch (Throwable t) {
            log("clear crashed flag failed: " + t);
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
            boolean ok = Settings.Secure.putString(
                    context.getContentResolver(), SETTING_ENABLED, value);
            if (ok) {
                sLastObservedEnabled = value;
            }
            return ok;
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
        if (KEY_IGNORE_SYSTEM.equals(key)) {
            if (sIgnoreSystemCache != null) return sIgnoreSystemCache;
            String secureVal = readSecure(KEY_IGNORE_SYSTEM);
            if (secureVal != null) {
                sIgnoreSystemCache = secureVal;
                return secureVal;
            }
            return "1";
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
        if (KEY_TOAST.equals(key)) {
            if (sToastCache != null) return sToastCache;
            String secureVal = readSecure(KEY_TOAST);
            if (secureVal != null) {
                sToastCache = secureVal;
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
            sEventSession = Long.toHexString(System.currentTimeMillis());
        }
    }

    static void setEventSessionForTesting(String session) {
        synchronized (sEventLock) {
            sEventSession = session;
        }
    }

    static String getEventSessionForTesting() {
        synchronized (sEventLock) {
            return sEventSession;
        }
    }

    static void putEventLineForTesting(String line, long seq) {
        synchronized (sEventLock) {
            sEventLines.addLast(line);
            if (seq > sEventSeq) {
                sEventSeq = seq;
            }
        }
    }

    static List<String> getEventLinesForTesting() {
        synchronized (sEventLock) {
            return new ArrayList<>(sEventLines);
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

    static void putLastPackageKillAtForTesting(String pkg, long lastPkgKillAt) {
        if (pkg != null) {
            sLastPackageKillAt.put(pkg, lastPkgKillAt);
        }
    }

    static Long getLastPackageKillAtForTesting(String pkg) {
        return sLastPackageKillAt.get(pkg);
    }

    static int getHotReloadCountForTesting() {
        return sHotReloadCount;
    }
}
