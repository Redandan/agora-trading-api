package com.agora.service.trading;

import com.agora.model.SpotExecutionAttempt.State;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    public static String draBuyClientOrderId(
            LocalDateTime signalBarOpenTime) {
        Objects.requireNonNull(
                signalBarOpenTime,
                "signalBarOpenTime");
        return checkedClientOrderId(
                "DRA1B" + CLIENT_TIME.format(signalBarOpenTime));
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
        return checkedClientOrderId(result);
    }

    private static String checkedClientOrderId(String result) {
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

    /**
     * Computes exact cumulative-provider deltas. Unlike the display helper
     * above, this mutation contract fails if provider quantity would consume
     * anything beyond the remaining strategy-owned lot.
     */
    public static ReconciliationDelta reconciliationDelta(
            BigDecimal providerCumulativeFilled,
            BigDecimal alreadyAppliedFilled,
            BigDecimal remainingBeforeApply,
            BigDecimal providerCumulativeGrossQuote,
            BigDecimal alreadyAppliedGrossQuote,
            BigDecimal providerCumulativeFeeUsdt,
            BigDecimal alreadyAppliedFeeUsdt) {
        requireNonNegative(
                providerCumulativeFilled,
                "providerCumulativeFilled");
        requireNonNegative(
                alreadyAppliedFilled,
                "alreadyAppliedFilled");
        requireNonNegative(
                remainingBeforeApply,
                "remainingBeforeApply");
        requireNonNegative(
                providerCumulativeGrossQuote,
                "providerCumulativeGrossQuote");
        requireNonNegative(
                alreadyAppliedGrossQuote,
                "alreadyAppliedGrossQuote");
        requireNonNegative(
                providerCumulativeFeeUsdt,
                "providerCumulativeFeeUsdt");
        requireNonNegative(
                alreadyAppliedFeeUsdt,
                "alreadyAppliedFeeUsdt");

        BigDecimal fillDelta =
                providerCumulativeFilled.subtract(alreadyAppliedFilled);
        if (fillDelta.signum() < 0) {
            throw new IllegalArgumentException(
                    "provider cumulative fill moved backwards");
        }
        if (fillDelta.compareTo(remainingBeforeApply) > 0) {
            throw new IllegalArgumentException(
                    "provider cumulative fill exceeds owned lot");
        }
        return new ReconciliationDelta(
                fillDelta,
                providerCumulativeGrossQuote.subtract(
                        alreadyAppliedGrossQuote),
                providerCumulativeFeeUsdt.subtract(
                        alreadyAppliedFeeUsdt));
    }

    public static BigDecimal buyNetQuantity(
            BigDecimal grossQuantity,
            BigDecimal signedFeeAmount,
            String feeCurrency,
            String baseCurrency,
            BigDecimal unknownFeeBufferRate) {
        requireNonNegative(grossQuantity, "grossQuantity");
        Objects.requireNonNull(baseCurrency, "baseCurrency");
        BigDecimal fee = signedFeeAmount == null
                ? BigDecimal.ZERO
                : signedFeeAmount;
        String currency = feeCurrency == null
                ? ""
                : feeCurrency.trim();
        if (!currency.isEmpty()
                && baseCurrency.equalsIgnoreCase(currency)) {
            BigDecimal net = grossQuantity.add(fee)
                    .setScale(
                            grossQuantity.scale(),
                            RoundingMode.DOWN);
            if (net.signum() <= 0) {
                throw new IllegalArgumentException(
                        "base fee consumes entire buy quantity");
            }
            return net;
        }
        if (!currency.isEmpty()) {
            return grossQuantity;
        }
        requireNonNegative(
                unknownFeeBufferRate,
                "unknownFeeBufferRate");
        if (unknownFeeBufferRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(
                    "unknown fee buffer must be below one");
        }
        return grossQuantity.multiply(
                        BigDecimal.ONE.subtract(
                                unknownFeeBufferRate))
                .setScale(
                        grossQuantity.scale(),
                        RoundingMode.DOWN);
    }

    public static BigDecimal effectiveBuyEntryPrice(
            BigDecimal averagePrice,
            BigDecimal grossQuantity,
            BigDecimal netQuantity,
            BigDecimal feeUsdt,
            String feeCurrency) {
        requireNonNegative(grossQuantity, "grossQuantity");
        requireNonNegative(netQuantity, "netQuantity");
        if (averagePrice == null || averagePrice.signum() <= 0
                || netQuantity.signum() == 0) {
            throw new IllegalArgumentException(
                    "buy price and net quantity must be positive");
        }
        BigDecimal cashCost = averagePrice.multiply(grossQuantity);
        if ("USDT".equalsIgnoreCase(feeCurrency)) {
            cashCost = cashCost.add(
                    feeUsdt == null
                            ? BigDecimal.ZERO
                            : feeUsdt);
        }
        return cashCost.divide(
                netQuantity,
                8,
                RoundingMode.HALF_UP);
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

    public record ReconciliationDelta(
            BigDecimal fillQuantity,
            BigDecimal grossQuoteAmount,
            BigDecimal feeUsdt) {
    }
}
