package com.agora.service.backtest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BtcBaseShadowBacktestSimulator {

    private BtcBaseShadowBacktestSimulator() {
    }

    public static Result run(List<Bar> bars, List<BuyIntent> buyIntents, Config config) {
        return run(bars, buyIntents, config, ExecutionSemantics.SHADOW_ALL_INTENTS);
    }

    public static Result run(List<Bar> bars, List<BuyIntent> buyIntents, Config config,
                             ExecutionSemantics executionSemantics) {
        Config cfg = config == null ? Config.defaults() : config.normalized();
        ExecutionSemantics semantics = executionSemantics == null
                ? ExecutionSemantics.SHADOW_ALL_INTENTS
                : executionSemantics;
        if (bars == null || bars.isEmpty()) {
            return Result.empty(cfg, semantics);
        }

        Map<LocalDateTime, List<BuyIntent>> intentsByTime = new LinkedHashMap<>();
        if (buyIntents != null) {
            for (BuyIntent intent : buyIntents) {
                if (intent == null || intent.time() == null) {
                    continue;
                }
                intentsByTime.computeIfAbsent(intent.time(), ignored -> new ArrayList<>()).add(intent);
            }
        }

        State state = new State(cfg.takeProfitReducePct());
        List<Event> events = new ArrayList<>();
        int orderIntentCount = buyIntents == null ? 0 : buyIntents.size();

        for (Bar bar : bars) {
            if (bar == null || bar.time() == null || bar.closePrice() <= 0.0) {
                continue;
            }

            List<BuyIntent> barIntents = intentsByTime.getOrDefault(bar.time(), List.of());
            if (!barIntents.isEmpty()) {
                state.orderBars.putIfAbsent(bar.time(), Boolean.TRUE);
                executeBarIntents(bar, barIntents, cfg, semantics, state, events);
            }

            evaluateReductions(bar, cfg, state, events);
            updateDrawdown(bar, cfg, state);
        }

        Bar finalBar = lastValidBar(bars);
        double finalClose = finalBar == null ? 0.0 : finalBar.closePrice();
        double finalValue = state.quantity * finalClose;
        double finalExitFee = finalValue * cfg.feeRate();
        double unrealizedPnl = finalValue - finalExitFee - state.costBasis;
        double totalPnl = state.realizedPnl + unrealizedPnl;
        double deployedReturn = state.totalGrossBuys > 0.0 ? totalPnl / state.totalGrossBuys : 0.0;
        double avgCost = state.quantity > 0.0 ? state.costBasis / state.quantity : 0.0;

        return new Result(
                cfg,
                semantics,
                finalBar == null ? null : finalBar.time(),
                finalClose,
                orderIntentCount,
                state.orderBars.size(),
                state.executedBuys,
                state.shadowOnlyIntents,
                state.aggregatedOrderBars,
                state.cappedBuys,
                state.skippedByCap,
                state.takeProfitReductions,
                state.emergencyWarnings,
                state.emergencyReductions,
                state.totalGrossBuys,
                state.maxCostBasis,
                state.costBasis,
                state.quantity,
                avgCost,
                state.totalFees,
                state.realizedPnl,
                unrealizedPnl,
                totalPnl,
                deployedReturn,
                state.maxInventoryDrawdownPct,
                finalValue,
                finalExitFee,
                List.copyOf(events));
    }

    private static void executeBarIntents(Bar bar, List<BuyIntent> intents, Config cfg,
                                          ExecutionSemantics semantics, State state, List<Event> events) {
        switch (semantics) {
            case SHADOW_ALL_INTENTS -> intents.forEach(intent ->
                    executeBuy(bar, intent, cfg.buyNotionalUsdt(), cfg, state, events));
            case LIVE_ONE_ORDER_PER_BAR -> {
                executeBuy(bar, intents.get(0), cfg.buyNotionalUsdt(), cfg, state, events);
                for (int i = 1; i < intents.size(); i++) {
                    state.shadowOnlyIntents++;
                    events.add(event(bar.time(), "SHADOW_ONLY_INTENT", bar.closePrice(), 0.0, 0.0, state,
                            "same bar intent retained but not executed under LIVE_ONE_ORDER_PER_BAR", intents.get(i)));
                }
            }
            case SHADOW_AGGREGATE_PER_BAR -> {
                double requestedNotional = cfg.buyNotionalUsdt() * intents.size();
                BuyIntent first = intents.get(0);
                double tvQuantity = intents.stream().mapToDouble(BuyIntent::tradingViewQuantity).sum();
                BuyIntent aggregate = new BuyIntent(first.time(), tvQuantity,
                        "AGGREGATED_INTENTS(" + intents.size() + ")", first.label(), first.signal());
                executeBuy(bar, aggregate, requestedNotional, cfg, state, events);
                state.aggregatedOrderBars++;
                state.shadowOnlyIntents += Math.max(0, intents.size() - 1);
            }
        }
    }

    private static void executeBuy(Bar bar, BuyIntent intent, double requestedNotional,
                                   Config cfg, State state, List<Event> events) {
        double remainingCapacity = cfg.maxBaseExposureUsdt() - state.costBasis;
        double buyNotional = Math.min(requestedNotional, remainingCapacity);
        if (buyNotional <= 0.0 || buyNotional < cfg.minBuyNotionalUsdt()) {
            state.skippedByCap++;
            events.add(event(bar.time(), "SKIP_CAP", bar.closePrice(), 0.0, 0.0, state,
                    "max base exposure reached", intent));
            return;
        }
        if (buyNotional + 0.0000001 < requestedNotional) {
            state.cappedBuys++;
        }

        double fee = buyNotional * cfg.feeRate();
        double quantity = (buyNotional - fee) / bar.closePrice();
        state.quantity += quantity;
        state.costBasis += buyNotional;
        state.totalGrossBuys += buyNotional;
        state.totalFees += fee;
        state.maxCostBasis = Math.max(state.maxCostBasis, state.costBasis);
        state.executedBuys++;
        state.emergencyReductionArmed = true;
        events.add(event(bar.time(), "BUY", bar.closePrice(), buyNotional, quantity, state,
                intent.reason(), intent));
    }

    private static void evaluateReductions(Bar bar, Config cfg, State state, List<Event> events) {
        if (state.quantity <= 0.0 || state.costBasis <= 0.0) {
            return;
        }

        double returnPct = inventoryReturnPct(bar.closePrice(), cfg.feeRate(), state);
        if (cfg.takeProfitReducePct() > 0.0 && cfg.takeProfitReduceFraction() > 0.0) {
            while (state.quantity > 0.0 && state.costBasis > 0.0 && returnPct >= state.nextTakeProfitReducePct) {
                executeSell(bar, cfg, state, events, "REDUCE_TAKE_PROFIT",
                        state.nextTakeProfitReducePct, cfg.takeProfitReduceFraction());
                state.takeProfitReductions++;
                state.nextTakeProfitReducePct += cfg.takeProfitReducePct();
                returnPct = inventoryReturnPct(bar.closePrice(), cfg.feeRate(), state);
            }
        }

        if (cfg.emergencyDrawdownPct() > 0.0 && returnPct <= -cfg.emergencyDrawdownPct()) {
            state.emergencyWarnings++;
            if (cfg.emergencyReduceFraction() > 0.0 && state.emergencyReductionArmed) {
                executeSell(bar, cfg, state, events, "REDUCE_DRAWDOWN",
                        -cfg.emergencyDrawdownPct(), cfg.emergencyReduceFraction());
                state.emergencyReductions++;
                state.emergencyReductionArmed = false;
            }
        }
    }

    private static void executeSell(Bar bar, Config cfg, State state, List<Event> events,
                                    String type, double threshold, double fraction) {
        double sellFraction = Math.max(0.0, Math.min(1.0, fraction));
        if (sellFraction <= 0.0 || state.quantity <= 0.0) {
            return;
        }
        double quantityBefore = state.quantity;
        double sellQty = quantityBefore * sellFraction;
        double grossProceeds = sellQty * bar.closePrice();
        double fee = grossProceeds * cfg.feeRate();
        double releasedCostBasis = state.costBasis * (sellQty / quantityBefore);
        double netProceeds = grossProceeds - fee;
        double realized = netProceeds - releasedCostBasis;

        state.quantity -= sellQty;
        state.costBasis -= releasedCostBasis;
        state.totalFees += fee;
        state.realizedPnl += realized;
        if (state.quantity < 0.0000000001) {
            state.quantity = 0.0;
            state.costBasis = 0.0;
        }
        events.add(event(bar.time(), type, bar.closePrice(), grossProceeds, sellQty, state,
                String.format(Locale.ROOT, "threshold=%.4f realized=%.4f", threshold, realized), null));
    }

    private static void updateDrawdown(Bar bar, Config cfg, State state) {
        if (state.quantity <= 0.0 || state.costBasis <= 0.0) {
            return;
        }
        double returnPct = inventoryReturnPct(bar.closePrice(), cfg.feeRate(), state);
        if (returnPct < 0.0) {
            state.maxInventoryDrawdownPct = Math.max(state.maxInventoryDrawdownPct, -returnPct);
        }
    }

    private static double inventoryReturnPct(double price, double feeRate, State state) {
        if (state.costBasis <= 0.0) {
            return 0.0;
        }
        double value = state.quantity * price;
        double exitFee = value * feeRate;
        return (value - exitFee - state.costBasis) / state.costBasis;
    }

    private static Event event(LocalDateTime time, String type, double price, double notional, double quantity,
                               State state, String reason, BuyIntent intent) {
        double avgCost = state.quantity > 0.0 ? state.costBasis / state.quantity : 0.0;
        return new Event(
                time,
                type,
                price,
                notional,
                quantity,
                state.costBasis,
                state.quantity,
                avgCost,
                state.realizedPnl,
                reason == null ? "" : reason,
                intent == null ? "" : intent.label(),
                intent == null ? "" : intent.signal(),
                intent == null ? 0.0 : intent.tradingViewQuantity());
    }

    private static Bar lastValidBar(List<Bar> bars) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            Bar bar = bars.get(i);
            if (bar != null && bar.time() != null && bar.closePrice() > 0.0) {
                return bar;
            }
        }
        return null;
    }

    public record Config(double buyNotionalUsdt,
                         double maxBaseExposureUsdt,
                         double minBuyNotionalUsdt,
                         double feeRate,
                         double takeProfitReducePct,
                         double takeProfitReduceFraction,
                         double emergencyDrawdownPct,
                         double emergencyReduceFraction) {
        public static Config defaults() {
            return new Config(10.0, 250.0, 10.0, 0.001, 0.0, 0.0, 0.12, 0.0);
        }

        Config normalized() {
            double buy = positiveOrDefault(buyNotionalUsdt, 10.0);
            double maxExposure = Math.max(buy, positiveOrDefault(maxBaseExposureUsdt, 250.0));
            double minBuy = Math.max(0.0, Math.min(positiveOrDefault(minBuyNotionalUsdt, 1.0), buy));
            double fee = Math.max(0.0, feeRate);
            double tpPct = Math.max(0.0, takeProfitReducePct);
            double tpFraction = clamp(takeProfitReduceFraction, 0.0, 1.0);
            double emergencyPct = Math.max(0.0, emergencyDrawdownPct);
            double emergencyFraction = clamp(emergencyReduceFraction, 0.0, 1.0);
            return new Config(buy, maxExposure, minBuy, fee, tpPct, tpFraction, emergencyPct, emergencyFraction);
        }

        private static double positiveOrDefault(double value, double defaultValue) {
            return value > 0.0 ? value : defaultValue;
        }

        private static double clamp(double value, double min, double max) {
            if (Double.isNaN(value)) {
                return min;
            }
            return Math.max(min, Math.min(max, value));
        }
    }

    public record Bar(LocalDateTime time, double closePrice) {
    }

    public record BuyIntent(LocalDateTime time, double tradingViewQuantity, String reason,
                            String label, String signal) {
    }

    public enum ExecutionSemantics {
        SHADOW_ALL_INTENTS,
        LIVE_ONE_ORDER_PER_BAR,
        SHADOW_AGGREGATE_PER_BAR
    }

    public record Event(LocalDateTime time,
                        String type,
                        double price,
                        double notional,
                        double quantity,
                        double costBasisUsdt,
                        double inventoryQty,
                        double avgCost,
                        double realizedPnl,
                        String reason,
                        String label,
                        String signal,
                        double tradingViewQuantity) {
    }

    public record Result(Config config,
                         ExecutionSemantics executionSemantics,
                         LocalDateTime finalTime,
                         double finalClose,
                         int orderIntentCount,
                         int orderBarCount,
                         int executedBuys,
                         int shadowOnlyIntentCount,
                         int aggregatedOrderBars,
                         int cappedBuys,
                         int skippedByCap,
                         int takeProfitReductions,
                         int emergencyWarnings,
                         int emergencyReductions,
                         double totalGrossBuys,
                         double maxCostBasis,
                         double remainingCostBasis,
                         double inventoryQty,
                         double avgCost,
                         double totalFees,
                         double realizedPnl,
                         double unrealizedPnl,
                         double totalPnl,
                         double deployedReturn,
                         double maxInventoryDrawdownPct,
                         double finalInventoryValue,
                         double finalExitFee,
                         List<Event> events) {
        static Result empty(Config config, ExecutionSemantics executionSemantics) {
            return new Result(config == null ? Config.defaults() : config.normalized(), executionSemantics, null, 0.0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0.0, 0.0, List.of());
        }
    }

    private static final class State {
        private final Map<LocalDateTime, Boolean> orderBars = new LinkedHashMap<>();
        private int executedBuys;
        private int shadowOnlyIntents;
        private int aggregatedOrderBars;
        private int cappedBuys;
        private int skippedByCap;
        private int takeProfitReductions;
        private int emergencyWarnings;
        private int emergencyReductions;
        private double quantity;
        private double costBasis;
        private double totalGrossBuys;
        private double maxCostBasis;
        private double totalFees;
        private double realizedPnl;
        private double maxInventoryDrawdownPct;
        private double nextTakeProfitReducePct;
        private boolean emergencyReductionArmed = true;

        private State(double firstTakeProfitReducePct) {
            this.nextTakeProfitReducePct = Math.max(0.0, firstTakeProfitReducePct);
        }
    }
}
