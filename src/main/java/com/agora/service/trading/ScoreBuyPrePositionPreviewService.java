package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
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
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ScoreBuyPrePositionPreviewService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final BigDecimal DEFAULT_MIN_NOTIONAL = new BigDecimal("5.00");
    private static final BigDecimal MIN_NOTIONAL_BRIDGE_TOLERANCE = new BigDecimal("0.25");

    private final ScoreBuyFormingDayObserverService formingDayObserverService;
    private final ScoreBuyConvictionPreviewService convictionPreviewService;
    private final RuntimeDecisionEvidenceService runtimeDecisionEvidenceService;
    private final BtLiveSignalRepository liveSignalRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String preview(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "previewScoreBuyPrePosition");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence write behavior changed.");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", sym);
        root.put("strategyId", sid);
        root.put("recommendedExecutionMode", "READ_ONLY_PRE_POSITION_PREFLIGHT");
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("telegramSent", false);
        root.put("writesRuntimeEvidence", false);

        JsonNode observer = readJson(formingDayObserverService.getStatus(sym, sid));
        JsonNode conviction = readJson(convictionPreviewService.preview(sym, sid));
        RuntimeEvidenceStatus runtimeEvidence = runtimeEvidence(sym, sid);

        String state = text(observer, "scoreBuyFormingState", "NONE");
        String convictionLevel = text(conviction, "conviction", "NONE");
        BigDecimal recommended = money(observer, "recommendedNotionalPreview", BigDecimal.ZERO);
        BigDecimal minNotional = money(observer, "exchangeMinNotionalUsdt", DEFAULT_MIN_NOTIONAL);
        BigDecimal remainingBudget = money(observer, "remainingPrePositionBudget", BigDecimal.ZERO);
        BigDecimal deployableUsdt = observer.path("capitalSnapshot").path("deployableTradingUsdt").isMissingNode()
                ? observer.path("capitalSnapshot").path("tradingUsdt").decimalValue()
                : money(observer.path("capitalSnapshot"), "deployableTradingUsdt", BigDecimal.ZERO);
        BigDecimal entry = money(observer.path("formingDailyFrame"), "close", BigDecimal.ZERO);
        boolean bridgeAvailable = minimumBridgeAvailable(recommended, minNotional, remainingBudget, deployableUsdt);

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> next = new ArrayList<>();

        copyArray(observer.path("observerHardBlockers"), blockers);
        if (!"EARLY_RECOVERY_SCOUT".equals(state)
                && !"PRE_POSITION".equals(state)
                && !"CONFIRMED_DAILY_SCORE_BUY".equals(state)) {
            blockers.add("FORMING_STATE_" + state + "_NOT_PRE_POSITION");
        }
        if (recommended.compareTo(BigDecimal.ZERO) <= 0) {
            blockers.add("NO_PRE_POSITION_NOTIONAL");
        }
        if (remainingBudget.compareTo(minNotional) < 0 && !bridgeAvailable) {
            blockers.add("SAME_THESIS_PRE_POSITION_BUDGET_BELOW_EXCHANGE_MIN");
        }
        if (deployableUsdt.compareTo(minNotional) < 0) {
            blockers.add("TRADING_USDT_BELOW_EXCHANGE_MIN");
        }
        if (!runtimeEvidence.available()) {
            blockers.add("RUNTIME_EVIDENCE_NOT_AVAILABLE_FOR_SCORE_BUY");
            next.add("RUNTIME_EVIDENCE_AVAILABLE");
        }
        if (entry.compareTo(BigDecimal.ZERO) <= 0) {
            blockers.add("ENTRY_PRICE_UNAVAILABLE");
        }

        String triggerStatus = text(conviction, "scoreBuyTriggerStatus", "UNKNOWN");
        if (!"DAILY_DIP_GATE_PASSED_ML_REQUIRED".equals(triggerStatus)) {
            warnings.add("OFFICIAL_DAILY_SCORE_BUY_NOT_TRIGGERED:" + triggerStatus);
        }
        if ("R2".equals(text(observer, "eventRiskLevel", ""))) {
            warnings.add("EVENT_RISK_R2_SCALES_PRE_POSITION_SIZE");
        } else if ("R3".equals(text(observer, "eventRiskLevel", ""))) {
            warnings.add("EVENT_RISK_R3_SCALES_PRE_POSITION_SIZE_AND_FORBIDS_LARGE_DEPLOY");
        }
        warnings.add("PRE_POSITION_PREVIEW_REQUIRES_OPERATOR_OR_FUTURE_WRITE_PATH_TO_RECHECK_OCO_HEALTH_AND_PREFLIGHT");

        BigDecimal proposed = proposedNotional(recommended, minNotional, remainingBudget, deployableUsdt,
                bridgeAvailable, blockers);
        boolean bridgeApplied = proposed.compareTo(minNotional) == 0
                && remainingBudget.compareTo(minNotional) < 0
                && bridgeAvailable;
        if (bridgeApplied) {
            warnings.add("SCORE_BUY_MIN_NOTIONAL_BRIDGE_APPLIED_WITHIN_TOLERANCE");
        }
        BigDecimal qty = entry.compareTo(BigDecimal.ZERO) > 0
                ? proposed.divide(entry, 8, RoundingMode.DOWN)
                : BigDecimal.ZERO;
        BigDecimal tp = ScoreBuyRiskPolicy.takeProfit(entry);
        BigDecimal sl = ScoreBuyRiskPolicy.disasterStopLoss(entry);
        BigDecimal maxLoss = ScoreBuyRiskPolicy.maxLossIfWrong(proposed, entry, sl);

        String ocoPreflight = entry.compareTo(BigDecimal.ZERO) > 0 && tp.compareTo(entry) > 0 && sl.compareTo(entry) < 0
                ? "PASS_PRICE_SHAPE_ONLY_OCO_HEALTH_REQUIRED_BEFORE_WRITE"
                : "NOT_READY_PRICE_SHAPE";
        if (!ocoPreflight.startsWith("PASS")) {
            blockers.add("OCO_PRICE_SHAPE_NOT_READY");
        } else {
            next.add("OCO_PREFLIGHT_PASS");
            next.add("OCO_HEALTH_OK");
        }
        next.add("DATA_FRESHNESS_OK");
        next.add("SYSTEM_HEALTH_OK");
        next.add("EXACT_DUPLICATE_OPPORTUNITY_FALSE");
        next.add("MAX_LOSS_WITHIN_BUDGET");
        next.add("CAPITAL_AND_RESERVE_CONSTRAINTS_OK");

        boolean canUseMinimum = recommended.compareTo(BigDecimal.ZERO) > 0
                && (remainingBudget.compareTo(minNotional) >= 0 || bridgeAvailable)
                && deployableUsdt.compareTo(minNotional) >= 0;
        boolean ready = blockers.isEmpty() && proposed.compareTo(BigDecimal.ZERO) > 0;

        root.put("prePositionReadiness", ready ? "READY_FOR_OPERATOR_REVIEW" : "NOT_READY");
        root.put("wouldMissOpportunityRisk", text(observer, "missedOpportunityRisk", "UNKNOWN"));
        root.put("scoreBuyFormingState", state);
        root.put("scoreBuyHoldingState", text(observer, "scoreBuyHoldingState", "UNKNOWN"));
        root.put("holdBtcMode", observer.path("holdBtcMode").asBoolean(false));
        root.put("holdBtcReason", text(observer, "holdBtcReason", "NONE"));
        root.put("autoSellAllowed", false);
        root.put("autoAddAllowed", ready
                && blockers.stream().noneMatch("SCORE_BUY_HOLD_BTC_MODE_NO_AUTO_ADD"::equals)
                && !observer.path("holdBtcMode").asBoolean(false));
        root.put("disasterOcoMode", text(observer, "disasterOcoMode", "KEEP_12PCT_HARD_OCO"));
        root.put("scoreBuyConviction", convictionLevel);
        root.put("scoreBuyTriggerStatus", triggerStatus);
        root.put("formingDailyRsi", observer.path("formingDailyRsi").asText("N/A"));
        root.put("formingDailyNearLowerBb", observer.path("formingDailyNearLowerBb").asBoolean(false));
        root.put("formingDailyVolumeRatio", observer.path("formingDailyVolumeRatio").asText("N/A"));
        root.put("formingDailyDipGateState", text(observer, "formingDailyDipGateState", "UNKNOWN"));
        root.put("intradayReversalStatus", text(observer, "intradayReversalStatus", "UNKNOWN"));
        root.put("eventRiskLevel", text(observer, "eventRiskLevel", "UNKNOWN"));
        root.put("eventRiskMultiplier", observer.path("eventRiskMultiplier").asDouble(1.0));
        putMoney(root, "recommendedNotionalPreview", recommended);
        putMoney(root, "minimumExecutableNotionalUsdt", minNotional);
        root.put("canUseMinimumOrder", canUseMinimum);
        root.put("minimumNotionalBridgeAvailable", bridgeAvailable);
        root.put("minimumNotionalBridgeApplied", bridgeApplied);
        putMoney(root, "minimumNotionalBridgeToleranceUsdt", MIN_NOTIONAL_BRIDGE_TOLERANCE);
        putMoney(root, "sameThesisBudgetOverrunUsdt",
                bridgeApplied ? minNotional.subtract(remainingBudget).max(BigDecimal.ZERO) : BigDecimal.ZERO);
        putMoney(root, "proposedNotionalUsdt", proposed);
        root.put("proposedQty", qty.stripTrailingZeros().toPlainString());
        putMoney(root, "entry", entry);
        putMoney(root, "tp", tp);
        putMoney(root, "sl", sl);
        ScoreBuyRiskPolicy.putStopLossPolicy(root);
        root.put("maxLossIfWrongUsdt", maxLoss.stripTrailingZeros().toPlainString());
        root.put("runtimeEvidenceStatus", runtimeEvidence.status());
        root.put("runtimeEvidenceMode", runtimeEvidence.mode());
        root.put("runtimeEvidenceRows", runtimeEvidence.rows());
        root.put("runtimeEvidenceFallbackRows", runtimeEvidence.fallbackRows());
        root.put("ocoPreflightStatus", ocoPreflight);
        root.set("capitalSnapshot", observer.path("capitalSnapshot").deepCopy());
        root.set("sameThesisExposure", observer.path("sameThesisExposure").deepCopy());
        root.set("hardBlockers", stringArray(blockers));
        root.set("warnings", stringArray(warnings));
        root.set("nextRequiredConditions", stringArray(next));
        root.set("nextRearmConditions", observer.path("nextRearmConditions").isArray()
                ? observer.path("nextRearmConditions").deepCopy()
                : objectMapper.createArrayNode());
        root.set("observerSummary", observerSummary(observer));
        return write(root);
    }

    private BigDecimal proposedNotional(BigDecimal recommended,
                                        BigDecimal minNotional,
                                        BigDecimal remainingBudget,
                                        BigDecimal deployableUsdt,
                                        boolean bridgeAvailable,
                                        List<String> blockers) {
        if (recommended.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);
        }
        BigDecimal cap = remainingBudget.min(deployableUsdt);
        if (cap.compareTo(minNotional) < 0 && !bridgeAvailable) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);
        }
        BigDecimal proposed = bridgeAvailable && remainingBudget.compareTo(minNotional) < 0
                ? minNotional
                : (recommended.compareTo(minNotional) < 0 ? minNotional : recommended);
        if (proposed.compareTo(cap) > 0 && !bridgeAvailable) {
            blockers.add("PROPOSED_NOTIONAL_EXCEEDS_AVAILABLE_PRE_POSITION_CAP");
            return BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);
        }
        return proposed.setScale(2, RoundingMode.DOWN);
    }

    private boolean minimumBridgeAvailable(BigDecimal recommended,
                                           BigDecimal minNotional,
                                           BigDecimal remainingBudget,
                                           BigDecimal deployableUsdt) {
        if (recommended.compareTo(BigDecimal.ZERO) <= 0) return false;
        if (deployableUsdt.compareTo(minNotional) < 0) return false;
        if (remainingBudget.compareTo(minNotional) >= 0) return false;
        return remainingBudget.add(MIN_NOTIONAL_BRIDGE_TOLERANCE).compareTo(minNotional) >= 0;
    }

    private RuntimeEvidenceStatus runtimeEvidence(String symbol, long strategyId) {
        List<RuntimeDecisionEvidence> rows = runtimeDecisionEvidenceService.listRecent(symbol, 1440, 100);
        long count = rows.stream()
                .filter(row -> row.getStrategyId() != null && row.getStrategyId() == strategyId)
                .count();
        if (count > 0) {
            return new RuntimeEvidenceStatus("AVAILABLE_CANONICAL_ROWS", "CANONICAL", count, 0, true);
        }
        try {
            long fallbackRows = liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(strategyId)
                    .stream()
                    .filter(row -> symbol.equalsIgnoreCase(row.getSymbol()))
                    .filter(this::isScoreBuyDurableLiveSignal)
                    .filter(row -> row.getOcoOrderListId() != null)
                    .count();
            if (fallbackRows > 0) {
                return new RuntimeEvidenceStatus("AVAILABLE_FALLBACK_SCORE_BUY_LIVE_SIGNAL_CHAIN",
                        "FALLBACK_LIVE_SIGNAL_CHAIN", 0, fallbackRows, true);
            }
        } catch (Exception ignored) {
            return new RuntimeEvidenceStatus("RUNTIME_EVIDENCE_FALLBACK_READ_FAILED",
                    "INSUFFICIENT", 0, 0, false);
        }
        return new RuntimeEvidenceStatus("NO_RECENT_CANONICAL_OR_FALLBACK_EVIDENCE_FOR_SCORE_BUY",
                "INSUFFICIENT", 0, 0, false);
    }

    private boolean isScoreBuyDurableLiveSignal(BtLiveSignal row) {
        String interval = row.getIntervalCode() == null ? "" : row.getIntervalCode().toUpperCase(Locale.ROOT);
        String reason = row.getFilterReason() == null ? "" : row.getFilterReason().toUpperCase(Locale.ROOT);
        String orderId = row.getExchangeOrderId() == null ? "" : row.getExchangeOrderId().toUpperCase(Locale.ROOT);
        return "SB_PRE".equals(interval)
                || "SB_ADD".equals(interval)
                || reason.startsWith("SCORE_BUY_EARLY_RECOVERY_SCOUT")
                || reason.startsWith("SCORE_BUY_POST_SCOUT_ADD")
                || orderId.startsWith("SCORE_BUY_PRE:")
                || orderId.startsWith("SCORE_BUY_ADD:");
    }

    private ObjectNode observerSummary(JsonNode observer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recommendedAction", text(observer, "recommendedAction", "UNKNOWN"));
        node.put("scoreBuyHoldingState", text(observer, "scoreBuyHoldingState", "UNKNOWN"));
        node.put("holdBtcMode", observer.path("holdBtcMode").asBoolean(false));
        node.put("holdBtcReason", text(observer, "holdBtcReason", "UNKNOWN"));
        node.put("disasterOcoMode", text(observer, "disasterOcoMode", "UNKNOWN"));
        node.put("executionFeasible", observer.path("executionFeasible").asBoolean(false));
        node.put("executionReadiness", text(observer, "executionReadiness", "UNKNOWN"));
        node.set("executionHardBlockers", observer.path("executionHardBlockers").deepCopy());
        return node;
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

    private void putMoney(ObjectNode node, String key, BigDecimal value) {
        node.put(key, value.setScale(2, RoundingMode.HALF_UP).toPlainString());
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

    private record RuntimeEvidenceStatus(String status,
                                         String mode,
                                         long rows,
                                         long fallbackRows,
                                         boolean available) {}
}
