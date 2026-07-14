package com.agora.service.trading;

import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.BtStrategyService;
import com.agora.service.backtest.BacktestEngine;
import com.agora.service.backtest.LiveSignalContext;
import com.agora.service.backtest.Strategy;
import com.agora.service.backtest.StrategyContext;
import com.agora.service.backtest.StrategyRegistry;
import com.agora.service.backtest.StrategySignal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.agora.service.trading.Strategy508TimeExitPolicy.FEE_RATE;
import static com.agora.service.trading.Strategy508TimeExitPolicy.ENTRY_MAX_DELAY_MINUTES;
import static com.agora.service.trading.Strategy508TimeExitPolicy.HISTORICAL_MIN_FINALIZED_EVENTS;
import static com.agora.service.trading.Strategy508TimeExitPolicy.HOLD_HOURS;
import static com.agora.service.trading.Strategy508TimeExitPolicy.INTERVAL;
import static com.agora.service.trading.Strategy508TimeExitPolicy.KLINE_SOURCE;
import static com.agora.service.trading.Strategy508TimeExitPolicy.NOTIONAL_USDT;
import static com.agora.service.trading.Strategy508TimeExitPolicy.POLICY_MODE;
import static com.agora.service.trading.Strategy508TimeExitPolicy.SLIPPAGE_RATE;
import static com.agora.service.trading.Strategy508TimeExitPolicy.STOP_LOSS_PCT;
import static com.agora.service.trading.Strategy508TimeExitPolicy.STRATEGY_ID;
import static com.agora.service.trading.Strategy508TimeExitPolicy.SYMBOL;
import static com.agora.service.trading.Strategy508TimeExitPolicy.TAKE_PROFIT_PCT;
import static com.agora.service.trading.Strategy508TimeExitPolicy.WINDOWS_DAYS;

/** Read-only, production-semantics analysis for the fixed strategy 508 time-exit candidate. */
@Service
@RequiredArgsConstructor
public class Strategy508TimeExitCandidateService {

    private static final int MINUTES_PER_HOUR = 60;
    private static final double MIN_MINUTE_COVERAGE = 0.99;
    private static final double PROMOTION_MIN_FINALIZATION_RATE = 0.99;
    private static final int BENCHMARK_HOLD_HOURS = 72;
    private static final int TEMPORAL_FOLDS = 5;
    private static final int MIN_CALENDAR_SPAN_DAYS = 270;

    private final BtStrategyService strategyService;
    private final StrategyRegistry strategyRegistry;
    private final BacktestEngine backtestEngine;
    private final MdKlineRepository klineRepository;
    private final ObjectMapper objectMapper;

    public String analyze(String requestedSymbol, Integer detailLimit) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(analyzeNode(requestedSymbol, detailLimit));
        } catch (JsonProcessingException e) {
            return "{\"tool\":\"analyzeStrategy508TimeExitCandidate\",\"status\":\"SERIALIZATION_FAILED\"," +
                    "\"livePromotionAllowed\":false}";
        }
    }

    public ObjectNode analyzeNode(String requestedSymbol, Integer detailLimit) {
        return analyzeNodeAt(requestedSymbol, detailLimit, LocalDateTime.now(ZoneOffset.UTC));
    }

    ObjectNode analyzeNodeAt(String requestedSymbol,
                             Integer detailLimit,
                             LocalDateTime requestedAsOfUtc) {
        String symbol = normalizeSymbol(requestedSymbol);
        int limit = Math.max(1, Math.min(detailLimit == null ? 50 : detailLimit, 200));
        LocalDateTime now = requestedAsOfUtc == null
                ? LocalDateTime.now(ZoneOffset.UTC) : requestedAsOfUtc;
        if (!SYMBOL.equals(symbol)) {
            return unsupportedSymbol(symbol, now);
        }
        LocalDateTime visibleStart = now.minusDays(365).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime queryStart = visibleStart.minusDays(60);

        BtStrategy strategyEntity = strategyService.getRequired(STRATEGY_ID);
        Strategy strategy = strategyRegistry.getRequiredStrategy(strategyEntity.getStrategyType());
        Map<String, Object> config = new LinkedHashMap<>(strategyService.parseConfig(strategyEntity.getConfigJson()));
        strategy.defaultExecutionConfig().forEach(config::putIfAbsent);
        config.put("runIntervalCode", INTERVAL);
        Strategy508TimeExitPolicy.applyMarketFeatureFreshnessPolicy(config);

        List<MdKline> strategyBars = closedBars(klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        symbol, INTERVAL, KLINE_SOURCE, queryStart, now), now, 240);
        List<EntryIntent> entries = evaluateEntries(strategy, config, strategyBars, visibleStart);

        LocalDateTime minuteStart = entries.isEmpty()
                ? visibleStart
                : entries.get(0).decisionTime().minusMinutes(ENTRY_MAX_DELAY_MINUTES);
        List<MinuteBar> minuteBars = closedBars(klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        symbol, "1m", KLINE_SOURCE, minuteStart, now), now, 1).stream()
                .map(this::minuteBar)
                .toList();

        ObjectNode root = analyzePrepared(symbol, now, entries, minuteBars, limit);
        root.put("strategyName", strategyEntity.getName());
        root.put("strategyType", strategyEntity.getStrategyType());
        root.put("strategyBars", strategyBars.size());
        root.put("strategyDataStart", strategyBars.isEmpty() ? null : text(strategyBars.get(0).getOpenTime()));
        root.put("strategyDataEnd", strategyBars.isEmpty() ? null : text(strategyBars.get(strategyBars.size() - 1).getOpenTime()));
        root.put("minuteBars", minuteBars.size());
        root.put("minuteDataStart", minuteBars.isEmpty() ? null : text(minuteBars.get(0).openTime()));
        root.put("minuteDataEnd", minuteBars.isEmpty() ? null : text(minuteBars.get(minuteBars.size() - 1).openTime()));
        root.put("strategyConfigSha256", Strategy508TimeExitPolicy.strategyConfigSha256(objectMapper, config));
        root.put("effectivePolicyConfigSha256", Strategy508TimeExitPolicy.effectiveConfigSha256(objectMapper, config));
        root.put("strategyConfigSnapshotSemantics",
                "CURRENT_DB_CONFIG_PLUS_FIXED_VERSIONED_POLICY_AT_ANALYSIS_TIME");
        return root;
    }

    ObjectNode analyzePreparedForTest(String symbol,
                                      LocalDateTime now,
                                      List<EntryIntent> entries,
                                      List<MinuteBar> minuteBars,
                                      int detailLimit) {
        return analyzePrepared(normalizeSymbol(symbol), now, entries, minuteBars, detailLimit);
    }

    EventResult simulateSingle(EntryIntent entry,
                               List<MinuteBar> minuteBars,
                               LocalDateTime now) {
        LocalDateTime qualityStart = entry == null ? null : entry.decisionTime();
        LocalDateTime qualityEnd = qualityStart == null ? null
                : qualityStart.plusHours(HOLD_HOURS)
                .plusMinutes(ENTRY_MAX_DELAY_MINUTES + 1L);
        if (hasRejectedMinuteRows(minuteBars, qualityStart, qualityEnd)) {
            return EventResult.unresolved(
                    entry, "INVALID_1M_SOURCE_ROW", null, null, BigDecimal.ZERO, 0.0);
        }
        if (hasDuplicateMinuteTimestamps(minuteBars, qualityStart, qualityEnd)) {
            return EventResult.unresolved(
                    entry, "DUPLICATE_1M_TIMESTAMP", null, null, BigDecimal.ZERO, 0.0);
        }
        return simulate(entry, normalizeMinuteBars(minuteBars), now);
    }

    private ObjectNode analyzePrepared(String symbol,
                                       LocalDateTime now,
                                       List<EntryIntent> inputEntries,
                                       List<MinuteBar> inputMinuteBars,
                                       int detailLimit) {
        List<EntryIntent> entries = inputEntries == null ? List.of() : inputEntries.stream()
                .filter(entry -> entry != null && entry.barOpenTime() != null && entry.decisionTime() != null)
                .sorted(Comparator.comparing(EntryIntent::decisionTime))
                .toList();
        long rejectedMinuteRows = inputMinuteBars == null ? 0 : inputMinuteBars.stream()
                .filter(bar -> !validMinuteBar(bar))
                .count();
        List<MinuteBar> minuteBars = normalizeMinuteBars(inputMinuteBars);
        long duplicateMinuteTimestampRows = Math.max(0,
                minuteBars.size() - minuteBars.stream().map(MinuteBar::openTime).distinct().count());

        List<EventResult> results = simulateCandidateCohort(entries, minuteBars, now);
        List<BenchmarkResult> benchmarkResults = simulateBenchmarkCohort(entries, minuteBars, now);
        Map<String, BenchmarkResult> finalizedBenchmarks = new HashMap<>();
        benchmarkResults.stream()
                .filter(BenchmarkResult::finalized)
                .filter(result -> result.coverage() >= 1.0)
                .forEach(result -> finalizedBenchmarks.put(eventKey(result.entry()), result));
        results = results.stream()
                .map(result -> result.withBenchmark(finalizedBenchmarks.containsKey(eventKey(result.entry()))
                        ? finalizedBenchmarks.get(eventKey(result.entry())).returnPct() : null))
                .toList();

        List<EventResult> finalized = results.stream().filter(EventResult::finalized).toList();
        Map<String, Long> outcomeCounts = outcomeCounts(results);
        long policySkippedEvents = countOutcome(results, "SKIPPED_MAX_OPEN_POSITION")
                + countOutcome(results, "SKIPPED_DAILY_ORDER_CAP");
        long pendingEvents = countOutcome(results, "PENDING_24H");
        long matureEligibleEvents = Math.max(0, results.size() - policySkippedEvents - pendingEvents);
        long matureUnresolvedEvents = Math.max(0, matureEligibleEvents - finalized.size());
        double finalizationRate = matureEligibleEvents == 0
                ? 0.0 : (double) finalized.size() / matureEligibleEvents;
        boolean completeMinuteCoverage = finalized.stream()
                .allMatch(result -> result.coverage() >= 1.0);
        Map<Integer, WindowMetrics> windows = new LinkedHashMap<>();
        for (Integer days : WINDOWS_DAYS) {
            LocalDateTime start = now.minusDays(days);
            windows.put(days, metrics(finalized.stream()
                    .filter(result -> !result.entry().barOpenTime().isBefore(start))
                    .toList()));
        }
        WindowMetrics metrics365 = windows.get(365);
        WindowMetrics metrics180 = windows.get(180);
        WalkForward walkForward = walkForward(finalized, now.minusDays(365), now);
        long calendarSpanDays = finalized.size() < 2 ? 0 : Duration.between(
                finalized.get(0).entry().barOpenTime(),
                finalized.get(finalized.size() - 1).entry().barOpenTime()).toDays();
        List<EventResult> benchmarkPairs = finalized.stream()
                .filter(result -> result.benchmark72hReturnPct() != null)
                .toList();
        double benchmark72Average = benchmarkPairs.stream()
                .mapToDouble(result -> result.benchmark72hReturnPct().doubleValue())
                .average().orElse(Double.NaN);
        double pairedCandidateAverage = benchmarkPairs.stream()
                .mapToDouble(result -> result.returnPct().doubleValue())
                .average().orElse(Double.NaN);
        double improvementPp = Double.isFinite(benchmark72Average)
                ? pairedCandidateAverage - benchmark72Average
                : Double.NaN;

        long positiveWindows = windows.values().stream().filter(WindowMetrics::positive).count();
        WindowMetrics worstWindow = windows.values().stream()
                .min(Comparator.comparingDouble(WindowMetrics::averageReturnPct))
                .orElse(WindowMetrics.empty());
        BigDecimal stressPnl = finalized.stream()
                .map(EventResult::stressPnlUsdt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unresolvedWorstLoss = NOTIONAL_USDT.multiply(
                STOP_LOSS_PCT.add(FEE_RATE.multiply(BigDecimal.valueOf(2)))
                        .add(SLIPPAGE_RATE.multiply(BigDecimal.valueOf(2)))).negate();
        BigDecimal worstCasePnl = metrics365.totalPnlUsdt().add(
                unresolvedWorstLoss.multiply(BigDecimal.valueOf(matureUnresolvedEvents)));

        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("sourceMinuteRowsValid", rejectedMinuteRows == 0);
        gates.put("uniqueMinuteTimestamps", duplicateMinuteTimestampRows == 0);
        gates.put("minimumFinalizedEvents", finalized.size() >= HISTORICAL_MIN_FINALIZED_EVENTS);
        gates.put("outcomeFinalizationRate", finalizationRate >= PROMOTION_MIN_FINALIZATION_RATE);
        gates.put("completeMinuteCoverage", completeMinuteCoverage);
        gates.put("minimumCalendarSpan", calendarSpanDays >= MIN_CALENDAR_SPAN_DAYS);
        gates.put("window180Average", metrics180.averageReturnPct() > 0.20);
        gates.put("window180Median", metrics180.medianReturnPct() > 0.0);
        gates.put("window180ProfitFactor", metrics180.profitFactor() >= 1.30);
        gates.put("window365Average", metrics365.averageReturnPct() > 0.20);
        gates.put("window365Median", metrics365.medianReturnPct() > 0.0);
        gates.put("window365ProfitFactor", metrics365.profitFactor() >= 1.30);
        gates.put("positiveWindows", positiveWindows >= 3);
        gates.put("worstWindowAverage", worstWindow.averageReturnPct() >= -0.10);
        gates.put("worstWindowProfitFactor", worstWindow.profitFactor() >= 0.90);
        gates.put("walkForward", walkForward.positiveFolds() >= 3
                && walkForward.nonEmptyFolds() == TEMPORAL_FOLDS);
        gates.put("stressNonNegative", stressPnl.signum() >= 0);
        gates.put("unresolvedWorstCaseNonNegative", worstCasePnl.signum() >= 0);
        gates.put("maxDrawdown", metrics365.maxDrawdownPct() <= 15.0);
        gates.put("benchmark72PairedSample", benchmarkPairs.size() >= HISTORICAL_MIN_FINALIZED_EVENTS);
        gates.put("beats72h", Double.isFinite(improvementPp) && improvementPp >= 0.50);
        boolean accepted = gates.values().stream().allMatch(Boolean::booleanValue);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "analyzeStrategy508TimeExitCandidate");
        root.put("boundary", "READ_ONLY");
        root.put("generatedAtUtc", text(now));
        root.put("policyMode", POLICY_MODE);
        root.put("strategyId", STRATEGY_ID);
        root.put("symbol", symbol);
        root.put("intervalCode", INTERVAL);
        root.put("source", KLINE_SOURCE);
        root.put("holdHours", HOLD_HOURS);
        root.put("notionalUsdt", NOTIONAL_USDT);
        root.put("takeProfitPct", TAKE_PROFIT_PCT);
        root.put("stopLossPct", STOP_LOSS_PCT);
        root.put("feeRatePerSide", FEE_RATE);
        root.put("slippageRatePerSide", SLIPPAGE_RATE);
        root.put("entrySemantics", "FIRST_1M_OPEN_AT_OR_AFTER_4H_CLOSE");
        root.put("executionSemantics", "ONE_OPEN_POSITION_MAX_AND_ONE_ORDER_PER_UTC_DAY");
        root.put("minuteReplaySemantics", "DETERMINISTIC_1M_OHLC_NOT_EXACT_EXCHANGE_FILL");
        root.put("minuteLatticeValidation",
                "EXACT_UTC_MINUTE_GRID_DISTINCT_TIMESTAMP_AND_OHLC_INVARIANTS");
        root.put("minimumPerEventMinuteCoverage", MIN_MINUTE_COVERAGE);
        root.put("promotionRequiresCompleteMinuteCoverage", true);
        root.put("rejectedMinuteRows", rejectedMinuteRows);
        root.put("duplicateMinuteTimestampRows", duplicateMinuteTimestampRows);
        root.put("rawBuyEvents", entries.size());
        root.put("eligibleEvents", results.stream()
                .filter(result -> result.entryTime() != null)
                .filter(result -> !"SKIPPED_MAX_OPEN_POSITION".equals(result.outcome()))
                .filter(result -> !"SKIPPED_DAILY_ORDER_CAP".equals(result.outcome()))
                .count());
        root.put("finalizedEvents", finalized.size());
        root.put("minimumFinalizedEvents", HISTORICAL_MIN_FINALIZED_EVENTS);
        root.put("matureEligibleEvents", matureEligibleEvents);
        root.put("matureUnresolvedEvents", matureUnresolvedEvents);
        root.put("finalizationRatePct", round(finalizationRate * 100.0));
        root.put("minimumFinalizationRatePct", PROMOTION_MIN_FINALIZATION_RATE * 100.0);
        root.put("calendarSpanDays", calendarSpanDays);
        root.put("minimumCalendarSpanDays", MIN_CALENDAR_SPAN_DAYS);
        root.put("ambiguousEvents", countOutcome(results, "AMBIGUOUS_SAME_MINUTE"));
        root.put("insufficientCoverageEvents", countOutcome(results, "INSUFFICIENT_1M_COVERAGE"));
        root.put("missingEntryEvents", countOutcome(results, "MISSING_NEXT_1M_ENTRY"));
        root.put("missingExitEvents", countOutcome(results, "MISSING_24H_EXIT_BAR"));
        root.put("pendingEvents", pendingEvents);
        root.put("overlapSkippedEvents", countOutcome(results, "SKIPPED_MAX_OPEN_POSITION"));
        root.put("dailyCapSkippedEvents", countOutcome(results, "SKIPPED_DAILY_ORDER_CAP"));
        root.put("stressTotalPnlUsdt", stressPnl);
        root.put("unresolvedWorstLossPerEventUsdt", unresolvedWorstLoss);
        root.put("unresolvedWorstCaseTotalPnlUsdt", worstCasePnl);
        ObjectNode outcomeNode = root.putObject("outcomeBreakdown");
        outcomeCounts.forEach(outcomeNode::put);
        root.put("outcomeReconciled", outcomeCounts.values().stream().mapToLong(Long::longValue).sum()
                == results.size());
        root.put("benchmark72hRawEvents", benchmarkResults.size());
        root.put("benchmark72hFinalizedEvents", benchmarkResults.stream()
                .filter(BenchmarkResult::finalized).count());
        root.put("benchmark72hCompleteCoverageEvents", benchmarkResults.stream()
                .filter(BenchmarkResult::finalized)
                .filter(result -> result.coverage() >= 1.0).count());
        root.put("benchmark72hOverlapSkippedEvents", countBenchmarkOutcome(
                benchmarkResults, "SKIPPED_MAX_OPEN_POSITION"));
        root.put("benchmark72hDailyCapSkippedEvents", countBenchmarkOutcome(
                benchmarkResults, "SKIPPED_DAILY_ORDER_CAP"));
        root.put("benchmark72hPairedEvents", benchmarkPairs.size());
        putFinite(root, "pairedCandidateAverageReturnPct", pairedCandidateAverage);
        putFinite(root, "benchmark72hAverageReturnPct", benchmark72Average);
        putFinite(root, "candidateImprovementVs72hPp", improvementPp);

        ObjectNode windowNode = root.putObject("windows");
        windows.forEach((days, metrics) -> writeMetrics(windowNode.putObject(days + "d"), metrics));
        ObjectNode walkNode = root.putObject("walkForward");
        walkNode.put("semantics", "FIXED_NON_OVERLAPPING_CALENDAR_FOLDS_NO_REFIT");
        walkNode.put("folds", walkForward.folds().size());
        walkNode.put("positiveFolds", walkForward.positiveFolds());
        walkNode.put("nonEmptyFolds", walkForward.nonEmptyFolds());
        ArrayNode foldRows = walkNode.putArray("rows");
        walkForward.folds().forEach(fold -> {
            ObjectNode node = foldRows.addObject();
            node.put("fold", fold.fold());
            node.put("startUtc", text(fold.startUtc()));
            node.put("endUtc", text(fold.endUtc()));
            node.put("events", fold.events());
            node.put("netPnlUsdt", decimal(fold.netPnlUsdt()));
            node.put("positive", fold.positive());
        });

        ObjectNode gateNode = root.putObject("promotionGates");
        gates.forEach(gateNode::put);
        root.put("historicalGatePassed", accepted);
        boolean replayQualityReady = rejectedMinuteRows == 0
                && duplicateMinuteTimestampRows == 0
                && finalizationRate >= PROMOTION_MIN_FINALIZATION_RATE
                && completeMinuteCoverage
                && calendarSpanDays >= MIN_CALENDAR_SPAN_DAYS;
        root.put("sampleStatus", finalized.size() < HISTORICAL_MIN_FINALIZED_EVENTS
                ? "INSUFFICIENT_EXACT_1M_SAMPLE"
                : replayQualityReady ? "HISTORICAL_SAMPLE_READY"
                : "HISTORICAL_SAMPLE_UNTRUSTED");
        root.put("replayQualityStatus", rejectedMinuteRows > 0
                ? "BLOCKED_INVALID_MINUTE_SOURCE_ROWS"
                : duplicateMinuteTimestampRows > 0
                ? "BLOCKED_DUPLICATE_MINUTE_TIMESTAMPS"
                : finalizationRate < PROMOTION_MIN_FINALIZATION_RATE
                ? "BLOCKED_OUTCOME_ATTRITION"
                : !completeMinuteCoverage ? "BLOCKED_INCOMPLETE_MINUTE_LATTICE"
                : calendarSpanDays < MIN_CALENDAR_SPAN_DAYS ? "BLOCKED_CALENDAR_SPAN"
                : "REPLAY_QUALITY_READY");
        root.put("verdict", accepted
                ? "READY_FOR_SINGLE_10_USDT_PROBE_REVIEW_NOT_AUTHORIZED"
                : "REJECTED_NO_LIVE_NO_MORE_PARAMETER_TUNING");
        root.put("livePromotionAllowed", false);

        ArrayNode eventRows = root.putArray("events");
        int start = Math.max(0, results.size() - Math.max(1, detailLimit));
        for (int i = start; i < results.size(); i++) {
            writeEvent(eventRows.addObject(), results.get(i));
        }
        ObjectNode safety = root.putObject("safety");
        safety.put("orderSent", false);
        safety.put("ocoModified", false);
        safety.put("databaseMutated", false);
        safety.put("externalBackfillUsed", false);
        return root;
    }

    private ObjectNode unsupportedSymbol(String symbol, LocalDateTime now) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "analyzeStrategy508TimeExitCandidate");
        root.put("boundary", "READ_ONLY");
        root.put("generatedAtUtc", text(now));
        root.put("policyMode", POLICY_MODE);
        root.put("strategyId", STRATEGY_ID);
        root.put("symbol", symbol);
        root.put("supportedSymbol", SYMBOL);
        root.put("status", "UNSUPPORTED_SYMBOL");
        root.put("historicalGatePassed", false);
        root.put("verdict", "REJECTED_NO_LIVE_NO_MORE_PARAMETER_TUNING");
        root.put("livePromotionAllowed", false);
        ObjectNode safety = root.putObject("safety");
        safety.put("orderSent", false);
        safety.put("ocoModified", false);
        safety.put("databaseMutated", false);
        return root;
    }

    private List<EntryIntent> evaluateEntries(Strategy strategy,
                                              Map<String, Object> config,
                                              List<MdKline> bars,
                                              LocalDateTime visibleStart) {
        if (bars.isEmpty()) {
            return List.of();
        }
        Map<String, double[]> indicators = backtestEngine.buildIndicators(bars, config);
        List<EntryIntent> entries = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            MdKline current = bars.get(i);
            if (current.getOpenTime().isBefore(visibleStart)) {
                continue;
            }
            MdKline previous = i > 0 ? bars.get(i - 1) : null;
            LiveSignalContext.clear();
            try {
                StrategySignal signal = strategy.evaluate(
                        new StrategyContext(i, current, previous, bars, indicators), config);
                if (signal == StrategySignal.BUY) {
                    Map<String, Object> details = new LinkedHashMap<>(LiveSignalContext.getDetails());
                    LocalDateTime decisionTime = current.getCloseTime() != null
                            ? current.getCloseTime()
                            : current.getOpenTime().plusHours(4);
                    entries.add(new EntryIntent(current.getOpenTime(), decisionTime,
                            String.valueOf(details.getOrDefault("trigger_reason", "all_gates_passed"))));
                }
            } finally {
                LiveSignalContext.clear();
            }
        }
        return entries;
    }

    private List<EventResult> simulateCandidateCohort(List<EntryIntent> entries,
                                                      List<MinuteBar> minuteBars,
                                                      LocalDateTime now) {
        List<EventResult> results = new ArrayList<>();
        Set<LocalDate> admittedDays = new HashSet<>();
        LocalDateTime occupiedUntil = null;
        for (EntryIntent entry : entries) {
            MinuteBar firstMinute = firstAtOrAfter(minuteBars, entry.decisionTime());
            if (timelyEntryMinute(entry, firstMinute)) {
                if (occupiedUntil != null && !firstMinute.openTime().isAfter(occupiedUntil)) {
                    results.add(EventResult.skipped(
                            entry, "SKIPPED_MAX_OPEN_POSITION", firstMinute.openTime()));
                    continue;
                }
                if (admittedDays.contains(firstMinute.openTime().toLocalDate())) {
                    results.add(EventResult.skipped(
                            entry, "SKIPPED_DAILY_ORDER_CAP", firstMinute.openTime()));
                    continue;
                }
            }
            EventResult result = simulate(entry, minuteBars, now);
            results.add(result);
            if (result.entryTime() != null) {
                admittedDays.add(result.entryTime().toLocalDate());
                occupiedUntil = result.finalized() && result.exitTime() != null
                        ? result.exitTime() : result.entryTime().plusHours(HOLD_HOURS);
            }
        }
        return results;
    }

    private List<BenchmarkResult> simulateBenchmarkCohort(List<EntryIntent> entries,
                                                          List<MinuteBar> minuteBars,
                                                          LocalDateTime now) {
        List<BenchmarkResult> results = new ArrayList<>();
        Set<LocalDate> admittedDays = new HashSet<>();
        LocalDateTime occupiedUntil = null;
        for (EntryIntent entry : entries) {
            MinuteBar firstMinute = firstAtOrAfter(minuteBars, entry.decisionTime());
            if (timelyEntryMinute(entry, firstMinute)) {
                if (occupiedUntil != null && !firstMinute.openTime().isAfter(occupiedUntil)) {
                    results.add(BenchmarkResult.skipped(
                            entry, "SKIPPED_MAX_OPEN_POSITION", firstMinute.openTime()));
                    continue;
                }
                if (admittedDays.contains(firstMinute.openTime().toLocalDate())) {
                    results.add(BenchmarkResult.skipped(
                            entry, "SKIPPED_DAILY_ORDER_CAP", firstMinute.openTime()));
                    continue;
                }
            }
            BenchmarkResult result = simulateBenchmark72(entry, minuteBars, now);
            results.add(result);
            if (result.entryTime() != null) {
                admittedDays.add(result.entryTime().toLocalDate());
                occupiedUntil = result.finalized() && result.exitTime() != null
                        ? result.exitTime() : result.entryTime().plusHours(BENCHMARK_HOLD_HOURS);
            }
        }
        return results;
    }

    private boolean timelyEntryMinute(EntryIntent entry, MinuteBar firstMinute) {
        return firstMinute != null
                && Duration.between(entry.decisionTime(), firstMinute.openTime()).toMinutes()
                <= ENTRY_MAX_DELAY_MINUTES;
    }

    private EventResult simulate(EntryIntent entry, List<MinuteBar> bars, LocalDateTime now) {
        MinuteBar first = firstAtOrAfter(bars, entry.decisionTime());
        if (first == null || Duration.between(entry.decisionTime(), first.openTime()).toMinutes() > ENTRY_MAX_DELAY_MINUTES) {
            return EventResult.unresolved(entry, "MISSING_NEXT_1M_ENTRY", null, null, BigDecimal.ZERO, 0.0);
        }

        LocalDateTime horizon = first.openTime().plusHours(HOLD_HOURS);
        if (now.isBefore(horizon)) {
            return EventResult.unresolved(entry, "PENDING_24H", first.openTime(), horizon, BigDecimal.ZERO, 0.0);
        }

        BigDecimal entryExecution = first.open().multiply(BigDecimal.ONE.add(SLIPPAGE_RATE));
        BigDecimal tp = entryExecution.multiply(BigDecimal.ONE.add(TAKE_PROFIT_PCT));
        BigDecimal sl = entryExecution.multiply(BigDecimal.ONE.subtract(STOP_LOSS_PCT));
        BigDecimal maxHigh = entryExecution;
        BigDecimal minLow = entryExecution;
        MinuteBar resolution = null;
        String outcome = "TIME_EXIT_24H";
        for (MinuteBar bar : bars) {
            if (bar.openTime().isBefore(first.openTime())) continue;
            if (!bar.openTime().isBefore(horizon)) break;
            maxHigh = maxHigh.max(bar.high());
            minLow = minLow.min(bar.low());
            boolean hitTp = bar.high().compareTo(tp) >= 0;
            boolean hitSl = bar.low().compareTo(sl) <= 0;
            if (hitTp && hitSl) {
                return EventResult.unresolved(entry, "AMBIGUOUS_SAME_MINUTE",
                        first.openTime(), bar.openTime(), BigDecimal.ZERO,
                        coverage(bars, first.openTime(), bar.openTime().plusMinutes(1)));
            }
            if (hitTp || hitSl) {
                resolution = bar;
                outcome = hitTp ? "TP_HIT" : "SL_HIT";
                break;
            }
        }

        LocalDateTime coverageEnd = resolution == null ? horizon : resolution.openTime().plusMinutes(1);
        double coverage = coverage(bars, first.openTime(), coverageEnd);
        if (coverage < MIN_MINUTE_COVERAGE) {
            return EventResult.unresolved(entry, "INSUFFICIENT_1M_COVERAGE",
                    first.openTime(), coverageEnd, BigDecimal.ZERO, coverage);
        }

        BigDecimal rawExit;
        LocalDateTime exitTime;
        if (resolution != null) {
            rawExit = "TP_HIT".equals(outcome) ? tp : sl;
            exitTime = resolution.openTime();
        } else {
            MinuteBar timeoutBar = firstAtOrAfter(bars, horizon);
            if (timeoutBar == null || Duration.between(horizon, timeoutBar.openTime()).toMinutes() > ENTRY_MAX_DELAY_MINUTES) {
                return EventResult.unresolved(entry, "MISSING_24H_EXIT_BAR",
                        first.openTime(), horizon, BigDecimal.ZERO, coverage);
            }
            rawExit = timeoutBar.open();
            exitTime = timeoutBar.openTime();
        }

        Pricing pricing = price(entryExecution, rawExit, FEE_RATE, SLIPPAGE_RATE);
        Pricing stress = price(entryExecution, rawExit, FEE_RATE.multiply(BigDecimal.valueOf(2)), SLIPPAGE_RATE);
        BigDecimal mfe = percent(maxHigh, entryExecution);
        BigDecimal mae = percent(minLow, entryExecution);
        return EventResult.finalized(entry, outcome, first.openTime(), exitTime,
                entryExecution, pricing.exitPrice(), pricing.pnlUsdt(), stress.pnlUsdt(),
                pricing.feesUsdt(), pricing.returnPct(), mfe, mae, null, coverage);
    }

    private BenchmarkResult simulateBenchmark72(EntryIntent entry,
                                                List<MinuteBar> bars,
                                                LocalDateTime now) {
        MinuteBar first = firstAtOrAfter(bars, entry.decisionTime());
        if (!timelyEntryMinute(entry, first)) {
            return BenchmarkResult.unresolved(
                    entry, "MISSING_NEXT_1M_ENTRY", null, null, null, 0.0);
        }
        LocalDateTime horizon = first.openTime().plusHours(BENCHMARK_HOLD_HOURS);
        if (now.isBefore(horizon)) {
            return BenchmarkResult.unresolved(
                    entry, "PENDING_72H", first.openTime(), horizon, null, 0.0);
        }
        double minuteCoverage = coverage(bars, first.openTime(), horizon);
        if (minuteCoverage < MIN_MINUTE_COVERAGE) {
            return BenchmarkResult.unresolved(entry, "INSUFFICIENT_1M_COVERAGE",
                    first.openTime(), horizon, null, minuteCoverage);
        }
        MinuteBar timeoutBar = firstAtOrAfter(bars, horizon);
        if (timeoutBar == null
                || Duration.between(horizon, timeoutBar.openTime()).toMinutes() > ENTRY_MAX_DELAY_MINUTES) {
            return BenchmarkResult.unresolved(entry, "MISSING_72H_EXIT_BAR",
                    first.openTime(), horizon, null, minuteCoverage);
        }
        BigDecimal entryExecution = first.open().multiply(BigDecimal.ONE.add(SLIPPAGE_RATE));
        BigDecimal returnPct = price(entryExecution, timeoutBar.open(), FEE_RATE, SLIPPAGE_RATE).returnPct();
        return BenchmarkResult.finalized(entry, first.openTime(), timeoutBar.openTime(),
                returnPct, minuteCoverage);
    }

    private Pricing price(BigDecimal entryExecution,
                          BigDecimal rawExit,
                          BigDecimal feeRate,
                          BigDecimal slippageRate) {
        BigDecimal entryFee = NOTIONAL_USDT.multiply(feeRate);
        BigDecimal quantity = NOTIONAL_USDT.subtract(entryFee)
                .divide(entryExecution, 16, RoundingMode.DOWN);
        BigDecimal exitExecution = rawExit.multiply(BigDecimal.ONE.subtract(slippageRate));
        BigDecimal grossExit = quantity.multiply(exitExecution);
        BigDecimal exitFee = grossExit.multiply(feeRate);
        BigDecimal pnl = grossExit.subtract(exitFee).subtract(NOTIONAL_USDT);
        BigDecimal returnPct = pnl.divide(NOTIONAL_USDT, 12, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return new Pricing(exitExecution, pnl, entryFee.add(exitFee), returnPct);
    }

    private WindowMetrics metrics(List<EventResult> events) {
        if (events.isEmpty()) return WindowMetrics.empty();
        List<Double> returns = events.stream().map(event -> event.returnPct().doubleValue()).sorted().toList();
        double positive = returns.stream().filter(value -> value > 0).mapToDouble(Double::doubleValue).sum();
        double negative = -returns.stream().filter(value -> value < 0).mapToDouble(Double::doubleValue).sum();
        double profitFactor = negative > 0 ? positive / negative : positive > 0 ? Double.POSITIVE_INFINITY : 0.0;
        long wins = returns.stream().filter(value -> value > 0).count();
        BigDecimal totalPnl = events.stream().map(EventResult::pnlUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
        double cumulative = 0.0;
        double peak = 0.0;
        double maxDrawdown = 0.0;
        for (EventResult event : events) {
            cumulative += event.pnlUsdt().doubleValue();
            peak = Math.max(peak, cumulative);
            maxDrawdown = Math.max(maxDrawdown, peak - cumulative);
        }
        return new WindowMetrics(events.size(), average(returns), median(returns),
                (double) wins / events.size() * 100.0, profitFactor,
                events.stream().mapToDouble(event -> event.mfePct().doubleValue()).average().orElse(0.0),
                events.stream().mapToDouble(event -> event.maePct().doubleValue()).average().orElse(0.0),
                totalPnl, maxDrawdown / NOTIONAL_USDT.doubleValue() * 100.0);
    }

    private WalkForward walkForward(List<EventResult> events,
                                    LocalDateTime startUtc,
                                    LocalDateTime endUtc) {
        List<Fold> folds = new ArrayList<>();
        long totalSeconds = Math.max(TEMPORAL_FOLDS,
                Duration.between(startUtc, endUtc).getSeconds());
        for (int fold = 0; fold < TEMPORAL_FOLDS; fold++) {
            LocalDateTime foldStart = startUtc.plusSeconds(totalSeconds * fold / TEMPORAL_FOLDS);
            LocalDateTime foldEnd = fold == TEMPORAL_FOLDS - 1
                    ? endUtc.plusNanos(1)
                    : startUtc.plusSeconds(totalSeconds * (fold + 1) / TEMPORAL_FOLDS);
            List<EventResult> slice = events.stream()
                    .filter(event -> !event.entry().barOpenTime().isBefore(foldStart))
                    .filter(event -> event.entry().barOpenTime().isBefore(foldEnd))
                    .toList();
            BigDecimal pnl = slice.stream().map(EventResult::pnlUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
            folds.add(new Fold(fold + 1, foldStart, foldEnd, slice.size(), pnl, pnl.signum() > 0));
        }
        return new WalkForward((int) folds.stream().filter(Fold::positive).count(),
                (int) folds.stream().filter(fold -> fold.events() > 0).count(), folds);
    }

    private double coverage(List<MinuteBar> bars, LocalDateTime start, LocalDateTime endExclusive) {
        long expected = Math.max(1, Duration.between(start, endExclusive).toMinutes());
        Set<LocalDateTime> observedTimes = bars.stream()
                .filter(this::validMinuteBar)
                .filter(bar -> !bar.openTime().isBefore(start) && bar.openTime().isBefore(endExclusive))
                .map(MinuteBar::openTime)
                .collect(java.util.stream.Collectors.toSet());
        long observed = 0;
        for (long minute = 0; minute < expected; minute++) {
            if (observedTimes.contains(start.plusMinutes(minute))) observed++;
        }
        return Math.min(1.0, (double) observed / expected);
    }

    private boolean validMinuteBar(MinuteBar bar) {
        if (bar == null || bar.openTime() == null || !minuteAligned(bar.openTime())
                || !positive(bar.open()) || !positive(bar.high())
                || !positive(bar.low()) || !positive(bar.close())) {
            return false;
        }
        BigDecimal bodyHigh = bar.open().max(bar.close());
        BigDecimal bodyLow = bar.open().min(bar.close());
        return bar.high().compareTo(bodyHigh) >= 0
                && bar.low().compareTo(bodyLow) <= 0
                && bar.high().compareTo(bar.low()) >= 0;
    }

    private List<MinuteBar> normalizeMinuteBars(List<MinuteBar> bars) {
        if (bars == null || bars.isEmpty()) return List.of();
        return bars.stream()
                .filter(this::validMinuteBar)
                .sorted(Comparator.comparing(MinuteBar::openTime))
                .toList();
    }

    private boolean hasRejectedMinuteRows(List<MinuteBar> bars,
                                          LocalDateTime start,
                                          LocalDateTime endExclusive) {
        if (bars == null || bars.isEmpty()) return false;
        return bars.stream().anyMatch(bar -> {
            if (bar == null || bar.openTime() == null) return true;
            boolean inWindow = start == null || endExclusive == null
                    || (!bar.openTime().isBefore(start) && bar.openTime().isBefore(endExclusive));
            return inWindow && !validMinuteBar(bar);
        });
    }

    private boolean hasDuplicateMinuteTimestamps(List<MinuteBar> bars,
                                                 LocalDateTime start,
                                                 LocalDateTime endExclusive) {
        if (bars == null || bars.isEmpty()) return false;
        Set<LocalDateTime> seen = new HashSet<>();
        for (MinuteBar bar : bars) {
            if (!validMinuteBar(bar)) continue;
            boolean inWindow = start == null || endExclusive == null
                    || (!bar.openTime().isBefore(start) && bar.openTime().isBefore(endExclusive));
            if (inWindow && !seen.add(bar.openTime())) return true;
        }
        return false;
    }

    private boolean minuteAligned(LocalDateTime value) {
        return value.equals(value.truncatedTo(ChronoUnit.MINUTES));
    }

    private MinuteBar firstAtOrAfter(List<MinuteBar> bars, LocalDateTime target) {
        int low = 0;
        int high = bars.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (!bars.get(mid).openTime().isBefore(target)) {
                found = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return found < 0 ? null : bars.get(found);
    }

    private List<MdKline> closedBars(List<MdKline> input, LocalDateTime now, int minutes) {
        if (input == null) return List.of();
        return input.stream()
                .filter(bar -> bar != null && bar.getOpenTime() != null)
                .filter(bar -> {
                    LocalDateTime close = bar.getCloseTime() != null
                            ? bar.getCloseTime() : bar.getOpenTime().plusMinutes(minutes);
                    return !close.isAfter(now);
                })
                .sorted(Comparator.comparing(MdKline::getOpenTime))
                .toList();
    }

    private MinuteBar minuteBar(MdKline bar) {
        return new MinuteBar(bar.getOpenTime(), bar.getOpenPrice(), bar.getHighPrice(),
                bar.getLowPrice(), bar.getClosePrice());
    }

    private void writeMetrics(ObjectNode node, WindowMetrics metrics) {
        node.put("finalizedEvents", metrics.events());
        node.put("averageNetReturnPct", round(metrics.averageReturnPct()));
        node.put("medianNetReturnPct", round(metrics.medianReturnPct()));
        node.put("winRatePct", round(metrics.winRatePct()));
        if (Double.isInfinite(metrics.profitFactor())) node.put("profitFactor", "INF");
        else node.put("profitFactor", round(metrics.profitFactor()));
        node.put("averageMfePct", round(metrics.averageMfePct()));
        node.put("averageMaePct", round(metrics.averageMaePct()));
        node.put("totalPnlUsdt", decimal(metrics.totalPnlUsdt()));
        node.put("maxDrawdownPct", round(metrics.maxDrawdownPct()));
        node.put("positive", metrics.positive());
    }

    private void writeEvent(ObjectNode node, EventResult result) {
        node.put("barOpenTime", text(result.entry().barOpenTime()));
        node.put("decisionTime", text(result.entry().decisionTime()));
        node.put("reason", result.entry().reason());
        node.put("classification", result.classification());
        node.put("outcome", result.outcome());
        node.put("finalized", result.finalized());
        node.put("entryTime", text(result.entryTime()));
        node.put("exitTime", text(result.exitTime()));
        putDecimal(node, "entryPrice", result.entryPrice());
        putDecimal(node, "exitPrice", result.exitPrice());
        putDecimal(node, "netPnlUsdt", result.pnlUsdt());
        putDecimal(node, "stressPnlUsdt", result.stressPnlUsdt());
        putDecimal(node, "feesUsdt", result.feesUsdt());
        putDecimal(node, "netReturnPct", result.returnPct());
        putDecimal(node, "mfePct", result.mfePct());
        putDecimal(node, "maePct", result.maePct());
        putDecimal(node, "benchmark72hReturnPct", result.benchmark72hReturnPct());
        node.put("oneMinuteCoverage", round(result.coverage()));
    }

    private long countOutcome(List<EventResult> results, String outcome) {
        return results.stream().filter(result -> outcome.equals(result.outcome())).count();
    }

    private long countBenchmarkOutcome(List<BenchmarkResult> results, String outcome) {
        return results.stream().filter(result -> outcome.equals(result.outcome())).count();
    }

    private Map<String, Long> outcomeCounts(List<EventResult> results) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (EventResult result : results) {
            counts.merge(result.outcome(), 1L, Long::sum);
        }
        return counts;
    }

    private String eventKey(EntryIntent entry) {
        return entry.barOpenTime() + "|" + entry.decisionTime();
    }

    private double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double median(List<Double> sorted) {
        if (sorted.isEmpty()) return 0.0;
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
                : sorted.get(middle);
    }

    private BigDecimal percent(BigDecimal value, BigDecimal base) {
        return value.subtract(base).divide(base, 12, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? SYMBOL
                : symbol.trim().toUpperCase(Locale.ROOT).replace("-", "")
                .replace("/", "").replace("_", "");
    }

    private String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.setScale(8, RoundingMode.HALF_UP).toPlainString();
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private void putFinite(ObjectNode node, String field, double value) {
        if (Double.isFinite(value)) node.put(field, round(value));
        else node.putNull(field);
    }

    private void putDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) node.putNull(field);
        else node.put(field, decimal(value));
    }

    record EntryIntent(LocalDateTime barOpenTime, LocalDateTime decisionTime, String reason) {
    }

    record MinuteBar(LocalDateTime openTime,
                     BigDecimal open,
                     BigDecimal high,
                     BigDecimal low,
                     BigDecimal close) {
    }

    record Pricing(BigDecimal exitPrice,
                   BigDecimal pnlUsdt,
                   BigDecimal feesUsdt,
                   BigDecimal returnPct) {
    }

    record EventResult(EntryIntent entry,
                       String classification,
                       String outcome,
                       boolean finalized,
                       LocalDateTime entryTime,
                       LocalDateTime exitTime,
                       BigDecimal entryPrice,
                       BigDecimal exitPrice,
                       BigDecimal pnlUsdt,
                       BigDecimal stressPnlUsdt,
                       BigDecimal feesUsdt,
                       BigDecimal returnPct,
                       BigDecimal mfePct,
                       BigDecimal maePct,
                       BigDecimal benchmark72hReturnPct,
                       double coverage) {
        EventResult withBenchmark(BigDecimal benchmarkReturnPct) {
            return new EventResult(entry, classification, outcome, finalized, entryTime, exitTime,
                    entryPrice, exitPrice, pnlUsdt, stressPnlUsdt, feesUsdt, returnPct,
                    mfePct, maePct, benchmarkReturnPct, coverage);
        }

        static EventResult skipped(EntryIntent entry, String outcome, LocalDateTime entryTime) {
            return unresolved(entry, outcome, entryTime, null, BigDecimal.ZERO, 0.0);
        }

        static EventResult unresolved(EntryIntent entry,
                                      String outcome,
                                      LocalDateTime entryTime,
                                      LocalDateTime exitTime,
                                      BigDecimal pnl,
                                      double coverage) {
            return new EventResult(entry, "NOT_FINALIZED", outcome, false, entryTime, exitTime,
                    null, null, pnl, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, null, coverage);
        }

        static EventResult finalized(EntryIntent entry,
                                     String outcome,
                                     LocalDateTime entryTime,
                                     LocalDateTime exitTime,
                                     BigDecimal entryPrice,
                                     BigDecimal exitPrice,
                                     BigDecimal pnl,
                                     BigDecimal stressPnl,
                                     BigDecimal fees,
                                     BigDecimal returnPct,
                                     BigDecimal mfe,
                                     BigDecimal mae,
                                     BigDecimal benchmark72,
                                     double coverage) {
            return new EventResult(entry, "FINALIZED", outcome, true, entryTime, exitTime,
                    entryPrice, exitPrice, pnl, stressPnl, fees, returnPct, mfe, mae, benchmark72, coverage);
        }
    }

    record BenchmarkResult(EntryIntent entry,
                           String outcome,
                           boolean finalized,
                           LocalDateTime entryTime,
                           LocalDateTime exitTime,
                           BigDecimal returnPct,
                           double coverage) {
        static BenchmarkResult skipped(EntryIntent entry, String outcome, LocalDateTime entryTime) {
            return unresolved(entry, outcome, entryTime, null, null, 0.0);
        }

        static BenchmarkResult unresolved(EntryIntent entry,
                                          String outcome,
                                          LocalDateTime entryTime,
                                          LocalDateTime exitTime,
                                          BigDecimal returnPct,
                                          double coverage) {
            return new BenchmarkResult(entry, outcome, false, entryTime, exitTime, returnPct, coverage);
        }

        static BenchmarkResult finalized(EntryIntent entry,
                                         LocalDateTime entryTime,
                                         LocalDateTime exitTime,
                                         BigDecimal returnPct,
                                         double coverage) {
            return new BenchmarkResult(
                    entry, "TIME_EXIT_72H", true, entryTime, exitTime, returnPct, coverage);
        }
    }

    record WindowMetrics(int events,
                         double averageReturnPct,
                         double medianReturnPct,
                         double winRatePct,
                         double profitFactor,
                         double averageMfePct,
                         double averageMaePct,
                         BigDecimal totalPnlUsdt,
                         double maxDrawdownPct) {
        static WindowMetrics empty() {
            return new WindowMetrics(0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0);
        }

        boolean positive() {
            return averageReturnPct > 0 && medianReturnPct > 0 && profitFactor > 1.0;
        }
    }

    record Fold(int fold,
                LocalDateTime startUtc,
                LocalDateTime endUtc,
                int events,
                BigDecimal netPnlUsdt,
                boolean positive) {
    }

    record WalkForward(int positiveFolds, int nonEmptyFolds, List<Fold> folds) {
    }
}
