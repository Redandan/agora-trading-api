package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ScoreBuyPostScoutManagementPolicyService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final String SIDE = "LONG";
    private static final BigDecimal EXCHANGE_MIN_NOTIONAL = new BigDecimal("5.00");
    private static final BigDecimal MAX_PARTIAL_REVERSAL_PERSISTENCE_ADD_NOTIONAL = new BigDecimal("5.00");
    private static final BigDecimal MAX_PULLBACK_ADD_NOTIONAL = new BigDecimal("15.00");
    private static final BigDecimal MAX_CONFIRMATION_ADD_NOTIONAL = new BigDecimal("25.00");
    private static final BigDecimal MIN_NOTIONAL_BRIDGE_TOLERANCE = new BigDecimal("0.25");
    private static final BigDecimal PARTIAL_REVERSAL_PERSISTENCE_ADD_PCT = new BigDecimal("0.015");
    private static final BigDecimal PULLBACK_ADD_PCT = new BigDecimal("0.03");
    private static final BigDecimal CONFIRMATION_ADD_PCT = new BigDecimal("0.05");
    private static final BigDecimal PULLBACK_AVG_ENTRY_MULTIPLIER = new BigDecimal("1.005");
    private static final BigDecimal CONFIRMATION_AVG_ENTRY_MULTIPLIER = new BigDecimal("1.003");
    private static final BigDecimal POST_SCOUT_DEPLOYABLE_BUDGET_PCT = new BigDecimal("0.20");
    private static final BigDecimal POST_SCOUT_OBSERVED_CAPITAL_BUDGET_PCT = new BigDecimal("0.12");
    private static final BigDecimal POST_SCOUT_SCORE_BUY_RESERVE_BUDGET_PCT = new BigDecimal("0.40");
    private static final BigDecimal MAX_POST_SCOUT_TOTAL_BUDGET_USDT = new BigDecimal("125.00");
    private static final int BASE_MAX_OPEN_SAME_THESIS_POSITIONS = 8;
    private static final int MAX_BUDGET_AWARE_OPEN_SAME_THESIS_POSITIONS = 16;
    private static final double ONE_HOUR_RSI_OVERHEAT_THRESHOLD = 65.0;
    private static final double FIFTEEN_MINUTE_RSI_OVERHEAT_THRESHOLD = 65.0;
    private static final double FIFTEEN_MINUTE_COOLDOWN_RSI_MAX = 58.0;
    private static final double REBOUND_OVEREXTENDED_PCT_MAX = 4.5;
    private static final double REBOUND_COOLDOWN_PCT_MAX = 4.2;
    private static final double FORMING_COOLDOWN_RSI_MAX = 50.0;
    private static final double FORMING_CONFIRMATION_RSI_MAX = 55.0;

    private final ScoreBuyFormingDayObserverService formingDayObserverService;
    private final ScoreBuyPrePositionExecutionPolicyPreviewService prePositionExecutionPolicyPreviewService;
    private final BtLiveSignalRepository liveSignalRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String getStatus(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        JsonNode observer = readJson(formingDayObserverService.getStatus(sym, sid));
        JsonNode prePosition = readJson(prePositionExecutionPolicyPreviewService.preview(sym, sid));
        List<BtLiveSignal> openScouts = openScoutPositions(sym, sid);

        BigDecimal mark = money(observer.path("formingDailyFrame"), "close",
                money(prePosition, "entry", BigDecimal.ZERO));
        PositionSummary positions = summarize(openScouts, mark);

        List<String> hardBlockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean softPostScoutInvalidation = softPostScoutInvalidation(observer, !openScouts.isEmpty());
        copyObserverHardBlockers(observer, hardBlockers, warnings, softPostScoutInvalidation);
        copyArray(prePosition.path("warnings"), warnings);

        String formingState = text(observer, "scoreBuyFormingState", "UNKNOWN");
        String holdingState = text(observer, "scoreBuyHoldingState", "UNKNOWN");
        boolean holdBtcMode = observer.path("holdBtcMode").asBoolean(false)
                || (!openScouts.isEmpty() && "INVALIDATED".equalsIgnoreCase(formingState));
        if (holdBtcMode) {
            hardBlockers.add("SCORE_BUY_HOLD_BTC_MODE_NO_AUTO_ADD");
            warnings.add("STRUCTURE_BROKEN_HOLD_BTC_NO_AUTO_SELL_KEEP_DISASTER_OCO");
        }
        String eventRisk = text(observer, "eventRiskLevel", "UNKNOWN");
        BigDecimal riskMultiplier = eventRiskMultiplier(eventRisk);
        boolean r3 = "R3".equalsIgnoreCase(eventRisk);
        if ("INVALIDATED".equalsIgnoreCase(formingState) && !softPostScoutInvalidation) {
            hardBlockers.add("SCORE_BUY_FORMATION_INVALIDATED");
        } else if ("INVALIDATED".equalsIgnoreCase(formingState)) {
            warnings.add("SCORE_BUY_FORMATION_INVALIDATED_PRE_POSITION_ONLY_POST_SCOUT_ALLOWED_IF_READY");
        }
        if (!runtimeEvidenceAvailable(text(prePosition, "runtimeEvidenceStatus", "UNKNOWN"))) {
            hardBlockers.add("RUNTIME_EVIDENCE_NOT_AVAILABLE");
        }
        if (!startsWith(text(prePosition, "ocoPreflightStatus", "UNKNOWN"), "PASS")) {
            hardBlockers.add("OCO_PREFLIGHT_NOT_PASS");
        }
        if (prePosition.path("exactDuplicateOpportunity").asBoolean(false)) {
            hardBlockers.add("EXACT_DUPLICATE_OPPORTUNITY");
        }
        BigDecimal deployable = money(observer.path("capitalSnapshot"), "reserveAwareDeployableUsdt",
                money(observer.path("capitalSnapshot"), "deployableTradingUsdt", BigDecimal.ZERO));
        BudgetPlan budget = postScoutBudget(observer, deployable);
        BigDecimal remainingBudget = budget.remainingPostScoutAddBudget();
        int effectiveMaxOpenPositions = effectiveMaxOpenSameThesisPositions(budget.postScoutAddBudgetLimit());
        if (positions.openCount() >= effectiveMaxOpenPositions) {
            hardBlockers.add("SAME_THESIS_OPEN_POSITION_LIMIT_REACHED");
        }
        boolean minNotionalBridgeAvailable = budgetSupportsMinNotionalBridge(remainingBudget, deployable);
        if (remainingBudget.compareTo(EXCHANGE_MIN_NOTIONAL) < 0 && !minNotionalBridgeAvailable) {
            hardBlockers.add("POST_SCOUT_STAGED_ADD_BUDGET_BELOW_EXCHANGE_MIN");
        }
        if (deployable.compareTo(EXCHANGE_MIN_NOTIONAL) < 0) {
            hardBlockers.add("DEPLOYABLE_CAPITAL_BELOW_EXCHANGE_MIN");
        }
        if (r3) {
            warnings.add("EVENT_RISK_R3_SCALES_POST_SCOUT_ADD_ONLY; no confirmed large deploy allowed.");
        } else if ("R2".equalsIgnoreCase(eventRisk)) {
            warnings.add("EVENT_RISK_R2_POST_SCOUT_ADD_MULTIPLIER_0_50");
        }

        MarketReadiness market = marketReadiness(observer, positions);
        Decision decision = decide(openScouts, formingState, hardBlockers, market, deployable, remainingBudget, riskMultiplier);
        BigDecimal suggested = suggestedNotional(decision.addOnType(), deployable, remainingBudget, riskMultiplier);
        if (!decision.addOnEligible()) {
            suggested = BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);
        }
        BigDecimal entry = mark.max(BigDecimal.ZERO);
        BigDecimal qty = entry.compareTo(BigDecimal.ZERO) > 0
                ? suggested.divide(entry, 8, RoundingMode.DOWN)
                : BigDecimal.ZERO;
        BigDecimal tp = ScoreBuyRiskPolicy.takeProfit(entry);
        BigDecimal sl = ScoreBuyRiskPolicy.disasterStopLoss(entry);
        BigDecimal maxLoss = ScoreBuyRiskPolicy.maxLossIfWrong(suggested, entry, sl);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "getScoreBuyPostScoutManagementStatus");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence write behavior changed.");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", sym);
        root.put("strategyId", sid);
        root.put("side", SIDE);
        root.put("postScoutManagementState", decision.state());
        root.put("recommendedAction", decision.recommendedAction());
        root.put("addOnEligible", decision.addOnEligible());
        root.put("addOnType", decision.addOnType());
        root.put("reason", decision.reason());
        root.put("scoreBuyFormingState", formingState);
        root.put("scoreBuyHoldingState", holdBtcMode ? "STRUCTURE_BROKEN_HOLD_BTC" : holdingState);
        root.put("holdBtcMode", holdBtcMode);
        root.put("holdBtcReason", holdBtcMode
                ? text(observer, "holdBtcReason", "Structure broken; keep existing BTC, do not auto-sell, do not add exposure.")
                : text(observer, "holdBtcReason", "NONE"));
        root.put("autoSellAllowed", false);
        root.put("autoAddAllowed", decision.addOnEligible() && !holdBtcMode);
        root.put("disasterOcoMode", text(observer, "disasterOcoMode", "KEEP_12PCT_HARD_OCO"));
        root.put("formationInvalidationReason", text(observer, "invalidationReason", "NONE"));
        root.put("formationInvalidationScope", holdBtcMode
                ? "STRUCTURE_BROKEN_HOLD_BTC_NO_AUTO_ADD"
                : softPostScoutInvalidation
                ? "PRE_POSITION_ONLY_POST_SCOUT_SOFT"
                : ("INVALIDATED".equalsIgnoreCase(formingState) ? "POST_SCOUT_HARD_BLOCK" : "NONE"));
        root.put("eventRiskLevel", eventRisk);
        root.put("eventRiskMultiplier", riskMultiplier);
        putMoney(root, "markPrice", mark);
        putMoney(root, "suggestedAddNotionalUsdt", suggested);
        root.put("suggestedAddQty", qty.stripTrailingZeros().toPlainString());
        putMoney(root, "entry", entry);
        putMoney(root, "tp", tp);
        putMoney(root, "sl", sl);
        ScoreBuyRiskPolicy.putStopLossPolicy(root);
        root.put("maxLossIfWrongUsdt", maxLoss.stripTrailingZeros().toPlainString());
        putMoney(root, "remainingSameThesisBudgetUsdt", remainingBudget);
        putMoney(root, "prePositionBudgetLimitUsdt", budget.prePositionBudgetLimit());
        putMoney(root, "postScoutAddBudgetLimitUsdt", budget.postScoutAddBudgetLimit());
        putMoney(root, "remainingPostScoutAddBudgetUsdt", budget.remainingPostScoutAddBudget());
        putMoney(root, "postScoutDeployableBudgetCapUsdt", budget.deployableBudgetCap());
        putMoney(root, "postScoutObservedCapitalBudgetCapUsdt", budget.observedCapitalBudgetCap());
        putMoney(root, "postScoutScoreBuyReserveBudgetCapUsdt", budget.scoreBuyReserveBudgetCap());
        putMoney(root, "postScoutMaxBudgetCapUsdt", MAX_POST_SCOUT_TOTAL_BUDGET_USDT);
        root.put("minimumNotionalBridgeAvailable", minNotionalBridgeAvailable);
        root.put("minimumNotionalBridgeApplied",
                suggested.compareTo(EXCHANGE_MIN_NOTIONAL) == 0
                        && remainingBudget.compareTo(EXCHANGE_MIN_NOTIONAL) < 0
                        && minNotionalBridgeAvailable);
        putMoney(root, "minimumNotionalBridgeToleranceUsdt", MIN_NOTIONAL_BRIDGE_TOLERANCE);
        putMoney(root, "sameThesisBudgetOverrunUsdt",
                suggested.compareTo(remainingBudget) > 0 ? suggested.subtract(remainingBudget) : BigDecimal.ZERO);
        putMoney(root, "reserveAwareDeployableUsdt", deployable);
        root.put("baseMaxOpenSameThesisPositions", BASE_MAX_OPEN_SAME_THESIS_POSITIONS);
        root.put("effectiveMaxOpenSameThesisPositions", effectiveMaxOpenPositions);
        root.put("openPositionCapacityRemaining", Math.max(0, effectiveMaxOpenPositions - positions.openCount()));
        root.set("postScoutRules", rulesJson(effectiveMaxOpenPositions));
        root.put("postScoutBudgetPolicy",
                "post-scout add uses an independent bounded budget: max(pre-position limit, min(max(20% reserve-aware deployable USDT, 12% observed capital, 40% SCORE_BUY reserve target), 125 USDT)); it does not reuse the pre-position-only 50 USDT cap.");
        root.set("scoutPositionSummary", positions.toJson(objectMapper));
        root.set("marketReadiness", market.toJson(objectMapper));
        root.set("nextTriggerSummary", nextTriggerSummary(decision, market, positions, mark, hardBlockers,
                remainingBudget, deployable, effectiveMaxOpenPositions, minNotionalBridgeAvailable, formingState));
        root.set("sameThesisExposure", postScoutSameThesisExposure(observer, budget));
        root.set("capitalSnapshot", observer.path("capitalSnapshot").deepCopy());
        root.set("blockers", stringArray(distinct(hardBlockers)));
        root.set("warnings", stringArray(distinct(warnings)));
        root.set("requiredWritePathChecks", stringArray(List.of(
                "OCO_PREFLIGHT_PASS",
                "OCO_HEALTH_OK",
                "RUNTIME_EVIDENCE_AVAILABLE",
                "DATA_FRESHNESS_OK",
                "SYSTEM_HEALTH_OK",
                "EXACT_DUPLICATE_OPPORTUNITY_FALSE",
                "SAME_THESIS_STAGED_ADD_BUDGET_OK",
                "DAILY_AND_WEEKLY_SCORE_BUY_BUDGET_OK",
                "MAX_LOSS_WITHIN_BUDGET",
                "CAPITAL_AND_RESERVE_CONSTRAINTS_OK"
        )));
        root.set("nextRearmConditions", observer.path("nextRearmConditions").isArray()
                ? observer.path("nextRearmConditions").deepCopy()
                : stringArray(List.of(
                "DAILY_SCORE_BUY_RECONFIRMED",
                "FORMING_DAY_INVALIDATION_CLEARED",
                "OCO_HEALTH_OK",
                "RUNTIME_EVIDENCE_AVAILABLE",
                "OPERATOR_CAN_REVIEW_REARM_BEFORE_NEW_ADD"
        )));
        root.set("observerSummary", observerSummary(observer));
        root.set("prePositionExecutionSummary", prePositionSummary(prePosition));
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("telegramSent", false);
        root.put("writesRuntimeEvidence", false);
        return write(root);
    }

    private Decision decide(List<BtLiveSignal> openScouts,
                            String formingState,
                            List<String> hardBlockers,
                            MarketReadiness market,
                            BigDecimal deployable,
                            BigDecimal remainingBudget,
                            BigDecimal riskMultiplier) {
        if (openScouts.isEmpty()) {
            return new Decision("NO_OPEN_SCOUT", "NO_ACTION_NO_OPEN_SCOUT", false, "NONE",
                    "No #485 same-thesis scout/pre-position is currently open.");
        }
        if (containsAny(hardBlockers, "SCORE_BUY_HOLD_BTC_MODE_NO_AUTO_ADD")) {
            return new Decision("STRUCTURE_BROKEN_HOLD_BTC", "HOLD_BTC_NO_AUTO_SELL_NO_MORE_ADD_KEEP_DISASTER_OCO", false, "NONE",
                    "SCORE_BUY structure is broken; keep existing BTC position, keep disaster OCO, and stop automatic adds until re-armed.");
        }
        if (containsAny(hardBlockers, "SCORE_BUY_FORMATION_INVALIDATED")) {
            return new Decision("SCOUT_INVALIDATED_PROTECT", "NO_ADD_MONITOR_OCO_OR_REDUCE_RISK_MANUALLY", false, "NONE",
                    "The forming setup is invalidated; do not add exposure.");
        }
        if (!hardBlockers.isEmpty()) {
            return new Decision("HOLD_SCOUT_HARD_BLOCKED", "HOLD_EXISTING_SCOUT_RECHECK_HARD_GATES", false, "NONE",
                    "Post-scout add is blocked by hard safety/readiness gates.");
        }
        if (deployable.compareTo(EXCHANGE_MIN_NOTIONAL) < 0
                || (remainingBudget.compareTo(EXCHANGE_MIN_NOTIONAL) < 0
                && !budgetSupportsMinNotionalBridge(remainingBudget, deployable))
                || riskMultiplier.compareTo(BigDecimal.ZERO) <= 0) {
            return new Decision("HOLD_SCOUT_BUDGET_NOT_AVAILABLE", "HOLD_EXISTING_SCOUT_NO_ADD_BUDGET", false, "NONE",
                    "Staged add budget is below exchange minimum.");
        }
        if ("CONFIRMED_DAILY_SCORE_BUY".equalsIgnoreCase(formingState)) {
            return new Decision("ADD_ON_DAILY_CONFIRMATION_READY", "CONFIRMED_DAILY_ADD_PREVIEW_ONLY", true, "DAILY_CONFIRMATION",
                    "Daily SCORE_BUY is confirmed; staged add can be considered by a future write path.");
        }
        if (market.pullbackAddReady()) {
            return new Decision("ADD_ON_PULLBACK_READY", "POST_SCOUT_PULLBACK_ADD_PREVIEW_ONLY", true, "PULLBACK",
                    "Open scout exists and price has pulled back without invalidating the setup.");
        }
        if (market.pullbackCooldownAddReady()) {
            return new Decision("ADD_ON_PULLBACK_READY", "POST_SCOUT_PULLBACK_COOLDOWN_ADD_PREVIEW_ONLY", true, "PULLBACK",
                    "Open scout exists, price is back near average entry, and 15m momentum has cooled enough for a bounded pullback add.");
        }
        if (market.partialReversalPersistenceReady()) {
            return new Decision("ADD_ON_PARTIAL_REVERSAL_READY", "POST_SCOUT_PARTIAL_REVERSAL_PERSISTENCE_ADD_PREVIEW_ONLY", true, "PARTIAL_REVERSAL_PERSISTENCE",
                    "Open scout exists and a closed-15m partial reversal persists near the scout entry; only a minimum-size bounded add is allowed.");
        }
        if (market.confirmationAddReady()) {
            return new Decision("ADD_ON_CONFIRMATION_READY", "POST_SCOUT_CONFIRMATION_ADD_PREVIEW_ONLY", true, "CONFIRMATION",
                    "Open scout exists and recovery confirmation is present without overextension.");
        }
        if (market.overheated()) {
            return new Decision("WAIT_PULLBACK_AFTER_SCOUT", "WAIT_FOR_PULLBACK_OR_DAILY_CONFIRMATION", false, "NONE",
                    "Scout is active, but short-term rebound is overheated; avoid chasing.");
        }
        return new Decision("HOLD_SCOUT_MONITOR", "HOLD_EXISTING_SCOUT_MONITOR_NEXT_15M", false, "NONE",
                "Scout remains valid but no add-on condition is currently active.");
    }

    private BigDecimal suggestedNotional(String addOnType, BigDecimal deployable, BigDecimal remainingBudget, BigDecimal riskMultiplier) {
        if ("NONE".equals(addOnType)) return BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);
        BigDecimal pct = "PARTIAL_REVERSAL_PERSISTENCE".equals(addOnType)
                ? PARTIAL_REVERSAL_PERSISTENCE_ADD_PCT
                : "PULLBACK".equals(addOnType) ? PULLBACK_ADD_PCT : CONFIRMATION_ADD_PCT;
        BigDecimal cap = "PARTIAL_REVERSAL_PERSISTENCE".equals(addOnType)
                ? MAX_PARTIAL_REVERSAL_PERSISTENCE_ADD_NOTIONAL
                : "PULLBACK".equals(addOnType) ? MAX_PULLBACK_ADD_NOTIONAL : MAX_CONFIRMATION_ADD_NOTIONAL;
        BigDecimal raw = deployable.multiply(pct).min(cap).min(remainingBudget).multiply(riskMultiplier)
                .setScale(2, RoundingMode.DOWN);
        if (raw.compareTo(BigDecimal.ZERO) > 0 && raw.compareTo(EXCHANGE_MIN_NOTIONAL) < 0
                && deployable.compareTo(EXCHANGE_MIN_NOTIONAL) >= 0
                && (remainingBudget.compareTo(EXCHANGE_MIN_NOTIONAL) >= 0
                || budgetSupportsMinNotionalBridge(remainingBudget, deployable))) {
            return EXCHANGE_MIN_NOTIONAL;
        }
        return raw.compareTo(EXCHANGE_MIN_NOTIONAL) >= 0 ? raw : BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);
    }

    private boolean budgetSupportsMinNotionalBridge(BigDecimal remainingBudget, BigDecimal deployable) {
        if (deployable.compareTo(EXCHANGE_MIN_NOTIONAL) < 0) return false;
        if (remainingBudget.compareTo(EXCHANGE_MIN_NOTIONAL) >= 0) return false;
        return remainingBudget.add(MIN_NOTIONAL_BRIDGE_TOLERANCE).compareTo(EXCHANGE_MIN_NOTIONAL) >= 0;
    }

    private BudgetPlan postScoutBudget(JsonNode observer, BigDecimal deployable) {
        BigDecimal prePositionLimit = money(observer.path("sameThesisExposure"),
                "sameThesisExposureLimit", new BigDecimal("50.00"));
        if (prePositionLimit.compareTo(BigDecimal.ZERO) <= 0) {
            prePositionLimit = new BigDecimal("50.00");
        }
        BigDecimal exposureUsed = money(observer.path("sameThesisExposure"),
                "sameThesisExposureUsed", BigDecimal.ZERO);
        BigDecimal observedTotal = money(observer.path("capitalSnapshot"), "observedTotalUsdt", BigDecimal.ZERO);
        BigDecimal scoreBuyReserveTarget = money(observer.path("capitalSnapshot"), "scoreBuyReserveTargetUsdt", BigDecimal.ZERO);
        BigDecimal deployableCap = deployable.multiply(POST_SCOUT_DEPLOYABLE_BUDGET_PCT)
                .setScale(2, RoundingMode.DOWN);
        BigDecimal observedCapitalCap = observedTotal.multiply(POST_SCOUT_OBSERVED_CAPITAL_BUDGET_PCT)
                .setScale(2, RoundingMode.DOWN);
        BigDecimal scoreBuyReserveCap = scoreBuyReserveTarget.multiply(POST_SCOUT_SCORE_BUY_RESERVE_BUDGET_PCT)
                .setScale(2, RoundingMode.DOWN);
        BigDecimal dynamicCap = max(deployableCap, observedCapitalCap, scoreBuyReserveCap)
                .min(MAX_POST_SCOUT_TOTAL_BUDGET_USDT)
                .setScale(2, RoundingMode.DOWN);
        BigDecimal postScoutLimit = prePositionLimit.max(dynamicCap)
                .setScale(2, RoundingMode.DOWN);
        BigDecimal remaining = postScoutLimit.subtract(exposureUsed)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.DOWN);
        return new BudgetPlan(prePositionLimit.setScale(2, RoundingMode.DOWN), postScoutLimit, remaining,
                deployableCap, observedCapitalCap, scoreBuyReserveCap);
    }

    private ObjectNode postScoutSameThesisExposure(JsonNode observer, BudgetPlan budget) {
        BigDecimal exposureUsed = money(observer.path("sameThesisExposure"),
                "sameThesisExposureUsed", BigDecimal.ZERO);
        BigDecimal limit = budget.postScoutAddBudgetLimit();
        BigDecimal usedPct = limit.compareTo(BigDecimal.ZERO) > 0
                ? exposureUsed.multiply(new BigDecimal("100")).divide(limit, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scope", "SCORE_BUY_POST_SCOUT_ADD");
        putMoney(node, "sameThesisExposureUsed", exposureUsed);
        putMoney(node, "sameThesisExposureLimit", limit);
        putMoney(node, "postScoutAddBudgetLimitUsdt", limit);
        putMoney(node, "prePositionSameThesisExposureLimit", budget.prePositionBudgetLimit());
        putMoney(node, "remainingPostScoutAddBudgetUsdt", budget.remainingPostScoutAddBudget());
        node.put("sameThesisExposureUsedPct", usedPct.doubleValue());
        node.put("policy", "post-scout add uses the post-scout staged add budget in the live execution path; pre-position budget exhaustion alone does not block pullback/confirmation adds.");
        node.set("notes", stringArray(List.of(
                "POST_SCOUT_BUDGET_SUPERSEDES_PRE_POSITION_LIMIT_FOR_ADD_PATH",
                "EXACT_DUPLICATE_AND_HARD_SAFETY_GATES_STILL_BLOCK"
        )));
        return node;
    }

    private int effectiveMaxOpenSameThesisPositions(BigDecimal postScoutBudgetLimit) {
        int budgetDerived = postScoutBudgetLimit == null || postScoutBudgetLimit.compareTo(EXCHANGE_MIN_NOTIONAL) < 0
                ? BASE_MAX_OPEN_SAME_THESIS_POSITIONS
                : postScoutBudgetLimit.divide(EXCHANGE_MIN_NOTIONAL, 0, RoundingMode.DOWN).intValue();
        return Math.min(MAX_BUDGET_AWARE_OPEN_SAME_THESIS_POSITIONS,
                Math.max(BASE_MAX_OPEN_SAME_THESIS_POSITIONS, budgetDerived));
    }

    private MarketReadiness marketReadiness(JsonNode observer, PositionSummary positions) {
        double current = money(observer.path("formingDailyFrame"), "close", BigDecimal.ZERO).doubleValue();
        double avgEntry = positions.averageEntryPrice().doubleValue();
        double oneHourRsi = observer.path("intradayProxy1h").path("rsi").asDouble(Double.NaN);
        double fifteenMinuteRsi = observer.path("intradayProxy15m").path("rsi").asDouble(Double.NaN);
        double formingRsi = observer.path("formingDailyRsi").asDouble(Double.NaN);
        double reboundRecentLow = observer.path("earlyRecoveryScout").path("reboundFromRecentLowPct").asDouble(Double.NaN);
        String reversalStatus = text(observer, "intradayReversalStatus", "UNKNOWN");
        String reversalEvaluationMode = text(observer, "intradayReversalEvaluationMode", "UNKNOWN");
        boolean reversalDecisionUsesLastClosed15m = observer.path("intradayReversalDecisionUsesLastClosed15m").asBoolean(false);
        String reversalCurrentBarStatus = observer.path("intradayReversalCurrentBar").path("status").asText("UNKNOWN");
        JsonNode reversal = observer.path("intradayReversal");
        boolean reversalNoNewLow = reversal.path("noNewLow").asBoolean(false);
        boolean reversalLowerWickRecovery = reversal.path("lowerWickRecovery").asBoolean(false);
        boolean reversalReclaimSma20 = reversal.path("reclaimSma20").asBoolean(false);
        boolean reversalOversold = reversal.path("intradayOversold").asBoolean(false);
        int reversalSignalCount = (reversalNoNewLow ? 1 : 0)
                + (reversalLowerWickRecovery ? 1 : 0)
                + (reversalReclaimSma20 ? 1 : 0)
                + (reversalOversold ? 1 : 0);
        boolean reversalPass = "PASS".equalsIgnoreCase(reversalStatus);
        boolean reversalPartialWithReclaim = "PARTIAL".equalsIgnoreCase(reversalStatus)
                && reversalReclaimSma20;
        boolean stillDiscounted = observer.path("earlyRecoveryScout").path("stillDiscounted").asBoolean(false);
        boolean recentLowNearBand = observer.path("earlyRecoveryScout").path("recentLowNearDailyBand").asBoolean(false);
        boolean oneHourOverheated = Double.isFinite(oneHourRsi) && oneHourRsi > ONE_HOUR_RSI_OVERHEAT_THRESHOLD;
        boolean fifteenMinuteOverheated = Double.isFinite(fifteenMinuteRsi)
                && fifteenMinuteRsi > FIFTEEN_MINUTE_RSI_OVERHEAT_THRESHOLD;
        boolean reboundOverextended = Double.isFinite(reboundRecentLow)
                && reboundRecentLow > REBOUND_OVEREXTENDED_PCT_MAX;
        boolean overheated = oneHourOverheated || fifteenMinuteOverheated || reboundOverextended;
        boolean nearEntryOrPullback = current > 0 && avgEntry > 0
                && current <= avgEntry * PULLBACK_AVG_ENTRY_MULTIPLIER.doubleValue();
        boolean partialReversalWatch = "PARTIAL".equalsIgnoreCase(reversalStatus)
                && recentLowNearBand
                && stillDiscounted
                && nearEntryOrPullback
                && !overheated;
        boolean partialReversalPersistenceReady = partialReversalWatch
                && reversalDecisionUsesLastClosed15m
                && reversalSignalCount >= 1;
        boolean pullbackAddReady = reversalPass && recentLowNearBand && stillDiscounted && nearEntryOrPullback && !overheated;
        boolean fifteenMinuteCooled = Double.isFinite(fifteenMinuteRsi)
                && fifteenMinuteRsi <= FIFTEEN_MINUTE_COOLDOWN_RSI_MAX;
        boolean reboundStillBounded = !Double.isFinite(reboundRecentLow)
                || reboundRecentLow <= REBOUND_COOLDOWN_PCT_MAX;
        boolean formingNotExtended = !Double.isFinite(formingRsi) || formingRsi <= FORMING_COOLDOWN_RSI_MAX;
        boolean pullbackCooldownAddReady = !pullbackAddReady
                && (reversalPass || reversalPartialWithReclaim)
                && recentLowNearBand
                && stillDiscounted
                && nearEntryOrPullback
                && oneHourOverheated
                && !fifteenMinuteOverheated
                && !reboundOverextended
                && fifteenMinuteCooled
                && reboundStillBounded
                && formingNotExtended;
        boolean confirmationAddReady = reversalPass
                && stillDiscounted
                && !overheated
                && current > 0
                && avgEntry > 0
                && current >= avgEntry * CONFIRMATION_AVG_ENTRY_MULTIPLIER.doubleValue()
                && (!Double.isFinite(formingRsi) || formingRsi <= FORMING_CONFIRMATION_RSI_MAX);
        return new MarketReadiness(reversalStatus,
                reversalEvaluationMode,
                reversalDecisionUsesLastClosed15m,
                reversalCurrentBarStatus,
                reversalPass,
                reversalNoNewLow,
                reversalLowerWickRecovery,
                reversalReclaimSma20,
                reversalOversold,
                reversalSignalCount,
                partialReversalWatch,
                partialReversalPersistenceReady,
                stillDiscounted, recentLowNearBand, overheated,
                oneHourOverheated, fifteenMinuteOverheated, reboundOverextended,
                nearEntryOrPullback, pullbackAddReady, pullbackCooldownAddReady, confirmationAddReady,
                round(oneHourRsi, 4), round(fifteenMinuteRsi, 4), round(formingRsi, 4),
                round(reboundRecentLow, 4));
    }

    private ObjectNode nextTriggerSummary(Decision decision,
                                          MarketReadiness market,
                                          PositionSummary positions,
                                          BigDecimal mark,
                                          List<String> hardBlockers,
                                          BigDecimal remainingBudget,
                                          BigDecimal deployable,
                                          int effectiveMaxOpenPositions,
                                          boolean minNotionalBridgeAvailable,
                                          String formingState) {
        boolean openScoutExists = positions.openCount() > 0;
        boolean hardGatesPass = hardBlockers == null || hardBlockers.isEmpty();
        boolean budgetCanFundMin = remainingBudget.compareTo(EXCHANGE_MIN_NOTIONAL) >= 0 || minNotionalBridgeAvailable;
        boolean deployableCanFundMin = deployable.compareTo(EXCHANGE_MIN_NOTIONAL) >= 0;
        boolean openPositionCapacity = positions.openCount() < effectiveMaxOpenPositions;
        BigDecimal avgEntry = positions.averageEntryPrice();
        BigDecimal pullbackMaxPrice = avgEntry.compareTo(BigDecimal.ZERO) > 0
                ? avgEntry.multiply(PULLBACK_AVG_ENTRY_MULTIPLIER).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal confirmationMinPrice = avgEntry.compareTo(BigDecimal.ZERO) > 0
                ? avgEntry.multiply(CONFIRMATION_AVG_ENTRY_MULTIPLIER).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", decision.state());
        node.put("primaryGap", primaryTriggerGap(decision, market, openScoutExists, hardGatesPass,
                budgetCanFundMin, deployableCanFundMin, openPositionCapacity));
        node.put("nextRequiredAction", nextRequiredAction(decision, market));
        node.put("addOnEligible", decision.addOnEligible());
        node.put("executionWouldStillRequireWritePathRecheck", true);
        node.put("intradayReversalStatus", market.intradayReversalStatus());
        node.put("intradayReversalEvaluationMode", market.intradayReversalEvaluationMode());
        node.put("intradayReversalDecisionUsesLastClosed15m", market.intradayReversalDecisionUsesLastClosed15m());
        node.put("intradayReversalCurrentBarStatus", market.intradayReversalCurrentBarStatus());
        node.put("intradayReversalSignalCount", market.intradayReversalSignalCount());
        node.put("partialReversalWatch", market.partialReversalWatch());
        node.put("partialReversalPersistenceReady", market.partialReversalPersistenceReady());
        node.set("intradayReversalDetails", market.intradayReversalJson(objectMapper));
        node.put("nextIntradayReversalRequirement", market.nextIntradayReversalRequirement());
        node.put("dailyConfirmedPathReady", "CONFIRMED_DAILY_SCORE_BUY".equalsIgnoreCase(formingState)
                && hardGatesPass && budgetCanFundMin && deployableCanFundMin && openPositionCapacity);
        node.set("readinessChecklist", readinessChecklist(openScoutExists, hardGatesPass, budgetCanFundMin,
                deployableCanFundMin, openPositionCapacity, market));
        node.set("triggerBlockingSignals", stringArray(triggerBlockingSignals(market, mark, avgEntry,
                pullbackMaxPrice, confirmationMinPrice, openScoutExists, hardGatesPass, budgetCanFundMin,
                deployableCanFundMin, openPositionCapacity)));
        node.set("pullbackPath", pullbackPath(market, mark, avgEntry, pullbackMaxPrice));
        node.set("partialReversalPersistencePath", partialReversalPersistencePath(market, openScoutExists,
                hardGatesPass, budgetCanFundMin, deployableCanFundMin, openPositionCapacity));
        node.set("confirmationPath", confirmationPath(market, mark, avgEntry, confirmationMinPrice));
        node.set("hardGateBlockers", stringArray(distinct(hardBlockers == null ? List.of() : hardBlockers)));
        node.put("interpretation", triggerInterpretation(decision, market));
        return node;
    }

    private String primaryTriggerGap(Decision decision,
                                     MarketReadiness market,
                                     boolean openScoutExists,
                                     boolean hardGatesPass,
                                     boolean budgetCanFundMin,
                                     boolean deployableCanFundMin,
                                     boolean openPositionCapacity) {
        if (decision.addOnEligible()) return "READY";
        if (!openScoutExists) return "NO_OPEN_SCOUT";
        if (!hardGatesPass) return "HARD_GATE_NOT_PASS";
        if (!openPositionCapacity) return "OPEN_POSITION_CAPACITY_FULL";
        if (!budgetCanFundMin || !deployableCanFundMin) return "POST_SCOUT_MIN_NOTIONAL_BUDGET_NOT_AVAILABLE";
        if (market.overheated() && !market.nearEntryOrPullback()) return "OVERHEATED_AND_PRICE_ABOVE_PULLBACK_ZONE";
        if (market.oneHourOverheated()) return "ONE_HOUR_RSI_OVERHEATED";
        if (market.fifteenMinuteOverheated()) return "FIFTEEN_MINUTE_RSI_OVERHEATED";
        if (market.reboundOverextended()) return "REBOUND_OVEREXTENDED";
        if (!market.nearEntryOrPullback()) return "PRICE_ABOVE_PULLBACK_ZONE";
        if (market.partialReversalWatch() && !market.partialReversalPersistenceReady()) {
            return "PARTIAL_REVERSAL_PERSISTENCE_NOT_READY";
        }
        if (!market.intradayReversalPass()) return "INTRADAY_REVERSAL_NOT_CONFIRMED";
        if (!market.recentLowNearDailyBand()) return "RECENT_LOW_NOT_NEAR_DAILY_BAND";
        if (!market.stillDiscounted()) return "NO_LONGER_DISCOUNTED";
        return "WAIT_PULLBACK_OR_CONFIRMATION";
    }

    private String nextRequiredAction(Decision decision, MarketReadiness market) {
        if (decision.addOnEligible()) {
            return "READY_FOR_WRITE_PATH_RECHECK";
        }
        if ("NO_OPEN_SCOUT".equals(decision.state())) {
            return "WAIT_FOR_SCORE_BUY_SCOUT_OR_CONFIRMED_DAILY_SCORE_BUY";
        }
        if ("SCOUT_INVALIDATED_PROTECT".equals(decision.state())) {
            return "DO_NOT_ADD_REVIEW_EXISTING_PROTECTION";
        }
        if ("HOLD_SCOUT_HARD_BLOCKED".equals(decision.state())) {
            return "FIX_HARD_GATES_BEFORE_ANY_ADD";
        }
        if (market.oneHourOverheated() || market.fifteenMinuteOverheated() || market.reboundOverextended()) {
            return "WAIT_FOR_1H_RSI_COOLDOWN_OR_15M_PULLBACK_COOLDOWN_OR_DAILY_CONFIRMATION";
        }
        if (market.partialReversalWatch() && !market.partialReversalPersistenceReady()) {
            return "WAIT_PARTIAL_REVERSAL_PERSISTENCE_OR_FULL_CONFIRMATION";
        }
        return "WAIT_FOR_PULLBACK_OR_RECOVERY_CONFIRMATION";
    }

    private ArrayNode readinessChecklist(boolean openScoutExists,
                                         boolean hardGatesPass,
                                         boolean budgetCanFundMin,
                                         boolean deployableCanFundMin,
                                         boolean openPositionCapacity,
                                         MarketReadiness market) {
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add(check("openScoutExists", openScoutExists, String.valueOf(openScoutExists), "true"));
        arr.add(check("hardGatesPass", hardGatesPass, String.valueOf(hardGatesPass), "true"));
        arr.add(check("postScoutBudgetCanFundExchangeMin", budgetCanFundMin, String.valueOf(budgetCanFundMin), "true"));
        arr.add(check("deployableCanFundExchangeMin", deployableCanFundMin, String.valueOf(deployableCanFundMin), "true"));
        arr.add(check("openPositionCapacityRemaining", openPositionCapacity, String.valueOf(openPositionCapacity), "true"));
        arr.add(check("pullbackPathReady", market.pullbackAddReady() || market.pullbackCooldownAddReady(),
                String.valueOf(market.pullbackAddReady() || market.pullbackCooldownAddReady()), "true"));
        arr.add(check("partialReversalPersistenceReady", market.partialReversalPersistenceReady(),
                String.valueOf(market.partialReversalPersistenceReady()), "true"));
        arr.add(check("confirmationPathReady", market.confirmationAddReady(),
                String.valueOf(market.confirmationAddReady()), "true"));
        return arr;
    }

    private ObjectNode pullbackPath(MarketReadiness market,
                                    BigDecimal mark,
                                    BigDecimal avgEntry,
                                    BigDecimal pullbackMaxPrice) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ready", market.pullbackAddReady() || market.pullbackCooldownAddReady());
        node.put("standardPullbackReady", market.pullbackAddReady());
        node.put("cooldownPullbackReady", market.pullbackCooldownAddReady());
        putMoney(node, "currentPrice", mark);
        putMoney(node, "averageScoutEntryPrice", avgEntry);
        putMoney(node, "requiredPullbackMaxPrice", pullbackMaxPrice);
        putFinite(node, "currentAbovePullbackMaxPct", pctAbove(mark, pullbackMaxPrice));
        node.put("nearEntryOrPullback", market.nearEntryOrPullback());
        node.put("intradayReversalPass", market.intradayReversalPass());
        node.put("intradayReversalStatus", market.intradayReversalStatus());
        node.put("intradayReversalEvaluationMode", market.intradayReversalEvaluationMode());
        node.put("intradayReversalDecisionUsesLastClosed15m", market.intradayReversalDecisionUsesLastClosed15m());
        node.put("intradayReversalCurrentBarStatus", market.intradayReversalCurrentBarStatus());
        node.put("intradayReversalSignalCount", market.intradayReversalSignalCount());
        node.put("partialReversalWatch", market.partialReversalWatch());
        node.put("partialReversalPersistenceReady", market.partialReversalPersistenceReady());
        node.set("intradayReversalDetails", market.intradayReversalJson(objectMapper));
        node.put("nextIntradayReversalRequirement", market.nextIntradayReversalRequirement());
        node.put("recentLowNearDailyBand", market.recentLowNearDailyBand());
        node.put("stillDiscounted", market.stillDiscounted());
        node.put("oneHourOverheated", market.oneHourOverheated());
        putFinite(node, "oneHourRsi", market.oneHourRsi());
        node.put("oneHourRsiMax", ONE_HOUR_RSI_OVERHEAT_THRESHOLD);
        node.put("fifteenMinuteOverheated", market.fifteenMinuteOverheated());
        putFinite(node, "fifteenMinuteRsi", market.fifteenMinuteRsi());
        node.put("fifteenMinuteCooldownRsiMax", FIFTEEN_MINUTE_COOLDOWN_RSI_MAX);
        node.put("fifteenMinuteCooldownPass", Double.isFinite(market.fifteenMinuteRsi())
                && market.fifteenMinuteRsi() <= FIFTEEN_MINUTE_COOLDOWN_RSI_MAX);
        node.put("reboundOverextended", market.reboundOverextended());
        putFinite(node, "reboundFromRecentLowPct", market.reboundFromRecentLowPct());
        node.put("reboundOverextendedMaxPct", REBOUND_OVEREXTENDED_PCT_MAX);
        node.put("reboundCooldownMaxPct", REBOUND_COOLDOWN_PCT_MAX);
        putFinite(node, "formingDailyRsi", market.formingDailyRsi());
        node.put("formingCooldownRsiMax", FORMING_COOLDOWN_RSI_MAX);
        return node;
    }

    private ObjectNode partialReversalPersistencePath(MarketReadiness market,
                                                      boolean openScoutExists,
                                                      boolean hardGatesPass,
                                                      boolean budgetCanFundMin,
                                                      boolean deployableCanFundMin,
                                                      boolean openPositionCapacity) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ready", market.partialReversalPersistenceReady());
        node.put("mode", "BOUNDED_MINIMUM_ADD_ONLY");
        node.put("maxNotionalUsdt", MAX_PARTIAL_REVERSAL_PERSISTENCE_ADD_NOTIONAL.toPlainString());
        node.put("partialReversalWatch", market.partialReversalWatch());
        node.put("decisionUsesLastClosed15m", market.intradayReversalDecisionUsesLastClosed15m());
        node.put("intradayReversalEvaluationMode", market.intradayReversalEvaluationMode());
        node.put("intradayReversalStatus", market.intradayReversalStatus());
        node.put("intradayReversalSignalCount", market.intradayReversalSignalCount());
        node.put("requiredSignalCountForPartialPersistence", 1);
        node.put("openScoutExists", openScoutExists);
        node.put("hardGatesPass", hardGatesPass);
        node.put("budgetCanFundExchangeMin", budgetCanFundMin);
        node.put("deployableCanFundExchangeMin", deployableCanFundMin);
        node.put("openPositionCapacityRemaining", openPositionCapacity);
        node.put("nearEntryOrPullback", market.nearEntryOrPullback());
        node.put("stillDiscounted", market.stillDiscounted());
        node.put("recentLowNearDailyBand", market.recentLowNearDailyBand());
        node.put("overheated", market.overheated());
        node.set("intradayReversalDetails", market.intradayReversalJson(objectMapper));
        node.put("policy", "Allows a $5 post-scout add on persistent closed-15m partial reversal only after all hard gates pass; does not bypass exact duplicate, OCO, runtime evidence, daily cap, budget, max loss, or open-position limits.");
        return node;
    }

    private ObjectNode confirmationPath(MarketReadiness market,
                                        BigDecimal mark,
                                        BigDecimal avgEntry,
                                        BigDecimal confirmationMinPrice) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ready", market.confirmationAddReady());
        putMoney(node, "currentPrice", mark);
        putMoney(node, "averageScoutEntryPrice", avgEntry);
        putMoney(node, "requiredConfirmationMinPrice", confirmationMinPrice);
        putFinite(node, "currentBelowConfirmationMinPct", pctBelow(mark, confirmationMinPrice));
        node.put("intradayReversalPass", market.intradayReversalPass());
        node.put("stillDiscounted", market.stillDiscounted());
        node.put("notOverheated", !market.overheated());
        putFinite(node, "formingDailyRsi", market.formingDailyRsi());
        node.put("formingDailyRsiMax", FORMING_CONFIRMATION_RSI_MAX);
        return node;
    }

    private List<String> triggerBlockingSignals(MarketReadiness market,
                                                BigDecimal mark,
                                                BigDecimal avgEntry,
                                                BigDecimal pullbackMaxPrice,
                                                BigDecimal confirmationMinPrice,
                                                boolean openScoutExists,
                                                boolean hardGatesPass,
                                                boolean budgetCanFundMin,
                                                boolean deployableCanFundMin,
                                                boolean openPositionCapacity) {
        List<String> signals = new ArrayList<>();
        boolean anyAddPathReady = market.pullbackAddReady()
                || market.pullbackCooldownAddReady()
                || market.partialReversalPersistenceReady()
                || market.confirmationAddReady();
        if (!openScoutExists) signals.add("NO_OPEN_SCOUT");
        if (!hardGatesPass) signals.add("HARD_GATE_NOT_PASS");
        if (!budgetCanFundMin) signals.add("POST_SCOUT_BUDGET_BELOW_EXCHANGE_MIN");
        if (!deployableCanFundMin) signals.add("DEPLOYABLE_CAPITAL_BELOW_EXCHANGE_MIN");
        if (!openPositionCapacity) signals.add("OPEN_POSITION_CAPACITY_FULL");
        if (!market.intradayReversalPass() && !market.partialReversalPersistenceReady()) {
            signals.add("INTRADAY_REVERSAL_NOT_CONFIRMED");
        }
        if (!market.recentLowNearDailyBand()) signals.add("RECENT_LOW_NOT_NEAR_DAILY_BAND");
        if (!market.stillDiscounted()) signals.add("NO_LONGER_DISCOUNTED");
        if (!market.nearEntryOrPullback() && avgEntry.compareTo(BigDecimal.ZERO) > 0) {
            signals.add("PRICE_ABOVE_PULLBACK_ZONE current=" + moneyText(mark)
                    + " requiredMax=" + moneyText(pullbackMaxPrice));
        }
        if (market.oneHourOverheated()) {
            signals.add("ONE_HOUR_RSI_OVERHEATED current=" + doubleText(market.oneHourRsi())
                    + " max=" + ONE_HOUR_RSI_OVERHEAT_THRESHOLD);
        }
        if (market.fifteenMinuteOverheated()) {
            signals.add("FIFTEEN_MINUTE_RSI_OVERHEATED current=" + doubleText(market.fifteenMinuteRsi())
                    + " max=" + FIFTEEN_MINUTE_RSI_OVERHEAT_THRESHOLD);
        }
        if (market.reboundOverextended()) {
            signals.add("REBOUND_OVEREXTENDED current=" + doubleText(market.reboundFromRecentLowPct())
                    + " max=" + REBOUND_OVEREXTENDED_PCT_MAX);
        }
        if (!anyAddPathReady
                && mark.compareTo(BigDecimal.ZERO) > 0
                && confirmationMinPrice.compareTo(BigDecimal.ZERO) > 0
                && mark.compareTo(confirmationMinPrice) < 0) {
            signals.add("CONFIRMATION_PRICE_NOT_RECLAIMED current=" + moneyText(mark)
                    + " requiredMin=" + moneyText(confirmationMinPrice));
        }
        return distinct(signals);
    }

    private ObjectNode check(String name, boolean pass, String current, String target) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.put("pass", pass);
        node.put("current", current);
        node.put("target", target);
        return node;
    }

    private String triggerInterpretation(Decision decision, MarketReadiness market) {
        if (decision.addOnEligible()) {
            return "Post-scout add trigger is ready in read-only preview; live execution must still recheck hard gates.";
        }
        if (market.oneHourOverheated() && !market.nearEntryOrPullback()) {
            return "The scout remains active, but the next add is waiting for either a pullback near average scout entry or enough RSI cooldown to avoid chasing the rebound.";
        }
        if (market.oneHourOverheated()) {
            return "The scout remains active, but the next add is waiting for 1h RSI cooldown or the bounded 15m cooldown path.";
        }
        if (market.partialReversalWatch() && !market.partialReversalPersistenceReady()) {
            return "The scout remains active with partial reversal evidence; wait until the closed-15m partial reversal persistence rule is satisfied or full recovery confirmation appears.";
        }
        if (market.partialReversalPersistenceReady()) {
            return "Partial reversal persistence is ready for a minimum-size post-scout add preview; live execution must still recheck hard gates.";
        }
        if (!market.nearEntryOrPullback()) {
            return "The scout remains active, but current price is above the pullback zone for an add.";
        }
        return "The scout remains active; wait for either pullback readiness, recovery confirmation, or official daily SCORE_BUY confirmation.";
    }

    private List<BtLiveSignal> openScoutPositions(String symbol, long strategyId) {
        return liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(strategyId).stream()
                .filter(row -> symbol.equalsIgnoreCase(row.getSymbol()))
                .sorted(Comparator.comparing(BtLiveSignal::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private PositionSummary summarize(List<BtLiveSignal> rows, BigDecimal mark) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (BtLiveSignal row : rows) {
            BigDecimal q = row.getOcoQty() != null ? row.getOcoQty() : row.getTradedQty();
            BigDecimal entry = row.getActualEntryPrice() != null ? row.getActualEntryPrice() : row.getEntryPrice();
            if (q == null || entry == null) continue;
            qty = qty.add(q.abs());
            cost = cost.add(q.abs().multiply(entry));
        }
        BigDecimal value = qty.multiply(mark == null ? BigDecimal.ZERO : mark).setScale(4, RoundingMode.HALF_UP);
        BigDecimal pnl = value.subtract(cost).setScale(4, RoundingMode.HALF_UP);
        BigDecimal avg = qty.compareTo(BigDecimal.ZERO) > 0
                ? cost.divide(qty, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        double pnlPct = cost.compareTo(BigDecimal.ZERO) > 0
                ? pnl.multiply(new BigDecimal("100")).divide(cost, 4, RoundingMode.HALF_UP).doubleValue()
                : 0.0;
        return new PositionSummary(rows.size(), qty, cost.setScale(4, RoundingMode.HALF_UP), value, pnl, pnlPct, avg);
    }

    private ObjectNode rulesJson(int effectiveMaxOpenPositions) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scope", "BTCUSDT strategyId=485 LONG only");
        node.put("policy", "Read-only post-scout management preview; no order is sent.");
        node.put("pullbackAddRule", "open scout + OCO/runtime evidence OK + post-scout staged budget + no exact duplicate + price near average entry + reversal intact; 1h overheat may allow a bounded add only after 15m cooldown.");
        node.put("partialReversalPersistenceRule", "open scout + OCO/runtime evidence OK + post-scout staged budget + no exact duplicate + closed-15m partial reversal persists near average entry; max $5 add only.");
        node.put("confirmationAddRule", "open scout + recovery confirmation + still discounted + not overheated + forming RSI not above 55");
        node.put("invalidatedRule", "Structural INVALIDATED forming state with an open scout enters HOLD_BTC mode: no auto-sell, no auto-add, keep 12% disaster OCO, and require explicit re-arm conditions before new exposure.");
        node.put("riskScaling", "R2 multiplier 0.50, R3 multiplier 0.25; R3 never allows confirmed large deploy.");
        node.put("baseMaxOpenSameThesisPositions", BASE_MAX_OPEN_SAME_THESIS_POSITIONS);
        node.put("maxOpenSameThesisPositions", effectiveMaxOpenPositions);
        node.put("maxBudgetAwareOpenSameThesisPositions", MAX_BUDGET_AWARE_OPEN_SAME_THESIS_POSITIONS);
        node.put("openPositionPolicy", "open count is budget-aware: base cap is 8, but the effective cap scales up to the number of exchange-minimum slices supported by post-scout staged budget, capped at 16; exact duplicate and budget exhaustion remain hard blocks.");
        node.put("postScoutBudgetPolicy", "max(pre-position limit, min(max(20% reserve-aware deployable USDT, 12% observed capital, 40% SCORE_BUY reserve target), 125 USDT)); pre-position budget exhaustion alone does not block confirmation/pullback adds.");
        putMoney(node, "minNotionalBridgeToleranceUsdt", MIN_NOTIONAL_BRIDGE_TOLERANCE);
        putMoney(node, "exchangeMinNotionalUsdt", EXCHANGE_MIN_NOTIONAL);
        putMoney(node, "maxPartialReversalPersistenceAddNotionalUsdt", MAX_PARTIAL_REVERSAL_PERSISTENCE_ADD_NOTIONAL);
        putMoney(node, "maxPullbackAddNotionalUsdt", MAX_PULLBACK_ADD_NOTIONAL);
        putMoney(node, "maxConfirmationAddNotionalUsdt", MAX_CONFIRMATION_ADD_NOTIONAL);
        return node;
    }

    private ObjectNode observerSummary(JsonNode observer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scoreBuyFormingState", text(observer, "scoreBuyFormingState", "UNKNOWN"));
        node.put("scoreBuyHoldingState", text(observer, "scoreBuyHoldingState", "UNKNOWN"));
        node.put("holdBtcMode", observer.path("holdBtcMode").asBoolean(false));
        node.put("holdBtcReason", text(observer, "holdBtcReason", "UNKNOWN"));
        node.put("disasterOcoMode", text(observer, "disasterOcoMode", "UNKNOWN"));
        node.put("postScoutLifecycleState", text(observer, "postScoutLifecycleState", "UNKNOWN"));
        node.put("postScoutLifecycleAction", text(observer, "postScoutLifecycleAction", "UNKNOWN"));
        node.put("intradayReversalDecisionBarOpenTime",
                text(observer, "intradayReversalDecisionBarOpenTime", "UNKNOWN"));
        node.put("intradayReversalCurrentBarOpenTime",
                text(observer, "intradayReversalCurrentBarOpenTime", "UNKNOWN"));
        node.put("missedOpportunityRisk", text(observer, "missedOpportunityRisk", "UNKNOWN"));
        node.set("earlyRecoveryScout", observer.path("earlyRecoveryScout").deepCopy());
        node.set("prePositionTrigger", observer.path("prePositionTrigger").deepCopy());
        return node;
    }

    private ObjectNode prePositionSummary(JsonNode preview) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("executionPolicy", text(preview, "executionPolicy", "UNKNOWN"));
        node.put("runtimeEvidenceStatus", text(preview, "runtimeEvidenceStatus", "UNKNOWN"));
        node.put("runtimeEvidenceMode", text(preview, "runtimeEvidenceMode", "UNKNOWN"));
        node.put("ocoPreflightStatus", text(preview, "ocoPreflightStatus", "UNKNOWN"));
        node.put("exactDuplicateOpportunity", preview.path("exactDuplicateOpportunity").asBoolean(false));
        node.set("blockers", preview.path("blockers").deepCopy());
        return node;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("parseError", e.getMessage());
            node.put("raw", json == null ? "N/A" : json);
            return node;
        }
    }

    private BigDecimal eventRiskMultiplier(String eventRisk) {
        return switch (eventRisk == null ? "" : eventRisk.toUpperCase(Locale.ROOT)) {
            case "R2" -> new BigDecimal("0.50");
            case "R3" -> new BigDecimal("0.25");
            default -> BigDecimal.ONE;
        };
    }

    private boolean startsWith(String value, String prefix) {
        return value != null && value.toUpperCase(Locale.ROOT).startsWith(prefix.toUpperCase(Locale.ROOT));
    }

    private boolean runtimeEvidenceAvailable(String status) {
        return startsWith(status, "AVAILABLE_CANONICAL")
                || startsWith(status, "AVAILABLE_FALLBACK_SCORE_BUY");
    }

    private boolean containsAny(List<String> values, String... needles) {
        for (String value : values) {
            for (String needle : needles) {
                if (value != null && value.toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean softPostScoutInvalidation(JsonNode observer, boolean openScoutExists) {
        if (!openScoutExists) return false;
        if (!"INVALIDATED".equalsIgnoreCase(text(observer, "scoreBuyFormingState", "UNKNOWN"))) return false;
        return "EVENT_RISK_R3_AND_DAILY_FORMING_SETUP_LOST"
                .equalsIgnoreCase(text(observer, "invalidationReason", "UNKNOWN"));
    }

    private void copyObserverHardBlockers(JsonNode observer,
                                          List<String> hardBlockers,
                                          List<String> warnings,
                                          boolean softPostScoutInvalidation) {
        JsonNode array = observer.path("hardBlockers");
        if (!array.isArray()) return;
        for (JsonNode value : array) {
            String blocker = value.asText("");
            if (blocker.isBlank()) continue;
            if (softPostScoutInvalidation && "FORMING_DAY_INVALIDATED".equalsIgnoreCase(blocker)) {
                warnings.add("POST_SCOUT_SOFT_FORMING_INVALIDATION:"
                        + text(observer, "invalidationReason", "UNKNOWN"));
            } else {
                hardBlockers.add(blocker);
            }
        }
    }

    private void copyArray(JsonNode array, List<String> target) {
        if (!array.isArray()) return;
        for (JsonNode value : array) {
            if (!value.asText("").isBlank()) target.add(value.asText());
        }
    }

    private List<String> distinct(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
    }

    private BigDecimal max(BigDecimal first, BigDecimal... rest) {
        BigDecimal out = first == null ? BigDecimal.ZERO : first;
        for (BigDecimal value : rest) {
            if (value != null && value.compareTo(out) > 0) {
                out = value;
            }
        }
        return out;
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode arr = objectMapper.createArrayNode();
        values.forEach(arr::add);
        return arr;
    }

    private BigDecimal money(JsonNode node, String key, BigDecimal fallback) {
        JsonNode value = node.path(key);
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.asText());
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() || value.asText("").isBlank() ? fallback : value.asText();
    }

    private void putMoney(ObjectNode node, String key, BigDecimal value) {
        node.put(key, (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    private void putFinite(ObjectNode node, String key, double value) {
        if (Double.isFinite(value)) {
            node.put(key, round(value, 4));
        } else {
            node.putNull(key);
        }
    }

    private double pctAbove(BigDecimal current, BigDecimal target) {
        if (current == null || target == null || target.compareTo(BigDecimal.ZERO) <= 0) {
            return Double.NaN;
        }
        if (current.compareTo(target) <= 0) {
            return 0.0;
        }
        return current.subtract(target)
                .multiply(new BigDecimal("100"))
                .divide(target, 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double pctBelow(BigDecimal current, BigDecimal target) {
        if (current == null || target == null || target.compareTo(BigDecimal.ZERO) <= 0) {
            return Double.NaN;
        }
        if (current.compareTo(target) >= 0) {
            return 0.0;
        }
        return target.subtract(current)
                .multiply(new BigDecimal("100"))
                .divide(target, 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private String moneyText(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String doubleText(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).toPlainString() : "N/A";
    }

    private double round(double value, int scale) {
        if (!Double.isFinite(value)) return value;
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String write(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return root.toString();
        }
    }

    private record Decision(String state,
                            String recommendedAction,
                            boolean addOnEligible,
                            String addOnType,
                            String reason) {
    }

    private record MarketReadiness(String intradayReversalStatus,
                                   String intradayReversalEvaluationMode,
                                   boolean intradayReversalDecisionUsesLastClosed15m,
                                   String intradayReversalCurrentBarStatus,
                                   boolean intradayReversalPass,
                                   boolean intradayReversalNoNewLow,
                                   boolean intradayReversalLowerWickRecovery,
                                   boolean intradayReversalReclaimSma20,
                                   boolean intradayReversalOversold,
                                   int intradayReversalSignalCount,
                                   boolean partialReversalWatch,
                                   boolean partialReversalPersistenceReady,
                                   boolean stillDiscounted,
                                   boolean recentLowNearDailyBand,
                                   boolean overheated,
                                   boolean oneHourOverheated,
                                   boolean fifteenMinuteOverheated,
                                   boolean reboundOverextended,
                                   boolean nearEntryOrPullback,
                                   boolean pullbackAddReady,
                                   boolean pullbackCooldownAddReady,
                                   boolean confirmationAddReady,
                                   double oneHourRsi,
                                   double fifteenMinuteRsi,
                                   double formingDailyRsi,
                                   double reboundFromRecentLowPct) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("intradayReversalStatus", intradayReversalStatus);
            node.put("intradayReversalEvaluationMode", intradayReversalEvaluationMode);
            node.put("intradayReversalDecisionUsesLastClosed15m", intradayReversalDecisionUsesLastClosed15m);
            node.put("intradayReversalCurrentBarStatus", intradayReversalCurrentBarStatus);
            node.put("intradayReversalPass", intradayReversalPass);
            node.put("intradayReversalSignalCount", intradayReversalSignalCount);
            node.put("partialReversalWatch", partialReversalWatch);
            node.put("partialReversalPersistenceReady", partialReversalPersistenceReady);
            node.set("intradayReversalDetails", intradayReversalJson(mapper));
            node.put("nextIntradayReversalRequirement", nextIntradayReversalRequirement());
            node.put("stillDiscounted", stillDiscounted);
            node.put("recentLowNearDailyBand", recentLowNearDailyBand);
            node.put("overheated", overheated);
            node.put("oneHourOverheated", oneHourOverheated);
            node.put("fifteenMinuteOverheated", fifteenMinuteOverheated);
            node.put("reboundOverextended", reboundOverextended);
            node.put("nearEntryOrPullback", nearEntryOrPullback);
            node.put("pullbackAddReady", pullbackAddReady);
            node.put("pullbackCooldownAddReady", pullbackCooldownAddReady);
            node.put("confirmationAddReady", confirmationAddReady);
            putFinite(node, "oneHourRsi", oneHourRsi);
            putFinite(node, "fifteenMinuteRsi", fifteenMinuteRsi);
            putFinite(node, "formingDailyRsi", formingDailyRsi);
            putFinite(node, "reboundFromRecentLowPct", reboundFromRecentLowPct);
            return node;
        }

        private ObjectNode intradayReversalJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("status", intradayReversalStatus);
            node.put("evaluationMode", intradayReversalEvaluationMode);
            node.put("decisionUsesLastClosed15m", intradayReversalDecisionUsesLastClosed15m);
            node.put("currentBarStatus", intradayReversalCurrentBarStatus);
            node.put("requiredSignalCountForPass", 2);
            node.put("requiredSignalCountForPartialPersistence", 1);
            node.put("signalCount", intradayReversalSignalCount);
            node.put("partialReversalPersistenceReady", partialReversalPersistenceReady);
            node.put("noNewLow", intradayReversalNoNewLow);
            node.put("lowerWickRecovery", intradayReversalLowerWickRecovery);
            node.put("reclaimSma20", intradayReversalReclaimSma20);
            node.put("intradayOversold", intradayReversalOversold);
            ArrayNode missing = mapper.createArrayNode();
            if (!intradayReversalNoNewLow) missing.add("NO_NEW_LOW");
            if (!intradayReversalLowerWickRecovery) missing.add("LOWER_WICK_RECOVERY");
            if (!intradayReversalReclaimSma20) missing.add("RECLAIM_SMA20");
            if (!intradayReversalOversold) missing.add("INTRADAY_OVERSOLD");
            node.set("missingSignals", missing);
            return node;
        }

        private String nextIntradayReversalRequirement() {
            if (intradayReversalPass) {
                return "PASS";
            }
            List<String> missing = new ArrayList<>();
            if (!intradayReversalNoNewLow) missing.add("NO_NEW_LOW");
            if (!intradayReversalLowerWickRecovery) missing.add("LOWER_WICK_RECOVERY");
            if (!intradayReversalReclaimSma20) missing.add("RECLAIM_SMA20");
            if (!intradayReversalOversold) missing.add("INTRADAY_OVERSOLD");
            int needed = Math.max(0, 2 - intradayReversalSignalCount);
            return needed <= 0
                    ? "WAIT_STATUS_REFRESH"
                    : "NEED_" + needed + "_OF_" + String.join("_OR_", missing);
        }

        private static void putFinite(ObjectNode node, String key, double value) {
            if (Double.isFinite(value)) node.put(key, value);
            else node.putNull(key);
        }
    }

    private record BudgetPlan(BigDecimal prePositionBudgetLimit,
                              BigDecimal postScoutAddBudgetLimit,
                              BigDecimal remainingPostScoutAddBudget,
                              BigDecimal deployableBudgetCap,
                              BigDecimal observedCapitalBudgetCap,
                              BigDecimal scoreBuyReserveBudgetCap) {
    }

    private record PositionSummary(int openCount,
                                   BigDecimal qty,
                                   BigDecimal costUsdt,
                                   BigDecimal marketValueUsdt,
                                   BigDecimal unrealizedPnlUsdt,
                                   double unrealizedPnlPct,
                                   BigDecimal averageEntryPrice) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("openScoutPositions", openCount);
            putQty(node, "qty", qty);
            putMoney(node, "costUsdt", costUsdt);
            putMoney(node, "marketValueUsdt", marketValueUsdt);
            putMoney(node, "unrealizedPnlUsdt", unrealizedPnlUsdt);
            node.put("unrealizedPnlPct", unrealizedPnlPct);
            putMoney(node, "averageEntryPrice", averageEntryPrice);
            return node;
        }

        private static void putMoney(ObjectNode node, String key, BigDecimal value) {
            node.put(key, (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString());
        }

        private static void putQty(ObjectNode node, String key, BigDecimal value) {
            node.put(key, (value == null ? BigDecimal.ZERO : value).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        }
    }
}
