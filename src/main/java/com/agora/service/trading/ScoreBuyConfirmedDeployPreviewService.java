package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ScoreBuyConfirmedDeployPreviewService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final BigDecimal EXCHANGE_MIN_NOTIONAL = new BigDecimal("5.00");
    private static final BigDecimal MIN_TARGET_PCT = new BigDecimal("0.20");
    private static final BigDecimal MAX_TARGET_PCT = new BigDecimal("0.40");
    private static final BigDecimal MAX_FIRST_TRANCHE = new BigDecimal("50.00");

    private final ScoreBuyFormingDayObserverService formingDayObserverService;
    private final ScoreBuyConvictionPreviewService convictionPreviewService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String preview(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        JsonNode observer = readJson(formingDayObserverService.getStatus(sym, sid));
        JsonNode conviction = readJson(convictionPreviewService.preview(sym, sid));

        String state = text(observer, "scoreBuyFormingState", "NONE");
        String holdingState = text(observer, "scoreBuyHoldingState", "UNKNOWN");
        boolean holdBtcMode = observer.path("holdBtcMode").asBoolean(false);
        String eventRisk = text(observer, "eventRiskLevel", "UNKNOWN");
        boolean observerDailyPass = "CONFIRMED_DAILY_SCORE_BUY".equals(state);
        boolean convictionDailyPass = conviction.path("dailyScoreBuyGate").path("dipGatePass").asBoolean(false);
        BigDecimal riskMultiplier = eventRiskMultiplier(eventRisk);

        JsonNode capital = observer.path("capitalSnapshot");
        BigDecimal reserveAwareDeployable = money(capital, "reserveAwareDeployableUsdt", BigDecimal.ZERO);
        BigDecimal scoreBuyReserveTarget = money(capital, "scoreBuyReserveTargetUsdt", BigDecimal.ZERO);
        BigDecimal liquidAfterReserve = money(capital, "liquidAfterReserveUsdt", BigDecimal.ZERO);
        BigDecimal sameThesisExposure = money(observer.path("sameThesisExposure"), "sameThesisExposureUsed", BigDecimal.ZERO);
        BigDecimal entry = money(observer.path("formingDailyFrame"), "close", BigDecimal.ZERO);

        BigDecimal minTarget = reserveAwareDeployable.multiply(MIN_TARGET_PCT).setScale(2, RoundingMode.DOWN);
        BigDecimal maxTarget = reserveAwareDeployable.multiply(MAX_TARGET_PCT).setScale(2, RoundingMode.DOWN);
        BigDecimal target = minPositive(scoreBuyReserveTarget, maxTarget);
        BigDecimal remaining = target.subtract(sameThesisExposure).max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN);
        BigDecimal riskAdjustedRemaining = remaining.multiply(riskMultiplier).setScale(2, RoundingMode.DOWN);

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!observerDailyPass || !convictionDailyPass) {
            blockers.add("DAILY_SCORE_BUY_NOT_CONFIRMED");
            warnings.add("Only scout/pre-position path may be used before confirmed daily SCORE_BUY.");
        }
        if ("INVALIDATED".equals(state)) {
            blockers.add("SCORE_BUY_FORMATION_INVALIDATED");
        }
        if (holdBtcMode || "STRUCTURE_BROKEN_HOLD_BTC".equalsIgnoreCase(holdingState)) {
            blockers.add("SCORE_BUY_HOLD_BTC_MODE_NO_CONFIRMED_DEPLOY");
            warnings.add("Structure-broken HOLD_BTC mode keeps existing BTC but blocks confirmed deploy/adds until re-armed.");
        }
        if ("R3".equals(eventRisk)) {
            blockers.add("EVENT_RISK_R3_BLOCKS_CONFIRMED_LARGE_DEPLOY");
            warnings.add("R3 may scale bounded scout/pre-position only; confirmed larger deploy remains blocked.");
        } else if ("R2".equals(eventRisk)) {
            warnings.add("EVENT_RISK_R2_CONFIRMED_DEPLOY_MULTIPLIER_0_50");
        }
        if (reserveAwareDeployable.compareTo(EXCHANGE_MIN_NOTIONAL) < 0) {
            blockers.add("DEPLOYABLE_CAPITAL_BELOW_EXCHANGE_MIN");
        }
        if (remaining.compareTo(EXCHANGE_MIN_NOTIONAL) < 0) {
            blockers.add("CONFIRMED_DEPLOY_BUDGET_EXHAUSTED_OR_BELOW_MIN");
        }

        ArrayNode candidateTranches = plannedTranches(riskAdjustedRemaining);
        BigDecimal firstTranche = firstTrancheNotional(candidateTranches);
        BigDecimal tp = ScoreBuyRiskPolicy.takeProfit(entry);
        BigDecimal sl = ScoreBuyRiskPolicy.disasterStopLoss(entry);
        BigDecimal maxLoss = ScoreBuyRiskPolicy.maxLossIfWrong(firstTranche, entry, sl);
        if (entry.compareTo(BigDecimal.ZERO) <= 0) {
            blockers.add("ENTRY_PRICE_UNAVAILABLE");
        }
        boolean eligible = blockers.isEmpty() && candidateTranches.size() > 0;

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "previewScoreBuyConfirmedDeploy");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence write behavior changed.");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", sym);
        root.put("strategyId", sid);
        root.put("scoreBuyFormingState", state);
        root.put("scoreBuyHoldingState", holdingState);
        root.put("holdBtcMode", holdBtcMode);
        root.put("holdBtcReason", text(observer, "holdBtcReason", "NONE"));
        root.put("autoSellAllowed", false);
        root.put("autoAddAllowed", eligible
                && !holdBtcMode
                && !"STRUCTURE_BROKEN_HOLD_BTC".equalsIgnoreCase(holdingState));
        root.put("disasterOcoMode", text(observer, "disasterOcoMode", "KEEP_12PCT_HARD_OCO"));
        root.put("dailyScoreBuyConfirmed", observerDailyPass && convictionDailyPass);
        root.put("confirmedDeployEligible", eligible);
        root.put("confirmedDeployPolicy", eligible
                ? "CONFIRMED_DAILY_SCORE_BUY_STAGED_DEPLOY_PREVIEW"
                : "SCOUT_ONLY_OR_WAIT");
        root.put("recommendedExecutionMode", eligible
                ? "CONFIRMED_DAILY_SCORE_BUY_STAGED_DEPLOY_PREVIEW_ONLY"
                : "NO_CONFIRMED_DEPLOY_PREVIEW_ONLY");
        root.put("eventRiskLevel", eventRisk);
        root.put("eventRiskMultiplier", riskMultiplier);
        putMoney(root, "reserveAwareDeployableUsdt", reserveAwareDeployable);
        putMoney(root, "liquidAfterReserveUsdt", liquidAfterReserve);
        putMoney(root, "scoreBuyReserveTargetUsdt", scoreBuyReserveTarget);
        putMoney(root, "sameThesisExposureUsedUsdt", sameThesisExposure);
        putMoney(root, "minimumConfirmedDeployTargetUsdt", minTarget);
        putMoney(root, "maximumConfirmedDeployTargetUsdt", maxTarget);
        putMoney(root, "targetConfirmedDeployUsdt", target);
        putMoney(root, "remainingConfirmedDeployBudgetUsdt", remaining);
        putMoney(root, "riskAdjustedDeployBudgetUsdt", riskAdjustedRemaining);
        putMoney(root, "firstTrancheNotionalUsdt", eligible ? firstTranche : BigDecimal.ZERO);
        putMoney(root, "entry", entry);
        putMoney(root, "tp", tp);
        putMoney(root, "sl", sl);
        ScoreBuyRiskPolicy.putStopLossPolicy(root);
        root.put("maxLossIfWrongUsdt", (eligible ? maxLoss : BigDecimal.ZERO).stripTrailingZeros().toPlainString());
        root.put("ocoPreflightStatus", entry.compareTo(BigDecimal.ZERO) > 0 && tp.compareTo(entry) > 0 && sl.compareTo(entry) < 0
                ? "PASS_PRICE_SHAPE_ONLY_OCO_HEALTH_REQUIRED_BEFORE_WRITE"
                : "NOT_READY_PRICE_SHAPE");
        root.set("plannedTranches", eligible ? candidateTranches : objectMapper.createArrayNode());
        root.set("hypotheticalTranchesIfDailyConfirmed", eligible ? objectMapper.createArrayNode() : candidateTranches);
        root.set("blockers", stringArray(blockers));
        root.set("warnings", stringArray(warnings));
        root.set("requiredWritePathChecks", stringArray(List.of(
                "DAILY_SCORE_BUY_CONFIRMED",
                "PROMOTED_ML_PWIN_ABOVE_BUY_THRESHOLD",
                "OCO_PREFLIGHT_PASS",
                "OCO_HEALTH_OK",
                "RUNTIME_EVIDENCE_AVAILABLE",
                "DATA_FRESHNESS_OK",
                "SYSTEM_HEALTH_OK",
                "EXACT_DUPLICATE_OPPORTUNITY_FALSE",
                "MAX_LOSS_WITHIN_BUDGET",
                "TOTAL_EXPOSURE_CAP_OK",
                "DAILY_AND_WEEKLY_SCORE_BUY_BUDGET_OK",
                "CAPITAL_AND_RESERVE_CONSTRAINTS_OK"
        )));
        root.set("nextRearmConditions", observer.path("nextRearmConditions").isArray()
                ? observer.path("nextRearmConditions").deepCopy()
                : objectMapper.createArrayNode());
        root.put("preConfirmationPolicy", "Before daily confirmation, only EARLY_RECOVERY_SCOUT/PRE_POSITION sizing is allowed.");
        root.put("postConfirmationPolicy", "After daily confirmation, deploy in staged tranches within 20%-40% reserve-aware deployable capital, never all-in.");
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("telegramSent", false);
        root.put("writesRuntimeEvidence", false);
        root.set("observerSummary", observerSummary(observer));
        root.set("convictionSummary", convictionSummary(conviction));
        return write(root);
    }

    private ArrayNode plannedTranches(BigDecimal riskAdjustedRemaining) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (riskAdjustedRemaining.compareTo(EXCHANGE_MIN_NOTIONAL) < 0) {
            return arr;
        }
        BigDecimal first = riskAdjustedRemaining.multiply(new BigDecimal("0.40"))
                .min(MAX_FIRST_TRANCHE)
                .setScale(2, RoundingMode.DOWN);
        if (first.compareTo(EXCHANGE_MIN_NOTIONAL) < 0) {
            first = EXCHANGE_MIN_NOTIONAL.min(riskAdjustedRemaining).setScale(2, RoundingMode.DOWN);
        }
        BigDecimal remaining = riskAdjustedRemaining.subtract(first).max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN);
        BigDecimal second = remaining.multiply(new BigDecimal("0.50")).setScale(2, RoundingMode.DOWN);
        BigDecimal third = remaining.subtract(second).max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN);
        addTranche(arr, 1, "CONFIRMED_DAILY_FIRST_TRANCHE", first,
                "daily confirmed, OCO-capable, runtime evidence present, no exact duplicate");
        addTranche(arr, 2, "FOLLOW_THROUGH_OR_RETEST_TRANCHE", second,
                "only after first tranche is OCO-protected and price structure remains valid");
        addTranche(arr, 3, "FINAL_CONFIRMATION_TRANCHE", third,
                "only if volume/ML/outcome governance remains favorable");
        return arr;
    }

    private void addTranche(ArrayNode arr, int idx, String label, BigDecimal notional, String condition) {
        if (notional.compareTo(EXCHANGE_MIN_NOTIONAL) < 0) {
            return;
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("tranche", idx);
        node.put("label", label);
        putMoney(node, "notionalUsdt", notional);
        node.put("condition", condition);
        arr.add(node);
    }

    private BigDecimal firstTrancheNotional(ArrayNode tranches) {
        if (tranches == null || tranches.isEmpty()) return BigDecimal.ZERO;
        return money(tranches.get(0), "notionalUsdt", BigDecimal.ZERO);
    }

    private ObjectNode observerSummary(JsonNode observer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scoreBuyFormingState", text(observer, "scoreBuyFormingState", "UNKNOWN"));
        node.put("scoreBuyHoldingState", text(observer, "scoreBuyHoldingState", "UNKNOWN"));
        node.put("holdBtcMode", observer.path("holdBtcMode").asBoolean(false));
        node.put("holdBtcReason", text(observer, "holdBtcReason", "UNKNOWN"));
        node.put("disasterOcoMode", text(observer, "disasterOcoMode", "UNKNOWN"));
        node.put("recommendedAction", text(observer, "recommendedAction", "UNKNOWN"));
        node.put("missedOpportunityRisk", text(observer, "missedOpportunityRisk", "UNKNOWN"));
        node.set("strategyDailyGate", observer.path("strategyDailyGate").deepCopy());
        node.set("sameThesisExposure", observer.path("sameThesisExposure").deepCopy());
        return node;
    }

    private ObjectNode convictionSummary(JsonNode conviction) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("conviction", text(conviction, "conviction", "UNKNOWN"));
        node.put("scoreBuyTriggerStatus", text(conviction, "scoreBuyTriggerStatus", "UNKNOWN"));
        node.put("mlGateStatus", text(conviction, "mlGateStatus", "UNKNOWN"));
        node.set("dailyScoreBuyGate", conviction.path("dailyScoreBuyGate").deepCopy());
        node.set("scoreBuySizingPreview", conviction.path("scoreBuySizingPreview").deepCopy());
        return node;
    }

    private BigDecimal minPositive(BigDecimal a, BigDecimal b) {
        if (a == null || a.compareTo(BigDecimal.ZERO) <= 0) return b == null ? BigDecimal.ZERO : b;
        if (b == null || b.compareTo(BigDecimal.ZERO) <= 0) return a;
        return a.min(b);
    }

    private BigDecimal eventRiskMultiplier(String eventRisk) {
        return switch (eventRisk == null ? "" : eventRisk.toUpperCase(Locale.ROOT)) {
            case "R2" -> new BigDecimal("0.50");
            case "R3" -> new BigDecimal("0.25");
            default -> BigDecimal.ONE;
        };
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

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() || value.asText("").isBlank() ? fallback : value.asText();
    }

    private BigDecimal money(JsonNode node, String key, BigDecimal fallback) {
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

    private ArrayNode stringArray(List<String> values) {
        ArrayNode arr = objectMapper.createArrayNode();
        values.stream().distinct().forEach(arr::add);
        return arr;
    }

    private void putMoney(ObjectNode node, String key, BigDecimal value) {
        node.put(key, (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    private String write(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return root.toString();
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
