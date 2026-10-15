package com.liuxingyu.liutrade;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** custom_data 解析：兼容显式 PublicBukkitValues 外层包裹。 */
class ItemBuilderTest {

    @Test
    void unwrapsPublicBukkitValuesWrapper() {
        Map<String, Object> out = ItemBuilder.customDataMap(
                Map.of("PublicBukkitValues", Map.of("liutrade:coupon", true, "tier", 3)));
        assertFalse(out.containsKey("PublicBukkitValues"));
        assertEquals(Boolean.TRUE, out.get("liutrade:coupon"));
        assertEquals(3, out.get("tier"));
    }

    @Test
    void unwrapsWrapperFromSnbtString() {
        Map<String, Object> out = ItemBuilder.customDataMap(
                "{PublicBukkitValues:{\"liutrade:coupon\":true,tier:3}}");
        assertEquals(Boolean.TRUE, out.get("liutrade:coupon"));
        assertEquals(3, out.get("tier"));
    }

    @Test
    void keepsDirectKeysUnchanged() {
        Map<String, Object> out = ItemBuilder.customDataMap(Map.of("liutrade:coupon", true));
        assertEquals(Boolean.TRUE, out.get("liutrade:coupon"));
        assertFalse(out.containsKey("PublicBukkitValues"));
    }
}
