package com.accessibilitymanager.probe;

import java.util.HashMap;
import java.util.Map;

/**
 * 非受管服务的日志聚合器：同一组件在时间窗口内只放行一条持久事件，
 * 其余只计数并合并进下一条，明细仍由调用方写 logcat，避免高频连接日志挤占事件缓冲。
 */
final class EventThrottle {

    /** 一次判定的结果：是否放行、放行时需要提示的省略条数 */
    static final class Decision {
        final boolean emit;
        final long suppressed;

        Decision(boolean emit, long suppressed) {
            this.emit = emit;
            this.suppressed = suppressed;
        }
    }

    private static final class Entry {
        boolean emitted;
        long lastEmitAt;
        long suppressed;
    }

    private final long windowMs;
    private final Map<String, Entry> entries = new HashMap<>();

    EventThrottle(long windowMs) {
        this.windowMs = windowMs;
    }

    synchronized Decision onEvent(String key, long nowMs) {
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry();
            entries.put(key, entry);
        }
        if (!entry.emitted) {
            entry.emitted = true;
            entry.lastEmitAt = nowMs;
            entry.suppressed = 0;
            return new Decision(true, 0);
        }
        long elapsed = nowMs - entry.lastEmitAt;
        // 时间回拨时按窗口外处理，避免长时间无法输出
        if (elapsed >= windowMs || elapsed < 0) {
            Decision decision = new Decision(true, entry.suppressed);
            entry.lastEmitAt = nowMs;
            entry.suppressed = 0;
            return decision;
        }
        entry.suppressed++;
        return new Decision(false, 0);
    }
}
