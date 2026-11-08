package com.accessibilitymanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogUtilTest {

    @Test
    public void shouldWriteDateHeader_skipsDuplicateHeaderWhenDailyFileAlreadyExists() {
        // 首次创建当天新文件：应写入日期分隔行
        assertTrue(LogUtil.shouldWriteDateHeader("2026-11-09", null, false));
        // 进程重启后 sCurrentDate 为 null，但当天日志文件已非空：不应重复写入日期分隔行
        assertFalse(LogUtil.shouldWriteDateHeader("2026-11-09", null, true));
        // 同进程内同一天后续写入：不应重复写入
        assertFalse(LogUtil.shouldWriteDateHeader("2026-11-09", "2026-11-09", true));
        // 跨天切换到新文件：应写入日期分隔行
        assertTrue(LogUtil.shouldWriteDateHeader("2026-11-10", "2026-11-09", false));
    }

    @Test
    public void formatForDisplay_stripsMillisAndRawMetaAndShortensComponents() {
        String rawLine = "01:17:04.128  [模块] [检测] 崩溃: "
                + "com.arlosoft.macrodroid/com.arlosoft.macrodroid.triggers.services.UIInteractionAccessibilityService"
                + " | hook=binderDied conn=@4f8a1b crashed=true iface=true up=142.3s";

        String display = LogUtil.formatForDisplay(rawLine);

        assertEquals(
                "01:17:04  [模块] [检测] 崩溃: com.arlosoft.macrodroid/.UIInteractionAccessibilityService",
                display);
    }

    @Test
    public void formatForDisplay_handlesMultipleColonSeparatedComponents() {
        String rawLine = "12:00:01.005  [模块] 下发锁定同步指令: "
                + "com.arlosoft.macrodroid/com.arlosoft.macrodroid.triggers.services.MacroDroidAccessibilityServiceJellyBean:"
                + "cn.litiaotiao.app/com.litiaotiao.app.LttService";

        String display = LogUtil.formatForDisplay(rawLine);

        assertEquals(
                "12:00:01  [模块] 下发锁定同步指令: "
                        + "com.arlosoft.macrodroid/.MacroDroidAccessibilityServiceJellyBean:"
                        + "cn.litiaotiao.app/.LttService",
                display);
    }

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

