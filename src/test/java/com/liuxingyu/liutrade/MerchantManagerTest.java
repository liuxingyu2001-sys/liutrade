package com.liuxingyu.liutrade;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确保 Bukkit 的 MemorySection 也能被正确解析（曾经的 0 交易 bug）。 */
class MerchantManagerTest {

    private static Merchant parse(String yaml) throws Exception {
        Path file = Files.createTempFile("liutrade-merchant", ".yml");
        Files.writeString(file, yaml);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file.toFile());
        return new MerchantManager(null).parse(cfg, "test");
    }

    @Test
    void parsesTradesWithSections() throws Exception {
        Merchant merchant = parse("""
                name: "&6铁匠"
                npc: "1"
                icon:
                  library_id: "mc:ANVIL"
                trades:
                  铁换金:
                    name: "铁->金"
                    inputs:
                      - material: IRON_INGOT
                        amount: 8
                    convert: true
                  钻换绿宝石:
                    inputs:
                      - material: DIAMOND
                    outputs:
                      - material: EMERALD
                        amount: 2
                """);
        assertNotNull(merchant);
        assertEquals("&6铁匠", merchant.name);
        assertEquals("1", merchant.npc);
        assertNotNull(merchant.icon);
        assertEquals("mc:ANVIL", merchant.icon.libraryId);
        assertEquals(2, merchant.trades.size());

        Trade first = merchant.trades.get(0);
        assertTrue(first.convert);
        assertEquals(1, first.inputs.size());
        assertEquals(8, first.inputs.get(0).amount);
        assertNull(first.outputs);

        Trade second = merchant.trades.get(1);
        assertEquals("DIAMOND", second.inputs.get(0).rule.get("material"));
        assertEquals("EMERALD", second.outputs.get(0).rule.get("material"));
        assertEquals(2, second.outputs.get(0).amount);
    }

    @Test
    void skipsMerchantWithoutTrades() throws Exception {
        assertNull(parse("name: \"空店\"\ntrades: {}\n"));
    }

    @Test
    void rejectsMultiInputConvert() throws Exception {
        assertNull(parse("""
                name: "坏店"
                trades:
                  双输入转换:
                    inputs:
                      - material: GOLD_INGOT
                      - material: REDSTONE
                    convert: true
                """));
    }

    @Test
    void parsesDescriptionAsListOrString() throws Exception {
        Merchant merchant = parse("""
                name: "店"
                trades:
                  甲:
                    description:
                      - "第一行"
                      - "第二行"
                    inputs:
                      - material: IRON_INGOT
                    outputs:
                      - material: GOLD_INGOT
                  乙:
                    description: "就一行"
                    inputs:
                      - material: COAL
                    outputs:
                      - material: TORCH
                """);
        assertNotNull(merchant);
        assertEquals(2, merchant.trades.get(0).description.size());
        assertEquals("第一行", merchant.trades.get(0).description.get(0));
        assertEquals(1, merchant.trades.get(1).description.size());
        assertEquals("就一行", merchant.trades.get(1).description.get(0));
    }

    @Test
    void parsesMultipleOutputsAndDisplay() throws Exception {
        Merchant merchant = parse("""
                name: "店"
                trades:
                  多产出:
                    inputs:
                      - material: PAPER
                        pdc: "jiu:jiu"
                        display:
                          library_id: "ce:ns:ticket"
                        display_name: "&6票券"
                    outputs:
                      - material: GOLD_INGOT
                        amount: 2
                      - material: EMERALD
                """);
        assertNotNull(merchant);
        Trade trade = merchant.trades.get(0);
        assertEquals(2, trade.outputs.size());
        assertEquals("ce:ns:ticket", trade.inputs.get(0).display.libraryId);
        assertEquals("&6票券", trade.inputs.get(0).configuredName());
    }
}
