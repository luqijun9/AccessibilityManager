package com.accessibilitymanager.probe;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单服务状态机调度器、单轨批量补回协调器与包级修复事务协调器。
 *
 * <p>线程模型：所有方法仅在模块专属 {@code A11yAutoRepair} HandlerThread 执行，无需全局状态锁。
 */
final class PackageRepairCoordinator {

    static final String STATE_MODULE_START_AT = "module_start_at";
    static final String STATE_FIRST_CONNECT_AT = "first_connect_at";
    static final String STATE_LAST_PKG_KILL_AT = "last_pkg_kill_at";
    static final String STATE_USER_DISABLED_KEYS = "user_disabled_keys";

    /**
     * 批量评估时的共享环境快照：一次读取配置、启用列表与去重 AMS 快照，
     * 并对同批内相同包名的可用性/系统包判定做局部记忆化，消除遍历 N 个服务时的 N 倍 Binder IPC。
     */
    static final class BatchEnv {
        final long now;
        final boolean moduleEnabled;
        final boolean dryRun;
        final boolean ignoreSystem;
        final String managedRaw;
        final String enabledRaw;
        final boolean enabledReadFailed;
        final boolean bootGraceActive;
        final long bootGraceRemainingMs;
        final AmsInspector.AmsSnapshot amsSnapshot;
        final Map<String, Boolean> pkgUsableCache = new HashMap<>();
        final Map<String, Boolean> systemPkgCache = new HashMap<>();
        boolean inBatch;

        BatchEnv(long now, boolean moduleEnabled, boolean dryRun, boolean ignoreSystem,
                String managedRaw, String enabledRaw, boolean enabledReadFailed,
                long bootGraceRemainingMs, AmsInspector.AmsSnapshot amsSnapshot, boolean inBatch) {
            this.now = now;
            this.moduleEnabled = moduleEnabled;
            this.dryRun = dryRun;
            this.ignoreSystem = ignoreSystem;
            this.managedRaw = managedRaw;
            this.enabledRaw = enabledRaw;
            this.enabledReadFailed = enabledReadFailed;
            this.bootGraceRemainingMs = bootGraceRemainingMs;
            this.bootGraceActive = bootGraceRemainingMs > 0L;
            this.amsSnapshot = amsSnapshot;
            this.inBatch = inBatch;
        }

        boolean isPackageUsableCached(String pkg) {
            if (pkg == null || pkg.isEmpty()) {
                return false;
            }
            Boolean cached = pkgUsableCache.get(pkg);
            if (cached != null) {
                return cached;
            }
            boolean usable = ModuleConfigStore.isPackageUsable(pkg);
            pkgUsableCache.put(pkg, usable);
            return usable;
        }

        boolean shouldIgnoreSystemCached(String pkg) {
            if (!ignoreSystem) {
                return false;
            }
            Boolean cached = systemPkgCache.get(pkg);
            if (cached != null) {
                return cached;
            }
            boolean isSys = ModuleConfigStore.isSystemPackage(pkg);
            systemPkgCache.put(pkg, isSys);
            return isSys;
        }

        boolean isManagedCached(ComponentKey key) {
            return key != null && managedRaw != null && !managedRaw.isEmpty()
                    && EnabledListDelta.contains(managedRaw, key);
        }
    }

    /**
     * 包级修复请求：同一包只保留一条，维护所有待修 trigger 集合，allowKill 取所有请求的“与”。
     */
    private static final class PackageRepairRequest {
        final String pkg;
        final Set<ComponentName> triggers = new LinkedHashSet<>();
        final String reason;
        final long requestedAt;
        boolean allowKill;

        PackageRepairRequest(String pkg, ComponentName trigger, boolean allowKill,
                String reason, long requestedAt) {
            this.pkg = pkg;
            if (trigger != null) {
                this.triggers.add(trigger);
            }
            this.allowKill = allowKill;
            this.reason = reason;
            this.requestedAt = requestedAt;
        }
    }

    private static final int MAX_RESTORE_WRITE_RETRIES = 2;
    private static final long RESTORE_RETRY_DELAY_MS = 3000L;

    private static volatile Handler sHandler;
    private static long sModuleStartAt = SystemClock.elapsedRealtime();
    private static long sFirstServiceConnectAt;
    private static Runnable sPostBootCheckRunnable;

    private static final Map<String, ServiceRepairStateMachine> sMachines = new HashMap<>();
    private static final Map<String, Runnable> sMachineTimers = new HashMap<>();
    private static final Map<String, Runnable> sPendingMachineSync = new HashMap<>();
    private static final Set<String> sUserDisabledKeys = new LinkedHashSet<>();
    private static final Map<String, PackageRepairRequest> sPackageRepairRequests = new HashMap<>();
    private static final List<String> sPackageRepairQueue = new ArrayList<>();
    private static final List<String> sActiveRepairBatch = new ArrayList<>();
    private static final Map<String, Long> sLastPackageKillAt = new HashMap<>();

    // 单轨批量补回队列（由 FSM RESTORING/REQUEST_RESTORE 唯一驱动）
    private static final Set<ComponentKey> sPendingRestoreKeys = new LinkedHashSet<>();
    private static String sPendingRestoreReason = "";
    private static boolean sRestoreFlushPending;
    private static long sLastRestoreWriteAt;
    private static int sRestoreWriteRetryCount;

    private static boolean sRepairTransactionActive;
    private static String sRepairTransactionPackage;
    private static long sRepairTransactionGeneration;

    private PackageRepairCoordinator() {
    }

    static void attachHandler(Handler handler) {
        sHandler = handler;
    }

    static long getModuleStartAt() {
        return sModuleStartAt;
    }

    static long getFirstServiceConnectAt() {
        return sFirstServiceConnectAt;
    }

    static ServiceRepairStateMachine getMachine(String key) {
        return sMachines.get(key);
    }

    static void onFirstServiceConnected(long now) {
        if (sFirstServiceConnectAt == 0L) {
            sFirstServiceConnectAt = now;
            long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
            if (remainingBoot > 0L) {
                schedulePostBootCheck(remainingBoot + 1000L);
            }
        }
    }

    static long getBootGraceDeadlineMs(long firstConnectAt) {
        return RepairPolicy.getBootGraceDeadlineMs(firstConnectAt);
    }

    static long getRemainingBootGraceMs(long now, long firstConnectAt) {
        return RepairPolicy.getRemainingBootGraceMs(now, sModuleStartAt, firstConnectAt);
    }

    static void schedulePostBootCheck(long delayMs) {
        Handler handler = sHandler;
        if (handler == null) {
            return;
        }
        if (sPostBootCheckRunnable != null) {
            handler.removeCallbacks(sPostBootCheckRunnable);
        }
        sPostBootCheckRunnable = () -> {
            sPostBootCheckRunnable = null;
            long now = SystemClock.elapsedRealtime();
            long remaining = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
            if (remaining > 0L) {
                // 还剩一点冷却时间时延后到冷却结束再查，避免卡在边界反复触发
                schedulePostBootCheck(remaining + 500L);
                return;
            }
            try {
                syncMachines("boot_ready");
            } catch (Throwable t) {
                EventLogBuffer.event("失败", "开机巡检异常: " + t);
            }
        };
        handler.postDelayed(sPostBootCheckRunnable, Math.max(0L, delayMs));
        EventLogBuffer.log("post-boot check scheduled in " + delayMs + "ms");
    }

    /**
     * 手动扫描入口：一次采样当前启用列表与去重 AMS 快照，为未绑定服务输出诊断并投递 SYNC。
     */
    static void runManualScan(String reason) {
        if (ModuleConfigStore.getContext() == null) {
            return;
        }
        if (!ModuleConfigStore.isModuleEnabled()) {
            EventLogBuffer.eventSkip("scan:disabled", "扫描", "跳过 (" + reason + ")：模块已停用");
            return;
        }
        String managed = ModuleConfigStore.readConfig(AutoRepairEntry.KEY_SERVICES);
        if (managed == null || managed.isEmpty()) {
            EventLogBuffer.eventSkip("scan:managed-empty", "扫描", "跳过 (" + reason + ")：锁定列表为空");
            return;
        }
        String enabled = ModuleConfigStore.readEnabled();
        if (enabled == null) {
            EventLogBuffer.eventSkip("scan:no-enabled",
                    ModuleConfigStore.getLastEnabledReadError() != null ? "失败" : "扫描",
                    "扫描跳过 (" + reason + ")：" + ModuleConfigStore.enabledReadSkipReason());
            return;
        }
        if (enabled.isEmpty()) {
            EventLogBuffer.event("扫描", "扫描完成 (" + reason + ")，发现 0 个异常服务",
                    "action=scan_done reason=" + reason + " unbound=0");
            return;
        }
        if (!AmsInspector.hasConnectionState()) {
            EventLogBuffer.eventSkip("scan:no-conn", "失败",
                    "扫描跳过 (" + reason + ")：没有可用的连接状态");
            return;
        }

        BatchEnv env = captureBatchEnv(managed, enabled, false, true);
        int candidates = 0;
        for (ComponentKey keyObj : EnabledListDelta.parse(managed)) {
            ComponentName component = new ComponentName(
                    keyObj.getPackageName(), keyObj.getClassName());
            String key = keyObj.flatten();
            if (!EnabledListDelta.contains(enabled, keyObj)) {
                continue;
            }
            if (!env.isPackageUsableCached(keyObj.getPackageName())) {
                EventLogBuffer.eventSkip("scan:unavailable:" + keyObj.getPackageName(), "扫描",
                        "跳过不可用包服务 (" + reason + "): " + key);
                continue;
            }
            AmsInspector.BoundLookupResult lookup = env.amsSnapshot.lookupBound(component);
            if (lookup.connection != null) {
                AmsInspector.clearCrashedMarker(lookup.connection, component);
                continue;
            }
            if (!lookup.checkAvailable) {
                EventLogBuffer.eventSkip("scan:no-bound-check", "失败",
                        "扫描中止 (" + reason + ")：无法读取绑定状态: " + key);
                return;
            }
            candidates++;
            EventLogBuffer.event("检测", "扫描发现未绑定服务: " + key,
                    "action=scan_hit reason=" + reason
                            + " lastConn=" + AmsInspector.connId(
                                    AmsInspector.getKnownConnection(key))
                            + " crashedMarker=" + AmsInspector.getCrashedMarker(component));
            handleServiceEvent(component, ServiceRepairStateMachine.Event.sync(), env);
        }
        env.inBatch = false;
        flushPendingRestores();
        EventLogBuffer.event("扫描", "扫描完成 (" + reason + ")，发现 " + candidates + " 个异常服务",
                "action=scan_done reason=" + reason + " unbound=" + candidates);
    }

    /**
     * 启用列表变化唯一入口：
     * 1. 先记录非系统组件增删日志；
     * 2. 再用同一份列表快照驱动全部托管服务状态机（同时缺失多个服务会一起进入 RESTORING）；
     * 3. 最后统一执行一次 delta 补回并把被补回成员切入 SUSPECT 校验绑定。
     */
    static void onEnabledListChanged() {
        String current = ModuleConfigStore.readEnabled();
        if (current != null) {
            String prev = ModuleConfigStore.getLastObservedEnabled();
            if (prev != null && !current.equals(prev)) {
                String removed = ModuleConfigStore.diffComponents(current, prev);
                String added = ModuleConfigStore.diffComponents(prev, current);
                if (ModuleConfigStore.isIgnoreSystemEnabled()) {
                    removed = RepairPolicy.filterOutSystemComponents(
                            removed, true, ModuleConfigStore::isSystemPackage);
                    added = RepairPolicy.filterOutSystemComponents(
                            added, true, ModuleConfigStore::isSystemPackage);
                }
                if (!removed.isEmpty() || !added.isEmpty()) {
                    EventLogBuffer.event("监听",
                            "启用列表变更: 移除=" + (removed.isEmpty() ? "无" : removed)
                                    + " 新增=" + (added.isEmpty() ? "无" : added),
                            ModuleConfigStore.getPowerStateSummary());
                }
            }
            ModuleConfigStore.setLastObservedEnabled(current);
        }

        if (!ModuleConfigStore.isModuleEnabled()) {
            if (ModuleConfigStore.isDebugCacheEnabled()) {
                EventLogBuffer.log("enabled change ignored (module disabled)");
            }
            return;
        }

        String managed = ModuleConfigStore.readConfig(AutoRepairEntry.KEY_SERVICES);
        BatchEnv env = captureBatchEnv(managed, current, current == null, true);

        Set<String> keysToNotify = new LinkedHashSet<>(sMachines.keySet());
        if (managed != null && !managed.isEmpty()) {
            for (ComponentKey keyObj : EnabledListDelta.parse(managed)) {
                keysToNotify.add(keyObj.flatten());
            }
        }
        for (String key : keysToNotify) {
            ComponentName component = ComponentName.unflattenFromString(key);
            if (component != null) {
                handleServiceEvent(component,
                        ServiceRepairStateMachine.Event.enabledListChanged(), env);
            }
        }
        env.inBatch = false;
        flushPendingRestores();
    }

    static void onAccessibilitySwitchChanged() {
        Context ctx = ModuleConfigStore.getContext();
        if (ctx == null) {
            return;
        }
        try {
            int enabled = android.provider.Settings.Secure.getInt(
                    ctx.getContentResolver(), AutoRepairEntry.SETTING_ACCESSIBILITY_ENABLED, -1);
            if (enabled == 1 && ModuleConfigStore.getLastAccessibilityEnabled() == 1) {
                EventLogBuffer.log("accessibility_enabled unchanged (1), skip redundant sync");
                return;
            }
            ModuleConfigStore.setLastAccessibilityEnabled(enabled);
            EventLogBuffer.event("监听", "无障碍总开关变更: accessibility_enabled=" + enabled,
                    ModuleConfigStore.getPowerStateSummary());
            if (enabled != 1 && ModuleConfigStore.isModuleEnabled()) {
                String managed = ModuleConfigStore.readConfig(AutoRepairEntry.KEY_SERVICES);
                if (managed != null && !managed.isEmpty()) {
                    ModuleConfigStore.ensureAccessibilitySwitchOn();
                }
            }
            syncMachines("accessibility_switch");
        } catch (Throwable t) {
            EventLogBuffer.log("onAccessibilitySwitchChanged error: " + t);
        }
    }

    /** 重新从锁定列表同步所有状态机；配置/模块启停/开机冷却结束/热重载后调用。 */
    static void syncMachines(String reason) {
        if (ModuleConfigStore.getContext() == null || sHandler == null) {
            return;
        }
        String managed = ModuleConfigStore.readConfig(AutoRepairEntry.KEY_SERVICES);
        if (managed == null) {
            EventLogBuffer.log("syncMachines skipped (managed unreadable): " + reason);
            return;
        }
        String enabled = ModuleConfigStore.readEnabled();
        BatchEnv env = captureBatchEnv(managed, enabled, enabled == null, true);

        Set<String> managedKeys = new LinkedHashSet<>();
        for (ComponentKey keyObj : EnabledListDelta.parse(managed)) {
            ComponentName component = new ComponentName(
                    keyObj.getPackageName(), keyObj.getClassName());
            managedKeys.add(keyObj.flatten());
            handleServiceEvent(component, ServiceRepairStateMachine.Event.sync(), env);
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
                        ServiceRepairStateMachine.Event.managedChanged(), env);
            }
            cancelMachineTimer(entry.getKey());
            Runnable pending = sPendingMachineSync.remove(entry.getKey());
            if (pending != null && sHandler != null) {
                sHandler.removeCallbacks(pending);
            }
            sUserDisabledKeys.remove(entry.getKey());
            sMachines.remove(entry.getKey());
        }
        env.inBatch = false;
        flushPendingRestores();
        EventLogBuffer.log("syncMachines (" + reason + "): managed=" + managedKeys.size());
    }

    private static BatchEnv captureBatchEnv(String managedRaw, String enabledRaw,
            boolean enabledReadFailed, boolean inBatch) {
        long now = SystemClock.elapsedRealtime();
        long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
        return new BatchEnv(
                now,
                ModuleConfigStore.isModuleEnabled(),
                ModuleConfigStore.isDryRun(),
                ModuleConfigStore.isIgnoreSystemEnabled(),
                managedRaw,
                enabledRaw,
                enabledReadFailed,
                remainingBoot,
                AmsInspector.captureAmsSnapshot(),
                inBatch);
    }

    private static ServiceRepairStateMachine.Snapshot buildMachineSnapshot(
            ComponentName component, BatchEnv env) {
        ServiceRepairStateMachine.Snapshot snapshot = new ServiceRepairStateMachine.Snapshot();
        if (env != null) {
            ComponentKey compKey = ComponentKey.of(
                    component.getPackageName(), component.getClassName());
            String flatKey = compKey != null ? compKey.flatten() : component.flattenToString();
            snapshot.now = env.now;
            snapshot.moduleEnabled = env.moduleEnabled;
            snapshot.dryRun = env.dryRun;
            snapshot.managed = env.isManagedCached(compKey)
                    && !env.shouldIgnoreSystemCached(component.getPackageName())
                    && env.isPackageUsableCached(component.getPackageName());
            snapshot.userDisabled = sUserDisabledKeys.contains(flatKey);
            snapshot.inEnabledList = env.enabledReadFailed
                    || EnabledListDelta.contains(env.enabledRaw, compKey);
            AmsInspector.BoundLookupResult lookup = env.amsSnapshot.lookupBound(component);
            snapshot.bound = lookup.connection != null;
            snapshot.bindCheckAvailable = lookup.checkAvailable && !env.enabledReadFailed;
            snapshot.bindingInProgress = !snapshot.bound
                    && env.amsSnapshot.isServiceBinding(component);
            snapshot.bootGraceActive = env.bootGraceActive;
            snapshot.bootGraceRemainingMs = env.bootGraceRemainingMs;
            return snapshot;
        }

        long now = SystemClock.elapsedRealtime();
        snapshot.now = now;
        snapshot.moduleEnabled = ModuleConfigStore.isModuleEnabled();
        snapshot.dryRun = ModuleConfigStore.isDryRun();
        snapshot.managed = ModuleConfigStore.isManaged(component)
                && !ModuleConfigStore.shouldIgnoreSystemApp(component.getPackageName())
                && ModuleConfigStore.isPackageUsable(component.getPackageName());
        snapshot.userDisabled = sUserDisabledKeys.contains(component.flattenToString());

        String enabled = ModuleConfigStore.readEnabled();
        boolean enabledReadFailed = enabled == null;
        ComponentKey compKey = ComponentKey.of(
                component.getPackageName(), component.getClassName());
        snapshot.inEnabledList = enabledReadFailed
                || EnabledListDelta.contains(enabled, compKey);

        AmsInspector.BoundLookupResult lookup = AmsInspector.lookupBoundConnection(component);
        snapshot.bound = lookup.connection != null;
        snapshot.bindCheckAvailable = lookup.checkAvailable && !enabledReadFailed;
        snapshot.bindingInProgress = !snapshot.bound && AmsInspector.isServiceBinding(component);

        long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
        snapshot.bootGraceActive = remainingBoot > 0L;
        snapshot.bootGraceRemainingMs = remainingBoot;
        return snapshot;
    }

    static void handleServiceEvent(ComponentName component,
            ServiceRepairStateMachine.Event event) {
        handleServiceEvent(component, event, null);
    }

    static void handleServiceEvent(ComponentName component,
            ServiceRepairStateMachine.Event event, BatchEnv env) {
        if (component == null || event == null) {
            return;
        }
        String key = component.flattenToString();
        ServiceRepairStateMachine machine = sMachines.get(key);
        ServiceRepairStateMachine.Snapshot snapshot = buildMachineSnapshot(component, env);
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
            EventLogBuffer.event("失败", "状态机处理异常: " + key + " " + t);
            return;
        }
        if (transition.isNoOp()) {
            return;
        }
        EventLogBuffer.log("fsm " + key + ": " + transition.getFrom() + " -> " + transition.getTo()
                + " event=" + event.getType() + " effects=" + transition.getEffects().size());
        executeMachineEffects(component, transition, env);
    }

    private static void executeMachineEffects(ComponentName component,
            ServiceRepairStateMachine.Transition transition, BatchEnv env) {
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
                    enqueueRestore(component, effect.getReason(), env);
                    break;
                case CLEAR_CRASHED_MARKER:
                    Object bound = AmsInspector.findBoundConnection(component);
                    if (bound != null) {
                        AmsInspector.clearCrashedMarker(bound, component);
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
        Handler handler = sHandler;
        if (handler == null) {
            EventLogBuffer.log("machine timer skipped (handler null): " + key);
            return;
        }
        Runnable runnable = () -> {
            sMachineTimers.remove(key);
            handleServiceEvent(component,
                    ServiceRepairStateMachine.Event.timer(timerType, generation));
        };
        sMachineTimers.put(key, runnable);
        handler.postDelayed(runnable, Math.max(0L, delayMs));
    }

    private static void cancelMachineTimer(String key) {
        Runnable old = sMachineTimers.remove(key);
        if (old != null && sHandler != null) {
            sHandler.removeCallbacks(old);
        }
    }

    static void scheduleMachineSync(ComponentName component, long delayMs, String reason) {
        Handler handler = sHandler;
        if (component == null || handler == null) {
            return;
        }
        String key = component.flattenToString();
        Runnable old = sPendingMachineSync.remove(key);
        if (old != null) {
            handler.removeCallbacks(old);
        }
        Runnable runnable = () -> {
            sPendingMachineSync.remove(key);
            handleServiceEvent(component, ServiceRepairStateMachine.Event.sync());
        };
        sPendingMachineSync.put(key, runnable);
        handler.postDelayed(runnable, Math.max(0L, delayMs));
        EventLogBuffer.log("machine sync scheduled in " + delayMs + "ms (" + reason + "): " + key);
    }

    private static void notifyMachineRecovered(ComponentName component,
            ServiceRepairStateMachine.State from) {
        String key = component.flattenToString();
        if (from == ServiceRepairStateMachine.State.VERIFYING
                || from == ServiceRepairStateMachine.State.REPAIRING) {
            EventLogBuffer.event("修复", "恢复成功: " + key, "action=fsm_verify_ok");
            ModuleConfigStore.showModuleToast("保活崩溃服务 " + ModuleConfigStore.getAppLabel(component));
        } else {
            EventLogBuffer.event("保活", "服务已恢复就绪（无需额外重启）: " + key,
                    "action=fsm_recovered from=" + from);
        }
    }

    /**
     * 单轨补回入队：
     *
     * <p>1. 解决场景：ColorOS force-stop 或系统外部批量移除同包/多包多个服务时，
     *    若首个服务立刻写 Settings.Secure，后续服务遍历时快照已变成 inEnabledList=true，
     *    导致它们仍停留在 BOUND 而不校验是否真正重绑。
     * <p>2. 前置条件与边界：批处理（env.inBatch==true）内仅收集待补回 ComponentKey，
     *    批循环结束后由 flushPendingRestores 一次合并写入并统一驱动所有成员切入 SUSPECT。
     * <p>3. 兜底策略：非批处理单事件触发时立即 flushPendingRestores。
     */
    private static void enqueueRestore(ComponentName component, String reason, BatchEnv env) {
        if (component == null) {
            return;
        }
        ComponentKey compKey = ComponentKey.of(
                component.getPackageName(), component.getClassName());
        if (compKey == null) {
            return;
        }
        sPendingRestoreKeys.add(compKey);
        if (reason != null && !reason.isEmpty()) {
            sPendingRestoreReason = reason;
        }
        if (env == null || !env.inBatch) {
            flushPendingRestores();
        }
    }

    /** 执行一次单轨批量补回；绝不递归投递 SYNC，演练模式与写失败重试受严格边界约束。 */
    static void flushPendingRestores() {
        if (sPendingRestoreKeys.isEmpty()) {
            return;
        }
        if (ModuleConfigStore.getContext() == null) {
            sPendingRestoreKeys.clear();
            return;
        }
        String reason = sPendingRestoreReason.isEmpty() ? "fsm_restore" : sPendingRestoreReason;
        if (!ModuleConfigStore.isModuleEnabled()) {
            EventLogBuffer.eventSkip("restore:disabled", "保活",
                    "跳过补回 (" + reason + ")：模块已停用");
            sPendingRestoreKeys.clear();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long remainingBoot = getRemainingBootGraceMs(now, sFirstServiceConnectAt);
        if (remainingBoot > 0L) {
            EventLogBuffer.eventSkip("restore:boot-grace", "保活",
                    "跳过补回 (" + reason + ")：开机保护期内");
            sPendingRestoreKeys.clear();
            schedulePostBootCheck(remainingBoot + 1000L);
            return;
        }
        String managed = ModuleConfigStore.readConfig(AutoRepairEntry.KEY_SERVICES);
        String enabled = ModuleConfigStore.readEnabled();
        if (enabled == null) {
            EventLogBuffer.eventSkip("restore:no-enabled",
                    ModuleConfigStore.getLastEnabledReadError() != null ? "失败" : "保活",
                    "补回跳过 (" + reason + ")：" + ModuleConfigStore.enabledReadSkipReason());
            if (sRestoreWriteRetryCount < MAX_RESTORE_WRITE_RETRIES
                    && !sRestoreFlushPending && sHandler != null) {
                sRestoreWriteRetryCount++;
                sRestoreFlushPending = true;
                sHandler.postDelayed(() -> {
                    sRestoreFlushPending = false;
                    flushPendingRestores();
                }, RESTORE_RETRY_DELAY_MS);
            } else {
                sPendingRestoreKeys.clear();
                sRestoreWriteRetryCount = 0;
            }
            return;
        }

        List<ComponentKey> addKeys = new ArrayList<>();
        StringBuilder addedText = new StringBuilder();
        Set<String> addedLabels = new LinkedHashSet<>();
        for (ComponentKey keyObj : new ArrayList<>(sPendingRestoreKeys)) {
            if (keyObj == null) {
                continue;
            }
            String flat = keyObj.flatten();
            if (managed == null || !EnabledListDelta.contains(managed, keyObj)) {
                continue;
            }
            if (sUserDisabledKeys.contains(flat) || isRepairingKey(flat)) {
                continue;
            }
            if (!ModuleConfigStore.isPackageUsable(keyObj.getPackageName())) {
                EventLogBuffer.eventSkip("restore:unavailable:" + keyObj.getPackageName(), "保活",
                        "跳过补回 (" + reason + ")：应用不可用: " + flat);
                continue;
            }
            if (!EnabledListDelta.contains(enabled, keyObj)) {
                addKeys.add(keyObj);
                if (addedText.length() > 0) {
                    addedText.append(' ');
                }
                addedText.append(flat);
                addedLabels.add(ModuleConfigStore.getAppLabel(
                        new ComponentName(keyObj.getPackageName(), keyObj.getClassName())));
            }
        }

        if (addKeys.isEmpty()) {
            // 已经存在于启用列表的成员（如其他路径已写入）：刷新状态机切出 RESTORING
            List<ComponentKey> alreadyPresent = new ArrayList<>(sPendingRestoreKeys);
            sPendingRestoreKeys.clear();
            sRestoreWriteRetryCount = 0;
            notifyRestoredComponents(alreadyPresent, managed, enabled);
            return;
        }

        if (ModuleConfigStore.isDryRun()) {
            // 演练模式：打一次演练日志并清空待补回集合，严禁排 3s 重试死循环
            EventLogBuffer.event("保活",
                    "演练模式：将补回 " + addKeys.size() + " 个: " + addedText + "（不实际写入）");
            sPendingRestoreKeys.clear();
            sRestoreWriteRetryCount = 0;
            return;
        }

        if (now - sLastRestoreWriteAt < AutoRepairEntry.CLOSE_MIN_INTERVAL_MS) {
            if (!sRestoreFlushPending && sHandler != null) {
                sRestoreFlushPending = true;
                long delay = AutoRepairEntry.CLOSE_MIN_INTERVAL_MS - (now - sLastRestoreWriteAt);
                sHandler.postDelayed(() -> {
                    sRestoreFlushPending = false;
                    flushPendingRestores();
                }, delay);
            }
            return;
        }

        sLastRestoreWriteAt = now;
        EventLogBuffer.event("保活", "补回已关闭服务 (" + addKeys.size() + "): " + addedText,
                "action=reconcile reason=" + reason + " count=" + addKeys.size()
                        + " " + ModuleConfigStore.getPowerStateSummary());
        SettingsWriter.WriteResult writeResult = ModuleConfigStore.settingsWriter().applyDelta(
                addKeys, Collections.<ComponentKey>emptyList());
        if (writeResult.isFailure()) {
            EventLogBuffer.event("失败", "补回写入失败: " + addedText,
                    "action=reconcile_write_fail reason=" + reason
                            + " status=" + writeResult.getStatus()
                            + " " + ModuleConfigStore.getPowerStateSummary());
            if (sRestoreWriteRetryCount < MAX_RESTORE_WRITE_RETRIES
                    && !sRestoreFlushPending && sHandler != null) {
                sRestoreWriteRetryCount++;
                sRestoreFlushPending = true;
                sHandler.postDelayed(() -> {
                    sRestoreFlushPending = false;
                    flushPendingRestores();
                }, RESTORE_RETRY_DELAY_MS);
            } else {
                sPendingRestoreKeys.clear();
                sRestoreWriteRetryCount = 0;
            }
            return;
        }

        sRestoreWriteRetryCount = 0;
        sPendingRestoreKeys.clear();
        if (writeResult.isWritten()) {
            ModuleConfigStore.ensureAccessibilitySwitchOn();
            ModuleConfigStore.showModuleToast("保活 " + ModuleConfigStore.joinLabels(addedLabels));
        }
        String latestEnabled = writeResult.getAfter() != null
                ? writeResult.getAfter() : ModuleConfigStore.readEnabled();
        notifyRestoredComponents(addKeys, managed, latestEnabled);
    }

    private static void notifyRestoredComponents(List<ComponentKey> keys,
            String managedRaw, String enabledRaw) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        BatchEnv postEnv = captureBatchEnv(managedRaw, enabledRaw, enabledRaw == null, false);
        for (ComponentKey keyObj : keys) {
            if (keyObj == null) {
                continue;
            }
            ComponentName component = new ComponentName(
                    keyObj.getPackageName(), keyObj.getClassName());
            handleServiceEvent(component,
                    ServiceRepairStateMachine.Event.enabledListChanged(), postEnv);
        }
    }

    /** 单服务状态机只提出“需要修复”，包级协调器负责合并同包成员、一次关开/强杀。 */
    private static void requestPackageRepair(ComponentName component, boolean allowKill,
            String reason) {
        if (component == null || !ModuleConfigStore.isModuleEnabled()) {
            return;
        }
        String key = component.flattenToString();
        if (!ModuleConfigStore.isManaged(component) || sUserDisabledKeys.contains(key)) {
            EventLogBuffer.log("skip repair request (not managed/user disabled): " + key);
            return;
        }
        if (ModuleConfigStore.isDryRun()) {
            EventLogBuffer.event("修复", "演练模式：将修复 " + key + "（不实际执行）",
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
            request.triggers.add(component);
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
            if (request == null || request.triggers.isEmpty()) {
                continue;
            }
            if (!ModuleConfigStore.isModuleEnabled()) {
                EventLogBuffer.log("package repair skipped (module disabled): " + pkg);
                syncTriggerMachines(request.triggers);
                continue;
            }
            if (!ModuleConfigStore.isPackageUsable(pkg)) {
                EventLogBuffer.eventSkip("repair:unavailable:" + pkg, "修复",
                        "跳过不可用包级修复: " + pkg);
                syncTriggerMachines(request.triggers);
                continue;
            }
            String managed = ModuleConfigStore.readConfig(AutoRepairEntry.KEY_SERVICES);
            String enabled = ModuleConfigStore.readEnabled();
            if (managed == null || enabled == null) {
                EventLogBuffer.event("失败", "包级修复读取配置失败: " + pkg,
                        "action=fsm_repair_read_fail");
                for (ComponentName t : request.triggers) {
                    scheduleMachineSync(t, 3000L, "repair_read_failed");
                }
                continue;
            }

            List<ComponentName> unboundTriggers = new ArrayList<>();
            for (ComponentName t : request.triggers) {
                if (AmsInspector.findBoundConnection(t) != null) {
                    EventLogBuffer.log("package repair trigger already bound: "
                            + t.flattenToShortString());
                    handleServiceEvent(t, ServiceRepairStateMachine.Event.sync());
                } else {
                    unboundTriggers.add(t);
                }
            }
            if (unboundTriggers.isEmpty()) {
                EventLogBuffer.log("package repair skipped, all triggers already bound: " + pkg);
                continue;
            }

            ComponentName primaryTrigger = unboundTriggers.get(0);
            ComponentKey trigger = ComponentKey.of(
                    primaryTrigger.getPackageName(), primaryTrigger.getClassName());
            List<ComponentKey> candidates = RepairPolicy.planPackageBatch(
                    managed, enabled, pkg, trigger);
            if (candidates.isEmpty()) {
                EventLogBuffer.log("package repair batch empty, re-evaluate: " + pkg);
                syncTriggerMachines(unboundTriggers);
                continue;
            }

            boolean allowKill = computePackageKillAllowed(request, candidates);
            Set<ComponentKey> unbound = allowKill
                    ? Collections.<ComponentKey>emptySet()
                    : findUnboundMembers(candidates);
            List<ComponentKey> batch = RepairPolicy.selectRepairBatch(
                    candidates, allowKill, unbound);
            if (batch.isEmpty()) {
                EventLogBuffer.log("package repair batch empty after trim, re-evaluate: " + pkg);
                syncTriggerMachines(unboundTriggers);
                continue;
            }

            // 1. 解决场景：若同包某个已排队的 trigger 在排队期间从启用列表消失等原因未进入最终 batch，
            //    且没有再收到 SYNC，它会永久卡死在 NEEDS_REPAIR（队列已出队，再来的崩溃也会被 noop）。
            // 2. 兜底策略：对不在最终 batch 中的每一个 trigger 立即投递 SYNC 重新评估。
            Set<ComponentKey> batchSet = new LinkedHashSet<>(batch);
            for (ComponentName t : unboundTriggers) {
                ComponentKey tk = ComponentKey.of(t.getPackageName(), t.getClassName());
                if (tk != null && !batchSet.contains(tk)) {
                    handleServiceEvent(t, ServiceRepairStateMachine.Event.sync());
                }
            }

            if (!allowKill && batch.size() < candidates.size()) {
                EventLogBuffer.event("修复", "同包健康服务不参与关开: " + pkg,
                        "action=fsm_batch_trim candidates=" + candidates.size()
                                + " batch=" + batch.size());
            }
            startPackageRepair(request, batch, allowKill);
            return;
        }
    }

    private static void syncTriggerMachines(Collection<ComponentName> triggers) {
        if (triggers == null) {
            return;
        }
        for (ComponentName t : triggers) {
            if (t != null) {
                handleServiceEvent(t, ServiceRepairStateMachine.Event.sync());
            }
        }
    }

    private static boolean computePackageKillAllowed(PackageRepairRequest request,
            List<ComponentKey> batchCandidates) {
        if (request == null || !request.allowKill
                || batchCandidates == null || batchCandidates.isEmpty()) {
            return false;
        }
        boolean fixMode = "1".equals(ModuleConfigStore.readConfig(AutoRepairEntry.KEY_FIX_MODE));
        String disabled = ModuleConfigStore.readConfig(AutoRepairEntry.KEY_FIX_DISABLED);
        Context ctx = ModuleConfigStore.getContext();
        String contextPkg = ctx != null ? ctx.getPackageName() : null;
        Long lastKill = sLastPackageKillAt.get(request.pkg);
        return RepairPolicy.shouldKillPackage(
                request.pkg, contextPkg, fixMode, disabled, batchCandidates, lastKill,
                SystemClock.elapsedRealtime(), getFastFailCountForPackage(request.pkg));
    }

    private static Set<ComponentKey> findUnboundMembers(List<ComponentKey> candidates) {
        Set<ComponentKey> unbound = new LinkedHashSet<>();
        if (candidates == null) {
            return unbound;
        }
        AmsInspector.AmsSnapshot snapshot = AmsInspector.captureAmsSnapshot();
        for (ComponentKey key : candidates) {
            if (key == null) {
                continue;
            }
            ComponentName component = new ComponentName(key.getPackageName(), key.getClassName());
            if (snapshot.lookupBound(component).connection == null) {
                unbound.add(key);
            }
        }
        return unbound;
    }

    private static void startPackageRepair(PackageRepairRequest request,
            List<ComponentKey> batch, boolean allowKill) {
        sRepairTransactionActive = true;
        sRepairTransactionPackage = request.pkg;
        long generation = ++sRepairTransactionGeneration;
        sActiveRepairBatch.clear();
        for (ComponentKey key : batch) {
            sActiveRepairBatch.add(key.flatten());
        }

        for (String key : sActiveRepairBatch) {
            ComponentName component = ComponentName.unflattenFromString(key);
            if (component != null) {
                handleServiceEvent(component,
                        ServiceRepairStateMachine.Event.packageRepairStarted());
            }
        }

        SettingsWriter.WriteResult removeResult = ModuleConfigStore.settingsWriter().applyDelta(
                Collections.<ComponentKey>emptyList(), batch);
        if (removeResult.isFailure()) {
            EventLogBuffer.event("失败", "包级修复关闭写入失败: " + request.pkg,
                    "action=fsm_repair_disable_fail status=" + removeResult.getStatus());
            failActivePackageRepair("disable_write_failed");
            return;
        }

        if (allowKill) {
            sLastPackageKillAt.put(request.pkg, SystemClock.elapsedRealtime());
            String killMeta = killAppProcesses(request.pkg);
            EventLogBuffer.event("修复", "强杀应用进程后重启: " + request.pkg,
                    "action=fsm_kill_package pkg=" + request.pkg + " " + killMeta);
        }

        EventLogBuffer.event("修复", "包级关开修复: " + request.pkg,
                "action=fsm_repair_start batch=" + sActiveRepairBatch
                        + " allowKill=" + allowKill
                        + " reason=" + request.reason
                        + " " + ModuleConfigStore.getPowerStateSummary());
        sHandler.postDelayed(() -> finishPackageRepairReAdd(generation, batch),
                AutoRepairEntry.TOGGLE_OFF_MS);
    }

    private static void finishPackageRepairReAdd(long generation, List<ComponentKey> batch) {
        if (generation != sRepairTransactionGeneration || !sRepairTransactionActive) {
            EventLogBuffer.log("stale package repair completion ignored: gen=" + generation);
            return;
        }
        if (!ModuleConfigStore.isModuleEnabled()) {
            // 1. 解决场景：用户在 500ms 关开窗口期间停用模块，若直接 abort 会把 500ms 前自己临时移除的
            //    服务遗留在关闭状态。
            // 2. 兜底策略：先把未用户关闭且包仍可用的成员写回启用列表，再中止事务。
            List<ComponentKey> rollbackAdd = collectEligibleReAdd(batch);
            if (!rollbackAdd.isEmpty()) {
                ModuleConfigStore.settingsWriter().applyDelta(
                        rollbackAdd, Collections.<ComponentKey>emptyList());
            }
            EventLogBuffer.event("失败", "包级修复中止：模块已停用（已回滚移除）: "
                    + sRepairTransactionPackage);
            failActivePackageRepair("module_disabled");
            return;
        }
        if (sRepairTransactionPackage == null
                || !ModuleConfigStore.isPackageUsable(sRepairTransactionPackage)) {
            EventLogBuffer.event("失败", "包级修复中止：包不可用: " + sRepairTransactionPackage);
            failActivePackageRepair("package_unavailable");
            return;
        }

        List<ComponentKey> add = collectEligibleReAdd(batch);
        SettingsWriter.WriteResult addResult = ModuleConfigStore.settingsWriter().applyDelta(
                add, Collections.<ComponentKey>emptyList());
        if (addResult.isFailure()) {
            EventLogBuffer.event("失败", "包级修复重新开启失败: " + sRepairTransactionPackage,
                    "action=fsm_repair_enable_fail status=" + addResult.getStatus());
            failActivePackageRepair("enable_write_failed");
            return;
        }

        if (!add.isEmpty()) {
            ModuleConfigStore.ensureAccessibilitySwitchOn();
        }
        EventLogBuffer.event("修复", "包级修复已重新开启: " + sRepairTransactionPackage,
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

    private static List<ComponentKey> collectEligibleReAdd(List<ComponentKey> batch) {
        List<ComponentKey> add = new ArrayList<>();
        if (batch == null) {
            return add;
        }
        for (ComponentKey key : batch) {
            if (key == null) {
                continue;
            }
            ComponentName component = new ComponentName(key.getPackageName(), key.getClassName());
            if (!ModuleConfigStore.isManaged(component)) {
                continue;
            }
            if (sUserDisabledKeys.contains(key.flatten())) {
                continue;
            }
            add.add(key);
        }
        return add;
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
        EventLogBuffer.event("失败", "包级修复失败: " + pkg + " (" + reason + ")",
                "action=fsm_repair_failed reason=" + reason);
        finishActivePackageRepair();
    }

    private static void finishActivePackageRepair() {
        sRepairTransactionActive = false;
        sRepairTransactionPackage = null;
        sActiveRepairBatch.clear();
        startNextPackageRepairIfIdle();
    }

    static void removeFromPackageRepair(ComponentName component) {
        if (component == null) {
            return;
        }
        String pkg = component.getPackageName();
        PackageRepairRequest request = sPackageRepairRequests.get(pkg);
        if (request != null) {
            request.triggers.remove(component);
            if (request.triggers.isEmpty()) {
                sPackageRepairRequests.remove(pkg);
                sPackageRepairQueue.remove(pkg);
            }
        }
    }

    private static boolean isRepairingKey(String key) {
        ServiceRepairStateMachine machine = sMachines.get(key);
        if (machine == null) {
            return false;
        }
        return machine.getState() == ServiceRepairStateMachine.State.REPAIRING;
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

    static String killAppProcesses(String pkg) {
        Context ctx = ModuleConfigStore.getContext();
        if (ctx == null || pkg == null || pkg.isEmpty()) {
            return "uid=-1 killedPids=[]";
        }
        final int uid;
        try {
            uid = ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            EventLogBuffer.log("killAppProcesses: package not found: " + pkg);
            return "uid=not_found killedPids=[]";
        }

        boolean forceStopCalled = false;
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                Method forceStopMethod = am.getClass().getMethod("forceStopPackage", String.class);
                forceStopMethod.invoke(am, pkg);
                forceStopCalled = true;
                EventLogBuffer.log("killAppProcesses: forceStopPackage called for " + pkg);
            }
        } catch (Throwable t) {
            EventLogBuffer.log("killAppProcesses: forceStopPackage reflection failed: " + t);
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
            EventLogBuffer.log("killAppProcesses enumerate failed: " + t);
        }

        Set<Integer> killed = new LinkedHashSet<>();
        for (int pid : pids) {
            try {
                Process.killProcess(pid);
                killed.add(pid);
                EventLogBuffer.log("killAppProcesses: killed pid " + pid + " for " + pkg);
            } catch (Throwable t) {
                EventLogBuffer.log("killAppProcesses: kill pid " + pid + " failed: " + t);
            }
        }
        return "uid=" + uid + " forceStop=" + forceStopCalled + " killedPids=" + killed;
    }

    static void markUserDisabled(String key, boolean disabled) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if (disabled) {
            sUserDisabledKeys.add(key);
        } else {
            sUserDisabledKeys.remove(key);
        }
    }

    static boolean isUserDisabled(String key) {
        return key != null && sUserDisabledKeys.contains(key);
    }

    static void retainUserDisabledForManaged(Set<String> managedKeys) {
        sUserDisabledKeys.retainAll(managedKeys);
    }

    static void saveHotReloadState(Map<String, Object> state) {
        state.put(STATE_MODULE_START_AT, sModuleStartAt);
        state.put(STATE_FIRST_CONNECT_AT, sFirstServiceConnectAt);
        state.put(STATE_LAST_PKG_KILL_AT, new HashMap<>(sLastPackageKillAt));
        state.put(STATE_USER_DISABLED_KEYS, new ArrayList<>(sUserDisabledKeys));
    }

    static void restoreHotReloadState(Map<?, ?> state) {
        Object firstConnect = state.get(STATE_FIRST_CONNECT_AT);
        if (firstConnect instanceof Long) {
            sFirstServiceConnectAt = (Long) firstConnect;
        }
        Object moduleStart = state.get(STATE_MODULE_START_AT);
        if (moduleStart instanceof Long && (Long) moduleStart > 0L) {
            sModuleStartAt = (Long) moduleStart;
        } else if (sFirstServiceConnectAt > 0L) {
            sModuleStartAt = Math.max(1L, sFirstServiceConnectAt - AutoRepairEntry.BOOT_GRACE_MS);
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
        Object userDisabled = state.get(STATE_USER_DISABLED_KEYS);
        if (userDisabled instanceof List) {
            sUserDisabledKeys.clear();
            for (Object item : (List<?>) userDisabled) {
                if (item instanceof String && !((String) item).isEmpty()) {
                    sUserDisabledKeys.add((String) item);
                }
            }
        }
    }

    static void clearForHotReload() {
        Handler handler = sHandler;
        sHandler = null;
        if (handler != null) {
            for (Runnable runnable : sMachineTimers.values()) {
                if (runnable != null) {
                    handler.removeCallbacks(runnable);
                }
            }
            for (Runnable runnable : sPendingMachineSync.values()) {
                if (runnable != null) {
                    handler.removeCallbacks(runnable);
                }
            }
            if (sPostBootCheckRunnable != null) {
                handler.removeCallbacks(sPostBootCheckRunnable);
            }
        }
        sPostBootCheckRunnable = null;
        sLastPackageKillAt.clear();
        sMachines.clear();
        sMachineTimers.clear();
        sPendingMachineSync.clear();
        sUserDisabledKeys.clear();
        sPackageRepairRequests.clear();
        sPackageRepairQueue.clear();
        sActiveRepairBatch.clear();
        sPendingRestoreKeys.clear();
        sPendingRestoreReason = "";
        sRestoreFlushPending = false;
        sLastRestoreWriteAt = 0L;
        sRestoreWriteRetryCount = 0;
        sRepairTransactionActive = false;
        sRepairTransactionPackage = null;
        sRepairTransactionGeneration = 0L;
    }

    static void resetForTesting() {
        clearForHotReload();
        sFirstServiceConnectAt = 0L;
        sModuleStartAt = SystemClock.elapsedRealtime();
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

    static void putUserDisabledKeyForTesting(String key) {
        if (key != null && !key.isEmpty()) {
            sUserDisabledKeys.add(key);
        }
    }

    static Set<String> getUserDisabledKeysForTesting() {
        return new LinkedHashSet<>(sUserDisabledKeys);
    }
}
