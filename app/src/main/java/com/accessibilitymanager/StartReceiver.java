package com.accessibilitymanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

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
                    context.registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(unlockReceiver, filter);
                }
            } else {
                startIfBootEnabled(context);
            }
        } else {
            startIfBootEnabled(context);
        }
    }

    private void startIfBootEnabled(Context context) {
        new Thread(() -> {
            SharedPreferences sp = context.getSharedPreferences("data", 0);
            if (!sp.getBoolean("boot", true)) return;

            TimerReceiver.scheduleNext(context);

            Intent i = new Intent(context, daemonService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i);
            else
                context.startService(i);
        }).start();
    }
}
