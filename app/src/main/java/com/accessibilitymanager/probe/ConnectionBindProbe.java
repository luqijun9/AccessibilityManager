package com.accessibilitymanager.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨 Android 版本 / OEM 魔改的无障碍连接绑定状态探测器（纯反射，无 Android / Xposed 强依赖）。
 *
 * <p>解决的真实场景与背景：
 * <ol>
 *   <li>Android 10~16 的 {@code AbstractAccessibilityServiceConnection} 使用字段
 *       {@code mServiceInterface}（{@code IAccessibilityServiceClient}）与 {@code mService}（{@code IBinder}）
 *       保存客户端接口引用；</li>
 *   <li>Android 17（API 37 / HyperOS 4.0 等）在 AOSP 重构中将上述字段重命名为
 *       {@code mClient}（{@code IAccessibilityServiceClient}）与 {@code mClientBinder}（{@code IBinder}），
 *       同时保留无参方法 {@code isConnectedLocked()}；</li>
 *   <li>旧实现仅硬编码读取 {@code mServiceInterface}，找不到字段时静默返回 {@code null}，
 *       且未将绑定检测标记为不可用，导致系统把所有正常已绑定服务误判为“未绑定 / 重绑失败”，
 *       在事件驱动状态机下形成高频关开死循环。</li>
 * </ol>
 *
 * <p>前置假设与保护边界：
 * <ul>
 *   <li>若连接对象本身为 {@code null}，确定视为未绑定（{@link Status#UNBOUND}）；</li>
 *   <li>若连接对象非空，按优先级探测已知字段、接口类型兜底字段、Binder 字段及 {@code isConnectedLocked()}；
 *       任意候选访问器返回已连接即判定为 {@link Status#BOUND}；</li>
 *   <li><strong>严禁将“找不到任何探测字段/方法”或“全部反射抛出异常”伪装成 {@link Status#UNBOUND}</strong>：
 *       此时必须返回 {@link Status#UNKNOWN}，由上层把 {@code sBoundCheckAvailable} 置为 {@code false}
 *       并跳过自动关开修复，避免未知 Android 版本升级后触发误杀风暴。</li>
 * </ul>
 */
final class ConnectionBindProbe {

    private static final String A11Y_CLIENT_TYPE_NAME =
            "android.accessibilityservice.IAccessibilityServiceClient";
    private static final String A11Y_CLIENT_SIMPLE_SUFFIX = ".IAccessibilityServiceClient";
    private static final String A11Y_CLIENT_INNER_SUFFIX = "$IAccessibilityServiceClient";
    private static final int MAX_SCHEMA_ITEMS = 20;

    enum Status {
        BOUND,
        UNBOUND,
        UNKNOWN
    }

    static final class Result {
        private final Status status;
        private final String strategy;
        private final String schemaSummary;

        Result(Status status, String strategy, String schemaSummary) {
            this.status = status;
            this.strategy = strategy == null ? "" : strategy;
            this.schemaSummary = schemaSummary == null ? "" : schemaSummary;
        }

        Status getStatus() {
            return status;
        }

        String getStrategy() {
            return strategy;
        }

        String getSchemaSummary() {
            return schemaSummary;
        }

        boolean isBound() {
            return status == Status.BOUND;
        }

        boolean isUnbound() {
            return status == Status.UNBOUND;
        }

        boolean isUnknown() {
            return status == Status.UNKNOWN;
        }

        boolean isAvailable() {
            return status != Status.UNKNOWN;
        }
    }

    private interface Accessor {
        String strategy();

        Boolean readBound(Object connection) throws Throwable;
    }

    private static final class FieldAccessor implements Accessor {
        private final Field field;
        private final String strategy;

        FieldAccessor(Field field, String strategy) {
            this.field = field;
            this.strategy = strategy;
        }

        @Override
        public String strategy() {
            return strategy;
        }

        @Override
        public Boolean readBound(Object connection) throws Throwable {
            return field.get(connection) != null;
        }
    }

    private static final class MethodAccessor implements Accessor {
        private final Method method;
        private final String strategy;

        MethodAccessor(Method method, String strategy) {
            this.method = method;
            this.strategy = strategy;
        }

        @Override
        public String strategy() {
            return strategy;
        }

        @Override
        public Boolean readBound(Object connection) throws Throwable {
            Object val = method.invoke(connection);
            if (val instanceof Boolean) {
                return (Boolean) val;
            }
            return null;
        }
    }

    private static final class ClassProbe {
        final List<Accessor> accessors;
        final String schemaSummary;

        ClassProbe(List<Accessor> accessors, String schemaSummary) {
            this.accessors = Collections.unmodifiableList(accessors);
            this.schemaSummary = schemaSummary;
        }
    }

    private static final Map<Class<?>, ClassProbe> CACHE = new ConcurrentHashMap<>();

    private ConnectionBindProbe() {
    }

    /** 探测连接对象的绑定状态，返回三态结果、命中的策略及类成员诊断摘要。 */
    static Result probe(Object connection) {
        if (connection == null) {
            return new Result(Status.UNBOUND, "null", "");
        }
        ClassProbe classProbe = resolve(connection.getClass());
        if (classProbe.accessors.isEmpty()) {
            return new Result(Status.UNKNOWN, "none", classProbe.schemaSummary);
        }

        String primaryStrategy = classProbe.accessors.get(0).strategy();
        boolean anyEvaluated = false;
        for (Accessor accessor : classProbe.accessors) {
            try {
                Boolean bound = accessor.readBound(connection);
                if (bound != null) {
                    anyEvaluated = true;
                    if (bound) {
                        return new Result(Status.BOUND, accessor.strategy(),
                                classProbe.schemaSummary);
                    }
                }
            } catch (Throwable ignored) {
                // 单个探测手段在特定 ROM 上可能因隐藏/权限/调用条件抛异常，继续尝试下一级兜底
            }
        }
        if (anyEvaluated) {
            return new Result(Status.UNBOUND, primaryStrategy, classProbe.schemaSummary);
        }
        return new Result(Status.UNKNOWN, "error", classProbe.schemaSummary);
    }

    /** 判断指定连接类是否至少具备一种可识别的绑定探测手段。 */
    static boolean isClassSupported(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return !resolve(clazz).accessors.isEmpty();
    }

    /** 生成连接类层级中与客户端/绑定状态相关的成员摘要，供诊断快照输出。 */
    static String describeSchema(Class<?> clazz) {
        if (clazz == null) {
            return "";
        }
        return resolve(clazz).schemaSummary;
    }

    private static ClassProbe resolve(Class<?> clazz) {
        ClassProbe cached = CACHE.get(clazz);
        if (cached != null) {
            return cached;
        }
        ClassProbe built = buildClassProbe(clazz);
        ClassProbe existing = CACHE.putIfAbsent(clazz, built);
        return existing != null ? existing : built;
    }

    private static ClassProbe buildClassProbe(Class<?> clazz) {
        List<Accessor> accessors = new ArrayList<>();
        Set<String> seenFields = new LinkedHashSet<>();

        // 1. Android 10~16 标准字段
        addFieldAccessor(clazz, "mServiceInterface", "field:mServiceInterface",
                accessors, seenFields);
        // 2. Android 17 (API 37) 重命名后的字段
        addFieldAccessor(clazz, "mClient", "field:mClient",
                accessors, seenFields);
        // 3. 类型兜底：任意名为非固定但类型为 IAccessibilityServiceClient 的字段（兼容未来改名或 OEM 魔改）
        addClientTypeFallbackFields(clazz, accessors, seenFields);
        // 4. Android 17 Binder 字段
        addFieldAccessor(clazz, "mClientBinder", "field:mClientBinder",
                accessors, seenFields);
        // 5. Android 10~16 Binder 字段
        addFieldAccessor(clazz, "mService", "field:mService",
                accessors, seenFields);
        // 6. 无参状态方法兜底（如 isConnectedLocked()）
        Method isConnected = findNoArgBooleanMethod(clazz, "isConnectedLocked");
        if (isConnected != null) {
            accessors.add(new MethodAccessor(isConnected, "method:isConnectedLocked"));
        }

        String schemaSummary = buildSchemaSummary(clazz);
        return new ClassProbe(accessors, schemaSummary);
    }

    private static void addFieldAccessor(Class<?> clazz, String fieldName, String strategy,
            List<Accessor> out, Set<String> seenFields) {
        Field field = findField(clazz, fieldName);
        if (field != null && seenFields.add(fieldIdentity(field))) {
            out.add(new FieldAccessor(field, strategy));
        }
    }

    private static void addClientTypeFallbackFields(Class<?> clazz, List<Accessor> out,
            Set<String> seenFields) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                if (field == null) {
                    continue;
                }
                String typeName = field.getType().getName();
                if (!A11Y_CLIENT_TYPE_NAME.equals(typeName)
                        && !typeName.endsWith(A11Y_CLIENT_SIMPLE_SUFFIX)
                        && !typeName.endsWith(A11Y_CLIENT_INNER_SUFFIX)) {
                    continue;
                }
                if (!seenFields.add(fieldIdentity(field))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    out.add(new FieldAccessor(field, "type:" + field.getName()));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Method findNoArgBooleanMethod(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                Class<?> ret = m.getReturnType();
                if (ret != boolean.class && ret != Boolean.class) {
                    continue;
                }
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String fieldIdentity(Field field) {
        return field.getDeclaringClass().getName() + "#" + field.getName();
    }

    private static String buildSchemaSummary(Class<?> clazz) {
        List<String> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                fields = null;
            }
            if (fields != null) {
                for (Field f : fields) {
                    if (f == null) {
                        continue;
                    }
                    String name = f.getName();
                    String typeSimple = f.getType().getSimpleName();
                    if (!isInterestingField(name, typeSimple)) {
                        continue;
                    }
                    String entry = name + ":" + typeSimple;
                    if (seen.add(entry)) {
                        items.add(entry);
                        if (items.size() >= MAX_SCHEMA_ITEMS) {
                            return joinSchemaItems(items, true);
                        }
                    }
                }
            }

            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable ignored) {
                methods = null;
            }
            if (methods != null) {
                for (Method m : methods) {
                    if (m == null || m.getParameterCount() != 0) {
                        continue;
                    }
                    Class<?> ret = m.getReturnType();
                    if (ret != boolean.class && ret != Boolean.class) {
                        continue;
                    }
                    String name = m.getName();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!lower.contains("connect") && !lower.contains("bound")) {
                        continue;
                    }
                    String entry = name + "():boolean";
                    if (seen.add(entry)) {
                        items.add(entry);
                        if (items.size() >= MAX_SCHEMA_ITEMS) {
                            return joinSchemaItems(items, true);
                        }
                    }
                }
            }
        }
        return joinSchemaItems(items, false);
    }

    private static boolean isInterestingField(String name, String typeSimple) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerType = typeSimple.toLowerCase(Locale.ROOT);
        return lowerName.contains("client")
                || lowerName.contains("service")
                || lowerName.contains("binder")
                || lowerName.contains("connect")
                || lowerName.contains("bound")
                || lowerType.contains("client")
                || lowerType.contains("binder")
                || lowerType.contains("service")
                || lowerType.contains("interface");
    }

    private static String joinSchemaItems(List<String> items, boolean truncated) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        if (truncated) {
            sb.append(", ...");
        }
        return sb.toString();
    }
}
