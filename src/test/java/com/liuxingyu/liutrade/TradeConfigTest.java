package com.liuxingyu.liutrade;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeConfigTest {

    @Test
    void nullSectionMessageDoesNotBreakLoad() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("messages.prefix", "&8[&6交易&8] ");
        cfg.createSection("messages.broken");
        TradeConfig config = new TradeConfig();
        config.load(cfg);
        // 值为节（getString 返回 null）时跳过，而不是让 Map.copyOf 抛 NPE。
        assertTrue(config.prefixed("broken").contains("broken"));
    }

    @Test
    void readsGuiAndPollSettings() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("settings.gui-mode", "custom");
        cfg.set("settings.poll-interval-ticks", 5);
        cfg.set("settings.max-pages", 0);
        TradeConfig config = new TradeConfig();
        config.load(cfg);
        assertEquals("custom", config.guiMode);
        assertEquals(20, config.pollIntervalTicks, "轮询间隔下限 20 tick");
        assertEquals(1, config.maxPages);
    }
}
