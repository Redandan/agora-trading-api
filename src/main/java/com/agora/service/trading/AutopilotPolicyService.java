package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AutopilotPolicyService {

    private final ObjectMapper objectMapper;

    public Decision decide(BtDecisionAudit audit,
                           JsonNode context,
                           String terminalBlocker,
                           String freshnessState,
                           String tqsJson,
                           String evJson,
                           String riskJson) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("version", "v0");
        inputs.put("autonomousExecutionEnabled", false);
        inputs.put("probePositionExecutorEnabled", false);
        inputs.put("eventType", audit == null ? null : audit.getEventType());
        inputs.put("outcome", audit == null ? null : audit.getOutcome());
        inputs.put("blocker", audit == null ? null : audit.getBlocker());
        inputs.put("terminalBlocker", terminalBlocker);
        inputs.put("freshnessState", freshnessState);
        inputs.put("tqs", jsonToMap(tqsJson));
        inputs.put("ev", jsonToMap(evJson));
        inputs.put("risk", jsonToMap(riskJson));
        inputs.put("duplicateBar", contains(terminalBlocker, "DuplicateBar")
                || contains(audit == null ? null : audit.getBlocker(), "DuplicateBar"));
        inputs.put("dataFreshnessHardFail", contains(terminalBlocker, "DataFreshnessGuard")
                || contains(freshnessState, "DATA_FRESHNESS"));
        inputs.put("exposureCapHit", contains(terminalBlocker, "ExposureOptimizer")
                || contains(riskJson, "EXPOSURE_ABOVE_CAP"));
        inputs.put("dailyBreaker", contains(terminalBlocker, "DailyLossGuard")
                || contains(audit == null ? null : audit.getBlocker(), "DailyLossGuard"));
        inputs.put("eventRiskBlocked", contains(terminalBlocker, "EventRiskControl")
                || contains(audit == null ? null : audit.getBlocker(), "EventRiskControl"));
        inputs.put("ocoCapable", bool(context, "ocoCapable") || bool(context, "ocoPlanCreated"));
        inputs.put("ocoHealthy", !contains(riskJson, "OCO_SYNC_ERROR") && !contains(riskJson, "OCO_MISSING"));
        inputs.put("strategyNotifyOnly", bool(context, "notify_only")
                || "SHADOW".equalsIgnoreCase(text(context, "executionMode")));
        inputs.put("strategyAllowlisted", bool(context, "strategyAllowlisted")
                || bool(context, "strategyAllowlistedForTinyLive"));
        inputs.put("orderSent", bool(context, "orderSent"));

        int qualityScore = intFromTqs(tqsJson, "qualityScore");
        String tqsBand = textFromJson(tqsJson, "tqsBand", "band");
        String evReason = textFromJson(evJson, "ev_reason");
        boolean evPass = "pass".equalsIgnoreCase(evReason) || bool(context, "candidateContinuedToEv");
        inputs.put("qualityScore", qualityScore);
        inputs.put("tqsBand", tqsBand);
        inputs.put("evPass", evPass);

        if (Boolean.TRUE.equals(inputs.get("dailyBreaker"))) {
            return decision("HALT_TRADING", "Daily breaker is active; dry-run policy halts entries.", inputs);
        }
        if (Boolean.TRUE.equals(inputs.get("dataFreshnessHardFail"))) {
            return decision("BLOCK", "DataFreshness hard fail blocks candidate.", inputs);
        }
        if (Boolean.TRUE.equals(inputs.get("duplicateBar"))) {
            return decision("BLOCK", "DuplicateBar remains a terminal duplicate-candidate block.", inputs);
        }
        if (Boolean.TRUE.equals(inputs.get("exposureCapHit"))) {
            return decision("BLOCK", "Exposure cap/risk gate blocks new entry.", inputs);
        }
        if (Boolean.TRUE.equals(inputs.get("eventRiskBlocked"))) {
            return decision("ALLOW_RISK_REDUCING_ONLY", "Event risk blocks additive entries; only risk-reducing actions allowed.", inputs);
        }
        if (!Boolean.TRUE.equals(inputs.get("ocoCapable")) || !Boolean.TRUE.equals(inputs.get("ocoHealthy"))) {
            return decision("READ_ONLY", "OCO-capable healthy protection path is not proven.", inputs);
        }
        if (!evPass) {
            return decision("READ_ONLY", "ExpectedValueGate pass evidence is missing.", inputs);
        }
        if (!Boolean.TRUE.equals(inputs.get("strategyAllowlisted"))
                && !Boolean.TRUE.equals(inputs.get("strategyNotifyOnly"))) {
            return decision("READ_ONLY", "Strategy is neither notifyOnly nor rollout allowlisted.", inputs);
        }
        if ("CAPPED_SMALL_DRY_RUN".equalsIgnoreCase(tqsBand)) {
            return decision("ALLOW_SMALL_ENTRY_DRY_RUN", "TQS permits capped small dry-run only; execution remains disabled.", inputs);
        }
        if ("SMALL_DRY_RUN".equalsIgnoreCase(tqsBand)) {
            return decision("ALLOW_SMALL_ENTRY_DRY_RUN", "TQS permits small dry-run only; execution remains disabled.", inputs);
        }
        if ("PROBE_DRY_RUN".equalsIgnoreCase(tqsBand)) {
            return decision("ALLOW_PROBE_ENTRY_DRY_RUN", "TQS permits probe dry-run only; execution remains disabled.", inputs);
        }
        return decision("NOTIFY_ONLY", "Candidate remains notify-only dry-run.", inputs);
    }

    public Decision decidePreview(PreviewInput input) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("version", "v0");
        inputs.put("preview", true);
        inputs.put("symbol", input.symbol());
        inputs.put("strategyId", input.strategyId());
        inputs.put("side", input.side());
        inputs.put("autonomousExecutionEnabled", input.autonomousExecutionEnabled());
        inputs.put("probePositionExecutorEnabled", input.probePositionExecutorEnabled());
        inputs.put("tqs", Map.of(
                "status", "PREVIEW",
                "tqsBand", input.tqsBand(),
                "qualityScore", input.qualityScore(),
                "recommendedAction", input.recommendedAction() == null ? "" : input.recommendedAction()));
        inputs.put("ev", Map.of(
                "status", "PREVIEW",
                "ev_reason", input.evPass() ? "pass" : "not_pass",
                "expected_r", input.expectedR() == null ? 0.0 : input.expectedR()));
        inputs.put("risk", Map.of("status", "PREVIEW"));
        inputs.put("duplicateBar", input.duplicateBar());
        inputs.put("dataFreshnessHardFail", input.dataFreshnessHardFail());
        inputs.put("exposureCapHit", input.exposureCapHit());
        inputs.put("dailyBreaker", input.dailyBreaker());
        inputs.put("eventRiskBlocked", input.eventRiskBlocked());
        inputs.put("ocoCapable", input.ocoCapable());
        inputs.put("ocoHealthy", input.ocoHealthy());
        inputs.put("strategyNotifyOnly", input.strategyNotifyOnly());
        inputs.put("strategyAllowlisted", input.strategyAllowlisted());
        inputs.put("orderSent", false);
        inputs.put("qualityScore", input.qualityScore());
        inputs.put("tqsBand", input.tqsBand());
        inputs.put("evPass", input.evPass());

        if (input.dailyBreaker()) {
            return decision("HALT_TRADING", "Daily breaker is active; dry-run policy halts entries.", inputs);
        }
        if (input.dataFreshnessHardFail()) {
            return decision("BLOCK", "DataFreshness hard fail blocks candidate.", inputs);
        }
        if (input.duplicateBar()) {
            return decision("BLOCK", "DuplicateBar remains a terminal duplicate-candidate block.", inputs);
        }
        if (input.eventRiskBlocked()) {
            return decision("ALLOW_RISK_REDUCING_ONLY", "Event risk blocks additive entries; only risk-reducing actions allowed.", inputs);
        }
        if (input.exposureCapHit()) {
            return decision("ALLOW_RISK_REDUCING_ONLY", "Exposure cap blocks additive entries; only risk-reducing actions allowed.", inputs);
        }
        if (!input.ocoHealthy()) {
            return decision("ALLOW_RISK_REDUCING_ONLY", "OCO health is not clean; only risk-reducing actions allowed.", inputs);
        }
        if (!input.ocoCapable()) {
            return decision("READ_ONLY", "OCO-capable protection path is not proven.", inputs);
        }
        if (!input.evPass()) {
            return decision("READ_ONLY", "ExpectedValueGate pass evidence is missing.", inputs);
        }
        if (!input.strategyAllowlisted() && !input.strategyNotifyOnly()) {
            return decision("READ_ONLY", "Strategy is neither notifyOnly nor rollout allowlisted.", inputs);
        }
        String tqsBand = input.tqsBand() == null ? "" : input.tqsBand();
        if ("CAPPED_SMALL_DRY_RUN".equalsIgnoreCase(tqsBand)
                || "SMALL_DRY_RUN".equalsIgnoreCase(tqsBand)) {
            return decision("ALLOW_SMALL_ENTRY_DRY_RUN", "TQS permits small dry-run only; execution remains disabled.", inputs);
        }
        if ("PROBE_DRY_RUN".equalsIgnoreCase(tqsBand)) {
            return decision("ALLOW_PROBE_ENTRY_DRY_RUN", "TQS permits probe dry-run only; execution remains disabled.", inputs);
        }
        return decision("NOTIFY_ONLY", "Candidate remains notify-only dry-run.", inputs);
    }

    private Decision decision(String mode, String reason, Map<String, Object> inputs) {
        return new Decision(mode, reason, toJson(inputs));
    }

    private String toJson(Map<String, Object> inputs) {
        try {
            return objectMapper.writeValueAsString(inputs);
        } catch (Exception e) {
            return "{\"version\":\"v0\",\"error\":\"policy inputs serialization failed\"}";
        }
    }

    private Map<String, Object> jsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of("status", "NOT_CAPTURED");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return map;
        } catch (Exception e) {
            return Map.of("status", "UNPARSEABLE");
        }
    }

    private int intFromTqs(String json, String key) {
        Object value = jsonToMap(json).get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private String textFromJson(String json, String... keys) {
        Map<String, Object> map = jsonToMap(json);
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private boolean bool(JsonNode node, String key) {
        if (node == null) return false;
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return false;
        if (value.isBoolean()) return value.asBoolean();
        String s = value.asText("").trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private String text(JsonNode node, String key) {
        if (node == null) return "";
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private boolean contains(String value, String needle) {
        return value != null && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    public record Decision(String policyMode, String policyReason, String policyInputsJson) {
    }

    public record PreviewInput(String symbol,
                               Long strategyId,
                               String side,
                               String tqsBand,
                               Integer qualityScore,
                               String recommendedAction,
                               Boolean evPass,
                               Double expectedR,
                               Boolean ocoCapable,
                               Boolean ocoHealthy,
                               Boolean exposureCapHit,
                               Boolean dailyBreaker,
                               Boolean duplicateBar,
                               Boolean dataFreshnessHardFail,
                               Boolean eventRiskBlocked,
                               Boolean strategyNotifyOnly,
                               Boolean strategyAllowlisted,
                               Boolean autonomousExecutionEnabled,
                               Boolean probePositionExecutorEnabled) {

        public PreviewInput {
            symbol = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase(Locale.ROOT);
            strategyId = strategyId == null ? 574L : strategyId;
            side = side == null || side.isBlank() ? "LONG" : side.trim().toUpperCase(Locale.ROOT);
            tqsBand = tqsBand == null ? "" : tqsBand.trim().toUpperCase(Locale.ROOT);
            qualityScore = qualityScore == null ? 0 : qualityScore;
            recommendedAction = recommendedAction == null ? "" : recommendedAction.trim().toUpperCase(Locale.ROOT);
            evPass = Boolean.TRUE.equals(evPass);
            ocoCapable = Boolean.TRUE.equals(ocoCapable);
            ocoHealthy = Boolean.TRUE.equals(ocoHealthy);
            exposureCapHit = Boolean.TRUE.equals(exposureCapHit);
            dailyBreaker = Boolean.TRUE.equals(dailyBreaker);
            duplicateBar = Boolean.TRUE.equals(duplicateBar);
            dataFreshnessHardFail = Boolean.TRUE.equals(dataFreshnessHardFail);
            eventRiskBlocked = Boolean.TRUE.equals(eventRiskBlocked);
            strategyNotifyOnly = Boolean.TRUE.equals(strategyNotifyOnly);
            strategyAllowlisted = Boolean.TRUE.equals(strategyAllowlisted);
            autonomousExecutionEnabled = Boolean.TRUE.equals(autonomousExecutionEnabled);
            probePositionExecutorEnabled = Boolean.TRUE.equals(probePositionExecutorEnabled);
        }
    }
}
