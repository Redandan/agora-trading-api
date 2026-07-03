package com.agora.service.tradingview;

import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.BtStrategyService;
import com.agora.service.backtest.BacktestEngine;
import com.agora.service.backtest.LiveSignalContext;
import com.agora.service.backtest.Strategy;
import com.agora.service.backtest.StrategyContext;
import com.agora.service.backtest.StrategyRegistry;
import com.agora.service.backtest.StrategySignal;
import com.agora.service.meta.DecisionAuditWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalTradingViewSignalEvaluatorTest {

    @AfterEach
    void clearContext() {
        LiveSignalContext.clear();
    }

    @Test
    void disabledEvaluatorDoesNotWriteAudit() {
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        LocalTradingViewSignalEvaluator evaluator = evaluator(false, auditWriter);

        evaluator.evaluate(kline(2));

        verify(auditWriter, never()).logSignalEval(any(), any(), any(), any(), any(), anyMap());
        verify(auditWriter, never()).logEntrySkip(any(), any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void enabledEvaluatorWritesDryRunAuditForEachTradingViewOrderIntent() {
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        LocalTradingViewSignalEvaluator evaluator = evaluator(true, auditWriter);

        evaluator.evaluate(kline(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter, times(3)).logSignalEval(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)), eq("BUY"), contextCaptor.capture());
        verify(auditWriter, times(3)).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)), eq("LocalTradingViewDryRun"),
                eq("Local TradingView parity dry-run; no order sent"), anyMap());

        assertThat(contextCaptor.getAllValues())
                .extracting(ctx -> ctx.get("orderReason"))
                .containsExactly(
                        "TRADINGVIEW_AI_BUY_SIGNAL",
                        "TRADINGVIEW_RELATIVE_LOW",
                        "TRADINGVIEW_POTENTIAL_LOW");
        assertThat(contextCaptor.getAllValues().get(0))
                .containsEntry("source", "LOCAL_TRADINGVIEW_PARITY")
                .containsEntry("signalSource", "LOCAL_TRADINGVIEW")
                .containsEntry("dryRun", true)
                .containsEntry("orderSent", false)
                .containsEntry("strategyDecision.tradingview_buy_signal", true);
    }

    private LocalTradingViewSignalEvaluator evaluator(boolean enabled, DecisionAuditWriter auditWriter) {
        TradingViewLocalSignalProperties props = new TradingViewLocalSignalProperties(
                enabled, 485L, "BTCUSDT", "1d", "", 10,
                new BigDecimal("10.0"), new BigDecimal("10.0"));
        BtStrategyService strategyService = mock(BtStrategyService.class);
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        BacktestEngine backtestEngine = mock(BacktestEngine.class);
        Strategy strategy = new Strategy() {
            @Override
            public String getType() {
                return "TEST_TV";
            }

            @Override
            public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
                LiveSignalContext.putDetail("tradingview_buy_signal", true);
                LiveSignalContext.addOrderIntent("TRADINGVIEW_AI_BUY_SIGNAL", "AI买点买入", 5000);
                LiveSignalContext.addOrderIntent("TRADINGVIEW_RELATIVE_LOW", "相对低点买入", 1000);
                LiveSignalContext.addOrderIntent("TRADINGVIEW_POTENTIAL_LOW", "潜在低点买入", 2000);
                return StrategySignal.BUY;
            }
        };

        BtStrategy btStrategy = new BtStrategy();
        btStrategy.setId(485L);
        btStrategy.setName("AI");
        btStrategy.setStrategyType("TEST_TV");
        btStrategy.setConfigJson("{}");
        when(strategyService.getRequired(485L)).thenReturn(btStrategy);
        when(strategyService.parseConfig("{}")).thenReturn(new HashMap<>());
        when(klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                eq("BTCUSDT"), eq("1d"), eq("okx"), any(Pageable.class)))
                .thenReturn(List.of(kline(2), kline(1), kline(0)));
        when(backtestEngine.buildIndicators(any(), anyMap())).thenReturn(new HashMap<>());

        return new LocalTradingViewSignalEvaluator(
                props,
                strategyService,
                new StrategyRegistry(List.of(strategy)),
                backtestEngine,
                klineRepository,
                auditWriter);
    }

    private MdKline kline(int offset) {
        MdKline kline = new MdKline();
        kline.setSymbol("BTCUSDT");
        kline.setIntervalCode("1d");
        kline.setSource("okx");
        kline.setOpenTime(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(offset));
        kline.setCloseTime(kline.getOpenTime().plusDays(1));
        kline.setOpenPrice(BigDecimal.valueOf(100 + offset));
        kline.setHighPrice(BigDecimal.valueOf(101 + offset));
        kline.setLowPrice(BigDecimal.valueOf(99 + offset));
        kline.setClosePrice(BigDecimal.valueOf(100 + offset));
        kline.setVolume(BigDecimal.valueOf(1000));
        return kline;
    }
}
