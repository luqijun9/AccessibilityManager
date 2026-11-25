package com.accessibilitymanager.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 单个无障碍服务的修复状态机（纯逻辑，无 Android / Xposed 依赖）。
 *
 * <p>重构背景：
 * 旧实现用 sSuppressUntil、sSelfRemovedUntil、sManualHiddenUntil、sPendingCheck、
 * sVerifyRun、sStabilityRun、sCheckRetries 等十几份时间窗口/任务表，隐式表达服务当前阶段。
 * 每个事件处理函数都要同时检查多份表，导致同包多服务、抑制期二次查杀、用户关闭竞态等场景
 * 互相叠加，分支快速膨胀。
 *
 * <p>本类把“服务现在处于什么阶段”显式建模为状态，把“下一步做什么”显式建模为效果（Effect）。
 * 所有输入都来自事件 + 快照，所有时间都来自快照/事件，不直接读取系统时钟，便于单元测试。
 *
 * <p>线程模型：
 * 状态机本身不做同步，约定只由单线程事件循环调用；因此不需要全局保护锁。
 * 唯一需要串行的是 Settings 的 read-modify-write，由 {@link SettingsWriter} 负责。
 */
final class ServiceRepairStateMachine {

    /**
     * 服务生命周期状态。
     *
     * <p>状态不要与“绑定状态”混淆：
     * BOUND 表示最近一次已知连接是健康的；SUSPECT 表示观察到断开但还没确认是否真死；
     * REPAIRING 表示已加入包级事务，此期间的断开是预期行为。
     */
    enum State {
        /** 不在锁定列表，或系统应用被忽略：不参与任何修复 */
        UNMANAGED,
        /** 用户明确关闭：不得复活，直到用户重新开启/重新加入锁定列表 */
        DISABLED,
        /** 模块被关闭：暂停所有修复，保留已有状态，模块重新启用后重新评估 */
        SUSPENDED,
        /** 最近一次已知连接健康 */
        BOUND,
        /** 观察到断开，等待 AMS 自愈/OEM 瞬态恢复 */
        SUSPECT,
        /** 已确认未绑定且允许修复，等待包级协调器收编 */
        NEEDS_REPAIR,
        /** 已被包级协调器收编，等待事务开始 */
        QUEUED,
        /** 正在执行包级关开/强杀事务；此期间的断开是预期行为 */
        REPAIRING,
        /** 关开写入已完成，等待绑定校验 */
        VERIFYING,
        /** 快速失败退避中，定时器到点后重新评估 */
        BACKOFF,
        /** 锁定服务从启用列表消失（OEM force-stop/外部移除），只做 delta 补回，不强杀 */
        RESTORING
    }

    /** 状态机自己持有的定时器类型；每个服务最多只有一个有效定时器。 */
    enum TimerType {
        /** 断开后的 settle 等待 */
        SETTLE,
        /** 关开写入后的绑定校验 */
        VERIFY,
        /** 修复成功后的稳定性复查 */
        STABILITY,
        /** 快速失败退避 */
        BACKOFF,
        /** 开机/解锁启动潮冷却，期间不修复 */
        BOOT_GRACE,
        /** 绑定状态不可读时的重试 */
        BIND_RETRY
    }

    /** 状态机向外部执行层发出的动作；执行层负责真正的 Settings 写入、强杀、日志/Toast。 */
    enum ActionType {
        /** 取消该服务当前所有定时器 */
        CANCEL_TIMERS,
        /** 安排一个带 generation 的定时器 */
        SCHEDULE_TIMER,
        /** 请求包级协调器收编修复（可带 allowKill 策略） */
        REQUEST_REPAIR,
        /** 请求 delta 补回启用列表（不加强杀） */
        REQUEST_RESTORE,
        /** 清除 AMS 的 crashed 标记 */
        CLEAR_CRASHED_MARKER,
        /** 服务从故障状态恢复；执行层据此记录日志/决定是否 Toast */
        NOTIFY_RECOVERED,
        /** 用户关闭/移出锁定列表时，通知包级协调器把该成员从当前批次移除 */
        REMOVE_FROM_BATCH
    }

    /** 一个待执行效果。只有 SCHEDULE_TIMER 会携带 timerType/delay/generation。 */
    static final class Effect {
        private final ActionType type;
        private final TimerType timerType;
        private final long delayMs;
        private final long generation;
        private final boolean allowKill;
        private final String reason;

        private Effect(ActionType type, TimerType timerType, long delayMs, long generation,
                boolean allowKill, String reason) {
            this.type = type;
            this.timerType = timerType;
            this.delayMs = delayMs;
            this.generation = generation;
            this.allowKill = allowKill;
            this.reason = reason;
        }

        static Effect cancel(String reason) {
            return new Effect(ActionType.CANCEL_TIMERS, null, 0L, 0L, false, reason);
        }

        static Effect timer(TimerType timerType, long delayMs, String reason) {
            return new Effect(ActionType.SCHEDULE_TIMER, timerType, Math.max(0L, delayMs),
                    0L, false, reason);
        }

        static Effect requestRepair(boolean allowKill, String reason) {
            return new Effect(ActionType.REQUEST_REPAIR, null, 0L, 0L, allowKill, reason);
        }

        static Effect requestRestore(String reason) {
            return new Effect(ActionType.REQUEST_RESTORE, null, 0L, 0L, false, reason);
        }

        static Effect clearCrashedMarker() {
            return new Effect(ActionType.CLEAR_CRASHED_MARKER, null, 0L, 0L, false, null);
        }

        static Effect notifyRecovered() {
            return new Effect(ActionType.NOTIFY_RECOVERED, null, 0L, 0L, false, null);
        }

        static Effect removeFromBatch() {
            return new Effect(ActionType.REMOVE_FROM_BATCH, null, 0L, 0L, false, null);
        }

        ActionType getType() {
            return type;
        }

        TimerType getTimerType() {
            return timerType;
        }

        long getDelayMs() {
            return delayMs;
        }

        long getGeneration() {
            return generation;
        }

        boolean isAllowKill() {
            return allowKill;
        }

        String getReason() {
            return reason;
        }

        Effect withGeneration(long value) {
            if (type != ActionType.SCHEDULE_TIMER) {
                return this;
            }
            return new Effect(type, timerType, delayMs, value, allowKill, reason);
        }
    }

    /** 一次事件处理结果：状态迁移、generation 和需要执行的效果列表。 */
    static final class Transition {
        private final State from;
        private final State to;
        private final long generation;
        private final List<Effect> effects;

        Transition(State from, State to, long generation, List<Effect> effects) {
            this.from = from;
            this.to = to;
            this.generation = generation;
            this.effects = Collections.unmodifiableList(new ArrayList<>(effects));
        }

        State getFrom() {
            return from;
        }

        State getTo() {
            return to;
        }

        long getGeneration() {
            return generation;
        }

        List<Effect> getEffects() {
            return effects;
        }

        boolean isNoOp() {
            return from == to && effects.isEmpty();
        }

        boolean hasEffect(ActionType type) {
            return findEffect(type) != null;
        }

        Effect findEffect(ActionType type) {
            for (Effect effect : effects) {
                if (effect.getType() == type) {
                    return effect;
                }
            }
            return null;
        }

        Effect findTimer(TimerType type) {
            for (Effect effect : effects) {
                if (effect.getType() == ActionType.SCHEDULE_TIMER
                        && effect.getTimerType() == type) {
                    return effect;
                }
            }
            return null;
        }
    }

    /**
     * 事件发生时刻的外部世界快照。
     *
     * <p>约定：快照必须反映“事件已经应用之后”的世界状态，例如 USER_ENABLE 时
     * userDisabled 必须为 false；否则全局门控会仍然把服务按关闭处理。
     *
     * <p>默认值全部取 false 的保守语义：调用方漏填字段时状态机倾向于“不修复”，
     * 避免因为快照构造错误去复活不该复活的服务。
     */
    static final class Snapshot {
        boolean managed;
        boolean moduleEnabled;
        boolean inEnabledList;
        boolean bound;
        boolean bindCheckAvailable;
        boolean userDisabled;
        boolean bootGraceActive;
        long bootGraceRemainingMs;
        long now;
    }

    /** 状态机输入事件。 */
    static final class Event {
        enum Type {
            SYNC,
            BINDER_DIED,
            INITIALIZED,
            ENABLED_LIST_CHANGED,
            MANAGED_CHANGED,
            USER_DISABLE,
            USER_ENABLE,
            MODULE_DISABLED,
            MODULE_ENABLED,
            TIMER,
            PACKAGE_REPAIR_STARTED,
            PACKAGE_REPAIR_APPLIED,
            PACKAGE_REPAIR_FAILED
        }

        private final Type type;
        private boolean success;
        private boolean newerConnectionBound;
        private TimerType timerType;
        private long generation;

        private Event(Type type) {
            this.type = type;
        }

        static Event sync() {
            return new Event(Type.SYNC);
        }

        static Event binderDied(boolean newerConnectionBound) {
            Event event = new Event(Type.BINDER_DIED);
            event.newerConnectionBound = newerConnectionBound;
            return event;
        }

        static Event initialized(boolean success) {
            Event event = new Event(Type.INITIALIZED);
            event.success = success;
            return event;
        }

        static Event enabledListChanged() {
            return new Event(Type.ENABLED_LIST_CHANGED);
        }

        static Event managedChanged() {
            return new Event(Type.MANAGED_CHANGED);
        }

        static Event userDisable() {
            return new Event(Type.USER_DISABLE);
        }

        static Event userEnable() {
            return new Event(Type.USER_ENABLE);
        }

        static Event moduleDisabled() {
            return new Event(Type.MODULE_DISABLED);
        }

        static Event moduleEnabled() {
            return new Event(Type.MODULE_ENABLED);
        }

        static Event timer(TimerType timerType, long generation) {
            Event event = new Event(Type.TIMER);
            event.timerType = timerType;
            event.generation = generation;
            return event;
        }

        static Event packageRepairStarted() {
            return new Event(Type.PACKAGE_REPAIR_STARTED);
        }

        static Event packageRepairApplied() {
            return new Event(Type.PACKAGE_REPAIR_APPLIED);
        }

        static Event packageRepairFailed() {
            return new Event(Type.PACKAGE_REPAIR_FAILED);
        }

        Type getType() {
            return type;
        }

        boolean isSuccess() {
            return success;
        }

        boolean isNewerConnectionBound() {
            return newerConnectionBound;
        }

        TimerType getTimerType() {
            return timerType;
        }

        long getGeneration() {
            return generation;
        }
    }

    private State state = State.UNMANAGED;
    private long generation;
    private long boundAt;
    private int fastFailCount;
    private int bindRetryAttempts;
    private long lastDeathAt;

    State getState() {
        return state;
    }

    long getGeneration() {
        return generation;
    }

    long getBoundAt() {
        return boundAt;
    }

    int getFastFailCount() {
        return fastFailCount;
    }

    long getLastDeathAt() {
        return lastDeathAt;
    }

    /**
     * 处理一个事件并返回迁移结果。
     *
     * <p>防御性约定：
     * - event/snapshot 为 null 时返回 no-op，绝不让 system_server 内的状态机因空指针抛异常；
     * - 过期定时器（generation 不匹配）直接忽略，避免旧 verify/stability 穿透到新状态；
     * - 模块停用、未锁定、用户关闭三个全局门控优先于所有事件，防止复活不该复活的服务。
     */
    Transition handle(Event event, Snapshot snapshot) {
        State from = state;
        if (event == null || snapshot == null) {
            return noop(from);
        }
        if (event.getType() == Event.Type.TIMER
                && event.getGeneration() != generation) {
            return noop(from);
        }

        // 显式关闭/停用事件优先于快照门控，避免调用方漏更新快照时继续修复
        if (event.getType() == Event.Type.MODULE_DISABLED) {
            return toSuspended(from);
        }
        if (event.getType() == Event.Type.USER_DISABLE) {
            return toDisabled(from);
        }

        if (!snapshot.moduleEnabled) {
            return toSuspended(from);
        }
        if (!snapshot.managed) {
            return toUnmanaged(from);
        }
        if (snapshot.userDisabled) {
            return toDisabled(from);
        }

        switch (event.getType()) {
            case SYNC:
            case MANAGED_CHANGED:
            case MODULE_ENABLED:
                return handleSync(snapshot, from);
            case BINDER_DIED:
                return handleBinderDied(event, snapshot, from);
            case INITIALIZED:
                return handleInitialized(event, snapshot, from);
            case ENABLED_LIST_CHANGED:
                return handleEnabledListChanged(snapshot, from);
            case USER_ENABLE:
                return handleUserEnable(snapshot, from);
            case TIMER:
                return handleTimer(event, snapshot, from);
            case PACKAGE_REPAIR_STARTED:
                return handlePackageStarted(snapshot, from);
            case PACKAGE_REPAIR_APPLIED:
                return handlePackageApplied(from);
            case PACKAGE_REPAIR_FAILED:
                return handlePackageFailed(from);
            default:
                return noop(from);
        }
    }

    private Transition handleSync(Snapshot snapshot, State from) {
        // 修复事务进行中不重新评估，避免把协调器的临时移除误判成“服务被关闭”
        if (from == State.REPAIRING || from == State.QUEUED) {
            return noop(from);
        }
        if (snapshot.bootGraceActive && !snapshot.bound) {
            return change(from, State.SUSPECT, "sync_boot_grace",
                    Effect.timer(TimerType.BOOT_GRACE, bootGraceDelay(snapshot), "sync"));
        }
        if (!snapshot.inEnabledList) {
            return change(from, State.RESTORING, "sync_missing_enabled",
                    Effect.requestRestore("sync"));
        }
        if (snapshot.bound) {
            return change(from, State.BOUND, "sync_bound",
                    Effect.clearCrashedMarker());
        }
        if (!snapshot.bindCheckAvailable) {
            return change(from, State.SUSPECT, "sync_bind_unknown",
                    Effect.timer(TimerType.BIND_RETRY,
                            RepairPolicy.FSM_BIND_RETRY_MS, "sync"));
        }
        return change(from, State.SUSPECT, "sync_settle",
                Effect.timer(TimerType.SETTLE, RepairPolicy.FSM_SETTLE_MS, "sync"));
    }

    private Transition handleBinderDied(Event event, Snapshot snapshot, State from) {
        // 包级事务正在关开/强杀，断开是预期行为；由 PACKAGE_REPAIR_APPLIED 统一进入 VERIFYING
        if (from == State.REPAIRING) {
            return noop(from);
        }
        // 旧连接退役：AMS 中已经有更新连接，清掉可能被写入的 crashed 标记即可
        if (event.isNewerConnectionBound()) {
            if (from == State.BOUND) {
                return stay(from, Effect.clearCrashedMarker());
            }
            if (from == State.SUSPECT || from == State.VERIFYING
                    || from == State.BACKOFF || from == State.RESTORING
                    || from == State.DISABLED) {
                return recoveryTransition(from, snapshot, "old_connection_died_new_bound");
            }
            return stay(from, Effect.clearCrashedMarker());
        }
        if (from == State.UNMANAGED || from == State.DISABLED || from == State.SUSPENDED) {
            return noop(from);
        }
        // 已经排队等待修复时，新的断开事件不重复入队；协调器会按批次统一校验
        if (from == State.NEEDS_REPAIR || from == State.QUEUED) {
            return noop(from);
        }

        if (from == State.BOUND || from == State.VERIFYING) {
            // VERIFYING 阶段没有 boundAt 说明“修复写入完成但尚未见到有效连接”，
            // 此时再次断开必须按快速失败累计，不能当成“寿命未知=健康”清零；
            // BOUND 阶段的 boundAt 未知则多发生在热重载/加载前已绑定，按健康处理更稳妥。
            long lifetime;
            if (boundAt > 0L) {
                lifetime = Math.max(0L, snapshot.now - boundAt);
            } else {
                lifetime = from == State.VERIFYING ? 0L : -1L;
            }
            fastFailCount = RepairPolicy.nextFastFailCount(fastFailCount, lifetime);
            boundAt = 0L;
        }
        lastDeathAt = snapshot.now;
        if (snapshot.bootGraceActive) {
            return change(from, State.SUSPECT, "died_boot_grace",
                    Effect.timer(TimerType.BOOT_GRACE, bootGraceDelay(snapshot), "died"));
        }
        return change(from, State.SUSPECT, "died_settle",
                Effect.timer(TimerType.SETTLE, RepairPolicy.FSM_SETTLE_MS, "died"));
    }

    private Transition handleInitialized(Event event, Snapshot snapshot, State from) {
        if (event.isSuccess()) {
            // 重复 initializeService：不重置 boundAt，也不取消尚未到期的稳定性复查
            if (from == State.BOUND) {
                return stay(from, Effect.clearCrashedMarker());
            }
            boundAt = snapshot.now;
            bindRetryAttempts = 0;
            boolean recovered = from == State.SUSPECT || from == State.VERIFYING
                    || from == State.RESTORING || from == State.BACKOFF
                    || from == State.DISABLED || from == State.QUEUED
                    || from == State.REPAIRING;
            if (recovered) {
                return recoveryTransition(from, snapshot, "init_success");
            }
            return change(from, State.BOUND, "init_success",
                    Effect.clearCrashedMarker());
        }

        // 重绑失败：事务/队列中的服务由协调器统一校验，不在单服务路径重复排期
        if (from == State.REPAIRING || from == State.NEEDS_REPAIR || from == State.QUEUED) {
            return noop(from);
        }
        if (from == State.UNMANAGED || from == State.DISABLED || from == State.SUSPENDED) {
            return noop(from);
        }
        if (from == State.VERIFYING || from == State.BOUND) {
            // 修复写入完成后进入 VERIFYING 阶段若立即收到重绑失败（INITIALIZED(false)），
            // 说明新连接未能成功建立；若不累加 fastFailCount，状态机会直接切回 SUSPECT
            // 并以 0ms 退避在 3.5s 后再次关开，在兼容异常或连续重绑失败时形成高频关开风暴。
            long lifetime;
            if (boundAt > 0L) {
                lifetime = Math.max(0L, snapshot.now - boundAt);
            } else {
                lifetime = from == State.VERIFYING ? 0L : -1L;
            }
            fastFailCount = RepairPolicy.nextFastFailCount(fastFailCount, lifetime);
        }
        boundAt = 0L;
        if (snapshot.bootGraceActive) {
            return change(from, State.SUSPECT, "init_failed_boot_grace",
                    Effect.timer(TimerType.BOOT_GRACE, bootGraceDelay(snapshot), "init_failed"));
        }
        return change(from, State.SUSPECT, "init_failed_settle",
                Effect.timer(TimerType.SETTLE, RepairPolicy.FSM_SETTLE_MS, "init_failed"));
    }

    private Transition handleEnabledListChanged(Snapshot snapshot, State from) {
        // 包级事务自己的移除/回加：不能当成用户关闭或 OEM 移除
        if (from == State.REPAIRING) {
            return noop(from);
        }
        if (!snapshot.inEnabledList) {
            if (from == State.DISABLED || from == State.UNMANAGED
                    || from == State.SUSPENDED) {
                return noop(from);
            }
            // OEM force-stop 会直接把整包服务从启用列表移除；这里只补回，不强杀
            return change(from, State.RESTORING, "enabled_missing",
                    Effect.requestRestore("enabled_changed"));
        }
        // 重新出现在启用列表：用户重新开启、补回写入完成、或外部重新启用
        if (from == State.RESTORING || from == State.DISABLED
                || from == State.UNMANAGED) {
            if (snapshot.bound) {
                return recoveryTransition(from, snapshot, "enabled_present_bound");
            }
            return change(from, State.SUSPECT, "enabled_present_settle",
                    Effect.timer(TimerType.SETTLE, RepairPolicy.FSM_SETTLE_MS,
                            "enabled_present"));
        }
        return noop(from);
    }

    private Transition handleUserEnable(Snapshot snapshot, State from) {
        if (from == State.BOUND) {
            return noop(from);
        }
        if (snapshot.bound) {
            return recoveryTransition(from, snapshot, "user_enable_bound");
        }
        return change(from, State.SUSPECT, "user_enable_settle",
                Effect.timer(TimerType.SETTLE, RepairPolicy.FSM_SETTLE_MS, "user_enable"));
    }

    private Transition handlePackageStarted(Snapshot snapshot, State from) {
        // 全局门控已经保证 managed/moduleEnabled/userDisabled 合法；
        // 这里仍以快照为准，避免批次里混入已被关闭/移出锁定列表的成员。
        if (!snapshot.managed || snapshot.userDisabled || !snapshot.moduleEnabled) {
            return stay(from, Effect.removeFromBatch());
        }
        return change(from, State.REPAIRING, "package_repair_started");
    }

    private Transition handlePackageApplied(State from) {
        if (from != State.REPAIRING && from != State.QUEUED) {
            return noop(from);
        }
        return change(from, State.VERIFYING, "package_repair_applied",
                Effect.timer(TimerType.VERIFY, RepairPolicy.FSM_VERIFY_MS, "package_applied"));
    }

    private Transition handlePackageFailed(State from) {
        if (from != State.REPAIRING && from != State.QUEUED) {
            return noop(from);
        }
        long delay = RepairPolicy.repairBackoffMs(fastFailCount);
        return change(from, State.BACKOFF, "package_repair_failed",
                Effect.timer(TimerType.BACKOFF, delay, "package_failed"));
    }

    private Transition handleTimer(Event event, Snapshot snapshot, State from) {
        if (event.getTimerType() == null) {
            return noop(from);
        }
        if (event.getTimerType() == TimerType.STABILITY) {
            if (snapshot.bound) {
                long lifetime = boundAt > 0L
                        ? Math.max(0L, snapshot.now - boundAt) : -1L;
                if (RepairPolicy.shouldResetFastFail(lifetime)) {
                    fastFailCount = 0;
                }
                if (from == State.BOUND) {
                    // 确认已绑定时顺手清掉 AMS 可能残留的 crashed 标记，避免设置页显示崩溃
                    return stay(from, Effect.clearCrashedMarker());
                }
                return recoveryTransition(from, snapshot, "stability_ok");
            }
            return evaluateUnbound(snapshot, from, "stability_unbound");
        }
        if (event.getTimerType() == TimerType.VERIFY
                && !snapshot.bound
                && snapshot.bindCheckAvailable
                && snapshot.inEnabledList
                && !snapshot.bootGraceActive) {
            // 关开修复后的 VERIFY 定时器到点仍未绑定，且期间未触发 binderDied 或 INITIALIZED(false)：
            // 按快速失败累计计数，避免在无断开回调的 ROM 上每 2.5s 无退避重复关开；
            // 注意 fastFailCount=0->1 时 repairBackoffMs(1)==0L，首次校验失败仍会立即重修，第 2 次起逐步退避。
            fastFailCount = RepairPolicy.nextFastFailCount(fastFailCount, 0L);
        }
        return evaluateUnbound(snapshot, from, "timer_" + event.getTimerType());
    }

    /**
     * 定时器到点后的统一评估：先看是否已恢复，再看环境/配置门控，最后决定重试、退避或请求修复。
     */
    private Transition evaluateUnbound(Snapshot snapshot, State from, String reason) {
        if (from == State.REPAIRING) {
            // 事务进行中不应由单服务定时器介入，等协调器 APPLIED/FAILED
            return noop(from);
        }
        if (snapshot.bound) {
            return recoveryTransition(from, snapshot, reason + "_bound");
        }
        if (snapshot.bootGraceActive) {
            return change(from, State.SUSPECT, reason + "_boot_grace",
                    Effect.timer(TimerType.BOOT_GRACE, bootGraceDelay(snapshot), reason));
        }
        if (!snapshot.inEnabledList) {
            return change(from, State.RESTORING, reason + "_missing_enabled",
                    Effect.requestRestore(reason));
        }
        if (!snapshot.bindCheckAvailable) {
            if (bindRetryAttempts >= RepairPolicy.FSM_MAX_BIND_RETRY_ATTEMPTS) {
                // 绑定状态长期不可读：停止定时器空转，等服务/配置事件重新触发
                return change(from, State.SUSPECT, reason + "_bind_unknown_give_up");
            }
            bindRetryAttempts++;
            return change(from, State.SUSPECT, reason + "_bind_unknown_retry",
                    Effect.timer(TimerType.BIND_RETRY,
                            RepairPolicy.FSM_BIND_RETRY_MS, reason));
        }
        bindRetryAttempts = 0;
        long backoff = RepairPolicy.repairBackoffMs(fastFailCount);
        // BACKOFF 定时器到点时即使还有退避值也要前进，否则会永远停在退避
        if (backoff > 0L && from != State.BACKOFF) {
            return change(from, State.BACKOFF, reason + "_backoff",
                    Effect.timer(TimerType.BACKOFF, backoff, reason));
        }
        boolean allowKill = RepairPolicy.shouldUseKillForFastFail(fastFailCount);
        return change(from, State.NEEDS_REPAIR, reason + "_needs_repair",
                Effect.requestRepair(allowKill, reason));
    }

    /** 从故障/关闭状态恢复为 BOUND：清 crashed 标记、通知恢复，并安排一次稳定性复查。 */
    private Transition recoveryTransition(State from, Snapshot snapshot, String reason) {
        boundAt = snapshot.now;
        bindRetryAttempts = 0;
        return change(from, State.BOUND, reason,
                Effect.clearCrashedMarker(),
                Effect.notifyRecovered(),
                Effect.timer(TimerType.STABILITY, RepairPolicy.FSM_STABILITY_MS, reason));
    }

    private Transition toSuspended(State from) {
        if (from == State.SUSPENDED) {
            return noop(from);
        }
        List<Effect> effects = new ArrayList<>();
        if (from == State.QUEUED || from == State.REPAIRING) {
            effects.add(Effect.removeFromBatch());
        }
        return change(from, State.SUSPENDED, "module_disabled", effects);
    }

    private Transition toDisabled(State from) {
        if (from == State.DISABLED) {
            return noop(from);
        }
        List<Effect> effects = new ArrayList<>();
        if (from == State.QUEUED || from == State.REPAIRING) {
            effects.add(Effect.removeFromBatch());
        }
        return change(from, State.DISABLED, "user_disabled", effects);
    }

    private Transition toUnmanaged(State from) {
        if (from == State.UNMANAGED) {
            return noop(from);
        }
        List<Effect> effects = new ArrayList<>();
        if (from == State.QUEUED || from == State.REPAIRING) {
            effects.add(Effect.removeFromBatch());
        }
        return change(from, State.UNMANAGED, "unmanaged", effects);
    }

    private Transition noop(State from) {
        return new Transition(from, state, generation, Collections.<Effect>emptyList());
    }

    private Transition stay(State from, Effect... effects) {
        return new Transition(from, state, generation, Arrays.asList(effects));
    }

    /**
     * 状态迁移：自动取消旧定时器、递增 generation，并把定时器效果绑定到新 generation。
     * 这样旧 verify/stability/backoff 回调到达时会被 handle 开头的 generation 校验丢弃。
     */
    private Transition change(State from, State to, String reason, Effect... extra) {
        return change(from, to, reason, Arrays.asList(extra));
    }

    private Transition change(State from, State to, String reason, List<Effect> extra) {
        long newGeneration = ++generation;
        state = to;
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.cancel(reason));
        if (extra != null) {
            for (Effect effect : extra) {
                if (effect == null) {
                    continue;
                }
                effects.add(effect.withGeneration(newGeneration));
            }
        }
        return new Transition(from, to, newGeneration, effects);
    }

    private static long bootGraceDelay(Snapshot snapshot) {
        // 避免快照剩余时间为 0/负数时形成 0ms 定时器忙循环
        return Math.max(1000L, snapshot.bootGraceRemainingMs);
    }
}
