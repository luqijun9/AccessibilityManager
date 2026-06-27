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
    private static final String PROXY_DOWNLOAD_PREFIX = "https://ghfast.top/";
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
                        final String releaseNotes = json.optString("body", "");
                        final String downloadUrl = PROXY_DOWNLOAD_PREFIX + json.getJSONArray("assets").getJSONObject(0).getString("browser_download_url");

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