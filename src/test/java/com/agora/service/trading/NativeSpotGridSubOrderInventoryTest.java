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

    @Test void liveSnapshotUsesExplicitDetailCountWithoutRepeatedCursorRead() {
        List<String> cursors = new ArrayList<>();
        var result = NativeSpotGridSubOrderInventory.collectLive("123", "BTC-USDT", liveDetail("10"), after -> {
            cursors.add(after == null ? "ROOT" : after);
            return page(10, 1);
        }, mapper);
        assertTrue(result.complete());
        assertEquals("LIVE_DETAIL_COUNT_MATCHED", result.reason());
        assertEquals(List.of("ROOT"), cursors);
        assertEquals(10, result.orders().size());
    }

    @Test void liveDetailMismatchAndTruncatedInventoryNeverProveCoverage() {
        var tooMany = NativeSpotGridSubOrderInventory.collectLive("123", "BTC-USDT", liveDetail("1"), after -> page(2, 1), mapper);
        assertFalse(tooMany.complete());
        assertEquals("LIVE_DETAIL_COUNT_MISMATCH", tooMany.reason());
        var tooFew = NativeSpotGridSubOrderInventory.collectLive("123", "BTC-USDT", liveDetail("3"), after ->
                after == null ? page(2, 1) : page(0, 1), mapper);
        assertFalse(tooFew.complete());
        assertEquals("LIVE_DETAIL_COUNT_MISMATCH", tooFew.reason());
        var capped = NativeSpotGridSubOrderInventory.collectLive("123", "BTC-USDT", liveDetail("101"), after -> page(100, 1), mapper);
        assertFalse(capped.complete());
    }

    @Test void absentInvalidForeignOrStoppedDetailCannotCertifyRepeatedLivePage() {
        for (var detail : List.of(liveDetail(""), liveDetail("N/A"), liveDetail("2").put("algoId", "456"),
                liveDetail("2").put("instId", "ETH-USDT"), liveDetail("2").put("state", "stopped"))) {
            assertFalse(NativeSpotGridSubOrderInventory.collectLive("123", "BTC-USDT", detail, after -> page(2, 1), mapper).complete());
        }
    }

    @Test void duplicateForeignAndFilledRowsCannotSatisfyLiveDetailCount() {
        var duplicates = page(1, 1); duplicates.add(duplicates.get(0).deepCopy());
        assertFalse(NativeSpotGridSubOrderInventory.collectLive("123", "BTC-USDT", liveDetail("2"), after -> duplicates, mapper).complete());
        var wrong = page(1, 1); ((com.fasterxml.jackson.databind.node.ObjectNode) wrong.get(0)).put("algoId", "456");
        assertFalse(NativeSpotGridSubOrderInventory.collectLive("123", "BTC-USDT", liveDetail("1"), after -> wrong, mapper).complete());
        var filled = page(1, 1); ((com.fasterxml.jackson.databind.node.ObjectNode) filled.get(0)).put("state", "filled");
        assertFalse(NativeSpotGridSubOrderInventory.collectLive("123", "BTC-USDT", liveDetail("1"), after -> filled, mapper).complete());
    }

    @Test void stoppedProviderErrorIsNotAnEmptySuccessfulInventory() {
        var result = NativeSpotGridSubOrderInventory.collectLive("123", "BTC-USDT", liveDetail("0").put("state", "stopped"), after -> {
            throw new IllegalStateException("OKX API error [code=51291]: The bot does not exist or has already stopped");
        }, mapper);
        assertFalse(result.complete());
        assertTrue(result.orders().isEmpty());
        assertEquals("SUB_ORDER_PROVIDER_ERROR_51291", result.reason());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode liveDetail(String count) {
        return mapper.createObjectNode().put("algoId", "123").put("instId", "BTC-USDT")
                .put("state", "running").put("activeOrdNum", count);
    }

    private ArrayNode page(int start, int end) {
        ArrayNode page = mapper.createArrayNode();
        for (int i = start; i >= end; i--) page.addObject().put("algoId", "123").put("instId", "BTC-USDT").put("ordId", "" + i).put("state", "live");
        return page;
    }
}
