package com.accessibilitymanager;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class daemonService extends Service {

    private SettingsValueChangeContentObserver mContentOb;
    SharedPreferences sp;
    Notification.Builder notification;
    NotificationManager systemService;
    String tmpSettingValue;
    List<String> l;
    PackageManager packageManager;
    private Handler mHandler;
    private Runnable mPostFixCheckRunnable;
    private volatile boolean mIsCheckingCrashed = false;
    private int mFixRetryRemaining = 1;
    private final Object mFixLock = new Object();

    final private BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String set = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (set == null) set = "";
            boolean settingChanged = !tmpSettingValue.equals(set);
            if (settingChanged) {
                doDaemon(set);
            }
            checkCrashedServices("解锁");
        }
    };

    //自定义一个内容监视器
    class SettingsValueChangeContentObserver extends ContentObserver {
        public SettingsValueChangeContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (selfChange) return;
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) s = "";
            boolean settingChanged = !tmpSettingValue.equals(s);
            if (settingChanged) {
                doDaemon(s);
            }
            checkCrashedServices("设置变化");
        }
    }


    private boolean isServiceInSetting(String serviceName, String setting) {
        if (setting == null || setting.isEmpty()) return false;
        String[] parts = Pattern.compile("/").split(serviceName);
        if (parts.length < 2) return false;
        String shortForm = parts[0] + "/" + parts[1];
        String longForm = parts[0] + "/" + parts[0] + parts[1];
        for (String entry : Pattern.compile(":").split(setting)) {
            if (entry.equals(shortForm) || entry.equals(longForm)) return true;
        }
        return false;
    }

    private void doDaemon(String s) {
        String list = sp.getString("daemon", "");
        String[] serviceNames = Pattern.compile(":").split(list);
        StringBuilder add = new StringBuilder();
        StringBuilder add1 = new StringBuilder();
        for (String serviceName : serviceNames) {
            if (serviceName == null || serviceName.equals("null") || serviceName.length() == 0 || isServiceInSetting(serviceName, s) || !l.contains(serviceName))
                continue;

            ApplicationInfo applicationInfo = new ApplicationInfo();
            try {

                applicationInfo = packageManager.getApplicationInfo(serviceName.substring(0, serviceName.indexOf("/")), PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            String packageLabel = applicationInfo.loadLabel(packageManager).toString();
            add.append(serviceName).append(":");
            add1.append(packageLabel).append("\n");
            if (sp.getBoolean("toast", true))
                Toast.makeText(daemonService.this, "保活" + packageLabel, Toast.LENGTH_SHORT).show();
        }
        if (add.length() > 0) {
            tmpSettingValue = add + s;
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);
            notification.setContentText(add1 + new SimpleDateFormat("时间：H:mm ss秒", Locale.getDefault()).format(Calendar.getInstance().getTime())).setContentTitle("已保活以下无障碍服务：");
            systemService.notify(1, notification.build());
            LogUtil.log(daemonService.this, "[保活] 已重新开启被关闭的服务：" + add1.toString().replace("\n", " "));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void checkCrashedServices(String source) {
        if (mIsCheckingCrashed) return;
        String daemonList = sp.getString("daemon", "");
        if (daemonList.length() == 0) return;
        LogUtil.log(daemonService.this, "[崩溃检测] dumpsys accessibility 执行中  触发来源：" + source);
        mIsCheckingCrashed = true;
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                os.writeBytes("dumpsys accessibility 2>/dev/null\n");
                os.writeBytes("exit\n");
                os.flush();
                os.close();

                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                boolean inCrashedSection = false;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Crashed services:")) {
                        inCrashedSection = true;
                        output.append(line).append("\n");
                    } else if (inCrashedSection && line.trim().startsWith("{")) {
                        output.append(line).append("\n");
                    } else if (inCrashedSection && !line.trim().isEmpty() && !line.contains(":")) {
                        output.append(line).append("\n");
                    } else if (inCrashedSection) {
                        break;
                    }
                }
                reader.close();
                p.waitFor();

                String result = output.toString();
                if (result.isEmpty() || !result.contains("Crashed services:")) {
                    mIsCheckingCrashed = false;
                    return;
                }

                String crashed = result
                        .replace("Crashed services:", "")
                        .replace("{", "")
                        .replace("}", "")
                        .trim();

                if (crashed.isEmpty()) {
                    mIsCheckingCrashed = false;
                    return;
                }

                String[] crashedServices = crashed.split("\\s+");
                String[] trackedServices = daemonList.split(":");
                final int retryRemaining = mFixRetryRemaining;

                for (String cs : crashedServices) {
                    cs = cs.trim();
                    if (cs.isEmpty()) continue;
                    for (String tracked : trackedServices) {
                        if (cs.equals(tracked)) {
                            if ("修复后复查".equals(source)) {
                                if (retryRemaining <= 0) {
                                    LogUtil.log(daemonService.this, "[崩溃检测] 修复后复查仍崩溃，已达最大重试次数，放弃：" + cs);
                                    mHandler.post(() -> Toast.makeText(daemonService.this, "保活崩溃服务失败", Toast.LENGTH_SHORT).show());
                                    continue;
                                }
                                LogUtil.log(daemonService.this, "[崩溃检测] 修复后复查仍崩溃，进行重试(" + retryRemaining + ")：" + cs);
                                mFixRetryRemaining--;
                                String serviceToFix = cs;
                                new Thread(() -> fixCrashedService(serviceToFix, true)).start();
                            } else {
                                LogUtil.log(daemonService.this, "[崩溃检测] 检测到保活服务崩溃：" + cs);
                                mFixRetryRemaining = 1;
                                String serviceToFix = cs;
                                new Thread(() -> fixCrashedService(serviceToFix, false)).start();
                            }
                            break;
                        }
                    }
                }
                mIsCheckingCrashed = false;
            } catch (Exception e) {
                mIsCheckingCrashed = false;
            }
        }).start();
    }

    private void fixCrashedService(String serviceName, boolean isRetry) {
        synchronized (mFixLock) {
        cancelPostFixCheck();

        String pkgName;
        try {
            pkgName = serviceName.substring(0, serviceName.indexOf("/"));
        } catch (Exception e) {
            mIsCheckingCrashed = false;
            return;
        }

        boolean noForceStop = sp.getBoolean("fixmode", false);

        ApplicationInfo applicationInfo = new ApplicationInfo();
        try {
            applicationInfo = packageManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        String packageLabel = applicationInfo.loadLabel(packageManager).toString();

        if (sp.getBoolean("toast", true))
            mHandler.post(() -> Toast.makeText(daemonService.this,
                    (isRetry ? "重试重启崩溃服务 " : "重启崩溃服务 ") + packageLabel, Toast.LENGTH_SHORT).show());

        String logPrefix = isRetry ? "[崩溃修复-重试]" : "[崩溃修复]";
        if (noForceStop) {
            LogUtil.log(daemonService.this, logPrefix + " 开始修复(仅关闭服务模式) " + serviceName);
        } else {
            LogUtil.log(daemonService.this, logPrefix + " 开始修复(强杀进程模式) " + serviceName + " ← 执行 force-stop " + pkgName);
        }

        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) enabled = "";
        String newEnabled = enabled.replace(serviceName + ":", "")
                .replace(":" + serviceName, "")
                .replace(serviceName, "");
        newEnabled = newEnabled.replace("::", ":").replaceAll("^:|:$", "");
        tmpSettingValue = newEnabled;

        if (!noForceStop) {
            try {
                Process forceStop = Runtime.getRuntime().exec("su");
                DataOutputStream fos = new DataOutputStream(forceStop.getOutputStream());
                fos.writeBytes("am force-stop " + pkgName + "\n");
                fos.writeBytes("exit\n");
                fos.flush();
                fos.close();
                forceStop.waitFor();
            } catch (Exception ignored) {
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }

        Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);

        LogUtil.log(daemonService.this, logPrefix + " 已移除 " + serviceName + "，等待重新添加");

        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }

        String checkAfterDisable = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (checkAfterDisable == null) checkAfterDisable = "";

        if (checkAfterDisable.isEmpty())
            tmpSettingValue = serviceName;
        else
            tmpSettingValue = checkAfterDisable + ":" + serviceName;

        Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);
        Settings.Secure.putString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");

        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }

        String verifyEnabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (verifyEnabled == null) verifyEnabled = "";

        if (!verifyEnabled.contains(serviceName)) {
            LogUtil.log(daemonService.this, logPrefix + " 重新启用失败：" + serviceName + "，将安排复查");
            mHandler.post(() -> {
                notification.setContentText((isRetry ? "重试" : "") + "重新启用失败：" + packageLabel)
                        .setContentTitle("无障碍保活异常");
                systemService.notify(1, notification.build());
            });
            schedulePostFixCheck();
            return;
        }

        LogUtil.log(daemonService.this, logPrefix + " 修复成功：" + serviceName + " 已重新启用");

        mHandler.post(() -> {
            notification.setContentText("已重启崩溃服务：" + packageLabel + "\n"
                            + new SimpleDateFormat("时间：H:mm ss秒", Locale.getDefault()).format(Calendar.getInstance().getTime()))
                    .setContentTitle("无障碍保活");
            systemService.notify(1, notification.build());
        });

        schedulePostFixCheck();
        }
    }

    private void schedulePostFixCheck() {
        cancelPostFixCheck();
        mPostFixCheckRunnable = () -> {
            mIsCheckingCrashed = false;
            checkCrashedServices("修复后复查");
        };
        mHandler.postDelayed(mPostFixCheckRunnable, 5000);
    }

    private void cancelPostFixCheck() {
        if (mPostFixCheckRunnable != null) {
            mHandler.removeCallbacks(mPostFixCheckRunnable);
            mPostFixCheckRunnable = null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler();
        sp = getSharedPreferences("data", 0);
        if (sp.getString("daemon", "").length() == 0) {
            stopSelf();
            return;
        }
        packageManager = getPackageManager();
        Toast.makeText(daemonService.this, "启动保活", Toast.LENGTH_SHORT).show();
        List<AccessibilityServiceInfo> list = ((AccessibilityManager) getApplicationContext().getSystemService(Context.ACCESSIBILITY_SERVICE)).getInstalledAccessibilityServiceList();
        l = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            l.add(list.get(i).getId());
        }
        //注册监视器，读取当前设置项并存到tmpsettingValue
        mContentOb = new SettingsValueChangeContentObserver();
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, mContentOb);
        tmpSettingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (tmpSettingValue == null) tmpSettingValue = "";

        registerReceiver(myReceiver, new IntentFilter("android.intent.action.USER_PRESENT"));
        //发送前台通知
        notification = new Notification.Builder(this)
                .setAutoCancel(true)
                .setContentText("猜对了两颗都给你！")
                .setContentTitle("海绵宝宝，猜猜我有几颗糖~");
        systemService = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notification
                    .setColor(getColor(R.color.bg))
                    .setSmallIcon(Icon.createWithResource(this, R.drawable.tile))
                    .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel("daemon", "保活无障碍", NotificationManager.IMPORTANCE_DEFAULT);
            notificationChannel.enableLights(false);
            notificationChannel.setShowBadge(false);
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            systemService.createNotificationChannel(notificationChannel);
            notification.setChannelId("daemon");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        startForeground(1, notification.build());

        //先做一次保活
        doDaemon(tmpSettingValue);
        //启动时也检查一次崩溃
        checkCrashedServices("服务启动");
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelPostFixCheck();
        unregisterReceiver(myReceiver);
        getContentResolver().unregisterContentObserver(mContentOb);
        Toast.makeText(daemonService.this, "停止保活", Toast.LENGTH_SHORT).show();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

}
