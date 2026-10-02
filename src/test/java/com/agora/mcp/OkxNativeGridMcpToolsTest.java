package com.agora.mcp;

import com.agora.config.OkxTradingProperties;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class OkxNativeGridMcpToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void statusKeepsProviderStateAndFetchesOneTickerPerInstrument() throws Exception {
        var provider = new OfflineProvider(); provider.twoBots = true;
        var report = mapper.readTree(new OkxNativeGridMcpTools(provider, mapper).getOkxNativeSpotGridStatus(false));
        assertEquals(2, report.path("outOfRangeCount").asInt());
        assertEquals(1, provider.tickerCalls);
        assertEquals(0, provider.historyCalls);
        assertEquals("running", report.path("active").get(0).path("state").asText());
        assertFalse(report.path("nativeGridCreateAllowed").asBoolean());
        assertFalse(report.path("orderSent").asBoolean());
    }

    @Test void tickerFailurePreservesInventoryAndNeverReportsPriceInsideRange() throws Exception {
        var provider = new OfflineProvider(); provider.failTicker = true;
        var report = mapper.readTree(new OkxNativeGridMcpTools(provider, mapper).getOkxNativeSpotGridStatus(false));
        assertEquals(1, report.path("activeCount").asInt());
        assertEquals(1, report.path("rangeMissingProofCount").asInt());
        assertEquals("MISSING_PROOF", report.path("rangeObservations").get(0).path("rangeStatus").asText());
    }

    @Test void missingLaterFilledPageCannotProduceExactNetDespiteBalancedFetchedFills() throws Exception {
        var provider = new OfflineProvider(); provider.terminal = true; provider.failSecondFilledPage = true;
        var report = mapper.readTree(new OkxNativeGridMcpTools(provider, mapper).getOkxNativeSpotGridAcceptanceEvidence("123"));
        assertTrue(report.path("terminalProviderStateProven").asBoolean());
        assertTrue(report.path("baseResidualWithinOneLot").asBoolean());
        assertFalse(report.path("filledSubOrderInventoryComplete").asBoolean());
        assertFalse(report.path("exactNetPnlProven").asBoolean());
        assertEquals("PARTIAL_FETCHED_FILLS_ONLY", report.path("cashFlowCoverage").asText());
        assertFalse(report.has("exactNetPnlUsdt"));
    }

    @Test void liveInventoryFailureBlocksExactNetEvenWhenFilledHistoryIsComplete() throws Exception {
        var provider = new OfflineProvider(); provider.terminal = true; provider.failLivePage = true;
        var report = mapper.readTree(new OkxNativeGridMcpTools(provider, mapper).getOkxNativeSpotGridAcceptanceEvidence("123"));
        assertTrue(report.path("filledSubOrderInventoryComplete").asBoolean());
        assertFalse(report.path("liveSubOrderInventoryComplete").asBoolean());
        assertFalse(report.path("exactNetPnlProven").asBoolean());
    }

    @Test void exhaustedHistoryAndBalancedTerminalReceiptKeepExistingExactNetPath() throws Exception {
        var provider = new OfflineProvider(); provider.terminal = true;
        var report = mapper.readTree(new OkxNativeGridMcpTools(provider, mapper).getOkxNativeSpotGridAcceptanceEvidence("123"));
        assertTrue(report.path("exactNetPnlProven").asBoolean());
        assertEquals(0, BigDecimal.ONE.compareTo(report.path("exactNetPnlUsdt").decimalValue()));
        assertEquals(2, report.path("filledSubOrderPageCount").asInt());
    }

    @Test void noActiveBotDoesNotQueryMarketOrInventRangeHealth() throws Exception {
        var provider = new OfflineProvider(); provider.terminal = true;
        var report = mapper.readTree(new OkxNativeGridMcpTools(provider, mapper).getOkxNativeSpotGridStatus(false));
        assertEquals(0, report.path("activeCount").asInt());
        assertTrue(report.path("rangeObservations").isEmpty());
        assertEquals(0, provider.tickerCalls);
    }

    private class OfflineProvider extends OkxTradingService {
        boolean twoBots, failTicker, terminal, failSecondFilledPage, failLivePage;
        int tickerCalls, historyCalls;
        OfflineProvider() { super(new OkxTradingProperties(), mapper); }
        @Override public JsonNode getNativeSpotGridOrders(boolean history) {
            if (history) historyCalls++;
            if (history != terminal) return mapper.createArrayNode();
            ArrayNode bots = mapper.createArrayNode().add(bot());
            if (twoBots) bots.add(bot().put("algoId", "456"));
            return bots;
        }
        @Override public JsonNode getNativeSpotGridOrderDetails(String algoId) { return mapper.createArrayNode().add(bot()); }
        @Override public JsonNode getSpotTickerSnapshot(String symbol) {
            tickerCalls++;
            if (failTicker) throw new IllegalStateException("offline ticker failure");
            return mapper.createObjectNode().put("instId", "BTC-USDT").put("instType", "SPOT")
                    .put("last", "86725.3").put("ts", Instant.now().toEpochMilli());
        }
        @Override public JsonNode getNativeSpotGridSubOrders(String algoId, String type, String after) {
            if ("live".equals(type)) {
                if (failLivePage) throw new IllegalStateException("offline live page failure");
                return mapper.createArrayNode();
            }
            if (after != null) {
                if (failSecondFilledPage) throw new IllegalStateException("offline pagination failure");
                return mapper.createArrayNode();
            }
            ArrayNode rows = mapper.createArrayNode();
            rows.addObject().put("algoId", "123").put("instId", "BTC-USDT").put("ordId", "2").put("groupId", "g1").put("side", "sell");
            rows.addObject().put("algoId", "123").put("instId", "BTC-USDT").put("ordId", "1").put("groupId", "g1").put("side", "buy");
            return rows;
        }
        @Override public JsonNode getFillHistoryPage(String type, String symbol, String orderId, int limit, String after) {
            var response = mapper.createObjectNode().put("code", "0"); var fills = response.putArray("data");
            if (after == null) fills.addObject().put("algoId", "123").put("ordId", orderId)
                    .put("billId", orderId).put("tradeId", orderId).put("fillSz", "1")
                    .put("fillPx", "2".equals(orderId) ? "101" : "100")
                    .put("side", "2".equals(orderId) ? "sell" : "buy").put("fee", "0").put("feeCcy", "USDT");
            return response;
        }
        @Override public SpotInstrumentRules getSpotInstrumentRules(String symbol) {
            return new SpotInstrumentRules("BTC-USDT", new BigDecimal("0.00001"), new BigDecimal("0.00000001"), new BigDecimal("0.1"));
        }
        private com.fasterxml.jackson.databind.node.ObjectNode bot() {
            return mapper.createObjectNode().put("algoId", "123").put("instId", "BTC-USDT")
                    .put("state", terminal ? "stopped" : "running").put("minPx", "63978").put("maxPx", "67259");
        }
    }
}
