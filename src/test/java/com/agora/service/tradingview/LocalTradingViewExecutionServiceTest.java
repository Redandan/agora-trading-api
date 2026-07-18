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
import com.agora.service.trading.VersionedProfitStartCohortService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

class LocalTradingViewExecutionServiceTest {

    @Test
    void waitMarketNeverSubmitsBuy() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        var wait = mock(com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.Snapshot.class);
        when(wait.decision()).thenReturn(
                com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.Decision.WAIT_MARKET);
        when(wait.snapshotId()).thenReturn("WAIT-SNAPSHOT");
        when(wait.sha256()).thenReturn("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(wait.reasons()).thenReturn(List.of("NO_VALID_CANDIDATE"));
        when(fixture.hardGateService.evaluate(any())).thenReturn(wait);

        fixture.service.preview(strategy(), kline(), "1d", "okx", intent(), Map.of(), 1);

        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.evidenceRepository, never()).save(any());
    }

    @Test
    void preSubmitSnapshotEvidenceSaveFailurePreventsBuy() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        when(fixture.preSubmitEvidencePersistenceService.persist(any(), any()))
                .thenThrow(new IllegalStateException("commit unavailable"));

        fixture.service.preview(strategy(), kline(), "1d", "okx", intent(), Map.of(), 1);

        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.liveSignalRepository, never()).save(any());
    }

    @Test
    void postPersistHashDriftPreventsBuy() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        when(fixture.hardGateService.verifyAtOrderBoundary(any(), any(), any())).thenReturn(
                new com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.BoundaryDecision(
                        false, null, List.of("SNAPSHOT_HASH_DRIFT")));

        fixture.service.preview(strategy(), kline(), "1d", "okx", intent(), Map.of(), 1);

        verify(fixture.preSubmitEvidencePersistenceService).persist(any(), any());
        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void postPersistExpiryPreventsBuy() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        when(fixture.hardGateService.verifyAtOrderBoundary(any(), any(), any())).thenReturn(
                new com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.BoundaryDecision(
                        false, null, List.of("SNAPSHOT_EXPIRED")));

        fixture.service.preview(strategy(), kline(), "1d", "okx", intent(), Map.of(), 1);

        verify(fixture.preSubmitEvidencePersistenceService).persist(any(), any());
        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void postPersistMissingFreshInputsPreventsBuy() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        when(fixture.hardGateAssembler.assemble(any(), anyMap()))
                .thenReturn(mock(com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.Inputs.class))
                .thenReturn(null);
        when(fixture.hardGateService.verifyAtOrderBoundary(any(), isNull(), any())).thenReturn(
                new com.agora.service.trading.VersionedProfitStartHardGateSnapshotService.BoundaryDecision(
                        false, null, List.of("CURRENT_SNAPSHOT_NOT_READY")));

        fixture.service.preview(strategy(), kline(), "1d", "okx", intent(), Map.of(), 1);

        verify(fixture.preSubmitEvidencePersistenceService).persist(any(), any());
        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
    }

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
    void liveModeFailsClosedBeforeOrderWhenVersionedCohortIdentityIsNotReady() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        when(fixture.cohortService.liveExecutionBlocker(
                fixture.cohortService.snapshot(), 485L, "BTCUSDT", "LIVE_MICRO"))
                .thenReturn("VERSIONED_PROFIT_START_COHORT_NOT_READY:EFFECTIVE_FROM_NOT_CONFIGURED");

        fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.auditWriter).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)),
                eq("VersionedProfitStartCohortNotReady"),
                eq("VERSIONED_PROFIT_START cohort identity is incomplete or mismatched; no order sent"),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("executionStatus", "BLOCKED_HARD_GATE")
                .containsEntry("versionedProfitStartCohortBlocker",
                        "VERSIONED_PROFIT_START_COHORT_NOT_READY:EFFECTIVE_FROM_NOT_CONFIGURED")
                .containsEntry("orderSent", false);
        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.liveSignalRepository, never()).save(any());
    }

    @Test
    void btcBaseLiveMicroPlacesMarketBuyWithoutOcoAndWritesEvidence() {
        Fixture fixture = fixture(props(ExecutionMode.BTC_BASE_LIVE_MICRO));
        TradeResult buy = new TradeResult();
        buy.setOrderId("ord-btc-base");
        buy.setAvgPrice(new BigDecimal("100.25"));
        buy.setQty(new BigDecimal("0.09975062"));
        when(fixture.tradingService.placeMarketBuy(eq("BTCUSDT"), eq(10.0))).thenReturn(buy);

        fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        verify(fixture.tradingService).placeMarketBuy("BTCUSDT", 10.0);
        verify(fixture.tradingService, never()).placeOco(any(), any(), any(), any());

        ArgumentCaptor<BtLiveSignal> signalCaptor = ArgumentCaptor.forClass(BtLiveSignal.class);
        verify(fixture.liveSignalRepository).save(signalCaptor.capture());
        BtLiveSignal savedSignal = signalCaptor.getValue();
        assertThat(savedSignal.getStrategyId()).isEqualTo(485L);
        assertThat(savedSignal.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(savedSignal.getExchangeOrderId()).isEqualTo("LOCAL_TV_BTC_BASE:ord-btc-base");
        assertThat(savedSignal.getOcoOrderListId()).isNull();
        assertThat(savedSignal.getOcoQty()).isNull();
        assertThat(savedSignal.getEntryPrice()).isEqualByComparingTo("100.25");
        assertThat(savedSignal.getFilterReason()).isEqualTo(
                "LOCAL_TRADINGVIEW_BTC_BASE:1:TRADINGVIEW_RELATIVE_LOW|COHORT:TEST-COHORT");

        ArgumentCaptor<RuntimeDecisionEvidence> evidenceCaptor = ArgumentCaptor.forClass(RuntimeDecisionEvidence.class);
        verify(fixture.evidenceRepository, org.mockito.Mockito.atLeastOnce()).save(evidenceCaptor.capture());
        RuntimeDecisionEvidence evidence = evidenceCaptor.getValue();
        assertThat(evidence.getSelectedAction()).isEqualTo("LOCAL_TRADINGVIEW_BTC_BASE_BUY");
        assertThat(evidence.getPolicyMode()).isEqualTo("LOCAL_TRADINGVIEW_BTC_BASE_MICRO_LIVE");
        assertThat(evidence.getFinalOutcome()).isEqualTo("EXECUTED_BTC_BASE_BUY");
        assertThat(evidence.getOrderSent()).isTrue();
        assertThat(evidence.getExecutionMode()).isEqualTo("LOCAL_TV_BTC_BASE_EXEC");
        assertThat(evidence.getOcoOrderListId()).isNull();
        assertThat(evidence.getExecutionPreviewJson())
                .contains("\"btcBaseMode\":true")
                .contains("\"btcBaseLiveMicro\":true")
                .contains("\"ocoAttached\":false");
    }

    @Test
    void btcBaseLiveMicroBlocksWhenExposureCapWouldBeExceeded() {
        Fixture fixture = fixture(props(ExecutionMode.BTC_BASE_LIVE_MICRO, 0, new BigDecimal("15.0")));
        BtLiveSignal openSlice = new BtLiveSignal();
        openSlice.setStrategyId(508L);
        openSlice.setSymbol("BTCUSDT");
        openSlice.setActualEntryPrice(new BigDecimal("100.00"));
        openSlice.setEntryPrice(new BigDecimal("100.00"));
        openSlice.setTradedQty(new BigDecimal("0.10"));
        openSlice.setFilterReason(com.agora.service.trading.BtcBasePositionStatePolicy.adoptedMarkerFromPending(
                com.agora.service.trading.BtcBasePositionStatePolicy.pendingMarker(1260L, "legacy-508")));
        when(fixture.liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull())
                .thenReturn(List.of(openSlice));

        fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.auditWriter).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"),
                eq(LocalDateTime.of(2026, 1, 3, 0, 0)),
                eq("LocalTradingViewBtcBaseExposureCapReached"),
                eq("Local TradingView BTC_BASE exposure cap reached"),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .containsEntry("executionStatus", "BLOCKED_HARD_GATE")
                .containsEntry("executionBlocker", "LocalTradingViewBtcBaseExposureCapReached")
                .containsEntry("btcBaseOpenExposureUsdt", new BigDecimal("10.00"))
                .containsEntry("btcBaseMaxExposureUsdt", new BigDecimal("15.0"))
                .containsEntry("btcBaseExposureAfterOrderUsdt", new BigDecimal("20.00"))
                .containsEntry("btcBaseExposureCapAvailable", false);
        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.tradingService, never()).placeOco(any(), any(), any(), any());
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

        InOrder boundaryOrder = inOrder(fixture.preSubmitEvidencePersistenceService,
                fixture.hardGateService, fixture.tradingService);
        boundaryOrder.verify(fixture.preSubmitEvidencePersistenceService).persist(any(), any());
        boundaryOrder.verify(fixture.hardGateService).verifyAtOrderBoundary(any(), any(), any());
        boundaryOrder.verify(fixture.tradingService).placeMarketBuy("BTCUSDT", 10.0);

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
        assertThat(savedSignal.getFilterReason()).isEqualTo(
                "LOCAL_TRADINGVIEW_PARITY:1:TRADINGVIEW_RELATIVE_LOW|COHORT:TEST-COHORT");

        ArgumentCaptor<BtDecisionAudit> auditCaptor = ArgumentCaptor.forClass(BtDecisionAudit.class);
        verify(fixture.decisionAuditRepository).save(auditCaptor.capture());
        BtDecisionAudit finalAudit = auditCaptor.getAllValues().get(auditCaptor.getAllValues().size() - 1);
        assertThat(finalAudit.getEventType()).isEqualTo("LOCAL_TV_EXECUTION");
        assertThat(finalAudit.getOutcome()).isEqualTo("PASS");
        assertThat(finalAudit.getLiveSignalId()).isEqualTo(77L);

        ArgumentCaptor<RuntimeDecisionEvidence> evidenceCaptor = ArgumentCaptor.forClass(RuntimeDecisionEvidence.class);
        verify(fixture.evidenceRepository, org.mockito.Mockito.atLeastOnce()).save(evidenceCaptor.capture());
        RuntimeDecisionEvidence evidence = evidenceCaptor.getValue();
        assertThat(evidence.getSignalSource()).isEqualTo("LOCAL_TRADINGVIEW");
        assertThat(evidence.getSelectedAction()).isEqualTo("LOCAL_TRADINGVIEW_EXECUTE");
        assertThat(evidence.getFinalOutcome()).isEqualTo("EXECUTED_OCO_ATTACHED");
        assertThat(evidence.getOrderSent()).isTrue();
        assertThat(evidence.getExecutionMode()).isEqualTo("LOCAL_TV_PARITY_EXEC");
        assertThat(evidence.getOcoOrderListId()).isEqualTo("999");
    }

    @Test
    void bootstrapOrderAuthorityNeverImplicitlyAuthorizesOco() {
        Fixture fixture = fixture(props(ExecutionMode.LIVE_MICRO));
        when(fixture.cohortSnapshot.bootstrapOcoAuthorityArmed()).thenReturn(false);

        fixture.service.preview(strategy(), kline(), "1d", "okx",
                intent(), Map.of("source", "LOCAL_TRADINGVIEW_PARITY"), 1);

        verify(fixture.preSubmitEvidencePersistenceService, never()).persist(any(), any());
        verify(fixture.tradingService, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.tradingService, never()).placeOco(any(), any(), any(), any());
        verify(fixture.auditWriter).logEntrySkip(eq(485L), eq("BTCUSDT"), eq("1d"), any(),
                eq("VersionedProfitStartBootstrapOcoAuthorityBlocked"), any(), anyMap());
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
        verify(fixture.evidenceRepository, org.mockito.Mockito.atLeastOnce()).save(evidenceCaptor.capture());
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
        verify(fixture.evidenceRepository, org.mockito.Mockito.atLeastOnce()).save(evidenceCaptor.capture());
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
        VersionedProfitStartCohortService cohortService = mock(VersionedProfitStartCohortService.class);
        VersionedProfitStartCohortService.Snapshot cohortSnapshot =
                mock(VersionedProfitStartCohortService.Snapshot.class);
        when(cohortService.snapshot()).thenReturn(cohortSnapshot);
        when(cohortSnapshot.bootstrapOcoAuthorityArmed()).thenReturn(true);
        when(cohortService.liveExecutionBlocker(eq(cohortSnapshot),
                org.mockito.ArgumentMatchers.anyLong(), any(String.class), any(String.class)))
                .thenReturn(null);
        when(cohortService.currentCohortMarker(cohortSnapshot)).thenReturn("|COHORT:TEST-COHORT");

        OkxTradingProperties okx = new OkxTradingProperties();
        okx.setEnabled(true);
        okx.setApiKey("key");
        okx.setSecretKey("secret");
        okx.setPassphrase("passphrase");

        when(liveSignalRepository.countByStrategyIdAndAutoTradedIsTrueAndCreatedAtAfter(eq(485L), any()))
                .thenReturn(0L);
        when(liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(485L))
                .thenReturn(List.of());
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of());
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

        var hardGateAssembler = readyHardGateAssembler();
        var hardGateService = readyHardGateService();
        var activationReadiness = readyActivationReadiness();
        PreSubmitEvidencePersistenceService preSubmitEvidencePersistenceService =
                mock(PreSubmitEvidencePersistenceService.class);
        when(preSubmitEvidencePersistenceService.persist(any(), any())).thenReturn(101L);
        LocalTradingViewExecutionService service = new LocalTradingViewExecutionService(
                props, auditWriter, liveSignalRepository, decisionAuditRepository, evidenceRepository,
                tradingService, okx, signalSourcePolicy, cohortService,
                hardGateAssembler, hardGateService, activationReadiness, preSubmitEvidencePersistenceService,
                new ObjectMapper(), telegramService);
        return new Fixture(service, auditWriter, liveSignalRepository, decisionAuditRepository,
                evidenceRepository, tradingService, telegramService, cohortService, hardGateAssembler,
                hardGateService, preSubmitEvidencePersistenceService, cohortSnapshot);
    }

    private com.agora.service.trading.VersionedProfitStartHardGateInputAssembler readyHardGateAssembler() {
        var assembler = mock(com.agora.service.trading.VersionedProfitStartHardGateInputAssembler.class);
        when(assembler.assemble(any(), anyMap())).thenReturn(
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
        var readiness = new com.agora.service.trading.VersionedProfitStartActivationReadinessService.Readiness(
                "TEST_READY", true, true, true, true, true, false, false,
                com.agora.service.trading.CurrentCohortCanonicalMetricReader.Classification.NOT_MEASURABLE,
                0, 0, 0, List.of());
        when(service.assess(any(), any())).thenReturn(readiness);
        when(service.assess(any(), any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(readiness);
        return service;
    }

    private TradingViewLocalSignalProperties props(ExecutionMode mode) {
        return props(mode, 0);
    }

    private TradingViewLocalSignalProperties props(ExecutionMode mode, long maxSignalAgeHours) {
        return props(mode, maxSignalAgeHours, new BigDecimal("250.0"));
    }

    private TradingViewLocalSignalProperties props(ExecutionMode mode, long maxSignalAgeHours, BigDecimal btcBaseMaxExposureUsdt) {
        return new TradingViewLocalSignalProperties(
                true, 485L, "BTCUSDT", "1d", "", 320, 1, maxSignalAgeHours,
                new BigDecimal("10.0"), new BigDecimal("10.0"),
                mode, false, true, false, 3, 1, 1,
                new BigDecimal("0.0300"), new BigDecimal("0.1200"),
                btcBaseMaxExposureUsdt);
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
            TelegramService telegramService,
            VersionedProfitStartCohortService cohortService,
            com.agora.service.trading.VersionedProfitStartHardGateInputAssembler hardGateAssembler,
            com.agora.service.trading.VersionedProfitStartHardGateSnapshotService hardGateService,
            PreSubmitEvidencePersistenceService preSubmitEvidencePersistenceService,
            VersionedProfitStartCohortService.Snapshot cohortSnapshot
    ) {
    }
}
