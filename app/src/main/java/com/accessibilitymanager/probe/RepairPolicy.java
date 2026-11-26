package com.accessibilitymanager.probe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 无障碍自动修复的策略计算辅助类（无 Android / Xposed 框架依赖，便于单元测试验证）：
 * 1. 开机与首次连接启动潮冷却期计算；
 * 2. 强杀进程时的系统/自身包名排除与同包冷却防连环双杀判定；
 * 3. 重构后的状态机时序、连接寿命与快速失败退避策略；
 * 4. 目标包可用性三态（卸载 / 冻结禁用 / 正常）判定，供管理器与模块共用。
 */
public final class RepairPolicy {

    // ── 包可用性三态 ──────────────────────────────────────────────────
    // 与 PackageManager.COMPONENT_ENABLED_STATE_* 的数值解耦，避免纯逻辑测试依赖 Android SDK。
    public static final int PKG_STATE_UNINSTALLED = 0;
    public static final int PKG_STATE_ENABLED = 1;
    public static final int PKG_STATE_DISABLED = 2;

    // PackageManager 的启用状态常量取值（Android 框架固定值）
    private static final int ENABLED_SETTING_DEFAULT = 0;
    private static final int ENABLED_SETTING_ENABLED = 1;
    private static final int ENABLED_SETTING_DISABLED = 2;
    private static final int ENABLED_SETTING_DISABLED_USER = 3;
    private static final int ENABLED_SETTING_DISABLED_UNTIL_USED = 4;

    static final long BOOT_GRACE_MS = 90_000L;
    static final long BOOT_FIRST_CONNECT_WINDOW_MS = 180_000L;
    static final long BOOT_WAVE_GRACE_MS = 30_000L;
    static final long PACKAGE_KILL_COOLDOWN_MS = 10_000L;

    // ── 普通模式解冻保护 ──────────────────────────────────────────────
    /** 普通模式解冻后保守期：禁止强杀，避免把"刚解冻尚未绑定"误判为崩溃；模块模式不再使用 */
    static final long UNFROZEN_GUARD_MS = 60_000L;

    // ── 服务状态机时序 ────────────────────────────────────────────────
    /** 观察到断开后的 settle 等待：给 AMS 自动重绑/OEM 瞬态恢复留出时间 */
    static final long FSM_SETTLE_MS = 3_000L;
    /** 关开写入完成后的首次绑定校验延迟 */
    static final long FSM_VERIFY_MS = 2_000L;
    /** 修复成功后的稳定性复查延迟，覆盖 OEM 数秒内二次查杀与事件丢失 */
    static final long FSM_STABILITY_MS = 5_000L;
    /** 绑定状态不可读时的重试间隔 */
    static final long FSM_BIND_RETRY_MS = 5_000L;
    /** 绑定状态不可读时最多重试 3 次，之后等服务/配置事件重新触发，避免定时器空转 */
    static final int FSM_MAX_BIND_RETRY_ATTEMPTS = 3;
    /**
     * AMS 正在绑定中（组件仍在 mBindingServices）的有界宽限等待。
     *
     * <p>真实场景（ColorOS 日志实证）：冷启动/重启拉起服务时，AMS 先在 1.5~2s 发起 bindLocked
     * 并将组件加入 mBindingServices，应用冷启动 + onServiceConnected 完成可能需要 2~4s；
     * 若此时 SETTLE(3s) 或 VERIFY(2s) 定时器到点直接发起关开修复，会把健康绑定中途打断并造成“重绑失败”。
     */
    static final long FSM_BINDING_GRACE_MS = 2_000L;
    /** 单轮绑定最多宽限 2 次（合计额外 4s），超时仍未完成绑定再进入退避/关开修复，避免无限等待卡死。 */
    static final int FSM_MAX_BINDING_GRACE_ATTEMPTS = 2;

    // ── 旧连接重复检测 ────────────────────────────────────────────────
    /**
     * 发现“同组件两个 bound 连接”后，不立即解绑，先等 AMS 把
     * mComponentNameToServiceMap 从旧连接切到新连接。
     *
     * <p>真实场景：AMS 的 unbindLocked 会 removeServiceLocked 并重建 map；如果模块在
     * 新连接还没进入 mBoundServices/map 时立即 unbind 旧连接，会和 AMS 的 map 重建赛跑，
     * 甚至反向解绑刚建立的新连接。500ms 覆盖一次 AMS bind/initializeService 的常见延迟。
     */
    static final long CONNECTION_DEDUPE_DELAY_MS = 500L;
    /** 最多再确认 2 次（总计约 1.5s），避免在 AMS 异常时无限重试。 */
    static final int CONNECTION_DEDUPE_MAX_ATTEMPTS = 2;

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
     * 把 PackageManager.getApplicationEnabledSetting() 的返回值归一化为三态。
     *
     * <p>场景与边界：
     * <ul>
     *   <li>0(DEFAULT)/1(ENABLED)：包可运行，允许保活与修复；</li>
     *   <li>2(DISABLED)/3(DISABLED_USER)/4(DISABLED_UNTIL_USED)：包不可运行。
     *       冰箱类工具与 `pm disable-user` 冻结使用 3；这几种状态都必须跳过保活与修复，
     *       但**不删除锁定**，等解冻(ACTION_PACKAGE_CHANGED)后自动恢复；</li>
     *   <li>未知取值：按可运行处理，宁可多尝试一次，也不要因为 ROM 私有状态值把正常服务误判为冻结。</li>
     * </ul>
     */
    public static int classifyPackageState(int enabledSetting) {
        switch (enabledSetting) {
            case ENABLED_SETTING_DEFAULT:
            case ENABLED_SETTING_ENABLED:
                return PKG_STATE_ENABLED;
            case ENABLED_SETTING_DISABLED:
            case ENABLED_SETTING_DISABLED_USER:
            case ENABLED_SETTING_DISABLED_UNTIL_USED:
                return PKG_STATE_DISABLED;
            default:
                return PKG_STATE_ENABLED;
        }
    }

    /**
     * 包状态广播到达时是否值得触发一次恢复对账：
     * 只有“已安装且恢复正常启用状态”才代表解冻/重新启用，才需要补回启用列表。
     */
    public static boolean shouldRecoverOnPackageChange(int state) {
        return state == PKG_STATE_ENABLED;
    }

    /** 普通模式解冻后的保守期内禁止强杀；模块模式已删除解冻时间窗。 */
    public static boolean isInUnfrozenGuard(long now, long unfrozenAt) {
        return unfrozenAt > 0L && now >= unfrozenAt && now - unfrozenAt < UNFROZEN_GUARD_MS;
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
     * 按“本次是否真的会强杀进程”裁剪同包批成员。
     *
     * <p>真实场景与边界：
     * <ul>
     *   <li>allowKill=true：{@code killAppProcesses} 会杀死整包进程，同包所有已启用服务都会断开，
     *       必须整批关开，否则未纳入批次的健康服务会停留在“启用但未绑定”状态；</li>
     *   <li>allowKill=false：只关开当前确认未绑定的成员。MacroDroid 等应用可能同时启用多个
     *       无障碍服务且共享同一进程；健康兄弟服务（尤其按键监听服务）被一起解绑/重绑时，
     *       会重新引入音量键事件延迟，因此不能因为“同包”就无条件整批处理；</li>
     *   <li>unbound 必须由调用方在真正执行修复前按 AMS 当前连接状态计算，不能复用旧快照，
     *       避免把已经自愈的服务再次加入批次。</li>
     * </ul>
     */
    static List<ComponentKey> selectRepairBatch(List<ComponentKey> candidates,
            boolean allowKill, Set<ComponentKey> unbound) {
        List<ComponentKey> result = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return result;
        }
        for (ComponentKey key : candidates) {
            if (key == null) {
                continue;
            }
            if (allowKill || (unbound != null && unbound.contains(key))) {
                result.add(key);
            }
        }
        return result;
    }

    /** 重复连接延迟确认的下一步动作。 */
    enum ConnectionDedupeAction {
        /** 等 AMS 的 map 切换/重试 */
        WAIT,
        /** AMS 已指向新连接，可以解绑旧连接 */
        UNBIND_EXISTING,
        /** AMS 仍指向旧连接，新连接是多余绑定，解绑新连接 */
        UNBIND_INCOMING,
        /** 状态不满足/无法判断，放弃本次 dedupe */
        GIVE_UP
    }

    /**
     * 根据 AMS 当前 map、双方 bound 状态和重试次数决定 dedupe 动作。
     *
     * <p>边界与安全原则：
     * <ul>
     *   <li>旧连接已经死了：无需动作；</li>
     *   <li>AMS 已指向 incoming 且 incoming 仍 bound：可以解绑 existing；</li>
     *   <li>AMS 仍指向 existing：说明 AMS 还没切过来，不能解绑 existing；
     *       等重试耗尽后，如果 incoming 也 bound，则解绑 incoming（AMS 不认它是当前连接）；</li>
     *   <li>AMS map 读不到（null/第三方）：先等重试，超时后放弃，绝不在未知状态下动手；</li>
     *   <li>currentIsIncoming=false：模块 map 已被更新的连接替换，放弃，避免操作过期对象。</li>
     * </ul>
     */
    static ConnectionDedupeAction decideConnectionDedupe(boolean existingBound,
            boolean incomingBound, boolean currentIsIncoming, boolean amsPointsIncoming,
            boolean amsPointsExisting, int attempts, int maxAttempts) {
        if (!existingBound) {
            return ConnectionDedupeAction.GIVE_UP;
        }
        if (amsPointsIncoming) {
            if (incomingBound && currentIsIncoming) {
                return ConnectionDedupeAction.UNBIND_EXISTING;
            }
            return ConnectionDedupeAction.GIVE_UP;
        }
        if (amsPointsExisting) {
            if (!incomingBound) {
                return ConnectionDedupeAction.GIVE_UP;
            }
            if (attempts < Math.max(1, maxAttempts)) {
                return ConnectionDedupeAction.WAIT;
            }
            return ConnectionDedupeAction.UNBIND_INCOMING;
        }
        if (attempts < Math.max(1, maxAttempts)) {
            return ConnectionDedupeAction.WAIT;
        }
        return ConnectionDedupeAction.GIVE_UP;
    }

    /**
     * 判定是否允许执行“同名服务重复连接去重/主动解绑旧连接”。
     *
     * <p>1. 解决场景：仅检查“忽略系统应用”会让未在锁定列表中的第三方服务、模块停用期间
     * 或演练模式下的服务也被模块主动调用 unbindLocked()，越权干预非托管服务。
     * <p>2. 前置条件与边界：必须同时满足模块已启用、非演练模式、非忽略系统应用且服务在锁定列表中。
     * <p>3. 兜底策略：不满足门控时仅记录普通连接事件，由系统 AMS 自行维护其连接生命周期。
     */
    static boolean shouldAttemptConnectionDedupe(boolean moduleEnabled, boolean dryRun,
            boolean ignoreSystem, boolean managed) {
        return moduleEnabled && !dryRun && !ignoreSystem && managed;
    }

    /**
     * 判断本次 initializeService 事件是否应按重复事件去重丢弃。
     *
     * <p>1. 解决场景：同一服务短时间内可能出现“旧连接 initializeService(false) 失败后，
     * 新连接 300ms 内 initializeService(true) 成功”的时序交错；若仅按 component 去重 1.5s，
     * 会把紧随其后的成功回调吞掉，导致状态机白等 2~3 秒甚至误触发关开。
     * <p>2. 前置条件与边界：仅在窗口内、同一连接对象且成败结果相同时才去重；任何失败→成功切换
     * 或不同连接对象的 initializeService 都不去重。
     * <p>3. 兜底策略：超过 {@code windowMs} 或时间回拨时一律放行，避免漏掉新连接初始化。
     */
    static boolean shouldDedupeInit(long lastAt, boolean lastSuccess, boolean sameConnection,
            long now, boolean currentSuccess, long windowMs) {
        if (lastAt <= 0L || now < lastAt || now - lastAt >= windowMs) {
            return false;
        }
        return sameConnection && lastSuccess == currentSuccess;
    }

    /**
     * 将开机相对毫秒格式化为秒（保留 1 位小数，如 142.3s）
     */
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

