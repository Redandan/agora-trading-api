package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.TelegramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreBuyPrePositionAutoExecutionService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final String SIDE = "LONG";
    private static final String INTERVAL = "SB_PRE";
    private static final BigDecimal MIN_NOTIONAL = new BigDecimal("5.00");

    private final ScoreBuyPrePositionExecutionPolicyPreviewService previewService;
    private final OkxTradingService okxTradingService;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper;
    private final Environment env;

    @Transactional(readOnly = true)
    public String status(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        JsonNode preview = readJson(previewService.preview(sym, sid));
        Evaluation evaluation = evaluate(preview, sid, false);
        ObjectNode root = baseStatus(preview, evaluation, sid);
        root.put("tool", "getScoreBuyPrePositionAutoExecutionStatus");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior changed.");
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("writesRuntimeEvidence", false);
        return write(root);
    }

    @Transactional
    public String executeIfEligible(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        JsonNode preview = readJson(previewService.preview(sym, sid));
        Evaluation evaluation = evaluate(preview, sid, true);
        if (!evaluation.eligible()) {
            log.info("[ScoreBuyPrePositionAutoExecution] blocked: {}", evaluation.blockers());
            return write(baseStatus(preview, evaluation, sid));
        }

        BigDecimal notional = money(preview, "proposedNotionalUsdt", BigDecimal.ZERO);
        BigDecimal previewEntry = money(preview, "entry", BigDecimal.ZERO);
        BigDecimal tp = money(preview, "tp", BigDecimal.ZERO);
        BigDecimal sl = money(preview, "sl", BigDecimal.ZERO);

        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(LocalDateTime.now(ZoneOffset.UTC));
        audit.setStrategyId(sid);
        audit.setSymbol(sym);
        audit.setIntervalCode(INTERVAL);
        audit.setEventType("SCORE_BUY_PRE_POSITION_EXECUTION");
        audit.setOutcome("STARTED");
        audit.setBlocker("ScoreBuyEarlyRecoveryScout");
        audit.setReason("ORDER_PLACEMENT_STARTED");
        audit.setContextJson(preview.toString());
        audit = decisionAuditRepository.save(audit);

        TradeResult buy;
        try {
            buy = okxTradingService.placeMarketBuy(sym, notional.doubleValue());
        } catch (Exception e) {
            audit.setOutcome("ERROR");
            audit.setReason("ORDER_FAILED: " + truncate(e.getMessage(), 420));
            audit.setContextJson(receipt(preview, null, null, "ORDER_FAILED", e.getMessage(), false));
            decisionAuditRepository.save(audit);
            return writeExecutionResult(audit, false, false, null, null);
        }

        Long ocoAlgoId = null;
        BtLiveSignal signal = null;
        try {
            ocoAlgoId = okxTradingService.placeOco(sym, buy.getQty(), tp, sl);
            signal = saveSignal(sid, sym, previewEntry, tp, sl, buy, ocoAlgoId);
            audit.setLiveSignalId(signal.getId());
            audit.setOutcome("PASS");
            audit.setReason("EXECUTED_OCO_ATTACHED");
            audit.setContextJson(receipt(preview, buy, ocoAlgoId, "EXECUTED_OCO_ATTACHED", null, true));
            decisionAuditRepository.save(audit);
            writeEvidence(audit, signal, preview, buy, ocoAlgoId, "EXECUTED_OCO_ATTACHED");
            telegramService.sendAlert("SCORE_BUY early-recovery pre-position executed. symbol=" + sym
                            + " strategyId=" + sid + " notional=" + notional + " orderId=" + buy.getOrderId()
                            + " ocoAlgoId=" + ocoAlgoId,
                    false, "ScoreBuyPrePosition", "INFO");
            return writeExecutionResult(audit, true, true, buy, ocoAlgoId);
        } catch (Exception e) {
            audit.setOutcome("ERROR");
            audit.setReason("CRITICAL_UNPROTECTED_SCORE_BUY_PRE_POSITION: " + truncate(e.getMessage(), 360));
            audit.setContextJson(receipt(preview, buy, ocoAlgoId, "CRITICAL_UNPROTECTED_SCORE_BUY_PRE_POSITION", e.getMessage(), true));
            decisionAuditRepository.save(audit);
            if (signal == null) {
                signal = saveSignal(sid, sym, previewEntry, tp, sl, buy, null);
                audit.setLiveSignalId(signal.getId());
                decisionAuditRepository.save(audit);
            }
            writeEvidence(audit, signal, preview, buy, ocoAlgoId, "CRITICAL_UNPROTECTED_SCORE_BUY_PRE_POSITION");
            telegramService.sendAlert("CRITICAL_UNPROTECTED_SCORE_BUY_PRE_POSITION order placed but OCO attach/audit failed. symbol="
                            + sym + " orderId=" + buy.getOrderId() + " error=" + e.getMessage(),
                    false, "ScoreBuyPrePosition", "CRITICAL");
            return writeExecutionResult(audit, true, false, buy, ocoAlgoId);
        }
    }

    private Evaluation evaluate(JsonNode preview, long strategyId, boolean writePath) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        copyArray(preview.path("blockers"), blockers);
        copyArray(preview.path("warnings"), warnings);
        String symbol = text(preview, "symbol", DEFAULT_SYMBOL);
        String policy = text(preview, "executionPolicy", "UNKNOWN");
        String eventRisk = text(preview, "eventRiskLevel", "UNKNOWN");
        BigDecimal notional = money(preview, "proposedNotionalUsdt", BigDecimal.ZERO);
        BigDecimal maxNotional = maxNotional();

        if (!DEFAULT_SYMBOL.equals(symbol) || strategyId != DEFAULT_STRATEGY_ID) {
            blockers.add("SCOPE_NOT_ALLOWLISTED");
        }
        if (!"AUTO_APPROVED_SCORE_BUY_PRE_POSITION_PREVIEW".equals(policy)
                && !"READY_FOR_MANUAL_APPROVAL".equals(policy)) {
            blockers.add("EXECUTION_POLICY_NOT_READY:" + policy);
        }
        if ("R3".equals(eventRisk)) {
            blockers.add("EVENT_RISK_R3_REQUIRES_EXPLICIT_OVERRIDE");
        }
        if (notional.compareTo(MIN_NOTIONAL) < 0) {
            blockers.add("NOTIONAL_BELOW_EXCHANGE_MIN");
        }
        if (notional.compareTo(maxNotional) > 0) {
            blockers.add("NOTIONAL_EXCEEDS_SCORE_BUY_PRE_POSITION_CAP");
        }
        long scoreBuyPrePositionOrdersToday = liveSignalRepository.countScoreBuyPrePositionAutoTradesSince(
                strategyId, symbol, LocalDate.now(ZoneOffset.UTC).atStartOfDay());
        if (scoreBuyPrePositionOrdersToday >= maxOrdersPerDay()) {
            blockers.add("DAILY_SCORE_BUY_PRE_POSITION_CAP_REACHED");
        }
        int openSameThesisPositions = liveSignalRepository
                .findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(strategyId).size();
        if (openSameThesisPositions >= maxOpenPositions()) {
            blockers.add("SAME_THESIS_OPEN_POSITION_LIMIT_REACHED");
        }
        if (writePath && dryRun()) {
            blockers.add("DRY_RUN_ENABLED");
        }
        return new Evaluation(blockers.isEmpty(), blockers, warnings, notional, maxNotional,
                openSameThesisPositions, maxOpenPositions(), scoreBuyPrePositionOrdersToday, maxOrdersPerDay());
    }

    private ObjectNode baseStatus(JsonNode preview, Evaluation evaluation, long strategyId) {
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
        root.put("executionPolicy", text(preview, "executionPolicy", "UNKNOWN"));
        root.put("scoreBuyFormingState", text(preview, "scoreBuyFormingState", "UNKNOWN"));
        root.put("scoreBuyHoldingState", text(preview, "scoreBuyHoldingState", "UNKNOWN"));
        root.put("holdBtcMode", preview.path("holdBtcMode").asBoolean(false));
        root.put("holdBtcReason", text(preview, "holdBtcReason", "NONE"));
        root.put("autoSellAllowed", false);
        root.put("autoAddAllowed", preview.path("autoAddAllowed").asBoolean(false) && evaluation.eligible());
        root.put("disasterOcoMode", text(preview, "disasterOcoMode", "KEEP_12PCT_HARD_OCO"));
        root.put("eventRiskLevel", text(preview, "eventRiskLevel", "UNKNOWN"));
        root.put("proposedNotionalUsdt", evaluation.notional().stripTrailingZeros().toPlainString());
        root.put("maxNotionalUsdt", evaluation.maxNotional().stripTrailingZeros().toPlainString());
        root.put("entry", text(preview, "entry", "0"));
        root.put("tp", text(preview, "tp", "0"));
        root.put("sl", text(preview, "sl", "0"));
        root.put("maxLossIfWrongUsdt", text(preview, "maxLossIfWrongUsdt", "0"));
        root.put("dailyCapScope", "SCORE_BUY_PRE_POSITION_STRATEGY_SYMBOL");
        root.put("dailyCapCountPredicate", "strategyId+symbol+LONG+(intervalCode=SB_PRE OR filterReason=SCORE_BUY_EARLY_RECOVERY_SCOUT OR exchangeOrderId=SCORE_BUY_PRE:*)");
        root.put("scoreBuyPrePositionOrdersToday", evaluation.ordersToday());
        root.put("maxOrdersPerDay", evaluation.maxOrdersPerDay());
        root.put("maxOpenPositions", evaluation.maxOpenPositions());
        root.put("openSameThesisPositions", evaluation.openSameThesisPositions());
        root.put("stagedBudgetEnforced", true);
        root.put("stagedExecutionMode", "SCORE_BUY_PRE_POSITION_AUTO");
        root.put("sameThesisOpenPositionPolicy",
                "existing same-thesis exposure is allowed while staged budget remains and open count is below maxOpenPositions; exact duplicate opportunity remains blocked by preview policy.");
        root.set("blockers", stringArray(evaluation.blockers()));
        root.set("primaryBlockers", stringArray(primaryBlockers(evaluation.blockers())));
        root.set("secondaryBlockers", stringArray(secondaryBlockers(evaluation.blockers())));
        root.set("capacityBlockers", stringArray(capacityBlockers(evaluation.blockers())));
        root.put("primaryNoBuyReason", primaryNoBuyReason(evaluation, text(preview, "scoreBuyFormingState", "UNKNOWN")));
        root.put("blockingInterpretation", blockingInterpretation(evaluation));
        root.set("warnings", stringArray(evaluation.warnings()));
        root.set("preview", preview.deepCopy());
        return root;
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
        boolean readinessBlocked = blockers.stream().anyMatch(this::isPrePositionReadinessBlocker);
        if (!readinessBlocked) return List.of();
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

    private String primaryNoBuyReason(Evaluation evaluation, String formingState) {
        if (evaluation.eligible()) {
            return "ELIGIBLE";
        }
        List<String> primary = primaryBlockers(evaluation.blockers());
        if (primary.stream().anyMatch(this::isPrePositionReadinessBlocker)) {
            return "PRE_POSITION_NOT_READY:" + formingState;
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("EVENT_RISK_R3"))) {
            return "EVENT_RISK_R3_REQUIRES_EXPLICIT_OVERRIDE";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("SCOPE_NOT_ALLOWLISTED"))) {
            return "SCOPE_NOT_ALLOWLISTED";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("DAILY_SCORE_BUY_PRE_POSITION_CAP_REACHED"))) {
            return "DAILY_CAP_WAIT";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("SAME_THESIS_OPEN_POSITION_LIMIT_REACHED"))) {
            return "OPEN_POSITION_LIMIT_REACHED";
        }
        return primary.isEmpty() ? "UNKNOWN_BLOCKER" : primary.get(0);
    }

    private String blockingInterpretation(Evaluation evaluation) {
        if (evaluation.eligible()) {
            return "Pre-position is eligible; execution still rechecks all write-path gates before any order.";
        }
        if (evaluation.blockers().stream().anyMatch(this::isPrePositionReadinessBlocker)) {
            return "Primary reason is pre-position readiness, not capacity: wait for forming-day/pre-position gates before interpreting notional, budget, daily-cap, or open-position blockers as actionable.";
        }
        return "Blocked by the listed primary blockers; no order is sent.";
    }

    private boolean isPrePositionReadinessBlocker(String blocker) {
        return blocker != null
                && (blocker.startsWith("FORMING_STATE_")
                || blocker.startsWith("PRE_POSITION_NOT_READY")
                || blocker.startsWith("EXECUTION_POLICY_NOT_READY")
                || "NO_PRE_POSITION_NOTIONAL".equals(blocker)
                || "NO_PROPOSED_PRE_POSITION_NOTIONAL".equals(blocker));
    }

    private boolean isDerivedCapacityBlocker(String blocker) {
        return "NOTIONAL_BELOW_EXCHANGE_MIN".equals(blocker)
                || "EXCHANGE_MINIMUM_ORDER_NOT_FEASIBLE".equals(blocker)
                || "SAME_THESIS_PRE_POSITION_BUDGET_BELOW_EXCHANGE_MIN".equals(blocker)
                || "SAME_THESIS_STAGED_ADD_BUDGET_NOT_AVAILABLE".equals(blocker)
                || "DAILY_SCORE_BUY_PRE_POSITION_CAP_REACHED".equals(blocker)
                || "SAME_THESIS_OPEN_POSITION_LIMIT_REACHED".equals(blocker);
    }

    private boolean isCapacityBlocker(String blocker) {
        return isDerivedCapacityBlocker(blocker)
                || "NOTIONAL_EXCEEDS_SCORE_BUY_PRE_POSITION_CAP".equals(blocker);
    }

    private BtLiveSignal saveSignal(long strategyId, String symbol, BigDecimal entry, BigDecimal tp, BigDecimal sl,
                                    TradeResult buy, Long ocoAlgoId) {
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
        signal.setExchangeOrderId("SCORE_BUY_PRE:" + buy.getOrderId());
        signal.setActualEntryPrice(buy.getAvgPrice());
        signal.setTradedQty(buy.getQty());
        signal.setOcoQty(buy.getQty());
        signal.setOcoOrderListId(ocoAlgoId);
        signal.setSide(SIDE);
        signal.setFilterReason("SCORE_BUY_EARLY_RECOVERY_SCOUT");
        return liveSignalRepository.save(signal);
    }

    private void writeEvidence(BtDecisionAudit audit, BtLiveSignal signal, JsonNode preview,
                               TradeResult buy, Long ocoAlgoId, String outcome) {
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setDecisionId(audit.getId());
        evidence.setEvidenceTime(LocalDateTime.now(ZoneOffset.UTC));
        evidence.setSymbol(signal.getSymbol());
        evidence.setSide(SIDE);
        evidence.setStrategyId(signal.getStrategyId());
        evidence.setIntervalCode(INTERVAL);
        evidence.setLiveSignalId(signal.getId());
        evidence.setSignalSource("SCORE_BUY_EARLY_RECOVERY_SCOUT");
        evidence.setFeaturesSnapshotJson(preview.toString());
        evidence.setFreshnessState("PASS_PREVIEW_RECHECK");
        evidence.setSelectedAction("SCORE_BUY_PRE_POSITION_EXECUTE");
        evidence.setReason(outcome);
        evidence.setPolicyMode("AUTO_APPROVED_SCORE_BUY_PRE_POSITION");
        evidence.setFinalOutcome(outcome);
        evidence.setOrderSent(true);
        evidence.setExecutionMode("SCORE_BUY_PRE_POSITION_AUTO");
        evidence.setOcoOrderListId(ocoAlgoId == null ? null : String.valueOf(ocoAlgoId));
        evidence.setExecutionPreviewJson(receipt(preview, buy, ocoAlgoId, outcome, null, true));
        evidenceRepository.save(evidence);
    }

    private String receipt(JsonNode preview, TradeResult buy, Long ocoAlgoId, String status, String error, boolean orderAttempted) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("version", "score-buy-pre-position-v0");
        node.put("status", status);
        node.put("symbol", text(preview, "symbol", DEFAULT_SYMBOL));
        node.put("strategyId", text(preview, "strategyId", String.valueOf(DEFAULT_STRATEGY_ID)));
        node.put("side", SIDE);
        node.put("orderAttempted", orderAttempted);
        node.put("orderSent", buy != null);
        node.put("ocoAttached", ocoAlgoId != null);
        node.put("orderId", buy == null ? null : buy.getOrderId());
        node.put("ocoAlgoId", ocoAlgoId);
        node.put("qty", buy == null ? null : buy.getQty().toPlainString());
        node.put("entryPrice", buy == null ? text(preview, "entry", "0") : buy.getAvgPrice().toPlainString());
        node.put("tp", text(preview, "tp", "0"));
        node.put("sl", text(preview, "sl", "0"));
        node.put("notionalUsdt", text(preview, "proposedNotionalUsdt", "0"));
        node.put("scoreBuyFormingState", text(preview, "scoreBuyFormingState", "UNKNOWN"));
        node.put("eventRiskLevel", text(preview, "eventRiskLevel", "UNKNOWN"));
        node.put("autonomousExecutionScope", "BTCUSDT/485/LONG/SCORE_BUY_PRE_POSITION");
        if (error != null) node.put("error", truncate(error, 420));
        return node.toString();
    }

    private String writeExecutionResult(BtDecisionAudit audit, boolean orderSent, boolean ocoAttached,
                                        TradeResult buy, Long ocoAlgoId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "executeScoreBuyPrePositionIfEligible");
        root.put("boundary", "INTERNAL_BOUNDED_SCORE_BUY_PRE_POSITION; no strategy/grid/fund/Earn behavior changed.");
        root.put("auditId", audit.getId());
        root.put("status", audit.getReason());
        root.put("orderSent", orderSent);
        root.put("ocoAttached", ocoAttached);
        root.put("orderId", buy == null ? null : buy.getOrderId());
        root.put("ocoAlgoId", ocoAlgoId);
        root.put("reason", audit.getReason());
        return write(root);
    }

    boolean enabled() {
        return Boolean.parseBoolean(env.getProperty("trading.score-buy.pre-position.execution.enabled", "false"));
    }

    boolean dryRun() {
        return Boolean.parseBoolean(env.getProperty("trading.score-buy.pre-position.execution.dry-run", "true"));
    }

    private long maxOrdersPerDay() {
        return Long.parseLong(env.getProperty("trading.score-buy.pre-position.execution.max-orders-per-day", "2"));
    }

    private int maxOpenPositions() {
        return Integer.parseInt(env.getProperty("trading.score-buy.pre-position.execution.max-open-positions", "8"));
    }

    private BigDecimal maxNotional() {
        return new BigDecimal(env.getProperty("trading.score-buy.pre-position.execution.max-notional-usdt", "25"));
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

    private record Evaluation(boolean eligible,
                              List<String> blockers,
                              List<String> warnings,
                              BigDecimal notional,
                              BigDecimal maxNotional,
                              int openSameThesisPositions,
                              int maxOpenPositions,
                              long ordersToday,
                              long maxOrdersPerDay) {
    }
}
