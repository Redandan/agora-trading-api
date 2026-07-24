package com.agora.service.tradingview;

import com.agora.service.backtest.LiveSignalContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Deterministic PAPER state machine for the captured TradingView daily BTC
 * accumulation strategy.
 *
 * <p>A signal is observed only after its daily bar closes and is filled at the
 * next available daily bar open. The engine has no sell, TP, SL, OCO, AI, ML,
 * ensemble, or discretionary risk input.</p>
 */
@Component
public final class TradingViewAccumulationPaperEngine {

    private static final int QUANTITY_SCALE = 12;
    private static final int MONEY_SCALE = 8;

    public State initialState() {
        return new State(
                null,
                null,
                BigDecimal.ZERO,
                0,
                "",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    public StepResult step(State previous,
                           PaperBar bar,
                           BigDecimal baseNotionalUsdt,
                           BigDecimal maxOrderNotionalUsdt,
                           BigDecimal maxExposureUsdt,
                           BigDecimal feeRate) {
        State state = previous == null ? initialState() : previous;
        validate(state, bar, baseNotionalUsdt, maxOrderNotionalUsdt, maxExposureUsdt, feeRate);

        List<PaperEvent> events = new ArrayList<>();
        BigDecimal inventoryQty = state.inventoryQty();
        BigDecimal deployedNotional = state.deployedNotionalUsdt();
        BigDecimal totalFees = state.totalFeesUsdt();
        int fillCount = state.fillCount();
        int queuedOrderBars = state.queuedOrderBars();
        int blockedOrderBars = state.blockedOrderBars();

        if (positive(state.pendingNotionalUsdt())) {
            BigDecimal fee = state.pendingNotionalUsdt()
                    .multiply(feeRate)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal netNotional = state.pendingNotionalUsdt().subtract(fee);
            if (!positive(netNotional)) {
                throw new DataQualityException("PAPER_FEE_CONSUMES_PENDING_NOTIONAL");
            }
            BigDecimal fillQty = netNotional.divide(
                    bar.openPrice(), QUANTITY_SCALE, RoundingMode.DOWN);
            if (!positive(fillQty)) {
                throw new DataQualityException("PAPER_FILL_QUANTITY_NOT_POSITIVE");
            }
            inventoryQty = inventoryQty.add(fillQty);
            deployedNotional = deployedNotional.add(state.pendingNotionalUsdt());
            totalFees = totalFees.add(fee);
            fillCount++;
            events.add(new PaperEvent(
                    "PAPER_FILL_NEXT_DAILY_OPEN",
                    bar.openTime(),
                    state.pendingSignalBarOpenTime(),
                    state.pendingNotionalUsdt(),
                    bar.openPrice(),
                    fillQty,
                    state.pendingIntentCount(),
                    state.pendingReasons(),
                    ""));
        }

        LocalDateTime pendingSignalBarOpenTime = null;
        BigDecimal pendingNotional = BigDecimal.ZERO;
        int pendingIntentCount = 0;
        String pendingReasons = "";

        if (bar.intents() != null && !bar.intents().isEmpty()) {
            TradingViewAccumulationOrderPlanner.Plan plan =
                    TradingViewAccumulationOrderPlanner.plan(
                            bar.intents(), baseNotionalUsdt, maxOrderNotionalUsdt);
            BigDecimal exposureAfterPending = deployedNotional.add(plan.requestedNotionalUsdt());
            if (!plan.withinOrderCap()) {
                blockedOrderBars++;
                events.add(blockedEvent(
                        bar,
                        plan,
                        "PAPER_ORDER_NOTIONAL_CAP_EXCEEDED"));
            } else if (exposureAfterPending.compareTo(maxExposureUsdt) > 0) {
                blockedOrderBars++;
                events.add(blockedEvent(
                        bar,
                        plan,
                        "PAPER_MAX_EXPOSURE_EXCEEDED"));
            } else {
                pendingSignalBarOpenTime = bar.openTime();
                pendingNotional = plan.requestedNotionalUsdt();
                pendingIntentCount = plan.intents().size();
                pendingReasons = plan.aggregateReasons();
                queuedOrderBars++;
                events.add(new PaperEvent(
                        "PAPER_INTENT_QUEUED_NEXT_DAILY_OPEN",
                        bar.openTime(),
                        bar.openTime(),
                        plan.requestedNotionalUsdt(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        plan.intents().size(),
                        plan.aggregateReasons(),
                        ""));
            }
        }

        BigDecimal inventoryValue = inventoryQty.multiply(bar.closePrice())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal estimatedExitFee = inventoryValue.multiply(feeRate)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal unrealizedPnl = inventoryValue
                .subtract(estimatedExitFee)
                .subtract(deployedNotional)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal deployedReturn = positive(deployedNotional)
                ? unrealizedPnl.divide(deployedNotional, MONEY_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal capitalLoss = deployedReturn.signum() < 0
                ? deployedReturn.abs()
                : BigDecimal.ZERO;
        BigDecimal maxCapitalLoss = state.maxCapitalLossPct().max(capitalLoss);
        BigDecimal averageCost = positive(inventoryQty)
                ? deployedNotional.divide(inventoryQty, MONEY_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        State next = new State(
                bar.openTime(),
                pendingSignalBarOpenTime,
                pendingNotional,
                pendingIntentCount,
                pendingReasons,
                inventoryQty.setScale(QUANTITY_SCALE, RoundingMode.DOWN),
                deployedNotional.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                totalFees.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                fillCount,
                queuedOrderBars,
                blockedOrderBars,
                averageCost,
                inventoryValue,
                estimatedExitFee,
                unrealizedPnl,
                deployedReturn,
                maxCapitalLoss);
        return new StepResult(next, List.copyOf(events));
    }

    private PaperEvent blockedEvent(PaperBar bar,
                                    TradingViewAccumulationOrderPlanner.Plan plan,
                                    String blocker) {
        return new PaperEvent(
                "PAPER_INTENT_BLOCKED",
                bar.openTime(),
                bar.openTime(),
                plan.requestedNotionalUsdt(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                plan.intents().size(),
                plan.aggregateReasons(),
                blocker);
    }

    private void validate(State state,
                          PaperBar bar,
                          BigDecimal baseNotionalUsdt,
                          BigDecimal maxOrderNotionalUsdt,
                          BigDecimal maxExposureUsdt,
                          BigDecimal feeRate) {
        if (bar == null || bar.openTime() == null) {
            throw new DataQualityException("PAPER_BAR_TIME_MISSING");
        }
        if (!positive(bar.openPrice()) || !positive(bar.closePrice())) {
            throw new DataQualityException("PAPER_BAR_PRICE_INVALID");
        }
        if (!positive(baseNotionalUsdt)
                || !positive(maxOrderNotionalUsdt)
                || !positive(maxExposureUsdt)
                || feeRate == null
                || feeRate.signum() < 0
                || feeRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new DataQualityException("PAPER_CONFIG_INVALID");
        }
        if (state.lastProcessedBarOpenTime() != null) {
            long days = ChronoUnit.DAYS.between(
                    state.lastProcessedBarOpenTime(), bar.openTime());
            if (days != 1
                    || !state.lastProcessedBarOpenTime().plusDays(1).equals(bar.openTime())) {
                throw new DataQualityException("PAPER_DAILY_BAR_SEQUENCE_GAP");
            }
        }
        if (positive(state.pendingNotionalUsdt())
                && (state.pendingSignalBarOpenTime() == null
                || !state.pendingSignalBarOpenTime().isBefore(bar.openTime()))) {
            throw new DataQualityException("PAPER_PENDING_INTENT_TIME_INVALID");
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public record PaperBar(
            LocalDateTime openTime,
            BigDecimal openPrice,
            BigDecimal closePrice,
            List<LiveSignalContext.OrderIntent> intents
    ) {
        public PaperBar {
            intents = intents == null ? List.of() : List.copyOf(intents);
        }
    }

    public record State(
            LocalDateTime lastProcessedBarOpenTime,
            LocalDateTime pendingSignalBarOpenTime,
            BigDecimal pendingNotionalUsdt,
            int pendingIntentCount,
            String pendingReasons,
            BigDecimal inventoryQty,
            BigDecimal deployedNotionalUsdt,
            BigDecimal totalFeesUsdt,
            int fillCount,
            int queuedOrderBars,
            int blockedOrderBars,
            BigDecimal averageCostUsdt,
            BigDecimal inventoryValueUsdt,
            BigDecimal estimatedExitFeeUsdt,
            BigDecimal unrealizedPnlUsdt,
            BigDecimal deployedReturn,
            BigDecimal maxCapitalLossPct
    ) {
    }

    public record PaperEvent(
            String type,
            LocalDateTime eventBarOpenTime,
            LocalDateTime signalBarOpenTime,
            BigDecimal notionalUsdt,
            BigDecimal fillPrice,
            BigDecimal fillQty,
            int intentCount,
            String reasons,
            String blocker
    ) {
    }

    public record StepResult(State state, List<PaperEvent> events) {
    }

    public static final class DataQualityException extends RuntimeException {
        public DataQualityException(String message) {
            super(message);
        }
    }
}
