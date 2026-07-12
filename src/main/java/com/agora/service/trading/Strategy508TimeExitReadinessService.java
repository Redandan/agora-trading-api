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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

        List<RuntimeDecisionEvidence> evidence = evidenceRepository
                .findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(POLICY_MODE, now.minusDays(365));
        List<RuntimeDecisionEvidence> entries = evidence.stream()
                .filter(row -> symbol.equalsIgnoreCase(row.getSymbol()))
                .filter(row -> row.getSelectedAction() != null
                        && row.getSelectedAction().contains("508_TIME_EXIT"))
                .toList();
        LocalDateTime forwardWindowStart = now.minusDays(FORWARD_MIN_DAYS);
        List<RuntimeDecisionEvidence> forwardWindowEntries = entries.stream()
                .filter(row -> row.getEvidenceTime() != null
                        && !row.getEvidenceTime().isBefore(forwardWindowStart))
                .toList();
        List<RuntimeDecisionEvidence> finalized = forwardWindowEntries.stream()
                .filter(row -> isFinalized(row.getFinalOutcome()))
                .toList();
        LocalDateTime firstEvidence = entries.isEmpty() ? null : entries.get(0).getEvidenceTime();
        long observationDays = firstEvidence == null ? 0 : Duration.between(firstEvidence, now).toDays();
        BigDecimal forwardNetPnl = finalized.stream()
                .map(this::netPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long hardGateBlocked = forwardWindowEntries.stream().filter(this::hasHardBlocker).count();
        long parityGapCount = forwardWindowEntries.stream().filter(this::hasParityGap).count();
        boolean forwardReady = observationDays >= FORWARD_MIN_DAYS
                && finalized.size() >= FORWARD_MIN_FINALIZED_EVENTS
                && forwardNetPnl.signum() > 0
                && hardGateBlocked == 0
                && parityGapCount == 0;

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
        BtLiveSignal firstProbeSignal = firstAttempt == null || firstAttempt.getLiveSignalId() == null
                ? null : liveSignals.stream()
                .filter(row -> firstAttempt.getLiveSignalId().equals(row.getId()))
                .findFirst().orElse(null);
        boolean firstProbeVerified = firstAttempt != null
                && firstProbeSignal != null
                && firstProbeSignal.getExitTime() != null
                && isFinalized(firstAttempt.getFinalOutcome())
                && !hasHardBlocker(firstAttempt)
                && !hasParityGap(firstAttempt)
                && hasCompleteFeeEvidence(firstAttempt);
        LocalDateTime firstAttemptTime = firstAttempt == null ? null : firstAttempt.getEvidenceTime();
        boolean pilotExpired = firstAttemptTime != null
                && Duration.between(firstAttemptTime, now).toDays() >= PILOT_MAX_DAYS;
        BigDecimal liveNetPnl = evidence.stream()
                .filter(row -> row.getLiveSignalId() != null)
                .filter(row -> isFinalized(row.getFinalOutcome()))
                .map(this::netPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean lossFuse = liveNetPnl.compareTo(MAX_CUMULATIVE_LOSS_USDT.negate()) <= 0;
        boolean pilotCap = liveAttempts.size() >= MAX_PILOT_ORDERS;

        List<String> blockers = new ArrayList<>();
        if (!supportedSymbol) blockers.add("UNSUPPORTED_SYMBOL");
        if (!properties.enabled()) blockers.add("MODE_OFF");
        if (!historicalReady) blockers.add("HISTORICAL_GATE_NOT_READY");
        if (!forwardReady) blockers.add("FORWARD_SHADOW_GATE_NOT_READY");
        if (!properties.liveMicroArmed()) blockers.add("LIVE_MICRO_NOT_EXPLICITLY_ARMED");
        if (!liveAttempts.isEmpty() && !firstProbeVerified) blockers.add("FIRST_PROBE_EXECUTION_NOT_VERIFIED");
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
        root.put("historicalGatePassed", historicalReady);
        if (historical != null) {
            root.put("historicalVerdict", historical.path("verdict").asText("UNKNOWN"));
            root.put("historicalFinalizedEvents", historical.path("finalizedEvents").asInt(0));
        }
        ObjectNode forward = root.putObject("forwardShadow");
        forward.put("observationDays", observationDays);
        forward.put("allObservedEntryEvents", entries.size());
        forward.put("evaluationWindowDays", FORWARD_MIN_DAYS);
        forward.put("evaluationWindowStartUtc", forwardWindowStart.toString());
        forward.put("entryEvents", forwardWindowEntries.size());
        forward.put("finalizedEvents", finalized.size());
        forward.put("minimumDays", FORWARD_MIN_DAYS);
        forward.put("minimumFinalizedEvents", FORWARD_MIN_FINALIZED_EVENTS);
        forward.put("netPnlUsdt", decimal(forwardNetPnl));
        forward.put("hardGateBlockedEvents", hardGateBlocked);
        forward.put("entryExitParityGapCount", parityGapCount);
        forward.put("gatePassed", forwardReady);
        ObjectNode pilot = root.putObject("livePilot");
        pilot.put("orders", liveSignals.size());
        pilot.put("orderAttempts", liveAttempts.size());
        pilot.put("openPositions", openLive);
        pilot.put("maxOrders", MAX_PILOT_ORDERS);
        pilot.put("maxDays", PILOT_MAX_DAYS);
        pilot.put("netPnlUsdt", decimal(liveNetPnl));
        pilot.put("lossFuseThresholdUsdt", decimal(MAX_CUMULATIVE_LOSS_USDT.negate()));
        pilot.put("lossFuseTriggered", lossFuse);
        pilot.put("pilotExpired", pilotExpired);
        pilot.put("firstProbeAttempted", firstAttempt != null);
        pilot.put("firstProbeVerified", firstProbeVerified);
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

    private boolean isFinalized(String outcome) {
        return "TP_HIT".equals(outcome) || "SL_HIT".equals(outcome)
                || "TIME_EXIT_24H".equals(outcome) || "OCO_TP".equals(outcome)
                || "OCO_SL".equals(outcome);
    }

    private boolean hasHardBlocker(RuntimeDecisionEvidence row) {
        JsonNode node = parse(row.getPolicyInputsJson());
        return node.path("hardBlockers").isArray() && !node.path("hardBlockers").isEmpty();
    }

    private boolean hasParityGap(RuntimeDecisionEvidence row) {
        JsonNode node = parse(row.getPolicyInputsJson());
        return node.path("entryParityGap").asBoolean(false)
                || node.path("exitParityGap").asBoolean(false);
    }

    private boolean hasCompleteFeeEvidence(RuntimeDecisionEvidence row) {
        JsonNode node = parse(row.getPolicyInputsJson());
        JsonNode netPnl = node.path("netPnlUsdt");
        boolean netPnlPresent = netPnl.isNumber()
                || (netPnl.isTextual() && !netPnl.asText().isBlank());
        return node.path("feeCoverageComplete").asBoolean(false) && netPnlPresent;
    }

    private BigDecimal netPnl(RuntimeDecisionEvidence row) {
        JsonNode node = parse(row.getPolicyInputsJson());
        JsonNode value = node.path("netPnlUsdt");
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return BigDecimal.ZERO;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
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
}
