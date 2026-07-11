package com.agora.service.backtest;

import com.agora.service.BtStrategyService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TimeframeAwareStrategyValidationService {

    private static final double LONG_STRESS_RELATIVE_DCA_VETO_PCT = -0.05;

    public String render(Request request, List<Bar> inputBars, List<EntryEvent> inputEvents,
                         ParityEvidence parityEvidence) {
        Request req = request.normalized();
        List<Bar> bars = normalizeBars(inputBars);
        HashSet<LocalDateTime> barTimes = new HashSet<>();
        bars.forEach(bar -> barTimes.add(bar.time()));
        List<EntryEvent> events = normalizeEvents(inputEvents).stream()
                .filter(event -> barTimes.contains(event.time()))
                .toList();
        Profile profile = Profile.fromInterval(req.intervalCode());
        ParityEvidence parity = parityEvidence == null ? ParityEvidence.notApplicable() : parityEvidence;

        if (bars.isEmpty()) {
            return noBars(req, profile, parity);
        }

        LocalDateTime finalTime = bars.get(bars.size() - 1).time();
        LocalDateTime recentStart = finalTime.minusDays(req.recentDays());
        LocalDateTime stressStart = finalTime.minusDays(req.stressDays());
        List<Bar> stressBars = bars.stream().filter(bar -> !bar.time().isBefore(stressStart)).toList();
        List<EntryEvent> recentEvents = events.stream()
                .filter(event -> !event.time().isBefore(recentStart) && !event.time().isAfter(finalTime))
                .toList();
        List<EntryEvent> stressEvents = events.stream()
                .filter(event -> !event.time().isBefore(stressStart) && !event.time().isAfter(finalTime))
                .toList();

        List<HorizonStats> horizonStats = profile.horizons().stream()
                .map(horizon -> evaluateHorizon(recentEvents, bars, horizon, req.feeRate()))
                .toList();
        HorizonStats decisionHorizon = horizonStats.get(horizonStats.size() - 1);
        RecentEdgeStatus recentStatus = recentStatus(
                recentEvents.size(), horizonStats, profile.minimumIndependentEvents());
        long positiveHorizons = horizonStats.stream().filter(this::isPositiveHorizon).count();
        StressResult stress = evaluateStress(stressBars, stressEvents, req.feeRate());

        boolean parityBlocked = req.entryParityRequired() && !parity.exactParity();
        String finalAssessment;
        String nextAction;
        if (parityBlocked) {
            finalAssessment = "BLOCKED_ENTRY_PARITY";
            nextAction = "Provide Pine source or Strategy Tester CSV and prove exact buy-point parity.";
        } else if (recentStatus == RecentEdgeStatus.INSUFFICIENT_SAMPLE) {
            finalAssessment = "WAIT_RECENT_EDGE_SAMPLE";
            nextAction = "Collect more independent entry bars and forward outcomes; do not count same-bar intents as separate samples.";
        } else if (recentStatus == RecentEdgeStatus.NO_POSITIVE_EDGE) {
            finalAssessment = "REJECT_RECENT_EDGE";
            nextAction = "Keep the strategy shadow-only and review entry/exit semantics before any live request.";
        } else if (stress.status() == LongStressStatus.INSUFFICIENT_SAMPLE) {
            finalAssessment = "WAIT_LONG_STRESS_SAMPLE";
            nextAction = "Extend closed-bar coverage before using the long-stress veto.";
        } else if (stress.status() == LongStressStatus.VETO) {
            finalAssessment = "REJECT_LONG_STRESS";
            nextAction = "Recent edge is not enough because long-stress risk is catastrophic or materially worse than fixed DCA.";
        } else {
            finalAssessment = "CANDIDATE_FOR_FORWARD_SHADOW_REVIEW_ONLY";
            nextAction = "Run forward shadow observation; a separate explicit authorization is still required for any live order.";
        }

        StringBuilder out = new StringBuilder();
        out.append("=== Timeframe-Aware BTC Strategy Validation ===\n");
        out.append("boundary=READ_ONLY\n");
        out.append(String.format(Locale.ROOT,
                "strategyId=%d strategy=%s symbol=%s interval=%s source=%s profile=%s recentDays=%d stressDays=%d feeRate=%.4f%n",
                req.strategyId(), req.strategyName(), req.symbol(), req.intervalCode(), req.source(),
                profile.name(), req.recentDays(), req.stressDays(), req.feeRate()));
        out.append(String.format(Locale.ROOT, "closedBars=%d dataStart=%s dataEnd=%s%n",
                bars.size(), bars.get(0).time(), finalTime));
        out.append("validationPolicy=BACKTEST_REJECT_ONLY_FORWARD_SHADOW_PROMOTES\n");
        out.append("livePromotionAllowed=false\n\n");

        out.append("[ENTRY_PARITY]\n");
        out.append(String.format(Locale.ROOT,
                "required=%s status=%s exactParity=%s expectedIntents=%d actualIntents=%d missingIntents=%d extraIntents=%d uniqueEntryBars=%d blocker=%s%n",
                req.entryParityRequired(), parity.status(), parity.exactParity(), parity.expectedIntents(),
                parity.actualIntents(), parity.missingIntents(), parity.extraIntents(), events.size(), parity.blocker()));
        out.append("entryParityAuthorizationAllowed=false\n\n");

        out.append("[RECENT_EDGE]\n");
        out.append(String.format(Locale.ROOT,
                "independentEntryBars=%d minimumIndependentEvents=%d decisionHorizon=%s positiveHorizons=%d/%d status=%s%n",
                recentEvents.size(), profile.minimumIndependentEvents(), decisionHorizon.horizon().label(),
                positiveHorizons, horizonStats.size(), recentStatus));
        for (HorizonStats stats : horizonStats) {
            out.append(String.format(Locale.ROOT,
                    "horizon=%s samples=%d avgNetReturn=%.2f%% medianNetReturn=%.2f%% winRate=%.1f%% profitFactor=%s avgMfe=%.2f%% avgMae=%.2f%%%n",
                    stats.horizon().label(), stats.samples(), stats.averageReturn() * 100.0,
                    stats.medianReturn() * 100.0, stats.winRate() * 100.0,
                    formatProfitFactor(stats.profitFactor()), stats.averageMfe() * 100.0,
                    stats.averageMae() * 100.0));
        }
        out.append("recentEdgeLiveAuthorizationAllowed=false\n\n");

        out.append("[LONG_STRESS]\n");
        out.append(String.format(Locale.ROOT,
                "absolutePositiveReturnRequired=false independentEntryBars=%d strategyTimedReturn=%.2f%% fixedCadenceDcaReturn=%.2f%% strategyVsDcaPp=%.2f hodlReturn=%.2f%% maxDrawdown=%.2f%% maxDrawdownLimit=%.2f%% relativeDcaVetoPp=%.2f status=%s%n",
                stressEvents.size(), stress.strategyReturn() * 100.0, stress.fixedDcaReturn() * 100.0,
                stress.strategyVsDcaPp(), stress.hodlReturn() * 100.0,
                stress.maxDrawdown() * 100.0, BtStrategyService.QUALITY_MAX_DRAWDOWN * 100.0,
                LONG_STRESS_RELATIVE_DCA_VETO_PCT * 100.0, stress.status()));
        out.append(String.format(Locale.ROOT,
                "drawdownVeto=%s relativeDcaVeto=%s longStressLiveAuthorizationAllowed=false%n%n",
                stress.drawdownVeto(), stress.relativeDcaVeto()));

        out.append("finalAssessment=").append(finalAssessment).append("\n");
        out.append("nextAction=").append(nextAction).append("\n");
        out.append("notAuthorization=no order, OCO, strategy, env, DB, scheduler, grid, fund, Earn, Telegram, backfill, or exchange mutation");
        return out.toString();
    }

    private String noBars(Request req, Profile profile, ParityEvidence parity) {
        return String.format(Locale.ROOT,
                "=== Timeframe-Aware BTC Strategy Validation ===%n" +
                        "boundary=READ_ONLY%nstrategyId=%d symbol=%s interval=%s source=%s profile=%s%n" +
                        "status=NO_CLOSED_BARS entryParityStatus=%s finalAssessment=WAIT_DATA_COVERAGE%n" +
                        "livePromotionAllowed=false%n" +
                        "notAuthorization=no order, OCO, strategy, env, DB, scheduler, grid, fund, Earn, Telegram, backfill, or exchange mutation",
                req.strategyId(), req.symbol(), req.intervalCode(), req.source(), profile.name(), parity.status());
    }

    private RecentEdgeStatus recentStatus(int independentEvents, List<HorizonStats> horizons,
                                          int minimumIndependentEvents) {
        HorizonStats decision = horizons.get(horizons.size() - 1);
        if (independentEvents < minimumIndependentEvents || decision.samples() < minimumIndependentEvents) {
            return RecentEdgeStatus.INSUFFICIENT_SAMPLE;
        }
        long positiveHorizons = horizons.stream().filter(this::isPositiveHorizon).count();
        if (isPositiveHorizon(decision) && positiveHorizons >= 2) {
            return RecentEdgeStatus.POSITIVE_EDGE_CANDIDATE;
        }
        return RecentEdgeStatus.NO_POSITIVE_EDGE;
    }

    private boolean isPositiveHorizon(HorizonStats stats) {
        return stats.averageReturn() > 0.0
                && stats.medianReturn() > 0.0
                && stats.profitFactor() > 1.0;
    }

    private HorizonStats evaluateHorizon(List<EntryEvent> events, List<Bar> bars,
                                         Horizon horizon, double feeRate) {
        List<Double> returns = new ArrayList<>();
        List<Double> mfes = new ArrayList<>();
        List<Double> maes = new ArrayList<>();
        for (EntryEvent event : events) {
            LocalDateTime targetTime = event.time().plusHours(horizon.hours());
            int targetIndex = firstBarAtOrAfter(bars, targetTime);
            if (targetIndex < 0) {
                continue;
            }
            Bar target = bars.get(targetIndex);
            if (target.time().isBefore(targetTime) || event.entryPrice() <= 0.0) {
                continue;
            }
            returns.add(netReturn(event.entryPrice(), target.closePrice(), feeRate));
            double maxHigh = event.entryPrice();
            double minLow = event.entryPrice();
            for (Bar bar : bars) {
                if (bar.time().isAfter(event.time()) && !bar.time().isAfter(target.time())) {
                    maxHigh = Math.max(maxHigh, bar.highPrice());
                    minLow = Math.min(minLow, bar.lowPrice());
                }
            }
            mfes.add(maxHigh / event.entryPrice() - 1.0);
            maes.add(minLow / event.entryPrice() - 1.0);
        }
        if (returns.isEmpty()) {
            return new HorizonStats(horizon, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        double positive = returns.stream().filter(value -> value > 0.0).mapToDouble(Double::doubleValue).sum();
        double negative = -returns.stream().filter(value -> value < 0.0).mapToDouble(Double::doubleValue).sum();
        double profitFactor = negative > 0.0 ? positive / negative
                : positive > 0.0 ? Double.POSITIVE_INFINITY : 0.0;
        long wins = returns.stream().filter(value -> value > 0.0).count();
        return new HorizonStats(
                horizon,
                returns.size(),
                average(returns),
                median(returns),
                (double) wins / returns.size(),
                profitFactor,
                average(mfes),
                average(maes));
    }

    private StressResult evaluateStress(List<Bar> bars, List<EntryEvent> events, double feeRate) {
        if (bars.isEmpty() || events.isEmpty()) {
            return StressResult.insufficient();
        }
        List<BtcBaseShadowBacktestSimulator.Bar> simulationBars = bars.stream()
                .map(bar -> new BtcBaseShadowBacktestSimulator.Bar(bar.time(), bar.closePrice()))
                .toList();
        List<BtcBaseShadowBacktestSimulator.BuyIntent> strategyIntents = events.stream()
                .map(event -> intent(event.time(), event.reason()))
                .toList();
        List<BtcBaseShadowBacktestSimulator.BuyIntent> fixedDcaIntents = fixedCadenceEvents(bars, events.size());
        List<BtcBaseShadowBacktestSimulator.BuyIntent> hodlIntent = List.of(
                intent(bars.get(0).time(), "HODL_BENCHMARK"));
        double maxExposure = Math.max(10.0, events.size() * 10.0 + 10.0);
        BtcBaseShadowBacktestSimulator.Config config = new BtcBaseShadowBacktestSimulator.Config(
                10.0, maxExposure, 10.0, feeRate, 0.0, 0.0, 0.0, 0.0);
        BtcBaseShadowBacktestSimulator.Result strategy = BtcBaseShadowBacktestSimulator.run(
                simulationBars, strategyIntents, config,
                BtcBaseShadowBacktestSimulator.ExecutionSemantics.LIVE_ONE_ORDER_PER_BAR);
        BtcBaseShadowBacktestSimulator.Result fixedDca = BtcBaseShadowBacktestSimulator.run(
                simulationBars, fixedDcaIntents, config,
                BtcBaseShadowBacktestSimulator.ExecutionSemantics.LIVE_ONE_ORDER_PER_BAR);
        BtcBaseShadowBacktestSimulator.Result hodl = BtcBaseShadowBacktestSimulator.run(
                simulationBars, hodlIntent, config,
                BtcBaseShadowBacktestSimulator.ExecutionSemantics.LIVE_ONE_ORDER_PER_BAR);
        double relative = strategy.deployedReturn() - fixedDca.deployedReturn();
        boolean drawdownVeto = strategy.maxInventoryDrawdownPct() > BtStrategyService.QUALITY_MAX_DRAWDOWN;
        boolean relativeDcaVeto = relative < LONG_STRESS_RELATIVE_DCA_VETO_PCT;
        return new StressResult(
                strategy.deployedReturn(),
                fixedDca.deployedReturn(),
                relative * 100.0,
                hodl.deployedReturn(),
                strategy.maxInventoryDrawdownPct(),
                drawdownVeto,
                relativeDcaVeto,
                drawdownVeto || relativeDcaVeto ? LongStressStatus.VETO : LongStressStatus.PASS_RISK_SCREEN);
    }

    private List<BtcBaseShadowBacktestSimulator.BuyIntent> fixedCadenceEvents(List<Bar> bars, int requestedCount) {
        int count = Math.min(requestedCount, bars.size());
        if (count <= 0) {
            return List.of();
        }
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = new ArrayList<>();
        if (count == 1) {
            intents.add(intent(bars.get(0).time(), "FIXED_CADENCE_DCA"));
            return intents;
        }
        for (int i = 0; i < count; i++) {
            int index = (int) Math.round((double) i * (bars.size() - 1) / (count - 1));
            intents.add(intent(bars.get(index).time(), "FIXED_CADENCE_DCA"));
        }
        return intents;
    }

    private BtcBaseShadowBacktestSimulator.BuyIntent intent(LocalDateTime time, String reason) {
        return new BtcBaseShadowBacktestSimulator.BuyIntent(
                time, 10.0, reason == null ? "ENTRY" : reason, reason == null ? "ENTRY" : reason, "BUY");
    }

    private int firstBarAtOrAfter(List<Bar> bars, LocalDateTime time) {
        int low = 0;
        int high = bars.size() - 1;
        int result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (!bars.get(mid).time().isBefore(time)) {
                result = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return result;
    }

    private double netReturn(double entryPrice, double exitPrice, double feeRate) {
        double exitValueAfterFee = (exitPrice / entryPrice) * (1.0 - feeRate);
        return exitValueAfterFee - (1.0 + feeRate);
    }

    private List<Bar> normalizeBars(List<Bar> input) {
        if (input == null) return List.of();
        return input.stream()
                .filter(bar -> bar != null && bar.time() != null && bar.closePrice() > 0.0
                        && bar.highPrice() > 0.0 && bar.lowPrice() > 0.0)
                .sorted(Comparator.comparing(Bar::time))
                .toList();
    }

    private List<EntryEvent> normalizeEvents(List<EntryEvent> input) {
        if (input == null) return List.of();
        Map<LocalDateTime, EntryEvent> unique = new LinkedHashMap<>();
        input.stream()
                .filter(event -> event != null && event.time() != null && event.entryPrice() > 0.0)
                .sorted(Comparator.comparing(EntryEvent::time))
                .forEach(event -> unique.merge(event.time(), event, (left, right) -> new EntryEvent(
                        left.time(),
                        left.entryPrice(),
                        joinReason(left.reason(), right.reason()),
                        Math.max(1, left.intentCount()) + Math.max(1, right.intentCount()))));
        return List.copyOf(unique.values());
    }

    private String joinReason(String left, String right) {
        if (left == null || left.isBlank()) return right == null ? "ENTRY" : right;
        if (right == null || right.isBlank() || left.contains(right)) return left;
        return left + "," + right;
    }

    private double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
                : sorted.get(middle);
    }

    private String formatProfitFactor(double value) {
        return Double.isInfinite(value) ? "INF" : String.format(Locale.ROOT, "%.2f", value);
    }

    public record Request(long strategyId, String strategyName, String symbol, String intervalCode,
                          String source, int recentDays, int stressDays, double feeRate,
                          boolean entryParityRequired) {
        Request normalized() {
            String interval = intervalCode == null || intervalCode.isBlank() ? "1d" : intervalCode.toLowerCase(Locale.ROOT);
            int recent = recentDays <= 0 ? 90 : Math.min(recentDays, 365);
            int stress = stressDays <= 0 ? 365 : Math.min(Math.max(stressDays, recent), 1500);
            return new Request(
                    strategyId,
                    strategyName == null || strategyName.isBlank() ? "strategy#" + strategyId : strategyName,
                    symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.toUpperCase(Locale.ROOT),
                    interval,
                    source == null || source.isBlank() ? "binance" : source.toLowerCase(Locale.ROOT),
                    recent,
                    stress,
                    Math.max(0.0, feeRate),
                    entryParityRequired);
        }
    }

    public record Bar(LocalDateTime time, double highPrice, double lowPrice, double closePrice) {}

    public record EntryEvent(LocalDateTime time, double entryPrice, String reason, int intentCount) {}

    public record ParityEvidence(String status, boolean exactParity, int expectedIntents,
                                 int actualIntents, int missingIntents, int extraIntents,
                                 String blocker) {
        public static ParityEvidence notApplicable() {
            return new ParityEvidence("NOT_APPLICABLE_NON_TRADINGVIEW", true,
                    0, 0, 0, 0, "NONE");
        }
    }

    private record Horizon(String label, int hours) {}

    private record HorizonStats(Horizon horizon, int samples, double averageReturn,
                                double medianReturn, double winRate, double profitFactor,
                                double averageMfe, double averageMae) {}

    private record StressResult(double strategyReturn, double fixedDcaReturn,
                                double strategyVsDcaPp, double hodlReturn, double maxDrawdown,
                                boolean drawdownVeto, boolean relativeDcaVeto,
                                LongStressStatus status) {
        static StressResult insufficient() {
            return new StressResult(0.0, 0.0, 0.0, 0.0, 0.0,
                    false, false, LongStressStatus.INSUFFICIENT_SAMPLE);
        }
    }

    private enum RecentEdgeStatus {
        INSUFFICIENT_SAMPLE,
        POSITIVE_EDGE_CANDIDATE,
        NO_POSITIVE_EDGE
    }

    private enum LongStressStatus {
        INSUFFICIENT_SAMPLE,
        PASS_RISK_SCREEN,
        VETO
    }

    private record Profile(String name, List<Horizon> horizons, int minimumIndependentEvents) {
        static Profile fromInterval(String intervalCode) {
            String interval = intervalCode == null ? "1d" : intervalCode.trim().toLowerCase(Locale.ROOT);
            if (interval.endsWith("d")) {
                return new Profile("SWING_1D",
                        List.of(new Horizon("1d", 24), new Horizon("3d", 72),
                                new Horizon("7d", 168), new Horizon("14d", 336)),
                        12);
            }
            return new Profile("INTRADAY_1H_4H",
                    List.of(new Horizon("4h", 4), new Horizon("24h", 24), new Horizon("72h", 72)),
                    30);
        }
    }
}
