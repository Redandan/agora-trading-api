package com.agora.service.tradingview;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties.ExecutionMode;
import com.agora.model.BtDecisionAudit;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.TelegramService;
import com.agora.service.backtest.LiveSignalContext;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.trading.TradeResult;
import com.agora.service.trading.TradingService;
import com.agora.service.trading.TradingSignalSourcePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class LocalTradingViewExecutionServiceTest {

    @Test
    void dryRunWritesExecutionReceiptWithoutOrder() {
        Fixture fixture = fixture(props(ExecutionMode.DRY_RUN));

        fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.auditWriter).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)),
                eq("LocalTradingViewExecutionDryRun"),
                eq("Local TradingView parity execution dry-run; no order sent"),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("executionStatus", "WOULD_EXECUTE_DRY_RUN")
                .containsEntry("executionModeSetting", "DRY_RUN")
                .containsEntry("wouldExecute", true)
                .containsEntry("orderSent", false)
                .containsEntry("ocoAttached", false)
                .containsEntry("executionWouldUseScoreBuySchedulers", false);
        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.liveSignalRepository, never()).save(any());
    }

    @Test
    void btcBaseDryRunWritesDedicatedReceiptWithoutOcoOrder() {
        Fixture fixture = fixture(props(ExecutionMode.BTC_BASE_DRY_RUN));

        fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.auditWriter).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)),
                eq("LocalTradingViewBtcBaseDryRun"),
                eq("Local TradingView BTC_BASE dry-run; buy point would accumulate BTC base without OCO order"),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("executionStatus", "WOULD_EXECUTE_DRY_RUN")
                .containsEntry("executionModeSetting", "BTC_BASE_DRY_RUN")
                .containsEntry("executionStrategy", "BTC_BASE")
                .containsEntry("btcBaseMode", true)
                .containsEntry("btcBaseOcoRequired", false)
                .containsEntry("btcBaseShadowOnly", true)
                .containsEntry("btcBaseBuyPointSource", "TradingViewParityOrderIntent")
                .containsEntry("wouldExecute", true)
                .containsEntry("orderSent", false)
                .containsEntry("ocoAttached", false);
        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.tradingService, never()).placeOco(any(), any(), any(), any());
        verify(fixture.liveSignalRepository, never()).save(any());
    }

    @Test
    void liveEnabledPlacesMarketBuyAttachesOcoAndWritesEvidence() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        TradeResult buy = new TradeResult();
        buy.setOrderId("ord-123");
        buy.setAvgPrice(new BigDecimal("100.25"));
        buy.setQty(new BigDecimal("0.09975062"));
        when(fixture.tradingService.placeMarketBuy(eq("BTCUSDT"), eq(10.0))).thenReturn(buy);
        when(fixture.tradingService.placeOco(eq("BTCUSDT"), eq(new BigDecimal("0.09975062")),
                eq(new BigDecimal("103.26")), eq(new BigDecimal("88.22")))).thenReturn(999L);

        fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        verify(fixture.tradingService).placeMarketBuy("BTCUSDT", 10.0);
        verify(fixture.tradingService).placeOco("BTCUSDT", new BigDecimal("0.09975062"),
                new BigDecimal("103.26"), new BigDecimal("88.22"));

        ArgumentCaptor<BtLiveSignal> signalCaptor = ArgumentCaptor.forClass(BtLiveSignal.class);
        verify(fixture.liveSignalRepository, org.mockito.Mockito.atLeastOnce()).save(signalCaptor.capture());
        BtLiveSignal savedSignal = signalCaptor.getAllValues().get(signalCaptor.getAllValues().size() - 1);
        assertThat(savedSignal.getStrategyId()).isEqualTo(485L);
        assertThat(savedSignal.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(savedSignal.getIntervalCode()).isEqualTo("1d");
        assertThat(savedSignal.getBarOpenTime()).isEqualTo(LocalDateTime.of(2026, 1, 3, 0, 0));
        assertThat(savedSignal.getExchangeOrderId()).isEqualTo("LOCAL_TV:ord-123");
        assertThat(savedSignal.getOcoOrderListId()).isEqualTo(999L);
        assertThat(savedSignal.getEntryPrice()).isEqualByComparingTo("100.25");
        assertThat(savedSignal.getSuggestedTp()).isEqualByComparingTo("103.26");
        assertThat(savedSignal.getSuggestedSl()).isEqualByComparingTo("88.22");
        assertThat(savedSignal.getFilterReason()).isEqualTo("LOCAL_TRADINGVIEW_PARITY:1:TRADINGVIEW_RELATIVE_LOW");

        ArgumentCaptor<BtDecisionAudit> auditCaptor = ArgumentCaptor.forClass(BtDecisionAudit.class);
        verify(fixture.decisionAuditRepository, org.mockito.Mockito.atLeast(2)).save(auditCaptor.capture());
        BtDecisionAudit finalAudit = auditCaptor.getAllValues().get(auditCaptor.getAllValues().size() - 1);
        assertThat(finalAudit.getEventType()).isEqualTo("LOCAL_TV_EXECUTION");
        assertThat(finalAudit.getOutcome()).isEqualTo("PASS");
        assertThat(finalAudit.getLiveSignalId()).isEqualTo(77L);

        ArgumentCaptor<RuntimeDecisionEvidence> evidenceCaptor = ArgumentCaptor.forClass(RuntimeDecisionEvidence.class);
        verify(fixture.evidenceRepository).save(evidenceCaptor.capture());
        RuntimeDecisionEvidence evidence = evidenceCaptor.getValue();
        assertThat(evidence.getSignalSource()).isEqualTo("LOCAL_TRADINGVIEW");
        assertThat(evidence.getSelectedAction()).isEqualTo("LOCAL_TRADINGVIEW_EXECUTE");
        assertThat(evidence.getFinalOutcome()).isEqualTo("EXECUTED_OCO_ATTACHED");
        assertThat(evidence.getOrderSent()).isTrue();
        assertThat(evidence.getExecutionMode()).isEqualTo("LOCAL_TV_PARITY_EXEC");
        assertThat(evidence.getOcoOrderListId()).isEqualTo("999");
    }

    @Test
    void ocoAttachFailureAfterBuySendsCriticalAlertAndWritesUnprotectedEvidence() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        TradeResult buy = new TradeResult();
        buy.setOrderId("ord-unprotected");
        buy.setAvgPrice(new BigDecimal("100.25"));
        buy.setQty(new BigDecimal("0.09975062"));
        when(fixture.tradingService.placeMarketBuy(eq("BTCUSDT"), eq(10.0))).thenReturn(buy);
        when(fixture.tradingService.placeOco(eq("BTCUSDT"), eq(new BigDecimal("0.09975062")),
                eq(new BigDecimal("103.26")), eq(new BigDecimal("88.22"))))
                .thenThrow(new RuntimeException("oco-down"));

        fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        verify(fixture.tradingService).placeMarketBuy("BTCUSDT", 10.0);
        verify(fixture.tradingService).placeOco("BTCUSDT", new BigDecimal("0.09975062"),
                new BigDecimal("103.26"), new BigDecimal("88.22"));
        verify(fixture.telegramService).sendAlert(
                org.mockito.ArgumentMatchers.contains("CRITICAL_UNPROTECTED_LOCAL_TRADINGVIEW"),
                eq(false),
                eq("LocalTradingViewExecution"),
                eq("CRITICAL"));

        ArgumentCaptor<RuntimeDecisionEvidence> evidenceCaptor = ArgumentCaptor.forClass(RuntimeDecisionEvidence.class);
        verify(fixture.evidenceRepository).save(evidenceCaptor.capture());
        RuntimeDecisionEvidence evidence = evidenceCaptor.getValue();
        assertThat(evidence.getFinalOutcome()).isEqualTo("CRITICAL_UNPROTECTED_LOCAL_TRADINGVIEW");
        assertThat(evidence.getOrderSent()).isTrue();
        assertThat(evidence.getOcoOrderListId()).isNull();
    }

    @Test
    void criticalAlertFailureDoesNotRollbackUnprotectedEvidence() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        TradeResult buy = new TradeResult();
        buy.setOrderId("ord-alert-down");
        buy.setAvgPrice(new BigDecimal("100.25"));
        buy.setQty(new BigDecimal("0.09975062"));
        when(fixture.tradingService.placeMarketBuy(eq("BTCUSDT"), eq(10.0))).thenReturn(buy);
        when(fixture.tradingService.placeOco(eq("BTCUSDT"), eq(new BigDecimal("0.09975062")),
                eq(new BigDecimal("103.26")), eq(new BigDecimal("88.22"))))
                .thenThrow(new RuntimeException("oco-down"));
        doThrow(new RuntimeException("telegram-down")).when(fixture.telegramService)
                .sendAlert(any(), eq(false), eq("LocalTradingViewExecution"), eq("CRITICAL"));

        assertThatCode(() -> fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1))
                .doesNotThrowAnyException();

        ArgumentCaptor<RuntimeDecisionEvidence> evidenceCaptor = ArgumentCaptor.forClass(RuntimeDecisionEvidence.class);
        verify(fixture.evidenceRepository).save(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue().getFinalOutcome())
                .isEqualTo("CRITICAL_UNPROTECTED_LOCAL_TRADINGVIEW");
        assertThat(evidenceCaptor.getValue().getOrderSent()).isTrue();
    }

    @Test
    void staleSignalAgeBlocksLiveOrderBeforeMarketBuy() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO, 1));
        MdKline staleKline = kline();
        staleKline.setCloseTime(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));

        fixture.service.preview(strategy(), staleKline, "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.auditWriter).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)),
                eq("LocalTradingViewSignalStale"),
                eq("Local TradingView signal is older than the configured max signal age; no order sent"),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("executionStatus", "BLOCKED_HARD_GATE")
                .containsEntry("executionBlocker", "LocalTradingViewSignalStale")
                .containsEntry("executionSignalAgeCheckEnabled", true)
                .containsEntry("executionMaxSignalAgeHours", 1L)
                .containsEntry("executionSignalStale", true)
                .containsEntry("orderSent", false)
                .containsEntry("ocoAttached", false);
        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.liveSignalRepository, never()).save(any());
    }

    @Test
    void liveEnabledIgnoresNonAutoTradedOpenSignalWhenCheckingOpenPositionGate() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        TradeResult buy = new TradeResult();
        buy.setOrderId("ord-after-shadow");
        buy.setAvgPrice(new BigDecimal("100.25"));
        buy.setQty(new BigDecimal("0.09975062"));
        when(fixture.tradingService.placeMarketBuy(eq("BTCUSDT"), eq(10.0))).thenReturn(buy);
        when(fixture.tradingService.placeOco(eq("BTCUSDT"), eq(new BigDecimal("0.09975062")),
                eq(new BigDecimal("103.26")), eq(new BigDecimal("88.22")))).thenReturn(1001L);
        when(fixture.liveSignalRepository.existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull(
                eq(485L), eq("BTCUSDT"), eq("LONG"), eq("1d"))).thenReturn(true);
        when(fixture.liveSignalRepository.existsOpenAutoTradedPosition(
                eq(485L), eq("BTCUSDT"), eq("LONG"), eq("1d"))).thenReturn(false);

        fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        verify(fixture.tradingService).placeMarketBuy("BTCUSDT", 10.0);
        verify(fixture.tradingService).placeOco("BTCUSDT", new BigDecimal("0.09975062"),
                new BigDecimal("103.26"), new BigDecimal("88.22"));
        verify(fixture.liveSignalRepository, never())
                .existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull(
                        eq(485L), eq("BTCUSDT"), eq("LONG"), eq("1d"));
    }

    @Test
    void liveEnabledBlocksWhenAutoTradedOpenPositionExists() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        when(fixture.liveSignalRepository.existsOpenAutoTradedPosition(
                eq(485L), eq("BTCUSDT"), eq("LONG"), eq("1d"))).thenReturn(true);

        fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.auditWriter).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)),
                eq("LocalTradingViewOpenPositionExists"),
                eq("Local TradingView same strategy/symbol/interval position already open"),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("executionStatus", "BLOCKED_HARD_GATE")
                .containsEntry("executionBlocker", "LocalTradingViewOpenPositionExists")
                .containsEntry("orderSent", false)
                .containsEntry("ocoAttached", false);
        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.liveSignalRepository, never()).save(any());
    }

    private Fixture fixture(TradingViewLocalSignalProperties props) {
        DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        BtDecisionAuditRepository decisionAuditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        TradingService tradingService = mock(TradingService.class);
        TelegramService telegramService = mock(TelegramService.class);
        TradingSignalSourcePolicy signalSourcePolicy = mock(TradingSignalSourcePolicy.class);
        when(signalSourcePolicy.primary()).thenReturn("LOCAL_TRADINGVIEW");
        when(signalSourcePolicy.shouldRunLocalTradingViewEvaluator()).thenReturn(true);

        OkxTradingProperties okx = new OkxTradingProperties();
        okx.setEnabled(true);
        okx.setApiKey("key");
        okx.setSecretKey("secret");
        okx.setPassphrase("passphrase");

        when(liveSignalRepository.countByStrategyIdAndAutoTradedIsTrueAndCreatedAtAfter(eq(485L), any()))
                .thenReturn(0L);
        when(liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(485L))
                .thenReturn(List.of());
        when(liveSignalRepository.existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull(
                eq(485L), eq("BTCUSDT"), eq("LONG"), eq("1d"))).thenReturn(false);
        when(liveSignalRepository.existsOpenAutoTradedPosition(
                eq(485L), eq("BTCUSDT"), eq("LONG"), eq("1d"))).thenReturn(false);
        when(liveSignalRepository.existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndNotifiedAtIsNotNull(
                eq(485L), eq("BTCUSDT"), eq("1d"), eq(LocalDateTime.of(2026, 1, 3, 0, 0)))).thenReturn(false);
        when(liveSignalRepository.save(any(BtLiveSignal.class))).thenAnswer(inv -> {
            BtLiveSignal signal = inv.getArgument(0);
            if (signal.getId() == null) {
                signal.setId(77L);
            }
            return signal;
        });
        AtomicLong auditIds = new AtomicLong(100);
        when(decisionAuditRepository.save(any(BtDecisionAudit.class))).thenAnswer(inv -> {
            BtDecisionAudit audit = inv.getArgument(0);
            audit.setId(auditIds.incrementAndGet());
            return audit;
        });
        when(evidenceRepository.save(any(RuntimeDecisionEvidence.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalTradingViewExecutionService service = new LocalTradingViewExecutionService(
                props, auditWriter, liveSignalRepository, decisionAuditRepository, evidenceRepository,
                tradingService, okx, signalSourcePolicy, new ObjectMapper(), telegramService);
        return new Fixture(service, auditWriter, liveSignalRepository, decisionAuditRepository,
                evidenceRepository, tradingService, telegramService);
    }

    private TradingViewLocalSignalProperties props(ExecutionMode mode) {
        return props(mode, 0);
    }

    private TradingViewLocalSignalProperties props(ExecutionMode mode, long maxSignalAgeHours) {
        return new TradingViewLocalSignalProperties(
                true, 485L, "BTCUSDT", "1d", "", 320, 1, maxSignalAgeHours,
                new BigDecimal("10.0"), new BigDecimal("10.0"),
                mode, false, true, false, 3, 1, 1,
                new BigDecimal("0.0300"), new BigDecimal("0.1200"));
    }

    private BtStrategy strategy() {
        BtStrategy strategy = new BtStrategy();
        strategy.setId(485L);
        strategy.setName("ScoreBuy TradingView parity");
        strategy.setStrategyType("SCORE_BUY");
        return strategy;
    }

    private MdKline kline() {
        MdKline kline = new MdKline();
        kline.setSymbol("BTCUSDT");
        kline.setIntervalCode("1d");
        kline.setSource("okx");
        kline.setOpenTime(LocalDateTime.of(2026, 1, 3, 0, 0));
        kline.setClosePrice(new BigDecimal("100.00"));
        return kline;
    }

    private LiveSignalContext.OrderIntent intent() {
        return new LiveSignalContext.OrderIntent("TRADINGVIEW_RELATIVE_LOW", "相对低点买入", 1000);
    }

    private record Fixture(
            LocalTradingViewExecutionService service,
            DecisionAuditWriter auditWriter,
            BtLiveSignalRepository liveSignalRepository,
            BtDecisionAuditRepository decisionAuditRepository,
            RuntimeDecisionEvidenceRepository evidenceRepository,
            TradingService tradingService,
            TelegramService telegramService
    ) {
    }
}
