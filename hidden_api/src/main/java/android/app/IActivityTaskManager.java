package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

import java.util.List;

/**
 * 系统隐藏接口 IActivityTaskManager 的编译时 Stub。
 * 运行时由 Android 系统 bootclasspath 提供真实实现，本 stub 仅用于编译通过。
 * 仅声明本项目实际用到的方法（getTasks / registerTaskStackListener / unregisterTaskStackListener）。
 */
@SuppressWarnings({"unused", "RedundantThrows"})
public interface IActivityTaskManager extends IInterface {

    // Android 10+
    abstract class Stub extends Binder implements IActivityTaskManager {
        public static IActivityTaskManager asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }
    }

    // https://diff.songe.li/i/IActivityTaskManager.getTasks
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum) throws Exception;

    List<ActivityManager.RunningTaskInfo> getTasks(
            int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra) throws Exception;

    List<ActivityManager.RunningTaskInfo> getTasks(
            int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId) throws Exception;

    void registerTaskStackListener(ITaskStackListener listener) throws Exception;

    void unregisterTaskStackListener(ITaskStackListener listener) throws Exception;
}
