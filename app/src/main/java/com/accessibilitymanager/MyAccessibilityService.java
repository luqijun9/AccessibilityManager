package com.accessibilitymanager;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
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

        CharSequence packageNameSeq = event.getPackageName();
        if (packageNameSeq != null) {
            String packageName = packageNameSeq.toString();
            // 过滤系统UI，避免下拉通知栏等操作导致包名频繁跳动
            if (!packageName.equals("com.android.systemui") && !packageName.equals("com.miui.systemui")) {
                SharedPreferences sp = getSharedPreferences("data", Context.MODE_PRIVATE);
                String oldPkg = sp.getString("current_foreground_pkg", "");
                if (!packageName.equals(oldPkg)) {
                    sp.edit().putString("current_foreground_pkg", packageName).apply();
                    // daemonService 已注册监听器，会自动响应 current_foreground_pkg 的变化
                }
            }
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
        Log.d("AM_DIAG", "[MyAccessibilityService] onDeviceUnlocked 被调用");
        SharedPreferences sp = getSharedPreferences("data", Context.MODE_PRIVATE);
        if (!sp.getBoolean("unlock_crash_check", false)) return;
        if (!sp.getBoolean("crashfix", false)) return;

        long now = System.currentTimeMillis();
        if (now - mLastUnlockTriggerTime < MIN_UNLOCK_TRIGGER_INTERVAL) return;
        mLastUnlockTriggerTime = now;

        daemonService.notifyUnlockFromAccessibility();
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
