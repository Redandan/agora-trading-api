package com.agora.service.strategy;

import com.agora.config.properties.BtcDraRuntimeProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.MdKline;
import com.agora.model.SpotExecutionAttempt.FeeReconciliationStatus;
import com.agora.repository.trading.*;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.agora.service.trading.BtcDraPolicy;
import com.agora.service.trading.BtcDraExecutionContract;
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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** On-demand observations only: no evaluation, provider call, state restore, or write. */
@Service
@RequiredArgsConstructor
public class StrategyRuntimeObservationService {
    private final StrategyRuntimeCatalog catalog;
    private final MdKlineRepository klines;
    private final RuntimeDecisionEvidenceRepository evidence;
    private final BtDecisionAuditRepository audits;
    private final BtLiveSignalRepository positions;
    private final SpotExecutionAttemptRepository attempts;
    private final TradingViewLocalSignalProperties tvProperties;
    private final BtcDraRuntimeProperties draProperties;
    private final ObjectMapper mapper;

    public String report() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            List<Map<String, Object>> lanes = new java.util.ArrayList<>();
            for (var d : catalog.definitions().stream()
                    .filter(d -> d.mode().evaluationAllowed())
                    .sorted(java.util.Comparator.comparing(StrategyRuntimeDefinition::key))
                    .toList()) {
                Map<String, Object> lane = snapshot(d, now);
                lanes.add(lane);
                // One failed DB read is enough; avoid multiplying pool timeouts per strategy.
                if ("UNAVAILABLE".equals(lane.get("observationStatus"))) break;
            }
            return "STRATEGY_RUNTIME_OBSERVATIONS\n"
                    + mapper.writeValueAsString(Map.of("asOfUtc", now.toString(),
                    "scope", "PERSISTED_EVIDENCE_ONLY_NOT_EXECUTION_AUTHORITY",
                    "unreportedStrategies", "NOT_QUERIED_IF_EARLIER_READ_FAILED", "strategies", lanes));
        } catch (Exception e) {
            return "STRATEGY_RUNTIME_OBSERVATIONS\nstatus=UNAVAILABLE errorType="
                    + e.getClass().getSimpleName();
        }
    }

    Map<String, Object> snapshot(StrategyRuntimeDefinition d, LocalDateTime now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", d.versionedKey());
        out.put("source", d.source());
        out.put("interval", d.interval());
        try {
            MdKline latest = klines
                    .findFirstBySymbolAndIntervalCodeAndSourceAndCloseTimeLessThanEqualOrderByOpenTimeDesc(
                            d.symbol(), d.interval(), d.source(), now).orElse(null);
            out.put("latestClosedBarUtc", latest == null ? null : text(latest.getCloseTime()));
            out.put("marketDataStatus", freshness(latest == null ? null : latest.getCloseTime(), d.interval(), now));
            LocalDateTime decisionBar;
            if (TradingViewScoreBuyAutoExitStrategyContract.KEY.equals(d.key())) {
                decisionBar = tvDecision(d, out);
            } else {
                decisionBar = evidenceDecision(d, out);
            }
            out.put("decisionBarOpenUtc", text(decisionBar));
            out.put("decisionMatchesLatestClosedBar", latest != null && decisionBar != null
                    && decisionBar.equals(latest.getOpenTime()));
            if (d.mode() == StrategyLifecycleMode.LIVE) appendInventory(d, now, out);
            out.put("observationStatus", "AVAILABLE");
        } catch (Exception e) {
            // Partial data cannot be mistaken for a healthy/empty lane during a DB failure.
            out.put("observationStatus", "UNAVAILABLE");
            out.put("errorType", e.getClass().getSimpleName());
        }
        return out;
    }

    private LocalDateTime tvDecision(StrategyRuntimeDefinition d, Map<String, Object> out) throws Exception {
        var row = audits.findFirstByStrategyIdAndSymbolAndEventTypeOrderByEventTimeDescIdDesc(
                d.databaseStrategyId(), d.symbol(), "SIGNAL_EVAL").orElse(null);
        if (row == null) {
            out.put("decisionEvidenceStatus", "MISSING_PROOF");
            return null;
        }
        JsonNode context = mapper.readTree(row.getContextJson());
        if (context == null || !"LOCAL_TRADINGVIEW_PARITY".equals(context.path("source").asText())) {
            out.put("decisionEvidenceStatus", "MISSING_PROOF_SOURCE_MISMATCH");
            return null;
        }
        out.put("decisionEvidenceStatus", "OBSERVED");
        out.put("decisionEvidenceId", row.getId());
        out.put("decisionObservedAtUtc", text(row.getEventTime()));
        out.put("lastDecision", context.path("decision").asText("MISSING_PROOF"));
        out.put("lastDecisionReason", context.path("blockers").asText(row.getReason()));
        return row.getBarOpenTime();
    }

    private LocalDateTime evidenceDecision(StrategyRuntimeDefinition d, Map<String, Object> out) throws Exception {
        var rows = evidence.findByPolicyModeAndSymbolAndIntervalCodeOrderByIdDesc(
                d.key(), d.symbol(), d.interval(), PageRequest.of(0, 1));
        if (rows.isEmpty()) {
            out.put("decisionEvidenceStatus", "MISSING_PROOF");
            return null;
        }
        var row = rows.getFirst();
        JsonNode features = mapper.readTree(row.getFeaturesSnapshotJson());
        if (features == null || !features.isObject()) throw new IllegalArgumentException("invalid evidence");
        if (features.has("source") && !d.source().equals(features.path("source").asText())) {
            out.put("decisionEvidenceStatus", "MISSING_PROOF_SOURCE_MISMATCH");
            return null;
        }
        out.put("decisionEvidenceStatus", "OBSERVED");
        out.put("decisionEvidenceId", row.getId());
        out.put("decisionObservedAtUtc", text(row.getEvidenceTime()));
        out.put("lastDecision", row.getSelectedAction());
        out.put("lastDecisionReason", row.getTerminalBlocker() != null ? row.getTerminalBlocker()
                : features.path("signal").path("reason").asText(row.getReason()));
        out.put("lastDecisionOrderSent", row.getOrderSent());
        out.put("lastDecisionFeeStatus", features.path("liveExecution").path("feeStatus").asText("N/A"));
        Map<String, Object> signal = new LinkedHashMap<>();
        for (String key : List.of("ready", "dailyDecision", "entryEligible", "closeAboveEma",
                "emaRisingFiveDays", "momentumPositive", "dailyReversalConfirmed")) {
            if (features.path("signal").path(key).isBoolean()) {
                signal.put(key, features.path("signal").path(key).booleanValue());
            }
        }
        out.put("lastSignalConditions", signal);
        if (BtcDraPolicy.POLICY_MODE.equals(d.key())) {
            out.put("executionContract", BtcDraExecutionContract.description());
            JsonNode entry = features.path("entryDecision");
            if ("DRA_ENTRY_DECISION_V1".equals(entry.path("schema").asText())
                    && BtcDraExecutionContract.PROFILE.equals(entry.path("profile").asText())
                    && features.path("barOpenTime").asText().equals(entry.path("barOpenUtc").asText())
                    && entry.path("queuedCandidate").isBoolean() && entry.path("stage").isTextual()) {
                out.put("entryDecision", entry);
            } else {
                out.put("entryDecisionStatus", "MISSING_PROOF_LEGACY_OR_INVALID_ENTRY_DECISION");
            }
        }
        // The top-level persisted bar identity is UTC. Nested legacy JSON offsets are not clocks.
        String bar = features.path("barOpenTime").asText(null);
        LocalDateTime decisionBar = bar == null ? null : LocalDateTime.parse(bar);
        if (BtcDraPolicy.POLICY_MODE.equals(d.key())) {
            var evaluation = audits.findFirstByStrategyIdAndSymbolAndEventTypeOrderByEventTimeDescIdDesc(
                    BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID, d.symbol(), "SPOT_ENTRY_EVAL_V1").orElse(null);
            if (evaluation != null && decisionBar != null && decisionBar.equals(evaluation.getBarOpenTime())) {
                JsonNode context = mapper.readTree(evaluation.getContextJson());
                if (context != null && "SPOT_ENTRY_EVAL_V1".equals(context.path("schema").asText())
                        && "DRA_V1".equals(context.path("owner").asText())
                        && context.path("candidate").isBoolean() && context.path("disposition").isTextual()) {
                    out.put("lastLiveEntryEvaluation", context);
                }
            }
            if (!out.containsKey("lastLiveEntryEvaluation")) {
                out.put("lastLiveEntryEvaluationStatus", "MISSING_PROOF_NO_MATCHING_BAR_EXECUTION_EVIDENCE");
            }
        }
        return decisionBar;
    }

    private void appendInventory(StrategyRuntimeDefinition d, LocalDateTime now, Map<String, Object> out) {
        boolean dra = BtcDraPolicy.POLICY_MODE.equals(d.key());
        List<BtLiveSignal> lots = positions.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                .filter(p -> d.symbol().equals(p.getSymbol()))
                .filter(p -> !"SHORT".equals(p.getSide()))
                .filter(p -> dra ? BtcBasePositionStatePolicy.isDraV1Position(p)
                        : BtcBasePositionStatePolicy.isTv509Position(p)).toList();
        out.put("openOwnedLots", lots.size());
        boolean complete = lots.stream().allMatch(p -> positive(p.getTradedQty()) && positive(p.getEntryPrice()));
        BigDecimal cap = dra ? draProperties.maxLiveExposureUsdt()
                : tvProperties.btcBaseMaxExposureUsdt();
        out.put("configuredExposureCapUsdt", cap);
        if (complete) {
            BigDecimal cost = lots.stream().map(p -> p.getEntryPrice().multiply(p.getTradedQty()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            out.put("recordedOpenCostUsdt", cost);
            out.put("currentCapitalUtilization", positive(cap) ? cost.divide(cap, 8, RoundingMode.HALF_UP) : null);
        }
        out.put("openCostEvidenceStatus", complete ? "RECORDED_COST_NOT_FEE_EXACT" : "MISSING_PROOF");
        out.put("oldestOpenRecordAgeHours", lots.stream().map(BtLiveSignal::getCreatedAt)
                .filter(java.util.Objects::nonNull).min(LocalDateTime::compareTo)
                .map(t -> Math.max(0, Duration.between(t, now).toHours())).orElse(null));
        out.put("holdingAgeBasis", "DATABASE_RECORD_CREATION_NOT_PROVIDER_FILL_TIME");
        out.put("blockedEntryCount", "MISSING_PROOF_NO_COMPLETE_SIGNAL_LEDGER");
        out.put("forwardEntryEvidence", "getOpenSpotPositions:FORWARD_SPOT_PERFORMANCE");
        if (dra) {
            out.put("pendingFeeAttempts", attempts.countByStrategyContractAndFeeReconciliationStatus(
                    d.key(), FeeReconciliationStatus.PENDING));
            var oldest = attempts.findFirstByStrategyContractAndFeeReconciliationStatusOrderByCreatedAtAscIdAsc(
                    d.key(), FeeReconciliationStatus.PENDING).orElse(null);
            if (oldest != null) {
                out.put("oldestPendingFeeAttemptId", oldest.getId());
                out.put("oldestPendingFeeAttemptCreatedUtc", text(oldest.getCreatedAt()));
            }
        } else {
            out.put("pendingFeeAttempts", "MISSING_PROOF_NO_PROVIDER_ATTEMPT_LEDGER");
        }
    }

    static String freshness(LocalDateTime close, String interval, LocalDateTime now) {
        if (close == null) return "MISSING_PROOF";
        if (close.isAfter(now)) return "INVALID_FUTURE_BAR";
        ChronoUnit unit = switch (interval) {
            case "1h" -> ChronoUnit.HOURS;
            case "1d" -> ChronoUnit.DAYS;
            default -> null;
        };
        if (unit == null) return "MISSING_PROOF_UNSUPPORTED_INTERVAL";
        // Binance closes at end-minus-1ms; OKX records the exclusive boundary.
        // Both represent the same completed interval, without accepting a future bar.
        return close.plusNanos(1_000_000).isBefore(now.minusMinutes(2).truncatedTo(unit))
                ? "STALE" : "CURRENT";
    }

    private static boolean positive(BigDecimal n) { return n != null && n.signum() > 0; }
    private static String text(LocalDateTime t) { return t == null ? null : t.toString(); }
}
