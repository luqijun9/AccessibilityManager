package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

/**
 * 系统隐藏接口 ITaskStackListener 的编译时 Stub。
 * 运行时由 Android 系统 bootclasspath 提供真实实现，本 stub 仅用于编译通过。
 */
@SuppressWarnings({"unused", "RedundantThrows"})
public interface ITaskStackListener {

    abstract class Stub extends Binder implements ITaskStackListener {
        public static ITaskStackListener asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            throw new RuntimeException("Stub!");
        }
    }

    // 任务栈发生变化（通用回调，应用→桌面不触发，从最近任务中划掉会触发）
    void onTaskStackChanged() throws Exception;

    // Android 8 ~ 9：切换到前台时回调，参数为 taskId
    void onTaskMovedToFront(int taskId) throws Exception;

    // Android 10+：切换到前台时回调，参数为 RunningTaskInfo
    void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) throws Exception;
}
