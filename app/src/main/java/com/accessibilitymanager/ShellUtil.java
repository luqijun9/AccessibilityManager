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

    public static boolean pingShell() {
        try {
            Process p = exec();
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("echo shell_alive\nexit\n");
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

            final Process waitP = p;
            Thread wt = new Thread(() -> {
                try { waitP.waitFor(); } catch (InterruptedException ignored) {}
            });
            wt.start();
            wt.join(10000);
            if (wt.isAlive()) {
                waitP.destroy();
                return false;
            }
            return output.toString().contains("shell_alive");
        } catch (Exception e) {
            return false;
        }
    }

    public static Process execWithRetry() throws IOException {
        try {
            return exec();
        } catch (IOException e) {
            reset();
            return exec();
        }
    }
}
