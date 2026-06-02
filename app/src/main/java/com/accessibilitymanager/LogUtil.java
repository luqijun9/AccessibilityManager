package com.accessibilitymanager;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogUtil {

    private static final String LOG_DIR = "logs";
    private static final String TAG = "AccMgr";
    private static final String DATE_SEPARATOR_PREFIX = "__DATE__";
    private static final String GAP_MARKER = "__GAP__";
    private static final SimpleDateFormat FILE_DATE_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private static final SimpleDateFormat DISPLAY_DATE_FMT = new SimpleDateFormat("M月d日", Locale.getDefault());
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private static final long SEPARATOR_INTERVAL = 5 * 60 * 1000L;
    private static final int MAX_LOG_DAYS = 3;
    private static String sCurrentDate;
    private static long sLastLogTime = 0;

    public static synchronized void log(Context ctx, String msg) {
        try {
            long now = System.currentTimeMillis();
            String today = FILE_DATE_FMT.format(new Date(now));
            File dir = new File(ctx.getFilesDir(), LOG_DIR);
            if (!dir.exists()) dir.mkdirs();

            File logFile = new File(dir, today + ".txt");

            if (!today.equals(sCurrentDate)) {
                sCurrentDate = today;
                sLastLogTime = 0;

                String displayDate = DISPLAY_DATE_FMT.format(new Date(now));
                FileOutputStream fos = new FileOutputStream(logFile, true);
                OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
                writer.write(DATE_SEPARATOR_PREFIX + displayDate + "\n");
                writer.flush();
                writer.close();

                cleanupOldLogs(dir);
            }

            String timestamp = TIME_FMT.format(new Date(now));
            String line;
            if (sLastLogTime > 0 && now - sLastLogTime > SEPARATOR_INTERVAL) {
                line = GAP_MARKER + "\n" + timestamp + "  " + msg + "\n";
            } else {
                line = timestamp + "  " + msg + "\n";
            }
            sLastLogTime = now;

            FileOutputStream fos = new FileOutputStream(logFile, true);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(line);
            writer.flush();
            writer.close();
            Log.d(TAG, msg);
        } catch (Exception ignored) {
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
