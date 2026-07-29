package com.agora.service.trading;

import com.agora.model.MdKline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BtcDraBootstrapEntryStateReplayerTest {

    private final BtcDraBootstrapEntryStateReplayer replayer =
            new BtcDraBootstrapEntryStateReplayer();

    @Test
    void historicalSignalStartsCooldownAndPreventsStartupChase() {
        LocalDateTime firstHistoricalBar =
                LocalDateTime.of(2026, 7, 25, 22, 0);
        BtcDraBootstrapEntryStateReplayer.Step armed = replayer.observe(
                replayer.initialState(),
                firstHistoricalBar,
                false);
        assertTrue(armed.armCreated());

        LocalDateTime historicalSignal =
                LocalDateTime.of(2026, 7, 25, 23, 0);
        BtcDraBootstrapEntryStateReplayer.Step observed = replayer.observe(
                armed.state(),
                historicalSignal,
                true);
        assertTrue(observed.signalObserved());
        assertEquals(
                historicalSignal,
                observed.state().lastEntrySignalBarOpenTime());
        assertNull(observed.state().armedAt());

        BtcDraBootstrapEntryStateReplayer.Step startupDayConfirmation =
                replayer.observe(
                        observed.state(),
                        LocalDateTime.of(2026, 7, 26, 23, 0),
                        true);
        assertFalse(startupDayConfirmation.signalObserved());
        assertFalse(startupDayConfirmation.armCreated());
        assertEquals(
                historicalSignal,
                startupDayConfirmation.state()
                        .lastEntrySignalBarOpenTime());
    }

    @Test
    void normalEntryLifecycleResumesAfterSevenDayCooldown() {
        LocalDateTime firstBar =
                LocalDateTime.of(2026, 7, 1, 0, 0);
        BtcDraBootstrapEntryStateReplayer.State state = replayer.observe(
                replayer.initialState(),
                firstBar,
                false).state();
        LocalDateTime firstSignal =
                LocalDateTime.of(2026, 7, 1, 23, 0);
        state = replayer.observe(state, firstSignal, true).state();

        LocalDateTime cooldownBoundary = firstSignal.plusDays(7);
        BtcDraBootstrapEntryStateReplayer.Step rearmed = replayer.observe(
                state,
                cooldownBoundary,
                false);
        assertTrue(rearmed.armCreated());
        assertEquals(cooldownBoundary, rearmed.state().armedAt());

        LocalDateTime nextDailyConfirmation =
                LocalDateTime.of(2026, 7, 9, 23, 0);
        BtcDraBootstrapEntryStateReplayer.Step nextSignal =
                replayer.observe(
                        rearmed.state(),
                        nextDailyConfirmation,
                        true);
        assertTrue(nextSignal.signalObserved());
        assertEquals(
                nextDailyConfirmation,
                nextSignal.state().lastEntrySignalBarOpenTime());
        assertEquals(2, nextSignal.state().observedSignalCount());
    }

    @Test
    void expiredArmIsReplacedWithoutCreatingHistoricalSignal() {
        LocalDateTime firstBar =
                LocalDateTime.of(2026, 1, 1, 0, 0);
        BtcDraBootstrapEntryStateReplayer.State state = replayer.observe(
                replayer.initialState(),
                firstBar,
                false).state();

        LocalDateTime expiry = firstBar.plusDays(30);
        BtcDraBootstrapEntryStateReplayer.Step expired = replayer.observe(
                state,
                expiry,
                false);

        assertTrue(expired.armExpired());
        assertTrue(expired.armCreated());
        assertFalse(expired.signalObserved());
        assertEquals(expiry, expired.state().armedAt());
        assertEquals(2, expired.state().armCount());
        assertEquals(1, expired.state().expiredArmCount());
    }

    @Test
    void seededHistoricalSignalPreventsOtherwiseEligibleBootstrapEntry() {
        BtcDraShadowEngine engine =
                new BtcDraShadowEngine(new ObjectMapper());
        BtcDraShadowEngine.State indicatorState = engine.initialState();
        LocalDateTime warmupStart =
                LocalDateTime.of(2026, 7, 20, 23, 0);
        for (int hour = 0; hour < 144; hour++) {
            BigDecimal close = new BigDecimal("100")
                    .add(new BigDecimal(hour).movePointLeft(2));
            indicatorState = engine.warmup(
                    indicatorState,
                    bar(warmupStart.plusHours(hour), close))
                    .state();
        }

        LocalDateTime currentDailyBar =
                LocalDateTime.of(2026, 7, 26, 23, 0);
        MdKline confirmationBar =
                bar(currentDailyBar, new BigDecimal("110"));

        BtcDraBootstrapEntryStateReplayer.State startupOnlyArm =
                new BtcDraBootstrapEntryStateReplayer.State(
                        LocalDateTime.of(2026, 7, 26, 15, 0),
                        LocalDateTime.of(2026, 8, 25, 15, 0),
                        null,
                        0,
                        1,
                        0);
        BtcDraShadowEngine.StepResult startupBugResult = engine.step(
                engine.seedBootstrapEntryState(
                        indicatorState,
                        startupOnlyArm),
                confirmationBar);
        assertTrue(startupBugResult.signal().dailyReversalConfirmed());
        assertTrue(startupBugResult.signal().entryEligible());
        assertTrue(startupBugResult.events().stream().anyMatch(
                event -> "VIRTUAL_ENTRY_QUEUED".equals(
                        event.eventType())));

        LocalDateTime historicalSignal =
                LocalDateTime.of(2026, 7, 25, 23, 0);
        BtcDraBootstrapEntryStateReplayer.State replayedHistory =
                new BtcDraBootstrapEntryStateReplayer.State(
                        null,
                        null,
                        historicalSignal,
                        1,
                        4,
                        0);
        BtcDraShadowEngine.StepResult fixedResult = engine.step(
                engine.seedBootstrapEntryState(
                        indicatorState,
                        replayedHistory),
                confirmationBar);

        assertTrue(fixedResult.signal().dailyReversalConfirmed());
        assertFalse(fixedResult.signal().entryEligible());
        assertFalse(fixedResult.events().stream().anyMatch(
                event -> "VIRTUAL_ENTRY_QUEUED".equals(
                        event.eventType())));
        assertEquals(
                historicalSignal,
                fixedResult.state().lastEntrySignalBarOpenTime());
        assertNull(fixedResult.state().armedAt());
    }

    private MdKline bar(LocalDateTime openTime, BigDecimal closePrice) {
        MdKline bar = new MdKline();
        bar.setSymbol(BtcDraPolicy.SYMBOL);
        bar.setIntervalCode(BtcDraPolicy.INTERVAL);
        bar.setSource(BtcDraPolicy.SOURCE);
        bar.setOpenTime(openTime);
        bar.setCloseTime(openTime.plusHours(1));
        bar.setOpenPrice(closePrice);
        bar.setHighPrice(closePrice);
        bar.setLowPrice(closePrice);
        bar.setClosePrice(closePrice);
        bar.setVolume(BigDecimal.ONE);
        return bar;
    }
}
