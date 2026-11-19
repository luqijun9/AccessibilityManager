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

import com.accessibilitymanager.probe.RepairPolicy;

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

    private void updateNotification() {
        if (notification == null || systemService == null) return;
        String notifyTitle = sp.getString("notify_title", "海绵宝宝，猜猜我有几颗糖");
        String notifyText = sp.getString("notify_text", "猜对了两颗都给你！");
        notification.setContentTitle(notifyTitle)
                    .setContentText(notifyText);
        systemService.notify(1, notification.build());
    }

    private static daemonService sInstance = null;

    public static void notifyUnlockFromAccessibility() {
        if (sInstance != null) {
            sInstance.checkCrashedServices("解锁(无障碍检测)");
        }
    }

    public static void notifyNotificationRemoved(Context context, String pkgName) {
        if (pkgName == null || pkgName.isEmpty()) {
            return;
        }
        String appLabel = pkgName;
        if (context != null) {
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(pkgName, PackageManager.GET_META_DATA);
                CharSequence label = info.loadLabel(pm);
                if (label != null && label.length() > 0) {
                    appLabel = label.toString();
                }
            } catch (Exception ignored) {
            }
        }
        final String resolvedLabel = appLabel;
        final daemonService instance = sInstance;
        if (instance != null) {
            instance.daemonExecutor.submit(() -> {
                if (instance.mIsFixing && System.currentTimeMillis() - instance.mLastFixStartTime <= 5000) {
                    LogUtil.log(instance, "[通知监听] 修复操作进行中(5秒内)，忽略 " + resolvedLabel + " 的通知清除事件");
                    return;
                }
                LogUtil.log(instance, "[通知监听] 监听到 " + resolvedLabel + " 通知被清除，触发崩溃检测");
                instance.checkCrashedServices("通知消失(" + resolvedLabel + ")");
            });
        } else if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences("data", 0);
            if (!prefs.getString("daemon", "").isEmpty()
                    && !prefs.getBoolean("lsposed_mode", false)
                    && prefs.getBoolean("crashfix", false)) {
                LogUtil.log(context, "[通知监听] 监听到 " + resolvedLabel + " 通知被清除，拉起守护服务进行崩溃检测");
                Intent serviceIntent = new Intent(context, daemonService.class);
                serviceIntent.putExtra("source", "NotificationRemoved");
                serviceIntent.putExtra("source_label", resolvedLabel);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ── 共享状态：仅在 daemonExecutor 线程中读写 ──
    private String tmpSettingValue;
    private List<String> l = new ArrayList<>();
    private boolean mIsFixing = false;
    private int mFixRetryRemaining = 2;
    private long mLastFixStartTime = 0;
    private final Map<String, Long> mLastFixTime = new HashMap<>();
    // 冻结目标跳过日志冷却（component -> 上次打印时间），10 分钟：只降噪日志，不影响解冻恢复
    private static final long FROZEN_SKIP_LOG_WINDOW_MS = 10 * 60 * 1000L;
    private final Map<String, Long> mFrozenSkipLogAt = new HashMap<>();
    // 解冻时刻（pkg -> 时间戳）：60s 内禁止崩溃修复，避免把"刚解冻尚未绑定"当成崩溃处理
    private final Map<String, Long> mRecentlyUnfrozenAt = new HashMap<>();
    private String mCrashedFixServiceName;
    private String mCrashedFixLabel;
    private boolean mFirstCommandAfterCreate = true;
    // 标记本次 Settings 变化是由白名单应用切换引起的，ContentObserver 应跳过崩溃检测
    private boolean mIsWhitelistAppSwitch = false;

    // LSPosed 模块模式：日志只提示一次，避免每次服务变化重复刷屏
    private boolean mModuleModeLogged;
    private SharedPreferences.OnSharedPreferenceChangeListener mForegroundPkgListener;

    // ── 解锁检测重复触发追踪（仅在 crashCheckExecutor 线程读写）──
    private long mLastUnlockBroadcastTime = 0;
    private long mLastAccessibilityUnlockTime = 0;
    private long mLastDumpsysTime = 0;

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
            daemonExecutor.submit(() -> {
                String currentSetting = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                handleUnlockBroadcast(currentSetting);
            });
        }
    };

    // ── 包状态广播：冻结/解冻感知（普通模式）──
    // 场景：冰箱类工具和 `pm disable-user/enable` 最终都走 PackageManager.setApplicationEnabledSetting，
    // 系统随后发送 ACTION_PACKAGE_CHANGED；接收后用 getApplicationEnabledSetting 读取状态（冻结=3，解冻=0/1）。
    // 恢复入口是 doDaemon（把服务写回启用列表），不是崩溃检测：解冻后的服务并不在 dumpsys Crashed 列表里。
    final private BroadcastReceiver mPackageStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            String pkg = intent.getData() != null
                    ? intent.getData().getSchemeSpecificPart() : null;
            boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            String[] changedComponents = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
            int pkgState = getPackageState(pkg);
            String stateText = pkgState == RepairPolicy.PKG_STATE_DISABLED ? "冻结/禁用"
                    : (pkgState == RepairPolicy.PKG_STATE_UNINSTALLED ? "未安装" : "正常");
            boolean managed = isManagedPackage(pkg);
            String msg = "[包状态] " + (managed ? "受管包" : "忽略") + " " + action
                    + ": pkg=" + pkg + " state=" + stateText
                    + (changedComponents != null ? " changed=" + java.util.Arrays.toString(changedComponents) : "")
                    + (replacing ? " replacing=true" : "");
            // 全量写 logcat 便于 adb 排障；管理器日志页只记录受管包，避免系统其它应用的状态变化刷屏
            Log.d("AM_DIAG", msg + " data=" + intent.getDataString());
            if (!managed) {
                return;
            }
            LogUtil.log(daemonService.this, msg);
            if (sp.getBoolean("lsposed_mode", false)) {
                // LSPosed 模式下由 system_server 内的模块负责恢复；管理器只保留日志，
                // 避免模块 reconcile 与普通模式 doDaemon 同时写启用列表造成双写/回滚。
                return;
            }
            // 解冻（3 -> 0/1）才触发恢复；冻结事件只记日志，绝不能在此期间写回启用列表
            if (Intent.ACTION_PACKAGE_CHANGED.equals(action)
                    && RepairPolicy.shouldRecoverOnPackageChange(pkgState)) {
                mRecentlyUnfrozenAt.put(pkg, System.currentTimeMillis());
                LogUtil.log(daemonService.this, "[包状态] 检测到解冻/重新启用: " + pkg + "，触发保活补回");
                handlePackageUnfrozen(pkg);
            }
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
            daemonExecutor.submit(() -> {
                String currentSetting = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                handleSettingChange(currentSetting);
            });
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

        // 白名单应用切换引起的 Settings 变化，跳过崩溃检测
        if (mIsWhitelistAppSwitch) {
            mIsWhitelistAppSwitch = false;
            return;
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
    //  包可用性三态判定（普通模式）
    // ════════════════════════════════════════════════════════════════
    /**
     * 读取包三态：未安装 / 冻结禁用 / 正常。
     *
     * <p>场景：冰箱类工具与 `pm disable-user` 冻结使用 COMPONENT_ENABLED_STATE_DISABLED_USER(3)。
     * 冻结后应用会从 getInstalledAccessibilityServiceList() 和启用列表中消失，但 PackageManager
     * 仍能查到包——旧代码因此把冻结当成"已安装"，在保活/崩溃修复路径反复操作。
     * 这里给保活、崩溃修复、包广播和 UI 提供统一口径。
     *
     * <p>边界：getApplicationEnabledSetting 在包不存在时抛 IllegalArgumentException；
     * 其它异常一律按"正常"处理，保持旧行为，避免因系统偶发异常停止保活。
     */
    private int getPackageState(String pkg) {
        if (pkg == null || pkg.isEmpty() || packageManager == null) {
            return RepairPolicy.PKG_STATE_UNINSTALLED;
        }
        try {
            packageManager.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return RepairPolicy.PKG_STATE_UNINSTALLED;
        } catch (Throwable t) {
            return RepairPolicy.PKG_STATE_ENABLED;
        }
        try {
            return RepairPolicy.classifyPackageState(
                    packageManager.getApplicationEnabledSetting(pkg));
        } catch (Throwable t) {
            return RepairPolicy.PKG_STATE_ENABLED;
        }
    }

    private String packageOfComponent(String component) {
        int slash = component == null ? -1 : component.indexOf('/');
        return slash > 0 ? component.substring(0, slash) : "";
    }

    /** 包是否在 daemon 锁定列表或全局白名单中（包广播只对受管包做日志/恢复，避免无关包刷屏）。 */
    private boolean isManagedPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        String daemonStr = sp.getString("daemon", "");
        String whitelistStr = sp.getBoolean("whitelist_global_enable", false)
                ? sp.getString("whitelist_services", "") : "";
        return (":" + daemonStr + ":").contains(":" + pkg + "/")
                || (":" + whitelistStr + ":").contains(":" + pkg + "/");
    }

    /**
     * 冻结跳过日志降噪：同一组件 10 分钟内只写一条。
     * 冻结期间其它服务变化仍会反复触发 doDaemon，如果每次都打印会重演日志刷屏；
     * 冷却只作用于日志，不影响解冻广播触发的恢复。
     */
    /** 解冻保守期内禁止崩溃修复：刚解冻的服务尚未绑定属于正常过渡态，不是崩溃。 */
    private boolean isInUnfrozenGuard(String pkg) {
        Long at = mRecentlyUnfrozenAt.get(pkg);
        return at != null && RepairPolicy.isInUnfrozenGuard(System.currentTimeMillis(), at);
    }

    private void logFrozenSkip(String source, String component, String pkg) {
        long now = System.currentTimeMillis();
        Long last = mFrozenSkipLogAt.get(component);
        if (last != null && now - last < FROZEN_SKIP_LOG_WINDOW_MS) {
            return;
        }
        mFrozenSkipLogAt.put(component, now);
        LogUtil.log(this, "[保活] 跳过" + source + "：应用已冻结，保留锁待解冻恢复: " + pkg);
    }

    /** 解冻后等待 AMS 刷新已安装列表：2s 一次，最多 30 次（约 60s）。 */
    private static final long UNFREEZE_LIST_POLL_MS = 2000L;
    private static final int UNFREEZE_LIST_POLL_MAX = 30;

    /**
     * 解冻后的恢复入口（daemonExecutor 串行执行）。
     *
     * <p>前置假设与边界：实测 ColorOS 解冻后 AccessibilityManager.getInstalledAccessibilityServiceList()
     * 会滞后于 PACKAGE_CHANGED 广播；在它刷新前直接写回启用列表会被 AMS 回滚，且不会触发绑定。
     * 因此先每 2 秒轮询一次已安装列表：
     * 1. 列表出现该包的服务后，再把锁定组件写回 ENABLED_ACCESSIBILITY_SERVICES；
     * 2. 写入后 900ms 校验是否被回滚，最多补写 3 次；
     * 3. 60 秒仍未出现时，改用一次"移除 → 500ms → 加回"强制重建连接（实测可清除残余 crashed
     *    连接并触发 AMS 绑定）。
     *
     * <p>兜底：只补回 daemon 锁定列表中的组件；白名单服务受前台应用状态影响，不在这里强行开启。
     * 全程不删锁：恢复失败时保留锁，等待后续包/设置事件再次触发。
     */
    private void handlePackageUnfrozen(String pkg) {
        waitInstalledListThenRecover(pkg, 0);
    }

    private void waitInstalledListThenRecover(String pkg, int attempt) {
        daemonExecutor.submit(() -> {
            if (getPackageState(pkg) != RepairPolicy.PKG_STATE_ENABLED) {
                return; // 期间又被冻结/卸载，放弃本轮恢复
            }
            refreshInstalledServiceList();
            boolean listed = false;
            for (String id : l) {
                if (id.startsWith(pkg + "/")) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                if (attempt == 0) {
                    LogUtil.log(this, "[包状态] 解冻恢复等待系统刷新已安装列表: " + pkg);
                }
                if (attempt < UNFREEZE_LIST_POLL_MAX) {
                    mHandler.postDelayed(() -> waitInstalledListThenRecover(pkg, attempt + 1),
                            UNFREEZE_LIST_POLL_MS);
                } else {
                    LogUtil.log(this, "[包状态] 已安装列表等待超时，改用关开强制重建连接: " + pkg);
                    forceToggleLockedComponents(pkg);
                }
                return;
            }
            writeRecoveryComponents(pkg, 0, true);
        });
    }

    /** 已安装列表已就绪后：把锁定组件写回启用列表，并按需校验/关开重建连接。 */
    private void writeRecoveryComponents(String pkg, int writeAttempt, boolean allowToggle) {
        if (getPackageState(pkg) != RepairPolicy.PKG_STATE_ENABLED) {
            return;
        }
        String setting = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (setting == null) setting = "";
        StringBuilder desired = new StringBuilder(setting);
        StringBuilder added = new StringBuilder();
        boolean hasRecoverable = false;
        String daemonStr = sp.getString("daemon", "");
        for (String raw : daemonStr.split(":")) {
            if (raw.isEmpty()) continue;
            ComponentName cn = ComponentName.unflattenFromString(raw);
            if (cn == null || !pkg.equals(cn.getPackageName())) continue;
            String normalized = cn.flattenToString();
            if (!isServiceComponentInstalled(normalized)) continue;
            hasRecoverable = true;
            if (containsServiceInSettingString(desired.toString(), cn)) continue;
            if (desired.length() > 0) desired.append(":");
            desired.append(normalized);
            if (added.length() > 0) added.append(" ");
            added.append(normalized);
        }
        if (added.length() > 0) {
            // 先更新本地快照，避免本进程 observer 把这次写入当成外部变化再走一遍清理
            tmpSettingValue = desired.toString();
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, desired.toString());
            LogUtil.log(this, "[包状态] 解冻恢复：已补回 " + added
                    + (writeAttempt > 0 ? "（重试" + writeAttempt + "）" : ""));
        } else if (writeAttempt == 0) {
            LogUtil.log(this, "[包状态] 解冻后无需补回（服务已在启用列表或没有锁定该包）: " + pkg);
        }
        refreshInstalledServiceList();
        if (added.length() > 0 && allowToggle) {
            // 实测解冻后仅写回启用列表不会触发绑定：AMS 仍保留冻结前的 crashed 连接。
            // 已安装列表就绪后做一次“移除→500ms→加回”，强制 AMS 丢弃旧连接并重新绑定。
            mHandler.postDelayed(() -> daemonExecutor.submit(() -> {
                if (getPackageState(pkg) != RepairPolicy.PKG_STATE_ENABLED) return;
                LogUtil.log(this, "[包状态] 解冻恢复：执行关开重建连接 " + pkg);
                forceToggleLockedComponents(pkg);
            }), 1200L);
        } else if (hasRecoverable && writeAttempt + 1 < 3) {
            // 校验写入是否被 AMS 的滞后已安装列表回滚；被回滚则再补一次
            mHandler.postDelayed(() -> daemonExecutor.submit(() -> {
                if (getPackageState(pkg) != RepairPolicy.PKG_STATE_ENABLED) return;
                if (!isAnyLockedComponentEnabled(pkg)) {
                    LogUtil.log(this, "[包状态] 解冻补回被系统回滚，重试: " + pkg);
                    writeRecoveryComponents(pkg, writeAttempt + 1, false);
                }
            }), 900L);
        }
    }

    /** 已安装列表长期不刷新时的兜底：移除锁定组件 → 500ms → 加回，强制 AMS 重建连接。 */
    private void forceToggleLockedComponents(String pkg) {
        if (getPackageState(pkg) != RepairPolicy.PKG_STATE_ENABLED) {
            return;
        }
        String setting = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (setting == null) setting = "";
        String without = setting;
        for (String raw : sp.getString("daemon", "").split(":")) {
            if (raw.isEmpty()) continue;
            ComponentName cn = ComponentName.unflattenFromString(raw);
            if (cn != null && pkg.equals(cn.getPackageName())) {
                without = removeComponentFromSetting(without, cn.flattenToString());
            }
        }
        if (!without.equals(setting)) {
            tmpSettingValue = without;
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        }
        writeRecoveryComponents(pkg, 0, false);
    }

    private String removeComponentFromSetting(String setting, String normalized) {
        ComponentName target = ComponentName.unflattenFromString(normalized);
        if (target == null || setting == null) return setting;
        StringBuilder sb = new StringBuilder();
        for (String entry : setting.split(":")) {
            if (entry.isEmpty()) continue;
            ComponentName cn = ComponentName.unflattenFromString(entry);
            if (target.equals(cn)) continue;
            if (sb.length() > 0) sb.append(":");
            sb.append(entry);
        }
        return sb.toString();
    }

    /** 该包在 daemon 锁定列表中的组件是否仍有一个处于启用列表（用于判断补回是否被回滚）。 */
    private boolean isAnyLockedComponentEnabled(String pkg) {
        String setting = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (setting == null) return false;
        String daemonStr = sp.getString("daemon", "");
        for (String raw : daemonStr.split(":")) {
            if (raw.isEmpty()) continue;
            ComponentName cn = ComponentName.unflattenFromString(raw);
            if (cn != null && pkg.equals(cn.getPackageName())
                    && containsServiceInSettingString(setting, cn)) {
                return true;
            }
        }
        return false;
    }

    /** 组件是否真实存在于当前包（用于区分"AMS 已安装列表滞后"与"服务已被应用移除"）。 */
    private boolean isServiceComponentInstalled(String normalized) {
        ComponentName cn = ComponentName.unflattenFromString(normalized);
        if (cn == null) return false;
        try {
            packageManager.getServiceInfo(cn, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean containsServiceInSettingString(String setting, ComponentName target) {
        if (setting == null || target == null) return false;
        for (String entry : setting.split(":")) {
            if (entry.isEmpty()) continue;
            ComponentName cn = ComponentName.unflattenFromString(entry);
            if (target.equals(cn)) return true;
        }
        return false;
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

    /** 调用者必须在 daemonExecutor 线程中执行 */
    private void doDaemonImpl(String s) {
        ProcessExitMonitor.updateHeartbeat(daemonService.this);
        // LSPosed 模式：保活与白名单全部交给模块
        if (sp.getBoolean("lsposed_mode", false)) {
            tmpSettingValue = s == null ? "" : s;
            mCrashedFixServiceName = null;
            mCrashedFixLabel = null;
            return;
        }
        final List<String> localL = l;  // 拍快照，本次执行使用同一份完整列表
        String daemonStr = sp.getString("daemon", "");
        String whitelistStr = sp.getString("whitelist_services", "");
        boolean globalWhitelistEnable = sp.getBoolean("whitelist_global_enable", false);

        String currentForegroundPkg = sp.getString("current_foreground_pkg", "");
        String foregroundAppLabel = currentForegroundPkg;
        if (!currentForegroundPkg.isEmpty()) {
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(currentForegroundPkg, PackageManager.GET_META_DATA);
                CharSequence label = appInfo.loadLabel(packageManager);
                if (label != null) {
                    foregroundAppLabel = label.toString();
                }
            } catch (PackageManager.NameNotFoundException ignored) {}
        }

        java.util.Set<String> daemonSet = new java.util.HashSet<>(java.util.Arrays.asList(daemonStr.split(":")));
        java.util.Set<String> whitelistSet = new java.util.HashSet<>(java.util.Arrays.asList(whitelistStr.split(":")));
        
        java.util.Set<String> managedServices = new java.util.HashSet<>();
        managedServices.addAll(daemonSet);
        if (globalWhitelistEnable) {
            managedServices.addAll(whitelistSet);
        }
        
        // Clean empty strings
        managedServices.remove("");
        daemonSet.remove("");
        whitelistSet.remove("");

        // Initialize current state
        java.util.Set<String> currentServices = new java.util.LinkedHashSet<>();
        if (s != null && !s.isEmpty()) {
            for (String entry : s.split(":")) {
                if (!entry.isEmpty()) currentServices.add(normalizeServiceId(entry));
            }
        }


        StringBuilder cleanedDaemon = null;
        StringBuilder cleanedWhitelist = null;
        
        StringBuilder logDaemonAdded = new StringBuilder();   // daemon 服务被开启，显示应用名称
        StringBuilder logWhitelistAdded = new StringBuilder(); // 白名单服务被开启
        StringBuilder logWhitelistRemoved = new StringBuilder(); // 白名单服务被关闭
        StringBuilder logAdded = new StringBuilder(); // 用于 Toast 崩溃判断（包含所有开启的）
        StringBuilder add1 = new StringBuilder(); // For crash fix toast label

        for (String serviceName : managedServices) {
            if (serviceName == null || !serviceName.contains("/")) {
                LogUtil.log(daemonService.this, "[保活] 跳过非法服务(无'/'): " + serviceName);
                continue;
            }
            String normalized = normalizeServiceId(serviceName);
            if (!isServiceInList(normalized, localL)) {
                String pkg = packageOfComponent(normalized);
                int pkgState = getPackageState(pkg);
                if (pkgState == RepairPolicy.PKG_STATE_DISABLED) {
                    // 冻结/禁用：绝不能删锁，也不能尝试写回启用列表。
                    // 系统会拒绝冻结服务并再次触发启用列表变更，写回只会形成"写→事件→再写"循环；
                    // 保留锁，等 ACTION_PACKAGE_CHANGED 解冻广播到达后重新执行 doDaemon 自动恢复。
                    logFrozenSkip("保活", normalized, pkg);
                    continue;
                }
                if (!localL.isEmpty()) {
                    if (pkgState == RepairPolicy.PKG_STATE_ENABLED
                            && isServiceComponentInstalled(normalized)) {
                        // 包已恢复正常且服务组件真实存在，但 AMS 的"已安装服务列表"仍滞后（解冻瞬间常见）。
                        // 此时绝不能当作已卸载删锁：保留锁并留待下一次设置/包事件重新对账。
                        if (mFrozenSkipLogAt.get(normalized) == null
                                || System.currentTimeMillis() - mFrozenSkipLogAt.get(normalized)
                                >= FROZEN_SKIP_LOG_WINDOW_MS) {
                            mFrozenSkipLogAt.put(normalized, System.currentTimeMillis());
                            LogUtil.log(daemonService.this,
                                    "[保活] 已安装列表滞后，保留锁等待刷新: " + normalized);
                        }
                        continue;
                    }
                    // 已安装列表已就绪且不含该服务，确认已卸载（或服务已被应用移除），清理并跳过保活
                    if (daemonSet.contains(serviceName)) {
                        if (cleanedDaemon == null) cleanedDaemon = new StringBuilder();
                        else cleanedDaemon.append(":");
                        cleanedDaemon.append(serviceName);
                    }
                    if (whitelistSet.contains(serviceName)) {
                        if (cleanedWhitelist == null) cleanedWhitelist = new StringBuilder();
                        else cleanedWhitelist.append(":");
                        cleanedWhitelist.append(serviceName);
                    }
                    continue;
                }
                // localL 为空（无已安装服务/异常），跳过清理但尝试保活，宁多勿少
                LogUtil.log(daemonService.this, "[保活] l 为空，跳过清理并尝试保活: " + normalized);
            }

            // Determine desired state
            boolean isLocked = daemonSet.contains(serviceName);
            boolean isWhitelisted = whitelistSet.contains(serviceName);
            boolean desiredState = false;

            if (isWhitelisted && globalWhitelistEnable) {
                String whitelist = sp.getString("whitelist_apps_" + normalized, "");
                if (whitelist.isEmpty()) {
                    desiredState = true;
                } else {
                    boolean isInWhitelist = false;
                    if (!getPackageName().equals(currentForegroundPkg)) {
                        for (String pkg : whitelist.split(",")) {
                            if (pkg.equals(currentForegroundPkg)) {
                                isInWhitelist = true;
                                break;
                            }
                        }
                    }
                    desiredState = !isInWhitelist;
                }
            } else if (isLocked) {
                desiredState = true;
            }

            // apply desired state
            if (desiredState) {
                if (!currentServices.contains(normalized)) {
                    currentServices.add(normalized);
                    logAdded.append(normalized).append(" ");

                    String appLabel = normalized;
                    int slashIdx = normalized.indexOf("/");
                    if (slashIdx > 0) {
                        try {
                            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(normalized.substring(0, slashIdx), PackageManager.GET_META_DATA);
                            CharSequence label = applicationInfo.loadLabel(packageManager);
                            if (label != null) {
                                appLabel = label.toString();
                            }
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                    }
                    
                    boolean isKeepAliveAction = false;
                    if (isLocked) {
                        if (mIsWhitelistAppSwitch && isWhitelisted) {
                            isKeepAliveAction = false; // 切换应用引发的白名单状态恢复，不算保活
                        } else {
                            isKeepAliveAction = true; // 崩溃或被强杀，且用户开启了保活
                        }
                    }

                    if (isKeepAliveAction) {
                        // daemon 服务：保活日志显示应用名称
                        logDaemonAdded.append(appLabel).append(" ");
                        LogUtil.log(daemonService.this, "[保活] 检测到服务缺失：" + appLabel + " (" + normalized + ")");
                        add1.append(appLabel).append(" ");
                    } else {
                        // 白名单服务：状态同步日志显示包名
                        logWhitelistAdded.append(normalized).append(" ");
                    }
                }
            } else {
                if (currentServices.contains(normalized)) {
                    currentServices.remove(normalized);
                    logWhitelistRemoved.append(normalized).append(" ");
                }
            }
        }

        // Cleanup stale services from SharedPreferences
        if (cleanedDaemon != null) {
            cleanupStaleServices("daemon", daemonStr, cleanedDaemon.toString());
        }
        if (cleanedWhitelist != null) {
            cleanupStaleServices("whitelist_services", whitelistStr, cleanedWhitelist.toString());
        }

        // Build new setting string
        StringBuilder newSettingSb = new StringBuilder();
        for (String cs : currentServices) {
            if (newSettingSb.length() > 0) newSettingSb.append(":");
            newSettingSb.append(cs);
        }
        String newSettingValue = newSettingSb.toString();

        if (!newSettingValue.equals(s)) {
            tmpSettingValue = newSettingValue;
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);
            // daemon 服务被开启：保活日志（显示应用名称，与旧版本保活日志保持一致）
            if (logDaemonAdded.length() > 0) {
                LogUtil.log(daemonService.this, "[保活] 已重新开启被关闭的服务：" + logDaemonAdded.toString().trim());
            }
            // 白名单服务开启/关闭：状态同步日志
            if (logWhitelistAdded.length() > 0) {
                LogUtil.log(daemonService.this, "[状态同步] 开启服务：" + logWhitelistAdded.toString().trim());
            }
            if (logWhitelistRemoved.length() > 0) {
                String logMsg = "[状态同步] 关闭服务：" + logWhitelistRemoved.toString().trim();
                if (!foregroundAppLabel.isEmpty()) {
                    logMsg += " (" + foregroundAppLabel + ")";
                }
                LogUtil.log(daemonService.this, logMsg);
            }
            
            if (sp.getBoolean("toast", true) && logAdded.length() > 0) {
                 final String crashedName = mCrashedFixServiceName;
                 boolean isCrashed = false;
                 if (crashedName != null) {
                     for (String added : logAdded.toString().split(" ")) {
                         if (added.isEmpty()) continue;
                         if (serviceNameMatches(added, crashedName)) {
                             isCrashed = true;
                             break;
                         }
                     }
                 }
                 if (isCrashed) {
                     final String crashedLabel = mCrashedFixLabel != null ? mCrashedFixLabel : "";
                     mCrashedFixServiceName = null;
                     mCrashedFixLabel = null;
                     mHandler.post(() -> Toast.makeText(daemonService.this, "保活崩溃服务 " + crashedLabel, Toast.LENGTH_SHORT).show());
                 } else {
                     final String label = add1.toString().trim();
                     if (!label.isEmpty()) {
                         mHandler.post(() -> Toast.makeText(daemonService.this, "保活" + label, Toast.LENGTH_SHORT).show());
                     }
                 }
            }
        } else {
            tmpSettingValue = s;
            if (mIsWhitelistAppSwitch) {
                mIsWhitelistAppSwitch = false;
            }
        }
        mCrashedFixServiceName = null;
        mCrashedFixLabel = null;
    }

    private void cleanupStaleServices(String key, String currentListStr, String staleListStr) {
        java.util.Set<String> staleSet = new java.util.HashSet<>();
        for (String stale : staleListStr.split(":")) {
            if (!stale.isEmpty()) staleSet.add(stale);
        }
        String[] entries = currentListStr.split(":");
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
        sp.edit().putString(key, newList.toString()).apply();
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
        if (sp.getBoolean("lsposed_mode", false)) {
            if (!mModuleModeLogged) {
                mModuleModeLogged = true;
                if (ModuleConfig.isModuleLoaded(this)) {
                    Log.d("AccMgr", "[LSPosed] 崩溃与关闭恢复交由模块处理，管理器不再主动检测/保活");
                } else {
                    LogUtil.log(this, "[LSPosed] 模块未加载，当前没有服务在守护，请检查模块激活状态");
                }
            }
            return;
        }
        if (!sp.getBoolean("crashfix", false)) return;
        crashCheckExecutor.submit(() -> checkCrashedServicesInternal(source));
    }

    private void recordUnlockDuplicate() {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date());
        String savedDate = sp.getString("unlock_duplicate_date", "");
        int count = sp.getInt("unlock_duplicate_count", 0);
        
        if (!today.equals(savedDate)) {
            count = 0;
            sp.edit().putString("unlock_duplicate_date", today).apply();
        }
        
        count++;
        sp.edit().putInt("unlock_duplicate_count", count).apply();
        LogUtil.log(daemonService.this, "[解锁检测] 记录到一次后台重复触发，今日累计: " + count + " 次");
        
        if (count >= 3) {
            LogUtil.log(daemonService.this, "[解锁检测] 今日累计达到3次，触发提示");
            sp.edit().putBoolean("unlock_duplicate_detected", true).apply();
        }
    }

    /** 在 crashCheckExecutor 线程中执行：运行 dumpsys，若发现崩溃则回调 daemonExecutor 修复 */
    //region debug-point crash-detection-internal
    private void checkCrashedServicesInternal(String source) {
        if (sp.getBoolean("lsposed_mode", false)) {
            return;
        }
        // ── 解锁检测重复触发判断 ──
        long now = System.currentTimeMillis();
        boolean isBackground = !MainActivity.sIsForeground;
        if ("解锁".equals(source)) {
            mLastUnlockBroadcastTime = now;
            if (isBackground && now - mLastAccessibilityUnlockTime <= 1000) {
                recordUnlockDuplicate();
                return;
            }
        } else if ("解锁(无障碍检测)".equals(source)) {
            mLastAccessibilityUnlockTime = now;
            if (isBackground && now - mLastUnlockBroadcastTime <= 1000) {
                recordUnlockDuplicate();
                return;
            }
        }

        // ── 解锁检测短时间防抖（15秒内重复解锁不重复触发 dumpsys） ──
        if ("解锁".equals(source) || "解锁(无障碍检测)".equals(source)) {
            if (now - mLastDumpsysTime < 15_000L) {
                return;
            }
        }

        String daemonList = sp.getString("daemon", "");
        boolean globalWhitelistEnable = sp.getBoolean("whitelist_global_enable", false);
        String whitelistStr = globalWhitelistEnable ? sp.getString("whitelist_services", "") : "";
        if (daemonList.length() == 0 && whitelistStr.length() == 0) {
            return;
        }
        // 先检查 DUMP 权限
        if (!ShellUtil.hasDumpPermission(this)) {
            LogUtil.log(daemonService.this, "[崩溃检测] 触发来源：" + source + "，未授予 DUMP 权限，跳过检测（请通过 ADB 授予：adb shell pm grant " + getPackageName() + " android.permission.DUMP）");
            return;
        }

        try {
            mLastDumpsysTime = System.currentTimeMillis();
            Process p = Runtime.getRuntime().exec("dumpsys accessibility");

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder allOutput = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                allOutput.append(line).append("\n");
            }
            reader.close();

            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LogUtil.log(daemonService.this, "[崩溃检测] 触发来源：" + source + "，dumpsys 超时(10s)，强制终止");
                return;
            }

            String fullOutput = allOutput.toString();

            Matcher rawLineMatcher = Pattern.compile("Crashed services:.*").matcher(fullOutput);
            String rawCrashedLine;
            if (rawLineMatcher.find()) {
                rawCrashedLine = rawLineMatcher.group().trim();
            } else {
                rawCrashedLine = "Crashed services: (未找到此行)";
            }
            LogUtil.log(daemonService.this, "[崩溃检测] 触发来源：" + source + "，返回 " + rawCrashedLine);

            List<String> crashedServicesList = new ArrayList<>();
            Matcher serviceMatcher = Pattern.compile("\\{([^{}]+)\\}").matcher(rawCrashedLine);
            while (serviceMatcher.find()) {
                String svc = serviceMatcher.group(1).trim();
                if (!svc.isEmpty()) {
                    crashedServicesList.add(svc);
                }
            }

            if (crashedServicesList.isEmpty()) {
                return;
            }

            java.util.Set<String> trackedSet = new java.util.HashSet<>(java.util.Arrays.asList(daemonList.split(":")));
            trackedSet.addAll(java.util.Arrays.asList(whitelistStr.split(":")));
            trackedSet.remove("");

            for (String cs : crashedServicesList) {
                for (String tracked : trackedSet) {
                    if (serviceNameMatches(cs, tracked)) {
                        String crashComponent = normalizeServiceId(cs);
                        String crashPkg = packageOfComponent(crashComponent);
                        if (getPackageState(crashPkg) == RepairPolicy.PKG_STATE_DISABLED) {
                            // dumpsys 的 Crashed 标记可能在"崩溃后立刻冻结"的瞬间残留（AMS 解绑延迟）。
                            // 冻结目标绝不能进入关开/强杀修复，否则会形成 8 秒冷却的写入循环。
                            logFrozenSkip("崩溃修复", crashComponent, crashPkg);
                            break;
                        }
                        if (isInUnfrozenGuard(crashPkg)) {
                            // 解冻后 60s 内 dumpsys 仍可能残留 Crashed 假象，直接跳过修复
                            LogUtil.log(daemonService.this,
                                    "[包状态] 解冻保守期内跳过崩溃修复: " + crashComponent);
                            break;
                        }
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
        } catch (Exception e) {
            LogUtil.log(daemonService.this, "[崩溃检测] 触发来源：" + source + "，异常: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }
    //endregion

    /** 在 daemonExecutor 线程中执行：处理首次检测到的崩溃 */
    private void handleFixDetected(String cs) {
        String crashPkg = packageOfComponent(normalizeServiceId(cs));
        if (getPackageState(crashPkg) == RepairPolicy.PKG_STATE_DISABLED) {
            // 双保险：适配器/复查/手动路径都可能直接调用修复入口，冻结目标一律跳过
            logFrozenSkip("崩溃修复", normalizeServiceId(cs), crashPkg);
            return;
        }
        if (isInUnfrozenGuard(crashPkg)) {
            LogUtil.log(this, "[包状态] 解冻保守期内跳过崩溃修复: " + normalizeServiceId(cs));
            return;
        }
        Long lastFix = mLastFixTime.get(cs);
        if (lastFix != null && System.currentTimeMillis() - lastFix < 8000) {
            return;
        }
        String appLabel = cs;
        int slashIdx = cs.indexOf('/');
        if (slashIdx > 0) {
            try {
                android.content.pm.PackageManager packageManager = getPackageManager();
                android.content.pm.ApplicationInfo applicationInfo = packageManager.getApplicationInfo(cs.substring(0, slashIdx), android.content.pm.PackageManager.GET_META_DATA);
                CharSequence label = applicationInfo.loadLabel(packageManager);
                if (label != null) {
                    appLabel = label.toString();
                }
            } catch (Exception ignored) {
            }
        }
        LogUtil.log(daemonService.this, "[崩溃检测] 检测到保活服务崩溃：" + appLabel + " (" + cs + ")");
        mFixRetryRemaining = 2;
        mLastFixTime.put(cs, System.currentTimeMillis());
        fixCrashedService(cs, 0);
    }

    /** 在 daemonExecutor 线程中执行：处理修复后复查的崩溃（重试） */
    private void handleFixRetry(String cs) {
        if (mFixRetryRemaining <= 0) {
            LogUtil.log(daemonService.this, "[崩溃修复] 修复后复查仍崩溃，已达最大重试次数，放弃：" + cs);
            mHandler.post(() -> Toast.makeText(daemonService.this, "保活崩溃服务失败", Toast.LENGTH_SHORT).show());
            return;
        }
        int currentRetry = 3 - mFixRetryRemaining;
        mFixRetryRemaining--;
        mLastFixTime.put(cs, System.currentTimeMillis());
        fixCrashedService(cs, currentRetry);
    }

    // ════════════════════════════════════════════════════════════════
    //  崩溃修复（仅在 daemonExecutor 线程中执行）
    // ════════════════════════════════════════════════════════════════
    private void fixCrashedService(String serviceName, int retryCount) {
        if (sp.getBoolean("lsposed_mode", false)) {
            LogUtil.log(this, "[崩溃修复] LSPosed 模块模式，跳过应用层修复: " + serviceName);
            mIsFixing = false;
            return;
        }
        String guardPkg = packageOfComponent(normalizeServiceId(serviceName));
        if (getPackageState(guardPkg) == RepairPolicy.PKG_STATE_DISABLED) {
            // 冻结目标不修复：保留锁，等解冻广播触发保活补回
            logFrozenSkip("崩溃修复", normalizeServiceId(serviceName), guardPkg);
            mIsFixing = false;
            return;
        }
        if (isInUnfrozenGuard(guardPkg)) {
            // 解冻后保守期内不做关开/强杀，避免打断系统自然绑定
            LogUtil.log(this, "[包状态] 解冻保守期内跳过崩溃修复: " + normalizeServiceId(serviceName));
            mIsFixing = false;
            return;
        }
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

        boolean useForceStop = sp.getBoolean("fixmode", false);
        boolean isDegraded = false;
        if (pkgName.equals(getPackageName())) {
            useForceStop = false;
        } else if (useForceStop) {
            String disabledStr = sp.getString("fixmode_disabled_services", "");
            if (disabledStr.contains(serviceName)) {
                useForceStop = false;
            } else if (!ShellUtil.hasAnyPermission()) {
                useForceStop = false;
                isDegraded = true;
            }
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

        String logPrefix;
        if (retryCount > 0) {
            logPrefix = "[崩溃修复] 修复后复查仍崩溃，进行重试(" + retryCount + "/2) ->";
        } else {
            logPrefix = "[崩溃修复]";
        }

        if (useForceStop) {
            LogUtil.log(daemonService.this, logPrefix + " 强杀进程：" + packageLabel + " (" + pkgName + ")");
            boolean forceStopSuccess = false;
            try {
                Process forceStop = ShellUtil.exec();
                DataOutputStream fos = new DataOutputStream(forceStop.getOutputStream());
                fos.writeBytes("am force-stop " + pkgName + "\n");
                fos.writeBytes("exit\n");
                fos.flush();
                fos.close();
                
                int exitCode = forceStop.waitFor();
                
                java.io.BufferedReader errorReader = new java.io.BufferedReader(new java.io.InputStreamReader(forceStop.getErrorStream()));
                StringBuilder errorOutput = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorOutput.append(line).append(" ");
                }
                errorReader.close();

                if (exitCode == 0 && errorOutput.length() == 0) {
                    forceStopSuccess = true;
                } else {
                    LogUtil.log(daemonService.this, logPrefix + " 强杀命令执行失败。ExitCode: " + exitCode + ", Error: " + errorOutput.toString().trim());
                }
            } catch (Exception e) {
                LogUtil.log(daemonService.this, logPrefix + " 强杀过程发生异常: " + e.getMessage());
            }

            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            
            if (!forceStopSuccess) {
                disableServiceOnly(serviceName, logPrefix, true, packageLabel, pkgName);
            }
        } else {
            disableServiceOnly(serviceName, logPrefix, isDegraded, packageLabel, pkgName);
        }

        // 不再显示崩溃重启通知

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

    private void disableServiceOnly(String serviceName, String logPrefix, boolean isDegraded, String packageLabel, String pkgName) {
        if (isDegraded) {
            LogUtil.log(daemonService.this, logPrefix + " (强杀失效降级) 仅关闭服务：" + packageLabel + " (" + pkgName + ")");
        } else {
            LogUtil.log(daemonService.this, logPrefix + " 仅关闭服务：" + packageLabel + " (" + pkgName + ")");
        }
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
        sInstance = this;
        ProcessExitMonitor.checkProcessExit(this);
        ProcessExitMonitor.updateHeartbeat(this);
        Log.d("AM_DIAG", "[daemonService] onCreate 开始, pid=" + android.os.Process.myPid());
        mHandler = new Handler();
        sp = getSharedPreferences("data", 0);
        packageManager = getPackageManager();
        if (sp.getBoolean("lsposed_mode", false)) {
            ModuleConfig.startLogSync(this);
        }
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, notification.build());
            }
        } catch (SecurityException se) {
            LogUtil.log(this, "[保活服务] 特殊前台服务权限受限，尝试常规启动: " + se.getMessage());
            try {
                startForeground(1, notification.build());
            } catch (Throwable t) {
                LogUtil.log(this, "[保活服务] 前台服务启动异常: " + t.getMessage());
            }
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

        if (MainActivity.sIsForeground && sp.getBoolean("toast", true)) {
            Toast.makeText(daemonService.this, "启动保活", Toast.LENGTH_SHORT).show();
        }

        // 注册 ContentObserver 和 BroadcastReceiver
        mContentOb = new SettingsValueChangeContentObserver();
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, mContentOb);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(myReceiver, new IntentFilter("android.intent.action.USER_PRESENT"), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(myReceiver, new IntentFilter("android.intent.action.USER_PRESENT"));
        }

        // 阶段一测试：监听包启用状态变化（冻结/解冻），只写日志验证广播可达性。
        // ACTION_PACKAGE_* 广播带 data=package:xxx，动态接收器必须声明 package 数据 scheme，否则匹配不到。
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mPackageStateReceiver, packageFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mPackageStateReceiver, packageFilter);
        }

        // 注册前台应用变化监听器，应用切换时直接在进程内触发白名单状态同步，不启动新服务
        mForegroundPkgListener = (prefs, key) -> {
            if ("current_foreground_pkg".equals(key)
                    && sp.getBoolean("whitelist_global_enable", false)
                    && !sp.getBoolean("lsposed_mode", false)) {
                daemonExecutor.submit(() -> {
                    String setting = Settings.Secure.getString(
                            getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    if (setting == null) setting = "";
                    mIsWhitelistAppSwitch = true;
                    doDaemon(setting);
                });
            }
        };
        sp.registerOnSharedPreferenceChangeListener(mForegroundPkgListener);

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
        sInstance = null;
        super.onDestroy();
        cancelPostFixCheck();
        TimerReceiver.cancel(this, "服务销毁");
        try { unregisterReceiver(myReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(mPackageStateReceiver); } catch (Exception ignored) { }
        if (mContentOb != null) {
            try { getContentResolver().unregisterContentObserver(mContentOb); } catch (Exception ignored) { }
        }
        if (mForegroundPkgListener != null) {
            sp.unregisterOnSharedPreferenceChangeListener(mForegroundPkgListener);
        }
        
        // 关键修复：防止停止保活时导致内存和线程泄漏
        if (daemonExecutor != null && !daemonExecutor.isShutdown()) {
            daemonExecutor.shutdownNow();
        }
        if (crashCheckExecutor != null && !crashCheckExecutor.isShutdown()) {
            crashCheckExecutor.shutdownNow();
        }
        
        Toast.makeText(daemonService.this, "停止保活", Toast.LENGTH_SHORT).show();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "UPDATE_NOTIFY".equals(intent.getAction())) {
            updateNotification();
            return START_STICKY;
        }

        final String alarmSource = intent != null ? intent.getStringExtra("source") : null;

        // 保活部分提交到 daemonExecutor 串行执行
        daemonExecutor.submit(() -> {
            String setting = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (setting == null) setting = "";
            refreshInstalledServiceList();
            doDaemon(setting);
        });

        // 崩溃检测部分
        if ("Alarm".equals(alarmSource)) {
            checkCrashedServices("Alarm");
        } else if ("AccessibilityService".equals(alarmSource)) {
            checkCrashedServices("解锁(无障碍检测)");
        } else if ("NotificationRemoved".equals(alarmSource)) {
            String sourceLabel = intent.getStringExtra("source_label");
            checkCrashedServices("通知消失(" + (sourceLabel != null ? sourceLabel : "未知") + ")");
        } else if (!mFirstCommandAfterCreate) {
            checkCrashedServices("服务刷新");
        }
        mFirstCommandAfterCreate = false;
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        LogUtil.log(this, "[系统监控] 管理器多任务卡片被划掉");
        ProcessExitMonitor.updateHeartbeat(this);
    }
}
