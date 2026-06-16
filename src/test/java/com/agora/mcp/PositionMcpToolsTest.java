package com.agora.mcp;

import com.agora.infra.notification.NotificationPort;
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
import java.time.LocalDateTime;
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
    void analyzeTrailingStopPnlReplayAlwaysDocumentsAmbiguousRowExclusion() {
        PositionMcpTools tools = tools();
        when(backtestTradeRepository.findReplayableRecentTrades(
                eq("BTCUSDT"), eq("1h"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        String output = tools.analyzeTrailingStopPnlReplay("BTCUSDT", "1h", 30, 10);

        assertThat(output).contains("boundary: READ_ONLY");
        assertThat(output).contains("acceptanceTarget: total trailing PnL improvement >= 5%");
        assertThat(output).contains("acceptanceNote=ambiguousSameBar rows are excluded from PnL acceptance totals");
        assertThat(output).contains("sampleStatus=NO_REPLAYABLE_TRADES");
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
                eq("BTCUSDT"), eq("1h"), eq("okx"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(bars);
        when(trailingStopReplayService.replayBacktestTrade(eq(ambiguous), eq(bars)))
                .thenReturn(replay(new BigDecimal("-10.00000000"), new BigDecimal("100.00000000"), true));
        when(trailingStopReplayService.replayBacktestTrade(eq(accepted), eq(bars)))
                .thenReturn(replay(new BigDecimal("-10.00000000"), new BigDecimal("-10.00000000"), false));

        String output = tools.analyzeTrailingStopPnlReplay("BTCUSDT", "1h", 30, 10);

        assertThat(output).contains("boundary: READ_ONLY");
        assertThat(output).contains("replayed=2 acceptanceRows=1");
        assertThat(output).contains("ambiguousSameBar=1");
        assertThat(output).contains("acceptanceOriginalNetPnl=-10.00000000");
        assertThat(output).contains("acceptanceTrailingNetPnl=-10.00000000");
        assertThat(output).contains("acceptanceDeltaPnl=0.00000000");
        assertThat(output).contains("acceptance=NOT_PROVEN");
        assertThat(output).contains("acceptanceNote=ambiguousSameBar rows are excluded");
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
