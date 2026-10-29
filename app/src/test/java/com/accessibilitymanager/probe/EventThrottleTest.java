package com.accessibilitymanager.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EventThrottleTest {

    private static final long WINDOW = 10 * 60 * 1000L;

    @Test
    public void firstEventAlwaysEmits() {
        EventThrottle throttle = new EventThrottle(WINDOW);
        // 开机不久 nowMs 可能小于窗口长度，首条仍应放行
        EventThrottle.Decision decision = throttle.onEvent("com.demo/service", 30_000L);
        assertTrue(decision.emit);
        assertEquals(0, decision.suppressed);
    }

    @Test
    public void eventsWithinWindowAreSuppressed() {
        EventThrottle throttle = new EventThrottle(WINDOW);
        throttle.onEvent("com.demo/service", 1_000L);

        EventThrottle.Decision second = throttle.onEvent("com.demo/service", 2_000L);
        assertFalse(second.emit);
        assertEquals(0, second.suppressed);

        EventThrottle.Decision third = throttle.onEvent("com.demo/service", 3_000L);
        assertFalse(third.emit);
        assertEquals(0, third.suppressed);
    }

    @Test
    public void emitsAgainAfterWindowWithSuppressedCount() {
        EventThrottle throttle = new EventThrottle(WINDOW);
        long start = 5_000L;
        assertTrue(throttle.onEvent("com.demo/service", start).emit);
        throttle.onEvent("com.demo/service", start + 100L);
        throttle.onEvent("com.demo/service", start + 200L);
        throttle.onEvent("com.demo/service", start + 300L);

        EventThrottle.Decision afterWindow =
                throttle.onEvent("com.demo/service", start + WINDOW);
        assertTrue(afterWindow.emit);
        assertEquals(3, afterWindow.suppressed);

        // 放行后计数清零，窗口内新的条目重新累计
        assertFalse(throttle.onEvent("com.demo/service", start + WINDOW + 1).emit);
        EventThrottle.Decision next =
                throttle.onEvent("com.demo/service", start + WINDOW * 2);
        assertTrue(next.emit);
        assertEquals(1, next.suppressed);
    }

    @Test
    public void differentKeysAreIndependent() {
        EventThrottle throttle = new EventThrottle(WINDOW);
        assertTrue(throttle.onEvent("com.a/service", 1_000L).emit);
        assertTrue(throttle.onEvent("com.b/service", 1_000L).emit);
        assertFalse(throttle.onEvent("com.a/service", 2_000L).emit);
        assertFalse(throttle.onEvent("com.b/service", 2_000L).emit);

        EventThrottle.Decision a = throttle.onEvent("com.a/service", 1_000L + WINDOW);
        assertEquals(1, a.suppressed);
        assertTrue(a.emit);
    }

    @Test
    public void timeRollbackDoesNotBlockEvents() {
        EventThrottle throttle = new EventThrottle(WINDOW);
        assertTrue(throttle.onEvent("com.demo/service", 10_000L).emit);
        // 时间回拨时按窗口外处理，避免事件被长期压制
        EventThrottle.Decision rollback = throttle.onEvent("com.demo/service", 9_000L);
        assertTrue(rollback.emit);
        assertEquals(0, rollback.suppressed);
        assertTrue(throttle.onEvent("com.demo/service", 8_000L).emit);
    }
}
