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
                    cancel(context, "崩溃修复已关闭");
                    pendingResult.finish();
                    return;
                }
                if (!sp.getBoolean("periodic_check", false)) {
                    LogUtil.log(context, "[定时唤醒] 定时检测未开启，取消残留定时器");
                    cancel(context, "定时检测已关闭");
                    pendingResult.finish();
                    return;
                }
                int intervalMinutes = sp.getInt("periodic_check_interval", 10);
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
            cancel(context, "崩溃修复已关闭");
            return;
        }
        if (!sp.getBoolean("periodic_check", false)) {
            cancel(context, "定时检测已关闭");
            return;
        }
        int intervalMinutes = sp.getInt("periodic_check_interval", 10);
        if (intervalMinutes <= 0) {
            cancel(context, "检测间隔无效");
            return;
        }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, TimerReceiver.class);
        intent.setAction(ACTION_PERIODIC_CHECK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerTime = SystemClock.elapsedRealtime() + intervalMinutes * 60 * 1000L;
        boolean wakeIdle = sp.getBoolean("periodic_check_wake_idle", true);

        if (wakeIdle) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi);
            }
            LogUtil.log(context, "[定时唤醒] 已调度下次强制检测：" + intervalMinutes + "分钟后");
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME, triggerTime, pi);
            LogUtil.log(context, "[定时唤醒] 已调度下次宽松检测：" + intervalMinutes + "分钟后");
        }
    }

    public static void cancel(Context context, String reason) {
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
            LogUtil.log(context, "[定时唤醒] 取消定时器：" + reason);
        }
    }
}
