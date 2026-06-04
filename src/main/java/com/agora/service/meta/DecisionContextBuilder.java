package com.agora.service.meta;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V2 schema builder for {@code bt_decision_audit.context_json}.
 *
 * <p>Purpose: the legacy context_json on 550+ prod rows stored only sparse
 * fields like {@code {fg: 23, whale: 0.231, signal: HOLD}}. That made
 * retrospection, Claude explainPrediction, and future ML training useless.
 * This builder produces a structured record that captures the full decision
 * trace at the moment the signal was evaluated / filtered / executed.
 *
 * <h3>Schema (emitted JSON shape)</h3>
 * <pre>
 * {
 *   "version": 2,
 *   "indicators":   {"adx_1h": 28.5, "rsi": 62.1, "atr_pct": 0.008, ...},
 *   "sentiment":    {"fg": 23, "whale_buy_ratio": 0.231, "orderbook_imbalance": 0.12, ...},
 *   "regime":       {"gemini_style": "TREND", "gemini_regime": "TRENDING_UP", "hint_version": 42},
 *   "filters":      [{"name": "LongAiFilter", "outcome": "PASS",
 *                     "rules": [{"id": "R1", "pass": true, "detail": "fg=23 >= 10"}, ...]}],
 *   "strategy":     {"id": 315, "type": "SOP_MTF_ADX", "score": 0.72,
 *                    "nn_output": 0.68, "allow_short": true, "kline_source": "okx"},
 *   "execution":    {"decision": "BUY", "size_usdt": 50, "sl_pct": 0.0087, "tp_pct": 0.0284},
 *   "data_quality": {"anomalous": true, "reasons": ["oscillation_3h"]}
 * }
 * </pre>
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>All numeric values kept as {@code Number} (Integer/Long/Double/BigDecimal) —
 *       Jackson handles them;  do not stringify in the builder.</li>
 *   <li>Null values are skipped (not emitted as {@code "foo": null}) for JSON compactness.</li>
 *   <li>Empty categories are omitted entirely (no empty {@code "indicators": {}}).</li>
 *   <li>Order is deterministic: version first, then categories in declaration order.</li>
 *   <li>Never throw — if a caller passes bad data, skip and continue. Audit write
 *       must never block the trading main flow.</li>
 * </ul>
 *
 * <h3>Backward compat</h3>
 * V1 rows (without {@code version} field) are treated as schema v1 by readers.
 * Readers detect via {@code JSON_EXTRACT(context_json, '$.version') = 2} for v2.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Map<String, Object> ctx = DecisionContextBuilder.v2()
 *         .indicator("adx_1h", adx1h)
 *         .indicator("rsi", rsiValue)
 *         .sentiment("fg", sent.fearGreedValue)
 *         .sentiment("whale_buy_ratio", sent.whaleBuyRatio)
 *         .regime("gemini_style", hint.getStyleHint())
 *         .strategy(strategy.getId(), strategy.getStrategyType(), score, nn,
 *                   strategy.getAllowShort(), strategy.getKlineSource())
 *         .execution("BUY", sizeUsdt, slPct, tpPct)
 *         .build();
 * auditWriter.logSignalEval(..., ctx);
 * }</pre>
 */
@Getter
public final class DecisionContextBuilder {

    /** Current schema version. Bump on breaking changes. */
    public static final int SCHEMA_VERSION = 2;

    private final Map<String, Object> indicators = new LinkedHashMap<>();
    private final Map<String, Object> sentiment = new LinkedHashMap<>();
    private final Map<String, Object> regime = new LinkedHashMap<>();
    private final List<Map<String, Object>> filters = new ArrayList<>();
    private final Map<String, Object> strategy = new LinkedHashMap<>();
    private final Map<String, Object> execution = new LinkedHashMap<>();
    private final Map<String, Object> dataQuality = new LinkedHashMap<>();
    /** Extra free-form fields for one-off audit events (e.g., override applied). */
    private final Map<String, Object> extras = new LinkedHashMap<>();

    private DecisionContextBuilder() {}

    public static DecisionContextBuilder v2() {
        return new DecisionContextBuilder();
    }

    // ────────── Indicators ──────────

    public DecisionContextBuilder indicator(String name, Number value) {
        putIfNotNull(indicators, name, value);
        return this;
    }

    /** Batch add — null batch or null values silently skipped. */
    public DecisionContextBuilder indicators(Map<String, ? extends Number> batch) {
        if (batch == null) return this;
        batch.forEach((k, v) -> putIfNotNull(indicators, k, v));
        return this;
    }

    // ────────── Sentiment ──────────

    public DecisionContextBuilder sentiment(String name, Number value) {
        putIfNotNull(sentiment, name, value);
        return this;
    }

    public DecisionContextBuilder sentimentBatch(Integer fg, Number whaleBuyRatio,
                                                 Number orderbookImbalance,
                                                 Number fundingRate, Number fgDelta24h) {
        putIfNotNull(sentiment, "fg", fg);
        putIfNotNull(sentiment, "whale_buy_ratio", whaleBuyRatio);
        putIfNotNull(sentiment, "orderbook_imbalance", orderbookImbalance);
        putIfNotNull(sentiment, "funding_rate", fundingRate);
        putIfNotNull(sentiment, "fg_delta_24h", fgDelta24h);
        return this;
    }

    // ────────── Regime ──────────

    public DecisionContextBuilder regime(String key, String value) {
        putIfNotNull(regime, key, value);
        return this;
    }

    public DecisionContextBuilder regime(String geminiStyle, String geminiRegime, Long hintVersion) {
        putIfNotNull(regime, "gemini_style", geminiStyle);
        putIfNotNull(regime, "gemini_regime", geminiRegime);
        putIfNotNull(regime, "hint_version", hintVersion);
        return this;
    }

    // ────────── Filter chain ──────────

    /** A rule outcome within a filter (e.g., "fg_gate passed because fg=23 >= 10"). */
    public record RuleOutcome(String id, boolean pass, String detail) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("pass", pass);
            if (detail != null) m.put("detail", detail);
            return m;
        }
    }

    public DecisionContextBuilder filter(String name, String outcome, List<RuleOutcome> rules) {
        if (name == null) return this;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        if (outcome != null) f.put("outcome", outcome);
        if (rules != null && !rules.isEmpty()) {
            List<Map<String, Object>> ruleMaps = new ArrayList<>(rules.size());
            for (RuleOutcome r : rules) {
                if (r != null) ruleMaps.add(r.toMap());
            }
            if (!ruleMaps.isEmpty()) f.put("rules", ruleMaps);
        }
        filters.add(f);
        return this;
    }

    /** Shortcut for the common "filter blocked" case with a single reason string. */
    public DecisionContextBuilder filterBlock(String name, String reason) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("outcome", "BLOCKED");
        if (reason != null) f.put("reason", reason);
        filters.add(f);
        return this;
    }

    // ────────── Strategy ──────────

    public DecisionContextBuilder strategy(Long id, String type, Number score, Number nnOutput,
                                           Boolean allowShort, String klineSource) {
        putIfNotNull(strategy, "id", id);
        putIfNotNull(strategy, "type", type);
        putIfNotNull(strategy, "score", score);
        putIfNotNull(strategy, "nn_output", nnOutput);
        putIfNotNull(strategy, "allow_short", allowShort);
        putIfNotNull(strategy, "kline_source", klineSource);
        return this;
    }

    public DecisionContextBuilder strategyField(String key, Object value) {
        putIfNotNull(strategy, key, value);
        return this;
    }

    // ────────── Execution ──────────

    public DecisionContextBuilder execution(String decision, Number sizeUsdt,
                                            Number slPct, Number tpPct) {
        putIfNotNull(execution, "decision", decision);
        putIfNotNull(execution, "size_usdt", sizeUsdt);
        putIfNotNull(execution, "sl_pct", slPct);
        putIfNotNull(execution, "tp_pct", tpPct);
        return this;
    }

    public DecisionContextBuilder executionField(String key, Object value) {
        putIfNotNull(execution, key, value);
        return this;
    }

    // ────────── Data quality (compat with thirsty-wing DataQualityMonitor) ──────────

    public DecisionContextBuilder dataQuality(Boolean anomalous, List<String> reasons) {
        putIfNotNull(dataQuality, "anomalous", anomalous);
        if (reasons != null && !reasons.isEmpty()) {
            dataQuality.put("reasons", new ArrayList<>(reasons));
        }
        return this;
    }

    // ────────── Extras (free-form) ──────────

    /**
     * Free-form entry for one-off fields that don't fit the schema.
     * Used e.g. for override_id, rule_name, exit_reason on EXIT events.
     */
    public DecisionContextBuilder extra(String key, Object value) {
        putIfNotNull(extras, key, value);
        return this;
    }

    // ────────── Build ──────────

    public Map<String, Object> build() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("version", SCHEMA_VERSION);
        ctx.put("built_at", Instant.now().toString());  // UTC ISO 8601
        if (!indicators.isEmpty()) ctx.put("indicators", indicators);
        if (!sentiment.isEmpty()) ctx.put("sentiment", sentiment);
        if (!regime.isEmpty()) ctx.put("regime", regime);
        if (!filters.isEmpty()) ctx.put("filters", filters);
        if (!strategy.isEmpty()) ctx.put("strategy", strategy);
        if (!execution.isEmpty()) ctx.put("execution", execution);
        if (!dataQuality.isEmpty()) ctx.put("data_quality", dataQuality);
        if (!extras.isEmpty()) ctx.put("extras", extras);
        return ctx;
    }

    // ────────── Helpers ──────────

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value == null) return;
        // Reject obvious JSON poison: we want primitives / String / BigDecimal only in leaves.
        // Callers mis-passing arrays or Kline lists here is what the V1 schema warned against.
        if (value instanceof Number || value instanceof String || value instanceof Boolean
                || value instanceof BigDecimal) {
            target.put(key, value);
        } else {
            // Allow nested maps (used for structured sub-objects)
            target.put(key, value);
        }
    }

    // ────────── Convenience for v1 compat detection ──────────

    /**
     * Null-safe check on a parsed context JSON tree.
     * @return true if the node represents a v2 schema.
     */
    public static boolean isV2(com.fasterxml.jackson.databind.JsonNode root) {
        return root != null && root.has("version") && root.get("version").asInt(1) == SCHEMA_VERSION;
    }
}
