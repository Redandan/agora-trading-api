package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.service.tradingview.TradingViewScoreBuyAutoExitStrategyContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Bounded read-only report. Gaps and mixed bases cannot become a drawdown claim. */
@Service
@RequiredArgsConstructor
public class SpotPerformanceReportService {
    private static final int LIMIT = 2000;
    private final BtDecisionAuditRepository audits;
    private final ObjectMapper mapper;

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public String report() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            var rows = audits.findWindow(now.minusDays(30), now, "BTCUSDT", null, false,
                    List.of(SpotPerformancePolicy.EVENT), PageRequest.of(0, LIMIT));
            var blocks = audits.findWindow(now.minusDays(30), now, "BTCUSDT", null, false,
                    List.of("ENTRY_SKIP"), PageRequest.of(0, LIMIT));
            var evaluations = audits.findWindow(now.minusDays(30), now, "BTCUSDT", null, false,
                    List.of("SPOT_ENTRY_EVAL_V1"), PageRequest.of(0, LIMIT));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("asOfUtc", now.toString());
            result.put("requestedWindowDays", 30);
            result.put("windowTruncated", rows.size() == LIMIT || blocks.size() == LIMIT || evaluations.size() == LIMIT);
            result.put("basis", SpotPerformancePolicy.BASIS);
            result.put("scope", "STRATEGY_RECORDED_LEDGER_EXCLUDES_GRID_LEGACY_MANUAL_BALANCES");
            result.put("drawdownBasis", "OBSERVED_HOURLY_REFERENCE_EQUITY_NOT_FEE_EXACT_ACCOUNT_DRAWDOWN");
            result.put("strategies", List.of(summarize("DRA_V1", rows, blocks, now),
                    summarize("TV509", rows, blocks, now)));
            result.put("entryEvaluations", List.of(entryEvaluations("DRA_V1", evaluations),
                    entryEvaluations("TV509", evaluations)));
            return "FORWARD_SPOT_PERFORMANCE\n" + mapper.writeValueAsString(result);
        } catch (Exception e) {
            return "FORWARD_SPOT_PERFORMANCE\nstatus=UNAVAILABLE errorType=" + e.getClass().getSimpleName();
        }
    }

    Map<String, Object> summarize(String owner, List<BtDecisionAudit> rows,
                                  List<BtDecisionAudit> blocks, LocalDateTime now) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", owner);
        Long strategyId = "DRA_V1".equals(owner) ? BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID
                : TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID;
        TreeMap<LocalDateTime, JsonNode> samples = new TreeMap<>();
        boolean complete = rows.size() < LIMIT;
        int duplicates = 0, invalid = 0;
        for (BtDecisionAudit row : rows) {
            if (!strategyId.equals(row.getStrategyId())) continue;
            JsonNode n;
            try {
                n = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .readTree(row.getContextJson());
                if (n == null || !owner.equals(n.path("owner").asText())
                        || !SpotPerformancePolicy.EVENT.equals(n.path("schema").asText())
                        || row.getBarOpenTime() == null
                        || !row.getBarOpenTime().plusHours(1).toString().equals(n.path("markTimeUtc").asText())
                        || row.getBarOpenTime().plusHours(1).isAfter(now)
                        || row.getBarOpenTime().getMinute() != 0 || row.getBarOpenTime().getSecond() != 0) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception e) { invalid++; complete = false; continue; }
            if (samples.putIfAbsent(row.getBarOpenTime(), n) != null) { duplicates++; complete = false; }
        }
        out.put("sampleCount", samples.size());
        out.put("duplicateSamples", duplicates);
        out.put("invalidSamples", invalid);
        out.put("missingBeforeFirstSample", "NOT_BACKFILLED");
        long missingHours = 0;
        LocalDateTime previous = null;
        BigDecimal capital = null, peak = null, maxDrawdown = BigDecimal.ZERO, maxDrawdownUsdt = BigDecimal.ZERO;
        BigDecimal utilizationSum = BigDecimal.ZERO;
        for (var entry : samples.entrySet()) {
            if (previous != null) {
                long hours = Duration.between(previous, entry.getKey()).toHours();
                if (hours != 1) { complete = false; missingHours += Math.max(0, hours - 1); }
            }
            previous = entry.getKey();
            JsonNode n = entry.getValue();
            if (!"OBSERVED".equals(n.path("status").asText())
                    || !SpotPerformancePolicy.BASIS.equals(n.path("basis").asText())
                    || !n.path("referenceEquityUsdt").isNumber() || !n.path("referenceCapitalUsdt").isNumber()
                    || !n.path("capitalUtilization").isNumber()) { complete = false; continue; }
            BigDecimal reference = n.path("referenceCapitalUsdt").decimalValue();
            BigDecimal equity = n.path("referenceEquityUsdt").decimalValue();
            if (capital == null) capital = reference;
            if (capital.compareTo(reference) != 0 || reference.signum() <= 0 || equity.signum() <= 0) {
                complete = false; continue;
            }
            peak = peak == null ? equity : peak.max(equity);
            BigDecimal drop = peak.subtract(equity);
            maxDrawdownUsdt = maxDrawdownUsdt.max(drop);
            maxDrawdown = maxDrawdown.max(drop.divide(peak, 12, RoundingMode.HALF_UP));
            utilizationSum = utilizationSum.add(n.path("capitalUtilization").decimalValue());
        }
        out.put("missingHoursWithinObservedWindow", missingHours);
        if (!samples.isEmpty()) {
            out.put("firstBarOpenUtc", samples.firstKey().toString());
            out.put("lastBarOpenUtc", samples.lastKey().toString());
            out.put("latest", samples.lastEntry().getValue());
            out.put("latestStatus", now.isAfter(samples.lastKey().plusHours(2).plusMinutes(2)) ? "STALE" : "CURRENT");
        }
        boolean eligible = complete && samples.size() >= 2;
        out.put("seriesStatus", samples.isEmpty() ? "MISSING_PROOF_NO_FORWARD_SAMPLES"
                : eligible ? "COMPLETE_OBSERVED_WINDOW" : "MISSING_PROOF_GAP_DUPLICATE_BASIS_OR_SAMPLE_COUNT");
        out.put("sampledMaximumDrawdownRatio", eligible ? maxDrawdown : null);
        out.put("sampledMaximumDrawdownUsdt", eligible ? maxDrawdownUsdt : null);
        out.put("meanHourlyCapitalUtilization", eligible
                ? utilizationSum.divide(BigDecimal.valueOf(samples.size()), 12, RoundingMode.HALF_UP) : null);
        out.put("blockedEntryEvidence", blocked(strategyId, blocks));
        return out;
    }

    private Map<String, Object> blocked(Long strategyId, List<BtDecisionAudit> rows) throws Exception {
        // Count unique signal bars, not repeated audit rows. Absence is never a zero-opportunity claim.
        Set<LocalDateTime> bars = new HashSet<>();
        Map<String, Set<LocalDateTime>> reasons = new TreeMap<>();
        int invalid = 0;
        for (BtDecisionAudit row : rows) {
            if (!strategyId.equals(row.getStrategyId())) continue;
            JsonNode n;
            try { n = mapper.readTree(row.getContextJson()); }
            catch (Exception e) { invalid++; continue; }
            if (n == null || !"LIVE_ENTRY_V1".equals(n.path("entryEvidenceSchema").asText())
                    || !n.path("entryCandidate").asBoolean() || !n.path("freshSignal").asBoolean()
                    || row.getBarOpenTime() == null) continue;
            String reason = row.getReason() == null ? "UNKNOWN" : row.getReason();
            // Duplicate delivery is idempotency evidence, not a blocked new opportunity.
            if (reason.contains("DUPLICATE") || reason.contains("ALREADY_RESERVED")) continue;
            bars.add(row.getBarOpenTime());
            reasons.computeIfAbsent(reason, ignored -> new HashSet<>()).add(row.getBarOpenTime());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("observedDistinctBlockedSignalBars", bars.size());
        Map<String, Integer> counts = new TreeMap<>();
        reasons.forEach((key, value) -> counts.put(key, value.size()));
        out.put("reasonCounts", counts);
        out.put("invalidRows", invalid);
        out.put("coverage", "OBSERVED_LOWER_BOUND_NOT_COMPLETE_OPPORTUNITY_LEDGER");
        return out;
    }

    Map<String, Object> entryEvaluations(String owner, List<BtDecisionAudit> rows) {
        Long strategyId = "DRA_V1".equals(owner) ? BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID
                : TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID;
        TreeMap<LocalDateTime, JsonNode> unique = new TreeMap<>();
        int invalid = 0, duplicate = 0;
        for (BtDecisionAudit row : rows) {
            if (!strategyId.equals(row.getStrategyId())) continue;
            try {
                JsonNode n = mapper.readTree(row.getContextJson());
                if (n == null || !owner.equals(n.path("owner").asText())
                        || !"SPOT_ENTRY_EVAL_V1".equals(n.path("schema").asText())
                        || !n.path("candidate").isBoolean() || n.path("disposition").asText().isBlank()
                        || row.getBarOpenTime() == null) {
                    invalid++; continue;
                }
                if (unique.putIfAbsent(row.getBarOpenTime(), n) != null) duplicate++;
            } catch (Exception e) { invalid++; }
        }
        int candidates = 0, handled = 0, deferred = 0, blocked = 0, unconfirmed = 0;
        long missing = 0;
        LocalDateTime previous = null;
        for (var entry : unique.entrySet()) {
            if (previous != null) {
                long expectedHours = "DRA_V1".equals(owner) ? 1 : 24;
                long hours = Duration.between(previous, entry.getKey()).toHours();
                if (hours != expectedHours) missing += Math.max(1, hours / expectedHours - 1);
            }
            previous = entry.getKey();
            JsonNode n = entry.getValue();
            if (!n.path("candidate").asBoolean()) continue;
            candidates++;
            String disposition = n.path("disposition").asText();
            if ("BUY_PATH_HANDLED".equals(disposition)) handled++;
            else if (disposition.startsWith("DEFERRED_")) deferred++;
            else if ((disposition.startsWith("BLOCKED:") || disposition.startsWith("SCOPE_BLOCKED:"))
                    && !disposition.contains("DUPLICATE")) blocked++;
            else unconfirmed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", owner);
        out.put("candidateBasis", "DRA_V1".equals(owner) ? "VIRTUAL_ENTRY_QUEUED" : "FROZEN_WEIGHTED_BUY_INTENTS");
        out.put("observedEvaluationBars", unique.size());
        out.put("candidateBars", duplicate == 0 ? candidates : null);
        out.put("buyPathHandledBars", duplicate == 0 ? handled : null);
        out.put("deferredCandidateBars", duplicate == 0 ? deferred : null);
        out.put("explicitBlockedCandidateBars", duplicate == 0 ? blocked : null);
        out.put("unconfirmedCandidateBars", duplicate == 0 ? unconfirmed : null);
        out.put("missingEvaluationsWithinObservedWindow", missing);
        out.put("invalidRows", invalid);
        out.put("duplicateRows", duplicate);
        out.put("firstBarOpenUtc", unique.isEmpty() ? null : unique.firstKey().toString());
        out.put("lastBarOpenUtc", unique.isEmpty() ? null : unique.lastKey().toString());
        out.put("coverage", unique.isEmpty() ? "MISSING_PROOF_NO_FORWARD_EVALUATIONS"
                : invalid + duplicate + missing + unconfirmed > 0 || rows.size() == LIMIT
                ? "INCOMPLETE_OBSERVED_WINDOW" : "CONTIGUOUS_OBSERVED_WINDOW_ONLY");
        out.put("foregonePnl", "MISSING_PROOF_NOT_A_COUNTERFACTUAL_PROFIT_ESTIMATE");
        return out;
    }
}
