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
    private Map<String, Merchant> merchants = Collections.emptyMap();

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
            if (merchant.npc.equals(id) || merchant.npc.equalsIgnoreCase(name)) {
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
                plugin.getLogger().warning("解析商店 " + id + " 失败：" + e);
            }
        }
        this.merchants = parsed;
        plugin.getLogger().info("已加载 " + parsed.size() + " 个交易商店。");
    }

    private Merchant parse(YamlConfiguration cfg, String id) {
        ConfigurationSection tradeSection = cfg.getConfigurationSection("trades");
        List<Trade> trades = new ArrayList<>();
        if (tradeSection != null) {
            int index = 0;
            for (String key : tradeSection.getKeys(false)) {
                Object raw = tradeSection.get(key);
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                Trade trade = parseTrade(map, id + "#" + key + "#" + index);
                if (trade != null) {
                    trades.add(trade);
                }
                index++;
            }
        }
        if (trades.isEmpty()) {
            plugin.getLogger().warning("商店 " + id + " 没有任何有效交易，已跳过。");
            return null;
        }
        Merchant merchant = new Merchant(id);
        merchant.name = cfg.getString("name", id);
        merchant.title = cfg.getString("title", merchant.name);
        merchant.npc = orEmpty(cfg.getString("npc", ""));
        merchant.permission = orEmpty(cfg.getString("permission", ""));
        merchant.icon = ItemSpec.parse(cfg.get("icon"));
        merchant.trades = Collections.unmodifiableList(trades);
        return merchant;
    }

    private Trade parseTrade(Map<?, ?> map, String id) {
        List<ItemSpec> inputs = new ArrayList<>();
        List<ItemSpec> outputs = new ArrayList<>();
        for (Object raw : list(map.get("inputs"), map.get("input"))) {
            ItemSpec spec = ItemSpec.parse(raw);
            if (spec != null) {
                inputs.add(spec);
            }
        }
        for (Object raw : list(map.get("outputs"), map.get("output"))) {
            ItemSpec spec = ItemSpec.parse(raw);
            if (spec != null) {
                outputs.add(spec);
            }
        }
        if (inputs.isEmpty() || inputs.size() > 2) {
            plugin.getLogger().warning("交易 " + id + " 的 inputs 必须为 1~2 项，已跳过。");
            return null;
        }
        boolean convert = booleanValue(map.get("convert"), outputs.isEmpty());
        if (convert && inputs.size() > 1) {
            plugin.getLogger().warning("交易 " + id + " 使用 convert 时必须只有 1 个输入，已跳过。");
            return null;
        }
        if (!convert && outputs.isEmpty()) {
            plugin.getLogger().warning("交易 " + id + " 既没有 outputs 也没有 convert: true，已跳过。");
            return null;
        }
        Trade trade = new Trade(id);
        trade.inputs = Collections.unmodifiableList(inputs);
        trade.outputs = outputs.isEmpty() ? null : Collections.unmodifiableList(outputs);
        trade.convert = convert;
        Object name = map.get("name");
        trade.name = name != null ? String.valueOf(name) : inputs.get(0).label();
        Object permission = map.get("permission");
        trade.permission = permission != null ? String.valueOf(permission) : "";
        return trade;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
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
