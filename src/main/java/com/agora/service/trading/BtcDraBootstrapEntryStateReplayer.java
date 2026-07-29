package com.agora.service.trading;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.agora.service.trading.BtcDraPolicy.ARM_EXPIRY_DAYS;
import static com.agora.service.trading.BtcDraPolicy.ENTRY_COOLDOWN_DAYS;

/**
 * Replays only the DRA entry lifecycle during bootstrap.
 *
 * <p>Historical bars may establish the arm, expiry, cooldown, and latest
 * already-observed entry signal, but they never create a pending buy, virtual
 * lot, live reservation, or exchange order. This prevents a fresh runtime
 * from treating an already-confirmed trend as a new reversal after startup.</p>
 */
@Component
public final class BtcDraBootstrapEntryStateReplayer {

    public State initialState() {
        return new State(null, null, null, 0, 0, 0);
    }

    public Step observe(
            State previous,
            LocalDateTime barOpenTime,
            boolean dailyReversalConfirmed) {
        if (barOpenTime == null) {
            throw new IllegalArgumentException("barOpenTime must not be null");
        }
        State state = previous == null ? initialState() : previous;
        LocalDateTime armedAt = state.armedAt();
        LocalDateTime armExpiresAt = state.armExpiresAt();
        LocalDateTime lastEntrySignalAt = state.lastEntrySignalBarOpenTime();
        int observedSignalCount = state.observedSignalCount();
        int armCount = state.armCount();
        int expiredArmCount = state.expiredArmCount();
        boolean armExpired = false;
        boolean signalObserved = false;

        if (armedAt != null && !barOpenTime.isBefore(armExpiresAt)) {
            armedAt = null;
            armExpiresAt = null;
            expiredArmCount++;
            armExpired = true;
        }

        if (armedAt != null
                && barOpenTime.isAfter(armedAt)
                && dailyReversalConfirmed) {
            lastEntrySignalAt = barOpenTime;
            armedAt = null;
            armExpiresAt = null;
            observedSignalCount++;
            signalObserved = true;
        }

        boolean cooldownPassed = lastEntrySignalAt == null
                || !barOpenTime.isBefore(
                        lastEntrySignalAt.plusDays(ENTRY_COOLDOWN_DAYS));
        boolean armCreated = false;
        if (armedAt == null && cooldownPassed) {
            armedAt = barOpenTime;
            armExpiresAt = barOpenTime.plusDays(ARM_EXPIRY_DAYS);
            armCount++;
            armCreated = true;
        }

        return new Step(
                new State(
                        armedAt,
                        armExpiresAt,
                        lastEntrySignalAt,
                        observedSignalCount,
                        armCount,
                        expiredArmCount),
                signalObserved,
                armCreated,
                armExpired);
    }

    public record State(
            LocalDateTime armedAt,
            LocalDateTime armExpiresAt,
            LocalDateTime lastEntrySignalBarOpenTime,
            int observedSignalCount,
            int armCount,
            int expiredArmCount
    ) {
        public State {
            if (armedAt == null ^ armExpiresAt == null) {
                throw new IllegalArgumentException(
                        "armedAt and armExpiresAt must both be present or absent");
            }
            if (observedSignalCount < 0 || armCount < 0 || expiredArmCount < 0) {
                throw new IllegalArgumentException(
                        "bootstrap replay counters must not be negative");
            }
        }
    }

    public record Step(
            State state,
            boolean signalObserved,
            boolean armCreated,
            boolean armExpired
    ) {
    }
}
