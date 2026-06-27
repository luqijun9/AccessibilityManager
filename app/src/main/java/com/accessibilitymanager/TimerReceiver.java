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

        // ════════════════════════════════════════════════════════════════
        //  跨版本 PendingIntent cancel 兼容方案
        // ════════════════════════════════════════════════════════════════
        // Android 12+ (API 31) 要求 PendingIntent.getBroadcast() 必须指定
        // FLAG_IMMUTABLE 或 FLAG_MUTABLE。旧版本创建的 PendingIntent 是
        // MUTABLE（默认），而新版本使用 IMMUTABLE 去 getBroadcast() 可能
        // 返回的是新创建的 PendingIntent，而非旧的 → am.cancel() 失效！
        //
        // 因此必须同时尝试 IMMUTABLE 和 MUTABLE 两种 flags。

        int matchedFlags = 0;
        String matchedDesc = "无";

        // ── API 31+：同时尝试 IMMUTABLE 和 MUTABLE ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (int mutableFlag : new int[]{PendingIntent.FLAG_IMMUTABLE, PendingIntent.FLAG_MUTABLE}) {
                try {
                    PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | mutableFlag);
                    am.cancel(pi);
                    matchedFlags++;
                    matchedDesc = (mutableFlag == PendingIntent.FLAG_IMMUTABLE) ? "IMMUTABLE" : "MUTABLE";
                } catch (Exception ignored) {
                }
            }
        }

        // ── API < 31：尝试带和不带 FLAG_IMMUTABLE ──
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.cancel(pi);
                matchedFlags++;
                matchedDesc = "IMMUTABLE";
            } catch (Exception ignored) {
            }
            try {
                PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
                am.cancel(pi);
                matchedFlags++;
                matchedDesc = "无FLAG";
            } catch (Exception ignored) {
            }
        }

        // ── 兜底：先 set 一个即将触发的 alarm 覆盖旧的，再取消 ──
        // 对于部分 OEM ROM，以上方式仍可能无效，通过 set() 强制覆盖旧 alarm
        try {
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_IMMUTABLE : 0));
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 100, pi);
            am.cancel(pi);
            matchedDesc += "+兜底set";
        } catch (Exception ignored) {
        }

        LogUtil.log(context, "[定时唤醒] cancel 完成, 匹配方式=" + matchedDesc);
    }
}
