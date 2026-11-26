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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 无障碍服务崩溃自动恢复模块（基于 Modern Xposed API 102，运行在 system_server 内）。
 *
 * <p>职责边界：本类仅保留 Xposed 生命周期入口、Hook 挂载、热重载跨代接管、
 * 系统广播/ContentObserver 胶水与命令路由；状态机调度、AMS 探测、配置缓存、事件缓冲分别
 * 由 {@link PackageRepairCoordinator}、{@link AmsInspector}、{@link ModuleConfigStore}
 * 与 {@link EventLogBuffer} 承担。
 */
public class AutoRepairEntry extends XposedModule {

    static final String TAG = "A11yRepair";
    private static final String CONNECTION_CLASS =
            "com.android.server.accessibility.AccessibilityServiceConnection";
    static final String HOOK_ID_BINDER_DIED = "a11y_repair_binder_died";
    static final String HOOK_ID_INIT_SERVICE = "a11y_repair_init_service";
    static final String HOOK_ID_BIND_LOCKED = "a11y_repair_bind_locked";
    static final String HOOK_ID_UNBIND_LOCKED = "a11y_repair_unbind_locked";

    // 热重载跨代状态键（仅使用 JDK/Android 标准类型，跨 ClassLoader 安全传递）
    static final String STATE_CONTEXT = "context";
    static final String STATE_CLASS_LOADER = "class_loader";
    private static final String STATE_HOT_RELOAD_COUNT = "hot_reload_count";

    // 配置项（Settings.Secure，由管理器写入）
    static final String KEY_ENABLE = "a11y_module_enable";
    static final String KEY_SERVICES = "a11y_module_services";
    static final String KEY_DRY_RUN = "a11y_module_dry_run";
    static final String KEY_SCAN = "a11y_module_scan";
    static final String KEY_DEBUG = "a11y_module_debug";
    static final String KEY_DEBUG_KILL = "a11y_module_debug_kill";
    /**
     * 实验开关：暂停“主动解绑重复连接”，只保留只读诊断。
     */
    static final String KEY_DEBUG_PAUSE_UNBIND = "a11y_module_debug_pause_unbind";
    static final String KEY_FIX_MODE = "a11y_module_fix_mode";
    static final String KEY_FIX_DISABLED = "a11y_module_fix_disabled";
    static final String KEY_IGNORE_SYSTEM = "a11y_module_ignore_system";
    static final String KEY_CMD_ACK = "a11y_module_cmd_ack";
    static final String KEY_TOAST = "a11y_module_toast";
    static final String KEY_LOADED = "a11y_module_loaded";
    static final String KEY_EVENTS = "a11y_module_events";
    static final String KEY_DUMP = "a11y_module_dump";

    static final int EVENT_LIMIT = EventLogBuffer.EVENT_LIMIT;
    static final int SECURE_MAX_CHARS = EventLogBuffer.SECURE_MAX_CHARS;
    static final int PULL_BATCH_LINES = EventLogBuffer.PULL_BATCH_LINES;
    static final int PULL_BATCH_MAX_CHARS = EventLogBuffer.PULL_BATCH_MAX_CHARS;

    static final String SETTING_ENABLED = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    static final String SETTING_ACCESSIBILITY_ENABLED = Settings.Secure.ACCESSIBILITY_ENABLED;
    static final String SETTING_BOOT_COUNT = "boot_count";

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

    /** 同一崩溃/初始化事件去重窗口（1.5s）。 */
    private static final long DEDUPE_MS = 1500L;
    /** 关开修复时“移除启用项 → 重新加入”之间的等待（500ms）。 */
    static final long TOGGLE_OFF_MS = 500L;
    /** 热重载后状态机重新对账延迟（1.5s）。 */
    private static final long SCAN_CONFIG_DELAY_MS = 1500L;
    /** 补回写入的最小合并间隔（250ms）。 */
    static final long CLOSE_MIN_INTERVAL_MS = 250L;

    static final long BOOT_GRACE_MS = RepairPolicy.BOOT_GRACE_MS;
    static final long BOOT_FIRST_CONNECT_WINDOW_MS = RepairPolicy.BOOT_FIRST_CONNECT_WINDOW_MS;
    static final long BOOT_WAVE_GRACE_MS = RepairPolicy.BOOT_WAVE_GRACE_MS;
    static final long PACKAGE_KILL_COOLDOWN_MS = RepairPolicy.PACKAGE_KILL_COOLDOWN_MS;

    static final int MAX_INIT_RETRY = 15;
    private static final long INIT_RETRY_DELAY_MS = 5000L;
    static final int MAX_HEARTBEAT_ATTEMPTS = 8;
    private static final long HEARTBEAT_RETRY_DELAY_MS = 5000L;
    private static final long HOT_RELOAD_JOIN_TIMEOUT_MS = 500L;

    private static volatile AutoRepairEntry sModuleInstance;
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
    private static boolean sHeartbeatRetryPending;
    private static volatile BroadcastReceiver sCommandReceiver;
    private static int sContextRetryCount;
    private static final HeartbeatTracker sHeartbeatTracker =
            new HeartbeatTracker(MAX_HEARTBEAT_ATTEMPTS);

    // 仅在 sHandler 线程读写的轻量事件去重与调试强杀状态
    private static final AtomicLong sConnEventSeq = new AtomicLong();
    private static final Map<String, Long> sLastDied = new HashMap<>();
    private static final Map<String, InitEventRecord> sLastInit = new HashMap<>();
    private static String sLastDebugKillPkg;
    private static long sLastDebugKillAt;

    /** 记录最近一次 initializeService 的连接对象与成败结果，供成败/换连接区分去重。 */
    private static final class InitEventRecord {
        final long at;
        final boolean success;
        final Object connection;

        InitEventRecord(long at, boolean success, Object connection) {
            this.at = at;
            this.success = success;
            this.connection = connection;
        }
    }

    public AutoRepairEntry() {
        sModuleInstance = this;
    }

    static XposedModule getModuleInstance() {
        return sModuleInstance;
    }

    static int getHotReloadCount() {
        return sHotReloadCount;
    }

    static long getLastHotReloadAt() {
        return sLastHotReloadAt;
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sModuleInstance = this;
        sProcessName = param.getProcessName();
        sIsSystemServer = param.isSystemServer();
        EventLogBuffer.log("Modern API " + safeApiVersion() + " loaded in " + sProcessName
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
     * 1. 等待旧代 HandlerThread 安全退出后再序列化跨代状态，消除快照期间的并发竞态；
     * 2. 注销旧代 ContentObserver 与 BroadcastReceiver；
     * 3. 封装纯 JDK/Android 框架类型 Map 传递给新代实例。
     */
    @Override
    public synchronized boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        if (!sIsSystemServer
                && sProcessName != null
                && !"system".equals(sProcessName)
                && !"android".equals(sProcessName)
                && !"system_server".equals(sProcessName)) {
            EventLogBuffer.log("skipping hot reload in non-system process: " + sProcessName);
            return false;
        }
        EventLogBuffer.log("onHotReloading in "
                + (sProcessName != null ? sProcessName : "system_server")
                + " (connections=" + AmsInspector.connectionCount()
                + ", hotReloadCount=" + sHotReloadCount + ")");

        // 1. 先刷新事件缓冲并停止旧代线程与系统监听器，确保快照时不存在并发写入
        EventLogBuffer.flushEventsNow();
        stopActiveResourcesForHotReload();

        // 2. 保存跨 ClassLoader 安全的状态快照
        try {
            Map<String, Object> state = new HashMap<>();
            state.put(STATE_HOT_RELOAD_COUNT, sHotReloadCount);
            ModuleConfigStore.saveHotReloadState(state);
            AmsInspector.saveHotReloadState(state);
            PackageRepairCoordinator.saveHotReloadState(state);
            EventLogBuffer.saveHotReloadState(state);
            param.setSavedInstanceState(state);
        } catch (Throwable t) {
            EventLogBuffer.log("onHotReloading state save failed: " + t);
        }

        // 3. 清理旧代内存引用
        clearInMemoryStateForHotReload();
        return true;
    }

    /**
     * 热重载完成阶段（运行在新代实例中）：
     * 恢复上一代连接、状态与事件缓冲，原子替换 Hook 并立即重新注册监听。
     */
    @Override
    public synchronized void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        sModuleInstance = this;
        sProcessName = param.getProcessName();
        sIsSystemServer = param.isSystemServer();
        int oldHookCount = param.getOldHookHandles() != null
                ? param.getOldHookHandles().size() : 0;

        restoreStateFromHotReload(param.getSavedInstanceState());
        sHotReloadCount++;
        sLastHotReloadAt = System.currentTimeMillis();

        ClassLoader targetLoader = resolveHotReloadClassLoader(param);
        sInstalled = false;
        initAndInstall(targetLoader, true);

        EventLogBuffer.event("配置",
                "模块已热重载生效 (第" + sHotReloadCount + "次, 继承连接="
                        + AmsInspector.connectionCount() + ", 原子替换Hook=" + oldHookCount + ")",
                "action=hot_reloaded count=" + sHotReloadCount
                        + " inheritedConns=" + AmsInspector.connectionCount()
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
        EventLogBuffer.attachHandler(null);
        AmsInspector.attachHandler(null);
        PackageRepairCoordinator.attachHandler(null);

        HandlerThread thread = sHandlerThread;
        sHandlerThread = null;
        if (thread != null) {
            try {
                thread.quitSafely();
                if (Thread.currentThread() != thread && thread.isAlive()) {
                    try {
                        thread.join(HOT_RELOAD_JOIN_TIMEOUT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        Context ctx = ModuleConfigStore.getContext();
        ContentObserver observer = sObserver;
        sObserver = null;
        if (observer != null && ctx != null) {
            try {
                ctx.getContentResolver().unregisterContentObserver(observer);
            } catch (Throwable t) {
                EventLogBuffer.log("unregister observer on hot reload failed: " + t);
            }
        }
        BroadcastReceiver receiver = sCommandReceiver;
        sCommandReceiver = null;
        if (receiver != null && ctx != null) {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (Throwable t) {
                EventLogBuffer.log("unregister receiver on hot reload failed: " + t);
            }
        }
    }

    private static void clearInMemoryStateForHotReload() {
        sInstalled = false;
        sScanPending = false;
        sHeartbeatRetryPending = false;
        sLastDebugKillPkg = null;
        sLastDebugKillAt = 0L;
        sLastDied.clear();
        sLastInit.clear();
        AmsInspector.clearForHotReload();
        PackageRepairCoordinator.clearForHotReload();
        ModuleConfigStore.clearForHotReload();
    }

    private static void restoreStateFromHotReload(Object savedObj) {
        if (!(savedObj instanceof Map)) {
            return;
        }
        Map<?, ?> state = (Map<?, ?>) savedObj;
        try {
            Object reloadCount = state.get(STATE_HOT_RELOAD_COUNT);
            if (reloadCount instanceof Integer) {
                sHotReloadCount = (Integer) reloadCount;
            }
            ModuleConfigStore.restoreHotReloadState(state);
            AmsInspector.restoreHotReloadState(state);
            PackageRepairCoordinator.restoreHotReloadState(state);
            EventLogBuffer.restoreHotReloadState(state);
        } catch (Throwable t) {
            EventLogBuffer.log("restoreStateFromHotReload error: " + t);
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
        ClassLoader cached = ModuleConfigStore.getClassLoader();
        if (cached != null) {
            return cached;
        }
        ClassLoader connLoader = AmsInspector.findConnectionClassLoader();
        if (connLoader != null) {
            return connLoader;
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
            ModuleConfigStore.setClassLoader(classLoader);
        }
        try {
            installHooks(classLoader, isHotReload);
        } catch (Throwable t) {
            EventLogBuffer.log("install failed: " + t);
        }
    }

    private void installHooks(ClassLoader classLoader, boolean isHotReload) {
        try {
            HandlerThread thread = new HandlerThread("A11yAutoRepair");
            // system_server 的默认未捕获异常处理器会直接杀死整机系统进程。
            // 模块线程捕获异常后只降级保活，绝不连坐 system_server；异常处理内不得访问 Settings。
            thread.setUncaughtExceptionHandler((t, e) -> {
                try {
                    Log.e(TAG, "A11yAutoRepair 线程未捕获异常，已拦截以避免 system_server 崩溃", e);
                } catch (Throwable ignored) {
                }
            });
            thread.start();
            sHandlerThread = thread;
            if (thread.getLooper() != null) {
                Handler handler = new Handler(thread.getLooper());
                sHandler = handler;
                EventLogBuffer.attachHandler(handler);
                AmsInspector.attachHandler(handler);
                PackageRepairCoordinator.attachHandler(handler);
                if (isHotReload) {
                    handler.post(AutoRepairEntry::scheduleReceiverInit);
                } else {
                    // onSystemServerStarting 时 AMS/ContentService 尚未就绪，延迟启动监听初始化
                    handler.postDelayed(AutoRepairEntry::scheduleReceiverInit, 10000L);
                }
            }
        } catch (Throwable t) {
            EventLogBuffer.log("handler thread init skipped or failed: " + t);
        }

        if (classLoader == null) {
            return;
        }

        try {
            Class<?> connClass = classLoader.loadClass(CONNECTION_CLASS);

            Method binderDiedMethod = AmsInspector.findMethod(connClass, "binderDied");
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
                EventLogBuffer.event("失败", "未找到 binderDied 方法");
            }

            Method initServiceMethod = AmsInspector.findMethod(connClass, "initializeService");
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
                EventLogBuffer.event("失败", "未找到 initializeService 方法");
            }

            // bindLocked/unbindLocked 运行在 AMS 持锁主线程：严禁读取 Settings，仅读纯内存缓存并被动登记 UserState
            Method bindLockedMethod = AmsInspector.findMethod(connClass, "bindLocked");
            if (bindLockedMethod != null) {
                hook(bindLockedMethod)
                        .setId(HOOK_ID_BIND_LOCKED)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object conn = chain.getThisObject();
                            AmsInspector.recordUserStatePassive(conn);
                            if (ModuleConfigStore.isDebugCacheEnabled()) {
                                ComponentName cn = AmsInspector.getComponentName(conn);
                                if (cn != null && ModuleConfigStore.isManagedCached(cn)) {
                                    EventLogBuffer.event("监听", "AMS发起绑定: "
                                            + cn.flattenToShortString()
                                            + " conn=" + AmsInspector.connId(conn));
                                }
                            }
                            return chain.proceed();
                        });
            }

            Method unbindLockedMethod = AmsInspector.findMethod(connClass, "unbindLocked");
            if (unbindLockedMethod != null) {
                hook(unbindLockedMethod)
                        .setId(HOOK_ID_UNBIND_LOCKED)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object conn = chain.getThisObject();
                            AmsInspector.recordUserStatePassive(conn);
                            if (ModuleConfigStore.isDebugCacheEnabled()) {
                                ComponentName cn = AmsInspector.getComponentName(conn);
                                if (cn != null && ModuleConfigStore.isManagedCached(cn)) {
                                    EventLogBuffer.event("监听", "AMS发起解绑: "
                                            + cn.flattenToShortString()
                                            + " conn=" + AmsInspector.connId(conn));
                                }
                            }
                            return chain.proceed();
                        });
            }

            if (!isHotReload) {
                EventLogBuffer.event("配置", "Modern API 102 模块已加载");
            }
        } catch (Throwable t) {
            if (!isHotReload) {
                EventLogBuffer.event("失败", "Hook 挂载异常: " + t);
            } else {
                EventLogBuffer.log("hook re-bind skipped or failed during hot reload: " + t);
            }
        }
    }

    private static void postEvent(boolean died, Object connection) {
        if (connection == null) {
            return;
        }
        try {
            ComponentName component = AmsInspector.getComponentName(connection);
            if (component == null) {
                return;
            }
            final long hookSeq = sConnEventSeq.incrementAndGet();
            final long hookAt = SystemClock.elapsedRealtime();
            Object info = AmsInspector.getFieldValue(connection, "mAccessibilityServiceInfo");
            boolean crashed = false;
            if (info != null) {
                Object crashedVal = AmsInspector.getFieldValue(info, "crashed");
                if (crashedVal instanceof Boolean) {
                    crashed = (Boolean) crashedVal;
                }
            }
            AmsInspector.recordUserStatePassive(connection);
            ConnectionBindProbe.Result bindProbe = AmsInspector.probeConnection(connection);
            boolean hasInterface = bindProbe.isBound();
            boolean bindProbeAvailable = bindProbe.isAvailable();
            String probeStrategy = bindProbe.getStrategy();
            String probeStatus = bindProbe.getStatus().name();
            Object ctxObj = AmsInspector.getFieldValue(connection, "mContext");
            Context context = ctxObj instanceof Context ? (Context) ctxObj : null;

            Handler handler = sHandler;
            if (context != null && (ModuleConfigStore.getContext() == null
                    || sObserver == null || sCommandReceiver == null)) {
                final boolean changed = ModuleConfigStore.getContext() != context;
                ModuleConfigStore.setContext(context);
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
                    long handleAt = SystemClock.elapsedRealtime();
                    boolean pauseUnbind = ModuleConfigStore.isDebugPauseUnbind();
                    String connKey = component.flattenToString();
                    if (ModuleConfigStore.isDebugCacheEnabled() || pauseUnbind) {
                        AmsInspector.logConnectionEvent(
                                died ? "died" : "init",
                                component,
                                connection,
                                AmsInspector.getKnownConnection(connKey),
                                AmsInspector.findAmsCurrentConnection(component),
                                finalCrashed,
                                hasInterface,
                                probeStrategy,
                                probeStatus,
                                pauseUnbind,
                                hookSeq,
                                hookAt,
                                handleAt);
                    }
                    if (!died) {
                        PackageRepairCoordinator.onFirstServiceConnected(handleAt);
                    }
                    String pkg = component.getPackageName();
                    boolean ignoreSystem = ModuleConfigStore.shouldIgnoreSystemApp(pkg);
                    if (!died) {
                        Object existing = AmsInspector.getKnownConnection(connKey);
                        boolean existingBound = existing != null
                                && AmsInspector.isConnectionBound(existing);
                        if (!hasInterface && existingBound && existing != connection) {
                            // 新连接初始化失败但旧连接仍然持有服务接口：保留旧连接，避免把失败重绑放大成整包关开。
                            if (ModuleConfigStore.isDebugCacheEnabled()) {
                                EventLogBuffer.log("ignore failed initializeService, existing connection still bound: "
                                        + component.flattenToShortString()
                                        + " old=" + AmsInspector.connId(existing)
                                        + " new=" + AmsInspector.connId(connection));
                            }
                            return;
                        }
                        boolean allowDedupe = RepairPolicy.shouldAttemptConnectionDedupe(
                                ModuleConfigStore.isModuleEnabled(),
                                ModuleConfigStore.isDryRun(),
                                ignoreSystem,
                                ModuleConfigStore.isManaged(component));
                        if (allowDedupe && existing != null && existing != connection
                                && existingBound) {
                            if (pauseUnbind) {
                                EventLogBuffer.event("调试",
                                        "重复连接解绑已暂停: " + component.flattenToShortString()
                                                + " old=" + AmsInspector.connId(existing)
                                                + " new=" + AmsInspector.connId(connection),
                                        "action=duplicate_unbind_paused");
                            } else {
                                AmsInspector.scheduleConnectionDedupe(
                                        component, connection, existing);
                            }
                        } else if (existing != null && existing != connection
                                && !existingBound && ModuleConfigStore.isDebugCacheEnabled()) {
                            EventLogBuffer.log("stale duplicate connection already dead: "
                                    + component.flattenToShortString()
                                    + " old=" + AmsInspector.connId(existing)
                                    + " new=" + AmsInspector.connId(connection));
                        }
                        AmsInspector.putKnownConnection(connKey, connection);
                        onInitializeService(component, finalCrashed, hasInterface,
                                bindProbeAvailable, probeStrategy, connection);
                    } else {
                        Object existing = AmsInspector.getKnownConnection(connKey);
                        if (existing == null || existing == connection
                                || !AmsInspector.isConnectionBound(existing)) {
                            AmsInspector.putKnownConnection(connKey, connection);
                        }
                        onBinderDied(component, finalCrashed, hasInterface, connection);
                    }
                } catch (Throwable t) {
                    EventLogBuffer.event("失败", "事件处理异常: " + t);
                }
            });
        } catch (Throwable t) {
            EventLogBuffer.event("失败", "事件读取失败: " + t);
        }
    }

    private static void onBinderDied(ComponentName component, boolean crashed,
            boolean hasInterface, Object connection) {
        String key = component.flattenToString();
        long now = SystemClock.elapsedRealtime();

        if (ModuleConfigStore.shouldIgnoreSystemApp(component.getPackageName())) {
            EventLogBuffer.log("ignore binderDied for system app: " + key);
            return;
        }
        if (!ModuleConfigStore.isManaged(component)) {
            Long lastNonManaged = sLastDied.get(key);
            if (lastNonManaged != null && now - lastNonManaged < DEDUPE_MS) {
                return;
            }
            sLastDied.put(key, now);
            EventLogBuffer.eventNonManaged(key, "未锁定服务挂掉(忽略) crashed=" + crashed);
            return;
        }
        if (ModuleConfigStore.getContext() == null) {
            EventLogBuffer.event("失败", "缺少系统 Context，忽略: " + key);
            return;
        }
        if (!ModuleConfigStore.isPackageUsable(component.getPackageName())) {
            EventLogBuffer.eventSkip("died:unavailable:" + component.getPackageName(), "检测",
                    "跳过不可用包的断开: " + key);
            return;
        }

        Object currentBound = AmsInspector.findBoundConnection(component);
        boolean newerConnectionBound = currentBound != null;
        if (newerConnectionBound) {
            AmsInspector.clearCrashedMarker(currentBound, component);
        } else {
            Long last = sLastDied.get(key);
            if (last != null && now - last < DEDUPE_MS) {
                return;
            }
            sLastDied.put(key, now);
        }

        EventLogBuffer.event("检测", "崩溃/断开: " + key,
                "hook=binderDied conn=" + AmsInspector.connId(connection)
                        + " crashed=" + crashed
                        + " iface=" + hasInterface
                        + " enabled=" + ModuleConfigStore.isModuleEnabled()
                        + " dryRun=" + ModuleConfigStore.isDryRun()
                        + " " + ModuleConfigStore.getPackageProcessSummary(
                                component.getPackageName())
                        + " " + ModuleConfigStore.getPowerStateSummary());
        PackageRepairCoordinator.handleServiceEvent(component,
                ServiceRepairStateMachine.Event.binderDied(newerConnectionBound));
    }

    private static void onInitializeService(ComponentName component, boolean crashed,
            boolean hasInterface, boolean bindProbeAvailable, String probeStrategy,
            Object connection) {
        String key = component.flattenToString();
        long now = SystemClock.elapsedRealtime();
        boolean success = !crashed && hasInterface;
        InitEventRecord lastInit = sLastInit.get(key);
        if (lastInit != null && RepairPolicy.shouldDedupeInit(
                lastInit.at, lastInit.success, lastInit.connection == connection,
                now, success, DEDUPE_MS)) {
            return;
        }
        sLastInit.put(key, new InitEventRecord(now, success, connection));

        if (!ModuleConfigStore.isManaged(component)) {
            EventLogBuffer.eventNonManaged(key, success
                    ? "未锁定服务连接(忽略)"
                    : "未锁定服务重绑失败(忽略) crashed=" + crashed + ", iface=" + hasInterface
                            + ", probe=" + probeStrategy);
            return;
        }
        if (!bindProbeAvailable && !crashed) {
            EventLogBuffer.eventSkip("init:probe-unknown:" + key, "警告",
                    "initializeService 绑定状态不可读，跳过重绑失败判定: " + key
                            + " | probe=" + probeStrategy);
            return;
        }

        if (success) {
            EventLogBuffer.log("[服务] initializeService 成功: " + key
                    + " conn=" + AmsInspector.connId(connection) + " probe=" + probeStrategy);
        } else {
            EventLogBuffer.event("检测", "重绑失败: " + key,
                    "hook=initializeService conn=" + AmsInspector.connId(connection)
                            + " crashed=" + crashed + " iface=" + hasInterface
                            + " probe=" + probeStrategy);
        }
        PackageRepairCoordinator.handleServiceEvent(component,
                ServiceRepairStateMachine.Event.initialized(success));
    }

    private static void writeHeartbeatOnce(String reason) {
        Context context = ModuleConfigStore.getContextOrSystem();
        if (context == null) {
            if (sHeartbeatTracker.onWriteFailure()) {
                scheduleHeartbeatRetry();
            } else {
                EventLogBuffer.log("heartbeat write deferred (context null, retry limit reached)");
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
                EventLogBuffer.log("[配置] 心跳已写入, bootCount=" + bootCount + " (" + reason + ")");
            } else {
                if (sHeartbeatTracker.onWriteFailure()) {
                    scheduleHeartbeatRetry();
                } else {
                    EventLogBuffer.log("heartbeat write returned false, retry limit reached");
                }
            }
        } catch (Throwable t) {
            if (sHeartbeatTracker.onWriteFailure()) {
                EventLogBuffer.log("heartbeat write deferred (system not ready): " + t);
                scheduleHeartbeatRetry();
            } else {
                EventLogBuffer.log("heartbeat auto-retry exhausted, waiting for passive/broadcast trigger: " + t);
            }
        }
    }

    private static void scheduleHeartbeatRetry() {
        Handler handler = sHandler;
        if (sHeartbeatRetryPending || handler == null) {
            return;
        }
        sHeartbeatRetryPending = true;
        handler.postDelayed(() -> {
            sHeartbeatRetryPending = false;
            writeHeartbeatOnce("retry");
        }, HEARTBEAT_RETRY_DELAY_MS);
    }

    private static void ensureObserver() {
        Context ctx = ModuleConfigStore.getContext();
        Handler handler = sHandler;
        if (ctx == null || handler == null) {
            return;
        }
        writeHeartbeatOnce("observer_check");
        EventLogBuffer.flushEventsNow();
        ensureCommandReceiver();
        if (sObserver != null) {
            return;
        }
        try {
            ContentResolver resolver = ctx.getContentResolver();
            if (ModuleConfigStore.getLastObservedEnabled() == null) {
                ModuleConfigStore.setLastObservedEnabled(ModuleConfigStore.readEnabled());
            }
            sObserver = new ContentObserver(new Handler(handler.getLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    String key = uri == null ? null : uri.getLastPathSegment();
                    if (SETTING_BOOT_COUNT.equals(key)) {
                        writeHeartbeatOnce("boot_count变更");
                        return;
                    }
                    if (SETTING_ENABLED.equals(key)) {
                        PackageRepairCoordinator.onEnabledListChanged();
                        return;
                    }
                    if (SETTING_ACCESSIBILITY_ENABLED.equals(key)) {
                        PackageRepairCoordinator.onAccessibilitySwitchChanged();
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
                        String prev = ModuleConfigStore.getDryRunCache();
                        String next = ModuleConfigStore.readSecure(KEY_DRY_RUN);
                        ModuleConfigStore.setDryRunCache(next);
                        if ("1".equals(prev) && !"1".equals(next)
                                && ModuleConfigStore.isModuleEnabled()) {
                            PackageRepairCoordinator.syncMachines("dry_run_off");
                        }
                        return;
                    }
                    if (KEY_DEBUG.equals(key)) {
                        ModuleConfigStore.refreshDebugCache();
                        return;
                    }
                    if (KEY_TOAST.equals(key)) {
                        ModuleConfigStore.refreshToastCache();
                        return;
                    }
                    if (KEY_FIX_MODE.equals(key)) {
                        ModuleConfigStore.refreshFixModeCache();
                        return;
                    }
                    if (KEY_FIX_DISABLED.equals(key)) {
                        ModuleConfigStore.refreshFixDisabledCache();
                        return;
                    }
                    if (KEY_IGNORE_SYSTEM.equals(key)) {
                        ModuleConfigStore.refreshIgnoreSystemCache();
                        return;
                    }
                    if (KEY_DEBUG_PAUSE_UNBIND.equals(key)) {
                        ModuleConfigStore.refreshDebugPauseUnbindCache();
                        return;
                    }
                    if (KEY_ENABLE.equals(key)) {
                        String prev = ModuleConfigStore.getModuleEnabledCache();
                        String next = ModuleConfigStore.readSecure(KEY_ENABLE);
                        ModuleConfigStore.setModuleEnabledCache(next);
                        if (prev != null && prev.equals(next)) {
                            EventLogBuffer.log("KEY_ENABLE observer unchanged, skip redundant sync: " + next);
                            return;
                        }
                        PackageRepairCoordinator.syncMachines("module_enabled_setting");
                    } else if (KEY_SERVICES.equals(key)) {
                        String prev = ModuleConfigStore.getManagedServicesCache();
                        String next = ModuleConfigStore.readSecure(KEY_SERVICES);
                        ModuleConfigStore.setManagedServicesCache(next);
                        if (prev != null && prev.equals(next)) {
                            EventLogBuffer.log("KEY_SERVICES observer unchanged, skip redundant sync: " + next);
                            return;
                        }
                        PackageRepairCoordinator.syncMachines("managed_setting");
                    }
                }
            };
            ModuleConfigStore.registerObservers(resolver, sObserver);
            sObserverAttempts = 0;
            EventLogBuffer.log("[配置] 配置监听已注册 (含 boot_count 实时同步)");
            ModuleConfigStore.warmupCaches();
            writeHeartbeatOnce("observer_ready");

            long sinceBoot = SystemClock.elapsedRealtime();
            long remainingBoot = PackageRepairCoordinator.getRemainingBootGraceMs(
                    sinceBoot, PackageRepairCoordinator.getFirstServiceConnectAt());
            if (remainingBoot > 0L) {
                PackageRepairCoordinator.syncMachines("observer_ready");
                PackageRepairCoordinator.schedulePostBootCheck(remainingBoot + 1000L);
            } else if (sHotReloadCount > 0) {
                handler.postDelayed(() -> PackageRepairCoordinator.syncMachines("hot_reload_init"),
                        SCAN_CONFIG_DELAY_MS);
            } else {
                PackageRepairCoordinator.syncMachines("observer_ready");
            }
        } catch (Throwable t) {
            sObserver = null;
            sObserverAttempts++;
            EventLogBuffer.log("register observer deferred (" + sObserverAttempts + "): " + t);
            if (sContextRetryCount >= MAX_INIT_RETRY && sObserverAttempts <= 3) {
                handler.postDelayed(AutoRepairEntry::ensureObserver, 5000L);
            } else if (sContextRetryCount >= MAX_INIT_RETRY) {
                EventLogBuffer.event("失败", "配置监听注册失败 (" + sObserverAttempts + "): " + t);
            }
        }
    }

    private static void scheduleReceiverInit() {
        if (ModuleConfigStore.getContext() == null) {
            ModuleConfigStore.getSystemContext();
        }
        if (ModuleConfigStore.getContext() != null) {
            ensureCommandReceiver();
            ensureObserver();
        }
        if ((sCommandReceiver == null || sObserver == null)
                && sContextRetryCount < MAX_INIT_RETRY) {
            sContextRetryCount++;
            Handler handler = sHandler;
            if (handler != null) {
                handler.postDelayed(AutoRepairEntry::scheduleReceiverInit, INIT_RETRY_DELAY_MS);
            }
        }
    }

    private static synchronized void ensureCommandReceiver() {
        if (sCommandReceiver != null) {
            return;
        }
        Context ctx = ModuleConfigStore.getContextOrSystem();
        if (ctx == null) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    if (!ACTION_SERVICE_CMD.equals(intent.getAction())) return;
                    String payload = intent.getStringExtra(EXTRA_PAYLOAD);
                    if (payload == null || payload.isEmpty()) return;
                    final ResultReceiver resultReceiver = extractResultReceiver(intent);
                    EventLogBuffer.log("received broadcast cmd: " + payload);
                    Handler handler = sHandler;
                    if (handler != null) {
                        handler.post(() -> {
                            try {
                                applyServiceCommand(payload, resultReceiver);
                            } catch (Throwable t) {
                                EventLogBuffer.event("失败", "广播指令执行异常: " + t);
                            }
                        });
                    } else {
                        applyServiceCommand(payload, resultReceiver);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_SERVICE_CMD);
            boolean registered = false;
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    ctx.registerReceiver(receiver, filter, PERMISSION_SEND_CMD, sHandler,
                            Context.RECEIVER_EXPORTED);
                    registered = true;
                } catch (Throwable t) {
                    EventLogBuffer.log("registerReceiver with permission failed: " + t
                            + ", retrying without permission");
                }
                if (!registered) {
                    try {
                        ctx.registerReceiver(receiver, filter, null, sHandler,
                                Context.RECEIVER_EXPORTED);
                        registered = true;
                    } catch (Throwable t2) {
                        EventLogBuffer.log("registerReceiver without permission failed: " + t2);
                    }
                }
            } else {
                try {
                    ctx.registerReceiver(receiver, filter, PERMISSION_SEND_CMD, sHandler);
                    registered = true;
                } catch (Throwable t) {
                    EventLogBuffer.log("registerReceiver with permission failed: " + t
                            + ", retrying without permission");
                }
                if (!registered) {
                    try {
                        ctx.registerReceiver(receiver, filter, null, sHandler);
                        registered = true;
                    } catch (Throwable t2) {
                        EventLogBuffer.log("registerReceiver without permission failed: " + t2);
                    }
                }
            }
            if (registered) {
                sCommandReceiver = receiver;
                EventLogBuffer.log("[配置] 系统广播指令监听已就绪 (方案A)");
            }
        } catch (Throwable t) {
            EventLogBuffer.event("失败", "注册广播接收器异常: " + t);
        }
    }

    private static void scheduleScan(long delayMs, String reason) {
        Handler handler = sHandler;
        if (sScanPending || handler == null) {
            return;
        }
        sScanPending = true;
        handler.postDelayed(() -> {
            sScanPending = false;
            try {
                PackageRepairCoordinator.runManualScan(reason);
            } catch (Throwable t) {
                EventLogBuffer.event("失败", "扫描异常: " + t);
            }
        }, delayMs);
        EventLogBuffer.log("scan scheduled in " + delayMs + "ms (" + reason + ")");
    }

    private static void notifyCommandAck() {
        Context context = ModuleConfigStore.getContextOrSystem();
        if (context == null) {
            return;
        }
        try {
            Settings.Secure.putString(context.getContentResolver(), KEY_CMD_ACK,
                    String.valueOf(SystemClock.elapsedRealtimeNanos()));
        } catch (Throwable t) {
            EventLogBuffer.log("notifyCommandAck failed: " + t);
        }
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
            EventLogBuffer.writeModuleDump(receiver);
            notifyCommandAck();
            return;
        }
        if ("scan".equals(body)) {
            scheduleScan(0L, "manual scan");
            EventLogBuffer.event("扫描", "收到手动扫描指令");
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
            EventLogBuffer.handlePullLogsCommand(component, receiver);
            return;
        }
        if ("ack_logs".equals(action)) {
            EventLogBuffer.handleAckLogsCommand(component);
            return;
        }

        Context ctx = ModuleConfigStore.getContext();
        if ("enable_module".equals(action)) {
            boolean enable = "1".equals(component);
            String newVal = enable ? "1" : "0";
            String prev = ModuleConfigStore.getModuleEnabledCache() != null
                    ? ModuleConfigStore.getModuleEnabledCache()
                    : ModuleConfigStore.readConfig(KEY_ENABLE);
            boolean changed = !newVal.equals(prev);
            ModuleConfigStore.setModuleEnabledCache(newVal);
            if (ctx != null && (changed || !newVal.equals(ModuleConfigStore.readSecure(KEY_ENABLE)))) {
                try {
                    Settings.Secure.putString(ctx.getContentResolver(), KEY_ENABLE, newVal);
                } catch (Throwable ignored) {
                }
            }
            if (changed) {
                EventLogBuffer.event("配置", enable ? "模块已启用 (广播指令)" : "模块已停用 (广播指令)");
                PackageRepairCoordinator.syncMachines("enable_module cmd");
            }
            notifyCommandAck();
            return;
        }

        if ("dry_run".equals(action)) {
            String newVal = "1".equals(component) ? "1" : "0";
            String prev = ModuleConfigStore.getDryRunCache() != null
                    ? ModuleConfigStore.getDryRunCache()
                    : ModuleConfigStore.readConfig(KEY_DRY_RUN);
            ModuleConfigStore.setDryRunCache(newVal);
            if (ctx != null && !newVal.equals(ModuleConfigStore.readSecure(KEY_DRY_RUN))) {
                try {
                    Settings.Secure.putString(ctx.getContentResolver(), KEY_DRY_RUN, newVal);
                } catch (Throwable ignored) {
                }
            }
            if ("1".equals(prev) && !"1".equals(newVal) && ModuleConfigStore.isModuleEnabled()) {
                PackageRepairCoordinator.syncMachines("dry_run_cmd_off");
            }
            notifyCommandAck();
            return;
        }

        if ("debug".equals(action)) {
            String newVal = "1".equals(component) ? "1" : "0";
            ModuleConfigStore.setDebugCache(newVal);
            if (ctx != null && !newVal.equals(ModuleConfigStore.readSecure(KEY_DEBUG))) {
                try {
                    Settings.Secure.putString(ctx.getContentResolver(), KEY_DEBUG, newVal);
                } catch (Throwable ignored) {
                }
            }
            notifyCommandAck();
            return;
        }

        if ("toast".equals(action)) {
            String newVal = "1".equals(component) ? "1" : "0";
            ModuleConfigStore.setToastCache(newVal);
            if (ctx != null && !newVal.equals(ModuleConfigStore.readSecure(KEY_TOAST))) {
                try {
                    Settings.Secure.putString(ctx.getContentResolver(), KEY_TOAST, newVal);
                } catch (Throwable ignored) {
                }
            }
            notifyCommandAck();
            return;
        }

        if ("sync_services".equals(action)) {
            String prev = ModuleConfigStore.getManagedServicesCache() != null
                    ? ModuleConfigStore.getManagedServicesCache()
                    : ModuleConfigStore.readConfig(KEY_SERVICES);
            boolean changed = !component.equals(prev);
            ModuleConfigStore.setManagedServicesCache(component);
            Set<String> managedKeys = new LinkedHashSet<>();
            for (ComponentKey key : EnabledListDelta.parse(component)) {
                managedKeys.add(key.flatten());
            }
            PackageRepairCoordinator.retainUserDisabledForManaged(managedKeys);
            if (ctx != null && (changed || !component.equals(ModuleConfigStore.readSecure(KEY_SERVICES)))) {
                try {
                    Settings.Secure.putString(ctx.getContentResolver(), KEY_SERVICES, component);
                } catch (Throwable ignored) {
                }
            }
            if (changed) {
                PackageRepairCoordinator.syncMachines("sync_services cmd");
                EventLogBuffer.event("配置", "模块已生效最新锁定列表: " + component);
            } else {
                EventLogBuffer.log("sync_services unchanged: " + component);
            }
            notifyCommandAck();
            return;
        }

        if ("sync_fixmode".equals(action)) {
            int sep = component.indexOf('#');
            String modeStr = sep >= 0 ? component.substring(0, sep) : component;
            String disabled = sep >= 0 ? component.substring(sep + 1) : "";
            String prevMode = ModuleConfigStore.getFixModeCache() != null
                    ? ModuleConfigStore.getFixModeCache()
                    : ModuleConfigStore.readConfig(KEY_FIX_MODE);
            String prevDisabled = ModuleConfigStore.getFixDisabledCache() != null
                    ? ModuleConfigStore.getFixDisabledCache()
                    : ModuleConfigStore.readConfig(KEY_FIX_DISABLED);
            boolean changed = !modeStr.equals(prevMode) || !disabled.equals(prevDisabled);
            ModuleConfigStore.setFixModeCache(modeStr);
            ModuleConfigStore.setFixDisabledCache(disabled);
            if (ctx != null) {
                try {
                    Settings.Secure.putString(ctx.getContentResolver(), KEY_FIX_MODE, modeStr);
                    Settings.Secure.putString(ctx.getContentResolver(), KEY_FIX_DISABLED, disabled);
                } catch (Throwable ignored) {
                }
            }
            if (changed) {
                EventLogBuffer.event("配置", "模块已生效强杀配置: fixmode=" + modeStr);
            } else {
                EventLogBuffer.log("sync_fixmode unchanged: fixmode=" + modeStr);
            }
            notifyCommandAck();
            return;
        }

        if ("sync_ignore_system".equals(action)) {
            String prev = ModuleConfigStore.getIgnoreSystemCache() != null
                    ? ModuleConfigStore.getIgnoreSystemCache()
                    : ModuleConfigStore.readConfig(KEY_IGNORE_SYSTEM);
            boolean changed = !component.equals(prev);
            ModuleConfigStore.setIgnoreSystemCache(component);
            if (ctx != null) {
                try {
                    Settings.Secure.putString(ctx.getContentResolver(), KEY_IGNORE_SYSTEM, component);
                } catch (Throwable ignored) {
                }
            }
            if (changed) {
                EventLogBuffer.event("配置", "模块已生效忽略系统应用配置: ignoreSystem=" + component);
            } else {
                EventLogBuffer.log("sync_ignore_system unchanged: ignoreSystem=" + component);
            }
            notifyCommandAck();
            return;
        }

        if ("debug_kill".equals(action)) {
            int slash = component.indexOf('/');
            String pkg = slash > 0 ? component.substring(0, slash) : component;
            sLastDebugKillPkg = pkg;
            sLastDebugKillAt = SystemClock.elapsedRealtime();
            String killMeta = PackageRepairCoordinator.killAppProcesses(pkg);
            EventLogBuffer.event("调试", "已触发进程强杀: " + component,
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
        ComponentKey targetKeyObj = ComponentKey.of(
                target.getPackageName(), target.getClassName());
        if (targetKeyObj == null) {
            return;
        }
        String targetKey = targetKeyObj.flatten();
        if (!enable) {
            PackageRepairCoordinator.markUserDisabled(targetKey, true);
            PackageRepairCoordinator.handleServiceEvent(target,
                    ServiceRepairStateMachine.Event.userDisable());
            PackageRepairCoordinator.removeFromPackageRepair(target);
        } else {
            PackageRepairCoordinator.markUserDisabled(targetKey, false);
        }

        // 手动开关同样走 SettingsWriter 串行 delta 通道，与模块自动写回共享同一把短锁及自写识别
        SettingsWriter.WriteResult writeResult = ModuleConfigStore.settingsWriter().applyDelta(
                enable ? Collections.singletonList(targetKeyObj)
                        : Collections.<ComponentKey>emptyList(),
                enable ? Collections.<ComponentKey>emptyList()
                        : Collections.singletonList(targetKeyObj));
        if (writeResult.isFailure()) {
            EventLogBuffer.event("失败",
                    "手动" + (enable ? "开启" : "关闭") + "失败："
                            + ModuleConfigStore.enabledReadSkipReason() + ": " + component);
            notifyCommandAck();
            return;
        }
        if (writeResult.isUnchanged()) {
            EventLogBuffer.log("manual command no-op: " + component);
            if (enable) {
                PackageRepairCoordinator.handleServiceEvent(target,
                        ServiceRepairStateMachine.Event.userEnable());
            }
            notifyCommandAck();
            return;
        }
        if (enable) {
            ModuleConfigStore.ensureAccessibilitySwitchOn();
            PackageRepairCoordinator.handleServiceEvent(target,
                    ServiceRepairStateMachine.Event.userEnable());
        }
        EventLogBuffer.event("保活", "手动" + (enable ? "开启" : "关闭") + ": " + component);
        notifyCommandAck();
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
            EventLogBuffer.log("extractResultReceiver failed: " + t);
            return null;
        }
    }

    private static void scheduleDebugKill() {
        Handler handler = sHandler;
        if (handler == null) {
            return;
        }
        handler.postDelayed(() -> {
            try {
                runDebugKill();
            } catch (Throwable t) {
                EventLogBuffer.log("debug kill error: " + t);
            }
        }, 500L);
    }

    private static void runDebugKill() {
        Context ctx = ModuleConfigStore.getContext();
        if (ctx == null) {
            return;
        }
        if (!"1".equals(ModuleConfigStore.readConfig(KEY_DEBUG))) {
            EventLogBuffer.log("debug kill skipped (debug disabled)");
            return;
        }
        String value = ModuleConfigStore.readConfig(KEY_DEBUG_KILL);
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
        long now = SystemClock.elapsedRealtime();
        if (pkg.equals(sLastDebugKillPkg)
                && now - sLastDebugKillAt >= 0L && now - sLastDebugKillAt < 2000L) {
            EventLogBuffer.log("debug kill skipped (already executed via broadcast): " + pkg);
            return;
        }

        final int uid;
        try {
            uid = ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            EventLogBuffer.log("debug kill: package not found: " + pkg);
            return;
        }

        Set<Integer> pids = new LinkedHashSet<>();
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
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
            EventLogBuffer.log("debug kill: enumerate failed: " + t);
        }

        if (pids.isEmpty()) {
            EventLogBuffer.log("debug kill: no pid found for " + pkg);
            return;
        }
        EventLogBuffer.log("debug kill: " + pkg + " uid=" + uid + " pids=" + pids);
        PackageRepairCoordinator.killAppProcesses(pkg);
    }

    // ── 包内转发 / 单元测试辅助接口（保持既有测试与外部签名兼容） ─────────────

    static long getBootGraceDeadlineMs(long firstConnectAt) {
        return PackageRepairCoordinator.getBootGraceDeadlineMs(firstConnectAt);
    }

    static long getRemainingBootGraceMs(long now, long firstConnectAt) {
        return PackageRepairCoordinator.getRemainingBootGraceMs(now, firstConnectAt);
    }

    static ConnectionBindProbe.Result probeWithConnectionLock(Object connection) {
        return AmsInspector.probeWithConnectionLock(connection);
    }

    static boolean isComponentInBindingServices(Object userState, Object lock, Object target) {
        return AmsInspector.isComponentInBindingServices(userState, lock, target);
    }

    static boolean isIgnoreSystemEnabled() {
        return ModuleConfigStore.isIgnoreSystemEnabled();
    }

    static boolean isSystemPackage(String pkg) {
        return ModuleConfigStore.isSystemPackage(pkg);
    }

    static boolean shouldIgnoreSystemApp(String pkg) {
        return ModuleConfigStore.shouldIgnoreSystemApp(pkg);
    }

    static boolean isSelfOrSystemPackage(String pkg, String contextPkg) {
        return RepairPolicy.isSelfOrSystemPackage(pkg, contextPkg);
    }

    static boolean shouldKillPackage(String pkg, String contextPkg, boolean fixMode,
            String disabledList, String componentKey, Long lastPkgKillAt, long now) {
        return RepairPolicy.shouldKillPackage(
                pkg, contextPkg, fixMode, disabledList, componentKey, lastPkgKillAt, now);
    }

    static boolean isDebugEnabled() {
        return ModuleConfigStore.isDebugCacheEnabled();
    }

    static void normalizeEventLinesSessionLocked(Deque<String> lines, String targetSession) {
        EventLogBuffer.normalizeEventLinesSessionLocked(lines, targetSession);
    }

    static String buildTailWithinCharLimit(Iterable<String> lines, int maxChars) {
        return EventLogBuffer.buildTailWithinCharLimit(lines, maxChars);
    }

    static String truncateToCharLimit(String text, int maxChars) {
        return EventLogBuffer.truncateToCharLimit(text, maxChars);
    }

    static int ackEventsUpToLocked(Deque<String> lines, String currentSession,
            String ackSession, long ackSeq) {
        return EventLogBuffer.ackEventsUpToLocked(lines, currentSession, ackSession, ackSeq);
    }

    static List<ArrayList<String>> buildPullBatchesLocked(Iterable<String> lines,
            String currentSession, String clientSession, long clientLastSeq,
            int maxBatchLines, int maxBatchChars) {
        return EventLogBuffer.buildPullBatchesLocked(lines, currentSession, clientSession,
                clientLastSeq, maxBatchLines, maxBatchChars);
    }

    static void resetForTesting() {
        stopActiveResourcesForHotReload();
        clearInMemoryStateForHotReload();
        ModuleConfigStore.resetForTesting();
        AmsInspector.resetForTesting();
        PackageRepairCoordinator.resetForTesting();
        EventLogBuffer.resetForTesting();
        sProcessName = null;
        sIsSystemServer = false;
        sHotReloadCount = 0;
        sLastHotReloadAt = 0L;
    }

    static void setEventSessionForTesting(String session) {
        EventLogBuffer.setEventSessionForTesting(session);
    }

    static String getEventSessionForTesting() {
        return EventLogBuffer.getEventSessionForTesting();
    }

    static void putEventLineForTesting(String line, long seq) {
        EventLogBuffer.putEventLineForTesting(line, seq);
    }

    static List<String> getEventLinesForTesting() {
        return EventLogBuffer.getEventLinesForTesting();
    }

    static void putConnectionForTesting(String key, Object conn) {
        AmsInspector.putConnectionForTesting(key, conn);
    }

    static Map<String, Object> getConnectionsForTesting() {
        return AmsInspector.getConnectionsForTesting();
    }

    static void setFirstServiceConnectAtForTesting(long firstConnectAt) {
        PackageRepairCoordinator.setFirstServiceConnectAtForTesting(firstConnectAt);
    }

    static long getFirstServiceConnectAtForTesting() {
        return PackageRepairCoordinator.getFirstServiceConnectAtForTesting();
    }

    static void putLastPackageKillAtForTesting(String pkg, long lastPkgKillAt) {
        PackageRepairCoordinator.putLastPackageKillAtForTesting(pkg, lastPkgKillAt);
    }

    static Long getLastPackageKillAtForTesting(String pkg) {
        return PackageRepairCoordinator.getLastPackageKillAtForTesting(pkg);
    }

    static int getHotReloadCountForTesting() {
        return sHotReloadCount;
    }

    static void putUserDisabledKeyForTesting(String key) {
        PackageRepairCoordinator.putUserDisabledKeyForTesting(key);
    }

    static Set<String> getUserDisabledKeysForTesting() {
        return PackageRepairCoordinator.getUserDisabledKeysForTesting();
    }
}
