package com.liuxingyu.liutrade;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
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
        int disabled = 0;
        if (tradeSection != null) {
            int index = 0;
            for (String key : tradeSection.getKeys(false)) {
                if (!(tradeSection.get(key) instanceof ConfigurationSection section)) {
                    warn("交易 " + id + "#" + key + " 格式不对（应为一个映射），已跳过。");
                    index++;
                    continue;
                }
                if (!tradeEnabled(section)) {
                    // 单独关闭这一笔：不加载、不显示、不可交易；配置保留，改回 true 即可恢复。
                    disabled++;
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
        if (disabled > 0) {
            info("商店 " + id + " 有 " + disabled + " 笔交易通过 enable: false 关闭，已跳过。");
        }
        if (trades.isEmpty()) {
            warn("商店 " + id + " 没有任何有效交易，已跳过。");
            return null;
        }
        Merchant merchant = new Merchant(id);
        merchant.name = cfg.getString("name", id);
        merchant.title = cfg.getString("title", merchant.name);
        merchant.npc = orEmpty(cfg.getString("npc", "")).trim();
        merchant.permission = orEmpty(cfg.getString("permission", "")).trim();
        merchant.icon = spec(cfg.get("icon"));
        merchant.trades = Collections.unmodifiableList(trades);
        return merchant;
    }

    private Trade parseTrade(ConfigurationSection section, String id, String key) {
        List<ItemSpec> inputs = new ArrayList<>();
        List<ItemSpec> outputs = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        String commandDisplayName = null;
        boolean outputCommandConfig = false;
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
                outputCommandConfig = true;
                Object rawCommands = map.containsKey("commands") ? map.get("commands") : map.get("command");
                commands.addAll(stringList(rawCommands));
                Map<String, Object> itemMap = new LinkedHashMap<>(map);
                itemMap.remove("commands");
                itemMap.remove("command");
                ItemSpec spec = spec(itemMap);
                if (spec != null) {
                    outputs.add(spec);
                } else {
                    // 纯指令产出没有物品可命名：条目上的 display_name/label 只用于指令产出图标。
                    String name = blankToNull(itemMap.get("display_name"));
                    if (name == null) {
                        name = blankToNull(itemMap.get("label"));
                    }
                    if (name != null) {
                        if (commandDisplayName == null) {
                            commandDisplayName = name;
                        } else if (!commandDisplayName.equals(name)) {
                            warn("交易 " + id + " 有多个指令产出名，只用第一个：" + commandDisplayName);
                        }
                    }
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
        commands.removeIf(command -> command == null || command.isBlank());
        if (inputs.isEmpty() || inputs.size() > Trade.MAX_INPUTS) {
            warn("交易 " + id + " 的 inputs 必须为 1~" + Trade.MAX_INPUTS + " 项，已跳过。");
            return null;
        }
        if (plugin != null) {
            SoulSpaceHook ss = plugin instanceof LiuTrade liuTrade ? liuTrade.soulSpace() : null;
            for (ItemSpec input : inputs) {
                // 输入全部放在一个槽位：超过该物品单堆叠上限就永远凑不齐，提前提醒管理员。
                ItemStack sample = input.sample(ss);
                int max = sample == null ? 64 : Math.max(1, sample.getMaxStackSize());
                if (input.amount > max) {
                    warn("交易 " + id + " 的输入数量 " + input.amount + " 超过该物品单槽上限 " + max
                            + "，将永远无法完成，请拆成多笔交易或减小数量。");
                }
            }
        }
        boolean hasItem = !outputs.isEmpty();
        boolean hasCommand = !commands.isEmpty();
        // 只有在完全没有配置 outputs/commands 时才默认 convert；空列表/全是空指令属于配置错误，
        // 不能悄悄退化成「按规则转换」。
        boolean hasOutputConfig = section.isSet("outputs") || section.isSet("output");
        boolean hasCommandConfig = section.isSet("commands") || outputCommandConfig;
        boolean convert = booleanValue(section.get("convert"), !hasOutputConfig && !hasCommandConfig);
        if (convert && inputs.size() > 1) {
            warn("交易 " + id + " 使用 convert 时必须只有 1 个输入，已跳过。");
            return null;
        }
        if (convert && hasItem) {
            warn("交易 " + id + " 同时配置了 outputs 与 convert: true，将按 outputs 发放（convert 被忽略）。");
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
        trade.commandDisplayName = commandDisplayName;
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
        String nameText = name == null ? "" : String.valueOf(name).trim();
        trade.name = nameText.isEmpty() ? inputs.get(0).label() : String.valueOf(name);
        Object permission = section.get("permission");
        trade.permission = permission != null ? String.valueOf(permission).trim() : "";
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

    private static String blankToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
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

    private void info(String message) {
        if (plugin != null) {
            plugin.getLogger().info(message);
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

    /** 单笔交易是否启用：{@code enable}/{@code enabled} 写 false 即关闭；未写或无法识别按启用处理。 */
    private static boolean tradeEnabled(ConfigurationSection section) {
        for (String key : new String[]{"enable", "enabled"}) {
            if (!section.isSet(key)) {
                continue;
            }
            Object raw = section.get(key);
            if (raw instanceof Boolean b) {
                return b;
            }
            if (raw instanceof String s) {
                String text = s.trim();
                if (text.equalsIgnoreCase("true")) {
                    return true;
                }
                if (text.equalsIgnoreCase("false")) {
                    return false;
                }
            }
            // 写成别的东西（空值/数字/错别字）时保持启用，避免手误把交易关掉。
            return true;
        }
        return true;
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
