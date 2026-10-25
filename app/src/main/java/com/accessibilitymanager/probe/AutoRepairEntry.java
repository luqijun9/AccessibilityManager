package com.accessibilitymanager.probe;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 无障碍服务崩溃自动恢复模块（基于 Modern Xposed API 102，运行在 system_server 内）。
 */
public class AutoRepairEntry extends XposedModule {

    private static final String TAG = "A11yRepair";
    private static final String CONNECTION_CLASS =
            "com.android.server.accessibility.AccessibilityServiceConnection";

    // 配置项（Settings.Secure，由管理器写入）
    private static final String KEY_ENABLE = "a11y_module_enable";
    private static final String KEY_SERVICES = "a11y_module_services";
    private static final String KEY_DRY_RUN = "a11y_module_dry_run";
    private static final String KEY_SCAN = "a11y_module_scan";
    private static final String KEY_DEBUG = "a11y_module_debug";
    private static final String KEY_DEBUG_KILL = "a11y_module_debug_kill";
    private static final String KEY_LOADED = "a11y_module_loaded";
    private static final String KEY_EVENTS = "a11y_module_events";
    private static final int EVENT_LIMIT = 40;
    private static final String SETTING_ENABLED =
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;

    private static final long DEDUPE_MS = 1500L;
    private static final long SETTLE_MS = 3000L;
    private static final long TOGGLE_OFF_MS = 500L;
    private static final long VERIFY_MS = 3500L;
    private static final long SUPPRESS_MS = 10000L;
    private static final long RETRY_COOLDOWN_MS = 20000L;
    private static final int MAX_ATTEMPTS = 2;
    private static final long RESET_AFTER_MS = 5 * 60 * 1000L;
    private static final long UNSTABLE_WINDOW_MS = 2 * 60 * 1000L;
    private static final int UNSTABLE_THRESHOLD = 3;
    private static final long UNSTABLE_COOLDOWN_MS = 10 * 60 * 1000L;
    private static final int GLOBAL_LIMIT = 5;
    private static final long GLOBAL_WINDOW_MS = 10 * 60 * 1000L;
    private static final long REPAIR_STUCK_MS = 15000L;
    private static final long SCAN_INIT_DELAY_MS = 10000L;
    private static final long SCAN_CONFIG_DELAY_MS = 1500L;

    // 关闭补回配置
    private static final long CLOSE_MIN_INTERVAL_MS = 250L;
    private static final long RECONCILE_CONFIG_DELAY_MS = 500L;
    // 补回写入后延迟检查是否真正绑定（force-stop 后可能“列表已恢复但未绑定”）
    private static final long BIND_VERIFY_DELAY_MS = 2000L;
    private static final long REPAIR_REMOVE_WINDOW_MS = TOGGLE_OFF_MS + 1500L;
    private static final long BOOT_GRACE_MS = 60000L;

    private static volatile AutoRepairEntry sModuleInstance;
    private static volatile Context sContext;
    private static Handler sHandler;
    private static boolean sInstalled;
    private static volatile ContentObserver sObserver;
    private static int sObserverAttempts;
    private static boolean sScanPending;
    private static boolean sHeartbeatWritten;

    // 事件缓冲
    private static final Object sEventLock = new Object();
    private static final Deque<String> sEventLines = new ArrayDeque<>();
    private static final String sEventSession =
            Long.toHexString(System.currentTimeMillis());
    private static long sEventSeq;

    // 状态缓存（仅在 sHandler 线程读写）
    private static boolean sRepairInProgress;
    private static long sRepairStartedAt;
    private static String sRepairKey;
    private static final Map<String, Long> sLastDied = new HashMap<>();
    private static final Map<String, Runnable> sPendingCheck = new HashMap<>();
    private static final Map<String, Runnable> sVerifyRun = new HashMap<>();
    private static final Map<String, Long> sSuppressUntil = new HashMap<>();
    private static final Map<String, Long> sBlockedUntil = new HashMap<>();
    private static final Map<String, Integer> sAttempts = new HashMap<>();
    private static final Map<String, Long> sLastAttemptAt = new HashMap<>();
    private static final Map<String, Deque<Long>> sCrashHistory = new HashMap<>();
    private static final Deque<Long> sRepairHistory = new ArrayDeque<>();
    private static final Map<String, Object> sConnections = new HashMap<>();
    private static boolean sBoundCheckAvailable;
    private static boolean sBoundCheckErrorLogged;

    private static boolean sReconcilePending;
    private static boolean sReconcileRetryPending;
    private static boolean sBindVerifyPending;
    private static long sLastReconcileWriteAt;
    private static final Map<String, Long> sSelfRemovedUntil = new HashMap<>();

    // 反射缓存
    private static final Map<String, Field> sFieldCache = new ConcurrentHashMap<>();
    private static final Map<String, Method> sMethodCache = new ConcurrentHashMap<>();

    public AutoRepairEntry() {
        sModuleInstance = this;
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        initAndInstall(param.getClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if ("android".equals(param.getPackageName())) {
            initAndInstall(param.getClassLoader());
        }
    }

    private synchronized void initAndInstall(ClassLoader classLoader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        sModuleInstance = this;
        try {
            installHooks(classLoader);
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    private void installHooks(ClassLoader classLoader) {
        HandlerThread thread = new HandlerThread("A11yAutoRepair");
        thread.start();
        sHandler = new Handler(thread.getLooper());

        try {
            Class<?> connClass = classLoader.loadClass(CONNECTION_CLASS);

            Method binderDiedMethod = findMethod(connClass, "binderDied");
            if (binderDiedMethod != null) {
                hook(binderDiedMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
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
                hook(initServiceMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            postEvent(false, chain.getThisObject());
                            return result;
                        });
            } else {
                event("失败", "未找到 initializeService 方法");
            }

            event("配置", "Modern API 102 模块已加载");
        } catch (Throwable t) {
            event("失败", "Hook 挂载异常: " + t);
        }
    }

    private static void event(String category, String message) {
        String line = "[" + category + "] " + message;
        log(line);
        appendEvent(line);
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
            if (context != null && (sContext == null || sObserver == null)) {
                final boolean changed = sContext != context;
                sContext = context;
                if (handler != null) {
                    handler.post(() -> {
                        if (changed) {
                            sObserverAttempts = 0;
                        }
                        ensureObserver();
                    });
                }
            }
            if (handler == null) {
                return;
            }
            final boolean finalCrashed = crashed;
            handler.post(() -> {
                try {
                    sConnections.put(component.flattenToString(), connection);
                    if (died) {
                        onBinderDied(component, finalCrashed, hasInterface, connection);
                    } else {
                        onInitializeService(component, finalCrashed, hasInterface, connection);
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
        if (sHeartbeatWritten || sContext == null) {
            return;
        }
        try {
            int bootCount = Settings.Global.getInt(
                    sContext.getContentResolver(), "boot_count", -1);
            Settings.Secure.putString(sContext.getContentResolver(), KEY_LOADED,
                    bootCount + "|1");
            sHeartbeatWritten = true;
            event("配置", "心跳已写入, bootCount=" + bootCount);
        } catch (Throwable t) {
            event("失败", "心跳写入失败: " + t);
        }
    }

    private static void ensureObserver() {
        if (sContext == null || sHandler == null) {
            return;
        }
        writeHeartbeatOnce();
        flushEvents();
        if (sObserver != null) {
            return;
        }
        try {
            ContentResolver resolver = sContext.getContentResolver();
            sObserver = new ContentObserver(new Handler(sHandler.getLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    String key = uri == null ? null : uri.getLastPathSegment();
                    if (SETTING_ENABLED.equals(key)) {
                        onEnabledListChanged();
                        return;
                    }
                    if (KEY_DEBUG_KILL.equals(key)) {
                        scheduleDebugKill();
                        return;
                    }
                    if (KEY_ENABLE.equals(key)) {
                        if (isModuleEnabled()) {
                            scheduleReconcile("enable", RECONCILE_CONFIG_DELAY_MS);
                        }
                    } else if (KEY_SERVICES.equals(key)) {
                        scheduleReconcile("services", RECONCILE_CONFIG_DELAY_MS);
                    }
                    if (!KEY_DEBUG.equals(key)) {
                        scheduleScan(SCAN_CONFIG_DELAY_MS, "config " + uri);
                    }
                }
            };
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
            resolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_ENABLED),
                    false, sObserver);
            sObserverAttempts = 0;
            event("配置", "配置监听已注册");
            scheduleScan(SCAN_INIT_DELAY_MS, "init");
            long sinceBoot = SystemClock.elapsedRealtime();
            long reconcileDelay = sinceBoot < BOOT_GRACE_MS
                    ? BOOT_GRACE_MS - sinceBoot + 5000L : SCAN_INIT_DELAY_MS;
            scheduleReconcile("init", reconcileDelay);
        } catch (Throwable t) {
            sObserver = null;
            sObserverAttempts++;
            event("失败", "配置监听注册失败 (" + sObserverAttempts + "): " + t);
            if (sObserverAttempts <= 3) {
                sHandler.postDelayed(AutoRepairEntry::ensureObserver, 5000L);
            } else {
                event("失败", "配置监听重试失败，已停用");
            }
        }
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
            event("配置", "扫描跳过：模块未启用 (" + reason + ")");
            return;
        }
        String managed = readSecure(KEY_SERVICES);
        if (managed == null || managed.isEmpty()) {
            event("配置", "扫描跳过：受管列表为空 (" + reason + ")");
            return;
        }
        String enabled = readEnabled();
        if (enabled == null || enabled.isEmpty()) {
            event("配置", "扫描跳过：无障碍启用列表为空 (" + reason + ")");
            return;
        }
        if (!hasConnectionState()) {
            event("配置", "扫描跳过：尚未收到服务连接事件 (" + reason + ")");
            return;
        }
        event("配置", "扫描开始 (" + reason + "): dryRun=" + isDryRun()
                + " managed=" + managed.split(":").length);

        int candidates = 0;
        for (String raw : managed.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(raw);
            if (component == null) {
                continue;
            }
            String key = component.flattenToString();
            if (!containsComponent(enabled, component)) {
                event("检测", "已关闭，跳过: " + key);
                continue;
            }
            Object boundConnection = findBoundConnection(component);
            if (boundConnection != null) {
                clearCrashedMarker(boundConnection, component);
                continue;
            }
            if (!sBoundCheckAvailable) {
                event("失败", "无法确认绑定状态，扫描中止");
                return;
            }
            candidates++;
            event("检测", "扫描发现崩溃: " + key);
            scheduleCheck(component, key, SETTLE_MS);
        }
        event("扫描", "扫描完成 (" + reason + ")，发现 " + candidates + " 个");
    }

    private static void onEnabledListChanged() {
        reconcileMissing("enabled change");
    }

    private static void reconcileMissing(String reason) {
        if (sContext == null) {
            return;
        }
        if (!isModuleEnabled()) {
            log("reconcile skipped (module disabled): " + reason);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < BOOT_GRACE_MS) {
            log("reconcile skipped (boot grace): " + reason);
            return;
        }
        String managed = readSecure(KEY_SERVICES);
        String enabled = readEnabled();
        if (managed == null || managed.isEmpty() || enabled == null) {
            return;
        }

        String desired = enabled;
        StringBuilder added = new StringBuilder();
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
            if (!isPackageInstalled(component.getPackageName())) {
                log("skip uninstalled: " + key);
                continue;
            }
            desired = addComponent(desired, component);
            if (added.length() > 0) {
                added.append(' ');
            }
            added.append(key);
            count++;
        }
        if (count == 0) {
            return;
        }
        if (isDryRun()) {
            event("保活", "演练：将补回 " + count + " 个: " + added);
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
        event("保活", "补回已关闭服务 (" + count + "): " + added + " [" + reason + "]");
        if (!writeEnabled(desired)) {
            event("失败", "补回写入失败: " + added);
            return;
        }
        scheduleBindVerify(reason);
    }

    /** 补回后等待 AMS 绑定，再确认是否真的起来了；force-stop 后可能只写了列表但没绑定 */
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
        if (sContext == null || !isModuleEnabled() || !hasConnectionState()) {
            return;
        }
        String managed = readSecure(KEY_SERVICES);
        String enabled = readEnabled();
        if (managed == null || managed.isEmpty() || enabled == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int unbound = 0;
        for (String raw : managed.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(raw);
            if (component == null) {
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
            if (findBoundConnection(component) != null) {
                continue;
            }
            if (!sBoundCheckAvailable) {
                return;
            }
            unbound++;
            scheduleCheck(component, key, SETTLE_MS);
        }
        if (unbound > 0) {
            event("检测", "补回后仍未绑定 " + unbound + " 个，准备修复 (" + reason + ")");
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
        if (!"1".equals(readSecure(KEY_DEBUG))) {
            log("debug kill skipped (debug disabled)");
            return;
        }
        String value = readSecure(KEY_DEBUG_KILL);
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
        for (int pid : pids) {
            try {
                Process.killProcess(pid);
                log("debug killed pid " + pid);
            } catch (Throwable t) {
                log("debug kill pid " + pid + " failed: " + t);
            }
        }
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
            log("unmanaged died ignored (crashed=" + crashed + "): " + key);
            return;
        }
        if (!crashed) {
            log("died event ignored (crashed=false): " + key);
            return;
        }
        if (sContext == null) {
            event("失败", "缺少系统 Context，忽略: " + key);
            return;
        }

        Long suppress = sSuppressUntil.get(key);
        if (suppress != null && now < suppress) {
            event("检测", "忽略（修复抑制窗口）: " + key);
            return;
        }

        Long blocked = sBlockedUntil.get(key);
        if (blocked != null) {
            if (now < blocked) {
                event("检测", "忽略（熔断中）: " + key);
                return;
            }
            sBlockedUntil.remove(key);
            sAttempts.remove(key);
            sLastAttemptAt.remove(key);
        }

        Long lastAttempt = sLastAttemptAt.get(key);
        if (lastAttempt != null && now - lastAttempt > RESET_AFTER_MS) {
            sAttempts.remove(key);
            sCrashHistory.remove(key);
        }

        boolean enabled = isModuleEnabled();
        event("检测", "崩溃: " + key + " (enable=" + enabled
                + ", dryRun=" + isDryRun() + ", iface=" + hasInterface + ")");
        if (!enabled) {
            return;
        }

        recordCrash(key, now);
        Long afterRecord = sBlockedUntil.get(key);
        if (afterRecord != null && now < afterRecord) {
            event("失败", "连续崩溃，熔断跳过: " + key);
            return;
        }
        if (sAttempts.getOrDefault(key, 0) >= MAX_ATTEMPTS) {
            event("失败", "重试次数已用完，跳过: " + key);
            return;
        }

        scheduleCheck(component, key, SETTLE_MS);
    }

    private static void onInitializeService(ComponentName component, boolean crashed,
            boolean hasInterface, Object connection) {
        String key = component.flattenToString();
        boolean managed = isManaged(component);

        if (!crashed && hasInterface) {
            boolean cancelled = sPendingCheck.remove(key) != null;
            Runnable verify = sVerifyRun.remove(key);
            if (verify != null) {
                sHandler.removeCallbacks(verify);
            }
            sSuppressUntil.remove(key);
            sAttempts.remove(key);
            sLastAttemptAt.remove(key);
            finishRepair(key);
            clearCrashedMarker(connection, component);
            if (managed) {
                event("检测", "已恢复" + (cancelled ? "（取消待执行的修复）" : "")
                        + ": " + key);
            } else {
                log("recovered (unmanaged): " + key);
            }
            return;
        }

        if (!managed) {
            log("unmanaged init ignored (crashed=" + crashed
                    + ", iface=" + hasInterface + "): " + key);
            return;
        }
        event("检测", "重绑失败 (crashed=" + crashed + ", iface=" + hasInterface
                + "): " + key);
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

        Long suppress = sSuppressUntil.get(key);
        if (suppress != null && now < suppress) {
            log("check skipped (self-repair window): " + key);
            return;
        }
        if (!isManaged(component)) {
            event("检测", "无需修复（已不在受管列表）: " + key);
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
            event("检测", "系统已自动恢复，不修复: " + key);
            return;
        }
        if (!sBoundCheckAvailable) {
            event("失败", "无法确认绑定状态，跳过修复: " + key);
            return;
        }

        Long blocked = sBlockedUntil.get(key);
        if (blocked != null && now < blocked) {
            event("失败", "跳过（熔断中）: " + key);
            return;
        }
        int attempts = sAttempts.getOrDefault(key, 0);
        if (attempts >= MAX_ATTEMPTS) {
            event("失败", "跳过（重试次数已用完）: " + key);
            return;
        }
        Long last = sLastAttemptAt.get(key);
        if (last != null && now - last < RETRY_COOLDOWN_MS) {
            event("检测", "跳过（重试冷却中）: " + key);
            return;
        }

        repair(component, key, attempts);
    }

    private static void repair(ComponentName component, String key, int attempts) {
        long now = SystemClock.elapsedRealtime();
        if (isRepairInProgress(now)) {
            scheduleCheck(component, key, 1500L);
            return;
        }
        if (isDryRun()) {
            event("修复", "演练：将关开 " + key);
            return;
        }

        pruneRepairHistory(now);
        if (sRepairHistory.size() >= GLOBAL_LIMIT) {
            event("失败", "全局限流（10 分钟 5 次）已满，跳过: " + key);
            return;
        }

        String original = readEnabled();
        if (!containsComponent(original, component)) {
            event("检测", "无需修复（服务已被关闭）: " + key);
            return;
        }
        String removed = removeComponent(original, component);

        sAttempts.put(key, attempts + 1);
        sLastAttemptAt.put(key, now);
        sSuppressUntil.put(key, now + SUPPRESS_MS);
        sSelfRemovedUntil.put(key, now + REPAIR_REMOVE_WINDOW_MS);
        sRepairStartedAt = now;
        sRepairInProgress = true;
        sRepairKey = key;
        sRepairHistory.addLast(now);

        event("修复", "第 " + (attempts + 1) + "/" + MAX_ATTEMPTS
                + " 次关开，先关闭: " + key);
        if (!writeEnabled(removed)) {
            event("失败", "关闭写入失败: " + key);
            finishRepair(key);
            block(key, "disable write failed");
            return;
        }

        sHandler.postDelayed(() -> {
            try {
                String current = readEnabled();
                if (containsComponent(current, component)) {
                    log("re-add skipped (already present): " + key);
                } else {
                    String readd = addComponent(current == null ? "" : current, component);
                    if (!writeEnabled(readd)) {
                        event("失败", "重新开启写入失败: " + key);
                        finishRepair(key);
                        block(key, "enable write failed");
                        return;
                    }
                    event("修复", "已重新开启: " + key);
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
                Object boundConnection = findBoundConnection(component);
                if (boundConnection != null) {
                    clearCrashedMarker(boundConnection, component);
                    sAttempts.remove(key);
                    sLastAttemptAt.remove(key);
                    event("修复", "恢复成功: " + key);
                } else if (!sBoundCheckAvailable) {
                    event("失败", "无法确认绑定状态，结束修复: " + key);
                } else {
                    int attempts = sAttempts.getOrDefault(key, 0);
                    event("失败", "恢复失败 (" + attempts + "/" + MAX_ATTEMPTS
                            + "): " + key);
                    if (attempts < MAX_ATTEMPTS) {
                        scheduleCheck(component, key, RETRY_COOLDOWN_MS);
                    } else {
                        event("失败", "放弃修复（已达重试上限）: " + key);
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

    private static void block(String key, String reason) {
        sBlockedUntil.put(key, SystemClock.elapsedRealtime() + UNSTABLE_COOLDOWN_MS);
        event("失败", "熔断 " + (UNSTABLE_COOLDOWN_MS / 60000) + " 分钟 ("
                + reason + "): " + key);
    }

    private static void recordCrash(String key, long now) {
        Deque<Long> history = sCrashHistory.get(key);
        if (history == null) {
            history = new ArrayDeque<>();
            sCrashHistory.put(key, history);
        }
        while (!history.isEmpty() && now - history.peekFirst() > UNSTABLE_WINDOW_MS) {
            history.pollFirst();
        }
        history.addLast(now);
        if (history.size() >= UNSTABLE_THRESHOLD) {
            sBlockedUntil.put(key, now + UNSTABLE_COOLDOWN_MS);
            event("失败", "连续 " + history.size() + " 次崩溃（"
                    + (UNSTABLE_WINDOW_MS / 1000) + " 秒内），熔断: " + key);
        }
    }

    private static void pruneRepairHistory(long now) {
        while (!sRepairHistory.isEmpty()
                && now - sRepairHistory.peekFirst() > GLOBAL_WINDOW_MS) {
            sRepairHistory.pollFirst();
        }
    }

    private static boolean isManaged(ComponentName target) {
        String list = readSecure(KEY_SERVICES);
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
        return "1".equals(readSecure(KEY_ENABLE));
    }

    private static boolean isDryRun() {
        String value = readSecure(KEY_DRY_RUN);
        return value == null || value.isEmpty() || "1".equals(value);
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

    private static String readEnabled() {
        Context context = sContext;
        if (context == null) {
            return null;
        }
        try {
            return Settings.Secure.getString(context.getContentResolver(), SETTING_ENABLED);
        } catch (Throwable t) {
            log("read enabled failed: " + t);
            return null;
        }
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
}
