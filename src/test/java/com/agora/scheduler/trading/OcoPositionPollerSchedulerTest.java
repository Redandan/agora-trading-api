package com.agora.scheduler.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.infra.notification.NotificationPort;
import com.agora.metrics.TradingMetrics;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.TgNotificationDeduper.Severity;
import com.agora.service.diagnostic.OrphanTradeReconcilerService;
import com.agora.service.ml.MlInferenceLogger;
import com.agora.service.trading.OcoManagementService;
import com.agora.service.trading.OcoOrderStateInspector;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.agora.service.trading.OkxEarnService;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.PositionAgingMonitor;
import com.agora.service.trading.PostTradeReviewService;
import com.agora.service.trading.SwapRiskMonitorService;
import com.agora.service.trading.SpotPositionCloseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OcoPositionPollerSchedulerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void smallUntrackedSpotDiffBelowNotionalThresholdDoesNotSeedOrNotify() {
        UntrackedHoldingTracker tracker = mock(UntrackedHoldingTracker.class);
        Fixture fixture = newFixture(tracker);
        stubReconcileInputs(fixture,
                new OkxTradingService.SpotHolding(
                        "BTC",
                        new BigDecimal("0.00024333292"),
                        new BigDecimal("0.00024333292"),
                        new BigDecimal("14.70")));
        BtLiveSignal dbPosition = new BtLiveSignal();
        dbPosition.setSymbol("BTCUSDT");
        dbPosition.setSide("LONG");
        dbPosition.setTradedQty(new BigDecimal("0.00008096"));
        when(fixture.liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of(dbPosition));

        invokeReconcile(fixture.scheduler);
        invokeReconcile(fixture.scheduler);

        verify(tracker, times(2)).clear("BTC");
        verify(tracker, never()).confirmOrSeed(anyString(), any(BigDecimal.class), any(LocalDateTime.class));
        verifyNoInteractions(fixture.tgDeduper, fixture.notificationPort, fixture.orphanReconciler);
    }

    @Test
    void confirmedUntrackedSpotDiffAboveNotionalThresholdKeepsAlertPath() {
        UntrackedHoldingTracker tracker = mock(UntrackedHoldingTracker.class);
        Fixture fixture = newFixture(tracker);
        stubReconcileInputs(fixture,
                new OkxTradingService.SpotHolding(
                        "BTC",
                        new BigDecimal("0.00030000"),
                        new BigDecimal("0.00030000"),
                        new BigDecimal("18.00")));
        when(tracker.confirmOrSeed(eq("BTC"), argThat(v -> v.compareTo(new BigDecimal("0.00030000")) == 0), any(LocalDateTime.class)))
                .thenReturn(true);
        when(fixture.tgDeduper.shouldSend(eq("Reconcile:Untracked:BTC"), any(Duration.class), eq(Severity.WARN)))
                .thenReturn(true);
        when(fixture.orphanReconciler.reconcile(eq("BTC"), eq(24), eq(10.0), eq(0.5), eq(5), eq(false)))
                .thenReturn("context");

        invokeReconcile(fixture.scheduler);

        verify(tracker).confirmOrSeed(eq("BTC"), argThat(v -> v.compareTo(new BigDecimal("0.00030000")) == 0), any(LocalDateTime.class));
        verify(fixture.tgDeduper).shouldSend(eq("Reconcile:Untracked:BTC"), any(Duration.class), eq(Severity.WARN));
        verify(fixture.notificationPort).alert(contains("發現未追蹤持倉"), eq(true), eq("Reconcile:Untracked:BTC"), eq("WARN"));
    }

    @Test
    void secondOcoChildFillClosesPositionAtThatChildPrice() throws Exception {
        Fixture fixture = newFixture(mock(UntrackedHoldingTracker.class));
        BtLiveSignal position = new BtLiveSignal();
        position.setId(260L);
        position.setSymbol("BTCUSDT");
        position.setSide("LONG");
        position.setAutoTraded(true);
        position.setActualEntryPrice(new BigDecimal("100"));
        position.setTradedQty(BigDecimal.ONE);
        position.setOcoQty(BigDecimal.ONE);
        position.setSuggestedTp(new BigDecimal("106"));
        position.setSuggestedSl(new BigDecimal("88"));
        position.setOcoOrderListId(1260L);
        when(fixture.liveSignalRepository.findById(260L)).thenReturn(Optional.of(position));
        when(fixture.okxTradingService.getAlgoOrder("BTCUSDT", 1260L)).thenReturn(MAPPER.readTree(
                "{\"state\":\"effective\",\"ordIdList\":[\"tp-260\",\"sl-260\"]}"));
        when(fixture.okxTradingService.querySpotOrderDetail("BTCUSDT", "tp-260"))
                .thenReturn(MAPPER.readTree("{\"state\":\"live\"}"));
        when(fixture.okxTradingService.querySpotOrderDetail("BTCUSDT", "sl-260"))
                .thenReturn(MAPPER.readTree("{\"state\":\"filled\",\"avgPx\":\"88\"}"));

        fixture.scheduler.checkAndClose(position);

        assertThat(position.getExitPrice()).isEqualByComparingTo("88");
        assertThat(position.getExitReason()).isEqualTo("SL");
        assertThat(position.getRealizedPnl()).isEqualByComparingTo("-12");
        assertThat(position.getExitTime()).isNotNull();
        verify(fixture.okxTradingService).querySpotOrderDetail("BTCUSDT", "tp-260");
        verify(fixture.okxTradingService).querySpotOrderDetail("BTCUSDT", "sl-260");
        verify(fixture.liveSignalRepository).save(position);
    }

    @Test
    void reconcileTreatsOcoQueryFailureAsProtectedUntilStateIsKnown() {
        Fixture fixture = newFixture(mock(UntrackedHoldingTracker.class));
        BtLiveSignal position = new BtLiveSignal();
        position.setId(261L);
        position.setSymbol("BTCUSDT");
        position.setSide("LONG");
        position.setOcoOrderListId(1261L);
        when(fixture.okxTradingService.getAlgoOrder("BTCUSDT", 1261L))
                .thenThrow(new RuntimeException("timeout"));

        Boolean guarded = ReflectionTestUtils.invokeMethod(fixture.scheduler, "isOcoStillActive", position);

        assertThat(guarded).isTrue();
    }

    @Test
    void btcBasePendingPositionNeverReceivesAutomaticOcoRetry() {
        Fixture fixture = newFixture(mock(UntrackedHoldingTracker.class));
        BtLiveSignal position = new BtLiveSignal();
        position.setId(260L);
        position.setSymbol("BTCUSDT");
        position.setSide("LONG");
        position.setAutoTraded(true);
        position.setTradedQty(new BigDecimal("0.00015933"));
        position.setSuggestedTp(new BigDecimal("66551"));
        position.setSuggestedSl(new BigDecimal("55250"));
        position.setFilterReason(BtcBasePositionStatePolicy.pendingMarker(1260L, null));
        when(fixture.liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNull())
                .thenReturn(List.of(position));

        ReflectionTestUtils.invokeMethod(fixture.scheduler, "retryUnprotectedPositions");

        verify(fixture.okxTradingService, never()).placeOco(
                anyString(), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class));
        verifyNoInteractions(fixture.notificationPort);
    }

    @Test
    void canceledPendingAdoptionClearsReferenceWithoutFalseTelegramAlert() throws Exception {
        Fixture fixture = newFixture(mock(UntrackedHoldingTracker.class));
        BtLiveSignal position = new BtLiveSignal();
        position.setId(260L);
        position.setSymbol("BTCUSDT");
        position.setSide("LONG");
        position.setAutoTraded(true);
        position.setTradedQty(new BigDecimal("0.00015933"));
        position.setOcoQty(new BigDecimal("0.00015933"));
        position.setOcoOrderListId(1260L);
        position.setFilterReason(BtcBasePositionStatePolicy.pendingMarker(1260L, null));
        when(fixture.liveSignalRepository.findById(260L)).thenReturn(Optional.of(position));
        when(fixture.okxTradingService.getAlgoOrder("BTCUSDT", 1260L)).thenReturn(MAPPER.readTree(
                "{\"state\":\"canceled\",\"sz\":\"0.00015933\",\"ordIdList\":[]}"));

        fixture.scheduler.checkAndClose(position);

        assertThat(position.getOcoOrderListId()).isNull();
        assertThat(BtcBasePositionStatePolicy.isAdoptionPending(position)).isTrue();
        verify(fixture.liveSignalRepository).save(position);
        verify(fixture.notificationPort, never()).broadcast(anyString(), eq(true));
    }

    private static void stubReconcileInputs(Fixture fixture, OkxTradingService.SpotHolding holding) {
        when(fixture.liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of());
        when(fixture.okxTradingService.getPendingOcoAlgos()).thenReturn(MAPPER.createArrayNode());
        when(fixture.okxTradingService.getSpotHoldings()).thenReturn(List.of(holding));
        when(fixture.okxTradingService.getUsdtBalance()).thenReturn("N/A");
    }

    private static void invokeReconcile(OcoPositionPollerScheduler scheduler) {
        ReflectionTestUtils.invokeMethod(scheduler, "reconcileHoldings");
    }

    private static Fixture newFixture(UntrackedHoldingTracker tracker) {
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        OkxTradingService okxTradingService = mock(OkxTradingService.class);
        NotificationPort notificationPort = mock(NotificationPort.class);
        TgNotificationDeduper tgDeduper = mock(TgNotificationDeduper.class);
        OrphanTradeReconcilerService orphanReconciler = mock(OrphanTradeReconcilerService.class);
        OcoPositionPollerScheduler scheduler = new OcoPositionPollerScheduler(
                liveSignalRepository,
                okxTradingService,
                mock(OcoManagementService.class),
                notificationPort,
                new OkxTradingProperties(),
                mock(PostTradeReviewService.class),
                mock(TradingMetrics.class),
                mock(MlInferenceLogger.class),
                mock(OkxEarnService.class),
                mock(SwapRiskMonitorService.class),
                mock(PositionAgingMonitor.class),
                tracker,
                orphanReconciler,
                tgDeduper,
                mock(SpotPositionCloseService.class),
                new OcoOrderStateInspector(okxTradingService));
        ReflectionTestUtils.setField(scheduler, "untrackedMinNotionalUsdt", new BigDecimal("10.0"));
        return new Fixture(scheduler, liveSignalRepository, okxTradingService,
                notificationPort, tgDeduper, orphanReconciler);
    }

    private record Fixture(
            OcoPositionPollerScheduler scheduler,
            BtLiveSignalRepository liveSignalRepository,
            OkxTradingService okxTradingService,
            NotificationPort notificationPort,
            TgNotificationDeduper tgDeduper,
            OrphanTradeReconcilerService orphanReconciler) {
    }
}
