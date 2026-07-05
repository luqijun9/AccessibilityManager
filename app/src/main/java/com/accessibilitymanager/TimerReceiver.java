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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class TimerReceiver extends BroadcastReceiver {
    public static final String ACTION_PERIODIC_CHECK = "com.accessibilitymanager.PERIODIC_CRASH_CHECK";
    private static final AtomicInteger sAlarmSequence = new AtomicInteger(0);

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("AM_DIAG", "[TimerReceiver] onReceive, action=" + intent.getAction());
        if (ACTION_PERIODIC_CHECK.equals(intent.getAction())) {
            final int seq = sAlarmSequence.incrementAndGet();
            final String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            final PendingResult pendingResult = goAsync();
            new Thread(() -> {
                SharedPreferences sp = context.getSharedPreferences("data", Context.MODE_PRIVATE);
                if (!sp.getBoolean("crashfix", false)) {
                    LogUtil.log(context, "[定时唤醒] #[seq=" + seq + "][" + nowStr + "] 崩溃修复已关闭，跳过本次检测");
                    cancel(context, "crashfix_off#" + seq);
                    pendingResult.finish();
                    return;
                }
                if (!sp.getBoolean("periodic_check", false)) {
                    LogUtil.log(context, "[定时唤醒] #[seq=" + seq + "][" + nowStr + "] 定时检测未开启，取消残留定时器");
                    cancel(context, "periodic_off#" + seq);
                    pendingResult.finish();
                    return;
                }
                int intervalMinutes = sp.getInt("periodic_check_interval", 10);
                LogUtil.log(context, "[定时唤醒] #[seq=" + seq + "][" + nowStr + "] AlarmManager 触发, 间隔=" + intervalMinutes + "分钟");
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
            cancel(context, "schedule_crashfix_off");
            return;
        }
        if (!sp.getBoolean("periodic_check", false)) {
            cancel(context, "schedule_periodic_off");
            return;
        }
        int intervalMinutes = sp.getInt("periodic_check_interval", 10);
        if (intervalMinutes <= 0) {
            cancel(context, "schedule_invalid_interval");
            return;
        }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, TimerReceiver.class);
        intent.setAction(ACTION_PERIODIC_CHECK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerTime = SystemClock.elapsedRealtime() + intervalMinutes * 60 * 1000L;
        String nextTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(
                new Date(System.currentTimeMillis() + intervalMinutes * 60 * 1000L));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi);
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi);
        }
        LogUtil.log(context, "[定时唤醒] 已调度下一次: " + intervalMinutes + "分钟后(" + nextTime + ")");
    }

    public static void cancel(Context context) {
        cancel(context, "外部调用");
    }

    public static void cancel(Context context, String reason) {
        String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        LogUtil.log(context, "[定时唤醒] cancel 调用, 原因=" + reason + ", 时间=" + nowStr);

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, TimerReceiver.class);
        intent.setAction(ACTION_PERIODIC_CHECK);

        // 官方推荐方式：先使用 FLAG_NO_CREATE 获取已有 PendingIntent，存在则取消
        // 参见 https://developer.android.com/develop/background-work/services/alarms#cancel
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            am.cancel(pi);
        }
        LogUtil.log(context, "[定时唤醒] cancel 完成");
    }
}
