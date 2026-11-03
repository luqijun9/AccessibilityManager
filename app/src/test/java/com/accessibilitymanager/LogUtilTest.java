package com.accessibilitymanager;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogUtilTest {

    @Test
    public void formatCrashLog_withMessageAndStackTrace() {
        Thread thread = new Thread("test-worker");
        Throwable exception = new RuntimeException("Simulated failure", new IllegalStateException("Root cause"));

        String formatted = LogUtil.formatCrashLog(thread, exception);

        assertNotNull(formatted);
        assertTrue(formatted.contains("[异常崩溃] 线程: test-worker"));
        assertTrue(formatted.contains("RuntimeException: Simulated failure"));
        assertTrue(formatted.contains("Caused by: java.lang.IllegalStateException: Root cause"));
        assertTrue(formatted.contains("at com.accessibilitymanager.LogUtilTest"));
    }

    @Test
    public void formatCrashLog_withNullMessageStillPreservesClassName() {
        Thread thread = new Thread("main");
        Throwable npe = new NullPointerException();

        String formatted = LogUtil.formatCrashLog(thread, npe);

        assertNotNull(formatted);
        assertTrue(formatted.contains("[异常崩溃] 线程: main"));
        assertTrue(formatted.contains("NullPointerException"));
        assertTrue(formatted.contains("at com.accessibilitymanager.LogUtilTest"));
    }

    @Test
    public void formatCrashLog_withNullsHandledSafely() {
        String formatted = LogUtil.formatCrashLog(null, null);

        assertNotNull(formatted);
        assertTrue(formatted.contains("[异常崩溃] 线程: unknown, 错误: Throwable"));
    }
}

