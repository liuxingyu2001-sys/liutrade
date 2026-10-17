package com.liuxingyu.liutrade;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 用 YAML 的 {@code material} + {@code components} 构建物品。
 *
 * <p>组件键可带或不带 {@code minecraft:} 前缀，支持：
 * {@code custom_name}/{@code item_name}、{@code lore}、{@code custom_model_data}、
 * {@code enchantments}/{@code stored_enchantments}、{@code unbreakable}、{@code rarity}、
 * {@code max_stack_size}、{@code fire_resistant}、{@code hide_tooltip}、
 * {@code enchantment_glint_override}、{@code damage}、{@code dyed_color}，
 * 以及 {@code custom_data}/{@code pdc}/{@code nbt}（嵌套 map 或 SNBT 字符串 → Bukkit PDC，
 * 可被 SoulSpace 的 {@code pdc} 规则匹配）。任意/无法表示的组件请用 {@code bytes:}（见下）。
 */
final class ItemBuilder {

    private static final Logger LOGGER = Logger.getLogger("LiuTrade");
    private static final java.util.Set<String> WARNED_COMPONENTS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ItemBuilder() {
    }

    /** 由材质 + 组件构建物品；材质非法返回 null（接受 DIAMOND/diamond/minecraft:diamond）。 */
    static ItemStack build(String material, Map<String, Object> components) {
        Material type = Materials.resolve(material);
        if (type == null) {
            return null;
        }
        ItemStack item = new ItemStack(type);
        apply(item, components);
        return item;
    }

    /** 由 {@code ItemStack.serializeAsBytes()} 的 Base64 还原任意物品（全保真通道）。 */
    static ItemStack fromBytes(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.trim());
            return ItemStack.deserializeBytes(bytes);
        } catch (Throwable t) {
            return null;
        }
    }

    static void apply(ItemStack item, Map<String, Object> components) {
        if (components == null || components.isEmpty()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : components.entrySet()) {
            applyOne(meta, normalize(entry.getKey()), entry.getValue());
        }
        item.setItemMeta(meta);
    }

    @SuppressWarnings("deprecation")
    private static void applyOne(ItemMeta meta, String key, Object value) {
        switch (key) {
            case "custom_name", "name" -> meta.setDisplayName(Msg.color(str(value)));
            case "item_name" -> meta.setItemName(Msg.color(str(value)));
            case "lore" -> meta.setLore(Msg.color(strList(value)));
            case "custom_model_data" -> meta.setCustomModelData(intValue(value, 0));
            case "unbreakable" -> meta.setUnbreakable(bool(value));
            case "rarity" -> meta.setRarity(rarity(value));
            case "max_stack_size" -> meta.setMaxStackSize(intValue(value, 64));
            case "fire_resistant" -> meta.setFireResistant(bool(value));
            case "hide_tooltip" -> meta.setHideTooltip(bool(value));
            case "enchantment_glint_override" -> meta.setEnchantmentGlintOverride(bool(value));
            case "damage" -> applyDamage(meta, value);
            case "dyed_color", "color" -> applyColor(meta, value);
            case "enchantments" -> applyEnchantments(meta, value, false);
            case "stored_enchantments" -> applyEnchantments(meta, value, true);
            case "custom_data", "pdc", "nbt" -> applyCustomData(meta, value);
            default -> {
                if (WARNED_COMPONENTS.add(key)) {
                    LOGGER.warning("未知物品组件：" + key + "（已忽略；任意组件请用 bytes:）");
                }
            }
        }
    }

    private static void applyDamage(ItemMeta meta, Object value) {
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) {
            damageable.setDamage(intValue(value, 0));
        }
    }

    private static void applyColor(ItemMeta meta, Object value) {
        if (!(meta instanceof LeatherArmorMeta leather)) {
            return;
        }
        Color color = color(value);
        if (color != null) {
            leather.setColor(color);
        }
    }

    private static Color color(Object value) {
        if (value instanceof Number n) {
            return Color.fromRGB(n.intValue() & 0xFFFFFF);
        }
        String s = str(value).trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        try {
            return Color.fromRGB(Integer.parseInt(s, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static void applyEnchantments(ItemMeta meta, Object value, boolean stored) {
        Map<String, Object> map = asMap(value);
        if (map == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Enchantment enchantment = enchantment(entry.getKey());
            if (enchantment == null) {
                continue;
            }
            int level = Math.max(1, intValue(entry.getValue(), 1));
            if (stored && meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storage) {
                storage.addStoredEnchant(enchantment, level, true);
            } else {
                meta.addEnchant(enchantment, level, true);
            }
        }
    }

    private static Enchantment enchantment(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        Enchantment enchantment = key == null ? null : Registry.ENCHANTMENT.get(key);
        return enchantment != null ? enchantment : Enchantment.getByName(name.toUpperCase(Locale.ROOT));
    }

    private static void applyCustomData(ItemMeta meta, Object value) {
        Map<String, Object> map = customDataMap(value);
        if (map == null || map.isEmpty()) {
            return;
        }
        try {
            PersistentDataContainer container = meta.getPersistentDataContainer();
            write(container, map);
        } catch (Throwable t) {
            // 键名非法等异常不应把整个界面渲染/点击事件拖下水。
            LOGGER.warning("写入 custom_data 失败（已忽略）：" + t);
        }
    }

    /**
     * 解析 {@code custom_data}：支持嵌套 map / SNBT 字符串，并兼容 CE/其它插件常见的
     * {@code PublicBukkitValues} 外层包裹。
     *
     * <p>Bukkit 的 PDC 本身就会自动包一层 {@code PublicBukkitValues}；若配置里又显式写了这层，
     * 老实现会把它当成普通键、产出一个多余的 {@code liutrade:publicbukkitvalues}，
     * 真正的 PDC 键被多嵌一层而导致规则匹配失效。这里统一解开该层。
     */
    static Map<String, Object> customDataMap(Object value) {
        Map<String, Object> map = asMap(value);
        if (map == null) {
            map = Snbt.parseCompound(str(value));
        }
        if (map == null || map.isEmpty()) {
            return map;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("PublicBukkitValues")) {
                Map<String, Object> inner = asMap(entry.getValue());
                if (inner == null) {
                    inner = Snbt.parseCompound(str(entry.getValue()));
                }
                if (inner != null) {
                    out.putAll(inner);
                    continue;
                }
            }
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static void write(PersistentDataContainer container, Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            NamespacedKey key = key(entry.getKey());
            if (key == null) {
                continue;
            }
            Object value = entry.getValue();
            Map<String, Object> nested = asMap(value);
            if (nested != null) {
                PersistentDataContainer child = container.getAdapterContext().newPersistentDataContainer();
                write(child, nested);
                container.set(key, PersistentDataType.TAG_CONTAINER, child);
            } else if (value instanceof Boolean b) {
                container.set(key, PersistentDataType.BOOLEAN, b);
            } else if (value instanceof Integer i) {
                container.set(key, PersistentDataType.INTEGER, i);
            } else if (value instanceof Long l) {
                container.set(key, PersistentDataType.LONG, l);
            } else if (value instanceof Double d) {
                container.set(key, PersistentDataType.DOUBLE, d);
            } else if (value instanceof Float f) {
                container.set(key, PersistentDataType.FLOAT, f);
            } else if (value instanceof Byte b) {
                container.set(key, PersistentDataType.BYTE, b);
            } else if (value instanceof Short s) {
                container.set(key, PersistentDataType.SHORT, s);
            } else if (value != null) {
                container.set(key, PersistentDataType.STRING, String.valueOf(value));
            }
        }
    }

    /** 键名：支持 {@code namespace:key}，否则用默认命名空间 {@code liutrade}。 */
    private static NamespacedKey key(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.contains(":")) {
                return NamespacedKey.fromString(text.toLowerCase(Locale.ROOT));
            }
            return new NamespacedKey("liutrade", text.toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            LOGGER.warning("非法的 custom_data 键名：" + raw + "（已跳过）");
            return null;
        }
    }

    private static String normalize(String key) {
        String text = key.trim().toLowerCase(Locale.ROOT);
        return text.startsWith("minecraft:") ? text.substring("minecraft:".length()) : text;
    }

    static Map<String, Object> asMap(Object value) {
        if (value instanceof org.bukkit.configuration.ConfigurationSection section) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                out.put(key, section.get(key));
            }
            return out;
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> strList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static ItemRarity rarity(Object value) {
        try {
            return ItemRarity.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ItemRarity.COMMON;
        }
    }
}
