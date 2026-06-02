package com.accessibilitymanager;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class LogActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean night = nightMode == Configuration.UI_MODE_NIGHT_YES;

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

        recyclerView = findViewById(R.id.recycler_log);
        tvEmpty = findViewById(R.id.tv_empty);
        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_share).setOnClickListener(v -> shareLog());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        List<LogUtil.LogEntry> entries = LogUtil.readRecentLogs(this);
        if (entries.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            LogAdapter adapter = new LogAdapter(entries);
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);
            recyclerView.scrollToPosition(entries.size() - 1);
        }
    }

    private void shareLog() {
        String logText = LogUtil.readRecentLogsRaw(this);
        if (logText.isEmpty()) {
            logText = "暂无日志记录";
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, logText);
        startActivity(Intent.createChooser(intent, "分享日志"));
    }

    private class LogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int VIEW_TYPE_LOG = 0;
        private static final int VIEW_TYPE_DATE = 1;
        private static final int VIEW_TYPE_GAP = 2;

        private final List<LogUtil.LogEntry> entries;

        LogAdapter(List<LogUtil.LogEntry> entries) {
            this.entries = entries;
        }

        @Override
        public int getItemViewType(int position) {
            LogUtil.LogEntry entry = entries.get(position);
            if (entry.type == LogUtil.LogEntry.TYPE_DATE_SEPARATOR) return VIEW_TYPE_DATE;
            if (entry.type == LogUtil.LogEntry.TYPE_GAP_SEPARATOR) return VIEW_TYPE_GAP;
            return VIEW_TYPE_LOG;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_DATE) {
                return createDateSeparatorHolder();
            } else if (viewType == VIEW_TYPE_GAP) {
                return new GapSeparatorHolder(createGapSeparatorView());
            } else {
                TextView tv = new TextView(LogActivity.this);
                tv.setTextSize(12f);
                tv.setPadding(0, 0, 0, 2);
                tv.setLineSpacing(0, 1f);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tv.setLayoutParams(lp);
                return new LogLineHolder(tv);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            LogUtil.LogEntry entry = entries.get(position);
            if (holder instanceof DateSeparatorHolder) {
                ((DateSeparatorHolder) holder).tvDate.setText(entry.text);
            } else if (holder instanceof LogLineHolder) {
                String line = entry.text;
                TextView tv = ((LogLineHolder) holder).textView;
                SpannableStringBuilder ssb = new SpannableStringBuilder(line);
                int color = getLineColor(line);
                ssb.setSpan(new ForegroundColorSpan(color), 0, line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setText(ssb);
            }
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        private int getLineColor(String line) {
            if (line.contains("[崩溃检测]")) return 0xFFE06D00;
            if (line.contains("[崩溃修复]") || line.contains("[崩溃修复-重试]")) return 0xFF2288DD;
            if (line.contains("[保活]")) return 0xFF22AA22;
            return 0xFFCCCCCC;
        }

        private DateSeparatorHolder createDateSeparatorHolder() {
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

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            layout.setLayoutParams(lp);

            return new DateSeparatorHolder(layout, tvDate);
        }

        private View createGapSeparatorView() {
            View line = new View(LogActivity.this);
            line.setBackgroundColor(0xFF444444);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 2);
            lp.topMargin = 6;
            lp.bottomMargin = 6;
            line.setLayoutParams(lp);
            return line;
        }

        class LogLineHolder extends RecyclerView.ViewHolder {
            TextView textView;

            LogLineHolder(TextView itemView) {
                super(itemView);
                this.textView = itemView;
            }
        }

        class DateSeparatorHolder extends RecyclerView.ViewHolder {
            TextView tvDate;

            DateSeparatorHolder(View itemView, TextView tvDate) {
                super(itemView);
                this.tvDate = tvDate;
            }
        }

        class GapSeparatorHolder extends RecyclerView.ViewHolder {
            GapSeparatorHolder(View itemView) {
                super(itemView);
            }
        }
    }
}
