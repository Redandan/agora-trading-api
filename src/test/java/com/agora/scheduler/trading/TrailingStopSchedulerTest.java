package com.agora.scheduler.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.trading.OcoManagementService;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrailingStopSchedulerTest {

    private final BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
    private final BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
    private final OkxTradingService okxTradingService = mock(OkxTradingService.class);
    private final AiStrategyDiscoveryService discoveryService = mock(AiStrategyDiscoveryService.class);
    private final OcoManagementService ocoManagementService = mock(OcoManagementService.class);
    private final NotificationPort notificationPort = mock(NotificationPort.class);

    @Test
    void schedulerContractStaysThirtySecondExplicitOptIn() throws Exception {
        ConditionalOnProperty conditional = TrailingStopScheduler.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(conditional).isNotNull();
        assertThat(conditional.name()).containsExactly("trailing-stop.enabled");
        assertThat(conditional.havingValue()).isEqualTo("true");
        assertThat(conditional.matchIfMissing()).isFalse();

        Method tick = TrailingStopScheduler.class.getDeclaredMethod("tick");
        Scheduled scheduled = tick.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(30_000L);
        assertThat(scheduled.initialDelay()).isEqualTo(60_000L);
    }

    @Test
    void dryRunBreakevenTriggerPersistsStateWithoutModifyOco() {
        TrailingStopScheduler scheduler = liveScheduler(true);
        BtLiveSignal pos = openPosition();
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull())
                .thenReturn(List.of(pos));
        when(strategyRepository.findById(485L)).thenReturn(Optional.of(strategyWithTrailingEnabled()));
        when(okxTradingService.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("101000"));
        when(liveSignalRepository.save(any(BtLiveSignal.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.tick();

        assertThat(pos.getTrailingState()).isEqualTo("BREAKEVEN_LOCKED");
        assertThat(pos.getSuggestedSl()).isEqualByComparingTo("90000");
        assertThat(pos.getTrailingLastTransitionAt()).isNotNull();
        verify(ocoManagementService, never()).modifyOco(any(), any(), any());
        verify(notificationPort, never()).broadcast(any(), eq(true));
        verify(liveSignalRepository).save(pos);
    }

    @Test
    void firstTickInitializesAtrSnapshotWithoutModifyOco() {
        TrailingStopScheduler scheduler = liveScheduler(true);
        BtLiveSignal pos = openPosition();
        pos.setTrailingAtr(null);
        pos.setTrailingHigh(null);
        pos.setTrailingState(null);
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull())
                .thenReturn(List.of(pos));
        when(strategyRepository.findById(485L)).thenReturn(Optional.of(strategyWithTrailingEnabled()));
        when(okxTradingService.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("100500"));
        when(discoveryService.buildMarketSnapshot("BTCUSDT", "1h"))
                .thenReturn(new AiStrategyDiscoveryService.MarketSnapshot(
                        "BTCUSDT", "1h", 100500.0, 52.0, 1.8, 1.2,
                        "UP", 100000.0, 1.1, 50, 25.0, 0.01, ">20=60%"));
        when(liveSignalRepository.save(any(BtLiveSignal.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.tick();

        assertThat(pos.getTrailingAtr()).isEqualByComparingTo("0.018000");
        assertThat(pos.getTrailingHigh()).isEqualByComparingTo("100500");
        assertThat(pos.getTrailingState()).isEqualTo("ENTERED");
        verify(ocoManagementService, never()).modifyOco(any(), any(), any());
        verify(liveSignalRepository).save(pos);
    }

    @Test
    void dryRunTrailingTriggerAdvancesToTrailingWithoutModifyOco() {
        TrailingStopScheduler scheduler = liveScheduler(true);
        BtLiveSignal pos = openPosition();
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull())
                .thenReturn(List.of(pos));
        when(strategyRepository.findById(485L)).thenReturn(Optional.of(strategyWithTrailingEnabled()));
        when(okxTradingService.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("102000"));
        when(liveSignalRepository.save(any(BtLiveSignal.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.tick();

        assertThat(pos.getTrailingState()).isEqualTo("TRAILING");
        assertThat(pos.getTrailingHigh()).isEqualByComparingTo("102000");
        assertThat(pos.getSuggestedSl()).isEqualByComparingTo("90000");
        assertThat(pos.getTrailingLastTransitionAt()).isNotNull();
        verify(ocoManagementService, never()).modifyOco(any(), any(), any());
        verify(notificationPort, never()).broadcast(any(), eq(true));
        verify(liveSignalRepository).save(pos);
    }

    @Test
    void liveJumpToTrailingDoesNotLowerStopBelowBreakeven() {
        TrailingStopScheduler scheduler = liveScheduler(false);
        BtLiveSignal pos = openPosition();
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull())
                .thenReturn(List.of(pos));
        when(strategyRepository.findById(485L)).thenReturn(Optional.of(strategyWithTrailingEnabled()));
        when(okxTradingService.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("102000"));
        when(liveSignalRepository.save(any(BtLiveSignal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ocoManagementService.modifyOco(eq(7L), eq(new BigDecimal("100100.000")), eq(new BigDecimal("103000"))))
                .thenReturn("ok");

        scheduler.tick();

        assertThat(pos.getTrailingState()).isEqualTo("TRAILING");
        assertThat(pos.getSuggestedSl()).isEqualByComparingTo("100100.000");
        verify(ocoManagementService).modifyOco(eq(7L), eq(new BigDecimal("100100.000")), eq(new BigDecimal("103000")));
        verify(liveSignalRepository).save(pos);
    }

    @Test
    void liveShortJumpToTrailingDoesNotRaiseStopAboveBreakeven() {
        TrailingStopScheduler scheduler = liveScheduler(false);
        BtLiveSignal pos = openShortPosition();
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull())
                .thenReturn(List.of(pos));
        when(strategyRepository.findById(485L)).thenReturn(Optional.of(strategyWithTrailingEnabled()));
        when(okxTradingService.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("98000"));
        when(liveSignalRepository.save(any(BtLiveSignal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ocoManagementService.modifyOco(eq(8L), eq(new BigDecimal("99900.000")), eq(new BigDecimal("97000"))))
                .thenReturn("ok");

        scheduler.tick();

        assertThat(pos.getTrailingState()).isEqualTo("TRAILING");
        assertThat(pos.getSuggestedSl()).isEqualByComparingTo("99900.000");
        verify(ocoManagementService).modifyOco(eq(8L), eq(new BigDecimal("99900.000")), eq(new BigDecimal("97000")));
        verify(liveSignalRepository).save(pos);
    }

    @Test
    void trailingNewHighBelowModifyThresholdStillPersistsExtremeWithoutModifyOco() {
        TrailingStopScheduler scheduler = liveScheduler(false);
        BtLiveSignal pos = openPosition();
        pos.setTrailingState("TRAILING");
        pos.setTrailingHigh(new BigDecimal("100000"));
        pos.setSuggestedSl(new BigDecimal("98000"));
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull())
                .thenReturn(List.of(pos));
        when(strategyRepository.findById(485L)).thenReturn(Optional.of(strategyWithTrailingEnabled()));
        when(okxTradingService.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("100040"));
        when(liveSignalRepository.save(any(BtLiveSignal.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.tick();

        assertThat(pos.getTrailingState()).isEqualTo("TRAILING");
        assertThat(pos.getTrailingHigh()).isEqualByComparingTo("100040");
        assertThat(pos.getSuggestedSl()).isEqualByComparingTo("98000");
        verify(ocoManagementService, never()).modifyOco(any(), any(), any());
        verify(notificationPort, never()).broadcast(any(), eq(true));
        verify(liveSignalRepository).save(pos);
    }

    @Test
    void liveModifyOcoRetriesUpToThirdAttemptBeforePersistingTrailingState() {
        TrailingStopScheduler scheduler = liveScheduler(false);
        BtLiveSignal pos = openPosition();
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull())
                .thenReturn(List.of(pos));
        when(strategyRepository.findById(485L)).thenReturn(Optional.of(strategyWithTrailingEnabled()));
        when(okxTradingService.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("101000"));
        when(liveSignalRepository.save(any(BtLiveSignal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ocoManagementService.modifyOco(eq(7L), eq(new BigDecimal("100100.000")), eq(new BigDecimal("103000"))))
                .thenThrow(new RuntimeException("transient-1"))
                .thenThrow(new RuntimeException("transient-2"))
                .thenReturn("ok");

        scheduler.tick();

        verify(ocoManagementService, times(3))
                .modifyOco(eq(7L), eq(new BigDecimal("100100.000")), eq(new BigDecimal("103000")));
        verify(liveSignalRepository).save(pos);
        verify(notificationPort).broadcast(
                org.mockito.ArgumentMatchers.contains("Trailing BTCUSDT"),
                eq(true));
    }

    @Test
    void liveModifyOcoAlertsOnlyAfterThreeFailedAttempts() {
        TrailingStopScheduler scheduler = liveScheduler(false);
        BtLiveSignal pos = openPosition();
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull())
                .thenReturn(List.of(pos));
        when(strategyRepository.findById(485L)).thenReturn(Optional.of(strategyWithTrailingEnabled()));
        when(okxTradingService.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("101000"));
        doThrow(new RuntimeException("still-down"))
                .when(ocoManagementService)
                .modifyOco(eq(7L), eq(new BigDecimal("100100.000")), eq(new BigDecimal("103000")));

        scheduler.tick();

        verify(ocoManagementService, times(3))
                .modifyOco(eq(7L), eq(new BigDecimal("100100.000")), eq(new BigDecimal("103000")));
        verify(notificationPort).broadcast(
                org.mockito.ArgumentMatchers.contains("Trailing modifyOco"),
                eq(true));
        verify(liveSignalRepository, org.mockito.Mockito.never()).save(any(BtLiveSignal.class));
    }

    private TrailingStopScheduler liveScheduler(boolean dryRun) {
        TrailingStopScheduler scheduler = new TrailingStopScheduler(
                liveSignalRepository,
                strategyRepository,
                okxTradingService,
                discoveryService,
                ocoManagementService,
                notificationPort,
                new ObjectMapper());
        ReflectionTestUtils.setField(scheduler, "schedulerEnabled", true);
        ReflectionTestUtils.setField(scheduler, "globalDryRun", dryRun);
        return scheduler;
    }

    private BtLiveSignal openPosition() {
        BtLiveSignal pos = new BtLiveSignal();
        pos.setId(7L);
        pos.setStrategyId(485L);
        pos.setSymbol("BTCUSDT");
        pos.setIntervalCode("1h");
        pos.setSide("LONG");
        pos.setAutoTraded(true);
        pos.setOcoOrderListId(123L);
        pos.setActualEntryPrice(new BigDecimal("100000"));
        pos.setSuggestedSl(new BigDecimal("90000"));
        pos.setSuggestedTp(new BigDecimal("103000"));
        pos.setTrailingAtr(new BigDecimal("0.02"));
        pos.setTrailingHigh(new BigDecimal("100000"));
        pos.setTrailingState("ENTERED");
        when(liveSignalRepository.findById(7L)).thenReturn(Optional.of(pos));
        return pos;
    }

    private BtLiveSignal openShortPosition() {
        BtLiveSignal pos = openPosition();
        pos.setId(8L);
        pos.setSide("SHORT");
        pos.setSuggestedSl(new BigDecimal("110000"));
        pos.setSuggestedTp(new BigDecimal("97000"));
        when(liveSignalRepository.findById(8L)).thenReturn(Optional.of(pos));
        return pos;
    }

    private BtStrategy strategyWithTrailingEnabled() {
        BtStrategy strategy = new BtStrategy();
        strategy.setId(485L);
        strategy.setConfigJson("{\"trailingStopEnabled\":true}");
        return strategy;
    }
}
