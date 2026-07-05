package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeDecisionEvidenceService {

    private final RuntimeDecisionEvidenceRepository repository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final ObjectMapper objectMapper;
    private final AutopilotPolicyService autopilotPolicyService;
    private final ProbePositionExecutorDryRunService probePositionExecutorDryRunService;

    enum EvidenceMode {
        CANONICAL,
        FALLBACK,
        INSUFFICIENT
    }

    enum ReadinessLevel {
        NOT_READY,
        SHADOW_READY_LOW_SAMPLE,
        SHADOW_READY_DEGRADED_EVIDENCE,
        TINY_LIVE_READY_RESTRICTED_RISK,
        AUTONOMOUS_READY_CANONICAL
    }

    @Value("${trading.runtime-evidence.enabled:false}")
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RuntimeDecisionEvidence> writeFromDecisionAudit(BtDecisionAudit audit) {
        if (!enabled || audit == null || audit.getId() == null) {
            return Optional.empty();
        }
        try {
            RuntimeDecisionEvidence evidence = repository.findByDecisionId(audit.getId())
                    .orElseGet(RuntimeDecisionEvidence::new);
            applyAudit(evidence, audit);
            return Optional.of(repository.save(evidence));
        } catch (Throwable t) {
            log.warn("[RuntimeEvidence] write failed: decisionId={} symbol={} err={}",
                    audit.getId(), audit.getSymbol(), t.getMessage());
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<RuntimeDecisionEvidence> listRecent(String symbol, Integer minutes, Integer limit) {
        int mins = minutes == null ? 1440 : Math.max(1, Math.min(minutes, 30 * 24 * 60));
        int lim = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        String sym = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase(Locale.ROOT);
        return repository.findRecent(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(mins), sym, PageRequest.of(0, lim));
    }

    @Transactional(readOnly = true)
    public String autonomousReadinessDashboard(String symbol, Integer minutes) {
        int mins = minutes == null ? 1440 : Math.max(1, Math.min(minutes, 30 * 24 * 60));
        List<RuntimeDecisionEvidence> rows = listRecent(symbol, mins, 200);
        ReadinessSnapshot snapshot = rows.isEmpty()
                ? buildFallbackReadiness(symbol, mins)
                : supplementCanonicalReadiness(symbol, mins, buildCanonicalReadiness(rows));

        long total = rows.size();
        long evSamples = rows.stream().filter(r -> jsonHasMeaningfulStatus(r.getEvResultJson())).count();
        long tqsSamples = rows.stream().filter(r -> jsonHasMeaningfulStatus(r.getTqsResultJson())).count();
        long fearGreedWarnings = rows.stream().filter(r ->
                "WARN_ONLY".equalsIgnoreCase(r.getFearGreedMode())
                        || contains(r.getWarningsJson(), "fearGreedWarning")).count();
        long fearGreedTerminal = rows.stream().filter(r ->
                r.getTerminalBlocker() != null
                        && r.getTerminalBlocker().toLowerCase(Locale.ROOT).contains("feargreed")).count();
        long shadowIntents = rows.stream().filter(r ->
                Boolean.TRUE.equals(r.getIntentCreated())
                        || "SHADOW_MODE".equalsIgnoreCase(r.getSuppressionReason())).count();
        long ocoPlans = rows.stream().filter(r -> Boolean.TRUE.equals(r.getOcoPlanCreated())).count();
        long orderSent = rows.stream().filter(this::isAutonomousOrderSentEvidence).count();
        long nonAutonomousOrders = rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.getOrderSent()))
                .filter(r -> !isAutonomousOrderSentEvidence(r))
                .count();
        long shadowOrders = rows.stream().filter(r ->
                isAutonomousOrderSentEvidence(r)
                        && "SHADOW_MODE".equalsIgnoreCase(r.getSuppressionReason())).count();
        long freshnessBlocks = rows.stream().filter(r ->
                "DataFreshnessGuard".equalsIgnoreCase(r.getTerminalBlocker())
                        || contains(r.getFreshnessState(), "DATA_FRESHNESS")).count();
        long riskBlocks = rows.stream().filter(r -> r.getTerminalBlocker() != null
                && ("DailyLossGuard".equalsIgnoreCase(r.getTerminalBlocker())
                || "EventRiskControl".equalsIgnoreCase(r.getTerminalBlocker())
                || "ExposureOptimizer".equalsIgnoreCase(r.getTerminalBlocker())
                || "EntryDedup".equalsIgnoreCase(r.getTerminalBlocker()))).count();

        String verdict;
        if (shadowOrders > 0) {
            verdict = "FAIL_SHADOW_SENT_ORDER";
        } else if (fearGreedTerminal > 0) {
            verdict = "FAIL_FEAR_GREED_TERMINAL_BLOCK";
        } else if (snapshot.shadowOrderViolations > 0) {
            verdict = "FAIL_SHADOW_SENT_ORDER";
        } else if (snapshot.readinessLevel == ReadinessLevel.AUTONOMOUS_READY_CANONICAL) {
            verdict = "PASS_AUTONOMOUS_READY_CANONICAL";
        } else if (snapshot.readinessLevel == ReadinessLevel.TINY_LIVE_READY_RESTRICTED_RISK) {
            verdict = "PASS_TINY_LIVE_READY_RESTRICTED_RISK";
        } else if (snapshot.readinessLevel == ReadinessLevel.SHADOW_READY_DEGRADED_EVIDENCE
                || snapshot.readinessLevel == ReadinessLevel.SHADOW_READY_LOW_SAMPLE) {
            verdict = "PASS_CONTROLLED_SHADOW_READY";
        } else {
            verdict = "PENDING_RUNTIME_SAMPLE";
        }

        String sym = symbol == null || symbol.isBlank() ? "ALL" : symbol.trim().toUpperCase(Locale.ROOT);
        return """
                === Autonomous Readiness Dashboard ===
                boundary: READ_ONLY diagnostic; no order/OCO/strategy/grid/fund/Earn behavior changed.
                symbol=%s minutes=%d enabled=%s
                evidenceMode=%s
                readinessLevel=%s
                sampleQuality=%s
                sampleCount=%d
                riskScalingMode=%s
                recommendedMaxNotional=%s
                recommendedExecutionMode=%s
                missingSignals=%s
                degradedReasons=%s

                runtimeEvidenceRows=%d
                candidateCount=%d
                evSamples=%d
                tqsSamples=%d
                fearGreedWarnOnlyEvidence=%d
                fearGreedTerminalBlocks=%d
                shadowExecutionIntents=%d
                shadowIntentCount=%d
                ocoPlansCreated=%d
                orderSentEvidence=%d
                nonAutonomousOrderEvidence=%d
                suppressedOrderCount=%d
                shadowOrderViolations=%d
                freshnessTerminalBlocks=%d
                riskGateTerminalBlocks=%d

                OCO health summary: read-only evidence view; ocoPlanCreated=%d, actual OCO modification is not performed here.
                exposure cap summary: risk/exposure terminal blocks=%d; detailed exposure remains in evidence exposure_snapshot_json/risk_gate_result_json.
                freshness summary: freshness terminal blocks=%d; current kline quality should still be validated with getCollectionFreshness/validateKlineQuality.
                unexpected trades: orderSentEvidence=%d, shadowOrderViolations=%d, nonAutonomousOrderEvidence=%d.

                finalReadinessVerdict=%s
                """.formatted(sym, mins, enabled, snapshot.evidenceMode, snapshot.readinessLevel,
                snapshot.sampleQuality, snapshot.sampleCount, snapshot.riskScalingMode,
                snapshot.recommendedMaxNotional, snapshot.recommendedExecutionMode,
                snapshot.missingSignals, snapshot.degradedReasons, total, snapshot.candidateCount,
                snapshot.evSamples, snapshot.tqsSamples,
                snapshot.fearGreedWarnOnlyEvidence, snapshot.fearGreedTerminalBlocks, shadowIntents,
                snapshot.shadowIntentCount, ocoPlans, snapshot.orderSentEvidence, nonAutonomousOrders,
                snapshot.suppressedOrderCount, snapshot.shadowOrderViolations, freshnessBlocks, riskBlocks,
                ocoPlans, riskBlocks, freshnessBlocks, snapshot.orderSentEvidence, snapshot.shadowOrderViolations,
                nonAutonomousOrders, verdict);
    }

    private ReadinessSnapshot buildCanonicalReadiness(List<RuntimeDecisionEvidence> rows) {
        ReadinessSnapshot snapshot = new ReadinessSnapshot(EvidenceMode.CANONICAL);
        snapshot.candidateCount = rows.size();
        snapshot.evSamples = rows.stream().filter(r -> jsonHasMeaningfulStatus(r.getEvResultJson())).count();
        snapshot.tqsSamples = rows.stream().filter(r -> jsonHasMeaningfulStatus(r.getTqsResultJson())).count();
        snapshot.fearGreedWarnOnlyEvidence = rows.stream().filter(r ->
                "WARN_ONLY".equalsIgnoreCase(r.getFearGreedMode())
                        || contains(r.getWarningsJson(), "fearGreedWarning")).count();
        snapshot.fearGreedTerminalBlocks = rows.stream().filter(r ->
                r.getTerminalBlocker() != null
                        && r.getTerminalBlocker().toLowerCase(Locale.ROOT).contains("feargreed")).count();
        snapshot.shadowIntentCount = rows.stream().filter(r ->
                Boolean.TRUE.equals(r.getIntentCreated())
                        || "SHADOW_MODE".equalsIgnoreCase(r.getSuppressionReason())).count();
        snapshot.suppressedOrderCount = rows.stream().filter(r ->
                Boolean.FALSE.equals(r.getOrderSent())
                        && ("SHADOW_MODE".equalsIgnoreCase(r.getSuppressionReason())
                        || contains(r.getSuppressionReason(), "DRY_RUN"))).count();
        snapshot.orderSentEvidence = rows.stream().filter(this::isAutonomousOrderSentEvidence).count();
        snapshot.shadowOrderViolations = rows.stream().filter(r ->
                isAutonomousOrderSentEvidence(r)
                        && "SHADOW_MODE".equalsIgnoreCase(r.getSuppressionReason())).count();

        finalizeProgressiveReadiness(snapshot, true, snapshot.fearGreedTerminalBlocks > 0);
        return snapshot;
    }

    private ReadinessSnapshot supplementCanonicalReadiness(String symbol,
                                                           int minutes,
                                                           ReadinessSnapshot canonical) {
        if (canonical.readinessLevel != ReadinessLevel.NOT_READY
                || canonical.shadowOrderViolations > 0
                || canonical.orderSentEvidence > 0
                || !canonical.missingSignals.contains("progressive-shadow-evidence-chain")) {
            return canonical;
        }

        ReadinessSnapshot fallback = buildFallbackReadiness(symbol, minutes);
        if (readinessRank(fallback.readinessLevel) <= readinessRank(canonical.readinessLevel)
                || fallback.shadowOrderViolations > 0
                || fallback.orderSentEvidence > 0) {
            return canonical;
        }

        canonical.readinessLevel = fallback.readinessLevel;
        canonical.sampleQuality = "CANONICAL_PLUS_FALLBACK";
        canonical.sampleCount = Math.max(canonical.sampleCount, fallback.sampleCount);
        canonical.riskScalingMode = fallback.riskScalingMode;
        canonical.recommendedMaxNotional = fallback.recommendedMaxNotional;
        canonical.recommendedExecutionMode = fallback.recommendedExecutionMode;
        canonical.missingSignals.remove("progressive-shadow-evidence-chain");
        canonical.degradedReasons.add("canonical-runtime-evidence-incomplete-fallback-supplemented");
        fallback.degradedReasons.stream()
                .filter(reason -> !canonical.degradedReasons.contains(reason))
                .forEach(canonical.degradedReasons::add);
        canonical.candidateCount = Math.max(canonical.candidateCount, fallback.candidateCount);
        canonical.evSamples = Math.max(canonical.evSamples, fallback.evSamples);
        canonical.tqsSamples = Math.max(canonical.tqsSamples, fallback.tqsSamples);
        canonical.fearGreedWarnOnlyEvidence = Math.max(
                canonical.fearGreedWarnOnlyEvidence, fallback.fearGreedWarnOnlyEvidence);
        canonical.shadowIntentCount = Math.max(canonical.shadowIntentCount, fallback.shadowIntentCount);
        canonical.suppressedOrderCount = Math.max(canonical.suppressedOrderCount, fallback.suppressedOrderCount);
        return canonical;
    }

    private int readinessRank(ReadinessLevel level) {
        return switch (level) {
            case NOT_READY -> 0;
            case SHADOW_READY_LOW_SAMPLE -> 1;
            case SHADOW_READY_DEGRADED_EVIDENCE -> 2;
            case TINY_LIVE_READY_RESTRICTED_RISK -> 3;
            case AUTONOMOUS_READY_CANONICAL -> 4;
        };
    }

    private void finalizeProgressiveReadiness(ReadinessSnapshot snapshot,
                                              boolean canonical,
                                              boolean currentFearGreedTerminalBlock) {
        snapshot.sampleCount = snapshot.candidateCount;
        if (snapshot.candidateCount == 0) {
            snapshot.sampleQuality = "NO_SAMPLE";
        } else if (snapshot.candidateCount < 3) {
            snapshot.sampleQuality = "LOW_SAMPLE";
        } else if (canonical) {
            snapshot.sampleQuality = "CANONICAL_SAMPLE";
        } else {
            snapshot.sampleQuality = "FALLBACK_SAMPLE";
        }

        boolean shadowEvidenceComplete = snapshot.candidateCount > 0
                && snapshot.fearGreedWarnOnlyEvidence > 0
                && snapshot.evSamples > 0
                && snapshot.tqsSamples > 0
                && snapshot.shadowIntentCount > 0
                && snapshot.suppressedOrderCount > 0;

        if (snapshot.shadowOrderViolations > 0) {
            snapshot.missingSignals.add("no-unintended-order-proof");
            snapshot.readinessLevel = ReadinessLevel.NOT_READY;
        } else if (currentFearGreedTerminalBlock) {
            snapshot.missingSignals.add("fear-greed-warning-only-proof");
            snapshot.readinessLevel = ReadinessLevel.NOT_READY;
        } else if (snapshot.orderSentEvidence > 0) {
            snapshot.missingSignals.add("no-unintended-order-proof");
            snapshot.readinessLevel = ReadinessLevel.NOT_READY;
        } else if (!shadowEvidenceComplete) {
            snapshot.missingSignals.add("progressive-shadow-evidence-chain");
            snapshot.readinessLevel = ReadinessLevel.NOT_READY;
        } else if (canonical && snapshot.candidateCount >= 3 && snapshot.maxSarsScore <= 5) {
            snapshot.readinessLevel = ReadinessLevel.AUTONOMOUS_READY_CANONICAL;
        } else if (snapshot.candidateCount < 3) {
            snapshot.degradedReasons.add("low-sample-count");
            snapshot.readinessLevel = ReadinessLevel.SHADOW_READY_LOW_SAMPLE;
        } else if (snapshot.maxSarsScore > 5) {
            snapshot.degradedReasons.add("sars-high-restricted-risk");
            snapshot.readinessLevel = ReadinessLevel.TINY_LIVE_READY_RESTRICTED_RISK;
        } else if (!canonical) {
            snapshot.readinessLevel = ReadinessLevel.SHADOW_READY_DEGRADED_EVIDENCE;
        } else {
            snapshot.readinessLevel = ReadinessLevel.TINY_LIVE_READY_RESTRICTED_RISK;
        }

        if (snapshot.readinessLevel == ReadinessLevel.NOT_READY) {
            boolean safetyFailed = snapshot.shadowOrderViolations > 0
                    || snapshot.orderSentEvidence > 0
                    || currentFearGreedTerminalBlock;
            snapshot.riskScalingMode = safetyFailed ? "BLOCKED_SAFETY" : "BLOCKED_INSUFFICIENT_EVIDENCE";
            snapshot.recommendedMaxNotional = "0";
            snapshot.recommendedExecutionMode = "READ_ONLY_DIAGNOSTIC";
        } else if (snapshot.readinessLevel == ReadinessLevel.SHADOW_READY_LOW_SAMPLE
                || snapshot.readinessLevel == ReadinessLevel.SHADOW_READY_DEGRADED_EVIDENCE) {
            snapshot.riskScalingMode = "SHADOW_ONLY";
            snapshot.recommendedMaxNotional = "0";
            snapshot.recommendedExecutionMode = "SHADOW_EXPLORATION";
        } else if (snapshot.readinessLevel == ReadinessLevel.TINY_LIVE_READY_RESTRICTED_RISK) {
            snapshot.riskScalingMode = snapshot.maxSarsScore > 5 ? "SARS_REDUCED_TINY_RISK" : "RESTRICTED_TINY_RISK";
            snapshot.recommendedMaxNotional = snapshot.maxSarsScore > 5 ? "5" : "10";
            snapshot.recommendedExecutionMode = "TINY_LIVE_RESTRICTED_REVIEW_REQUIRED";
        } else {
            snapshot.riskScalingMode = "CANONICAL_NORMAL_REVIEW_REQUIRED";
            snapshot.recommendedMaxNotional = "policy_cap";
            snapshot.recommendedExecutionMode = "FULL_AUTONOMOUS_REVIEW_REQUIRED";
        }
    }

    private ReadinessSnapshot buildFallbackReadiness(String symbol, int minutes) {
        ReadinessSnapshot snapshot = new ReadinessSnapshot(EvidenceMode.FALLBACK);
        String sym = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase(Locale.ROOT);
        List<BtDecisionAudit> audits = decisionAuditRepository.findRecent(
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutes), sym, PageRequest.of(0, 500));

        for (BtDecisionAudit audit : audits) {
            JsonNode context = readContextSafely(audit.getContextJson());
            if (isAutonomousCandidateAudit(audit, context)) {
                snapshot.candidateCount++;
            }
            if (isEvSampleAudit(audit, context)) {
                snapshot.evSamples++;
            }
            if (isTqsSampleAudit(context)) {
                snapshot.tqsSamples++;
            }
            if (isFallbackFearGreedWarnOnly(audit, context)) {
                snapshot.fearGreedWarnOnlyEvidence++;
                snapshot.latestFallbackPositiveEvidenceTime = latest(
                        snapshot.latestFallbackPositiveEvidenceTime, audit.getEventTime());
            }
            if (isFallbackFearGreedTerminal(audit, context)) {
                snapshot.fearGreedTerminalBlocks++;
                snapshot.latestFearGreedTerminalTime = latest(
                        snapshot.latestFearGreedTerminalTime, audit.getEventTime());
            }
            if (isShadowIntentAudit(audit, context)) {
                snapshot.shadowIntentCount++;
                snapshot.latestFallbackPositiveEvidenceTime = latest(
                        snapshot.latestFallbackPositiveEvidenceTime, audit.getEventTime());
            }
            if (isSuppressedOrderAudit(audit, context)) {
                snapshot.suppressedOrderCount++;
            }
            if (isOrderSentAudit(audit, context)) {
                snapshot.orderSentEvidence++;
            }
            if (isShadowOrderViolation(audit, context)) {
                snapshot.shadowOrderViolations++;
            }
            Integer sars = firstInt(context, "sars", "sarsScore", "strategyActivationRiskScore");
            if (sars != null) {
                snapshot.maxSarsScore = Math.max(snapshot.maxSarsScore, sars);
            }
        }

        if (snapshot.candidateCount == 0) snapshot.missingSignals.add("candidate-flow");
        if (snapshot.fearGreedWarnOnlyEvidence == 0) snapshot.missingSignals.add("fear-greed-warn-only");
        if (snapshot.evSamples == 0) snapshot.missingSignals.add("expected-value-sample");
        if (snapshot.tqsSamples == 0) snapshot.missingSignals.add("tqs-continuation-sample");
        if (snapshot.shadowIntentCount == 0) snapshot.missingSignals.add("shadow-execution-intent");
        if (snapshot.suppressedOrderCount == 0) snapshot.missingSignals.add("shadow-order-suppression");
        if (snapshot.orderSentEvidence > 0) snapshot.degradedReasons.add("audit-chain-has-order-sent-evidence");
        if (snapshot.shadowOrderViolations > 0) snapshot.degradedReasons.add("shadow-mode-order-sent-violation");
        if (snapshot.fearGreedTerminalBlocks > 0) snapshot.degradedReasons.add("fear-greed-terminal-block-observed");
        snapshot.degradedReasons.add("canonical-runtime-decision-evidence-rows-missing");
        snapshot.degradedReasons.add("fallback-derived-from-bt-decision-audit");

        boolean currentFearGreedTerminalBlock = snapshot.latestFearGreedTerminalTime != null
                && (snapshot.latestFallbackPositiveEvidenceTime == null
                || snapshot.latestFearGreedTerminalTime.isAfter(snapshot.latestFallbackPositiveEvidenceTime));
        finalizeProgressiveReadiness(snapshot, false, currentFearGreedTerminalBlock);
        if (snapshot.readinessLevel == ReadinessLevel.NOT_READY && snapshot.candidateCount == 0) {
            snapshot.evidenceMode = EvidenceMode.INSUFFICIENT;
        }
        return snapshot;
    }

    private boolean isAutonomousCandidateAudit(BtDecisionAudit audit, JsonNode context) {
        String eventType = defaultText(audit.getEventType(), "");
        return ("SIGNAL_EVAL".equalsIgnoreCase(eventType)
                && ("BUY".equalsIgnoreCase(firstText(context, "side", "direction", "signalSide"))
                || "LONG".equalsIgnoreCase(firstText(context, "side", "direction", "signalSide"))))
                || isEvSampleAudit(audit, context)
                || isShadowIntentAudit(audit, context);
    }

    private boolean isEvSampleAudit(BtDecisionAudit audit, JsonNode context) {
        return "ExpectedValueGate".equalsIgnoreCase(audit.getBlocker())
                || contains(audit.getReason(), "ExpectedValueGatePass")
                || "ExpectedValueGatePass".equalsIgnoreCase(audit.getReason())
                || context.has("expected_r")
                || firstBoolean(context, "candidateContinuedToEv");
    }

    private boolean isTqsSampleAudit(JsonNode context) {
        return context.has("qualityScore")
                || context.has("tqs")
                || context.has("tqsBand")
                || firstBoolean(context, "candidateContinuedToTqs");
    }

    private boolean isFallbackFearGreedWarnOnly(BtDecisionAudit audit, JsonNode context) {
        return isWarningOnlyFearGreed(audit, context)
                || "WARN_ONLY".equalsIgnoreCase(firstText(context,
                "fearGreedFilterMode", "fearGreedRequestedMode", "fearGreedFilterState"));
    }

    private boolean isFallbackFearGreedTerminal(BtDecisionAudit audit, JsonNode context) {
        String blocker = defaultText(audit.getBlocker(), "");
        return blocker.toLowerCase(Locale.ROOT).contains("feargreed")
                && !isFallbackFearGreedWarnOnly(audit, context);
    }

    private boolean isShadowIntentAudit(BtDecisionAudit audit, JsonNode context) {
        return "ShadowExecutionIntent".equalsIgnoreCase(audit.getBlocker())
                || firstBoolean(context, "intentCreated")
                || "SHADOW".equalsIgnoreCase(firstText(context, "executionMode", "execution_mode"));
    }

    private boolean isSuppressedOrderAudit(BtDecisionAudit audit, JsonNode context) {
        Boolean orderSent = firstBooleanOrNull(context, "orderSent", "order_sent");
        String suppression = firstText(context, "suppressionReason", "suppression_reason");
        return "ShadowExecutionIntent".equalsIgnoreCase(audit.getBlocker())
                || (Boolean.FALSE.equals(orderSent)
                && ("SHADOW_MODE".equalsIgnoreCase(suppression)
                || "TRADING_DISABLED".equalsIgnoreCase(suppression)
                || contains(suppression, "DRY_RUN")));
    }

    private boolean isOrderSentAudit(BtDecisionAudit audit, JsonNode context) {
        if (isNonAutonomousGridOrder(context)) {
            return false;
        }
        return "AUTOTRADE_OK".equalsIgnoreCase(defaultText(audit.getEventType(), ""))
                || Boolean.TRUE.equals(firstBooleanOrNull(context, "orderSent", "order_sent"));
    }

    private boolean isShadowOrderViolation(BtDecisionAudit audit, JsonNode context) {
        String suppression = firstText(context, "suppressionReason", "suppression_reason");
        return isOrderSentAudit(audit, context) && "SHADOW_MODE".equalsIgnoreCase(suppression);
    }

    private boolean isAutonomousOrderSentEvidence(RuntimeDecisionEvidence row) {
        return row != null
                && Boolean.TRUE.equals(row.getOrderSent())
                && !isNonAutonomousGridEvidence(row);
    }

    private boolean isNonAutonomousGridEvidence(RuntimeDecisionEvidence row) {
        if (row == null) {
            return false;
        }
        return row.getStrategyId() == null
                && row.getLiveSignalId() == null
                && (contains(row.getFeaturesSnapshotJson(), "\"source\":\"GRID_")
                || contains(row.getFeaturesSnapshotJson(), "\"source\": \"GRID_")
                || contains(row.getFeaturesSnapshotJson(), "\"grid_id\"")
                || contains(row.getSignalSource(), "GRID_"));
    }

    private boolean isNonAutonomousGridOrder(JsonNode context) {
        return context != null
                && !context.isMissingNode()
                && (contains(firstText(context, "source"), "GRID_") || context.has("grid_id"));
    }

    private LocalDateTime latest(LocalDateTime current, LocalDateTime candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.isAfter(current)) {
            return candidate;
        }
        return current;
    }

    private static class ReadinessSnapshot {
        private EvidenceMode evidenceMode;
        private ReadinessLevel readinessLevel = ReadinessLevel.NOT_READY;
        private final List<String> missingSignals = new ArrayList<>();
        private final List<String> degradedReasons = new ArrayList<>();
        private String sampleQuality = "NO_SAMPLE";
        private long sampleCount;
        private String riskScalingMode = "BLOCKED_INSUFFICIENT_EVIDENCE";
        private String recommendedMaxNotional = "0";
        private String recommendedExecutionMode = "READ_ONLY_DIAGNOSTIC";
        private long candidateCount;
        private long evSamples;
        private long tqsSamples;
        private long fearGreedWarnOnlyEvidence;
        private long fearGreedTerminalBlocks;
        private long shadowIntentCount;
        private long suppressedOrderCount;
        private long orderSentEvidence;
        private long shadowOrderViolations;
        private int maxSarsScore = 0;
        private LocalDateTime latestFallbackPositiveEvidenceTime;
        private LocalDateTime latestFearGreedTerminalTime;

        private ReadinessSnapshot(EvidenceMode evidenceMode) {
            this.evidenceMode = evidenceMode;
        }
    }

    private void applyAudit(RuntimeDecisionEvidence evidence, BtDecisionAudit audit) throws Exception {
        JsonNode context = readContext(audit.getContextJson());
        evidence.setDecisionId(audit.getId());
        evidence.setEvidenceTime(audit.getEventTime() != null ? audit.getEventTime() : LocalDateTime.now());
        evidence.setSymbol(normalizeSymbol(audit.getSymbol()));
        evidence.setSide(firstText(context, "side", "direction", "signalSide"));
        evidence.setStrategyId(audit.getStrategyId());
        evidence.setIntervalCode(audit.getIntervalCode());
        evidence.setLiveSignalId(audit.getLiveSignalId());
        evidence.setSignalSource(defaultText(firstText(context, "signalSource", "source"), audit.getEventType()));
        evidence.setFeaturesSnapshotJson(jsonOrNull(context));
        evidence.setFreshnessState(resolveFreshnessState(audit, context));
        evidence.setBlockerReason(resolveBlockerReason(audit));
        String terminalBlocker = resolveTerminalBlocker(audit, context);
        String tqsJson = resolveTqsJson(audit, context, terminalBlocker);
        evidence.setTqsJson(tqsJson);
        evidence.setSelectedAction(resolveSelectedAction(audit));
        evidence.setReason(truncate(audit.getReason(), 500));
        evidence.setExposureSnapshotJson(resolveExposureSnapshotJson(context));
        evidence.setOcoOrderListId(firstText(context, "ocoOrderListId", "ocoAlgoId", "oco_algo_id"));
        evidence.setFinalOutcome(defaultText(firstText(context, "finalOutcome"), "PENDING"));
        evidence.setScore(firstDouble(context, "score", "nnOutput", "qualityScore"));
        evidence.setThreshold(firstDouble(context, "threshold", "min_expected_r", "buyThreshold", "activationThreshold"));
        evidence.setDecision(defaultText(firstText(context, "decision", "ev_reason", "selectedAction"), evidence.getSelectedAction()));
        evidence.setWarningsJson(resolveWarningsJson(context));
        evidence.setTerminalBlocker(terminalBlocker);
        evidence.setFearGreedMode(firstText(context, "fearGreedFilterMode", "fearGreedRequestedMode", "fearGreedFilterState"));
        String evJson = resolveEvResultJson(context);
        String riskJson = resolveRiskGateResultJson(audit, context);
        evidence.setEvResultJson(evJson);
        evidence.setTqsResultJson(tqsJson);
        evidence.setRiskGateResultJson(riskJson);
        evidence.setExecutionMode(resolveExecutionMode(context));
        evidence.setOrderSent(resolveOrderSent(audit, context));
        evidence.setSuppressionReason(resolveSuppressionReason(audit, context));
        evidence.setIntentCreated(firstBoolean(context, "intentCreated"));
        evidence.setOcoPlanCreated(firstBoolean(context, "ocoPlanCreated"));
        AutopilotPolicyService.Decision policy = autopilotPolicyService.decide(
                audit, context, terminalBlocker, evidence.getFreshnessState(), tqsJson, evJson, riskJson);
        String explicitPolicyMode = firstText(context, "runtimeEvidencePolicyMode");
        String explicitPolicyReason = firstText(context, "runtimeEvidencePolicyReason");
        AutopilotPolicyService.Decision effectivePolicy = new AutopilotPolicyService.Decision(
                defaultText(explicitPolicyMode, policy.policyMode()),
                defaultText(explicitPolicyReason, policy.policyReason()),
                policy.policyInputsJson());
        evidence.setPolicyMode(effectivePolicy.policyMode());
        evidence.setPolicyReason(truncate(effectivePolicy.policyReason(), 500));
        evidence.setPolicyInputsJson(effectivePolicy.policyInputsJson());
        evidence.setExecutionPreviewJson(resolveExecutionPreviewJson(evidence, context, effectivePolicy, terminalBlocker, tqsJson, evJson));
    }

    private JsonNode readContext(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(json);
    }

    private String resolveFreshnessState(BtDecisionAudit audit, JsonNode context) {
        String explicit = firstText(context, "freshnessState", "dataFreshnessState", "freshness_state");
        if (explicit != null) {
            return explicit;
        }
        if ("DataFreshnessGuard".equalsIgnoreCase(audit.getBlocker())) {
            return "BLOCKED_BY_DATA_FRESHNESS_GUARD";
        }
        return "NOT_EVALUATED";
    }

    private String resolveBlockerReason(BtDecisionAudit audit) {
        if (audit.getBlocker() == null && audit.getReason() == null) {
            return null;
        }
        if (audit.getBlocker() == null) {
            return truncate(audit.getReason(), 255);
        }
        if (audit.getReason() == null || audit.getReason().isBlank()) {
            return truncate(audit.getBlocker(), 255);
        }
        return truncate(audit.getBlocker() + ": " + audit.getReason(), 255);
    }

    private String resolveTqsJson(BtDecisionAudit audit, JsonNode context, String terminalBlocker) {
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = objectMapper.convertValue(context, java.util.Map.class);
            if (audit != null) {
                map.putIfAbsent("eventType", audit.getEventType());
                map.putIfAbsent("outcome", audit.getOutcome());
                map.putIfAbsent("blocker", audit.getBlocker());
                map.putIfAbsent("reason", audit.getReason());
            }
            String terminal = terminalBlocker != null ? terminalBlocker : audit == null ? null : audit.getBlocker();
            return objectMapper.writeValueAsString(TradeQualityEngine.scoreJsonV0(map, terminal));
        } catch (Exception e) {
            ObjectNode tqs = objectMapper.createObjectNode();
            tqs.put("version", "v0");
            tqs.put("status", "ERROR");
            tqs.put("error", truncate(e.getMessage(), 160));
            return tqs.toString();
        }
    }

    private String resolveWarningsJson(JsonNode context) {
        ObjectNode warnings = objectMapper.createObjectNode();
        copyIfPresent(context, warnings, "fearGreedWarning");
        copyIfPresent(context, warnings, "fearGreedFilterState");
        copyIfPresent(context, warnings, "fearGreedCondition");
        copyIfPresent(context, warnings, "warnings");
        copyIfPresent(context, warnings, "candidateContinuedToEv");
        copyIfPresent(context, warnings, "candidateContinuedToTqs");
        if (warnings.isEmpty()) {
            warnings.put("status", "NONE");
        }
        return warnings.toString();
    }

    private String resolveEvResultJson(JsonNode context) {
        ObjectNode ev = objectMapper.createObjectNode();
        copyIfPresent(context, ev, "expected_r");
        copyIfPresent(context, ev, "min_expected_r");
        copyIfPresent(context, ev, "ev_reason");
        copyIfPresent(context, ev, "gate_enabled");
        copyIfPresent(context, ev, "candidateContinuedToEv");
        if (ev.isEmpty()) {
            ev.put("status", "NOT_EVALUATED");
        }
        return ev.toString();
    }

    private String resolveRiskGateResultJson(BtDecisionAudit audit, JsonNode context) {
        ObjectNode risk = objectMapper.createObjectNode();
        copyIfPresent(context, risk, "riskGateResult");
        copyIfPresent(context, risk, "exposureBefore");
        copyIfPresent(context, risk, "exposureAfter");
        copyIfPresent(context, risk, "actualExposure");
        copyIfPresent(context, risk, "actualExposurePct");
        copyIfPresent(context, risk, "eventRiskLevel");
        copyIfPresent(context, risk, "dailyLossGuard");
        if (audit.getBlocker() != null && isRiskBlocker(audit.getBlocker())) {
            risk.put("blocker", audit.getBlocker());
            risk.put("reason", defaultText(audit.getReason(), ""));
        }
        if (risk.isEmpty()) {
            risk.put("status", "NOT_EVALUATED");
        }
        return risk.toString();
    }

    private String resolveTerminalBlocker(BtDecisionAudit audit, JsonNode context) {
        String explicit = firstText(context, "terminalBlocker", "terminal_blocker");
        if (explicit != null) {
            return "NONE".equalsIgnoreCase(explicit) ? null : explicit;
        }
        String blocker = audit.getBlocker();
        if (blocker == null || blocker.isBlank()) {
            return null;
        }
        if (isWarningOnlyFearGreed(audit, context)) {
            return null;
        }
        String eventType = audit.getEventType() == null ? "" : audit.getEventType().toUpperCase(Locale.ROOT);
        if ("FILTER_BLOCK".equals(eventType) || "ENTRY_SKIP".equals(eventType)) {
            return truncate(blocker, 128);
        }
        return null;
    }

    private String resolveExecutionMode(JsonNode context) {
        String explicit = firstText(context, "executionMode", "execution_mode");
        if (explicit != null) {
            return explicit;
        }
        if (firstBoolean(context, "notify_only") || firstBoolean(context, "dry_run")) {
            return "SHADOW";
        }
        return "NOT_EVALUATED";
    }

    private Boolean resolveOrderSent(BtDecisionAudit audit, JsonNode context) {
        Boolean explicit = firstBooleanOrNull(context, "orderSent", "order_sent");
        if (explicit != null) {
            return explicit;
        }
        return "AUTOTRADE_OK".equalsIgnoreCase(defaultText(audit.getEventType(), ""));
    }

    private String resolveSuppressionReason(BtDecisionAudit audit, JsonNode context) {
        String explicit = firstText(context, "suppressionReason", "suppression_reason");
        if (explicit != null) {
            return explicit;
        }
        if (firstBoolean(context, "notify_only")) {
            return "SHADOW_MODE";
        }
        if (firstBoolean(context, "dry_run")) {
            return "DRY_RUN_ONLY";
        }
        String terminal = resolveTerminalBlocker(audit, context);
        return terminal == null ? null : terminal;
    }

    private String resolveExposureSnapshotJson(JsonNode context) {
        ObjectNode exposure = objectMapper.createObjectNode();
        copyIfPresent(context, exposure, "capital");
        copyIfPresent(context, exposure, "capitalUsdt");
        copyIfPresent(context, exposure, "actualExposure");
        copyIfPresent(context, exposure, "actualExposureUsdt");
        copyIfPresent(context, exposure, "actualExposurePct");
        copyIfPresent(context, exposure, "actualExposureCapPct");
        copyIfPresent(context, exposure, "openMaxLoss");
        copyIfPresent(context, exposure, "openMaxLossUsdt");
        copyIfPresent(context, exposure, "openMaxLossCapUsdt");
        copyIfPresent(context, exposure, "candidateMaxLossUsdt");
        copyIfPresent(context, exposure, "maxLossIfWrongUsdt");
        copyIfPresent(context, exposure, "projectedOpenMaxLossUsdt");
        copyIfPresent(context, exposure, "maxLossCapRemainingUsdt");
        copyIfPresent(context, exposure, "maxLossSnapshot");
        copyIfPresent(context, exposure, "freeUsdt");
        copyIfPresent(context, exposure, "exposureBefore");
        copyIfPresent(context, exposure, "exposureAfter");
        copyIfPresent(context, exposure, "dailyCapScope");
        copyIfPresent(context, exposure, "dailyCapUsed");
        copyIfPresent(context, exposure, "dailyCapLimit");
        copyIfPresent(context, exposure, "dailyCapRemaining");
        copyIfPresent(context, exposure, "dailyCapCountSinceUtc");
        copyIfPresent(context, exposure, "shadowDailyCapUsed");
        copyIfPresent(context, exposure, "shadowDailyCapLimit");
        copyIfPresent(context, exposure, "shadowDailyCapRemaining");
        copyIfPresent(context, exposure, "dailyCapSnapshot");
        if (exposure.isEmpty()) {
            exposure.put("status", "NOT_CAPTURED");
        }
        return exposure.toString();
    }

    private String resolveExecutionPreviewJson(RuntimeDecisionEvidence evidence,
                                               JsonNode context,
                                               AutopilotPolicyService.Decision policy,
                                               String terminalBlocker,
                                               String tqsJson,
                                               String evJson) {
        boolean duplicateBar = contains(terminalBlocker, "DuplicateBar")
                || contains(evidence.getBlockerReason(), "DuplicateBar");
        boolean dataFreshnessHardFail = contains(terminalBlocker, "DataFreshnessGuard")
                || contains(evidence.getFreshnessState(), "DATA_FRESHNESS");
        ProbePositionExecutorDryRunService.PreviewInput input =
                new ProbePositionExecutorDryRunService.PreviewInput(
                        evidence.getSymbol(),
                        evidence.getStrategyId(),
                        evidence.getSide(),
                        policy.policyMode(),
                        intFromJson(tqsJson, "qualityScore"),
                        textFromJson(tqsJson, "tqsBand", "band"),
                        doubleFromJson(evJson, "expected_r"),
                        contains(evidence.getRiskGateResultJson(), "EXPOSURE_ABOVE_CAP"),
                        firstBoolean(context, "ocoCapable") || firstBoolean(context, "ocoPlanCreated"),
                        !contains(evidence.getRiskGateResultJson(), "OCO_SYNC_ERROR")
                                && !contains(evidence.getRiskGateResultJson(), "OCO_MISSING"),
                        contains(evidence.getRiskGateResultJson(), "DailyLossGuard")
                                || contains(evidence.getBlockerReason(), "DailyLossGuard"),
                        duplicateBar,
                        dataFreshnessHardFail,
                        firstDecimal(context, "entryPrice", "entry", "signalPrice", "currentPrice"),
                        firstDecimal(context, "tpPrice", "takeProfitPrice", "takeProfit", "tp"),
                        firstDecimal(context, "slPrice", "stopLossPrice", "stopLoss", "sl"),
                        firstDecimal(context, "availableUsdt", "freeUsdt"));
        return probePositionExecutorDryRunService.previewJson(input);
    }

    private String resolveSelectedAction(BtDecisionAudit audit) {
        String explicit = firstText(readContextSafely(audit.getContextJson()), "selectedAction", "selected_action");
        if (explicit != null) {
            return explicit;
        }
        String eventType = audit.getEventType() == null ? "" : audit.getEventType().toUpperCase(Locale.ROOT);
        String outcome = audit.getOutcome() == null ? "" : audit.getOutcome().toUpperCase(Locale.ROOT);
        if ("FILTER_BLOCK".equals(eventType) || "ENTRY_SKIP".equals(eventType) || "BLOCKED".equals(outcome)) {
            return "BLOCK";
        }
        if ("AUTOTRADE_OK".equals(eventType)) {
            return "EXECUTED_EXISTING_AUTOTRADE";
        }
        if ("AUTOTRADE_FAIL".equals(eventType) || "ERROR".equals(outcome)) {
            return "ERROR";
        }
        if ("EXIT".equals(eventType)) {
            return "EXIT_RECORDED";
        }
        if ("EXIT_ADJUST".equals(eventType)) {
            return "RISK_REVIEW_RECORDED";
        }
        if ("SIGNAL_EVAL".equals(eventType)) {
            String side = firstText(readContextSafely(audit.getContextJson()), "side", "direction", "signalSide");
            if ("BUY".equalsIgnoreCase(side) || "LONG".equalsIgnoreCase(side)) {
                return "SIGNAL_EVAL_BUY";
            }
            if ("SELL".equalsIgnoreCase(side) || "SHORT".equalsIgnoreCase(side)) {
                return "SIGNAL_EVAL_SELL";
            }
            if ("HOLD".equalsIgnoreCase(side) || "WAIT".equalsIgnoreCase(side)) {
                return "WAIT";
            }
            return "EVALUATED_ONLY";
        }
        return "AUDIT_ONLY";
    }

    private JsonNode readContextSafely(String json) {
        try {
            return readContext(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String key) {
        if (source.has(key)) {
            target.set(key, source.get(key));
        }
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull() && !value.asText("").isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private Double firstDouble(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isNumber()) {
                return value.asDouble();
            }
            if (value != null && value.isTextual()) {
                try {
                    return Double.parseDouble(value.asText());
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private BigDecimal firstDecimal(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isNumber()) {
                return value.decimalValue();
            }
            if (value != null && value.isTextual()) {
                try {
                    return new BigDecimal(value.asText());
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private Integer firstInt(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isNumber()) {
                return value.asInt();
            }
            if (value != null && value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText());
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private boolean firstBoolean(JsonNode node, String key) {
        Boolean value = firstBooleanOrNull(node, key);
        return value != null && value;
    }

    private Boolean firstBooleanOrNull(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isBoolean()) {
                return value.asBoolean();
            }
            if (value != null && value.isTextual()) {
                String text = value.asText();
                if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
                if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
            }
        }
        return null;
    }

    private boolean isWarningOnlyFearGreed(BtDecisionAudit audit, JsonNode context) {
        String reason = defaultText(audit.getReason(), "");
        String blocker = defaultText(audit.getBlocker(), "");
        String mode = defaultText(firstText(context, "fearGreedFilterState", "fearGreedFilterMode"), "");
        return ("FearGreedWarnOnlyDryRun".equalsIgnoreCase(blocker)
                || reason.contains("FearGreedFilterWarnOnly")
                || reason.contains("FearGreed WARN_ONLY")
                || "WARN_ONLY".equalsIgnoreCase(mode))
                && !firstBoolean(context, "fearGreedHardBlock");
    }

    private boolean isRiskBlocker(String blocker) {
        return "DailyLossGuard".equalsIgnoreCase(blocker)
                || "EventRiskControl".equalsIgnoreCase(blocker)
                || "ExposureOptimizer".equalsIgnoreCase(blocker)
                || "EntryDedup".equalsIgnoreCase(blocker);
    }

    private String jsonOrNull(JsonNode node) {
        return node == null || node.isEmpty() ? null : node.toString();
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean contains(String value, String needle) {
        return value != null && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private Integer intFromJson(String json, String key) {
        JsonNode node = readJsonSafely(json);
        Integer direct = firstInt(node, key);
        if (direct != null) return direct;
        JsonNode nested = node.get("tqs");
        return nested == null ? null : firstInt(nested, key);
    }

    private Double doubleFromJson(String json, String key) {
        JsonNode node = readJsonSafely(json);
        return firstDouble(node, key);
    }

    private String textFromJson(String json, String... keys) {
        JsonNode node = readJsonSafely(json);
        String direct = firstText(node, keys);
        if (direct != null) return direct;
        JsonNode nested = node.get("tqs");
        return nested == null ? null : firstText(nested, keys);
    }

    private JsonNode readJsonSafely(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private boolean jsonHasMeaningfulStatus(String json) {
        if (json == null || json.isBlank()) return false;
        String lower = json.toLowerCase(Locale.ROOT);
        return !lower.contains("not_evaluated") || lower.contains("expected_r") || lower.contains("qualityscore");
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
    }
}
