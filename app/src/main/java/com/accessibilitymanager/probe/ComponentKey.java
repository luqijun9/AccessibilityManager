package com.accessibilitymanager.probe;

/**
 * 无障碍组件键（纯逻辑，无 Android / Xposed 依赖）。
 *
 * <p>解决的真实场景：
 * <ul>
 *   <li>Settings.Secure 里的同一服务可能写成 "pkg/.Service" 或 "pkg/pkg.Service"；</li>
 *   <li>binderDied 连接对象、用户命令、系统补回写入可能混用两种形式；</li>
 *   <li>直接字符串比较会把同一服务当成两个组件，造成重复关开、漏补回或批次成员判断错误。</li>
 * </ul>
 *
 * <p>统一解析为 "pkg/完整类名" 的规范形式，状态机、包级批处理和 Settings delta
 * 全部使用该规范形式，避免同一服务出现多种身份。
 */
final class ComponentKey implements Comparable<ComponentKey> {

    private final String packageName;
    private final String className;
    private final String flat;
    private final int hash;

    private ComponentKey(String packageName, String className) {
        this.packageName = packageName;
        this.className = className;
        this.flat = packageName + "/" + className;
        this.hash = flat.hashCode();
    }

    /**
     * 解析 Settings 中的组件字符串。
     * 与 Android ComponentName.unflattenFromString 的行为保持一致：
     * - 必须以 "pkg/" 开头且类名非空；
     * - 类名以 '.' 开头时补全包名，例如 "com.foo/.Bar" -> "com.foo/com.foo.Bar"；
     * - 不带 '.' 的类名按原样保留，避免把 OEM 的特殊写法误改写。
     *
     * @return 解析失败返回 null，调用方必须容忍脏数据
     */
    static ComponentKey parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        int slash = value.indexOf('/');
        if (slash <= 0 || slash >= value.length() - 1) {
            return null;
        }
        String pkg = value.substring(0, slash).trim();
        String cls = value.substring(slash + 1).trim();
        if (pkg.isEmpty() || cls.isEmpty()) {
            return null;
        }
        if (cls.charAt(0) == '.') {
            cls = pkg + cls;
        }
        return new ComponentKey(pkg, cls);
    }

    /** 由包名和完整类名构造；参数非法时返回 null，保持与 parse 一致的容错语义。 */
    static ComponentKey of(String pkg, String cls) {
        if (pkg == null || cls == null) {
            return null;
        }
        String p = pkg.trim();
        String c = cls.trim();
        if (p.isEmpty() || c.isEmpty()) {
            return null;
        }
        if (c.charAt(0) == '.') {
            c = p + c;
        }
        return new ComponentKey(p, c);
    }

    String getPackageName() {
        return packageName;
    }

    String getClassName() {
        return className;
    }

    /** 规范长形式："pkg/完整类名"。所有内部比较、写入和批次规划都使用该形式。 */
    String flatten() {
        return flat;
    }

    /**
     * 短形式："pkg/.类名"（仅当类名确实以包名开头时）。
     * 仅用于日志/展示，禁止用于状态判断，避免 short/long 混用导致匹配失败。
     */
    String flattenShort() {
        String prefix = packageName + ".";
        if (className.startsWith(prefix)) {
            return packageName + "/." + className.substring(prefix.length());
        }
        return flat;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ComponentKey)) {
            return false;
        }
        ComponentKey other = (ComponentKey) o;
        return flat.equals(other.flat);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public int compareTo(ComponentKey other) {
        return other == null ? 1 : flat.compareTo(other.flat);
    }

    @Override
    public String toString() {
        return flattenShort();
    }
}
