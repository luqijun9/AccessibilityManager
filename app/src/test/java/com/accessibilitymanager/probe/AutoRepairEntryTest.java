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
    public void classifyPackageState_mapsEnabledSettingToThreeStates() {
        // 0=DEFAULT / 1=ENABLED 都代表包可运行
        assertEquals(RepairPolicy.PKG_STATE_ENABLED, RepairPolicy.classifyPackageState(0));
        assertEquals(RepairPolicy.PKG_STATE_ENABLED, RepairPolicy.classifyPackageState(1));
        // 2=DISABLED，3=DISABLED_USER（冰箱/pm disable-user 冻结），4=DISABLED_UNTIL_USED
        assertEquals(RepairPolicy.PKG_STATE_DISABLED, RepairPolicy.classifyPackageState(2));
        assertEquals(RepairPolicy.PKG_STATE_DISABLED, RepairPolicy.classifyPackageState(3));
        assertEquals(RepairPolicy.PKG_STATE_DISABLED, RepairPolicy.classifyPackageState(4));
        // ROM 私有值：保守按可运行处理，避免误跳过正常服务
        assertEquals(RepairPolicy.PKG_STATE_ENABLED, RepairPolicy.classifyPackageState(99));
    }

    @Test
    public void shouldRecoverOnPackageChange_onlyWhenPackageIsUsable() {
        // 解冻（3 -> 0/1）归一化为 ENABLED，应触发补回
        assertTrue(RepairPolicy.shouldRecoverOnPackageChange(RepairPolicy.PKG_STATE_ENABLED));
        // 冻结事件、卸载事件不触发补回，避免冻结期间重新写入启用列表形成循环
        assertFalse(RepairPolicy.shouldRecoverOnPackageChange(RepairPolicy.PKG_STATE_DISABLED));
        assertFalse(RepairPolicy.shouldRecoverOnPackageChange(RepairPolicy.PKG_STATE_UNINSTALLED));
    }

    @Test
    public void unfrozenGuard_suppressesKillOnlyForNormalMode() {
        long unfrozenAt = 10_000L;
        // 普通模式保守期：0 ~ 60s 内禁止强杀；模块模式已删除 15s 静默期与解冻自动恢复
        assertTrue(RepairPolicy.isInUnfrozenGuard(unfrozenAt + 1L, unfrozenAt));
        assertTrue(RepairPolicy.isInUnfrozenGuard(
                unfrozenAt + RepairPolicy.UNFROZEN_GUARD_MS - 1L, unfrozenAt));
        assertFalse(RepairPolicy.isInUnfrozenGuard(
                unfrozenAt + RepairPolicy.UNFROZEN_GUARD_MS, unfrozenAt));
        // 时间未知/时钟回拨时不启用窗口，保持原行为
        assertFalse(RepairPolicy.isInUnfrozenGuard(unfrozenAt - 1L, unfrozenAt));
        assertFalse(RepairPolicy.isInUnfrozenGuard(unfrozenAt, 0L));
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
    public void selectRepairBatch_keepsHealthySiblingsOnlyWhenKillingProcess() {
        ComponentKey a = ComponentKey.parse("com.macro/.ServiceA");
        ComponentKey b = ComponentKey.parse("com.macro/.ServiceB");
        List<ComponentKey> candidates = new ArrayList<>();
        candidates.add(a);
        candidates.add(b);

        java.util.Set<ComponentKey> unbound = new java.util.LinkedHashSet<>();
        unbound.add(a);

        // 不强杀：只关开真正未绑定的 A，健康的 B 不参与，避免按键监听服务被无谓重绑
        List<ComponentKey> noKill = RepairPolicy.selectRepairBatch(candidates, false, unbound);
        assertEquals(1, noKill.size());
        assertEquals(a, noKill.get(0));

        // 强杀会杀死整包进程：A、B 都必须回加
        List<ComponentKey> kill = RepairPolicy.selectRepairBatch(candidates, true, unbound);
        assertEquals(2, kill.size());
        assertTrue(kill.contains(a));
        assertTrue(kill.contains(b));

        // 没有未绑定成员时，不强杀不应产生空批次之外的动作
        assertTrue(RepairPolicy.selectRepairBatch(
                candidates, false, Collections.<ComponentKey>emptySet()).isEmpty());
    }

    @Test
    public void decideConnectionDedupe_waitsForAmsMapToSwitch() {
        // AMS 仍指向旧连接：不能立即解绑旧连接，先等待
        assertEquals(RepairPolicy.ConnectionDedupeAction.WAIT,
                RepairPolicy.decideConnectionDedupe(true, true, true,
                        false, true, 0, 2));
        // 等待次数用尽后，AMS 仍指向旧连接：incoming 是多余绑定，解绑 incoming
        assertEquals(RepairPolicy.ConnectionDedupeAction.UNBIND_INCOMING,
                RepairPolicy.decideConnectionDedupe(true, true, true,
                        false, true, 2, 2));
        // incoming 自己没 bound：没有重复连接，放弃
        assertEquals(RepairPolicy.ConnectionDedupeAction.GIVE_UP,
                RepairPolicy.decideConnectionDedupe(true, false, true,
                        false, true, 2, 2));

        // AMS 已指向 incoming 且 incoming 仍 bound：可以解绑旧连接
        assertEquals(RepairPolicy.ConnectionDedupeAction.UNBIND_EXISTING,
                RepairPolicy.decideConnectionDedupe(true, true, true,
                        true, false, 0, 2));
        // AMS 已指向 incoming，但模块 map 已被更新连接替换：放弃，避免操作过期对象
        assertEquals(RepairPolicy.ConnectionDedupeAction.GIVE_UP,
                RepairPolicy.decideConnectionDedupe(true, true, false,
                        true, false, 0, 2));
        // 旧连接已经死亡：无需 dedupe
        assertEquals(RepairPolicy.ConnectionDedupeAction.GIVE_UP,
                RepairPolicy.decideConnectionDedupe(false, true, true,
                        true, false, 0, 2));
        // AMS map 完全读不到：先等，超时后放弃，绝不在未知状态下动手
        assertEquals(RepairPolicy.ConnectionDedupeAction.WAIT,
                RepairPolicy.decideConnectionDedupe(true, true, true,
                        false, false, 0, 2));
        assertEquals(RepairPolicy.ConnectionDedupeAction.GIVE_UP,
                RepairPolicy.decideConnectionDedupe(true, true, true,
                        false, false, 2, 2));
    }

    @Test
    public void nextFastFailCount_usesConnectionLifetime() {
        // 寿命未知（热重载/加载前已绑定）按健康处理，避免一上来就退避
        assertEquals(0, RepairPolicy.nextFastFailCount(3, -1L));
        // 健康连接的新故障清零快速失败计数
        assertEquals(0, RepairPolicy.nextFastFailCount(3,
                RepairPolicy.HEALTHY_CONNECTION_MS));
        // 5s~30s 之间保持原计数，不额外惩罚
        assertEquals(3, RepairPolicy.nextFastFailCount(3, 10_000L));
        // 短命连接累计计数，并封顶
        assertEquals(4, RepairPolicy.nextFastFailCount(3, 1_000L));
        assertEquals(RepairPolicy.MAX_FAST_FAIL_COUNT,
                RepairPolicy.nextFastFailCount(RepairPolicy.MAX_FAST_FAIL_COUNT, 1_000L));
        assertEquals(0, RepairPolicy.nextFastFailCount(-5, 40_000L));
    }

    @Test
    public void repairBackoffMs_escalatesAndCaps() {
        assertEquals(0L, RepairPolicy.repairBackoffMs(0));
        assertEquals(0L, RepairPolicy.repairBackoffMs(1));
        assertEquals(3_000L, RepairPolicy.repairBackoffMs(2));
        assertEquals(8_000L, RepairPolicy.repairBackoffMs(3));
        assertEquals(15_000L, RepairPolicy.repairBackoffMs(4));
        assertEquals(30_000L, RepairPolicy.repairBackoffMs(5));
        assertEquals(30_000L, RepairPolicy.repairBackoffMs(99));
        assertEquals(0L, RepairPolicy.repairBackoffMs(-1));

        assertTrue(RepairPolicy.shouldResetFastFail(RepairPolicy.HEALTHY_CONNECTION_MS));
        assertFalse(RepairPolicy.shouldResetFastFail(1_000L));
    }

    @Test
    public void shouldUseKillForFastFail_disablesKillAfterRepeatedShortFailures() {
        assertTrue(RepairPolicy.shouldUseKillForFastFail(0));
        assertTrue(RepairPolicy.shouldUseKillForFastFail(1));
        assertFalse(RepairPolicy.shouldUseKillForFastFail(2));

        String pkg = "com.arlosoft.macrodroid";
        String svc = pkg
                + "/com.arlosoft.macrodroid.action.services.UIInteractionAccessibilityService";
        // 即使 fixMode 开启且不在冷却期，快速失败 >= 2 也必须禁止强杀
        assertFalse(RepairPolicy.shouldKillPackage(
                pkg, "android", true, "", svc, null, 100_000L, 2));
        assertTrue(RepairPolicy.shouldKillPackage(
                pkg, "android", true, "", svc, null, 100_000L, 1));
    }

    @Test
    public void planPackageBatch_onlyIncludesManagedAndEnabledSamePackage() {
        String managed = "com.macro/.ServiceA:com.macro/.ServiceB:com.other/.ServiceC";
        String enabled = "com.macro/.ServiceA:com.other/.ServiceC";
        ComponentKey triggerB = ComponentKey.parse("com.macro/.ServiceB");

        // B 已不在启用列表（用户关闭/OEM 移除），批量修复只能包含 A，不能把 B 复活
        List<ComponentKey> onlyA = RepairPolicy.planPackageBatch(
                managed, enabled, "com.macro", triggerB);
        assertEquals(1, onlyA.size());
        assertEquals(ComponentKey.parse("com.macro/.ServiceA"), onlyA.get(0));

        // A/B 都在启用列表时，同包两个服务一起进入批次
        List<ComponentKey> both = RepairPolicy.planPackageBatch(
                managed,
                "com.macro/.ServiceA:com.macro/.ServiceB:com.other/.ServiceC",
                "com.macro", triggerB);
        assertEquals(2, both.size());
        assertTrue(both.contains(ComponentKey.parse("com.macro/.ServiceA")));
        assertTrue(both.contains(triggerB));

        // 脏数据/空包名不能导致异常或误加成员
        assertTrue(RepairPolicy.planPackageBatch("bad-token", "bad-token", "com.macro", null)
                .isEmpty());
        assertTrue(RepairPolicy.planPackageBatch(managed, enabled, null, triggerB).isEmpty());
    }

    private interface IAccessibilityServiceClient {
    }

    private static class FakeLegacyBaseConnection {
        Object mService;
        Object mServiceInterface;

        boolean isConnectedLocked() {
            return mService != null;
        }
    }

    private static final class FakeLegacyConnection extends FakeLegacyBaseConnection {
    }

    private static class FakeAndroid17BaseConnection {
        Object mClientBinder;
        Object mClient;

        boolean isConnectedLocked() {
            return mClientBinder != null;
        }
    }

    private static final class FakeAndroid17Connection extends FakeAndroid17BaseConnection {
    }

    private static final class FakeTypeFallbackConnection {
        IAccessibilityServiceClient mRenamedRemoteProxy;
    }

    private static final class FakeBinderOnlyConnection {
        Object mClientBinder;
    }

    private static final class FakeMethodOnlyConnection {
        private final boolean connected;

        FakeMethodOnlyConnection(boolean connected) {
            this.connected = connected;
        }

        boolean isConnectedLocked() {
            return connected;
        }
    }

    private static final class FakeHybridRomConnection {
        Object mServiceInterface;
        Object mClient;
    }

    private static final class FakeUnknownConnection {
        Object mOpaqueToken = new Object();
        int mConnectionId = 42;
    }

    @Test
    public void connectionBindProbe_supportsLegacyAndAndroid17AndTypeFallbackAndUnknown() {
        // 1. Android 10~16 连接类：mServiceInterface 非空为 BOUND，置空为 UNBOUND
        FakeLegacyConnection legacy = new FakeLegacyConnection();
        legacy.mServiceInterface = new Object();
        legacy.mService = new Object();
        ConnectionBindProbe.Result legacyBound = ConnectionBindProbe.probe(legacy);
        assertEquals(ConnectionBindProbe.Status.BOUND, legacyBound.getStatus());
        assertEquals("field:mServiceInterface", legacyBound.getStrategy());
        assertTrue(legacyBound.isAvailable());

        legacy.mServiceInterface = null;
        legacy.mService = null;
        ConnectionBindProbe.Result legacyUnbound = ConnectionBindProbe.probe(legacy);
        assertEquals(ConnectionBindProbe.Status.UNBOUND, legacyUnbound.getStatus());
        assertTrue(legacyUnbound.isAvailable());

        // 2. Android 17 连接类：无 mServiceInterface，改为 mClient / mClientBinder + isConnectedLocked()
        FakeAndroid17Connection a17 = new FakeAndroid17Connection();
        a17.mClient = new Object();
        a17.mClientBinder = new Object();
        ConnectionBindProbe.Result a17Bound = ConnectionBindProbe.probe(a17);
        assertEquals(ConnectionBindProbe.Status.BOUND, a17Bound.getStatus());
        assertEquals("field:mClient", a17Bound.getStrategy());
        assertTrue(a17Bound.getSchemaSummary().contains("mClient"));
        assertTrue(a17Bound.getSchemaSummary().contains("mClientBinder"));

        a17.mClient = null;
        a17.mClientBinder = null;
        assertEquals(ConnectionBindProbe.Status.UNBOUND, ConnectionBindProbe.probe(a17).getStatus());

        // 3. 类型兜底（字段名被未来版本改名，但类型仍为 IAccessibilityServiceClient）
        FakeTypeFallbackConnection typeFallback = new FakeTypeFallbackConnection();
        typeFallback.mRenamedRemoteProxy = new IAccessibilityServiceClient() {
        };
        ConnectionBindProbe.Result typeResult = ConnectionBindProbe.probe(typeFallback);
        assertEquals(ConnectionBindProbe.Status.BOUND, typeResult.getStatus());
        assertEquals("type:mRenamedRemoteProxy", typeResult.getStrategy());

        // 4. 仅保留 mClientBinder 或仅保留 isConnectedLocked()
        FakeBinderOnlyConnection binderOnly = new FakeBinderOnlyConnection();
        binderOnly.mClientBinder = new Object();
        ConnectionBindProbe.Result binderResult = ConnectionBindProbe.probe(binderOnly);
        assertEquals(ConnectionBindProbe.Status.BOUND, binderResult.getStatus());
        assertEquals("field:mClientBinder", binderResult.getStrategy());
        binderOnly.mClientBinder = null;
        assertEquals(ConnectionBindProbe.Status.UNBOUND,
                ConnectionBindProbe.probe(binderOnly).getStatus());

        ConnectionBindProbe.Result methodBound =
                ConnectionBindProbe.probe(new FakeMethodOnlyConnection(true));
        assertEquals(ConnectionBindProbe.Status.BOUND, methodBound.getStatus());
        assertEquals("method:isConnectedLocked", methodBound.getStrategy());
        assertEquals(ConnectionBindProbe.Status.UNBOUND,
                ConnectionBindProbe.probe(new FakeMethodOnlyConnection(false)).getStatus());

        // 5. 魔改 ROM 同时保留了为 null 的 mServiceInterface 与有效的 mClient
        FakeHybridRomConnection hybrid = new FakeHybridRomConnection();
        hybrid.mServiceInterface = null;
        hybrid.mClient = new Object();
        ConnectionBindProbe.Result hybridResult = ConnectionBindProbe.probe(hybrid);
        assertEquals(ConnectionBindProbe.Status.BOUND, hybridResult.getStatus());
        assertEquals("field:mClient", hybridResult.getStrategy());

        // 6. 完全无法识别的未来连接类：绝不能伪装成 UNBOUND，必须返回 UNKNOWN 暂停误修
        FakeUnknownConnection unknown = new FakeUnknownConnection();
        ConnectionBindProbe.Result unknownResult = ConnectionBindProbe.probe(unknown);
        assertEquals(ConnectionBindProbe.Status.UNKNOWN, unknownResult.getStatus());
        assertFalse(unknownResult.isBound());
        assertFalse(unknownResult.isAvailable());
        assertFalse(ConnectionBindProbe.isClassSupported(FakeUnknownConnection.class));
        assertTrue(ConnectionBindProbe.isClassSupported(FakeAndroid17Connection.class));
        assertEquals("none", unknownResult.getStrategy());
    }

    private static class FakeLockCheckedAndroid17BaseConnection {
        final Object mLock = new Object();
        Object mClient;
        Object mClientBinder;
        boolean observedLockHeld;

        boolean isConnectedLocked() {
            observedLockHeld = Thread.holdsLock(mLock);
            if (!observedLockHeld) {
                throw new IllegalStateException("mLock not held during isConnectedLocked()");
            }
            return mClientBinder != null;
        }
    }

    private static final class FakeLockCheckedAndroid17Connection
            extends FakeLockCheckedAndroid17BaseConnection {
    }

    @Test
    public void probeWithConnectionLock_acquiresMLockWhenProbingAndroid17Connection() {
        FakeLockCheckedAndroid17Connection conn = new FakeLockCheckedAndroid17Connection();
        // 未绑定路径会一路探测到 isConnectedLocked()，借此校验 mLock 确实全程处于持有状态
        ConnectionBindProbe.Result unbound = AutoRepairEntry.probeWithConnectionLock(conn);
        assertEquals(ConnectionBindProbe.Status.UNBOUND, unbound.getStatus());
        assertTrue("探测 Android 17 连接状态时必须持有 mLock", conn.observedLockHeld);

        // 模拟仅通过 mClientBinder / isConnectedLocked 判定为已连接（mClient 尚未赋值或已置空）
        conn.observedLockHeld = false;
        conn.mClientBinder = new Object();
        ConnectionBindProbe.Result boundByBinder = AutoRepairEntry.probeWithConnectionLock(conn);
        assertEquals(ConnectionBindProbe.Status.BOUND, boundByBinder.getStatus());
        assertEquals("field:mClientBinder", boundByBinder.getStrategy());

        conn.mClient = new Object();
        ConnectionBindProbe.Result boundByClient = AutoRepairEntry.probeWithConnectionLock(conn);
        assertEquals(ConnectionBindProbe.Status.BOUND, boundByClient.getStatus());
        assertEquals("field:mClient", boundByClient.getStrategy());

        // 空连接与无 mLock 连接也应安全降级，不抛异常
        assertEquals(ConnectionBindProbe.Status.UNBOUND,
                AutoRepairEntry.probeWithConnectionLock(null).getStatus());
        FakeAndroid17Connection noLockConn = new FakeAndroid17Connection();
        noLockConn.mClient = new Object();
        assertEquals(ConnectionBindProbe.Status.BOUND,
                AutoRepairEntry.probeWithConnectionLock(noLockConn).getStatus());
    }
}
