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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreBuyConfirmedDeployAutoExecutionService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final String SIDE = "LONG";
    private static final String INTERVAL = "SB_CONF";
    private static final BigDecimal MIN_NOTIONAL = new BigDecimal("5.00");
    private static final BigDecimal DEFAULT_MAX_LOSS_BUDGET = new BigDecimal("2.00");

    private final ScoreBuyConfirmedDeployPreviewService previewService;
    private final RuntimeDecisionEvidenceService runtimeDecisionEvidenceService;
    private final OkxTradingService okxTradingService;
    private final OcoOrderStateInspector ocoOrderStateInspector;
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
        root.put("tool", "getScoreBuyConfirmedDeployAutoExecutionStatus");
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
        JsonNode preview = readJson(previewService.preview(sym, sid));
        Evaluation evaluation = evaluate(preview, sid, true);
        if (!evaluation.eligible()) {
            log.info("[ScoreBuyConfirmedDeploy] blocked: {}", evaluation.blockers());
            return write(baseStatus(preview, evaluation, sid));
        }

        BigDecimal notional = evaluation.notional();
        BigDecimal entry = money(preview, "entry", BigDecimal.ZERO);
        BigDecimal tp = money(preview, "tp", BigDecimal.ZERO);
        BigDecimal sl = money(preview, "sl", BigDecimal.ZERO);

        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(LocalDateTime.now(ZoneOffset.UTC));
        audit.setStrategyId(sid);
        audit.setSymbol(sym);
        audit.setIntervalCode(INTERVAL);
        audit.setEventType("SCORE_BUY_CONF_DEPLOY");
        audit.setOutcome("STARTED");
        audit.setBlocker("ScoreBuyConfirmedDeploy");
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
            signal = saveSignal(sid, sym, entry, tp, sl, buy, ocoAlgoId);
            audit.setLiveSignalId(signal.getId());
            audit.setOutcome("PASS");
            audit.setReason("EXECUTED_OCO_ATTACHED");
            audit.setContextJson(receipt(preview, buy, ocoAlgoId, "EXECUTED_OCO_ATTACHED", null, true));
            decisionAuditRepository.save(audit);
            writeEvidence(audit, signal, preview, buy, ocoAlgoId, "EXECUTED_OCO_ATTACHED");
            telegramService.sendAlert("SCORE_BUY confirmed deploy first tranche executed. symbol=" + sym
                            + " strategyId=" + sid + " notional=" + notional + " orderId=" + buy.getOrderId()
                            + " ocoAlgoId=" + ocoAlgoId,
                    false, "ScoreBuyConfirmedDeploy", "INFO");
            return writeExecutionResult(audit, true, true, buy, ocoAlgoId);
        } catch (Exception e) {
            audit.setOutcome("ERROR");
            audit.setReason("CRITICAL_UNPROTECTED_SCORE_BUY_CONFIRMED_DEPLOY: " + truncate(e.getMessage(), 340));
            audit.setContextJson(receipt(preview, buy, ocoAlgoId, "CRITICAL_UNPROTECTED_SCORE_BUY_CONFIRMED_DEPLOY",
                    e.getMessage(), true));
            decisionAuditRepository.save(audit);
            if (signal == null) {
                signal = saveSignal(sid, sym, entry, tp, sl, buy, null);
                audit.setLiveSignalId(signal.getId());
                decisionAuditRepository.save(audit);
            }
            writeEvidence(audit, signal, preview, buy, ocoAlgoId, "CRITICAL_UNPROTECTED_SCORE_BUY_CONFIRMED_DEPLOY");
            telegramService.sendAlert("CRITICAL_UNPROTECTED_SCORE_BUY_CONFIRMED_DEPLOY order placed but OCO attach/audit failed. symbol="
                            + sym + " orderId=" + buy.getOrderId() + " error=" + e.getMessage(),
                    false, "ScoreBuyConfirmedDeploy", "CRITICAL");
            return writeExecutionResult(audit, true, false, buy, ocoAlgoId);
        }
    }

    private Evaluation evaluate(JsonNode preview, long strategyId, boolean writePath) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        copyArray(preview.path("blockers"), blockers);
        copyArray(preview.path("warnings"), warnings);

        String symbol = text(preview, "symbol", DEFAULT_SYMBOL);
        String eventRisk = text(preview, "eventRiskLevel", "UNKNOWN");
        String ocoPreflight = text(preview, "ocoPreflightStatus", "UNKNOWN");
        BigDecimal notional = money(preview, "firstTrancheNotionalUsdt", BigDecimal.ZERO);
        BigDecimal maxLoss = money(preview, "maxLossIfWrongUsdt", BigDecimal.ZERO);
        BigDecimal maxNotional = maxNotional();

        if (!DEFAULT_SYMBOL.equals(symbol) || strategyId != DEFAULT_STRATEGY_ID) {
            blockers.add("SCOPE_NOT_ALLOWLISTED");
        }
        if (!preview.path("confirmedDeployEligible").asBoolean(false)) {
            blockers.add("CONFIRMED_DEPLOY_NOT_ELIGIBLE:" + text(preview, "confirmedDeployPolicy", "UNKNOWN"));
        }
        if (!preview.path("dailyScoreBuyConfirmed").asBoolean(false)) {
            blockers.add("DAILY_SCORE_BUY_NOT_CONFIRMED");
        }
        if ("R3".equals(eventRisk)) {
            blockers.add("EVENT_RISK_R3_BLOCKS_CONFIRMED_LARGE_DEPLOY");
        }
        if (!startsWith(ocoPreflight, "PASS")) {
            blockers.add("OCO_PREFLIGHT_NOT_PASS");
        }
        if (notional.compareTo(MIN_NOTIONAL) < 0) {
            blockers.add("NOTIONAL_BELOW_EXCHANGE_MIN");
        }
        if (notional.compareTo(maxNotional) > 0) {
            blockers.add("NOTIONAL_EXCEEDS_SCORE_BUY_CONFIRMED_DEPLOY_CAP");
        }
        if (maxLoss.compareTo(maxLossBudget()) > 0) {
            blockers.add("MAX_LOSS_EXCEEDS_SCORE_BUY_CONFIRMED_DEPLOY_BUDGET");
        }
        if (!runtimeEvidenceAvailable(symbol, strategyId)) {
            blockers.add("RUNTIME_EVIDENCE_NOT_AVAILABLE");
        }
        OcoHealth ocoHealth = new OcoHealth(true, "SKIPPED_CONFIRMED_DEPLOY_NOT_READY");
        if (blockers.isEmpty()) {
            ocoHealth = checkExistingOcoHealth(strategyId, symbol);
            if (!ocoHealth.ok()) {
                blockers.add("OCO_HEALTH_ABNORMAL:" + ocoHealth.reason());
            }
        }
        long ordersToday = liveSignalRepository.countScoreBuyConfirmedDeployTradesSince(
                strategyId, symbol, LocalDate.now(ZoneOffset.UTC).atStartOfDay());
        if (ordersToday >= maxOrdersPerDay()) {
            blockers.add("DAILY_SCORE_BUY_CONFIRMED_DEPLOY_CAP_REACHED");
        }
        int openSameThesisPositions = liveSignalRepository
                .findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(strategyId).size();
        if (openSameThesisPositions >= maxOpenPositions()) {
            blockers.add("SAME_THESIS_OPEN_POSITION_LIMIT_REACHED");
        }
        if (writePath && dryRun()) {
            blockers.add("DRY_RUN_ENABLED");
        }
        return new Evaluation(blockers.stream().distinct().toList().isEmpty(),
                blockers.stream().distinct().toList(),
                warnings.stream().distinct().toList(),
                notional,
                maxNotional,
                maxLoss,
                ocoHealth.reason(),
                openSameThesisPositions,
                maxOpenPositions(),
                ordersToday,
                maxOrdersPerDay());
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
        root.put("confirmedDeployPolicy", text(preview, "confirmedDeployPolicy", "UNKNOWN"));
        root.put("dailyScoreBuyConfirmed", preview.path("dailyScoreBuyConfirmed").asBoolean(false));
        root.put("scoreBuyFormingState", text(preview, "scoreBuyFormingState", "UNKNOWN"));
        root.put("scoreBuyHoldingState", text(preview, "scoreBuyHoldingState", "UNKNOWN"));
        root.put("holdBtcMode", preview.path("holdBtcMode").asBoolean(false));
        root.put("holdBtcReason", text(preview, "holdBtcReason", "NONE"));
        root.put("autoSellAllowed", false);
        root.put("autoAddAllowed", preview.path("autoAddAllowed").asBoolean(false) && evaluation.eligible());
        root.put("disasterOcoMode", text(preview, "disasterOcoMode", "KEEP_12PCT_HARD_OCO"));
        root.put("eventRiskLevel", text(preview, "eventRiskLevel", "UNKNOWN"));
        root.put("firstTrancheNotionalUsdt", evaluation.notional().stripTrailingZeros().toPlainString());
        root.put("maxNotionalUsdt", evaluation.maxNotional().stripTrailingZeros().toPlainString());
        root.put("entry", text(preview, "entry", "0"));
        root.put("tp", text(preview, "tp", "0"));
        root.put("sl", text(preview, "sl", "0"));
        root.put("maxLossIfWrongUsdt", evaluation.maxLoss().stripTrailingZeros().toPlainString());
        root.put("maxLossBudgetUsdt", maxLossBudget().stripTrailingZeros().toPlainString());
        root.put("dailyCapScope", "SCORE_BUY_CONFIRMED_DEPLOY_STRATEGY_SYMBOL");
        root.put("scoreBuyConfirmedDeployOrdersToday", evaluation.ordersToday());
        root.put("maxOrdersPerDay", evaluation.maxOrdersPerDay());
        root.put("maxOpenPositions", evaluation.maxOpenPositions());
        root.put("openSameThesisPositions", evaluation.openSameThesisPositions());
        root.put("existingOcoHealth", evaluation.ocoHealth());
        root.put("stagedExecutionMode", "SCORE_BUY_CONFIRMED_DEPLOY_AUTO_FIRST_TRANCHE");
        root.set("plannedTranches", preview.path("plannedTranches").deepCopy());
        root.set("blockers", stringArray(evaluation.blockers()));
        root.set("primaryBlockers", stringArray(primaryBlockers(evaluation.blockers())));
        root.set("secondaryBlockers", stringArray(secondaryBlockers(evaluation.blockers())));
        root.set("capacityBlockers", stringArray(capacityBlockers(evaluation.blockers())));
        root.put("primaryNoBuyReason", primaryNoBuyReason(evaluation));
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
        boolean readinessBlocked = blockers.stream().anyMatch(this::isConfirmedDeployReadinessBlocker);
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

    private String primaryNoBuyReason(Evaluation evaluation) {
        if (evaluation.eligible()) {
            return "ELIGIBLE";
        }
        List<String> primary = primaryBlockers(evaluation.blockers());
        if (primary.stream().anyMatch(this::isConfirmedDeployReadinessBlocker)) {
            return "CONFIRMED_DEPLOY_NOT_READY";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("EVENT_RISK_R3"))) {
            return "EVENT_RISK_R3_BLOCKS_CONFIRMED_LARGE_DEPLOY";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("RUNTIME_EVIDENCE_NOT_AVAILABLE"))) {
            return "RUNTIME_EVIDENCE_NOT_AVAILABLE";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("OCO_PREFLIGHT_NOT_PASS")
                || blocker.startsWith("OCO_HEALTH_ABNORMAL"))) {
            return "OCO_NOT_READY";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("DAILY_SCORE_BUY_CONFIRMED_DEPLOY_CAP_REACHED"))) {
            return "DAILY_CAP_WAIT";
        }
        if (primary.stream().anyMatch(blocker -> blocker.startsWith("SAME_THESIS_OPEN_POSITION_LIMIT_REACHED"))) {
            return "OPEN_POSITION_LIMIT_REACHED";
        }
        return primary.isEmpty() ? "UNKNOWN_BLOCKER" : primary.get(0);
    }

    private String blockingInterpretation(Evaluation evaluation) {
        if (evaluation.eligible()) {
            return "Confirmed deploy is eligible; execution still rechecks all write-path gates before any order.";
        }
        if (evaluation.blockers().stream().anyMatch(this::isConfirmedDeployReadinessBlocker)) {
            return "Primary reason is confirmed daily SCORE_BUY readiness, not capacity: wait for the official daily thesis before interpreting notional or daily-cap blockers as actionable.";
        }
        return "Blocked by the listed primary blockers; no order is sent.";
    }

    private boolean isConfirmedDeployReadinessBlocker(String blocker) {
        return blocker != null
                && (blocker.startsWith("DAILY_SCORE_BUY_NOT_CONFIRMED")
                || blocker.startsWith("CONFIRMED_DEPLOY_NOT_ELIGIBLE"));
    }

    private boolean isDerivedCapacityBlocker(String blocker) {
        return "NOTIONAL_BELOW_EXCHANGE_MIN".equals(blocker)
                || "DAILY_SCORE_BUY_CONFIRMED_DEPLOY_CAP_REACHED".equals(blocker);
    }

    private boolean isCapacityBlocker(String blocker) {
        return isDerivedCapacityBlocker(blocker)
                || "SAME_THESIS_OPEN_POSITION_LIMIT_REACHED".equals(blocker)
                || "NOTIONAL_EXCEEDS_SCORE_BUY_CONFIRMED_DEPLOY_CAP".equals(blocker)
                || "MAX_LOSS_EXCEEDS_SCORE_BUY_CONFIRMED_DEPLOY_BUDGET".equals(blocker);
    }

    private boolean runtimeEvidenceAvailable(String symbol, long strategyId) {
        try {
            return runtimeDecisionEvidenceService.listRecent(symbol, 1440, 100)
                    .stream()
                    .anyMatch(row -> row.getStrategyId() != null && row.getStrategyId() == strategyId);
        } catch (Exception e) {
            return false;
        }
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
                return new OcoHealth(false, "OCO_FILLED_DB_OPEN:position=" + row.getId());
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
        signal.setExchangeOrderId("SCORE_BUY_CONF:" + buy.getOrderId());
        signal.setActualEntryPrice(buy.getAvgPrice());
        signal.setTradedQty(buy.getQty());
        signal.setOcoQty(buy.getQty());
        signal.setOcoOrderListId(ocoAlgoId);
        signal.setSide(SIDE);
        signal.setFilterReason("SCORE_BUY_CONFIRMED_DEPLOY_FIRST_TRANCHE");
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
        evidence.setSignalSource("SCORE_BUY_CONFIRMED_DEPLOY");
        evidence.setFeaturesSnapshotJson(preview.toString());
        evidence.setFreshnessState("PASS_CONFIRMED_DAILY_RECHECK");
        evidence.setSelectedAction("SCORE_BUY_CONFIRMED_DEPLOY_EXECUTE");
        evidence.setReason(outcome);
        evidence.setPolicyMode("AUTO_APPROVED_SCORE_BUY_CONFIRMED_DEPLOY");
        evidence.setFinalOutcome(outcome);
        evidence.setOrderSent(true);
        evidence.setExecutionMode("SCORE_BUY_CONFIRMED_DEPLOY_AUTO");
        evidence.setOcoOrderListId(ocoAlgoId == null ? null : String.valueOf(ocoAlgoId));
        evidence.setExecutionPreviewJson(receipt(preview, buy, ocoAlgoId, outcome, null, true));
        evidenceRepository.save(evidence);
    }

    private String receipt(JsonNode preview, TradeResult buy, Long ocoAlgoId, String status, String error,
                           boolean orderAttempted) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("version", "score-buy-confirmed-deploy-v0");
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
        node.put("notionalUsdt", text(preview, "firstTrancheNotionalUsdt", "0"));
        node.put("scoreBuyFormingState", text(preview, "scoreBuyFormingState", "UNKNOWN"));
        node.put("eventRiskLevel", text(preview, "eventRiskLevel", "UNKNOWN"));
        node.put("autonomousExecutionScope", "BTCUSDT/485/LONG/SCORE_BUY_CONFIRMED_DEPLOY");
        if (error != null) node.put("error", truncate(error, 420));
        return node.toString();
    }

    private String writeExecutionResult(BtDecisionAudit audit, boolean orderSent, boolean ocoAttached,
                                        TradeResult buy, Long ocoAlgoId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "executeScoreBuyConfirmedDeployIfEligible");
        root.put("boundary", "INTERNAL_BOUNDED_SCORE_BUY_CONFIRMED_DEPLOY; no strategy/grid/fund/Earn behavior changed.");
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
        return Boolean.parseBoolean(env.getProperty("trading.score-buy.confirmed-deploy.execution.enabled", "false"));
    }

    boolean dryRun() {
        return Boolean.parseBoolean(env.getProperty("trading.score-buy.confirmed-deploy.execution.dry-run", "true"));
    }

    private long maxOrdersPerDay() {
        return Long.parseLong(env.getProperty("trading.score-buy.confirmed-deploy.execution.max-orders-per-day", "1"));
    }

    private int maxOpenPositions() {
        return Integer.parseInt(env.getProperty("trading.score-buy.confirmed-deploy.execution.max-open-positions", "12"));
    }

    private BigDecimal maxNotional() {
        return new BigDecimal(env.getProperty("trading.score-buy.confirmed-deploy.execution.max-notional-usdt", "50"));
    }

    private BigDecimal maxLossBudget() {
        return new BigDecimal(env.getProperty("trading.score-buy.confirmed-deploy.execution.max-loss-usdt", DEFAULT_MAX_LOSS_BUDGET.toPlainString()));
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
                              BigDecimal maxLoss,
                              String ocoHealth,
                              int openSameThesisPositions,
                              int maxOpenPositions,
                              long ordersToday,
                              long maxOrdersPerDay) {
    }

    private record OcoHealth(boolean ok, String reason) {
    }
}
