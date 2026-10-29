package com.accessibilitymanager;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LogActivity extends AppCompatActivity {

    private static final int VIEW_TYPE_LOG = 0;
    private static final int VIEW_TYPE_DATE = 1;
    private static final int VIEW_TYPE_GAP = 2;
    /** 同步列表类长日志：折叠时单行显示，右侧带展开/收起按钮 */
    private static final int VIEW_TYPE_LONG_LINE = 3;

    private ListView listView;
    private TextView tvEmpty;
    private LogAdapter adapter;
    /** 原始日志条目（保持完整，不因折叠丢失） */
    private List<LogUtil.LogEntry> entries;
    /** 界面展示列表：在原始日志基础上把连续重复日志折叠成组 */
    private List<LogItem> displayItems = new ArrayList<>();
    /** 已展开的折叠组标识（组内第一条原始日志的下标）；默认不展开即默认折叠 */
    private final Set<Integer> mExpandedGroups = new HashSet<>();
    /** 已展开的长日志下标（同步列表类日志默认单行显示） */
    private final Set<Integer> mExpandedLongLines = new HashSet<>();
    private boolean night;
    private androidx.appcompat.widget.Toolbar toolbar;
    private boolean mIsMultiSelectMode = false;
    private final java.util.Set<Integer> mSelectedPositions = new java.util.TreeSet<>();

    /** 可折叠日志的分组标签：只有连续且同组的日志才会合并 */
    private static final String GROUP_IGNORED_SERVICE = "非保活服务";

    // ── 实时监听 ──
    private LogUtil.LogListener mLogListener;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtils.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        night = nightMode == Configuration.UI_MODE_NIGHT_YES;

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (!night) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }

        listView = findViewById(R.id.recycler_log);
        tvEmpty = findViewById(R.id.tv_empty);

        toolbar = findViewById(R.id.toolbar);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            toolbar.setNavigationIcon(R.drawable.ic_back_arrow);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (displayItems == null || position < 0 || position >= displayItems.size()) return false;
            LogItem item = displayItems.get(position);
            if (item.kind != LogItem.KIND_LINE && item.kind != LogItem.KIND_GROUP) return false;
            
            if (!mIsMultiSelectMode) {
                enterMultiSelectMode(position);
            } else {
                toggleItemSelection(position);
            }
            return true;
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (displayItems == null || position < 0 || position >= displayItems.size()) return;
            LogItem item = displayItems.get(position);
            if (!mIsMultiSelectMode) {
                // 点击折叠组可展开/收起明细
                if (item.kind == LogItem.KIND_GROUP) {
                    toggleGroupExpanded(item);
                    return;
                }
                // 同步列表类长日志：点击整行也可展开/收起
                if (item.kind == LogItem.KIND_LINE && isLongListLine(item.text)) {
                    toggleLongLineExpanded(item);
                }
                return;
            }
            if (item.kind != LogItem.KIND_LINE && item.kind != LogItem.KIND_GROUP) return;
            toggleItemSelection(position);
        });

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        
        View btnShare = findViewById(R.id.btn_share);
        btnShare.setOnTouchListener(new View.OnTouchListener() {
            private android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            private boolean isLongPressed = false;
            private Runnable longPressRunnable = () -> {
                isLongPressed = true;
                btnShare.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                showUploadDialog();
            };
            
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    isLongPressed = false;
                    handler.postDelayed(longPressRunnable, 1000); // 1秒长按
                } else if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    handler.removeCallbacks(longPressRunnable);
                    if (!isLongPressed) {
                        shareLog();
                    }
                } else if (event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    handler.removeCallbacks(longPressRunnable);
                }
                return false; // 返回 false，让 Button 能够自己处理涟漪动画和 pressed 状态
            }
        });

        findViewById(R.id.btn_dump_exec).setOnClickListener(v -> executeDumpDirect());

        // 初次加载已有日志
        loadLogs();
        // 注册实时监听
        startListening();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListening();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 先补读模块事件，再加载日志
        ModuleConfig.drainLogs(this);
        loadLogs();
        startListening();
    }

    @Override
    public void onBackPressed() {
        if (mIsMultiSelectMode) {
            exitMultiSelectMode();
            return;
        }
        super.onBackPressed();
    }

    private void enterMultiSelectMode(int position) {
        mIsMultiSelectMode = true;
        mSelectedPositions.clear();
        mSelectedPositions.add(position);
        listView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        updateMultiSelectToolbar();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void toggleItemSelection(int position) {
        if (mSelectedPositions.contains(position)) {
            mSelectedPositions.remove(position);
            if (mSelectedPositions.isEmpty()) {
                exitMultiSelectMode();
                return;
            }
        } else {
            mSelectedPositions.add(position);
        }
        updateMultiSelectToolbar();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void exitMultiSelectMode() {
        mIsMultiSelectMode = false;
        mSelectedPositions.clear();
        toolbar.setTitle("运行日志");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            toolbar.setNavigationIcon(R.drawable.ic_back_arrow);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.getMenu().clear();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateMultiSelectToolbar() {
        toolbar.setTitle("已选择 " + mSelectedPositions.size() + " 项");
        toolbar.setNavigationIcon(R.drawable.ic_close);
        toolbar.setNavigationOnClickListener(v -> exitMultiSelectMode());
        toolbar.getMenu().clear();

        android.view.MenuItem itemSelectAll = toolbar.getMenu().add(0, 1, 0, "全选");
        itemSelectAll.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);

        android.view.MenuItem itemCopy = toolbar.getMenu().add(0, 2, 1, "复制");
        itemCopy.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);

        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                toggleSelectAll();
                return true;
            } else if (item.getItemId() == 2) {
                copySelectedLogs();
                return true;
            }
            return false;
        });
    }

    private void toggleSelectAll() {
        if (displayItems == null) return;
        int totalLogCount = 0;
        for (int i = 0; i < displayItems.size(); i++) {
            if (isSelectable(displayItems.get(i))) totalLogCount++;
        }
        if (mSelectedPositions.size() >= totalLogCount) {
            mSelectedPositions.clear();
            exitMultiSelectMode();
        } else {
            mSelectedPositions.clear();
            for (int i = 0; i < displayItems.size(); i++) {
                if (isSelectable(displayItems.get(i))) {
                    mSelectedPositions.add(i);
                }
            }
            updateMultiSelectToolbar();
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    private static boolean isSelectable(LogItem item) {
        return item.kind == LogItem.KIND_LINE || item.kind == LogItem.KIND_GROUP;
    }

    private void copySelectedLogs() {
        if (mSelectedPositions.isEmpty() || displayItems == null) {
            exitMultiSelectMode();
            return;
        }
        StringBuilder sb = new StringBuilder();
        int copiedLines = 0;
        for (int pos : mSelectedPositions) {
            if (pos < 0 || pos >= displayItems.size()) continue;
            LogItem item = displayItems.get(pos);
            if (item.kind == LogItem.KIND_GROUP && entries != null) {
                // 折叠组一次性复制组内全部原始日志
                for (int i = item.rawStart; i < item.rawEnd && i < entries.size(); i++) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(entries.get(i).text);
                    copiedLines++;
                }
            } else if (item.text != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(item.text);
                copiedLines++;
            }
        }
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", sb.toString()));
            Toast.makeText(this, "已复制 " + copiedLines + " 条日志", Toast.LENGTH_SHORT).show();
        }
        exitMultiSelectMode();
    }

    // ── 实时监听 ──

    private void startListening() {
        stopListening();
        mLogListener = (type, text) -> runOnUiThread(() -> appendLog(type, text));
        LogUtil.addListener(mLogListener);
    }

    private void stopListening() {
        if (mLogListener != null) {
            LogUtil.removeListener(mLogListener);
            mLogListener = null;
        }
    }

    /** 将新日志行追加到列表末尾 */
    private void appendLog(int type, String text) {
        // 懒初始化：如果还没有 entries，先从文件读取
        if (entries == null) {
            loadLogs();
            return;
        }

        LogUtil.LogEntry entry = new LogUtil.LogEntry(type, text);
        entries.add(entry);
        int rawIndex = entries.size() - 1;

        // 增量维护展示列表，避免日志刷屏时反复全量重建
        if (type == LogUtil.LogEntry.TYPE_DATE_SEPARATOR) {
            displayItems.add(LogItem.date(text));
        } else if (type == LogUtil.LogEntry.TYPE_GAP_SEPARATOR) {
            displayItems.add(LogItem.gap());
        } else {
            String groupLabel = collapseGroupLabel(text);
            if (groupLabel != null) {
                appendCollapsibleLine(rawIndex, text, groupLabel);
            } else {
                displayItems.add(LogItem.line(rawIndex, text));
            }
        }

        if (adapter == null) {
            listView.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            adapter = new LogAdapter(night);
            listView.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
        // 自动滚动到底部
        listView.setSelection(displayItems.size() - 1);
    }

    /** 追加一条可折叠日志：与上一条同组日志合并成折叠组 */
    private void appendCollapsibleLine(int rawIndex, String text, String groupLabel) {
        LogItem last = displayItems.isEmpty() ? null : displayItems.get(displayItems.size() - 1);
        if (last == null) {
            displayItems.add(LogItem.line(rawIndex, text));
            return;
        }
        // 1) 上一条是折叠组（未展开）：直接累计
        if (last.kind == LogItem.KIND_GROUP && last.rawEnd == rawIndex && !last.expanded) {
            last.rawEnd = rawIndex + 1;
            last.count++;
            last.text = groupHeaderText(last.groupLabel, last.count, false);
            return;
        }
        // 2) 上一条属于已展开的折叠组：扩充该组并继续显示明细
        if (last.kind == LogItem.KIND_LINE && last.ownerGroup != null
                && last.ownerGroup.expanded && last.ownerGroup.rawEnd == rawIndex) {
            LogItem group = last.ownerGroup;
            group.rawEnd = rawIndex + 1;
            group.count++;
            group.text = groupHeaderText(group.groupLabel, group.count, true);
            displayItems.add(LogItem.line(rawIndex, text, group));
            return;
        }
        // 3) 上一条是可折叠的普通行：把这两条合并成新的折叠组
        if (last.kind == LogItem.KIND_LINE && last.ownerGroup == null
                && last.rawStart == rawIndex - 1
                && groupLabel.equals(collapseGroupLabel(entries.get(last.rawStart).text))) {
            displayItems.remove(displayItems.size() - 1);
            displayItems.add(LogItem.group(last.rawStart, rawIndex + 1, 2, false,
                    groupHeaderText(groupLabel, 2, false), groupLabel));
            return;
        }
        displayItems.add(LogItem.line(rawIndex, text));
    }

    /** 点击折叠组：切换展开/收起状态并重建展示列表 */
    private void toggleGroupExpanded(LogItem item) {
        if (!mExpandedGroups.remove(item.rawStart)) {
            mExpandedGroups.add(item.rawStart);
        }
        displayItems = buildDisplayItems(entries);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        // 保持折叠组表头可见
        for (int i = 0; i < displayItems.size(); i++) {
            LogItem it = displayItems.get(i);
            if (it.kind == LogItem.KIND_GROUP && it.rawStart == item.rawStart) {
                listView.setSelection(i);
                break;
            }
        }
    }

    /** 把原始日志转换为展示列表：连续的同类日志合并为可折叠组 */
    private List<LogItem> buildDisplayItems(List<LogUtil.LogEntry> raw) {
        List<LogItem> items = new ArrayList<>();
        if (raw == null) return items;
        int i = 0;
        while (i < raw.size()) {
            LogUtil.LogEntry e = raw.get(i);
            String groupLabel = e.type == LogUtil.LogEntry.TYPE_LOG_LINE
                    ? collapseGroupLabel(e.text) : null;
            if (groupLabel != null) {
                int j = i + 1;
                while (j < raw.size()
                        && raw.get(j).type == LogUtil.LogEntry.TYPE_LOG_LINE
                        && groupLabel.equals(collapseGroupLabel(raw.get(j).text))) {
                    j++;
                }
                int count = j - i;
                if (count >= 2) {
                    boolean expanded = mExpandedGroups.contains(i);
                    LogItem group = LogItem.group(i, j, count, expanded,
                            groupHeaderText(groupLabel, count, expanded), groupLabel);
                    items.add(group);
                    if (expanded) {
                        for (int k = i; k < j; k++) {
                            items.add(LogItem.line(k, raw.get(k).text, group));
                        }
                    }
                } else {
                    items.add(LogItem.line(i, e.text));
                }
                i = j;
            } else if (e.type == LogUtil.LogEntry.TYPE_DATE_SEPARATOR) {
                items.add(LogItem.date(e.text));
                i++;
            } else if (e.type == LogUtil.LogEntry.TYPE_GAP_SEPARATOR) {
                items.add(LogItem.gap());
                i++;
            } else {
                items.add(LogItem.line(i, e.text));
                i++;
            }
        }
        return items;
    }

    /** 返回可折叠日志的分组标签，null 表示不折叠；兼容旧版本的“非受管”写法 */
    private static String collapseGroupLabel(String text) {
        if (text == null) return null;
        if (text.contains("非保活服务") || text.contains("非受管服务")) {
            return GROUP_IGNORED_SERVICE;
        }
        return null;
    }

    /** 同步列表类长日志：不展开时只占一行，右侧提供展开/收起按钮 */
    private static boolean isLongListLine(String text) {
        return text != null
                && (text.contains("下发锁定同步指令") || text.contains("模块已生效最新锁定列表"));
    }

    private void toggleLongLineExpanded(LogItem item) {
        if (!mExpandedLongLines.remove(item.rawStart)) {
            mExpandedLongLines.add(item.rawStart);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private static String groupHeaderText(String label, int count, boolean expanded) {
        return (expanded ? "▾ " : "▸ ") + label + "：共 " + count + " 条，点击"
                + (expanded ? "收起" : "展开");
    }

    /** 从文件加载所有已有日志 */
    private void loadLogs() {
        List<LogUtil.LogEntry> newEntries = LogUtil.readRecentLogs(this);
        entries = newEntries;
        mExpandedGroups.clear();
        mExpandedLongLines.clear();
        displayItems = buildDisplayItems(entries);
        if (newEntries.isEmpty()) {
            listView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            adapter = null;
            return;
        }

        listView.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        adapter = new LogAdapter(night);
        listView.setAdapter(adapter);
        listView.setSelection(displayItems.size() - 1);
    }

    private String getSharedPreferencesDump() {
        StringBuilder sb = new StringBuilder();
        
        java.util.Map<String, String> descMap = new java.util.HashMap<>();
        descMap.put("boot", "开机自启");
        descMap.put("auto_update", "自动检查更新");
        descMap.put("last_update_check_time", "上次检查更新的时间戳");
        descMap.put("toast", "toast保活提示");
        descMap.put("daemon", "加锁守护服务列表");
        descMap.put("delay_daemon", "延迟保活");
        descMap.put("crashfix", "崩溃检测总开关");
        descMap.put("crashfix_auto_disabled", "防崩溃触发阈值后自动禁用");
        descMap.put("fixmode", "强制停止修复模式");
        descMap.put("fixmode_disabled_services", "强制停止模式下被禁用的服务记录");
        descMap.put("periodic_check", "周期性检查状态");
        descMap.put("periodic_check_interval", "周期检查间隔(分钟)");
        descMap.put("periodic_check_wake_idle", "检查时唤醒空闲设备");
        descMap.put("unlock_crash_check", "解锁检测");
        descMap.put("unlock_crash_dialog_dismissed", "解锁崩溃提示已忽略");
        descMap.put("unlock_duplicate_date", "解锁重复检测日期");
        descMap.put("unlock_duplicate_count", "解锁重复检测次数");
        descMap.put("unlock_duplicate_detected", "检测到异常重复解锁");
        descMap.put("ignore_system_crash_trigger", "忽略系统级崩溃触发");
        descMap.put("notify_title", "保活通知的标题");
        descMap.put("notify_text", "保活通知的内容");
        descMap.put("whitelist_global_enable", "全局白名单启用状态");
        descMap.put("whitelist_services", "白名单服务列表");
        descMap.put("global_cooldown_enable", "全局冷却时间启用");
        descMap.put("global_cooldown_time_minutes", "全局冷却时间(分钟)");
        descMap.put("useronly", "仅显示用户应用");
        descMap.put("hide", "隐藏最近任务界面");
        descMap.put("first", "首次运行隐私提示状态");
        descMap.put("pin_hint_shown", "置顶toast提示");
        descMap.put("top", "置顶服务列表");
        descMap.put("favorites", "收藏服务列表");
        descMap.put("favorites_tab_active", "当前处于收藏标签页");
        descMap.put("battery_warning_dismissed", "电池优化警告已忽略");
        descMap.put("current_foreground_pkg", "当前前台应用包名记录");
        descMap.put("theme", "应用UI主题");

        sb.append("--- [data] SharedPreferences ---\n");
        android.content.SharedPreferences sp = getSharedPreferences("data", android.content.Context.MODE_PRIVATE);
        for (java.util.Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            String key = entry.getKey();
            String desc = descMap.get(key);
            if (desc != null) {
                sb.append(desc).append(" (").append(key).append("): ").append(entry.getValue()).append("\n");
            } else {
                sb.append(key).append(": ").append(entry.getValue()).append("\n");
            }
        }

        sb.append("\n--- [Main] SharedPreferences ---\n");
        android.content.SharedPreferences mainSp = getSharedPreferences("Main", android.content.Context.MODE_PRIVATE);
        for (java.util.Map.Entry<String, ?> entry : mainSp.getAll().entrySet()) {
            String key = entry.getKey();
            String desc = descMap.get(key);
            if (desc != null) {
                sb.append(desc).append(" (").append(key).append("): ").append(entry.getValue()).append("\n");
            } else {
                sb.append(key).append(": ").append(entry.getValue()).append("\n");
            }
        }
        return sb.toString();
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = android.provider.Settings.Secure.getInt(
                    getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (android.provider.Settings.SettingNotFoundException e) {
            // Ignore
        }
        android.text.TextUtils.SimpleStringSplitter mStringColonSplitter = new android.text.TextUtils.SimpleStringSplitter(':');

        if (accessibilityEnabled == 1) {
            String settingValue = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue);
                while (mStringColonSplitter.hasNext()) {
                    String accessibilityService = mStringColonSplitter.next();
                    if (accessibilityService.equalsIgnoreCase(getPackageName() + "/" + MyAccessibilityService.class.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String buildCompleteLogContent() {
        String logText = LogUtil.readRecentLogsRaw(this);
        if (logText == null || logText.isEmpty()) {
            logText = "暂无日志记录";
        }

        String accessibilityInfo = getAccessibilityServiceInfo();

        StringBuilder fullLog = new StringBuilder();
        fullLog.append("=============== App Status & Settings ===============\n\n");
        fullLog.append("MyAccessibilityService Enabled: ").append(isAccessibilityServiceEnabled()).append("\n\n");
        fullLog.append(getSharedPreferencesDump());
        fullLog.append("\n\n");

        fullLog.append("=============== Accessibility Service Info ===============\n\n");
        fullLog.append(accessibilityInfo);
        fullLog.append("\n\n=============== Application Logs ===============\n\n");
        fullLog.append(logText);
        return fullLog.toString();
    }

    private void shareLog() {
        String fullLogStr = buildCompleteLogContent();

        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
            File cacheDir = getCacheDir();
            File logFile = new File(cacheDir, "accessibility_log_" + timestamp + ".txt");

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile)) {
                fos.write(fullLogStr.getBytes("UTF-8"));
            }

            Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".logshare", logFile);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, contentUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("", contentUri));
            startActivity(Intent.createChooser(intent, "分享日志文件"));
        } catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, fullLogStr);
            startActivity(Intent.createChooser(intent, "分享日志"));
        }
    }

    private void showUploadDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("上传日志")
                .setMessage("是否将当前日志上传？")
                .setPositiveButton("上传", (dialog, which) -> uploadLogToGitee())
                .setNegativeButton("取消", null)
                .show();
    }

    private void uploadLogToGitee() {
        android.widget.Toast.makeText(this, "正在上传日志...", android.widget.Toast.LENGTH_SHORT).show();

        final String finalLogContent = buildCompleteLogContent();

        new Thread(() -> {
            try {
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
                String fileName = "accessibility_log_" + timestamp + ".txt";

                java.io.File cacheDir = getCacheDir();
                java.io.File logFile = new java.io.File(cacheDir, fileName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile)) {
                    fos.write(finalLogContent.getBytes("UTF-8"));
                }
                
                byte[] fileBytes = new byte[(int) logFile.length()];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(logFile)) {
                    fis.read(fileBytes);
                }
                
                String base64Content = android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP);

                java.net.URL url = new java.net.URL(BuildConfig.GITEE_REPO_URL + fileName);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                conn.setDoOutput(true);

                org.json.JSONObject bodyObj = new org.json.JSONObject();
                bodyObj.put("access_token", BuildConfig.GITEE_ACCESS_TOKEN);
                bodyObj.put("content", base64Content);
                bodyObj.put("message", "App 用户日志上传: " + fileName);

                String jsonBodyString = bodyObj.toString();
                byte[] bodyBytes = jsonBodyString.getBytes("UTF-8");
                
                conn.setFixedLengthStreamingMode(bodyBytes.length);

                java.io.OutputStream os = conn.getOutputStream();
                os.write(bodyBytes);
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == 201 || responseCode == 200) {
                    runOnUiThread(() -> android.widget.Toast.makeText(LogActivity.this, "上传成功", android.widget.Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> android.widget.Toast.makeText(LogActivity.this, "上传失败", android.widget.Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(LogActivity.this, "上传失败", android.widget.Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String getAccessibilityServiceInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            boolean hasDump = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                hasDump = checkSelfPermission(android.Manifest.permission.DUMP) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            } else {
                hasDump = checkCallingOrSelfPermission(android.Manifest.permission.DUMP) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }

            if (hasDump) {
                Process process = Runtime.getRuntime().exec("dumpsys accessibility");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();

                BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), "UTF-8"));
                while ((line = errReader.readLine()) != null) {
                    sb.append("[错误] ").append(line).append("\n");
                }
                errReader.close();

                process.waitFor();
                return sb.toString();
            }

            Process process;
            int permState = ShellUtil.getPermissionState();

            if (permState == ShellUtil.PERM_ROOT) {
                Process su = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(su.getOutputStream());
                os.writeBytes("dumpsys accessibility\nexit\n");
                os.flush();
                process = su;
            } else if (permState == ShellUtil.PERM_SHIZUKU) {
                process = Shizuku.newProcess(new String[]{"dumpsys", "accessibility"}, null, null);
            } else {
                sb.append("无 DUMP、Root 或 Shizuku 权限，无法获取 Accessibility 服务信息");
                return sb.toString();
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            process.waitFor();
        } catch (Exception e) {
            sb.append("无法获取 Accessibility 服务信息: ").append(e.getMessage());
        }
        return sb.toString();
    }

    /** 直接使用 DUMP 权限执行 dumpsys accessibility（不依赖 Root/Shizuku） */
    private void executeDumpDirect() {
        if (entries == null) {
            entries = new ArrayList<>();
        }
        // 添加分隔标题
        entries.add(new LogUtil.LogEntry(
                LogUtil.LogEntry.TYPE_DATE_SEPARATOR,
                "────────  dumpsys accessibility (DUMP)  ────────"
        ));

        StringBuilder sb = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec("dumpsys accessibility");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();

            // 也读取错误流
            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), "UTF-8"));
            while ((line = errReader.readLine()) != null) {
                sb.append("[错误] ").append(line).append("\n");
            }
            errReader.close();

            process.waitFor();
        } catch (Exception e) {
            sb.append("直接执行 dumpsys 失败: ").append(e.getMessage());
        }

        String result = sb.toString().trim();
        if (result.isEmpty()) {
            result = "（无输出，可能未授予 DUMP 权限）";
        }

        // 添加结果行
        for (String line : result.split("\n")) {
            entries.add(new LogUtil.LogEntry(LogUtil.LogEntry.TYPE_LOG_LINE, line));
        }

        // 添加结尾分隔
        entries.add(new LogUtil.LogEntry(
                LogUtil.LogEntry.TYPE_DATE_SEPARATOR,
                "────────  执行完毕  ────────"
        ));

        // 批量追加后重建展示列表（折叠组按原始下标保持展开状态）
        displayItems = buildDisplayItems(entries);

        if (adapter == null) {
            listView.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            adapter = new LogAdapter(night);
            listView.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
        listView.setSelection(displayItems.size() - 1);
    }

    /** 长日志行的子视图缓存：左侧文本 + 右侧展开/收起按钮 */
    private static class LongLineHolder {
        final TextView text;
        final TextView button;

        LongLineHolder(TextView text, TextView button) {
            this.text = text;
            this.button = button;
        }
    }

    /** 展示列表条目：普通日志行、日期/间隔分隔符，或折叠组（表头+可选展开的明细） */
    private static class LogItem {
        static final int KIND_DATE = 0;
        static final int KIND_GAP = 1;
        static final int KIND_LINE = 2;
        static final int KIND_GROUP = 3;

        int kind;
        String text;
        /** 普通行：原始日志下标；折叠组：组内第一条原始日志下标 */
        int rawStart;
        /** 折叠组专用：组内最后一条原始日志下标 + 1 */
        int rawEnd;
        /** 折叠组专用：组内日志条数 */
        int count;
        /** 折叠组专用：是否展开 */
        boolean expanded;
        /** 折叠组专用：分组标签（同类日志才合并） */
        String groupLabel;
        /** 展开状态下，该明细行所属的折叠组表头 */
        LogItem ownerGroup;

        static LogItem date(String text) {
            LogItem item = new LogItem();
            item.kind = KIND_DATE;
            item.text = text;
            return item;
        }

        static LogItem gap() {
            LogItem item = new LogItem();
            item.kind = KIND_GAP;
            return item;
        }

        static LogItem line(int rawIndex, String text) {
            return line(rawIndex, text, null);
        }

        static LogItem line(int rawIndex, String text, LogItem ownerGroup) {
            LogItem item = new LogItem();
            item.kind = KIND_LINE;
            item.rawStart = rawIndex;
            item.rawEnd = rawIndex + 1;
            item.text = text;
            item.ownerGroup = ownerGroup;
            return item;
        }

        static LogItem group(int start, int end, int count, boolean expanded, String text,
                String groupLabel) {
            LogItem item = new LogItem();
            item.kind = KIND_GROUP;
            item.rawStart = start;
            item.rawEnd = end;
            item.count = count;
            item.expanded = expanded;
            item.text = text;
            item.groupLabel = groupLabel;
            return item;
        }
    }

    private class LogAdapter extends BaseAdapter {

        private boolean night;

        LogAdapter(boolean night) {
            this.night = night;
        }

        @Override
        public int getCount() {
            return displayItems.size();
        }

        @Override
        public Object getItem(int position) {
            return displayItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getItemViewType(int position) {
            LogItem item = displayItems.get(position);
            if (item.kind == LogItem.KIND_DATE) return VIEW_TYPE_DATE;
            if (item.kind == LogItem.KIND_GAP) return VIEW_TYPE_GAP;
            if (item.kind == LogItem.KIND_LINE && isLongListLine(item.text)) {
                return VIEW_TYPE_LONG_LINE;
            }
            return VIEW_TYPE_LOG;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LogItem entry = displayItems.get(position);
            int type = getItemViewType(position);

            if (type == VIEW_TYPE_DATE) {
                if (convertView == null) {
                    convertView = createDateSeparatorView();
                }
                TextView tvDate = (TextView) convertView.getTag();
                tvDate.setText(entry.text);
                return convertView;
            } else if (type == VIEW_TYPE_GAP) {
                if (convertView == null) {
                    convertView = createGapSeparatorView();
                }
                return convertView;
            } else if (type == VIEW_TYPE_LONG_LINE) {
                if (convertView == null) {
                    convertView = createLongLineView();
                }
                LongLineHolder holder = (LongLineHolder) convertView.getTag();
                String line = entry.text;
                boolean expanded = mExpandedLongLines.contains(entry.rawStart);
                holder.text.setMaxLines(expanded ? Integer.MAX_VALUE : 1);
                holder.text.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
                SpannableStringBuilder ssb = new SpannableStringBuilder(line);
                ssb.setSpan(new ForegroundColorSpan(getLineColor(line)), 0, line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                holder.text.setText(ssb);
                holder.button.setText(expanded ? "收起" : "展开");
                holder.button.setTextColor(night ? 0xFF8AB4F8 : 0xFF1A73E8);
                holder.button.setOnClickListener(v -> {
                    if (!mIsMultiSelectMode) {
                        toggleLongLineExpanded(entry);
                    }
                });
                if (mSelectedPositions.contains(position)) {
                    android.graphics.drawable.GradientDrawable selBg = new android.graphics.drawable.GradientDrawable();
                    selBg.setColor(night ? 0x33FFFFFF : 0x22000000);
                    selBg.setCornerRadius(10f);
                    convertView.setBackground(selBg);
                } else {
                    convertView.setBackground(null);
                }
                return convertView;
            } else {
                TextView tv;
                if (convertView == null) {
                    tv = new TextView(LogActivity.this);
                    tv.setTextSize(12f);
                    tv.setPadding(16, 6, 16, 6);
                    tv.setLineSpacing(0, 1.15f);
                    tv.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                } else {
                    tv = (TextView) convertView;
                }
                String line = entry.text;
                SpannableStringBuilder ssb = new SpannableStringBuilder(line);
                int color;
                if (entry.kind == LogItem.KIND_GROUP) {
                    // 折叠组表头使用弱化颜色，方便和真实日志区分
                    color = night ? 0xFF9E9E9E : 0xFF757575;
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                } else {
                    color = getLineColor(line);
                    tv.setTypeface(Typeface.DEFAULT);
                }
                ssb.setSpan(new ForegroundColorSpan(color), 0, line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setText(ssb);

                if (mSelectedPositions.contains(position)) {
                    android.graphics.drawable.GradientDrawable selBg = new android.graphics.drawable.GradientDrawable();
                    selBg.setColor(night ? 0x33FFFFFF : 0x22000000);
                    selBg.setCornerRadius(10f);
                    tv.setBackground(selBg);
                } else {
                    tv.setBackground(null);
                }
                return tv;
            }
        }

        private View createLongLineView() {
            LinearLayout row = new LinearLayout(LogActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView text = new TextView(LogActivity.this);
            text.setTextSize(12f);
            text.setPadding(16, 6, 8, 6);
            text.setLineSpacing(0, 1.15f);
            text.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView button = new TextView(LogActivity.this);
            button.setTextSize(12f);
            button.setPadding(8, 6, 16, 6);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            row.addView(text);
            row.addView(button);
            row.setTag(new LongLineHolder(text, button));
            return row;
        }

        private int getLineColor(String line) {
            if (line.contains("[系统监控]") || line.contains("[系统诊断]") || line.contains("[异常崩溃]")) return 0xFFE53935;
            if (line.contains("[崩溃检测]")) return 0xFFE06D00;
            if (line.contains("[崩溃修复]") || line.contains("[崩溃修复-重试]")) return 0xFF2288DD;
            if (line.contains("[Shizuku诊断]")) return 0xFF8E24AA;
            if (line.contains("[保活]")) return 0xFF22AA22;
            return night ? 0xFFCCCCCC : 0xFF333333;
        }

        private View createDateSeparatorView() {
            LinearLayout layout = new LinearLayout(LogActivity.this);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setGravity(Gravity.CENTER_VERTICAL);
            layout.setPadding(0, 12, 0, 4);

            View lineLeft = new View(LogActivity.this);
            lineLeft.setBackgroundColor(0xFF666666);
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                    0, 1, 1f);
            lineLeft.setLayoutParams(leftParams);
            layout.addView(lineLeft);

            TextView tvDate = new TextView(LogActivity.this);
            tvDate.setTextSize(13f);
            tvDate.setTextColor(0xFFAAAAAA);
            tvDate.setTypeface(Typeface.DEFAULT_BOLD);
            tvDate.setPadding(16, 0, 16, 0);
            layout.addView(tvDate);

            View lineRight = new View(LogActivity.this);
            lineRight.setBackgroundColor(0xFF666666);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                    0, 1, 1f);
            lineRight.setLayoutParams(rightParams);
            layout.addView(lineRight);

            layout.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            layout.setTag(tvDate);
            return layout;
        }

        private View createGapSeparatorView() {
            View line = new View(LogActivity.this);
            line.setBackgroundColor(0xFF444444);
            line.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 2));
            return line;
        }
    }
}