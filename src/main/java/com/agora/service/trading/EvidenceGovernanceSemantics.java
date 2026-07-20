package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;

/**
 * Shared read-model semantics for deciding whether canonical evidence represents an executable BUY intent.
 */
public final class EvidenceGovernanceSemantics {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EvidenceGovernanceSemantics() {
    }

    public static boolean hasExecutableBuyEntryIntent(Map<String, Object> row) {
        if (row == null || isStrategyNoEntryIntent(row)) return false;
        String selected = upper(row.get("selected_action"));
        String decision = upper(row.get("decision"));
        String source = upper(row.get("signal_source"));
        String side = upper(row.get("side"));
        String lane = String.join(" ", selected, decision, source, side);
        if (containsAny(lane, "SELL", "SHORT", "EXIT")) return false;
        boolean buyLane = selected.contains("BUY") || selected.contains("ENTRY")
                || decision.contains("BUY") || decision.contains("ENTRY")
                || source.contains("SIGNAL_BUY") || source.contains("ENTRY_SKIP");
        return buyLane && hasExplicitIntent(row);
    }

    public static boolean isStrategyNoEntryIntent(Map<String, Object> row) {
        if (row == null || bool(row.get("order_sent"))) return false;
        String semantics = semanticText(row);
        if (containsAny(semantics,
                "LOCAL_TRADINGVIEW_NO_BUY", "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE",
                "NO_CURRENT_BUY_CANDIDATE", "NO_BUY_ENTRY_INTENT", "STRATEGY_NO_ENTRY_INTENT")) {
            return true;
        }
        String selected = upper(row.get("selected_action"));
        String decision = upper(row.get("decision"));
        return (selected.contains("HOLD") || selected.contains("EVALUATED_ONLY") || decision.contains("HOLD"))
                && !selected.contains("BUY") && !decision.contains("BUY") && !hasExplicitIntent(row);
    }

    public static boolean hasExplicitIntent(Map<String, Object> row) {
        return row != null && (bool(row.get("intent_created"))
                || jsonBoolean(row.get("policy_inputs_json"), "intentCreated", "intent_created")
                || jsonBoolean(row.get("execution_preview_json"), "intentCreated", "intent_created")
                || jsonBoolean(row.get("features_snapshot_json"), "intentCreated", "intent_created"));
    }

    private static String semanticText(Map<String, Object> row) {
        return String.join(" ",
                upper(row.get("terminal_blocker")), upper(row.get("blocker_reason")),
                upper(row.get("suppression_reason")), upper(row.get("selected_action")),
                upper(row.get("decision")), upper(row.get("signal_source")), upper(row.get("final_outcome")),
                upper(row.get("policy_inputs_json")), upper(row.get("execution_preview_json")),
                upper(row.get("features_snapshot_json")));
    }

    private static boolean jsonBoolean(Object rawJson, String... keys) {
        if (rawJson == null || rawJson.toString().isBlank()) return false;
        try {
            JsonNode node = rawJson instanceof JsonNode jsonNode ? jsonNode : JSON.readTree(rawJson.toString());
            for (String key : keys) {
                JsonNode value = node.path(key);
                if ((value.isBoolean() && value.asBoolean())
                        || (value.isNumber() && value.asInt() != 0)
                        || (value.isTextual() && Boolean.parseBoolean(value.asText()))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) return true;
        }
        return false;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value instanceof Number numberValue) return numberValue.intValue() != 0;
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String upper(Object value) {
        return value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
    }
}
