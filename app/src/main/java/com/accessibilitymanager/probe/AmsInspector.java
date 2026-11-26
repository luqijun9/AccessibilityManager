package com.accessibilitymanager.probe;

import android.content.ComponentName;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AMS 无障碍连接探测、去重 UserState 扫描、反射缓存与重复连接延迟去重。
 */
final class AmsInspector {

    static final String STATE_CONNECTIONS = "connections";

    /** 单次绑定查询结果，替代全局副作用标志 sBoundCheckAvailable。 */
    static final class BoundLookupResult {
        final Object connection;
        final boolean checkAvailable;

        BoundLookupResult(Object connection, boolean checkAvailable) {
            this.connection = connection;
            this.checkAvailable = checkAvailable;
        }
    }

    /** 单个 UserState 及其伴随连接锁、采样连接类。 */
    static final class UserStateEntry {
        final Object userState;
        final Object lock;
        final Class<?> sampleConnectionClass;

        UserStateEntry(Object userState, Object lock, Class<?> sampleConnectionClass) {
            this.userState = userState;
            this.lock = lock;
            this.sampleConnectionClass = sampleConnectionClass;
        }
    }

    /** 一个待动用的被观察 UserState 弱引用记录（来自 bindLocked/unbindLocked 钩子）。 */
    private static final class ObservedUserState {
        final WeakReference<Object> userStateRef;
        final WeakReference<Object> lockRef;
        final Class<?> sampleConnectionClass;

        ObservedUserState(Object userState, Object lock, Class<?> sampleConnectionClass) {
            this.userStateRef = new WeakReference<>(userState);
            this.lockRef = lock == null ? null : new WeakReference<>(lock);
            this.sampleConnectionClass = sampleConnectionClass;
        }
    }

    /**
     * 批处理共享 AMS 快照：对每个去重后的 UserState 仅加锁 1 次，一次性拷贝
     * mBoundServices、mComponentNameToServiceMap 与 mBindingServices。
     */
    static final class AmsSnapshot {
        private final List<UserStateSnapshot> userStates;

        private AmsSnapshot(List<UserStateSnapshot> userStates) {
            this.userStates = userStates;
        }

        boolean isEmpty() {
            return userStates.isEmpty();
        }

        BoundLookupResult lookupBound(ComponentName target) {
            if (target == null) {
                return new BoundLookupResult(null, false);
            }
            if (userStates.isEmpty()) {
                return lookupLocalFallback(target);
            }
            boolean checkAvailable = false;
            for (UserStateSnapshot us : userStates) {
                boolean matchedItem = false;
                for (Object item : us.boundServices) {
                    if (item == null) {
                        continue;
                    }
                    ComponentName name = getComponentName(item);
                    if (target.equals(name)) {
                        matchedItem = true;
                        ConnectionBindProbe.Result probe = probeConnection(item);
                        if (probe.isAvailable()) {
                            checkAvailable = true;
                        }
                        if (probe.isBound()) {
                            return new BoundLookupResult(item, true);
                        }
                    }
                }
                Object mappedItem = us.componentToService.get(target);
                if (mappedItem != null) {
                    matchedItem = true;
                    ConnectionBindProbe.Result probe = probeConnection(mappedItem);
                    if (probe.isAvailable()) {
                        checkAvailable = true;
                    }
                    if (probe.isBound()) {
                        return new BoundLookupResult(mappedItem, true);
                    }
                }
                if (!matchedItem && us.listsReadable && us.sampleConnectionClass != null
                        && ConnectionBindProbe.isClassSupported(us.sampleConnectionClass)) {
                    checkAvailable = true;
                }
            }
            return new BoundLookupResult(null, checkAvailable);
        }

        boolean isServiceBinding(ComponentName target) {
            if (target == null) {
                return false;
            }
            for (UserStateSnapshot us : userStates) {
                if (us.bindingServices.contains(target)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class UserStateSnapshot {
        final List<Object> boundServices;
        final Map<Object, Object> componentToService;
        final List<Object> bindingServices;
        final boolean listsReadable;
        final Class<?> sampleConnectionClass;

        UserStateSnapshot(List<Object> boundServices, Map<Object, Object> componentToService,
                List<Object> bindingServices, boolean listsReadable,
                Class<?> sampleConnectionClass) {
            this.boundServices = boundServices;
            this.componentToService = componentToService;
            this.bindingServices = bindingServices;
            this.listsReadable = listsReadable;
            this.sampleConnectionClass = sampleConnectionClass;
        }
    }

    /**
     * 一条待确认的重复连接：等到 AMS 的 mComponentNameToServiceMap 明确指向 incoming 后，
     * 才解绑 existing；否则先等待/重试，避免和 AMS 的 map 重建赛跑。
     */
    private static final class ConnectionDedupeTask {
        final ComponentName component;
        final String key;
        Object incoming;
        Object existing;
        int attempts;
        Runnable runnable;

        ConnectionDedupeTask(ComponentName component, Object incoming, Object existing) {
            this.component = component;
            this.key = component.flattenToString();
            this.incoming = incoming;
            this.existing = existing;
        }
    }

    private static final int MAX_OBSERVED_USER_STATES = 16;
    private static final Object sObservedUserStatesLock = new Object();
    private static final List<ObservedUserState> sObservedUserStates = new ArrayList<>();

    // 以下集合仅在 sHandler 线程读写
    private static final Map<String, Object> sConnections = new HashMap<>();
    private static final Map<String, ConnectionDedupeTask> sPendingConnectionDedupe = new HashMap<>();
    private static boolean sBoundCheckErrorLogged;
    private static volatile String sLastBindProbeStrategy;
    private static volatile String sLastBindProbeSchema;
    private static volatile boolean sBindProbeLogged;
    private static volatile Handler sHandler;

    // 反射缓存（ConcurrentHashMap 支持 AMS 线程与 sHandler 线程并发读写）
    private static final Map<String, Field> sFieldCache = new ConcurrentHashMap<>();
    private static final Map<String, Method> sMethodCache = new ConcurrentHashMap<>();

    private AmsInspector() {
    }

    static void attachHandler(Handler handler) {
        sHandler = handler;
    }

    static Object getKnownConnection(String key) {
        return sConnections.get(key);
    }

    static void putKnownConnection(String key, Object connection) {
        if (key != null && connection != null) {
            sConnections.put(key, connection);
        }
    }

    static int connectionCount() {
        return sConnections.size();
    }

    static String getLastBindProbeStrategy() {
        return sLastBindProbeStrategy;
    }

    static String getLastBindProbeSchema() {
        return sLastBindProbeSchema;
    }

    static void ensureBindProbeSampled() {
        if (sLastBindProbeStrategy == null && !sConnections.isEmpty()) {
            for (Object conn : sConnections.values()) {
                if (conn != null) {
                    probeConnection(conn);
                    break;
                }
            }
        }
    }

    static String connId(Object connection) {
        return connection == null
                ? "null"
                : ("@" + Integer.toHexString(System.identityHashCode(connection)));
    }

    /**
     * 在 bindLocked/unbindLocked 钩子或事件路径中被动登记 UserState 弱引用，
     * 补齐冷启动尚未收到 initializeService 时的检测盲区。
     *
     * <p>1. 解决场景：纯缓存读且不触发 Settings IPC，可在 AMS 持锁钩子线程安全执行；
     *    若服务冷启动仅经历过 bindLocked 尚未 initializeService，sConnections 为空，
     *    登记 UserState 后即可正常读取 mBoundServices / mBindingServices。
     * <p>2. 前置条件与边界：按对象身份（==）去重，列表上限 16 条；弱引用回收后自动清理。
     */
    static void recordUserStatePassive(Object connection) {
        if (connection == null) {
            return;
        }
        Object userState = getUserState(connection);
        if (userState == null) {
            return;
        }
        Object lock = getFieldValue(connection, "mLock");
        synchronized (sObservedUserStatesLock) {
            Iterator<ObservedUserState> it = sObservedUserStates.iterator();
            while (it.hasNext()) {
                ObservedUserState entry = it.next();
                Object existingUs = entry.userStateRef.get();
                if (existingUs == null) {
                    it.remove();
                    continue;
                }
                if (existingUs == userState) {
                    return;
                }
            }
            if (sObservedUserStates.size() < MAX_OBSERVED_USER_STATES) {
                sObservedUserStates.add(new ObservedUserState(userState, lock, connection.getClass()));
            }
        }
    }

    /**
     * 按对象身份（==）收集并去重所有可见的 UserState（纯集合操作，供单测验证去重效果）。
     *
     * <p>真实场景：User 0 下启用 8 个无障碍服务时，8 个连接指向同一个 AccessibilityUserState；
     * 不去重会导致每次状态检查重复锁 8 次 AMS mLock、拷贝 8 次 mBoundServices。
     */
    static List<Object> collectUniqueUserStates(Collection<?> connections,
            Collection<WeakReference<Object>> extraUserStates) {
        List<Object> unique = new ArrayList<>();
        if (connections != null) {
            for (Object conn : connections) {
                Object us = getUserState(conn);
                if (us != null && !containsIdentity(unique, us)) {
                    unique.add(us);
                }
            }
        }
        if (extraUserStates != null) {
            for (WeakReference<Object> ref : extraUserStates) {
                if (ref == null) {
                    continue;
                }
                Object us = ref.get();
                if (us != null && !containsIdentity(unique, us)) {
                    unique.add(us);
                }
            }
        }
        return unique;
    }

    private static List<UserStateEntry> collectUniqueUserStateEntries() {
        List<UserStateEntry> result = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        for (Object conn : sConnections.values()) {
            Object us = getUserState(conn);
            if (us != null && !containsIdentity(seen, us)) {
                seen.add(us);
                result.add(new UserStateEntry(us, getFieldValue(conn, "mLock"), conn.getClass()));
            }
        }
        synchronized (sObservedUserStatesLock) {
            Iterator<ObservedUserState> it = sObservedUserStates.iterator();
            while (it.hasNext()) {
                ObservedUserState entry = it.next();
                Object us = entry.userStateRef.get();
                if (us == null) {
                    it.remove();
                    continue;
                }
                if (!containsIdentity(seen, us)) {
                    seen.add(us);
                    Object lock = entry.lockRef != null ? entry.lockRef.get() : null;
                    result.add(new UserStateEntry(us, lock, entry.sampleConnectionClass));
                }
            }
        }
        return result;
    }

    private static boolean containsIdentity(List<Object> list, Object target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                return true;
            }
        }
        return false;
    }

    /** 采集一份批处理共享的 AMS 状态快照：每个去重后的 UserState 只加锁 1 次。 */
    static AmsSnapshot captureAmsSnapshot() {
        List<UserStateEntry> entries = collectUniqueUserStateEntries();
        List<UserStateSnapshot> snapshots = new ArrayList<>(entries.size());
        for (UserStateEntry entry : entries) {
            try {
                snapshots.add(readUserStateSnapshot(entry));
            } catch (Throwable t) {
                if (!sBoundCheckErrorLogged) {
                    sBoundCheckErrorLogged = true;
                    EventLogBuffer.event("失败", "绑定状态读取失败: " + t);
                }
            }
        }
        return new AmsSnapshot(snapshots);
    }

    private static UserStateSnapshot readUserStateSnapshot(UserStateEntry entry) {
        final List<Object> boundSnapshot = new ArrayList<>();
        final Map<Object, Object> mapSnapshot = new HashMap<>();
        final List<Object> bindingSnapshot = new ArrayList<>();
        final boolean[] flags = new boolean[2]; // [0]=sawBoundServices, [1]=sawMap
        Runnable reader = () -> {
            Object boundServices = getFieldValue(entry.userState, "mBoundServices");
            if (boundServices instanceof Iterable) {
                flags[0] = true;
                for (Object item : (Iterable<?>) boundServices) {
                    boundSnapshot.add(item);
                }
            }
            Object map = getFieldValue(entry.userState, "mComponentNameToServiceMap");
            if (map instanceof Map) {
                flags[1] = true;
                for (Map.Entry<?, ?> kv : ((Map<?, ?>) map).entrySet()) {
                    if (kv.getKey() != null && kv.getValue() != null) {
                        mapSnapshot.put(kv.getKey(), kv.getValue());
                    }
                }
            }
            Object bindingServices = getFieldValue(entry.userState, "mBindingServices");
            if (bindingServices instanceof Iterable) {
                for (Object item : (Iterable<?>) bindingServices) {
                    if (item != null) {
                        bindingSnapshot.add(item);
                    }
                }
            }
        };
        if (entry.lock != null) {
            synchronized (entry.lock) {
                reader.run();
            }
        } else {
            reader.run();
        }
        return new UserStateSnapshot(boundSnapshot, mapSnapshot, bindingSnapshot,
                flags[0] || flags[1], entry.sampleConnectionClass);
    }

    @FunctionalInterface
    interface ComponentKeyReader {
        Object readComponent(Object connection);
    }

    /**
     * 按 AMS 真值优先查询指定组件的当前已绑定连接。
     *
     * <p>1. 解决场景：旧实现优先返回本地缓存 sConnections.get(key)，一旦连接已被 AMS 摘除
     *    但本地对象仍残留接口引用，会误判为“已绑定”而漏修；现在优先扫描去重后的 AMS UserState。
     * <p>2. 兜底策略：仅在没有任何可读 UserState（如纯单测或极早期无 UserState）时退回查本地缓存。
     */
    static BoundLookupResult lookupBoundConnection(ComponentName target) {
        if (target == null) {
            return new BoundLookupResult(null, false);
        }
        List<UserStateEntry> entries = collectUniqueUserStateEntries();
        Object localFallback = sConnections.get(target.flattenToString());
        return lookupBoundInEntries(target, localFallback, entries, AmsInspector::getComponentName);
    }

    static BoundLookupResult lookupBoundInEntries(Object target, Object localFallback,
            List<UserStateEntry> entries, ComponentKeyReader keyReader) {
        if (target == null) {
            return new BoundLookupResult(null, false);
        }
        if (entries == null || entries.isEmpty()) {
            return lookupLocalFallbackConnection(localFallback);
        }
        boolean checkAvailable = false;
        for (UserStateEntry entry : entries) {
            if (entry == null || entry.userState == null) {
                continue;
            }
            try {
                List<Object> boundSnapshot = null;
                Object mappedItem = null;
                boolean sawBoundServices = false;
                boolean sawMap = false;

                if (entry.lock != null) {
                    synchronized (entry.lock) {
                        Object boundServices = getFieldValue(entry.userState, "mBoundServices");
                        if (boundServices instanceof Iterable) {
                            sawBoundServices = true;
                            boundSnapshot = new ArrayList<>();
                            for (Object item : (Iterable<?>) boundServices) {
                                boundSnapshot.add(item);
                            }
                        }
                        Object map = getFieldValue(entry.userState, "mComponentNameToServiceMap");
                        if (map instanceof Map) {
                            sawMap = true;
                            mappedItem = ((Map<?, ?>) map).get(target);
                        }
                    }
                } else {
                    Object boundServices = getFieldValue(entry.userState, "mBoundServices");
                    if (boundServices instanceof Iterable) {
                        sawBoundServices = true;
                        boundSnapshot = new ArrayList<>();
                        for (Object item : (Iterable<?>) boundServices) {
                            boundSnapshot.add(item);
                        }
                    }
                    Object map = getFieldValue(entry.userState, "mComponentNameToServiceMap");
                    if (map instanceof Map) {
                        sawMap = true;
                        mappedItem = ((Map<?, ?>) map).get(target);
                    }
                }

                boolean matchedItem = false;
                if (boundSnapshot != null) {
                    for (Object item : boundSnapshot) {
                        if (item == null) {
                            continue;
                        }
                        Object name = keyReader != null ? keyReader.readComponent(item) : null;
                        if (target.equals(name)) {
                            matchedItem = true;
                            ConnectionBindProbe.Result itemProbe = probeConnection(item);
                            if (itemProbe.isAvailable()) {
                                checkAvailable = true;
                            }
                            if (itemProbe.isBound()) {
                                return new BoundLookupResult(item, true);
                            }
                        }
                    }
                }
                if (mappedItem != null) {
                    matchedItem = true;
                    ConnectionBindProbe.Result mapProbe = probeConnection(mappedItem);
                    if (mapProbe.isAvailable()) {
                        checkAvailable = true;
                    }
                    if (mapProbe.isBound()) {
                        return new BoundLookupResult(mappedItem, true);
                    }
                }
                if (!matchedItem && (sawBoundServices || sawMap)
                        && entry.sampleConnectionClass != null
                        && ConnectionBindProbe.isClassSupported(entry.sampleConnectionClass)) {
                    checkAvailable = true;
                }
            } catch (Throwable t) {
                if (!sBoundCheckErrorLogged) {
                    sBoundCheckErrorLogged = true;
                    EventLogBuffer.event("失败", "绑定状态读取失败: " + t);
                }
            }
        }
        return new BoundLookupResult(null, checkAvailable);
    }

    private static BoundLookupResult lookupLocalFallback(ComponentName target) {
        return lookupLocalFallbackConnection(sConnections.get(target.flattenToString()));
    }

    private static BoundLookupResult lookupLocalFallbackConnection(Object known) {
        if (known == null) {
            return new BoundLookupResult(null, false);
        }
        ConnectionBindProbe.Result probe = probeConnection(known);
        return new BoundLookupResult(probe.isBound() ? known : null, probe.isAvailable());
    }

    static Object findBoundConnection(ComponentName target) {
        return lookupBoundConnection(target).connection;
    }

    static Object findAmsCurrentConnection(ComponentName target) {
        if (target == null) {
            return null;
        }
        for (UserStateEntry entry : collectUniqueUserStateEntries()) {
            if (entry.lock != null) {
                synchronized (entry.lock) {
                    Object map = getFieldValue(entry.userState, "mComponentNameToServiceMap");
                    if (map instanceof Map) {
                        Object current = ((Map<?, ?>) map).get(target);
                        if (current != null) {
                            return current;
                        }
                    }
                }
            } else {
                Object map = getFieldValue(entry.userState, "mComponentNameToServiceMap");
                if (map instanceof Map) {
                    Object current = ((Map<?, ?>) map).get(target);
                    if (current != null) {
                        return current;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 检查目标组件当前是否正处于 AMS {@code mBindingServices}（已发起 {@code bindLocked}、等待回调中）。
     */
    static boolean isComponentInBindingServices(Object userState, Object lock, Object target) {
        if (userState == null || target == null) {
            return false;
        }
        try {
            if (lock != null) {
                synchronized (lock) {
                    Object bindingServices = getFieldValue(userState, "mBindingServices");
                    return bindingServices instanceof Collection
                            && ((Collection<?>) bindingServices).contains(target);
                }
            }
            Object bindingServices = getFieldValue(userState, "mBindingServices");
            return bindingServices instanceof Collection
                    && ((Collection<?>) bindingServices).contains(target);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isServiceBinding(ComponentName target) {
        if (target == null) {
            return false;
        }
        for (UserStateEntry entry : collectUniqueUserStateEntries()) {
            if (isComponentInBindingServices(entry.userState, entry.lock, target)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasConnectionState() {
        return !collectUniqueUserStateEntries().isEmpty();
    }

    /**
     * 在连接自身的 {@code mLock} 同步保护下探测绑定状态。
     */
    static ConnectionBindProbe.Result probeWithConnectionLock(Object connection) {
        if (connection == null) {
            return ConnectionBindProbe.probe(null);
        }
        Object lock = getFieldValue(connection, "mLock");
        if (lock != null) {
            synchronized (lock) {
                return ConnectionBindProbe.probe(connection);
            }
        }
        return ConnectionBindProbe.probe(connection);
    }

    static ConnectionBindProbe.Result probeConnection(Object connection) {
        ConnectionBindProbe.Result result = probeWithConnectionLock(connection);
        if (connection != null) {
            sLastBindProbeStrategy = result.getStrategy();
            if (!result.getSchemaSummary().isEmpty()) {
                sLastBindProbeSchema = result.getSchemaSummary();
            }
            String className = connection.getClass().getName();
            if (result.isUnknown()) {
                EventLogBuffer.eventSkip("probe:unknown:" + className, "失败",
                        "无法识别连接绑定字段，已暂停自动修复判断 | class=" + className
                                + " schema=" + result.getSchemaSummary());
            } else if (!sBindProbeLogged && !"field:mServiceInterface".equals(result.getStrategy())) {
                sBindProbeLogged = true;
                EventLogBuffer.event("配置", "连接绑定探测已适配: " + result.getStrategy(),
                        "class=" + className + " schema=" + result.getSchemaSummary());
            }
        }
        return result;
    }

    static boolean isConnectionBound(Object connection) {
        return connection != null && probeConnection(connection).isBound();
    }

    static void clearCrashedMarker(Object connection, ComponentName component) {
        try {
            Object info = getFieldValue(connection, "mAccessibilityServiceInfo");
            if (info != null) {
                setFieldValue(info, "crashed", false);
            }
        } catch (Throwable t) {
            EventLogBuffer.log("clear crashed flag failed: " + t);
        }
    }

    static String getCrashedMarker(ComponentName component) {
        if (component == null) {
            return "无记录";
        }
        Object connection = sConnections.get(component.flattenToString());
        if (connection == null) {
            return "无记录";
        }
        Object info = getFieldValue(connection, "mAccessibilityServiceInfo");
        Object crashed = info == null ? null : getFieldValue(info, "crashed");
        return crashed instanceof Boolean ? String.valueOf(crashed) : "未知";
    }

    static ComponentName getComponentName(Object connection) {
        if (connection == null) {
            return null;
        }
        try {
            Method m = findMethod(connection.getClass(), "getComponentName");
            if (m != null) {
                Object res = m.invoke(connection);
                if (res instanceof ComponentName) {
                    return (ComponentName) res;
                }
            }
        } catch (Throwable ignored) {
        }
        Object obj = getFieldValue(connection, "mComponentName");
        return obj instanceof ComponentName ? (ComponentName) obj : null;
    }

    static Object getUserState(Object connection) {
        if (connection == null) {
            return null;
        }
        try {
            Object ref = getFieldValue(connection, "mUserStateWeakReference");
            if (ref instanceof WeakReference) {
                return ((WeakReference<?>) ref).get();
            }
            return ref;
        } catch (Throwable t) {
            return null;
        }
    }

    static void logConnectionEvent(String kind, ComponentName component,
            Object connection, Object existing, Object amsCurrent, boolean crashed,
            boolean hasInterface, String probeStrategy, String probeStatus,
            boolean pauseUnbind, long hookSeq, long hookAt, long handleAt) {
        StringBuilder sb = new StringBuilder("conn ");
        sb.append(kind)
                .append(" hookSeq=").append(hookSeq)
                .append(" hookAt=").append(hookAt)
                .append(" handleAt=").append(handleAt)
                .append(" delay=").append(Math.max(0L, handleAt - hookAt))
                .append(" key=").append(component.flattenToShortString())
                .append(" conn=").append(connId(connection))
                .append(" existing=").append(connId(existing))
                .append(" existingBound=").append(isConnectionBound(existing))
                .append(" incomingBound=").append(isConnectionBound(connection))
                .append(" amsCurrent=").append(connId(amsCurrent))
                .append(" iface=").append(hasInterface)
                .append(" probe=").append(probeStrategy)
                .append(" bindStatus=").append(probeStatus)
                .append(" crashed=").append(crashed)
                .append(" pauseUnbind=").append(pauseUnbind);
        EventLogBuffer.log(sb.toString());
    }

    /**
     * 发现同组件两个 bound 连接后，先登记一条延迟确认，而不是立即 unbind。
     */
    static void scheduleConnectionDedupe(ComponentName component,
            Object incoming, Object existing) {
        if (component == null || incoming == null || existing == null
                || incoming == existing) {
            return;
        }
        Handler handler = sHandler;
        if (handler == null) {
            EventLogBuffer.log("duplicate dedupe skipped (handler null): "
                    + component.flattenToShortString());
            return;
        }
        String key = component.flattenToString();
        ConnectionDedupeTask pending = sPendingConnectionDedupe.get(key);
        if (pending != null) {
            if (incoming == pending.existing) {
                // 最新事件又回到了旧连接对象：震荡取消，避免把当前连接误解绑
                finishConnectionDedupe(pending);
                return;
            }
            pending.incoming = incoming;
            pending.existing = existing;
            EventLogBuffer.log("duplicate dedupe coalesced: " + component.flattenToShortString()
                    + " old=" + connId(existing) + " new=" + connId(incoming)
                    + " attempts=" + pending.attempts);
            return;
        }
        ConnectionDedupeTask task = new ConnectionDedupeTask(component, incoming, existing);
        task.runnable = () -> runConnectionDedupe(task);
        sPendingConnectionDedupe.put(key, task);
        handler.postDelayed(task.runnable, RepairPolicy.CONNECTION_DEDUPE_DELAY_MS);
        EventLogBuffer.log("duplicate dedupe scheduled in " + RepairPolicy.CONNECTION_DEDUPE_DELAY_MS
                + "ms: " + component.flattenToShortString()
                + " old=" + connId(existing) + " new=" + connId(incoming));
    }

    private static void runConnectionDedupe(ConnectionDedupeTask task) {
        if (task == null || sPendingConnectionDedupe.get(task.key) != task) {
            return;
        }
        if (task.incoming == task.existing || sHandler == null) {
            finishConnectionDedupe(task);
            return;
        }
        if (!RepairPolicy.shouldAttemptConnectionDedupe(
                ModuleConfigStore.isModuleEnabled(),
                ModuleConfigStore.isDryRun(),
                ModuleConfigStore.shouldIgnoreSystemApp(task.component.getPackageName()),
                ModuleConfigStore.isManaged(task.component))) {
            finishConnectionDedupe(task);
            return;
        }
        if (ModuleConfigStore.isDebugPauseUnbind()) {
            EventLogBuffer.event("调试",
                    "重复连接确认已暂停: " + task.component.flattenToShortString()
                            + " old=" + connId(task.existing) + " new=" + connId(task.incoming),
                    "action=duplicate_dedupe_paused");
            finishConnectionDedupe(task);
            return;
        }

        Object current = sConnections.get(task.key);
        boolean existingBound = isConnectionBound(task.existing);
        boolean incomingBound = isConnectionBound(task.incoming);
        Object amsCurrent = findAmsCurrentConnection(task.component);
        boolean amsPointsIncoming = amsCurrent == task.incoming;
        boolean amsPointsExisting = amsCurrent == task.existing;
        RepairPolicy.ConnectionDedupeAction action = RepairPolicy.decideConnectionDedupe(
                existingBound, incomingBound, current == task.incoming,
                amsPointsIncoming, amsPointsExisting, task.attempts,
                RepairPolicy.CONNECTION_DEDUPE_MAX_ATTEMPTS);

        switch (action) {
            case WAIT:
                task.attempts++;
                EventLogBuffer.log("duplicate dedupe wait #" + task.attempts
                        + ": " + task.component.flattenToShortString()
                        + " old=" + connId(task.existing)
                        + " new=" + connId(task.incoming)
                        + " ams=" + connId(amsCurrent)
                        + " oldBound=" + existingBound
                        + " newBound=" + incomingBound);
                sHandler.removeCallbacks(task.runnable);
                sHandler.postDelayed(task.runnable, RepairPolicy.CONNECTION_DEDUPE_DELAY_MS);
                return;
            case UNBIND_EXISTING:
                EventLogBuffer.event("警告",
                        "AMS 已切换到新连接，解除旧连接: " + task.component.flattenToShortString()
                                + " old=" + connId(task.existing)
                                + " new=" + connId(task.incoming),
                        "action=duplicate_unbind_old ams=" + connId(amsCurrent));
                unbindConnection(task.existing, "duplicate_confirmed");
                finishConnectionDedupe(task);
                return;
            case UNBIND_INCOMING:
                EventLogBuffer.event("警告",
                        "AMS 仍指向旧连接，解除多余新连接: " + task.component.flattenToShortString()
                                + " old=" + connId(task.existing)
                                + " new=" + connId(task.incoming),
                        "action=duplicate_unbind_incoming ams=" + connId(amsCurrent));
                unbindConnection(task.incoming, "duplicate_incoming");
                if (sConnections.get(task.key) == task.incoming) {
                    sConnections.put(task.key, task.existing);
                }
                finishConnectionDedupe(task);
                return;
            case GIVE_UP:
            default:
                EventLogBuffer.log("duplicate dedupe give up: "
                        + task.component.flattenToShortString()
                        + " old=" + connId(task.existing)
                        + " new=" + connId(task.incoming)
                        + " ams=" + connId(amsCurrent)
                        + " oldBound=" + existingBound
                        + " newBound=" + incomingBound
                        + " attempts=" + task.attempts);
                finishConnectionDedupe(task);
                return;
        }
    }

    private static void finishConnectionDedupe(ConnectionDedupeTask task) {
        if (task == null) {
            return;
        }
        if (sPendingConnectionDedupe.get(task.key) == task) {
            sPendingConnectionDedupe.remove(task.key);
        }
        if (task.runnable != null && sHandler != null) {
            sHandler.removeCallbacks(task.runnable);
        }
    }

    private static void unbindConnection(Object connection, String reason) {
        if (connection == null) {
            return;
        }
        try {
            Method unbindMethod = findMethod(connection.getClass(), "unbindLocked");
            if (unbindMethod != null) {
                Object lock = getFieldValue(connection, "mLock");
                if (lock != null) {
                    synchronized (lock) {
                        unbindMethod.invoke(connection);
                    }
                } else {
                    unbindMethod.invoke(connection);
                }
                EventLogBuffer.log("unbindConnection (" + reason + "): successfully unbound "
                        + connId(connection));
            } else {
                EventLogBuffer.log("unbindConnection (" + reason
                        + "): unbindLocked not found on " + connection.getClass());
            }
        } catch (Throwable t) {
            EventLogBuffer.log("unbindConnection (" + reason + ") failed for "
                    + connId(connection) + ": " + t);
        }
    }

    // 反射辅助方法
    static Field findField(Class<?> clazz, String name) {
        if (clazz == null) {
            return null;
        }
        String key = clazz.getName() + "#" + name;
        Field cached = sFieldCache.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                sFieldCache.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        if (clazz == null) {
            return null;
        }
        StringBuilder keyBuilder = new StringBuilder(clazz.getName())
                .append('#').append(name).append('(');
        if (paramTypes != null) {
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) keyBuilder.append(',');
                keyBuilder.append(paramTypes[i] == null ? "null" : paramTypes[i].getName());
            }
        }
        keyBuilder.append(')');
        String key = keyBuilder.toString();
        Method cached = sMethodCache.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                sMethodCache.put(key, m);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    static Object getFieldValue(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            Field f = findField(target.getClass(), fieldName);
            if (f != null) {
                return f.get(target);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static void setFieldValue(Object target, String fieldName, Object value) {
        if (target == null) {
            return;
        }
        try {
            Field f = findField(target.getClass(), fieldName);
            if (f != null) {
                f.set(target, value);
            }
        } catch (Throwable ignored) {
        }
    }

    static ClassLoader findConnectionClassLoader() {
        for (Object conn : sConnections.values()) {
            if (conn != null && conn.getClass().getClassLoader() != null) {
                return conn.getClass().getClassLoader();
            }
        }
        return null;
    }

    static void saveHotReloadState(Map<String, Object> state) {
        state.put(STATE_CONNECTIONS, new HashMap<>(sConnections));
    }

    static void restoreHotReloadState(Map<?, ?> state) {
        Object conns = state.get(STATE_CONNECTIONS);
        if (conns instanceof Map) {
            sConnections.clear();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) conns).entrySet()) {
                if (entry.getKey() instanceof String && entry.getValue() != null) {
                    sConnections.put((String) entry.getKey(), entry.getValue());
                }
            }
        }
    }

    static void clearForHotReload() {
        sHandler = null;
        sBoundCheckErrorLogged = false;
        sLastBindProbeStrategy = null;
        sLastBindProbeSchema = null;
        sBindProbeLogged = false;
        sConnections.clear();
        sPendingConnectionDedupe.clear();
        synchronized (sObservedUserStatesLock) {
            sObservedUserStates.clear();
        }
        sFieldCache.clear();
        sMethodCache.clear();
    }

    static void resetForTesting() {
        clearForHotReload();
    }

    static void putConnectionForTesting(String key, Object conn) {
        sConnections.put(key, conn);
    }

    static Map<String, Object> getConnectionsForTesting() {
        return new HashMap<>(sConnections);
    }
}
