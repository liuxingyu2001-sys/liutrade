package com.liuxingyu.liutrade;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemSpecTest {

    @Test
    void parsesLibraryIdWithAmount() {
        ItemSpec spec = ItemSpec.parse(Map.of("library_id", "ce:ns:id", "amount", 3));
        assertEquals("ce:ns:id", spec.libraryId);
        assertEquals(3, spec.amount);
        assertNull(spec.rule);
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
}
