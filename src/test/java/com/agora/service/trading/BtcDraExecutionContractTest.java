package com.agora.service.trading;

import com.agora.model.MdKline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BtcDraExecutionContractTest {
    private static final LocalDateTime BAR = LocalDateTime.of(2024, 1, 10, 23, 0);
    private static final BigDecimal ONE = BigDecimal.ONE;

    @Test void exitRequiresNetFivePercentAfterBothSellCosts() {
        // Gross price gain of 5% is below the existing net exit threshold.
        assertEquals(new BigDecimal("0.048425525000"),
                BtcDraExecutionContract.estimatedNetReturn(ONE, new BigDecimal("100"), new BigDecimal("105")));
        assertTrue(BtcDraExecutionContract.estimatedNetReturn(new BigDecimal("0.00031"),
                new BigDecimal("100"), new BigDecimal("105.16")).compareTo(BtcDraPolicy.NET_PROFIT_TRIGGER) > 0);
        assertTrue(BtcDraExecutionContract.estimatedNetReturn(ONE,
                new BigDecimal("100"), new BigDecimal("101.2")).compareTo(BtcDraPolicy.NET_PROFIT_TRIGGER) < 0);
        assertEquals(BigDecimal.valueOf(-1), BtcDraExecutionContract.estimatedNetReturn(BigDecimal.ZERO, ONE, ONE));
    }

    @Test void blockedLiveSignalsStillHaveTheFrozenVirtualCooldownAndNeverBecomeOrders() {
        var step = step(BAR.minusDays(3), false, List.of());
        var decision = BtcDraExecutionContract.entryDecision(step, BAR, false, false);
        assertEquals("VIRTUAL_SIGNAL_COOLDOWN", decision.get("stage"));
        assertEquals(BAR.plusDays(4).toString(), decision.get("cooldownUntilBarOpenUtc"));
        assertEquals(false, decision.get("queuedCandidate"));
        assertNull(BtcDraExecutionContract.entryEvent(step));
        assertEquals("BOOTSTRAP_NO_LIVE_EXECUTION", BtcDraExecutionContract.entryDecision(step, BAR, true, false).get("stage"));
        assertEquals("CATCH_UP_NO_LIVE_EXECUTION", BtcDraExecutionContract.entryDecision(step, BAR, false, true).get("stage"));
    }

    @Test void onlyQueuedEventIsACandidateAndVirtualCapacityIsDistinctFromLiveCapacity() {
        var blocked = event("VIRTUAL_ENTRY_BLOCKED");
        var step = step(null, true, List.of(blocked));
        assertNull(BtcDraExecutionContract.entryEvent(step));
        assertEquals("VIRTUAL_REFERENCE_CAPACITY_BLOCKED", BtcDraExecutionContract.entryDecision(step, BAR, false, false).get("stage"));
        var queued = event("VIRTUAL_ENTRY_QUEUED");
        var ready = step(null, true, List.of(queued));
        assertSame(queued, BtcDraExecutionContract.entryEvent(ready));
        var decision = BtcDraExecutionContract.entryDecision(ready, BAR, false, false);
        assertEquals("QUEUED_AWAITING_LIVE_GATES", decision.get("stage"));
        assertEquals("2024-01-10T23:00:00", decision.get("barOpenUtc"));
        assertEquals(false, BtcDraExecutionContract.description().get("referencePerformanceIsLivePerformance"));
        assertEquals(new BigDecimal("250.00"), BtcDraExecutionContract.description().get("signalReferenceCapUsdt"));
        assertEquals(1, BtcDraExecutionContract.description().get("maximumLiveLots"));
    }

    private BtcDraShadowEngine.StepResult step(LocalDateTime last, boolean eligible,
                                               List<BtcDraShadowEngine.RuntimeEvent> events) {
        var engine = new BtcDraShadowEngine(new ObjectMapper());
        MdKline bar = new MdKline(); bar.setSource("okx"); bar.setSymbol("BTCUSDT"); bar.setIntervalCode("1h");
        bar.setOpenTime(BAR.minusHours(1)); bar.setCloseTime(BAR);
        bar.setOpenPrice(ONE); bar.setHighPrice(ONE); bar.setLowPrice(ONE); bar.setClosePrice(ONE); bar.setVolume(ONE);
        var state = engine.warmup(engine.initialState(), bar).state();
        state = engine.seedBootstrapEntryState(state, new BtcDraBootstrapEntryStateReplayer.State(null, null, last, 0, 0, 0));
        return new BtcDraShadowEngine.StepResult(state,
                new BtcDraShadowEngine.SignalSnapshot(true, true, ONE, ONE, ONE, true, true, true,
                        true, eligible, null, null, "fixture"), events);
    }

    static BtcDraShadowEngine.RuntimeEvent event(String type) {
        return new BtcDraShadowEngine.RuntimeEvent(type, BAR, BAR, "fixture", new BigDecimal("30"),
                ONE, ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "fixture");
    }
}
