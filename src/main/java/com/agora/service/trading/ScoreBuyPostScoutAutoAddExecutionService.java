package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.TelegramService;
import com.agora.service.meta.DecisionAuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreBuyPostScoutAutoAddExecutionService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final String SIDE = "LONG";
    private static final String INTERVAL = "SB_ADD";
    private static final ZoneId DAILY_CAP_LOCAL_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DAILY_CAP_RESET_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final BigDecimal MIN_NOTIONAL = new BigDecimal("5.00");
    private static final BigDecimal DEFAULT_MAX_LOSS_BUDGET = new BigDecimal("2.00");
    private static final int MAX_BUDGET_AWARE_OPEN_POSITIONS = 16;
    private static final String TIER_PULLBACK = "PULLBACK_BASE";
    private static final String TIER_PARTIAL_REVERSAL = "PARTIAL_REVERSAL_BASE";
    private static final String TIER_CONFIRMATION = "CONFIRMATION_RESERVED";

    private final ScoreBuyPostScoutManagementPolicyService postScoutManagementPolicyService;
    private final OkxTradingService okxTradingService;
    private final OcoOrderStateInspector ocoOrderStateInspector;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper;
    private final Environment env;
    private final ScoreBuyPostScoutAutoAddSchedulerStateService schedulerStateService;
    private final DecisionAuditWriter decisionAuditWriter;
    private final AtomicReference<BlockAuditSnapshot> lastBlockAudit =
            new AtomicReference<>(new BlockAuditSnapshot("", Instant.EPOCH));

    @Transactional(readOnly = true)
    public String status(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        JsonNode preview = readJson(postScoutManagementPolicyService.getStatus(sym, sid));
        Evaluation evaluation = evaluate(preview, sid, false);
        ObjectNode root = baseStatus(preview, evaluation, sid);
        root.put("tool", "getScoreBuyPostScoutAutoAddStatus");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior changed.");
        root.put("orderSent", false);
        root.put("ocoAttached", false);
        root.put("ocoModified", false);
        root.put("writesRuntimeEvidence", false);
        return write(root);
    }

    @Transactional
    public String executeIfEligible(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        JsonNode preview = readJson(postScoutManagementPolicyService.getStatus(sym, sid));
        Evaluation evaluation = evaluate(preview, sid, true);
        if (!evaluation.eligible()) {
            log.info("[ScoreBuyPostScoutAutoAdd] blocked: {}", evaluation.blockers());
            maybeAuditBlockedWritePath(preview, evaluation, sid);
            return write(baseStatus(preview, evaluation, sid));
        }

        BigDecimal notional = money(preview, "suggestedAddNotionalUsdt", BigDecimal.ZERO);
        BigDecimal previewEntry = money(preview, "entry", BigDecimal.ZERO);
        BigDecimal tp = money(preview, "tp", BigDecimal.ZERO);
        BigDecimal sl = money(preview, "sl", BigDecimal.ZERO);

        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(LocalDateTime.now(ZoneOffset.UTC));
        audit.setStrategyId(sid);
        audit.setSymbol(sym);
        audit.setIntervalCode(INTERVAL);
        audit.setEventType("SCORE_BUY_POST_ADD_EXEC");
        audit.setOutcome("STARTED");
        audit.setBlocker("ScoreBuyPostScoutAdd");
        audit.setReason("ORDER_PLACEMENT_STARTED");
        audit.setContextJson(preview.toString());
        audit = decisionAuditRepository.save(audit);

        TradeResult buy;
        try {
            buy = okxTradingService.placeMarketBuy(sym, notional.doubleValue());
        } catch (Exception e) {
            audit.setOutcome("ERROR");
            audit.setReason("ORDER_FAILED: " + truncate(e.getMessage(), 420));
            audit.setContextJson(receipt(preview, null, null, "ORDER_FAILED", e.getMessage(), false,
                    evaluation.currentOpportunityKey()));
            decisionAuditRepository.save(audit);
            return writeExecutionResult(audit, false, false, null, null);
        }

        Long ocoAlgoId = null;
        BtLiveSignal signal = null;
        try {
            ocoAlgoId = okxTradingService.placeOco(sym, buy.getQty(), tp, sl);
            signal = saveSignal(sid, sym, previewEntry, tp, sl, buy, ocoAlgoId,
                    evaluation.addOnType(), evaluation.currentOpportunityKey(), evaluation.executionSlotTag());
            audit.setLiveSignalId(signal.getId());
            audit.setOutcome("PASS");
            audit.setReason("EXECUTED_OCO_ATTACHED");
            audit.setContextJson(receipt(preview, buy, ocoAlgoId, "EXECUTED_OCO_ATTACHED", null, true,
                    evaluation.currentOpportunityKey()));
            decisionAuditRepository.save(audit);
            writeEvidence(audit, signal, preview, buy, ocoAlgoId, "EXECUTED_OCO_ATTACHED",
                    evaluation.currentOpportunityKey());
            telegramService.sendAlert("SCORE_BUY post-scout add executed. symbol=" + sym
                            + " strategyId=" + sid + " addType=" + evaluation.addOnType()
                            + " notional=" + notional + " orderId=" + buy.getOrderId()
                            + " ocoAlgoId=" + ocoAlgoId,
                    false, "ScoreBuyPostScoutAdd", "INFO");
            return writeExecutionResult(audit, true, true, buy, ocoAlgoId);
        } catch (Exception e) {
            audit.setOutcome("ERROR");
            audit.setReason("CRITICAL_UNPROTECTED_SCORE_BUY_POST_SCOUT_ADD: " + truncate(e.getMessage(), 360));
            audit.setContextJson(receipt(preview, buy, ocoAlgoId, "CRITICAL_UNPROTECTED_SCORE_BUY_POST_SCOUT_ADD",
                    e.getMessage(), true, evaluation.currentOpportunityKey()));
            decisionAuditRepository.save(audit);
            if (signal == null) {
                signal = saveSignal(sid, sym, previewEntry, tp, sl, buy, null,
                        evaluation.addOnType(), evaluation.currentOpportunityKey(), evaluation.executionSlotTag());
                audit.setLiveSignalId(signal.getId());
                decisionAuditRepository.save(audit);
            }
            writeEvidence(audit, signal, preview, buy, ocoAlgoId, "CRITICAL_UNPROTECTED_SCORE_BUY_POST_SCOUT_ADD",
                    evaluation.currentOpportunityKey());
            telegramService.sendAlert("CRITICAL_UNPROTECTED_SCORE_BUY_POST_SCOUT_ADD order placed but OCO attach/audit failed. symbol="
                            + sym + " orderId=" + buy.getOrderId() + " error=" + e.getMessage(),
                    false, "ScoreBuyPostScoutAdd", "CRITICAL");
            return writeExecutionResult(audit, true, false, buy, ocoAlgoId);
        }
    }

    private Evaluation evaluate(JsonNode preview, long strategyId, boolean writePath) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        copyArray(preview.path("blockers"), blockers);
        copyArray(preview.path("warnings"), warnings);

        String symbol = text(preview, "symbol", DEFAULT_SYMBOL);
        String state = text(preview, "postScoutManagementState", "UNKNOWN");
        String addOnType = text(preview, "addOnType", "NONE");
        BigDecimal notional = money(preview, "suggestedAddNotionalUsdt", BigDecimal.ZERO);
        BigDecimal maxLoss = money(preview, "maxLossIfWrongUsdt", BigDecimal.ZERO);
        BigDecimal maxNotional = maxNotional();

        if (!DEFAULT_SYMBOL.equals(symbol) || strategyId != DEFAULT_STRATEGY_ID) {
            blockers.add("SCOPE_NOT_ALLOWLISTED");
        }
        if (!preview.path("addOnEligible").asBoolean(false)) {
            blockers.add("POST_SCOUT_ADD_NOT_ELIGIBLE:" + state);
        }
        if (!isExecutablePostScoutState(state)) {
            blockers.add("POST_SCOUT_STATE_NOT_EXECUTABLE:" + state);
        }
        if (notional.compareTo(MIN_NOTIONAL) < 0) {
            blockers.add("NOTIONAL_BELOW_EXCHANGE_MIN");
        }
        if (notional.compareTo(maxNotional) > 0) {
            blockers.add("NOTIONAL_EXCEEDS_SCORE_BUY_POST_SCOUT_ADD_CAP");
        }
        if (maxLoss.compareTo(maxLossBudget()) > 0) {
            blockers.add("MAX_LOSS_EXCEEDS_SCORE_BUY_POST_SCOUT_ADD_BUDGET");
        }
        JsonNode prePositionSummary = preview.path("prePositionExecutionSummary");
        if (!runtimeEvidenceAvailable(text(prePositionSummary, "runtimeEvidenceStatus", "UNKNOWN"))) {
            blockers.add("RUNTIME_EVIDENCE_NOT_AVAILABLE");
        }
        if (!startsWith(text(prePositionSummary, "ocoPreflightStatus", "UNKNOWN"), "PASS")) {
            blockers.add("OCO_PREFLIGHT_NOT_PASS");
        }
        if (prePositionSummary.path("exactDuplicateOpportunity").asBoolean(false)) {
            blockers.add("EXACT_DUPLICATE_OPPORTUNITY");
        }

        OcoHealth ocoHealth = checkExistingOcoHealth(strategyId, symbol);
        if (!ocoHealth.ok()) {
            blockers.add("OCO_HEALTH_ABNORMAL:" + ocoHealth.reason());
        }

        int openSameThesisPositions = liveSignalRepository
                .findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(strategyId).size();
        int effectiveMaxOpenPositions = effectiveMaxOpenPositions(preview);
        if (openSameThesisPositions >= effectiveMaxOpenPositions) {
            blockers.add("SAME_THESIS_OPEN_POSITION_LIMIT_REACHED");
        }
        LocalDateTime dailyCapCountSinceUtc = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        long postScoutOrdersToday = liveSignalRepository.countScoreBuyPostScoutAddTradesSince(
                strategyId, symbol, dailyCapCountSinceUtc);
        String addOnQualityTier = addOnQualityTier(state, addOnType, preview);
        long tierOrdersToday = countTierOrdersToday(strategyId, symbol, dailyCapCountSinceUtc, addOnQualityTier);
        long tierMaxOrdersPerDay = tierMaxOrdersPerDay(addOnQualityTier);
        long microSlotOrdersToday = countMissedAlphaMicroSlotOrdersToday(strategyId, symbol, dailyCapCountSinceUtc);
        if (tierMaxOrdersPerDay > 0 && tierOrdersToday >= tierMaxOrdersPerDay) {
            blockers.add(tierCapBlocker(addOnQualityTier));
        }
        long baseMaxOrdersPerDay = maxOrdersPerDay();
        boolean adaptiveCapApplied = adaptiveDailyCapAllowed(preview, blockers, postScoutOrdersToday,
                baseMaxOrdersPerDay, effectiveMaxOpenPositions, openSameThesisPositions);
        boolean budgetResidualCapApplied = false;
        long effectiveMaxOrdersPerDay = adaptiveCapApplied
                ? baseMaxOrdersPerDay + adaptiveExtraOrdersPerDay()
                : baseMaxOrdersPerDay;
        if (!adaptiveCapApplied && postScoutOrdersToday >= effectiveMaxOrdersPerDay) {
            budgetResidualCapApplied = budgetResidualDailyCapAllowed(preview, blockers, postScoutOrdersToday,
                    baseMaxOrdersPerDay, effectiveMaxOpenPositions, openSameThesisPositions);
            if (budgetResidualCapApplied) {
                effectiveMaxOrdersPerDay = postScoutOrdersToday + 1;
            }
        }
        if (postScoutOrdersToday >= effectiveMaxOrdersPerDay) {
            blockers.add("DAILY_SCORE_BUY_POST_SCOUT_ADD_CAP_REACHED");
        } else if (budgetResidualCapApplied) {
            warnings.add("BUDGET_RESIDUAL_DAILY_CAP_EXTRA_SLOT_USED_FOR_FINAL_POST_SCOUT_ADD");
        } else if (adaptiveCapApplied) {
            warnings.add("ADAPTIVE_DAILY_CAP_EXTRA_SLOT_USED_FOR_HIGH_QUALITY_POST_SCOUT_ADD");
        }
        MissedAlphaMicroSlot missedAlphaMicroSlot = evaluateMissedAlphaMicroSlot(preview, blockers, postScoutOrdersToday,
                effectiveMaxOrdersPerDay, effectiveMaxOpenPositions, openSameThesisPositions,
                addOnQualityTier, microSlotOrdersToday);
        if (missedAlphaMicroSlot.applied()) {
            blockers.removeIf(this::isPostScoutCapBlocker);
            effectiveMaxOrdersPerDay = Math.max(effectiveMaxOrdersPerDay, postScoutOrdersToday + 1);
            warnings.add("MISSED_ALPHA_ADAPTIVE_MICRO_SLOT_USED");
        }
        String executionSlotTag = executionSlotTag(missedAlphaMicroSlot.applied());
        OpportunityDedup dedup = evaluateOpportunityDedup(preview, strategyId, symbol, executionSlotTag);
        if (!dedup.distinct()) {
            blockers.add("SAME_POST_SCOUT_OPPORTUNITY_COOLDOWN");
        }
        if (writePath && dryRun()) {
            blockers.add("DRY_RUN_ENABLED");
        }

        return new Evaluation(blockers.stream().distinct().toList().isEmpty(),
                blockers.stream().distinct().toList(),
                warnings.stream().distinct().toList(),
                state,
                addOnType,
                notional,
                maxNotional,
                maxLoss,
                ocoHealth.reason(),
                openSameThesisPositions,
                effectiveMaxOpenPositions,
                postScoutOrdersToday,
                dailyCapCountSinceUtc,
                addOnQualityTier,
                tierOrdersToday,
                tierMaxOrdersPerDay,
                microSlotOrdersToday,
                missedAlphaMicroSlot.applied(),
                missedAlphaMicroSlot.reason(),
                missedAlphaMicroSlot.recentMissedAlphaCount(),
                baseMaxOrdersPerDay,
                effectiveMaxOrdersPerDay,
                adaptiveCapApplied,
                budgetResidualCapApplied,
                adaptiveCapReason(preview, adaptiveCapApplied, budgetResidualCapApplied,
                        postScoutOrdersToday, baseMaxOrdersPerDay),
                dedup.currentOpportunityKey(),
                dedup.lastOpportunityKey(),
                dedup.lastOpportunityAtUtc(),
                dedup.distinct(),
                dedup.reason(),
                dedup.cooldownMinutes());
    }

    private ObjectNode baseStatus(JsonNode preview, Evaluation evaluation, long strategyId) {
        Instant now = Instant.now();
        Instant nextResetAt = nextDailyCapResetInstant(now);
        long resetMinutesRemaining = Math.max(0, Duration.between(now, nextResetAt).toMinutes());
        boolean dailyCapOnlyBlocker = dailyCapOnlyBlocker(evaluation.blockers());
        boolean schedulerEnabled = enabled();
        boolean schedulerDryRun = dryRun();
        long schedulerFixedDelayMs = schedulerFixedDelayMs();
        long schedulerInitialDelayMs = schedulerInitialDelayMs();
        ObjectNode schedulerState = schedulerStateService.toJson(objectMapper, schedulerEnabled, schedulerDryRun,
                schedulerFixedDelayMs, schedulerInitialDelayMs);
        ObjectNode capResetWatchdog = capResetRecheckWatchdog(evaluation, dailyCapOnlyBlocker, nextResetAt,
                resetMinutesRemaining, schedulerEnabled, schedulerDryRun, schedulerFixedDelayMs, schedulerState);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", text(preview, "symbol", DEFAULT_SYMBOL));
        root.put("strategyId", strategyId);
        root.put("side", SIDE);
        root.put("enabled", enabled());
        root.put("dryRun", dryRun());
        root.put("orderSent", false);
        root.put("ocoAttached", false);
        root.put("executionEligible", evaluation.eligible());
        root.put("wouldExecute", enabled() && !dryRun() && evaluation.eligible());
        root.put("postScoutManagementState", evaluation.state());
        root.put("recommendedAction", text(preview, "recommendedAction", "UNKNOWN"));
        root.put("addOnType", evaluation.addOnType());
        root.put("reason", text(preview, "reason", "UNKNOWN"));
        root.put("scoreBuyFormingState", text(preview, "scoreBuyFormingState", "UNKNOWN"));
        root.put("scoreBuyHoldingState", text(preview, "scoreBuyHoldingState", "UNKNOWN"));
        root.put("holdBtcMode", preview.path("holdBtcMode").asBoolean(false));
        root.put("holdBtcReason", text(preview, "holdBtcReason", "NONE"));
        root.put("autoSellAllowed", false);
        root.put("autoAddAllowed", preview.path("autoAddAllowed").asBoolean(false) && evaluation.eligible());
        root.put("disasterOcoMode", text(preview, "disasterOcoMode", "KEEP_12PCT_HARD_OCO"));
        root.put("eventRiskLevel", text(preview, "eventRiskLevel", "UNKNOWN"));
        root.put("eventRiskMultiplier", preview.path("eventRiskMultiplier").asDouble(1.0));
        root.put("suggestedAddNotionalUsdt", evaluation.notional().stripTrailingZeros().toPlainString());
        root.put("maxNotionalUsdt", evaluation.maxNotional().stripTrailingZeros().toPlainString());
        root.put("entry", text(preview, "entry", "0"));
        root.put("tp", text(preview, "tp", "0"));
        root.put("sl", text(preview, "sl", "0"));
        root.put("maxLossIfWrongUsdt", evaluation.maxLossIfWrong().stripTrailingZeros().toPlainString());
        root.put("maxLossBudgetUsdt", maxLossBudget().stripTrailingZeros().toPlainString());
        root.put("postScoutDuplicateMode", "DISTINCT_OPPORTUNITY_KEY_WITH_COOLDOWN");
        root.put("postScoutOpportunityCooldownMinutes", evaluation.opportunityCooldownMinutes());
        root.put("currentOpportunityKey", evaluation.currentOpportunityKey());
        root.put("lastOpportunityKey", evaluation.lastOpportunityKey());
        root.put("lastOpportunityAtUtc", evaluation.lastOpportunityAtUtc());
        root.put("isDistinctPostScoutOpportunity", evaluation.distinctOpportunity());
        root.put("postScoutDuplicateReason", evaluation.opportunityDedupReason());
        root.put("dailyCapScope", "SCORE_BUY_POST_SCOUT_ADD_STRATEGY_SYMBOL");
        root.put("postScoutDailyCapMode", "TIERED_RESERVED_SLOTS");
        root.put("addOnQualityTier", evaluation.addOnQualityTier());
        root.put("addOnTierOrdersToday", evaluation.addOnTierOrdersToday());
        root.put("addOnTierMaxOrdersPerDay", evaluation.addOnTierMaxOrdersPerDay());
        root.put("pullbackMaxOrdersPerDay", pullbackMaxOrdersPerDay());
        root.put("partialReversalMaxOrdersPerDay", partialReversalMaxOrdersPerDay());
        root.put("confirmationReserveOrdersPerDay", confirmationReserveOrdersPerDay());
        root.put("adaptiveExtraRequiresConfirmation", adaptiveExtraRequiresConfirmation());
        root.put("budgetResidualExtraRequiresConfirmation", budgetResidualExtraRequiresConfirmation());
        root.put("reservedSlotPolicy",
                "base pullback slots are separate from confirmation reserve; adaptive/residual extra slots require confirmation-tier post-scout adds by default.");
        root.put("missedAlphaMicroSlotEnabled", missedAlphaMicroSlotEnabled());
        root.put("missedAlphaMicroSlotApplied", evaluation.missedAlphaMicroSlotApplied());
        root.put("missedAlphaMicroSlotReason", evaluation.missedAlphaMicroSlotReason());
        root.put("missedAlphaMicroSlotOrdersToday", evaluation.missedAlphaMicroSlotOrdersToday());
        root.put("missedAlphaMicroSlotMaxOrdersPerDay", missedAlphaMicroSlotMaxOrdersPerDay());
        root.put("missedAlphaMicroSlotLookbackHours", missedAlphaMicroSlotLookbackHours());
        root.put("missedAlphaMicroSlotMinFalseBlocks", missedAlphaMicroSlotMinFalseBlocks());
        root.put("missedAlphaMicroSlotRecentFalseBlocks", evaluation.missedAlphaMicroSlotRecentFalseBlocks());
        root.put("missedAlphaMicroSlotReturnThresholdPct", missedAlphaMicroSlotReturnThresholdPct());
        root.put("missedAlphaMicroSlotMfeThresholdPct", missedAlphaMicroSlotMfeThresholdPct());
        root.put("missedAlphaMicroSlotMaxNotionalUsdt",
                missedAlphaMicroSlotMaxNotional().stripTrailingZeros().toPlainString());
        root.put("executionSlotTag", evaluation.executionSlotTag());
        root.put("dailyCapCountSinceUtc", evaluation.dailyCapCountSinceUtc().toString());
        root.put("dailyCapCountSinceAsiaTaipei", DAILY_CAP_RESET_FORMATTER.format(evaluation.dailyCapCountSinceUtc()
                .atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(DAILY_CAP_LOCAL_ZONE)));
        root.put("scoreBuyPostScoutAddOrdersToday", evaluation.ordersToday());
        root.put("maxOrdersPerDay", evaluation.maxOrdersPerDay());
        root.put("baseMaxOrdersPerDay", evaluation.baseMaxOrdersPerDay());
        root.put("adaptiveExtraOrdersEnabled", adaptiveExtraOrdersEnabled());
        root.put("adaptiveExtraOrdersPerDay", adaptiveExtraOrdersPerDay());
        root.put("budgetResidualExtraOrdersEnabled", budgetResidualExtraOrdersEnabled());
        root.put("budgetResidualExtraOrdersPerDay", budgetResidualExtraOrdersPerDay());
        root.put("adaptiveDailyCapApplied", evaluation.adaptiveDailyCapApplied());
        root.put("budgetResidualDailyCapApplied", evaluation.budgetResidualDailyCapApplied());
        root.put("adaptiveDailyCapReason", evaluation.adaptiveDailyCapReason());
        root.set("dailyCapAudit", dailyCapAudit(evaluation));
        root.put("dailyCapOnlyBlocker", dailyCapOnlyBlocker);
        root.put("dailyCapResetTimezone", "UTC");
        root.put("dailyCapResetLocalTimezone", DAILY_CAP_LOCAL_ZONE.getId());
        root.put("nextDailyCapResetAtUtc", nextResetAt.toString());
        root.put("nextDailyCapResetAtAsiaTaipei", DAILY_CAP_RESET_FORMATTER.format(nextResetAt.atZone(DAILY_CAP_LOCAL_ZONE)));
        root.put("dailyCapResetMinutesRemaining", resetMinutesRemaining);
        root.put("eligibleAfterDailyCapResetPreview", dailyCapOnlyBlocker);
        root.put("wouldExecuteAfterDailyCapReset", enabled() && !dryRun() && dailyCapOnlyBlocker);
        root.put("dailyCapResetAction", dailyCapOnlyBlocker
                ? "WAIT_UTC_DAY_RESET_RECHECK_ALL_GATES"
                : "N/A");
        root.put("maxOpenPositions", evaluation.maxOpenPositions());
        root.put("configuredBaseMaxOpenPositions", configuredMaxOpenPositions());
        root.put("budgetAwareMaxOpenPositionsCap", MAX_BUDGET_AWARE_OPEN_POSITIONS);
        root.put("openSameThesisPositions", evaluation.openSameThesisPositions());
        root.put("existingOcoHealth", evaluation.ocoHealth());
        root.put("stagedBudgetEnforced", true);
        root.put("stagedExecutionMode", "SCORE_BUY_POST_SCOUT_ADD_AUTO");
        root.put("sameThesisOpenPositionPolicy",
                "same-thesis add is allowed only while staged budget remains and open count is below a budget-aware maxOpenPositions; exact duplicate opportunity remains blocked.");
        root.put("schedulerInstalled", true);
        root.put("blockAuditEnabled", true);
        root.put("blockAuditThrottleMinutes", blockAuditThrottleMinutes());
        root.put("lastBlockAuditKey", lastBlockAudit.get().key());
        root.put("lastBlockAuditAtUtc", lastBlockAudit.get().at().equals(Instant.EPOCH)
                ? "N/A" : lastBlockAudit.get().at().toString());
        root.put("schedulerEnabled", schedulerEnabled);
        root.put("schedulerDryRun", schedulerDryRun);
        root.put("schedulerFixedDelayMs", schedulerFixedDelayMs);
        root.put("schedulerInitialDelayMs", schedulerInitialDelayMs);
        root.put("schedulerNextCheckAtUtc", schedulerState.path("schedulerNextCheckAtUtc").asText());
        root.set("schedulerState", schedulerState);
        root.set("blockers", stringArray(evaluation.blockers()));
        root.set("primaryBlockers", stringArray(primaryBlockers(evaluation.blockers())));
        root.set("secondaryBlockers", stringArray(secondaryBlockers(evaluation.blockers())));
        root.set("capacityBlockers", stringArray(capacityBlockers(evaluation.blockers())));
        root.put("primaryNoBuyReason", primaryNoBuyReason(evaluation));
        root.put("blockingInterpretation", blockingInterpretation(evaluation));
        root.set("nextTriggerSummary", preview.path("nextTriggerSummary").deepCopy());
        root.set("capResetRecheckWatchdog", capResetWatchdog);
        root.put("capResetWatchdogState", text(capResetWatchdog, "state", "UNKNOWN"));
        root.put("capResetWatchdogReason", text(capResetWatchdog, "reason", "UNKNOWN"));
        root.put("capResetPostResetRecheckExpected",
                capResetWatchdog.path("postResetRecheckExpected").asBoolean(false));
        root.put("capResetPostResetExecutionPossibleIfStillEligible",
                capResetWatchdog.path("postResetExecutionPossibleIfStillEligible").asBoolean(false));
        root.put("capResetSchedulerFresh", capResetWatchdog.path("schedulerFresh").asBoolean(false));
        root.put("capResetSchedulerLagSeconds", capResetWatchdog.path("schedulerLagSeconds").asLong(-1));
        root.set("warnings", stringArray(evaluation.warnings()));
        root.set("postScoutPreview", preview.deepCopy());
        return root;
    }

    private void maybeAuditBlockedWritePath(JsonNode preview, Evaluation evaluation, long strategyId) {
        if (evaluation.eligible() || decisionAuditWriter == null) {
            return;
        }
        String symbol = text(preview, "symbol", DEFAULT_SYMBOL);
        String blocker = primaryBlockers(evaluation.blockers()).stream()
                .findFirst()
                .orElse("SCORE_BUY_POST_SCOUT_ADD_BLOCKED");
        String reason = primaryNoBuyReason(evaluation);
        String key = symbol + "|" + strategyId + "|" + evaluation.state() + "|" + evaluation.addOnType()
                + "|" + reason + "|" + String.join(",", evaluation.blockers());
        Instant now = Instant.now();
        long throttleSeconds = Math.max(60L, blockAuditThrottleMinutes() * 60L);
        while (true) {
            BlockAuditSnapshot previous = lastBlockAudit.get();
            if (key.equals(previous.key())
                    && Duration.between(previous.at(), now).getSeconds() < throttleSeconds) {
                return;
            }
            if (lastBlockAudit.compareAndSet(previous, new BlockAuditSnapshot(key, now))) {
                break;
            }
        }
        decisionAuditWriter.logEntrySkip(strategyId, symbol, INTERVAL, null,
                truncate(blocker, 64),
                "SCORE_BUY_POST_SCOUT_ADD_BLOCKED:" + reason,
                blockAuditContext(preview, evaluation, reason));
    }

    private Map<String, Object> blockAuditContext(JsonNode preview, Evaluation evaluation, String reason) {
        Instant now = Instant.now();
        Instant nextResetAt = nextDailyCapResetInstant(now);
        boolean dailyCapOnlyBlocker = dailyCapOnlyBlocker(evaluation.blockers());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("scope", "SCORE_BUY_POST_SCOUT_ADD");
        context.put("signalSource", "ScoreBuyPostScoutAutoAddExecutionService");
        context.put("side", SIDE);
        context.put("postScoutManagementState", evaluation.state());
        context.put("addOnType", evaluation.addOnType());
        context.put("primaryNoBuyReason", reason);
        context.put("blockers", String.join(",", evaluation.blockers()));
        context.put("warnings", String.join(",", evaluation.warnings()));
        context.put("runtimeEvidencePolicyMode", "BLOCK");
        context.put("runtimeEvidencePolicyReason", "SCORE_BUY post-scout add blocked: " + reason);
        context.put("dailyCapOnlyBlocker", dailyCapOnlyBlocker);
        context.put("dailyCapDeferredOpportunity", dailyCapOnlyBlocker);
        context.put("eligibleAfterDailyCapResetPreview", dailyCapOnlyBlocker);
        context.put("wouldExecuteAfterDailyCapReset", enabled() && !dryRun() && dailyCapOnlyBlocker);
        context.put("dailyCapResetAction", dailyCapOnlyBlocker
                ? "WAIT_UTC_DAY_RESET_RECHECK_ALL_GATES"
                : "FOLLOW_CURRENT_BLOCKER");
        context.put("dailyCapCountSinceUtc", evaluation.dailyCapCountSinceUtc().toString());
        context.put("dailyCapCountSinceAsiaTaipei",
                DAILY_CAP_RESET_FORMATTER.format(evaluation.dailyCapCountSinceUtc()
                        .atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(DAILY_CAP_LOCAL_ZONE)));
        context.put("nextDailyCapResetAtUtc", nextResetAt.toString());
        context.put("nextDailyCapResetAtAsiaTaipei",
                DAILY_CAP_RESET_FORMATTER.format(nextResetAt.atZone(DAILY_CAP_LOCAL_ZONE)));
        context.put("dailyCapResetMinutesRemaining",
                Math.max(0, Duration.between(now, nextResetAt).toMinutes()));
        context.put("ordersToday", evaluation.ordersToday());
        context.put("postScoutDailyCapMode", "TIERED_RESERVED_SLOTS");
        context.put("addOnQualityTier", evaluation.addOnQualityTier());
        context.put("addOnTierOrdersToday", evaluation.addOnTierOrdersToday());
        context.put("addOnTierMaxOrdersPerDay", evaluation.addOnTierMaxOrdersPerDay());
        context.put("missedAlphaMicroSlotApplied", evaluation.missedAlphaMicroSlotApplied());
        context.put("missedAlphaMicroSlotReason", evaluation.missedAlphaMicroSlotReason());
        context.put("missedAlphaMicroSlotOrdersToday", evaluation.missedAlphaMicroSlotOrdersToday());
        context.put("missedAlphaMicroSlotRecentFalseBlocks", evaluation.missedAlphaMicroSlotRecentFalseBlocks());
        context.put("executionSlotTag", evaluation.executionSlotTag());
        context.put("maxOrdersPerDay", evaluation.maxOrdersPerDay());
        context.put("baseMaxOrdersPerDay", evaluation.baseMaxOrdersPerDay());
        context.put("openSameThesisPositions", evaluation.openSameThesisPositions());
        context.put("maxOpenPositions", evaluation.maxOpenPositions());
        context.put("addOnEligible", preview.path("addOnEligible").asBoolean(false));
        context.put("eventRiskLevel", text(preview, "eventRiskLevel", "UNKNOWN"));
        context.put("suggestedAddNotionalUsdt", evaluation.notional().stripTrailingZeros().toPlainString());
        context.put("maxLossIfWrongUsdt", evaluation.maxLossIfWrong().stripTrailingZeros().toPlainString());
        context.put("postScoutDuplicateMode", "DISTINCT_OPPORTUNITY_KEY_WITH_COOLDOWN");
        context.put("currentOpportunityKey", evaluation.currentOpportunityKey());
        context.put("lastOpportunityKey", evaluation.lastOpportunityKey());
        context.put("lastOpportunityAtUtc", evaluation.lastOpportunityAtUtc());
        context.put("isDistinctPostScoutOpportunity", evaluation.distinctOpportunity());
        context.put("postScoutDuplicateReason", evaluation.opportunityDedupReason());
        context.put("postScoutOpportunityCooldownMinutes", evaluation.opportunityCooldownMinutes());
        context.put("entryPrice", text(preview, "entry", "0"));
        context.put("tpPrice", text(preview, "tp", "0"));
        context.put("slPrice", text(preview, "sl", "0"));
        context.put("partialReversalPersistenceReady",
                preview.path("marketReadiness").path("partialReversalPersistenceReady").asBoolean(false));
        context.put("intradayReversalStatus",
                preview.path("marketReadiness").path("intradayReversalStatus").asText("UNKNOWN"));
        context.put("postResetRecheckExpected", dailyCapOnlyBlocker && enabled());
        context.put("postResetExecutionPossibleIfStillEligible", enabled() && !dryRun() && dailyCapOnlyBlocker);
        context.put("orderSent", false);
        return context;
    }

    private ObjectNode capResetRecheckWatchdog(Evaluation evaluation,
                                               boolean dailyCapOnlyBlocker,
                                               Instant nextResetAt,
                                               long resetMinutesRemaining,
                                               boolean schedulerEnabled,
                                               boolean schedulerDryRun,
                                               long schedulerFixedDelayMs,
                                               ObjectNode schedulerState) {
        SchedulerFreshness freshness = schedulerFreshness(schedulerState, schedulerEnabled, schedulerFixedDelayMs);
        boolean capBlockerPresent = evaluation.blockers().stream().anyMatch(blocker ->
                "DAILY_SCORE_BUY_POST_SCOUT_ADD_CAP_REACHED".equals(blocker)
                        || isTierCapBlocker(blocker));
        boolean postResetRecheckExpected = schedulerEnabled && freshness.fresh() && dailyCapOnlyBlocker;
        boolean postResetExecutionPossibleIfStillEligible = postResetRecheckExpected && !schedulerDryRun;

        String state;
        String reason;
        String nextRequiredAction;
        if (!schedulerEnabled) {
            state = "SCHEDULER_DISABLED";
            reason = "post-scout auto-add scheduler is disabled; no automatic reset recheck will run.";
            nextRequiredAction = "ENABLE_SCHEDULER_IF_POST_SCOUT_AUTO_ADD_SHOULD_RUN";
        } else if (!freshness.fresh()) {
            state = "NO_COMPLETED_TICK_YET".equals(freshness.reason())
                    ? "SCHEDULER_NOT_STARTED"
                    : "SCHEDULER_STALE";
            reason = "scheduler freshness is not confirmed: " + freshness.reason();
            nextRequiredAction = "VERIFY_SCHEDULER_HEALTH_BEFORE_DAILY_CAP_RESET";
        } else if (dailyCapOnlyBlocker) {
            state = "READY_AFTER_RESET_PENDING";
            reason = "daily cap is the only blocker; scheduler should recheck all gates after the UTC cap reset.";
            nextRequiredAction = "WAIT_DAILY_CAP_RESET_RECHECK_ALL_GATES";
        } else if (capBlockerPresent) {
            state = "READINESS_NOT_ACTIVE";
            reason = "daily cap is present but not the only blocker; reset alone is not enough until post-scout readiness returns.";
            nextRequiredAction = "WAIT_POST_SCOUT_READINESS_BEFORE_CAP_RESET_MATTERS";
        } else {
            state = "RESET_NOT_RELEVANT";
            reason = "daily cap is not currently blocking this post-scout add evaluation.";
            nextRequiredAction = "FOLLOW_CURRENT_POST_SCOUT_READINESS";
        }

        ObjectNode node = objectMapper.createObjectNode();
        node.put("state", state);
        node.put("reason", reason);
        node.put("nextRequiredAction", nextRequiredAction);
        node.put("dailyCapOnlyBlocker", dailyCapOnlyBlocker);
        node.put("dailyCapBlockerPresent", capBlockerPresent);
        node.put("eligibleAfterDailyCapResetPreview", dailyCapOnlyBlocker);
        node.put("wouldExecuteAfterDailyCapReset", enabled() && !dryRun() && dailyCapOnlyBlocker);
        node.put("nextDailyCapResetAtUtc", nextResetAt.toString());
        node.put("nextDailyCapResetAtAsiaTaipei",
                DAILY_CAP_RESET_FORMATTER.format(nextResetAt.atZone(DAILY_CAP_LOCAL_ZONE)));
        node.put("minutesUntilReset", resetMinutesRemaining);
        node.put("schedulerEnabled", schedulerEnabled);
        node.put("schedulerDryRun", schedulerDryRun);
        node.put("schedulerFixedDelayMs", schedulerFixedDelayMs);
        node.put("schedulerLastCompletedAtUtc", freshness.lastCompletedAtUtc());
        node.put("schedulerNextCheckAtUtc", schedulerState.path("schedulerNextCheckAtUtc").asText());
        node.put("schedulerFresh", freshness.fresh());
        node.put("schedulerLagSeconds", freshness.lagSeconds());
        node.put("schedulerStaleAfterSeconds", freshness.staleAfterSeconds());
        node.put("schedulerFreshnessReason", freshness.reason());
        node.put("postResetRecheckExpected", postResetRecheckExpected);
        node.put("postResetExecutionPossibleIfStillEligible", postResetExecutionPossibleIfStillEligible);
        node.put("orderSent", false);
        return node;
    }

    private SchedulerFreshness schedulerFreshness(ObjectNode schedulerState, boolean schedulerEnabled,
                                                  long schedulerFixedDelayMs) {
        long staleAfterSeconds = Math.max(180_000L, Math.max(1L, schedulerFixedDelayMs) * 3L) / 1000L;
        if (!schedulerEnabled) {
            return new SchedulerFreshness(false, -1L, staleAfterSeconds, "N/A", "SCHEDULER_DISABLED");
        }
        String completedRaw = text(schedulerState, "schedulerLastCompletedAtUtc", "N/A");
        if (completedRaw == null || completedRaw.isBlank() || "N/A".equals(completedRaw)) {
            return new SchedulerFreshness(false, -1L, staleAfterSeconds, "N/A", "NO_COMPLETED_TICK_YET");
        }
        try {
            Instant completedAt = Instant.parse(completedRaw);
            long lagSeconds = Math.max(0L, Duration.between(completedAt, Instant.now()).toSeconds());
            boolean fresh = lagSeconds <= staleAfterSeconds;
            return new SchedulerFreshness(fresh, lagSeconds, staleAfterSeconds, completedRaw,
                    fresh ? "FRESH" : "STALE");
        } catch (Exception ignored) {
            return new SchedulerFreshness(false, -1L, staleAfterSeconds, completedRaw, "INVALID_COMPLETED_AT");
        }
    }

    private List<String> primaryBlockers(List<String> blockers) {
        if (blockers == null || blockers.isEmpty()) return List.of();
        List<String> secondary = secondaryBlockers(blockers);
        return blockers.stream()
                .filter(blocker -> !secondary.contains(blocker))
                .distinct()
                .toList();
    }

    private List<String> secondaryBlockers(List<String> blockers) {
        if (blockers == null || blockers.isEmpty()) return List.of();
        boolean hasReadinessBlocker = blockers.stream().anyMatch(this::isPostScoutReadinessBlocker);
        if (!hasReadinessBlocker) return List.of();
        return blockers.stream()
                .filter(this::isDerivedCapacityBlocker)
                .distinct()
                .toList();
    }

    private List<String> capacityBlockers(List<String> blockers) {
        if (blockers == null || blockers.isEmpty()) return List.of();
        return blockers.stream()
                .filter(this::isCapacityBlocker)
                .distinct()
                .toList();
    }

    private String primaryNoBuyReason(Evaluation evaluation) {
        if (evaluation.eligible()) {
            return "ELIGIBLE";
        }
        List<String> primary = primaryBlockers(evaluation.blockers());
        if (primary.stream().anyMatch(this::isPostScoutReadinessBlocker)) {
            return "POST_SCOUT_NOT_READY:" + evaluation.state();
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("EXACT_DUPLICATE_OPPORTUNITY"))) {
            return "EXACT_DUPLICATE_OPPORTUNITY";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("SAME_POST_SCOUT_OPPORTUNITY_COOLDOWN"))) {
            return "SAME_POST_SCOUT_OPPORTUNITY_COOLDOWN";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("RUNTIME_EVIDENCE_NOT_AVAILABLE"))) {
            return "RUNTIME_EVIDENCE_NOT_AVAILABLE";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("OCO_PREFLIGHT_NOT_PASS")
                || blocker.startsWith("OCO_HEALTH_ABNORMAL"))) {
            return "OCO_NOT_READY";
        }
        if (primary.stream().anyMatch(this::isTierCapBlocker)) {
            return "POST_SCOUT_TIER_CAP_WAIT:" + evaluation.addOnQualityTier();
        }
        if (dailyCapOnlyBlocker(primary)) {
            return "DAILY_CAP_WAIT";
        }
        return primary.isEmpty() ? "UNKNOWN_BLOCKER" : primary.get(0);
    }

    private String blockingInterpretation(Evaluation evaluation) {
        if (evaluation.eligible()) {
            return "Post-scout add is eligible; execution still rechecks all write-path gates before any order.";
        }
        if (evaluation.blockers().stream().anyMatch(this::isPostScoutReadinessBlocker)) {
            return "Primary reason is post-scout readiness, not capacity: wait for pullback/confirmation before interpreting min-notional or daily-cap blockers as actionable.";
        }
        if (evaluation.blockers().stream().anyMatch(this::isTierCapBlocker)) {
            return "This post-scout add tier has used its daily slot; confirmation reserve remains protected for higher-quality post-scout confirmation.";
        }
        if (dailyCapOnlyBlocker(evaluation.blockers())) {
            return "All non-cap gates passed in this preview; wait for the UTC daily cap reset and recheck all gates before any add.";
        }
        if (evaluation.blockers().contains("SAME_POST_SCOUT_OPPORTUNITY_COOLDOWN")) {
            return "Same stable post-scout identity already produced an add inside the cooldown window; "
                    + "wait for a different decision bar, side, strategy, management state, invalidation, or execution slot.";
        }
        return "Blocked by the listed primary blockers; no order is sent.";
    }

    private boolean isPostScoutReadinessBlocker(String blocker) {
        return blocker != null
                && (blocker.startsWith("POST_SCOUT_ADD_NOT_ELIGIBLE")
                || blocker.startsWith("POST_SCOUT_STATE_NOT_EXECUTABLE"));
    }

    private boolean isDerivedCapacityBlocker(String blocker) {
        return "NOTIONAL_BELOW_EXCHANGE_MIN".equals(blocker)
                || "DAILY_SCORE_BUY_POST_SCOUT_ADD_CAP_REACHED".equals(blocker)
                || isTierCapBlocker(blocker);
    }

    private boolean isCapacityBlocker(String blocker) {
        return isDerivedCapacityBlocker(blocker)
                || "SAME_THESIS_OPEN_POSITION_LIMIT_REACHED".equals(blocker)
                || "NOTIONAL_EXCEEDS_SCORE_BUY_POST_SCOUT_ADD_CAP".equals(blocker)
                || "MAX_LOSS_EXCEEDS_SCORE_BUY_POST_SCOUT_ADD_BUDGET".equals(blocker);
    }

    private ObjectNode dailyCapAudit(Evaluation evaluation) {
        long base = Math.max(0, evaluation.baseMaxOrdersPerDay());
        long adaptiveLimit = base + (adaptiveExtraOrdersEnabled() ? Math.max(0, adaptiveExtraOrdersPerDay()) : 0);
        long residualLimit = adaptiveLimit + (adaptiveExtraOrdersEnabled() && budgetResidualExtraOrdersEnabled()
                ? Math.max(0, budgetResidualExtraOrdersPerDay())
                : 0);
        long configuredTotalLimit = Math.max(base, residualLimit);
        long ordersToday = Math.max(0, evaluation.ordersToday());
        boolean baseCapExhausted = ordersToday >= base && base > 0;
        boolean currentCapBlocksNextOrder = evaluation.maxOrdersPerDay() > 0 && ordersToday >= evaluation.maxOrdersPerDay();
        boolean priorExtraSlotsUsed = ordersToday > base;
        boolean breachSuspected = configuredTotalLimit > 0 && ordersToday > configuredTotalLimit;

        ObjectNode node = objectMapper.createObjectNode();
        node.put("scope", "SCORE_BUY_POST_SCOUT_ADD_STRATEGY_SYMBOL");
        node.put("mode", "TIERED_RESERVED_SLOTS");
        node.put("reservedSlotPolicy",
                "adaptive/residual extra slots are reserved for confirmation-tier post-scout adds unless explicitly configured otherwise.");
        node.put("addOnQualityTier", evaluation.addOnQualityTier());
        node.put("addOnTierOrdersToday", Math.max(0, evaluation.addOnTierOrdersToday()));
        node.put("addOnTierMaxOrdersPerDay", Math.max(0, evaluation.addOnTierMaxOrdersPerDay()));
        node.put("pullbackMaxOrdersPerDay", pullbackMaxOrdersPerDay());
        node.put("partialReversalMaxOrdersPerDay", partialReversalMaxOrdersPerDay());
        node.put("confirmationReserveOrdersPerDay", confirmationReserveOrdersPerDay());
        node.put("adaptiveExtraRequiresConfirmation", adaptiveExtraRequiresConfirmation());
        node.put("budgetResidualExtraRequiresConfirmation", budgetResidualExtraRequiresConfirmation());
        node.put("missedAlphaMicroSlotEnabled", missedAlphaMicroSlotEnabled());
        node.put("missedAlphaMicroSlotApplied", evaluation.missedAlphaMicroSlotApplied());
        node.put("missedAlphaMicroSlotReason", evaluation.missedAlphaMicroSlotReason());
        node.put("missedAlphaMicroSlotOrdersToday", evaluation.missedAlphaMicroSlotOrdersToday());
        node.put("missedAlphaMicroSlotMaxOrdersPerDay", missedAlphaMicroSlotMaxOrdersPerDay());
        node.put("missedAlphaMicroSlotRecentFalseBlocks", evaluation.missedAlphaMicroSlotRecentFalseBlocks());
        node.put("executionSlotTag", evaluation.executionSlotTag());
        node.put("countSinceUtc", evaluation.dailyCapCountSinceUtc().toString());
        node.put("countSinceAsiaTaipei", DAILY_CAP_RESET_FORMATTER.format(evaluation.dailyCapCountSinceUtc()
                .atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(DAILY_CAP_LOCAL_ZONE)));
        node.put("ordersToday", ordersToday);
        node.put("baseMaxOrdersPerDay", base);
        node.put("currentMaxOrdersPerDayForNextOrder", evaluation.maxOrdersPerDay());
        node.put("configuredAdaptiveLimit", adaptiveLimit);
        node.put("configuredResidualLimit", residualLimit);
        node.put("maxConfiguredOrdersPerDayIncludingAdaptiveAndResidual", configuredTotalLimit);
        node.put("baseCapExhausted", baseCapExhausted);
        node.put("currentCapBlocksNextOrder", currentCapBlocksNextOrder);
        node.put("priorAdaptiveOrResidualSlotsUsed", priorExtraSlotsUsed);
        node.put("extraSlotsUsedToday", Math.max(0, ordersToday - base));
        node.put("adaptiveDailyCapAppliedForCurrentEvaluation", evaluation.adaptiveDailyCapApplied());
        node.put("budgetResidualDailyCapAppliedForCurrentEvaluation", evaluation.budgetResidualDailyCapApplied());
        node.put("dailyCapBreachSuspected", breachSuspected);
        node.put("state", breachSuspected
                ? "POTENTIAL_CAP_BREACH_REVIEW"
                : currentCapBlocksNextOrder ? "BLOCK_NEXT_ORDER_NO_RETROACTIVE_BREACH" : "AVAILABLE_FOR_NEXT_ORDER");
        node.put("interpretation", breachSuspected
                ? "ordersToday exceeds the configured base+adaptive+residual limit; review execution audit before allowing more adds."
                : currentCapBlocksNextOrder
                ? "Daily cap is blocking the next post-scout add. If ordersToday is above the base cap, those rows may include earlier adaptive/residual slots; this field is an audit explanation, not retroactive proof of a cap breach."
                : "Daily cap has remaining room for the next post-scout add if all other hard gates pass.");
        return node;
    }

    private boolean dailyCapOnlyBlocker(List<String> blockers) {
        return blockers != null
                && !blockers.isEmpty()
                && blockers.stream().allMatch(blocker ->
                "DAILY_SCORE_BUY_POST_SCOUT_ADD_CAP_REACHED".equals(blocker)
                        || isTierCapBlocker(blocker));
    }

    private Instant nextDailyCapResetInstant(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
    }

    private BtLiveSignal saveSignal(long strategyId, String symbol, BigDecimal entry, BigDecimal tp, BigDecimal sl,
                                    TradeResult buy, Long ocoAlgoId, String addOnType, String opportunityKey,
                                    String executionSlotTag) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setStrategyId(strategyId);
        signal.setSymbol(symbol);
        signal.setIntervalCode(INTERVAL);
        signal.setBarOpenTime(LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0));
        signal.setEntryPrice(entry);
        signal.setSuggestedTp(tp);
        signal.setSuggestedSl(sl);
        signal.setScore(new BigDecimal("0.0000"));
        signal.setNnOutput(new BigDecimal("0.0000"));
        signal.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        signal.setAutoTraded(true);
        signal.setExchangeOrderId("SCORE_BUY_ADD:" + buy.getOrderId());
        signal.setActualEntryPrice(buy.getAvgPrice());
        signal.setTradedQty(buy.getQty());
        signal.setOcoQty(buy.getQty());
        signal.setOcoOrderListId(ocoAlgoId);
        signal.setSide(SIDE);
        signal.setFilterReason("SCORE_BUY_POST_SCOUT_ADD_" + addOnType
                + "|SLOT:" + executionSlotTag
                + "|OPP:" + opportunityKey);
        return liveSignalRepository.save(signal);
    }

    private void writeEvidence(BtDecisionAudit audit, BtLiveSignal signal, JsonNode preview,
                               TradeResult buy, Long ocoAlgoId, String outcome, String opportunityKey) {
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setDecisionId(audit.getId());
        evidence.setEvidenceTime(LocalDateTime.now(ZoneOffset.UTC));
        evidence.setSymbol(signal.getSymbol());
        evidence.setSide(SIDE);
        evidence.setStrategyId(signal.getStrategyId());
        evidence.setIntervalCode(INTERVAL);
        evidence.setLiveSignalId(signal.getId());
        evidence.setSignalSource("SCORE_BUY_POST_SCOUT_ADD");
        evidence.setFeaturesSnapshotJson(preview.toString());
        evidence.setFreshnessState("PASS_POST_SCOUT_RECHECK");
        evidence.setSelectedAction("SCORE_BUY_POST_SCOUT_ADD_EXECUTE");
        evidence.setReason(outcome);
        evidence.setPolicyMode("AUTO_APPROVED_SCORE_BUY_POST_SCOUT_ADD");
        evidence.setFinalOutcome(outcome);
        evidence.setOrderSent(true);
        evidence.setExecutionMode("SCORE_BUY_POST_SCOUT_ADD_AUTO");
        evidence.setOcoOrderListId(ocoAlgoId == null ? null : String.valueOf(ocoAlgoId));
        evidence.setExecutionPreviewJson(receipt(preview, buy, ocoAlgoId, outcome, null, true, opportunityKey));
        evidenceRepository.save(evidence);
    }

    private String receipt(JsonNode preview, TradeResult buy, Long ocoAlgoId, String status, String error,
                           boolean orderAttempted, String opportunityKey) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("version", "score-buy-post-scout-add-v0");
        node.put("status", status);
        node.put("symbol", text(preview, "symbol", DEFAULT_SYMBOL));
        node.put("strategyId", text(preview, "strategyId", String.valueOf(DEFAULT_STRATEGY_ID)));
        node.put("side", SIDE);
        node.put("addOnType", text(preview, "addOnType", "UNKNOWN"));
        node.put("postScoutManagementState", text(preview, "postScoutManagementState", "UNKNOWN"));
        node.put("orderAttempted", orderAttempted);
        node.put("orderSent", buy != null);
        node.put("ocoAttached", ocoAlgoId != null);
        node.put("orderId", buy == null ? null : buy.getOrderId());
        node.put("ocoAlgoId", ocoAlgoId);
        node.put("qty", buy == null ? null : buy.getQty().toPlainString());
        node.put("entryPrice", buy == null ? text(preview, "entry", "0") : buy.getAvgPrice().toPlainString());
        node.put("tp", text(preview, "tp", "0"));
        node.put("sl", text(preview, "sl", "0"));
        node.put("notionalUsdt", text(preview, "suggestedAddNotionalUsdt", "0"));
        node.put("scoreBuyFormingState", text(preview, "scoreBuyFormingState", "UNKNOWN"));
        node.put("scoreBuyHoldingState", text(preview, "scoreBuyHoldingState", "UNKNOWN"));
        node.put("holdBtcMode", preview.path("holdBtcMode").asBoolean(false));
        node.put("disasterOcoMode", text(preview, "disasterOcoMode", "KEEP_12PCT_HARD_OCO"));
        node.put("eventRiskLevel", text(preview, "eventRiskLevel", "UNKNOWN"));
        node.put("postScoutOpportunityKey", opportunityKey);
        node.put("autonomousExecutionScope", "BTCUSDT/485/LONG/SCORE_BUY_POST_SCOUT_ADD");
        if (error != null) node.put("error", truncate(error, 420));
        return node.toString();
    }

    private String writeExecutionResult(BtDecisionAudit audit, boolean orderSent, boolean ocoAttached,
                                        TradeResult buy, Long ocoAlgoId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "executeScoreBuyPostScoutAddIfEligible");
        root.put("boundary", "INTERNAL_BOUNDED_SCORE_BUY_POST_SCOUT_ADD; no strategy/grid/fund/Earn behavior changed.");
        root.put("auditId", audit.getId());
        root.put("status", audit.getReason());
        root.put("orderSent", orderSent);
        root.put("ocoAttached", ocoAttached);
        root.put("orderId", buy == null ? null : buy.getOrderId());
        root.put("ocoAlgoId", ocoAlgoId);
        root.put("reason", audit.getReason());
        return write(root);
    }

    private OpportunityDedup evaluateOpportunityDedup(JsonNode preview, long strategyId, String symbol,
                                                      String executionSlotTag) {
        int cooldownMinutes = opportunityCooldownMinutes();
        String currentKey = opportunityKey(preview, strategyId, SIDE, executionSlotTag);
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(cooldownMinutes);
        List<BtLiveSignal> recent = liveSignalRepository.findRecentScoreBuyPostScoutAddTradesSince(
                strategyId, symbol, since, Pageable.unpaged());
        BtLiveSignal last = recent == null || recent.isEmpty() ? null : recent.get(0);
        if (last == null) {
            return new OpportunityDedup(currentKey, "NONE", "N/A", true,
                    "NO_RECENT_POST_SCOUT_ADD", cooldownMinutes);
        }
        BtLiveSignal matching = recent.stream()
                .filter(row -> currentKey.equals(opportunityKeyFromFilterReason(row.getFilterReason())))
                .findFirst()
                .orElse(null);
        if (matching != null) {
            String matchingAt = matching.getCreatedAt() == null ? "UNKNOWN" : matching.getCreatedAt().toString();
            return new OpportunityDedup(currentKey, currentKey, matchingAt, false,
                    "SAME_POST_SCOUT_OPPORTUNITY_WITHIN_COOLDOWN", cooldownMinutes);
        }
        String lastKey = opportunityKeyFromFilterReason(last.getFilterReason());
        String lastAt = last.getCreatedAt() == null ? "UNKNOWN" : last.getCreatedAt().toString();
        if (lastKey.isBlank()) {
            return new OpportunityDedup(currentKey, "UNKNOWN", lastAt, true,
                    "RECENT_ADD_HAS_NO_OPPORTUNITY_KEY_LEGACY_ROW", cooldownMinutes);
        }
        return new OpportunityDedup(currentKey, lastKey, lastAt, true,
                "DISTINCT_POST_SCOUT_OPPORTUNITY", cooldownMinutes);
    }

    String opportunityKey(JsonNode preview, long strategyId, String side, String executionSlotTag) {
        return String.join("|",
                normalizeSymbol(text(preview, "symbol", DEFAULT_SYMBOL)),
                String.valueOf(strategyId),
                normalizeIdentityComponent(side, "UNKNOWN"),
                text(preview, "postScoutManagementState", "UNKNOWN"),
                text(preview, "addOnType", "UNKNOWN"),
                "bar=" + text(preview.path("observerSummary"), "intradayReversalDecisionBarOpenTime", "UNKNOWN"),
                "risk=" + text(preview, "eventRiskLevel", "UNKNOWN"),
                "inv=" + text(preview, "formationInvalidationReason", "NONE"),
                "slot=" + normalizeIdentityComponent(executionSlotTag, "UNKNOWN"));
    }

    String opportunityKeyFromFilterReason(String filterReason) {
        if (filterReason == null) return "";
        int marker = filterReason.indexOf("|OPP:");
        if (marker < 0) return "";
        String executionSlotTag = markerValue(filterReason.substring(0, marker), "|SLOT:");
        String rawKey = filterReason.substring(marker + 5).trim();
        List<String> stableParts = java.util.Arrays.stream(rawKey.split("\\|"))
                .filter(part -> !part.startsWith("entry=")
                        && !part.startsWith("tp=")
                        && !part.startsWith("sl=")
                        && !part.startsWith("slot="))
                .toList();
        return String.join("|", stableParts)
                + "|slot=" + normalizeIdentityComponent(executionSlotTag, "UNKNOWN");
    }

    private String markerValue(String value, String marker) {
        int start = value.indexOf(marker);
        if (start < 0) return "";
        return value.substring(start + marker.length()).trim();
    }

    private String normalizeIdentityComponent(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String executionSlotTag(boolean missedAlphaMicroSlotApplied) {
        return missedAlphaMicroSlotApplied ? "MISSED_ALPHA_MICRO" : "STANDARD";
    }

    private OcoHealth checkExistingOcoHealth(long strategyId, String symbol) {
        List<BtLiveSignal> open = liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(strategyId)
                .stream()
                .filter(row -> symbol.equalsIgnoreCase(row.getSymbol()))
                .toList();
        for (BtLiveSignal row : open) {
            if (row.getOcoOrderListId() == null) {
                return new OcoHealth(false, "OPEN_POSITION_WITHOUT_OCO:" + row.getId());
            }
            OcoOrderStateInspector.Inspection inspection = ocoOrderStateInspector.inspectSpot(
                    row.getSymbol(), row.getOcoOrderListId());
            if (inspection.filled()) {
                String reason = inspection.filledChildOrderId() == null
                        ? "OCO_FILLED_DB_OPEN" : "OCO_EFFECTIVE_CHILD_FILLED";
                return new OcoHealth(false, reason + ":position=" + row.getId());
            }
            if (!inspection.queryComplete()) {
                return new OcoHealth(false, "OCO_READ_FAILED:"
                        + truncate(String.join(",", inspection.errors()), 120));
            }
            if (!inspection.active()) {
                return new OcoHealth(false, "OCO_STATE_" + inspection.parentState()
                        + ":position=" + row.getId());
            }
        }
        return new OcoHealth(true, "OK");
    }

    private boolean isExecutablePostScoutState(String state) {
        return "ADD_ON_PULLBACK_READY".equalsIgnoreCase(state)
                || "ADD_ON_PARTIAL_REVERSAL_READY".equalsIgnoreCase(state)
                || "ADD_ON_CONFIRMATION_READY".equalsIgnoreCase(state)
                || "ADD_ON_DAILY_CONFIRMATION_READY".equalsIgnoreCase(state);
    }

    private boolean hasExecutableMarketReadiness(JsonNode preview) {
        JsonNode readiness = preview.path("marketReadiness");
        return readiness.path("pullbackAddReady").asBoolean(false)
                || readiness.path("pullbackCooldownAddReady").asBoolean(false)
                || readiness.path("partialReversalPersistenceReady").asBoolean(false)
                || readiness.path("confirmationAddReady").asBoolean(false);
    }

    private String addOnQualityTier(String state, String addOnType, JsonNode preview) {
        String normalizedState = normalizeToken(state);
        String normalizedType = normalizeToken(addOnType);
        if (normalizedState.contains("CONFIRMATION") || normalizedType.contains("CONFIRMATION")) {
            return TIER_CONFIRMATION;
        }
        if (normalizedState.contains("PARTIAL_REVERSAL") || normalizedType.contains("PARTIAL_REVERSAL")) {
            return TIER_PARTIAL_REVERSAL;
        }
        return TIER_PULLBACK;
    }

    private String normalizeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isConfirmationAddOn(JsonNode preview) {
        return TIER_CONFIRMATION.equals(addOnQualityTier(
                text(preview, "postScoutManagementState", "UNKNOWN"),
                text(preview, "addOnType", "UNKNOWN"),
                preview));
    }

    private long countTierOrdersToday(long strategyId, String symbol, LocalDateTime since, String tier) {
        return liveSignalRepository.countScoreBuyPostScoutAddTradesByFilterReasonLikeSince(
                strategyId, symbol, since, tierFilterReasonLike(tier));
    }

    private String tierFilterReasonLike(String tier) {
        if (TIER_CONFIRMATION.equals(tier)) {
            return "SCORE_BUY_POST_SCOUT_ADD_%CONFIRMATION%";
        }
        if (TIER_PARTIAL_REVERSAL.equals(tier)) {
            return "SCORE_BUY_POST_SCOUT_ADD_PARTIAL_REVERSAL%";
        }
        return "SCORE_BUY_POST_SCOUT_ADD_PULLBACK%";
    }

    private String tierCapBlocker(String tier) {
        return "SCORE_BUY_POST_SCOUT_" + tier + "_CAP_REACHED";
    }

    private boolean isTierCapBlocker(String blocker) {
        return blocker != null
                && blocker.startsWith("SCORE_BUY_POST_SCOUT_")
                && blocker.endsWith("_CAP_REACHED")
                && !"DAILY_SCORE_BUY_POST_SCOUT_ADD_CAP_REACHED".equals(blocker);
    }

    private boolean isPostScoutCapBlocker(String blocker) {
        return "DAILY_SCORE_BUY_POST_SCOUT_ADD_CAP_REACHED".equals(blocker)
                || isTierCapBlocker(blocker);
    }

    private long countMissedAlphaMicroSlotOrdersToday(long strategyId, String symbol, LocalDateTime since) {
        return liveSignalRepository.countScoreBuyPostScoutAddTradesByFilterReasonLikeSince(
                strategyId, symbol, since, "%SLOT:MISSED_ALPHA_MICRO%");
    }

    private MissedAlphaMicroSlot evaluateMissedAlphaMicroSlot(JsonNode preview,
                                                              List<String> blockers,
                                                              long ordersToday,
                                                              long currentMaxOrdersPerDay,
                                                              int effectiveMaxOpenPositions,
                                                              int openSameThesisPositions,
                                                              String addOnQualityTier,
                                                              long microSlotOrdersToday) {
        if (!missedAlphaMicroSlotEnabled()) {
            return MissedAlphaMicroSlot.notApplied("DISABLED", 0);
        }
        if (TIER_CONFIRMATION.equals(addOnQualityTier)) {
            return MissedAlphaMicroSlot.notApplied("CONFIRMATION_TIER_USES_RESERVED_SLOT", 0);
        }
        if (microSlotOrdersToday >= missedAlphaMicroSlotMaxOrdersPerDay()) {
            return MissedAlphaMicroSlot.notApplied("MISSED_ALPHA_MICRO_SLOT_DAILY_CAP_REACHED", 0);
        }
        if (ordersToday < currentMaxOrdersPerDay && blockers.stream().noneMatch(this::isTierCapBlocker)) {
            return MissedAlphaMicroSlot.notApplied("CAP_NOT_EXHAUSTED", 0);
        }
        List<String> nonCapBlockers = blockers.stream()
                .filter(blocker -> !isPostScoutCapBlocker(blocker))
                .distinct()
                .toList();
        if (!nonCapBlockers.isEmpty()) {
            return MissedAlphaMicroSlot.notApplied("NON_CAP_BLOCKERS_PRESENT:" + String.join(",", nonCapBlockers), 0);
        }
        if (openSameThesisPositions >= effectiveMaxOpenPositions) {
            return MissedAlphaMicroSlot.notApplied("OPEN_POSITION_LIMIT_REACHED", 0);
        }
        if (!eventRiskAllowsAdaptiveExtra(text(preview, "eventRiskLevel", "UNKNOWN"))) {
            return MissedAlphaMicroSlot.notApplied("EVENT_RISK_TOO_HIGH", 0);
        }
        if (!preview.path("addOnEligible").asBoolean(false)) {
            return MissedAlphaMicroSlot.notApplied("ADD_ON_NOT_ELIGIBLE", 0);
        }
        if (!isExecutablePostScoutState(text(preview, "postScoutManagementState", "UNKNOWN"))) {
            return MissedAlphaMicroSlot.notApplied("POST_SCOUT_STATE_NOT_EXECUTABLE", 0);
        }
        if (!hasExecutableMarketReadiness(preview)) {
            return MissedAlphaMicroSlot.notApplied("MARKET_READINESS_NOT_EXECUTABLE", 0);
        }
        BigDecimal notional = money(preview, "suggestedAddNotionalUsdt", BigDecimal.ZERO);
        BigDecimal remainingBudget = money(preview, "remainingPostScoutAddBudgetUsdt", BigDecimal.ZERO);
        BigDecimal maxLoss = money(preview, "maxLossIfWrongUsdt", BigDecimal.ZERO);
        if (notional.compareTo(MIN_NOTIONAL) < 0) {
            return MissedAlphaMicroSlot.notApplied("NOTIONAL_BELOW_MIN", 0);
        }
        if (notional.compareTo(missedAlphaMicroSlotMaxNotional()) > 0) {
            return MissedAlphaMicroSlot.notApplied("NOTIONAL_EXCEEDS_MICRO_SLOT_MAX", 0);
        }
        if (remainingBudget.compareTo(notional) < 0) {
            return MissedAlphaMicroSlot.notApplied("POST_SCOUT_BUDGET_NOT_AVAILABLE", 0);
        }
        if (maxLoss.compareTo(maxLossBudget()) > 0) {
            return MissedAlphaMicroSlot.notApplied("MAX_LOSS_EXCEEDS_BUDGET", 0);
        }

        long recentMissedAlpha = countRecentMissedAlphaBlocks();
        if (recentMissedAlpha < missedAlphaMicroSlotMinFalseBlocks()) {
            return MissedAlphaMicroSlot.notApplied("INSUFFICIENT_RECENT_MISSED_ALPHA:" + recentMissedAlpha,
                    recentMissedAlpha);
        }
        return new MissedAlphaMicroSlot(true,
                "RECENT_MISSED_ALPHA_FALSE_BLOCKS:" + recentMissedAlpha,
                recentMissedAlpha);
    }

    private long countRecentMissedAlphaBlocks() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime since = now.minusHours(missedAlphaMicroSlotLookbackHours());
        LocalDateTime maturedBefore = now.minusHours(1);
        if (!maturedBefore.isAfter(since)) {
            return 0L;
        }
        try {
            return evidenceRepository.countScoreBuyPostScoutMissedAlphaBlocksSince(
                    DEFAULT_SYMBOL,
                    DEFAULT_STRATEGY_ID,
                    since,
                    maturedBefore,
                    missedAlphaMicroSlotReturnThresholdPct(),
                    missedAlphaMicroSlotMfeThresholdPct());
        } catch (Exception e) {
            log.warn("[ScoreBuyPostScoutAutoAdd] missed-alpha micro-slot evidence query failed: {}", e.getMessage());
            return 0L;
        }
    }

    private boolean adaptiveDailyCapAllowed(JsonNode preview, List<String> blockers,
                                            long ordersToday, long baseMaxOrdersPerDay,
                                            int effectiveMaxOpenPositions,
                                            int openSameThesisPositions) {
        if (!adaptiveExtraOrdersEnabled()) return false;
        long extra = adaptiveExtraOrdersPerDay();
        if (extra <= 0) return false;
        if (adaptiveExtraRequiresConfirmation() && !isConfirmationAddOn(preview)) return false;
        if (ordersToday < baseMaxOrdersPerDay || ordersToday >= baseMaxOrdersPerDay + extra) return false;
        if (!blockers.isEmpty()) return false;
        if (openSameThesisPositions >= effectiveMaxOpenPositions) return false;
        if (!eventRiskAllowsAdaptiveExtra(text(preview, "eventRiskLevel", "UNKNOWN"))) return false;
        if (!preview.path("addOnEligible").asBoolean(false)) return false;
        if (!isExecutablePostScoutState(text(preview, "postScoutManagementState", "UNKNOWN"))) return false;
        if (!hasExecutableMarketReadiness(preview)) {
            return false;
        }
        BigDecimal remainingBudget = money(preview, "remainingPostScoutAddBudgetUsdt", BigDecimal.ZERO);
        BigDecimal notional = money(preview, "suggestedAddNotionalUsdt", BigDecimal.ZERO);
        BigDecimal maxLoss = money(preview, "maxLossIfWrongUsdt", BigDecimal.ZERO);
        return remainingBudget.compareTo(notional) >= 0
                && notional.compareTo(MIN_NOTIONAL) >= 0
                && maxLoss.compareTo(maxLossBudget()) <= 0;
    }

    private boolean budgetResidualDailyCapAllowed(JsonNode preview, List<String> blockers,
                                                  long ordersToday, long baseMaxOrdersPerDay,
                                                  int effectiveMaxOpenPositions,
                                                  int openSameThesisPositions) {
        if (!adaptiveExtraOrdersEnabled()) return false;
        if (!budgetResidualExtraOrdersEnabled()) return false;
        if (budgetResidualExtraRequiresConfirmation() && !isConfirmationAddOn(preview)) return false;
        long adaptiveCap = baseMaxOrdersPerDay + adaptiveExtraOrdersPerDay();
        long residualCap = adaptiveCap + budgetResidualExtraOrdersPerDay();
        if (ordersToday < adaptiveCap || ordersToday >= residualCap) return false;
        if (!blockers.isEmpty()) return false;
        if (openSameThesisPositions >= effectiveMaxOpenPositions) return false;
        if (!eventRiskAllowsAdaptiveExtra(text(preview, "eventRiskLevel", "UNKNOWN"))) return false;
        if (!preview.path("addOnEligible").asBoolean(false)) return false;
        if (!isExecutablePostScoutState(text(preview, "postScoutManagementState", "UNKNOWN"))) return false;
        if (!hasExecutableMarketReadiness(preview)) {
            return false;
        }
        BigDecimal remainingBudget = money(preview, "remainingPostScoutAddBudgetUsdt", BigDecimal.ZERO);
        BigDecimal notional = money(preview, "suggestedAddNotionalUsdt", BigDecimal.ZERO);
        BigDecimal maxLoss = money(preview, "maxLossIfWrongUsdt", BigDecimal.ZERO);
        BigDecimal remainingAfterOneAdd = remainingBudget.subtract(notional);
        return remainingBudget.compareTo(notional) >= 0
                && remainingAfterOneAdd.compareTo(MIN_NOTIONAL) < 0
                && notional.compareTo(MIN_NOTIONAL) >= 0
                && maxLoss.compareTo(maxLossBudget()) <= 0;
    }

    private String adaptiveCapReason(JsonNode preview, boolean applied, boolean budgetResidualApplied,
                                     long ordersToday, long baseMaxOrdersPerDay) {
        if (budgetResidualApplied) {
            return "base/adaptive daily cap reached but one final residual-budget post-scout add remains: remainingBudget="
                    + money(preview, "remainingPostScoutAddBudgetUsdt", BigDecimal.ZERO)
                    + " notional=" + money(preview, "suggestedAddNotionalUsdt", BigDecimal.ZERO)
                    + " eventRisk=" + text(preview, "eventRiskLevel", "UNKNOWN");
        }
        if (!applied) {
            if (!adaptiveExtraOrdersEnabled()) return "ADAPTIVE_EXTRA_DISABLED";
            if (ordersToday < baseMaxOrdersPerDay) return "BASE_DAILY_CAP_NOT_EXHAUSTED";
            if (adaptiveExtraRequiresConfirmation() && !isConfirmationAddOn(preview)) {
                return "ADAPTIVE_EXTRA_RESERVED_FOR_CONFIRMATION_TIER";
            }
            if (!budgetResidualExtraOrdersEnabled()) return "BUDGET_RESIDUAL_EXTRA_DISABLED";
            return "ADAPTIVE_EXTRA_NOT_AVAILABLE_OR_GATES_NOT_SATISFIED";
        }
        return "base daily cap reached but post-scout add remains high-quality, OCO/evidence/budget gates pass, eventRisk="
                + text(preview, "eventRiskLevel", "UNKNOWN");
    }

    private boolean eventRiskAllowsAdaptiveExtra(String eventRiskLevel) {
        String level = eventRiskLevel == null ? "" : eventRiskLevel.trim().toUpperCase(Locale.ROOT);
        return level.isBlank() || "R0".equals(level) || "R1".equals(level) || "R2".equals(level);
    }

    boolean enabled() {
        return Boolean.parseBoolean(env.getProperty("trading.score-buy.post-scout-add.execution.enabled", "false"));
    }

    boolean dryRun() {
        return Boolean.parseBoolean(env.getProperty("trading.score-buy.post-scout-add.execution.dry-run", "true"));
    }

    private long maxOrdersPerDay() {
        return Long.parseLong(env.getProperty("trading.score-buy.post-scout-add.execution.max-orders-per-day", "2"));
    }

    private long tierMaxOrdersPerDay(String tier) {
        if (TIER_CONFIRMATION.equals(tier)) {
            return confirmationReserveOrdersPerDay();
        }
        if (TIER_PARTIAL_REVERSAL.equals(tier)) {
            return partialReversalMaxOrdersPerDay();
        }
        return pullbackMaxOrdersPerDay();
    }

    private long pullbackMaxOrdersPerDay() {
        return Long.parseLong(env.getProperty(
                "trading.score-buy.post-scout-add.execution.pullback-max-orders-per-day",
                String.valueOf(maxOrdersPerDay())));
    }

    private long partialReversalMaxOrdersPerDay() {
        return Long.parseLong(env.getProperty(
                "trading.score-buy.post-scout-add.execution.partial-reversal-max-orders-per-day", "1"));
    }

    private long confirmationReserveOrdersPerDay() {
        return Long.parseLong(env.getProperty(
                "trading.score-buy.post-scout-add.execution.confirmation-reserve-orders-per-day", "1"));
    }

    private long schedulerFixedDelayMs() {
        return Long.parseLong(env.getProperty("trading.score-buy.post-scout-add.execution.fixed-delay-ms", "60000"));
    }

    private long schedulerInitialDelayMs() {
        return Long.parseLong(env.getProperty("trading.score-buy.post-scout-add.execution.initial-delay-ms", "90000"));
    }

    private long blockAuditThrottleMinutes() {
        return Long.parseLong(env.getProperty(
                "trading.score-buy.post-scout-add.execution.block-audit-throttle-minutes", "15"));
    }

    private int opportunityCooldownMinutes() {
        return Integer.parseInt(env.getProperty(
                "trading.score-buy.post-scout-add.execution.opportunity-cooldown-minutes", "30"));
    }

    private boolean adaptiveExtraOrdersEnabled() {
        return Boolean.parseBoolean(env.getProperty(
                "trading.score-buy.post-scout-add.execution.adaptive-extra-orders-enabled", "true"));
    }

    private long adaptiveExtraOrdersPerDay() {
        return Long.parseLong(env.getProperty(
                "trading.score-buy.post-scout-add.execution.adaptive-extra-orders-per-day", "1"));
    }

    private boolean adaptiveExtraRequiresConfirmation() {
        return Boolean.parseBoolean(env.getProperty(
                "trading.score-buy.post-scout-add.execution.adaptive-extra-requires-confirmation", "true"));
    }

    private boolean budgetResidualExtraOrdersEnabled() {
        return Boolean.parseBoolean(env.getProperty(
                "trading.score-buy.post-scout-add.execution.budget-residual-extra-orders-enabled", "true"));
    }

    private long budgetResidualExtraOrdersPerDay() {
        return Long.parseLong(env.getProperty(
                "trading.score-buy.post-scout-add.execution.budget-residual-extra-orders-per-day", "1"));
    }

    private boolean budgetResidualExtraRequiresConfirmation() {
        return Boolean.parseBoolean(env.getProperty(
                "trading.score-buy.post-scout-add.execution.budget-residual-extra-requires-confirmation", "true"));
    }

    private boolean missedAlphaMicroSlotEnabled() {
        return Boolean.parseBoolean(env.getProperty(
                "trading.score-buy.post-scout-add.execution.missed-alpha-micro-slot-enabled", "true"));
    }

    private long missedAlphaMicroSlotMaxOrdersPerDay() {
        return Long.parseLong(env.getProperty(
                "trading.score-buy.post-scout-add.execution.missed-alpha-micro-slot-max-orders-per-day", "1"));
    }

    private int missedAlphaMicroSlotLookbackHours() {
        return Integer.parseInt(env.getProperty(
                "trading.score-buy.post-scout-add.execution.missed-alpha-micro-slot-lookback-hours", "16"));
    }

    private long missedAlphaMicroSlotMinFalseBlocks() {
        return Long.parseLong(env.getProperty(
                "trading.score-buy.post-scout-add.execution.missed-alpha-micro-slot-min-false-blocks", "3"));
    }

    private double missedAlphaMicroSlotReturnThresholdPct() {
        return Double.parseDouble(env.getProperty(
                "trading.score-buy.post-scout-add.execution.missed-alpha-micro-slot-return-threshold-pct", "0.10"));
    }

    private double missedAlphaMicroSlotMfeThresholdPct() {
        return Double.parseDouble(env.getProperty(
                "trading.score-buy.post-scout-add.execution.missed-alpha-micro-slot-mfe-threshold-pct", "1.00"));
    }

    private BigDecimal missedAlphaMicroSlotMaxNotional() {
        return new BigDecimal(env.getProperty(
                "trading.score-buy.post-scout-add.execution.missed-alpha-micro-slot-max-notional-usdt", "5"));
    }

    private int configuredMaxOpenPositions() {
        return Integer.parseInt(env.getProperty("trading.score-buy.post-scout-add.execution.max-open-positions", "8"));
    }

    private int effectiveMaxOpenPositions(JsonNode preview) {
        int configured = configuredMaxOpenPositions();
        BigDecimal budgetLimit = money(preview, "postScoutAddBudgetLimitUsdt", BigDecimal.ZERO);
        if (budgetLimit.compareTo(MIN_NOTIONAL) < 0) return configured;
        int budgetDerived = budgetLimit.divide(MIN_NOTIONAL, 0, java.math.RoundingMode.DOWN).intValue();
        return Math.min(MAX_BUDGET_AWARE_OPEN_POSITIONS, Math.max(configured, budgetDerived));
    }

    private BigDecimal maxNotional() {
        return new BigDecimal(env.getProperty("trading.score-buy.post-scout-add.execution.max-notional-usdt", "25"));
    }

    private BigDecimal maxLossBudget() {
        return new BigDecimal(env.getProperty("trading.score-buy.post-scout-add.execution.max-loss-usdt", DEFAULT_MAX_LOSS_BUDGET.toPlainString()));
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

    private boolean startsWith(String value, String prefix) {
        return value != null && value.toUpperCase(Locale.ROOT).startsWith(prefix.toUpperCase(Locale.ROOT));
    }

    private boolean runtimeEvidenceAvailable(String status) {
        return startsWith(status, "AVAILABLE_CANONICAL")
                || startsWith(status, "AVAILABLE_FALLBACK_SCORE_BUY");
    }

    private void copyArray(JsonNode array, List<String> target) {
        if (!array.isArray()) return;
        for (JsonNode value : array) {
            if (!value.asText("").isBlank()) target.add(value.asText());
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

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record OcoHealth(boolean ok, String reason) {
    }

    private record SchedulerFreshness(boolean fresh,
                                      long lagSeconds,
                                      long staleAfterSeconds,
                                      String lastCompletedAtUtc,
                                      String reason) {
    }

    private record BlockAuditSnapshot(String key, Instant at) {
    }

    private record OpportunityDedup(String currentOpportunityKey,
                                    String lastOpportunityKey,
                                    String lastOpportunityAtUtc,
                                    boolean distinct,
                                    String reason,
                                    int cooldownMinutes) {
    }

    private record MissedAlphaMicroSlot(boolean applied,
                                        String reason,
                                        long recentMissedAlphaCount) {
        private static MissedAlphaMicroSlot notApplied(String reason, long recentMissedAlphaCount) {
            return new MissedAlphaMicroSlot(false, reason, recentMissedAlphaCount);
        }
    }

    private record Evaluation(boolean eligible,
                              List<String> blockers,
                              List<String> warnings,
                              String state,
                              String addOnType,
                              BigDecimal notional,
                              BigDecimal maxNotional,
                              BigDecimal maxLossIfWrong,
                              String ocoHealth,
                              int openSameThesisPositions,
                              int maxOpenPositions,
                              long ordersToday,
                              LocalDateTime dailyCapCountSinceUtc,
                              String addOnQualityTier,
                              long addOnTierOrdersToday,
                              long addOnTierMaxOrdersPerDay,
                              long missedAlphaMicroSlotOrdersToday,
                              boolean missedAlphaMicroSlotApplied,
                              String missedAlphaMicroSlotReason,
                              long missedAlphaMicroSlotRecentFalseBlocks,
                              long baseMaxOrdersPerDay,
                              long maxOrdersPerDay,
                              boolean adaptiveDailyCapApplied,
                              boolean budgetResidualDailyCapApplied,
                              String adaptiveDailyCapReason,
                              String currentOpportunityKey,
                              String lastOpportunityKey,
                              String lastOpportunityAtUtc,
                              boolean distinctOpportunity,
                              String opportunityDedupReason,
                              int opportunityCooldownMinutes) {
        private String executionSlotTag() {
            return missedAlphaMicroSlotApplied ? "MISSED_ALPHA_MICRO" : "STANDARD";
        }
    }
}
