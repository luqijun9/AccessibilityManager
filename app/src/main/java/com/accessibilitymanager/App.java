package com.accessibilitymanager;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.content.Context;
import com.google.android.material.color.DynamicColors;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class App extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // GKD 同款：在 Android P+ 上解除所有隐藏 API 限制
        // 必须在 onCreate 之前、attachBaseContext 中调用，确保后续类加载不受限制
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        registerActivityLifecycleCallbacks(new ForegroundLifecycleCallback());
    }

    private static class ForegroundLifecycleCallback implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            MainActivity.sIsForeground = true;
        }

        @Override
        public void onActivityPaused(Activity activity) {
            PowerManager pm = (PowerManager) activity.getSystemService(POWER_SERVICE);
            boolean interactive = pm != null && pm.isInteractive();
            // 屏幕关闭（锁屏中）不属于真正的后台，保持 sIsForeground = true
            if (pm == null || interactive) {
                MainActivity.sIsForeground = false;
            }
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

        @Override
        public void onActivityStarted(Activity activity) {}

        @Override
        public void onActivityStopped(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

        @Override
        public void onActivityDestroyed(Activity activity) {}
    }
}