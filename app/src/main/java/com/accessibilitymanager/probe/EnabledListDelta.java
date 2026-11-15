package com.accessibilitymanager.probe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 的纯逻辑增删工具。
 *
 * <p>真实场景约束：
 * <ul>
 *   <li>启用列表是全局字符串，模块、用户、系统设置和 OEM 都会改它；</li>
 *   <li>写入时必须按“当前值 + 本次增删”计算，不能拿旧快照整份覆盖，否则会丢掉外部新增的服务；</li>
 *   <li>列表中可能存在无法解析的脏数据，必须原样保留，不能因为模块不认识就删除；</li>
 *   <li>同一服务可能以 short/long 两种形式出现，必须按规范形式去重。</li>
 * </ul>
 */
final class EnabledListDelta {

    private EnabledListDelta() {
    }

    /** 解析列表中的合法组件（去重、保持首次出现顺序）；脏数据会被忽略，不参与匹配。 */
    static List<ComponentKey> parse(String list) {
        List<ComponentKey> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String token : split(list)) {
            ComponentKey key = ComponentKey.parse(token);
            if (key != null && seen.add(key.flatten())) {
                result.add(key);
            }
        }
        return result;
    }

    /** 判断列表是否包含目标组件，自动兼容 short/long 两种写法。 */
    static boolean contains(String list, ComponentKey target) {
        if (target == null) {
            return false;
        }
        for (String token : split(list)) {
            ComponentKey key = ComponentKey.parse(token);
            if (key != null && key.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对当前启用列表应用一次 delta（增/删）。
     *
     * <p>边界语义：
     * <ul>
     *   <li>如果本次增删没有改变任何成员，直接返回原始字符串，避免无意义写入和内容抖动；</li>
     *   <li>删除优先于新增：同一个组件同时出现在 add/remove 时按删除处理，避免修复流程自己打架；</li>
     *   <li>无法解析的脏数据原样保留，避免丢失模块不认识的第三方服务；</li>
     *   <li>新增项追加在末尾，已有项保持原顺序，减少对系统观察器的干扰。</li>
     * </ul>
     */
    static String apply(String current, Collection<ComponentKey> add,
            Collection<ComponentKey> remove) {
        String base = current == null ? "" : current;
        Set<ComponentKey> removeSet = normalize(remove);
        Set<String> currentIds = new LinkedHashSet<>();
        for (String token : split(base)) {
            ComponentKey key = ComponentKey.parse(token);
            currentIds.add(key != null ? key.flatten() : rawId(token));
        }

        boolean changed = false;
        for (ComponentKey key : removeSet) {
            if (currentIds.contains(key.flatten())) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            for (ComponentKey key : normalize(add)) {
                if (!removeSet.contains(key) && !currentIds.contains(key.flatten())) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) {
            return base;
        }

        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String token : split(base)) {
            ComponentKey key = ComponentKey.parse(token);
            if (key != null && removeSet.contains(key)) {
                continue;
            }
            String id = key != null ? key.flatten() : rawId(token);
            if (seen.add(id)) {
                // 保留原有 short/long 写法，避免每次写入都把整份列表规范化，引发无意义的启用列表变更
                result.add(token);
            }
        }
        for (ComponentKey key : normalize(add)) {
            if (key == null || removeSet.contains(key)) {
                continue;
            }
            if (seen.add(key.flatten())) {
                result.add(key.flatten());
            }
        }
        return join(result);
    }

    private static Set<ComponentKey> normalize(Collection<ComponentKey> keys) {
        Set<ComponentKey> result = new LinkedHashSet<>();
        if (keys != null) {
            for (ComponentKey key : keys) {
                if (key != null) {
                    result.add(key);
                }
            }
        }
        return result;
    }

    private static List<String> split(String list) {
        List<String> result = new ArrayList<>();
        if (list == null || list.isEmpty()) {
            return result;
        }
        for (String raw : list.split(":")) {
            String token = raw == null ? "" : raw.trim();
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    private static String rawId(String token) {
        // 脏数据没有组件语义，用前缀隔离，避免与合法组件规范形式意外相等
        return "raw:" + token;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(value);
        }
        return sb.toString();
    }
}
