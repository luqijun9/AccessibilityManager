package com.accessibilitymanager.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoRepairEntryTest {

    @Test
    public void bootGraceDeadline_withoutFirstConnect_usesBaseBootGrace() {
        assertEquals(RepairPolicy.BOOT_GRACE_MS,
                RepairPolicy.getBootGraceDeadlineMs(0L));
        assertEquals(20_000L,
                RepairPolicy.getRemainingBootGraceMs(70_000L, 0L));
        assertEquals(0L,
                RepairPolicy.getRemainingBootGraceMs(90_000L, 0L));
    }

    @Test
    public void bootGraceDeadline_withFirstConnectDuringBootWave_extendsGraceWindow() {
        // 模拟开机第 70s 用户解锁后首个无障碍服务连接，冷却期应延长至 70s + 30s = 100s
        long firstConnectAt = 70_000L;
        assertEquals(100_000L,
                RepairPolicy.getBootGraceDeadlineMs(firstConnectAt));
        assertEquals(25_000L,
                RepairPolicy.getRemainingBootGraceMs(75_000L, firstConnectAt));
        assertEquals(0L,
                RepairPolicy.getRemainingBootGraceMs(100_000L, firstConnectAt));
    }

    @Test
    public void bootGraceDeadline_withLateFirstConnect_doesNotTriggerBootGrace() {
        // 手机已开机超过 3 分钟后首次连接，不应再进入开机冷却期
        long firstConnectAt = 200_000L;
        assertEquals(RepairPolicy.BOOT_GRACE_MS,
                RepairPolicy.getBootGraceDeadlineMs(firstConnectAt));
        assertEquals(0L,
                RepairPolicy.getRemainingBootGraceMs(201_000L, firstConnectAt));
    }

    @Test
    public void shouldKillPackage_excludesSystemAndSelfPackages() {
        assertFalse(RepairPolicy.shouldKillPackage(
                "android", "android", true, "", "android/foo", null, 100_000L));
        // system_server 下 contextPkg 为 "android"，仍应排除管理器自身包名
        assertFalse(RepairPolicy.shouldKillPackage(
                "com.accessibilitymanager.pro", "android", true, "",
                "com.accessibilitymanager.pro/com.accessibilitymanager.MyAccessibilityService",
                null, 100_000L));
        assertFalse(RepairPolicy.shouldKillPackage(
                "com.accessibilitymanager", "android", true, "",
                "com.accessibilitymanager/.MyAccessibilityService",
                null, 100_000L));
    }

    @Test
    public void shouldKillPackage_preventsDuplicateKillForMultiServiceAppWithinCooldown() {
        String pkg = "com.arlosoft.macrodroid";
        String svcA = pkg + "/com.arlosoft.macrodroid.action.services.UIInteractionAccessibilityService";
        String svcB = pkg + "/com.arlosoft.macrodroid.triggers.services.MacroDroidAccessibilityServiceJellyBean";

        long firstKillAt = 100_000L;
        // 第一个服务修复时允许强杀
        assertTrue(RepairPolicy.shouldKillPackage(
                pkg, "android", true, "", svcA, null, firstKillAt));

        // 4 秒后同包的第二个服务修复时，处于 15 秒同包冷却期内，禁止二次强杀
        assertFalse(RepairPolicy.shouldKillPackage(
                pkg, "android", true, "", svcB, firstKillAt, firstKillAt + 4_000L));

        // 超过 15 秒同包冷却期后，允许再次强杀
        assertTrue(RepairPolicy.shouldKillPackage(
                pkg, "android", true, "", svcB,
                firstKillAt, firstKillAt + RepairPolicy.PACKAGE_KILL_COOLDOWN_MS));
    }

    @Test
    public void shouldKillPackage_respectsFixModeAndDisabledList() {
        String pkg = "com.arlosoft.macrodroid";
        String svcA = pkg + "/com.arlosoft.macrodroid.action.services.UIInteractionAccessibilityService";

        assertFalse(RepairPolicy.shouldKillPackage(
                pkg, "android", false, "", svcA, null, 100_000L));
        assertFalse(RepairPolicy.shouldKillPackage(
                pkg, "android", true, svcA, svcA, null, 100_000L));
    }

    @Test
    public void formatEventLine_appendsRawMetaAndUptime() {
        assertEquals("142.3s", RepairPolicy.formatUptimeSeconds(142_340L));
        assertEquals(
                "[检测] 崩溃: com.foo/.BarService | hook=binderDied conn=@1a2b crashed=true up=142.3s",
                RepairPolicy.formatEventLine(
                        "检测", "崩溃: com.foo/.BarService",
                        "hook=binderDied conn=@1a2b crashed=true", 142_340L));
        assertEquals(
                "[配置] 模块已启用 | up=5.0s",
                RepairPolicy.formatEventLine("配置", "模块已启用", null, 5_000L));
    }
}
