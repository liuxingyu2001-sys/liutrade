package com.liuxingyu.liutrade;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条物品规格：交易输入的需求或产出。
 *
 * <p>写法：
 * <ul>
 *   <li>{@code library_id: "ce:ns:id"} —— 经 SoulSpace {@code resolveItem} 生成（CE/MM/IE/原版材质）；</li>
 *   <li>{@code material: PAPER} + {@code components: {...}} —— 用组件构建自定义物品（见 {@link ItemBuilder}）；</li>
 *   <li>{@code bytes: "<base64>"} —— {@code ItemStack.serializeAsBytes()} 的全保真通道（任意物品）；</li>
 *   <li>规则字段（{@code pdc}/{@code name}/{@code lore}/{@code custom_model_data} 及 {@code not_*}）
 *       —— 经 SoulSpace {@code matchesRule} 匹配；</li>
 *   <li>{@code display: {...}} —— 只用于界面显示的物品（规则输入想显示成 CE 物品时用）；
 *       {@code display_name: "钻石"}/{@code label: "钻石"} —— 只用于界面显示的名字。</li>
 * </ul>
 * {@code amount} 表示一笔所需/产出的数量（默认 1）。
 */
final class ItemSpec {

    final String libraryId;
    final String bytes;
    final String material;
    final Map<String, Object> components;
    final Map<String, Object> rule;
    final int amount;
    /** 仅用于界面显示的物品规格（可为 null）；匹配仍走 {@link #rule}/{@link #material}。 */
    final ItemSpec display;
    /** 仅用于界面显示的名字（可为 null）。 */
    final String displayName;

    private ItemSpec(String libraryId, String bytes, String material,
                     Map<String, Object> components, Map<String, Object> rule, int amount,
                     ItemSpec display, String displayName) {
        this.libraryId = libraryId;
        this.bytes = bytes;
        this.material = material;
        this.components = components;
        this.rule = rule;
        this.amount = Math.max(1, amount);
        this.display = display;
        this.displayName = displayName;
    }

    static ItemSpec parse(Object raw) {
        if (raw instanceof String s && !s.isBlank()) {
            String value = s.trim();
            String mat = Materials.canonical(value);
            if (mat == null) {
                mat = value;
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("material", mat);
            return new ItemSpec(null, null, mat, Map.of(), rule, 1, null, null);
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        int amount = 1;
        Object amountRaw = map.get("amount");
        if (amountRaw instanceof Number n) {
            amount = n.intValue();
        }
        String libraryId = blankToNull(map.get("library_id"));
        String bytes = blankToNull(map.get("bytes"));
        if (bytes == null) {
            bytes = blankToNull(map.get("base64"));
        }
        Map<String, Object> components = ItemBuilder.asMap(map.get("components"));
        if (components == null) {
            components = Map.of();
        }
        ItemSpec display = parse(map.get("display"));
        String displayName = blankToNull(map.get("display_name"));
        if (displayName == null) {
            displayName = blankToNull(map.get("label"));
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        String material = null;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.equals("amount") || key.equals("library_id") || key.equals("bytes")
                    || key.equals("base64") || key.equals("components")
                    || key.equals("display") || key.equals("display_name") || key.equals("label")) {
                continue;
            }
            rule.put(key, entry.getValue());
            if (key.equals("material")) {
                String canonical = Materials.canonical(String.valueOf(entry.getValue()));
                if (canonical != null) {
                    // 归一为枚举名（DIAMOND），保证 SoulSpace 规则匹配与旧版兼容
                    material = canonical;
                    rule.put(key, canonical);
                } else {
                    material = String.valueOf(entry.getValue()).trim();
                }
            }
        }
        if (libraryId == null && bytes == null && material == null && rule.isEmpty()) {
            return null;
        }
        return new ItemSpec(libraryId, bytes, material, components, rule, amount, display, displayName);
    }

    /** 生成匹配/构建用样品（数量 1）；无法解析时返回 null。 */
    ItemStack sample(SoulSpaceHook ss) {
        if (bytes != null) {
            ItemStack fromBytes = ItemBuilder.fromBytes(bytes);
            if (fromBytes != null) {
                return fromBytes;
            }
        }
        if (libraryId != null) {
            ItemStack resolved = ss.resolveItem(libraryId).orElse(null);
            if (resolved != null) {
                return resolved;
            }
            // 旧版 SoulSpace 或裸材质 id（diamond/minecraft:diamond）本地兑底
            Material type = Materials.resolve(libraryId);
            return type == null ? null : new ItemStack(type);
        }
        if (material != null) {
            return ItemBuilder.build(material, components);
        }
        return null;
    }

    /** 生成界面显示用样品：优先 {@code display}，否则回退 {@link #sample}。 */
    ItemStack iconSample(SoulSpaceHook ss) {
        ItemStack custom = display == null ? null : display.sample(ss);
        return custom != null ? custom : sample(ss);
    }

    boolean matches(ItemStack item, SoulSpaceHook ss) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        boolean exact = libraryId != null || bytes != null || (material != null && !components.isEmpty());
        if (exact) {
            ItemStack sample = sample(ss);
            return sample != null && item.isSimilar(sample);
        }
        if (ss.canMatchRules() && !rule.isEmpty()) {
            return ss.matchesRule(rule, item);
        }
        if (material != null) {
            Material type = Materials.resolve(material);
            return type != null && item.getType() == type;
        }
        return false;
    }

    /**
     * 能否用原生村民界面表示：需要能唯一采样（{@code library_id}/{@code bytes}/
     * {@code material + components}），或规则只有 {@code material}。
     * 只有 {@code pdc}/{@code name} 等无法采样的字段时，原生界面无法按规则匹配（需自定义界面）。
     */
    boolean nativeRepresentable() {
        if (libraryId != null || bytes != null || !components.isEmpty()) {
            return true;
        }
        return rule.size() == 1 && rule.containsKey("material");
    }

    /** 界面显示名：配置的 {@code display_name}/{@code label} 优先；否则返回 null（由界面按样品取名）。 */
    String configuredName() {
        return displayName;
    }

    /** 无法取到样品/名字时的兜底文案。 */
    String label() {
        if (libraryId != null) {
            return libraryId;
        }
        if (bytes != null) {
            return "自定义物品";
        }
        if (material != null) {
            return material;
        }
        return String.join(",", rule.keySet());
    }

    private static String blankToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
