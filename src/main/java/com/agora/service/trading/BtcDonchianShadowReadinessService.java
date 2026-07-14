package com.agora.service.trading;

import com.agora.config.properties.BtcDonchianShadowProperties;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.agora.service.trading.BtcDonchianShadowPolicy.EVIDENCE_SCHEMA_VERSION;
import static com.agora.service.trading.BtcDonchianShadowPolicy.FORWARD_MIN_COMPLETED_TRADES;
import static com.agora.service.trading.BtcDonchianShadowPolicy.FORWARD_MIN_DAYS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.FORWARD_MIN_UNIQUE_ENTRIES;
import static com.agora.service.trading.BtcDonchianShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.NORMAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.POLICY_MODE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SOURCE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.STRESS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SYMBOL;

/** Independent fail-closed forward gate for the SHADOW-only Donchian lane. */
@Service
@RequiredArgsConstructor
public class BtcDonchianShadowReadinessService {

    private final BtcDonchianShadowProperties properties;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final RuntimeDecisionEvidenceService runtimeEvidenceService;
    private final BtcDonchianShadowGoldenParityService goldenParityService;
    private final BtcDonchianShadowEngine engine;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String report(String requestedSymbol) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot(requestedSymbol));
        } catch (Exception e) {
            return "{\"tool\":\"getBtcDonchianShadowReadiness\",\"status\":"
                    + "\"REPORT_FAILED_FAIL_CLOSED\",\"liveOrderAllowed\":false}";
        }
    }

    @Transactional(readOnly = true)
    public ObjectNode snapshot(String requestedSymbol) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String symbol = normalizeSymbol(requestedSymbol);
        ObjectNode report = baseReport(symbol);
        ArrayNode blockers = report.withArray("blockers");
        if (!SYMBOL.equals(symbol)) blockers.add("UNSUPPORTED_SYMBOL");
        if (!properties.enabled()) blockers.add("SHADOW_MODE_OFF");
        if (!runtimeEvidenceService.isEnabled()) blockers.add("RUNTIME_EVIDENCE_DISABLED");

        ObjectNode golden = SYMBOL.equals(symbol) ? goldenParityService.analyzeNode(symbol) : null;
        boolean goldenPassed = golden != null && golden.path("goldenParityPassed").asBoolean(false);
        report.set("goldenParity", golden == null ? objectMapper.createObjectNode() : golden);
        if (!goldenPassed) blockers.add("GOLDEN_PARITY_NOT_PROVEN");

        List<RuntimeDecisionEvidence> rows = SYMBOL.equals(symbol)
                ? evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
                POLICY_MODE, now.minusDays(365)) : List.of();
        List<EvidenceView> views = rows.stream()
                .filter(row -> SYMBOL.equalsIgnoreCase(row.getSymbol()))
                .filter(row -> INTERVAL.equalsIgnoreCase(row.getIntervalCode()))
                .map(this::view)
                .sorted(Comparator.comparing(EvidenceView::barOpenTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<EvidenceView> observed = views.stream()
                .filter(EvidenceView::valid)
                .filter(view -> "SHADOW_OBSERVED".equals(view.row().getFinalOutcome()))
                .toList();
        List<EvidenceView> forward = observed.stream()
                .filter(view -> !view.bootstrap() && !view.catchUp())
                .toList();

        long malformedRows = views.stream().filter(view -> !view.valid()).count();
        Set<LocalDateTime> observedBarTimes = observed.stream()
                .map(EvidenceView::barOpenTime).filter(value -> value != null).collect(Collectors.toSet());
        List<EvidenceView> blocked = views.stream().filter(view -> "BLOCKED_DATA_QUALITY".equals(
                view.row().getFinalOutcome())).toList();
        long blockedRows = blocked.size();
        long resolvedBlockedRows = blocked.stream().filter(view -> view.barOpenTime() != null
                && observedBarTimes.contains(view.barOpenTime())).count();
        long unresolvedBlockedRows = blockedRows - resolvedBlockedRows;
        long orderSentViolations = views.stream().filter(view -> Boolean.TRUE.equals(view.row().getOrderSent())).count();
        long executionModeViolations = views.stream().filter(view -> view.valid()
                && !"SHADOW_ONLY".equals(view.row().getExecutionMode())).count();
        long stateHashMismatchRows = observed.stream().filter(view -> !stateHashValid(view.root())).count();
        long timingGapRows = observed.stream().filter(view -> !view.root().path("timingCausal").asBoolean(false)).count();
        long latticeGapRows = observed.stream().filter(view -> !view.root().path("hourlyLatticeComplete")
                .asBoolean(false)).count();
        long feeGapRows = observed.stream().filter(view -> !view.root().path("feeModelComplete")
                .asBoolean(false)).count();
        long slippageGapRows = observed.stream().filter(view -> !view.root().path("slippageModelComplete")
                .asBoolean(false)).count();
        long duplicateBarRows = duplicateBarRows(observed);
        long sequenceGapRows = sequenceGapRows(observed);

        ScenarioForward normal = forwardScenario(forward, NORMAL.name());
        ScenarioForward stress = forwardScenario(forward, STRESS.name());
        LocalDateTime firstForwardClose = forward.stream().map(EvidenceView::barCloseTime)
                .filter(value -> value != null).min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime lastForwardClose = forward.stream().map(EvidenceView::barCloseTime)
                .filter(value -> value != null).max(LocalDateTime::compareTo).orElse(null);
        long observationDays = firstForwardClose == null || lastForwardClose == null
                ? 0 : Duration.between(firstForwardClose, lastForwardClose).toDays();
        long latestAgeMinutes = lastForwardClose == null ? Long.MAX_VALUE
                : Math.max(0, Duration.between(lastForwardClose, now).toMinutes());
        boolean currentEnough = latestAgeMinutes <= 120;

        report.put("runtimeEvidenceRows", rows.size());
        report.put("validObservedBars", observed.size());
        report.put("forwardNonBootstrapBars", forward.size());
        report.put("firstForwardCloseTimeUtc", value(firstForwardClose));
        report.put("lastForwardCloseTimeUtc", value(lastForwardClose));
        report.put("observationDays", observationDays);
        report.put("requiredObservationDays", FORWARD_MIN_DAYS);
        report.put("latestForwardBarAgeMinutes", latestAgeMinutes == Long.MAX_VALUE ? -1 : latestAgeMinutes);
        report.put("latestForwardBarCurrent", currentEnough);
        report.put("malformedEvidenceRows", malformedRows);
        report.put("blockedDataQualityRows", blockedRows);
        report.put("resolvedBlockedDataQualityRows", resolvedBlockedRows);
        report.put("unresolvedBlockedDataQualityRows", unresolvedBlockedRows);
        report.put("orderSentViolations", orderSentViolations);
        report.put("executionModeViolations", executionModeViolations);
        report.put("stateHashMismatchRows", stateHashMismatchRows);
        report.put("timingGapRows", timingGapRows);
        report.put("hourlyLatticeGapRows", latticeGapRows);
        report.put("feeModelGapRows", feeGapRows);
        report.put("slippageModelGapRows", slippageGapRows);
        report.put("duplicateBarRows", duplicateBarRows);
        report.put("sequenceGapRows", sequenceGapRows);
        report.set("normalForward", scenarioNode(normal));
        report.set("stressForward", scenarioNode(stress));

        if (malformedRows > 0) blockers.add("MALFORMED_RUNTIME_EVIDENCE");
        if (unresolvedBlockedRows > 0) blockers.add("UNRESOLVED_DATA_QUALITY_BLOCKER_PRESENT");
        if (orderSentViolations > 0) blockers.add("SHADOW_ORDER_SENT_VIOLATION");
        if (executionModeViolations > 0) blockers.add("NON_SHADOW_EXECUTION_MODE_EVIDENCE");
        if (stateHashMismatchRows > 0) blockers.add("STATE_HASH_MISMATCH");
        if (timingGapRows > 0) blockers.add("NON_CAUSAL_TIMING_EVIDENCE");
        if (latticeGapRows > 0 || sequenceGapRows > 0) blockers.add("HOURLY_LATTICE_NOT_COMPLETE");
        if (feeGapRows > 0) blockers.add("FEE_MODEL_EVIDENCE_INCOMPLETE");
        if (slippageGapRows > 0) blockers.add("SLIPPAGE_MODEL_EVIDENCE_INCOMPLETE");
        if (duplicateBarRows > 0) blockers.add("DUPLICATE_CANONICAL_BAR_EVIDENCE");
        if (observationDays < FORWARD_MIN_DAYS) blockers.add("FORWARD_OBSERVATION_DAYS_INSUFFICIENT");
        if (normal.uniqueEntries() < FORWARD_MIN_UNIQUE_ENTRIES) blockers.add("FORWARD_UNIQUE_ENTRIES_INSUFFICIENT");
        if (normal.completedTrades() < FORWARD_MIN_COMPLETED_TRADES) blockers.add("FORWARD_COMPLETED_TRADES_INSUFFICIENT");
        if (!currentEnough) blockers.add("LATEST_FORWARD_BAR_NOT_CURRENT");
        if (normal.netPnl().signum() <= 0) blockers.add("NORMAL_FORWARD_NET_PNL_NOT_POSITIVE");
        if (stress.netPnl().signum() < 0) blockers.add("STRESS_FORWARD_NET_PNL_NEGATIVE");
        if (normal.orphanTradeRows() > 0 || stress.orphanTradeRows() > 0) {
            blockers.add("FORWARD_TRADE_WITHOUT_FORWARD_ENTRY");
        }

        boolean integrityClear = malformedRows == 0 && unresolvedBlockedRows == 0 && orderSentViolations == 0
                && executionModeViolations == 0 && stateHashMismatchRows == 0 && timingGapRows == 0
                && latticeGapRows == 0 && feeGapRows == 0 && slippageGapRows == 0
                && duplicateBarRows == 0 && sequenceGapRows == 0;
        boolean sampleReady = observationDays >= FORWARD_MIN_DAYS
                && normal.uniqueEntries() >= FORWARD_MIN_UNIQUE_ENTRIES
                && normal.completedTrades() >= FORWARD_MIN_COMPLETED_TRADES;
        boolean economicsReady = normal.netPnl().signum() > 0 && stress.netPnl().signum() >= 0
                && normal.orphanTradeRows() == 0 && stress.orphanTradeRows() == 0;
        boolean forwardGatePassed = SYMBOL.equals(symbol) && properties.enabled()
                && runtimeEvidenceService.isEnabled() && goldenPassed && integrityClear
                && sampleReady && economicsReady && currentEnough;
        report.put("runtimeIntegrityClear", integrityClear);
        report.put("forwardSampleReady", sampleReady);
        report.put("forwardEconomicsReady", economicsReady);
        report.put("forwardGatePassed", forwardGatePassed);
        report.put("status", status(goldenPassed, integrityClear, sampleReady, economicsReady,
                currentEnough, forwardGatePassed));
        report.put("liveImplementationPresent", false);
        report.put("liveOrderAllowed", false);
        report.put("promotionAuthorizationGranted", false);
        return report;
    }

    private EvidenceView view(RuntimeDecisionEvidence row) {
        try {
            JsonNode root = objectMapper.readTree(row.getFeaturesSnapshotJson());
            boolean valid = root != null && EVIDENCE_SCHEMA_VERSION.equals(
                    root.path("evidenceSchemaVersion").asText())
                    && POLICY_MODE.equals(root.path("policyMode").asText())
                    && !root.path("barOpenTime").asText("").isBlank();
            LocalDateTime open = valid ? LocalDateTime.parse(root.path("barOpenTime").asText()) : null;
            LocalDateTime close = valid && !root.path("barCloseTime").asText("").isBlank()
                    ? LocalDateTime.parse(root.path("barCloseTime").asText()) : null;
            return new EvidenceView(row, root, valid, open, close,
                    valid && root.path("bootstrap").asBoolean(false),
                    valid && root.path("catchUp").asBoolean(false));
        } catch (Exception e) {
            return new EvidenceView(row, objectMapper.createObjectNode(), false, null, null, false, false);
        }
    }

    private boolean stateHashValid(JsonNode root) {
        try {
            JsonNode stateNode = root.path("stateAfter");
            String expected = root.path("stateAfterSha256").asText("");
            if (stateNode.isMissingNode() || stateNode.isNull() || expected.isBlank()) return false;
            BtcDonchianShadowEngine.State state = objectMapper.treeToValue(
                    stateNode, BtcDonchianShadowEngine.State.class);
            return expected.equals(engine.stateSha256(state));
        } catch (Exception e) {
            return false;
        }
    }

    private long duplicateBarRows(List<EvidenceView> views) {
        Map<LocalDateTime, Long> counts = new LinkedHashMap<>();
        for (EvidenceView view : views) counts.merge(view.barOpenTime(), 1L, Long::sum);
        return counts.values().stream().filter(count -> count > 1).mapToLong(count -> count - 1).sum();
    }

    private long sequenceGapRows(List<EvidenceView> views) {
        List<LocalDateTime> unique = views.stream().map(EvidenceView::barOpenTime)
                .filter(value -> value != null).distinct().sorted().toList();
        long gaps = 0;
        for (int i = 1; i < unique.size(); i++) {
            if (!unique.get(i - 1).plusHours(1).equals(unique.get(i))) gaps++;
        }
        return gaps;
    }

    private ScenarioForward forwardScenario(List<EvidenceView> views, String scenario) {
        Set<String> entries = new HashSet<>();
        List<JsonNode> tradeEvents = new ArrayList<>();
        for (EvidenceView view : views) {
            for (JsonNode event : view.root().path("events")) {
                if (!scenario.equals(event.path("scenario").asText())) continue;
                if ("ENTRY_SIGNAL".equals(event.path("eventType").asText())) {
                    entries.add(event.path("eventId").asText());
                } else if ("VIRTUAL_TRADE_CLOSED".equals(event.path("eventType").asText())) {
                    tradeEvents.add(event);
                }
            }
        }
        BigDecimal netPnl = BigDecimal.ZERO;
        long completed = 0;
        long orphan = 0;
        for (JsonNode event : tradeEvents) {
            String entryId = event.path("payload").path("entrySignalId").asText("");
            if (!entries.contains(entryId)) {
                orphan++;
                continue;
            }
            JsonNode pnl = event.path("payload").path("profitLossEquityUnits");
            if (!pnl.isNumber()) {
                orphan++;
                continue;
            }
            netPnl = netPnl.add(pnl.decimalValue());
            completed++;
        }
        return new ScenarioForward(scenario, entries.size(), completed, orphan, netPnl);
    }

    private ObjectNode scenarioNode(ScenarioForward scenario) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scenario", scenario.scenario());
        node.put("uniqueEntries", scenario.uniqueEntries());
        node.put("requiredUniqueEntries", FORWARD_MIN_UNIQUE_ENTRIES);
        node.put("completedTrades", scenario.completedTrades());
        node.put("requiredCompletedTrades", FORWARD_MIN_COMPLETED_TRADES);
        node.put("orphanTradeRows", scenario.orphanTradeRows());
        node.put("netPnlEquityUnits", scenario.netPnl());
        return node;
    }

    private ObjectNode baseReport(String symbol) {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("tool", "getBtcDonchianShadowReadiness");
        report.put("boundary", "READ_ONLY_SHADOW_EVIDENCE_NO_LIVE_IMPLEMENTATION");
        report.put("policyMode", POLICY_MODE);
        report.put("requestedSymbol", symbol);
        report.put("symbol", SYMBOL);
        report.put("intervalCode", INTERVAL);
        report.put("source", SOURCE);
        report.put("configuredMode", properties.mode().name());
        report.put("runtimeEvidenceEnabled", runtimeEvidenceService.isEnabled());
        report.put("requiredObservationDays", FORWARD_MIN_DAYS);
        report.put("requiredUniqueEntries", FORWARD_MIN_UNIQUE_ENTRIES);
        report.put("requiredCompletedTrades", FORWARD_MIN_COMPLETED_TRADES);
        report.put("orderSent", false);
        report.put("ocoModified", false);
        report.put("telegramSent", false);
        report.set("blockers", objectMapper.createArrayNode());
        return report;
    }

    private String status(boolean goldenPassed,
                          boolean integrityClear,
                          boolean sampleReady,
                          boolean economicsReady,
                          boolean currentEnough,
                          boolean ready) {
        if (!properties.enabled()) return "OFF_NOT_COLLECTING";
        if (!runtimeEvidenceService.isEnabled()) return "BLOCKED_RUNTIME_EVIDENCE_DISABLED";
        if (!goldenPassed) return "BLOCKED_GOLDEN_PARITY_NOT_PROVEN";
        if (!integrityClear) return "FAIL_CLOSED_RUNTIME_EVIDENCE_INVALID";
        if (!sampleReady || !currentEnough) return "PENDING_FORWARD_SHADOW_SAMPLE";
        if (!economicsReady) return "REJECTED_FORWARD_EDGE_NOT_POSITIVE";
        return ready ? "READY_FOR_SHADOW_EVIDENCE_REVIEW_NOT_LIVE" : "BLOCKED_FAIL_CLOSED";
    }

    private String normalizeSymbol(String value) {
        if (value == null || value.isBlank()) return SYMBOL;
        return value.toUpperCase(Locale.ROOT).replace("-", "").replace("/", "").replace("_", "");
    }

    private String value(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private record EvidenceView(
            RuntimeDecisionEvidence row,
            JsonNode root,
            boolean valid,
            LocalDateTime barOpenTime,
            LocalDateTime barCloseTime,
            boolean bootstrap,
            boolean catchUp
    ) {
    }

    private record ScenarioForward(
            String scenario,
            int uniqueEntries,
            long completedTrades,
            long orphanTradeRows,
            BigDecimal netPnl
    ) {
    }
}
