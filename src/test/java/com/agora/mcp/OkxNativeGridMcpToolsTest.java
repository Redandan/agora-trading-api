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
import java.time.Instant;
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

        ObjectNode buyFillPage = fillPage(mapper, "123456789", "1001", "t1", "900", "buy",
                "60000", "0.0001", "-0.0000001", "BTC");
        ObjectNode sellFillPage = fillPage(mapper, "123456789", "1002", "t2", "800", "sell",
                "61000", "0.0000999", "-0.00060939", "USDT");
        ObjectNode emptyFillPage = mapper.createObjectNode().put("code", "0");
        emptyFillPage.putArray("data");
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", "1001", 100, null))
                .thenReturn(buyFillPage);
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", "1001", 100, "900"))
                .thenReturn(emptyFillPage);
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", "1002", 100, null))
                .thenReturn(sellFillPage);
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", "1002", 100, "800"))
                .thenReturn(emptyFillPage);
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
                "\"fillHistoryPageCount\" : 4",
                "\"fillHistoryTotalCount\" : 2",
                "\"orderDetailSingleFillFallbackCount\" : 0",
                "\"fillHistoryCoverageComplete\" : true",
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
    void acceptanceEvidenceUsesOrderDetailOnlyWhenItProvesOneCompleteFill() {
        OkxTradingService okx = mock(OkxTradingService.class);
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode history = mapper.createArrayNode();
        history.addObject().put("algoId", "123456789").put("instId", "BTC-USDT").put("state", "stopped");
        when(okx.getNativeSpotGridOrders(false)).thenReturn(mapper.createArrayNode());
        when(okx.getNativeSpotGridOrders(true)).thenReturn(history);
        when(okx.getNativeSpotGridOrderDetails("123456789")).thenReturn(history);
        ArrayNode filled = mapper.createArrayNode();
        filled.addObject().put("algoId", "123456789").put("ordId", "1001")
                .put("groupId", "pair-1").put("side", "buy");
        filled.addObject().put("algoId", "123456789").put("ordId", "1002")
                .put("groupId", "pair-1").put("side", "sell");
        when(okx.getNativeSpotGridSubOrders("123456789", "filled")).thenReturn(filled);
        when(okx.getNativeSpotGridSubOrders("123456789", "live")).thenReturn(mapper.createArrayNode());
        ObjectNode empty = mapper.createObjectNode().put("code", "0");
        empty.putArray("data");
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", "1001", 100, null)).thenReturn(empty);
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", "1002", 100, null)).thenReturn(empty);
        when(okx.getSpotOrderDetail("BTC-USDT", "1001")).thenReturn(singleFillOrder(
                mapper, "1001", "t1", "buy", "60000", "0.0001", "-0.0000001", "BTC"));
        when(okx.getSpotOrderDetail("BTC-USDT", "1002")).thenReturn(singleFillOrder(
                mapper, "1002", "t2", "sell", "61000", "0.0000999", "-0.00060939", "USDT"));
        when(okx.getSpotInstrumentRules("BTC-USDT")).thenReturn(
                new OkxTradingService.SpotInstrumentRules("BTC-USDT", new BigDecimal("0.00001"),
                        new BigDecimal("0.00000001"), new BigDecimal("0.1")));

        String output = new OkxNativeGridMcpTools(okx, mapper, mock(BtGridRepository.class),
                mock(BtGridLevelRepository.class), mock(OkxNativeGridExecutionService.class))
                .getOkxNativeSpotGridAcceptanceEvidence("123456789");

        assertThat(output).contains(
                "\"fillHistoryPageCount\" : 2",
                "\"fillHistoryTotalCount\" : 2",
                "\"orderDetailSingleFillFallbackCount\" : 2",
                "\"evidenceSource\" : \"ORDER_DETAIL_PROVEN_SINGLE_FILL\"",
                "\"fillHistoryCoverageComplete\" : true",
                "\"signedFeeCoverageComplete\" : true",
                "\"exactNetPnlProven\" : true",
                "\"exactNetPnlUsdt\" : 0.09329061");
    }

    @Test
    void acceptanceEvidenceRejectsOrderDetailWhenLatestFillIsNotTheWholeOrder() {
        OkxTradingService okx = mock(OkxTradingService.class);
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode history = mapper.createArrayNode();
        history.addObject().put("algoId", "123456789").put("instId", "BTC-USDT").put("state", "stopped");
        when(okx.getNativeSpotGridOrders(false)).thenReturn(mapper.createArrayNode());
        when(okx.getNativeSpotGridOrders(true)).thenReturn(history);
        when(okx.getNativeSpotGridOrderDetails("123456789")).thenReturn(history);
        ArrayNode filled = mapper.createArrayNode();
        filled.addObject().put("algoId", "123456789").put("ordId", "1001")
                .put("groupId", "pair-1").put("side", "buy");
        when(okx.getNativeSpotGridSubOrders("123456789", "filled")).thenReturn(filled);
        when(okx.getNativeSpotGridSubOrders("123456789", "live")).thenReturn(mapper.createArrayNode());
        ObjectNode empty = mapper.createObjectNode().put("code", "0");
        empty.putArray("data");
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", "1001", 100, null)).thenReturn(empty);
        ObjectNode multiFill = singleFillOrder(
                mapper, "1001", "t1", "buy", "60000", "0.0001", "-0.0000001", "BTC");
        multiFill.put("accFillSz", "0.0002");
        when(okx.getSpotOrderDetail("BTC-USDT", "1001")).thenReturn(multiFill);
        when(okx.getSpotInstrumentRules("BTC-USDT")).thenReturn(
                new OkxTradingService.SpotInstrumentRules("BTC-USDT", new BigDecimal("0.00001"),
                        new BigDecimal("0.00000001"), new BigDecimal("0.1")));

        String output = new OkxNativeGridMcpTools(okx, mapper, mock(BtGridRepository.class),
                mock(BtGridLevelRepository.class), mock(OkxNativeGridExecutionService.class))
                .getOkxNativeSpotGridAcceptanceEvidence("123456789");

        assertThat(output).contains(
                "\"orderDetailSingleFillFallbackCount\" : 0",
                "\"fillHistoryCoverageComplete\" : false",
                "FILL_HISTORY_PAGINATION_INCOMPLETE",
                "\"exactNetPnlProven\" : false");
    }

    @Test
    void acceptanceEvidenceFailsClosedWhenOrderFillCursorDoesNotAdvance() {
        OkxTradingService okx = mock(OkxTradingService.class);
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode history = mapper.createArrayNode();
        history.addObject().put("algoId", "123456789").put("instId", "BTC-USDT").put("state", "stopped");
        when(okx.getNativeSpotGridOrders(false)).thenReturn(mapper.createArrayNode());
        when(okx.getNativeSpotGridOrders(true)).thenReturn(history);
        when(okx.getNativeSpotGridOrderDetails("123456789")).thenReturn(history);
        ArrayNode filled = mapper.createArrayNode();
        filled.addObject().put("algoId", "123456789").put("ordId", "1001")
                .put("groupId", "pair-1").put("side", "buy");
        filled.addObject().put("algoId", "123456789").put("ordId", "1002")
                .put("groupId", "pair-1").put("side", "sell");
        when(okx.getNativeSpotGridSubOrders("123456789", "filled")).thenReturn(filled);
        when(okx.getNativeSpotGridSubOrders("123456789", "live")).thenReturn(mapper.createArrayNode());

        ObjectNode buyPage = fillPage(mapper, "123456789", "1001", "t1", "900", "buy",
                "60000", "0.0001", "-0.0000001", "BTC");
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", "1001", 100, null)).thenReturn(buyPage);
        when(okx.getFillHistoryPage("SPOT", "BTC-USDT", "1001", 100, "900")).thenReturn(buyPage);
        when(okx.getSpotInstrumentRules("BTC-USDT")).thenReturn(
                new OkxTradingService.SpotInstrumentRules("BTC-USDT", new BigDecimal("0.00001"),
                        new BigDecimal("0.00000001"), new BigDecimal("0.1")));

        String output = new OkxNativeGridMcpTools(okx, mapper, mock(BtGridRepository.class),
                mock(BtGridLevelRepository.class), mock(OkxNativeGridExecutionService.class))
                .getOkxNativeSpotGridAcceptanceEvidence("123456789");

        assertThat(output).contains(
                "\"fillHistoryCoverageComplete\" : false",
                "FILL_HISTORY_PAGINATION_INCOMPLETE",
                "\"exactNetPnlProven\" : false")
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

    @Test
    void functionalSafetyEvidencePassesOnlyAsAReadOnlyGateAComponent() {
        OkxTradingService okx = mock(OkxTradingService.class);
        BtGridRepository grids = mock(BtGridRepository.class);
        BtGridLevelRepository levels = mock(BtGridLevelRepository.class);
        ObjectMapper mapper = new ObjectMapper();

        BtGrid closedGrid = new BtGrid();
        closedGrid.setId(10L);
        closedGrid.setSymbol("BTCUSDT");
        closedGrid.setClosedAt(LocalDateTime.parse("2026-07-22T09:00:00"));
        BtGridLevel historical = new BtGridLevel();
        historical.setId(70L);
        historical.setGridId(10L);
        historical.setStatus("CLOSED");
        historical.setBuyOrderId("legacy-buy");
        historical.setSellOrderId("legacy-sell");
        historical.setFilledAt(LocalDateTime.parse("2026-07-22T08:00:00"));
        historical.setClosedAt(LocalDateTime.parse("2026-07-22T08:05:00"));
        when(grids.findAll()).thenReturn(List.of(closedGrid));
        when(levels.findAll()).thenReturn(List.of(historical));
        when(okx.getNativeSpotGridOrders(false)).thenReturn(mapper.createArrayNode());
        ArrayNode history = mapper.createArrayNode();
        history.addObject().put("algoId", "123456789").put("algoClOrdId", "AGOKXG120260722")
                .put("instId", "BTC-USDT")
                .put("cTime", Long.toString(Instant.parse("2026-07-22T10:01:00Z").toEpochMilli()));
        when(okx.getNativeSpotGridOrders(true)).thenReturn(history);
        when(okx.getFreshSpotHoldings()).thenReturn(List.of(
                new OkxTradingService.SpotHolding("USDT", new BigDecimal("477"),
                        new BigDecimal("477"), new BigDecimal("477"))));

        String output = new OkxNativeGridMcpTools(okx, mapper, grids, levels,
                mock(OkxNativeGridExecutionService.class))
                .getOkxNativeSpotGridFunctionalSafetyEvidence(
                        "123456789", "AGOKXG120260722", "2026-07-22T10:00:00Z");

        assertThat(output).contains(
                "READ_ONLY_GATE_A_SAFETY_EVIDENCE_NO_MUTATION",
                "\"openLegacyGridCount\" : 0",
                "\"legacyInventoryOrInFlightLevelCount\" : 0",
                "\"customGridOrderActivityCountSinceWindowStart\" : 0",
                "\"nativeBtcUsdtBotCountCreatedSinceWindowStart\" : 1",
                "\"targetProviderIdentityCount\" : 1",
                "\"safetyEvidencePass\" : true",
                "PASS_GATE_A_SAFETY_COMPONENT_ONLY",
                "\"overallFunctionalAcceptance\" : \"NOT_YET_PROVEN_BY_THIS_COMPONENT\"",
                "\"orderSent\" : false",
                "\"databaseMutation\" : false");
    }

    @Test
    void functionalSafetyEvidenceFailsClosedOnLegacyActivityAndDuplicateNativeBots() {
        OkxTradingService okx = mock(OkxTradingService.class);
        BtGridRepository grids = mock(BtGridRepository.class);
        BtGridLevelRepository levels = mock(BtGridLevelRepository.class);
        ObjectMapper mapper = new ObjectMapper();

        BtGrid openGrid = new BtGrid();
        openGrid.setId(10L);
        openGrid.setSymbol("BTCUSDT");
        BtGridLevel holding = new BtGridLevel();
        holding.setId(70L);
        holding.setGridId(10L);
        holding.setStatus("HOLDING");
        holding.setBuyOrderId("late-custom-buy");
        holding.setFilledAt(LocalDateTime.parse("2026-07-22T10:02:00"));
        when(grids.findAll()).thenReturn(List.of(openGrid));
        when(levels.findAll()).thenReturn(List.of(holding));
        ArrayNode active = mapper.createArrayNode();
        active.addObject().put("algoId", "123456789").put("algoClOrdId", "AGOKXG120260722")
                .put("instId", "BTC-USDT")
                .put("cTime", Long.toString(Instant.parse("2026-07-22T10:01:00Z").toEpochMilli()));
        active.addObject().put("algoId", "987654321").put("algoClOrdId", "AGOKXG1DUPLICATE")
                .put("instId", "BTC-USDT")
                .put("cTime", Long.toString(Instant.parse("2026-07-22T10:03:00Z").toEpochMilli()));
        when(okx.getNativeSpotGridOrders(false)).thenReturn(active);
        when(okx.getNativeSpotGridOrders(true)).thenReturn(mapper.createArrayNode());
        when(okx.getFreshSpotHoldings()).thenReturn(List.of(
                new OkxTradingService.SpotHolding("USDT", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN)));

        String output = new OkxNativeGridMcpTools(okx, mapper, grids, levels,
                mock(OkxNativeGridExecutionService.class))
                .getOkxNativeSpotGridFunctionalSafetyEvidence(
                        "123456789", "AGOKXG120260722", "2026-07-22T10:00:00Z");

        assertThat(output).contains(
                "OPEN_LEGACY_GRIDS_REMAIN",
                "LEGACY_INVENTORY_OR_IN_FLIGHT_REMAINS",
                "CUSTOM_GRID_ORDER_ACTIVITY_IN_ACCEPTANCE_WINDOW",
                "MULTIPLE_ACTIVE_NATIVE_BOTS",
                "EXACTLY_ONE_NATIVE_BTC_USDT_BOT_MUST_BE_CREATED_IN_WINDOW",
                "\"safetyEvidencePass\" : false",
                "FAIL_GATE_A_SAFETY_COMPONENT");
    }

    private static ArrayNode minimumInvestment(ObjectMapper mapper, String quoteAmount) {
        ArrayNode data = mapper.createArrayNode();
        data.addObject().putArray("minInvestmentData")
                .addObject().put("amt", quoteAmount).put("ccy", "USDT");
        return data;
    }

    private static ObjectNode fillPage(ObjectMapper mapper, String algoId, String orderId,
                                       String tradeId, String billId, String side, String price,
                                       String quantity, String fee, String feeCurrency) {
        ObjectNode page = mapper.createObjectNode().put("code", "0");
        page.putArray("data").addObject()
                .put("algoId", algoId)
                .put("ordId", orderId)
                .put("tradeId", tradeId)
                .put("billId", billId)
                .put("side", side)
                .put("fillPx", price)
                .put("fillSz", quantity)
                .put("fee", fee)
                .put("feeCcy", feeCurrency);
        return page;
    }

    private static ObjectNode singleFillOrder(ObjectMapper mapper, String orderId, String tradeId,
                                              String side, String price, String quantity,
                                              String fee, String feeCurrency) {
        return mapper.createObjectNode()
                .put("ordId", orderId)
                .put("tradeId", tradeId)
                .put("state", "filled")
                .put("side", side)
                .put("fillPx", price)
                .put("avgPx", price)
                .put("fillSz", quantity)
                .put("accFillSz", quantity)
                .put("fillTime", "1784779297919")
                .put("fee", fee)
                .put("feeCcy", feeCurrency);
    }
}
