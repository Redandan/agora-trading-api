package com.agora.service.trading;

import com.agora.config.properties.Strategy508TimeExitProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.TelegramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.agora.service.trading.Strategy508TimeExitPolicy.HOLD_HOURS;
import static com.agora.service.trading.Strategy508TimeExitPolicy.INTERVAL;
import static com.agora.service.trading.Strategy508TimeExitPolicy.KLINE_SOURCE;
import static com.agora.service.trading.Strategy508TimeExitPolicy.POLICY_MODE;

@Service
@RequiredArgsConstructor
@Slf4j
public class Strategy508TimeExitOutcomeService {

    private final Strategy508TimeExitProperties properties;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final MdKlineRepository klineRepository;
    private final Strategy508TimeExitCandidateService candidateService;
    private final SpotPositionCloseService closeService;
    private final OkxTradingService okxTradingService;
    private final ObjectMapper objectMapper;
    private final TelegramService telegramService;

    @Transactional
    public void processPending() {
        if (!properties.enabled()) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<RuntimeDecisionEvidence> rows = evidenceRepository
                .findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(POLICY_MODE, now.minusDays(365));
        for (RuntimeDecisionEvidence evidence : rows) {
            if (!"PENDING_24H".equals(evidence.getFinalOutcome())) continue;
            try {
                if (evidence.getLiveSignalId() == null) resolveShadow(evidence, now);
                else resolveLive(evidence, now);
            } catch (Exception e) {
                log.warn("[508TimeExit] outcome resolution failed evidence={} liveSignal={} error={}",
                        evidence.getId(), evidence.getLiveSignalId(), e.getMessage());
            }
        }
    }

    private void resolveShadow(RuntimeDecisionEvidence evidence, LocalDateTime now) {
        ObjectNode context = context(evidence);
        LocalDateTime barOpen = parseTime(context.path("barOpenTime").asText(null));
        LocalDateTime decisionTime = parseTime(context.path("decisionTime").asText(null));
        if (barOpen == null || decisionTime == null) {
            evidence.setFinalOutcome("SHADOW_EVENT_KEY_MISSING");
            evidence.setReason("barOpenTime/decisionTime missing");
            evidenceRepository.save(evidence);
            return;
        }
        if (now.isBefore(decisionTime.plusHours(HOLD_HOURS))) return;

        List<MdKline> rows = klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        evidence.getSymbol(), "1m", KLINE_SOURCE,
                        decisionTime.minusMinutes(2), decisionTime.plusHours(73).plusMinutes(2));
        List<Strategy508TimeExitCandidateService.MinuteBar> minuteBars = rows.stream()
                .map(row -> new Strategy508TimeExitCandidateService.MinuteBar(
                        row.getOpenTime(), row.getOpenPrice(), row.getHighPrice(), row.getLowPrice(), row.getClosePrice()))
                .toList();
        Strategy508TimeExitCandidateService.EventResult result = candidateService.simulateSingle(
                new Strategy508TimeExitCandidateService.EntryIntent(barOpen, decisionTime, "RAW_BUY_4H"),
                minuteBars, now);
        if ("PENDING_24H".equals(result.outcome())) return;
        context.put("resolvedAtUtc", now.toString());
        context.put("outcome", result.outcome());
        context.put("finalized", result.finalized());
        context.put("rawCounterfactualOutcome", true);
        context.put("counterfactualOutcomeTracked", true);
        context.put("entryTime", result.entryTime() == null ? null : result.entryTime().toString());
        context.put("exitTime", result.exitTime() == null ? null : result.exitTime().toString());
        context.put("oneMinuteCoverage", result.coverage());
        put(context, "entryPrice", result.entryPrice());
        put(context, "exitPrice", result.exitPrice());
        put(context, "netPnlUsdt", result.pnlUsdt());
        put(context, "stressPnlUsdt", result.stressPnlUsdt());
        put(context, "entryAndExitFeesUsdt", result.feesUsdt());
        put(context, "netReturnPct", result.returnPct());
        put(context, "mfePct", result.mfePct());
        put(context, "maePct", result.maePct());
        put(context, "benchmark72hReturnPct", result.benchmark72hReturnPct());
        boolean exactMinuteLattice = result.finalized()
                && Double.compare(result.coverage(), 1.0d) == 0;
        boolean modeledFeeFieldsComplete = result.feesUsdt() != null
                && result.pnlUsdt() != null && result.returnPct() != null;
        context.put("minuteLatticeExact", exactMinuteLattice);
        context.put("feeEvidenceSemantics",
                "DETERMINISTIC_MODELED_FEE_AND_SLIPPAGE_NOT_EXCHANGE_FILL");
        context.put("modeledFeeFieldsComplete", modeledFeeFieldsComplete);
        context.put("feeCoverageComplete",
                result.finalized() && exactMinuteLattice && modeledFeeFieldsComplete);
        context.put("exitParityGap", !result.finalized() || !exactMinuteLattice);
        boolean executionHardBlocked = "HARD_BLOCKED".equals(
                context.path("executionGateOutcome").asText()) || hasHardBlockers(context);
        context.put("eligibleForLivePromotion",
                !executionHardBlocked && context.path("executableCohortEligible").asBoolean(false));
        evidence.setFinalOutcome(result.outcome());
        evidence.setReason(result.finalized()
                ? executionHardBlocked
                ? "SHADOW_COUNTERFACTUAL_OUTCOME_FINALIZED_HARD_BLOCKED"
                : "SHADOW_OUTCOME_FINALIZED_EXECUTABLE_COHORT"
                : executionHardBlocked
                ? "SHADOW_COUNTERFACTUAL_OUTCOME_NOT_FINALIZED_HARD_BLOCKED"
                : "SHADOW_OUTCOME_NOT_FINALIZED_EXECUTABLE_COHORT");
        evidence.setPolicyInputsJson(context.toString());
        evidence.setFeaturesSnapshotJson(context.toString());
        evidenceRepository.save(evidence);
    }

    private void resolveLive(RuntimeDecisionEvidence evidence, LocalDateTime now) {
        BtLiveSignal signal = liveSignalRepository.findById(evidence.getLiveSignalId()).orElse(null);
        if (signal == null) {
            markExecutionGap(evidence, "LIVE_SIGNAL_NOT_FOUND");
            return;
        }
        if (!POLICY_MODE.equals(signal.getFilterReason())) {
            markExecutionGap(evidence, "POLICY_POSITION_TAG_MISMATCH");
            return;
        }
        if (signal.getExitTime() != null) {
            finalizeClosedSignal(evidence, signal, null);
            return;
        }
        LocalDateTime entryTime = signal.getCreatedAt() != null ? signal.getCreatedAt() : evidence.getEvidenceTime();
        if (entryTime == null || now.isBefore(entryTime.plusHours(HOLD_HOURS))) return;

        SpotPositionCloseService.CloseResult close = closeService.closeAtMarket(signal.getId(), "TIME_EXIT_24H");
        if ("PARTIAL".equals(close.status())) {
            ObjectNode context = context(evidence);
            boolean priorPartialFeeCoverage = !context.has("partialExitFeeCoverageComplete")
                    || context.path("partialExitFeeCoverageComplete").asBoolean(false);
            put(context, "partialGrossPnlUsdt",
                    decimal(context, "partialGrossPnlUsdt").add(zero(close.grossPnlUsdt())));
            put(context, "partialExitFeeUsdt",
                    decimal(context, "partialExitFeeUsdt").add(zero(close.exitFeeUsdt())));
            context.put("partialExitFeeCoverageComplete",
                    priorPartialFeeCoverage && close.exitFeeUsdt() != null);
            context.put("partialFill", true);
            context.put("remainingQty", text(close.remainingQty()));
            context.put("replacementOcoId", close.replacementOcoId());
            evidence.setPolicyInputsJson(context.toString());
            evidence.setReason("TIME_EXIT_PARTIAL_FILL_REPROTECTED");
            evidenceRepository.save(evidence);
            sendCritical("24H exit partially filled and remainder was re-protected", signal.getId(), close.reason());
            return;
        }
        if (close.closedSuccessfully()) {
            BtLiveSignal closed = liveSignalRepository.findById(signal.getId()).orElse(signal);
            finalizeClosedSignal(evidence, closed, close);
            sendInfo("24H time exit completed", closed.getId(), closed.getRealizedPnl());
            return;
        }
        if ("OCO_ALREADY_FILLED".equals(close.status()) || "BUSY".equals(close.status())) return;
        evidence.setReason("TIME_EXIT_FAILED:" + close.reason());
        evidence.setTerminalBlocker("TIME_EXIT_EXECUTION_FAILED");
        evidenceRepository.save(evidence);
        sendCritical("24H time exit failed", signal.getId(), close.reason());
    }

    private void finalizeClosedSignal(RuntimeDecisionEvidence evidence,
                                      BtLiveSignal signal,
                                      SpotPositionCloseService.CloseResult close) {
        ObjectNode context = context(evidence);
        BigDecimal entryFee = decimalOrNull(context, "entryFeeUsdt");
        BigDecimal exitFee = close != null ? close.exitFeeUsdt() : ocoExitFeeUsdt(signal);
        BigDecimal partialGross = decimal(context, "partialGrossPnlUsdt");
        BigDecimal partialFee = decimal(context, "partialExitFeeUsdt");
        BigDecimal gross = partialGross.add(zero(signal.getRealizedPnl()));
        BigDecimal totalExitFee = partialFee.add(zero(exitFee));
        boolean partialFeeComplete = !context.has("partialExitFeeCoverageComplete")
                || context.path("partialExitFeeCoverageComplete").asBoolean(false);
        boolean reportedFeeFieldsComplete = entryFee != null && exitFee != null && partialFeeComplete;
        boolean exactFeeCoverage = reportedFeeFieldsComplete
                && Strategy508TimeExitPolicy.EXACT_LIVE_FILL_EVIDENCE_IMPLEMENTED;
        BigDecimal net = reportedFeeFieldsComplete
                ? gross.subtract(entryFee).subtract(totalExitFee) : null;
        String outcome = switch (signal.getExitReason() == null ? "" : signal.getExitReason()) {
            case "TP" -> "OCO_TP";
            case "SL" -> "OCO_SL";
            case "TIME_EXIT_24H" -> "TIME_EXIT_24H";
            default -> "TIME_EXIT_24H";
        };
        context.put("resolvedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        context.put("outcome", outcome);
        context.put("finalized", true);
        context.put("entryTime", signal.getCreatedAt() == null ? null : signal.getCreatedAt().toString());
        context.put("exitTime", signal.getExitTime() == null ? null : signal.getExitTime().toString());
        put(context, "exitPrice", signal.getExitPrice());
        put(context, "grossPnlUsdt", gross);
        put(context, "exitFeeUsdt", exitFee);
        put(context, "totalExitFeeUsdt", totalExitFee);
        put(context, "netPnlUsdt", net);
        context.put("reportedFeeFieldsComplete", reportedFeeFieldsComplete);
        context.put("fillAggregationComplete", false);
        context.put("feeSignPreserved", false);
        context.put("feeCoverageComplete", exactFeeCoverage);
        context.put("netPnlEvidenceStatus", exactFeeCoverage
                ? "IMMUTABLE_ALL_FILL_SIGNED_FEE_PROVENANCE"
                : reportedFeeFieldsComplete
                ? "NUMERIC_FEES_PRESENT_PROVENANCE_NOT_IMPLEMENTED"
                : "FEE_FIELDS_INCOMPLETE");
        context.put("exitParityGap", !exactFeeCoverage);
        evidence.setFinalOutcome(outcome);
        evidence.setReason(exactFeeCoverage
                ? "LIVE_OUTCOME_FINALIZED_EXACT_FEES"
                : reportedFeeFieldsComplete
                ? "LIVE_OUTCOME_FINALIZED_FEE_PROVENANCE_GAP"
                : "LIVE_OUTCOME_FINALIZED_FEE_GAP");
        evidence.setPolicyInputsJson(context.toString());
        evidence.setFeaturesSnapshotJson(context.toString());
        evidenceRepository.save(evidence);
    }

    private BigDecimal ocoExitFeeUsdt(BtLiveSignal signal) {
        if (signal.getOcoOrderListId() == null) return null;
        try {
            JsonNode algo = okxTradingService.getAlgoOrder(signal.getSymbol(), signal.getOcoOrderListId());
            String childOrderId = algo.path("ordIdList").path(0).asText("");
            if (childOrderId.isBlank()) return null;
            JsonNode child = okxTradingService.querySpotOrderDetail(signal.getSymbol(), childOrderId);
            String feeCurrency = child.path("fillFeeCcy").asText("");
            BigDecimal fee = new BigDecimal(child.path("fillFee").asText("0")).abs();
            if ("USDT".equalsIgnoreCase(feeCurrency)) return fee;
            if ("BTC".equalsIgnoreCase(feeCurrency) && signal.getExitPrice() != null) {
                return fee.multiply(signal.getExitPrice()).setScale(8, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            log.warn("[508TimeExit] OCO fee lookup failed position={} error={}", signal.getId(), e.getMessage());
        }
        return null;
    }

    private void markExecutionGap(RuntimeDecisionEvidence evidence, String reason) {
        ObjectNode context = context(evidence);
        context.put("exitParityGap", true);
        context.put("executionGapReason", reason);
        evidence.setFinalOutcome(reason);
        evidence.setReason(reason);
        evidence.setTerminalBlocker(reason);
        evidence.setPolicyInputsJson(context.toString());
        evidence.setFeaturesSnapshotJson(context.toString());
        evidenceRepository.save(evidence);
    }

    private ObjectNode context(RuntimeDecisionEvidence evidence) {
        for (String json : List.of(nullToEmpty(evidence.getPolicyInputsJson()),
                nullToEmpty(evidence.getFeaturesSnapshotJson()))) {
            if (json.isBlank()) continue;
            try {
                JsonNode parsed = objectMapper.readTree(json);
                if (parsed instanceof ObjectNode objectNode) return objectNode.deepCopy();
            } catch (Exception ignored) {
            }
        }
        return objectMapper.createObjectNode();
    }

    private BigDecimal decimal(ObjectNode node, String field) {
        BigDecimal value = decimalOrNull(node, field);
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean hasHardBlockers(ObjectNode context) {
        JsonNode blockers = context == null ? null : context.path("hardBlockers");
        return blockers != null && blockers.isArray() && !blockers.isEmpty();
    }

    private BigDecimal decimalOrNull(ObjectNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private void put(ObjectNode node, String field, BigDecimal value) {
        if (value == null) node.putNull(field);
        else node.put(field, value.setScale(8, RoundingMode.HALF_UP).toPlainString());
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void sendCritical(String message, Long positionId, String detail) {
        try {
            telegramService.sendAlert(message + " policy=" + POLICY_MODE + " position=" + positionId
                    + " detail=" + detail, false, "Strategy508TimeExit", "CRITICAL");
        } catch (Exception e) {
            log.error("[508TimeExit] critical notification failed: {}", e.getMessage());
        }
    }

    private void sendInfo(String message, Long positionId, BigDecimal pnl) {
        try {
            telegramService.sendAlert(message + " policy=" + POLICY_MODE + " position=" + positionId
                    + " grossPnlUsdt=" + text(pnl), false, "Strategy508TimeExit", "INFO");
        } catch (Exception e) {
            log.warn("[508TimeExit] exit confirmation notification failed: {}", e.getMessage());
        }
    }
}
