package com.accessibilitymanager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public class TimerReceiver extends BroadcastReceiver {
    public static final String ACTION_PERIODIC_CHECK = "com.accessibilitymanager.PERIODIC_CRASH_CHECK";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("AM_DIAG", "[TimerReceiver] onReceive, action=" + intent.getAction());
        if (ACTION_PERIODIC_CHECK.equals(intent.getAction())) {
            final PendingResult pendingResult = goAsync();
            new Thread(() -> {
                SharedPreferences sp = context.getSharedPreferences("data", Context.MODE_PRIVATE);
                if (!sp.getBoolean("crashfix", false)) {
                    LogUtil.log(context, "[定时唤醒] 崩溃修复已关闭，跳过本次检测");
                    cancel(context);
                    pendingResult.finish();
                    return;
                }
                if (!sp.getBoolean("periodic_check", true)) {
                    LogUtil.log(context, "[定时唤醒] 定时检测未开启，取消残留定时器");
                    cancel(context);
                    pendingResult.finish();
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
                pendingResult.finish();
            }).start();
        }
    }

    public static void scheduleNext(Context context) {
        SharedPreferences sp = context.getSharedPreferences("data", Context.MODE_PRIVATE);
        if (!sp.getBoolean("crashfix", false)) {
            cancel(context);  // 清除残留定时器，避免开关关闭后旧定时器仍触发
            return;
        }
        if (!sp.getBoolean("periodic_check", true)) {
            cancel(context);  // 同上
            return;
        }
        int intervalMinutes = sp.getInt("periodic_check_interval", 10);
        if (intervalMinutes <= 0) {
            cancel(context);  // 同上
            return;
        }

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

        // 尝试多种 flag 组合，确保跨应用版本升级后旧 PendingIntent 也能被取消
        // 旧版本可能未使用 FLAG_IMMUTABLE，而新版强制使用了它，导致 cancel() 无法匹配
        int[][] flagCombos = {
                {PendingIntent.FLAG_UPDATE_CURRENT, PendingIntent.FLAG_IMMUTABLE},
                {PendingIntent.FLAG_CANCEL_CURRENT, PendingIntent.FLAG_IMMUTABLE},
                {PendingIntent.FLAG_IMMUTABLE}
        };
        // API < 31 时不带 FLAG_IMMUTABLE 的组合（兼容旧版创建的 PendingIntent）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            flagCombos = new int[][]{
                    {PendingIntent.FLAG_UPDATE_CURRENT, PendingIntent.FLAG_IMMUTABLE},
                    {PendingIntent.FLAG_CANCEL_CURRENT, PendingIntent.FLAG_IMMUTABLE},
                    {PendingIntent.FLAG_IMMUTABLE},
                    {PendingIntent.FLAG_UPDATE_CURRENT},
                    {PendingIntent.FLAG_CANCEL_CURRENT},
                    {}
            };
        }
        for (int[] combo : flagCombos) {
            int flags = 0;
            for (int f : combo) flags |= f;
            try {
                PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, flags);
                if (pi != null) {
                    am.cancel(pi);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
