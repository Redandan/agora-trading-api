package com.agora.service.backtest;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class TradingViewProfitOptimizationService {

    private static final List<Integer> HORIZONS = List.of(90, 180, 270, 365);

    public String compareAggregateCandidate(String symbol, String source, double feeRate,
                                            List<BtcBaseShadowBacktestSimulator.Bar> allBars,
                                            List<BtcBaseShadowBacktestSimulator.BuyIntent> allIntents) {
        if (allBars == null || allBars.isEmpty()) {
            return "status=NO_BARS candidatePromotionAllowed=false";
        }
        LocalDateTime end = allBars.get(allBars.size() - 1).time();
        StringBuilder report = new StringBuilder();
        report.append("=== TradingView Profit Optimization Shadow Report ===\n");
        report.append("boundary=READ_ONLY\n");
        report.append("symbol=").append(symbol).append(" source=").append(source)
                .append(" feeRate=").append(String.format(Locale.ROOT, "%.4f", feeRate)).append("\n");
        report.append("baseline=LIVE_ONE_ORDER_PER_BAR candidate=SHADOW_AGGREGATE_PER_BAR\n");
        report.append("buyPointPolicy=PRESERVE_ALL_TRADINGVIEW_INTENTS\n");

        SimulationPair horizon365 = null;
        SimulationPair horizon180 = null;
        for (int days : HORIZONS) {
            SimulationPair pair = simulateWindow(end.minusDays(days), allBars, allIntents, feeRate);
            if (days == 180) horizon180 = pair;
            if (days == 365) horizon365 = pair;
            report.append(formatWindow(days, pair));
        }

        int positiveFolds = walkForwardPositiveFolds(end, allBars, allIntents, feeRate);
        SimulationPair stress = simulateWindow(end.minusDays(365), allBars, allIntents,
                Math.max(0.002, feeRate * 2.0));
        double improvementPp = horizon365 == null ? Double.NEGATIVE_INFINITY
                : (horizon365.candidate().deployedReturn() - horizon365.baseline().deployedReturn()) * 100.0;
        boolean positive180 = horizon180 != null && horizon180.candidate().totalPnl() > 0.0;
        boolean positive365 = horizon365 != null && horizon365.candidate().totalPnl() > 0.0;
        boolean improvementGate = improvementPp >= 5.0;
        boolean drawdownGate = horizon365 != null && horizon365.candidate().maxInventoryDrawdownPct() <= 0.15;
        boolean walkForwardGate = positiveFolds >= 4;
        boolean stressGate = stress.candidate().totalPnl() >= 0.0;
        boolean accepted = positive180 && positive365 && improvementGate && drawdownGate
                && walkForwardGate && stressGate;

        report.append(String.format(Locale.ROOT,
                "gates: positive180=%s positive365=%s improvement365Pp=%.2f improvementAtLeast5Pp=%s " +
                        "maxDrawdownAtMost15Pct=%s walkForwardPositiveFolds=%d/5 walkForwardGate=%s " +
                        "stressFeeRate=%.4f stressPnl=%.2f stressNonNegative=%s%n",
                positive180, positive365, improvementPp, improvementGate, drawdownGate,
                positiveFolds, walkForwardGate, Math.max(0.002, feeRate * 2.0),
                stress.candidate().totalPnl(), stressGate));
        report.append("candidateVerdict=").append(accepted ? "PASS_FOR_NEW_LIVE_AUTHORIZATION_REVIEW" : "REJECTED").append("\n");
        report.append("candidatePromotionAllowed=false\n");
        report.append("nextCandidate=").append(accepted ? "NONE_AWAIT_EXPLICIT_AUTHORIZATION"
                : "DEEP_DROP_TIERED_ADD_SHADOW_ONLY").append("\n");
        report.append("notAuthorization=no order, OCO, strategy, env, DB, scheduler, grid, fund, Earn, Telegram, or exchange mutation");
        return report.toString();
    }

    private SimulationPair simulateWindow(LocalDateTime start,
                                          List<BtcBaseShadowBacktestSimulator.Bar> allBars,
                                          List<BtcBaseShadowBacktestSimulator.BuyIntent> allIntents,
                                          double feeRate) {
        List<BtcBaseShadowBacktestSimulator.Bar> bars = allBars.stream()
                .filter(bar -> !bar.time().isBefore(start)).toList();
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = allIntents.stream()
                .filter(intent -> !intent.time().isBefore(start)).toList();
        BtcBaseShadowBacktestSimulator.Config config = new BtcBaseShadowBacktestSimulator.Config(
                10.0, 250.0, 1.0, feeRate, 0.06, 0.25, 0.12, 0.0);
        return new SimulationPair(
                BtcBaseShadowBacktestSimulator.run(bars, intents, config,
                        BtcBaseShadowBacktestSimulator.ExecutionSemantics.LIVE_ONE_ORDER_PER_BAR),
                BtcBaseShadowBacktestSimulator.run(bars, intents, config,
                        BtcBaseShadowBacktestSimulator.ExecutionSemantics.SHADOW_AGGREGATE_PER_BAR));
    }

    private int walkForwardPositiveFolds(LocalDateTime end,
                                         List<BtcBaseShadowBacktestSimulator.Bar> allBars,
                                         List<BtcBaseShadowBacktestSimulator.BuyIntent> allIntents,
                                         double feeRate) {
        LocalDateTime start365 = end.minusDays(365);
        int positive = 0;
        for (int fold = 0; fold < 5; fold++) {
            LocalDateTime foldStart = start365.plusDays(fold * 73L);
            LocalDateTime foldEnd = fold == 4 ? end.plusNanos(1) : foldStart.plusDays(73);
            List<BtcBaseShadowBacktestSimulator.Bar> bars = allBars.stream()
                    .filter(bar -> !bar.time().isBefore(foldStart) && bar.time().isBefore(foldEnd)).toList();
            List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = allIntents.stream()
                    .filter(intent -> !intent.time().isBefore(foldStart) && intent.time().isBefore(foldEnd)).toList();
            BtcBaseShadowBacktestSimulator.Config config = new BtcBaseShadowBacktestSimulator.Config(
                    10.0, 250.0, 1.0, feeRate, 0.06, 0.25, 0.12, 0.0);
            BtcBaseShadowBacktestSimulator.Result result = BtcBaseShadowBacktestSimulator.run(
                    bars, intents, config,
                    BtcBaseShadowBacktestSimulator.ExecutionSemantics.SHADOW_AGGREGATE_PER_BAR);
            if (result.totalPnl() > 0.0) positive++;
        }
        return positive;
    }

    private String formatWindow(int days, SimulationPair pair) {
        BtcBaseShadowBacktestSimulator.Result baseline = pair.baseline();
        BtcBaseShadowBacktestSimulator.Result candidate = pair.candidate();
        return String.format(Locale.ROOT,
                "window=%dd intents=%d bars=%d baselineInvested=%.2f baselinePnl=%.2f baselineReturn=%.2f%% " +
                        "baselineMaxDrawdown=%.2f%% candidateInvested=%.2f candidatePnl=%.2f " +
                        "candidateReturn=%.2f%% candidateMaxDrawdown=%.2f%% diffPnl=%.2f diffReturnPp=%.2f%n",
                days, baseline.orderIntentCount(), baseline.orderBarCount(), baseline.totalGrossBuys(),
                baseline.totalPnl(), baseline.deployedReturn() * 100.0,
                baseline.maxInventoryDrawdownPct() * 100.0, candidate.totalGrossBuys(),
                candidate.totalPnl(), candidate.deployedReturn() * 100.0,
                candidate.maxInventoryDrawdownPct() * 100.0,
                candidate.totalPnl() - baseline.totalPnl(),
                (candidate.deployedReturn() - baseline.deployedReturn()) * 100.0);
    }

    private record SimulationPair(BtcBaseShadowBacktestSimulator.Result baseline,
                                  BtcBaseShadowBacktestSimulator.Result candidate) {
    }
}
