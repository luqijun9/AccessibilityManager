package com.accessibilitymanager.probe;

/**
 * 模块心跳写入状态与重试上限保护器：
 * <ul>
 *     <li>同一 {@code boot_count} 成功写入后不再重复写入；</li>
 *     <li>若开机极早期写入了旧 {@code boot_count}，后续系统 {@code boot_count} 自增后允许重新刷新；</li>
 *     <li>连续写入失败（如 {@code SettingsProvider} 未就绪）时最多重试 {@code maxAttempts} 次，达到上限后熔断停止。</li>
 * </ul>
 */
final class HeartbeatTracker {

    private final int maxAttempts;
    private boolean written;
    private int writtenBootCount = Integer.MIN_VALUE;
    private int attempts;

    HeartbeatTracker(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /**
     * 判断当前是否需要针对 {@code currentBootCount} 写入心跳。
     * 注意：此方法仅幂等去重（同一 boot_count 已成功写入则跳过），绝不因为早期自发重试达到上限
     * 而拦截后续由无障碍服务连接或 App 广播（ping/sync）被动触发的心跳写入。
     */
    synchronized boolean shouldWrite(int currentBootCount) {
        if (written) {
            if (currentBootCount == writtenBootCount) {
                return false;
            }
            // 系统 boot_count 在开机完成阶段发生了自增，重置状态以写入最新 boot_count
            written = false;
            attempts = 0;
            return true;
        }
        return true;
    }

    /**
     * 记录本次心跳写入成功。
     */
    synchronized void onWriteSuccess(int currentBootCount) {
        written = true;
        writtenBootCount = currentBootCount;
        attempts = 0;
    }

    /**
     * 记录一次写入失败，返回是否仍允许继续延迟重试（严格受 {@code maxAttempts} 上限保护）。
     */
    synchronized boolean onWriteFailure() {
        if (written) {
            return false;
        }
        attempts++;
        return attempts < maxAttempts;
    }

    synchronized boolean isWritten() {
        return written;
    }

    synchronized int getWrittenBootCount() {
        return writtenBootCount;
    }

    synchronized int getAttempts() {
        return attempts;
    }

    synchronized boolean isExhausted() {
        return !written && attempts >= maxAttempts;
    }
}

