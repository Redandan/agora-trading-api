package com.agora.mcp;

import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.OkxNativeGridExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OkxNativeGridMcpToolsTest {

    @Test
    void reportsNativeInventoryWithoutExposingMutation() {
        OkxTradingService okx = mock(OkxTradingService.class);
        BtGridRepository grids = mock(BtGridRepository.class);
        BtGridLevelRepository levels = mock(BtGridLevelRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode active = mapper.createArrayNode();
        ObjectNode bot = active.addObject();
        bot.put("algoId", "native-grid-1");
        bot.put("instId", "BTC-USDT");
        when(okx.getNativeSpotGridOrders(false)).thenReturn(active);
        when(okx.getNativeSpotGridOrders(true)).thenReturn(mapper.createArrayNode());

        String output = new OkxNativeGridMcpTools(okx, mapper, grids, levels, mock(OkxNativeGridExecutionService.class))
                .getOkxNativeSpotGridStatus(true);

        assertThat(output).contains(
                "READ_ONLY_OKX_NATIVE_SPOT_GRID_NO_BOT_MUTATION",
                "\"activeCount\" : 1",
                "\"historyCount\" : 0",
                "\"nativeGridCreateAllowed\" : false",
                "\"nativeGridStopAllowed\" : false",
                "native-grid-1");
        verify(okx).getNativeSpotGridOrders(false);
        verify(okx).getNativeSpotGridOrders(true);
    }

    @Test
    void migrationPreviewReportsLegacyHoldingAndNeverMutates() {
        OkxTradingService okx = mock(OkxTradingService.class);
        BtGridRepository grids = mock(BtGridRepository.class);
        BtGridLevelRepository levels = mock(BtGridLevelRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        when(okx.getNativeSpotGridOrders(false)).thenReturn(mapper.createArrayNode());
        when(okx.getSpotInstrumentRules("BTC-USDT")).thenReturn(
                new OkxTradingService.SpotInstrumentRules("BTC-USDT", new BigDecimal("0.00001"),
                        new BigDecimal("0.00000001"), new BigDecimal("0.1")));
        when(okx.getLastPrice("BTC-USDT")).thenReturn(new BigDecimal("66370.5"));
        when(okx.getNativeSpotGridMinimumInvestment(
                "BTC-USDT", new BigDecimal("60000"), new BigDecimal("70000"), 10))
                .thenReturn(minimumInvestment(mapper, "10"));

        BtGrid grid = new BtGrid();
        grid.setId(10L);
        grid.setSymbol("BTCUSDT");
        grid.setEnabled(false);
        grid.setPausedAt(LocalDateTime.parse("2026-07-21T00:00:00"));
        BtGridLevel holding = new BtGridLevel();
        holding.setId(101L);
        holding.setStatus("HOLDING");
        holding.setFilledQty(new BigDecimal("0.00008096"));
        holding.setPairedSellPrice(new BigDecimal("66511.1467"));
        when(grids.findBySymbolAndClosedAtIsNull("BTCUSDT")).thenReturn(List.of(grid));
        when(levels.findByGridId(10L)).thenReturn(List.of(holding));

        String output = new OkxNativeGridMcpTools(okx, mapper, grids, levels, mock(OkxNativeGridExecutionService.class))
                .previewOkxNativeSpotGridMigration("BTCUSDT", new BigDecimal("60000"),
                        new BigDecimal("70000"), 10, new BigDecimal("10"));

        assertThat(output).contains(
                "READ_ONLY_PREFLIGHT_NO_GRID_OR_ORDER_MUTATION",
                "\"gridId\" : 10",
                "\"status\" : \"HOLDING\"",
                "LEGACY_INVENTORY_OR_IN_FLIGHT_LEVEL_REQUIRES_SEPARATE_RESOLUTION_AUTHORIZATION",
                "PUBLIC_RULE_LOWER_BOUND_PASSES_NOT_PROVIDER_CREATE_ACCEPTANCE",
                "\"minimumTotalQuoteLowerBound\" : 6.00000",
                "\"exactMinimumQuoteUsdt\" : 10",
                "\"exactMinimumStatus\" : \"PASSES\"",
                "\"orderSent\" : false",
                "\"dbMutation\" : false",
                "NOT_READY_FOR_TRADE_AUTHORIZATION");
        verify(okx).getNativeSpotGridOrders(false);
        verify(grids).findBySymbolAndClosedAtIsNull("BTCUSDT");
        verify(levels).findByGridId(10L);
    }

    @Test
    void acceptanceEvidenceComputesExactNetOnlyFromTerminalSignedFeeCompleteBotFills() {
        OkxTradingService okx = mock(OkxTradingService.class);
        BtGridRepository grids = mock(BtGridRepository.class);
        BtGridLevelRepository levels = mock(BtGridLevelRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        when(okx.getNativeSpotGridOrders(false)).thenReturn(mapper.createArrayNode());
        ArrayNode history = mapper.createArrayNode();
        history.addObject().put("algoId", "123456789").put("instId", "BTC-USDT").put("state", "stopped");
        when(okx.getNativeSpotGridOrders(true)).thenReturn(history);
        ArrayNode detail = mapper.createArrayNode();
        detail.addObject().put("algoId", "123456789").put("instId", "BTC-USDT").put("state", "stopped");
        when(okx.getNativeSpotGridOrderDetails("123456789")).thenReturn(detail);

        ArrayNode filled = mapper.createArrayNode();
        filled.addObject().put("algoId", "123456789").put("ordId", "1001")
                .put("groupId", "pair-1").put("side", "buy");
        filled.addObject().put("algoId", "123456789").put("ordId", "1002")
                .put("groupId", "pair-1").put("side", "sell");
        when(okx.getNativeSpotGridSubOrders("123456789", "filled")).thenReturn(filled);
        when(okx.getNativeSpotGridSubOrders("123456789", "live")).thenReturn(mapper.createArrayNode());

        ObjectNode fillPage = mapper.createObjectNode().put("code", "0");
        ArrayNode fills = fillPage.putArray("data");
        fills.addObject().put("algoId", "123456789").put("ordId", "1001").put("side", "buy")
                .put("fillPx", "60000").put("fillSz", "0.0001").put("fee", "-0.0000001").put("feeCcy", "BTC");
        fills.addObject().put("algoId", "123456789").put("ordId", "1002").put("side", "sell")
                .put("fillPx", "61000").put("fillSz", "0.0000999").put("fee", "-0.00060939").put("feeCcy", "USDT");
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", 100, null)).thenReturn(fillPage);
        when(okx.getSpotInstrumentRules("BTC-USDT")).thenReturn(
                new OkxTradingService.SpotInstrumentRules("BTC-USDT", new BigDecimal("0.00001"),
                        new BigDecimal("0.00000001"), new BigDecimal("0.1")));

        String output = new OkxNativeGridMcpTools(okx, mapper, grids, levels,
                mock(OkxNativeGridExecutionService.class))
                .getOkxNativeSpotGridAcceptanceEvidence("123456789");

        assertThat(output).contains(
                "READ_ONLY_NATIVE_GRID_EXACT_EVIDENCE_NO_MUTATION",
                "\"providerLifecycle\" : \"HISTORY_TERMINAL\"",
                "\"completedProviderGroupPairCount\" : 1",
                "\"providerFillCount\" : 2",
                "\"signedFeeCoverageComplete\" : true",
                "\"baseResidualWithinOneLot\" : true",
                "\"exactNetPnlProven\" : true",
                "\"exactNetPnlUsdt\" : 0.09329061",
                "\"functionalAcceptance\" : \"NOT_YET_PROVEN\"",
                "\"orderSent\" : false",
                "\"databaseMutation\" : false");
    }

    @Test
    void acceptanceEvidenceNeverLabelsActiveOrFillIncompleteBotAsExactNet() {
        OkxTradingService okx = mock(OkxTradingService.class);
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode active = mapper.createArrayNode();
        active.addObject().put("algoId", "123456789").put("instId", "BTC-USDT").put("state", "running");
        when(okx.getNativeSpotGridOrders(false)).thenReturn(active);
        when(okx.getNativeSpotGridOrders(true)).thenReturn(mapper.createArrayNode());
        when(okx.getNativeSpotGridOrderDetails("123456789")).thenReturn(active);
        when(okx.getNativeSpotGridSubOrders("123456789", "filled")).thenReturn(mapper.createArrayNode());
        when(okx.getNativeSpotGridSubOrders("123456789", "live")).thenReturn(mapper.createArrayNode());
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", 100, null))
                .thenReturn(mapper.createObjectNode().put("code", "0").set("data", mapper.createArrayNode()));
        when(okx.getSpotInstrumentRules("BTC-USDT")).thenReturn(
                new OkxTradingService.SpotInstrumentRules("BTC-USDT", new BigDecimal("0.00001"),
                        new BigDecimal("0.00000001"), new BigDecimal("0.1")));

        String output = new OkxNativeGridMcpTools(okx, mapper, mock(BtGridRepository.class),
                mock(BtGridLevelRepository.class), mock(OkxNativeGridExecutionService.class))
                .getOkxNativeSpotGridAcceptanceEvidence("123456789");

        assertThat(output).contains(
                "\"providerLifecycle\" : \"ACTIVE\"",
                "NO_COMPLETED_PROVIDER_BUY_SELL_GROUP_PAIR",
                "NO_PROVIDER_FILLS_BOUND_TO_BOT",
                "BOT_NOT_TERMINAL_IN_PROVIDER_HISTORY",
                "\"exactNetPnlProven\" : false",
                "\"functionalAcceptance\" : \"NOT_YET_PROVEN\"")
                .doesNotContain("\"exactNetPnlUsdt\"");
    }

    @Test
    void migrationPreviewRejectsBudgetAboveTinyLiveCap() {
        OkxTradingService okx = mock(OkxTradingService.class);
        BtGridRepository grids = mock(BtGridRepository.class);
        BtGridLevelRepository levels = mock(BtGridLevelRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        when(okx.getNativeSpotGridOrders(false)).thenReturn(mapper.createArrayNode());
        when(okx.getSpotInstrumentRules("BTC-USDT")).thenReturn(
                new OkxTradingService.SpotInstrumentRules("BTC-USDT", new BigDecimal("0.00001"),
                        new BigDecimal("0.00000001"), new BigDecimal("0.1")));
        when(okx.getLastPrice("BTC-USDT")).thenReturn(new BigDecimal("66370.5"));
        when(grids.findBySymbolAndClosedAtIsNull("BTCUSDT")).thenReturn(List.of());

        String output = new OkxNativeGridMcpTools(okx, mapper, grids, levels, mock(OkxNativeGridExecutionService.class))
                .previewOkxNativeSpotGridMigration("BTC-USDT", new BigDecimal("60000"),
                        new BigDecimal("70000"), 10, new BigDecimal("10.01"));

        assertThat(output).contains("TOTAL_QUOTE_MUST_BE_POSITIVE_AND_AT_MOST_10_USDT");
    }

    @Test
    void migrationPreviewRejectsAmountBelowPublicInstrumentRuleLowerBound() {
        OkxTradingService okx = mock(OkxTradingService.class);
        BtGridRepository grids = mock(BtGridRepository.class);
        BtGridLevelRepository levels = mock(BtGridLevelRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        when(okx.getNativeSpotGridOrders(false)).thenReturn(mapper.createArrayNode());
        when(okx.getSpotInstrumentRules("BTC-USDT")).thenReturn(
                new OkxTradingService.SpotInstrumentRules("BTC-USDT", new BigDecimal("0.00001"),
                        new BigDecimal("0.00000001"), new BigDecimal("0.1")));
        when(okx.getLastPrice("BTC-USDT")).thenReturn(new BigDecimal("66370.5"));
        when(grids.findBySymbolAndClosedAtIsNull("BTCUSDT")).thenReturn(List.of());

        String output = new OkxNativeGridMcpTools(okx, mapper, grids, levels, mock(OkxNativeGridExecutionService.class))
                .previewOkxNativeSpotGridMigration("BTC-USDT", new BigDecimal("60000"),
                        new BigDecimal("70000"), 20, new BigDecimal("10"));

        assertThat(output).contains(
                "PUBLIC_RULE_LOWER_BOUND_FAILS",
                "TOTAL_QUOTE_BELOW_OKX_PUBLIC_MIN_SIZE_LOWER_BOUND",
                "\"minimumTotalQuoteLowerBound\" : 12.00000");
    }

    private static ArrayNode minimumInvestment(ObjectMapper mapper, String quoteAmount) {
        ArrayNode data = mapper.createArrayNode();
        data.addObject().putArray("minInvestmentData")
                .addObject().put("amt", quoteAmount).put("ccy", "USDT");
        return data;
    }
}
