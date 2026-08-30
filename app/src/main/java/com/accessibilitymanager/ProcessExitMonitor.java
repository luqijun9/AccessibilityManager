package com.accessibilitymanager;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ProcessExitMonitor {

    private static final String PREF_LAST_RECORDED_EXIT = "last_recorded_exit_timestamp";
    private static final String PREF_LAST_HEARTBEAT = "last_heartbeat_time";
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final long HEARTBEAT_INTERVAL_MS = 20 * 60 * 1000L; // 20分钟一次心跳

    private static volatile long sLastHeartbeatWriteTime = 0;

    /**
     * 在 Application 或 daemonService 启动时调用，检查并记录上次退出原因
     */
    public static void checkProcessExit(Context context) {
        new Thread(() -> {
            try {
                SharedPreferences sp = context.getSharedPreferences("data", Context.MODE_PRIVATE);
                long lastRecordedExit = sp.getLong(PREF_LAST_RECORDED_EXIT, 0);
                long lastHeartbeat = sp.getLong(PREF_LAST_HEARTBEAT, 0);
                long now = System.currentTimeMillis();

                // 优先使用 Android 11+ 官方 ApplicationExitInfo (系统内核记录)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        List<ApplicationExitInfo> exitList = am.getHistoricalProcessExitReasons(
                                context.getPackageName(), 0, 3);
                        if (exitList != null && !exitList.isEmpty()) {
                            ApplicationExitInfo lastExit = exitList.get(0);
                            long exitTime = lastExit.getTimestamp();
                            if (exitTime > lastRecordedExit && exitTime < now) {
                                sp.edit().putLong(PREF_LAST_RECORDED_EXIT, exitTime).apply();

                                String reasonStr = getReasonString(lastExit.getReason());
                                String timeStr = TIME_FMT.format(new Date(exitTime));
                                long pssMb = lastExit.getPss() / (1024 * 1024);
                                String desc = lastExit.getDescription();

                                StringBuilder msg = new StringBuilder();
                                msg.append("[系统诊断] 上次管理器于 ").append(timeStr).append(" 被终止，原因: ").append(reasonStr);
                                if (pssMb > 0) {
                                    msg.append("，内存占用: ").append(pssMb).append("MB");
                                }
                                if (desc != null && !desc.isEmpty()) {
                                    msg.append(" (").append(desc.trim()).append(")");
                                }
                                LogUtil.log(context, msg.toString());
                                return;
                            }
                        }
                    }
                } else {
                    // Android 11 以下版本：使用 20 分钟心跳时间推算
                    if (lastHeartbeat > 0) {
                        long offlineDurationMs = now - lastHeartbeat;
                        // 离线达到或超过心跳间隔（20分钟）时记录推算日志
                        if (offlineDurationMs >= HEARTBEAT_INTERVAL_MS) {
                            String lastTimeStr = TIME_FMT.format(new Date(lastHeartbeat));
                            long minutes = offlineDurationMs / (60 * 1000);
                            LogUtil.log(context, "[系统诊断] 上次活跃时间为: " + lastTimeStr + "，离线时长约 " + minutes + " 分钟");
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    /**
     * 更新心跳时间（节流：每20分钟最多更新写入一次）
     */
    public static void updateHeartbeat(Context context) {
        long now = System.currentTimeMillis();
        if (now - sLastHeartbeatWriteTime < HEARTBEAT_INTERVAL_MS) {
            return; // 不足20分钟，跳过写入，避免频繁写磁盘
        }
        sLastHeartbeatWriteTime = now;
        try {
            SharedPreferences sp = context.getSharedPreferences("data", Context.MODE_PRIVATE);
            sp.edit().putLong(PREF_LAST_HEARTBEAT, now).apply();
        } catch (Exception ignored) {
        }
    }

    private static String getReasonString(int reason) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            switch (reason) {
                case ApplicationExitInfo.REASON_LOW_MEMORY:
                    return "系统低内存强杀 (Low Memory Killer)";
                case ApplicationExitInfo.REASON_USER_REQUESTED:
                    return "用户主动终止 (多任务划掉或强行停止)";
                case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                    return "资源/功耗超标被系统终止 (CPU/后台超额)";
                case ApplicationExitInfo.REASON_CRASH:
                    return "Java异常崩溃 (Crash)";
                case ApplicationExitInfo.REASON_CRASH_NATIVE:
                    return "Native底层崩溃 (Native Crash)";
                case ApplicationExitInfo.REASON_ANR:
                    return "应用无响应 (ANR)";
                case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                    return "初始化失败";
                case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                    return "权限变更导致重启";
                case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                    return "依赖系统进程死亡";
                case ApplicationExitInfo.REASON_OTHER:
                    return "其他系统原因";
                case ApplicationExitInfo.REASON_EXIT_SELF:
                    return "应用自身正常退出";
                default:
                    return "未知原因(" + reason + ")";
            }
        }
        return "未知";
    }
}
