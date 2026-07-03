package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MissedOpportunityRegressionValidationService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final String DEFAULT_SIDE = "LONG";
    private static final long TINY_LIVE_STRATEGY_ID = 574L;
    private static final long SCORE_BUY_STRATEGY_ID = 485L;
    private static final BigDecimal HIGH_FORWARD_RETURN_NO_BUY_THRESHOLD_PCT = new BigDecimal("1.0");

    private final TinyLiveMinimumOrderPreviewService tinyLivePreviewService;
    private final ExplorationPolicyService explorationPolicyService;
    private final AutonomousExplorationLoopService loopService;
    private final ScoreBuyPrePositionExecutionPolicyPreviewService scoreBuyExecutionPreviewService;
    private final ScoreBuyPrePositionAutoExecutionService scoreBuyAutoExecutionService;
    private final ScoreBuyPostScoutAutoAddExecutionService scoreBuyPostScoutAutoAddExecutionService;
    private final ScoreBuyConfirmedDeployAutoExecutionService scoreBuyConfirmedDeployAutoExecutionService;
    private final CapitalAllocationPolicyPreviewService capitalAllocationPolicyPreviewService;
    private final StagedAddPolicyService stagedAddPolicyService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String getMissedOpportunityRegressionReport(String symbol, Integer hours) {
        String sym = normalizeSymbol(symbol);
        int windowHours = normalizeHours(hours);
        List<Row> rows = List.of(
                tinyLiveRow(sym),
                scoreBuyPrePositionRow(sym),
                scoreBuyPostScoutAddRow(sym),
                scoreBuyConfirmedDeployRow(sym));

        int bugCount = countClassification(rows, "BUG_");
        int missedRiskCount = countClassification(rows, "MISSED_OPPORTUNITY_RISK");
        int scopeLeakCount = countClassification(rows, "BUG_SCOPE_LEAK");
        int dedupTooCoarseCount = countClassification(rows, "BUG_ENTRY_DEDUP_TOO_COARSE");
        int capitalMisreadCount = countClassification(rows, "BUG_CAPITAL_MISREAD");
        int staleDataCount = countClassification(rows, "BUG_STALE_DATA");
        int capacityLimitedOpportunityCount = countClassification(rows, "WATCH_SCORE_BUY_CAPACITY_LIMITED_ADD");
        HighForwardReturnNoBuyScan highForwardReturnNoBuy = scanHighForwardReturnNoBuy(sym, windowHours);
        String stagedAddDashboard = safe("stagedAddGovernance", () ->
                stagedAddPolicyService.getEntryDedupGovernanceDashboard(sym, windowHours));
        JsonNode stagedAddNode = readJson(stagedAddDashboard);
        int genericWouldAllowGroups = stagedAddNode.path("wouldAllowStagedAddGroups").asInt(0);
        String overallStatus = bugCount > 0 ? "FAIL"
                : (missedRiskCount > 0
                || genericWouldAllowGroups > 0
                || capacityLimitedOpportunityCount > 0
                || highForwardReturnNoBuy.count() > 0) ? "WARN" : "PASS";

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "getMissedOpportunityRegressionReport");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior changed.");
        root.put("generatedAt", Instant.now().toString());
        root.put("symbol", sym);
        root.put("hours", windowHours);
        root.put("overallStatus", overallStatus);
        root.put("suspiciousNoBuyCount", bugCount + missedRiskCount + genericWouldAllowGroups
                + highForwardReturnNoBuy.count());
        root.put("falseBlockRiskCount", missedRiskCount + genericWouldAllowGroups
                + highForwardReturnNoBuy.count());
        root.put("scopeLeakSuspects", scopeLeakCount);
        root.put("dedupTooCoarseSuspects", dedupTooCoarseCount);
        root.put("capitalMisreadSuspects", capitalMisreadCount);
        root.put("staleDataSuspects", staleDataCount);
        root.put("capacityLimitedOpportunityCount", capacityLimitedOpportunityCount);
        root.put("genericStagedAddWouldAllowGroups", genericWouldAllowGroups);
        root.put("highForwardReturnNoBuyCount", highForwardReturnNoBuy.count());
        root.set("highForwardReturnNoBuyExamples", highForwardReturnNoBuy.examples());
        root.put("recommendedFix", recommendedFix(rows, genericWouldAllowGroups, highForwardReturnNoBuy.count()));
        ArrayNode taxonomy = root.putArray("noBuyReasonTaxonomy");
        for (String value : List.of(
                "VALID_HARD_SAFETY_BLOCK",
                "VALID_OPEN_POSITION_WAIT",
                "VALID_DAILY_CAP_WAIT",
                "VALID_BUDGET_WAIT",
                "VALID_SIGNAL_NOT_READY",
                "WATCH_SCORE_BUY_CAPACITY_LIMITED_ADD",
                "WATCH_SIGNAL_NEAR_BUY_THRESHOLD",
                "VALID_EV_SAMPLE_MISSING",
                "VALID_EV_FAIL",
                "VALID_OCO_BLOCK",
                "BUG_SCOPE_LEAK",
                "BUG_STALE_DATA",
                "BUG_CAPITAL_MISREAD",
                "BUG_ENTRY_DEDUP_TOO_COARSE",
                "MISSED_OPPORTUNITY_RISK")) {
            taxonomy.add(value);
        }
        root.set("rows", rowsArray(rows));
        root.set("genericStagedAddSummary", stagedAddNode);
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("writesRuntimeEvidence", false);
        return write(root);
    }

    @Transactional(readOnly = true)
    public String validateAutonomousOpportunityReadiness(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? TINY_LIVE_STRATEGY_ID : strategyId;
        Row row = sid == SCORE_BUY_STRATEGY_ID ? scoreBuyPrePositionRow(sym) : tinyLiveRow(sym);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "validateAutonomousOpportunityReadiness");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior changed.");
        root.put("generatedAt", Instant.now().toString());
        root.put("symbol", sym);
        root.put("strategyId", sid);
        root.put("side", normalizeSide(side));
        root.put("readinessClassification", row.classification());
        root.put("eligible", autonomousOpportunityEligible(row));
        root.put("reason", row.reason());
        root.set("blockers", stringArray(row.blockers()));
        root.set("warnings", stringArray(row.warnings()));
        root.set("evidence", row.evidence());
        root.put("orderSent", false);
        return write(root);
    }

    @Transactional(readOnly = true)
    public String getNoBuyReasonTruthTable(String symbol, Integer hours, Integer limit) {
        String sym = normalizeSymbol(symbol);
        int windowHours = normalizeHours(hours);
        int maxRows = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        List<Row> rows = List.of(tinyLiveRow(sym), scoreBuyPrePositionRow(sym),
                scoreBuyPostScoutAddRow(sym), scoreBuyConfirmedDeployRow(sym));
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "getNoBuyReasonTruthTable");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior changed.");
        root.put("generatedAt", Instant.now().toString());
        root.put("symbol", sym);
        root.put("hours", windowHours);
        root.put("limit", maxRows);
        root.set("rows", rowsArray(rows.subList(0, Math.min(rows.size(), maxRows))));
        root.put("orderSent", false);
        return write(root);
    }

    private Row tinyLiveRow(String symbol) {
        TinyLiveMinimumOrderPreviewService.PreviewResult preview =
                tinyLivePreviewService.preview(symbol, TINY_LIVE_STRATEGY_ID, DEFAULT_SIDE);
        String exploration = safe("exploration", () ->
                explorationPolicyService.getExplorationReadiness(symbol, TINY_LIVE_STRATEGY_ID, DEFAULT_SIDE));
        String loop = safe("loop", () ->
                loopService.getAutonomousExplorationLoopStatus(symbol, TINY_LIVE_STRATEGY_ID, DEFAULT_SIDE));

        List<String> blockers = new ArrayList<>(preview.denialReasons());
        List<String> warnings = new ArrayList<>(preview.warnings());
        addBlockersFromText(exploration, blockers);
        addBlockersFromText(loop, blockers);
        sanitizeTinyLiveContextBlockers(preview, blockers, warnings, loop);

        String classification = classifyTinyLive(preview, blockers, warnings, exploration, loop);
        String reason = switch (classification) {
            case "BUG_SCOPE_LEAK" -> "Tiny-live daily cap reports reached while scoped tiny-live count is below maxOrdersToday.";
            case "VALID_OPEN_POSITION_WAIT" -> "Tiny-live correctly waits because the same strategy already has an open tiny-live position.";
            case "VALID_EV_FAIL" -> "Tiny-live correctly waits because ExpectedValueGate is not passing.";
            case "VALID_EV_SAMPLE_MISSING" -> "Tiny-live correctly waits because the current candidate has no fresh ExpectedValueGate sample yet.";
            case "WATCH_SIGNAL_NEAR_BUY_THRESHOLD" -> "Tiny-live is not allowed to buy yet, but the latest #574 HOLD is close to its BUY threshold; keep high-frequency observation active.";
            case "VALID_SIGNAL_NOT_READY" -> "Tiny-live correctly waits because strategy #574 has no current BUY candidate.";
            case "VALID_DAILY_CAP_WAIT" -> "Tiny-live correctly waits because scoped daily cap is reached.";
            case "VALID_OCO_BLOCK" -> "Tiny-live correctly blocks because OCO preflight is not passing.";
            case "BUG_STALE_DATA" -> "Freshness/stale data blocker is present and needs data pipeline review.";
            case "MISSED_OPPORTUNITY_RISK" -> "Tiny-live has no hard blocker but execution is still not progressing; review loop/rollout settings.";
            default -> "Tiny-live no-buy reason is consistent with current safety/readiness gates.";
        };

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("scope", "TINY_LIVE_STRATEGY_SYMBOL");
        evidence.put("previewStatus", preview.status());
        evidence.put("allowedAfterManualApproval", preview.allowedAfterManualApproval());
        evidence.put("tinyLiveAutoTradesToday", preview.autoTradesToday());
        evidence.put("maxOrdersToday", preview.maxOrdersToday());
        evidence.put("openTinyLivePositions", preview.currentSameStrategyTinyLiveOpenPositions());
        evidence.put("openAutoPositions", preview.currentAutoTradeOpenPositions());
        evidence.put("evStatus", preview.evStatus());
        evidence.put("runtimeEvidenceStatus", preview.runtimeEvidenceStatus());
        evidence.put("ocoPreflightStatus", preview.ocoPreflightStatus());
        evidence.put("duplicateBarStatus", preview.duplicateBarStatus());
        evidence.put("isDistinctOpportunity", preview.distinctOpportunity());
        evidence.put("currentSignalDecision", preview.currentSignalDecision());
        evidence.put("currentSignalReason", preview.currentSignalReason());
        evidence.put("noCurrentBuyCandidateReason", preview.noCurrentBuyCandidateReason());
        evidence.put("signalProximityState", warningValue(warnings, "signalProximityState"));
        evidence.put("signalThresholdGap", warningValue(warnings, "signalThresholdGap"));
        evidence.put("signalThresholdGapPct", warningValue(warnings, "signalThresholdGapPct"));
        evidence.put("nextRequiredAction", warningValue(warnings, "nextRequiredAction"));
        evidence.put("staleTinyLiveSlotReleaseEligible", containsAny(warnings, "staleTinyLiveSlotReleaseEligible=true",
                "OPEN_TINY_LIVE_POSITION_STALE_SLOT_RELEASE_ELIGIBLE"));
        evidence.put("loopState", value(loop, "currentState"));
        evidence.put("dailyCapStatus", value(loop, "dailyCapStatus"));
        evidence.put("orderSent", false);
        return new Row(TINY_LIVE_STRATEGY_ID, "TINY_LIVE_AUTO_EXPLORATION", classification, reason,
                distinct(blockers), distinct(warnings), evidence);
    }

    private Row scoreBuyPrePositionRow(String symbol) {
        JsonNode executionPreview = readJson(safe("scoreBuyExecutionPreview", () ->
                scoreBuyExecutionPreviewService.preview(symbol, SCORE_BUY_STRATEGY_ID)));
        JsonNode status = readJson(safe("scoreBuyAutoExecutionStatus", () ->
                scoreBuyAutoExecutionService.status(symbol, SCORE_BUY_STRATEGY_ID)));
        String capital = safe("capitalAllocation", () -> capitalAllocationPolicyPreviewService.preview(symbol));

        List<String> blockers = arrayText(status.path("blockers"));
        List<String> warnings = arrayText(status.path("warnings"));
        addArray(executionPreview.path("blockers"), blockers);
        addArray(executionPreview.path("warnings"), warnings);
        if ("true".equalsIgnoreCase(value(capital, "missedOpportunityDueToCapitalSegmentation"))) {
            warnings.add("CAPITAL_SEGMENTATION_MISSED_OPPORTUNITY_RISK");
        }

        String classification = classifyScoreBuy(executionPreview, status, blockers, capital);
        String reason = switch (classification) {
            case "BUG_ENTRY_DEDUP_TOO_COARSE" -> "Coarse EntryDedup would block while staged add-budget policy says a bounded add could proceed.";
            case "BUG_SCOPE_LEAK" -> "SCORE_BUY daily cap reports reached while strategy-scoped pre-position orders are below maxOrdersPerDay.";
            case "BUG_CAPITAL_MISREAD" -> "Capital preview suggests deployable/Earn capital is being misread as unavailable.";
            case "VALID_DAILY_CAP_WAIT" -> "SCORE_BUY pre-position correctly waits because its own daily cap is reached.";
            case "VALID_SIGNAL_NOT_READY" -> "SCORE_BUY pre-position correctly waits because forming-day/pre-position readiness is not active.";
            case "VALID_OCO_BLOCK" -> "SCORE_BUY pre-position correctly blocks because OCO preflight is not passing.";
            case "VALID_HARD_SAFETY_BLOCK" -> "SCORE_BUY pre-position is blocked by a non-bypassable safety gate.";
            case "MISSED_OPPORTUNITY_RISK" -> "SCORE_BUY is near opportunity but still blocked by non-critical readiness/scaling conditions.";
            default -> "SCORE_BUY no-buy reason is consistent with staged safety/readiness gates.";
        };

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("scope", "SCORE_BUY_PRE_POSITION_STRATEGY_SYMBOL");
        evidence.put("executionPolicy", text(status, "executionPolicy", text(executionPreview, "executionPolicy", "UNKNOWN")));
        evidence.put("scoreBuyFormingState", text(status, "scoreBuyFormingState", text(executionPreview, "scoreBuyFormingState", "UNKNOWN")));
        evidence.put("dailyCapScope", text(status, "dailyCapScope", "UNKNOWN"));
        evidence.put("primaryNoBuyReason", text(status, "primaryNoBuyReason", "UNKNOWN"));
        copyOptional(status, evidence, "primaryBlockers");
        copyOptional(status, evidence, "secondaryBlockers");
        copyOptional(status, evidence, "capacityBlockers");
        evidence.put("blockingInterpretation", text(status, "blockingInterpretation", "UNKNOWN"));
        evidence.put("scoreBuyPrePositionOrdersToday", status.path("scoreBuyPrePositionOrdersToday").asLong(-1));
        evidence.put("maxOrdersPerDay", status.path("maxOrdersPerDay").asLong(-1));
        evidence.put("openSameThesisPositions", status.path("openSameThesisPositions").asInt(-1));
        evidence.put("maxOpenPositions", status.path("maxOpenPositions").asInt(-1));
        evidence.put("stagedBudgetEnforced", status.path("stagedBudgetEnforced").asBoolean(false));
        evidence.put("entryDedupMismatch", executionPreview.path("entryDedupMismatch").asBoolean(false));
        evidence.put("coarseEntryDedupWouldBlock", executionPreview.path("coarseEntryDedupWouldBlock").asBoolean(false));
        evidence.put("stagedAddPolicyWouldAllow", executionPreview.path("stagedAddPolicyWouldAllow").asBoolean(false));
        evidence.put("missedOpportunityRisk", text(executionPreview, "missedOpportunityRisk", "UNKNOWN"));
        evidence.put("capitalSegmentationRisk", value(capital, "missedOpportunityDueToCapitalSegmentation"));
        evidence.put("orderSent", false);
        return new Row(SCORE_BUY_STRATEGY_ID, "SCORE_BUY_PRE_POSITION", classification, reason,
                distinct(blockers), distinct(warnings), evidence);
    }

    private Row scoreBuyPostScoutAddRow(String symbol) {
        JsonNode status = readJson(safe("scoreBuyPostScoutAutoAddStatus", () ->
                scoreBuyPostScoutAutoAddExecutionService.status(symbol, SCORE_BUY_STRATEGY_ID)));

        List<String> blockers = arrayText(status.path("blockers"));
        List<String> warnings = arrayText(status.path("warnings"));
        String classification = classifyScoreBuyPostScout(status, blockers);
        String reason = switch (classification) {
            case "WATCH_SCORE_BUY_CAPACITY_LIMITED_ADD" -> "SCORE_BUY post-scout add conditions are active, but daily/open-position/budget capacity prevents another bounded add.";
            case "VALID_DAILY_CAP_WAIT" -> "SCORE_BUY post-scout add correctly waits because its scoped daily cap is reached.";
            case "VALID_BUDGET_WAIT" -> "SCORE_BUY post-scout add correctly waits because the bounded post-scout add budget is exhausted.";
            case "VALID_SIGNAL_NOT_READY" -> "SCORE_BUY post-scout add correctly waits because pullback/confirmation add conditions are not active.";
            case "VALID_OCO_BLOCK" -> "SCORE_BUY post-scout add correctly blocks because existing/proposed OCO protection is not healthy.";
            case "VALID_HARD_SAFETY_BLOCK" -> "SCORE_BUY post-scout add is blocked by a non-bypassable safety/readiness gate.";
            case "MISSED_OPPORTUNITY_RISK" -> "SCORE_BUY post-scout add appears ready but is not progressing; review scheduler/execution state.";
            default -> "SCORE_BUY post-scout add no-buy reason is consistent with current staged safety/readiness gates.";
        };

        JsonNode preview = status.path("postScoutPreview");
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("scope", "SCORE_BUY_POST_SCOUT_ADD_STRATEGY_SYMBOL");
        evidence.put("executionEligible", status.path("executionEligible").asBoolean(false));
        evidence.put("wouldExecute", status.path("wouldExecute").asBoolean(false));
        evidence.put("postScoutManagementState", text(status, "postScoutManagementState", "UNKNOWN"));
        evidence.put("addOnType", text(status, "addOnType", "UNKNOWN"));
        evidence.put("suggestedAddNotionalUsdt", text(status, "suggestedAddNotionalUsdt", "0"));
        evidence.put("primaryNoBuyReason", text(status, "primaryNoBuyReason", "UNKNOWN"));
        copyOptional(status, evidence, "primaryBlockers");
        copyOptional(status, evidence, "secondaryBlockers");
        copyOptional(status, evidence, "capacityBlockers");
        evidence.put("blockingInterpretation", text(status, "blockingInterpretation", "UNKNOWN"));
        evidence.put("scoreBuyPostScoutAddOrdersToday", status.path("scoreBuyPostScoutAddOrdersToday").asLong(-1));
        evidence.put("maxOrdersPerDay", status.path("maxOrdersPerDay").asLong(-1));
        evidence.put("openSameThesisPositions", status.path("openSameThesisPositions").asInt(-1));
        evidence.put("maxOpenPositions", status.path("maxOpenPositions").asInt(-1));
        evidence.put("existingOcoHealth", text(status, "existingOcoHealth", "UNKNOWN"));
        evidence.put("remainingPostScoutAddBudgetUsdt", text(preview, "remainingPostScoutAddBudgetUsdt",
                text(preview, "remainingSameThesisBudgetUsdt", "UNKNOWN")));
        evidence.put("postScoutAddBudgetLimitUsdt", text(preview, "postScoutAddBudgetLimitUsdt", "UNKNOWN"));
        evidence.set("marketReadiness", preview.path("marketReadiness").deepCopy());
        evidence.put("orderSent", false);
        return new Row(SCORE_BUY_STRATEGY_ID, "SCORE_BUY_POST_SCOUT_ADD", classification, reason,
                distinct(blockers), distinct(warnings), evidence);
    }

    private void copyOptional(JsonNode source, ObjectNode target, String key) {
        if (source.has(key)) {
            target.set(key, source.path(key).deepCopy());
        }
    }

    private Row scoreBuyConfirmedDeployRow(String symbol) {
        JsonNode status = readJson(safe("scoreBuyConfirmedDeployAutoExecutionStatus", () ->
                scoreBuyConfirmedDeployAutoExecutionService.status(symbol, SCORE_BUY_STRATEGY_ID)));

        List<String> blockers = arrayText(status.path("blockers"));
        List<String> warnings = arrayText(status.path("warnings"));
        String classification = classifyScoreBuyConfirmedDeploy(status, blockers);
        String reason = switch (classification) {
            case "VALID_DAILY_CAP_WAIT" -> "SCORE_BUY confirmed deploy correctly waits because its scoped daily cap is reached.";
            case "VALID_SIGNAL_NOT_READY" -> "SCORE_BUY confirmed deploy correctly waits because the official daily #485 thesis is not confirmed.";
            case "VALID_OCO_BLOCK" -> "SCORE_BUY confirmed deploy correctly blocks because OCO protection is not healthy or not feasible.";
            case "VALID_HARD_SAFETY_BLOCK" -> "SCORE_BUY confirmed deploy is blocked by a non-bypassable safety/readiness gate.";
            case "MISSED_OPPORTUNITY_RISK" -> "SCORE_BUY confirmed deploy appears ready but is not progressing; review scheduler/execution state.";
            default -> "SCORE_BUY confirmed deploy no-buy reason is consistent with current staged safety/readiness gates.";
        };

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("scope", "SCORE_BUY_CONFIRMED_DEPLOY_STRATEGY_SYMBOL");
        evidence.put("executionEligible", status.path("executionEligible").asBoolean(false));
        evidence.put("wouldExecute", status.path("wouldExecute").asBoolean(false));
        evidence.put("dailyScoreBuyConfirmed", status.path("dailyScoreBuyConfirmed").asBoolean(false));
        evidence.put("confirmedDeployPolicy", text(status, "confirmedDeployPolicy", "UNKNOWN"));
        evidence.put("firstTrancheNotionalUsdt", text(status, "firstTrancheNotionalUsdt", "0"));
        evidence.put("primaryNoBuyReason", text(status, "primaryNoBuyReason", "UNKNOWN"));
        copyOptional(status, evidence, "primaryBlockers");
        copyOptional(status, evidence, "secondaryBlockers");
        copyOptional(status, evidence, "capacityBlockers");
        evidence.put("blockingInterpretation", text(status, "blockingInterpretation", "UNKNOWN"));
        evidence.put("scoreBuyConfirmedDeployOrdersToday", status.path("scoreBuyConfirmedDeployOrdersToday").asLong(-1));
        evidence.put("maxOrdersPerDay", status.path("maxOrdersPerDay").asLong(-1));
        evidence.put("openSameThesisPositions", status.path("openSameThesisPositions").asInt(-1));
        evidence.put("maxOpenPositions", status.path("maxOpenPositions").asInt(-1));
        evidence.put("existingOcoHealth", text(status, "existingOcoHealth", "UNKNOWN"));
        evidence.put("orderSent", false);
        return new Row(SCORE_BUY_STRATEGY_ID, "SCORE_BUY_CONFIRMED_DEPLOY", classification, reason,
                distinct(blockers), distinct(warnings), evidence);
    }

    private String classifyTinyLive(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                    List<String> blockers,
                                    List<String> warnings,
                                    String exploration,
                                    String loop) {
        if (blockers.contains("MAX_TINY_LIVE_ORDERS_TODAY_REACHED")
                && preview.maxOrdersToday() > 0
                && preview.autoTradesToday() < preview.maxOrdersToday()) {
            return "BUG_SCOPE_LEAK";
        }
        boolean staleSlotReleased = containsAny(warnings, "staleTinyLiveSlotReleaseEligible=true",
                "OPEN_TINY_LIVE_POSITION_STALE_SLOT_RELEASE_ELIGIBLE");
        if ((preview.currentSameStrategyTinyLiveOpenPositions() > 0 && !staleSlotReleased)
                || containsAny(blockers, "OPEN_TINY_LIVE_POSITION")) {
            return "VALID_OPEN_POSITION_WAIT";
        }
        if (blockers.contains("NO_CURRENT_BUY_CANDIDATE") || containsAny(blockers, "NO_CURRENT_BUY_CANDIDATE")) {
            if (containsAny(warnings, "signalProximityState=NEAR_BUY_THRESHOLD",
                    "signalProximityState=NEAR_BUY_BELOW_THRESHOLD")) {
                return "WATCH_SIGNAL_NEAR_BUY_THRESHOLD";
            }
            return "VALID_SIGNAL_NOT_READY";
        }
        if (containsAny(blockers, "EV_SAMPLE_MISSING")) {
            return "VALID_EV_SAMPLE_MISSING";
        }
        if (blockers.contains("EXPECTED_VALUE_GATE_NOT_PASSED") || containsAny(blockers, "EV_FAIL")) {
            return "VALID_EV_FAIL";
        }
        if (blockers.contains("MAX_TINY_LIVE_ORDERS_TODAY_REACHED") || containsAny(blockers, "DAILY_EXPLORATION_CAP_REACHED")) {
            return "VALID_DAILY_CAP_WAIT";
        }
        if (blockers.contains("OCO_PREFLIGHT_FAILED") || containsAny(blockers, "OCO_FAIL", "OCO_HEALTH_ABNORMAL")) {
            return "VALID_OCO_BLOCK";
        }
        if (blockers.contains("RUNTIME_EVIDENCE_NOT_AVAILABLE") || containsAny(blockers, "RUNTIME_EVIDENCE")) {
            return "VALID_HARD_SAFETY_BLOCK";
        }
        if (containsAny(blockers, "DATA_FRESHNESS", "STALE")) {
            return "BUG_STALE_DATA";
        }
        if (!blockers.isEmpty()) {
            return "VALID_HARD_SAFETY_BLOCK";
        }
        if (!"READY_TO_EXPLORE".equals(value(loop, "currentState"))
                && !"true".equalsIgnoreCase(value(exploration, "eligible"))) {
            return "VALID_SIGNAL_NOT_READY";
        }
        return "MISSED_OPPORTUNITY_RISK";
    }

    private boolean autonomousOpportunityEligible(Row row) {
        return row != null
                && row.blockers().isEmpty()
                && "MISSED_OPPORTUNITY_RISK".equals(row.classification());
    }

    private void sanitizeTinyLiveContextBlockers(TinyLiveMinimumOrderPreviewService.PreviewResult preview,
                                                 List<String> blockers,
                                                 List<String> warnings,
                                                 String loop) {
        int before = blockers.size();
        if (preview.ocoPreflightStatus() != null && preview.ocoPreflightStatus().startsWith("PASS")) {
            blockers.removeIf(v -> contains(v, "OCO_PREFLIGHT_FAIL") || contains(v, "OCO_PREFLIGHT_FAILED"));
        }
        boolean noCurrentBuyCandidate = containsAny(blockers, "NO_CURRENT_BUY_CANDIDATE");
        if (noCurrentBuyCandidate
                && "NOT_READY_MISSING_ENTRY_TP_SL".equalsIgnoreCase(preview.ocoPreflightStatus())) {
            blockers.removeIf(v -> contains(v, "OCO_PREFLIGHT_FAIL") || contains(v, "OCO_PREFLIGHT_FAILED"));
            String warning = "ocoPreflightPendingUntilBuyCandidate=" + preview.ocoPreflightStatus();
            if (!warnings.contains(warning)) {
                warnings.add(warning);
            }
        }
        if (preview.runtimeEvidenceStatus() != null
                && preview.runtimeEvidenceStatus().toUpperCase(Locale.ROOT).startsWith("AVAILABLE_CANONICAL")) {
            blockers.removeIf(v -> contains(v, "RUNTIME_EVIDENCE"));
        }
        if ("NO_DUPLICATE_BAR".equalsIgnoreCase(preview.duplicateBarStatus()) && preview.distinctOpportunity()) {
            blockers.removeIf(v -> contains(v, "DUPLICATE_BAR"));
        }
        if (preview.maxOrdersToday() > 0 && preview.autoTradesToday() < preview.maxOrdersToday()
                && contains(value(loop, "dailyCapStatus"), "available=true")) {
            blockers.removeIf(v -> contains(v, "DAILY_EXPLORATION_CAP_REACHED")
                    || contains(v, "DAILY_CAP_REACHED"));
        }
        int removed = before - blockers.size();
        if (removed > 0) {
            warnings.add("CONTEXT_BLOCKERS_SANITIZED_BY_CANONICAL_TINY_LIVE_PREVIEW:" + removed);
        }
    }

    private String classifyScoreBuy(JsonNode executionPreview,
                                    JsonNode status,
                                    List<String> blockers,
                                    String capital) {
        if (executionPreview.path("entryDedupMismatch").asBoolean(false)) {
            return "BUG_ENTRY_DEDUP_TOO_COARSE";
        }
        long ordersToday = status.path("scoreBuyPrePositionOrdersToday").asLong(-1);
        long maxOrders = status.path("maxOrdersPerDay").asLong(-1);
        if (containsAny(blockers, "DAILY_SCORE_BUY_PRE_POSITION_CAP_REACHED")
                && maxOrders > 0
                && ordersToday >= 0
                && ordersToday < maxOrders) {
            return "BUG_SCOPE_LEAK";
        }
        if (containsAny(blockers, "OCO_PREFLIGHT", "OCO_FAIL", "OCO_HEALTH_ABNORMAL")) {
            return "VALID_OCO_BLOCK";
        }
        if (containsAny(blockers, "RUNTIME_EVIDENCE", "DATA_FRESHNESS", "SYSTEM_HEALTH", "EXPOSURE_CAP")) {
            return "VALID_HARD_SAFETY_BLOCK";
        }
        if ("true".equalsIgnoreCase(value(capital, "missedOpportunityDueToCapitalSegmentation"))
                && containsAny(blockers, "INSUFFICIENT", "CAPITAL", "BALANCE")) {
            return "BUG_CAPITAL_MISREAD";
        }
        if (scoreBuySignalNotReady(executionPreview, status, blockers)) {
            return "VALID_SIGNAL_NOT_READY";
        }
        if (containsAny(blockers, "DAILY_SCORE_BUY_PRE_POSITION_CAP_REACHED")) {
            return "VALID_DAILY_CAP_WAIT";
        }
        String missedRisk = text(executionPreview, "missedOpportunityRisk", "UNKNOWN");
        if ("HIGH".equalsIgnoreCase(missedRisk) || "ELEVATED".equalsIgnoreCase(missedRisk)) {
            return "MISSED_OPPORTUNITY_RISK";
        }
        if (!blockers.isEmpty()) {
            return "VALID_HARD_SAFETY_BLOCK";
        }
        return "MISSED_OPPORTUNITY_RISK";
    }

    private boolean scoreBuySignalNotReady(JsonNode executionPreview, JsonNode status, List<String> blockers) {
        String formingState = text(status, "scoreBuyFormingState", text(executionPreview, "scoreBuyFormingState", "UNKNOWN"));
        boolean noPrePositionNotional = containsAny(blockers,
                "NO_PRE_POSITION_NOTIONAL",
                "NO_PROPOSED_PRE_POSITION_NOTIONAL",
                "NOTIONAL_BELOW_EXCHANGE_MIN",
                "EXCHANGE_MINIMUM_ORDER_NOT_FEASIBLE");
        boolean notPrePositionState = containsAny(blockers, "FORMING_STATE")
                || "SCOUT_ACTIVE".equalsIgnoreCase(formingState)
                || "WATCHING".equalsIgnoreCase(formingState)
                || "NONE".equalsIgnoreCase(formingState)
                || "INVALIDATED".equalsIgnoreCase(formingState);
        boolean policyNotReady = containsAny(blockers, "EXECUTION_POLICY_NOT_READY");
        return noPrePositionNotional || notPrePositionState || policyNotReady
                || containsAny(blockers, "PRE_POSITION_NOT_READY");
    }

    private String classifyScoreBuyPostScout(JsonNode status, List<String> blockers) {
        long ordersToday = status.path("scoreBuyPostScoutAddOrdersToday").asLong(-1);
        long maxOrders = status.path("maxOrdersPerDay").asLong(-1);
        boolean dailyCapReached = containsAny(blockers, "DAILY_SCORE_BUY_POST_SCOUT_ADD_CAP_REACHED");
        if (containsAny(blockers, "OCO_PREFLIGHT", "OCO_FAIL", "OCO_HEALTH_ABNORMAL")) {
            return "VALID_OCO_BLOCK";
        }
        if (containsAny(blockers, "RUNTIME_EVIDENCE", "DATA_FRESHNESS", "SYSTEM_HEALTH", "EXPOSURE_CAP", "EXACT_DUPLICATE")) {
            return "VALID_HARD_SAFETY_BLOCK";
        }
        if (dailyCapReached
                && dailyCapAtConfiguredMax(status)) {
            return dailyCapOnlyPostScoutWait(status, blockers) ? "VALID_DAILY_CAP_WAIT" : "VALID_SIGNAL_NOT_READY";
        }
        if (postScoutMarketReadinessActive(status) && containsAny(blockers,
                "DAILY_SCORE_BUY_POST_SCOUT_ADD_CAP_REACHED",
                "SAME_THESIS_OPEN_POSITION_LIMIT_REACHED",
                "POST_SCOUT_STAGED_ADD_BUDGET_BELOW_EXCHANGE_MIN",
                "SAME_THESIS_STAGED_ADD_BUDGET_BELOW_EXCHANGE_MIN",
                "NOTIONAL_BELOW_EXCHANGE_MIN")) {
            return "WATCH_SCORE_BUY_CAPACITY_LIMITED_ADD";
        }
        if (dailyCapReached
                && maxOrders > 0
                && ordersToday >= maxOrders) {
            return dailyCapOnlyPostScoutWait(status, blockers) ? "VALID_DAILY_CAP_WAIT" : "VALID_SIGNAL_NOT_READY";
        }
        if (containsAny(blockers, "POST_SCOUT_STAGED_ADD_BUDGET_BELOW_EXCHANGE_MIN",
                "SAME_THESIS_STAGED_ADD_BUDGET_BELOW_EXCHANGE_MIN")) {
            return "VALID_BUDGET_WAIT";
        }
        if (status.path("executionEligible").asBoolean(false) || status.path("wouldExecute").asBoolean(false)) {
            return "MISSED_OPPORTUNITY_RISK";
        }
        String state = text(status, "postScoutManagementState", "UNKNOWN");
        if (containsAny(blockers, "POST_SCOUT_ADD_NOT_ELIGIBLE", "POST_SCOUT_STATE_NOT_EXECUTABLE", "NOTIONAL_BELOW_EXCHANGE_MIN")
                || state.startsWith("HOLD_")
                || state.startsWith("WAIT_")
                || "NO_OPEN_SCOUT".equalsIgnoreCase(state)) {
            return "VALID_SIGNAL_NOT_READY";
        }
        if (!blockers.isEmpty()) {
            return "VALID_HARD_SAFETY_BLOCK";
        }
        return "VALID_SIGNAL_NOT_READY";
    }

    private boolean postScoutMarketReadinessActive(JsonNode status) {
        JsonNode readiness = status.path("postScoutPreview").path("marketReadiness");
        return readiness.path("pullbackAddReady").asBoolean(false)
                || readiness.path("pullbackCooldownAddReady").asBoolean(false)
                || readiness.path("partialReversalPersistenceReady").asBoolean(false)
                || readiness.path("confirmationAddReady").asBoolean(false);
    }

    private boolean dailyCapOnlyPostScoutWait(JsonNode status, List<String> blockers) {
        if (status.path("dailyCapOnlyBlocker").asBoolean(false)
                || status.path("eligibleAfterDailyCapResetPreview").asBoolean(false)
                || status.path("wouldExecuteAfterDailyCapReset").asBoolean(false)) {
            return true;
        }
        String state = text(status, "postScoutManagementState", "UNKNOWN");
        if (state.startsWith("WAIT_")
                || state.startsWith("HOLD_")
                || "NO_OPEN_SCOUT".equalsIgnoreCase(state)
                || containsAny(blockers,
                "POST_SCOUT_ADD_NOT_ELIGIBLE",
                "POST_SCOUT_STATE_NOT_EXECUTABLE",
                "NOTIONAL_BELOW_EXCHANGE_MIN")) {
            return false;
        }
        return blockers.stream().allMatch(blocker ->
                blocker != null && blocker.contains("DAILY_SCORE_BUY_POST_SCOUT_ADD_CAP_REACHED"));
    }

    private boolean dailyCapAtConfiguredMax(JsonNode status) {
        JsonNode audit = status.path("dailyCapAudit");
        long ordersToday = audit.path("ordersToday").asLong(status.path("scoreBuyPostScoutAddOrdersToday").asLong(-1));
        long maxConfigured = audit.path("maxConfiguredOrdersPerDayIncludingAdaptiveAndResidual").asLong(-1);
        boolean breachSuspected = audit.path("dailyCapBreachSuspected").asBoolean(false);
        return maxConfigured > 0 && ordersToday >= maxConfigured && !breachSuspected;
    }

    private String classifyScoreBuyConfirmedDeploy(JsonNode status, List<String> blockers) {
        long ordersToday = status.path("scoreBuyConfirmedDeployOrdersToday").asLong(-1);
        long maxOrders = status.path("maxOrdersPerDay").asLong(-1);
        if (containsAny(blockers, "DAILY_SCORE_BUY_CONFIRMED_DEPLOY_CAP_REACHED")
                && maxOrders > 0
                && ordersToday >= maxOrders) {
            return "VALID_DAILY_CAP_WAIT";
        }
        if (containsAny(blockers, "OCO_PREFLIGHT", "OCO_FAIL", "OCO_HEALTH_ABNORMAL")) {
            return "VALID_OCO_BLOCK";
        }
        if (containsAny(blockers, "RUNTIME_EVIDENCE", "DATA_FRESHNESS", "SYSTEM_HEALTH", "EXPOSURE_CAP", "EXACT_DUPLICATE")) {
            return "VALID_HARD_SAFETY_BLOCK";
        }
        if (status.path("executionEligible").asBoolean(false) || status.path("wouldExecute").asBoolean(false)) {
            return "MISSED_OPPORTUNITY_RISK";
        }
        if (!status.path("dailyScoreBuyConfirmed").asBoolean(false)
                || containsAny(blockers, "DAILY_SCORE_BUY_NOT_CONFIRMED", "CONFIRMED_DEPLOY_NOT_ELIGIBLE", "NOTIONAL_BELOW_EXCHANGE_MIN")) {
            return "VALID_SIGNAL_NOT_READY";
        }
        if (!blockers.isEmpty()) {
            return "VALID_HARD_SAFETY_BLOCK";
        }
        return "VALID_SIGNAL_NOT_READY";
    }

    private HighForwardReturnNoBuyScan scanHighForwardReturnNoBuy(String symbol, int hours) {
        ArrayNode examples = objectMapper.createArrayNode();
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime since = now.minusHours(hours);
            LocalDateTime matureUntil = now.minusHours(1);
            if (!matureUntil.isAfter(since)) {
                return new HighForwardReturnNoBuyScan(0, examples);
            }

            List<Map<String, Object>> candidates = new ArrayList<>();
            candidates.addAll(queryNoBuyRuntimeEvidence(symbol, since, matureUntil));
            candidates.addAll(queryNoBuyDecisionAudit(symbol, since, matureUntil));
            if (candidates.isEmpty()) {
                return new HighForwardReturnNoBuyScan(0, examples);
            }

            LocalDateTime minTime = candidates.stream()
                    .map(row -> asTime(row.get("evidence_time")))
                    .filter(time -> time != null)
                    .min(LocalDateTime::compareTo)
                    .orElse(null);
            LocalDateTime maxTime = candidates.stream()
                    .map(row -> asTime(row.get("evidence_time")))
                    .filter(time -> time != null)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
            if (minTime == null || maxTime == null) {
                return new HighForwardReturnNoBuyScan(0, examples);
            }

            NavigableMap<LocalDateTime, BigDecimal> closes = loadOneMinuteCloses(
                    symbol, minTime.minusMinutes(5), maxTime.plusHours(1).plusMinutes(5));
            if (closes.isEmpty()) {
                return new HighForwardReturnNoBuyScan(0, examples);
            }

            int count = 0;
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> row : candidates) {
                LocalDateTime t = asTime(row.get("evidence_time"));
                if (t == null || t.isAfter(matureUntil)) {
                    continue;
                }
                if (!seen.add(noBuyDedupeKey(row, t))) {
                    continue;
                }
                BigDecimal entry = closeAtOrAfter(closes, t);
                BigDecimal horizon = closeAtOrAfter(closes, t.plusHours(1));
                if (entry == null || horizon == null || entry.signum() <= 0) {
                    continue;
                }
                BigDecimal returnPct = horizon.subtract(entry)
                        .divide(entry, 8, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                if (returnPct.compareTo(HIGH_FORWARD_RETURN_NO_BUY_THRESHOLD_PCT) < 0) {
                    continue;
                }
                count++;
                if (examples.size() < 8) {
                    ObjectNode example = examples.addObject();
                    example.put("rowSource", text(row.get("row_source")));
                    example.put("decisionId", text(row.get("decision_id")));
                    example.put("evidenceTime", t.toString());
                    example.put("strategyId", text(row.get("strategy_id")));
                    example.put("selectedAction", text(row.get("selected_action")));
                    example.put("terminalBlocker", text(row.get("terminal_blocker")));
                    example.put("blockerReason", text(row.get("blocker_reason")));
                    example.put("entryPrice", money(entry));
                    example.put("horizonPrice1h", money(horizon));
                    example.put("forwardReturn1hPct", money(returnPct));
                }
            }
            return new HighForwardReturnNoBuyScan(count, examples);
        } catch (Exception e) {
            ObjectNode example = examples.addObject();
            example.put("scanError", truncate(e.getMessage(), 240));
            return new HighForwardReturnNoBuyScan(0, examples);
        }
    }

    private List<Map<String, Object>> queryNoBuyRuntimeEvidence(String symbol,
                                                                LocalDateTime since,
                                                                LocalDateTime matureUntil) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(10000) */
                           'RUNTIME_EVIDENCE' row_source,
                           e.id decision_id,
                           e.evidence_time,
                           e.strategy_id,
                           e.selected_action,
                           e.final_outcome,
                           e.policy_mode,
                           e.terminal_blocker,
                           e.blocker_reason,
                           e.reason,
                           e.order_sent
                    FROM bt_runtime_decision_evidence e FORCE INDEX (idx_rt_decision_evidence_symbol_time)
                    WHERE e.symbol = ?
                      AND e.evidence_time >= ?
                      AND e.evidence_time <= ?
                      AND (e.order_sent = 0 OR e.order_sent IS NULL)
                    ORDER BY e.evidence_time DESC, e.id DESC
                    LIMIT 500
                    """, new Object[]{symbol, since, matureUntil});
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> queryNoBuyDecisionAudit(String symbol,
                                                              LocalDateTime since,
                                                              LocalDateTime matureUntil) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(10000) */
                           'DECISION_AUDIT' row_source,
                           a.id decision_id,
                           a.event_time evidence_time,
                           a.strategy_id,
                           a.outcome selected_action,
                           a.outcome final_outcome,
                           NULL policy_mode,
                           a.blocker terminal_blocker,
                           a.reason blocker_reason,
                           a.reason,
                           COALESCE(s.auto_traded, 0) order_sent
                    FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
                    LEFT JOIN bt_live_signal s ON s.id = a.live_signal_id
                    WHERE a.symbol = ?
                      AND a.event_time >= ?
                      AND a.event_time <= ?
                      AND a.event_type IN ('SIGNAL_EVAL','FILTER_BLOCK','ENTRY_SKIP')
                      AND COALESCE(s.auto_traded, 0) = 0
                    ORDER BY a.event_time DESC, a.id DESC
                    LIMIT 500
                    """, new Object[]{symbol, since, matureUntil});
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    private NavigableMap<LocalDateTime, BigDecimal> loadOneMinuteCloses(String symbol,
                                                                        LocalDateTime from,
                                                                        LocalDateTime to) {
        NavigableMap<LocalDateTime, BigDecimal> closes = new TreeMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(10000) */
                           open_time, close_price
                    FROM md_kline
                    WHERE symbol = ?
                      AND interval_code = '1m'
                      AND source = 'okx'
                      AND open_time >= ?
                      AND open_time <= ?
                    ORDER BY open_time ASC
                    """, new Object[]{symbol, from, to});
            if (rows == null) {
                return closes;
            }
            for (Map<String, Object> row : rows) {
                LocalDateTime openTime = asTime(row.get("open_time"));
                BigDecimal close = asDecimal(row.get("close_price"));
                if (openTime != null && close != null) {
                    closes.put(openTime, close);
                }
            }
        } catch (Exception e) {
            return new TreeMap<>();
        }
        return closes;
    }

    private BigDecimal closeAtOrAfter(NavigableMap<LocalDateTime, BigDecimal> closes, LocalDateTime time) {
        Map.Entry<LocalDateTime, BigDecimal> entry = closes.ceilingEntry(time);
        if (entry != null) {
            return entry.getValue();
        }
        entry = closes.floorEntry(time);
        return entry == null ? null : entry.getValue();
    }

    private String noBuyDedupeKey(Map<String, Object> row, LocalDateTime time) {
        long minuteBucket = time.toEpochSecond(ZoneOffset.UTC) / 60;
        return text(row.get("strategy_id")) + "|" + minuteBucket + "|" + text(row.get("selected_action"))
                + "|" + text(row.get("terminal_blocker")) + "|" + text(row.get("blocker_reason"));
    }

    private LocalDateTime asTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return LocalDateTime.parse(text.replace("Z", ""));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? "N/A" : String.valueOf(value);
    }

    private String money(BigDecimal value) {
        if (value == null) {
            return "N/A";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String recommendedFix(List<Row> rows, int genericStagedAddWouldAllowGroups, int highForwardReturnNoBuyCount) {
        if (rows.stream().anyMatch(r -> "BUG_SCOPE_LEAK".equals(r.classification()))) {
            return "Fix cap-scope leakage before changing trading thresholds.";
        }
        if (rows.stream().anyMatch(r -> "BUG_ENTRY_DEDUP_TOO_COARSE".equals(r.classification()))) {
            return "Replace coarse same-strategy EntryDedup with staged add-budget + exact opportunity hash checks.";
        }
        if (rows.stream().anyMatch(r -> "BUG_CAPITAL_MISREAD".equals(r.classification()))) {
            return "Fix capital snapshot to separate deployable trading USDT from Earn capital without treating all funds as unavailable.";
        }
        if (rows.stream().anyMatch(r -> "MISSED_OPPORTUNITY_RISK".equals(r.classification()))) {
            return "Review bounded SCORE_BUY/tiny-live execution scheduler and scoped caps; do not relax hard safety gates.";
        }
        if (rows.stream().anyMatch(r -> "WATCH_SCORE_BUY_CAPACITY_LIMITED_ADD".equals(r.classification()))) {
            return "Review SCORE_BUY staged add capacity: signal/readiness exists, but daily/open-position/budget caps are preventing more exposure.";
        }
        if (genericStagedAddWouldAllowGroups > 0) {
            return "Review generic EntryDedup staged-add candidates; keep read-only until strategy-specific budgets are approved.";
        }
        if (highForwardReturnNoBuyCount > 0) {
            return "Review near-threshold/no-buy candidates with high 1h forward return; consider bounded pre-threshold exploration only if hard gates remain intact.";
        }
        return "No regression fix required; current no-buy reasons are expected under configured gates.";
    }

    private ArrayNode rowsArray(List<Row> rows) {
        ArrayNode array = objectMapper.createArrayNode();
        for (Row row : rows) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("strategyId", row.strategyId());
            node.put("path", row.path());
            node.put("classification", row.classification());
            node.put("reason", row.reason());
            node.set("blockers", stringArray(row.blockers()));
            node.set("warnings", stringArray(row.warnings()));
            node.set("evidence", row.evidence());
            array.add(node);
        }
        return array;
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    private int countClassification(List<Row> rows, String prefixOrExact) {
        int count = 0;
        for (Row row : rows) {
            if (row.classification().equals(prefixOrExact) || row.classification().startsWith(prefixOrExact)) {
                count++;
            }
        }
        return count;
    }

    private void addBlockersFromText(String text, List<String> blockers) {
        String value = value(text, "blockers");
        if (value == null || "N/A".equals(value)) return;
        for (String part : value.replace("[", "").replace("]", "").split(",")) {
            String clean = part.trim();
            if (!clean.isBlank()) blockers.add(clean);
        }
    }

    private void addArray(JsonNode node, List<String> values) {
        if (node == null || !node.isArray()) return;
        node.forEach(v -> values.add(v.asText()));
    }

    private List<String> arrayText(JsonNode node) {
        List<String> out = new ArrayList<>();
        addArray(node, out);
        return out;
    }

    private List<String> distinct(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String warningValue(List<String> warnings, String key) {
        if (warnings == null || key == null || key.isBlank()) {
            return "N/A";
        }
        String prefix = key + "=";
        for (String warning : warnings) {
            if (warning != null && warning.startsWith(prefix)) {
                return warning.substring(prefix.length()).trim();
            }
        }
        return "N/A";
    }

    private boolean containsAny(List<String> values, String... needles) {
        if (values == null) return false;
        for (String value : values) {
            for (String needle : needles) {
                if (contains(value, needle)) return true;
            }
        }
        return false;
    }

    private boolean contains(String value, String needle) {
        return value != null && needle != null
                && value.toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
    }

    private String safe(String section, ThrowingSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return section + "=UNAVAILABLE error=" + truncate(e.getMessage(), 240);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("parseError", truncate(e.getMessage(), 240));
            node.put("raw", truncate(json, 500));
            return node;
        }
    }

    private String text(JsonNode node, String key, String fallback) {
        if (node == null) return fallback;
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return fallback;
        String text = value.asText("");
        return text.isBlank() ? fallback : text;
    }

    private String value(String text, String key) {
        if (text == null) return "N/A";
        Pattern pattern = Pattern.compile("(?m)^\\s*\"?" + Pattern.quote(key) + "\"?\\s*[:=]\\s*(.+)$");
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return "N/A";
        String value = matcher.group(1).trim();
        if (value.endsWith(",")) value = value.substring(0, value.length() - 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        String s = side == null || side.isBlank() ? DEFAULT_SIDE : side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(s) ? DEFAULT_SIDE : s;
    }

    private int normalizeHours(Integer hours) {
        return hours == null ? 24 : Math.max(1, Math.min(hours, 168));
    }

    private String truncate(String value, int max) {
        if (value == null) return "N/A";
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    private record Row(long strategyId,
                       String path,
                       String classification,
                       String reason,
                       List<String> blockers,
                       List<String> warnings,
                       ObjectNode evidence) {
    }

    private record HighForwardReturnNoBuyScan(int count, ArrayNode examples) {
    }
}
