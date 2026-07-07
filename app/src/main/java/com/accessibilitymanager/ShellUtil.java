package com.accessibilitymanager;

import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

public class ShellUtil {

    public static final int PERM_NONE = 0;
    public static final int PERM_ROOT = 1;
    public static final int PERM_SHIZUKU = 2;

    private static int sPermissionState = -1;

    public static synchronized int getPermissionState() {
        if (sPermissionState >= 0) return sPermissionState;
        sPermissionState = detectPermission();
        return sPermissionState;
    }

    public static boolean hasRoot() {
        return getPermissionState() == PERM_ROOT;
    }

    public static boolean hasShizukuOnly() {
        return getPermissionState() == PERM_SHIZUKU;
    }

    public static boolean hasAnyPermission() {
        return getPermissionState() != PERM_NONE;
    }

    public static synchronized void reset() {
        sPermissionState = -1;
    }

    private static int detectPermission() {
        if (checkRoot()) {
            return PERM_ROOT;
        }
        if (checkShizuku()) {
            return PERM_SHIZUKU;
        }
        return PERM_NONE;
    }

    private static boolean checkRoot() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("echo root_ok\nexit\n");
            os.flush();
            os.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            reader.close();

            BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while (errReader.readLine() != null) {
            }
            errReader.close();

            p.waitFor();
            return output.toString().contains("root_ok");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isShizukuRunning() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Shizuku.checkSelfPermission();
                return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    /** 检查 DUMP 权限是否已授予 */
    public static boolean hasDumpPermission(android.content.Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission("android.permission.DUMP")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    /** 检查 WRITE_SECURE_SETTINGS 权限是否已授予 */
    public static boolean hasWriteSecurePermission(android.content.Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        try {
            android.content.pm.ApplicationInfo ai = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(),
                            android.content.pm.PackageManager.GET_CONFIGURATIONS)
                    .applicationInfo;
            return (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean checkShizuku() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    public static Process exec() throws IOException {
        int state = getPermissionState();
        if (state == PERM_ROOT) {
            return Runtime.getRuntime().exec("su");
        } else if (state == PERM_SHIZUKU) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Shizuku.newProcess(new String[]{"sh"}, null, null);
            }
        }
        throw new IOException("No root or shizuku permission");
    }
}
