package com.agora.service.trading;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TradeQualityEngine {

    public static final String POLICY_NOTIFY_ONLY = "NOTIFY_ONLY";
    public static final String POLICY_DRY_RUN_ONLY = "DRY_RUN_ONLY";

    public Map<String, Object> applyV0(Map<String, Object> context, String terminalBlocker) {
        return applyV0Score(context, terminalBlocker);
    }

    public static Map<String, Object> applyV0Score(Map<String, Object> context, String terminalBlocker) {
        Map<String, Object> target = context == null ? new LinkedHashMap<>() : context;
        Score score = scoreV0(target, terminalBlocker);
        target.put("qualityScore", score.qualityScore);
        target.put("tqsBand", score.band);
        target.put("recommendedAction", score.recommendedAction);
        target.put("scoreBreakdown", score.breakdown);
        target.put("tqsPolicy", score.policy);
        target.putIfAbsent("policyMode", score.policy);
        target.putIfAbsent("selectedAction", score.recommendedAction);
        target.put("tqs", Map.of(
                "version", "v0",
                "status", "DRY_RUN_SCORE",
                "qualityScore", score.qualityScore,
                "band", score.band,
                "recommendedAction", score.recommendedAction,
                "policy", score.policy,
                "scoreBreakdown", score.breakdown
        ));
        return target;
    }

    public static Map<String, Object> scoreJsonV0(Map<String, Object> context, String terminalBlocker) {
        Score score = scoreV0(context == null ? Map.of() : context, terminalBlocker);
        return Map.of(
                "version", "v0",
                "status", "DRY_RUN_SCORE",
                "qualityScore", score.qualityScore,
                "tqsBand", score.band,
                "recommendedAction", score.recommendedAction,
                "policy", score.policy,
                "scoreBreakdown", score.breakdown
        );
    }

    private static Score scoreV0(Map<String, Object> context, String terminalBlocker) {
        List<Map<String, Object>> breakdown = new ArrayList<>();
        String blocker = terminalBlocker == null ? "" : terminalBlocker;
        if (contains(blocker, "DataFreshnessGuard")
                || contains(text(context, "freshnessState", "dataFreshnessState"), "DATA_FRESHNESS")) {
            breakdown.add(part("DataFreshness hard fail", -50, "terminal safety block"));
            return new Score(0, "BLOCK", "BLOCK", POLICY_DRY_RUN_ONLY, breakdown);
        }
        if (contains(blocker, "DuplicateBar")) {
            breakdown.add(part("DuplicateBar terminal block", -50, "same strategy/symbol/interval/bar already processed"));
            return new Score(0, "BLOCK", "BLOCK", POLICY_DRY_RUN_ONLY, breakdown);
        }
        if (contains(blocker, "ExposureOptimizer") || contains(text(context, "riskGateResult"), "EXPOSURE_ABOVE_CAP")) {
            breakdown.add(part("Exposure above cap", -50, "exposure safety block"));
            return new Score(0, "BLOCK", "BLOCK", POLICY_DRY_RUN_ONLY, breakdown);
        }

        int score = 50;
        breakdown.add(part("base", 50, "base dry-run candidate score"));

        String evReason = text(context, "ev_reason");
        if ("pass".equalsIgnoreCase(evReason) || bool(context, "candidateContinuedToEv")) {
            score += 10;
            breakdown.add(part("ExpectedValueGate pass", 10, "candidate continued to EV"));
        } else {
            score -= 10;
            breakdown.add(part("missing EV", -10, "EV pass evidence missing"));
        }

        if (bool(context, "strategyAllowlisted") || bool(context, "strategyAllowlistedForTinyLive")) {
            score += 5;
            breakdown.add(part("strategy allowlisted", 5, "strategy is in controlled rollout allowlist"));
        }

        if (bool(context, "ocoCapable") || bool(context, "ocoPlanCreated")) {
            score += 5;
            breakdown.add(part("OCO-capable path", 5, "TP/SL/OCO plan evidence present"));
        }

        if (bool(context, "fearGreedWarning") || "WARN_ONLY".equalsIgnoreCase(text(context, "fearGreedFilterState", "fearGreedFilterMode"))) {
            score -= 10;
            breakdown.add(part("FearGreed warning", -10, "warning-only penalty, not terminal blocker"));
        }

        if (!bool(context, "mlFeatureAvailable") && !hasAny(context, "score", "nnOutput", "qualityScore")) {
            score -= 5;
            breakdown.add(part("missing ML/TQS feature", -5, "ML/TQS feature evidence incomplete"));
        }

        int bounded = Math.max(0, Math.min(100, score));
        String band = bandFor(bounded);
        return new Score(bounded, band, band, POLICY_NOTIFY_ONLY, breakdown);
    }

    private static String bandFor(int score) {
        if (score <= 24) return "BLOCK";
        if (score <= 49) return "PROBE_DRY_RUN";
        if (score <= 69) return "SMALL_DRY_RUN";
        return "CAPPED_SMALL_DRY_RUN";
    }

    private static Map<String, Object> part(String name, int delta, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("delta", delta);
        row.put("reason", reason);
        return row;
    }

    private static boolean bool(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value instanceof Boolean b) return b;
        if (value == null) return false;
        String s = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static String text(Map<String, Object> context, String... keys) {
        for (String key : keys) {
            Object value = context.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private static boolean hasAny(Map<String, Object> context, String... keys) {
        for (String key : keys) {
            if (context.containsKey(key) && context.get(key) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String needle) {
        return value != null && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private record Score(
            int qualityScore,
            String band,
            String recommendedAction,
            String policy,
            List<Map<String, Object>> breakdown
    ) {
    }
}
