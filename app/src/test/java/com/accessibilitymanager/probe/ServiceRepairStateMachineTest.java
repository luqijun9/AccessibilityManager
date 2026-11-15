package com.accessibilitymanager.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ServiceRepairStateMachine 纯逻辑单元测试。
 *
 * <p>覆盖真实故障场景：
 * 同包多服务、旧连接延迟 binderDied、抑制期二次查杀、启动宽限、快速失败退避、
 * 开机冷却、用户关闭、模块停用、OEM 移除启用列表、包级事务和过期定时器。
 */
public class ServiceRepairStateMachineTest {

    private static ServiceRepairStateMachine.Snapshot snapshot(long now) {
        ServiceRepairStateMachine.Snapshot snapshot = new ServiceRepairStateMachine.Snapshot();
        snapshot.now = now;
        snapshot.managed = true;
        snapshot.moduleEnabled = true;
        snapshot.inEnabledList = true;
        snapshot.bindCheckAvailable = true;
        return snapshot;
    }

    private static void initBound(ServiceRepairStateMachine machine, long now) {
        ServiceRepairStateMachine.Transition transition =
                machine.handle(ServiceRepairStateMachine.Event.initialized(true, true),
                        snapshot(now));
        assertEquals(ServiceRepairStateMachine.State.BOUND, transition.getTo());
    }

    /** 驱动到 NEEDS_REPAIR：绑定 → 短命断开 → settle 到期且进程未启动。 */
    private static ServiceRepairStateMachine.Transition driveToNeedsRepair(
            ServiceRepairStateMachine machine, long start) {
        initBound(machine, start);
        ServiceRepairStateMachine.Transition died = machine.handle(
                ServiceRepairStateMachine.Event.binderDied(false, false),
                snapshot(start + 1_000L));
        ServiceRepairStateMachine.Snapshot settleSnapshot = snapshot(start + 4_000L);
        settleSnapshot.processRunning = false;
        return machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.SETTLE,
                        died.getGeneration()),
                settleSnapshot);
    }

    @Test
    public void sync_unboundManaged_enabled_entersSuspectWithSettleTimer() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();

        ServiceRepairStateMachine.Transition t = machine.handle(
                ServiceRepairStateMachine.Event.sync(), snapshot(1_000L));

        assertEquals(ServiceRepairStateMachine.State.SUSPECT, t.getTo());
        assertTrue(t.hasEffect(ServiceRepairStateMachine.ActionType.CANCEL_TIMERS));
        ServiceRepairStateMachine.Effect timer = t.findTimer(
                ServiceRepairStateMachine.TimerType.SETTLE);
        assertNotNull(timer);
        assertEquals(RepairPolicy.FSM_SETTLE_MS, timer.getDelayMs());
        assertEquals(t.getGeneration(), timer.getGeneration());
        assertFalse(t.hasEffect(ServiceRepairStateMachine.ActionType.REQUEST_REPAIR));
    }

    @Test
    public void sync_bound_entersBoundAndClearsCrashedMarker() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        ServiceRepairStateMachine.Snapshot snapshot = snapshot(1_000L);
        snapshot.bound = true;

        ServiceRepairStateMachine.Transition t = machine.handle(
                ServiceRepairStateMachine.Event.sync(), snapshot);

        assertEquals(ServiceRepairStateMachine.State.BOUND, t.getTo());
        assertTrue(t.hasEffect(ServiceRepairStateMachine.ActionType.CLEAR_CRASHED_MARKER));
        assertNull(t.findTimer(ServiceRepairStateMachine.TimerType.SETTLE));
    }

    @Test
    public void binderDied_fromBound_entersSuspectAndCountsShortLivedFailure() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);

        ServiceRepairStateMachine.Transition t = machine.handle(
                ServiceRepairStateMachine.Event.binderDied(false, false),
                snapshot(2_000L));

        assertEquals(ServiceRepairStateMachine.State.SUSPECT, t.getTo());
        assertEquals(1, machine.getFastFailCount());
        assertEquals(RepairPolicy.FSM_SETTLE_MS,
                t.findTimer(ServiceRepairStateMachine.TimerType.SETTLE).getDelayMs());
    }

    @Test
    public void binderDied_oldConnection_newBound_staysBoundAndClearsMarker() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);

        ServiceRepairStateMachine.Transition t = machine.handle(
                ServiceRepairStateMachine.Event.binderDied(true, false),
                snapshot(2_000L));

        assertEquals(ServiceRepairStateMachine.State.BOUND, t.getTo());
        assertTrue(t.hasEffect(ServiceRepairStateMachine.ActionType.CLEAR_CRASHED_MARKER));
        assertFalse(t.hasEffect(ServiceRepairStateMachine.ActionType.REQUEST_REPAIR));
        assertEquals(0, machine.getFastFailCount());
    }

    @Test
    public void settleTimer_bound_recoversAndSchedulesStabilityCheck() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);
        ServiceRepairStateMachine.Transition died = machine.handle(
                ServiceRepairStateMachine.Event.binderDied(false, false),
                snapshot(2_000L));

        ServiceRepairStateMachine.Snapshot recovered = snapshot(5_000L);
        recovered.bound = true;
        ServiceRepairStateMachine.Transition t = machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.SETTLE,
                        died.getGeneration()),
                recovered);

        assertEquals(ServiceRepairStateMachine.State.BOUND, t.getTo());
        assertTrue(t.hasEffect(ServiceRepairStateMachine.ActionType.NOTIFY_RECOVERED));
        assertNotNull(t.findTimer(ServiceRepairStateMachine.TimerType.STABILITY));
    }

    @Test
    public void settleTimer_unbound_processRunning_grantsTwoGracesThenRepairs() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);
        ServiceRepairStateMachine.Transition died = machine.handle(
                ServiceRepairStateMachine.Event.binderDied(false, false),
                snapshot(2_000L));

        ServiceRepairStateMachine.Snapshot graceSnapshot = snapshot(5_000L);
        graceSnapshot.processRunning = true;
        ServiceRepairStateMachine.Transition first = machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.SETTLE,
                        died.getGeneration()),
                graceSnapshot);
        assertEquals(ServiceRepairStateMachine.State.SUSPECT, first.getTo());
        assertEquals(1, machine.getGraceAttempts());
        assertEquals(RepairPolicy.FSM_GRACE_MS,
                first.findTimer(ServiceRepairStateMachine.TimerType.GRACE).getDelayMs());

        ServiceRepairStateMachine.Transition second = machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.GRACE,
                        first.getGeneration()),
                graceSnapshot);
        assertEquals(ServiceRepairStateMachine.State.SUSPECT, second.getTo());
        assertEquals(2, machine.getGraceAttempts());

        ServiceRepairStateMachine.Transition third = machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.GRACE,
                        second.getGeneration()),
                graceSnapshot);
        assertEquals(ServiceRepairStateMachine.State.NEEDS_REPAIR, third.getTo());
        ServiceRepairStateMachine.Effect repair = third.findEffect(
                ServiceRepairStateMachine.ActionType.REQUEST_REPAIR);
        assertNotNull(repair);
        assertTrue(repair.isAllowKill());
    }

    @Test
    public void fastFail_secondShortLivedDeath_backsOffAndDisablesKill() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);
        machine.handle(ServiceRepairStateMachine.Event.binderDied(false, false),
                snapshot(2_000L));
        assertEquals(1, machine.getFastFailCount());

        // 自愈后再次短命崩溃：快速失败计数累计到 2
        machine.handle(ServiceRepairStateMachine.Event.initialized(true, true),
                snapshot(2_100L));
        ServiceRepairStateMachine.Transition died = machine.handle(
                ServiceRepairStateMachine.Event.binderDied(false, false),
                snapshot(2_200L));
        assertEquals(2, machine.getFastFailCount());

        ServiceRepairStateMachine.Snapshot settleSnapshot = snapshot(5_200L);
        settleSnapshot.processRunning = false;
        ServiceRepairStateMachine.Transition backoff = machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.SETTLE,
                        died.getGeneration()),
                settleSnapshot);

        assertEquals(ServiceRepairStateMachine.State.BACKOFF, backoff.getTo());
        ServiceRepairStateMachine.Effect backoffTimer = backoff.findTimer(
                ServiceRepairStateMachine.TimerType.BACKOFF);
        assertNotNull(backoffTimer);
        assertEquals(RepairPolicy.repairBackoffMs(2), backoffTimer.getDelayMs());

        ServiceRepairStateMachine.Transition retry = machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.BACKOFF,
                        backoff.getGeneration()),
                settleSnapshot);
        assertEquals(ServiceRepairStateMachine.State.NEEDS_REPAIR, retry.getTo());
        ServiceRepairStateMachine.Effect repair = retry.findEffect(
                ServiceRepairStateMachine.ActionType.REQUEST_REPAIR);
        assertNotNull(repair);
        assertFalse("快速失败 >= 2 时不得再次强杀", repair.isAllowKill());
    }

    @Test
    public void bootGrace_deathOrTimer_doesNotRepairUntilGraceEnds() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);
        ServiceRepairStateMachine.Snapshot bootGrace = snapshot(2_000L);
        bootGrace.bootGraceActive = true;
        bootGrace.bootGraceRemainingMs = 5_000L;

        ServiceRepairStateMachine.Transition died = machine.handle(
                ServiceRepairStateMachine.Event.binderDied(false, false), bootGrace);
        assertEquals(ServiceRepairStateMachine.State.SUSPECT, died.getTo());
        assertEquals(5_000L,
                died.findTimer(ServiceRepairStateMachine.TimerType.BOOT_GRACE)
                        .getDelayMs());
        assertFalse(died.hasEffect(ServiceRepairStateMachine.ActionType.REQUEST_REPAIR));

        ServiceRepairStateMachine.Snapshot afterGrace = snapshot(7_000L);
        afterGrace.bootGraceActive = false;
        ServiceRepairStateMachine.Transition t = machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.BOOT_GRACE,
                        died.getGeneration()),
                afterGrace);
        assertEquals(ServiceRepairStateMachine.State.NEEDS_REPAIR, t.getTo());
    }

    @Test
    public void userDisable_stopsRepair_userEnableRestartsSuspectCycle() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);

        ServiceRepairStateMachine.Snapshot disabled = snapshot(2_000L);
        disabled.userDisabled = true;
        ServiceRepairStateMachine.Transition disable = machine.handle(
                ServiceRepairStateMachine.Event.userDisable(), disabled);
        assertEquals(ServiceRepairStateMachine.State.DISABLED, disable.getTo());
        assertFalse(disable.hasEffect(ServiceRepairStateMachine.ActionType.REQUEST_REPAIR));

        ServiceRepairStateMachine.Snapshot enabled = snapshot(3_000L);
        enabled.userDisabled = false;
        enabled.bound = false;
        ServiceRepairStateMachine.Transition enable = machine.handle(
                ServiceRepairStateMachine.Event.userEnable(), enabled);
        assertEquals(ServiceRepairStateMachine.State.SUSPECT, enable.getTo());
        assertNotNull(enable.findTimer(ServiceRepairStateMachine.TimerType.SETTLE));
    }

    @Test
    public void moduleDisabled_suspendsAndCancelsPendingRepair() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        driveToNeedsRepair(machine, 1_000L);
        assertEquals(ServiceRepairStateMachine.State.NEEDS_REPAIR, machine.getState());

        ServiceRepairStateMachine.Snapshot disabled = snapshot(5_000L);
        disabled.moduleEnabled = false;
        ServiceRepairStateMachine.Transition t = machine.handle(
                ServiceRepairStateMachine.Event.moduleDisabled(), disabled);

        assertEquals(ServiceRepairStateMachine.State.SUSPENDED, t.getTo());
        assertTrue(t.hasEffect(ServiceRepairStateMachine.ActionType.CANCEL_TIMERS));
        assertFalse(t.hasEffect(ServiceRepairStateMachine.ActionType.REQUEST_REPAIR));
    }

    @Test
    public void enabledListRemoved_restoresWithoutKill_butIgnoredDuringRepair() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);
        ServiceRepairStateMachine.Snapshot missing = snapshot(2_000L);
        missing.inEnabledList = false;

        ServiceRepairStateMachine.Transition restore = machine.handle(
                ServiceRepairStateMachine.Event.enabledListChanged(), missing);
        assertEquals(ServiceRepairStateMachine.State.RESTORING, restore.getTo());
        ServiceRepairStateMachine.Effect request = restore.findEffect(
                ServiceRepairStateMachine.ActionType.REQUEST_RESTORE);
        assertNotNull(request);
        assertFalse(request.isAllowKill());

        ServiceRepairStateMachine repairing = new ServiceRepairStateMachine();
        driveToNeedsRepair(repairing, 1_000L);
        repairing.handle(ServiceRepairStateMachine.Event.packageRepairStarted(),
                snapshot(5_000L));
        ServiceRepairStateMachine.Transition ignored = repairing.handle(
                ServiceRepairStateMachine.Event.enabledListChanged(),
                snapshot(5_100L));
        assertTrue("事务内自己的移除不能被当成 OEM 移除", ignored.isNoOp());
    }

    @Test
    public void enabledListRestored_fromRestoring_entersSuspectSettle() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);
        ServiceRepairStateMachine.Snapshot missing = snapshot(2_000L);
        missing.inEnabledList = false;
        machine.handle(ServiceRepairStateMachine.Event.enabledListChanged(), missing);

        ServiceRepairStateMachine.Snapshot restored = snapshot(3_000L);
        restored.inEnabledList = true;
        restored.bound = false;
        ServiceRepairStateMachine.Transition t = machine.handle(
                ServiceRepairStateMachine.Event.enabledListChanged(), restored);

        assertEquals(ServiceRepairStateMachine.State.SUSPECT, t.getTo());
        assertNotNull(t.findTimer(ServiceRepairStateMachine.TimerType.SETTLE));
    }

    @Test
    public void packageRepair_appliedThenVerifyBound_recoversAndSchedulesStability() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        driveToNeedsRepair(machine, 1_000L);

        ServiceRepairStateMachine.Transition started = machine.handle(
                ServiceRepairStateMachine.Event.packageRepairStarted(), snapshot(5_000L));
        assertEquals(ServiceRepairStateMachine.State.REPAIRING, started.getTo());

        ServiceRepairStateMachine.Transition applied = machine.handle(
                ServiceRepairStateMachine.Event.packageRepairApplied(), snapshot(5_500L));
        assertEquals(ServiceRepairStateMachine.State.VERIFYING, applied.getTo());
        assertEquals(RepairPolicy.FSM_VERIFY_MS,
                applied.findTimer(ServiceRepairStateMachine.TimerType.VERIFY).getDelayMs());

        ServiceRepairStateMachine.Snapshot bound = snapshot(7_500L);
        bound.bound = true;
        ServiceRepairStateMachine.Transition verified = machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.VERIFY,
                        applied.getGeneration()),
                bound);

        assertEquals(ServiceRepairStateMachine.State.BOUND, verified.getTo());
        assertTrue(verified.hasEffect(ServiceRepairStateMachine.ActionType.NOTIFY_RECOVERED));
        assertNotNull(verified.findTimer(ServiceRepairStateMachine.TimerType.STABILITY));
    }

    @Test
    public void packageRepair_failed_entersBackoff() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        driveToNeedsRepair(machine, 1_000L);
        machine.handle(ServiceRepairStateMachine.Event.packageRepairStarted(),
                snapshot(5_000L));

        ServiceRepairStateMachine.Transition failed = machine.handle(
                ServiceRepairStateMachine.Event.packageRepairFailed(), snapshot(5_500L));

        assertEquals(ServiceRepairStateMachine.State.BACKOFF, failed.getTo());
        assertNotNull(failed.findTimer(ServiceRepairStateMachine.TimerType.BACKOFF));
    }

    @Test
    public void staleTimer_isIgnoredAfterNewerTransition() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);
        ServiceRepairStateMachine.Transition died = machine.handle(
                ServiceRepairStateMachine.Event.binderDied(false, false),
                snapshot(2_000L));
        long oldGeneration = died.getGeneration();

        // 服务自愈，状态迁移到 BOUND，generation 递增
        machine.handle(ServiceRepairStateMachine.Event.initialized(true, true),
                snapshot(2_100L));

        ServiceRepairStateMachine.Transition stale = machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.SETTLE,
                        oldGeneration),
                snapshot(5_000L));

        assertTrue("旧 generation 的 settle 定时器必须被丢弃", stale.isNoOp());
        assertEquals(ServiceRepairStateMachine.State.BOUND, machine.getState());
    }

    @Test
    public void bindCheckUnavailable_retriesThenStopsToAvoidTimerSpin() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        ServiceRepairStateMachine.Snapshot unknown = snapshot(1_000L);
        unknown.bindCheckAvailable = false;

        ServiceRepairStateMachine.Transition t = machine.handle(
                ServiceRepairStateMachine.Event.sync(), unknown);
        assertEquals(ServiceRepairStateMachine.State.SUSPECT, t.getTo());
        assertNotNull(t.findTimer(ServiceRepairStateMachine.TimerType.BIND_RETRY));

        ServiceRepairStateMachine.Transition current = t;
        for (int i = 0; i < RepairPolicy.FSM_MAX_BIND_RETRY_ATTEMPTS; i++) {
            current = machine.handle(
                    ServiceRepairStateMachine.Event.timer(
                            ServiceRepairStateMachine.TimerType.BIND_RETRY,
                            current.getGeneration()),
                    unknown);
            assertNotNull(current.findTimer(ServiceRepairStateMachine.TimerType.BIND_RETRY));
        }

        ServiceRepairStateMachine.Transition giveUp = machine.handle(
                ServiceRepairStateMachine.Event.timer(
                        ServiceRepairStateMachine.TimerType.BIND_RETRY,
                        current.getGeneration()),
                unknown);
        assertEquals(ServiceRepairStateMachine.State.SUSPECT, giveUp.getTo());
        assertNull("重试耗尽后不得继续安排定时器空转",
                giveUp.findTimer(ServiceRepairStateMachine.TimerType.BIND_RETRY));
    }

    @Test
    public void duplicateInitWhileBound_doesNotResetBoundAt() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        initBound(machine, 1_000L);
        assertEquals(1_000L, machine.getBoundAt());

        machine.handle(ServiceRepairStateMachine.Event.initialized(true, true),
                snapshot(9_000L));

        assertEquals(ServiceRepairStateMachine.State.BOUND, machine.getState());
        assertEquals("重复 initializeService 不应刷新连接寿命起点",
                1_000L, machine.getBoundAt());
    }

    @Test
    public void userDisableDuringRepair_removesFromBatch() {
        ServiceRepairStateMachine machine = new ServiceRepairStateMachine();
        driveToNeedsRepair(machine, 1_000L);
        machine.handle(ServiceRepairStateMachine.Event.packageRepairStarted(),
                snapshot(5_000L));
        assertEquals(ServiceRepairStateMachine.State.REPAIRING, machine.getState());

        ServiceRepairStateMachine.Snapshot disabled = snapshot(5_100L);
        disabled.userDisabled = true;
        ServiceRepairStateMachine.Transition t = machine.handle(
                ServiceRepairStateMachine.Event.userDisable(), disabled);

        assertEquals(ServiceRepairStateMachine.State.DISABLED, t.getTo());
        assertTrue(t.hasEffect(ServiceRepairStateMachine.ActionType.REMOVE_FROM_BATCH));
    }
}
