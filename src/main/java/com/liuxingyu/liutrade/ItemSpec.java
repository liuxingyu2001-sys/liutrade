package com.liuxingyu.liutrade;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条物品规格：交易输入的需求或产出。
 *
 * <p>两种写法（可混用规则字段）：
 * <ul>
 *   <li>{@code library_id: "ce:ns:id"} —— 经 SoulSpace {@code resolveItem} 生成，匹配用 {@link ItemStack#isSimilar};</li>
 *   <li>规则字段（{@code material}/{@code pdc}/{@code name}/{@code lore}/{@code custom_model_data}
 *       以及 {@code not_*}）—— 经 SoulSpace {@code matchesRule} 匹配，无需 SoulSpace 时退化为仅比材质。</li>
 * </ul>
 * {@code amount} 表示一笔所需/产出的数量（默认 1）。
 */
final class ItemSpec {

    final String libraryId;
    final Map<String, Object> rule;
    final int amount;

    private ItemSpec(String libraryId, Map<String, Object> rule, int amount) {
        this.libraryId = libraryId;
        this.rule = rule;
        this.amount = Math.max(1, amount);
    }

    static ItemSpec parse(Object raw) {
        if (raw instanceof String s && !s.isBlank()) {
            return new ItemSpec(null, Map.of("material", s.trim()), 1);
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        int amount = 1;
        Object amountRaw = map.get("amount");
        if (amountRaw instanceof Number n) {
            amount = n.intValue();
        }
        Object lib = map.get("library_id");
        Map<String, Object> rule = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (key.equals("amount") || key.equals("library_id")) {
                continue;
            }
            rule.put(key, e.getValue());
        }
        if (lib != null && !String.valueOf(lib).isBlank()) {
            return new ItemSpec(String.valueOf(lib).trim(), null, amount);
        }
        if (rule.isEmpty()) {
            return null;
        }
        return new ItemSpec(null, rule, amount);
    }

    /** 生成展示用样品（数量 1）；无法解析时返回 null。 */
    ItemStack sample(SoulSpaceHook ss) {
        if (libraryId != null) {
            return ss.resolveItem(libraryId).map(ItemStack::clone).orElse(null);
        }
        Object material = rule.get("material");
        if (material != null) {
            Material m = Material.matchMaterial(String.valueOf(material));
            if (m != null && !m.isAir()) {
                return new ItemStack(m);
            }
        }
        return null;
    }

    boolean matches(ItemStack item, SoulSpaceHook ss) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (libraryId != null) {
            ItemStack sample = ss.resolveItem(libraryId).orElse(null);
            return sample != null && item.isSimilar(sample);
        }
        if (ss.available()) {
            return ss.matchesRule(rule, item);
        }
        Object material = rule.get("material");
        return material != null && item.getType().name().equalsIgnoreCase(String.valueOf(material));
    }

    String label() {
        if (libraryId != null) {
            return libraryId;
        }
        Object material = rule.get("material");
        if (material != null) {
            return String.valueOf(material);
        }
        return String.join(",", rule.keySet());
    }
}
