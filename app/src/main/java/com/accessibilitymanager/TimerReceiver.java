package com.accessibilitymanager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

public class TimerReceiver extends BroadcastReceiver {
    public static final String ACTION_PERIODIC_CHECK = "com.accessibilitymanager.PERIODIC_CRASH_CHECK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_PERIODIC_CHECK.equals(intent.getAction())) {
            SharedPreferences sp = context.getSharedPreferences("data", Context.MODE_PRIVATE);
            if (!sp.getBoolean("crashfix", false)) {
                LogUtil.log(context, "[定时唤醒] 崩溃修复已关闭，跳过本次检测");
                cancel(context);
                return;
            }
            LogUtil.log(context, "[定时唤醒] AlarmManager 触发");
            Intent serviceIntent = new Intent(context, daemonService.class);
            serviceIntent.putExtra("source", "Alarm");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                LogUtil.log(context, "[定时唤醒] 启动服务失败: " + e.getMessage());
            }
            scheduleNext(context);
        }
    }

    public static void scheduleNext(Context context) {
        SharedPreferences sp = context.getSharedPreferences("data", Context.MODE_PRIVATE);
        if (!sp.getBoolean("crashfix", false)) return;
        if (!sp.getBoolean("periodic_check", true)) return;
        int intervalMinutes = sp.getInt("periodic_check_interval", 10);
        if (intervalMinutes <= 0) return;

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, TimerReceiver.class);
        intent.setAction(ACTION_PERIODIC_CHECK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerTime = SystemClock.elapsedRealtime() + intervalMinutes * 60 * 1000L;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi);
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi);
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, TimerReceiver.class);
        intent.setAction(ACTION_PERIODIC_CHECK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }
}
