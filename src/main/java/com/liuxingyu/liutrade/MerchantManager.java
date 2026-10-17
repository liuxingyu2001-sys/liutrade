package com.liuxingyu.liutrade;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 扫描 merchants/ 目录（递归、相对路径排序、忽略 {@code _} 前缀模板）并解析商店。 */
final class MerchantManager {

    private final Plugin plugin;
    /** 自动重载在全局线程整体替换，其他线程读；volatile + 不可变 Map 保证安全发布。 */
    private volatile Map<String, Merchant> merchants = Collections.emptyMap();

    MerchantManager(Plugin plugin) {
        this.plugin = plugin;
    }

    List<Merchant> all() {
        return new ArrayList<>(merchants.values());
    }

    Merchant get(String id) {
        return id == null ? null : merchants.get(id);
    }

    /** 按 Citizens NPC id 或名字查找绑定的商店。 */
    Merchant byNpc(String id, String name) {
        for (Merchant merchant : merchants.values()) {
            if (merchant.npc == null || merchant.npc.isBlank()) {
                continue;
            }
            if (merchant.npc.equals(id) || (name != null && merchant.npc.equalsIgnoreCase(name))) {
                return merchant;
            }
        }
        return null;
    }

    int size() {
        return merchants.size();
    }

    void load(File root) {
        Map<String, Merchant> parsed = new LinkedHashMap<>();
        List<File> files = new ArrayList<>();
        collect(root, files);
        files.sort((a, b) -> relativeId(root, a).compareTo(relativeId(root, b)));
        for (File file : files) {
            if (file.getName().startsWith("_")) {
                continue;
            }
            String id = relativeId(root, file);
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
                Merchant merchant = parse(cfg, id);
                if (merchant != null) {
                    parsed.put(merchant.id, merchant);
                }
            } catch (Exception e) {
                warn("解析商店 " + id + " 失败：" + e);
            }
        }
        this.merchants = Collections.unmodifiableMap(parsed);
        if (plugin != null) {
            plugin.getLogger().info("已加载 " + parsed.size() + " 个交易商店。");
        }
    }

    Merchant parse(YamlConfiguration cfg, String id) {
        ConfigurationSection tradeSection = cfg.getConfigurationSection("trades");
        List<Trade> trades = new ArrayList<>();
        if (tradeSection != null) {
            int index = 0;
            for (String key : tradeSection.getKeys(false)) {
                if (!(tradeSection.get(key) instanceof ConfigurationSection section)) {
                    warn("交易 " + id + "#" + key + " 格式不对（应为一个映射），已跳过。");
                    index++;
                    continue;
                }
                Trade trade = parseTrade(section, id + "#" + key + "#" + index, key);
                if (trade != null) {
                    trades.add(trade);
                }
                index++;
            }
        }
        if (trades.isEmpty()) {
            warn("商店 " + id + " 没有任何有效交易，已跳过。");
            return null;
        }
        Merchant merchant = new Merchant(id);
        merchant.name = cfg.getString("name", id);
        merchant.title = cfg.getString("title", merchant.name);
        merchant.npc = orEmpty(cfg.getString("npc", ""));
        merchant.permission = orEmpty(cfg.getString("permission", ""));
        merchant.icon = spec(cfg.get("icon"));
        merchant.trades = Collections.unmodifiableList(trades);
        return merchant;
    }

    private Trade parseTrade(ConfigurationSection section, String id, String key) {
        List<ItemSpec> inputs = new ArrayList<>();
        List<ItemSpec> outputs = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        for (Object raw : list(section.get("inputs"), section.get("input"))) {
            ItemSpec spec = spec(raw);
            if (spec != null) {
                inputs.add(spec);
            }
        }
        for (Object raw : list(section.get("outputs"), section.get("output"))) {
            Map<String, Object> map = ItemBuilder.asMap(raw);
            if (map != null && (map.containsKey("commands") || map.containsKey("command"))) {
                // 产出条目可以是「指令产出」：commands: [...]（也可与物品字段共存）。
                Object rawCommands = map.containsKey("commands") ? map.get("commands") : map.get("command");
                commands.addAll(stringList(rawCommands));
                Map<String, Object> itemMap = new LinkedHashMap<>(map);
                itemMap.remove("commands");
                itemMap.remove("command");
                ItemSpec spec = spec(itemMap);
                if (spec != null) {
                    outputs.add(spec);
                }
            } else {
                ItemSpec spec = spec(raw);
                if (spec != null) {
                    outputs.add(spec);
                }
            }
        }
        // 也允许把 commands 写在交易层级
        commands.addAll(stringList(section.get("commands")));
        if (inputs.isEmpty() || inputs.size() > Trade.MAX_INPUTS) {
            warn("交易 " + id + " 的 inputs 必须为 1~" + Trade.MAX_INPUTS + " 项，已跳过。");
            return null;
        }
        for (ItemSpec input : inputs) {
            // 输入全部放在一个槽位，超过原版单槽上限（64）永远无法凑齐，提前提醒管理员。
            if (input.amount > 64) {
                warn("交易 " + id + " 的输入数量 " + input.amount
                        + " 超过单槽上限 64，将永远无法完成，请拆成多笔或减小数量。");
            }
        }
        boolean hasItem = !outputs.isEmpty();
        boolean hasCommand = !commands.isEmpty();
        boolean convert = booleanValue(section.get("convert"), !hasItem && !hasCommand);
        if (convert && inputs.size() > 1) {
            warn("交易 " + id + " 使用 convert 时必须只有 1 个输入，已跳过。");
            return null;
        }
        if (!convert && !hasItem && !hasCommand) {
            warn("交易 " + id + " 既没有 outputs/commands 也没有 convert: true，已跳过。");
            return null;
        }
        Trade trade = new Trade(id);
        trade.key = key;
        trade.inputs = Collections.unmodifiableList(inputs);
        trade.outputs = outputs.isEmpty() ? null : Collections.unmodifiableList(outputs);
        trade.commands = Collections.unmodifiableList(commands);
        trade.convert = convert;
        trade.description = stringList(section.get("description"));
        trade.display = spec(section.get("display"));
        Object displayName = section.get("display_name");
        if (displayName == null) {
            displayName = section.get("label");
        }
        trade.displayName = displayName != null && !String.valueOf(displayName).isBlank()
                ? String.valueOf(displayName) : null;
        Object name = section.get("name");
        trade.name = name != null ? String.valueOf(name) : inputs.get(0).label();
        Object permission = section.get("permission");
        trade.permission = permission != null ? String.valueOf(permission) : "";
        return trade;
    }

    /** 把 YAML 里的一个映射（MemorySection 或 Map）或字符串简写转成 ItemSpec。 */
    private static ItemSpec spec(Object raw) {
        if (raw instanceof String s) {
            return ItemSpec.parse(s);
        }
        if (raw instanceof ConfigurationSection cs) {
            return ItemSpec.parse(new LinkedHashMap<>(cs.getValues(false)));
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return ItemSpec.parse(out);
        }
        return null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 把字符串或字符串列表归一为不可变列表（用于 description 等）。 */
    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> l) {
            for (Object o : l) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
        return Collections.unmodifiableList(out);
    }

    private void warn(String message) {
        if (plugin != null) {
            plugin.getLogger().warning(message);
        }
    }

    private static List<Object> list(Object first, Object second) {
        List<Object> out = new ArrayList<>();
        addAll(out, first);
        addAll(out, second);
        return out;
    }

    private static void addAll(List<Object> out, Object value) {
        if (value instanceof List<?> l) {
            out.addAll(l);
        } else if (value != null) {
            out.add(value);
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else if (child.getName().toLowerCase().endsWith(".yml")) {
                out.add(child);
            }
        }
    }

    private static String relativeId(File root, File file) {
        String rel = root.toURI().relativize(file.toURI()).getPath();
        if (rel.toLowerCase().endsWith(".yml")) {
            rel = rel.substring(0, rel.length() - 4);
        }
        return rel.replace('\\', '/');
    }
}
