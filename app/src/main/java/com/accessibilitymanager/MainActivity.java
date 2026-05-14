package com.accessibilitymanager;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import androidx.appcompat.app.AppCompatActivity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;
import androidx.appcompat.widget.Toolbar;

public class MainActivity extends AppCompatActivity {

    private SettingsValueChangeContentObserver mContentOb;  //自定义的内容监视器
    List<AccessibilityServiceInfo> l, tmp;//所有AccessibilityServiceInfo列表，临时列表
    ListView listView;//列表视图
    SharedPreferences sp;//共享偏好设置
    String settingValue, tmpSettingValue, daemon, top;  //当前设置项值，临时设置项值，守护进程名称，顶部进程名称
    boolean night = true;//是否为夜间模式
    PackageManager pm;//包管理器
    boolean perm = false;//是否获取了权限
    private boolean listenerAdded = false;//是否添加了内容监视器
    private boolean mPendingCrashFixRequest = false;//是否有待处理的崩溃修复请求
    private boolean mUseDialogSettings = true;//是否使用对话框设置

    LinearLayout batteryWarning;//电池警告布局
    TextView batteryWarningText;//电池警告文本
    TextView batteryWarningGo;//电池警告“去设置”按钮
    TextView batteryWarningDismiss;//电池警告“关闭”按钮

    //自定义一个内容监视器
    class SettingsValueChangeContentObserver extends ContentObserver {
        public SettingsValueChangeContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            //更新settingValue，并与APP内的tmpsettingValue作比对。如果不同，则说明本次设置项改变来自APP外部，于是刷新一下主界面的列表。相同则说明这次改变就是本APP改的，无需处理。
            settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue == null) settingValue = "";
            if (!settingValue.equals(tmpSettingValue))
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int firstPosition = listView.getFirstVisiblePosition();
                        int lastPosition = listView.getLastVisiblePosition();
                        for (int i = firstPosition; i <= lastPosition; i++) {
                            View view = listView.getChildAt(i - firstPosition);
                            boolean isChecked = isServiceEnabled(tmp.get(i).getId(), settingValue);
                            (view.findViewById(R.id.ib)).setVisibility(isChecked ? View.VISIBLE : View.INVISIBLE);
                            ((Switch) view.findViewById(R.id.s)).setChecked(isChecked);
                        }
                    }
                });
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        night = nightMode == Configuration.UI_MODE_NIGHT_YES;

        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitleTextColor(night ? Color.WHITE : Color.BLACK);
        toolbar.setPopupTheme(night ? R.style.PopupOverlay_Dark : R.style.PopupOverlay_Light);
        Drawable overflowIcon = toolbar.getOverflowIcon();
        if (overflowIcon != null) {
            overflowIcon.setTint(night ? Color.WHITE : Color.BLACK);
        }

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
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }

        //设置导航栏透明，UI会好看些
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        setTitle("无障碍管理");
        
        //注册shizuku授权结果监听器，始终注册以支持崩溃修复权限回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            listenerAdded = true;
            Shizuku.addRequestPermissionResultListener(RL);
        }


        pm = getPackageManager();
        //注册设置项改变的监听器，用于实时更新APP内显示的各个无障碍服务的状态
        mContentOb = new SettingsValueChangeContentObserver();
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, mContentOb);

        //获取本机安装的无障碍服务列表，包括开启的和未开启的都有
        l = ((AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE)).getInstalledAccessibilityServiceList();
        sp = getSharedPreferences("data", 0);

        //读取用户设置“是否隐藏后台”，并进行隐藏后台
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            ((ActivityManager) getSystemService(Service.ACTIVITY_SERVICE)).getAppTasks().get(0).setExcludeFromRecents(sp.getBoolean("hide", true));

        daemon = sp.getString("daemon", "");
        top = sp.getString("top", "");
        Sort();


        listView = findViewById(R.id.list);

        batteryWarning = findViewById(R.id.battery_warning);
        batteryWarningText = findViewById(R.id.battery_warning_text);
        batteryWarningGo = findViewById(R.id.battery_warning_go);
        batteryWarningDismiss = findViewById(R.id.battery_warning_dismiss);

        batteryWarningGo.setOnClickListener(v -> openBatteryOptimizationSettings());

        batteryWarningDismiss.setOnClickListener(v -> {
            sp.edit().putBoolean("battery_warning_dismissed", true).apply();
            batteryWarning.setVisibility(View.GONE);
        });

        //获得当前开启的无障碍服务列表
        settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (settingValue == null) settingValue = "";
        tmpSettingValue = settingValue;


        //初次使用触发


        if (sp.getBoolean("first", true)) {
            new AlertDialog.Builder(this)
                    .setTitle("隐私政策")
                    .setMessage("本应用不会收集或记录您的任何信息，也不包含任何联网功能。继续使用则代表您同意上述隐私政策。")
                    .setPositiveButton("OK", null).create().show();
            sp.edit().putBoolean("first", false).apply();
        }


        //如果设备一次都没打开过无障碍设置界面，则下面这个设置项值不存在，同时本APP是无法获取到无障碍设置列表的。所以要在这里加个判断，如果从来没开启过，则需要本APP来给这个设置项写入1来开启。

        new Thread(() -> {
            ShellUtil.reset();
            ShellUtil.getPermissionState();
            boolean crashFix = sp.getBoolean("crashfix", false);
            boolean autoDisabled = sp.getBoolean("crashfix_auto_disabled", false);
            boolean hasPermission = ShellUtil.hasAnyPermission();
            if (hasPermission && autoDisabled && !crashFix) {
                sp.edit().putBoolean("crashfix", true).putBoolean("crashfix_auto_disabled", false).apply();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "已检测到权限，崩溃修复已重新开启", Toast.LENGTH_SHORT).show());
            } else if (!hasPermission && crashFix) {
                runOnUiThread(() -> requestCrashFixPermission(true));
            }
            runOnUiThread(() -> invalidateOptionsMenu());
        }).start();

        if (Settings.Secure.getString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED) != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    listView.setAdapter(new adapter(tmp));
                }
            });
            // 只要有任意一个加锁服务，启动一次保活服务即可，不需要为每个加锁服务重复启动
            if (!daemon.isEmpty()) {
                StartForeGroundDaemon();
            }

        } else {
            new AlertDialog.Builder(this).setMessage("您的设备尚未启用无障碍服务功能。您可以选择在系统设置-无障碍-打开或关闭任意服务项来激活系统的无障碍服务功能，也可以授权本APP安全设置写入权限以解决.")
                    .setNegativeButton("root激活", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            Process p;
                            try {
                                p = Runtime.getRuntime().exec("su");
                                DataOutputStream o = new DataOutputStream(p.getOutputStream());
                                o.writeBytes("pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS\nexit\n");
                                o.flush();
                                o.close();
                                p.waitFor();
                                if (p.exitValue() == 0) {
                                    Toast.makeText(MainActivity.this, "成功激活", Toast.LENGTH_SHORT).show();
                                }
                            } catch (IOException | InterruptedException ignored) {
                                Toast.makeText(MainActivity.this, "激活失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setPositiveButton("复制命令", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"));
                            Toast.makeText(MainActivity.this, "命令已复制到剪切板", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("Shizuku激活", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) check();
                        }
                    })
                    .create().show();
            try {
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            } catch (Exception ignored) {
            }
        }

        checkBatteryOptimization();
    }

    private void Sort() {
        tmp = new ArrayList<>();
        boolean userOnly = sp.getBoolean("useronly", false);
        for (AccessibilityServiceInfo info : l) {
            if (userOnly && !isUserApp(info)) continue;
            tmp.add(info);
        }
        final String currentSetting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        Collections.sort(tmp, new Comparator<AccessibilityServiceInfo>() {
            @Override
            public int compare(AccessibilityServiceInfo info1, AccessibilityServiceInfo info2) {
                String id1 = info1.getId();
                String id2 = info2.getId();
                boolean top1 = containsService(top, id1);
                boolean top2 = containsService(top, id2);
                if (top1 && !top2) return -1;
                if (!top1 && top2) return 1;
                if (top1 && top2) {
                    return Integer.compare(top.indexOf(id1), top.indexOf(id2));
                }
                boolean enabled1 = isServiceEnabled(id1, currentSetting);
                boolean enabled2 = isServiceEnabled(id2, currentSetting);
                if (enabled1 && !enabled2) return -1;
                if (!enabled1 && enabled2) return 1;
                return 0;
            }
        });
    }

    private boolean isUserApp(AccessibilityServiceInfo info) {
        try {
            String[] parts = Pattern.compile("/").split(info.getId());
            if (parts.length < 1) return true;
            ApplicationInfo appInfo = pm.getApplicationInfo(parts[0], 0);
            return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private boolean isServiceEnabled(String serviceId, String settingValue) {
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


    private boolean containsService(String list, String serviceId) {
        if (list == null || list.isEmpty()) return false;
        ComponentName target = ComponentName.unflattenFromString(serviceId);
        if (target == null) return false;
        for (String entry : list.split(":")) {
            if (entry.isEmpty()) continue;
            ComponentName entryCn = ComponentName.unflattenFromString(entry);
            if (target.equals(entryCn)) return true;
        }
        return false;
    }

    private String normalizeServiceId(String serviceId) {
        ComponentName cn = ComponentName.unflattenFromString(serviceId);
        return cn != null ? cn.flattenToString() : serviceId;
    }


    //返回键退出APP，用于适配安卓12和高于12的系统上返回键默认仅把APP放后台的问题。
    @Override
    public void onBackPressed() {
        finish();
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBatteryOptimization();
    }

    private void checkBatteryOptimization() {
        if (sp.getBoolean("battery_warning_dismissed", false)) {
            batteryWarning.setVisibility(View.GONE);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                batteryWarning.setVisibility(View.GONE);
            } else {
                batteryWarning.setVisibility(View.VISIBLE);
            }
        } else {
            batteryWarning.setVisibility(View.GONE);
        }
    }

    private void openBatteryOptimizationSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private final Shizuku.OnRequestPermissionResultListener RL = (requestCode, grantResult) -> {
        LogUtil.log(MainActivity.this, "[权限] Shizuku回调, grantResult=" + grantResult + ", pendingCrashFix=" + mPendingCrashFixRequest);
        ShellUtil.reset();
        if (mPendingCrashFixRequest) {
            mPendingCrashFixRequest = false;
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                LogUtil.log(MainActivity.this, "[权限] 用户已授权(grantResult=GRANTED)，立即启用崩溃修复");
                ShellUtil.getPermissionState();
                enableCrashFix();
            } else {
                LogUtil.log(MainActivity.this, "[权限] 用户拒绝授权(grantResult=" + grantResult + ")，标记自动禁用");
                sp.edit().putBoolean("crashfix", false).putBoolean("crashfix_auto_disabled", true).apply();
                showNoPermissionDialog(false);
            }
        }
        check();
    };

    //检查Shizuku权限，申请Shizuku权限的函数
    private void check() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
            return;
        boolean b = true, c = false;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                Shizuku.requestPermission(0);
            else c = true;
        } catch (Exception e) {
            if (checkSelfPermission("moe.shizuku.manager.permission.API_V23") == PackageManager.PERMISSION_GRANTED)
                c = true;
            if (e.getClass() == IllegalStateException.class) {
                b = false;
                Toast.makeText(this, "shizuku未运行", Toast.LENGTH_SHORT).show();
            }

        }
        if (b && c) {
            try {
                Process p = Shizuku.newProcess(new String[]{"sh"}, null, null);
                OutputStream out = p.getOutputStream();
                out.write(("pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS\nexit\n").getBytes());
                out.flush();
                out.close();
                p.waitFor();
                if (p.exitValue() == 0) {
                    Toast.makeText(this, "成功激活", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException | InterruptedException ioException) {
                Toast.makeText(this, "激活失败", Toast.LENGTH_SHORT).show();
            }
        }

    }

    private void requestCrashFixPermission(boolean fromStartup) {
        LogUtil.log(this, "[权限] 请求权限 fromStartup=" + fromStartup);
        ShellUtil.reset();
        if (ShellUtil.hasAnyPermission()) {
            LogUtil.log(this, "[权限] 已拥有权限，直接启用崩溃修复");
            enableCrashFix();
            return;
        }

        if (ShellUtil.isShizukuRunning()) {
            LogUtil.log(this, "[权限] Shizuku运行中，发起授权请求");
            mPendingCrashFixRequest = true;
            try {
                Shizuku.requestPermission(0);
            } catch (Exception e) {
                LogUtil.log(this, "[权限] Shizuku.requestPermission异常: " + e.getClass().getSimpleName());
                mPendingCrashFixRequest = false;
                showNoPermissionDialog(fromStartup);
            }
        } else {
            LogUtil.log(this, "[权限] Shizuku未运行，显示权限不足对话框");
            showNoPermissionDialog(fromStartup);
        }
    }

    private void enableCrashFix() {
        LogUtil.log(this, "[权限] 启用崩溃修复");
        sp.edit().putBoolean("crashfix", true).putBoolean("crashfix_auto_disabled", false).apply();
        invalidateOptionsMenu();
        int state = ShellUtil.getPermissionState();
        String permName = state == ShellUtil.PERM_ROOT ? "root" : (state == ShellUtil.PERM_SHIZUKU ? "shizuku" : "未知");
        LogUtil.log(this, "[权限] 当前权限=" + permName + "(" + state + ")");
        Toast.makeText(this, "已获取" + permName + "权限，崩溃修复已开启", Toast.LENGTH_SHORT).show();
        if (!daemon.isEmpty()) {
            StartForeGroundDaemon();
        }
    }

    private void showNoPermissionDialog(boolean fromStartup) {
        boolean shizukuRunning = ShellUtil.isShizukuRunning();
        LogUtil.log(this, "[权限] 显示权限不足对话框 shizukuRunning=" + shizukuRunning + " fromStartup=" + fromStartup);
        StringBuilder message = new StringBuilder();
        if (fromStartup) {
            sp.edit().putBoolean("crashfix", false).putBoolean("crashfix_auto_disabled", true).apply();
            message.append("崩溃修复已开启但未检测到权限，已自动暂停。\n\n");
        }
        message.append("崩溃修复功能需要root或Shizuku权限。\n\n");
        if (shizukuRunning) {
            message.append("已检测到Shizuku正在运行，请授权本应用使用Shizuku。");
        } else {
            message.append("当前未检测到任何权限。\n请安装Shizuku并授权，或获取root权限后重试。");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("权限不足")
                .setMessage(message.toString());

        if (shizukuRunning) {
            builder.setPositiveButton("申请Shizuku权限", (dialog, which) -> {
                LogUtil.log(MainActivity.this, "[权限] 对话框中点击申请Shizuku权限");
                mPendingCrashFixRequest = true;
                try {
                    Shizuku.requestPermission(0);
                } catch (Exception e) {
                    LogUtil.log(MainActivity.this, "[权限] 对话框申请异常: " + e.getClass().getSimpleName());
                    mPendingCrashFixRequest = false;
                    Toast.makeText(this, "Shizuku权限申请失败", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNeutralButton("取消", (dialog, which) -> {
                LogUtil.log(MainActivity.this, "[权限] 对话框取消，崩溃修复已关闭");
                Toast.makeText(MainActivity.this, "崩溃修复已关闭", Toast.LENGTH_SHORT).show();
            });
        } else {
            builder.setPositiveButton("确定", null);
        }

        builder.create().show();
        invalidateOptionsMenu();
    }

    //一些收尾工作，取消注册监听器什么的
    @Override
    protected void onDestroy() {
        if (listenerAdded) Shizuku.removeRequestPermissionResultListener(RL);

        getContentResolver().unregisterContentObserver(mContentOb);
        super.onDestroy();
    }


    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (!mUseDialogSettings) {
            menu.findItem(R.id.boot).setChecked(sp.getBoolean("boot", true));
            menu.findItem(R.id.toast).setChecked(sp.getBoolean("toast", true));
            menu.findItem(R.id.useronly).setChecked(sp.getBoolean("useronly", false));
            boolean crashFixEnabled = sp.getBoolean("crashfix", false);
            menu.findItem(R.id.crashfix).setChecked(crashFixEnabled);
            menu.findItem(R.id.periodic_check).setChecked(sp.getBoolean("periodic_check", true));
            menu.findItem(R.id.periodic_check).setEnabled(crashFixEnabled);
            menu.findItem(R.id.unlock_crash_check).setChecked(sp.getBoolean("unlock_crash_check", false));
            menu.findItem(R.id.unlock_crash_check).setEnabled(crashFixEnabled);
            menu.findItem(R.id.fixmode).setChecked(sp.getBoolean("fixmode", true));
            menu.findItem(R.id.fixmode).setEnabled(crashFixEnabled);
            menu.findItem(R.id.hide).setChecked(sp.getBoolean("hide", true));
            menu.findItem(R.id.delay_daemon).setChecked(sp.getBoolean("delay_daemon", false));
        }

        MenuItem permItem = menu.findItem(R.id.perm_status);
        if (permItem != null) {
            int state = ShellUtil.getPermissionState();
            String text;
            if (state == ShellUtil.PERM_ROOT) {
                text = "root";
            } else if (state == ShellUtil.PERM_SHIZUKU) {
                text = "shizuku";
            } else {
                text = null;
            }
            if (text != null) {
                SpannableStringBuilder ssb = new SpannableStringBuilder(text);
                ssb.setSpan(new ForegroundColorSpan(night ? Color.WHITE : Color.BLACK),
                        0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                permItem.setTitle(ssb);
                permItem.setVisible(true);
            } else {
                permItem.setVisible(false);
            }
        }

        return super.onPrepareOptionsMenu(menu);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mUseDialogSettings) {
            getMenuInflater().inflate(R.menu.arrange, menu);
        } else {
            getMenuInflater().inflate(R.menu.arrange_overflow, menu);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int itemId = menuItem.getItemId();

        if (!mUseDialogSettings) {
            if (itemId == R.id.boot) {
                sp.edit().putBoolean("boot", !menuItem.isChecked()).apply();
                menuItem.setChecked(!menuItem.isChecked());
            } else if (itemId == R.id.toast) {
                sp.edit().putBoolean("toast", !menuItem.isChecked()).apply();
                menuItem.setChecked(!menuItem.isChecked());
            } else if (itemId == R.id.useronly) {
                sp.edit().putBoolean("useronly", !menuItem.isChecked()).apply();
                menuItem.setChecked(!menuItem.isChecked());
                Sort();
                runOnUiThread(() -> listView.setAdapter(new adapter(tmp)));
            } else if (itemId == R.id.crashfix) {
                boolean newState = !menuItem.isChecked();
                if (newState) {
                    ShellUtil.reset();
                    ShellUtil.getPermissionState();
                    if (!ShellUtil.hasAnyPermission()) {
                        requestCrashFixPermission(false);
                        return true;
                    }
                }
                sp.edit().putBoolean("crashfix", newState).putBoolean("crashfix_auto_disabled", false).apply();
                menuItem.setChecked(newState);
                invalidateOptionsMenu();
            } else if (itemId == R.id.periodic_check) {
                boolean newState = !menuItem.isChecked();
                sp.edit().putBoolean("periodic_check", newState).apply();
                menuItem.setChecked(newState);
                if (newState) {
                    TimerReceiver.scheduleNext(this);
                } else {
                    TimerReceiver.cancel(this);
                }
            } else if (itemId == R.id.unlock_crash_check) {
                boolean newState = !menuItem.isChecked();
                if (newState && !sp.getBoolean("unlock_crash_dialog_dismissed", false)) {
                    new AlertDialog.Builder(this)
                            .setTitle("提示")
                            .setMessage("经过测试，开启自启动权限才可稳定接收解锁广播信号，建议去系统设置开启无障碍管理器自启动。")
                            .setNegativeButton("不再提示", (d, w) -> {
                                sp.edit().putBoolean("unlock_crash_dialog_dismissed", true).apply();
                                sp.edit().putBoolean("unlock_crash_check", true).apply();
                                menuItem.setChecked(true);
                                invalidateOptionsMenu();
                            })
                            .setPositiveButton("确定", (d, w) -> {
                                sp.edit().putBoolean("unlock_crash_check", true).apply();
                                menuItem.setChecked(true);
                                invalidateOptionsMenu();
                            })
                            .create().show();
                    return true;
                }
                sp.edit().putBoolean("unlock_crash_check", newState).apply();
                menuItem.setChecked(newState);
                invalidateOptionsMenu();
            } else if (itemId == R.id.fixmode) {
                sp.edit().putBoolean("fixmode", !menuItem.isChecked()).apply();
                menuItem.setChecked(!menuItem.isChecked());
            } else if (itemId == R.id.delay_daemon) {
                sp.edit().putBoolean("delay_daemon", !menuItem.isChecked()).apply();
                menuItem.setChecked(!menuItem.isChecked());
            } else if (itemId == R.id.notify_custom) {
                showNotifyCustomDialog();
            } else if (itemId == R.id.periodic_interval) {
                showIntervalDialog(null);
            } else if (itemId == R.id.hide) {
                sp.edit().putBoolean("hide", !menuItem.isChecked()).apply();
                menuItem.setChecked(!menuItem.isChecked());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ((ActivityManager) getSystemService(Service.ACTIVITY_SERVICE)).getAppTasks().get(0).setExcludeFromRecents(sp.getBoolean("hide", true));
                }
            } else if (itemId == R.id.viewlog) {
                showLogDialog();
            }
            return super.onOptionsItemSelected(menuItem);
        }

        if (itemId == R.id.settings) {
            showSettingsDialog();
        } else if (itemId == R.id.viewlog) {
            showLogDialog();
        }
        return super.onOptionsItemSelected(menuItem);
    }

    private void showLogDialog() {
        String logContent = LogUtil.readTodayLog(this);
        if (logContent.length() == 0) logContent = "今日暂无日志记录";

        int defaultColor = night ? Color.WHITE : Color.BLACK;
        int crashDetectColor = Color.rgb(0xE0, 0x6D, 0x00);
        int crashFixColor = Color.rgb(0x22, 0x88, 0xDD);
        int daemonColor = Color.rgb(0x22, 0xAA, 0x22);

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        for (String line : logContent.split("\n")) {
            int start = ssb.length();
            ssb.append(line);
            ssb.append("\n");
            int end = ssb.length();
            int color;
            if (line.contains("[崩溃检测]")) {
                color = crashDetectColor;
            } else if (line.contains("[崩溃修复]") || line.contains("[崩溃修复-重试]")) {
                color = crashFixColor;
            } else if (line.contains("[保活]")) {
                color = daemonColor;
            } else {
                color = defaultColor;
            }
            ssb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        final ScrollView scrollView = new ScrollView(this);
        final TextView textView = new TextView(this);
        textView.setTextIsSelectable(true);
        textView.setPadding(40, 20, 40, 20);
        textView.setTextSize(12f);
        textView.setText(ssb);
        scrollView.addView(textView);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        new AlertDialog.Builder(this)
                .setTitle("今日运行日志")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .create().show();
    }

    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);

        Switch switchBoot = dialogView.findViewById(R.id.boot);
        Switch switchToast = dialogView.findViewById(R.id.toast);
        Switch switchUserOnly = dialogView.findViewById(R.id.useronly);
        Switch switchHide = dialogView.findViewById(R.id.hide);
        Switch switchDelayDaemon = dialogView.findViewById(R.id.delay_daemon);
        Switch switchCrashFix = dialogView.findViewById(R.id.crashfix);
        Switch switchUnlockCrashCheck = dialogView.findViewById(R.id.unlock_crash_check);
        Switch switchFixMode = dialogView.findViewById(R.id.fixmode);
        Switch switchPeriodicCheck = dialogView.findViewById(R.id.periodic_check);
        TextView intervalLabel = dialogView.findViewById(R.id.periodic_interval_label);
        TextView notifyCustomBtn = dialogView.findViewById(R.id.notify_custom_btn);

        switchBoot.setChecked(sp.getBoolean("boot", true));
        switchToast.setChecked(sp.getBoolean("toast", true));
        switchUserOnly.setChecked(sp.getBoolean("useronly", false));
        switchHide.setChecked(sp.getBoolean("hide", true));
        switchDelayDaemon.setChecked(sp.getBoolean("delay_daemon", false));
        switchCrashFix.setChecked(sp.getBoolean("crashfix", false));
        switchUnlockCrashCheck.setChecked(sp.getBoolean("unlock_crash_check", false));
        switchFixMode.setChecked(sp.getBoolean("fixmode", true));
        switchPeriodicCheck.setChecked(sp.getBoolean("periodic_check", true));
        intervalLabel.setText(sp.getInt("periodic_check_interval", 10) + "分钟");

        refreshCrashFixDependent(dialogView);

        switchBoot.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("boot", checked).apply();
        });

        switchToast.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("toast", checked).apply();
        });

        switchUserOnly.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("useronly", checked).apply();
            Sort();
            runOnUiThread(() -> listView.setAdapter(new adapter(tmp)));
        });

        switchHide.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("hide", checked).apply();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ((ActivityManager) getSystemService(Service.ACTIVITY_SERVICE)).getAppTasks().get(0).setExcludeFromRecents(checked);
            }
        });

        switchDelayDaemon.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("delay_daemon", checked).apply();
        });

        final Switch crashFixRef = switchCrashFix;
        final CompoundButton.OnCheckedChangeListener[] crashFixListenerHolder = new CompoundButton.OnCheckedChangeListener[1];
        crashFixListenerHolder[0] = (btn, checked) -> {
            if (checked) {
                ShellUtil.reset();
                ShellUtil.getPermissionState();
                if (!ShellUtil.hasAnyPermission()) {
                    crashFixRef.setOnCheckedChangeListener(null);
                    crashFixRef.setChecked(false);
                    crashFixRef.setOnCheckedChangeListener(crashFixListenerHolder[0]);
                    requestCrashFixPermission(false);
                    return;
                }
            }
            sp.edit().putBoolean("crashfix", checked).putBoolean("crashfix_auto_disabled", false).apply();
            refreshCrashFixDependent(dialogView);
        };
        switchCrashFix.setOnCheckedChangeListener(crashFixListenerHolder[0]);

        final Switch unlockCrashCheckRef = switchUnlockCrashCheck;
        final CompoundButton.OnCheckedChangeListener[] unlockCrashCheckListenerHolder = new CompoundButton.OnCheckedChangeListener[1];
        unlockCrashCheckListenerHolder[0] = (btn, checked) -> {
            if (checked && !sp.getBoolean("unlock_crash_dialog_dismissed", false)) {
                new AlertDialog.Builder(this)
                        .setTitle("提示")
                        .setMessage("经过测试，开启自启动权限才可稳定接收解锁广播信号，建议去系统设置开启无障碍管理器自启动。")
                        .setNegativeButton("不再提示", (d, w) -> {
                            sp.edit().putBoolean("unlock_crash_dialog_dismissed", true).apply();
                            sp.edit().putBoolean("unlock_crash_check", true).apply();
                            unlockCrashCheckRef.setOnCheckedChangeListener(null);
                            unlockCrashCheckRef.setChecked(true);
                            unlockCrashCheckRef.setOnCheckedChangeListener(unlockCrashCheckListenerHolder[0]);
                        })
                        .setPositiveButton("确定", (d, w) -> {
                            sp.edit().putBoolean("unlock_crash_check", true).apply();
                            unlockCrashCheckRef.setOnCheckedChangeListener(null);
                            unlockCrashCheckRef.setChecked(true);
                            unlockCrashCheckRef.setOnCheckedChangeListener(unlockCrashCheckListenerHolder[0]);
                        })
                        .create().show();
                return;
            }
            sp.edit().putBoolean("unlock_crash_check", checked).apply();
        };
        switchUnlockCrashCheck.setOnCheckedChangeListener(unlockCrashCheckListenerHolder[0]);

        switchFixMode.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("fixmode", checked).apply();
        });

        switchPeriodicCheck.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("periodic_check", checked).apply();
            if (checked) {
                TimerReceiver.scheduleNext(MainActivity.this);
            } else {
                TimerReceiver.cancel(MainActivity.this);
            }
        });

        intervalLabel.setOnClickListener(v -> showIntervalDialog(() -> {
            intervalLabel.setText(sp.getInt("periodic_check_interval", 10) + "分钟");
        }));

        notifyCustomBtn.setOnClickListener(v -> showNotifyCustomDialog());

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(dialogView)
                .setPositiveButton("关闭", null)
                .create().show();
    }

    private void refreshCrashFixDependent(View dialogView) {
        boolean crashFixEnabled = ((Switch) dialogView.findViewById(R.id.crashfix)).isChecked();
        dialogView.findViewById(R.id.unlock_crash_check).setEnabled(crashFixEnabled);
        dialogView.findViewById(R.id.fixmode).setEnabled(crashFixEnabled);
        dialogView.findViewById(R.id.periodic_check).setEnabled(crashFixEnabled);
        dialogView.findViewById(R.id.periodic_interval_label).setEnabled(crashFixEnabled);
    }

    //这个是用于适配列表中的每一项设置项的显示
    public class adapter extends BaseAdapter {
        private final List<AccessibilityServiceInfo> list;


        public adapter(List<AccessibilityServiceInfo> list) {
            super();
            this.list = list;
        }

        public int getCount() {
            return list.size();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item, null);
            holder = new ViewHolder();
            holder.texta = convertView.findViewById(R.id.a);
            holder.textb = convertView.findViewById(R.id.b);
            holder.imageView = convertView.findViewById(R.id.c);
            holder.sw = convertView.findViewById(R.id.s);
            holder.ib = convertView.findViewById(R.id.ib);
            convertView.setTag(holder);
            AccessibilityServiceInfo info = list.get(position);
            String rawServiceName = info.getId();
            String serviceName = normalizeServiceId(rawServiceName);
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
            String[] packageName = Pattern.compile("/").split(serviceName);
            Drawable icon = null;
            String Packagelabel = null;
            String ServiceLabel = null;
            String Description = null;
            try {
                icon = pm.getApplicationIcon(packageName[0]);
                Packagelabel = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName[0], PackageManager.GET_META_DATA)));
                ServiceLabel = pm.getServiceInfo(new ComponentName(packageName[0], packageName[1]), PackageManager.MATCH_DEFAULT_ONLY).loadLabel(pm).toString();
                Description = info.loadDescription(pm);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            if (ServiceLabel == null) ServiceLabel = Packagelabel;
            holder.imageView.setImageDrawable(icon);
            holder.textb.setText(Packagelabel.equals(ServiceLabel) ? ServiceLabel : String.format("%s/%s", Packagelabel, ServiceLabel));
            holder.texta.setText(Description == null || Description.length() == 0 ? "该服务没有描述" : Description);


            holder.ib.setImageResource(containsService(daemon, serviceName) ? R.drawable.lock1 : R.drawable.lock);
//            holder.sw.setEnabled(!containsService(daemon, serviceName));
            holder.ib.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (checkPermission()) {
                        createPermissionDialog();
                        return;
                    }
                    if (containsService(daemon, serviceName)) {
                        String[] entries = daemon.split(":");
                        StringBuilder newList = new StringBuilder();
                        for (String entry : entries) {
                            if (entry.isEmpty()) continue;
                            if (serviceComponent.equals(ComponentName.unflattenFromString(entry))) continue;
                            if (newList.length() > 0) newList.append(":");
                            newList.append(entry);
                        }
                        daemon = newList.toString();
                    } else {
                        daemon = serviceName + ":" + daemon;
                    }
                    sp.edit().putString("daemon", daemon).apply();
                    holder.ib.setImageResource(containsService(daemon, serviceName) ? R.drawable.lock1 : R.drawable.lock);
//                    holder.sw.setEnabled(!containsService(daemon, serviceName));
                    StartForeGroundDaemon();
                }
            });
            holder.sw.setChecked(isServiceEnabled(serviceName, settingValue));
            holder.ib.setVisibility(holder.sw.isChecked() ? View.VISIBLE : View.INVISIBLE);
            holder.sw.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (checkPermission()) {
                        createPermissionDialog();
                        holder.sw.setChecked(!holder.sw.isChecked());
                    } else {

                        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                        if (s == null) s = "";

                        if (holder.sw.isChecked()) {
                            if (!isServiceEnabled(serviceName, s)) {
                                tmpSettingValue = serviceName + ":" + s;
                            } else {
                                tmpSettingValue = s;
                            }
                        } else {
                            StringBuilder sb = new StringBuilder();
                            for (String entry : s.split(":")) {
                                if (entry.isEmpty()) continue;
                                ComponentName entryCn = ComponentName.unflattenFromString(entry);
                                if (serviceComponent.equals(entryCn)) continue;
                                if (sb.length() > 0) sb.append(":");
                                sb.append(entry);
                            }
                            tmpSettingValue = sb.toString();
                        }

                        Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);
                        holder.ib.setVisibility(holder.sw.isChecked() ? View.VISIBLE : View.INVISIBLE);

                    }
                }
            });


            //点击某个项目的空白处将展示该服务的详细信息，下面的代码是解析各类FLAG的，挺麻烦，不过没别的方法。
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    int fb = info.feedbackType;
                    String feedback = "";
                    if ((fb & 32) != 0) feedback += "盲文反馈\n";
                    if ((fb & 16) != 0) feedback += "通用反馈\n";
                    if ((fb & 8) != 0) feedback += "视觉反馈\n";
                    if ((fb & 4) != 0) feedback += "可听（未说出）反馈\n";
                    if ((fb & 2) != 0) feedback += "触觉反馈\n";
                    if ((fb & 1) != 0) feedback += "口头反馈\n";
                    if (feedback.equals("")) feedback = "无\n";


                    int cap = info.getCapabilities();
                    String capa = "";
                    if ((cap & 32) != 0) capa += "执行手势\n";
                    if ((cap & 16) != 0) capa += "控制显示器放大率\n";
                    if ((cap & 8) != 0) capa += "监听和拦截按键事件\n";
                    if ((cap & 4) != 0) capa += "请求增强的Web辅助功能增强功能。 例如，安装脚本以使网页内容更易于访问\n";
                    if ((cap & 2) != 0) capa += "请求触摸探索模式，使触屏操作变成鼠标操作\n";
                    if ((cap & 1) != 0) capa += "读取屏幕内容\n";
                    if (capa.equals("")) capa = "无\n";

                    int eve = info.eventTypes;
                    String event = "";
                    if ((eve & 33554432) != 0) event += "当前正在阅读用户屏幕上下文的助理事件\n";
                    if ((eve & 16777216) != 0) event += "点击控件上下文的事件\n";
                    if ((eve & 8388608) != 0) event += "窗口更改的事件\n";
                    if ((eve & 4194304) != 0) event += "用户结束触摸屏幕的事件\n";
                    if ((eve & 2097152) != 0) event += "用户开始触摸屏幕的事件\n";
                    if ((eve & 1048576) != 0) event += "结束手势检测的事件\n";
                    if ((eve & 524288) != 0) event += "开始手势检测事件\n";
                    if ((eve & 262144) != 0) event += "遍历视图文本事件\n";
                    if ((eve & 131072) != 0) event += "清除可访问性焦点事件\n";
                    if ((eve & 65536) != 0) event += "获得可访问性焦点的事件\n";
                    if ((eve & 32768) != 0) event += "发布公告的应用程序的事件\n";
                    if ((eve & 16384) != 0) event += "更改选中文本的事件\n";
                    if ((eve & 8192) != 0) event += "滚动视图的事件\n";
                    if ((eve & 4096) != 0) event += "窗口内容更改的事件\n";
                    if ((eve & 2048) != 0) event += "结束触摸探索手势的事件\n";
                    if ((eve & 1024) != 0) event += "开始触摸探索手势的事件\n";
                    if ((eve & 512) != 0) event += "控件结束文字输入事件\n";
                    if ((eve & 256) != 0) event += "控件接受文字输入事件\n";
                    if ((eve & 128) != 0) event += "通知状态改变的事件\n";
                    if ((eve & 64) != 0) event += "窗口状态更改的事件\n";
                    if ((eve & 32) != 0) event += "文本框的文字改变事件\n";
                    if ((eve & 16) != 0) event += "控件获得焦点的事件\n";
                    if ((eve & 8) != 0) event += "控件被选取的事件\n";
                    if ((eve & 4) != 0) event += "长按控件的事件\n";
                    if ((eve & 2) != 0) event += "点击控件的事件\n";
                    if (event.equals("")) event = "无\n";


                    String range = info.packageNames == null ? "全局生效" : Arrays.toString(info.packageNames).replace("[", "").replace("]", "").replace(", ", "\n").replace(",", "\n");

                    int fg = info.flags;
                    String flag = "";
                    if ((fg & 64) != 0) flag += "访问所有交互式窗口的内容\n";
                    if ((fg & 32) != 0) flag += "监听和拦截按键事件\n";
                    if ((fg & 16) != 0) flag += "获取屏幕视图上所有控件的ID\n";
                    if ((fg & 8) != 0) flag += "启用Web可访问性增强扩展\n";
                    if ((fg & 4) != 0) flag += "要求系统进入触摸探索模式\n";
                    if ((fg & 2) != 0) flag += "查询窗口中的不重要内容\n";
                    if ((fg & 1) != 0) flag += "默认\n";
                    if (flag.equals("")) flag = "无\n";


                    try {
                        final ScrollView scrollView = new ScrollView(MainActivity.this);
                        final TextView textView = new TextView(MainActivity.this);
                        textView.setTextIsSelectable(true);
                        textView.setPadding(40, 20, 40, 20);
                        textView.setTextSize(18f);
                        textView.setAlpha(0.8f);
                        textView.setTextColor(night ? Color.WHITE : Color.BLACK);
                        textView.setText(String.format("服务类名：\n%s\n\n特殊能力：\n%s\n生效范围：\n%s\n\n反馈方式：\n%s\n捕获事件类型：\n%s\n特殊标志：\n%s", serviceName, capa, range, feedback, event, flag));
                        scrollView.addView(textView);
                        if (info.getSettingsActivityName() != null && info.getSettingsActivityName().length() > 0)
                            builder.setNegativeButton("设置", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    try {
                                        startActivity(new Intent().setComponent(new ComponentName(packageName[0], info.getSettingsActivityName())));
                                    } catch (Exception ignored) {
                                    }
                                }
                            });

                        builder
                                .setIcon(pm.getApplicationIcon(packageName[0]))
                                .setView(scrollView).setTitle("服务详细信息")
                                .setPositiveButton("知道了", null)
                                .create().show();
                    } catch (Exception ignored) {
                    }
                }
            });
            String finalServiceLabel = ServiceLabel;
            convertView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    if (!containsService(top, serviceName)) {
                        top = serviceName + ":" + top;
                        Toast.makeText(MainActivity.this, "已将" + finalServiceLabel + "置顶", Toast.LENGTH_SHORT).show();
                    } else {
                        String[] entries = top.split(":");
                        StringBuilder newTop = new StringBuilder();
                        for (String entry : entries) {
                            if (entry.isEmpty()) continue;
                            if (serviceComponent.equals(ComponentName.unflattenFromString(entry))) continue;
                            if (newTop.length() > 0) newTop.append(":");
                            newTop.append(entry);
                        }
                        top = newTop.toString();
                        Toast.makeText(MainActivity.this, "已将" + finalServiceLabel + "取消置顶", Toast.LENGTH_SHORT).show();
                    }
                    sp.edit().putString("top", top).apply();
                    Sort();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            listView.setAdapter(new adapter(tmp));
                        }
                    });
                    return true;
                }
            });
            if (top.contains(serviceName))
                convertView.setBackgroundColor(night ? Color.DKGRAY : Color.LTGRAY);
            return convertView;
        }

        private void createPermissionDialog() {
            String cmd = "pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage("安卓5.1和更低版本的设备，需将本APP转换为系统应用。\n\n安卓6.0及更高版本的设备，在下面三个方法中任选一个均可：\n1.连接电脑USB调试后在电脑CMD执行以下命令：\nadb shell " + cmd + "\n\n2.root激活。\n\n3.Shizuku激活。")
                    .setTitle("需要安全设置写入权限")
                    .setPositiveButton("复制命令", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", "adb shell " + cmd));
                            Toast.makeText(MainActivity.this, "命令已复制到剪切板", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("root激活", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialoginterface, int i) {
                            Process p;
                            try {
                                p = Runtime.getRuntime().exec("su");
                                DataOutputStream o = new DataOutputStream(p.getOutputStream());
                                o.writeBytes(cmd + "\nexit\n");
                                o.flush();
                                o.close();
                                p.waitFor();
                                if (p.exitValue() == 0) {
                                    Toast.makeText(MainActivity.this, "成功激活", Toast.LENGTH_SHORT).show();
                                }
                            } catch (IOException | InterruptedException ignored) {
                                Toast.makeText(MainActivity.this, "激活失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setNeutralButton("Shizuku激活", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) check();
                        }
                    })
                    .create().show();
        }

        class ViewHolder {

            TextView texta;
            TextView textb;
            ImageView imageView;
            Switch sw;
            ImageButton ib;
        }


    }

    private void showNotifyCustomDialog() {
        String currentTitle = sp.getString("notify_title", "海绵宝宝，猜猜我有几颗糖");
        String currentText = sp.getString("notify_text", "猜对了两颗都给你！");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText titleInput = new EditText(this);
        titleInput.setHint("通知标题");
        titleInput.setText(currentTitle);
        titleInput.setSingleLine();
        layout.addView(titleInput);

        final EditText textInput = new EditText(this);
        textInput.setHint("通知内容");
        textInput.setText(currentText);
        textInput.setSingleLine();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 20;
        textInput.setLayoutParams(params);
        layout.addView(textInput);

        new AlertDialog.Builder(this)
                .setTitle("自定义通知栏文字")
                .setView(layout)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String newTitle = titleInput.getText().toString().trim();
                        String newText = textInput.getText().toString().trim();
                        if (newTitle.isEmpty()) newTitle = "海绵宝宝，猜猜我有几颗糖";
                        if (newText.isEmpty()) newText = "猜对了两颗都给你！";
                        sp.edit().putString("notify_title", newTitle).apply();
                        sp.edit().putString("notify_text", newText).apply();
                        Toast.makeText(MainActivity.this, "已保存，保活服务重启后生效", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        sp.edit().putString("notify_title", "海绵宝宝，猜猜我有几颗糖").apply();
                        sp.edit().putString("notify_text", "猜对了两颗都给你！").apply();
                        Toast.makeText(MainActivity.this, "已恢复默认，保活服务重启后生效", Toast.LENGTH_SHORT).show();
                    }
                })
                .create().show();
    }

    private void showIntervalDialog(Runnable onSaved) {
        int currentInterval = sp.getInt("periodic_check_interval", 10);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText input = new EditText(this);
        input.setHint("10");
        input.setText(String.valueOf(currentInterval));
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(inputParams);
        layout.addView(input);

        TextView unit = new TextView(this);
        unit.setText("分钟");
        unit.setTextSize(16f);
        if (night) unit.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        unitParams.leftMargin = 16;
        unit.setLayoutParams(unitParams);
        layout.addView(unit);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设置定时检测间隔（非精确时间）")
                .setView(layout)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String inputStr = input.getText().toString().trim();
                int minutes;
                try {
                    minutes = Integer.parseInt(inputStr);
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "请输入有效数字", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (minutes < 1 || minutes > 1440) {
                    Toast.makeText(MainActivity.this, "请输入1-1440之间的数字", Toast.LENGTH_SHORT).show();
                    return;
                }
                sp.edit().putInt("periodic_check_interval", minutes).apply();
                TimerReceiver.cancel(MainActivity.this);
                LogUtil.log(MainActivity.this, "[定时检测] 已取消旧定时");
                TimerReceiver.scheduleNext(MainActivity.this);
                LogUtil.log(MainActivity.this, "[定时检测] 已设置新间隔 = " + minutes + " 分钟");
                Toast.makeText(MainActivity.this, "定时检测间隔已设为 " + minutes + " 分钟", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                if (onSaved != null) onSaved.run();
            }
        });
    }

    //查看APP是否可以写入安全设置
    boolean checkPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            perm = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        else {
            PackageInfo packageInfo = new PackageInfo();
            try {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_CONFIGURATIONS);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            perm = (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }
        return !perm;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 0 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            StartForeGroundDaemon();
        }
    }

    //启动前台服务，进行保活!
    void StartForeGroundDaemon() {

        if (checkPermission()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
            Toast.makeText(this, "请授予通知权限", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(new Intent(this, daemonService.class));
        else
            startService(new Intent(this, daemonService.class));

    }


}