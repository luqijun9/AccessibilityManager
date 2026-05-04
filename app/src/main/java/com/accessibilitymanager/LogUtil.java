package com.accessibilitymanager;

import android.content.Context;

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
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private static String sCurrentDate;

    public static synchronized void log(Context ctx, String msg) {
        try {
            String today = DATE_FMT.format(new Date());
            File dir = new File(ctx.getFilesDir(), LOG_DIR);
            if (!dir.exists()) dir.mkdirs();

            File logFile = new File(dir, today + ".txt");

            if (!today.equals(sCurrentDate)) {
                sCurrentDate = today;
                if (logFile.exists()) logFile.delete();
            }

            String timestamp = TIME_FMT.format(new Date());
            String line = timestamp + "  " + msg + "\n";

            FileOutputStream fos = new FileOutputStream(logFile, true);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(line);
            writer.flush();
            writer.close();
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
