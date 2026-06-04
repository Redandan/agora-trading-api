package com.agora.service.trading;

import com.agora.mcp.PositionMcpTools;
import com.agora.mcp.SignalCorrectnessMcpTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AutonomousExplorationMonitorService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 574L;
    private static final String DEFAULT_SIDE = "LONG";
    private static final String SIGNAL_NEAR_BUY_THRESHOLD = "SIGNAL_NEAR_BUY_THRESHOLD";

    private final ExplorationPolicyService explorationPolicyService;
    private final TinyLiveExecutionService tinyLiveExecutionService;
    private final PositionMcpTools positionMcpTools;
    private final ObjectProvider<SignalCorrectnessMcpTools> signalCorrectnessMcpToolsProvider;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Report evaluate(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);

        ExplorationPolicyService.Decision readiness = explorationPolicyService.evaluate(sym, sid, normalizedSide);
        String candidatePreview = readiness.renderCandidatePreview();
        String tinyLiveExecutions = tinyLiveExecutionService.listExecutions(sym, 7 * 24 * 60, 5);
        String ocoHealth = safe("ocoHealth", positionMcpTools::getOcoHealth);
        SignalCorrectnessMcpTools signalCorrectnessMcpTools = signalCorrectnessMcpToolsProvider.getObject();
        String outcomeStatus = safe("outcomeLabeler", () ->
                signalCorrectnessMcpTools.getSignalOutcomeLabelerStatus(sym, 24, "1h", false));
        String governanceDrift = safe("governanceDrift", () ->
                signalCorrectnessMcpTools.getGovernanceDriftDashboard(sym, 7, "1h"));
        String relaxation = safe("relaxationCandidates", () ->
                signalCorrectnessMcpTools.findGovernanceRelaxationCandidates(sym, 7, "1h"));
        String tightening = safe("tighteningCandidates", () ->
                signalCorrectnessMcpTools.findGovernanceTighteningCandidates(sym, 7, "1h"));

        List<String> blockers = new ArrayList<>(readiness.blockers());
        List<String> warnings = new ArrayList<>(readiness.warnings());

        boolean ocoAbnormal = ocoHealth.contains("[SYNC_ERROR")
                || ocoHealth.contains("[UNPROTECTED]")
                || ocoHealth.contains("CRITICAL_UNPROTECTED")
                || containsNonZeroTail(ocoHealth, "SYNC_ERROR")
                || containsNonZeroTail(ocoHealth, "異常");
        if (ocoAbnormal) {
            blockers.add("OCO_HEALTH_ABNORMAL");
        }

        String governanceMode = value(governanceDrift, "governanceMode");
        if ("TOO_STRICT".equals(governanceMode)) {
            warnings.add("GOVERNANCE_TOO_STRICT");
        }
        if ("TOO_LOOSE".equals(governanceMode)) {
            warnings.add("GOVERNANCE_TOO_LOOSE");
        }
        if (isNearBuyThreshold(readiness)) {
            warnings.add(SIGNAL_NEAR_BUY_THRESHOLD);
        }

        int unresolved = intValue(outcomeStatus, "unresolvedCandidates");
        if (unresolved > 0) {
            warnings.add("OUTCOME_LABELS_UNRESOLVED=" + unresolved);
        }

        String monitorStatus = monitorStatus(readiness, blockers, warnings, ocoAbnormal, governanceMode, unresolved);
        String nextAction = nextAction(monitorStatus, readiness);
        String recommendation = recommendation(monitorStatus, relaxation, tightening);

        return new Report(
                Instant.now(),
                monitorStatus,
                nextAction,
                blockers.stream().distinct().toList(),
                warnings.stream().distinct().toList(),
                lastExecutionSummary(tinyLiveExecutions),
                compactOco(ocoHealth),
                readinessSummary(readiness),
                outcomeSummary(outcomeStatus),
                governanceSummary(governanceDrift, relaxation, tightening),
                recommendation,
                false,
                candidatePreview,
                tinyLiveExecutions,
                ocoHealth,
                outcomeStatus,
                governanceDrift);
    }

    @Transactional(readOnly = true)
    public String getAutonomousExplorationMonitorStatus(String symbol, Long strategyId, String side) {
        return evaluate(symbol, strategyId, side).render();
    }

    private String monitorStatus(ExplorationPolicyService.Decision readiness,
                                 List<String> blockers,
                                 List<String> warnings,
                                 boolean ocoAbnormal,
                                 String governanceMode,
                                 int unresolved) {
        if (blockers.stream().anyMatch(b -> b.contains("SYSTEM_HEALTH_CRITICAL"))
                || blockers.stream().anyMatch(b -> b.contains("CRITICAL_UNPROTECTED"))) {
            return "ERROR_NEEDS_OPERATOR";
        }
        if (ocoAbnormal || blockers.contains("OCO_PREFLIGHT_FAIL")) {
            return "WAIT_OCO_HEALTH";
        }
        if (blockers.contains("OPEN_TINY_LIVE_POSITION")) {
            return "WAIT_OPEN_POSITION";
        }
        if (blockers.contains("DAILY_EXPLORATION_CAP_REACHED")) {
            return "WAIT_DAILY_CAP_RESET";
        }
        if (blockers.contains("NO_CURRENT_BUY_CANDIDATE")) {
            if (isNearBuyThreshold(readiness)) {
                return "WATCH_SIGNAL_NEAR_BUY_THRESHOLD";
            }
            return "WAIT_SIGNAL_BUY";
        }
        if (blockers.contains("EV_SAMPLE_MISSING") || blockers.contains("EV_FAIL")) {
            return "WAIT_EV_PASS";
        }
        if (unresolved > 0) {
            return "WAIT_OUTCOME_MATURITY";
        }
        if ("TOO_STRICT".equals(governanceMode)) {
            return "WATCH_GOVERNANCE_TOO_STRICT";
        }
        if ("TOO_LOOSE".equals(governanceMode)) {
            return "WATCH_GOVERNANCE_TOO_LOOSE";
        }
        if (readiness.eligible()) {
            return "READY_TO_EXPLORE";
        }
        if (!blockers.isEmpty()) {
            return "ERROR_NEEDS_OPERATOR";
        }
        return "WAIT_OUTCOME_MATURITY";
    }

    private String nextAction(String status, ExplorationPolicyService.Decision readiness) {
        return switch (status) {
            case "READY_TO_EXPLORE" -> "Let existing AutoApprovalPolicy/execution path decide; monitor does not place orders.";
            case "WAIT_OPEN_POSITION" -> "Wait for the current tiny-live position to close and OCO outcome to reconcile.";
            case "WAIT_DAILY_CAP_RESET" -> "Wait for daily exploration cap reset.";
            case "WATCH_SIGNAL_NEAR_BUY_THRESHOLD" -> "Keep high-frequency observation active; wait for BUY threshold cross before any tiny-live execution.";
            case "WAIT_SIGNAL_BUY" -> "Wait for a current BUY candidate before evaluating tiny-live execution.";
            case "WAIT_EV_PASS" -> "Wait for next candidate with EV pass.";
            case "WAIT_OCO_HEALTH" -> "Operator should inspect OCO health before any exploration.";
            case "WAIT_OUTCOME_MATURITY" -> "Wait for outcome labels to mature or inspect labeler unresolved reasons.";
            case "WATCH_GOVERNANCE_TOO_STRICT" -> "Review relaxation candidates; do not auto-relax in v0.";
            case "WATCH_GOVERNANCE_TOO_LOOSE" -> "Review tightening candidates; do not auto-tighten in v0.";
            default -> "Operator review required. Current exploration mode=" + readiness.explorationMode();
        };
    }

    private String recommendation(String status, String relaxation, String tightening) {
        if ("WATCH_GOVERNANCE_TOO_STRICT".equals(status)) {
            return firstMeaningfulLine(relaxation);
        }
        if ("WATCH_GOVERNANCE_TOO_LOOSE".equals(status)) {
            return firstMeaningfulLine(tightening);
        }
        return "No automatic trading/OCO/strategy/grid/fund action is performed by this monitor.";
    }

    private boolean isNearBuyThreshold(ExplorationPolicyService.Decision readiness) {
        if (readiness == null || readiness.warnings() == null) {
            return false;
        }
        return readiness.warnings().stream().anyMatch(w ->
                containsText(w, "signalProximityState=NEAR_BUY_THRESHOLD")
                        || containsText(w, "signalProximityState=NEAR_BUY_BELOW_THRESHOLD")
                        || containsText(w, "nextRequiredAction=WAIT_BUY_THRESHOLD_CROSS"));
    }

    private boolean containsText(String value, String needle) {
        return value != null && needle != null
                && value.toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
    }

    private String lastExecutionSummary(String executions) {
        if (executions == null || executions.contains("No tiny-live execution audit rows found")) {
            return "No recent tiny-live execution audit rows found.";
        }
        return executions.lines()
                .filter(line -> line.trim().startsWith("1. #"))
                .findFirst()
                .orElse("Tiny-live execution history present; no latest row parsed.");
    }

    private String readinessSummary(ExplorationPolicyService.Decision readiness) {
        return "explorationMode=%s eligible=%s blockers=%s warnings=%s learningValue=%s budgetRemaining=%s ordersToday=%d openTinyLivePositions=%d openTinyLiveWait=%s"
                .formatted(readiness.explorationMode(), readiness.eligible(), readiness.blockers(), readiness.warnings(),
                        readiness.expectedLearningValue(), readiness.explorationBudgetRemaining(),
                        readiness.ordersToday(), readiness.openTinyLivePositions(),
                        readiness.openTinyLiveWait().renderCompact());
    }

    private String outcomeSummary(String text) {
        return "labelCoveragePct=%s matureLabelCoveragePct=%s actionableCandidates=%s labeledCandidates=%s unresolvedCandidates=%s pendingForwardWindowCount=%s rowsByCorrectnessLabel=%s"
                .formatted(value(text, "labelCoveragePct"), value(text, "matureLabelCoveragePct"),
                        value(text, "actionableCandidates"), value(text, "labeledCandidates"), value(text, "unresolvedCandidates"),
                        value(text, "pendingForwardWindowCount"),
                        value(text, "rowsByCorrectnessLabel"));
    }

    private String governanceSummary(String drift, String relaxation, String tightening) {
        return "governanceMode=%s falseBlockRate=%s falsePositiveRate=%s relaxation=%s tightening=%s"
                .formatted(value(drift, "governanceMode"), value(drift, "falseBlockRate"),
                        value(drift, "falsePositiveRate"), firstMeaningfulLine(relaxation),
                        firstMeaningfulLine(tightening));
    }

    private String compactOco(String text) {
        if (text == null) {
            return "N/A";
        }
        return text.lines()
                .filter(line -> line.contains("OK |") || line.contains("無開倉") || line.contains("SYNC_ERROR") || line.contains("UNPROTECTED"))
                .reduce((a, b) -> b)
                .orElse(text.lines().findFirst().orElse("N/A"));
    }

    private boolean containsNonZeroTail(String text, String label) {
        if (text == null) {
            return false;
        }
        Pattern pattern = Pattern.compile("(\\d+)\\s+" + Pattern.quote(label));
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) > 0) {
                return true;
            }
        }
        return false;
    }

    private String value(String text, String key) {
        if (text == null) {
            return "N/A";
        }
        Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(key) + "=([^\\n]+)");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "N/A";
    }

    private int intValue(String text, String key) {
        String value = value(text, key);
        try {
            return Integer.parseInt(value.replaceAll("[^0-9-]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private String firstMeaningfulLine(String text) {
        if (text == null || text.isBlank()) {
            return "N/A";
        }
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("==="))
                .filter(line -> !line.startsWith("boundary"))
                .filter(line -> !line.startsWith("symbol="))
                .filter(line -> !line.startsWith("criteria:"))
                .findFirst()
                .orElse("N/A");
    }

    private String safe(String section, SupplierWithException supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return section + "Error=" + e.getMessage();
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) return DEFAULT_SIDE;
        String upper = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(upper) ? DEFAULT_SIDE : upper;
    }

    public record Report(Instant generatedAt,
                         String monitorStatus,
                         String nextRecommendedAction,
                         List<String> blockers,
                         List<String> warnings,
                         String lastTinyLiveExecution,
                         String ocoHealthSummary,
                         String explorationReadinessSummary,
                         String outcomeLabelSummary,
                         String governanceDriftSummary,
                         String recommendationSummary,
                         boolean orderSent,
                         String explorationCandidatePreview,
                         String tinyLiveExecutionRaw,
                         String ocoHealthRaw,
                         String outcomeLabelRaw,
                         String governanceDriftRaw) {
        public String render() {
            return """
                    === Autonomous Exploration Monitor v0 ===
                    boundary=READ_ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.
                    generatedAt=%s
                    monitorStatus=%s
                    nextRecommendedAction=%s
                    blockers=%s
                    warnings=%s
                    lastTinyLiveExecution=%s
                    ocoHealthSummary=%s
                    explorationReadinessSummary=%s
                    outcomeLabelSummary=%s
                    governanceDriftSummary=%s
                    recommendationSummary=%s
                    orderSent=false
                    """.formatted(generatedAt, monitorStatus, nextRecommendedAction, blockers, warnings,
                    lastTinyLiveExecution, ocoHealthSummary, explorationReadinessSummary,
                    outcomeLabelSummary, governanceDriftSummary, recommendationSummary);
        }

        public String fingerprint() {
            return monitorStatus + "|" + blockers + "|" + warnings + "|" + lastTinyLiveExecution + "|" + ocoHealthSummary;
        }

        public String notificationMessage() {
            ObjectNode node = new ObjectMapper().createObjectNode();
            node.put("monitorStatus", monitorStatus);
            node.put("nextRecommendedAction", nextRecommendedAction);
            node.put("blockers", String.valueOf(blockers));
            node.put("warnings", String.valueOf(warnings));
            node.put("lastTinyLiveExecution", lastTinyLiveExecution);
            node.put("ocoHealthSummary", ocoHealthSummary);
            return "Autonomous Exploration Monitor state changed\n" + node;
        }
    }

    @FunctionalInterface
    private interface SupplierWithException {
        String get() throws Exception;
    }
}
