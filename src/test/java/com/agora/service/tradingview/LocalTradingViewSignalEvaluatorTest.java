package com.agora.service.tradingview;

import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties.ExecutionMode;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.BtStrategyService;
import com.agora.service.backtest.BacktestEngine;
import com.agora.service.backtest.LiveSignalContext;
import com.agora.service.backtest.Strategy;
import com.agora.service.backtest.StrategyContext;
import com.agora.service.backtest.StrategyRegistry;
import com.agora.service.backtest.StrategySignal;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.trading.TradingService;
import com.agora.service.trading.TradingSignalSourcePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        LocalTradingViewSignalEvaluator evaluator = evaluator(true, false, auditWriter);

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
                .containsEntry("executionEnabled", false)
                .containsEntry("strategyDecision.tradingview_buy_signal", true);
    }

    @Test
    void enabledEvaluatorWritesNoBuyAuditWhenTradingViewHasNoOrderIntent() {
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        Strategy strategy = new Strategy() {
            @Override
            public String getType() {
                return "TEST_TV";
            }

            @Override
            public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
                LiveSignalContext.putDetail("tradingview_buy_signal", false);
                LiveSignalContext.putDetail("trend_filter", "WAIT");
                return StrategySignal.HOLD;
            }
        };
        LocalTradingViewSignalEvaluator evaluator = evaluator(true, false, 1, auditWriter, strategy);

        evaluator.evaluate(kline(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).logSignalEval(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)), eq("HOLD"), contextCaptor.capture());
        verify(auditWriter, never()).logEntrySkip(any(), any(), any(), any(), any(), any(), anyMap());

        assertThat(contextCaptor.getValue())
                .containsEntry("source", "LOCAL_TRADINGVIEW_PARITY")
                .containsEntry("signalSource", "LOCAL_TRADINGVIEW")
                .containsEntry("action", "WAIT")
                .containsEntry("selectedAction", "WAIT")
                .containsEntry("decision", "LOCAL_TRADINGVIEW_NO_BUY")
                .containsEntry("currentSignalDecision", "HOLD")
                .containsEntry("currentSignalSource", "LOCAL_TRADINGVIEW")
                .containsEntry("noBuyReason", "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE")
                .containsEntry("noCurrentBuyCandidateReason", "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE")
                .containsEntry("intentCreated", false)
                .containsEntry("orderSent", false)
                .containsEntry("suppressionReason", "LOCAL_TRADINGVIEW_NO_BUY")
                .containsEntry("executionMode", "LOCAL_TRADINGVIEW_PARITY_EVALUATION")
                .containsEntry("strategyDecision.tradingview_buy_signal", false)
                .containsEntry("strategyDecision.trend_filter", "WAIT");
    }

    @Test
    void buySignalWithoutOrderIntentIsAuditedAsWaitNotBuyCandidate() {
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        Strategy strategy = new Strategy() {
            @Override
            public String getType() {
                return "TEST_TV";
            }

            @Override
            public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
                LiveSignalContext.putDetail("tradingview_buy_signal", true);
                return StrategySignal.BUY;
            }
        };
        LocalTradingViewSignalEvaluator evaluator = evaluator(true, false, 1, auditWriter, strategy);

        evaluator.evaluate(kline(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).logSignalEval(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)), eq("HOLD"), contextCaptor.capture());
        verify(auditWriter, never()).logEntrySkip(any(), any(), any(), any(), any(), any(), anyMap());

        assertThat(contextCaptor.getValue())
                .containsEntry("side", "HOLD")
                .containsEntry("selectedAction", "WAIT")
                .containsEntry("currentSignalDecision", "BUY")
                .containsEntry("noBuyReason", "LOCAL_TRADINGVIEW_BUY_WITHOUT_ORDER_INTENT")
                .containsEntry("intentCreated", false)
                .containsEntry("orderSent", false)
                .containsEntry("strategyDecision.tradingview_buy_signal", true);
    }

    @Test
    void executionEnabledAddsOneDedicatedDryRunReceiptPerBarAndKeepsOtherIntentsShadowOnly() {
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        LocalTradingViewSignalEvaluator evaluator = evaluator(true, true, auditWriter);

        evaluator.evaluate(kline(2));

        ArgumentCaptor<String> blockerCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter, times(4)).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)), blockerCaptor.capture(),
                any(), contextCaptor.capture());

        assertThat(blockerCaptor.getAllValues())
                .contains("LocalTradingViewDryRun")
                .contains("LocalTradingViewExecutionDryRun");
        assertThat(contextCaptor.getAllValues())
                .anySatisfy(ctx -> assertThat(ctx)
                        .containsEntry("executionMode", "LOCAL_TRADINGVIEW_PARITY_EXECUTION")
                        .containsEntry("executionEnabled", true)
                        .containsEntry("executionDryRun", true)
                        .containsEntry("executionLiveOrderEnabled", false)
                        .containsEntry("executionStatus", "WOULD_EXECUTE_DRY_RUN")
                        .containsEntry("executionSelection", "LATEST_ELIGIBLE_FIRST_INTENT")
                        .containsEntry("wouldExecute", true)
                        .containsEntry("orderSent", false));

        assertThat(contextCaptor.getAllValues())
                .filteredOn(ctx -> "LocalTradingViewDryRun".equals(ctx.get("blocker"))
                        || ctx.containsKey("orderIntentIndex"))
                .anySatisfy(ctx -> assertThat(ctx)
                        .containsEntry("executionSelection", "SHADOW_ONLY_ADDITIONAL_INTENT")
                        .containsEntry("liveExecutionSelected", false));
    }

    @Test
    void catchUpEvaluatesRecentClosedBarsSoMissedCloseEventsDoNotDropBuyIntents() {
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        LocalTradingViewSignalEvaluator evaluator = evaluator(true, false, 2, auditWriter);

        evaluator.evaluate(kline(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter, times(6)).logSignalEval(eq(485L), eq("BTCUSDT"), eq("1d"),
                any(), eq("BUY"), contextCaptor.capture());

        assertThat(contextCaptor.getAllValues())
                .extracting(ctx -> ctx.get("barTime"))
                .containsExactly(
                        "2026-01-02T00:00",
                        "2026-01-02T00:00",
                        "2026-01-02T00:00",
                        "2026-01-03T00:00",
                        "2026-01-03T00:00",
                        "2026-01-03T00:00");
        assertThat(contextCaptor.getAllValues().get(0))
                .containsEntry("catchUpEvaluation", true)
                .containsEntry("triggerBarTime", "2026-01-03T00:00")
                .containsEntry("catchUpBars", 2)
                .containsEntry("orderIntentIndex", 1);
        assertThat(contextCaptor.getAllValues().get(3))
                .containsEntry("catchUpEvaluation", false)
                .containsEntry("orderIntentIndex", 1);
    }

    @Test
    void catchUpExecutesOnlyNewestEligibleBarsFirstIntent() {
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        LocalTradingViewSignalEvaluator evaluator = evaluator(true, true, 2, auditWriter);

        evaluator.evaluate(kline(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter, times(7)).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"),
                any(), any(), any(), contextCaptor.capture());

        List<Map<String, Object>> selected = contextCaptor.getAllValues().stream()
                .filter(ctx -> Boolean.TRUE.equals(ctx.get("liveExecutionSelected")))
                .toList();
        assertThat(selected).hasSize(2);
        assertThat(selected)
                .allSatisfy(ctx -> assertThat(ctx)
                        .containsEntry("barTime", "2026-01-03T00:00")
                        .containsEntry("orderIntentIndex", 1)
                        .containsEntry("executionSelection", "LATEST_ELIGIBLE_FIRST_INTENT"));
        assertThat(contextCaptor.getAllValues())
                .anySatisfy(ctx -> assertThat(ctx)
                        .containsEntry("barTime", "2026-01-02T00:00")
                        .containsEntry("executionSelection", "SHADOW_ONLY_CATCH_UP_INTENT")
                        .containsEntry("liveExecutionSelected", false));
    }

    @Test
    void catchUpContinuesWhenOlderBarEvaluationFails() {
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        Strategy strategy = new Strategy() {
            @Override
            public String getType() {
                return "TEST_TV";
            }

            @Override
            public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
                if (context.getCurrent().getOpenTime().equals(LocalDateTime.of(2026, 1, 2, 0, 0))) {
                    throw new IllegalStateException("previous bar failed");
                }
                LiveSignalContext.putDetail("tradingview_buy_signal", true);
                LiveSignalContext.addOrderIntent("TRADINGVIEW_AI_BUY_SIGNAL", "AI买点买入", 5000);
                return StrategySignal.BUY;
            }
        };
        LocalTradingViewSignalEvaluator evaluator = evaluator(true, false, 2, auditWriter, strategy);

        evaluator.evaluate(kline(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter, times(1)).logSignalEval(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)), eq("BUY"), contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("catchUpEvaluation", false)
                .containsEntry("orderIntentIndex", 1)
                .containsEntry("orderReason", "TRADINGVIEW_AI_BUY_SIGNAL");
    }

    private LocalTradingViewSignalEvaluator evaluator(boolean enabled, DecisionAuditWriter auditWriter) {
        return evaluator(enabled, false, auditWriter);
    }

    private LocalTradingViewSignalEvaluator evaluator(boolean enabled, boolean executionEnabled, DecisionAuditWriter auditWriter) {
        return evaluator(enabled, executionEnabled, 1, auditWriter);
    }

    private LocalTradingViewSignalEvaluator evaluator(boolean enabled, boolean executionEnabled, int catchUpBars, DecisionAuditWriter auditWriter) {
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
        return evaluator(enabled, executionEnabled, catchUpBars, auditWriter, strategy);
    }

    private LocalTradingViewSignalEvaluator evaluator(boolean enabled, boolean executionEnabled, int catchUpBars,
                                                     DecisionAuditWriter auditWriter, Strategy strategy) {
        ExecutionMode executionMode = executionEnabled ? ExecutionMode.DRY_RUN : ExecutionMode.LEGACY;
        TradingViewLocalSignalProperties props = new TradingViewLocalSignalProperties(
                enabled, 485L, "BTCUSDT", "1d", "", 10, catchUpBars, 0,
                new BigDecimal("10.0"), new BigDecimal("10.0"),
                executionMode,
                executionEnabled, true, false, 3, 1, 1,
                new BigDecimal("0.0300"), new BigDecimal("0.1200"),
                new BigDecimal("250.0"));
        BtStrategyService strategyService = mock(BtStrategyService.class);
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        BacktestEngine backtestEngine = mock(BacktestEngine.class);

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
                auditWriter,
                executionService(props, auditWriter));
    }

    private LocalTradingViewExecutionService executionService(TradingViewLocalSignalProperties props,
                                                              DecisionAuditWriter auditWriter) {
        TradingSignalSourcePolicy signalSourcePolicy = mock(TradingSignalSourcePolicy.class);
        when(signalSourcePolicy.primary()).thenReturn("LOCAL_TRADINGVIEW");
        return new LocalTradingViewExecutionService(
                props,
                auditWriter,
                mock(BtLiveSignalRepository.class),
                mock(BtDecisionAuditRepository.class),
                mock(RuntimeDecisionEvidenceRepository.class),
                mock(TradingService.class),
                new com.agora.config.OkxTradingProperties(),
                signalSourcePolicy,
                new ObjectMapper(),
                mock(com.agora.service.TelegramService.class));
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
