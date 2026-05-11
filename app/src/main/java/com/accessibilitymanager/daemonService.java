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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
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
    private volatile boolean mIsFixing = false;
    private int mFixRetryRemaining = 1;
    private volatile long mLastCrashedCheckStartTime = 0;
    private volatile long mLastFixStartTime = 0;
    private final Object mFixLock = new Object();
    private final Map<String, Long> mLastFixTime = new ConcurrentHashMap<>();

    final private BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String set = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (set == null) set = "";
            boolean settingChanged = !tmpSettingValue.equals(set);
            if (settingChanged) {
                doDaemon(set);
            }
            if (!mIsFixing || System.currentTimeMillis() - mLastFixStartTime > 5000) {
                if (mIsFixing) {
                    LogUtil.log(daemonService.this, "[崩溃检测] 修复操作超时，强制重置mIsFixing");
                    mIsFixing = false;
                }
                if (settingChanged) {
                    mHandler.postDelayed(() -> checkCrashedServices("解锁"), 1000);
                } else {
                    checkCrashedServices("解锁");
                }
            }
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
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) s = "";
            boolean settingChanged = !tmpSettingValue.equals(s);
            if (settingChanged) {
                doDaemon(s);
            }
            if ((!mIsFixing || System.currentTimeMillis() - mLastFixStartTime > 5000) && settingChanged) {
                if (mIsFixing) {
                    LogUtil.log(daemonService.this, "[崩溃检测] 修复操作超时，强制重置mIsFixing");
                    mIsFixing = false;
                }
                mHandler.postDelayed(() -> checkCrashedServices("设置变化"), 1000);
            }
        }
    }


    private void doDaemon(String s) {
        String list = sp.getString("daemon", "");
        if (sp.getBoolean("delay_daemon", false)) {
            final String setting = s;
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                doDaemonImpl(setting);
            }).start();
            return;
        }
        doDaemonImpl(s);
    }

    private void doDaemonImpl(String s) {
        String list = sp.getString("daemon", "");
        String[] serviceNames = Pattern.compile(":").split(list);
        StringBuilder add = new StringBuilder();
        StringBuilder add1 = new StringBuilder();
        StringBuilder cleanedDaemon = null;
        for (String serviceName : serviceNames) {
            if (serviceName == null || serviceName.equals("null") || serviceName.length() == 0)
                continue;
            if (!l.contains(serviceName)) {
                if (cleanedDaemon == null) cleanedDaemon = new StringBuilder();
                else cleanedDaemon.append(":");
                cleanedDaemon.append(serviceName);
                continue;
            }
            String[] parts = Pattern.compile("/").split(serviceName);
            if (parts.length >= 2 && (s.contains(parts[0] + "/" + parts[1]) || s.contains(parts[0] + "/" + parts[0] + parts[1])))
                continue;

            ApplicationInfo applicationInfo = new ApplicationInfo();
            try {

                applicationInfo = packageManager.getApplicationInfo(serviceName.substring(0, serviceName.indexOf("/")), PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            String packageLabel = applicationInfo.loadLabel(packageManager).toString();
            LogUtil.log(daemonService.this, "[保活] 检测到服务缺失：" + serviceName + " (" + packageLabel + ")");
            add.append(serviceName).append(":");
            add1.append(packageLabel).append("\n");
            if (sp.getBoolean("toast", true))
                mHandler.post(() -> Toast.makeText(daemonService.this, "保活" + packageLabel, Toast.LENGTH_SHORT).show());
        }
        if (cleanedDaemon != null) {
            String cleanedList = list;
            for (String stale : cleanedDaemon.toString().split(":")) {
                cleanedList = cleanedList.replace(stale + ":", "").replace(":" + stale, "").replace(stale, "");
            }
            cleanedList = cleanedList.replace("::", ":").replaceAll("^:|:$", "");
            sp.edit().putString("daemon", cleanedList).apply();
        }
        if (add.length() > 0) {
            tmpSettingValue = add + s;
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);
            final String text = add1.toString();
            mHandler.post(() -> {
                notification.setContentText(text + new SimpleDateFormat("时间：H:mm:ss秒", Locale.getDefault()).format(Calendar.getInstance().getTime())).setContentTitle("已保活以下无障碍服务：");
                systemService.notify(1, notification.build());
            });
            LogUtil.log(daemonService.this, "[保活] 已重新开启被关闭的服务：" + add1.toString().replace("\n", " "));
        } else {
            tmpSettingValue = s;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean serviceNameMatches(String name1, String name2) {
        if (name1.equals(name2)) return true;
        String[] p1 = Pattern.compile("/").split(name1);
        String[] p2 = Pattern.compile("/").split(name2);
        if (p1.length < 2 || p2.length < 2) return false;
        if (!p1[0].equals(p2[0])) return false;
        String svc1 = p1[1];
        String svc2 = p2[1];
        if (svc1.equals(svc2)) return true;
        if (svc1.startsWith(".") && (p1[0] + svc1).equals(svc2)) return true;
        if (svc2.startsWith(".") && (p2[0] + svc2).equals(svc1)) return true;
        return false;
    }

    private void checkCrashedServices(String source) {
        if (!sp.getBoolean("crashfix", false)) return;
        if (mIsCheckingCrashed) {
            if (System.currentTimeMillis() - mLastCrashedCheckStartTime > 5000) {
                LogUtil.log(daemonService.this, "[崩溃检测] 上次检测超时，强制重置");
                mIsCheckingCrashed = false;
            } else {
                return;
            }
        }
        String daemonList = sp.getString("daemon", "");
        if (daemonList.length() == 0) return;
        LogUtil.log(daemonService.this, "[崩溃检测] 触发来源：" + source);
        mIsCheckingCrashed = true;
        mLastCrashedCheckStartTime = System.currentTimeMillis();
        new Thread(() -> {
            try {
                Process p;
                try {
                    p = ShellUtil.exec();
                } catch (Exception e) {
                    mIsCheckingCrashed = false;
                    return;
                }
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                os.writeBytes("dumpsys accessibility 2>/dev/null\n");
                os.writeBytes("exit\n");
                os.flush();
                os.close();

                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder allOutput = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    allOutput.append(line).append("\n");
                }
                reader.close();
                p.waitFor();

                String fullOutput = allOutput.toString();

                Matcher rawLineMatcher = Pattern.compile("Crashed services:.*").matcher(fullOutput);
                String rawCrashedLine;
                if (rawLineMatcher.find()) {
                    rawCrashedLine = rawLineMatcher.group().trim();
                } else {
                    rawCrashedLine = "Crashed services: (未找到此行)";
                }
                LogUtil.log(daemonService.this, "[崩溃检测] 返回 " + rawCrashedLine);

                List<String> crashedServicesList = new ArrayList<>();
                Matcher serviceMatcher = Pattern.compile("\\{([^{}]+)\\}").matcher(rawCrashedLine);
                while (serviceMatcher.find()) {
                    String svc = serviceMatcher.group(1).trim();
                    if (!svc.isEmpty()) {
                        crashedServicesList.add(svc);
                    }
                }

                if (crashedServicesList.isEmpty()) {
                    mIsCheckingCrashed = false;
                    return;
                }

                StringBuilder crashedLog = new StringBuilder();
                for (String cs : crashedServicesList) {
                    if (crashedLog.length() > 0) crashedLog.append(", ");
                    crashedLog.append(cs);
                }

                LogUtil.log(daemonService.this, "[崩溃检测] 检测到" + crashedServicesList.size() + "个崩溃服务：" + crashedLog.toString());

                String[] trackedServices = daemonList.split(":");
                final int retryRemaining = mFixRetryRemaining;
                boolean anyMatched = false;

                for (String cs : crashedServicesList) {
                    for (String tracked : trackedServices) {
                        if (serviceNameMatches(cs, tracked)) {
                            anyMatched = true;
                            if ("修复后复查".equals(source)) {
                                if (retryRemaining <= 0) {
                                    LogUtil.log(daemonService.this, "[崩溃修复] 修复后复查仍崩溃，已达最大重试次数，放弃：" + cs);
                                    mHandler.post(() -> Toast.makeText(daemonService.this, "保活崩溃服务失败", Toast.LENGTH_SHORT).show());
                                    continue;
                                }
                                LogUtil.log(daemonService.this, "[崩溃修复] 修复后复查仍崩溃，进行重试(" + retryRemaining + ")：" + cs);
                                mFixRetryRemaining--;
                                mLastFixTime.put(cs, System.currentTimeMillis());
                                String serviceToFix = cs;
                                new Thread(() -> fixCrashedService(serviceToFix, true)).start();
                            } else {
                                Long lastFix = mLastFixTime.get(cs);
                                if (lastFix != null && System.currentTimeMillis() - lastFix < 8000) {
                                    continue;
                                }
                                LogUtil.log(daemonService.this, "[崩溃检测] 检测到保活服务崩溃：" + cs);
                                mFixRetryRemaining = 1;
                                mLastFixTime.put(cs, System.currentTimeMillis());
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
        mIsFixing = true;
        mLastFixStartTime = System.currentTimeMillis();
        cancelPostFixCheck();

        String pkgName;
        try {
            pkgName = serviceName.substring(0, serviceName.indexOf("/"));
        } catch (Exception e) {
            mIsCheckingCrashed = false;
            mIsFixing = false;
            return;
        }

        boolean useForceStop = sp.getBoolean("fixmode", true);

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

        if (useForceStop) {
            LogUtil.log(daemonService.this, logPrefix + " 强杀进程：" + serviceName + " ← force-stop " + pkgName);
            try {
                Process forceStop = ShellUtil.exec();
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
        } else {
            LogUtil.log(daemonService.this, logPrefix + " 仅关闭服务：" + serviceName);
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) enabled = "";
            String newEnabled = enabled.replace(serviceName + ":", "")
                    .replace(":" + serviceName, "")
                    .replace(serviceName, "");
            newEnabled = newEnabled.replace("::", ":").replaceAll("^:|:$", "");
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newEnabled);
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
        }

        mHandler.post(() -> {
            notification.setContentText("已重启崩溃服务：" + packageLabel + "\n"
                            + new SimpleDateFormat("时间：H:mm:ss秒", Locale.getDefault()).format(Calendar.getInstance().getTime()))
                    .setContentTitle("无障碍保活");
            systemService.notify(1, notification.build());
        });

        mIsFixing = false;
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
        boolean crashFix = sp.getBoolean("crashfix", false);
        boolean autoDisabled = sp.getBoolean("crashfix_auto_disabled", false);
        ShellUtil.reset();
        if (crashFix && !ShellUtil.hasAnyPermission()) {
            sp.edit().putBoolean("crashfix", false).putBoolean("crashfix_auto_disabled", true).apply();
            LogUtil.log(this, "[崩溃修复] 无root/Shizuku权限，已自动关闭崩溃修复功能");
        } else if (!crashFix && autoDisabled && ShellUtil.hasAnyPermission()) {
            sp.edit().putBoolean("crashfix", true).putBoolean("crashfix_auto_disabled", false).apply();
            LogUtil.log(this, "[崩溃修复] 检测到权限恢复，已自动重新开启崩溃修复功能");
        }
        packageManager = getPackageManager();
        Toast.makeText(daemonService.this, "启动保活", Toast.LENGTH_SHORT).show();
        refreshInstalledServiceList();
        //注册监视器，读取当前设置项并存到tmpsettingValue
        mContentOb = new SettingsValueChangeContentObserver();
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, mContentOb);
        tmpSettingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (tmpSettingValue == null) tmpSettingValue = "";

        registerReceiver(myReceiver, new IntentFilter("android.intent.action.USER_PRESENT"));
        //发送前台通知
        String notifyTitle = sp.getString("notify_title", "海绵宝宝，猜猜我有几颗糖");
        String notifyText = sp.getString("notify_text", "猜对了两颗都给你！");
        notification = new Notification.Builder(this)
                .setAutoCancel(true)
                .setContentText(notifyText)
                .setContentTitle(notifyTitle);
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
        refreshInstalledServiceList();
        String currentSetting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (currentSetting == null) currentSetting = "";
        doDaemon(currentSetting);
        checkCrashedServices("服务重启");
        return START_STICKY;
    }

    private void refreshInstalledServiceList() {
        l = new ArrayList<>();
        try {
            List<AccessibilityServiceInfo> list = ((AccessibilityManager) getApplicationContext()
                    .getSystemService(Context.ACCESSIBILITY_SERVICE)).getInstalledAccessibilityServiceList();
            for (AccessibilityServiceInfo info : list) {
                l.add(info.getId());
            }
        } catch (Exception ignored) {
        }
    }

}
