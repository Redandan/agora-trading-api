package com.agora.scheduler.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.infra.notification.NotificationPort;
import com.agora.metrics.TradingMetrics;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.TgNotificationDeduper.Severity;
import com.agora.service.diagnostic.OrphanTradeReconcilerService;
import com.agora.service.ml.MlInferenceLogger;
import com.agora.service.trading.OcoManagementService;
import com.agora.service.trading.OkxEarnService;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.PositionAgingMonitor;
import com.agora.service.trading.PostTradeReviewService;
import com.agora.service.trading.SwapRiskMonitorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

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

    private static void stubReconcileInputs(Fixture fixture, OkxTradingService.SpotHolding holding) {
        when(fixture.liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of());
        when(fixture.gridLevelRepository.sumFilledQtyBySymbolForActiveGrids()).thenReturn(List.of());
        when(fixture.okxTradingService.getPendingOcoAlgos()).thenReturn(MAPPER.createArrayNode());
        when(fixture.okxTradingService.getSpotHoldings()).thenReturn(List.of(holding));
        when(fixture.okxTradingService.getUsdtBalance()).thenReturn("N/A");
    }

    private static void invokeReconcile(OcoPositionPollerScheduler scheduler) {
        ReflectionTestUtils.invokeMethod(scheduler, "reconcileHoldings");
    }

    private static Fixture newFixture(UntrackedHoldingTracker tracker) {
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        BtGridLevelRepository gridLevelRepository = mock(BtGridLevelRepository.class);
        OkxTradingService okxTradingService = mock(OkxTradingService.class);
        NotificationPort notificationPort = mock(NotificationPort.class);
        TgNotificationDeduper tgDeduper = mock(TgNotificationDeduper.class);
        OrphanTradeReconcilerService orphanReconciler = mock(OrphanTradeReconcilerService.class);
        OcoPositionPollerScheduler scheduler = new OcoPositionPollerScheduler(
                liveSignalRepository,
                gridLevelRepository,
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
                tgDeduper);
        ReflectionTestUtils.setField(scheduler, "untrackedMinNotionalUsdt", new BigDecimal("10.0"));
        return new Fixture(scheduler, liveSignalRepository, gridLevelRepository, okxTradingService,
                notificationPort, tgDeduper, orphanReconciler);
    }

    private record Fixture(
            OcoPositionPollerScheduler scheduler,
            BtLiveSignalRepository liveSignalRepository,
            BtGridLevelRepository gridLevelRepository,
            OkxTradingService okxTradingService,
            NotificationPort notificationPort,
            TgNotificationDeduper tgDeduper,
            OrphanTradeReconcilerService orphanReconciler) {
    }
}
