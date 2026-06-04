package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ScoreBuyPrePositionApprovalPreviewService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final BigDecimal FIRST_PHASE_MAX_NOTIONAL = new BigDecimal("25.00");
    private static final BigDecimal DEFAULT_MAX_LOSS_BUDGET = new BigDecimal("2.00");
    private static final BigDecimal MIN_NOTIONAL_BRIDGE_TOLERANCE = new BigDecimal("0.25");

    private final ScoreBuyPrePositionPreviewService prePositionPreviewService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String preview(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        JsonNode preflight = readJson(prePositionPreviewService.preview(sym, sid));

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> requiredWriteChecks = new ArrayList<>();
        copyArray(preflight.path("hardBlockers"), blockers);
        copyArray(preflight.path("warnings"), warnings);
        copyArray(preflight.path("nextRequiredConditions"), requiredWriteChecks);

        String readiness = text(preflight, "prePositionReadiness", "NOT_READY");
        String evidence = text(preflight, "runtimeEvidenceStatus", "UNKNOWN");
        String oco = text(preflight, "ocoPreflightStatus", "UNKNOWN");
        String eventRisk = text(preflight, "eventRiskLevel", "UNKNOWN");
        BigDecimal proposed = decimal(preflight, "proposedNotionalUsdt", BigDecimal.ZERO);
        BigDecimal maxLoss = decimal(preflight, "maxLossIfWrongUsdt", BigDecimal.ZERO);
        boolean canUseMinimum = preflight.path("canUseMinimumOrder").asBoolean(false);

        if (!"READY_FOR_OPERATOR_REVIEW".equals(readiness)) {
            blockers.add("PRE_POSITION_NOT_READY:" + readiness);
        }
        if (!canUseMinimum) {
            blockers.add("EXCHANGE_MINIMUM_ORDER_NOT_FEASIBLE");
        }
        if (proposed.compareTo(BigDecimal.ZERO) <= 0) {
            blockers.add("NO_PROPOSED_PRE_POSITION_NOTIONAL");
        }
        if (proposed.compareTo(FIRST_PHASE_MAX_NOTIONAL) > 0) {
            blockers.add("PROPOSED_NOTIONAL_EXCEEDS_FIRST_PHASE_CAP");
        }
        if (!runtimeEvidenceAvailable(evidence)) {
            blockers.add("RUNTIME_EVIDENCE_NOT_AVAILABLE_FOR_SCORE_BUY");
        }
        if (!oco.startsWith("PASS")) {
            blockers.add("OCO_PREFLIGHT_NOT_PASS");
        }
        if (maxLoss.compareTo(DEFAULT_MAX_LOSS_BUDGET) > 0) {
            blockers.add("MAX_LOSS_EXCEEDS_PRE_POSITION_BUDGET");
        }

        boolean r3 = "R3".equals(eventRisk);
        if (r3) {
            warnings.add("EVENT_RISK_R3_REQUIRES_EXPLICIT_OVERRIDE_FOR_ANY_WRITE_PATH");
        }

        ObjectNode stagedBudget = stagedBudget(preflight, proposed);
        boolean stagedAddAllowed = stagedBudget.path("sameThesisAddAllowed").asBoolean(false);
        if (!stagedAddAllowed) {
            blockers.add("SAME_THESIS_STAGED_ADD_BUDGET_NOT_AVAILABLE");
        }

        boolean hardReady = blockers.isEmpty();
        boolean autoEligible = hardReady && !r3;
        boolean operatorApprovalRequired = hardReady && r3;
        String approvalMode;
        String approvalReason;
        if (!hardReady) {
            approvalMode = "BLOCKED";
            approvalReason = "One or more hard pre-position gates are not ready.";
        } else if (r3) {
            approvalMode = "EVENT_RISK_OVERRIDE_REQUIRED";
            approvalReason = "Pre-position gates pass, but R3 event risk requires explicit event-risk override before any write path.";
        } else {
            approvalMode = "AUTO_APPROVAL_ELIGIBLE_PREVIEW";
            approvalReason = "Bounded SCORE_BUY pre-position gates pass for the internal auto-execution path; this preview remains read-only and sends no order.";
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "previewScoreBuyPrePositionApproval");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence write behavior changed.");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", sym);
        root.put("strategyId", sid);
        root.put("approvalMode", approvalMode);
        root.put("approvalReason", approvalReason);
        root.put("autoApprovalEligible", autoEligible);
        root.put("executionEligible", hardReady);
        root.put("operatorApprovalRequired", operatorApprovalRequired);
        root.put("eventRiskOverrideRequired", hardReady && r3);
        root.put("approvalPath", autoEligible
                ? "INTERNAL_AUTO_EXECUTION_RECHECKS_ALL_GATES"
                : operatorApprovalRequired ? "EXPLICIT_EVENT_RISK_OVERRIDE_REQUIRED" : "BLOCKED");
        root.put("wouldCreateApprovalToken", false);
        root.put("wouldExecute", false);
        root.put("recommendedExecutionMode", "SCORE_BUY_PRE_POSITION_APPROVAL_PREVIEW_ONLY");
        root.put("wouldMissOpportunityRisk", text(preflight, "wouldMissOpportunityRisk", "UNKNOWN"));
        root.put("scoreBuyFormingState", text(preflight, "scoreBuyFormingState", "UNKNOWN"));
        root.put("scoreBuyHoldingState", text(preflight, "scoreBuyHoldingState", "UNKNOWN"));
        root.put("holdBtcMode", preflight.path("holdBtcMode").asBoolean(false));
        root.put("holdBtcReason", text(preflight, "holdBtcReason", "NONE"));
        root.put("autoSellAllowed", false);
        root.put("autoAddAllowed", preflight.path("autoAddAllowed").asBoolean(false) && hardReady);
        root.put("disasterOcoMode", text(preflight, "disasterOcoMode", "KEEP_12PCT_HARD_OCO"));
        root.put("prePositionReadiness", readiness);
        root.put("eventRiskLevel", eventRisk);
        root.put("eventRiskMultiplier", preflight.path("eventRiskMultiplier").asDouble(1.0));
        root.put("runtimeEvidenceStatus", evidence);
        root.put("runtimeEvidenceMode", text(preflight, "runtimeEvidenceMode", "UNKNOWN"));
        root.put("runtimeEvidenceRows", preflight.path("runtimeEvidenceRows").asLong(0));
        root.put("runtimeEvidenceFallbackRows", preflight.path("runtimeEvidenceFallbackRows").asLong(0));
        root.put("ocoPreflightStatus", oco);
        root.put("canUseMinimumOrder", canUseMinimum);
        root.put("proposedNotionalUsdt", proposed.stripTrailingZeros().toPlainString());
        root.put("entry", text(preflight, "entry", "0"));
        root.put("tp", text(preflight, "tp", "0"));
        root.put("sl", text(preflight, "sl", "0"));
        root.put("firstPhaseMaxNotionalUsdt", FIRST_PHASE_MAX_NOTIONAL.toPlainString());
        root.put("maxLossIfWrongUsdt", maxLoss.stripTrailingZeros().toPlainString());
        root.put("maxLossBudgetUsdt", DEFAULT_MAX_LOSS_BUDGET.toPlainString());
        root.set("stagedAddBudgetStatus", stagedBudget);
        root.set("capitalSnapshot", preflight.path("capitalSnapshot").deepCopy());
        root.set("sameThesisExposure", preflight.path("sameThesisExposure").deepCopy());
        root.set("nextRearmConditions", preflight.path("nextRearmConditions").isArray()
                ? preflight.path("nextRearmConditions").deepCopy()
                : objectMapper.createArrayNode());
        root.set("blockers", stringArray(blockers));
        root.set("warnings", stringArray(warnings));
        root.set("requiredWritePathChecks", stringArray(requiredWriteChecks));
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("telegramSent", false);
        root.put("writesRuntimeEvidence", false);
        return write(root);
    }

    private ObjectNode stagedBudget(JsonNode preflight, BigDecimal proposed) {
        JsonNode exposure = preflight.path("sameThesisExposure");
        BigDecimal used = decimal(exposure, "sameThesisExposureUsed", BigDecimal.ZERO);
        BigDecimal limit = decimal(exposure, "sameThesisExposureLimit", BigDecimal.ZERO);
        BigDecimal remaining = decimal(preflight, "proposedNotionalUsdt", BigDecimal.ZERO);
        BigDecimal explicitRemaining = decimal(preflight, "remainingPrePositionBudget", BigDecimal.valueOf(-1));
        if (explicitRemaining.signum() >= 0) {
            remaining = explicitRemaining;
        } else if (limit.compareTo(BigDecimal.ZERO) > 0) {
            remaining = limit.subtract(used).max(BigDecimal.ZERO);
        }

        ObjectNode node = objectMapper.createObjectNode();
        boolean bridgeApplied = preflight.path("minimumNotionalBridgeApplied").asBoolean(false);
        boolean addAllowed = proposed.compareTo(BigDecimal.ZERO) > 0
                && (remaining.compareTo(proposed) >= 0
                || (bridgeApplied && remaining.add(MIN_NOTIONAL_BRIDGE_TOLERANCE).compareTo(proposed) >= 0));
        node.put("sameThesisExposureUsed", used.stripTrailingZeros().toPlainString());
        node.put("sameThesisExposureLimit", limit.stripTrailingZeros().toPlainString());
        node.put("remainingPrePositionBudget", remaining.stripTrailingZeros().toPlainString());
        node.put("proposedNotionalUsdt", proposed.stripTrailingZeros().toPlainString());
        node.put("exactDuplicateOpportunityMustRemainBlocked", true);
        node.put("sameThesisAddAllowed", addAllowed);
        node.put("minimumNotionalBridgeApplied", bridgeApplied);
        node.put("minimumNotionalBridgeToleranceUsdt", MIN_NOTIONAL_BRIDGE_TOLERANCE.toPlainString());
        node.put("sameThesisBudgetOverrunUsdt",
                bridgeApplied && proposed.compareTo(remaining) > 0
                        ? proposed.subtract(remaining).stripTrailingZeros().toPlainString()
                        : "0");
        node.put("policy", "EXACT_DUPLICATE_BLOCKS; existing same-thesis exposure only blocks when staged budget is exhausted; min-notional bridge allows a tiny tolerance gap only.");
        return node;
    }

    private boolean runtimeEvidenceAvailable(String status) {
        return status != null
                && (status.startsWith("AVAILABLE_CANONICAL")
                || status.startsWith("AVAILABLE_FALLBACK_SCORE_BUY"));
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("parseError", e.getMessage());
            return node;
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() || value.asText("").isBlank() ? fallback : value.asText();
    }

    private BigDecimal decimal(JsonNode node, String key, BigDecimal fallback) {
        JsonNode value = node.path(key);
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.asText());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private void copyArray(JsonNode array, List<String> target) {
        if (!array.isArray()) return;
        for (JsonNode value : array) {
            if (!value.asText("").isBlank()) {
                target.add(value.asText());
            }
        }
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.stream().distinct().forEach(array::add);
        return array;
    }

    private String write(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return root.toString();
        }
    }
}
