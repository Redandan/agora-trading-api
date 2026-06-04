package com.agora.service.ml;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight rule-based market regime classifier (V069).
 *
 * <h2>Why rule-based, not ML?</h2>
 * Training a separate ML regime model requires labelled regime data (which we
 * don't have) and hundreds of samples (which we barely have for signal_scorer).
 * A well-calibrated rule-based classifier with 3 orthogonal signals is more
 * reliable at small data scale and remains fully explainable.
 *
 * <h2>Three-signal majority vote</h2>
 * Each signal returns +1 (BULL), -1 (BEAR), or 0 (NEUTRAL):
 * <ol>
 *   <li><b>Fear &amp; Greed</b> — market-wide sentiment; queried from
 *       {@code market_indicator_history} and cached per symbol for
 *       {@value #FG_CACHE_TTL_MS} ms to avoid per-inference DB round-trips.</li>
 *   <li><b>dist_from_ema200_pct</b> — price position relative to the 200-bar
 *       EMA; extracted from the {@code EntryFeatureSnapshot} already computed
 *       by the caller (zero extra computation).</li>
 *   <li><b>momentum_50bar_pct</b> — 50-bar price change; same source.</li>
 * </ol>
 * Score = sum of three votes.  Score ≥ 2 → BULL, ≤ −2 → BEAR, else SIDEWAYS.
 *
 * <h2>Thresholds (v1)</h2>
 * <pre>
 *   F&G      : ≥ 55 → +1 (BULL)  ;  ≤ 35 → -1 (BEAR)
 *   EMA200   : > +3% → +1        ;  < -3% → -1
 *   Momentum : > +3% → +1        ;  < -3% → -1
 * </pre>
 * These are deliberately wide to avoid flickering between BULL/BEAR on noisy bars.
 * Adjust via {@link #FG_BULL_THRESHOLD}, {@link #FG_BEAR_THRESHOLD}, etc. as more
 * regime data accumulates.
 *
 * <h2>Thread safety</h2>
 * Called from the {@code @Async} ML-shadow thread. The F&G cache is a
 * {@link ConcurrentHashMap} with atomic compare-and-swap; safe for concurrent
 * reads from multiple async threads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegimeClassifier {

    // ── Thresholds ─────────────────────────────────────────────────────────

    /** F&G ≥ this → BULL vote (+1). Greed territory. */
    private static final double FG_BULL_THRESHOLD = 55.0;
    /** F&G ≤ this → BEAR vote (−1). Fear territory. */
    private static final double FG_BEAR_THRESHOLD = 35.0;

    /** dist_from_ema200_pct > this → BULL vote. Price clearly above EMA200. */
    private static final double EMA200_BULL_PCT = 3.0;
    /** dist_from_ema200_pct < this → BEAR vote. Price clearly below EMA200. */
    private static final double EMA200_BEAR_PCT = -3.0;

    /** momentum_50bar_pct > this → BULL vote. */
    private static final double MOM_BULL_PCT = 3.0;
    /** momentum_50bar_pct < this → BEAR vote. */
    private static final double MOM_BEAR_PCT = -3.0;

    /** Cache TTL for F&G per symbol (30 minutes — F&G updates daily, no need to hammer DB). */
    private static final long FG_CACHE_TTL_MS = 30 * 60 * 1000L;

    // ── Dependencies ───────────────────────────────────────────────────────

    private final JdbcTemplate jdbc;

    // ── F&G cache: symbol → {value, cachedAtMs} ───────────────────────────

    private final ConcurrentHashMap<String, FgCache> fgCache = new ConcurrentHashMap<>();

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Classify the current market regime for the given symbol using the
     * pre-computed feature map from {@link com.agora.service.backtest.EntryFeatureSnapshot}.
     *
     * @param symbol   trading pair, e.g. "BTCUSDT"
     * @param features feature map already built by {@code MlInferenceLogger.buildFeatures()}
     * @return regime label; never null (falls back to SIDEWAYS on any error)
     */
    public MarketRegime classify(String symbol, Map<String, Object> features) {
        try {
            int score = 0;

            // Signal 1: Fear & Greed (cached DB query)
            Double fg = getLatestFearGreed(symbol);
            if (fg != null) {
                if (fg >= FG_BULL_THRESHOLD) score += 1;
                else if (fg <= FG_BEAR_THRESHOLD) score -= 1;
            }

            // Signal 2: dist_from_ema200_pct (from EntryFeatureSnapshot)
            Double ema200 = toDouble(features.get("dist_from_ema200_pct"));
            if (ema200 != null) {
                if (ema200 > EMA200_BULL_PCT) score += 1;
                else if (ema200 < EMA200_BEAR_PCT) score -= 1;
            }

            // Signal 3: momentum_50bar_pct (from EntryFeatureSnapshot)
            Double mom = toDouble(features.get("momentum_50bar_pct"));
            if (mom != null) {
                if (mom > MOM_BULL_PCT) score += 1;
                else if (mom < MOM_BEAR_PCT) score -= 1;
            }

            MarketRegime regime = score >= 2 ? MarketRegime.BULL
                    : score <= -2 ? MarketRegime.BEAR
                    : MarketRegime.SIDEWAYS;

            log.debug("[RegimeClassifier] {} → {} (fg={} ema200={}% mom={}% score={})",
                    symbol, regime, fg, ema200, mom, score);
            return regime;

        } catch (Exception e) {
            log.warn("[RegimeClassifier] classify failed for {}, defaulting SIDEWAYS: {}", symbol, e.getMessage());
            return MarketRegime.SIDEWAYS;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Return the most recent Fear &amp; Greed value for the given symbol from
     * {@code market_indicator_history}.  Result is cached for {@value #FG_CACHE_TTL_MS} ms.
     * Returns null if no data is available (table empty / symbol not tracked).
     */
    private Double getLatestFearGreed(String symbol) {
        long now = System.currentTimeMillis();
        FgCache entry = fgCache.get(symbol);
        if (entry != null && (now - entry.cachedAtMs) < FG_CACHE_TTL_MS) {
            return entry.value;  // null is a valid cached "no data" marker
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT value FROM market_indicator_history " +
                            "WHERE symbol = ? AND indicator = 'fear_greed' " +
                            "ORDER BY captured_at DESC LIMIT 1",
                    symbol);
            Double value = rows.isEmpty() ? null
                    : ((Number) rows.get(0).get("value")).doubleValue();
            fgCache.put(symbol, new FgCache(value, now));
            return value;
        } catch (Exception e) {
            log.warn("[RegimeClassifier] F&G query failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private static Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return null; }
    }

    // ── Inner types ────────────────────────────────────────────────────────

    private record FgCache(Double value, long cachedAtMs) {}
}
