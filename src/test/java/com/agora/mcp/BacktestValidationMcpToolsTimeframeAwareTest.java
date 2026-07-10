package com.agora.mcp;

import com.agora.dto.backtest.BacktestResultResponse;
import com.agora.dto.backtest.BacktestRunRequest;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtBacktestResultRepository;
import com.agora.repository.trading.BtBacktestTradeRepository;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.BacktestService;
import com.agora.service.BtStrategyService;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.backtest.BacktestEngine;
import com.agora.service.backtest.BacktestTradeValidator;
import com.agora.service.backtest.StrategyRegistry;
import com.agora.service.backtest.TimeframeAwareStrategyValidationService;
import com.agora.service.backtest.TradingViewGoldenTruthVerifier;
import com.agora.service.backtest.TradingViewProfitOptimizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BacktestValidationMcpToolsTimeframeAwareTest {

    @Test
    void intradayToolUsesSkipPersistAndKeepsResultShadowOnly() {
        BacktestService backtestService = mock(BacktestService.class);
        BtStrategyService strategyService = mock(BtStrategyService.class);
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        BtStrategy strategy = new BtStrategy();
        strategy.setId(508L);
        strategy.setName("OI Funding Intraday");
        strategy.setStrategyType("OI_FUNDING_DIVERGENCE");
        strategy.setKlineSource("okx");
        strategy.setConfigJson("{}");
        when(strategyService.getRequired(508L)).thenReturn(strategy);
        when(strategyService.parseConfig("{}")).thenReturn(Map.of());

        LocalDateTime end = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime start = end.minusDays(100);
        List<MdKline> bars = hourlyBars(start, 100 * 24 + 1);
        when(klineRepository.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                eq("BTCUSDT"), eq("1h"), eq("okx"), any(), any())).thenReturn(bars);

        BacktestResultResponse backtest = new BacktestResultResponse();
        List<BacktestResultResponse.TradeRecordDto> trades = new ArrayList<>();
        for (int day = 85; day >= 5; day -= 2) {
            BacktestResultResponse.TradeRecordDto trade = new BacktestResultResponse.TradeRecordDto();
            LocalDateTime entryTime = end.minusDays(day);
            trade.setEntryTime(entryTime);
            trade.setEntryPrice(BigDecimal.valueOf(priceAt(start, entryTime)));
            trade.setEntryReason("INTRADAY_LONG");
            trade.setSide("LONG");
            trades.add(trade);
        }
        backtest.setTrades(trades);
        when(backtestService.runForExploration(any())).thenReturn(backtest);

        BacktestValidationMcpTools tools = new BacktestValidationMcpTools(
                backtestService,
                mock(AiStrategyDiscoveryService.class),
                mock(BacktestTradeValidator.class),
                mock(GeminiMarketHintRepository.class),
                strategyService,
                new ObjectMapper(),
                mock(BtBacktestResultRepository.class),
                mock(BtBacktestTradeRepository.class),
                mock(BtDecisionAuditRepository.class),
                klineRepository,
                mock(StrategyRegistry.class),
                mock(BacktestEngine.class),
                mock(TradingViewGoldenTruthVerifier.class),
                mock(TradingViewProfitOptimizationService.class),
                new TimeframeAwareStrategyValidationService());

        String report = tools.runTimeframeAwareStrategyValidation(
                508L, "BTCUSDT", "1h", 90, 90, null, null, 0.001);

        assertThat(report)
                .contains("boundary=READ_ONLY")
                .contains("profile=INTRADAY_1H_4H")
                .contains("status=NOT_APPLICABLE_NON_TRADINGVIEW")
                .contains("horizon=4h")
                .contains("horizon=24h")
                .contains("horizon=72h")
                .contains("livePromotionAllowed=false")
                .contains("notAuthorization=no order, OCO");

        ArgumentCaptor<BacktestRunRequest> requestCaptor = ArgumentCaptor.forClass(BacktestRunRequest.class);
        verify(backtestService).runForExploration(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getSource()).isEqualTo("okx");
        assertThat(requestCaptor.getValue().getSkipPersist()).isTrue();
    }

    private List<MdKline> hourlyBars(LocalDateTime start, int count) {
        List<MdKline> bars = new ArrayList<>();
        for (int hour = 0; hour < count; hour++) {
            double close = 100.0 + hour * 0.01;
            MdKline bar = new MdKline();
            bar.setSymbol("BTCUSDT");
            bar.setIntervalCode("1h");
            bar.setSource("okx");
            bar.setOpenTime(start.plusHours(hour));
            bar.setCloseTime(start.plusHours(hour + 1));
            bar.setOpenPrice(BigDecimal.valueOf(close - 0.01));
            bar.setHighPrice(BigDecimal.valueOf(close * 1.001));
            bar.setLowPrice(BigDecimal.valueOf(close * 0.999));
            bar.setClosePrice(BigDecimal.valueOf(close));
            bar.setVolume(BigDecimal.ONE);
            bars.add(bar);
        }
        return bars;
    }

    private double priceAt(LocalDateTime start, LocalDateTime time) {
        return 100.0 + ChronoUnit.HOURS.between(start, time) * 0.01;
    }
}
