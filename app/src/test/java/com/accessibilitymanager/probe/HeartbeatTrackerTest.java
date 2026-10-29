package com.accessibilitymanager.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HeartbeatTrackerTest {

    @Test
    public void initialWriteSuccessStopsFurtherWritesForSameBootCount() {
        HeartbeatTracker tracker = new HeartbeatTracker(AutoRepairEntry.MAX_HEARTBEAT_ATTEMPTS);
        assertTrue(tracker.shouldWrite(12));
        tracker.onWriteSuccess(12);

        assertTrue(tracker.isWritten());
        assertEquals(12, tracker.getWrittenBootCount());
        assertEquals(0, tracker.getAttempts());
        assertFalse(tracker.shouldWrite(12));
    }

    @Test
    public void bootCountIncrementAfterEarlyBootTriggersRefresh() {
        HeartbeatTracker tracker = new HeartbeatTracker(AutoRepairEntry.MAX_HEARTBEAT_ATTEMPTS);
        // 开机极早期 systemReady 前写入了旧的 boot_count (11)
        assertTrue(tracker.shouldWrite(11));
        tracker.onWriteSuccess(11);
        assertFalse(tracker.shouldWrite(11));

        // 随后系统完成自增 boot_count -> 12，应允许重新写入最新值
        assertTrue(tracker.shouldWrite(12));
        assertFalse(tracker.isWritten());
        tracker.onWriteSuccess(12);
        assertTrue(tracker.isWritten());
        assertEquals(12, tracker.getWrittenBootCount());
        assertFalse(tracker.shouldWrite(12));
    }

    @Test
    public void consecutiveFailuresStopAtMaxAttemptsLimit() {
        int maxAttempts = 5;
        HeartbeatTracker tracker = new HeartbeatTracker(maxAttempts);

        for (int i = 1; i < maxAttempts; i++) {
            assertTrue(tracker.shouldWrite(10));
            assertTrue("attempt " + i + " should allow retry", tracker.onWriteFailure());
            assertEquals(i, tracker.getAttempts());
            assertFalse(tracker.isExhausted());
        }

        // 第 maxAttempts 次失败后达到上限，不再允许继续自动重试
        assertTrue(tracker.shouldWrite(10));
        assertFalse("reaching maxAttempts must stop retrying", tracker.onWriteFailure());
        assertEquals(maxAttempts, tracker.getAttempts());
        assertTrue(tracker.isExhausted());
        assertFalse(tracker.shouldWrite(10));
    }

    @Test
    public void successAfterPartialFailuresResetsRetryCounter() {
        HeartbeatTracker tracker = new HeartbeatTracker(4);
        assertTrue(tracker.shouldWrite(7));
        assertTrue(tracker.onWriteFailure());
        assertTrue(tracker.onWriteFailure());
        assertEquals(2, tracker.getAttempts());

        assertTrue(tracker.shouldWrite(7));
        tracker.onWriteSuccess(7);
        assertEquals(0, tracker.getAttempts());
        assertTrue(tracker.isWritten());
        assertFalse(tracker.isExhausted());
    }
}

