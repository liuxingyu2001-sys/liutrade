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
 *   <li>{@code library_id: "ce:ns:id"} —— 先经 LiuLib 完整物品规格解析（{@code base64:}/{@code nbt:}/
 *       {@code bytes:}/{@code item:}/{@code lib:}/{@code ce:}），失败再走 SoulSpace {@code resolveItem}
 *       （CE/MM/IE/原版材质）；</li>
 *   <li>{@code material: PAPER} + {@code components: {...}} —— 用组件构建自定义物品（见 {@link ItemBuilder}）；
 *       该键同样接受 LiuLib 完整物品规格（{@code ce:}/{@code base64:}/{@code lib:}…）、
 *       数量写法（{@code DIAMOND*3} / {@code DIAMOND:3} / {@code minecraft:diamond:3}）
 *       以及 SoulSpace 通配（{@code SHULKER_*} / {@code *SWORD*}）；</li>
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
    /**
     * {@code material} 是否为 LiuLib 完整物品规格（{@code ce:}/{@code mm:}/{@code base64:}/{@code lib:}…）。
     * 这类值没有原版 {@link Material} 可匹配，只能按样例 {@code isSimilar} 精确匹配。
     */
    final boolean opaqueMaterial;
    /** 仅用于界面显示的物品规格（可为 null）；匹配仍走 {@link #rule}/{@link #material}。 */
    final ItemSpec display;
    /** 仅用于界面显示的名字（可为 null）。 */
    final String displayName;
    /** 样品解析缓存（bytes 反序列化/CE 解析在渲染热路径上只做一次）。 */
    private volatile ItemStack sampleCache;

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
        this.opaqueMaterial = isOpaqueSpec(material);
    }

    static ItemSpec parse(Object raw) {
        if (raw instanceof String s && !s.isBlank()) {
            String value = s.trim();
            int amount = 1;
            String[] split = splitCount(value);
            if (split != null) {
                value = split[0];
                amount = Integer.parseInt(split[1]);
            }
            int colon = value.indexOf(':');
            if (colon > 0) {
                String namespace = value.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
                if (!namespace.equals("minecraft") && !namespace.equals("mc") && !namespace.equals("vanilla")) {
                    // 字符串简写也接受物品库 id（ce:/mm:/ie:...），与 library_id 等价。
                    return new ItemSpec(value, null, null, Map.of(), Map.of(), amount, null, null);
                }
            }
            String mat = Materials.canonical(value);
            if (mat == null) {
                mat = value;
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("material", mat);
            return new ItemSpec(null, null, mat, Map.of(), rule, amount, null, null);
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        int amount = 1;
        boolean amountExplicit = map.containsKey("amount");
        Object amountRaw = map.get("amount");
        if (amountRaw instanceof Number n) {
            amount = n.intValue();
        } else if (amountRaw instanceof String text) {
            try {
                amount = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                // 保持默认 1；非法数量不应让整条交易解析失败。
            }
        }
        String libraryId = blankToNull(map.get("library_id"));
        if (libraryId != null) {
            // library_id 也接受 DIAMOND*3 / minecraft:diamond:3：拆出数量后交给 LiuLib 解析基础物品
            String[] split = splitCount(libraryId);
            if (split != null) {
                libraryId = split[0];
                if (!amountExplicit) {
                    amount = Integer.parseInt(split[1]);
                }
            }
        }
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
            if (key.equals("material")) {
                String text = String.valueOf(entry.getValue()).trim();
                String[] split = splitCount(text);
                if (split != null) {
                    text = split[0];
                    if (!amountExplicit) {
                        // 兼容 LiuLib 数量写法：DIAMOND*3 / DIAMOND:3 / minecraft:diamond:3
                        amount = Integer.parseInt(split[1]);
                    }
                }
                String canonical = Materials.canonical(text);
                // 归一为枚举名（DIAMOND）；非原版命名空间（ce:/mm:/lib:…）保留原文，按 LiuLib 规格精确匹配
                material = canonical != null ? canonical : text;
                rule.put(key, material);
                continue;
            }
            rule.put(key, entry.getValue());
        }
        if (libraryId == null && bytes == null && material == null && rule.isEmpty()) {
            return null;
        }
        return new ItemSpec(libraryId, bytes, material, components, rule, amount, display, displayName);
    }

    /** 生成匹配/构建用样品（数量 1）；无法解析时返回 null。返回副本，调用方可安全修改数量。 */
    ItemStack sample(SoulSpaceHook ss) {
        ItemStack cached = sampleCache;
        if (cached != null) {
            return cached.clone();
        }
        ItemStack built = buildSample(ss);
        if (built != null && !built.getType().isAir()) {
            sampleCache = built.clone();
            return built;
        }
        return built;
    }

    private ItemStack buildSample(SoulSpaceHook ss) {
        if (bytes != null) {
            // 完整物品优先交给 LiuLib（Paper 二进制 + 旧版对象流都能解），失败再走本地 serializeAsBytes
            ItemStack viaLib = LiuLibItems.buildOrNull("base64:" + bytes);
            if (viaLib != null) {
                return viaLib;
            }
            ItemStack fromBytes = ItemBuilder.fromBytes(bytes);
            if (fromBytes != null) {
                return fromBytes;
            }
        }
        if (libraryId != null) {
            // LiuLib 完整规格（base64:/nbt:/lib:/ce:…）优先，SoulSpace 物品库解析作为兜底
            ItemStack viaLib = LiuLibItems.buildOrNull(libraryId);
            if (viaLib != null) {
                return viaLib;
            }
            ItemStack resolved = ss == null ? null : ss.resolveItem(libraryId).orElse(null);
            if (resolved != null) {
                return resolved;
            }
            // 旧版 SoulSpace 或裸材质 id（diamond/minecraft:diamond）本地兑底
            Material type = Materials.resolve(libraryId);
            return type == null ? null : new ItemStack(type);
        }
        if (material != null) {
            // 非原版命名空间（ce:/mm:/ia:…）也允许 SoulSpace 物品库兜底，兼容旧配置。
            if (opaqueMaterial && components.isEmpty() && ss != null) {
                ItemStack viaSoulSpace = ss.resolveItem(material).orElse(null);
                if (viaSoulSpace != null) {
                    return viaSoulSpace;
                }
            }
            // ItemBuilder 内部同样先试 LiuLib，材质/组件逻辑保持原样
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
        boolean exact = libraryId != null || bytes != null
                || (material != null && (!components.isEmpty() || opaqueMaterial));
        if (exact) {
            ItemStack sample = sample(ss);
            if (sample == null || !item.isSimilar(sample)) {
                return false;
            }
            // 完整规格之外还写了 pdc/name 等规则字段时，必须同时满足规则；
            // 只按样例匹配会让普通同款物品绕过额外规则。
            if (requiresRules()) {
                return ss != null && ss.canMatchRules() && ss.matchesRule(rule, item);
            }
            return true;
        }
        if (ss != null && ss.canMatchRules() && !rule.isEmpty()) {
            return ss.matchesRule(rule, item);
        }
        // 需要规则字段（pdc/name/lore/custom_model_data/not_*）但 SoulSpace 不可用时，
        // 必须失败关闭：否则只按材质匹配会让任意同材质物品绕过规则（如普通纸冒充票券）。
        if (requiresRules()) {
            return false;
        }
        if (material != null) {
            Material type = Materials.resolve(material);
            return type != null && item.getType() == type;
        }
        return false;
    }

    /** 是否依赖 SoulSpace 规则匹配（除 material 外还有其它规则字段）。 */
    boolean requiresRules() {
        for (String key : rule.keySet()) {
            if (!key.equals("material")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 能否用原生村民界面表示：需要能唯一采样（{@code library_id}/{@code bytes}/
     * {@code material + components}/{@code material} 写成 LiuLib 完整规格），或规则只有 {@code material}。
     * 只有 {@code pdc}/{@code name} 等无法采样的字段时，原生界面无法按规则匹配（需自定义界面）。
     */
    boolean nativeRepresentable() {
        // 原生村民界面只能按物品样例匹配：额外规则（pdc/name/...）和通配材质都无法表示。
        if (requiresRules() || wildcardMaterial()) {
            return false;
        }
        if (libraryId != null || bytes != null || !components.isEmpty()
                || (material != null && opaqueMaterial)) {
            return true;
        }
        return rule.size() == 1 && rule.containsKey("material");
    }

    /** material 是否是 SoulSpace 通配写法（{@code SHULKER_*} / {@code *SWORD*}）。 */
    boolean wildcardMaterial() {
        return material != null && (material.indexOf('*') >= 0 || material.indexOf('?') >= 0);
    }

    /**
     * {@code material} 是否 LiuLib 完整物品规格 / 非原版命名空间（{@code base64:}/{@code nbt:}/
     * {@code bytes:}/{@code item:}/{@code lib:}/{@code ce:}…）。这类写法没有原版材质可匹配，
     * 只能按样例 {@code isSimilar} 精确匹配；通配写法（{@code SHULKER_*}）不在此列，仍走 SoulSpace 规则。
     */
    static boolean isOpaqueSpec(String raw) {
        if (raw == null) {
            return false;
        }
        String text = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty()) {
            return false;
        }
        if (text.startsWith("base64:") || text.startsWith("nbt:") || text.startsWith("bytes:")
                || text.startsWith("item:") || text.startsWith("lib:")) {
            return true;
        }
        int colon = text.indexOf(':');
        if (colon > 0) {
            String namespace = text.substring(0, colon);
            return !namespace.equals("minecraft") && !namespace.equals("mc") && !namespace.equals("vanilla");
        }
        return false;
    }

    /**
     * 拆分材质数量写法（{@code DIAMOND*3} / {@code DIAMOND:3} / {@code minecraft:diamond:3}），
     * 返回 {@code [材质部分, 数量]}；不是数量写法时返回 null。
     */
    private static String[] splitCount(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        int star = text.lastIndexOf('*');
        if (star > 0 && star == text.indexOf('*')) {
            String digits = text.substring(star + 1).trim();
            String base = text.substring(0, star).trim();
            if (!base.isEmpty() && positiveInt(digits)) {
                return new String[]{base, digits};
            }
        }
        int colon = text.lastIndexOf(':');
        if (colon > 0) {
            String digits = text.substring(colon + 1).trim();
            String base = text.substring(0, colon).trim();
            // 只有「:数字」且前半部分能解析成原版材质时才算数量，避免误伤 ce:ns:123 这类物品库 id。
            if (Materials.resolve(base) != null && positiveInt(digits)) {
                return new String[]{base, digits};
            }
        }
        return null;
    }

    private static boolean positiveInt(String text) {
        if (text.isEmpty()) {
            return false;
        }
        try {
            return Integer.parseInt(text) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
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
            // 完整物品规格是一大段 Base64/NBT 文本或命名空间 id，不能直接当名字显示
            return opaqueMaterial ? "自定义物品" : material;
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
