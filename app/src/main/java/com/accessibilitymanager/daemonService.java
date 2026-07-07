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

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class daemonService extends Service {

    private SettingsValueChangeContentObserver mContentOb;
    SharedPreferences sp;
    Notification.Builder notification;
    NotificationManager systemService;
    PackageManager packageManager;
    private Handler mHandler;
    private Runnable mPostFixCheckRunnable;

    // ── 共享状态：仅在 daemonExecutor 线程中读写 ──
    private String tmpSettingValue;
    private List<String> l = new ArrayList<>();
    private boolean mIsFixing = false;
    private int mFixRetryRemaining = 1;
    private long mLastFixStartTime = 0;
    private final Map<String, Long> mLastFixTime = new HashMap<>();
    private String mCrashedFixServiceName;
    private String mCrashedFixLabel;
    private boolean mFirstCommandAfterCreate = true;

    // ── 解锁检测重复触发追踪（仅在 crashCheckExecutor 线程读写）──
    private long mLastUnlockBroadcastTime = 0;
    private long mLastAccessibilityUnlockTime = 0;

    // ── 执行器 ──
    // daemonExecutor: 串行处理所有共享状态（保活、修复、设置读写）
    private final ExecutorService daemonExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "daemon-worker");
        t.setDaemon(true);
        return t;
    });
    // crashCheckExecutor: 独立执行 dumpsys，不阻塞保活队列
    private final ExecutorService crashCheckExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "crash-check-worker");
        t.setDaemon(true);
        return t;
    });

    // ════════════════════════════════════════════════════════════════
    //  BroadcastReceiver
    // ════════════════════════════════════════════════════════════════
    final private BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            LogUtil.log(daemonService.this, "[解锁广播] 收到 USER_PRESENT 广播");
            // 主线程读设置（ContentProvider IPC 快），结果提交到 daemonExecutor
            final String currentSetting = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            daemonExecutor.submit(() -> handleUnlockBroadcast(currentSetting));
        }
    };

    /** 在 daemonExecutor 线程中执行 */
    private void handleUnlockBroadcast(String currentSetting) {
        if (currentSetting == null) currentSetting = "";
        String oldValue = tmpSettingValue;
        boolean settingChanged = !oldValue.equals(currentSetting);

        // 保活逻辑：如果设置变化，刷新已安装列表并执行保活
        if (settingChanged) {
            refreshInstalledServiceList();
            doDaemon(currentSetting);
        }

        // 崩溃检测逻辑
        if (!sp.getBoolean("unlock_crash_check", false)) {
            LogUtil.log(daemonService.this, "[解锁广播] unlock_crash_check 未开启，跳过崩溃检测");
            return;
        }
        if (mIsFixing && System.currentTimeMillis() - mLastFixStartTime <= 5000) {
            LogUtil.log(daemonService.this, "[解锁广播] 修复操作进行中(5秒内)，跳过本次检测");
            return;
        }
        if (mIsFixing) {
            LogUtil.log(daemonService.this, "[崩溃检测] 修复操作超时(>5s)，跳过本次广播检测");
        }
        if (settingChanged) {
            String systemAppLabel = getSystemAppChangeLabel(oldValue, currentSetting);
            boolean ignoreSystemChange = sp.getBoolean("ignore_system_crash_trigger", true);
            if (systemAppLabel != null && ignoreSystemChange) {
                LogUtil.log(daemonService.this, "忽略" + systemAppLabel + "的服务变化，不触发检测");
                return;
            }
        }
        // 提交到 crashCheckExecutor 执行 dumpsys
        crashCheckExecutor.submit(() -> checkCrashedServicesInternal("解锁"));
    }

    // ════════════════════════════════════════════════════════════════
    //  ContentObserver
    // ════════════════════════════════════════════════════════════════
    class SettingsValueChangeContentObserver extends ContentObserver {
        public SettingsValueChangeContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            final String currentSetting = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            daemonExecutor.submit(() -> handleSettingChange(currentSetting));
        }
    }

    /** 在 daemonExecutor 线程中执行 */
    private void handleSettingChange(String currentSetting) {
        if (currentSetting == null) currentSetting = "";
        String oldValue = tmpSettingValue;
        boolean settingChanged = !oldValue.equals(currentSetting);

        if (settingChanged) {
            refreshInstalledServiceList();
            doDaemon(currentSetting);
        }

        if (mIsFixing && System.currentTimeMillis() - mLastFixStartTime <= 5000) {
            return; // 修复中，不触发新的崩溃检测
        }
        if (!settingChanged) return;

        String systemAppLabel = getSystemAppChangeLabel(oldValue, currentSetting);
        boolean ignoreSystemChange = sp.getBoolean("ignore_system_crash_trigger", true);
        if (systemAppLabel != null && ignoreSystemChange) {
            return;
        }
        checkCrashedServices("服务变化");
    }

    // ════════════════════════════════════════════════════════════════
    //  getSystemAppChangeLabel（只读 sp/packageManager，任意线程安全）
    // ════════════════════════════════════════════════════════════════
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
            CharSequence label = appInfo.loadLabel(packageManager);
            return label != null ? label.toString() : pkgName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  doDaemon / doDaemonImpl（仅在 daemonExecutor 线程调用）
    // ════════════════════════════════════════════════════════════════
    /** 调用者必须在 daemonExecutor 线程中 */
    private void doDaemon(String s) {
        boolean delay = sp.getBoolean("delay_daemon", false);
        if (delay) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        doDaemonImpl(s);
    }

    /** 调用者必须在 daemonExecutor 线程中 */
    private void doDaemonImpl(String s) {
        final List<String> localL = l;  // 拍快照，本次执行使用同一份完整列表
        String list = sp.getString("daemon", "");
        String[] serviceNames = Pattern.compile(":").split(list);
        StringBuilder add = new StringBuilder();
        StringBuilder add1 = new StringBuilder();
        StringBuilder cleanedDaemon = null;
        for (String serviceName : serviceNames) {
            if (serviceName == null || serviceName.equals("null") || serviceName.length() == 0)
                continue;
            String normalized = normalizeServiceId(serviceName);
            if (!isServiceInList(normalized, localL)) {
                if (!localL.isEmpty()) {
                    // 已安装列表已就绪且不含该服务，确认已卸载，清理并跳过保活
                    if (cleanedDaemon == null) cleanedDaemon = new StringBuilder();
                    else cleanedDaemon.append(":");
                    cleanedDaemon.append(serviceName);
                    continue;
                }
                // localL 为空（无已安装服务/异常），跳过清理但尝试保活，宁多勿少
                LogUtil.log(daemonService.this, "[保活] l 为空，跳过清理并尝试保活: " + normalized);
            }
            if (isServiceInSettings(normalized, s))
                continue;

            ApplicationInfo applicationInfo = new ApplicationInfo();
            int slashIdx = serviceName.indexOf("/");
            if (slashIdx <= 0) {
                LogUtil.log(daemonService.this, "[保活] 跳过非法服务名(无'/'): " + serviceName);
                continue;
            }
            try {
                applicationInfo = packageManager.getApplicationInfo(serviceName.substring(0, slashIdx), PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            CharSequence label = applicationInfo.loadLabel(packageManager);
            String packageLabel = label != null ? label.toString() : serviceName;
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
                if (!sp.getBoolean("keep_custom_notify", false)) {
                    notification.setContentText(text + new SimpleDateFormat("时间：H:mm:ss", Locale.getDefault()).format(Calendar.getInstance().getTime())).setContentTitle("已保活以下无障碍服务：");
                    systemService.notify(1, notification.build());
                }
            });
            LogUtil.log(daemonService.this, "[保活] 已重新开启被关闭的服务：" + add1.toString().replace("\n", " "));
        } else {
            tmpSettingValue = s;
        }
        mCrashedFixServiceName = null;
        mCrashedFixLabel = null;
    }

    // ════════════════════════════════════════════════════════════════
    //  onBind / 工具方法
    // ════════════════════════════════════════════════════════════════
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
        return isServiceInList(serviceId, l);
    }

    private boolean isServiceInList(String serviceId, List<String> serviceList) {
        ComponentName target = ComponentName.unflattenFromString(serviceId);
        if (target == null) return false;
        for (String entry : serviceList) {
            ComponentName entryCn = ComponentName.unflattenFromString(entry);
            if (target.equals(entryCn)) return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  崩溃检测（在 crashCheckExecutor 中执行 dumpsys）
    // ════════════════════════════════════════════════════════════════

    /** 从任意线程调用，提交到 crashCheckExecutor */
    private void checkCrashedServices(String source) {
        if (!sp.getBoolean("crashfix", false)) return;
        crashCheckExecutor.submit(() -> checkCrashedServicesInternal(source));
    }

    /** 在 crashCheckExecutor 线程中执行：运行 dumpsys，若发现崩溃则回调 daemonExecutor 修复 */
    //region debug-point crash-detection-internal
    private void checkCrashedServicesInternal(String source) {
        // ── 解锁检测重复触发判断 ──
        long now = System.currentTimeMillis();
        boolean isBackground = !MainActivity.sIsForeground;
        if ("解锁".equals(source)) {
            mLastUnlockBroadcastTime = now;
            if (isBackground && now - mLastAccessibilityUnlockTime <= 3000) {
                LogUtil.log(daemonService.this, "[解锁检测] 后台同时触发解锁广播+无障碍检测，记录重复标志");
                sp.edit().putBoolean("unlock_duplicate_detected", true).apply();
            }
        } else if ("解锁(无障碍检测)".equals(source)) {
            mLastAccessibilityUnlockTime = now;
            if (isBackground && now - mLastUnlockBroadcastTime <= 3000) {
                LogUtil.log(daemonService.this, "[解锁检测] 后台同时触发无障碍检测+解锁广播，记录重复标志");
                sp.edit().putBoolean("unlock_duplicate_detected", true).apply();
            }
        }

        String taskId = Integer.toHexString(System.identityHashCode(this)) + "-" + (int)(Math.random() * 0xFFFF);
        Log.d("AccMgrDebug", "[TASK-" + taskId + "] ENTER source=" + source);
        String daemonList = sp.getString("daemon", "");
        if (daemonList.length() == 0) {
            Log.d("AccMgrDebug", "[TASK-" + taskId + "] daemonList empty, return");
            return;
        }
        LogUtil.log(daemonService.this, "[崩溃检测] 触发来源：" + source);
        try {
            Log.d("AccMgrDebug", "[TASK-" + taskId + "] Executing dumpsys accessibility directly (DUMP)...");
            Process p = Runtime.getRuntime().exec("dumpsys accessibility");
            Log.d("AccMgrDebug", "[TASK-" + taskId + "] Direct exec() OK pid=" + p);

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder allOutput = new StringBuilder();
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                allOutput.append(line).append("\n");
                lineCount++;
            }
            reader.close();
            Log.d("AccMgrDebug", "[TASK-" + taskId + "] Read complete, lines=" + lineCount + ", waiting for process...");

            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LogUtil.log(daemonService.this, "[崩溃检测] dumpsys 超时(10s)，强制终止");
                Log.d("AccMgrDebug", "[TASK-" + taskId + "] waitFor TIMEOUT, destroyed");
                return;
            }
            Log.d("AccMgrDebug", "[TASK-" + taskId + "] waitFor OK, parsing output...");

            String fullOutput = allOutput.toString();

            Matcher rawLineMatcher = Pattern.compile("Crashed services:.*").matcher(fullOutput);
            String rawCrashedLine;
            if (rawLineMatcher.find()) {
                rawCrashedLine = rawLineMatcher.group().trim();
            } else {
                rawCrashedLine = "Crashed services: (未找到此行)";
            }
            Log.d("AccMgrDebug", "[TASK-" + taskId + "] rawCrashedLine=" + rawCrashedLine);
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
                Log.d("AccMgrDebug", "[TASK-" + taskId + "] crashedServicesList empty, return");
                return;
            }
            Log.d("AccMgrDebug", "[TASK-" + taskId + "] crashedServicesList=" + crashedServicesList);

            String[] trackedServices = daemonList.split(":");

            for (String cs : crashedServicesList) {
                for (String tracked : trackedServices) {
                    if (serviceNameMatches(cs, tracked)) {
                        // 把修复操作提交回 daemonExecutor 串行执行
                        if ("修复后复查".equals(source)) {
                            daemonExecutor.submit(() -> handleFixRetry(cs));
                        } else {
                            daemonExecutor.submit(() -> handleFixDetected(cs));
                        }
                        break;
                    }
                }
            }
            Log.d("AccMgrDebug", "[TASK-" + taskId + "] NORMAL_EXIT");
        } catch (Exception e) {
            LogUtil.log(daemonService.this, "[崩溃检测] 异常: " + e.getClass().getName() + ": " + e.getMessage());
            Log.d("AccMgrDebug", "[TASK-" + taskId + "] OUTER_CATCH: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }
    //endregion

    /** 在 daemonExecutor 线程中执行：处理首次检测到的崩溃 */
    private void handleFixDetected(String cs) {
        Long lastFix = mLastFixTime.get(cs);
        if (lastFix != null && System.currentTimeMillis() - lastFix < 8000) {
            return;
        }
        LogUtil.log(daemonService.this, "[崩溃检测] 检测到保活服务崩溃：" + cs);
        mFixRetryRemaining = 1;
        mLastFixTime.put(cs, System.currentTimeMillis());
        fixCrashedService(cs, false);
    }

    /** 在 daemonExecutor 线程中执行：处理修复后复查的崩溃（重试） */
    private void handleFixRetry(String cs) {
        if (mFixRetryRemaining <= 0) {
            LogUtil.log(daemonService.this, "[崩溃修复] 修复后复查仍崩溃，已达最大重试次数，放弃：" + cs);
            mHandler.post(() -> Toast.makeText(daemonService.this, "保活崩溃服务失败", Toast.LENGTH_SHORT).show());
            return;
        }
        LogUtil.log(daemonService.this, "[崩溃修复] 修复后复查仍崩溃，进行重试(" + mFixRetryRemaining + ")：" + cs);
        mFixRetryRemaining--;
        mLastFixTime.put(cs, System.currentTimeMillis());
        fixCrashedService(cs, true);
    }

    // ════════════════════════════════════════════════════════════════
    //  崩溃修复（仅在 daemonExecutor 线程中执行）
    // ════════════════════════════════════════════════════════════════
    private void fixCrashedService(String serviceName, boolean isRetry) {
        mIsFixing = true;
        mLastFixStartTime = System.currentTimeMillis();
        cancelPostFixCheck();

        String pkgName;
        try {
            pkgName = serviceName.substring(0, serviceName.indexOf("/"));
        } catch (Exception e) {
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
        CharSequence label = applicationInfo.loadLabel(packageManager);
        String packageLabel = label != null ? label.toString() : pkgName;

        mCrashedFixServiceName = serviceName;
        mCrashedFixLabel = packageLabel;

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
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
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
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        }

        mHandler.post(() -> {
            if (!sp.getBoolean("keep_custom_notify", false)) {
                notification.setContentText("已重启崩溃服务：" + packageLabel + "\n"
                                + new SimpleDateFormat("时间：H:mm:ss", Locale.getDefault()).format(Calendar.getInstance().getTime()))
                        .setContentTitle("无障碍保活");
                systemService.notify(1, notification.build());
            }
        });

        mIsFixing = false;
        // force-stop/putString 会触发 ContentObserver → doDaemonImpl 保活，
        // 5 秒后复查崩溃状态确认修复是否生效
        schedulePostFixCheck();
    }

    private void schedulePostFixCheck() {
        cancelPostFixCheck();
        mPostFixCheckRunnable = () -> {
            crashCheckExecutor.submit(() -> checkCrashedServicesInternal("修复后复查"));
        };
        mHandler.postDelayed(mPostFixCheckRunnable, 5000);
    }

    private void cancelPostFixCheck() {
        if (mPostFixCheckRunnable != null) {
            mHandler.removeCallbacks(mPostFixCheckRunnable);
            mPostFixCheckRunnable = null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  refreshInstalledServiceList（仅在 daemonExecutor 线程调用）
    // ════════════════════════════════════════════════════════════════
    private void refreshInstalledServiceList() {
        List<String> newList = new ArrayList<>();
        try {
            List<AccessibilityServiceInfo> list = ((AccessibilityManager) getApplicationContext()
                    .getSystemService(Context.ACCESSIBILITY_SERVICE)).getInstalledAccessibilityServiceList();
            for (AccessibilityServiceInfo info : list) {
                newList.add(info.getId());
            }
        } catch (Exception ignored) {
        }
        l = newList;
    }

    // ════════════════════════════════════════════════════════════════
    //  onCreate / onDestroy / onStartCommand
    // ════════════════════════════════════════════════════════════════
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("AM_DIAG", "[daemonService] onCreate 开始, pid=" + android.os.Process.myPid());
        mHandler = new Handler();
        sp = getSharedPreferences("data", 0);
        packageManager = getPackageManager();
        String daemonVal = sp.getString("daemon", "");
        Log.d("AM_DIAG", "[daemonService] daemon='" + daemonVal + "', 长度=" + daemonVal.length());

        // 必须先 startForeground() 再 stopSelf()，否则 5 秒内未调用 startForeground 会 ANR
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
                    .setContentIntent(PendingIntent.getActivity(this, 0,
                            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
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

        // ★ 先无条件取消旧定时器，避免更新/重启后残留定时器触发
        // 即使 daemon 为空也执行 cancel，防止旧 PendingIntent 残留
        TimerReceiver.cancel(this, "服务启动清理");
        // TimerReceiver 调度（内部会根据 periodic_check 开关决定是否设置新定时器）
        new Thread(() -> TimerReceiver.scheduleNext(daemonService.this)).start();

        if (daemonVal.length() == 0) {
            Log.d("AM_DIAG", "[daemonService] daemon为空, 停止保活服务");
            stopSelf();
            return;
        }

        Toast.makeText(daemonService.this, "启动保活", Toast.LENGTH_SHORT).show();

        // 注册 ContentObserver 和 BroadcastReceiver
        mContentOb = new SettingsValueChangeContentObserver();
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, mContentOb);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(myReceiver, new IntentFilter("android.intent.action.USER_PRESENT"), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(myReceiver, new IntentFilter("android.intent.action.USER_PRESENT"));
        }

        // 通过 daemonExecutor 串行执行初始化
        daemonExecutor.submit(() -> {
            tmpSettingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (tmpSettingValue == null) tmpSettingValue = "";
            refreshInstalledServiceList();
            doDaemon(tmpSettingValue);
        });

        // crashCheckExecutor 执行首次崩溃检测
        checkCrashedServices("服务启动");
    }

    @Override
    public void onDestroy() {
        Log.d("AM_DIAG", "[daemonService] onDestroy 开始, pid=" + android.os.Process.myPid());
        super.onDestroy();
        cancelPostFixCheck();
        TimerReceiver.cancel(this, "服务销毁");
        try { unregisterReceiver(myReceiver); } catch (Exception ignored) { }
        if (mContentOb != null) {
            try { getContentResolver().unregisterContentObserver(mContentOb); } catch (Exception ignored) { }
        }
        Toast.makeText(daemonService.this, "停止保活", Toast.LENGTH_SHORT).show();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String currentSetting = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        final String alarmSource = intent != null ? intent.getStringExtra("source") : null;

        // 保活部分提交到 daemonExecutor 串行执行
        daemonExecutor.submit(() -> {
            String setting = currentSetting;
            if (setting == null) setting = "";
            refreshInstalledServiceList();
            doDaemon(setting);
        });

        // 崩溃检测部分
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
}
