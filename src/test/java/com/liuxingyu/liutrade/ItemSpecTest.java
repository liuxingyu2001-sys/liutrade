package com.liuxingyu.liutrade;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSpecTest {

    @Test
    void parsesLibraryIdWithAmount() {
        ItemSpec spec = ItemSpec.parse(Map.of("library_id", "ce:ns:id", "amount", 3));
        assertEquals("ce:ns:id", spec.libraryId);
        assertEquals(3, spec.amount);
        assertTrue(spec.rule.isEmpty());
    }

    @Test
    void parsesRuleFieldsWithoutAmount() {
        ItemSpec spec = ItemSpec.parse(Map.of("material", "PAPER", "pdc", "jiu:jiu", "amount", 8));
        assertNull(spec.libraryId);
        assertEquals(8, spec.amount);
        assertEquals("PAPER", spec.rule.get("material"));
        assertEquals("jiu:jiu", spec.rule.get("pdc"));
        assertFalse(spec.rule.containsKey("amount"));
        assertFalse(spec.rule.containsKey("library_id"));
    }

    @Test
    void parsesStringShorthand() {
        ItemSpec spec = ItemSpec.parse("DIAMOND");
        assertEquals("DIAMOND", spec.rule.get("material"));
        assertEquals(1, spec.amount);
    }

    @Test
    void rejectsEmptySpec() {
        assertNull(ItemSpec.parse(Map.of("amount", 2)));
        assertNull(ItemSpec.parse(""));
    }

    @Test
    void componentsBecomeExactItem() {
        ItemSpec spec = ItemSpec.parse(Map.of("material", "PAPER", "components", Map.of("custom_model_data", 7)));
        assertEquals("PAPER", spec.material);
        assertEquals(7, spec.components.get("custom_model_data"));
        assertTrue(spec.nativeRepresentable());
    }

    @Test
    void pdcRuleIsNotNativeRepresentable() {
        ItemSpec spec = ItemSpec.parse(Map.of("material", "PAPER", "pdc", "jiu:jiu"));
        assertFalse(spec.nativeRepresentable());
    }

    @Test
    void parsesDisplayAndDisplayNameWithoutPollutingRule() {
        ItemSpec spec = ItemSpec.parse(Map.of(
                "material", "PAPER",
                "pdc", "jiu:jiu",
                "display", Map.of("library_id", "ce:ns:id"),
                "display_name", "&6神秘票券"));
        assertNotNull(spec);
        assertEquals("&6神秘票券", spec.configuredName());
        assertNotNull(spec.display);
        assertEquals("ce:ns:id", spec.display.libraryId);
        assertTrue(spec.rule.containsKey("material"));
        assertTrue(spec.rule.containsKey("pdc"));
        assertFalse(spec.rule.containsKey("display"));
        assertFalse(spec.rule.containsKey("display_name"));
        assertFalse(spec.nativeRepresentable());
    }

    @Test
    void normalizesEnglishMaterialIds() {
        assertEquals("DIAMOND", ItemSpec.parse("diamond").material);
        ItemSpec spec = ItemSpec.parse(Map.of("material", "minecraft:diamond"));
        assertEquals("DIAMOND", spec.material);
        assertEquals("DIAMOND", spec.rule.get("material"));
        assertEquals("SHULKER_*", ItemSpec.parse(Map.of("material", "shulker_*")).material);
    }

    @Test
    void stringShorthandAcceptsLibraryId() {
        ItemSpec spec = ItemSpec.parse("ce:example:ticket");
        assertEquals("ce:example:ticket", spec.libraryId);
        assertNull(spec.material);
        assertEquals(1, spec.amount);
    }

    @Test
    void ruleFieldsRequireSoulSpaceRules() {
        // 除 material 外的规则字段（pdc/name/not_* 等）必须依赖 SoulSpace 匹配。
        assertTrue(ItemSpec.parse(Map.of("material", "PAPER", "pdc", "jiu:jiu")).requiresRules());
        assertTrue(ItemSpec.parse(Map.of("material", "PAPER", "not_pdc", "soulbound")).requiresRules());
        assertFalse(ItemSpec.parse("PAPER").requiresRules());
        assertFalse(ItemSpec.parse(Map.of("material", "DIAMOND", "amount", 3)).requiresRules());
    }

    @Test
    void materialSupportsCountSuffix() {
        ItemSpec star = ItemSpec.parse(Map.of("material", "DIAMOND*3"));
        assertEquals("DIAMOND", star.material);
        assertEquals(3, star.amount);
        assertFalse(star.opaqueMaterial);

        ItemSpec colon = ItemSpec.parse(Map.of("material", "minecraft:diamond:2"));
        assertEquals("DIAMOND", colon.material);
        assertEquals(2, colon.amount);

        ItemSpec shorthand = ItemSpec.parse("DIAMOND*4");
        assertEquals("DIAMOND", shorthand.material);
        assertEquals(4, shorthand.amount);

        // 显式 amount 优先于材质里自带的数量
        ItemSpec explicit = ItemSpec.parse(Map.of("material", "DIAMOND*3", "amount", 5));
        assertEquals("DIAMOND", explicit.material);
        assertEquals(5, explicit.amount);

        // library_id 同样兼容数量写法
        ItemSpec library = ItemSpec.parse(Map.of("library_id", "DIAMOND*2"));
        assertEquals("DIAMOND", library.libraryId);
        assertEquals(2, library.amount);
    }

    @Test
    void opaqueMaterialSpecsAreExactNotRuleMatched() {
        ItemSpec ce = ItemSpec.parse(Map.of("material", "ce:ns:id"));
        assertEquals("ce:ns:id", ce.material);
        assertTrue(ce.opaqueMaterial);
        assertTrue(ce.nativeRepresentable());

        ItemSpec encoded = ItemSpec.parse(Map.of("material", "base64:AAAA"));
        assertTrue(encoded.opaqueMaterial);
        assertEquals("自定义物品", encoded.label());

        // 通配写法仍走 SoulSpace 规则，不能被当成 LiuLib 物品规格
        ItemSpec wildcard = ItemSpec.parse(Map.of("material", "shulker_*"));
        assertFalse(wildcard.opaqueMaterial);
        assertEquals("SHULKER_*", wildcard.material);
        assertFalse(wildcard.nativeRepresentable());
    }

    @Test
    void nativeRepresentableRejectsExtraRulesOnExactSpecs() {
        // 完整物品规格 + pdc 规则：原生界面无法同时按规则匹配
        assertFalse(ItemSpec.parse(Map.of("library_id", "ce:ns:id", "pdc", "jiu:jiu")).nativeRepresentable());
        assertFalse(ItemSpec.parse(Map.of(
                "material", "PAPER",
                "components", Map.of("custom_model_data", 1),
                "pdc", "jiu:jiu")).nativeRepresentable());
        // 只有组件、没有额外规则时仍可用原生界面
        assertTrue(ItemSpec.parse(Map.of(
                "material", "PAPER",
                "components", Map.of("custom_model_data", 1))).nativeRepresentable());
    }
}
