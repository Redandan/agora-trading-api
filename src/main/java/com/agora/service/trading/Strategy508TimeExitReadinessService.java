package com.agora.service.trading;

import com.agora.config.properties.Strategy508TimeExitProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.agora.service.trading.Strategy508TimeExitPolicy.FORWARD_MIN_DAYS;
import static com.agora.service.trading.Strategy508TimeExitPolicy.FORWARD_MIN_FINALIZED_EVENTS;
import static com.agora.service.trading.Strategy508TimeExitPolicy.MAX_CUMULATIVE_LOSS_USDT;
import static com.agora.service.trading.Strategy508TimeExitPolicy.MAX_PILOT_ORDERS;
import static com.agora.service.trading.Strategy508TimeExitPolicy.PILOT_MAX_DAYS;
import static com.agora.service.trading.Strategy508TimeExitPolicy.POLICY_MODE;
import static com.agora.service.trading.Strategy508TimeExitPolicy.STRATEGY_ID;
import static com.agora.service.trading.Strategy508TimeExitPolicy.SYMBOL;

@Service
@RequiredArgsConstructor
public class Strategy508TimeExitReadinessService {

    private final Strategy508TimeExitProperties properties;
    private final Strategy508TimeExitCandidateService candidateService;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final ObjectMapper objectMapper;

    public String report(String requestedSymbol) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(snapshot(requestedSymbol, true).node());
        } catch (Exception e) {
            return "{\"tool\":\"getStrategy508TimeExitReadiness\",\"status\":\"REPORT_FAILED\"," +
                    "\"liveOrderAllowed\":false}";
        }
    }

    public ReadinessSnapshot snapshot(String requestedSymbol, boolean includeHistorical) {
        String symbol = normalizeSymbol(requestedSymbol);
        boolean supportedSymbol = SYMBOL.equals(symbol);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ObjectNode historical = includeHistorical && supportedSymbol
                ? candidateService.analyzeNode(symbol, 5) : null;
        boolean historicalReady = historical != null && historical.path("historicalGatePassed").asBoolean(false);
        String expectedConfigHash = historical == null
                ? null : historical.path("effectivePolicyConfigSha256").asText(null);
        boolean expectedConfigHashAvailable = expectedConfigHash != null && !expectedConfigHash.isBlank();

        List<RuntimeDecisionEvidence> evidence = evidenceRepository
                .findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(POLICY_MODE, now.minusDays(365));
        List<RuntimeDecisionEvidence> candidateRows = evidence.stream()
                .filter(row -> symbol.equalsIgnoreCase(row.getSymbol()))
                .filter(row -> row.getSelectedAction() != null
                        && row.getSelectedAction().startsWith("STRATEGY_508_TIME_EXIT_SHADOW"))
                .toList();
        List<EvidenceView> candidateViews = candidateRows.stream()
                .map(this::view)
                .sorted(Comparator.comparing(this::evidenceTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        LocalDateTime forwardWindowStart = now.minusDays(FORWARD_MIN_DAYS);
        ConfigCohort configCohort = currentConfigCohort(candidateViews, expectedConfigHash);
        List<EvidenceView> currentConfigCandidateViews = configCohort.currentViews();
        List<EvidenceView> candidateWindowViews = currentConfigCandidateViews.stream()
                .filter(view -> inWindow(view.row(), forwardWindowStart))
                .toList();
        long nonShadowModeRows = candidateWindowViews.stream()
                .filter(view -> !"SHADOW".equals(view.row().getExecutionMode()))
                .count();
        List<EvidenceView> shadowViews = currentConfigCandidateViews.stream()
                .filter(view -> "SHADOW".equals(view.row().getExecutionMode()))
                .toList();
        long malformedContextRows = shadowViews.stream()
                .filter(view -> inWindow(view.row(), forwardWindowStart))
                .filter(view -> !view.valid())
                .count();
        long configMismatchRows = shadowViews.stream()
                .filter(EvidenceView::valid)
                .filter(view -> inWindow(view.row(), forwardWindowStart))
                .filter(view -> !matchesEffectiveConfig(view, expectedConfigHash))
                .count();
        List<EvidenceView> validShadowViews = shadowViews.stream()
                .filter(EvidenceView::valid)
                .filter(view -> matchesEffectiveConfig(view, expectedConfigHash))
                .sorted(Comparator.comparing(view -> view.row().getEvidenceTime(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Map<String, EvidenceView> uniqueRawByEvent = new LinkedHashMap<>();
        for (EvidenceView view : validShadowViews) {
            uniqueRawByEvent.putIfAbsent(view.eventKey(), view);
        }
        Map<String, Long> rawWindowEventCounts = new LinkedHashMap<>();
        validShadowViews.stream()
                .filter(view -> inWindow(view.row(), forwardWindowStart))
                .forEach(view -> rawWindowEventCounts.merge(view.eventKey(), 1L, Long::sum));
        long rawDuplicateEventRows = rawWindowEventCounts.values().stream()
                .filter(count -> count > 1)
                .mapToLong(count -> count - 1)
                .sum();
        List<EvidenceView> rawUniqueEntries = List.copyOf(uniqueRawByEvent.values());
        List<EvidenceView> rawForwardEntries = rawUniqueEntries.stream()
                .filter(view -> inWindow(view.row(), forwardWindowStart))
                .toList();
        List<EvidenceView> rawFinalized = rawForwardEntries.stream()
                .filter(view -> isFinalized(view.row().getFinalOutcome()))
                .toList();
        long rawHardGateBlocked = rawForwardEntries.stream().filter(this::hasHardBlocker).count();
        long rawPending = rawForwardEntries.stream()
                .filter(view -> "PENDING_24H".equals(view.row().getFinalOutcome()))
                .count();
        long rawOutcomeContextMismatchRows = rawForwardEntries.stream()
                .filter(this::hasOutcomeContextMismatch)
                .count();
        long rawIncompleteFeeRows = rawFinalized.stream()
                .filter(view -> !hasCompleteFeeEvidence(view))
                .count();
        BigDecimal rawCounterfactualNetPnl = rawFinalized.stream()
                .filter(this::hasCompleteFeeEvidence)
                .map(this::netPnl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long explicitRawCounterfactualRows = rawForwardEntries.stream()
                .filter(this::isExplicitRawCounterfactualCohort)
                .count();
        long legacyHardBlockedRawOnlyRows = rawForwardEntries.stream()
                .filter(view -> !isExplicitRawCounterfactualCohort(view))
                .filter(this::hasHardBlocker)
                .count();
        long unboundPotentialExecutableRows = rawForwardEntries.stream()
                .filter(view -> !isExplicitRawCounterfactualCohort(view))
                .filter(view -> !hasHardBlocker(view))
                .count();
        long cohortBindingMismatchRows = rawForwardEntries.stream()
                .filter(this::hasCohortBindingMismatch)
                .count();

        List<EvidenceView> executableViews = validShadowViews.stream()
                .filter(this::isExplicitExecutableCohort)
                .toList();
        Map<String, EvidenceView> uniqueExecutableByEvent = new LinkedHashMap<>();
        for (EvidenceView view : executableViews) {
            uniqueExecutableByEvent.putIfAbsent(view.eventKey(), view);
        }
        Map<String, Long> executableWindowEventCounts = new LinkedHashMap<>();
        executableViews.stream()
                .filter(view -> inWindow(view.row(), forwardWindowStart))
                .forEach(view -> executableWindowEventCounts.merge(view.eventKey(), 1L, Long::sum));
        long duplicateEventRows = executableWindowEventCounts.values().stream()
                .filter(count -> count > 1)
                .mapToLong(count -> count - 1)
                .sum();
        List<EvidenceView> uniqueExecutableEntries = List.copyOf(uniqueExecutableByEvent.values());
        LocalDateTime cohortTimingValidationStart = forwardWindowStart
                .minusHours(Strategy508TimeExitPolicy.HOLD_HOURS);
        if (configCohort.resetAt() != null
                && configCohort.resetAt().isAfter(cohortTimingValidationStart)) {
            cohortTimingValidationStart = configCohort.resetAt();
        }
        ShadowCohort shadowCohort = canonicalShadowCohort(
                uniqueExecutableEntries, cohortTimingValidationStart);
        List<EvidenceView> entries = shadowCohort.entries();
        List<EvidenceView> forwardWindowEntries = entries.stream()
                .filter(view -> inWindow(view.row(), forwardWindowStart))
                .toList();
        List<EvidenceView> finalized = forwardWindowEntries.stream()
                .filter(view -> isFinalized(view.row().getFinalOutcome()))
                .toList();
        LocalDateTime firstEvidence = entries.isEmpty() ? null : entries.get(0).row().getEvidenceTime();
        long observationDays = firstEvidence == null ? 0 : Duration.between(firstEvidence, now).toDays();
        LocalDateTime firstRawEvidence = rawUniqueEntries.isEmpty()
                ? null : rawUniqueEntries.get(0).row().getEvidenceTime();
        long rawObservationDays = firstRawEvidence == null
                ? 0 : Duration.between(firstRawEvidence, now).toDays();
        BigDecimal forwardNetPnl = finalized.stream()
                .filter(this::hasCompleteFeeEvidence)
                .map(this::netPnl)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long parityGapCount = forwardWindowEntries.stream().filter(this::hasParityGap).count();
        long outcomeContextMismatchRows = entries.stream()
                .filter(this::hasOutcomeContextMismatch)
                .count();
        long incompleteFeeRows = finalized.stream().filter(view -> !hasCompleteFeeEvidence(view)).count();
        long invalidNetPnlRows = finalized.stream().filter(view -> netPnl(view) == null).count();
        boolean forwardReady = observationDays >= FORWARD_MIN_DAYS
                && finalized.size() >= FORWARD_MIN_FINALIZED_EVENTS
                && forwardNetPnl.signum() > 0
                && parityGapCount == 0
                && outcomeContextMismatchRows == 0
                && incompleteFeeRows == 0
                && invalidNetPnlRows == 0
                && duplicateEventRows == 0
                && malformedContextRows == 0
                && nonShadowModeRows == 0
                && shadowCohort.timingGapRows() == 0
                && expectedConfigHashAvailable
                && configMismatchRows == 0
                && unboundPotentialExecutableRows == 0
                && cohortBindingMismatchRows == 0;

        List<BtLiveSignal> liveSignals = liveSignalRepository
                .findByStrategyIdAndCreatedAtAfter(STRATEGY_ID, now.minusDays(365)).stream()
                .filter(row -> symbol.equalsIgnoreCase(row.getSymbol()))
                .filter(this::isPolicyPosition)
                .filter(row -> Boolean.TRUE.equals(row.getAutoTraded()))
                .sorted(Comparator.comparing(BtLiveSignal::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        long openLive = liveSignals.stream().filter(row -> row.getExitTime() == null).count();
        List<RuntimeDecisionEvidence> liveAttempts = evidence.stream()
                .filter(row -> symbol.equalsIgnoreCase(row.getSymbol()))
                .filter(row -> Boolean.TRUE.equals(row.getOrderSent()) || row.getLiveSignalId() != null)
                .sorted(Comparator.comparing(RuntimeDecisionEvidence::getEvidenceTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        RuntimeDecisionEvidence firstAttempt = liveAttempts.isEmpty() ? null : liveAttempts.get(0);
        EvidenceView firstAttemptView = firstAttempt == null ? null : view(firstAttempt);
        BtLiveSignal firstProbeSignal = firstAttempt == null || firstAttempt.getLiveSignalId() == null
                ? null : liveSignals.stream()
                .filter(row -> firstAttempt.getLiveSignalId().equals(row.getId()))
                .findFirst().orElse(null);
        BigDecimal firstProbeNetPnl = firstAttemptView == null ? null : netPnl(firstAttemptView);
        boolean firstProbeNetPositive = firstProbeNetPnl != null && firstProbeNetPnl.signum() > 0;
        boolean firstProbeVerified = Strategy508TimeExitPolicy.EXACT_LIVE_FILL_EVIDENCE_IMPLEMENTED
                && firstAttempt != null
                && firstProbeSignal != null
                && firstProbeSignal.getExitTime() != null
                && isFinalized(firstAttempt.getFinalOutcome())
                && firstAttemptView.valid()
                && !hasHardBlocker(firstAttemptView)
                && !hasParityGap(firstAttemptView)
                && !hasPartialFillIncident(firstAttemptView)
                && hasCompleteFeeEvidence(firstAttemptView)
                && firstProbeNetPositive;
        LocalDateTime firstAttemptTime = firstAttempt == null ? null : firstAttempt.getEvidenceTime();
        boolean pilotExpired = firstAttemptTime != null
                && Duration.between(firstAttemptTime, now).toDays() >= PILOT_MAX_DAYS;
        List<EvidenceView> liveFinalizedViews = liveAttempts.stream()
                .map(this::view)
                .filter(view -> isFinalized(view.row().getFinalOutcome()))
                .toList();
        long liveFeeGapCount = liveFinalizedViews.stream()
                .filter(view -> !view.valid() || !hasCompleteFeeEvidence(view)).count();
        long liveParityGapCount = liveFinalizedViews.stream().filter(this::hasParityGap).count();
        long livePartialFillIncidents = liveFinalizedViews.stream()
                .filter(this::hasPartialFillIncident).count();
        BigDecimal liveNetPnl = liveFinalizedViews.stream()
                .filter(this::hasCompleteFeeEvidence)
                .map(this::netPnl)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean pilotHealthyAndNetPositive = liveAttempts.isEmpty()
                || (liveFinalizedViews.size() == liveAttempts.size()
                && liveFeeGapCount == 0
                && liveParityGapCount == 0
                && livePartialFillIncidents == 0
                && liveNetPnl.signum() > 0);
        boolean lossFuse = liveNetPnl.compareTo(MAX_CUMULATIVE_LOSS_USDT.negate()) <= 0;
        boolean pilotCap = liveAttempts.size() >= MAX_PILOT_ORDERS;

        List<String> blockers = new ArrayList<>();
        if (!supportedSymbol) blockers.add("UNSUPPORTED_SYMBOL");
        if (!properties.enabled()) blockers.add("MODE_OFF");
        if (!historicalReady) blockers.add("HISTORICAL_GATE_NOT_READY");
        if (!forwardReady) blockers.add("FORWARD_SHADOW_GATE_NOT_READY");
        if (!expectedConfigHashAvailable) blockers.add("EFFECTIVE_POLICY_CONFIG_HASH_UNAVAILABLE");
        if (!properties.liveMicroArmed()) blockers.add("LIVE_MICRO_NOT_EXPLICITLY_ARMED");
        if (!Strategy508TimeExitPolicy.EXACT_LIVE_FILL_EVIDENCE_IMPLEMENTED) {
            blockers.add("LIVE_EXACT_FILL_PROVENANCE_NOT_IMPLEMENTED");
        }
        if (!liveAttempts.isEmpty() && !firstProbeVerified) blockers.add("FIRST_PROBE_EXECUTION_NOT_VERIFIED");
        if (firstAttempt != null && isFinalized(firstAttempt.getFinalOutcome())
                && hasCompleteFeeEvidence(firstAttemptView) && !firstProbeNetPositive) {
            blockers.add("FIRST_PROBE_NET_NOT_POSITIVE");
        }
        if (liveFeeGapCount > 0) blockers.add("LIVE_PILOT_FEE_EVIDENCE_GAP");
        if (livePartialFillIncidents > 0) blockers.add("LIVE_PILOT_PARTIAL_FILL_INCIDENT");
        if (!liveAttempts.isEmpty() && liveFinalizedViews.size() == liveAttempts.size()
                && liveFeeGapCount == 0 && liveParityGapCount == 0
                && livePartialFillIncidents == 0 && liveNetPnl.signum() <= 0) {
            blockers.add("LIVE_PILOT_NET_NOT_POSITIVE");
        } else if (!liveAttempts.isEmpty() && !pilotHealthyAndNetPositive) {
            blockers.add("LIVE_PILOT_EXECUTION_NOT_COMPLETE_OR_HEALTHY");
        }
        if (openLive >= Strategy508TimeExitPolicy.MAX_OPEN_POSITIONS) blockers.add("EXPERIMENT_OPEN_POSITION_CAP");
        if (pilotCap) blockers.add("PILOT_ORDER_CAP_REACHED");
        if (pilotExpired) blockers.add("PILOT_WINDOW_EXPIRED");
        if (lossFuse) blockers.add("EXPERIMENT_CUMULATIVE_LOSS_FUSE");
        boolean liveEntryReady = blockers.isEmpty();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "getStrategy508TimeExitReadiness");
        root.put("boundary", "READ_ONLY");
        root.put("generatedAtUtc", now.toString());
        root.put("policyMode", POLICY_MODE);
        root.put("strategyId", STRATEGY_ID);
        root.put("symbol", symbol);
        root.put("configuredMode", properties.mode().name());
        root.put("liveOrderFlag", properties.liveOrderEnabled());
        root.put("shadowArmed", properties.enabled());
        root.put("liveMicroArmed", properties.liveMicroArmed());
        root.put("effectivePolicyConfigSha256", expectedConfigHash);
        root.put("exactLiveFillEvidenceImplemented",
                Strategy508TimeExitPolicy.EXACT_LIVE_FILL_EVIDENCE_IMPLEMENTED);
        root.put("exactLiveFillEvidenceStatus",
                Strategy508TimeExitPolicy.EXACT_LIVE_FILL_EVIDENCE_IMPLEMENTED
                        ? "AVAILABLE" : "BLOCKED_NO_IMMUTABLE_ALL_FILL_SIGNED_FEE_LEDGER");
        root.put("historicalGatePassed", historicalReady);
        if (historical != null) {
            root.put("historicalVerdict", historical.path("verdict").asText("UNKNOWN"));
            root.put("historicalFinalizedEvents", historical.path("finalizedEvents").asInt(0));
        }
        ObjectNode rawCounterfactual = root.putObject("rawSignalCounterfactual");
        rawCounterfactual.put("cohortSchemaVersion", Strategy508TimeExitPolicy.COHORT_SCHEMA_VERSION);
        rawCounterfactual.put("currentConfigEvidenceRows", currentConfigCandidateViews.size());
        rawCounterfactual.put("preResetEvidenceRowsExcluded", configCohort.preResetRowsExcluded());
        rawCounterfactual.put("observationDays", rawObservationDays);
        rawCounterfactual.put("observedEntryEvents", rawUniqueEntries.size());
        rawCounterfactual.put("windowEntryEvents", rawForwardEntries.size());
        rawCounterfactual.put("finalizedEvents", rawFinalized.size());
        rawCounterfactual.put("pendingEvents", rawPending);
        rawCounterfactual.put("hardGateBlockedEvents", rawHardGateBlocked);
        rawCounterfactual.put("exactOutcomeEvents", rawFinalized.size() - rawIncompleteFeeRows);
        rawCounterfactual.put("incompleteOutcomeEvidenceEvents", rawIncompleteFeeRows);
        rawCounterfactual.put("outcomeContextMismatchRows", rawOutcomeContextMismatchRows);
        rawCounterfactual.put("duplicateEventRows", rawDuplicateEventRows);
        rawCounterfactual.put("explicitlyBoundRows", explicitRawCounterfactualRows);
        rawCounterfactual.put("legacyHardBlockedRawOnlyRows", legacyHardBlockedRawOnlyRows);
        rawCounterfactual.put("unboundPotentialExecutableRows", unboundPotentialExecutableRows);
        rawCounterfactual.put("cohortBindingMismatchRows", cohortBindingMismatchRows);
        rawCounterfactual.put("netPnlUsdt", decimal(rawCounterfactualNetPnl));
        rawCounterfactual.put("livePromotionEligible", false);
        rawCounterfactual.put("semantics",
                "ALL_UNIQUE_RAW_BUY_EVENTS_OUTCOME_TRACKED_BLOCKED_EVENTS_NEVER_PROMOTE");
        ObjectNode forward = root.putObject("forwardShadow");
        forward.put("observationDays", observationDays);
        forward.put("rawCandidateEvidenceRows", currentConfigCandidateViews.size());
        forward.put("totalCandidateEvidenceRows", candidateRows.size());
        forward.put("preResetEvidenceRowsExcluded", configCohort.preResetRowsExcluded());
        forward.put("configTransitionRows", configCohort.transitionRows());
        if (configCohort.resetAt() == null) forward.putNull("configCohortResetAtUtc");
        else forward.put("configCohortResetAtUtc", configCohort.resetAt().toString());
        forward.put("configCohortSemantics", "CONTIGUOUS_SUFFIX_AFTER_LAST_DIFFERENT_EFFECTIVE_CONFIG_HASH");
        forward.put("allObservedEntryEvents", rawUniqueEntries.size());
        forward.put("explicitExecutableEntryEvents", uniqueExecutableEntries.size());
        forward.put("canonicalAdmittedEntryEvents", entries.size());
        forward.put("overlapSkippedEvents", shadowCohort.overlapSkippedRows());
        forward.put("dailyCapSkippedEvents", shadowCohort.dailyCapSkippedRows());
        forward.put("canonicalTimingGapRows", shadowCohort.timingGapRows());
        forward.put("timingValidationSeedStartUtc", cohortTimingValidationStart.toString());
        forward.put("evaluationWindowDays", FORWARD_MIN_DAYS);
        forward.put("evaluationWindowStartUtc", forwardWindowStart.toString());
        forward.put("entryEvents", forwardWindowEntries.size());
        forward.put("finalizedEvents", finalized.size());
        forward.put("minimumDays", FORWARD_MIN_DAYS);
        forward.put("minimumFinalizedEvents", FORWARD_MIN_FINALIZED_EVENTS);
        forward.put("netPnlUsdt", decimal(forwardNetPnl));
        forward.put("hardGateBlockedEvents", rawHardGateBlocked);
        forward.put("hardGateBlockedEventsAffectPromotion", false);
        forward.put("entryExitParityGapCount", parityGapCount);
        forward.put("outcomeContextMismatchRows", outcomeContextMismatchRows);
        forward.put("incompleteFeeEvidenceEvents", incompleteFeeRows);
        forward.put("invalidNetPnlEvents", invalidNetPnlRows);
        forward.put("duplicateEventRows", duplicateEventRows);
        forward.put("malformedContextRows", malformedContextRows);
        forward.put("effectiveConfigMismatchRows", configMismatchRows);
        forward.put("nonShadowExecutionModeRows", nonShadowModeRows);
        forward.put("unboundPotentialExecutableRows", unboundPotentialExecutableRows);
        forward.put("cohortBindingMismatchRows", cohortBindingMismatchRows);
        forward.put("promotionCohort", Strategy508TimeExitPolicy.EXECUTABLE_SHADOW_COHORT);
        forward.put("cohortSemantics",
                "EXPLICIT_EXECUTABLE_SHADOW_ONLY_UNIQUE_EVENT_CONFIG_BOUND_FAIL_CLOSED");
        forward.put("gatePassed", forwardReady);
        ObjectNode pilot = root.putObject("livePilot");
        pilot.put("orders", liveSignals.size());
        pilot.put("orderAttempts", liveAttempts.size());
        pilot.put("openPositions", openLive);
        pilot.put("maxOrders", MAX_PILOT_ORDERS);
        pilot.put("maxDays", PILOT_MAX_DAYS);
        pilot.put("netPnlUsdt", decimal(liveNetPnl));
        pilot.put("finalizedAttempts", liveFinalizedViews.size());
        pilot.put("feeEvidenceGapCount", liveFeeGapCount);
        pilot.put("parityGapCount", liveParityGapCount);
        pilot.put("partialFillIncidentCount", livePartialFillIncidents);
        pilot.put("pilotHealthyAndNetPositive", pilotHealthyAndNetPositive);
        pilot.put("lossFuseThresholdUsdt", decimal(MAX_CUMULATIVE_LOSS_USDT.negate()));
        pilot.put("lossFuseTriggered", lossFuse);
        pilot.put("pilotExpired", pilotExpired);
        pilot.put("firstProbeAttempted", firstAttempt != null);
        pilot.put("firstProbeVerified", firstProbeVerified);
        pilot.put("firstProbeNetPositive", firstProbeNetPositive);
        if (firstProbeNetPnl == null) pilot.putNull("firstProbeNetPnlUsdt");
        else pilot.put("firstProbeNetPnlUsdt", decimal(firstProbeNetPnl));
        if (firstAttempt != null) {
            pilot.put("firstProbeEvidenceId", firstAttempt.getId());
            pilot.put("firstProbeLiveSignalId", firstAttempt.getLiveSignalId());
        }
        ArrayNode blockerNode = root.putArray("blockers");
        blockers.forEach(blockerNode::add);
        root.put("liveEntryReady", liveEntryReady);
        root.put("liveOrderAllowed", false);
        root.put("verdict", liveEntryReady
                ? "READY_FOR_SINGLE_10_USDT_PROBE_NOT_AUTHORIZED"
                : properties.enabled() ? "SHADOW_COLLECTING_NOT_LIVE" : "OFF");
        ObjectNode safety = root.putObject("safety");
        safety.put("orderSent", false);
        safety.put("ocoModified", false);
        safety.put("writesRuntimeEvidence", false);
        return new ReadinessSnapshot(root, liveEntryReady, blockers, forwardNetPnl, liveNetPnl);
    }

    private ConfigCohort currentConfigCohort(List<EvidenceView> source,
                                             String expectedConfigHash) {
        List<EvidenceView> sorted = source == null ? List.of() : source.stream()
                .sorted(Comparator.comparing(this::evidenceTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        LocalDateTime resetAt = null;
        long transitionRows = 0;
        for (EvidenceView view : sorted) {
            if (!view.valid() || matchesEffectiveConfig(view, expectedConfigHash)) {
                continue;
            }
            transitionRows++;
            LocalDateTime rowTime = evidenceTime(view);
            if (rowTime != null && (resetAt == null || rowTime.isAfter(resetAt))) {
                resetAt = rowTime;
            }
        }
        LocalDateTime finalResetAt = resetAt;
        List<EvidenceView> current = sorted.stream()
                .filter(view -> {
                    LocalDateTime rowTime = evidenceTime(view);
                    return finalResetAt == null || rowTime == null || rowTime.isAfter(finalResetAt);
                })
                .toList();
        return new ConfigCohort(current, sorted.size() - current.size(), transitionRows, resetAt);
    }

    private LocalDateTime evidenceTime(EvidenceView view) {
        return view == null || view.row() == null ? null : view.row().getEvidenceTime();
    }

    private ShadowCohort canonicalShadowCohort(List<EvidenceView> source,
                                               LocalDateTime timingValidationStart) {
        List<EvidenceView> sorted = source.stream()
                .sorted(Comparator.comparing(this::cohortEntryTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<EvidenceView> accepted = new ArrayList<>();
        Set<LocalDate> admittedDays = new HashSet<>();
        LocalDateTime occupiedUntil = null;
        long overlapSkipped = 0;
        long dailyCapSkipped = 0;
        long timingGaps = 0;
        for (EvidenceView view : sorted) {
            if (hasHardBlocker(view)) {
                accepted.add(view);
                continue;
            }
            LocalDateTime exactEntry = parseTime(view.context().path("entryTime").asText(null));
            LocalDateTime entryTime = exactEntry != null
                    ? exactEntry : parseTime(view.context().path("decisionTime").asText(null));
            boolean finalized = isFinalized(view.row().getFinalOutcome());
            boolean pending = "PENDING_24H".equals(view.row().getFinalOutcome());
            if (entryTime == null) {
                if (timingGapRelevant(view, null, timingValidationStart)) timingGaps++;
                accepted.add(view);
                continue;
            }
            if ((finalized || pending) && exactEntry == null
                    && timingGapRelevant(view, entryTime, timingValidationStart)) {
                timingGaps++;
            }
            if (occupiedUntil != null && !entryTime.isAfter(occupiedUntil)) {
                overlapSkipped++;
                continue;
            }
            if (admittedDays.contains(entryTime.toLocalDate())) {
                dailyCapSkipped++;
                continue;
            }
            accepted.add(view);
            admittedDays.add(entryTime.toLocalDate());
            if (finalized) {
                LocalDateTime exitTime = parseTime(view.context().path("exitTime").asText(null));
                if (exitTime == null || exitTime.isBefore(entryTime)) {
                    if (timingGapRelevant(view, entryTime, timingValidationStart)) timingGaps++;
                    occupiedUntil = entryTime.plusHours(Strategy508TimeExitPolicy.HOLD_HOURS);
                } else {
                    occupiedUntil = exitTime;
                }
            } else if (pending) {
                occupiedUntil = exactEntry == null
                        ? entryTime.plusMinutes(Strategy508TimeExitPolicy.ENTRY_MAX_DELAY_MINUTES)
                        .plusHours(Strategy508TimeExitPolicy.HOLD_HOURS)
                        : entryTime.plusHours(Strategy508TimeExitPolicy.HOLD_HOURS);
            } else {
                if (timingGapRelevant(view, entryTime, timingValidationStart)) timingGaps++;
                occupiedUntil = entryTime.plusHours(Strategy508TimeExitPolicy.HOLD_HOURS);
            }
        }
        return new ShadowCohort(accepted, overlapSkipped, dailyCapSkipped, timingGaps);
    }

    private boolean timingGapRelevant(EvidenceView view,
                                      LocalDateTime entryTime,
                                      LocalDateTime validationStart) {
        if (validationStart == null) return true;
        LocalDateTime reference = entryTime;
        if (reference == null && view != null && view.row() != null) {
            reference = view.row().getEvidenceTime();
        }
        return reference == null || !reference.isBefore(validationStart);
    }

    private LocalDateTime cohortEntryTime(EvidenceView view) {
        if (view == null || !view.valid()) return null;
        LocalDateTime entry = parseTime(view.context().path("entryTime").asText(null));
        if (entry != null) return entry;
        LocalDateTime decision = parseTime(view.context().path("decisionTime").asText(null));
        return decision != null ? decision : view.row().getEvidenceTime();
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isFinalized(String outcome) {
        return "TP_HIT".equals(outcome) || "SL_HIT".equals(outcome)
                || "TIME_EXIT_24H".equals(outcome) || "OCO_TP".equals(outcome)
                || "OCO_SL".equals(outcome);
    }

    private boolean hasHardBlocker(EvidenceView view) {
        if (view == null || !view.valid()) return true;
        JsonNode node = view.context();
        return node.path("hardBlockers").isArray() && !node.path("hardBlockers").isEmpty();
    }

    private boolean isExplicitRawCounterfactualCohort(EvidenceView view) {
        if (view == null || !view.valid()) return false;
        JsonNode node = view.context();
        return Strategy508TimeExitPolicy.COHORT_SCHEMA_VERSION.equals(
                node.path("cohortSchemaVersion").asText())
                && node.path("rawSignalCounterfactualEligible").asBoolean(false)
                && node.path("counterfactualOutcomeTracked").asBoolean(false);
    }

    private boolean isExplicitExecutableCohort(EvidenceView view) {
        if (!isExplicitRawCounterfactualCohort(view)) return false;
        JsonNode node = view.context();
        return node.path("executableCohortEligible").asBoolean(false)
                && Strategy508TimeExitPolicy.EXECUTABLE_SHADOW_COHORT.equals(
                node.path("promotionCohort").asText())
                && "PASSED".equals(node.path("executionGateOutcome").asText())
                && !hasHardBlocker(view);
    }

    private boolean hasCohortBindingMismatch(EvidenceView view) {
        if (!isExplicitRawCounterfactualCohort(view)) return false;
        JsonNode node = view.context();
        boolean executable = node.path("executableCohortEligible").asBoolean(false);
        String promotionCohort = node.path("promotionCohort").asText();
        String gateOutcome = node.path("executionGateOutcome").asText();
        boolean hardBlocked = hasHardBlocker(view);
        if (executable) {
            return hardBlocked
                    || !Strategy508TimeExitPolicy.EXECUTABLE_SHADOW_COHORT.equals(promotionCohort)
                    || !"PASSED".equals(gateOutcome);
        }
        return !hardBlocked
                || !Strategy508TimeExitPolicy.RAW_COUNTERFACTUAL_COHORT.equals(promotionCohort)
                || !"HARD_BLOCKED".equals(gateOutcome);
    }

    private boolean hasParityGap(EvidenceView view) {
        if (view == null || !view.valid()) return true;
        JsonNode node = view.context();
        return node.path("entryParityGap").asBoolean(false)
                || node.path("exitParityGap").asBoolean(false);
    }

    private boolean hasOutcomeContextMismatch(EvidenceView view) {
        if (view == null || !view.valid() || view.row() == null) return true;
        JsonNode node = view.context();
        String rowOutcome = view.row().getFinalOutcome();
        if (node.has("outcome")
                && !Objects.equals(rowOutcome, node.path("outcome").asText(null))) {
            return true;
        }
        return node.has("finalized")
                && node.path("finalized").asBoolean(false) != isFinalized(rowOutcome);
    }

    private boolean hasCompleteFeeEvidence(EvidenceView view) {
        if (view == null || !view.valid()) return false;
        JsonNode node = view.context();
        JsonNode netPnl = node.path("netPnlUsdt");
        boolean netPnlPresent = netPnl.isNumber()
                || (netPnl.isTextual() && !netPnl.asText().isBlank());
        boolean partialFeesComplete = !node.has("partialExitFeeCoverageComplete")
                || node.path("partialExitFeeCoverageComplete").asBoolean(false);
        boolean liveExecution = view.row().getExecutionMode() != null
                && view.row().getExecutionMode().startsWith("LIVE");
        boolean exactShadowEvidence = liveExecution || hasExactShadowOutcomeEvidence(view);
        boolean liveFillProvenanceComplete = !liveExecution
                || (node.path("fillAggregationComplete").asBoolean(false)
                && node.path("feeSignPreserved").asBoolean(false));
        return node.path("feeCoverageComplete").asBoolean(false)
                && partialFeesComplete && netPnlPresent
                && exactShadowEvidence && liveFillProvenanceComplete;
    }

    private boolean hasExactShadowOutcomeEvidence(EvidenceView view) {
        if (view == null || !view.valid() || view.row() == null) return false;
        JsonNode node = view.context();
        String rowOutcome = view.row().getFinalOutcome();
        return isFinalized(rowOutcome)
                && node.path("finalized").asBoolean(false)
                && Objects.equals(rowOutcome, node.path("outcome").asText(null))
                && decimalEquals(node.path("oneMinuteCoverage"), BigDecimal.ONE)
                && node.path("minuteLatticeExact").asBoolean(false)
                && "DETERMINISTIC_MODELED_FEE_AND_SLIPPAGE_NOT_EXCHANGE_FILL".equals(
                node.path("feeEvidenceSemantics").asText())
                && node.path("modeledFeeFieldsComplete").asBoolean(false)
                && decimalValue(node.path("entryAndExitFeesUsdt")) != null
                && decimalValue(node.path("netReturnPct")) != null
                && parseTime(node.path("entryTime").asText(null)) != null
                && parseTime(node.path("exitTime").asText(null)) != null;
    }

    private BigDecimal decimalValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasPartialFillIncident(EvidenceView view) {
        return view != null && view.valid() && view.context().path("partialFill").asBoolean(false);
    }

    private BigDecimal netPnl(EvidenceView view) {
        if (view == null || !view.valid()) return null;
        JsonNode node = view.context();
        JsonNode value = node.path("netPnlUsdt");
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private EvidenceView view(RuntimeDecisionEvidence row) {
        JsonNode context = parseStrict(row == null ? null : row.getPolicyInputsJson());
        String contextConfigHash = context == null
                ? null : context.path("effectivePolicyConfigSha256").asText(null);
        boolean valid = row != null
                && context != null
                && context.isObject()
                && STRATEGY_ID == (row.getStrategyId() == null ? -1L : row.getStrategyId())
                && SYMBOL.equalsIgnoreCase(row.getSymbol())
                && Strategy508TimeExitPolicy.INTERVAL.equalsIgnoreCase(row.getIntervalCode())
                && POLICY_MODE.equals(context.path("policyMode").asText())
                && SYMBOL.equalsIgnoreCase(context.path("symbol").asText())
                && Strategy508TimeExitPolicy.INTERVAL.equalsIgnoreCase(
                        context.path("intervalCode").asText())
                && Strategy508TimeExitPolicy.KLINE_SOURCE.equalsIgnoreCase(
                        context.path("source").asText())
                && parseTime(context.path("barOpenTime").asText(null)) != null
                && parseTime(context.path("decisionTime").asText(null)) != null
                && decimalEquals(context.path("notionalUsdt"), Strategy508TimeExitPolicy.NOTIONAL_USDT)
                && decimalEquals(context.path("takeProfitPct"), Strategy508TimeExitPolicy.TAKE_PROFIT_PCT)
                && decimalEquals(context.path("stopLossPct"), Strategy508TimeExitPolicy.STOP_LOSS_PCT)
                && context.path("holdHours").asInt(-1) == Strategy508TimeExitPolicy.HOLD_HOURS
                && contextConfigHash != null && !contextConfigHash.isBlank();
        String eventKey = valid
                ? row.getStrategyId() + "|" + row.getSymbol().toUpperCase() + "|"
                + row.getIntervalCode().toLowerCase() + "|" + context.path("barOpenTime").asText()
                : null;
        return new EvidenceView(row, context, valid, eventKey);
    }

    private boolean matchesEffectiveConfig(EvidenceView view, String expectedConfigHash) {
        return view != null && view.valid()
                && expectedConfigHash != null && !expectedConfigHash.isBlank()
                && expectedConfigHash.equalsIgnoreCase(
                view.context().path("effectivePolicyConfigSha256").asText(""));
    }

    private boolean decimalEquals(JsonNode node, BigDecimal expected) {
        if (node == null || node.isMissingNode() || node.isNull() || expected == null) return false;
        try {
            BigDecimal actual = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText());
            return actual.compareTo(expected) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode parseStrict(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean inWindow(RuntimeDecisionEvidence row, LocalDateTime start) {
        return row != null && row.getEvidenceTime() != null
                && !row.getEvidenceTime().isBefore(start);
    }

    private boolean isPolicyPosition(BtLiveSignal signal) {
        return signal.getFilterReason() != null && signal.getFilterReason().contains(POLICY_MODE);
    }

    private String normalizeSymbol(String requestedSymbol) {
        if (requestedSymbol == null || requestedSymbol.isBlank()) return SYMBOL;
        return requestedSymbol.trim().toUpperCase().replace("-", "")
                .replace("/", "").replace("_", "");
    }

    private String decimal(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP).toPlainString();
    }

    public record ReadinessSnapshot(ObjectNode node,
                                    boolean liveEntryReady,
                                    List<String> blockers,
                                    BigDecimal forwardNetPnl,
                                    BigDecimal liveNetPnl) {
    }

    private record EvidenceView(RuntimeDecisionEvidence row,
                                JsonNode context,
                                boolean valid,
                                String eventKey) {
    }

    private record ShadowCohort(List<EvidenceView> entries,
                                long overlapSkippedRows,
                                long dailyCapSkippedRows,
                                long timingGapRows) {
    }

    private record ConfigCohort(List<EvidenceView> currentViews,
                                long preResetRowsExcluded,
                                long transitionRows,
                                LocalDateTime resetAt) {
    }
}
