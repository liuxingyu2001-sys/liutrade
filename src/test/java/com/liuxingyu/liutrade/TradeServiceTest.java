package com.liuxingyu.liutrade;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeServiceTest {

    @Test
    void splitsOutputsAboveMaxStackSize() {
        assertEquals(List.of(64, 64, 2), TradeService.splitAmounts(130, 64));
    }

    @Test
    void ignoresNonPositiveAmount() {
        assertTrue(TradeService.splitAmounts(0, 64).isEmpty());
        assertTrue(TradeService.splitAmounts(-1, 64).isEmpty());
    }

    @Test
    void handlesInvalidMaxStackSize() {
        assertEquals(List.of(1, 1), TradeService.splitAmounts(2, 0));
    }
}
