package com.accessibilitymanager.probe;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedModule;

/**
 * 模块事件缓冲、日志重入保护、防抖落盘、增量拉取与诊断快照采集器。
 */
final class EventLogBuffer {

    static final int EVENT_LIMIT = 1000;
    static final int RAW_TRACE_LIMIT = 1000;
    static final int SECURE_MAX_CHARS = 20000;
    static final int PULL_BATCH_LINES = 120;
    static final int PULL_BATCH_MAX_CHARS = 64000;
    /**
     * KEY_EVENTS 防抖合并窗口：单次崩溃/修复过程连续产生多条事件，
     * 300ms 内合并为一次 Settings.Secure 写入，避免频繁全量写 20KB 字符串。
     */
    static final long EVENT_FLUSH_DEBOUNCE_MS = 300L;
    private static final long SKIP_EVENT_WINDOW_MS = 10 * 60 * 1000L;

    static final String STATE_EVENT_LINES = "event_lines";
    static final String STATE_RAW_TRACE_LINES = "raw_trace_lines";
    static final String STATE_EVENT_SEQ = "event_seq";
    static final String STATE_EVENT_SESSION = "event_session";

    interface DebounceScheduler {
        void postDelayed(Runnable task, long delayMs);

        void removeCallbacks(Runnable task);
    }

    /**
     * 事件落盘防抖合并器（纯逻辑，便于单元测试验证多次事件合并成一次写入）。
     */
    static final class FlushDebouncer {
        private final long delayMs;
        private final DebounceScheduler scheduler;
        private final Runnable sink;
        private final Runnable triggerTask;
        private boolean pending;

        FlushDebouncer(long delayMs, DebounceScheduler scheduler, Runnable sink) {
            this.delayMs = Math.max(0L, delayMs);
            this.scheduler = scheduler;
            this.sink = sink;
            this.triggerTask = () -> {
                pending = false;
                if (this.sink != null) {
                    this.sink.run();
                }
            };
        }

        void requestFlush() {
            if (scheduler == null || delayMs <= 0L) {
                pending = false;
                if (sink != null) {
                    sink.run();
                }
                return;
            }
            if (pending) {
                return;
            }
            pending = true;
            scheduler.postDelayed(triggerTask, delayMs);
        }

        void flushNow() {
            if (pending && scheduler != null) {
                scheduler.removeCallbacks(triggerTask);
            }
            pending = false;
            if (sink != null) {
                sink.run();
            }
        }

        void cancel() {
            if (pending && scheduler != null) {
                scheduler.removeCallbacks(triggerTask);
            }
            pending = false;
        }

        boolean isPending() {
            return pending;
        }
    }

    private static final Object sEventLock = new Object();
    /**
     * 日志重入保护（线程级）。
     *
     * <p>真实故障场景：软重启后 system_server 在约 15~20 秒时，SettingsProvider/ContentService 仍可能未就绪。
     * 此时模块线程写日志（log）若通过 readConfig -> readSecure 读取 Settings，
     * 读取失败后的补偿日志再次进入 log 会形成无限递归并打爆线程栈导致整机崩溃。
     *
     * <p>这层保护是最后一道防线：只要当前线程已经在写日志，任何嵌套 log 直接返回，
     * 绝不再次进入配置读取，保证递归在物理上不可能发生。
     */
    private static final ThreadLocal<Boolean> sLoggingGuard = new ThreadLocal<>();
    private static final Deque<String> sEventLines = new ArrayDeque<>();
    private static final Deque<String> sRawTraceLines = new ArrayDeque<>();
    private static String sEventSession = Long.toHexString(System.currentTimeMillis());
    private static long sEventSeq;
    private static final EventThrottle sSkipThrottle = new EventThrottle(SKIP_EVENT_WINDOW_MS);
    private static volatile Handler sHandler;
    private static FlushDebouncer sFlushDebouncer;

    private EventLogBuffer() {
    }

    static void attachHandler(Handler handler) {
        sHandler = handler;
        if (handler == null) {
            if (sFlushDebouncer != null) {
                sFlushDebouncer.cancel();
                sFlushDebouncer = null;
            }
            return;
        }
        sFlushDebouncer = new FlushDebouncer(
                EVENT_FLUSH_DEBOUNCE_MS,
                new DebounceScheduler() {
                    @Override
                    public void postDelayed(Runnable task, long delayMs) {
                        handler.postDelayed(task, delayMs);
                    }

                    @Override
                    public void removeCallbacks(Runnable task) {
                        handler.removeCallbacks(task);
                    }
                },
                EventLogBuffer::flushEventsNow);
    }

    static void event(String category, String message) {
        event(category, message, null);
    }

    static void event(String category, String message, String rawMeta) {
        long sinceStart = Math.max(0L,
                SystemClock.elapsedRealtime() - PackageRepairCoordinator.getModuleStartAt());
        String line = RepairPolicy.formatEventLine(category, message, rawMeta, sinceStart);
        log(line);
        appendEvent(line);
    }

    /**
     * 未锁定服务的连接/断开：只写 logcat，不写入持久事件缓冲，避免与锁定服务排障日志混在一起。
     */
    static void eventNonManaged(String key, String summary) {
        log("[监听] " + summary + ": " + key);
    }

    /** 高频跳过路径降噪：同一原因 10 分钟内只写一条持久事件，明细仍写 logcat。 */
    static void eventSkip(String key, String category, String message) {
        EventThrottle.Decision decision = sSkipThrottle.onEvent(key, SystemClock.elapsedRealtime());
        if (!decision.emit) {
            log("[" + category + "] " + message);
            return;
        }
        String suffix = decision.suppressed > 0
                ? "（窗口内已省略 " + decision.suppressed + " 条）" : "";
        event(category, message + suffix);
    }

    private static void appendEvent(String message) {
        Handler handler = sHandler;
        if (handler != null) {
            handler.post(() -> {
                synchronized (sEventLock) {
                    addEventLineLocked(message);
                }
                FlushDebouncer debouncer = sFlushDebouncer;
                if (debouncer != null) {
                    debouncer.requestFlush();
                } else {
                    flushEventsNow();
                }
            });
        } else {
            synchronized (sEventLock) {
                addEventLineLocked(message);
                flushEventsLocked();
            }
        }
    }

    private static void addEventLineLocked(String message) {
        sEventSeq++;
        StringBuilder line = new StringBuilder();
        line.append(sEventSession).append('|').append(sEventSeq).append('|')
                .append(System.currentTimeMillis()).append('|')
                .append(message.replace('\n', ' '));
        sEventLines.addLast(line.toString());
        while (sEventLines.size() > EVENT_LIMIT) {
            sEventLines.removeFirst();
        }
    }

    static void flushEventsNow() {
        FlushDebouncer debouncer = sFlushDebouncer;
        if (debouncer != null && debouncer.isPending()) {
            debouncer.cancel();
        }
        synchronized (sEventLock) {
            flushEventsLocked();
        }
    }

    private static void flushEventsLocked() {
        Context context = ModuleConfigStore.getContext();
        if (context == null || sEventLines.isEmpty()) {
            return;
        }
        String payload = buildTailWithinCharLimit(sEventLines, SECURE_MAX_CHARS);
        try {
            Settings.Secure.putString(context.getContentResolver(),
                    AutoRepairEntry.KEY_EVENTS, payload);
        } catch (Throwable t) {
            log("event write failed: " + t);
        }
    }

    static String extractSession(String line) {
        if (line == null) {
            return "";
        }
        int firstPipe = line.indexOf('|');
        return firstPipe > 0 ? line.substring(0, firstPipe) : "";
    }

    static long extractSeq(String line) {
        if (line == null) {
            return -1L;
        }
        int firstPipe = line.indexOf('|');
        if (firstPipe < 0) {
            return -1L;
        }
        int secondPipe = line.indexOf('|', firstPipe + 1);
        if (secondPipe <= firstPipe + 1) {
            return -1L;
        }
        try {
            return Long.parseLong(line.substring(firstPipe + 1, secondPipe));
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    static void normalizeEventLinesSessionLocked(Deque<String> lines, String targetSession) {
        if (lines == null || lines.isEmpty() || targetSession == null || targetSession.isEmpty()) {
            return;
        }
        List<String> normalized = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            int firstPipe = line.indexOf('|');
            if (firstPipe >= 0) {
                String oldSession = line.substring(0, firstPipe);
                if (!targetSession.equals(oldSession)) {
                    normalized.add(targetSession + line.substring(firstPipe));
                    continue;
                }
            }
            normalized.add(line);
        }
        lines.clear();
        for (String line : normalized) {
            lines.addLast(line);
        }
    }

    static String buildTailWithinCharLimit(Iterable<String> lines, int maxChars) {
        if (lines == null || maxChars <= 0) {
            return "";
        }
        List<String> list = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isEmpty()) {
                list.add(line);
            }
        }
        if (list.isEmpty()) {
            return "";
        }
        int totalChars = 0;
        int startIdx = list.size();
        for (int i = list.size() - 1; i >= 0; i--) {
            String line = list.get(i);
            int added = line.length() + (startIdx < list.size() ? 1 : 0);
            if (totalChars + added > maxChars) {
                if (startIdx == list.size()) {
                    return line.substring(0, maxChars);
                }
                break;
            }
            totalChars += added;
            startIdx = i;
        }
        StringBuilder sb = new StringBuilder(totalChars);
        for (int i = startIdx; i < list.size(); i++) {
            if (i > startIdx) {
                sb.append('\n');
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    static String truncateToCharLimit(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    static int ackEventsUpToLocked(Deque<String> lines, String currentSession,
            String ackSession, long ackSeq) {
        if (lines == null || lines.size() <= 1 || ackSeq <= 0L
                || ackSession == null || !ackSession.equals(currentSession)) {
            return 0;
        }
        int removed = 0;
        while (lines.size() > 1) {
            String head = lines.peekFirst();
            if (head == null) {
                lines.removeFirst();
                continue;
            }
            String lineSession = extractSession(head);
            long lineSeq = extractSeq(head);
            if (!currentSession.equals(lineSession) || lineSeq < 0L || lineSeq > ackSeq) {
                break;
            }
            lines.removeFirst();
            removed++;
        }
        return removed;
    }

    static List<ArrayList<String>> buildPullBatchesLocked(Iterable<String> lines,
            String currentSession, String clientSession, long clientLastSeq,
            int maxBatchLines, int maxBatchChars) {
        List<ArrayList<String>> batches = new ArrayList<>();
        if (lines == null || currentSession == null || currentSession.isEmpty()) {
            return batches;
        }
        boolean sameSession = currentSession.equals(clientSession);
        int lineLimit = Math.max(1, maxBatchLines);
        int charLimit = Math.max(256, maxBatchChars);

        ArrayList<String> currentBatch = new ArrayList<>();
        int currentChars = 0;
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            if (sameSession) {
                long seq = extractSeq(line);
                if (seq >= 0L && seq <= clientLastSeq) {
                    continue;
                }
            }
            int len = line.length() + 1;
            if (!currentBatch.isEmpty()
                    && (currentBatch.size() >= lineLimit || currentChars + len > charLimit)) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentChars = 0;
            }
            currentBatch.add(line);
            currentChars += len;
        }
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        return batches;
    }

    static void handlePullLogsCommand(String arg, ResultReceiver receiver) {
        String clientSession = "";
        long clientLastSeq = 0L;
        if (arg != null && !arg.isEmpty()) {
            int sep = arg.indexOf('|');
            if (sep >= 0) {
                clientSession = arg.substring(0, sep);
                try {
                    clientLastSeq = Long.parseLong(arg.substring(sep + 1));
                } catch (Throwable ignored) {
                }
            } else {
                clientSession = arg;
            }
        }

        final String sessionSnapshot;
        final long maxSeqSnapshot;
        final List<ArrayList<String>> batches;
        synchronized (sEventLock) {
            ackEventsUpToLocked(sEventLines, sEventSession, clientSession, clientLastSeq);
            sessionSnapshot = sEventSession;
            maxSeqSnapshot = sEventSeq;
            batches = buildPullBatchesLocked(sEventLines, sEventSession,
                    clientSession, clientLastSeq, PULL_BATCH_LINES, PULL_BATCH_MAX_CHARS);
        }

        if (receiver == null) {
            return;
        }
        try {
            if (batches.isEmpty()) {
                Bundle b = new Bundle();
                b.putString(AutoRepairEntry.BUNDLE_SESSION, sessionSnapshot);
                b.putLong(AutoRepairEntry.BUNDLE_MAX_SEQ, maxSeqSnapshot);
                b.putBoolean(AutoRepairEntry.BUNDLE_HAS_MORE, false);
                b.putStringArrayList(AutoRepairEntry.BUNDLE_LINES, new ArrayList<>());
                receiver.send(0, b);
                return;
            }
            for (int i = 0; i < batches.size(); i++) {
                ArrayList<String> batch = batches.get(i);
                long batchMaxSeq = maxSeqSnapshot;
                if (!batch.isEmpty()) {
                    long lastSeqInBatch = extractSeq(batch.get(batch.size() - 1));
                    if (lastSeqInBatch > 0L) {
                        batchMaxSeq = lastSeqInBatch;
                    }
                }
                Bundle b = new Bundle();
                b.putString(AutoRepairEntry.BUNDLE_SESSION, sessionSnapshot);
                b.putLong(AutoRepairEntry.BUNDLE_MAX_SEQ, batchMaxSeq);
                b.putBoolean(AutoRepairEntry.BUNDLE_HAS_MORE, i < batches.size() - 1);
                b.putStringArrayList(AutoRepairEntry.BUNDLE_LINES, batch);
                receiver.send(0, b);
            }
        } catch (Throwable t) {
            log("pull_logs ResultReceiver.send failed: " + t);
        }
    }

    static void handleAckLogsCommand(String arg) {
        if (arg == null || arg.isEmpty()) {
            return;
        }
        int sep = arg.indexOf('|');
        if (sep < 0) {
            return;
        }
        String ackSession = arg.substring(0, sep);
        long ackSeq;
        try {
            ackSeq = Long.parseLong(arg.substring(sep + 1));
        } catch (Throwable ignored) {
            return;
        }
        synchronized (sEventLock) {
            ackEventsUpToLocked(sEventLines, sEventSession, ackSession, ackSeq);
        }
    }

    /** 采集 system_server 视角的诊断快照，供管理器在无 DUMP/Root/Shizuku 权限时导出日志。 */
    static void writeModuleDump(ResultReceiver receiver) {
        flushEventsNow();
        Context context = ModuleConfigStore.getContextOrSystem();
        if (context == null) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        try {
            String enabled = Settings.Secure.getString(resolver, AutoRepairEntry.SETTING_ENABLED);
            int accessibilityEnabled;
            try {
                accessibilityEnabled = Settings.Secure.getInt(resolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED);
            } catch (Throwable t) {
                accessibilityEnabled = -1;
            }
            String heartbeat = ModuleConfigStore.readSecure(AutoRepairEntry.KEY_LOADED);
            String managed = ModuleConfigStore.readConfig(AutoRepairEntry.KEY_SERVICES);

            StringBuilder sb = new StringBuilder();
            sb.append("采集时间: ").append(System.currentTimeMillis()).append('\n');
            sb.append("模块心跳: ").append(heartbeat == null ? "不存在" : heartbeat)
                    .append("  bootCount=")
                    .append(Settings.Global.getInt(resolver, AutoRepairEntry.SETTING_BOOT_COUNT, -1))
                    .append("  热重载次数=").append(AutoRepairEntry.getHotReloadCount());
            long lastReloadAt = AutoRepairEntry.getLastHotReloadAt();
            if (lastReloadAt > 0L) {
                sb.append(" (最近=").append(lastReloadAt).append(')');
            }
            sb.append('\n');
            sb.append("无障碍总开关: ").append(accessibilityEnabled < 0 ? "不存在" : accessibilityEnabled)
                    .append("  启用列表: ").append(enabled == null ? "不存在"
                            : (enabled.isEmpty() ? "空" : enabled))
                    .append('\n');
            AmsInspector.ensureBindProbeSampled();
            sb.append("绑定状态可读: ").append(AmsInspector.hasConnectionState())
                    .append("  已记录连接: ").append(AmsInspector.connectionCount()).append('\n');
            sb.append("绑定探测方式: ")
                    .append(AmsInspector.getLastBindProbeStrategy() == null
                            ? "未触发" : AmsInspector.getLastBindProbeStrategy())
                    .append('\n');
            String schema = AmsInspector.getLastBindProbeSchema();
            if (schema != null && !schema.isEmpty()) {
                sb.append("连接类关键成员: ").append(schema).append('\n');
            }
            sb.append("模块启用: ").append(ModuleConfigStore.isModuleEnabled())
                    .append("  演练模式: ").append(ModuleConfigStore.isDryRun())
                    .append("  强杀模式: ").append("1".equals(
                            ModuleConfigStore.readConfig(AutoRepairEntry.KEY_FIX_MODE)))
                    .append("  忽略系统应用: ").append(ModuleConfigStore.isIgnoreSystemEnabled())
                    .append("  Toast提示: ").append(ModuleConfigStore.isToastEnabled()).append('\n');
            sb.append("省电与屏幕状态: ").append(ModuleConfigStore.getPowerStateSummary()).append('\n');
            sb.append("锁定列表: ").append(managed == null || managed.isEmpty() ? "空" : managed)
                    .append('\n');
            if (managed != null && !managed.isEmpty()) {
                for (String raw : managed.split(":")) {
                    ComponentKey keyObj = ComponentKey.parse(raw);
                    if (keyObj == null) {
                        continue;
                    }
                    android.content.ComponentName component = new android.content.ComponentName(
                            keyObj.getPackageName(), keyObj.getClassName());
                    String key = keyObj.flatten();
                    AmsInspector.BoundLookupResult lookup = AmsInspector.lookupBoundConnection(component);
                    ServiceRepairStateMachine machine = PackageRepairCoordinator.getMachine(key);
                    sb.append("[服务] ").append(key)
                            .append("  在启用列表=").append(EnabledListDelta.contains(enabled, keyObj))
                            .append("  已绑定=").append(lookup.connection != null)
                            .append("  绑定中=").append(lookup.connection == null
                                    && AmsInspector.isServiceBinding(component))
                            .append("  状态=").append(machine == null ? "UNTRACKED" : machine.getState())
                            .append("  fastFail=").append(machine == null ? 0 : machine.getFastFailCount())
                            .append("  崩溃标记=").append(AmsInspector.getCrashedMarker(component))
                            .append("  ").append(ModuleConfigStore.getPackageProcessSummary(
                                    keyObj.getPackageName()))
                            .append('\n');
                }
            }
            synchronized (sEventLock) {
                if (!sRawTraceLines.isEmpty()) {
                    sb.append("\n--- 模块底层原始轨迹 (最近 ")
                            .append(sRawTraceLines.size()).append(" 条) ---\n");
                    for (String traceLine : sRawTraceLines) {
                        sb.append(traceLine).append('\n');
                    }
                }
            }
            String fullDump = sb.toString();
            if (receiver != null) {
                try {
                    Bundle b = new Bundle();
                    b.putString(AutoRepairEntry.BUNDLE_DUMP, fullDump);
                    receiver.send(0, b);
                } catch (Throwable t) {
                    log("dump_info ResultReceiver.send failed: " + t);
                }
            }
            Settings.Secure.putString(resolver, AutoRepairEntry.KEY_DUMP,
                    truncateToCharLimit(fullDump, SECURE_MAX_CHARS));
        } catch (Throwable t) {
            log("dump info failed: " + t);
            String err = "采集失败: " + t;
            if (receiver != null) {
                try {
                    Bundle b = new Bundle();
                    b.putString(AutoRepairEntry.BUNDLE_DUMP, err);
                    receiver.send(1, b);
                } catch (Throwable ignored) {
                }
            }
            try {
                Settings.Secure.putString(resolver, AutoRepairEntry.KEY_DUMP, err);
            } catch (Throwable ignored) {
            }
        }
    }

    static void log(String message) {
        // 重入直接返回：正常日志不会嵌套调用 log；只有“读取配置失败 -> 补偿日志”这类异常路径会嵌套。
        if (Boolean.TRUE.equals(sLoggingGuard.get())) {
            return;
        }
        sLoggingGuard.set(Boolean.TRUE);
        try {
            try {
                synchronized (sEventLock) {
                    String trace = System.currentTimeMillis()
                            + " (up=" + RepairPolicy.formatUptimeSeconds(SystemClock.elapsedRealtime())
                            + ") " + message;
                    sRawTraceLines.addLast(trace);
                    while (sRawTraceLines.size() > RAW_TRACE_LIMIT) {
                        sRawTraceLines.removeFirst();
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Log.i(AutoRepairEntry.TAG, message);
            } catch (Throwable ignored) {
            }
            try {
                XposedModule inst = AutoRepairEntry.getModuleInstance();
                // 关键：这里绝不能调用 readConfig()/readSecure()，只能读纯内存缓存，
                // 否则在 Settings 未就绪时形成 log -> readSecure -> log 递归打爆栈。
                if (inst != null && ModuleConfigStore.isDebugCacheEnabled()) {
                    inst.log(Log.INFO, AutoRepairEntry.TAG, message);
                }
            } catch (Throwable ignored) {
            }
        } finally {
            sLoggingGuard.remove();
        }
    }

    static void saveHotReloadState(Map<String, Object> state) {
        synchronized (sEventLock) {
            state.put(STATE_EVENT_LINES, new ArrayList<>(sEventLines));
            state.put(STATE_RAW_TRACE_LINES, new ArrayList<>(sRawTraceLines));
            state.put(STATE_EVENT_SEQ, sEventSeq);
            state.put(STATE_EVENT_SESSION, sEventSession);
        }
    }

    static void restoreHotReloadState(Map<?, ?> state) {
        synchronized (sEventLock) {
            Object sessionObj = state.get(STATE_EVENT_SESSION);
            Object seq = state.get(STATE_EVENT_SEQ);
            if (seq instanceof Long) {
                sEventSeq = (Long) seq;
            }
            Object lines = state.get(STATE_EVENT_LINES);
            if (lines instanceof List) {
                sEventLines.clear();
                for (Object item : (List<?>) lines) {
                    if (item instanceof String) {
                        sEventLines.addLast((String) item);
                    }
                }
            }
            if (sessionObj instanceof String && !((String) sessionObj).isEmpty()) {
                sEventSession = (String) sessionObj;
            } else if (!sEventLines.isEmpty()) {
                String candidate = extractSession(sEventLines.peekLast());
                if (candidate.isEmpty()) {
                    candidate = extractSession(sEventLines.peekFirst());
                }
                if (!candidate.isEmpty()) {
                    sEventSession = candidate;
                }
            }
            normalizeEventLinesSessionLocked(sEventLines, sEventSession);
            while (sEventLines.size() > EVENT_LIMIT) {
                sEventLines.removeFirst();
            }
            Object traces = state.get(STATE_RAW_TRACE_LINES);
            if (traces instanceof List) {
                sRawTraceLines.clear();
                for (Object item : (List<?>) traces) {
                    if (item instanceof String) {
                        sRawTraceLines.addLast((String) item);
                    }
                }
                while (sRawTraceLines.size() > RAW_TRACE_LIMIT) {
                    sRawTraceLines.removeFirst();
                }
            }
        }
    }

    static void resetForTesting() {
        attachHandler(null);
        synchronized (sEventLock) {
            sEventLines.clear();
            sRawTraceLines.clear();
            sEventSeq = 0L;
            sEventSession = Long.toHexString(System.currentTimeMillis());
        }
    }

    static void setEventSessionForTesting(String session) {
        synchronized (sEventLock) {
            sEventSession = session;
        }
    }

    static String getEventSessionForTesting() {
        synchronized (sEventLock) {
            return sEventSession;
        }
    }

    static void putEventLineForTesting(String line, long seq) {
        synchronized (sEventLock) {
            sEventLines.addLast(line);
            if (seq > sEventSeq) {
                sEventSeq = seq;
            }
        }
    }

    static List<String> getEventLinesForTesting() {
        synchronized (sEventLock) {
            return new ArrayList<>(sEventLines);
        }
    }
}
