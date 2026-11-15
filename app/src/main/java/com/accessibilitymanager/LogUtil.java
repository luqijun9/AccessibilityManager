package com.accessibilitymanager;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class LogUtil {

    // ── 日志监听器 ──
    public interface LogListener {
        /** 有新日志行写入时回调，type 为 LogEntry.TYPE_* */
        void onNewLog(int type, String text);
    }

    private static final List<LogListener> sListeners = new CopyOnWriteArrayList<>();

    public static void addListener(LogListener listener) {
        sListeners.add(listener);
    }

    public static void removeListener(LogListener listener) {
        sListeners.remove(listener);
    }

    private static final String LOG_DIR = "logs";
    private static final String TAG = "AccMgr";
    private static final String DATE_SEPARATOR_PREFIX = "__DATE__";
    private static final String GAP_MARKER = "__GAP__";
    private static final SimpleDateFormat FILE_DATE_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private static final SimpleDateFormat DISPLAY_DATE_FMT = new SimpleDateFormat("M月d日", Locale.getDefault());
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private static final long SEPARATOR_INTERVAL = 5 * 60 * 1000L;
    private static final int MAX_LOG_DAYS = 3;
    private static String sCurrentDate;
    private static long sLastLogTime = 0;

    public static synchronized void log(Context ctx, String msg) {
        logAt(ctx, System.currentTimeMillis(), msg);
    }

    /** 使用指定时间写日志，补读模块事件时保持原始时间且不重复加时间戳 */
    public static synchronized void logAt(Context ctx, long time, String msg) {
        try {
            long now = time;
            String today = FILE_DATE_FMT.format(new Date(now));
            File dir = new File(ctx.getFilesDir(), LOG_DIR);
            if (!dir.exists()) dir.mkdirs();

            File logFile = new File(dir, today + ".txt");

            if (!today.equals(sCurrentDate)) {
                boolean fileExistsAndNonEmpty = logFile.exists() && logFile.length() > 0;
                boolean writeHeader = shouldWriteDateHeader(today, sCurrentDate, fileExistsAndNonEmpty);
                sCurrentDate = today;
                sLastLogTime = 0;

                if (writeHeader) {
                    String displayDate = DISPLAY_DATE_FMT.format(new Date(now));
                    FileOutputStream fos = new FileOutputStream(logFile, true);
                    OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
                    String dateLine = DATE_SEPARATOR_PREFIX + displayDate;
                    writer.write(dateLine + "\n");
                    writer.flush();
                    writer.close();
                    // 通知日期分隔行
                    notifyListeners(LogEntry.TYPE_DATE_SEPARATOR, displayDate);
                }

                cleanupOldLogs(dir);
            }

            String timestamp = TIME_FMT.format(new Date(now));
            String body;
            if (sLastLogTime > 0 && now - sLastLogTime > SEPARATOR_INTERVAL) {
                body = GAP_MARKER + "\n" + timestamp + "  " + msg;
            } else {
                body = timestamp + "  " + msg;
            }
            sLastLogTime = now;

            FileOutputStream fos = new FileOutputStream(logFile, true);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(body + "\n");
            writer.flush();
            writer.close();
            Log.d(TAG, msg);

            // 通知监听者
            String[] lines = body.split("\n", -1);
            for (String l : lines) {
                if (GAP_MARKER.equals(l)) {
                    notifyListeners(LogEntry.TYPE_GAP_SEPARATOR, null);
                } else if (!l.isEmpty()) {
                    notifyListeners(LogEntry.TYPE_LOG_LINE, l);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void notifyListeners(int type, String text) {
        for (LogListener l : sListeners) {
            try {
                l.onNewLog(type, text);
            } catch (Exception ignored) {
            }
        }
    }

    private static void cleanupOldLogs(File dir) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -MAX_LOG_DAYS);
        String cutoffDate = FILE_DATE_FMT.format(cal.getTime());
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (name.endsWith(".txt") && name.compareTo(cutoffDate + ".txt") < 0) {
                    f.delete();
                }
            }
        }
    }

    public static List<LogEntry> readRecentLogs(Context ctx) {
        List<LogEntry> entries = new ArrayList<>();
        try {
            File dir = new File(ctx.getFilesDir(), LOG_DIR);
            if (!dir.exists()) return entries;

            for (int i = MAX_LOG_DAYS - 1; i >= 0; i--) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, -i);
                String dateStr = FILE_DATE_FMT.format(cal.getTime());
                File logFile = new File(dir, dateStr + ".txt");
                if (!logFile.exists()) continue;

                FileInputStream fis = new FileInputStream(logFile);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(DATE_SEPARATOR_PREFIX)) {
                        entries.add(new LogEntry(LogEntry.TYPE_DATE_SEPARATOR,
                                line.substring(DATE_SEPARATOR_PREFIX.length())));
                    } else if (GAP_MARKER.equals(line)) {
                        entries.add(new LogEntry(LogEntry.TYPE_GAP_SEPARATOR, null));
                    } else {
                        entries.add(new LogEntry(LogEntry.TYPE_LOG_LINE, line));
                    }
                }
                reader.close();
            }
        } catch (Exception ignored) {
        }
        return entries;
    }

    public static String readRecentLogsRaw(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            File dir = new File(ctx.getFilesDir(), LOG_DIR);
            if (!dir.exists()) return "";

            for (int i = MAX_LOG_DAYS - 1; i >= 0; i--) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, -i);
                String dateStr = FILE_DATE_FMT.format(cal.getTime());
                File logFile = new File(dir, dateStr + ".txt");
                if (!logFile.exists()) continue;

                FileInputStream fis = new FileInputStream(logFile);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(DATE_SEPARATOR_PREFIX)) {
                        sb.append("────────────── ").append(line.substring(DATE_SEPARATOR_PREFIX.length()))
                                .append(" ──────────────\n");
                    } else if (GAP_MARKER.equals(line)) {
                        sb.append("──────────────────────────────────────\n");
                    } else {
                        sb.append(line).append("\n");
                    }
                }
                reader.close();
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern FULL_COMPONENT_PATTERN =
            java.util.regex.Pattern.compile(
                    "([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)+)/([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*)+)");

    /**
     * 日志中可能出现的无障碍组件（支持展示层 short 形式与落盘 long 形式）：
     * - pkg/.ClassName
     * - pkg/pkg.sub.ClassName
     * - pkg/pkg.sub.ClassName$Inner
     *
     * <p>用于按服务分配日志颜色。裸类名（pkg/Service）不参与匹配，避免把 URL 里的
     * "github.com/owner" 之类误判成服务组件，导致颜色分配被无关文本挤占。
     */
    private static final java.util.regex.Pattern LOG_COMPONENT_PATTERN =
            java.util.regex.Pattern.compile(
                    "([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+)/"
                            + "(\\.?[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*)");

    static boolean shouldWriteDateHeader(String today, String currentDate, boolean fileExistsAndNonEmpty) {
        if (today == null || today.equals(currentDate)) {
            return false;
        }
        return !fileExistsAndNonEmpty;
    }

    /**
     * 将落盘的完整原始日志行格式化为适合手机界面阅读的简洁文本：
     * 1. 隐藏毫秒后缀（HH:mm:ss.SSS -> HH:mm:ss）；
     * 2. 隐藏竖线 " | " 之后的底层 AI/诊断元数据；
     * 3. 将冗长的完整组件名（pkg/pkg.sub.ClassName）缩写为 pkg/.ClassName。
     */
    public static String formatForDisplay(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) {
            return "";
        }
        String line = rawLine;
        int rawSep = line.indexOf(" | ");
        if (rawSep > 0) {
            line = line.substring(0, rawSep);
        }
        if (line.length() >= 14
                && line.charAt(2) == ':'
                && line.charAt(5) == ':'
                && line.charAt(8) == '.'
                && Character.isDigit(line.charAt(9))
                && Character.isDigit(line.charAt(10))
                && Character.isDigit(line.charAt(11))
                && line.charAt(12) == ' ') {
            line = line.substring(0, 8) + line.substring(12);
        }
        return shortenComponents(line);
    }

    public static String shortenComponents(String text) {
        if (text == null || text.indexOf('/') < 0) {
            return text;
        }
        java.util.regex.Matcher matcher = FULL_COMPONENT_PATTERN.matcher(text);
        StringBuffer sb = null;
        while (matcher.find()) {
            if (sb == null) {
                sb = new StringBuffer(text.length());
            }
            String pkg = matcher.group(1);
            String cls = matcher.group(2);
            int lastDot = cls.lastIndexOf('.');
            String shortComp = lastDot >= 0
                    ? (pkg + "/." + cls.substring(lastDot + 1))
                    : (pkg + "/" + cls);
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(shortComp));
        }
        if (sb == null) {
            return text;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 日志中出现的无障碍组件匹配结果（含文本位置），供日志页给每个服务单独上色。
     * key 统一为 pkg/完整类名，start/end 为在原始展示文本中的下标。
     */
    public static final class ComponentMatch {
        public final String key;
        public final int start;
        public final int end;

        ComponentMatch(String key, int start, int end) {
            this.key = key;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * 提取文本中出现的所有无障碍组件规范键（pkg/完整类名），按首次出现顺序去重。
     * 供日志页按服务分配颜色使用；无法识别的文本返回空列表。
     */
    public static List<String> extractComponentKeys(String text) {
        List<String> keys = new ArrayList<>();
        for (ComponentMatch match : findComponentMatches(text)) {
            if (!keys.contains(match.key)) {
                keys.add(match.key);
            }
        }
        return keys;
    }

    /** 提取文本中的第一个无障碍组件规范键；没有则返回 null。 */
    public static String extractPrimaryComponentKey(String text) {
        List<ComponentMatch> matches = findComponentMatches(text);
        return matches.isEmpty() ? null : matches.get(0).key;
    }

    /** 查找文本中所有可识别的无障碍组件及其位置，保持出现顺序。 */
    public static List<ComponentMatch> findComponentMatches(String text) {
        List<ComponentMatch> matches = new ArrayList<>();
        if (text == null || text.indexOf('/') < 0) {
            return matches;
        }
        java.util.regex.Matcher matcher = LOG_COMPONENT_PATTERN.matcher(text);
        while (matcher.find()) {
            String key = normalizeComponentKey(matcher.group(1), matcher.group(2));
            if (key != null) {
                matches.add(new ComponentMatch(key, matcher.start(), matcher.end()));
            }
        }
        return matches;
    }

    private static String normalizeComponentKey(String pkg, String cls) {
        if (pkg == null || cls == null || pkg.isEmpty() || cls.isEmpty()) {
            return null;
        }
        if (cls.charAt(0) == '.') {
            // short 形式补全包名，保证同一服务的 short/long 写法得到同一个颜色键
            return pkg + "/" + pkg + cls;
        }
        // 纯类名没有包名信息，容易和 URL 等文本混淆，不参与颜色分配
        if (cls.indexOf('.') < 0) {
            return null;
        }
        return pkg + "/" + cls;
    }

    /**
     * 格式化崩溃异常信息，输出包含线程、异常类型、提示信息及完整堆栈追踪
     */
    public static String formatCrashLog(Thread t, Throwable e) {
        String threadName = t != null ? t.getName() : "unknown";
        String errorMsg = (e != null ? e.getClass().getName() : "Throwable")
                + (e != null && e.getMessage() != null ? (": " + e.getMessage()) : "");
        StringBuilder sb = new StringBuilder();
        sb.append("[异常崩溃] 线程: ").append(threadName).append(", 错误: ").append(errorMsg);
        if (e != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            String trace = sw.toString().trim();
            if (!trace.isEmpty()) {
                sb.append("\n").append(trace);
            }
        }
        return sb.toString();
    }

    public static class LogEntry {
        public static final int TYPE_LOG_LINE = 0;
        public static final int TYPE_DATE_SEPARATOR = 1;
        public static final int TYPE_GAP_SEPARATOR = 2;

        public final int type;
        public final String text;

        public LogEntry(int type, String text) {
            this.type = type;
            this.text = text;
        }
    }
}
