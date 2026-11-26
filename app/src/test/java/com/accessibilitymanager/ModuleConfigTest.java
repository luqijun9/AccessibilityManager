package com.accessibilitymanager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModuleConfigTest {

    @Test
    public void isLoadedValueValid_matchesCurrentBootCount() {
        assertTrue(ModuleConfig.isLoadedValueValid("15|1", 15));
        assertFalse(ModuleConfig.isLoadedValueValid("14|1", 15));
    }

    @Test
    public void isLoadedValueValid_fallsBackWhenRomHasNoBootCount() {
        assertTrue(ModuleConfig.isLoadedValueValid("-1|1", -1));
        assertTrue(ModuleConfig.isLoadedValueValid("10|1", -1));
    }

    @Test
    public void isLoadedValueValid_rejectsNullEmptyOrMalformed() {
        assertFalse(ModuleConfig.isLoadedValueValid(null, 10));
        assertFalse(ModuleConfig.isLoadedValueValid("", 10));
        assertFalse(ModuleConfig.isLoadedValueValid("invalid|1", 10));
    }

    @Test
    public void hasUnconsumedGapInSecure_detectsTrimmedHeadOrSessionChange() {
        assertFalse(ModuleConfig.hasUnconsumedGapInSecure(null, "s1", 10L));
        assertFalse(ModuleConfig.hasUnconsumedGapInSecure("", "s1", 10L));
        assertFalse(ModuleConfig.hasUnconsumedGapInSecure("s1|15|1000|msg", "", 0L));
        assertFalse(ModuleConfig.hasUnconsumedGapInSecure("s1|10|1000|msg\ns1|11|1001|msg2", "s1", 10L));
        assertFalse(ModuleConfig.hasUnconsumedGapInSecure("s1|11|1001|msg2", "s1", 10L));
        assertTrue(ModuleConfig.hasUnconsumedGapInSecure("s1|15|1000|msg", "s1", 10L));
        assertTrue(ModuleConfig.hasUnconsumedGapInSecure("s2|1|1000|msg", "s1", 10L));
    }

    @Test
    public void shouldDrainForSecureKey_onlyAllowsEventsOrParentUri() {
        assertTrue(ModuleConfig.shouldDrainForSecureKey(null));
        assertTrue(ModuleConfig.shouldDrainForSecureKey(""));
        assertTrue(ModuleConfig.shouldDrainForSecureKey("secure"));
        assertTrue(ModuleConfig.shouldDrainForSecureKey(ModuleConfig.KEY_EVENTS));

        assertFalse("cmd_ack 变更不得触发日志拉取，避免自激广播刷屏",
                ModuleConfig.shouldDrainForSecureKey(ModuleConfig.KEY_CMD_ACK));
        assertFalse(ModuleConfig.shouldDrainForSecureKey(ModuleConfig.KEY_SERVICES));
        assertFalse(ModuleConfig.shouldDrainForSecureKey("enabled_accessibility_services"));
    }

    @Test
    public void shouldPullLogsViaBinder_skipsUnnecessaryBroadcastOnObserverWhenNoGap() {
        // 观察器路径：KEY_EVENTS 可读且无缺口时，不需要再发 pull_logs 广播
        assertFalse(ModuleConfig.shouldPullLogsViaBinder(false, false, "s1|10|1000|msg"));
        // 观察器路径：出现 session/seq 缺口或读不到 KEY_EVENTS 时，必须走 Binder 分批补拉
        assertTrue(ModuleConfig.shouldPullLogsViaBinder(false, true, "s1|15|1000|msg"));
        assertTrue(ModuleConfig.shouldPullLogsViaBinder(false, false, null));
        // 显式调用 drainLogs（如打开界面/日志页）时允许 Binder 拉取全量尾段
        assertTrue(ModuleConfig.shouldPullLogsViaBinder(true, false, "s1|10|1000|msg"));
    }
}

