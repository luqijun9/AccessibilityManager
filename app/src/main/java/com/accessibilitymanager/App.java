package com.accessibilitymanager;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.PowerManager;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
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