package com.accessibilitymanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class StartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // 不能使用 goAsync() 延迟 finish()——USER_UNLOCKED 可能在几分钟后才到达，
                // 而 goAsync() 的超时只有约10秒，会导致 ANR。
                // 改用动态注册 receiver，在 USER_UNLOCKED 到达后启动服务。
                IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
                BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent i) {
                        ctx.unregisterReceiver(this);
                        startIfBootEnabled(ctx);
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.getApplicationContext().registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.getApplicationContext().registerReceiver(unlockReceiver, filter);
                }
            } else {
                startIfBootEnabled(context);
            }
        } else {
            startIfBootEnabled(context);
        }
    }

    private void startIfBootEnabled(Context context) {
        Log.d("AM_DIAG", "[StartReceiver] startIfBootEnabled 被调用");
        new Thread(() -> {
            SharedPreferences sp = context.getSharedPreferences("data", 0);
            if (!sp.getBoolean("boot", true)) return;
            // daemon 为空时无需启动保活服务，避免 startForegroundService 后 onCreate
            // 中因 daemon 为空而 stopSelf() 导致 ForegroundServiceDidNotStartInTimeException
            if (sp.getString("daemon", "").length() == 0) return;

            TimerReceiver.scheduleNext(context);

            Intent i = new Intent(context, daemonService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i);
            else
                context.startService(i);
        }).start();
    }
}
