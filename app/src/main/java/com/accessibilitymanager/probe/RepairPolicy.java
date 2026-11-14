package com.accessibilitymanager.probe;

/**
 * 无障碍自动修复的策略计算辅助类（无 Android / Xposed 框架依赖，便于单元测试验证）：
 * 1. 开机与首次连接启动潮冷却期计算；
 * 2. 强杀进程时的系统/自身包名排除与同包冷却防连环双杀判定。
 */
final class RepairPolicy {

    static final long BOOT_GRACE_MS = 90_000L;
    static final long BOOT_FIRST_CONNECT_WINDOW_MS = 180_000L;
    static final long BOOT_WAVE_GRACE_MS = 30_000L;
    static final long PACKAGE_KILL_COOLDOWN_MS = 10_000L;

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

    static boolean isSelfOrSystemPackage(String pkg, String contextPkg) {
        if (pkg == null || pkg.isEmpty()) {
            return true;
        }
        if ("android".equals(pkg) || pkg.equals(contextPkg)) {
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

