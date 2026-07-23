package com.accessibilitymanager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.ArrayList;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.view.ViewGroup;
import rikka.shizuku.Shizuku;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences sp;
    private View rootView;

    // 子项视图引用
    private MaterialSwitch switchBoot, switchToast, switchUserOnly, switchHide, switchDelayDaemon;
    private MaterialSwitch switchCrashFix, switchUnlockCrashCheck, switchFixMode, switchGlobalCooldown;
    private MaterialSwitch switchPeriodicCheck, switchWakeIdle, switchIgnoreSystemCrash;
    private MaterialSwitch switchAutoUpdate;
    private LinearLayout crashDependentLayout, fixmodeServicesLayout, periodicSubLayout, globalCooldownTimeLayout,
            globalCooldownEnableLayout, globalCooldownSubLayout;
    private View fixmodeServicesBtn;
    private View fixmodeWrapper, globalCooldownWrapper, periodicWrapper;
    private TextView intervalLabel, notifyCustomBtn, crashTutorialBtn, aboutBtn, checkUpdateBtn,
            globalCooldownTimeLabel;
    private View btnThemeColor;
    private TextView tvThemeDesc;

    // Shizuku 权限状态
    private boolean mPendingCrashFixRequest = false;
    private boolean mPendingFixModeRequest = false;
    private boolean night;

    private final Shizuku.OnRequestPermissionResultListener mShizukuListener = (requestCode, grantResult) -> {
        LogUtil.log(this, "[权限] SettingsActivity Shizuku回调, grantResult=" + grantResult
                + ", pendingCrashFix=" + mPendingCrashFixRequest
                + ", pendingFixMode=" + mPendingFixModeRequest);
        if (mPendingCrashFixRequest) {
            mPendingCrashFixRequest = false;
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                // 通过 Shizuku 授予权限，等待命令执行完毕再启用崩溃修复
                try {
                    Process p = Shizuku.newProcess(
                            new String[] { "sh" }, null, null);
                    java.io.OutputStream out = p.getOutputStream();
                    out.write(("pm grant " + getPackageName()
                            + " android.permission.DUMP\nexit\n").getBytes());
                    out.flush();
                    out.close();
                    p.waitFor();
                    if (p.exitValue() == 0) {
                        Toast.makeText(SettingsActivity.this, "成功激活", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    LogUtil.log(this, "[权限] Shizuku 授予 DUMP 失败: " + e.getMessage());
                }
                ShellUtil.reset();
                enableCrashFix(false);
            } else {
                Toast.makeText(this, "获取Shizuku权限失败", Toast.LENGTH_SHORT).show();
            }
        }
        if (mPendingFixModeRequest) {
            mPendingFixModeRequest = false;
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                LogUtil.log(this, "[权限] Shizuku 已授权，启用强杀模式");
                enableFixMode();
            } else {
                ShellUtil.reset();
                LogUtil.log(this, "[权限] Shizuku 授权被拒绝");
                Toast.makeText(this, "获取Shizuku权限失败，强杀模式未开启", Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtils.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        int nightMode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        night = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        sp = getSharedPreferences("data", 0);
        rootView = findViewById(android.R.id.content);

        initToolbar();
        initViews();
        loadValues();
        setupListeners();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Shizuku.addRequestPermissionResultListener(mShizukuListener);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Shizuku.removeRequestPermissionResultListener(mShizukuListener);
        }
    }

    // ========== 初始化 ==========

    private void initToolbar() {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            toolbar.setNavigationIcon(R.drawable.ic_back_arrow);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        toolbar.setPaddingRelative(
                toolbar.getPaddingStart(),
                toolbar.getPaddingTop(),
                (int) (8 * getResources().getDisplayMetrics().density + 0.5f),
                toolbar.getPaddingBottom());

        toolbar.inflateMenu(R.menu.settings_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.viewlog) {
                startActivity(new Intent(this, LogActivity.class));
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                return true;
            }
            return false;
        });

        if (!night) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    controller.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }
    }

    private void initViews() {
        switchBoot = findViewById(R.id.boot);
        switchToast = findViewById(R.id.toast);
        switchUserOnly = findViewById(R.id.useronly);
        switchHide = findViewById(R.id.hide);
        switchDelayDaemon = findViewById(R.id.delay_daemon);
        switchCrashFix = findViewById(R.id.crashfix);
        switchGlobalCooldown = findViewById(R.id.global_cooldown_enable);
        globalCooldownEnableLayout = findViewById(R.id.global_cooldown_enable_layout);
        globalCooldownSubLayout = findViewById(R.id.global_cooldown_sub_layout);
        globalCooldownTimeLayout = findViewById(R.id.global_cooldown_time_layout);
        globalCooldownTimeLabel = findViewById(R.id.global_cooldown_time);
        switchUnlockCrashCheck = findViewById(R.id.unlock_crash_check);
        switchFixMode = findViewById(R.id.fixmode);
        switchPeriodicCheck = findViewById(R.id.periodic_check);
        switchWakeIdle = findViewById(R.id.periodic_check_wake_idle);
        switchIgnoreSystemCrash = findViewById(R.id.ignore_system_crash);
        switchAutoUpdate = findViewById(R.id.auto_update);
        crashDependentLayout = findViewById(R.id.crash_dependent_layout);
        fixmodeServicesLayout = findViewById(R.id.fixmode_services_layout);
        periodicSubLayout = findViewById(R.id.periodic_sub_layout);
        fixmodeServicesBtn = findViewById(R.id.fixmode_services_btn);
        intervalLabel = findViewById(R.id.periodic_interval_label);
        notifyCustomBtn = findViewById(R.id.notify_custom_btn);
        crashTutorialBtn = findViewById(R.id.crash_tutorial_btn);
        aboutBtn = findViewById(R.id.about_btn);
        checkUpdateBtn = findViewById(R.id.check_update_btn);
        btnThemeColor = findViewById(R.id.btn_theme_color);
        tvThemeDesc = findViewById(R.id.tv_theme_desc);
        fixmodeWrapper = findViewById(R.id.fixmode_wrapper);
        globalCooldownWrapper = findViewById(R.id.global_cooldown_wrapper);
        periodicWrapper = findViewById(R.id.periodic_wrapper);
    }

    private void loadValues() {
        switchBoot.setChecked(sp.getBoolean("boot", true));
        switchAutoUpdate.setChecked(sp.getBoolean("auto_update", true));
        switchToast.setChecked(sp.getBoolean("toast", true));
        switchUserOnly.setChecked(sp.getBoolean("useronly", false));
        switchHide.setChecked(sp.getBoolean("hide", false));
        switchDelayDaemon.setChecked(sp.getBoolean("delay_daemon", false));
        switchCrashFix.setChecked(sp.getBoolean("crashfix", false));
        boolean globalCooldownEnabled = sp.getBoolean("global_cooldown_enable", false);
        switchGlobalCooldown.setChecked(globalCooldownEnabled);
        globalCooldownSubLayout.setVisibility(globalCooldownEnabled ? View.VISIBLE : View.GONE);
        globalCooldownWrapper.setBackgroundResource(globalCooldownEnabled ? R.drawable.bg_nested_setting : 0);
        globalCooldownTimeLabel.setText(sp.getInt("global_cooldown_time_minutes", 15) + "分钟");
        switchUnlockCrashCheck.setChecked(sp.getBoolean("unlock_crash_check", false));
        switchFixMode.setChecked(sp.getBoolean("fixmode", false));
        switchPeriodicCheck.setChecked(sp.getBoolean("periodic_check", false));
        switchWakeIdle.setChecked(sp.getBoolean("periodic_check_wake_idle", true));
        switchIgnoreSystemCrash.setChecked(sp.getBoolean("ignore_system_crash_trigger", true));
        intervalLabel.setText(sp.getInt("periodic_check_interval", 10) + "分钟");

        SharedPreferences mainSp = getSharedPreferences("Main", Context.MODE_PRIVATE);
        String theme = mainSp.getString(ThemeUtils.PREF_THEME, ThemeUtils.THEME_BLUE);
        switch (theme) {
            case ThemeUtils.THEME_GREEN:
                tvThemeDesc.setText("护眼绿");
                break;
            case ThemeUtils.THEME_PURPLE:
                tvThemeDesc.setText("优雅紫");
                break;
            case ThemeUtils.THEME_BLUE:
                tvThemeDesc.setText("经典蓝");
                break;
            case ThemeUtils.THEME_DYNAMIC:
            default:
                tvThemeDesc.setText("跟随系统 (莫奈取色)");
                break;
        }

        refreshCrashFixDependent();
    }

    // ========== 事件绑定 ==========

    private void setupListeners() {
        btnThemeColor.setOnClickListener(v -> showThemeDialog());

        switchBoot.setOnCheckedChangeListener((btn, checked) -> sp.edit().putBoolean("boot", checked).apply());

        switchToast.setOnCheckedChangeListener((btn, checked) -> sp.edit().putBoolean("toast", checked).apply());

        switchUserOnly.setOnCheckedChangeListener((btn, checked) -> sp.edit().putBoolean("useronly", checked).apply());

        switchHide.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("hide", checked).apply();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                java.util.List<android.app.ActivityManager.AppTask> tasks = ((android.app.ActivityManager) getSystemService(
                        Context.ACTIVITY_SERVICE))
                        .getAppTasks();
                if (!tasks.isEmpty())
                    tasks.get(0).setExcludeFromRecents(checked);
            }
        });

        switchDelayDaemon
                .setOnCheckedChangeListener((btn, checked) -> sp.edit().putBoolean("delay_daemon", checked).apply());

        // 崩溃服务检测总开关
        final MaterialSwitch crashFixRef = switchCrashFix;
        final android.widget.CompoundButton.OnCheckedChangeListener[] crashFixListenerHolder = new android.widget.CompoundButton.OnCheckedChangeListener[1];
        crashFixListenerHolder[0] = (btn, checked) -> {
            if (checked) {
                if (!ShellUtil.hasDumpPermission(SettingsActivity.this)) {
                    crashFixRef.setOnCheckedChangeListener(null);
                    crashFixRef.setChecked(false);
                    crashFixRef.setOnCheckedChangeListener(crashFixListenerHolder[0]);
                    showDumpPermissionDialog();
                    return;
                }
            }
            sp.edit().putBoolean("crashfix", checked)
                    .putBoolean("crashfix_auto_disabled", false).apply();
            if (checked) {
                TimerReceiver.scheduleNext(SettingsActivity.this);
            } else {
                TimerReceiver.cancel(SettingsActivity.this, "崩溃修复已关闭");
            }
            refreshCrashFixDependent();
        };
        switchCrashFix.setOnCheckedChangeListener(crashFixListenerHolder[0]);

        switchGlobalCooldown.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("global_cooldown_enable", checked).apply();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                android.transition.TransitionManager.beginDelayedTransition((android.view.ViewGroup) rootView);
            }
            globalCooldownSubLayout.setVisibility(checked ? View.VISIBLE : View.GONE);
            globalCooldownWrapper.setBackgroundResource(checked ? R.drawable.bg_nested_setting : 0);
        });

        globalCooldownEnableLayout.setOnClickListener(v -> switchGlobalCooldown.toggle());
        globalCooldownTimeLabel.setOnClickListener(v -> showCooldownTimeDialog());

        // 解锁检测
        final MaterialSwitch unlockCrashCheckRef = switchUnlockCrashCheck;
        final android.widget.CompoundButton.OnCheckedChangeListener[] unlockListenerHolder = new android.widget.CompoundButton.OnCheckedChangeListener[1];
        unlockListenerHolder[0] = (btn, checked) -> {
            if (checked && !sp.getBoolean("unlock_crash_dialog_dismissed", false)) {
                String ownServiceId = new ComponentName(SettingsActivity.this,
                        MyAccessibilityService.class).flattenToString();
                String sv = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (sv == null)
                    sv = "";
                String daemon = sp.getString("daemon", "");
                if (isServiceEnabled(ownServiceId, sv) && containsService(daemon, ownServiceId)) {
                    sp.edit().putBoolean("unlock_crash_check", true).apply();
                    return;
                }

                final android.app.Dialog unlockDialog = new android.app.Dialog(this);
                unlockDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                View dv = getLayoutInflater().inflate(R.layout.dialog_unlock_check, null);
                unlockDialog.setContentView(dv);
                android.view.Window w = unlockDialog.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.TRANSPARENT));
                    android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                    int marginPx = (int) (16 * dm.density + 0.5f);
                    android.view.WindowManager.LayoutParams lp = w.getAttributes();
                    lp.width = dm.widthPixels - marginPx * 2;
                    lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
                    w.setAttributes(lp);
                }

                // 构建带粗体的消息
                String rawMsg = "由于部分系统限制可能导致无法进行解锁检测，请进行以下操作查看是否能正常接收解锁广播:\n\n"
                        + "回到主界面后进行锁屏和解锁操作，打开管理器并点击日志，查看是否有\"收到USER_PRESENT广播\"的日志\n\n"
                        + "如果有则不需要额外操作，否则请使用以下方案其中之一：\n"
                        + "① 开启无障碍管理器的无障碍服务\n"
                        + "② 开启无障碍管理器的自启动权限\n\n"
                        + "现在是否要开启并保活管理器的无障碍服务？";
                SpannableString msg = new SpannableString(rawMsg);
                int idx1 = rawMsg.indexOf("回到主界面");
                int idx2 = rawMsg.indexOf("锁屏和解锁");
                int idx3 = rawMsg.indexOf("收到USER_PRESENT广播");
                if (idx1 >= 0)
                    msg.setSpan(new StyleSpan(Typeface.BOLD), idx1, idx1 + 5,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (idx2 >= 0)
                    msg.setSpan(new StyleSpan(Typeface.BOLD), idx2, idx2 + 5,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (idx3 >= 0)
                    msg.setSpan(new StyleSpan(Typeface.BOLD), idx3, idx3 + 17,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                ((TextView) dv.findViewById(R.id.unlock_msg)).setText(msg);

                dv.findViewById(R.id.unlock_btn_negative).setOnClickListener(v -> {
                    sp.edit().putBoolean("unlock_crash_check", true).apply();
                    unlockCrashCheckRef.setOnCheckedChangeListener(null);
                    unlockCrashCheckRef.setChecked(true);
                    unlockCrashCheckRef.setOnCheckedChangeListener(unlockListenerHolder[0]);
                    unlockDialog.dismiss();
                });
                dv.findViewById(R.id.unlock_btn_neutral).setOnClickListener(v -> {
                    sp.edit().putBoolean("unlock_crash_dialog_dismissed", true).apply();
                    sp.edit().putBoolean("unlock_crash_check", true).apply();
                    unlockCrashCheckRef.setOnCheckedChangeListener(null);
                    unlockCrashCheckRef.setChecked(true);
                    unlockCrashCheckRef.setOnCheckedChangeListener(unlockListenerHolder[0]);
                    unlockDialog.dismiss();
                });
                dv.findViewById(R.id.unlock_btn_positive).setOnClickListener(v -> {
                    sp.edit().putBoolean("unlock_crash_check", true).apply();
                    unlockCrashCheckRef.setOnCheckedChangeListener(null);
                    unlockCrashCheckRef.setChecked(true);
                    unlockCrashCheckRef.setOnCheckedChangeListener(unlockListenerHolder[0]);
                    unlockDialog.dismiss();
                    if (checkWriteSecurePermission()) {
                        Toast.makeText(SettingsActivity.this,
                                "需要授予安全设置写入权限才能开启无障碍服务", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String s = Settings.Secure.getString(getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    if (s == null)
                        s = "";
                    if (!isServiceEnabled(ownServiceId, s)) {
                        Settings.Secure.putString(getContentResolver(),
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                ownServiceId + ":" + s);
                    }
                    if (!containsService(daemon, ownServiceId)) {
                        String newDaemon = ownServiceId + ":" + daemon;
                        sp.edit().putString("daemon", newDaemon).apply();
                        startDaemonService();
                    }
                });

                unlockDialog.show();
                return;
            }
            sp.edit().putBoolean("unlock_crash_check", checked).apply();
        };
        switchUnlockCrashCheck.setOnCheckedChangeListener(unlockListenerHolder[0]);

        // 重启时强杀对应APP（需要 Root/Shizuku 权限）
        final MaterialSwitch fixModeRef = switchFixMode;
        final android.widget.CompoundButton.OnCheckedChangeListener[] fixModeListenerHolder = new android.widget.CompoundButton.OnCheckedChangeListener[1];
        fixModeListenerHolder[0] = (btn, checked) -> {
            if (checked) {
                ShellUtil.reset();
                ShellUtil.getPermissionState();
                if (!ShellUtil.hasAnyPermission()) {
                    fixModeRef.setOnCheckedChangeListener(null);
                    fixModeRef.setChecked(false);
                    fixModeRef.setOnCheckedChangeListener(fixModeListenerHolder[0]);
                    showFixModePermissionDialog();
                    return;
                }
            }
            sp.edit().putBoolean("fixmode", checked).apply();
            refreshCrashFixDependent();
        };
        switchFixMode.setOnCheckedChangeListener(fixModeListenerHolder[0]);

        fixmodeServicesLayout.setOnClickListener(v -> showFixmodeServicesDialog());
        fixmodeServicesBtn.setOnClickListener(v -> showFixmodeServicesDialog());

        switchPeriodicCheck.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("periodic_check", checked).apply();
            if (checked) {
                TimerReceiver.scheduleNext(SettingsActivity.this);
            } else {
                TimerReceiver.cancel(SettingsActivity.this, "定时检测已关闭");
            }
            refreshCrashFixDependent();
            intervalLabel.setEnabled(checked);
        });

        switchWakeIdle.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("periodic_check_wake_idle", checked).apply();
            if (switchPeriodicCheck.isChecked()) {
                TimerReceiver.scheduleNext(SettingsActivity.this);
            }
        });

        switchIgnoreSystemCrash.setOnCheckedChangeListener(
                (btn, checked) -> sp.edit().putBoolean("ignore_system_crash_trigger", checked).apply());

        intervalLabel.setOnClickListener(v -> {
            if (!switchPeriodicCheck.isChecked())
                return;
            showIntervalDialog(() -> intervalLabel.setText(sp.getInt("periodic_check_interval", 10) + "分钟"));
        });

        // 自定义通知栏文字 - 整行触发
        ((View) notifyCustomBtn.getParent()).setOnClickListener(v -> showNotifyCustomDialog());
        notifyCustomBtn.setOnClickListener(v -> showNotifyCustomDialog());

        crashTutorialBtn.setOnClickListener(v -> showCrashTutorialDialog());

        switchAutoUpdate
                .setOnCheckedChangeListener((btn, checked) -> sp.edit().putBoolean("auto_update", checked).apply());

        // 检测更新 - 整行触发
        ((View) checkUpdateBtn.getParent()).setOnClickListener(v -> checkForUpdate());
        checkUpdateBtn.setOnClickListener(v -> checkForUpdate());

        // 关于 - 整行触发
        ((View) aboutBtn.getParent()).setOnClickListener(v -> showAboutDialog());
        aboutBtn.setOnClickListener(v -> showAboutDialog());
    }

    // ========== 崩溃修复权限流程 ==========

    private void requestCrashFixPermission() {
        LogUtil.log(this, "[权限] 请求权限（来自崩溃修复开关）");
        ShellUtil.reset();
        if (ShellUtil.hasAnyPermission()) {
            LogUtil.log(this, "[权限] 已拥有权限，直接启用崩溃修复");
            enableCrashFix();
            return;
        }
        showNoPermissionDialog(true);
    }

    private void enableCrashFix() {
        enableCrashFix(true);
    }

    private void enableCrashFix(boolean showToast) {
        LogUtil.log(this, "[权限] 启用崩溃修复");
        sp.edit().putBoolean("crashfix", true)
                .putBoolean("crashfix_auto_disabled", false).apply();
        switchCrashFix.setChecked(true);
        refreshCrashFixDependent();
        boolean hasDump = ShellUtil.hasDumpPermission(this);
        boolean hasShell = ShellUtil.hasAnyPermission();
        StringBuilder permInfo = new StringBuilder();
        if (hasDump)
            permInfo.append("DUMP");
        if (hasDump && hasShell)
            permInfo.append("+");
        if (hasShell)
            permInfo.append(ShellUtil.hasRoot() ? "Root" : "Shizuku");
        LogUtil.log(this, "[权限] 当前权限: " + permInfo.toString());
        if (showToast) {
            Toast.makeText(this, "已获取" + permInfo.toString() + "权限，崩溃检测已开启", Toast.LENGTH_SHORT).show();
        }
        String daemon = sp.getString("daemon", "");
        if (!daemon.isEmpty()) {
            startDaemonService();
        }
    }

    private void showNoPermissionDialog(boolean closeCrashFixOnCancel) {
        boolean shizukuRunning = ShellUtil.isShizukuRunning();
        LogUtil.log(this, "[权限] 显示权限不足对话框 shizukuRunning=" + shizukuRunning);
        StringBuilder message = new StringBuilder();
        message.append("崩溃修复功能需要root或Shizuku权限。\n\n");
        if (shizukuRunning) {
            message.append("已检测到Shizuku正在运行，请授权本应用使用Shizuku。");
        } else {
            message.append("当前未检测到任何权限。\n请安装Shizuku并授权，或获取root权限后重试。");
        }

        final android.app.Dialog permDialog = new android.app.Dialog(this);
        permDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        View dv = getLayoutInflater().inflate(R.layout.dialog_permission, null);
        permDialog.setContentView(dv);
        android.view.Window w = permDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int marginPx = (int) (16 * dm.density + 0.5f);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = dm.widthPixels - marginPx * 2;
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }

        ((TextView) dv.findViewById(R.id.perm_msg)).setText(message.toString());

        if (shizukuRunning) {
            dv.findViewById(R.id.perm_btn_positive).setOnClickListener(v -> {
                LogUtil.log(SettingsActivity.this, "[权限] 对话框中点击申请Shizuku权限");
                mPendingCrashFixRequest = true;
                try {
                    Shizuku.requestPermission(0);
                } catch (Exception e) {
                    LogUtil.log(SettingsActivity.this,
                            "[权限] 对话框申请异常: " + e.getClass().getSimpleName());
                    mPendingCrashFixRequest = false;
                    Toast.makeText(this, "Shizuku权限申请失败", Toast.LENGTH_SHORT).show();
                }
                permDialog.dismiss();
            });
            dv.findViewById(R.id.perm_btn_neutral).setOnClickListener(v -> {
                if (closeCrashFixOnCancel) {
                    sp.edit().putBoolean("crashfix", false)
                            .putBoolean("crashfix_auto_disabled", false).apply();
                    LogUtil.log(SettingsActivity.this, "[权限] 对话框取消，崩溃修复已关闭");
                    Toast.makeText(SettingsActivity.this, "崩溃修复已关闭", Toast.LENGTH_SHORT).show();
                }
                permDialog.dismiss();
            });
        } else {
            ((TextView) dv.findViewById(R.id.perm_btn_positive)).setText("知道了");
            dv.findViewById(R.id.perm_btn_neutral).setVisibility(View.GONE);
            dv.findViewById(R.id.perm_btn_positive).setOnClickListener(v -> permDialog.dismiss());
            if (closeCrashFixOnCancel) {
                permDialog.setOnDismissListener(d -> {
                    sp.edit().putBoolean("crashfix", false)
                            .putBoolean("crashfix_auto_disabled", false).apply();
                });
            }
        }
        permDialog.show();
    }

    /** 显示 DUMP 权限不足的对话框（卡片样式） */
    private void showDumpPermissionDialog() {
        final String pkgName = getPackageName();
        final String cmd = "pm grant " + pkgName + " android.permission.DUMP";

        final android.app.Dialog permDialog = new android.app.Dialog(this);
        permDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        View dv = getLayoutInflater().inflate(R.layout.dialog_permission, null);
        permDialog.setContentView(dv);
        android.view.Window w = permDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int marginPx = (int) (16 * dm.density + 0.5f);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = dm.widthPixels - marginPx * 2;
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }

        // 设置标题
        ((TextView) dv.findViewById(R.id.perm_title)).setText("需要授予 DUMP 权限");

        // 设置消息
        String msg = "在下面三个方法中任选一个即可：\n\n"
                + "1. 连接电脑USB调试后在电脑CMD执行以下命令：\n"
                + "adb shell " + cmd + "\n\n"
                + "2. Root 激活。\n\n"
                + "3. Shizuku 激活。";
        ((TextView) dv.findViewById(R.id.perm_msg)).setText(msg);

        // 复制命令按钮
        ((TextView) dv.findViewById(R.id.perm_btn_positive)).setText("复制命令");
        dv.findViewById(R.id.perm_btn_positive).setOnClickListener(v -> {
            ((android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(android.content.ClipData.newPlainText("c", "adb shell " + cmd));
            Toast.makeText(this, "命令已复制到剪切板", Toast.LENGTH_SHORT).show();
            permDialog.dismiss();
        });

        // Root 激活按钮
        dv.findViewById(R.id.perm_btn_root).setVisibility(View.VISIBLE);
        dv.findViewById(R.id.perm_btn_root).setOnClickListener(v -> {
            permDialog.dismiss();
            new Thread(() -> {
                try {
                    Process p = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream o = new java.io.DataOutputStream(p.getOutputStream());
                    o.writeBytes(cmd + "\nexit\n");
                    o.flush();
                    o.close();
                    p.waitFor();
                    runOnUiThread(() -> {
                        if (p.exitValue() == 0) {
                            Toast.makeText(SettingsActivity.this, "成功激活", Toast.LENGTH_SHORT).show();
                            ShellUtil.reset();
                            enableCrashFix(false);
                        } else {
                            Toast.makeText(this, "激活失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "激活失败", Toast.LENGTH_SHORT).show());
                }
            }).start();
        });

        // Shizuku 激活按钮
        dv.findViewById(R.id.perm_btn_shizuku).setVisibility(View.VISIBLE);
        dv.findViewById(R.id.perm_btn_shizuku).setOnClickListener(v -> {
            permDialog.dismiss();
            mPendingCrashFixRequest = true;
            try {
                Shizuku.requestPermission(0);
            } catch (Exception e) {
                mPendingCrashFixRequest = false;
                Toast.makeText(this, "Shizuku权限申请失败", Toast.LENGTH_SHORT).show();
            }
        });

        // 取消按钮
        dv.findViewById(R.id.perm_btn_neutral).setOnClickListener(v -> {
            sp.edit().putBoolean("crashfix", false)
                    .putBoolean("crashfix_auto_disabled", false).apply();
            LogUtil.log(this, "[权限] 对话框取消，崩溃修复已关闭");
            Toast.makeText(this, "崩溃修复已关闭", Toast.LENGTH_SHORT).show();
            permDialog.dismiss();
        });

        permDialog.show();
    }

    /** 显示强杀模式需要 Root/Shizuku 权限的对话框（卡片样式） */
    private void showFixModePermissionDialog() {
        boolean shizukuRunning = ShellUtil.isShizukuRunning();
        boolean hasRoot = ShellUtil.hasRoot();
        LogUtil.log(this, "[权限] 显示强杀权限不足对话框 shizukuRunning=" + shizukuRunning + " hasRoot=" + hasRoot);

        final android.app.Dialog permDialog = new android.app.Dialog(this);
        permDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        View dv = getLayoutInflater().inflate(R.layout.dialog_permission, null);
        permDialog.setContentView(dv);
        android.view.Window w = permDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int marginPx = (int) (16 * dm.density + 0.5f);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = dm.widthPixels - marginPx * 2;
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }

        // 设置标题
        ((TextView) dv.findViewById(R.id.perm_title)).setText("权限不足");

        // 设置消息
        StringBuilder msg = new StringBuilder();
        msg.append("重启时强杀对应APP需要 Root 或 Shizuku 权限。\n\n");
        if (shizukuRunning) {
            msg.append("已检测到Shizuku正在运行，请授权本应用使用Shizuku。");
        } else {
            msg.append("当前未检测到任何权限。\n请安装Shizuku并授权，或获取root权限后重试。");
        }
        ((TextView) dv.findViewById(R.id.perm_msg)).setText(msg.toString());

        // 隐藏第二行的取消按钮（放在第一行与申请按钮同行）
        dv.findViewById(R.id.perm_btn_neutral).setVisibility(View.GONE);

        if (shizukuRunning) {
            // 第一行显示：取消 + 申请Shizuku权限
            dv.findViewById(R.id.perm_btn_root).setVisibility(View.VISIBLE);
            ((TextView) dv.findViewById(R.id.perm_btn_root)).setText("取消");
            dv.findViewById(R.id.perm_btn_root).setOnClickListener(v -> permDialog.dismiss());

            dv.findViewById(R.id.perm_btn_shizuku).setVisibility(View.GONE);
            ((TextView) dv.findViewById(R.id.perm_btn_positive)).setText("申请Shizuku权限");
            dv.findViewById(R.id.perm_btn_positive).setOnClickListener(v -> {
                LogUtil.log(SettingsActivity.this, "[权限] 对话框中点击申请Shizuku权限（强杀模式）");
                mPendingFixModeRequest = true;
                try {
                    Shizuku.requestPermission(0);
                } catch (Exception e) {
                    LogUtil.log(SettingsActivity.this,
                            "[权限] 对话框申请异常: " + e.getClass().getSimpleName());
                    mPendingFixModeRequest = false;
                    Toast.makeText(this, "Shizuku权限申请失败", Toast.LENGTH_SHORT).show();
                }
                permDialog.dismiss();
            });
        } else {
            // 无任何权限时，只显示"知道了"
            dv.findViewById(R.id.perm_btn_root).setVisibility(View.GONE);
            dv.findViewById(R.id.perm_btn_shizuku).setVisibility(View.GONE);
            ((TextView) dv.findViewById(R.id.perm_btn_positive)).setText("知道了");
            dv.findViewById(R.id.perm_btn_positive).setOnClickListener(v -> permDialog.dismiss());
        }

        permDialog.show();
    }

    private void enableFixMode() {
        LogUtil.log(this, "[权限] 启用强杀模式");
        sp.edit().putBoolean("fixmode", true).apply();
        switchFixMode.setChecked(true);
        ShellUtil.reset();
        ShellUtil.getPermissionState();
        String permName = ShellUtil.hasRoot() ? "root" : (ShellUtil.hasShizukuOnly() ? "shizuku" : "未知");
        Toast.makeText(this, "已获取" + permName + "权限，强杀模式已开启", Toast.LENGTH_SHORT).show();
    }

    // ========== 刷新子项状态 ==========

    private void refreshCrashFixDependent() {
        boolean crashFixEnabled = switchCrashFix.isChecked();
        boolean periodicEnabled = crashFixEnabled && switchPeriodicCheck.isChecked();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            android.transition.TransitionManager.beginDelayedTransition((android.view.ViewGroup) rootView);
        }
        if (crashDependentLayout != null) {
            crashDependentLayout.setVisibility(crashFixEnabled ? View.VISIBLE : View.GONE);
        }
        if (periodicSubLayout != null) {
            periodicSubLayout.setVisibility(periodicEnabled ? View.VISIBLE : View.GONE);
            periodicWrapper.setBackgroundResource(periodicEnabled ? R.drawable.bg_nested_setting : 0);
        }

        switchUnlockCrashCheck.setEnabled(crashFixEnabled);
        switchFixMode.setEnabled(crashFixEnabled);
        switchPeriodicCheck.setEnabled(crashFixEnabled);
        switchWakeIdle.setEnabled(periodicEnabled);
        switchIgnoreSystemCrash.setEnabled(crashFixEnabled);
        intervalLabel.setEnabled(periodicEnabled);

        boolean fixModeEnabled = crashFixEnabled && switchFixMode.isChecked();
        if (fixmodeServicesLayout != null) {
            fixmodeServicesLayout.setEnabled(fixModeEnabled);
            fixmodeServicesLayout.setVisibility(fixModeEnabled ? View.VISIBLE : View.GONE);
            fixmodeWrapper.setBackgroundResource(fixModeEnabled ? R.drawable.bg_nested_setting : 0);
        }
        if (fixmodeServicesBtn != null)
            fixmodeServicesBtn.setEnabled(fixModeEnabled);
    }

    // ========== 对话框 ==========

    class ServiceItem {
        String serviceId;
        String label;
        String packageLabelPinyin;
        String serviceLabelPinyin;
        boolean isChecked;
        ServiceItem(String id, String l, boolean c, String pkgPinyin, String svcPinyin) { 
            serviceId = id; label = l; isChecked = c; 
            packageLabelPinyin = pkgPinyin; serviceLabelPinyin = svcPinyin;
        }
    }

    private void showFixmodeServicesDialog() {
        if (!switchFixMode.isChecked())
            return;

        java.util.List<android.accessibilityservice.AccessibilityServiceInfo> list = new java.util.ArrayList<>(
                ((android.view.accessibility.AccessibilityManager) getApplicationContext()
                        .getSystemService(Context.ACCESSIBILITY_SERVICE)).getInstalledAccessibilityServiceList());

        final PackageManager pm = getPackageManager();
        String disabledStr = sp.getString("fixmode_disabled_services", "");

        List<ServiceItem> allItems = new ArrayList<>();

        for (android.accessibilityservice.AccessibilityServiceInfo info : list) {
            String id = info.getId();
            // 排除无障碍管理器自身
            if (id.startsWith(getPackageName() + "/"))
                continue;

            // 默认不显示系统应用
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                if ((info.getResolveInfo().serviceInfo.applicationInfo.flags
                        & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
            }

            CharSequence label = info.getResolveInfo().loadLabel(pm);
            String labelStr = label != null ? label.toString() : id;
            boolean isChecked = !disabledStr.contains(id);
            
            String[] parts = id.split("/");
            String appLabel = "";
            String svcLabel = "";
            if (parts.length >= 2) {
                try {
                    CharSequence pkgLabel = pm.getApplicationLabel(pm.getApplicationInfo(parts[0], PackageManager.GET_META_DATA));
                    appLabel = pkgLabel != null ? pkgLabel.toString() : parts[0];
                    svcLabel = pm.getServiceInfo(new ComponentName(parts[0], parts[1]), PackageManager.MATCH_DEFAULT_ONLY).loadLabel(pm).toString();
                } catch (Exception e) {}
            } else {
                appLabel = labelStr;
            }
            String pkgPinyin = PinyinUtils.getPinyin(appLabel);
            String svcPinyin = PinyinUtils.getPinyin(svcLabel);
            
            allItems.add(new ServiceItem(id, labelStr, isChecked, pkgPinyin, svcPinyin));
        }

        if (allItems.isEmpty()) {
            Toast.makeText(this, "未找到其他无障碍服务", Toast.LENGTH_SHORT).show();
            return;
        }

        java.util.Collections.sort(allItems, (o1, o2) -> {
            int compare = (o1.packageLabelPinyin != null ? o1.packageLabelPinyin : "").compareToIgnoreCase(
                          o2.packageLabelPinyin != null ? o2.packageLabelPinyin : "");
            if (compare == 0) {
                compare = (o1.serviceLabelPinyin != null ? o1.serviceLabelPinyin : "").compareToIgnoreCase(
                          o2.serviceLabelPinyin != null ? o2.serviceLabelPinyin : "");
            }
            return compare;
        });

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_favorites, null);
        android.widget.EditText editSearch = dialogView.findViewById(R.id.edit_search);
        ListView listFavorites = dialogView.findViewById(R.id.list_favorites);

        class ServiceAdapter extends BaseAdapter {
            List<ServiceItem> displayedItems = new ArrayList<>(allItems);

            @Override public int getCount() { return displayedItems.size(); }
            @Override public ServiceItem getItem(int position) { return displayedItems.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(android.R.layout.simple_list_item_multiple_choice, parent, false);
                }
                android.widget.CheckedTextView ctv = (android.widget.CheckedTextView) convertView;
                ServiceItem item = getItem(position);
                ctv.setText(item.label);
                ctv.setChecked(item.isChecked);
                
                android.graphics.drawable.Drawable icon = null;
                try {
                    String packageName = item.serviceId.split("/")[0];
                    icon = pm.getApplicationIcon(packageName);
                } catch (Exception ignored) {}
                
                if (icon != null) {
                    icon = icon.getConstantState().newDrawable().mutate();
                    int size = (int) (32 * getResources().getDisplayMetrics().density + 0.5f);
                    icon.setBounds(0, 0, size, size);
                    ctv.setCompoundDrawables(icon, null, null, null);
                    ctv.setCompoundDrawablePadding((int) (12 * getResources().getDisplayMetrics().density + 0.5f));
                } else {
                    ctv.setCompoundDrawables(null, null, null, null);
                }
                
                ctv.setOnClickListener(v -> {
                    item.isChecked = !item.isChecked;
                    ctv.setChecked(item.isChecked);
                });
                return convertView;
            }

            public void filter(String text) {
                String filterString = text.toLowerCase();
                List<ServiceItem> filtered = new ArrayList<>();
                for (ServiceItem item : allItems) {
                    if (item.label.toLowerCase().contains(filterString)) {
                        filtered.add(item);
                    }
                }
                displayedItems = filtered;
                notifyDataSetChanged();
            }
        }

        ServiceAdapter adapter = new ServiceAdapter();
        listFavorites.setAdapter(adapter);

        editSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                adapter.filter(s.toString());
            }
        });

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("选择强杀重启的服务")
                .setView(dialogView)
                .setPositiveButton("确定", (d, which) -> {
                    StringBuilder sb = new StringBuilder();
                    for (ServiceItem item : allItems) {
                        if (!item.isChecked) {
                            if (sb.length() > 0)
                                sb.append(":");
                            sb.append(item.serviceId);
                        }
                    }
                    sp.edit().putString("fixmode_disabled_services", sb.toString()).apply();
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("全选/全不选", null)
                .create();

        dialog.show();

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (adapter.displayedItems.isEmpty()) return;
            boolean allChecked = true;
            for (ServiceItem item : adapter.displayedItems) {
                if (!item.isChecked) {
                    allChecked = false;
                    break;
                }
            }
            boolean targetState = !allChecked;
            for (ServiceItem item : adapter.displayedItems) {
                item.isChecked = targetState;
            }
            adapter.notifyDataSetChanged();
        });
    }

    private void showCrashTutorialDialog() {
        View dv = getLayoutInflater().inflate(R.layout.dialog_crash_tutorial, null);
        ((TextView) dv.findViewById(R.id.tutorial_msg)).setText(
                "1. 崩溃检测：检测无障碍服务是否假死（已开启但显示\"无法运行\"），检测到后关闭服务再打开\n\n"
                        + "2. 解锁检测：设备解锁时进行检测。如果解锁检测无效，即日志中看不到[解锁广播]相关的日志，请开启管理器的无障碍服务或自启动权限\n\n"
                        + "3. 强杀应用重启：检测到崩溃的无障碍服务后，将会使服务重启，默认重启方式为直接重启服务。若普通重启仍失效，可尝试开启此功能强杀 App 进程后再恢复服务（需 Shizuku/Root 权限）\n\n"
                        + "4. 定时检测：定时检测服务状态，建议间隔≥10分钟\n");

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(dv);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int marginPx = (int) (16 * dm.density + 0.5f);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = dm.widthPixels - marginPx * 2;
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }

        dv.findViewById(R.id.tutorial_ok_btn).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showNotifyCustomDialog() {
        String currentTitle = sp.getString("notify_title",
                "海绵宝宝，猜猜我有几颗糖");
        String currentText = sp.getString("notify_text",
                "猜对了两颗都给你！");

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_notify_custom, null);
        final EditText titleInput = dialogView.findViewById(R.id.notify_title_input);
        final EditText textInput = dialogView.findViewById(R.id.notify_text_input);
        titleInput.setText(currentTitle);
        textInput.setText(currentText);

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(dialogView);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int marginPx = (int) (16 * dm.density + 0.5f);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = dm.widthPixels - marginPx * 2;
            params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
        dialog.show();

        dialogView.findViewById(R.id.notify_save_btn).setOnClickListener(v -> {
            String newTitle = titleInput.getText().toString().trim();
            String newText = textInput.getText().toString().trim();
            if (newTitle.isEmpty())
                newTitle = "海绵宝宝，猜猜我有几颗糖";
            if (newText.isEmpty())
                newText = "猜对了两颗都给你！";
            sp.edit().putString("notify_title", newTitle).apply();
            sp.edit().putString("notify_text", newText).apply();
            Toast.makeText(SettingsActivity.this,
                    "已保存", Toast.LENGTH_SHORT).show();
            startService(new Intent(SettingsActivity.this, daemonService.class).setAction("UPDATE_NOTIFY"));
            dialog.dismiss();
        });

        dialogView.findViewById(R.id.notify_reset_btn).setOnClickListener(v -> {
            sp.edit().putString("notify_title", "海绵宝宝，猜猜我有几颗糖").apply();
            sp.edit().putString("notify_text", "猜对了两颗都给你！").apply();
            titleInput.setText("海绵宝宝，猜猜我有几颗糖");
            textInput.setText("猜对了两颗都给你！");
            Toast.makeText(SettingsActivity.this,
                    "已恢复默认", Toast.LENGTH_SHORT).show();
            startService(new Intent(SettingsActivity.this, daemonService.class).setAction("UPDATE_NOTIFY"));
            dialog.dismiss();
        });
    }

    private void showIntervalDialog(Runnable onSaved) {
        int currentInterval = sp.getInt("periodic_check_interval", 10);

        View layout = getLayoutInflater().inflate(R.layout.dialog_time_input, null);
        final com.google.android.material.textfield.TextInputEditText input = layout.findViewById(R.id.edit_text);
        final com.google.android.material.textfield.TextInputLayout inputLayout = layout.findViewById(R.id.text_input_layout);
        inputLayout.setHint("定时检测间隔");
        input.setText(String.valueOf(currentInterval));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("设置定时检测间隔（非精确时间）")
                .setView(layout)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String inputStr = input.getText().toString().trim();
            int minutes;
            try {
                minutes = Integer.parseInt(inputStr);
            } catch (NumberFormatException e) {
                Toast.makeText(SettingsActivity.this,
                        "请输入有效数字", Toast.LENGTH_SHORT).show();
                return;
            }
            if (minutes < 1 || minutes > 1440) {
                Toast.makeText(SettingsActivity.this,
                        "请输入1-1440之间的数字", Toast.LENGTH_SHORT).show();
                return;
            }
            sp.edit().putInt("periodic_check_interval", minutes).apply();
            TimerReceiver.cancel(SettingsActivity.this, "检测间隔变更");
            LogUtil.log(SettingsActivity.this, "[定时检测] 已取消旧定时");
            TimerReceiver.scheduleNext(SettingsActivity.this);
            LogUtil.log(SettingsActivity.this,
                    "[定时检测] 已设置新间隔 = " + minutes + " 分钟");
            Toast.makeText(SettingsActivity.this,
                    "定时检测间隔已设为 " + minutes + " 分钟", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            if (onSaved != null)
                onSaved.run();
        });
    }

    private void showCooldownTimeDialog() {
        int currentCooldown = sp.getInt("global_cooldown_time_minutes", 15);

        View layout = getLayoutInflater().inflate(R.layout.dialog_time_input, null);
        final com.google.android.material.textfield.TextInputEditText input = layout.findViewById(R.id.edit_text);
        final com.google.android.material.textfield.TextInputLayout inputLayout = layout.findViewById(R.id.text_input_layout);
        inputLayout.setHint("全局冷却时间");
        input.setText(String.valueOf(currentCooldown));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("设置全局冷却时间")
                .setView(layout)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String inputStr = input.getText().toString().trim();
            int minutes;
            try {
                minutes = Integer.parseInt(inputStr);
            } catch (NumberFormatException e) {
                Toast.makeText(SettingsActivity.this,
                        "请输入有效数字", Toast.LENGTH_SHORT).show();
                return;
            }
            if (minutes < 1 || minutes > 1440) {
                Toast.makeText(SettingsActivity.this,
                        "请输入1-1440之间的数字", Toast.LENGTH_SHORT).show();
                return;
            }
            sp.edit().putInt("global_cooldown_time_minutes", minutes).apply();
            globalCooldownTimeLabel.setText(minutes + "分钟");
            Toast.makeText(SettingsActivity.this,
                    "全局冷却时间已设为 " + minutes + " 分钟", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    private void showThemeDialog() {
        String[] themes = { "跟随系统 (莫奈取色)", "经典蓝", "护眼绿", "优雅紫" };
        String[] themeValues = { ThemeUtils.THEME_DYNAMIC, ThemeUtils.THEME_BLUE, ThemeUtils.THEME_GREEN,
                ThemeUtils.THEME_PURPLE };
        SharedPreferences mainSp = getSharedPreferences("Main", Context.MODE_PRIVATE);
        String currentTheme = mainSp.getString(ThemeUtils.PREF_THEME, ThemeUtils.THEME_BLUE);
        int checkedItem = 0;
        for (int i = 0; i < themeValues.length; i++) {
            if (themeValues[i].equals(currentTheme)) {
                checkedItem = i;
                break;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择主题颜色")
                .setSingleChoiceItems(themes, checkedItem, (dialog, which) -> {
                    String selectedTheme = themeValues[which];
                    if (!selectedTheme.equals(currentTheme)) {
                        mainSp.edit().putString(ThemeUtils.PREF_THEME, selectedTheme).apply();
                        dialog.dismiss();
                        recreate();
                    } else {
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void showAboutDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_about, null);

        // 显示版本号
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) dialogView.findViewById(R.id.about_version))
                    .setText("v" + versionName);
        } catch (Exception ignored) {
            dialogView.findViewById(R.id.about_version).setVisibility(View.GONE);
        }

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(dialogView);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int marginPx = (int) (16 * dm.density + 0.5f);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = dm.widthPixels - marginPx * 2;
            params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
        dialog.show();

        dialogView.findViewById(R.id.about_copy_btn).setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(
                        "mqqapi://card/show_pslcard?src_type=internal&card_type=group&uin=1079270847&version=1"));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(SettingsActivity.this, "未检测到QQ客户端", Toast.LENGTH_SHORT).show();
            }
        });

        dialogView.findViewById(R.id.about_close_btn).setOnClickListener(v -> dialog.dismiss());
    }

    // ========== 更新检测 ==========

    private void checkForUpdate() {
        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show();
        UpdateChecker.checkForUpdate(this, new UpdateChecker.UpdateListener() {
            @Override
            public void onUpdateAvailable(String versionName, String downloadUrl,
                    String releaseNotes) {
                showUpdateDialog(versionName, downloadUrl, releaseNotes);
            }

            @Override
            public void onNoUpdate() {
                Toast.makeText(SettingsActivity.this,
                        "当前已是最新版本", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String errorMessage) {
                Toast.makeText(SettingsActivity.this,
                        "检查更新失败: " + errorMessage, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showUpdateDialog(String versionName, String downloadUrl, String releaseNotes) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_update, null);

        TextView title = dialogView.findViewById(R.id.update_title);
        TextView version = dialogView.findViewById(R.id.update_version);
        TextView changelog = dialogView.findViewById(R.id.update_changelog);
        android.widget.Button updateLater = dialogView.findViewById(R.id.update_later);
        android.widget.Button updateNow = dialogView.findViewById(R.id.update_now);

        title.setText("发现新版本 " + versionName);
        version.setText("版本号: " + versionName);
        changelog.setText(releaseNotes.isEmpty() ? "暂无更新说明" : releaseNotes);

        AlertDialog updateDialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .create();
        updateDialog.show();

        updateLater.setText("暂不更新");
        updateLater.setOnClickListener(v -> updateDialog.dismiss());
        updateNow.setOnClickListener(v -> {
            updateDialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开下载链接", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ========== 工具方法 ==========

    private void startDaemonService() {
        String daemon = sp.getString("daemon", "");
        if (daemon.isEmpty())
            return;
        if (checkWriteSecurePermission())
            return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                        .areNotificationsEnabled()) {
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 0);
            Toast.makeText(this, "请授予通知权限", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(new Intent(this, daemonService.class));
        else
            startService(new Intent(this, daemonService.class));
    }

    private boolean checkWriteSecurePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return checkSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED;
        // 低于M时，系统应用才有权限
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager()
                    .getPackageInfo(getPackageName(), PackageManager.GET_CONFIGURATIONS).applicationInfo;
            return (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private int getColorCompat(int id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getResources().getColor(id, getTheme());
        }
        return getResources().getColor(id);
    }

    // 以下方法从MainActivity复制，因为它们是package-private级别的工具方法

    private boolean isServiceEnabled(String serviceName, String settingValue) {
        if (settingValue == null || settingValue.isEmpty())
            return false;
        String[] services = settingValue.split(":");
        for (String s : services) {
            if (s != null && s.equals(serviceName))
                return true;
        }
        return false;
    }

    private static boolean containsService(String daemon, String serviceName) {
        if (daemon == null || daemon.isEmpty())
            return false;
        String[] entries = daemon.split(":");
        for (String entry : entries) {
            if (entry != null && entry.equals(serviceName))
                return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 0 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDaemonService();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }
}