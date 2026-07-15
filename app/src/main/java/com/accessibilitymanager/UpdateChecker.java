package com.accessibilitymanager;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private static final String GITHUB_RELEASE_URL = "https://api.github.com/repos/luqijun9/AccessibilityManager/releases/latest";
    private static final String PROXY_DOWNLOAD_PREFIX = "https://gh-proxy.com/";
    private static boolean isChecking = false;

    public interface UpdateListener {
        void onUpdateAvailable(String versionName, String downloadUrl, String releaseNotes);
        void onNoUpdate();
        void onError(String errorMessage);
    }

    public static void checkForUpdate(final Context context, final UpdateListener listener) {
        if (isChecking) {
            return;
        }
        isChecking = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(GITHUB_RELEASE_URL);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    connection.setRequestProperty("Accept", "application/vnd.github.v3+json");

                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();

                        JSONObject json = new JSONObject(response.toString());
                        final String latestVersion = json.getString("tag_name");
                        String rawReleaseNotes = json.optString("body", "");
                        final String htmlUrl = json.optString("html_url", "https://github.com/luqijun9/AccessibilityManager/releases/latest");
                        
                        final String downloadUrl;
                        final String releaseNotes;
                        
                        // 检查云端跳转指令
                        if (rawReleaseNotes.contains("[jump_repo]") || rawReleaseNotes.contains("★") || rawReleaseNotes.contains("⭐")) {
                            // 如果包含指令，则跳转链接设置为仓库页面，并从更新说明中移除所有指令
                            downloadUrl = htmlUrl;
                            releaseNotes = rawReleaseNotes
                                    .replace("[jump_repo]", "")
                                    .replace("★", "")
                                    .replace("⭐", "")
                                    .trim();
                        } else {
                            releaseNotes = rawReleaseNotes;
                            // 原有的直接下载逻辑
                            final String rawDownloadUrl = json.getJSONArray("assets").getJSONObject(0).getString("browser_download_url");
                            
                            // 构造代理下载URL
                            final String proxyDownloadUrl = PROXY_DOWNLOAD_PREFIX + rawDownloadUrl;

                            // 测试代理连通性：先试代理，不通则降级直连
                            downloadUrl = testDownloadUrl(proxyDownloadUrl, rawDownloadUrl);
                        }

                        // 记录日志供调试
                        android.util.Log.i("UpdateChecker", "最终下载URL: " + downloadUrl);

                        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                        final String currentVersion = packageInfo.versionName;

                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                isChecking = false;
                                if (compareVersions(latestVersion, currentVersion) > 0) {
                                    listener.onUpdateAvailable(latestVersion, downloadUrl, releaseNotes);
                                } else {
                                    listener.onNoUpdate();
                                }
                            }
                        });
                    } else {
                        throw new Exception("HTTP response code: " + responseCode);
                    }
                    connection.disconnect();
                } catch (final Exception e) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            isChecking = false;
                            listener.onError(e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 测试下载URL连通性：先试代理，不通则降级直连
     * @param proxyUrl  ghproxy 代理URL
     * @param directUrl GitHub 原始直连URL
     * @return 可用的下载URL
     */
    private static String testDownloadUrl(String proxyUrl, String directUrl) {
        try {
            URL url = new URL(proxyUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            conn.disconnect();

            if (code >= 200 && code < 400) {
                android.util.Log.i("UpdateChecker", "代理下载可用, HTTP " + code);
                return proxyUrl;
            }
            android.util.Log.w("UpdateChecker", "代理返回异常状态码 " + code + "，降级直连");
        } catch (Exception e) {
            android.util.Log.w("UpdateChecker", "代理连接失败: " + e.getMessage() + "，降级直连");
        }
        return directUrl;
    }

    private static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.replaceAll("[^0-9.]", "").split("\\.");
        String[] parts2 = v2.replaceAll("[^0-9.]", "").split("\\.");
        
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) {
                return p1 - p2;
            }
        }
        return 0;
    }
}