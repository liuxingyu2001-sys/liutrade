package com.liuxingyu.liutrade;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/** config.yml 读取。 */
final class TradeConfig {

    /** 自动重载在全局线程写、玩家线程读：字段 volatile，消息表整体替换为不可变快照。 */
    private volatile Map<String, String> messages = Map.of();
    volatile String sharedConfigPath = "";
    volatile boolean autoReload = true;
    volatile int pollIntervalTicks = 200;
    volatile String leftoverOnClose = "return";
    volatile boolean feedback = true;
    volatile int maxPages = 20;
    volatile String guiMode = "villager";

    void load(FileConfiguration cfg) {
        Map<String, String> next = new HashMap<>();
        sharedConfigPath = cfg.getString("settings.shared-config-path", "");
        autoReload = cfg.getBoolean("settings.auto-reload", true);
        pollIntervalTicks = Math.max(20, cfg.getInt("settings.poll-interval-ticks", 200));
        leftoverOnClose = cfg.getString("settings.leftover-on-close", "return");
        feedback = cfg.getBoolean("settings.feedback", true);
        maxPages = Math.max(1, cfg.getInt("settings.max-pages", 20));
        guiMode = "custom".equalsIgnoreCase(cfg.getString("settings.gui-mode", "villager")) ? "custom" : "villager";

        if (cfg.isConfigurationSection("messages")) {
            for (String key : cfg.getConfigurationSection("messages").getKeys(false)) {
                String value = cfg.getString("messages." + key);
                if (value != null) {
                    next.put(key, value);
                }
            }
        }
        messages = Map.copyOf(next);
    }

    String msg(String key, String... replacements) {
        return msg(messages, key, replacements);
    }

    private String msg(Map<String, String> snapshot, String key, String... replacements) {
        String raw = snapshot.get(key);
        if (raw == null) {
            // 键缺失或值为 null（例如被写成配置节）时，退回键名而不是发一条空消息。
            raw = key;
        }
        String text = Msg.color(raw);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return text;
    }

    String prefixed(String key, String... replacements) {
        Map<String, String> snapshot = messages;
        String prefixRaw = snapshot.getOrDefault("prefix", "&8[&6交易&8] &r");
        return Msg.color(prefixRaw) + msg(snapshot, key, replacements);
    }
}
