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
}
