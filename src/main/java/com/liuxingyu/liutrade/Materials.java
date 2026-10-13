package com.liuxingyu.liutrade;

import org.bukkit.Material;

import java.util.Locale;

/**
 * 原版材质解析：接受 {@code DIAMOND} / {@code diamond} / {@code minecraft:diamond} /
 * {@code mc:DIAMOND} / {@code vanilla:diamond} 等常见英文 id 写法；其它命名空间返回 null。
 */
final class Materials {

    private Materials() {
    }

    /**
     * 纯字符串归一（不依赖服务端）：去掉 {@code minecraft:}/{@code mc:}/{@code vanilla:}
     * 前缀并转大写；明显不是原版材质的（其它命名空间）返回 null。
     */
    static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            return null;
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            String namespace = name.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!namespace.equals("minecraft") && !namespace.equals("mc") && !namespace.equals("vanilla")) {
                return null;
            }
            name = name.substring(colon + 1);
        }
        if (name.isEmpty()) {
            return null;
        }
        return name.toUpperCase(Locale.ROOT);
    }

    /** 解析为 Material（需要服务端）；非法或无法解析返回 null。 */
    static Material resolve(String raw) {
        String name = canonical(raw);
        if (name == null || name.indexOf(' ') >= 0 || name.indexOf('*') >= 0) {
            return null;
        }
        try {
            Material material = Material.matchMaterial(name);
            return material == null || material.isAir() ? null : material;
        } catch (Throwable t) {
            return null;
        }
    }
}
