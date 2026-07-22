package com.agora.mcp;

import com.agora.config.properties.TradingGridProperties;
import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.trading.CapitalAllocationPolicyPreviewService;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.TradeResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GridMcpToolsTest {

    @Test
    void deprecatedCustomCreateResumeAndAutoRebalanceExpansionAreBlocked() {
        BtGridRepository gridRepository = mock(BtGridRepository.class);
        GridMcpTools tools = new GridMcpTools(
                gridRepository,
                mock(BtGridLevelRepository.class),
                mock(BtLiveSignalRepository.class),
                mock(MdKlineRepository.class),
                mock(OkxTradingService.class),
                new TradingGridProperties(false, false, 24, 300000, true, new BigDecimal("5.0")),
                mock(CapitalAllocationPolicyPreviewService.class));

        assertThat(tools.createGrid("BTCUSDT", new BigDecimal("60000"), new BigDecimal("70000"),
                2, new BigDecimal("10"), new BigDecimal("0.03"), "SIDEWAYS"))
                .contains("BLOCKED_DEPRECATED_CUSTOM_GRID_CREATE_USE_OKX_NATIVE");
        assertThat(tools.resumeGrid(10L))
                .contains("BLOCKED_DEPRECATED_CUSTOM_GRID_RESUME_USE_OKX_NATIVE");
        assertThat(tools.enableGridAutoRebalance(10L, true, null, null, null))
                .contains("BLOCKED_DEPRECATED_CUSTOM_GRID_AUTO_REBALANCE_USE_OKX_NATIVE");
        verifyNoInteractions(gridRepository);
    }

    @Test
    void createGridUsesUpperPriceAsSellBoundaryNotBuyLevel() {
        BigDecimal lower = new BigDecimal("54067.59");
        BigDecimal upper = new BigDecimal("66082.61");

        List<BigDecimal> buyPrices = GridMcpTools.buildBuyLevelPrices(lower, upper, 2);
        BigDecimal step = GridMcpTools.calcGridStep(lower, upper, 2);

        assertThat(buyPrices)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("54067.59000000");
        assertThat(buyPrices).noneMatch(price -> price.compareTo(upper) == 0);
        assertThat(buyPrices.get(0).add(step)).isEqualByComparingTo(upper);
        assertThat(GridMcpTools.estimateCreateGridCapital(new BigDecimal("5"), 2))
                .isEqualByComparingTo("5");
    }

    @Test
    void createGridBuildsOneFewerBuyLevelThanPriceLines() {
        List<BigDecimal> buyPrices = GridMcpTools.buildBuyLevelPrices(
                new BigDecimal("100"), new BigDecimal("140"), 5);

        assertThat(buyPrices)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("100.00000000", "110.00000000", "120.00000000", "130.00000000");
        assertThat(buyPrices).noneMatch(price -> price.compareTo(new BigDecimal("140")) == 0);
        assertThat(GridMcpTools.estimateCreateGridCapital(new BigDecimal("10"), 5))
                .isEqualByComparingTo("40");
    }

    @Test
    void trendAdjustmentReviewDoesNotRecommendActionWithoutActiveGrid() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "NO_GRID", "UP_STRONG", new BigDecimal("4.2"), new BigDecimal("0.8"),
                null, null, 0, 72, 0, 0);

        assertThat(action).isEqualTo("WATCH");
    }

    @Test
    void trendAdjustmentReviewRequiresEnoughEvidence() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE", "UP", new BigDecimal("1.5"), new BigDecimal("0.5"),
                new BigDecimal("8"), new BigDecimal("50"), 0, 12, 0, 1);

        assertThat(action).isEqualTo("WATCH");
    }

    @Test
    void trendAdjustmentReviewPrioritizesMaterialFailures() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE", "SIDEWAYS", BigDecimal.ZERO, new BigDecimal("0.5"),
                new BigDecimal("8"), new BigDecimal("50"), 1, 72, 2, 5);

        assertThat(action).isEqualTo("PAUSE");
    }

    @Test
    void trendAdjustmentReviewFlagsStrongUpBreakoutAboveRange() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "OUT_ABOVE",
                "UP_STRONG", new BigDecimal("5.1"), new BigDecimal("0.8"),
                "UP", new BigDecimal("3.2"), new BigDecimal("1.2"),
                new BigDecimal("7"), new BigDecimal("120"),
                0, 72, 18, 2, 5, 60);

        assertThat(action).isEqualTo("REBUILD_REVIEW");
    }

    @Test
    void trendAdjustmentReviewWidensNarrowRangeInHighVolatility() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE", "SIDEWAYS", new BigDecimal("0.2"), new BigDecimal("1.30"),
                new BigDecimal("4"), new BigDecimal("50"), 0, 72, 2, 5);

        assertThat(action).isEqualTo("RESIZE_REVIEW");
    }

    @Test
    void trendAdjustmentReviewPausesConfirmedDirectionalBoundaryPressure() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE",
                "DOWN_STRONG", new BigDecimal("-3.5"), new BigDecimal("0.70"),
                "DOWN", new BigDecimal("-1.4"), new BigDecimal("1.10"),
                new BigDecimal("10"), new BigDecimal("8"),
                0, 72, 18, 2, 5, 70);

        assertThat(action).isEqualTo("PAUSE");
    }

    @Test
    void trendAdjustmentReviewWatchesMixedTimeframesInsteadOfRebuilding() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "OUT_ABOVE",
                "UP_STRONG", new BigDecimal("4.1"), new BigDecimal("0.80"),
                "DOWN", new BigDecimal("-1.2"), new BigDecimal("1.00"),
                new BigDecimal("10"), new BigDecimal("110"),
                0, 72, 18, 2, 5, 65);

        assertThat(action).isEqualTo("WATCH");
    }

    @Test
    void trendAlignmentRequiresFourHourConfirmation() {
        assertThat(GridMcpTools.trendAlignment("UP_STRONG", "UP", 18))
                .isEqualTo("UP_CONFIRMED");
        assertThat(GridMcpTools.trendAlignment("DOWN_STRONG", "SIDEWAYS", 18))
                .isEqualTo("DOWN_FORMING");
        assertThat(GridMcpTools.trendAlignment("UP_STRONG", "DOWN", 18))
                .isEqualTo("MIXED");
        assertThat(GridMcpTools.trendAlignment("UP_STRONG", "UP", 6))
                .isEqualTo("UP_UNCONFIRMED_4H");
    }

    @Test
    void closeGridClosesOnlyWhenEveryHoldingSellsFully() {
        Fixture fixture = closeGridFixture();
        TradeResult tradeResult = new TradeResult();
        tradeResult.setOrderId("ord-close-grid");
        tradeResult.setAvgPrice(new BigDecimal("110"));
        tradeResult.setQty(new BigDecimal("0.001"));
        when(fixture.okxTradingService.placeMarketSellWithFill(eq("BTCUSDT"),
                argThat(q -> q.compareTo(new BigDecimal("0.001")) == 0))).thenReturn(tradeResult);

        String output = fixture.tools.closeGrid(7L);

        assertThat(output).contains(
                "close_grid_status=CLOSED_NO_RESIDUAL",
                "grid_closed=true",
                "partial 0 / failed 0");
        assertThat(fixture.grid.getClosedAt()).isNotNull();
        assertThat(fixture.grid.getEnabled()).isFalse();
        assertThat(fixture.grid.getPausedAt()).isNull();
        assertThat(fixture.level46.getStatus()).isEqualTo("CLOSED");
        assertThat(fixture.level46.getSellOrderId()).isEqualTo("ord-close-grid");
        assertThat(fixture.level46.getRetryCount()).isZero();
        assertThat(fixture.grid.getClosedPairCount()).isEqualTo(1);
        assertThat(fixture.grid.getTotalRealizedPnl()).isEqualByComparingTo("0.01000000");
        verify(fixture.okxTradingService).placeMarketSellWithFill(eq("BTCUSDT"), any(BigDecimal.class));
    }

    @Test
    void closeGridDoesNotSetClosedAtWhenSellThrows() {
        Fixture fixture = closeGridFixture();
        when(fixture.okxTradingService.placeMarketSellWithFill(eq("BTCUSDT"), any(BigDecimal.class)))
                .thenThrow(new RuntimeException("okx timeout"));

        String output = fixture.tools.closeGrid(7L);

        assertThat(output).contains(
                "close_grid_status=BLOCKED_RESIDUAL_NOT_CLOSED",
                "grid_closed=false",
                "failed: 1");
        assertThat(fixture.grid.getClosedAt()).isNull();
        assertThat(fixture.grid.getEnabled()).isTrue();
        assertThat(fixture.grid.getPausedAt()).isNotNull();
        assertThat(fixture.grid.getPausedReason()).contains("closeGrid blocked");
        assertThat(fixture.level46.getStatus()).isEqualTo("SELLING_OKX");
        assertThat(fixture.level46.getIntentAt()).isNotNull();
        assertThat(fixture.level46.getErrorMessage()).contains("okx timeout");
    }

    @Test
    void closeGridFailsClosedBeforeSellWhenFeeAwareQuantityCannotBeProven() {
        Fixture fixture = closeGridFixture();
        when(fixture.okxTradingService.getGridRetirementQuantity(
                "BTCUSDT", "buy-order-7-46", new BigDecimal("0.001")))
                .thenThrow(new IllegalStateException("DB/provider gross quantity mismatch"));

        String output = fixture.tools.closeGrid(7L);

        assertThat(output).contains(
                "close_grid_status=BLOCKED_RESIDUAL_NOT_CLOSED",
                "grid_closed=false",
                "failed: 1");
        assertThat(fixture.level46.getStatus()).isEqualTo("HOLDING");
        assertThat(fixture.level46.getIntentAt()).isNull();
        assertThat(fixture.level46.getErrorMessage()).contains(
                "fee-aware quantity preflight blocked",
                "DB/provider gross quantity mismatch");
        verify(fixture.okxTradingService, never()).placeMarketSellWithFill(any(), any());
    }

    @Test
    void closeGridDoesNotSetClosedAtWhenSellPartiallyFills() {
        Fixture fixture = closeGridFixture();
        TradeResult tradeResult = new TradeResult();
        tradeResult.setOrderId("ord-partial-close-grid");
        tradeResult.setAvgPrice(new BigDecimal("110"));
        tradeResult.setQty(new BigDecimal("0.0004"));
        when(fixture.okxTradingService.placeMarketSellWithFill(eq("BTCUSDT"), any(BigDecimal.class)))
                .thenReturn(tradeResult);

        String output = fixture.tools.closeGrid(7L);

        assertThat(output).contains(
                "close_grid_status=BLOCKED_RESIDUAL_NOT_CLOSED",
                "grid_closed=false",
                "partial: 1");
        assertThat(fixture.grid.getClosedAt()).isNull();
        assertThat(fixture.grid.getEnabled()).isTrue();
        assertThat(fixture.grid.getPausedAt()).isNotNull();
        assertThat(fixture.level46.getStatus()).isEqualTo("SELL_PARTIAL");
        assertThat(fixture.level46.getFilledQty()).isEqualByComparingTo("0.0006");
        assertThat(fixture.level46.getSellOrderId()).isEqualTo("ord-partial-close-grid");
        assertThat(fixture.level46.getErrorMessage()).contains("closeGrid partial fill");
        assertThat(fixture.grid.getTotalRealizedPnl()).isEqualByComparingTo("0.00400000");
    }

    @Test
    void closedGridResidualCleanupDryRunRequiresExactConfirmBeforeSelling() {
        Fixture fixture = closedGridResidualFixture();

        String output = fixture.tools.cleanupClosedGridResidual(
                "BTCUSDT", 7L, "46,47", new BigDecimal("0.00012479"),
                new BigDecimal("15"), false, null);

        assertThat(output).contains(
                "closed_grid_residual_cleanup_status=READY_NOT_EXECUTED",
                "requiredConfirmText=EXECUTE_CLOSED_GRID_RESIDUAL_CLEANUP_BTCUSDT_GRID7_LEVELS46_47_QTY0.00012479",
                "order_attempted=false",
                "db_write_attempted=false",
                "telegram_send_allowed=false");
        verify(fixture.okxTradingService, never()).placeMarketSellWithFill(any(), any());
        verify(fixture.levelRepository, never()).save(any(BtGridLevel.class));
        verify(fixture.gridRepository, never()).save(any(BtGrid.class));
    }

    @Test
    void closedGridResidualCleanupBlocksConfirmMismatch() {
        Fixture fixture = closedGridResidualFixture();

        String output = fixture.tools.cleanupClosedGridResidual(
                "BTCUSDT", 7L, "46,47", new BigDecimal("0.00012479"),
                new BigDecimal("15"), true, "bad-confirm");

        assertThat(output).contains(
                "CONFIRM_TEXT_MISMATCH",
                "closed_grid_residual_cleanup_status=BLOCKED_PRECHECK",
                "order_attempted=false",
                "db_write_attempted=false");
        verify(fixture.okxTradingService, never()).placeMarketSellWithFill(any(), any());
        verify(fixture.levelRepository, never()).save(any(BtGridLevel.class));
    }

    @Test
    void closedGridResidualCleanupExecutesExactSellAndClosesRows() {
        Fixture fixture = closedGridResidualFixture();
        TradeResult tradeResult = new TradeResult();
        tradeResult.setOrderId("ord-closed-grid-residual");
        tradeResult.setAvgPrice(new BigDecimal("63266.7"));
        tradeResult.setQty(new BigDecimal("0.00012479"));
        when(fixture.okxTradingService.placeMarketSellWithFill(eq("BTCUSDT"),
                argThat(q -> q.compareTo(new BigDecimal("0.00012479")) == 0))).thenReturn(tradeResult);

        String output = fixture.tools.cleanupClosedGridResidual(
                "BTCUSDT", 7L, "46,47", new BigDecimal("0.00012479"),
                new BigDecimal("15"), true,
                "EXECUTE_CLOSED_GRID_RESIDUAL_CLEANUP_BTCUSDT_GRID7_LEVELS46_47_QTY0.00012479");

        assertThat(output).contains(
                "closed_grid_residual_cleanup_status=EXECUTED",
                "order_attempted=true",
                "db_write_attempted=true",
                "orderId=ord-closed-grid-residual",
                "soldQty=0.00012479");
        assertThat(fixture.level46.getStatus()).isEqualTo("CLOSED");
        assertThat(fixture.level47.getStatus()).isEqualTo("CLOSED");
        assertThat(fixture.level46.getFilledQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fixture.level47.getFilledQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fixture.level46.getSellOrderId()).isEqualTo("ord-closed-grid-residual");
        assertThat(fixture.level47.getSellOrderId()).isEqualTo("ord-closed-grid-residual");
        assertThat(fixture.level46.getClosedAt()).isNotNull();
        assertThat(fixture.level47.getClosedAt()).isNotNull();
        assertThat(fixture.grid.getClosedPairCount()).isEqualTo(2);
        assertThat(fixture.grid.getTotalRealizedPnl()).isNotZero();
        verify(fixture.levelRepository).save(fixture.level46);
        verify(fixture.levelRepository).save(fixture.level47);
        verify(fixture.gridRepository).save(fixture.grid);
    }

    private static Fixture closedGridResidualFixture() {
        BtGridRepository gridRepository = mock(BtGridRepository.class);
        BtGridLevelRepository levelRepository = mock(BtGridLevelRepository.class);
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        OkxTradingService okxTradingService = mock(OkxTradingService.class);
        GridMcpTools tools = new GridMcpTools(
                gridRepository,
                levelRepository,
                liveSignalRepository,
                klineRepository,
                okxTradingService,
                new TradingGridProperties(false, false, 24, 300000, true, new BigDecimal("5.0")),
                mock(CapitalAllocationPolicyPreviewService.class));

        BtGrid grid = new BtGrid();
        grid.setId(7L);
        grid.setSymbol("BTCUSDT");
        grid.setClosedAt(LocalDateTime.of(2026, 7, 4, 0, 0));
        grid.setTotalRealizedPnl(BigDecimal.ZERO);
        grid.setClosedPairCount(0);

        BtGridLevel level46 = closedResidualLevel(46L, new BigDecimal("0.00000165"), new BigDecimal("79504"), "SELL_FAILED");
        BtGridLevel level47 = closedResidualLevel(47L, new BigDecimal("0.00012314"), new BigDecimal("81206.3"), "HOLDING");

        when(gridRepository.findById(7L)).thenReturn(Optional.of(grid));
        when(levelRepository.findById(46L)).thenReturn(Optional.of(level46));
        when(levelRepository.findById(47L)).thenReturn(Optional.of(level47));
        when(okxTradingService.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("63266.7"));
        when(okxTradingService.getSpotHoldings()).thenReturn(List.of(
                new OkxTradingService.SpotHolding(
                        "BTC",
                        new BigDecimal("0.00024333292"),
                        new BigDecimal("0.00024333292"),
                        new BigDecimal("15.38"))));
        when(levelRepository.sumFilledQtyBySymbolForActiveGrids())
                .thenReturn(List.<Object[]>of(new Object[] {"BTCUSDT", new BigDecimal("0.00008096")}));
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of());
        return new Fixture(tools, gridRepository, levelRepository, okxTradingService, grid, level46, level47);
    }

    private static Fixture closeGridFixture() {
        BtGridRepository gridRepository = mock(BtGridRepository.class);
        BtGridLevelRepository levelRepository = mock(BtGridLevelRepository.class);
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        OkxTradingService okxTradingService = mock(OkxTradingService.class);
        GridMcpTools tools = new GridMcpTools(
                gridRepository,
                levelRepository,
                liveSignalRepository,
                klineRepository,
                okxTradingService,
                new TradingGridProperties(false, false, 24, 300000, true, new BigDecimal("5.0")),
                mock(CapitalAllocationPolicyPreviewService.class));

        BtGrid grid = new BtGrid();
        grid.setId(7L);
        grid.setSymbol("BTCUSDT");
        grid.setEnabled(true);
        grid.setTotalRealizedPnl(BigDecimal.ZERO);
        grid.setClosedPairCount(0);

        BtGridLevel level46 = closedResidualLevel(46L, new BigDecimal("0.001"), new BigDecimal("100"), "HOLDING");
        level46.setLevelIndex(2);
        level46.setRetryCount(1);
        level46.setPairedSellPrice(new BigDecimal("110"));
        level46.setBuyOrderId("buy-order-7-46");
        BtGridLevel level47 = closedResidualLevel(47L, BigDecimal.ZERO, new BigDecimal("100"), "PENDING");

        when(gridRepository.findById(7L)).thenReturn(Optional.of(grid));
        when(levelRepository.findByGridIdAndStatusIn(eq(7L), eq(List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL"))))
                .thenReturn(List.of(level46));
        when(okxTradingService.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("110"));
        when(okxTradingService.getGridRetirementQuantity(
                "BTCUSDT", "buy-order-7-46", new BigDecimal("0.001")))
                .thenReturn(new OkxTradingService.GridRetirementQuantity(
                        "BTC-USDT", "buy-order-7-46", new BigDecimal("0.001"),
                        new BigDecimal("0.001"), BigDecimal.ZERO, "BTC",
                        new BigDecimal("0.001"), new BigDecimal("0.001"),
                        BigDecimal.ZERO, new BigDecimal("0.00000001")));
        return new Fixture(tools, gridRepository, levelRepository, okxTradingService, grid, level46, level47);
    }

    private static BtGridLevel closedResidualLevel(Long id, BigDecimal qty, BigDecimal filledPrice, String status) {
        BtGridLevel level = new BtGridLevel();
        level.setId(id);
        level.setGridId(7L);
        level.setStatus(status);
        level.setFilledQty(qty);
        level.setFilledPrice(filledPrice);
        level.setRealizedPnl(BigDecimal.ZERO);
        level.setRetryCount(3);
        return level;
    }

    private record Fixture(
            GridMcpTools tools,
            BtGridRepository gridRepository,
            BtGridLevelRepository levelRepository,
            OkxTradingService okxTradingService,
            BtGrid grid,
            BtGridLevel level46,
            BtGridLevel level47) {
    }
}
