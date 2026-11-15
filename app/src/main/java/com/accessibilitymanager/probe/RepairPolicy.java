package com.accessibilitymanager.probe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 无障碍自动修复的策略计算辅助类（无 Android / Xposed 框架依赖，便于单元测试验证）：
 * 1. 开机与首次连接启动潮冷却期计算；
 * 2. 强杀进程时的系统/自身包名排除与同包冷却防连环双杀判定；
 * 3. 重构后的状态机时序、连接寿命与快速失败退避策略。
 */
final class RepairPolicy {

    static final long BOOT_GRACE_MS = 90_000L;
    static final long BOOT_FIRST_CONNECT_WINDOW_MS = 180_000L;
    static final long BOOT_WAVE_GRACE_MS = 30_000L;
    static final long PACKAGE_KILL_COOLDOWN_MS = 10_000L;

    // ── 服务状态机时序 ────────────────────────────────────────────────
    /** 观察到断开后的 settle 等待：给 AMS 自动重绑/OEM 瞬态恢复留出时间 */
    static final long FSM_SETTLE_MS = 3_000L;
    /** 应用进程已存活但服务未绑定时的启动宽限，每次 2 秒 */
    static final long FSM_GRACE_MS = 2_000L;
    /** 关开写入完成后的首次绑定校验延迟 */
    static final long FSM_VERIFY_MS = 2_000L;
    /** 修复成功后的稳定性复查延迟，覆盖 OEM 数秒内二次查杀与事件丢失 */
    static final long FSM_STABILITY_MS = 5_000L;
    /** 绑定状态不可读时的重试间隔 */
    static final long FSM_BIND_RETRY_MS = 5_000L;
    /** 启动宽限最多 2 次，避免应用进程长期存活但服务永远不绑定时无限等待 */
    static final int FSM_MAX_GRACE_ATTEMPTS = 2;
    /** 绑定状态不可读时最多重试 3 次，之后等服务/配置事件重新触发，避免定时器空转 */
    static final int FSM_MAX_BIND_RETRY_ATTEMPTS = 3;

    // ── 连接寿命与快速失败退避 ────────────────────────────────────────
    /** 连接存活超过 30 秒视为健康服务：下一次崩溃按“新故障”处理并清空快速失败计数 */
    static final long HEALTHY_CONNECTION_MS = 30_000L;
    /** 连接存活不足 5 秒视为快速失败：累计计数并逐步拉长下一次修复间隔 */
    static final long SHORT_LIVED_CONNECTION_MS = 5_000L;
    static final int MAX_FAST_FAIL_COUNT = 6;
    /** 快速失败达到 2 次后，后续修复只做关开、不再强杀，避免 OEM 杀循环被强杀放大 */
    static final int FAST_FAIL_KILL_DISABLE_THRESHOLD = 2;
    /**
     * 快速失败退避表（下标为计数）：
     * 0/1 次不额外等待；2 次 3s；3 次 8s；4 次 15s；5 次及以上 30s 封顶。
     * 这里不是硬性重试上限，仍然无限重试，只是把修复节奏拉开。
     */
    private static final long[] REPAIR_BACKOFF_MS = {
            0L, 0L, 3_000L, 8_000L, 15_000L, 30_000L, 30_000L
    };

    private RepairPolicy() {
    }

    /**
     * 计算开机冷却期截止时间（elapsedRealtime 毫秒）：
     * 基础冷却 BOOT_GRACE_MS（90s）；若在开机 180s 内首次收到无障碍服务连接（用户解锁后系统开始批量绑定），
     * 则从首个服务连接起至少给予 BOOT_WAVE_GRACE_MS（30s）的缓冲期，等待系统将队列中的慢启动服务自然绑定完毕。
     */
    static long getBootGraceDeadlineMs(long firstConnectAt) {
        if (firstConnectAt > 0L && firstConnectAt <= BOOT_FIRST_CONNECT_WINDOW_MS) {
            return Math.max(BOOT_GRACE_MS, firstConnectAt + BOOT_WAVE_GRACE_MS);
        }
        return BOOT_GRACE_MS;
    }

    static long getRemainingBootGraceMs(long now, long firstConnectAt) {
        long deadline = getBootGraceDeadlineMs(firstConnectAt);
        return now < deadline ? (deadline - now) : 0L;
    }

    /**
     * 基于模块在 system_server 中的启动时刻（moduleStartAt）计算剩余开机/软重启冷却时间，
     * 解决软重启不重置内核 elapsedRealtime 导致开机冷却期失效的问题。
     */
    static long getRemainingBootGraceMs(long now, long moduleStartAt, long firstConnectAt) {
        long relativeNow = moduleStartAt > 0L && now >= moduleStartAt
                ? (now - moduleStartAt)
                : now;
        long relativeFirstConnect = 0L;
        if (firstConnectAt > 0L) {
            relativeFirstConnect = moduleStartAt > 0L && firstConnectAt >= moduleStartAt
                    ? (firstConnectAt - moduleStartAt)
                    : firstConnectAt;
        }
        return getRemainingBootGraceMs(relativeNow, relativeFirstConnect);
    }

    public interface PackageSystemChecker {
        boolean isSystemPackage(String pkg);
    }

    public static boolean isWellKnownSystemPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return true;
        }
        return pkg.equals("android")
                || pkg.startsWith("android.")
                || pkg.startsWith("com.android.")
                || pkg.startsWith("com.coloros.")
                || pkg.startsWith("com.oplus.")
                || pkg.startsWith("com.miui.")
                || pkg.startsWith("com.xiaomi.")
                || pkg.startsWith("com.huawei.")
                || pkg.startsWith("com.honor.")
                || pkg.startsWith("com.samsung.")
                || pkg.startsWith("com.vivo.");
    }

    public static boolean shouldIgnoreApp(boolean ignoreSystem, String pkg,
            PackageSystemChecker checker) {
        if (!ignoreSystem) {
            return false;
        }
        if (isWellKnownSystemPackage(pkg)) {
            return true;
        }
        return checker != null && checker.isSystemPackage(pkg);
    }

    public static String filterOutSystemComponents(String componentsStr,
            boolean ignoreSystem, PackageSystemChecker checker) {
        if (!ignoreSystem || componentsStr == null || componentsStr.isEmpty()) {
            return componentsStr == null ? "" : componentsStr;
        }
        StringBuilder sb = new StringBuilder();
        for (String item : componentsStr.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int slash = trimmed.indexOf('/');
            String pkg = slash > 0 ? trimmed.substring(0, slash) : trimmed;
            if (isWellKnownSystemPackage(pkg) || (checker != null && checker.isSystemPackage(pkg))) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    static boolean isSelfOrSystemPackage(String pkg, String contextPkg) {
        if (pkg == null || pkg.isEmpty()) {
            return true;
        }
        if ("android".equals(pkg) || pkg.equals(contextPkg) || isWellKnownSystemPackage(pkg)) {
            return true;
        }
        return pkg.equals("com.accessibilitymanager")
                || pkg.startsWith("com.accessibilitymanager.");
    }

    static boolean shouldKillPackage(String pkg, String contextPkg, boolean fixMode,
            String disabledList, String componentKey, Long lastPkgKillAt, long now) {
        if (!fixMode) {
            return false;
        }
        if (disabledList != null && disabledList.contains(componentKey)) {
            return false;
        }
        if (isSelfOrSystemPackage(pkg, contextPkg)) {
            return false;
        }
        if (lastPkgKillAt != null && now - lastPkgKillAt >= 0
                && now - lastPkgKillAt < PACKAGE_KILL_COOLDOWN_MS) {
            return false;
        }
        return true;
    }

    /**
     * 计算下一次快速失败计数。
     *
     * <p>场景与边界：
     * <ul>
     *   <li>连接寿命未知（模块热重载/加载前已绑定）：按健康处理，避免一上来就退避；</li>
     *   <li>寿命 >= 30s：健康服务的新故障，清零快速失败计数；</li>
     *   <li>寿命 < 5s：OEM 二次查杀/崩溃循环特征，累计计数；</li>
     *   <li>5s ~ 30s：既不算健康新故障，也不追加快速失败，保持原计数。</li>
     * </ul>
     */
    static int nextFastFailCount(int current, long connectionLifetimeMs) {
        int safeCurrent = Math.max(0, Math.min(current, MAX_FAST_FAIL_COUNT));
        if (connectionLifetimeMs < 0L || connectionLifetimeMs >= HEALTHY_CONNECTION_MS) {
            return 0;
        }
        if (connectionLifetimeMs < SHORT_LIVED_CONNECTION_MS) {
            return Math.min(MAX_FAST_FAIL_COUNT, safeCurrent + 1);
        }
        return safeCurrent;
    }

    /** 连接存活达到健康阈值时，状态机可在 stability 复查通过后清空快速失败计数。 */
    static boolean shouldResetFastFail(long connectionLifetimeMs) {
        return connectionLifetimeMs >= HEALTHY_CONNECTION_MS;
    }

    /** 按快速失败计数返回下一次修复前的额外退避时间；0 表示 settle/verify 后立即修复。 */
    static long repairBackoffMs(int fastFailCount) {
        int index = Math.max(0, Math.min(fastFailCount, REPAIR_BACKOFF_MS.length - 1));
        return REPAIR_BACKOFF_MS[index];
    }

    /**
     * 快速失败达到阈值后禁止强杀：强杀会再次杀死整包进程，
     * 在 OEM 杀循环中容易把同包其他已恢复服务一起带死，形成 A/B 乒乓。
     */
    static boolean shouldUseKillForFastFail(int fastFailCount) {
        return fastFailCount < FAST_FAIL_KILL_DISABLE_THRESHOLD;
    }

    /** 组合“快速失败 + 原有强杀条件”的完整判定，供包级协调器使用。 */
    static boolean shouldKillPackage(String pkg, String contextPkg, boolean fixMode,
            String disabledList, String componentKey, Long lastPkgKillAt, long now,
            int fastFailCount) {
        return shouldUseKillForFastFail(fastFailCount)
                && shouldKillPackage(pkg, contextPkg, fixMode, disabledList,
                componentKey, lastPkgKillAt, now);
    }

    /**
     * 规划同包批处理成员：
     * 只包含“仍在锁定列表且仍在当前启用列表”的同包组件，另加显式触发组件。
     *
     * <p>为什么以当前启用列表为准：
     * 用户可能在崩溃前已经关闭了同包另一个服务，不能因为修复触发组件就把它一起复活；
     * 而 ColorOS force-stop 把整包从启用列表移除时，由 reconcile/restore 路径负责补回，
     * 本方法只负责“崩溃修复”场景的批成员规划。
     */
    static List<ComponentKey> planPackageBatch(String managedList, String enabledList,
            String pkg, ComponentKey trigger) {
        List<ComponentKey> result = new ArrayList<>();
        if (pkg == null || pkg.isEmpty()) {
            return result;
        }
        Set<ComponentKey> managed = new LinkedHashSet<>(EnabledListDelta.parse(managedList));
        Set<ComponentKey> enabled = new LinkedHashSet<>(EnabledListDelta.parse(enabledList));
        Set<ComponentKey> seen = new LinkedHashSet<>();
        for (ComponentKey key : managed) {
            if (pkg.equals(key.getPackageName()) && enabled.contains(key) && seen.add(key)) {
                result.add(key);
            }
        }
        if (trigger != null && pkg.equals(trigger.getPackageName())
                && managed.contains(trigger) && enabled.contains(trigger)
                && seen.add(trigger)) {
            result.add(trigger);
        }
        return result;
    }

    /**
     * 计算服务在抑制期内再次断开时的延后检查延迟（毫秒）：
     * 当处于修复抑制期内且当前服务已无任何存活连接时（说明新服务启动后遭遇系统二次查杀，而非旧连接残留回调），
     * 绝不能直接丢弃事件，应计算延后检查的延迟毫秒数，待抑制期结束后立即执行补偿检查。
     *
     * @param now               当前时刻 (elapsedRealtime)
     * @param suppressUntilMs   抑制期截止时刻 (elapsedRealtime)
     * @return 延后检查所需的毫秒延迟；若抑制期已过则返回 0L
     */
    static long calculateSecondaryCrashDelay(long now, long suppressUntilMs) {
        if (now >= suppressUntilMs) {
            return 0L;
        }
        // 抑制期剩余时间 + 500ms 缓冲，且至少延后 1000ms，避开系统的查杀波动期
        return Math.max(1000L, (suppressUntilMs - now) + 500L);
    }

    /**
     * 稳定性复查后是否需要重新排期修复：
     * 仅在模块启用、服务仍在锁定列表、仍在系统启用列表、绑定状态可读取且确认未绑定时才重新修复。
     * 用户主动关闭服务、模块停用或绑定状态不可读时必须保持克制，避免复活被关闭的服务或误修复。
     */
    static boolean shouldScheduleStabilityRepair(boolean moduleEnabled, boolean managed,
            boolean inEnabledList, boolean boundCheckAvailable, boolean bound) {
        return moduleEnabled && managed && inEnabledList && boundCheckAvailable && !bound;
    }

    /** 将开机相对毫秒格式化为秒（保留 1 位小数，如 142.3s） */
    static String formatUptimeSeconds(long elapsedRealtimeMs) {
        long tenths = Math.max(0L, elapsedRealtimeMs) / 100L;
        return (tenths / 10L) + "." + (tenths % 10L) + "s";
    }

    /**
     * 组合面向用户展示的中文描述与面向 AI/排障的原始技术参数：
     * 格式为 "[分类] 中文描述 | rawKey=val ... up=142.3s"
     */
    static String formatEventLine(String category, String message, String rawMeta,
            long elapsedRealtimeMs) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(category).append("] ").append(message);
        sb.append(" | ");
        if (rawMeta != null && !rawMeta.isEmpty()) {
            sb.append(rawMeta).append(' ');
        }
        sb.append("up=").append(formatUptimeSeconds(elapsedRealtimeMs));
        return sb.toString();
    }
}

