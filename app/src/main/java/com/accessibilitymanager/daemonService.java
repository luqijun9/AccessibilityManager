package com.accessibilitymanager;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
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
    private volatile String mCrashedFixServiceName;
    private volatile String mCrashedFixLabel;
    // 标记 onStartCommand 是否是紧跟在 onCreate 之后的首次调用。
    // 首次启动时 onCreate 已触发崩溃检测，onStartCommand 不应重复触发；
    // 仅当 App 在服务已运行时再次调用 startService 才触发"服务刷新"检测。
    private boolean mFirstCommandAfterCreate = true;

    final private BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String set = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (set == null) set = "";
            String oldValue = tmpSettingValue;
            boolean settingChanged = !oldValue.equals(set);
            if (settingChanged) {
                doDaemon(set);
            }
            if (!sp.getBoolean("unlock_crash_check", false)) return;
            if (!mIsFixing || System.currentTimeMillis() - mLastFixStartTime > 5000) {
                if (mIsFixing) {
                    LogUtil.log(daemonService.this, "[崩溃检测] 修复操作超时，强制重置mIsFixing");
                    mIsFixing = false;
                }
                if (settingChanged) {
                    String systemAppLabel = getSystemAppChangeLabel(oldValue, set);
                    boolean ignoreSystemChange = sp.getBoolean("ignore_system_crash_trigger", true);
                    if (systemAppLabel != null && ignoreSystemChange) {
                        LogUtil.log(daemonService.this, "忽略" + systemAppLabel + "的设置变化");
                        return;
                    }
                    mHandler.postDelayed(() -> checkCrashedServices("解锁"), 1000);
                } else {
                    checkCrashedServices("解锁");
                }
            }
        }
    };

    private String getSystemAppChangeLabel(String oldValue, String newValue) {
        if (oldValue == null) oldValue = "";
        if (newValue == null) newValue = "";
        String changedService = null;
        if (oldValue.length() < newValue.length()) {
            for (String s : newValue.split(":")) {
                if (s.isEmpty()) continue;
                if (!(":" + oldValue + ":").contains(":" + s + ":")) {
                    changedService = s;
                    break;
                }
            }
        } else {
            for (String s : oldValue.split(":")) {
                if (s.isEmpty()) continue;
                if (!(":" + newValue + ":").contains(":" + s + ":")) {
                    changedService = s;
                    break;
                }
            }
        }
        if (changedService == null) return null;
        try {
            int slashIdx = changedService.indexOf("/");
            if (slashIdx <= 0) return null;
            String pkgName = changedService.substring(0, slashIdx);
            ApplicationInfo appInfo = packageManager.getApplicationInfo(pkgName, 0);
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) return null;
            return appInfo.loadLabel(packageManager).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

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
            String oldValue = tmpSettingValue;
            boolean settingChanged = !oldValue.equals(s);
            if (settingChanged) {
                doDaemon(s);
            }
            if ((!mIsFixing || System.currentTimeMillis() - mLastFixStartTime > 5000) && settingChanged) {
                if (mIsFixing) {
                    LogUtil.log(daemonService.this, "[崩溃检测] 修复操作超时，强制重置mIsFixing");
                    mIsFixing = false;
                }
                String systemAppLabel = getSystemAppChangeLabel(oldValue, s);
                boolean ignoreSystemChange = sp.getBoolean("ignore_system_crash_trigger", true);
                if (systemAppLabel != null && ignoreSystemChange) {
                    LogUtil.log(daemonService.this, "忽略" + systemAppLabel + "的设置变化");
                    return;
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
            String normalized = normalizeServiceId(serviceName);
            if (!isServiceInList(normalized)) {
                if (cleanedDaemon == null) cleanedDaemon = new StringBuilder();
                else cleanedDaemon.append(":");
                cleanedDaemon.append(serviceName);
                continue;
            }
            if (isServiceInSettings(normalized, s))
                continue;

            ApplicationInfo applicationInfo = new ApplicationInfo();
            try {

                applicationInfo = packageManager.getApplicationInfo(serviceName.substring(0, serviceName.indexOf("/")), PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            String packageLabel = applicationInfo.loadLabel(packageManager).toString();
            LogUtil.log(daemonService.this, "[保活] 检测到服务缺失：" + normalized + " (" + packageLabel + ")");
            add.append(normalized).append(":");
            add1.append(packageLabel).append("\n");
            if (sp.getBoolean("toast", true)) {
                final String crashedName = mCrashedFixServiceName;
                final String crashedLabel = mCrashedFixLabel;
                final boolean isCrashed = crashedName != null && serviceNameMatches(normalized, crashedName);
                if (isCrashed) {
                    mCrashedFixServiceName = null;
                    mCrashedFixLabel = null;
                }
                final String toastText = isCrashed ? "保活崩溃服务 " + crashedLabel : "保活" + packageLabel;
                mHandler.post(() -> Toast.makeText(daemonService.this, toastText, Toast.LENGTH_SHORT).show());
            }
        }
        if (cleanedDaemon != null) {
            java.util.Set<String> staleSet = new java.util.HashSet<>();
            for (String stale : cleanedDaemon.toString().split(":")) {
                if (!stale.isEmpty()) staleSet.add(stale);
            }
            String[] entries = list.split(":");
            StringBuilder newList = new StringBuilder();
            for (String entry : entries) {
                if (entry.isEmpty()) continue;
                boolean isStale = false;
                ComponentName entryCn = ComponentName.unflattenFromString(entry);
                for (String stale : staleSet) {
                    ComponentName staleCn = ComponentName.unflattenFromString(stale);
                    if (staleCn != null && staleCn.equals(entryCn)) {
                        isStale = true;
                        break;
                    }
                }
                if (!isStale) {
                    if (newList.length() > 0) newList.append(":");
                    newList.append(entry);
                }
            }
            sp.edit().putString("daemon", newList.toString()).apply();
        }
        if (add.length() > 0) {
            tmpSettingValue = add + s;
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);
            final String text = add1.toString();
            mHandler.post(() -> {
                notification.setContentText(text + new SimpleDateFormat("时间：H:mm:ss", Locale.getDefault()).format(Calendar.getInstance().getTime())).setContentTitle("已保活以下无障碍服务：");
                systemService.notify(1, notification.build());
            });
            LogUtil.log(daemonService.this, "[保活] 已重新开启被关闭的服务：" + add1.toString().replace("\n", " "));
        } else {
            tmpSettingValue = s;
        }
        mCrashedFixServiceName = null;
        mCrashedFixLabel = null;
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

    private String normalizeServiceId(String serviceId) {
        ComponentName cn = ComponentName.unflattenFromString(serviceId);
        return cn != null ? cn.flattenToString() : serviceId;
    }

    private boolean isServiceInSettings(String serviceId, String settingValue) {
        if (settingValue == null) return false;
        ComponentName target = ComponentName.unflattenFromString(serviceId);
        if (target == null) return false;
        for (String entry : settingValue.split(":")) {
            if (entry.isEmpty()) continue;
            ComponentName entryCn = ComponentName.unflattenFromString(entry);
            if (target.equals(entryCn)) return true;
        }
        return false;
    }

    private boolean isServiceInList(String serviceId) {
        ComponentName target = ComponentName.unflattenFromString(serviceId);
        if (target == null) return false;
        for (String entry : l) {
            ComponentName entryCn = ComponentName.unflattenFromString(entry);
            if (target.equals(entryCn)) return true;
        }
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

                String[] trackedServices = daemonList.split(":");
                final int retryRemaining = mFixRetryRemaining;

                for (String cs : crashedServicesList) {
                    for (String tracked : trackedServices) {
                        if (serviceNameMatches(cs, tracked)) {
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

        if (pkgName.equals(getPackageName())) {
            useForceStop = false;
        }

        ApplicationInfo applicationInfo = new ApplicationInfo();
        try {
            applicationInfo = packageManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        String packageLabel = applicationInfo.loadLabel(packageManager).toString();

        mCrashedFixServiceName = serviceName;
        mCrashedFixLabel = packageLabel;

        String logPrefix = isRetry ? "[崩溃修复-重试]" : "[崩溃修复]";

        if (useForceStop) {
            // 强行停止会导致对应app的无障碍服务也被关闭，触发 ContentObserver，
            // 由 doDaemon → doDaemonImpl 保活路径统一弹 Toast
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
            ComponentName target = ComponentName.unflattenFromString(serviceName);
            StringBuilder sb = new StringBuilder();
            for (String entry : enabled.split(":")) {
                if (entry.isEmpty()) continue;
                ComponentName entryCn = ComponentName.unflattenFromString(entry);
                if (target != null && target.equals(entryCn)) continue;
                if (sb.length() > 0) sb.append(":");
                sb.append(entry);
            }
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, sb.toString());
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
        }

        mHandler.post(() -> {
            notification.setContentText("已重启崩溃服务：" + packageLabel + "\n"
                            + new SimpleDateFormat("时间：H:mm:ss", Locale.getDefault()).format(Calendar.getInstance().getTime()))
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
        packageManager = getPackageManager();
        Toast.makeText(daemonService.this, "启动保活", Toast.LENGTH_SHORT).show();
        refreshInstalledServiceList();
        //注册监视器，读取当前设置项并存到tmpsettingValue
        mContentOb = new SettingsValueChangeContentObserver();
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, mContentOb);
        tmpSettingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (tmpSettingValue == null) tmpSettingValue = "";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(myReceiver, new IntentFilter("android.intent.action.USER_PRESENT"), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(myReceiver, new IntentFilter("android.intent.action.USER_PRESENT"));
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification.build());
        }

        //先做一次保活
        doDaemon(tmpSettingValue);
        //启动时也检查一次崩溃
        checkCrashedServices("服务启动");
        TimerReceiver.scheduleNext(this);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelPostFixCheck();
        TimerReceiver.cancel(this);
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

        String alarmSource = intent != null ? intent.getStringExtra("source") : null;
        if ("Alarm".equals(alarmSource)) {
            checkCrashedServices("Alarm");
        } else if ("AccessibilityService".equals(alarmSource)) {
            checkCrashedServices("解锁(无障碍检测)");
        } else if (!mFirstCommandAfterCreate) {
            checkCrashedServices("服务刷新");
        }
        mFirstCommandAfterCreate = false;
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
