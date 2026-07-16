package com.accessibilitymanager;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class WhitelistActivity extends AppCompatActivity {
    private SharedPreferences sp;
    private Toolbar toolbar;
    private RecyclerView listView;
    private WhitelistAdapter mAdapter;
    private List<AccessibilityServiceInfo> tmp;
    private List<AccessibilityServiceInfo> mFilteredList;
    private AccessibilityManager mAccessibilityManager;

    private boolean night;
    private String mCurrentTheme;
    private int mColorPrimary;

    // Search
    private View mSearchView;
    private EditText mSearchInput;
    private boolean mIsSearching = false;

    // Global switch
    private MaterialSwitch mGlobalWhitelistSwitch;

    // Cache
    private List<AppCacheItem> mCachedAppList;
    static class AppCacheItem {
        ApplicationInfo info;
        String label;
        Drawable icon;
        AppCacheItem(ApplicationInfo info, String label, Drawable icon) {
            this.info = info; this.label = label; this.icon = icon;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtils.applyTheme(this);
        mCurrentTheme = getSharedPreferences("Main", Context.MODE_PRIVATE).getString(ThemeUtils.PREF_THEME, ThemeUtils.THEME_BLUE);
        super.onCreate(savedInstanceState);

        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
        mColorPrimary = typedValue.data;
        night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        setContentView(R.layout.activity_whitelist);
        sp = getSharedPreferences("data", 0);

        if (!night) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    controller.setSystemBarsAppearance(
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        }

        toolbar = findViewById(R.id.toolbar);
        setupToolbarTitle();


        listView = findViewById(R.id.list);
        listView.setLayoutManager(new LinearLayoutManager(this));

        mAccessibilityManager = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        loadServices();
    }

    private void loadServices() {
        List<AccessibilityServiceInfo> l = mAccessibilityManager.getInstalledAccessibilityServiceList();
        tmp = new ArrayList<>();
        boolean userOnly = sp.getBoolean("useronly", false);
        for (AccessibilityServiceInfo info : l) {
            if (userOnly && !isUserApp(info)) continue;
            tmp.add(info);
        }
        sortForWhitelist(tmp);
        updateAdapter(tmp);
    }

    private void setupToolbarTitle() {
        toolbar.setNavigationIcon(R.drawable.ic_back_arrow);
        int textColor = night ? Color.WHITE : Color.BLACK;
        if (toolbar.getNavigationIcon() != null) {
            toolbar.getNavigationIcon().setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        layout.setClickable(true);
        layout.setFocusable(true);

        TextView mTitleText = new TextView(this);
        mTitleText.setText("局部关闭");
        mTitleText.setTextColor(textColor);
        mTitleText.setTextSize(20);
        mTitleText.setTypeface(null, android.graphics.Typeface.BOLD);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mTitleText.setLayoutParams(textParams);

        mGlobalWhitelistSwitch = new MaterialSwitch(this);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        switchParams.setMarginEnd((int) (16 * getResources().getDisplayMetrics().density));
        mGlobalWhitelistSwitch.setLayoutParams(switchParams);
        mGlobalWhitelistSwitch.setChecked(sp.getBoolean("whitelist_global_enable", false));
        
        final CompoundButton.OnCheckedChangeListener[] whitelistSwitchListenerHolder = new CompoundButton.OnCheckedChangeListener[1];
        whitelistSwitchListenerHolder[0] = (buttonView, isChecked) -> {
            if (isChecked && !sp.getBoolean("whitelist_global_enable", false)) {
                buttonView.setOnCheckedChangeListener(null);
                buttonView.setChecked(false);
                buttonView.setOnCheckedChangeListener(whitelistSwitchListenerHolder[0]);
                showWhitelistEnableDialog(buttonView, whitelistSwitchListenerHolder[0]);
                return;
            }
            sp.edit().putBoolean("whitelist_global_enable", isChecked).apply();
            if (mAdapter != null) mAdapter.notifyDataSetChanged();
            Intent intent = new Intent(WhitelistActivity.this, daemonService.class);
            intent.putExtra("source", "AppSwitch");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } catch (Exception ignored) {}
        };
        mGlobalWhitelistSwitch.setOnCheckedChangeListener(whitelistSwitchListenerHolder[0]);

        ImageView mSearchBtn = new ImageView(this);
        mSearchBtn.setImageResource(R.drawable.ic_search);
        mSearchBtn.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN);
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
        mSearchBtn.setBackgroundResource(outValue.resourceId);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        mSearchBtn.setPadding(padding, padding, padding, padding);
        mSearchBtn.setOnClickListener(v -> enterSearchMode());

        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        searchParams.setMarginEnd((int) (8 * getResources().getDisplayMetrics().density));
        mSearchBtn.setLayoutParams(searchParams);

        layout.addView(mTitleText);
        layout.addView(mSearchBtn);
        layout.addView(mGlobalWhitelistSwitch);

        toolbar.addView(layout, new Toolbar.LayoutParams(
                Toolbar.LayoutParams.MATCH_PARENT,
                Toolbar.LayoutParams.MATCH_PARENT,
                android.view.Gravity.START
        ));
    }

    private void enterSearchMode() {
        if (mIsSearching) return;
        mIsSearching = true;
        for(int i=0; i<toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof LinearLayout) {
                child.setVisibility(View.GONE);
            }
        }

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

        mSearchView.setAlpha(0f);
        mSearchView.setTranslationX(80f * getResources().getDisplayMetrics().density);
        mSearchView.animate().alpha(1f).translationX(0f).setDuration(220).start();
    }

    private void exitSearchMode() {
        if (!mIsSearching) return;
        mIsSearching = false;

        toolbar.removeView(mSearchView);
        mSearchView = null;
        mSearchInput = null;

        for(int i=0; i<toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof LinearLayout) {
                child.setVisibility(View.VISIBLE);
            }
        }

        sortForWhitelist(tmp);
        updateAdapter(tmp);
        mFilteredList = null;

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(toolbar.getWindowToken(), 0);
    }

    private final TextWatcher searchWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override
        public void afterTextChanged(Editable s) {
            filterServices(s.toString().trim());
        }
    };

    private void filterServices(String query) {
        if (query.isEmpty()) {
            mFilteredList = null;
            updateAdapter(tmp);
            return;
        }
        mFilteredList = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        PackageManager pm = getPackageManager();
        for (AccessibilityServiceInfo info : tmp) {
            String id = info.getId();
            String[] parts = id.split("/");
            if (parts.length != 2) continue;
            String packageName = parts[0];
            String serviceName = parts[1];
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                String appLabel = pm.getApplicationLabel(appInfo).toString();
                if (appLabel.toLowerCase().contains(lowerQuery)) {
                    mFilteredList.add(info);
                    continue;
                }
            } catch (Exception ignored) {}
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
        sortForWhitelist(mFilteredList);
        updateAdapter(mFilteredList);
    }

    private void updateAdapter(List<AccessibilityServiceInfo> newData) {
        if (mAdapter == null) {
            mAdapter = new WhitelistAdapter(newData);
            listView.setAdapter(mAdapter);
        } else {
            mAdapter.updateData(newData);
        }
        findViewById(R.id.empty_view).setVisibility(newData.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void sortForWhitelist(List<AccessibilityServiceInfo> list) {
        String whitelistServices = sp.getString("whitelist_services", "");
        final java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.CHINA);
        final ComponentName ownCn = ComponentName.unflattenFromString(new ComponentName(this, MyAccessibilityService.class).flattenToString());
        
        Collections.sort(list, new Comparator<AccessibilityServiceInfo>() {
            @Override
            public int compare(AccessibilityServiceInfo info1, AccessibilityServiceInfo info2) {
                String id1 = info1.getId();
                String id2 = info2.getId();
                
                boolean enabled1 = containsService(whitelistServices, normalizeServiceId(id1));
                boolean enabled2 = containsService(whitelistServices, normalizeServiceId(id2));
                if (enabled1 && !enabled2) return -1;
                if (!enabled1 && enabled2) return 1;

                ComponentName cn1 = ComponentName.unflattenFromString(id1);
                ComponentName cn2 = ComponentName.unflattenFromString(id2);
                boolean own1 = ownCn != null && ownCn.equals(cn1);
                boolean own2 = ownCn != null && ownCn.equals(cn2);
                if (own1 && !own2) return -1;
                if (!own1 && own2) return 1;

                String label1 = info1.getResolveInfo().loadLabel(getPackageManager()).toString();
                String label2 = info2.getResolveInfo().loadLabel(getPackageManager()).toString();
                return collator.compare(label1, label2);
            }
        });
    }

    private boolean containsService(String services, String serviceName) {
        if (services == null || services.isEmpty()) return false;
        String[] enabledServices = services.split(":");
        ComponentName targetComponent = ComponentName.unflattenFromString(serviceName);
        for (String enabledService : enabledServices) {
            ComponentName enabledComponent = ComponentName.unflattenFromString(enabledService);
            if (enabledComponent != null && enabledComponent.equals(targetComponent)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeServiceId(String id) {
        ComponentName componentName = ComponentName.unflattenFromString(id);
        if (componentName != null) {
            return componentName.flattenToString();
        }
        return id;
    }

    private boolean isUserApp(AccessibilityServiceInfo info) {
        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(info.getResolveInfo().serviceInfo.packageName, 0);
            return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean checkWriteSecurePermission() {
        return checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED;
    }

    private boolean isOwnAccessibilityServiceEnabled() {
        String ownServiceId = new ComponentName(this, MyAccessibilityService.class).flattenToString();
        String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (settingValue == null) settingValue = "";
        return containsService(settingValue, ownServiceId);
    }

    private void showWhitelistEnableDialog(final CompoundButton switchButton, final CompoundButton.OnCheckedChangeListener listener) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = getLayoutInflater().inflate(R.layout.dialog_whitelist_enable, null);
        dialog.setContentView(view);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int marginPx = (int) (16 * dm.density + 0.5f);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = dm.widthPixels - marginPx * 2;
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }

        final TextView statusText = view.findViewById(R.id.permission_status_text);
        final Button btnGoSettings = view.findViewById(R.id.btn_go_settings);
        
        Runnable updateStatus = () -> {
            boolean isEnabled = isOwnAccessibilityServiceEnabled();
            if (isEnabled) {
                statusText.setText("已开启 ✓");
                statusText.setTextColor(Color.parseColor("#4CAF50")); // Green
                btnGoSettings.setVisibility(View.GONE);
            } else {
                statusText.setText("未开启 ✗");
                statusText.setTextColor(Color.parseColor("#F44336")); // Red
                btnGoSettings.setVisibility(View.VISIBLE);
            }
        };
        
        updateStatus.run();
        
        View.OnClickListener goSettingsAction = v -> {
            if (checkWriteSecurePermission()) {
                Toast.makeText(WhitelistActivity.this, "需要安全设置写权限才能开启无障碍", Toast.LENGTH_SHORT).show();
                return;
            }
            String ownServiceId = new ComponentName(WhitelistActivity.this, MyAccessibilityService.class).flattenToString();
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) s = "";
            if (!isOwnAccessibilityServiceEnabled()) {
                Settings.Secure.putString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        ownServiceId + ":" + s);
            }
            String currentDaemon = sp.getString("daemon", "");
            if (!currentDaemon.contains(ownServiceId)) {
                String newDaemon = currentDaemon.isEmpty() ? ownServiceId : ownServiceId + ":" + currentDaemon;
                sp.edit().putString("daemon", newDaemon).apply();
            }
            updateStatus.run();
            Toast.makeText(WhitelistActivity.this, "已尝试开启", Toast.LENGTH_SHORT).show();
        };

        btnGoSettings.setOnClickListener(goSettingsAction);
        statusText.setOnClickListener(goSettingsAction);
        
        Button btnCancel = view.findViewById(R.id.btn_cancel);
        Button btnConfirm = view.findViewById(R.id.btn_continue);
        
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            switchButton.setOnCheckedChangeListener(null);
            switchButton.setChecked(true);
            switchButton.setOnCheckedChangeListener(listener);
            sp.edit().putBoolean("whitelist_global_enable", true).apply();
            if (mAdapter != null) mAdapter.notifyDataSetChanged();
            Intent intent = new Intent(WhitelistActivity.this, daemonService.class);
            intent.putExtra("source", "AppSwitch");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } catch (Exception ignored) {}
        });
        
        dialog.show();
    }

    private void showWhitelistAppPickerDialog(String serviceName) {
        android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(this);
        progressDialog.setMessage("正在加载应用列表...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Set<String> checkedPkgs = new HashSet<>();
            String currentList = sp.getString("whitelist_apps_" + serviceName, "");
            if (!currentList.isEmpty()) {
                Collections.addAll(checkedPkgs, currentList.split(","));
            }

            if (mCachedAppList == null) {
                List<ApplicationInfo> allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                mCachedAppList = new java.util.ArrayList<>();
                for (ApplicationInfo info : allApps) {
                    Intent launchIntent = pm.getLaunchIntentForPackage(info.packageName);
                    if (launchIntent != null) {
                        mCachedAppList.add(new AppCacheItem(info, info.loadLabel(pm).toString(), info.loadIcon(pm)));
                    }
                }
            }
            List<AppCacheItem> userApps = new java.util.ArrayList<>(mCachedAppList);

            java.util.Collections.sort(userApps, (a, b) -> {
                boolean aChecked = checkedPkgs.contains(a.info.packageName);
                boolean bChecked = checkedPkgs.contains(b.info.packageName);
                if (aChecked != bChecked) {
                    return aChecked ? -1 : 1;
                }
                return java.text.Collator.getInstance().compare(a.label, b.label);
            });

            runOnUiThread(() -> {
                progressDialog.dismiss();
                if (isDestroyed() || isFinishing()) return;

                View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_whitelist_apps, null);
                EditText searchEdit = dialogView.findViewById(R.id.edit_search_app);
                ListView listViewDialog = dialogView.findViewById(R.id.list_apps);
                android.widget.CheckedTextView checkSelectAll = dialogView.findViewById(R.id.check_select_all);
                Runnable[] updateSelectAllState = new Runnable[1];

                class AppAdapter extends BaseAdapter implements android.widget.Filterable {
                    private List<AppCacheItem> originalList = userApps;
                    private List<AppCacheItem> filteredList = new java.util.ArrayList<>(userApps);
                    
                    @Override
                    public int getCount() { return filteredList.size(); }
                    @Override
                    public AppCacheItem getItem(int position) { return filteredList.get(position); }
                    @Override
                    public long getItemId(int position) { return position; }

                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        if (convertView == null) {
                            convertView = LayoutInflater.from(WhitelistActivity.this).inflate(R.layout.item_app_picker, parent, false);
                        }
                        ImageView iconView = convertView.findViewById(R.id.app_icon);
                        android.widget.CheckedTextView nameView = convertView.findViewById(R.id.app_name);
                        
                        AppCacheItem item = getItem(position);
                        iconView.setImageDrawable(item.icon);
                        nameView.setText(item.label);
                        nameView.setChecked(checkedPkgs.contains(item.info.packageName));
                        
                        convertView.setOnClickListener(v -> {
                            boolean isChecked = !nameView.isChecked();
                            nameView.setChecked(isChecked);
                            if (isChecked) {
                                checkedPkgs.add(item.info.packageName);
                            } else {
                                checkedPkgs.remove(item.info.packageName);
                            }
                            if (updateSelectAllState[0] != null) {
                                updateSelectAllState[0].run();
                            }
                        });
                        return convertView;
                    }

                    @Override
                    public android.widget.Filter getFilter() {
                        return new android.widget.Filter() {
                            @Override
                            protected FilterResults performFiltering(CharSequence constraint) {
                                FilterResults results = new FilterResults();
                                if (TextUtils.isEmpty(constraint)) {
                                    results.values = originalList;
                                    results.count = originalList.size();
                                } else {
                                    String filterString = constraint.toString().toLowerCase();
                                    List<AppCacheItem> filtered = new java.util.ArrayList<>();
                                    for (AppCacheItem item : originalList) {
                                        if (item.label.toLowerCase().contains(filterString) || 
                                            item.info.packageName.toLowerCase().contains(filterString)) {
                                            filtered.add(item);
                                        }
                                    }
                                    results.values = filtered;
                                    results.count = filtered.size();
                                }
                                return results;
                            }
                            @Override
                            @SuppressWarnings("unchecked")
                            protected void publishResults(CharSequence constraint, FilterResults results) {
                                filteredList = (List<AppCacheItem>) results.values;
                                notifyDataSetChanged();
                                if (updateSelectAllState[0] != null) {
                                    updateSelectAllState[0].run();
                                }
                            }
                        };
                    }
                }
                
                AppAdapter adapter = new AppAdapter();
                listViewDialog.setAdapter(adapter);
                
                View.OnClickListener selectAllListener = v -> {
                    boolean isChecked = !checkSelectAll.isChecked();
                    checkSelectAll.setChecked(isChecked);
                    if (isChecked) {
                        for (AppCacheItem item : adapter.originalList) checkedPkgs.add(item.info.packageName);
                    } else {
                        for (AppCacheItem item : adapter.originalList) checkedPkgs.remove(item.info.packageName);
                    }
                    adapter.notifyDataSetChanged();
                };
                checkSelectAll.setOnClickListener(selectAllListener);
                
                updateSelectAllState[0] = () -> {
                    if (adapter.originalList == null || adapter.originalList.isEmpty()) {
                        checkSelectAll.setChecked(false);
                        return;
                    }
                    boolean allChecked = true;
                    for (AppCacheItem item : adapter.originalList) {
                        if (!checkedPkgs.contains(item.info.packageName)) {
                            allChecked = false;
                            break;
                        }
                    }
                    checkSelectAll.setChecked(allChecked);
                };

                updateSelectAllState[0].run();
                
                searchEdit.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override
                    public void afterTextChanged(Editable s) {
                        adapter.getFilter().filter(s);
                    }
                });

                androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("配置应用白名单")
                        .setView(dialogView)
                        .setPositiveButton("保存", (d, which) -> {
                            sp.edit().putString("whitelist_apps_" + serviceName, TextUtils.join(",", checkedPkgs)).apply();
                            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .create();
                
                Window window = dialog.getWindow();
                dialog.show();
            });
        }).start();
    }

    private class WhitelistAdapter extends RecyclerView.Adapter<WhitelistAdapter.ViewHolder> {
        private List<AccessibilityServiceInfo> mData;
        private PackageManager pm;

        WhitelistAdapter(List<AccessibilityServiceInfo> data) {
            mData = data;
            pm = getPackageManager();
        }

        void updateData(List<AccessibilityServiceInfo> newData) {
            mData = newData;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_whitelist, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AccessibilityServiceInfo info = mData.get(position);
            String serviceName = normalizeServiceId(info.getId());
            String[] packageName = Pattern.compile("/").split(serviceName);
            
            Drawable icon = null;
            String packageLabel = null;
            String serviceLabel = null;
            String description = null;
            
            try {
                icon = pm.getApplicationIcon(packageName[0]);
                packageLabel = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName[0], PackageManager.GET_META_DATA)));
                serviceLabel = pm.getServiceInfo(new ComponentName(packageName[0], packageName[1]), PackageManager.MATCH_DEFAULT_ONLY).loadLabel(pm).toString();
                description = info.loadDescription(pm);
            } catch (PackageManager.NameNotFoundException ignored) {}
            
            if (serviceLabel == null) serviceLabel = packageLabel;
            
            holder.imageView.setImageDrawable(icon);
            holder.textb.setText(packageLabel.equals(serviceLabel) ? serviceLabel : String.format("%s/%s", packageLabel, serviceLabel));
            holder.texta.setText(description == null || description.length() == 0 ? "该应用没有提供描述" : description);
            
            String whitelistServices = sp.getString("whitelist_services", "");
            boolean isWhitelisted = containsService(whitelistServices, serviceName);
            boolean globalWhitelistEnable = sp.getBoolean("whitelist_global_enable", false);
            
            holder.sw.setOnCheckedChangeListener(null);
            holder.sw.setChecked(isWhitelisted);
            holder.sw.setEnabled(globalWhitelistEnable);
            
            if (isWhitelisted) {
                holder.ib.setVisibility(View.VISIBLE);
                if (globalWhitelistEnable) {
                    holder.ib.setAlpha(1.0f);
                    holder.ib.setOnClickListener(v -> showWhitelistAppPickerDialog(serviceName));
                } else {
                    holder.ib.setAlpha(0.5f);
                    holder.ib.setOnClickListener(null);
                }
            } else {
                holder.ib.setVisibility(View.INVISIBLE);
                holder.ib.setAlpha(1.0f);
                holder.ib.setOnClickListener(null);
            }
            
            holder.sw.setOnCheckedChangeListener((view, isChecked) -> {
                String ws = sp.getString("whitelist_services", "");
                String newWs = ws;
                if (isChecked) {
                    if (!containsService(ws, serviceName)) {
                        newWs = ws.isEmpty() ? serviceName : serviceName + ":" + ws;
                    }
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (String entry : ws.split(":")) {
                        if (entry.isEmpty() || ComponentName.unflattenFromString(serviceName).equals(ComponentName.unflattenFromString(entry))) continue;
                        if (sb.length() > 0) sb.append(":");
                        sb.append(entry);
                    }
                    newWs = sb.toString();
                }
                sp.edit().putString("whitelist_services", newWs).apply();
                
                if (isChecked) {
                    holder.ib.setVisibility(View.VISIBLE);
                    if (sp.getBoolean("whitelist_global_enable", false)) {
                        holder.ib.setAlpha(1.0f);
                        holder.ib.setOnClickListener(v -> showWhitelistAppPickerDialog(serviceName));
                    } else {
                        holder.ib.setAlpha(0.5f);
                    }
                } else {
                    holder.ib.setVisibility(View.INVISIBLE);
                    holder.ib.setAlpha(1.0f);
                }
                sortForWhitelist(tmp);
            });
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;
            TextView texta, textb;
            MaterialSwitch sw;
            ImageButton ib;
            
            ViewHolder(View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.c);
                textb = itemView.findViewById(R.id.b);
                texta = itemView.findViewById(R.id.a);
                sw = itemView.findViewById(R.id.s);
                ib = itemView.findViewById(R.id.ib);
            }
        }
    }
}
