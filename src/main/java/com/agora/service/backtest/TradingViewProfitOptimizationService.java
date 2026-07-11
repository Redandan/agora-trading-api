package com.agora.service.backtest;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class TradingViewProfitOptimizationService {

    private static final List<Integer> HORIZONS = List.of(90, 180, 270, 365);
    private static final double BUY_NOTIONAL_USDT = 10.0;
    private static final double MAX_BASE_EXPOSURE_USDT = 250.0;
    private static final double MIN_ORDER_NOTIONAL_USDT = 10.0;
    private static final double EMERGENCY_DRAWDOWN_WARNING_PCT = 0.12;

    public String compareCurrentCandidate(String symbol, String source, double feeRate,
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
        report.append("baseline=LIVE_ONE_ORDER_PER_BAR candidate=SHADOW_PINE_QUANTITY_TIERED_PER_BAR\n");
        report.append("buyPointPolicy=PRESERVE_ALL_TRADINGVIEW_INTENTS\n");
        report.append("productionOrderPolicy=FIXED_10_USDT_FULL_SLICE maxBaseExposureUsdt=250.00\n");
        report.append("baselineExitPolicy=HOLD_BTC_BASE_NO_OCO_NO_AUTO_SELL\n");
        report.append("candidatePolicy=PINE_QUANTITY_1000_2000_5000_TO_1X_2X_5X_ONE_ORDER_PER_BAR_NO_AUTO_SELL\n");
        report.append("candidateLookahead=false candidateAddsBuyPoints=false candidateDeletesBuyPoints=false\n");

        SimulationPair horizon365 = null;
        SimulationPair horizon180 = null;
        for (int days : HORIZONS) {
            SimulationPair pair = simulateWindow(end.minusDays(days), allBars, allIntents, feeRate);
            if (days == 180) horizon180 = pair;
            if (days == 365) horizon365 = pair;
            report.append(formatWindow(days, pair));
        }

        WalkForwardResult walkForward = walkForward(end, allBars, allIntents, feeRate);
        for (FoldComparison fold : walkForward.folds()) {
            report.append(String.format(Locale.ROOT,
                    "walkForwardFold=%d start=%s endExclusive=%s intents=%d bars=%d " +
                            "baselinePnl=%.2f baselineReturn=%.2f%% candidatePnl=%.2f candidateReturn=%.2f%%%n",
                    fold.fold(), fold.start(), fold.endExclusive(), fold.intentCount(), fold.orderBarCount(),
                    fold.baselinePnl(), fold.baselineReturn() * 100.0,
                    fold.candidatePnl(), fold.candidateReturn() * 100.0));
        }
        report.append(String.format(Locale.ROOT,
                "walkForwardSummary=baselinePositiveFolds=%d/5 candidatePositiveFolds=%d/5%n",
                walkForward.baselinePositiveFolds(), walkForward.candidatePositiveFolds()));
        SimulationPair stress = simulateWindow(end.minusDays(365), allBars, allIntents,
                Math.max(0.002, feeRate * 2.0));
        double improvementPp = horizon365 == null ? Double.NEGATIVE_INFINITY
                : (horizon365.candidate().deployedReturn() - horizon365.baseline().deployedReturn()) * 100.0;
        boolean positive180 = horizon180 != null && horizon180.candidate().totalPnl() > 0.0;
        boolean positive365 = horizon365 != null && horizon365.candidate().totalPnl() > 0.0;
        boolean improvementGate = improvementPp >= 5.0;
        boolean drawdownGate = horizon365 != null && horizon365.candidate().maxInventoryDrawdownPct() <= 0.15;
        boolean walkForwardGate = walkForward.candidatePositiveFolds() >= 4;
        boolean stressGate = stress.candidate().totalPnl() >= 0.0;
        boolean accepted = positive180 && positive365 && improvementGate && drawdownGate
                && walkForwardGate && stressGate;

        report.append(String.format(Locale.ROOT,
                "gates: positive180=%s positive365=%s improvement365Pp=%.2f improvementAtLeast5Pp=%s " +
                        "maxDrawdownAtMost15Pct=%s walkForwardPositiveFolds=%d/5 walkForwardGate=%s " +
                        "stressFeeRate=%.4f stressBaselinePnl=%.2f stressBaselineReturn=%.2f%% " +
                        "stressCandidatePnl=%.2f stressCandidateReturn=%.2f%% stressPnl=%.2f " +
                        "stressNonNegative=%s%n",
                positive180, positive365, improvementPp, improvementGate, drawdownGate,
                walkForward.candidatePositiveFolds(), walkForwardGate, Math.max(0.002, feeRate * 2.0),
                stress.baseline().totalPnl(), stress.baseline().deployedReturn() * 100.0,
                stress.candidate().totalPnl(), stress.candidate().deployedReturn() * 100.0,
                stress.candidate().totalPnl(), stressGate));
        report.append("candidateVerdict=").append(accepted ? "PASS_FOR_NEW_LIVE_AUTHORIZATION_REVIEW" : "REJECTED").append("\n");
        report.append("candidatePromotionAllowed=false\n");
        report.append("nextCandidate=").append(accepted ? "NONE_AWAIT_EXPLICIT_AUTHORIZATION"
                : "DEEP_DROP_252D_DRAWDOWN_TIERED_ADD_SHADOW_ONLY").append("\n");
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
        BtcBaseShadowBacktestSimulator.Config config = productionConfig(feeRate);
        return new SimulationPair(
                BtcBaseShadowBacktestSimulator.run(bars, intents, config,
                        BtcBaseShadowBacktestSimulator.ExecutionSemantics.LIVE_ONE_ORDER_PER_BAR),
                BtcBaseShadowBacktestSimulator.run(bars, intents, config,
                        BtcBaseShadowBacktestSimulator.ExecutionSemantics.SHADOW_PINE_QUANTITY_TIERED_PER_BAR));
    }

    private WalkForwardResult walkForward(LocalDateTime end,
                                          List<BtcBaseShadowBacktestSimulator.Bar> allBars,
                                          List<BtcBaseShadowBacktestSimulator.BuyIntent> allIntents,
                                          double feeRate) {
        LocalDateTime start365 = end.minusDays(365);
        int baselinePositive = 0;
        int candidatePositive = 0;
        List<FoldComparison> folds = new ArrayList<>();
        for (int fold = 0; fold < 5; fold++) {
            LocalDateTime foldStart = start365.plusDays(fold * 73L);
            LocalDateTime foldEnd = fold == 4 ? end.plusNanos(1) : foldStart.plusDays(73);
            List<BtcBaseShadowBacktestSimulator.Bar> bars = allBars.stream()
                    .filter(bar -> !bar.time().isBefore(foldStart) && bar.time().isBefore(foldEnd)).toList();
            List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = allIntents.stream()
                    .filter(intent -> !intent.time().isBefore(foldStart) && intent.time().isBefore(foldEnd)).toList();
            BtcBaseShadowBacktestSimulator.Config config = productionConfig(feeRate);
            BtcBaseShadowBacktestSimulator.Result baseline = BtcBaseShadowBacktestSimulator.run(
                    bars, intents, config,
                    BtcBaseShadowBacktestSimulator.ExecutionSemantics.LIVE_ONE_ORDER_PER_BAR);
            BtcBaseShadowBacktestSimulator.Result candidate = BtcBaseShadowBacktestSimulator.run(
                    bars, intents, config,
                    BtcBaseShadowBacktestSimulator.ExecutionSemantics.SHADOW_PINE_QUANTITY_TIERED_PER_BAR);
            if (baseline.totalPnl() > 0.0) baselinePositive++;
            if (candidate.totalPnl() > 0.0) candidatePositive++;
            folds.add(new FoldComparison(
                    fold + 1, foldStart, foldEnd,
                    baseline.orderIntentCount(), baseline.orderBarCount(),
                    baseline.totalPnl(), baseline.deployedReturn(),
                    candidate.totalPnl(), candidate.deployedReturn()));
        }
        return new WalkForwardResult(baselinePositive, candidatePositive, List.copyOf(folds));
    }

    private String formatWindow(int days, SimulationPair pair) {
        BtcBaseShadowBacktestSimulator.Result baseline = pair.baseline();
        BtcBaseShadowBacktestSimulator.Result candidate = pair.candidate();
        return String.format(Locale.ROOT,
                "window=%dd intents=%d bars=%d baselineInvested=%.2f baselinePnl=%.2f baselineReturn=%.2f%% " +
                        "baselineMaxDrawdown=%.2f%% baselineExecuted=%d baselineShadowOnly=%d baselineSkipped=%d " +
                        "baselineFeesPaid=%.2f baselineFinalExitFee=%.2f baselineTotalFeesEstimate=%.2f " +
                        "baselineRealized=%.2f baselineUnrealized=%.2f baselineTakeProfitReductions=%d " +
                        "candidateInvested=%.2f candidatePnl=%.2f candidateReturn=%.2f%% " +
                        "candidateMaxDrawdown=%.2f%% candidateExecuted=%d candidateShadowOnly=%d candidateSkipped=%d " +
                        "candidateUpsizedBars=%d " +
                        "candidateFeesPaid=%.2f candidateFinalExitFee=%.2f candidateTotalFeesEstimate=%.2f " +
                        "candidateRealized=%.2f candidateUnrealized=%.2f candidateTakeProfitReductions=%d " +
                        "diffPnl=%.2f diffReturnPp=%.2f%n",
                days, baseline.orderIntentCount(), baseline.orderBarCount(), baseline.totalGrossBuys(),
                baseline.totalPnl(), baseline.deployedReturn() * 100.0,
                baseline.maxInventoryDrawdownPct() * 100.0,
                baseline.executedBuys(), baseline.shadowOnlyIntentCount(), baseline.skippedByCap(),
                baseline.totalFees(), baseline.finalExitFee(), baseline.totalFees() + baseline.finalExitFee(),
                baseline.realizedPnl(), baseline.unrealizedPnl(), baseline.takeProfitReductions(),
                candidate.totalGrossBuys(),
                candidate.totalPnl(), candidate.deployedReturn() * 100.0,
                candidate.maxInventoryDrawdownPct() * 100.0,
                candidate.executedBuys(), candidate.shadowOnlyIntentCount(), candidate.skippedByCap(),
                candidate.events().stream()
                        .filter(event -> "BUY".equals(event.type()))
                        .filter(event -> event.notional() > BUY_NOTIONAL_USDT + 0.0000001)
                        .count(),
                candidate.totalFees(), candidate.finalExitFee(), candidate.totalFees() + candidate.finalExitFee(),
                candidate.realizedPnl(), candidate.unrealizedPnl(), candidate.takeProfitReductions(),
                candidate.totalPnl() - baseline.totalPnl(),
                (candidate.deployedReturn() - baseline.deployedReturn()) * 100.0);
    }

    private BtcBaseShadowBacktestSimulator.Config productionConfig(double feeRate) {
        return new BtcBaseShadowBacktestSimulator.Config(
                BUY_NOTIONAL_USDT,
                MAX_BASE_EXPOSURE_USDT,
                MIN_ORDER_NOTIONAL_USDT,
                feeRate,
                0.0,
                0.0,
                EMERGENCY_DRAWDOWN_WARNING_PCT,
                0.0);
    }

    private record SimulationPair(BtcBaseShadowBacktestSimulator.Result baseline,
                                  BtcBaseShadowBacktestSimulator.Result candidate) {
    }

    private record WalkForwardResult(int baselinePositiveFolds,
                                     int candidatePositiveFolds,
                                     List<FoldComparison> folds) {
    }

    private record FoldComparison(int fold,
                                  LocalDateTime start,
                                  LocalDateTime endExclusive,
                                  int intentCount,
                                  int orderBarCount,
                                  double baselinePnl,
                                  double baselineReturn,
                                  double candidatePnl,
                                  double candidateReturn) {
    }
}
