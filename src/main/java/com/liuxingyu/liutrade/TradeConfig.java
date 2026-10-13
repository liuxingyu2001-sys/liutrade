package com.liuxingyu.liutrade;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/** config.yml 读取。 */
final class TradeConfig {

    private final Map<String, String> messages = new HashMap<>();
    String sharedConfigPath = "";
    boolean autoReload = true;
    int pollIntervalTicks = 200;
    String leftoverOnClose = "return";
    boolean feedback = true;
    int maxPages = 20;
    String guiMode = "villager";

    void load(FileConfiguration cfg) {
        sharedConfigPath = cfg.getString("settings.shared-config-path", "");
        autoReload = cfg.getBoolean("settings.auto-reload", true);
        pollIntervalTicks = Math.max(20, cfg.getInt("settings.poll-interval-ticks", 200));
        leftoverOnClose = cfg.getString("settings.leftover-on-close", "return");
        feedback = cfg.getBoolean("settings.feedback", true);
        maxPages = Math.max(1, cfg.getInt("settings.max-pages", 20));
        guiMode = "custom".equalsIgnoreCase(cfg.getString("settings.gui-mode", "villager")) ? "custom" : "villager";

        messages.clear();
        if (cfg.isConfigurationSection("messages")) {
            for (String key : cfg.getConfigurationSection("messages").getKeys(false)) {
                messages.put(key, cfg.getString("messages." + key));
            }
        }
    }

    String msg(String key, String... replacements) {
        String raw = messages.get(key);
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
        String prefixRaw = messages.getOrDefault("prefix", "&8[&6交易&8] &r");
        return Msg.color(prefixRaw) + msg(key, replacements);
    }
}
