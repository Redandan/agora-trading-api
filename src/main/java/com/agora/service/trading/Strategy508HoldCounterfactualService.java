package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-only counterfactual for strategy 508 BUY events that reached the strategy
 * trigger but were stopped by an allowlisted research gate.
 */
@Service
@RequiredArgsConstructor
public class Strategy508HoldCounterfactualService {

    static final long STRATEGY_ID = 508L;
    static final int MIN_FINALIZED_EVENTS = 30;
    static final BigDecimal NOTIONAL_USDT = new BigDecimal("10.00");
    static final BigDecimal FEE_RATE = new BigDecimal("0.001");
    static final BigDecimal TAKE_PROFIT_PCT = new BigDecimal("0.06");
    static final BigDecimal STOP_LOSS_PCT = new BigDecimal("0.12");
    static final double MIN_KLINE_COVERAGE = 0.99;

    private static final int DEFAULT_HOURS = 720;
    private static final int MAX_HOURS = 2160;
    private static final int DEFAULT_DETAIL_LIMIT = 50;
    private static final int MAX_DETAIL_LIMIT = 200;
    private static final int MAX_EVIDENCE_ROWS = 20_000;
    private static final int KLINE_LOOKAHEAD_TOLERANCE_MINUTES = 5;

    private static final Set<String> SOFT_BLOCKER_MARKERS = Set.of(
            "EXPECTEDVALUEGATE",
            "TRADEPLANQUALITYGATE",
            "ENSEMBLEGATE",
            "LONGAIFILTER",
            "REGIMEFILTER");

    private static final Set<String> HARD_BLOCKER_MARKERS = Set.of(
            "DATAFRESHNESS",
            "SYSTEMHEALTH",
            "DAILYLOSS",
            "EVENTRISK",
            "ENTRYDEDUP",
            "EXPOSURE",
            "POSITIONSIZING",
            "OCOPREFLIGHT",
            "OCOHEALTH",
            "DUPLICATEBAR",
            "EXACTDUPLICATE",
            "MAXLOSS",
            "MINNOTIONAL",
            "BELOWMINNOTIONAL",
            "INSUFFICIENTBALANCE",
            "TRADINGDISABLED",
            "SCHEDULERDISABLED",
            "STALEKLINE",
            "MISSINGKLINE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String analyze(String symbol, Integer hours, Integer detailLimit) {
        String normalizedSymbol = normalizeSymbol(symbol);
        int windowHours = hours == null ? DEFAULT_HOURS : Math.max(24, Math.min(hours, MAX_HOURS));
        int maxDetails = detailLimit == null
                ? DEFAULT_DETAIL_LIMIT
                : Math.max(1, Math.min(detailLimit, MAX_DETAIL_LIMIT));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime since = now.minusHours(windowHours);

        try {
            List<Map<String, Object>> evidenceRows = loadEvidence(normalizedSymbol, since, now);
            List<EventChain> chains = buildEventChains(evidenceRows, normalizedSymbol);
            List<EventChain> eligible = chains.stream()
                    .filter(chain -> "ELIGIBLE_SOFT_GATE_COUNTERFACTUAL".equals(classify(chain)))
                    .toList();

            NavigableMap<LocalDateTime, MinuteBar> bars = new TreeMap<>();
            if (!eligible.isEmpty()) {
                LocalDateTime first = eligible.stream()
                        .map(chain -> chain.decisionTime)
                        .filter(time -> time != null)
                        .min(LocalDateTime::compareTo)
                        .orElse(since);
                LocalDateTime last = eligible.stream()
                        .map(chain -> chain.decisionTime)
                        .filter(time -> time != null)
                        .max(LocalDateTime::compareTo)
                        .orElse(now);
                bars = loadMinuteBars(normalizedSymbol, first.minusMinutes(2), min(now, last.plusHours(24).plusMinutes(5)));
            }
            return buildReport(normalizedSymbol, windowHours, maxDetails, now, evidenceRows.size(), chains, bars,
                    evidenceRows.size() >= MAX_EVIDENCE_ROWS);
        } catch (Exception e) {
            return buildQueryFailureReport(normalizedSymbol, windowHours, e);
        }
    }

    String analyzeRowsForTest(String symbol,
                              Integer hours,
                              Integer detailLimit,
                              LocalDateTime now,
                              List<Map<String, Object>> evidenceRows,
                              List<Map<String, Object>> klineRows) {
        String normalizedSymbol = normalizeSymbol(symbol);
        int windowHours = hours == null ? DEFAULT_HOURS : Math.max(24, Math.min(hours, MAX_HOURS));
        int maxDetails = detailLimit == null
                ? DEFAULT_DETAIL_LIMIT
                : Math.max(1, Math.min(detailLimit, MAX_DETAIL_LIMIT));
        List<EventChain> chains = buildEventChains(evidenceRows, normalizedSymbol);
        return buildReport(normalizedSymbol, windowHours, maxDetails, now, evidenceRows.size(), chains,
                toMinuteBars(klineRows), false);
    }

    private List<Map<String, Object>> loadEvidence(String symbol, LocalDateTime since, LocalDateTime until) {
        return jdbc.queryForList("""
                SELECT a.id AS audit_id,
                       a.event_time,
                       a.strategy_id,
                       a.symbol,
                       COALESCE(a.interval_code, s.interval_code) AS interval_code,
                       COALESCE(a.bar_open_time, s.bar_open_time) AS bar_open_time,
                       a.event_type,
                       a.outcome,
                       a.blocker AS audit_blocker,
                       a.reason AS audit_reason,
                       a.context_json,
                       e.id AS evidence_id,
                       e.selected_action,
                       e.decision,
                       e.policy_mode,
                       e.policy_reason,
                       e.freshness_state,
                       e.terminal_blocker,
                       e.blocker_reason,
                       e.reason AS runtime_reason,
                       e.order_sent,
                       e.suppression_reason,
                       e.intent_created,
                       e.risk_gate_result_json,
                       COALESCE(s.auto_traded, 0) AS live_signal_auto_traded
                FROM bt_decision_audit a
                LEFT JOIN bt_runtime_decision_evidence e ON e.decision_id = a.id
                LEFT JOIN bt_live_signal s ON s.id = a.live_signal_id
                WHERE a.strategy_id = ?
                  AND a.symbol = ?
                  AND a.event_time >= ?
                  AND a.event_time <= ?
                  AND a.event_type IN ('SIGNAL_EVAL','SIGNAL_BUY','FILTER_BLOCK','ENTRY_SKIP','AUTOTRADE_OK','AUTOTRADE_FAIL')
                ORDER BY a.event_time ASC, a.id ASC
                LIMIT 20000
                """, STRATEGY_ID, symbol, since, until);
    }

    private NavigableMap<LocalDateTime, MinuteBar> loadMinuteBars(String symbol,
                                                                  LocalDateTime from,
                                                                  LocalDateTime to) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT open_time, open_price, high_price, low_price, close_price
                FROM md_kline
                WHERE symbol = ?
                  AND interval_code = '1m'
                  AND source = 'okx'
                  AND open_time >= ?
                  AND open_time <= ?
                ORDER BY open_time ASC
                """, symbol, from, to);
        return toMinuteBars(rows);
    }

    private NavigableMap<LocalDateTime, MinuteBar> toMinuteBars(List<Map<String, Object>> rows) {
        NavigableMap<LocalDateTime, MinuteBar> bars = new TreeMap<>();
        if (rows == null) {
            return bars;
        }
        for (Map<String, Object> row : rows) {
            LocalDateTime openTime = asTime(value(row, "open_time"));
            BigDecimal open = asDecimal(value(row, "open_price"));
            BigDecimal high = asDecimal(value(row, "high_price"));
            BigDecimal low = asDecimal(value(row, "low_price"));
            BigDecimal close = asDecimal(value(row, "close_price"));
            if (openTime != null && open != null && high != null && low != null && close != null) {
                bars.put(openTime, new MinuteBar(openTime, open, high, low, close));
            }
        }
        return bars;
    }

    private List<EventChain> buildEventChains(List<Map<String, Object>> rows, String symbol) {
        Map<String, EventChain> grouped = new LinkedHashMap<>();
        if (rows == null) {
            return List.of();
        }
        for (Map<String, Object> row : rows) {
            if (asLong(value(row, "strategy_id")) != STRATEGY_ID) {
                continue;
            }
            String rowSymbol = text(value(row, "symbol"));
            if (!symbol.equalsIgnoreCase(rowSymbol)) {
                continue;
            }
            JsonNode context = parseJson(value(row, "context_json"));
            LocalDateTime barOpen = firstNonNull(
                    asTime(value(row, "bar_open_time")),
                    recursiveTime(context, "candidateBarOpenTime", "bar_open_time", "latest_bar_open"));
            String interval = firstNonBlank(text(value(row, "interval_code")), recursiveText(context, "interval", "intervalCode"));
            Long auditId = nullableLong(value(row, "audit_id"));
            String key = barOpen == null || interval == null
                    ? "MISSING_EVENT_KEY|" + (auditId == null ? grouped.size() : auditId)
                    : STRATEGY_ID + "|" + symbol + "|LONG|" + interval.toLowerCase(Locale.ROOT) + "|" + barOpen;
            EventChain chain = grouped.computeIfAbsent(key,
                    ignored -> new EventChain(key, symbol, interval, barOpen));
            chain.rawRows++;
            chain.auditIds.add(auditId == null ? "N/A" : auditId.toString());

            LocalDateTime eventTime = asTime(value(row, "event_time"));
            String eventType = upper(value(row, "event_type"));
            String auditReason = text(value(row, "audit_reason"));
            boolean rowBuyEvidence = ("SIGNAL_EVAL".equals(eventType) && "BUY".equalsIgnoreCase(auditReason))
                    || "SIGNAL_BUY".equals(eventType)
                    || "BUY".equalsIgnoreCase(recursiveText(context, "decision", "signalDecision"));
            boolean rowHoldEvidence = "SIGNAL_EVAL".equals(eventType)
                    && "HOLD".equalsIgnoreCase(auditReason);
            boolean rowAllGates = "all_gates_passed".equalsIgnoreCase(recursiveText(context, "trigger_reason"))
                    || allStrategyGatesTrue(context);

            chain.buyEvidence |= rowBuyEvidence;
            chain.holdEvidence |= rowHoldEvidence;
            chain.allStrategyGatesProven |= rowAllGates;
            if (rowBuyEvidence && eventTime != null
                    && (chain.decisionTime == null || eventTime.isBefore(chain.decisionTime))) {
                chain.decisionTime = eventTime;
            }
            if (chain.firstEventTime == null || eventTime != null && eventTime.isBefore(chain.firstEventTime)) {
                chain.firstEventTime = eventTime;
            }

            BigDecimal candidateEntry = recursiveDecimal(context, "candidateEntry", "entry", "entryPrice");
            if (candidateEntry != null && candidateEntry.signum() > 0 && chain.candidateEntry == null) {
                chain.candidateEntry = candidateEntry;
            }

            chain.orderSent |= truthy(value(row, "order_sent"))
                    || truthy(value(row, "live_signal_auto_traded"))
                    || "AUTOTRADE_OK".equals(eventType)
                    || recursiveBoolean(context, "orderSent", "order_sent");

            classifyExplicitBlocker(chain,
                    text(value(row, "audit_blocker")),
                    firstNonBlank(auditReason, text(value(row, "runtime_reason"))),
                    eventType);
            classifyExplicitBlocker(chain,
                    text(value(row, "terminal_blocker")),
                    text(value(row, "blocker_reason")),
                    eventType);
            classifyExplicitBlocker(chain,
                    text(value(row, "suppression_reason")),
                    text(value(row, "policy_reason")),
                    eventType);

            String riskResult = firstNonBlank(
                    recursiveText(context, "riskGateResult", "exposureOptimizerDecision"),
                    text(value(row, "risk_gate_result_json")));
            if (riskResult != null && containsAnyNormalized(riskResult, HARD_BLOCKER_MARKERS)) {
                chain.hardBlockers.add(canonicalBlocker(riskResult));
            }
        }
        return new ArrayList<>(grouped.values());
    }

    private void classifyExplicitBlocker(EventChain chain, String blocker, String reason, String eventType) {
        if (blocker == null || blocker.isBlank()) {
            return;
        }
        String normalized = normalizeMarker(blocker + " " + firstNonBlank(reason, ""));
        if (isInformationalBlocker(normalized)) {
            return;
        }
        if (containsAnyNormalized(normalized, HARD_BLOCKER_MARKERS)) {
            chain.hardBlockers.add(canonicalBlocker(blocker));
            return;
        }
        if (containsAnyNormalized(normalized, SOFT_BLOCKER_MARKERS)) {
            chain.softBlockers.add(canonicalBlocker(blocker));
            return;
        }
        if ("FILTER_BLOCK".equals(eventType) || "ENTRY_SKIP".equals(eventType)) {
            chain.unknownBlockers.add(canonicalBlocker(blocker));
        }
    }

    private String classify(EventChain chain) {
        if (chain.barOpenTime == null || chain.intervalCode == null) {
            return "EXCLUDED_MISSING_EVENT_KEY";
        }
        if (chain.orderSent) {
            return "EXCLUDED_ALREADY_ORDERED";
        }
        if (!chain.hardBlockers.isEmpty()) {
            return "EXCLUDED_HARD_SAFETY_BLOCK";
        }
        if (!chain.unknownBlockers.isEmpty()) {
            return "EXCLUDED_UNCLASSIFIED_BLOCKER";
        }
        if (chain.buyEvidence && chain.holdEvidence) {
            return "EXCLUDED_INCONSISTENT_STRATEGY_DECISION";
        }
        if (!chain.buyEvidence || !chain.allStrategyGatesProven) {
            return "EXCLUDED_SIGNAL_NOT_READY";
        }
        if (chain.decisionTime == null) {
            return "EXCLUDED_MISSING_EVENT_KEY";
        }
        if (chain.softBlockers.isEmpty()) {
            return "EXCLUDED_NO_PROVEN_SOFT_BLOCKER";
        }
        return "ELIGIBLE_SOFT_GATE_COUNTERFACTUAL";
    }

    private String buildReport(String symbol,
                               int hours,
                               int detailLimit,
                               LocalDateTime now,
                               int rawEvidenceRows,
                               List<EventChain> chains,
                               NavigableMap<LocalDateTime, MinuteBar> bars,
                               boolean evidenceQueryTruncated) {
        List<EventResult> results = new ArrayList<>();
        Map<String, Integer> classifications = new LinkedHashMap<>();
        Map<String, Integer> blockerBreakdown = new LinkedHashMap<>();
        int hardSafetyEventsEligible = 0;

        for (EventChain chain : chains) {
            String classification = classify(chain);
            classifications.merge(classification, 1, Integer::sum);
            chain.hardBlockers.forEach(blocker -> blockerBreakdown.merge("HARD:" + blocker, 1, Integer::sum));
            chain.softBlockers.forEach(blocker -> blockerBreakdown.merge("SOFT:" + blocker, 1, Integer::sum));
            chain.unknownBlockers.forEach(blocker -> blockerBreakdown.merge("UNKNOWN:" + blocker, 1, Integer::sum));
            if ("ELIGIBLE_SOFT_GATE_COUNTERFACTUAL".equals(classification)) {
                if (!chain.hardBlockers.isEmpty()) {
                    hardSafetyEventsEligible++;
                }
                results.add(simulate(chain, bars, now));
            } else {
                results.add(EventResult.excluded(chain, classification));
            }
        }

        List<EventResult> eligible = results.stream().filter(EventResult::eligible).toList();
        List<EventResult> finalized = eligible.stream().filter(EventResult::finalized).toList();
        String sampleStatus = finalized.size() < MIN_FINALIZED_EVENTS || evidenceQueryTruncated
                ? "INSUFFICIENT_DATA"
                : "SHADOW_SAMPLE_READY_FOR_REVIEW_NOT_LIVE";

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "analyzeStrategy508HoldCounterfactual");
        root.put("boundary", "READ_ONLY");
        root.put("generatedAt", now.toInstant(ZoneOffset.UTC).toString());
        root.put("strategyId", STRATEGY_ID);
        root.put("strategyType", "OI_FUNDING_DIVERGENCE");
        root.put("symbol", symbol);
        root.put("hours", hours);
        root.put("klineSource", "okx");
        root.put("executionSemantics", "UNIQUE_MARKET_EVENT_ONE_FIXED_ORDER");
        root.put("sampleStatus", sampleStatus);
        root.put("sampleGateMinFinalizedEvents", MIN_FINALIZED_EVENTS);
        root.put("liveRelaxationAllowed", false);
        root.put("recommendation", finalized.size() < MIN_FINALIZED_EVENTS
                ? "COLLECT_MORE_UNIQUE_FINALIZED_EVENTS_KEEP_LIVE_GATES"
                : "REVIEW_SHADOW_EVIDENCE_SEPARATELY_NO_LIVE_AUTHORIZATION");

        ObjectNode safety = root.putObject("safety");
        safety.put("writesRuntimeEvidence", false);
        safety.put("orderSent", false);
        safety.put("ocoModified", false);
        safety.put("strategyFlagsChanged", false);
        safety.put("productionStateChanged", false);
        safety.put("hardSafetyEventsEligible", hardSafetyEventsEligible);
        safety.put("hardSafetyPolicy", "FAIL_CLOSED_ANY_HARD_BLOCK_EXCLUDES_WHOLE_EVENT");

        ObjectNode assumptions = root.putObject("simulationAssumptions");
        assumptions.put("notionalUsdt", NOTIONAL_USDT);
        assumptions.put("entryFeeRate", FEE_RATE);
        assumptions.put("exitFeeRate", FEE_RATE);
        assumptions.put("takeProfitPct", TAKE_PROFIT_PCT);
        assumptions.put("stopLossPct", STOP_LOSS_PCT);
        assumptions.put("maxHoldingHours", 24);
        assumptions.put("minOneMinuteKlineCoverage", MIN_KLINE_COVERAGE);
        assumptions.put("sameMinuteTpSl", "AMBIGUOUS_NOT_FINALIZED");
        assumptions.put("entryPrice", "candidate plan entry; first post-decision 1m open fallback");

        ObjectNode counts = root.putObject("counts");
        counts.put("rawEvidenceRows", rawEvidenceRows);
        counts.put("uniqueMarketEvents", chains.size());
        counts.put("eventChainRowsCollapsed", Math.max(0, rawEvidenceRows - chains.size()));
        counts.put("eligibleUniqueEvents", eligible.size());
        counts.put("finalizedUniqueEvents", finalized.size());
        counts.put("pendingUniqueEvents", countOutcome(eligible, "PENDING_24H"));
        counts.put("ambiguousSameMinuteEvents", countOutcome(eligible, "AMBIGUOUS_SAME_MINUTE"));
        counts.put("insufficientKlineCoverageEvents", countOutcome(eligible, "INSUFFICIENT_KLINE_COVERAGE"));
        counts.put("evidenceQueryTruncated", evidenceQueryTruncated);
        counts.put("hardSafetyEventsEligible", hardSafetyEventsEligible);

        ObjectNode classificationNode = root.putObject("classificationBreakdown");
        classifications.forEach(classificationNode::put);
        ObjectNode blockerNode = root.putObject("blockerBreakdown");
        blockerBreakdown.forEach(blockerNode::put);

        ObjectNode metrics = root.putObject("metrics");
        appendMetrics(metrics, finalized, eligible);

        ArrayNode details = root.putArray("events");
        results.stream()
                .sorted(Comparator.<EventResult>comparingInt(result -> result.eligible ? 0 : 1)
                        .thenComparing(EventResult::sortTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(detailLimit)
                .forEach(result -> appendEvent(details.addObject(), result));

        ArrayNode methodology = root.putArray("methodology");
        methodology.add("Unique key=strategyId|symbol|side|interval|barOpenTime; blocker/reason/source never enter the key.");
        methodology.add("A strategy 508 event is eligible only when BUY and all_gates_passed are proven and an allowlisted soft gate is the only blocker.");
        methodology.add("Ordinary HOLD, missing predicate evidence, unknown blockers, existing orders, and every hard-safety blocker fail closed.");
        methodology.add("PnL uses fixed 10 USDT gross buy notional, both entry and exit fees, and first-touch +6% TP / -12% SL on OKX 1m bars.");
        methodology.add("Fewer than 30 unique finalized events is INSUFFICIENT_DATA and cannot justify a live policy relaxation.");

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return root.toString();
        }
    }

    private EventResult simulate(EventChain chain,
                                 NavigableMap<LocalDateTime, MinuteBar> bars,
                                 LocalDateTime now) {
        LocalDateTime startMinute = chain.decisionTime.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        Map.Entry<LocalDateTime, MinuteBar> firstBarEntry = bars.ceilingEntry(startMinute);
        MinuteBar firstBar = firstBarEntry == null ? null : firstBarEntry.getValue();
        if (firstBar == null || firstBar.openTime.isAfter(startMinute.plusMinutes(KLINE_LOOKAHEAD_TOLERANCE_MINUTES))) {
            return EventResult.unresolved(chain, "INSUFFICIENT_KLINE_COVERAGE", null, null,
                    null, null, null, null, null, null, 0, 0.0, "NO_ENTRY_BAR");
        }

        BigDecimal entry = chain.candidateEntry != null ? chain.candidateEntry : firstBar.open;
        String entrySource = chain.candidateEntry != null ? "CANDIDATE_PLAN" : "FIRST_POST_DECISION_1M_OPEN";
        BigDecimal tp = entry.multiply(BigDecimal.ONE.add(TAKE_PROFIT_PCT));
        BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(STOP_LOSS_PCT));
        LocalDateTime horizon = chain.decisionTime.plusHours(24);
        LocalDateTime scanUntil = min(now, horizon);
        NavigableMap<LocalDateTime, MinuteBar> window = bars.subMap(firstBar.openTime, true, scanUntil, true);

        BigDecimal maxHigh = entry;
        BigDecimal minLow = entry;
        String firstTouch = null;
        LocalDateTime firstTouchTime = null;
        int barsThroughTouch = 0;
        int seen = 0;
        for (MinuteBar bar : window.values()) {
            seen++;
            maxHigh = max(maxHigh, bar.high);
            minLow = min(minLow, bar.low);
            if (firstTouch == null) {
                boolean tpHit = bar.high.compareTo(tp) >= 0;
                boolean slHit = bar.low.compareTo(sl) <= 0;
                if (tpHit && slHit) {
                    firstTouch = "AMBIGUOUS_SAME_MINUTE";
                    firstTouchTime = bar.openTime;
                    barsThroughTouch = seen;
                } else if (tpHit) {
                    firstTouch = "TP_HIT";
                    firstTouchTime = bar.openTime;
                    barsThroughTouch = seen;
                } else if (slHit) {
                    firstTouch = "SL_HIT";
                    firstTouchTime = bar.openTime;
                    barsThroughTouch = seen;
                }
            }
        }

        BigDecimal ret1h = netReturnPct(entry, closeNear(bars, chain.decisionTime.plusHours(1)));
        BigDecimal ret4h = netReturnPct(entry, closeNear(bars, chain.decisionTime.plusHours(4)));
        BigDecimal ret24h = netReturnPct(entry, closeNear(bars, horizon));
        BigDecimal mfe = pct(maxHigh, entry);
        BigDecimal mae = pct(minLow, entry);

        if ("AMBIGUOUS_SAME_MINUTE".equals(firstTouch)) {
            return EventResult.unresolved(chain, firstTouch, entry, tp, sl, ret1h, ret4h, ret24h,
                    mfe, mae, seen, coverage(firstBar.openTime, firstTouchTime, barsThroughTouch), entrySource);
        }

        if ("TP_HIT".equals(firstTouch) || "SL_HIT".equals(firstTouch)) {
            double coverage = coverage(firstBar.openTime, firstTouchTime, barsThroughTouch);
            if (coverage < MIN_KLINE_COVERAGE) {
                return EventResult.unresolved(chain, "INSUFFICIENT_KLINE_COVERAGE", entry, tp, sl,
                        ret1h, ret4h, ret24h, mfe, mae, seen, coverage, entrySource);
            }
            BigDecimal exit = "TP_HIT".equals(firstTouch) ? tp : sl;
            return EventResult.finalized(chain, firstTouch, entry, exit, tp, sl, ret1h, ret4h, ret24h,
                    mfe, mae, seen, coverage, entrySource, tradePnl(entry, exit), tradeFees(entry, exit));
        }

        if (now.isBefore(horizon)) {
            return EventResult.unresolved(chain, "PENDING_24H", entry, tp, sl, ret1h, ret4h, ret24h,
                    mfe, mae, seen, coverage(firstBar.openTime, scanUntil, seen), entrySource);
        }

        double coverage = coverage(firstBar.openTime, horizon, seen);
        BigDecimal exit24h = closeNear(bars, horizon);
        if (coverage < MIN_KLINE_COVERAGE || exit24h == null) {
            return EventResult.unresolved(chain, "INSUFFICIENT_KLINE_COVERAGE", entry, tp, sl,
                    ret1h, ret4h, ret24h, mfe, mae, seen, coverage, entrySource);
        }
        return EventResult.finalized(chain, "TIMEOUT_24H", entry, exit24h, tp, sl,
                ret1h, ret4h, ret24h, mfe, mae, seen, coverage, entrySource,
                tradePnl(entry, exit24h), tradeFees(entry, exit24h));
    }

    private void appendMetrics(ObjectNode metrics, List<EventResult> finalized, List<EventResult> eligible) {
        BigDecimal totalPnl = finalized.stream().map(EventResult::pnlUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFees = finalized.stream().map(EventResult::feesUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossNotional = NOTIONAL_USDT.multiply(BigDecimal.valueOf(finalized.size()));
        long profitable = finalized.stream().filter(row -> row.pnlUsdt.signum() > 0).count();

        metrics.put("totalGrossBuyNotionalUsdt", money(grossNotional));
        metrics.put("totalFeesUsdt", money(totalFees));
        metrics.put("totalPnlUsdt", money(totalPnl));
        metrics.put("averagePnlUsdt", money(average(finalized.stream().map(EventResult::pnlUsdt).toList())));
        metrics.put("profitableEventRatePct", finalized.isEmpty() ? 0.0 : round(100.0 * profitable / finalized.size(), 4));
        metrics.put("tpHitCount", countOutcome(finalized, "TP_HIT"));
        metrics.put("slHitCount", countOutcome(finalized, "SL_HIT"));
        metrics.put("timeout24hCount", countOutcome(finalized, "TIMEOUT_24H"));
        appendAverageMetric(metrics, "return1h", eligible.stream().map(EventResult::return1hPct).toList());
        appendAverageMetric(metrics, "return4h", eligible.stream().map(EventResult::return4hPct).toList());
        appendAverageMetric(metrics, "return24h", eligible.stream().map(EventResult::return24hPct).toList());
        appendAverageMetric(metrics, "mfe24h", eligible.stream().map(EventResult::mfePct).toList());
        appendAverageMetric(metrics, "mae24h", eligible.stream().map(EventResult::maePct).toList());

        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        List<EventResult> chronological = finalized.stream()
                .sorted(Comparator.comparing(EventResult::sortTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (EventResult result : chronological) {
            cumulative = cumulative.add(result.pnlUsdt);
            peak = max(peak, cumulative);
            maxDrawdown = max(maxDrawdown, peak.subtract(cumulative));
        }
        metrics.put("maxCumulativePnlDrawdownUsdt", money(maxDrawdown));
        metrics.put("maxDrawdownPctOfGrossBuyNotional", grossNotional.signum() == 0
                ? 0.0
                : round(maxDrawdown.divide(grossNotional, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue(), 4));
    }

    private void appendAverageMetric(ObjectNode metrics, String name, List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(value -> value != null).toList();
        metrics.put(name + "SampleCount", present.size());
        metrics.put("average" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Pct", money(average(present)));
    }

    private void appendEvent(ObjectNode node, EventResult result) {
        EventChain chain = result.chain;
        node.put("eventKey", chain.key);
        node.put("barOpenTimeUtc", chain.barOpenTime == null ? "N/A" : chain.barOpenTime.toString());
        node.put("decisionTimeUtc", chain.decisionTime == null ? "N/A" : chain.decisionTime.toString());
        node.put("intervalCode", firstNonBlank(chain.intervalCode, "N/A"));
        node.put("classification", result.classification);
        node.put("eligible", result.eligible);
        node.put("finalized", result.finalized);
        node.put("outcome", result.outcome);
        node.put("rawRows", chain.rawRows);
        node.putPOJO("auditIds", chain.auditIds);
        node.putPOJO("softBlockers", chain.softBlockers);
        node.putPOJO("hardBlockers", chain.hardBlockers);
        node.putPOJO("unknownBlockers", chain.unknownBlockers);
        putDecimal(node, "entryPrice", result.entryPrice);
        node.put("entryPriceSource", firstNonBlank(result.entryPriceSource, "N/A"));
        putDecimal(node, "takeProfitPrice", result.takeProfitPrice);
        putDecimal(node, "stopLossPrice", result.stopLossPrice);
        putDecimal(node, "return1hPct", result.return1hPct);
        putDecimal(node, "return4hPct", result.return4hPct);
        putDecimal(node, "return24hPct", result.return24hPct);
        putDecimal(node, "mfePct", result.mfePct);
        putDecimal(node, "maePct", result.maePct);
        putDecimal(node, "pnlUsdt", result.pnlUsdt);
        putDecimal(node, "feesUsdt", result.feesUsdt);
        node.put("oneMinuteBarsObserved", result.oneMinuteBarsObserved);
        node.put("klineCoverage", round(result.klineCoverage, 6));
    }

    private String buildQueryFailureReport(String symbol, int hours, Exception error) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "analyzeStrategy508HoldCounterfactual");
        root.put("boundary", "READ_ONLY");
        root.put("strategyId", STRATEGY_ID);
        root.put("symbol", symbol);
        root.put("hours", hours);
        root.put("sampleStatus", "DATA_QUERY_FAILED");
        root.put("sampleGateMinFinalizedEvents", MIN_FINALIZED_EVENTS);
        root.put("liveRelaxationAllowed", false);
        root.put("error", truncate(error.getMessage(), 300));
        ObjectNode safety = root.putObject("safety");
        safety.put("writesRuntimeEvidence", false);
        safety.put("orderSent", false);
        safety.put("ocoModified", false);
        safety.put("productionStateChanged", false);
        safety.put("hardSafetyEventsEligible", 0);
        return root.toPrettyString();
    }

    private BigDecimal closeNear(NavigableMap<LocalDateTime, MinuteBar> bars, LocalDateTime target) {
        Map.Entry<LocalDateTime, MinuteBar> entry = bars.ceilingEntry(target.truncatedTo(ChronoUnit.MINUTES));
        if (entry == null || entry.getKey().isAfter(target.plusMinutes(KLINE_LOOKAHEAD_TOLERANCE_MINUTES))) {
            return null;
        }
        return entry.getValue().close;
    }

    private BigDecimal netReturnPct(BigDecimal entry, BigDecimal exit) {
        if (entry == null || exit == null || entry.signum() <= 0) {
            return null;
        }
        BigDecimal pnl = tradePnl(entry, exit);
        BigDecimal cost = NOTIONAL_USDT.add(NOTIONAL_USDT.multiply(FEE_RATE));
        return pnl.divide(cost, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal tradePnl(BigDecimal entry, BigDecimal exit) {
        BigDecimal quantity = NOTIONAL_USDT.divide(entry, 16, RoundingMode.HALF_UP);
        BigDecimal exitGross = quantity.multiply(exit);
        return exitGross.subtract(exitGross.multiply(FEE_RATE))
                .subtract(NOTIONAL_USDT)
                .subtract(NOTIONAL_USDT.multiply(FEE_RATE));
    }

    private BigDecimal tradeFees(BigDecimal entry, BigDecimal exit) {
        BigDecimal quantity = NOTIONAL_USDT.divide(entry, 16, RoundingMode.HALF_UP);
        BigDecimal exitGross = quantity.multiply(exit);
        return NOTIONAL_USDT.multiply(FEE_RATE).add(exitGross.multiply(FEE_RATE));
    }

    private BigDecimal pct(BigDecimal price, BigDecimal entry) {
        if (price == null || entry == null || entry.signum() <= 0) {
            return null;
        }
        return price.subtract(entry).divide(entry, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    private double coverage(LocalDateTime first, LocalDateTime last, int observed) {
        if (first == null || last == null || last.isBefore(first)) {
            return 0.0;
        }
        long expected = Math.max(1, Duration.between(first, last.truncatedTo(ChronoUnit.MINUTES)).toMinutes() + 1);
        return Math.min(1.0, (double) observed / expected);
    }

    private boolean allStrategyGatesTrue(JsonNode context) {
        return recursiveBoolean(context, "gate_funding_low")
                && recursiveBoolean(context, "gate_oi_stable")
                && recursiveBoolean(context, "gate_volume_confirmed")
                && recursiveBoolean(context, "gate_above_sma200")
                && recursiveBoolean(context, "gate_dex_flow")
                && recursiveBoolean(context, "gate_spread_ok");
    }

    private JsonNode parseJson(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return objectMapper.missingNode();
        }
        try {
            return objectMapper.readTree(String.valueOf(value));
        } catch (Exception ignored) {
            return objectMapper.missingNode();
        }
    }

    private String recursiveText(JsonNode node, String... names) {
        JsonNode found = recursiveField(node, names);
        return found == null || found.isNull() || found.isContainerNode() ? null : found.asText();
    }

    private BigDecimal recursiveDecimal(JsonNode node, String... names) {
        JsonNode found = recursiveField(node, names);
        if (found == null || found.isNull() || found.isContainerNode()) {
            return null;
        }
        return asDecimal(found.isNumber() ? found.numberValue() : found.asText());
    }

    private boolean recursiveBoolean(JsonNode node, String... names) {
        JsonNode found = recursiveField(node, names);
        if (found == null || found.isNull() || found.isContainerNode()) {
            return false;
        }
        return found.isBoolean() ? found.asBoolean() : truthy(found.asText());
    }

    private LocalDateTime recursiveTime(JsonNode node, String... names) {
        JsonNode found = recursiveField(node, names);
        return found == null || found.isNull() || found.isContainerNode() ? null : asTime(found.asText());
    }

    private JsonNode recursiveField(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            for (String name : names) {
                JsonNode direct = node.get(name);
                if (direct != null && !direct.isNull()) {
                    return direct;
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                JsonNode found = recursiveField(fields.next().getValue(), names);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = recursiveField(child, names);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean isInformationalBlocker(String normalized) {
        return normalized.isBlank()
                || normalized.equals("NONE")
                || normalized.equals("NA")
                || normalized.equals("HOLD")
                || normalized.equals("BUY")
                || normalized.startsWith("ATTENTIONRULE")
                || normalized.startsWith("AUTONOMOUSEXECUTIONINTENT")
                || normalized.contains("EXPECTEDVALUEGATEPASS");
    }

    private boolean containsAnyNormalized(String value, Set<String> markers) {
        String normalized = normalizeMarker(value);
        return markers.stream().anyMatch(normalized::contains);
    }

    private String canonicalBlocker(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        int separator = value.indexOf(':');
        String candidate = separator > 0 ? value.substring(0, separator) : value;
        candidate = candidate.trim();
        return candidate.length() > 80 ? candidate.substring(0, 80) : candidate;
    }

    private String normalizeMarker(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private Object value(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private LocalDateTime asTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            String text = String.valueOf(value).trim();
            try {
                return LocalDateTime.parse(text.replace("Z", ""));
            } catch (Exception ignored) {
                try {
                    return LocalDateTime.ofInstant(Instant.parse(text), ZoneOffset.UTC);
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
        }
        return null;
    }

    private BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return new BigDecimal(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private long asLong(Object value) {
        Long parsed = nullableLong(value);
        return parsed == null ? Long.MIN_VALUE : parsed;
    }

    private Long nullableLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String upper(Object value) {
        String text = text(value);
        return text == null ? "" : text.toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private LocalDateTime min(LocalDateTime left, LocalDateTime right) {
        return left.isBefore(right) ? left : right;
    }

    private BigDecimal max(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private BigDecimal min(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP);
    }

    private int countOutcome(List<EventResult> results, String outcome) {
        return (int) results.stream().filter(result -> outcome.equals(result.outcome)).count();
    }

    private double money(BigDecimal value) {
        return value == null ? 0.0 : value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().doubleValue();
    }

    private double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private void putDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, money(value));
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "unknown";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class EventChain {
        private final String key;
        private final String symbol;
        private final String intervalCode;
        private final LocalDateTime barOpenTime;
        private final Set<String> auditIds = new LinkedHashSet<>();
        private final Set<String> softBlockers = new LinkedHashSet<>();
        private final Set<String> hardBlockers = new LinkedHashSet<>();
        private final Set<String> unknownBlockers = new LinkedHashSet<>();
        private LocalDateTime firstEventTime;
        private LocalDateTime decisionTime;
        private BigDecimal candidateEntry;
        private int rawRows;
        private boolean buyEvidence;
        private boolean holdEvidence;
        private boolean allStrategyGatesProven;
        private boolean orderSent;

        private EventChain(String key, String symbol, String intervalCode, LocalDateTime barOpenTime) {
            this.key = key;
            this.symbol = symbol;
            this.intervalCode = intervalCode;
            this.barOpenTime = barOpenTime;
        }
    }

    private record MinuteBar(LocalDateTime openTime,
                             BigDecimal open,
                             BigDecimal high,
                             BigDecimal low,
                             BigDecimal close) {
    }

    private record EventResult(EventChain chain,
                               String classification,
                               boolean eligible,
                               boolean finalized,
                               String outcome,
                               BigDecimal entryPrice,
                               BigDecimal exitPrice,
                               BigDecimal takeProfitPrice,
                               BigDecimal stopLossPrice,
                               BigDecimal return1hPct,
                               BigDecimal return4hPct,
                               BigDecimal return24hPct,
                               BigDecimal mfePct,
                               BigDecimal maePct,
                               BigDecimal pnlUsdt,
                               BigDecimal feesUsdt,
                               int oneMinuteBarsObserved,
                               double klineCoverage,
                               String entryPriceSource) {

        private static EventResult excluded(EventChain chain, String classification) {
            return new EventResult(chain, classification, false, false, "NOT_SIMULATED", null, null,
                    null, null, null, null, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO,
                    0, 0.0, null);
        }

        private static EventResult unresolved(EventChain chain,
                                              String outcome,
                                              BigDecimal entry,
                                              BigDecimal tp,
                                              BigDecimal sl,
                                              BigDecimal ret1h,
                                              BigDecimal ret4h,
                                              BigDecimal ret24h,
                                              BigDecimal mfe,
                                              BigDecimal mae,
                                              int bars,
                                              double coverage,
                                              String entrySource) {
            return new EventResult(chain, "ELIGIBLE_SOFT_GATE_COUNTERFACTUAL", true, false, outcome,
                    entry, null, tp, sl, ret1h, ret4h, ret24h, mfe, mae, BigDecimal.ZERO,
                    BigDecimal.ZERO, bars, coverage, entrySource);
        }

        private static EventResult finalized(EventChain chain,
                                             String outcome,
                                             BigDecimal entry,
                                             BigDecimal exit,
                                             BigDecimal tp,
                                             BigDecimal sl,
                                             BigDecimal ret1h,
                                             BigDecimal ret4h,
                                             BigDecimal ret24h,
                                             BigDecimal mfe,
                                             BigDecimal mae,
                                             int bars,
                                             double coverage,
                                             String entrySource,
                                             BigDecimal pnl,
                                             BigDecimal fees) {
            return new EventResult(chain, "ELIGIBLE_SOFT_GATE_COUNTERFACTUAL", true, true, outcome,
                    entry, exit, tp, sl, ret1h, ret4h, ret24h, mfe, mae, pnl, fees,
                    bars, coverage, entrySource);
        }

        private LocalDateTime sortTime() {
            return chain.decisionTime != null ? chain.decisionTime : chain.firstEventTime;
        }
    }
}
