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
            final PendingResult pendingResult = goAsync();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
                BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent i) {
                        ctx.unregisterReceiver(this);
                        startIfBootEnabled(ctx);
                        pendingResult.finish();
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(unlockReceiver, filter);
                }
            } else {
                startIfBootEnabled(context);
                pendingResult.finish();
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
