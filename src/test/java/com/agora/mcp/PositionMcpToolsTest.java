package com.agora.mcp;

import com.agora.infra.notification.NotificationPort;
import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.BtBacktestResult;
import com.agora.model.BtBacktestTrade;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtBacktestTradeRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.trading.OcoManagementService;
import com.agora.service.trading.OkxEarnService;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.TrailingStopReplayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PositionMcpToolsTest {

    private final BtBacktestTradeRepository backtestTradeRepository = mock(BtBacktestTradeRepository.class);
    private final BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
    private final MdKlineRepository mdKlineRepository = mock(MdKlineRepository.class);
    private final TrailingStopReplayService trailingStopReplayService = mock(TrailingStopReplayService.class);

    @Test
    void analyzeTrailingStopPnlReplayKeepsOpsAuthAndReadOnlyAcceptanceCategories() throws Exception {
        Method method = PositionMcpTools.class.getDeclaredMethod(
                "analyzeTrailingStopPnlReplay",
                String.class,
                String.class,
                Integer.class,
                Integer.class,
                String.class);

        McpAuth auth = method.getAnnotation(McpAuth.class);
        McpCategory category = method.getAnnotation(McpCategory.class);

        assertThat(auth).isNotNull();
        assertThat(auth.value()).isEqualTo(McpAuthLevel.OPS);
        assertThat(category).isNotNull();
        assertThat(Arrays.asList(category.value()))
                .containsExactlyInAnyOrder(Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS);
    }

    @Test
    void analyzeTrailingStopParameterSweepKeepsOpsAuthAndReadOnlyCategory() throws Exception {
        Method method = PositionMcpTools.class.getDeclaredMethod(
                "analyzeTrailingStopParameterSweep",
                String.class,
                String.class,
                Integer.class,
                Integer.class,
                String.class,
                Integer.class,
                String.class,
                String.class,
                String.class);

        McpAuth auth = method.getAnnotation(McpAuth.class);
        McpCategory category = method.getAnnotation(McpCategory.class);

        assertThat(auth).isNotNull();
        assertThat(auth.value()).isEqualTo(McpAuthLevel.OPS);
        assertThat(category).isNotNull();
        assertThat(Arrays.asList(category.value()))
                .containsExactlyInAnyOrder(Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS);
    }

    @Test
    void analyzeTrailingStopPnlReplayAlwaysDocumentsAmbiguousRowExclusion() {
        PositionMcpTools tools = tools();
        when(backtestTradeRepository.findReplayableRecentTrades(
                eq("BTCUSDT"), eq("1h"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        String output = tools.analyzeTrailingStopPnlReplay("BTCUSDT", "1h", 30, 10, null);

        assertThat(output).contains("boundary: READ_ONLY");
        assertThat(output).contains("backtestInterval: 1h");
        assertThat(output).contains("replayInterval: 1m");
        assertThat(output).contains("replayIntervalNote=backtest interval selects normalized trades");
        assertThat(output).contains("acceptanceTarget: total trailing PnL improvement >= 5%");
        assertThat(output).contains("acceptanceNote=ambiguousSameBar rows are excluded from PnL acceptance totals");
        assertThat(output).contains("sampleStatus=NO_REPLAYABLE_TRADES");
        assertThat(output).contains("acceptanceBlocker=NO_REPLAYABLE_TRADES");
        assertThat(output).contains("no normalized backtest trades matched the requested symbol/interval/window");
    }

    @Test
    void analyzeTrailingStopPnlReplayExcludesAmbiguousRowsFromAcceptanceTotals() {
        PositionMcpTools tools = tools();

        BtBacktestTrade ambiguous = trade(1L);
        BtBacktestTrade accepted = trade(2L);
        List<MdKline> bars = List.of(new MdKline());

        when(backtestTradeRepository.findReplayableRecentTrades(
                eq("BTCUSDT"), eq("1h"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(ambiguous, accepted));
        when(mdKlineRepository.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                eq("BTCUSDT"), eq("1m"), eq("okx"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(bars);
        when(trailingStopReplayService.replayBacktestTrade(eq(ambiguous), eq(bars)))
                .thenReturn(replay(new BigDecimal("-10.00000000"), new BigDecimal("100.00000000"), true));
        when(trailingStopReplayService.replayBacktestTrade(eq(accepted), eq(bars)))
                .thenReturn(replay(new BigDecimal("-10.00000000"), new BigDecimal("-10.00000000"), false));

        String output = tools.analyzeTrailingStopPnlReplay("BTCUSDT", "1h", 30, 10, null);

        assertThat(output).contains("boundary: READ_ONLY");
        assertThat(output).contains("backtestInterval: 1h");
        assertThat(output).contains("replayInterval: 1m");
        assertThat(output).contains("replayed=2 acceptanceRows=1");
        assertThat(output).contains("ambiguousSameBar=1");
        assertThat(output).contains("acceptanceOriginalNetPnl=-10.00000000");
        assertThat(output).contains("acceptanceTrailingNetPnl=-10.00000000");
        assertThat(output).contains("acceptanceDeltaPnl=0.00000000");
        assertThat(output).contains("acceptance=NOT_PROVEN");
        assertThat(output).contains("acceptanceBlocker=CURRENT_PARAMETERS_NO_PNL_IMPROVEMENT");
        assertThat(output).contains("current +0.5/+1.0 ATR overlay did not improve accepted rows");
        assertThat(output).contains("acceptanceNote=ambiguousSameBar rows are excluded");
    }

    @Test
    void analyzeTrailingStopPnlReplayExplainsAllAmbiguousRowsAsNotProven() {
        PositionMcpTools tools = tools();

        BtBacktestTrade first = trade(1L);
        BtBacktestTrade second = trade(2L);
        List<MdKline> bars = List.of(new MdKline());

        when(backtestTradeRepository.findReplayableRecentTrades(
                eq("BTCUSDT"), eq("1h"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(mdKlineRepository.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                eq("BTCUSDT"), eq("1m"), eq("okx"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(bars);
        when(trailingStopReplayService.replayBacktestTrade(eq(first), eq(bars)))
                .thenReturn(replay(new BigDecimal("-10.00000000"), new BigDecimal("100.00000000"), true));
        when(trailingStopReplayService.replayBacktestTrade(eq(second), eq(bars)))
                .thenReturn(replay(new BigDecimal("-10.00000000"), new BigDecimal("100.00000000"), true));

        String output = tools.analyzeTrailingStopPnlReplay("BTCUSDT", "1h", 30, 10, null);

        assertThat(output).contains("replayed=2 acceptanceRows=0");
        assertThat(output).contains("ambiguousSameBar=2");
        assertThat(output).contains("acceptance=NOT_PROVEN");
        assertThat(output).contains("acceptanceBlocker=ALL_REPLAYED_ROWS_AMBIGUOUS");
        assertThat(output).contains("OHLC bars cannot prove trigger/stop ordering");
    }

    @Test
    void analyzeTrailingStopPnlReplaySeparatesBacktestAndReplayIntervals() {
        PositionMcpTools tools = tools();

        BtBacktestTrade trade = trade(1L);
        List<MdKline> bars = List.of(new MdKline());

        when(backtestTradeRepository.findReplayableRecentTrades(
                eq("BTCUSDT"), eq("1h"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(trade));
        when(mdKlineRepository.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                eq("BTCUSDT"), eq("5m"), eq("okx"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(bars);
        when(trailingStopReplayService.replayBacktestTrade(eq(trade), eq(bars)))
                .thenReturn(replay(new BigDecimal("-10.00000000"), new BigDecimal("100.00000000"), false));

        String output = tools.analyzeTrailingStopPnlReplay("BTCUSDT", "1h", 30, 10, "5m");

        assertThat(output).contains("backtestInterval: 1h");
        assertThat(output).contains("replayInterval: 5m");
        assertThat(output).contains("acceptance=PASS");
    }

    @Test
    void analyzeTrailingStopParameterSweepReportsCurrentAndBestReadOnlyCandidate() {
        PositionMcpTools tools = tools();

        BtBacktestTrade trade = trade(1L);
        List<MdKline> bars = List.of(new MdKline());

        when(backtestTradeRepository.findReplayableRecentTrades(
                eq("BTCUSDT"), eq("1h"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(trade));
        when(mdKlineRepository.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                eq("BTCUSDT"), eq("1m"), eq("okx"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(bars);
        when(trailingStopReplayService.replayBacktestTrade(
                eq(trade),
                eq(bars),
                any(TrailingStopReplayService.ReplayPolicy.class)))
                .thenAnswer(invocation -> {
                    TrailingStopReplayService.ReplayPolicy policy = invocation.getArgument(2);
                    if (policy.trailingDistanceAtrMult().compareTo(new BigDecimal("0.5")) == 0) {
                        return replay(new BigDecimal("-10.00000000"), new BigDecimal("5.00000000"), false);
                    }
                    return replay(new BigDecimal("-10.00000000"), new BigDecimal("-20.00000000"), false);
                });

        String output = tools.analyzeTrailingStopParameterSweep(
                "BTCUSDT", "1h", 30, 10, "1m", 3,
                "0.5", "1.0", "0.5,1.0");

        assertThat(output).contains("boundary: READ_ONLY");
        assertThat(output).contains("currentPolicy=breakevenAtr=0.5 trailingTriggerAtr=1.0 trailingDistanceAtr=1.0");
        assertThat(output).contains("sampleStatus=REPLAYED");
        assertThat(output).contains("candidatesTested=2");
        assertThat(output).contains("currentPolicySummary=policy=be=0.5,trigger=1,distance=1 currentPolicy=true");
        assertThat(output).contains("bestPolicySummary=policy=be=0.5,trigger=1,distance=0.5 currentPolicy=false acceptance=PASS");
        assertThat(output).contains("bestVsCurrentDeltaPnl=25.00000000");
        assertThat(output).contains("operatorAction: REVIEW_PARAMETER_CANDIDATE_NOT_LIVE");
    }

    @Test
    void analyzeSpotAntiWickPolicyCoverageFlagsSmallTpWideDisasterSlAsymmetry() {
        PositionMcpTools tools = tools();
        BtStrategy strategy = new BtStrategy();
        strategy.setId(485L);
        strategy.setName("BTC score-buy");
        strategy.setStrategyType("SCORE_BUY");
        strategy.setSymbols("BTCUSDT");
        strategy.setConfigJson("""
                {
                  "notifyOnly": true,
                  "spotWickAwareExitEnabled": true,
                  "spotWickAwareSlMode": "ULTRA_LOW_DISASTER",
                  "spotWickAwareDisasterSlPct": 0.12,
                  "fixedTakeProfitPct": 0.03
                }
                """);
        when(strategyRepository.findByEnabled(Boolean.TRUE)).thenReturn(List.of(strategy));

        String output = tools.analyzeSpotAntiWickPolicyCoverage("BTCUSDT");

        assertThat(output).contains("boundary: READ_ONLY");
        assertThat(output).contains("#485 BTC score-buy status=SHADOW_COVERED");
        assertThat(output).contains("asymmetryFlag=TP_SL_ASYMMETRY");
        assertThat(output).contains("plannedTpPct=3.00%");
        assertThat(output).contains("effectiveSlPct=12.00%");
        assertThat(output).contains("tpSlAsymmetry=1");
        assertThat(output).contains("Operator action: HOLD_WITH_SIZE_CAPS");
    }

    private BtBacktestTrade trade(long id) {
        BtBacktestResult backtest = new BtBacktestResult();
        backtest.setId(100L + id);
        backtest.setKlineSource("okx");

        BtBacktestTrade trade = new BtBacktestTrade();
        trade.setId(id);
        trade.setBacktest(backtest);
        trade.setEntryTime(LocalDateTime.parse("2026-06-01T00:00:00").plusHours(id));
        trade.setExitTime(trade.getEntryTime().plusHours(1));
        return trade;
    }

    private TrailingStopReplayService.ReplayResult replay(BigDecimal original, BigDecimal trailing,
                                                          boolean ambiguousSameBar) {
        BigDecimal delta = trailing.subtract(original);
        return new TrailingStopReplayService.ReplayResult(
                true,
                true,
                ambiguousSameBar,
                "TRAILING_STOP",
                original,
                trailing,
                delta,
                delta.divide(original.abs(), 6, java.math.RoundingMode.HALF_UP),
                LocalDateTime.parse("2026-06-01T01:00:00"),
                new BigDecimal("100"),
                "TRAILING",
                3,
                null);
    }

    private PositionMcpTools tools() {
        return new PositionMcpTools(
                mock(BtLiveSignalRepository.class),
                backtestTradeRepository,
                strategyRepository,
                mdKlineRepository,
                mock(OkxTradingService.class),
                mock(OcoManagementService.class),
                mock(com.agora.service.trading.OcoOutcomeAnalysisService.class),
                mock(com.agora.service.trading.PriceScenarioSimulationService.class),
                mock(com.agora.service.trading.OpportunityScannerService.class),
                mock(OkxEarnService.class),
                mock(NotificationPort.class),
                new ObjectMapper(),
                mock(DecisionAuditWriter.class),
                trailingStopReplayService);
    }
}
