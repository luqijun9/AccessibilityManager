package com.accessibilitymanager.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public class AutoRepairEntryTest {

    @Before
    public void setUp() {
        AutoRepairEntry.resetForTesting();
    }

    @After
    public void tearDown() {
        AutoRepairEntry.resetForTesting();
    }

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

    @Test
    public void hotReload_preservesConnectionsAndStateAcrossGenerations() {
        AutoRepairEntry oldGen = new AutoRepairEntry();
        Object fakeConnA = new Object();
        Object fakeConnB = new Object();
        String svcA = "com.foo/.ServiceA";
        String svcB = "com.bar/.ServiceB";

        AutoRepairEntry.putConnectionForTesting(svcA, fakeConnA);
        AutoRepairEntry.putConnectionForTesting(svcB, fakeConnB);
        AutoRepairEntry.setFirstServiceConnectAtForTesting(68_500L);
        AutoRepairEntry.putRetryStateForTesting(svcA, 1, 300_000L, 120_000L, "com.foo");

        final Object[] savedStateBox = new Object[1];
        XposedModuleInterface.HotReloadingParam reloadingParam =
                new XposedModuleInterface.HotReloadingParam() {
                    @Override
                    public Bundle getExtras() {
                        return null;
                    }

                    @Override
                    public void setSavedInstanceState(Object outState) {
                        savedStateBox[0] = outState;
                    }
                };

        assertTrue(oldGen.onHotReloading(reloadingParam));

        // 验证旧代内存已被清理，且保存状态为跨 ClassLoader 安全的标准 Map
        assertTrue("onHotReloading 后旧代连接映射应已清空",
                AutoRepairEntry.getConnectionsForTesting().isEmpty());
        assertEquals(0, AutoRepairEntry.getAttemptsForTesting(svcA));
        assertNotNull("savedInstanceState 不应为空", savedStateBox[0]);
        assertTrue("savedInstanceState 应为标准 Map", savedStateBox[0] instanceof Map);

        // 模拟新代实例通过 onHotReloaded 接管
        AutoRepairEntry newGen = new AutoRepairEntry();
        XposedModuleInterface.HotReloadedParam reloadedParam =
                new XposedModuleInterface.HotReloadedParam() {
                    @Override
                    public boolean isSystemServer() {
                        return true;
                    }

                    @Override
                    public String getProcessName() {
                        return "system_server";
                    }

                    @Override
                    public Bundle getExtras() {
                        return null;
                    }

                    @Override
                    public Object getSavedInstanceState() {
                        return savedStateBox[0];
                    }

                    @Override
                    public List<XposedInterface.HookHandle> getOldHookHandles() {
                        return Collections.emptyList();
                    }
                };

        newGen.onHotReloaded(reloadedParam);

        Map<String, Object> restoredConns = AutoRepairEntry.getConnectionsForTesting();
        assertEquals(2, restoredConns.size());
        assertSame(fakeConnA, restoredConns.get(svcA));
        assertSame(fakeConnB, restoredConns.get(svcB));
        assertEquals(68_500L, AutoRepairEntry.getFirstServiceConnectAtForTesting());
        assertEquals(1, AutoRepairEntry.getAttemptsForTesting(svcA));
        assertEquals(Long.valueOf(300_000L), AutoRepairEntry.getBlockedUntilForTesting(svcA));
        assertEquals(Long.valueOf(120_000L), AutoRepairEntry.getLastPackageKillAtForTesting("com.foo"));
        assertEquals(1, AutoRepairEntry.getHotReloadCountForTesting());
    }

    @Test
    public void hotReload_skipsNonSystemProcess() {
        AutoRepairEntry entry = new AutoRepairEntry();
        entry.onModuleLoaded(new XposedModuleInterface.ModuleLoadedParam() {
            @Override
            public boolean isSystemServer() {
                return false;
            }

            @Override
            public String getProcessName() {
                return "com.example.userapp";
            }
        });

        boolean accepted = entry.onHotReloading(new XposedModuleInterface.HotReloadingParam() {
            @Override
            public Bundle getExtras() {
                return null;
            }

            @Override
            public void setSavedInstanceState(Object outState) {
            }
        });

        assertFalse("非 system_server 进程应拒绝热重载", accepted);
    }

    @Test
    public void bootGrace_softRebootWithLargeKernelUptime_stillEnforcesGraceWindow() {
        // 模拟手机已开机 6585s 后执行软重启（system_server 重启但内核 elapsedRealtime 未清零）
        long moduleStartAt = 6_585_000L;
        // 软重启后 30s（6615s），无障碍尚未连接，仍处于 90s 冷却期内（剩余 60s）
        assertEquals(60_000L,
                RepairPolicy.getRemainingBootGraceMs(6_615_000L, moduleStartAt, 0L));
        // 软重启后 70s（6655s）首个服务连接，冷却期应延展至软重启后 100s（6685s）
        long firstConnectAt = 6_655_000L;
        assertEquals(20_000L,
                RepairPolicy.getRemainingBootGraceMs(6_665_000L, moduleStartAt, firstConnectAt));
        assertEquals(0L,
                RepairPolicy.getRemainingBootGraceMs(6_685_000L, moduleStartAt, firstConnectAt));
    }

    @Test
    public void circuitBreakerAndRetryReset_calculatesExpirationAndWakeupDelays() {
        long blockedUntil = 600_000L;
        assertFalse(RepairPolicy.isBlockExpired(blockedUntil, 500_000L));
        assertEquals(100_500L, RepairPolicy.getRemainingBlockDelayMs(blockedUntil, 500_000L));
        assertTrue(RepairPolicy.isBlockExpired(blockedUntil, 600_000L));
        assertEquals(0L, RepairPolicy.getRemainingBlockDelayMs(blockedUntil, 600_000L));

        long resetAfterMs = 5 * 60 * 1000L; // 300_000ms
        long lastAttemptAt = 100_000L;
        assertFalse(RepairPolicy.shouldResetAttempts(lastAttemptAt, 350_000L, resetAfterMs));
        assertEquals(50_500L,
                RepairPolicy.getRemainingResetDelayMs(lastAttemptAt, 350_000L, resetAfterMs));
        assertTrue(RepairPolicy.shouldResetAttempts(lastAttemptAt, 400_000L, resetAfterMs));
        assertEquals(500L,
                RepairPolicy.getRemainingResetDelayMs(lastAttemptAt, 400_000L, resetAfterMs));
    }
}
