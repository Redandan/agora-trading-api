package com.agora.service.tradingview;

import com.agora.service.backtest.LiveSignalContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic PAPER state machine for score-buy entries with per-lot,
 * profit-only automatic exits.
 *
 * <p>Every closed daily score-buy signal is filled at the next daily open and
 * becomes an independently tracked lot. A lot queues an exit only after its
 * estimated net liquidation return reaches the configured target. The queued
 * exit is filled at the next daily open only when the configured minimum net
 * profit is still available; otherwise it is deferred without realizing a
 * loss.</p>
 *
 * <p>The engine is used only by the registered PAPER lane. It has no exchange
 * adapter and cannot place a real order.</p>
 */
@Component
public final class TradingViewScoreBuyAutoExitPaperEngine {

    private static final int QUANTITY_SCALE = 12;
    private static final int MONEY_SCALE = 8;
    private static final int RETURN_SCALE = 8;

    public State initialState() {
        return new State(
                null,
                null,
                BigDecimal.ZERO,
                0,
                "",
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0,
                0,
                0,
                0,
                0,
                BigDecimal.ZERO);
    }

    public StepResult step(State previous, PaperBar bar, Policy policy) {
        State state = previous == null ? initialState() : previous;
        validate(state, bar, policy);

        List<PaperEvent> events = new ArrayList<>();
        List<Lot> lots = new ArrayList<>();
        BigDecimal totalBuyNotional = state.totalBuyNotionalUsdt();
        BigDecimal totalSellProceeds = state.totalSellProceedsUsdt();
        BigDecimal realizedPnl = state.realizedPnlUsdt();
        BigDecimal totalFees = state.totalFeesUsdt();
        int buyFillCount = state.buyFillCount();
        int sellFillCount = state.sellFillCount();
        int winningExitLotCount = state.winningExitLotCount();
        int deferredExitCount = state.deferredExitCount();

        for (Lot lot : state.openLots()) {
            if (lot.exitQueuedAtBarOpenTime() == null) {
                lots.add(lot);
                continue;
            }

            BigDecimal sellPrice = adverseSellPrice(bar.openPrice(), policy.slippageRate());
            BigDecimal grossProceeds = lot.quantity().multiply(sellPrice)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal sellFee = grossProceeds.multiply(policy.feeRate())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal netProceeds = grossProceeds.subtract(sellFee);
            BigDecimal lotPnl = netProceeds.subtract(lot.grossBuyNotionalUsdt())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal realizedReturn = returnOnCost(
                    netProceeds, lot.grossBuyNotionalUsdt());

            if (realizedReturn.compareTo(policy.minRealizedNetProfit()) < 0) {
                deferredExitCount++;
                lots.add(lot.withExitQueuedAt(null));
                events.add(new PaperEvent(
                        "PAPER_EXIT_DEFERRED_OPEN_BELOW_PROFIT_FLOOR",
                        bar.openTime(),
                        lot.signalBarOpenTime(),
                        lot.lotId(),
                        BigDecimal.ZERO,
                        sellPrice,
                        lot.quantity(),
                        BigDecimal.ZERO,
                        lotPnl,
                        realizedReturn,
                        lot.reasons(),
                        "MIN_REALIZED_NET_PROFIT_NOT_MET"));
                continue;
            }

            totalSellProceeds = totalSellProceeds.add(netProceeds);
            realizedPnl = realizedPnl.add(lotPnl);
            totalFees = totalFees.add(sellFee);
            sellFillCount++;
            if (lotPnl.signum() > 0) {
                winningExitLotCount++;
            }
            events.add(new PaperEvent(
                    "PAPER_SELL_FILL_NEXT_DAILY_OPEN",
                    bar.openTime(),
                    lot.signalBarOpenTime(),
                    lot.lotId(),
                    lot.grossBuyNotionalUsdt(),
                    sellPrice,
                    lot.quantity(),
                    sellFee,
                    lotPnl,
                    realizedReturn,
                    lot.reasons(),
                    ""));
        }

        if (positive(state.pendingBuyNotionalUsdt())) {
            BigDecimal buyPrice = adverseBuyPrice(bar.openPrice(), policy.slippageRate());
            BigDecimal buyFee = state.pendingBuyNotionalUsdt()
                    .multiply(policy.feeRate())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal netBuyNotional = state.pendingBuyNotionalUsdt().subtract(buyFee);
            if (!positive(netBuyNotional)) {
                throw new DataQualityException("PAPER_FEE_CONSUMES_PENDING_BUY_NOTIONAL");
            }
            BigDecimal fillQty = netBuyNotional.divide(
                    buyPrice, QUANTITY_SCALE, RoundingMode.DOWN);
            if (!positive(fillQty)) {
                throw new DataQualityException("PAPER_BUY_FILL_QUANTITY_NOT_POSITIVE");
            }
            String lotId = lotId(state.pendingSignalBarOpenTime());
            lots.add(new Lot(
                    lotId,
                    state.pendingSignalBarOpenTime(),
                    bar.openTime(),
                    state.pendingBuyNotionalUsdt(),
                    buyPrice,
                    fillQty,
                    state.pendingIntentCount(),
                    state.pendingReasons(),
                    null));
            totalBuyNotional = totalBuyNotional.add(state.pendingBuyNotionalUsdt());
            totalFees = totalFees.add(buyFee);
            buyFillCount++;
            events.add(new PaperEvent(
                    "PAPER_BUY_FILL_NEXT_DAILY_OPEN",
                    bar.openTime(),
                    state.pendingSignalBarOpenTime(),
                    lotId,
                    state.pendingBuyNotionalUsdt(),
                    buyPrice,
                    fillQty,
                    buyFee,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    state.pendingReasons(),
                    ""));
        }

        List<Lot> evaluatedLots = new ArrayList<>();
        for (Lot lot : lots) {
            if (lot.exitQueuedAtBarOpenTime() != null) {
                evaluatedLots.add(lot);
                continue;
            }
            BigDecimal expectedNetProceeds = estimatedNetSellProceeds(
                    lot.quantity(), bar.closePrice(), policy);
            BigDecimal expectedNetReturn = returnOnCost(
                    expectedNetProceeds, lot.grossBuyNotionalUsdt());
            if (expectedNetReturn.compareTo(policy.netProfitTrigger()) >= 0) {
                Lot queued = lot.withExitQueuedAt(bar.openTime());
                evaluatedLots.add(queued);
                events.add(new PaperEvent(
                        "PAPER_EXIT_QUEUED_NEXT_DAILY_OPEN",
                        bar.openTime(),
                        lot.signalBarOpenTime(),
                        lot.lotId(),
                        lot.grossBuyNotionalUsdt(),
                        adverseSellPrice(bar.closePrice(), policy.slippageRate()),
                        lot.quantity(),
                        BigDecimal.ZERO,
                        expectedNetProceeds.subtract(lot.grossBuyNotionalUsdt()),
                        expectedNetReturn,
                        lot.reasons(),
                        ""));
            } else {
                evaluatedLots.add(lot);
            }
        }

        LocalDateTime pendingSignalBarOpenTime = null;
        BigDecimal pendingBuyNotional = BigDecimal.ZERO;
        int pendingIntentCount = 0;
        String pendingReasons = "";
        int queuedBuyBars = state.queuedBuyBars();
        int blockedBuyBars = state.blockedBuyBars();

        if (!bar.intents().isEmpty()) {
            TradingViewAccumulationOrderPlanner.Plan plan =
                    TradingViewAccumulationOrderPlanner.plan(
                            bar.intents(),
                            policy.baseNotionalUsdt(),
                            policy.maxOrderNotionalUsdt());
            BigDecimal openCost = evaluatedLots.stream()
                    .map(Lot::grossBuyNotionalUsdt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal exposureAfterPending = openCost.add(plan.requestedNotionalUsdt());
            if (!plan.withinOrderCap()) {
                blockedBuyBars++;
                events.add(blockedBuyEvent(
                        bar, plan, "PAPER_ORDER_NOTIONAL_CAP_EXCEEDED"));
            } else if (exposureAfterPending.compareTo(policy.maxExposureUsdt()) > 0) {
                blockedBuyBars++;
                events.add(blockedBuyEvent(
                        bar, plan, "PAPER_MAX_OPEN_COST_EXCEEDED"));
            } else {
                pendingSignalBarOpenTime = bar.openTime();
                pendingBuyNotional = plan.requestedNotionalUsdt();
                pendingIntentCount = plan.intents().size();
                pendingReasons = plan.aggregateReasons();
                queuedBuyBars++;
                events.add(new PaperEvent(
                        "PAPER_BUY_INTENT_QUEUED_NEXT_DAILY_OPEN",
                        bar.openTime(),
                        bar.openTime(),
                        lotId(bar.openTime()),
                        plan.requestedNotionalUsdt(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        plan.aggregateReasons(),
                        ""));
            }
        }

        Metrics metrics = metrics(evaluatedLots, bar.closePrice(), policy);
        BigDecimal totalPnl = realizedPnl.add(metrics.unrealizedPnlUsdt())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalReturn = positive(totalBuyNotional)
                ? totalPnl.divide(totalBuyNotional, RETURN_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal currentCapitalLoss = metrics.openReturn().signum() < 0
                ? metrics.openReturn().abs()
                : BigDecimal.ZERO;
        BigDecimal maxCapitalLoss = state.maxOpenCapitalLossPct().max(currentCapitalLoss);

        State next = new State(
                bar.openTime(),
                pendingSignalBarOpenTime,
                pendingBuyNotional,
                pendingIntentCount,
                pendingReasons,
                List.copyOf(evaluatedLots),
                totalBuyNotional.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                totalSellProceeds.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                realizedPnl.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                totalFees.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                metrics.openCostUsdt(),
                metrics.inventoryQty(),
                metrics.inventoryValueUsdt(),
                metrics.unrealizedPnlUsdt(),
                totalPnl,
                totalReturn,
                buyFillCount,
                sellFillCount,
                winningExitLotCount,
                deferredExitCount,
                queuedBuyBars,
                blockedBuyBars,
                maxCapitalLoss);
        return new StepResult(next, List.copyOf(events));
    }

    private Metrics metrics(List<Lot> lots, BigDecimal closePrice, Policy policy) {
        BigDecimal openCost = BigDecimal.ZERO;
        BigDecimal inventoryQty = BigDecimal.ZERO;
        BigDecimal inventoryValue = BigDecimal.ZERO;
        for (Lot lot : lots) {
            openCost = openCost.add(lot.grossBuyNotionalUsdt());
            inventoryQty = inventoryQty.add(lot.quantity());
            inventoryValue = inventoryValue.add(
                    estimatedNetSellProceeds(lot.quantity(), closePrice, policy));
        }
        BigDecimal unrealizedPnl = inventoryValue.subtract(openCost)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal openReturn = positive(openCost)
                ? unrealizedPnl.divide(openCost, RETURN_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new Metrics(
                openCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                inventoryQty.setScale(QUANTITY_SCALE, RoundingMode.DOWN),
                inventoryValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                unrealizedPnl,
                openReturn);
    }

    private BigDecimal estimatedNetSellProceeds(BigDecimal quantity,
                                                BigDecimal referencePrice,
                                                Policy policy) {
        BigDecimal sellPrice = adverseSellPrice(referencePrice, policy.slippageRate());
        BigDecimal gross = quantity.multiply(sellPrice)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return gross.subtract(gross.multiply(policy.feeRate())
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal adverseBuyPrice(BigDecimal price, BigDecimal slippageRate) {
        return price.multiply(BigDecimal.ONE.add(slippageRate))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal adverseSellPrice(BigDecimal price, BigDecimal slippageRate) {
        return price.multiply(BigDecimal.ONE.subtract(slippageRate))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal returnOnCost(BigDecimal value, BigDecimal cost) {
        if (!positive(cost)) {
            return BigDecimal.ZERO;
        }
        return value.subtract(cost)
                .divide(cost, RETURN_SCALE, RoundingMode.HALF_UP);
    }

    private PaperEvent blockedBuyEvent(PaperBar bar,
                                       TradingViewAccumulationOrderPlanner.Plan plan,
                                       String blocker) {
        return new PaperEvent(
                "PAPER_BUY_INTENT_BLOCKED",
                bar.openTime(),
                bar.openTime(),
                lotId(bar.openTime()),
                plan.requestedNotionalUsdt(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                plan.aggregateReasons(),
                blocker);
    }

    private void validate(State state, PaperBar bar, Policy policy) {
        if (bar == null || bar.openTime() == null) {
            throw new DataQualityException("PAPER_BAR_TIME_MISSING");
        }
        if (!positive(bar.openPrice()) || !positive(bar.closePrice())) {
            throw new DataQualityException("PAPER_BAR_PRICE_INVALID");
        }
        if (policy == null
                || !positive(policy.baseNotionalUsdt())
                || !positive(policy.maxOrderNotionalUsdt())
                || !positive(policy.maxExposureUsdt())
                || invalidRate(policy.feeRate())
                || invalidRate(policy.slippageRate())
                || !positive(policy.netProfitTrigger())
                || policy.netProfitTrigger().compareTo(BigDecimal.ONE) >= 0
                || policy.minRealizedNetProfit() == null
                || policy.minRealizedNetProfit().signum() < 0
                || policy.minRealizedNetProfit().compareTo(policy.netProfitTrigger()) > 0) {
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
        if (positive(state.pendingBuyNotionalUsdt())
                && (state.pendingSignalBarOpenTime() == null
                || !state.pendingSignalBarOpenTime().isBefore(bar.openTime()))) {
            throw new DataQualityException("PAPER_PENDING_BUY_TIME_INVALID");
        }
    }

    private boolean invalidRate(BigDecimal value) {
        return value == null
                || value.signum() < 0
                || value.compareTo(BigDecimal.ONE) >= 0;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String lotId(LocalDateTime signalBarOpenTime) {
        return signalBarOpenTime == null
                ? ""
                : "TV508V2-" + signalBarOpenTime.toString();
    }

    public record Policy(
            BigDecimal baseNotionalUsdt,
            BigDecimal maxOrderNotionalUsdt,
            BigDecimal maxExposureUsdt,
            BigDecimal feeRate,
            BigDecimal slippageRate,
            BigDecimal netProfitTrigger,
            BigDecimal minRealizedNetProfit
    ) {
        public static Policy paperDefault(BigDecimal baseNotionalUsdt,
                                          BigDecimal maxOrderNotionalUsdt,
                                          BigDecimal maxExposureUsdt) {
            return new Policy(
                    baseNotionalUsdt,
                    maxOrderNotionalUsdt,
                    maxExposureUsdt,
                    TradingViewScoreBuyAutoExitStrategyContract.PAPER_FEE_RATE,
                    TradingViewScoreBuyAutoExitStrategyContract.PAPER_ADVERSE_SLIPPAGE_RATE,
                    TradingViewScoreBuyAutoExitStrategyContract.NET_PROFIT_TRIGGER,
                    TradingViewScoreBuyAutoExitStrategyContract.MIN_REALIZED_NET_PROFIT);
        }
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

    public record Lot(
            String lotId,
            LocalDateTime signalBarOpenTime,
            LocalDateTime buyFillBarOpenTime,
            BigDecimal grossBuyNotionalUsdt,
            BigDecimal buyFillPrice,
            BigDecimal quantity,
            int intentCount,
            String reasons,
            LocalDateTime exitQueuedAtBarOpenTime
    ) {
        public Lot withExitQueuedAt(LocalDateTime queuedAt) {
            return new Lot(
                    lotId,
                    signalBarOpenTime,
                    buyFillBarOpenTime,
                    grossBuyNotionalUsdt,
                    buyFillPrice,
                    quantity,
                    intentCount,
                    reasons,
                    queuedAt);
        }
    }

    public record State(
            LocalDateTime lastProcessedBarOpenTime,
            LocalDateTime pendingSignalBarOpenTime,
            BigDecimal pendingBuyNotionalUsdt,
            int pendingIntentCount,
            String pendingReasons,
            List<Lot> openLots,
            BigDecimal totalBuyNotionalUsdt,
            BigDecimal totalSellProceedsUsdt,
            BigDecimal realizedPnlUsdt,
            BigDecimal totalFeesUsdt,
            BigDecimal openCostUsdt,
            BigDecimal inventoryQty,
            BigDecimal inventoryValueUsdt,
            BigDecimal unrealizedPnlUsdt,
            BigDecimal totalPnlUsdt,
            BigDecimal totalReturn,
            int buyFillCount,
            int sellFillCount,
            int winningExitLotCount,
            int deferredExitCount,
            int queuedBuyBars,
            int blockedBuyBars,
            BigDecimal maxOpenCapitalLossPct
    ) {
        public State {
            openLots = openLots == null ? List.of() : List.copyOf(openLots);
        }
    }

    public record PaperEvent(
            String type,
            LocalDateTime eventBarOpenTime,
            LocalDateTime signalBarOpenTime,
            String lotId,
            BigDecimal notionalUsdt,
            BigDecimal fillPrice,
            BigDecimal fillQty,
            BigDecimal feeUsdt,
            BigDecimal netPnlUsdt,
            BigDecimal netReturn,
            String reasons,
            String blocker
    ) {
    }

    public record StepResult(State state, List<PaperEvent> events) {
    }

    private record Metrics(
            BigDecimal openCostUsdt,
            BigDecimal inventoryQty,
            BigDecimal inventoryValueUsdt,
            BigDecimal unrealizedPnlUsdt,
            BigDecimal openReturn
    ) {
    }

    public static final class DataQualityException extends RuntimeException {
        public DataQualityException(String message) {
            super(message);
        }
    }
}
