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
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogActivity extends AppCompatActivity {

    private static final int VIEW_TYPE_LOG = 0;
    private static final int VIEW_TYPE_DATE = 1;
    private static final int VIEW_TYPE_GAP = 2;

    private ListView listView;
    private TextView tvEmpty;
    private LogAdapter adapter;
    private List<LogUtil.LogEntry> entries;
    private boolean night;

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
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (!night) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                }
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }

        listView = findViewById(R.id.recycler_log);
        tvEmpty = findViewById(R.id.tv_empty);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            toolbar.setNavigationIcon(R.drawable.ic_back_arrow);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_share).setOnClickListener(v -> shareLog());
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
        // 重新加载（可能跨天）
        loadLogs();
        startListening();
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

        if (adapter == null) {
            listView.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            adapter = new LogAdapter(entries, night);
            listView.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
        // 自动滚动到底部
        listView.setSelection(entries.size() - 1);
    }

    /** 从文件加载所有已有日志 */
    private void loadLogs() {
        List<LogUtil.LogEntry> newEntries = LogUtil.readRecentLogs(this);
        if (newEntries.isEmpty()) {
            listView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            adapter = null;
            entries = newEntries;
            return;
        }

        entries = newEntries;
        listView.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        adapter = new LogAdapter(entries, night);
        listView.setAdapter(adapter);
        listView.setSelection(entries.size() - 1);
    }

    private void shareLog() {
        String logText = LogUtil.readRecentLogsRaw(this);
        if (logText.isEmpty()) {
            logText = "暂无日志记录";
        }

        // 获取 dumpsys accessibility 信息
        String accessibilityInfo = getAccessibilityServiceInfo();

        // 组合完整日志内容
        StringBuilder fullLog = new StringBuilder();
        fullLog.append("=============== Accessibility Service Info ===============\n\n");
        fullLog.append(accessibilityInfo);
        fullLog.append("\n\n=============== Application Logs ===============\n\n");
        fullLog.append(logText);

        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
            File cacheDir = getCacheDir();
            File logFile = new File(cacheDir, "accessibility_log_" + timestamp + ".txt");

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile)) {
                fos.write(fullLog.toString().getBytes("UTF-8"));
            }

            // 使用 AndroidX FileProvider 生成 content:// URI，APP 关闭后缓存文件自动清理
            Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".logshare", logFile);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, contentUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("", contentUri));
            startActivity(Intent.createChooser(intent, "分享日志文件"));
        } catch (Exception e) {
            // 文件分享失败时降级为纯文本分享
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, fullLog.toString());
            startActivity(Intent.createChooser(intent, "分享日志"));
        }
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

        if (adapter == null) {
            listView.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            adapter = new LogAdapter(entries, night);
            listView.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
        listView.setSelection(entries.size() - 1);
    }

    private class LogAdapter extends BaseAdapter {

        private final List<LogUtil.LogEntry> entries;
        private boolean night;

        LogAdapter(List<LogUtil.LogEntry> entries, boolean night) {
            this.entries = entries;
            this.night = night;
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getItemViewType(int position) {
            LogUtil.LogEntry entry = entries.get(position);
            if (entry.type == LogUtil.LogEntry.TYPE_DATE_SEPARATOR) return VIEW_TYPE_DATE;
            if (entry.type == LogUtil.LogEntry.TYPE_GAP_SEPARATOR) return VIEW_TYPE_GAP;
            return VIEW_TYPE_LOG;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LogUtil.LogEntry entry = entries.get(position);
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
            } else {
                TextView tv;
                if (convertView == null) {
                    tv = new TextView(LogActivity.this);
                    tv.setTextSize(12f);
                    tv.setPadding(0, 0, 0, 2);
                    tv.setLineSpacing(0, 1f);
                    tv.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                } else {
                    tv = (TextView) convertView;
                }
                String line = entry.text;
                SpannableStringBuilder ssb = new SpannableStringBuilder(line);
                int color = getLineColor(line);
                ssb.setSpan(new ForegroundColorSpan(color), 0, line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setText(ssb);
                return tv;
            }
        }

        private int getLineColor(String line) {
            if (line.contains("[崩溃检测]")) return 0xFFE06D00;
            if (line.contains("[崩溃修复]") || line.contains("[崩溃修复-重试]")) return 0xFF2288DD;
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