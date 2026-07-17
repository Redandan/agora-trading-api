package com.agora.service.backtest;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.TradingSignalSourceProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties.ExecutionMode;
import com.agora.event.KlineClosedEvent;
import com.agora.model.BtDecisionAudit;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.BtStrategyService;
import com.agora.service.TelegramService;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.trading.BtcDonchianShadowLaneService;
import com.agora.service.trading.TradeResult;
import com.agora.service.trading.TradingService;
import com.agora.service.trading.Strategy508TimeExitLaneService;
import com.agora.service.trading.TradingSignalSourcePolicy;
import com.agora.service.trading.VersionedProfitStartCohortService;
import com.agora.service.tradingview.LocalTradingViewExecutionService;
import com.agora.service.tradingview.LocalTradingViewSignalEvaluator;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KlineClosedEventListenerTest {

    @AfterEach
    void clearLiveSignalContext() {
        LiveSignalContext.clear();
    }

    @Test
    void tradingViewPrimarySkipsLegacyLiveEvaluator() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(signalSourceProps("TRADINGVIEW", false)),
                local,
                mock(Strategy508TimeExitLaneService.class),
                mock(BtcDonchianShadowLaneService.class));

        listener.onKlineClosed(new KlineClosedEvent(this, kline("BTCUSDT", "1D")));

        verify(evaluator, never()).evaluate("BTCUSDT", "1D");
        verify(local, never()).evaluate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void legacyPrimaryWithExplicitEnableRunsEvaluator() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(signalSourceProps("LEGACY", true)),
                local,
                mock(Strategy508TimeExitLaneService.class),
                mock(BtcDonchianShadowLaneService.class));

        listener.onKlineClosed(new KlineClosedEvent(this, kline("BTCUSDT", "1D")));

        verify(evaluator).evaluate("BTCUSDT", "1D");
        verify(local, never()).evaluate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void localTradingViewPrimaryRunsLocalParityEvaluatorOnly() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(signalSourceProps("LOCAL_TRADINGVIEW", false)),
                local,
                mock(Strategy508TimeExitLaneService.class),
                mock(BtcDonchianShadowLaneService.class));

        MdKline kline = kline("BTCUSDT", "1D");
        listener.onKlineClosed(new KlineClosedEvent(this, kline));

        verify(local).evaluate(kline);
        verify(evaluator, never()).evaluate("BTCUSDT", "1D");
    }

    @Test
    void localTradingViewPrimaryCanAlsoRunSecondaryLegacyEvaluatorAfterLocalParity() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(new TradingSignalSourceProperties(
                        "LOCAL_TRADINGVIEW", false, true, "508", new BigDecimal("10.0"))),
                local,
                mock(Strategy508TimeExitLaneService.class),
                mock(BtcDonchianShadowLaneService.class));

        MdKline kline = kline("BTCUSDT", "1h");
        listener.onKlineClosed(new KlineClosedEvent(this, kline));

        verify(local).evaluate(kline);
        verify(evaluator).evaluate("BTCUSDT", "1h");
    }

    @Test
    void localTradingViewPrimaryLiveMicroBuysAndAttachesOcoFromClosedKlineEvent() {
        LiveSignalEvaluator legacyEvaluator = mock(LiveSignalEvaluator.class);
        TradingSignalSourcePolicy policy = new TradingSignalSourcePolicy(
                signalSourceProps("LOCAL_TRADINGVIEW", false));
        TradingService tradingService = mock(TradingService.class);
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        BtDecisionAuditRepository decisionAuditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        TelegramService telegramService = mock(TelegramService.class);

        TradeResult buy = new TradeResult();
        buy.setOrderId("event-buy-1");
        buy.setAvgPrice(new BigDecimal("100.50"));
        buy.setQty(new BigDecimal("0.09950249"));
        when(tradingService.placeMarketBuy(eq("BTCUSDT"), eq(10.0))).thenReturn(buy);
        when(tradingService.placeOco(eq("BTCUSDT"), eq(new BigDecimal("0.09950249")),
                eq(new BigDecimal("103.52")), eq(new BigDecimal("88.44")))).thenReturn(777L);

        when(liveSignalRepository.countByStrategyIdAndAutoTradedIsTrueAndCreatedAtAfter(eq(485L), any()))
                .thenReturn(0L);
        when(liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(485L))
                .thenReturn(List.of());
        when(liveSignalRepository.existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull(
                eq(485L), eq("BTCUSDT"), eq("LONG"), eq("1d"))).thenReturn(false);
        when(liveSignalRepository.existsOpenAutoTradedPosition(
                eq(485L), eq("BTCUSDT"), eq("LONG"), eq("1d"))).thenReturn(false);
        when(liveSignalRepository.existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndNotifiedAtIsNotNull(
                eq(485L), eq("BTCUSDT"), eq("1d"), eq(LocalDateTime.of(2026, 7, 2, 0, 0)))).thenReturn(false);
        when(liveSignalRepository.save(any(BtLiveSignal.class))).thenAnswer(inv -> {
            BtLiveSignal signal = inv.getArgument(0);
            if (signal.getId() == null) {
                signal.setId(901L);
            }
            return signal;
        });
        AtomicLong auditIds = new AtomicLong(1200);
        when(decisionAuditRepository.save(any(BtDecisionAudit.class))).thenAnswer(inv -> {
            BtDecisionAudit audit = inv.getArgument(0);
            audit.setId(auditIds.incrementAndGet());
            return audit;
        });
        when(evidenceRepository.save(any(RuntimeDecisionEvidence.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalTradingViewExecutionService executionService = new LocalTradingViewExecutionService(
                localProps(ExecutionMode.LIVE_MICRO),
                auditWriter,
                liveSignalRepository,
                decisionAuditRepository,
                evidenceRepository,
                tradingService,
                okxProps(),
                policy,
                readyCohortService(),
                readyHardGateAssembler(),
                readyHardGateService(),
                readyActivationReadiness(),
                mock(com.agora.service.tradingview.PreSubmitEvidencePersistenceService.class),
                new ObjectMapper(),
                telegramService);
        LocalTradingViewSignalEvaluator localEvaluator = localEvaluator(
                localProps(ExecutionMode.LIVE_MICRO), auditWriter, executionService);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                legacyEvaluator, policy, localEvaluator, mock(Strategy508TimeExitLaneService.class),
                mock(BtcDonchianShadowLaneService.class));

        MdKline event = kline("BTCUSDT", "1D");
        event.setClosePrice(new BigDecimal("100.00"));
        listener.onKlineClosed(new KlineClosedEvent(this, event));

        verify(legacyEvaluator, never()).evaluate("BTCUSDT", "1D");
        verify(tradingService).placeMarketBuy("BTCUSDT", 10.0);
        verify(tradingService).placeOco("BTCUSDT", new BigDecimal("0.09950249"),
                new BigDecimal("103.52"), new BigDecimal("88.44"));

        ArgumentCaptor<BtLiveSignal> signalCaptor = ArgumentCaptor.forClass(BtLiveSignal.class);
        verify(liveSignalRepository, org.mockito.Mockito.atLeastOnce()).save(signalCaptor.capture());
        BtLiveSignal savedSignal = signalCaptor.getAllValues().get(signalCaptor.getAllValues().size() - 1);
        assertThat(savedSignal.getExchangeOrderId()).isEqualTo("LOCAL_TV:event-buy-1");
        assertThat(savedSignal.getOcoOrderListId()).isEqualTo(777L);
        assertThat(savedSignal.getBarOpenTime()).isEqualTo(LocalDateTime.of(2026, 7, 2, 0, 0));
        assertThat(savedSignal.getEntryPrice()).isEqualByComparingTo("100.50");
        assertThat(savedSignal.getSuggestedTp()).isEqualByComparingTo("103.52");
        assertThat(savedSignal.getSuggestedSl()).isEqualByComparingTo("88.44");

        ArgumentCaptor<RuntimeDecisionEvidence> evidenceCaptor = ArgumentCaptor.forClass(RuntimeDecisionEvidence.class);
        verify(evidenceRepository, org.mockito.Mockito.atLeastOnce()).save(evidenceCaptor.capture());
        RuntimeDecisionEvidence finalEvidence = evidenceCaptor.getAllValues().get(evidenceCaptor.getAllValues().size() - 1);
        assertThat(finalEvidence.getFinalOutcome()).isEqualTo("EXECUTED_OCO_ATTACHED");
        assertThat(finalEvidence.getOrderSent()).isTrue();
    }

    @Test
    void localTradingViewPrimaryDryRunFromClosedKlineProducesWouldExecuteWithoutOrder() {
        LiveSignalEvaluator legacyEvaluator = mock(LiveSignalEvaluator.class);
        TradingSignalSourcePolicy policy = new TradingSignalSourcePolicy(
                signalSourceProps("LOCAL_TRADINGVIEW", false));
        TradingService tradingService = mock(TradingService.class);
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        BtDecisionAuditRepository decisionAuditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        TelegramService telegramService = mock(TelegramService.class);

        LocalTradingViewExecutionService executionService = new LocalTradingViewExecutionService(
                localProps(ExecutionMode.DRY_RUN),
                auditWriter,
                liveSignalRepository,
                decisionAuditRepository,
                evidenceRepository,
                tradingService,
                okxProps(),
                policy,
                readyCohortService(),
                readyHardGateAssembler(),
                readyHardGateService(),
                readyActivationReadiness(),
                mock(com.agora.service.tradingview.PreSubmitEvidencePersistenceService.class),
                new ObjectMapper(),
                telegramService);
        LocalTradingViewSignalEvaluator localEvaluator = localEvaluator(
                localProps(ExecutionMode.DRY_RUN), auditWriter, executionService);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                legacyEvaluator, policy, localEvaluator, mock(Strategy508TimeExitLaneService.class),
                mock(BtcDonchianShadowLaneService.class));

        MdKline event = kline("BTCUSDT", "1D");
        event.setClosePrice(new BigDecimal("100.00"));
        listener.onKlineClosed(new KlineClosedEvent(this, event));

        verify(legacyEvaluator, never()).evaluate("BTCUSDT", "1D");
        verify(tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(tradingService, never()).placeOco(any(), any(), any(), any());
        verify(liveSignalRepository, never()).save(any(BtLiveSignal.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 7, 2, 0, 0)),
                eq("LocalTradingViewExecutionDryRun"),
                eq("Local TradingView parity execution dry-run; no order sent"),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("executionStatus", "WOULD_EXECUTE_DRY_RUN")
                .containsEntry("executionModeSetting", "DRY_RUN")
                .containsEntry("wouldExecute", true)
                .containsEntry("orderSent", false)
                .containsEntry("ocoAttached", false)
                .containsEntry("signalSource", "LOCAL_TRADINGVIEW");
    }

    @Test
    void oneMinuteBarsRemainIgnoredEvenWhenLegacyEnabled() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        Strategy508TimeExitLaneService timeExitLane = mock(Strategy508TimeExitLaneService.class);
        BtcDonchianShadowLaneService donchianLane = mock(BtcDonchianShadowLaneService.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(signalSourceProps("LEGACY", true)),
                local,
                timeExitLane,
                donchianLane);

        listener.onKlineClosed(new KlineClosedEvent(this, kline("BTCUSDT", "1m")));

        verify(evaluator, never()).evaluate("BTCUSDT", "1m");
        verify(local, never()).evaluate(org.mockito.ArgumentMatchers.any());
        verify(timeExitLane, never()).evaluate(org.mockito.ArgumentMatchers.any());
        verify(donchianLane, never()).evaluate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void strategy508TimeExitLaneReceivesFourHourEventWhenLegacyIsDisabled() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        Strategy508TimeExitLaneService timeExitLane = mock(Strategy508TimeExitLaneService.class);
        BtcDonchianShadowLaneService donchianLane = mock(BtcDonchianShadowLaneService.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(signalSourceProps("TRADINGVIEW", false)),
                local,
                timeExitLane,
                donchianLane);

        MdKline event = kline("BTCUSDT", "4h");
        listener.onKlineClosed(new KlineClosedEvent(this, event));

        verify(timeExitLane).evaluate(event);
        verify(donchianLane).evaluate(event);
        verify(evaluator, never()).evaluate("BTCUSDT", "4h");
    }

    @Test
    void btcDonchianLaneReceivesOneHourEventWhenLegacyIsDisabled() {
        LiveSignalEvaluator evaluator = mock(LiveSignalEvaluator.class);
        LocalTradingViewSignalEvaluator local = mock(LocalTradingViewSignalEvaluator.class);
        Strategy508TimeExitLaneService timeExitLane = mock(Strategy508TimeExitLaneService.class);
        BtcDonchianShadowLaneService donchianLane = mock(BtcDonchianShadowLaneService.class);
        KlineClosedEventListener listener = new KlineClosedEventListener(
                evaluator,
                new TradingSignalSourcePolicy(signalSourceProps("TRADINGVIEW", false)),
                local,
                timeExitLane,
                donchianLane);

        MdKline event = kline("BTCUSDT", "1h");
        listener.onKlineClosed(new KlineClosedEvent(this, event));

        verify(donchianLane).evaluate(event);
        verify(timeExitLane).evaluate(event);
        verify(evaluator, never()).evaluate("BTCUSDT", "1h");
    }

    private MdKline kline(String symbol, String intervalCode) {
        MdKline kline = new MdKline();
        kline.setSymbol(symbol);
        kline.setIntervalCode(intervalCode);
        kline.setOpenTime(LocalDateTime.of(2026, 7, 2, 0, 0));
        kline.setSource("okx");
        kline.setClosePrice(new BigDecimal("100.00"));
        return kline;
    }

    private TradingSignalSourceProperties signalSourceProps(String primary, boolean legacyLiveEvaluatorEnabled) {
        return new TradingSignalSourceProperties(primary, legacyLiveEvaluatorEnabled, false, "", BigDecimal.ZERO);
    }

    private LocalTradingViewSignalEvaluator localEvaluator(TradingViewLocalSignalProperties props,
                                                           DecisionAuditWriter auditWriter,
                                                           LocalTradingViewExecutionService executionService) {
        BtStrategyService strategyService = mock(BtStrategyService.class);
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        BacktestEngine backtestEngine = mock(BacktestEngine.class);
        Strategy strategy = new Strategy() {
            @Override
            public String getType() {
                return "LOCAL_TV_TEST";
            }

            @Override
            public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
                LiveSignalContext.putDetail("tradingview_buy_signal", true);
                LiveSignalContext.addOrderIntent("TRADINGVIEW_RELATIVE_LOW", "相对低点买入", 1000);
                return StrategySignal.BUY;
            }
        };

        BtStrategy btStrategy = new BtStrategy();
        btStrategy.setId(485L);
        btStrategy.setName("ScoreBuy TradingView parity");
        btStrategy.setStrategyType("LOCAL_TV_TEST");
        btStrategy.setConfigJson("{}");
        when(strategyService.getRequired(485L)).thenReturn(btStrategy);
        when(strategyService.parseConfig("{}")).thenReturn(new HashMap<>());
        MdKline event = kline("BTCUSDT", "1D");
        MdKline previous = kline("BTCUSDT", "1D");
        previous.setOpenTime(LocalDateTime.of(2026, 7, 1, 0, 0));
        previous.setClosePrice(new BigDecimal("99.00"));
        when(klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                eq("BTCUSDT"), eq("1d"), eq("okx"), any(Pageable.class)))
                .thenReturn(List.of(event, previous));
        when(backtestEngine.buildIndicators(any(), anyMap())).thenReturn(new HashMap<>());

        return new LocalTradingViewSignalEvaluator(
                props,
                strategyService,
                new StrategyRegistry(List.of(strategy)),
                backtestEngine,
                klineRepository,
                auditWriter,
                executionService);
    }

    private TradingViewLocalSignalProperties localProps(ExecutionMode mode) {
        return new TradingViewLocalSignalProperties(
                true, 485L, "BTCUSDT", "1d", "okx", 320, 1, 0,
                new BigDecimal("10.0"), new BigDecimal("10.0"),
                mode, false, true, false, 3, 1, 1,
                new BigDecimal("0.0300"), new BigDecimal("0.1200"),
                new BigDecimal("250.0"));
    }

    private OkxTradingProperties okxProps() {
        OkxTradingProperties okx = new OkxTradingProperties();
        okx.setEnabled(true);
        okx.setApiKey("key");
        okx.setSecretKey("secret");
        okx.setPassphrase("passphrase");
        return okx;
    }

    private VersionedProfitStartCohortService readyCohortService() {
        VersionedProfitStartCohortService service = mock(VersionedProfitStartCohortService.class);
        VersionedProfitStartCohortService.Snapshot snapshot =
                mock(VersionedProfitStartCohortService.Snapshot.class);
        when(service.snapshot()).thenReturn(snapshot);
        when(service.liveExecutionBlocker(eq(snapshot),
                org.mockito.ArgumentMatchers.anyLong(), any(String.class), any(String.class)))
                .thenReturn(null);
        when(service.currentCohortMarker(snapshot)).thenReturn("|COHORT:TEST-COHORT");
        return service;
    }

    private com.agora.service.trading.VersionedProfitStartHardGateInputAssembler readyHardGateAssembler() {
        var assembler = mock(com.agora.service.trading.VersionedProfitStartHardGateInputAssembler.class);
        when(assembler.assemble(any(), any(Map.class))).thenReturn(
                mock(com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.Inputs.class));
        return assembler;
    }

    private com.agora.service.trading.VersionedProfitStartHardGateSnapshotService readyHardGateService() {
        var service = mock(com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.class);
        var snapshot = mock(com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.Snapshot.class);
        when(snapshot.decision()).thenReturn(
                com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.Decision.READY);
        when(snapshot.snapshotId()).thenReturn("TEST-SNAPSHOT");
        when(snapshot.sha256()).thenReturn("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(snapshot.reasons()).thenReturn(List.of());
        when(service.evaluate(any())).thenReturn(snapshot);
        when(service.verifyAtOrderBoundary(eq(snapshot), any(), any())).thenReturn(
                new com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.BoundaryDecision(
                        true, snapshot, List.of()));
        return service;
    }

    private com.agora.service.trading.VersionedProfitStartActivationReadinessService readyActivationReadiness() {
        var service = mock(com.agora.service.trading.VersionedProfitStartActivationReadinessService.class);
        when(service.assess(any(), any())).thenReturn(
                new com.agora.service.trading.VersionedProfitStartActivationReadinessService.Readiness(
                        "TEST_READY", true, true, true, true, false,
                        com.agora.service.trading.CurrentCohortCanonicalMetricReader.Classification.NOT_MEASURABLE,
                        0, 0, 0, List.of()));
        return service;
    }
}
