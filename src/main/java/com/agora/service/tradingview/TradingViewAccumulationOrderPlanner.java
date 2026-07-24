package com.agora.service.tradingview;

import com.agora.service.backtest.LiveSignalContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Converts the captured Pine quantities into scaled BTC spot accumulation
 * notional. Pine quantities are relative weights (1000/2000/5000 -> 1/2/5),
 * never literal live BTC quantities.
 */
public final class TradingViewAccumulationOrderPlanner {

    private static final BigDecimal PINE_BASE_QUANTITY = new BigDecimal("1000");

    private TradingViewAccumulationOrderPlanner() {
    }

    public static Plan plan(List<LiveSignalContext.OrderIntent> intents,
                            BigDecimal baseNotionalUsdt,
                            BigDecimal maxOrderNotionalUsdt) {
        if (intents == null || intents.isEmpty()) {
            throw new IllegalArgumentException("at least one TradingView order intent is required");
        }
        if (!positive(baseNotionalUsdt)) {
            throw new IllegalArgumentException("baseNotionalUsdt must be positive");
        }
        if (!positive(maxOrderNotionalUsdt)) {
            throw new IllegalArgumentException("maxOrderNotionalUsdt must be positive");
        }

        List<IntentPlan> intentPlans = intents.stream()
                .filter(Objects::nonNull)
                .map(intent -> new IntentPlan(
                        intent.reason(),
                        intent.label(),
                        intent.quantity(),
                        weight(intent.quantity()),
                        baseNotionalUsdt.multiply(weight(intent.quantity()))
                                .setScale(2, RoundingMode.HALF_UP)))
                .toList();
        if (intentPlans.isEmpty()) {
            throw new IllegalArgumentException("at least one non-null TradingView order intent is required");
        }

        BigDecimal aggregateWeight = intentPlans.stream()
                .map(IntentPlan::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal requestedNotional = baseNotionalUsdt.multiply(aggregateWeight)
                .setScale(2, RoundingMode.HALF_UP);
        return new Plan(
                intentPlans,
                aggregateWeight.stripTrailingZeros(),
                requestedNotional,
                maxOrderNotionalUsdt,
                requestedNotional.compareTo(maxOrderNotionalUsdt) <= 0,
                intentPlans.stream().map(IntentPlan::reason).collect(Collectors.joining(",")));
    }

    static BigDecimal weight(double pineQuantity) {
        if (!Double.isFinite(pineQuantity) || pineQuantity <= 0.0) {
            throw new IllegalArgumentException("TradingView Pine quantity must be positive and finite");
        }
        BigDecimal quantity = BigDecimal.valueOf(pineQuantity);
        BigDecimal[] division = quantity.divideAndRemainder(PINE_BASE_QUANTITY);
        if (division[1].compareTo(BigDecimal.ZERO) != 0
                || division[0].compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException(
                    "TradingView Pine quantity must be a positive multiple of 1000");
        }
        return division[0];
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    public record Plan(
            List<IntentPlan> intents,
            BigDecimal aggregateWeight,
            BigDecimal requestedNotionalUsdt,
            BigDecimal maxOrderNotionalUsdt,
            boolean withinOrderCap,
            String aggregateReasons
    ) {
    }

    public record IntentPlan(
            String reason,
            String label,
            double pineQuantity,
            BigDecimal weight,
            BigDecimal requestedNotionalUsdt
    ) {
    }
}
