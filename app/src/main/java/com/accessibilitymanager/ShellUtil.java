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
                return Shizuku.pingBinder();
            }
        } catch (Exception e) {
        }
        return false;
    }

    /** 诊断信息：Shizuku binder 状态与服务端版本，用于日志定位授权问题 */
    public static String shizukuDiag() {
        try {
            if (!Shizuku.pingBinder()) return "binder=未连接";
            return "binder=已连接,ver=" + Shizuku.getVersion();
        } catch (Throwable t) {
            return "binder=异常:" + t.getClass().getSimpleName();
        }
    }

    /** 诊断快照：单行输出 Shizuku 授权相关的全部状态，用于定位"授权失败/激活失败" */
    public static String shizukuState(android.content.Context ctx) {
        StringBuilder sb = new StringBuilder(200);
        try {
            sb.append("自身uid=").append(android.os.Process.myUid())
                    .append(",user=").append(android.os.Process.myUserHandle())
                    .append(",binder=").append(Shizuku.pingBinder() ? "已连接" : "未连接");
            try {
                if (Shizuku.pingBinder()) {
                    int serverUid = Shizuku.getUid();
                    sb.append(",服务端ver=").append(Shizuku.getVersion())
                            .append(",服务端uid=").append(serverUid)
                            .append(serverUid == 2000 ? "(adb)" : serverUid == 0 ? "(root)" : "")
                            .append(",preV11=").append(Shizuku.isPreV11());
                }
            } catch (Throwable ignored) {
            }
            try {
                sb.append(",Shizuku授权=").append(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                        ? "已授权" : "未授权")
                        .append(",之前被拒=").append(Shizuku.shouldShowRequestPermissionRationale());
            } catch (Throwable t) {
                sb.append(",Shizuku授权=查询失败(").append(t.getClass().getSimpleName()).append(")");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sb.append(",DUMP=").append(ctx.checkSelfPermission("android.permission.DUMP")
                                == PackageManager.PERMISSION_GRANTED ? "已授予" : "未授予")
                        .append(",WRITE_SECURE=").append(ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                                == PackageManager.PERMISSION_GRANTED ? "已授予" : "未授予");
            }
            sb.append(",管理器=").append(shizukuManagerVersion(ctx));
        } catch (Throwable t) {
            sb.append(",诊断异常=").append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static String shizukuManagerVersion(android.content.Context ctx) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo("moe.shizuku.manager", 0);
            return "已安装(" + pi.versionName + ")";
        } catch (Throwable t) {
            return "未安装";
        }
    }

    /** 打一条授权状态快照，日志页搜 [Shizuku诊断] 可看各时点的授权情况 */
    public static void logShizukuState(android.content.Context ctx, String scene) {
        // LSPosed 模式下崩溃检测/修复不走 Shizuku，不再输出诊断噪音
        if (ctx != null && ctx.getSharedPreferences("data", 0)
                .getBoolean("lsposed_mode", false)) {
            return;
        }
        LogUtil.log(ctx, "[Shizuku诊断] " + scene + " | " + shizukuState(ctx));
    }

    /** 读取进程 stderr（pm grant 的报错输出），读到 EOF 才返回，顺带保证进程已结束 */
    public static String readProcessError(Process p) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() < 500) sb.append(line).append(" | ");
            }
        } catch (Throwable ignored) {
        }
        return sb.toString().trim();
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
                // Shizuku.checkSelfPermission() 命中进程内缓存时不会访问 binder，
                // 服务端已重启/授权已失效时仍会返回已授权，必须先确认 binder 存活
                if (Shizuku.pingBinder()
                        && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
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
                if (!Shizuku.pingBinder()) {
                    throw new IOException("Shizuku 未运行");
                }
                return Shizuku.newProcess(new String[]{"sh"}, null, null);
            }
        }
        throw new IOException("No root or shizuku permission");
    }
}
