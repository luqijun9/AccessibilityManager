package com.accessibilitymanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotificationMonitorServiceTest {

    @Test
    public void shouldTriggerCrashCheck_requiresNonLsposedAndCrashFixAndFeatureEnabled() {
        String watched = "com.litiaotiao.app:li.songe.gkd";

        // 正常满足条件：非 LSPosed 模式 + 崩溃检测开启 + 监听通知开启 + 包名命中
        assertTrue(NotificationMonitorService.shouldTriggerCrashCheck(
                false, true, true, watched, "li.songe.gkd"));

        // 开启了 LSPosed 模式时不触发
        assertFalse(NotificationMonitorService.shouldTriggerCrashCheck(
                true, true, true, watched, "li.songe.gkd"));

        // 崩溃检测总开关关闭时不触发
        assertFalse(NotificationMonitorService.shouldTriggerCrashCheck(
                false, false, true, watched, "li.songe.gkd"));

        // 监听通知检测开关关闭时不触发
        assertFalse(NotificationMonitorService.shouldTriggerCrashCheck(
                false, true, false, watched, "li.songe.gkd"));

        // 包名不在所选列表中时不触发
        assertFalse(NotificationMonitorService.shouldTriggerCrashCheck(
                false, true, true, watched, "com.tencent.mm"));

        // 默认不勾选任何应用（空字符串）时不触发
        assertFalse(NotificationMonitorService.shouldTriggerCrashCheck(
                false, true, true, "", "li.songe.gkd"));
    }

    @Test
    public void isPackageWatched_matchesExactPackageNameOnly() {
        String watched = "com.example.a:com.example.b";
        assertTrue(NotificationMonitorService.isPackageWatched(watched, "com.example.a"));
        assertTrue(NotificationMonitorService.isPackageWatched(watched, "com.example.b"));
        assertFalse(NotificationMonitorService.isPackageWatched(watched, "com.example"));
        assertFalse(NotificationMonitorService.isPackageWatched("", "com.example.a"));
        assertFalse(NotificationMonitorService.isPackageWatched(null, "com.example.a"));
    }

    @Test
    public void countWatchedPackages_returnsAccurateCount() {
        assertEquals(0, NotificationMonitorService.countWatchedPackages(null));
        assertEquals(0, NotificationMonitorService.countWatchedPackages(""));
        assertEquals(1, NotificationMonitorService.countWatchedPackages("li.songe.gkd"));
        assertEquals(2, NotificationMonitorService.countWatchedPackages("li.songe.gkd:com.litiaotiao.app"));
    }

    @Test
    public void isComponentInEnabledListeners_supportsFullAndShorthandComponentNames() {
        String pkg = "com.accessibilitymanager";
        String cls = "com.accessibilitymanager.NotificationMonitorService";

        assertTrue(NotificationMonitorService.isComponentInEnabledListeners(
                "com.other/.Listener:com.accessibilitymanager/com.accessibilitymanager.NotificationMonitorService",
                pkg, cls));

        assertTrue(NotificationMonitorService.isComponentInEnabledListeners(
                "com.accessibilitymanager/.NotificationMonitorService:com.other/.Listener",
                pkg, cls));

        assertFalse(NotificationMonitorService.isComponentInEnabledListeners(
                "com.other/.Listener", pkg, cls));
        assertFalse(NotificationMonitorService.isComponentInEnabledListeners(
                "", pkg, cls));
        assertFalse(NotificationMonitorService.isComponentInEnabledListeners(
                null, pkg, cls));
    }
}

