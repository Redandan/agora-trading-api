package com.agora.service.trading;

import com.agora.model.SpotExecutionAttempt.State;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure invariants shared by spot execution reservation and reconciliation.
 */
public final class SpotExecutionAttemptPolicy {

    private static final DateTimeFormatter CLIENT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
    private static final int MAX_CLIENT_ORDER_ID_LENGTH = 32;

    private SpotExecutionAttemptPolicy() {
    }

    /**
     * Preserves the accepted first DRA sell id. Proven partial attempts add a
     * durable sequence suffix without changing the original signal identity.
     */
    public static String draSellClientOrderId(
            LocalDateTime originalSignalBarOpenTime,
            int attemptSequence) {
        Objects.requireNonNull(
                originalSignalBarOpenTime,
                "originalSignalBarOpenTime");
        if (attemptSequence <= 0) {
            throw new IllegalArgumentException(
                    "attemptSequence must be positive");
        }
        String base = "DRA1S"
                + CLIENT_TIME.format(originalSignalBarOpenTime);
        String result = attemptSequence == 1
                ? base
                : base + "A" + String.format(
                        Locale.ROOT,
                        "%03d",
                        attemptSequence);
        if (result.length() > MAX_CLIENT_ORDER_ID_LENGTH
                || !result.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException(
                    "client order id is outside OKX constraints");
        }
        return result;
    }

    /**
     * Returns only the provider cumulative fill that has not already been
     * applied to the strategy-owned lot.
     */
    public static BigDecimal unappliedFillDelta(
            BigDecimal providerCumulativeFilled,
            BigDecimal alreadyApplied,
            BigDecimal remainingBeforeApply) {
        requireNonNegative(
                providerCumulativeFilled,
                "providerCumulativeFilled");
        requireNonNegative(alreadyApplied, "alreadyApplied");
        requireNonNegative(remainingBeforeApply, "remainingBeforeApply");

        BigDecimal delta =
                providerCumulativeFilled.subtract(alreadyApplied);
        if (delta.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return delta.min(remainingBeforeApply);
    }

    public static boolean canTransition(State from, State to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return switch (from) {
            case RESERVED -> to == State.SUBMITTING;
            case SUBMITTING -> to == State.SUBMISSION_UNKNOWN
                    || to == State.PROVIDER_ACCEPTED
                    || to == State.REJECTED;
            case SUBMISSION_UNKNOWN -> to == State.PROVIDER_ACCEPTED
                    || to == State.REJECTED;
            case PROVIDER_ACCEPTED -> to == State.RECONCILED_FILLED
                    || to == State.RECONCILED_PARTIAL;
            case RECONCILED_FILLED,
                    RECONCILED_PARTIAL,
                    REJECTED -> false;
        };
    }

    private static void requireNonNegative(
            BigDecimal value,
            String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    field + " must not be negative");
        }
    }
}
