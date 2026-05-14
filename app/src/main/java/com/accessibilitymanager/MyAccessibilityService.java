package com.accessibilitymanager;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

public class MyAccessibilityService extends AccessibilityService {

    private static final long MIN_UNLOCK_TRIGGER_INTERVAL = 5000;
    private long mLastUnlockTriggerTime = 0;
    private boolean mWasLocked = true;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null) return;

        boolean isLocked = km.isKeyguardLocked();

        if (mWasLocked && !isLocked) {
            mWasLocked = false;
            onDeviceUnlocked();
        } else if (isLocked) {
            mWasLocked = true;
        }
    }

    private void onDeviceUnlocked() {
        SharedPreferences sp = getSharedPreferences("data", Context.MODE_PRIVATE);
        if (!sp.getBoolean("unlock_crash_check", false)) return;
        if (!sp.getBoolean("crashfix", false)) return;

        long now = System.currentTimeMillis();
        if (now - mLastUnlockTriggerTime < MIN_UNLOCK_TRIGGER_INTERVAL) return;
        mLastUnlockTriggerTime = now;

        //LogUtil.log(this, "[无障碍服务] 检测到设备解锁，触发崩溃检测");

        Intent intent = new Intent(this, daemonService.class);
        intent.putExtra("source", "AccessibilityService");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            LogUtil.log(this, "[无障碍服务] 启动保活服务失败: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null) {
            mWasLocked = km.isKeyguardLocked();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LogUtil.log(this, "[无障碍服务] 服务已销毁");
    }
}
