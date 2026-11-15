package com.accessibilitymanager.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
    public void calculateSecondaryCrashDelay_schedulesCheckAfterSuppressionWindow() {
        // 场景 1：在抑制期还剩 2500ms 时发生二次断开，应延迟 2500 + 500 = 3000ms 执行补偿检查
        long now = 10_000L;
        long suppressUntil = 12_500L;
        assertEquals(3000L, RepairPolicy.calculateSecondaryCrashDelay(now, suppressUntil));

        // 场景 2：在抑制期即将结束时（还剩 200ms）发生二次断开，保底延迟至少 1000ms
        now = 12_300L;
        assertEquals(1000L, RepairPolicy.calculateSecondaryCrashDelay(now, suppressUntil));

        // 场景 3：抑制期已过，延迟为 0
        now = 13_000L;
        assertEquals(0L, RepairPolicy.calculateSecondaryCrashDelay(now, suppressUntil));
    }

    @Test
    public void isWellKnownSystemPackage_identifiesOemAndAndroidPackages() {
        assertTrue(RepairPolicy.isWellKnownSystemPackage("android"));
        assertTrue(RepairPolicy.isWellKnownSystemPackage("com.android.settings"));
        assertTrue(RepairPolicy.isWellKnownSystemPackage("com.coloros.codebook"));
        assertTrue(RepairPolicy.isWellKnownSystemPackage("com.oplus.autofill"));
        assertTrue(RepairPolicy.isWellKnownSystemPackage("com.miui.securitycenter"));
        assertTrue(RepairPolicy.isWellKnownSystemPackage("com.huawei.systemmanager"));
        assertTrue(RepairPolicy.isWellKnownSystemPackage("com.samsung.accessibility"));

        assertFalse(RepairPolicy.isWellKnownSystemPackage("com.arlosoft.macrodroid"));
        assertFalse(RepairPolicy.isWellKnownSystemPackage("li.songe.gkd"));
        assertFalse(RepairPolicy.isWellKnownSystemPackage("com.moting.linkgo"));
        assertFalse(RepairPolicy.isWellKnownSystemPackage("com.ksxkq.loaftoday"));
    }

    @Test
    public void shouldIgnoreApp_respectsToggleAndChecker() {
        // 开启忽略系统应用：系统应用被忽略，第三方应用不被忽略
        assertTrue(RepairPolicy.shouldIgnoreApp(true, "com.coloros.codebook", null));
        assertFalse(RepairPolicy.shouldIgnoreApp(true, "com.arlosoft.macrodroid", null));

        // 支持自定义系统包检查器识别特殊厂商系统应用
        RepairPolicy.PackageSystemChecker customChecker = pkg -> "com.custom.vendor.service".equals(pkg);
        assertTrue(RepairPolicy.shouldIgnoreApp(true, "com.custom.vendor.service", customChecker));
        assertFalse(RepairPolicy.shouldIgnoreApp(true, "com.other.userapp", customChecker));

        // 关闭忽略系统应用：无论系统还是第三方均不忽略
        assertFalse(RepairPolicy.shouldIgnoreApp(false, "com.coloros.codebook", null));
        assertFalse(RepairPolicy.shouldIgnoreApp(false, "com.custom.vendor.service", customChecker));
    }

    @Test
    public void filterOutSystemComponents_filtersSystemAppsFromDiffString() {
        String mixed = "com.coloros.codebook/com.oplus.autofill.service.AutofillAccessibilityServiceText,"
                + "com.arlosoft.macrodroid/com.arlosoft.macrodroid.action.services.UIInteractionAccessibilityService,"
                + "com.android.settings/com.android.settings.accessibility.MyService";

        // 开启忽略系统应用时，过滤掉 coloros 和 android 组件，仅保留 macrodroid
        assertEquals(
                "com.arlosoft.macrodroid/com.arlosoft.macrodroid.action.services.UIInteractionAccessibilityService",
                RepairPolicy.filterOutSystemComponents(mixed, true, null));

        // 纯系统应用列表被全部过滤为空字符串
        String onlySystem = "com.coloros.codebook/com.oplus.autofill.service.AutofillAccessibilityServiceText,"
                + "com.oplus.test/com.oplus.test.Service";
        assertEquals("", RepairPolicy.filterOutSystemComponents(onlySystem, true, null));

        // 关闭忽略系统应用时，原样保留
        assertEquals(mixed, RepairPolicy.filterOutSystemComponents(mixed, false, null));
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
        AutoRepairEntry.putLastPackageKillAtForTesting("com.foo", 120_000L);

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
    public void buildTailWithinCharLimit_trimsOldestLinesAndRespectsLimit() {
        List<String> lines = new ArrayList<>();
        StringBuilder padding = new StringBuilder();
        for (int p = 0; p < 180; p++) {
            padding.append('x');
        }
        for (int i = 1; i <= 300; i++) {
            lines.add("sess|" + i + "|" + (1000L + i) + "|msg_" + i + "_" + padding);
        }

        String tail = AutoRepairEntry.buildTailWithinCharLimit(lines, AutoRepairEntry.SECURE_MAX_CHARS);
        assertTrue(tail.length() <= AutoRepairEntry.SECURE_MAX_CHARS);
        assertFalse(tail.contains("sess|1|1001|"));
        assertTrue(tail.contains("sess|300|1300|"));
        assertEquals("abcd", AutoRepairEntry.truncateToCharLimit("abcdef", 4));
    }

    @Test
    public void ackAndPullBatches_filtersConsumedAndSplitsBatches() {
        Deque<String> queue = new ArrayDeque<>();
        for (int i = 1; i <= 25; i++) {
            queue.addLast("s1|" + i + "|" + (1000L + i) + "|msg_" + i);
        }

        List<ArrayList<String>> batches = AutoRepairEntry.buildPullBatchesLocked(
                queue, "s1", "s1", 10L, 6, 1000);
        assertEquals(3, batches.size());
        assertEquals(6, batches.get(0).size());
        assertEquals(6, batches.get(1).size());
        assertEquals(3, batches.get(2).size());
        assertTrue(batches.get(0).get(0).startsWith("s1|11|"));
        assertTrue(batches.get(2).get(2).startsWith("s1|25|"));

        assertEquals(0, AutoRepairEntry.ackEventsUpToLocked(queue, "s1", "other", 25L));
        int removed = AutoRepairEntry.ackEventsUpToLocked(queue, "s1", "s1", 25L);
        assertEquals(24, removed);
        assertEquals(1, queue.size());
        assertTrue(queue.peekFirst().startsWith("s1|25|"));
    }

    @Test
    public void hotReload_preservesSessionAndNormalizesMixedSessionLines() {
        AutoRepairEntry oldGen = new AutoRepairEntry();
        AutoRepairEntry.setEventSessionForTesting("sessNew");
        AutoRepairEntry.putEventLineForTesting("oldA|1|1000|a", 1L);
        AutoRepairEntry.putEventLineForTesting("oldB|2|2000|b", 2L);

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

        AutoRepairEntry newGen = new AutoRepairEntry();
        newGen.onHotReloaded(new XposedModuleInterface.HotReloadedParam() {
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
        });

        assertEquals("sessNew", AutoRepairEntry.getEventSessionForTesting());
        List<String> restored = AutoRepairEntry.getEventLinesForTesting();
        assertTrue(restored.size() >= 2);
        assertEquals("sessNew|1|1000|a", restored.get(0));
        assertEquals("sessNew|2|2000|b", restored.get(1));
        for (String line : restored) {
            assertTrue("所有日志行应统一为同一个 session 前缀: " + line, line.startsWith("sessNew|"));
        }
    }

    @Test
    public void checkRetries_trackedAndClearedOnReset() {
        String key = "com.arlosoft.macrodroid/.action.services.UIInteractionAccessibilityService";
        AutoRepairEntry.putCheckRetryForTesting(key, 2);
        assertEquals(2, AutoRepairEntry.getCheckRetryForTesting(key));

        AutoRepairEntry.resetForTesting();
        assertEquals(0, AutoRepairEntry.getCheckRetryForTesting(key));
    }

    @Test
    public void isPackageProcessRunning_withNullContext_returnsFalse() {
        assertFalse(AutoRepairEntry.isPackageProcessRunning("com.arlosoft.macrodroid"));
        assertFalse(AutoRepairEntry.isPackageProcessRunning(null));
        assertFalse(AutoRepairEntry.isPackageProcessRunning(""));
    }
}
