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
import java.util.Date;
import java.util.Locale;

public class LogUtil {

    private static final String LOG_DIR = "logs";
    private static final String TAG = "AccMgr";
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private static final long SEPARATOR_INTERVAL = 5 * 60 * 1000L;
    private static final String SEPARATOR = "----------------------------------------------------------------------\n";
    private static String sCurrentDate;
    private static long sLastLogTime = 0;

    public static synchronized void log(Context ctx, String msg) {
        try {
            long now = System.currentTimeMillis();
            String today = DATE_FMT.format(new Date(now));
            File dir = new File(ctx.getFilesDir(), LOG_DIR);
            if (!dir.exists()) dir.mkdirs();

            File logFile = new File(dir, today + ".txt");

            if (!today.equals(sCurrentDate)) {
                if (sCurrentDate != null) {
                    File oldFile = new File(dir, sCurrentDate + ".txt");
                    if (oldFile.exists()) oldFile.delete();
                }
                sCurrentDate = today;
                sLastLogTime = logFile.exists() ? logFile.lastModified() : 0;
            }

            String timestamp = TIME_FMT.format(new Date(now));
            String line;
            if (sLastLogTime > 0 && now - sLastLogTime > SEPARATOR_INTERVAL) {
                line = SEPARATOR + timestamp + "  " + msg + "\n";
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

    public static String readTodayLog(Context ctx) {
        try {
            String today = DATE_FMT.format(new Date());
            File logFile = new File(new File(ctx.getFilesDir(), LOG_DIR), today + ".txt");
            if (!logFile.exists()) return "";

            FileInputStream fis = new FileInputStream(logFile);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
