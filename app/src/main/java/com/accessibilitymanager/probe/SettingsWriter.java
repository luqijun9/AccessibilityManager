package com.accessibilitymanager.probe;

import java.util.Collection;

/**
 * 启用列表的串行写入器（纯逻辑，Store 由外部适配 ContentResolver）。
 *
 * <p>为什么必须保留这一把锁：
 * <ul>
 *   <li>Settings.Secure 是全局字符串，读改写不是原子操作；</li>
 *   <li>用户、系统设置、OEM（例如 ColorOS force-stop 后移除服务）也会改同一项；</li>
 *   <li>如果两个修复流程同时读旧值再写回，后写者会覆盖前者的成员，造成服务被误删或漏补。</li>
 * </ul>
 *
 * <p>因此这里保留唯一一个短临界区：<b>读当前值 → 应用 delta → 写回</b>。
 * 临界区只覆盖一次 Settings 读写的耗时，绝不允许跨 500ms 关开等待或 verify 阶段持有。
 * 这样既能保证写入安全，又不会像旧 sRepairInProgress 那样阻塞无关服务。
 *
 * <p>Store 契约：
 * <ul>
 *   <li>{@link Store#read()} 返回当前值；设置项不存在时返回空字符串；读取失败请抛异常；</li>
 *   <li>{@link Store#write(String)} 返回 true 表示写入成功；失败返回 false 或抛异常；</li>
 *   <li>返回 null 视为读取失败，避免把“读取异常”误当成空列表而清空所有服务。</li>
 * </ul>
 */
final class SettingsWriter {

    interface Store {
        String read() throws Exception;

        boolean write(String value) throws Exception;
    }

    enum Status {
        /** 写入成功，lastWrittenValue/writeGeneration 已更新 */
        WRITTEN,
        /** 本次 delta 没有改变列表，未产生写入 */
        UNCHANGED,
        /** 读取失败，未产生写入 */
        READ_FAILED,
        /** 写入失败，lastWrittenValue/writeGeneration 保持不变 */
        WRITE_FAILED
    }

    static final class WriteResult {
        private final Status status;
        private final String before;
        private final String after;
        private final long generation;

        private WriteResult(Status status, String before, String after, long generation) {
            this.status = status;
            this.before = before;
            this.after = after;
            this.generation = generation;
        }

        Status getStatus() {
            return status;
        }

        String getBefore() {
            return before;
        }

        String getAfter() {
            return after;
        }

        long getGeneration() {
            return generation;
        }

        boolean isWritten() {
            return status == Status.WRITTEN;
        }

        boolean isUnchanged() {
            return status == Status.UNCHANGED;
        }

        boolean isFailure() {
            return status == Status.READ_FAILED || status == Status.WRITE_FAILED;
        }
    }

    private final Store store;
    private String lastWrittenValue;
    private long writeGeneration;

    SettingsWriter(Store store) {
        if (store == null) {
            throw new IllegalArgumentException("store == null");
        }
        this.store = store;
    }

    /**
     * 串行执行一次 read-modify-write。
     *
     * <p>为什么每次都要重新读：
     * 调用方可能在排队期间被 OEM/用户/系统设置插入修改；只有执行时重新读取，
     * 才能把本次 add/remove 应用到“最新值”上，而不是旧快照。
     *
     * <p>锁边界：
     * synchronized 只包住本方法内的 read + apply + write；
     * 调用方不得在持有本对象锁时等待 500ms、做 binder 调用或执行 verify。
     */
    synchronized WriteResult applyDelta(Collection<ComponentKey> add,
            Collection<ComponentKey> remove) {
        final String before;
        try {
            before = store.read();
        } catch (Throwable t) {
            return new WriteResult(Status.READ_FAILED, null, null, writeGeneration);
        }
        // null 表示读取异常，而不是“设置项不存在”；此时绝不能写回，否则可能清空启用列表
        if (before == null) {
            return new WriteResult(Status.READ_FAILED, null, null, writeGeneration);
        }

        String after = EnabledListDelta.apply(before, add, remove);
        if (after.equals(before)) {
            return new WriteResult(Status.UNCHANGED, before, before, writeGeneration);
        }

        try {
            if (!store.write(after)) {
                return new WriteResult(Status.WRITE_FAILED, before, after, writeGeneration);
            }
        } catch (Throwable t) {
            return new WriteResult(Status.WRITE_FAILED, before, after, writeGeneration);
        }

        lastWrittenValue = after;
        writeGeneration++;
        return new WriteResult(Status.WRITTEN, before, after, writeGeneration);
    }

    /**
     * ContentObserver 自写识别：观察到的值与模块最近一次成功写入完全一致时，
     * 说明这次变化由模块自己触发，不应再当作“OEM 移除/用户关闭”去补回。
     *
     * <p>只做精确比较，不靠时间窗口。外部写入覆盖后，该值自然不再匹配。
     */
    synchronized boolean isLastWrittenValue(String value) {
        return lastWrittenValue != null && lastWrittenValue.equals(value);
    }

    synchronized String getLastWrittenValue() {
        return lastWrittenValue;
    }

    synchronized long getWriteGeneration() {
        return writeGeneration;
    }
}
