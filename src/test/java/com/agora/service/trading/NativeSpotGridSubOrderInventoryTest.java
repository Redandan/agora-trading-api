package com.agora.service.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpotGridSubOrderInventoryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void fullFirstPageDoesNotTruncateOlderOrdersAndShortPageStillNeedsExhaustion() {
        List<String> cursors = new ArrayList<>();
        var result = NativeSpotGridSubOrderInventory.collect("123", "BTC-USDT", after -> {
            cursors.add(after == null ? "ROOT" : after);
            return after == null ? page(110, 11) : after.equals("11") ? page(10, 1) : page(0, 1);
        }, mapper);
        assertTrue(result.complete());
        assertEquals(110, result.orders().size());
        assertEquals(List.of("ROOT", "11", "1"), cursors);
    }

    @Test void repeatedCursorAndDuplicateOrdersCannotProveFullCoverage() {
        var repeat = NativeSpotGridSubOrderInventory.collect("123", "BTC-USDT", after -> page(2, 1), mapper);
        assertFalse(repeat.complete());
        assertEquals(2, repeat.pageCount());
        assertEquals(2, repeat.orders().size());
        ArrayNode duplicates = page(2, 2); duplicates.add(duplicates.get(0).deepCopy());
        assertFalse(NativeSpotGridSubOrderInventory.collect("123", "BTC-USDT", after -> duplicates, mapper).complete());
    }

    @Test void malformedPagesWrongBotWrongInstrumentAndProviderFailureFailClosed() {
        assertFalse(NativeSpotGridSubOrderInventory.collect("123", "BTC-USDT", after -> null, mapper).complete());
        var wrongBot = page(1, 1); ((com.fasterxml.jackson.databind.node.ObjectNode) wrongBot.get(0)).put("algoId", "999");
        assertFalse(NativeSpotGridSubOrderInventory.collect("123", "BTC-USDT", after -> wrongBot, mapper).complete());
        var wrongSymbol = page(1, 1); ((com.fasterxml.jackson.databind.node.ObjectNode) wrongSymbol.get(0)).put("instId", "ETH-USDT");
        assertFalse(NativeSpotGridSubOrderInventory.collect("123", "BTC-USDT", after -> wrongSymbol, mapper).complete());
        var partial = NativeSpotGridSubOrderInventory.collect("123", "BTC-USDT", after -> {
            if (after == null) return page(2, 1);
            throw new IllegalStateException("unavailable");
        }, mapper);
        assertFalse(partial.complete()); assertEquals(2, partial.orders().size());
        assertEquals("SUB_ORDER_PAGE_UNAVAILABLE", partial.reason());
    }

    @Test void pageBudgetPreventsUnboundedProviderReads() {
        var result = NativeSpotGridSubOrderInventory.collect("123", "BTC-USDT", after ->
                page(after == null ? 100 : Integer.parseInt(after) - 1,
                     after == null ? 100 : Integer.parseInt(after) - 1), mapper);
        assertFalse(result.complete());
        assertEquals(NativeSpotGridSubOrderInventory.MAX_PAGES, result.pageCount());
        assertEquals("SUB_ORDER_PAGE_LIMIT_REACHED", result.reason());
    }

    private ArrayNode page(int start, int end) {
        ArrayNode page = mapper.createArrayNode();
        for (int i = start; i >= end; i--) page.addObject().put("algoId", "123").put("instId", "BTC-USDT").put("ordId", "" + i);
        return page;
    }
}
