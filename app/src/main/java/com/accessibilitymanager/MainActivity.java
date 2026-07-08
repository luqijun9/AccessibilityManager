package com.accessibilitymanager;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
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
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Gravity;
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
import android.widget.AbsListView;
import android.widget.PopupWindow;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import android.util.Log;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    /** 标记管理器是否在前台，供 daemonService 判断 */
    public static volatile boolean sIsForeground = false;

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
    private boolean mPendingFixModeRequest = false;//是否有待处理的强杀模式权限请求
    private static final int REQUEST_SETTINGS = 1001;
    private long mLastAutoUpdateCheckTime = 0;

    // 收藏相关
    private ImageButton fabAdd;
    private boolean mIsFavoritesTab = false;
    private boolean mFabHidden = false;
    private String favorites = "";
    private List<AccessibilityServiceInfo> mFavoritesList;
    private TextView mPinHint;
    private final Map<String, ServiceCache> mServiceCache = new HashMap<>();
    private View mTitleView;
    private TextView mTitleText;

    LinearLayout batteryWarning;//电池警告布局
    TextView batteryWarningText;//电池警告文本
    TextView batteryWarningGo;//电池警告“去设置”按钮
    TextView batteryWarningDismiss;//电池警告“关闭”按钮
    private Toolbar toolbar;

    //搜索相关
    private View mSearchView;
    private EditText mSearchInput;
    private boolean mIsSearching = false;
    private Menu mMenu;
    private List<AccessibilityServiceInfo> mFilteredList;

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
                        if (tmp == null || tmp.isEmpty() || firstPosition < 0) return;
                        lastPosition = Math.min(lastPosition, tmp.size() - 1);
                        if (firstPosition > lastPosition) return;
                        for (int i = firstPosition; i <= lastPosition; i++) {
                            View view = listView.getChildAt(i - firstPosition);
                            if (view == null) continue;
                            boolean isChecked = isServiceEnabled(tmp.get(i).getId(), settingValue);
                            View ib = view.findViewById(R.id.ib);
                            if (ib != null) ib.setVisibility(isChecked ? View.VISIBLE : View.INVISIBLE);
                            Switch sw = view.findViewById(R.id.s);
                            if (sw != null) sw.setChecked(isChecked);
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

        toolbar = findViewById(R.id.toolbar);
        setupToolbarTitle();
        toolbar.setPaddingRelative(
                toolbar.getPaddingStart(),
                toolbar.getPaddingTop(),
                (int) (8 * getResources().getDisplayMetrics().density + 0.5f),
                toolbar.getPaddingBottom()
        );
        toolbar.inflateMenu(R.menu.arrange);
        mMenu = toolbar.getMenu();
        toolbar.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.search) {
                enterSearchMode();
                return true;
            }
            if (itemId == R.id.perm_status) {
                if (sp.getBoolean("crashfix", false) && !ShellUtil.hasDumpPermission(this)) {
                    showDumpPermissionDialog();
                    return true;
                }
                if (sp.getBoolean("fixmode", false) && !ShellUtil.hasAnyPermission()) {
                    showFixModePermissionDialog();
                    return true;
                }
                boolean hasDump = ShellUtil.hasDumpPermission(this);
                int state = ShellUtil.getPermissionState();
                StringBuilder permToast = new StringBuilder();
                if (hasDump) permToast.append("DUMP");
                if (hasDump && state != ShellUtil.PERM_NONE) permToast.append("+");
                if (state == ShellUtil.PERM_ROOT) permToast.append("Root");
                else if (state == ShellUtil.PERM_SHIZUKU) permToast.append("Shizuku");
                if (permToast.length() > 0) {
                    Toast.makeText(this, "已获取" + permToast.toString() + "权限", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "未获取任何权限", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            if (itemId == R.id.settings) {
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                return true;
            }
            if (itemId == R.id.viewlog) {
                startActivity(new Intent(this, LogActivity.class));
                return true;
            }
            return false;
        });
        sp = getSharedPreferences("data", 0);
        updateToolbarMenu();
        setTitle("无障碍管理");

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

        //读取用户设置“是否隐藏后台”，并进行隐藏后台
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<ActivityManager.AppTask> tasks = ((ActivityManager) getSystemService(Service.ACTIVITY_SERVICE)).getAppTasks();
            if (!tasks.isEmpty())
                tasks.get(0).setExcludeFromRecents(sp.getBoolean("hide", true));
        }

        daemon = sp.getString("daemon", "");
        top = sp.getString("top", "");
        Sort();


        listView = findViewById(R.id.list);
        listView.setEmptyView(findViewById(R.id.empty_view));

        // 列表底部提示
        mPinHint = new TextView(this);
        mPinHint.setText("长按服务项可将其置顶");
        mPinHint.setTextColor(getResources().getColor(R.color.text_hint));
        mPinHint.setTextSize(12f);
        mPinHint.setGravity(Gravity.CENTER);
        mPinHint.setPadding(0, 6, 0, 6);
        listView.addFooterView(mPinHint, null, false);

        // ListView 滚动监听：FAB 显示/隐藏动画
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            private int mLastFirstVisibleItem = 0;

            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {}

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (!mIsFavoritesTab || fabAdd.getVisibility() != View.VISIBLE) return;
                if (firstVisibleItem > mLastFirstVisibleItem && !mFabHidden) {
                    // 向下滑动 → 隐藏 FAB
                    fabAdd.animate().cancel();
                    fabAdd.animate().translationY(fabAdd.getHeight() + 32).alpha(0f).setDuration(200)
                            .withEndAction(() -> mFabHidden = true).start();
                } else if (firstVisibleItem < mLastFirstVisibleItem && mFabHidden) {
                    // 向上滑动 → 显示 FAB
                    fabAdd.animate().cancel();
                    fabAdd.animate().translationY(0).alpha(1f).setDuration(200)
                            .withEndAction(() -> mFabHidden = false).start();
                }
                mLastFirstVisibleItem = firstVisibleItem;
            }
        });

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
            AlertDialog privacyDialog = new AlertDialog.Builder(this)
                    .setTitle("隐私政策")
                    .setMessage("本应用不会收集或记录您的任何信息，也不包含任何联网功能。继续使用则代表您同意上述隐私政策。")
                    .setPositiveButton("OK", null).create();
            privacyDialog.setOnDismissListener(d -> showPinHintOnce());
            privacyDialog.show();
            sp.edit().putBoolean("first", false).apply();
        } else if (!sp.getBoolean("pin_hint_shown", false)) {
            showPinHintOnce();
        }


        //如果设备一次都没打开过无障碍设置界面，则下面这个设置项值不存在，同时本APP是无法获取到无障碍设置列表的。所以要在这里加个判断，如果从来没开启过，则需要本APP来给这个设置项写入1来开启。

        new Thread(() -> {
            ShellUtil.reset();
            ShellUtil.getPermissionState();
            runOnUiThread(() -> updateToolbarMenu());
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
            String pkg = getPackageName();
            String grantCmd = "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS; pm grant " + pkg + " android.permission.DUMP";
            String adbCmd = "adb shell \"" + grantCmd + "\"";
            new AlertDialog.Builder(this).setMessage("您的设备尚未启用无障碍服务功能。您可以选择在系统设置-无障碍-打开或关闭任意服务项来激活系统的无障碍服务功能，也可以授权本APP所需权限以解决。\n\n如需授权，请选以下方式之一：\n1.连接电脑USB调试后在电脑CMD执行以下命令：\n" + adbCmd + "\n\n2.root激活。\n\n3.Shizuku激活。")
                    .setTitle("需要授予权限")
                    .setNegativeButton("root激活", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            Process p;
                            try {
                                p = Runtime.getRuntime().exec("su");
                                DataOutputStream o = new DataOutputStream(p.getOutputStream());
                                o.writeBytes(grantCmd + "\nexit\n");
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
                            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", adbCmd));
                            Toast.makeText(MainActivity.this, "命令已复制到剪切板", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("Shizuku激活", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) check(true);
                        }
                    })
                    .create().show();
            try {
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            } catch (Exception ignored) {
            }
        }

        checkBatteryOptimization();

        // ── FAB 初始化 ──
        fabAdd = findViewById(R.id.fab_add);
        fabAdd.setOnClickListener(v -> showAddFavoritesDialog());

        favorites = sp.getString("favorites", "");

        // 恢复上次的 Tab 状态
        mIsFavoritesTab = sp.getBoolean("favorites_tab_active", false);
        switchToTab(mIsFavoritesTab);
    }

    private void Sort() {
        tmp = new ArrayList<>();
        boolean userOnly = sp.getBoolean("useronly", false);
        final String ownServiceId = new ComponentName(MainActivity.this, MyAccessibilityService.class).flattenToString();
        for (AccessibilityServiceInfo info : l) {
            if (userOnly && !isUserApp(info)) continue;
            tmp.add(info);
            // 预加载应用信息到缓存
            String sid = normalizeServiceId(info.getId());
            if (!mServiceCache.containsKey(sid)) {
                String[] parts = Pattern.compile("/").split(sid);
                if (parts.length >= 2) {
                    try {
                        Drawable icon = pm.getApplicationIcon(parts[0]);
                        CharSequence pkgLabel = pm.getApplicationLabel(pm.getApplicationInfo(parts[0], PackageManager.GET_META_DATA));
                        String packagelabel = pkgLabel != null ? pkgLabel.toString() : parts[0];
                        String svcLabel = pm.getServiceInfo(new ComponentName(parts[0], parts[1]), PackageManager.MATCH_DEFAULT_ONLY).loadLabel(pm).toString();
                        String desc = info.loadDescription(pm);
                        mServiceCache.put(sid, new ServiceCache(icon, packagelabel, svcLabel, desc));
                    } catch (Exception ignored) {
                    }
                }
            }
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
                ComponentName ownCn = ComponentName.unflattenFromString(ownServiceId);
                ComponentName cn1 = ComponentName.unflattenFromString(id1);
                ComponentName cn2 = ComponentName.unflattenFromString(id2);
                boolean own1 = ownCn != null && ownCn.equals(cn1);
                boolean own2 = ownCn != null && ownCn.equals(cn2);
                if (own1 && !own2) return -1;
                if (!own1 && own2) return 1;
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

    // ==================== 底栏 Tab & 收藏 ====================

    private void switchToTab(boolean isFavorites) {
        mIsFavoritesTab = isFavorites;
        updateTitleView();

        // 重置 FAB 状态
        if (isFavorites) {
            fabAdd.setVisibility(View.VISIBLE);
            fabAdd.animate().cancel();
            fabAdd.setTranslationY(0);
            fabAdd.setAlpha(1f);
            mFabHidden = false;
            listView.setClipToPadding(false);
            int paddingBottom = (int) (64 * getResources().getDisplayMetrics().density + 0.5f);
            listView.setPaddingRelative(listView.getPaddingStart(), listView.getPaddingTop(), listView.getPaddingEnd(), paddingBottom);
        } else {
            fabAdd.setVisibility(View.GONE);
            listView.setClipToPadding(true);
            listView.setPaddingRelative(listView.getPaddingStart(), listView.getPaddingTop(), listView.getPaddingEnd(), 0);
        }

        // 更新底部提示文本
        mPinHint.setText(isFavorites ? "长按可取消收藏" : "长按服务项可将其置顶");

        if (mIsSearching) {
            exitSearchMode();
        }

        if (isFavorites) {
            // 切换到收藏 Tab
            List<AccessibilityServiceInfo> source = (mFilteredList != null) ? mFilteredList : tmp;
            mFavoritesList = new ArrayList<>();
            for (AccessibilityServiceInfo info : source) {
                if (isServiceFavorite(normalizeServiceId(info.getId()))) {
                    mFavoritesList.add(info);
                }
            }
            if (mFavoritesList.isEmpty()) {
                ((TextView) findViewById(R.id.empty_view)).setText("还没有收藏的服务\n点击右下角 + 添加");
            } else {
                ((TextView) findViewById(R.id.empty_view)).setText("未找到结果");
            }
            listView.setAdapter(new adapter(mFavoritesList));
        } else {
            // 切换到全部 Tab
            ((TextView) findViewById(R.id.empty_view)).setText("未找到结果");
            List<AccessibilityServiceInfo> source = (mFilteredList != null) ? mFilteredList : tmp;
            listView.setAdapter(new adapter(source));
        }
    }

    private void updateTitleView() {
        int textColor = night ? Color.WHITE : Color.BLACK;
        mTitleText.setText(mIsFavoritesTab ? "收藏" : "全部");
        mTitleText.setTextColor(textColor);
        Drawable icon = getResources().getDrawable(mIsFavoritesTab ? R.drawable.ic_star : R.drawable.ic_grid).mutate();
        icon.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN);
        mTitleText.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    private void setupToolbarTitle() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        layout.setClickable(true);
        layout.setFocusable(true);
        TypedArray ta = getTheme().obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground});
        Drawable bg = ta.getDrawable(0);
        ta.recycle();
        layout.setBackground(bg);

        mTitleText = new TextView(this);
        mTitleText.setTextSize(18);
        mTitleText.setIncludeFontPadding(false);
        mTitleText.setCompoundDrawablePadding((int) (6 * getResources().getDisplayMetrics().density + 0.5f));
        mTitleText.setPaddingRelative(0, 0, (int) (4 * getResources().getDisplayMetrics().density + 0.5f), 0);

        ImageView arrowDown = new ImageView(this);
        int arrowSize = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        arrowDown.setLayoutParams(new LinearLayout.LayoutParams(arrowSize, arrowSize));
        arrowDown.setImageResource(R.drawable.ic_arrow_down);
        arrowDown.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int arrowMargin = (int) (2 * getResources().getDisplayMetrics().density + 0.5f);
        ((LinearLayout.LayoutParams) arrowDown.getLayoutParams()).setMarginStart(arrowMargin);

        updateTitleView();

        layout.addView(mTitleText);
        layout.addView(arrowDown);

        layout.setOnClickListener(v -> showTabSwitcherPopup(v));

        toolbar.addView(layout, new Toolbar.LayoutParams(
                Toolbar.LayoutParams.WRAP_CONTENT,
                Toolbar.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.START
        ));

        mTitleView = layout;
    }

    private void showTabSwitcherPopup(View anchor) {
        View contentView = getLayoutInflater().inflate(R.layout.popup_tab_switcher, null);
        PopupWindow popup = new PopupWindow(contentView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(8);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            contentView.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            popup.setEnterTransition(new android.transition.Fade());
        }
        popup.showAsDropDown(anchor, (int) (-10 * getResources().getDisplayMetrics().density + 0.5f), (int) (8 * getResources().getDisplayMetrics().density + 0.5f));

        contentView.findViewById(R.id.item_all).setOnClickListener(v -> {
            switchToTab(false);
            popup.dismiss();
        });
        contentView.findViewById(R.id.item_fav).setOnClickListener(v -> {
            switchToTab(true);
            popup.dismiss();
        });
    }

    private boolean isServiceFavorite(String serviceId) {
        return containsService(favorites, serviceId);
    }

    private void saveFavorites() {
        sp.edit().putString("favorites", favorites).apply();
    }

    private void addToFavorites(String serviceId) {
        if (!isServiceFavorite(serviceId)) {
            favorites = serviceId + ":" + favorites;
            saveFavorites();
        }
    }

    private void removeFromFavorites(String serviceId) {
        String[] entries = favorites.split(":");
        StringBuilder sb = new StringBuilder();
        ComponentName target = ComponentName.unflattenFromString(serviceId);
        for (String entry : entries) {
            if (entry.isEmpty()) continue;
            ComponentName cn = ComponentName.unflattenFromString(entry);
            if (target != null && target.equals(cn)) continue;
            if (sb.length() > 0) sb.append(":");
            sb.append(entry);
        }
        favorites = sb.toString();
        saveFavorites();
    }

    private void showAddFavoritesDialog() {
        if (tmp == null || tmp.isEmpty()) return;

        final String[] items = new String[tmp.size()];
        final boolean[] checked = new boolean[tmp.size()];
        final String[] serviceIds = new String[tmp.size()];

        for (int i = 0; i < tmp.size(); i++) {
            AccessibilityServiceInfo info = tmp.get(i);
            String rawName = normalizeServiceId(info.getId());
            serviceIds[i] = rawName;

            String[] parts = rawName.split("/");
            String label;
            try {
                String packageName = parts[0];
                String serviceName = parts.length > 1 ? parts[1] : "";
                String appLabel = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)));
                if (!serviceName.isEmpty()) {
                    android.content.pm.ServiceInfo si = pm.getServiceInfo(
                            new ComponentName(packageName, serviceName),
                            PackageManager.MATCH_DEFAULT_ONLY);
                    String svcLabel = si.loadLabel(pm).toString();
                    label = appLabel.equals(svcLabel) ? appLabel : appLabel + "/" + svcLabel;
                } else {
                    label = appLabel;
                }
            } catch (Exception e) {
                label = parts.length > 1 ? parts[1] : parts[0];
            }
            items[i] = label;
            checked[i] = isServiceFavorite(rawName);
        }

        new AlertDialog.Builder(this)
                .setTitle("添加收藏")
                .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> {
                    if (isChecked) {
                        addToFavorites(serviceIds[which]);
                    } else {
                        removeFromFavorites(serviceIds[which]);
                    }
                })
                .setPositiveButton("确定", (dialog, which) -> {
                    // 如果当前在收藏 Tab，刷新列表
                    if (mIsFavoritesTab) {
                        switchToTab(true);
                    }
                })
                .setNegativeButton("取消", null)
                .create()
                .show();
    }


    //返回键退出APP，用于适配安卓12和高于12的系统上返回键默认仅把APP放后台的问题。
    @Override
    public void onBackPressed() {
        if (mIsSearching) {
            exitSearchMode();
            return;
        }
        finish();
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 保存当前 Tab 状态
        sp.edit().putBoolean("favorites_tab_active", mIsFavoritesTab).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBatteryOptimization();
        if (sp.getBoolean("auto_update", true)) {
            long now = System.currentTimeMillis();
            if (now - mLastAutoUpdateCheckTime < 10_000) {
                return; // 10秒内防重复
            }
            mLastAutoUpdateCheckTime = now;
            long lastCheckTime = sp.getLong("last_update_check_time", 0);
            long currentTime = System.currentTimeMillis();
            long minutesSinceLastCheck = (currentTime - lastCheckTime) / 1000 / 60;
            if (currentTime - lastCheckTime > 24 * 60 * 60 * 1000) {
                LogUtil.log(this, "[自动更新] 距上次检测超过24小时，开始检测更新");
                UpdateChecker.checkForUpdate(MainActivity.this, new UpdateChecker.UpdateListener() {
                    @Override
                    public void onUpdateAvailable(String versionName, String downloadUrl, String releaseNotes) {
                        String skippedVersion = sp.getString("skipped_update_version", "");
                        if (!versionName.equals(skippedVersion)) {
                            LogUtil.log(MainActivity.this, "[自动更新] 发现新版本: " + versionName);
                            showUpdateDialog(versionName, downloadUrl, releaseNotes);
                        } else {
                            LogUtil.log(MainActivity.this, "[自动更新] 发现新版本 " + versionName + " 但已被跳过，不显示弹窗");
                        }
                    }

                    @Override
                    public void onNoUpdate() {
                        LogUtil.log(MainActivity.this, "[自动更新] 当前已是最新版本");
                    }

                    @Override
                    public void onError(String errorMessage) {
                        LogUtil.log(MainActivity.this, "[自动更新] 检测失败: " + errorMessage);
                    }
                });
                sp.edit().putLong("last_update_check_time", currentTime).apply();
                LogUtil.log(this, "[自动更新] 已更新上次检测时间戳=" + currentTime);
            } else {
                // 距上次检测不足24小时，跳过本次检测（不输出日志）
            }
        }
        // ── 检查解锁检测重复触发提示 ──
        if (sp.getBoolean("unlock_duplicate_detected", false)
                && !sp.getBoolean("unlock_duplicate_dialog_dismissed", false)) {
            sp.edit().putBoolean("unlock_duplicate_detected", false).apply();
            showUnlockDuplicateDialog();
        }
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
        LogUtil.log(MainActivity.this, "[权限] Shizuku回调, grantResult=" + grantResult + ", pendingCrashFix=" + mPendingCrashFixRequest + ", pendingFixMode=" + mPendingFixModeRequest);
        ShellUtil.reset();
        if (mPendingCrashFixRequest) {
            mPendingCrashFixRequest = false;
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                LogUtil.log(MainActivity.this, "[权限] 用户已授权(grantResult=GRANTED)，通过 Shizuku 授予权限");
                // 先通过 check() 使用 Shizuku 授予 WRITE_SECURE_SETTINGS + DUMP，再启用崩溃修复
                check(false);
                enableCrashFix();
            } else {
                LogUtil.log(MainActivity.this, "[权限] 用户拒绝授权(grantResult=" + grantResult + ")");
                Toast.makeText(MainActivity.this, "获取shizuku权限失败", Toast.LENGTH_SHORT).show();
            }
        } else if (mPendingFixModeRequest) {
            mPendingFixModeRequest = false;
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                LogUtil.log(MainActivity.this, "[权限] Shizuku 已授权，启用强杀模式");
                ShellUtil.reset();
                updateToolbarMenu();
                Toast.makeText(MainActivity.this, "已获取Shizuku权限，强杀模式已开启", Toast.LENGTH_SHORT).show();
            } else {
                LogUtil.log(MainActivity.this, "[权限] Shizuku 授权被拒绝");
                Toast.makeText(MainActivity.this, "获取Shizuku权限失败，强杀模式未开启", Toast.LENGTH_SHORT).show();
            }
        } else if (grantResult == PackageManager.PERMISSION_GRANTED) {
            // 权限被授予时执行 check() 尝试授予 WRITE_SECURE_SETTINGS
            // 注意：拒绝时不能调用 check()，否则 check() 内部会重新 requestPermission 导致无限循环
            check(true);
        }
    };

    //检查Shizuku权限，申请Shizuku权限的函数
    private void check(boolean showSuccessToast) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED)
            return;
        String grantBoth = "pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS; pm grant " + getPackageName() + " android.permission.DUMP";
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
                out.write((grantBoth + "\nexit\n").getBytes());
                out.flush();
                out.close();
                p.waitFor();
                if (p.exitValue() == 0 && showSuccessToast) {
                    Toast.makeText(this, "成功激活", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException | InterruptedException ioException) {
                Toast.makeText(this, "激活失败", Toast.LENGTH_SHORT).show();
            }
        }

    }

    private void requestCrashFixPermission() {
        LogUtil.log(this, "[权限] 请求权限（来自崩溃修复开关）");
        ShellUtil.reset();
        if (ShellUtil.hasAnyPermission()) {
            LogUtil.log(this, "[权限] 已拥有权限，直接启用崩溃修复");
            enableCrashFix();
            return;
        }

        if (ShellUtil.isShizukuRunning()) {
            LogUtil.log(this, "[权限] Shizuku运行中，显示权限不足对话框");
            showNoPermissionDialog(true);
        } else {
            LogUtil.log(this, "[权限] Shizuku未运行，显示权限不足对话框");
            showNoPermissionDialog(true);
        }
    }

    private void enableCrashFix() {
        LogUtil.log(this, "[权限] 启用崩溃修复");
        sp.edit().putBoolean("crashfix", true).putBoolean("crashfix_auto_disabled", false).apply();
        updateToolbarMenu();
        boolean hasDump = ShellUtil.hasDumpPermission(this);
        int state = ShellUtil.getPermissionState();
        StringBuilder permInfo = new StringBuilder();
        if (hasDump) permInfo.append("DUMP");
        if (hasDump && state != ShellUtil.PERM_NONE) permInfo.append("+");
        if (state == ShellUtil.PERM_ROOT) permInfo.append("Root");
        else if (state == ShellUtil.PERM_SHIZUKU) permInfo.append("Shizuku");
        LogUtil.log(this, "[权限] 当前权限: " + permInfo.toString());
        Toast.makeText(this, "已获取" + permInfo.toString() + "权限，崩溃检测已开启", Toast.LENGTH_SHORT).show();
        if (!daemon.isEmpty()) {
            StartForeGroundDaemon();
        }
    }

    /** 显示 DUMP 权限不足的对话框（与设置页打开崩溃检测总开关时一致） */
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
        String msg = "崩溃检测需要 DUMP 权限。\n在下面三个方法中任选一个即可：\n\n"
                + "1. 连接电脑USB调试后在电脑CMD执行以下命令：\n"
                + "adb shell " + cmd + "\n\n"
                + "2. Root 激活。\n\n"
                + "3. Shizuku 激活。";
        ((TextView) dv.findViewById(R.id.perm_msg)).setText(msg);

        // 复制命令按钮
        ((TextView) dv.findViewById(R.id.perm_btn_positive)).setText("复制命令");
        dv.findViewById(R.id.perm_btn_positive).setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("c", "adb shell " + cmd));
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
                    DataOutputStream o = new DataOutputStream(p.getOutputStream());
                    o.writeBytes(cmd + "\nexit\n");
                    o.flush();
                    o.close();
                    p.waitFor();
                    runOnUiThread(() -> {
                        if (p.exitValue() == 0) {
                            ShellUtil.reset();
                            updateToolbarMenu();
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

        // 取消按钮 - 点击取消不会关闭崩溃修复（仅查看状态）
        dv.findViewById(R.id.perm_btn_neutral).setOnClickListener(v -> {
            LogUtil.log(this, "[权限] 对话框取消");
            permDialog.dismiss();
        });

        permDialog.show();
    }

    /** 显示强杀功能需要 Root/Shizuku 权限的对话框 */
    private void showFixModePermissionDialog() {
        boolean shizukuRunning = ShellUtil.isShizukuRunning();
        LogUtil.log(this, "[权限] 显示强杀权限不足对话框 shizukuRunning=" + shizukuRunning);

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

        // 隐藏取消按钮（第二行）
        dv.findViewById(R.id.perm_btn_neutral).setVisibility(View.GONE);

        if (shizukuRunning) {
            // 第一行：取消 + 申请Shizuku权限
            dv.findViewById(R.id.perm_btn_root).setVisibility(View.VISIBLE);
            ((TextView) dv.findViewById(R.id.perm_btn_root)).setText("取消");
            dv.findViewById(R.id.perm_btn_root).setOnClickListener(v -> permDialog.dismiss());

            dv.findViewById(R.id.perm_btn_shizuku).setVisibility(View.GONE);
            ((TextView) dv.findViewById(R.id.perm_btn_positive)).setText("申请Shizuku权限");
            dv.findViewById(R.id.perm_btn_positive).setOnClickListener(v -> {
                LogUtil.log(MainActivity.this, "[权限] 对话框中点击申请Shizuku权限（强杀模式）");
                mPendingFixModeRequest = true;
                try {
                    Shizuku.requestPermission(0);
                } catch (Exception e) {
                    LogUtil.log(MainActivity.this, "[权限] 对话框申请异常: " + e.getClass().getSimpleName());
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

    private void showNoPermissionDialog(boolean closeCrashFixOnCancel) {
        boolean shizukuRunning = ShellUtil.isShizukuRunning();
        boolean hasDump = ShellUtil.hasDumpPermission(this);
        LogUtil.log(this, "[权限] 显示权限不足对话框 shizukuRunning=" + shizukuRunning + " hasDump=" + hasDump + " closeCrashFixOnCancel=" + closeCrashFixOnCancel);
        StringBuilder message = new StringBuilder();
        if (!hasDump) {
            message.append("崩溃检测需要 DUMP 权限。\n请通过 ADB 授予：adb shell pm grant " + getPackageName() + " android.permission.DUMP\n\n");
        }
        if (!ShellUtil.hasAnyPermission()) {
            message.append("崩溃修复（force-stop）需要 Root 或 Shizuku 权限。\n");
            if (shizukuRunning) {
                message.append("已检测到Shizuku正在运行，请授权本应用使用Shizuku。");
            } else {
                message.append("当前未检测到任何权限。\n请安装Shizuku并授权，或获取root权限后重试。");
            }
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
                if (closeCrashFixOnCancel) {
                    sp.edit().putBoolean("crashfix", false).putBoolean("crashfix_auto_disabled", false).apply();
                    LogUtil.log(MainActivity.this, "[权限] 对话框取消，崩溃修复已关闭");
                    Toast.makeText(MainActivity.this, "崩溃修复已关闭", Toast.LENGTH_SHORT).show();
                    updateToolbarMenu();
                } else {
                    LogUtil.log(MainActivity.this, "[权限] 对话框取消");
                }
            });
        } else {
            builder.setPositiveButton("确定", null);
        }

        AlertDialog dialog = builder.create();
        dialog.show();
        if (shizukuRunning) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTransformationMethod(null);
        }
        updateToolbarMenu();
    }

    //一些收尾工作，取消注册监听器什么的
    @Override
    protected void onDestroy() {
        if (listenerAdded) Shizuku.removeRequestPermissionResultListener(RL);

        getContentResolver().unregisterContentObserver(mContentOb);
        super.onDestroy();
    }

    private void updateToolbarMenu() {
        Menu menu = toolbar.getMenu();
        if (menu == null) return;
        int menuColor = night ? Color.WHITE : Color.BLACK;

        // 日志按钮 - 使用与权限显示一致的样式
        MenuItem logItem = menu.findItem(R.id.viewlog);
        if (logItem != null) {
            SpannableStringBuilder logSsb = new SpannableStringBuilder("日志");
            logSsb.setSpan(new ForegroundColorSpan(menuColor),
                    0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            logItem.setTitle(logSsb);
        }

        // 权限显示按钮 - 每次更新菜单时重新检测权限状态，防止 Shizuku 停止后仍显示旧状态
        MenuItem permItem = menu.findItem(R.id.perm_status);
        if (permItem != null) {
            ShellUtil.reset();
            boolean crashFixEnabled = sp.getBoolean("crashfix", false);
            boolean fixModeEnabled = sp.getBoolean("fixmode", false);
            boolean hasDump = ShellUtil.hasDumpPermission(this);
            String text;
            int color = menuColor;
            if (crashFixEnabled && !hasDump) {
                // 崩溃检测仅依赖 DUMP 权限，没有 DUMP 就显示不可用
                text = "崩溃检测不可用ⓘ";
                color = Color.rgb(0xFF, 0x00, 0x00);
            } else if (fixModeEnabled && !ShellUtil.hasAnyPermission()) {
                // 强杀功能需要 Root/Shizuku 权限
                text = "强杀功能不可用ⓘ";
                color = Color.rgb(0xFF, 0x00, 0x00);
            } else {
                text = null;
            }
            if (text != null) {
                SpannableStringBuilder ssb = new SpannableStringBuilder(text);
                ssb.setSpan(new ForegroundColorSpan(color),
                        0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                permItem.setTitle(ssb);
                permItem.setVisible(true);
            } else {
                permItem.setVisible(false);
            }
        }
    }

    //==================== 搜索功能 ====================
    private void enterSearchMode() {
        if (mIsSearching) return;
        mIsSearching = true;

        //隐藏标题和菜单项
        toolbar.setTitle("");
        if (mMenu != null) {
            mMenu.findItem(R.id.search).setVisible(false);
            mMenu.findItem(R.id.viewlog).setVisible(false);
            mMenu.findItem(R.id.perm_status).setVisible(false);
            mMenu.findItem(R.id.settings).setVisible(false);
        }

        //添加搜索视图到Toolbar
        mSearchView = getLayoutInflater().inflate(R.layout.toolbar_search, null);
        Toolbar.LayoutParams params = new Toolbar.LayoutParams(
                Toolbar.LayoutParams.MATCH_PARENT,
                Toolbar.LayoutParams.MATCH_PARENT);
        toolbar.addView(mSearchView, params);

        mSearchInput = mSearchView.findViewById(R.id.search_input);
        ImageButton closeBtn = mSearchView.findViewById(R.id.search_close);

        mSearchInput.addTextChangedListener(searchWatcher);
        closeBtn.setOnClickListener(v -> exitSearchMode());

        mSearchInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(mSearchInput, 0);
    }

    private void exitSearchMode() {
        if (!mIsSearching) return;
        mIsSearching = false;

        //移除搜索视图
        toolbar.removeView(mSearchView);
        mSearchView = null;
        mSearchInput = null;

        //恢复菜单项
        if (mMenu != null) {
            mMenu.findItem(R.id.search).setVisible(true);
            mMenu.findItem(R.id.viewlog).setVisible(true);
            mMenu.findItem(R.id.perm_status).setVisible(true);
            mMenu.findItem(R.id.settings).setVisible(true);
        }

        //刷新排序并恢复完整列表
        Sort();
        if (mIsFavoritesTab) {
            // 收藏 Tab → 恢复收藏列表
            List<AccessibilityServiceInfo> favList = new ArrayList<>();
            for (AccessibilityServiceInfo info : tmp) {
                if (isServiceFavorite(normalizeServiceId(info.getId()))) {
                    favList.add(info);
                }
            }
            mFavoritesList = favList;
            listView.setAdapter(new adapter(mFavoritesList));
            if (mFavoritesList.isEmpty()) {
                ((TextView) findViewById(R.id.empty_view)).setText("还没有收藏的服务\n点击右下角 + 添加");
            } else {
                ((TextView) findViewById(R.id.empty_view)).setText("未找到结果");
            }
        } else {
            listView.setAdapter(new adapter(tmp));
        }
        mFilteredList = null;

        // 刷新权限菜单显示（退出搜索时恢复的菜单项可能已过期）
        updateToolbarMenu();

        //隐藏键盘
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(toolbar.getWindowToken(), 0);
    }

    private final TextWatcher searchWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override
        public void afterTextChanged(Editable s) {
            String query = s.toString().trim();
            filterServices(query);
        }
    };

    private void filterServices(String query) {
        // 根据当前 Tab 确定搜索源
        List<AccessibilityServiceInfo> source;
        if (mIsFavoritesTab) {
            // 收藏 Tab → 只在已收藏服务中搜索
            source = new ArrayList<>();
            for (AccessibilityServiceInfo info : tmp) {
                if (isServiceFavorite(normalizeServiceId(info.getId()))) {
                    source.add(info);
                }
            }
        } else {
            source = tmp;
        }

        if (query.isEmpty()) {
            mFilteredList = null;
            listView.setAdapter(new adapter(source));
            return;
        }

        mFilteredList = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (AccessibilityServiceInfo info : source) {
            String rawName = info.getId();
            String[] parts = rawName.split("/");
            if (parts.length < 1) continue;

            String packageName = parts[0];
            String serviceName = parts.length > 1 ? parts[1] : "";

            //匹配包名
            if (packageName != null && packageName.toLowerCase().contains(lowerQuery)) {
                mFilteredList.add(info);
                continue;
            }
            //匹配服务名
            if (serviceName.toLowerCase().contains(lowerQuery)) {
                mFilteredList.add(info);
                continue;
            }
            //匹配应用名
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                String appLabel = pm.getApplicationLabel(appInfo).toString();
                if (appLabel.toLowerCase().contains(lowerQuery)) {
                    mFilteredList.add(info);
                    continue;
                }
            } catch (Exception ignored) {}

            //匹配服务显示名称
            try {
                android.content.pm.ServiceInfo si = pm.getServiceInfo(
                        new ComponentName(packageName, serviceName),
                        PackageManager.MATCH_DEFAULT_ONLY);
                String svcLabel = si.loadLabel(pm).toString();
                if (svcLabel.toLowerCase().contains(lowerQuery)) {
                    mFilteredList.add(info);
                }
            } catch (Exception ignored) {}
        }

        listView.setAdapter(new adapter(mFilteredList));
    }

    // 首次置顶提示（仅触发一次）
    private void showPinHintOnce() {
        if (sp.getBoolean("pin_hint_shown", false)) return;
        sp.edit().putBoolean("pin_hint_shown", true).apply();
        new Handler().postDelayed(() ->
                Toast.makeText(MainActivity.this, "长按服务项可将其置顶", Toast.LENGTH_LONG).show()
        , 500);
    }

    private void showUnlockDuplicateDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        View dv = getLayoutInflater().inflate(R.layout.dialog_unlock_duplicate, null);
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

        String msg = "检测到您的系统可以正常接收解锁广播，无需开启无障碍服务也能进行解锁检测。\n\n"
                + "当前解锁检测同时触发了「广播接收」和「无障碍服务」两种方式，"
                + "无障碍管理器的无障碍服务可以关闭以节省资源。\n\n"
                + "是否要取消保活并关闭管理器的无障碍服务？";
        ((TextView) dv.findViewById(R.id.unlock_dup_msg)).setText(msg);

        // "取消保活并关闭无障碍"
        dv.findViewById(R.id.unlock_dup_btn_positive).setOnClickListener(v -> {
            dialog.dismiss();
            String ownServiceId = new ComponentName(MainActivity.this,
                    MyAccessibilityService.class).flattenToString();

            // 从保活列表中移除
            String daemonStr = sp.getString("daemon", "");
            StringBuilder newDaemon = new StringBuilder();
            for (String entry : daemonStr.split(":")) {
                if (entry.isEmpty()) continue;
                ComponentName cn = ComponentName.unflattenFromString(entry);
                if (cn != null && ownServiceId.equals(cn.flattenToString())) {
                    continue; // 跳过管理器自己的无障碍服务
                }
                if (newDaemon.length() > 0) newDaemon.append(":");
                newDaemon.append(entry);
            }
            String resultDaemon = newDaemon.toString();
            sp.edit().putString("daemon", resultDaemon).apply();

            // 从已开启的无障碍服务列表中移除
            String sv = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (sv != null) {
                StringBuilder newSv = new StringBuilder();
                for (String entry : sv.split(":")) {
                    if (entry.isEmpty()) continue;
                    ComponentName cn = ComponentName.unflattenFromString(entry);
                    if (cn != null && ownServiceId.equals(cn.flattenToString())) {
                        continue; // 跳过管理器自己的无障碍服务
                    }
                    if (newSv.length() > 0) newSv.append(":");
                    newSv.append(entry);
                }
                Settings.Secure.putString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newSv.toString());
            }

            // 如果保活列表为空，停止保活服务
            if (resultDaemon.isEmpty()) {
                stopService(new Intent(MainActivity.this, daemonService.class));
            } else {
                // 否则刷新保活服务
                Intent intent = new Intent(MainActivity.this, daemonService.class);
                intent.putExtra("source", "服务刷新");
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                } catch (Exception ignored) {}
            }

            // 刷新本地字段，确保界面使用最新数据
            settingValue = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue == null) settingValue = "";
            daemon = sp.getString("daemon", "");

            // 刷新列表显示
            runOnUiThread(() -> listView.setAdapter(new adapter(tmp)));

            Toast.makeText(MainActivity.this, "已取消保活并关闭无障碍服务", Toast.LENGTH_SHORT).show();
        });

        // "知道了"
        dv.findViewById(R.id.unlock_dup_btn_negative).setOnClickListener(v -> dialog.dismiss());

        // "不再提示"
        dv.findViewById(R.id.unlock_dup_btn_neutral).setOnClickListener(v -> {
            sp.edit().putBoolean("unlock_duplicate_dialog_dismissed", true).apply();
            dialog.dismiss();
        });

        dialog.show();
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
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item, null);
                holder = new ViewHolder();
                holder.texta = convertView.findViewById(R.id.a);
                holder.textb = convertView.findViewById(R.id.b);
                holder.imageView = convertView.findViewById(R.id.c);
                holder.sw = convertView.findViewById(R.id.s);
                holder.ib = convertView.findViewById(R.id.ib);
                holder.pinIndicator = convertView.findViewById(R.id.pin_indicator);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            AccessibilityServiceInfo info = list.get(position);
            String rawServiceName = info.getId();
            String serviceName = normalizeServiceId(rawServiceName);
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
            String[] packageName = Pattern.compile("/").split(serviceName);
            Drawable icon = null;
            String Packagelabel = null;
            String ServiceLabel = null;
            String Description = null;
            ServiceCache cache = mServiceCache.get(serviceName);
            if (cache != null) {
                icon = cache.icon;
                Packagelabel = cache.packageLabel;
                ServiceLabel = cache.serviceLabel;
                Description = cache.description;
            } else {
                // 缓存未命中时回退到原始加载
                try {
                    icon = pm.getApplicationIcon(packageName[0]);
                    Packagelabel = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName[0], PackageManager.GET_META_DATA)));
                    ServiceLabel = pm.getServiceInfo(new ComponentName(packageName[0], packageName[1]), PackageManager.MATCH_DEFAULT_ONLY).loadLabel(pm).toString();
                    Description = info.loadDescription(pm);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
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
            if (mIsFavoritesTab) {
                // 收藏 Tab：长按取消收藏
                convertView.setOnLongClickListener(v -> {
                    removeFromFavorites(serviceName);
                    Toast.makeText(MainActivity.this, "已取消收藏" + finalServiceLabel, Toast.LENGTH_SHORT).show();
                    switchToTab(true);
                    return true;
                });
            } else {
                // 全部服务 Tab：长按置顶/取消置顶
                convertView.setOnLongClickListener(v -> {
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
                    runOnUiThread(() -> {
                        if (mIsFavoritesTab) {
                            switchToTab(true);
                        } else {
                            listView.setAdapter(new adapter(tmp));
                        }
                    });
                    return true;
                });
            }
            if (top.contains(serviceName))
                holder.pinIndicator.setVisibility(View.VISIBLE);
            else
                holder.pinIndicator.setVisibility(View.GONE);
            return convertView;
        }

        private void createPermissionDialog() {
            String pkg = getPackageName();
            String cmd = "adb shell \"pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS; pm grant " + pkg + " android.permission.DUMP\"";
            String rootCmd = "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS; pm grant " + pkg + " android.permission.DUMP";
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage("安卓5.1和更低版本的设备，需将本APP转换为系统应用。\n\n安卓6.0及更高版本的设备，在下面三个方法中任选一个均可：\n1.连接电脑USB调试后在电脑CMD执行以下命令：\n" + cmd + "\n\n2.root激活。\n\n3.Shizuku激活。")
                    .setTitle("需要授予权限")
                    .setPositiveButton("复制命令", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", cmd));
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
                                o.writeBytes(rootCmd + "\nexit\n");
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) check(true);
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
            View pinIndicator;
        }

    }

    static class ServiceCache {
        Drawable icon;
        String packageLabel;
        String serviceLabel;
        String description;

        ServiceCache(Drawable icon, String packageLabel, String serviceLabel, String description) {
            this.icon = icon;
            this.packageLabel = packageLabel;
            this.serviceLabel = serviceLabel;
            this.description = description;
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
                TimerReceiver.cancel(MainActivity.this, "检测间隔变更");
                LogUtil.log(MainActivity.this, "[定时检测] 已取消旧定时");
                TimerReceiver.scheduleNext(MainActivity.this);
                LogUtil.log(MainActivity.this, "[定时检测] 已设置新间隔 = " + minutes + " 分钟");
                Toast.makeText(MainActivity.this, "定时检测间隔已设为 " + minutes + " 分钟", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                if (onSaved != null) onSaved.run();
            }
        });
    }

    //查看APP是否可以写入安全设置（同时检查 WRITE_SECURE_SETTINGS 和 DUMP）
    boolean checkPermission() {
        boolean writeSecureOk = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            writeSecureOk = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        } else {
            PackageInfo packageInfo = new PackageInfo();
            try {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_CONFIGURATIONS);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            writeSecureOk = (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }
        perm = writeSecureOk;
        return !perm;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS) {
            // 从设置页面返回，刷新相关状态
            updateToolbarMenu();
            favorites = sp.getString("favorites", "");
            daemon = sp.getString("daemon", "");
            top = sp.getString("top", "");
            Sort();
            runOnUiThread(() -> {
                // 刷新当前 Tab
                if (mIsFavoritesTab) {
                    switchToTab(true);
                } else {
                    listView.setAdapter(new adapter(tmp));
                }
            });
        }
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
        Log.d("AM_DIAG", "[MainActivity] StartForeGroundDaemon() 被调用");
        if (daemon.isEmpty()) {
            Log.d("AM_DIAG", "[MainActivity] daemon为空，跳过启动保活服务");
            return;
        }
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

    private void checkForUpdate() {
        Toast.makeText(MainActivity.this, "正在检查更新...", Toast.LENGTH_SHORT).show();
        UpdateChecker.checkForUpdate(MainActivity.this, new UpdateChecker.UpdateListener() {
            @Override
            public void onUpdateAvailable(String versionName, String downloadUrl, String releaseNotes) {
                showUpdateDialog(versionName, downloadUrl, releaseNotes);
            }

            @Override
            public void onNoUpdate() {
                Toast.makeText(MainActivity.this, "当前已是最新版本", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String errorMessage) {
                Toast.makeText(MainActivity.this, "检查更新失败: " + errorMessage, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showUpdateDialog(String versionName, String downloadUrl, String releaseNotes) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_update, null);

        TextView title = dialogView.findViewById(R.id.update_title);
        TextView version = dialogView.findViewById(R.id.update_version);
        TextView changelog = dialogView.findViewById(R.id.update_changelog);
        Button updateLater = dialogView.findViewById(R.id.update_later);
        Button updateNow = dialogView.findViewById(R.id.update_now);

        version.setText("版本 " + versionName);
        changelog.setText(releaseNotes.isEmpty() ? "暂无更新内容" : releaseNotes);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        updateLater.setOnClickListener(v -> {
            sp.edit().putString("skipped_update_version", versionName).apply();
            dialog.dismiss();
        });

        updateNow.setOnClickListener(v -> {
            dialog.dismiss();
            sp.edit().remove("skipped_update_version").apply();
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            startActivity(intent);
        });

        dialog.show();
    }

}