package com.agora.service.ml;

/**
 * Rule-based market regime label used by {@link RegimeClassifier}.
 *
 * <p>Three signals each cast a vote (BULL / BEAR / NEUTRAL):
 * <ol>
 *   <li>Fear & Greed index: ≥ 55 → BULL, ≤ 35 → BEAR</li>
 *   <li>dist_from_ema200_pct: > +3% → BULL, < −3% → BEAR</li>
 *   <li>momentum_50bar_pct: > +3% → BULL, < −3% → BEAR</li>
 * </ol>
 * Majority (≥ 2 / 3 same vote) determines the final label; otherwise SIDEWAYS.
 *
 * <p>Usage: stored in {@code ml_inference_log.regime} (V069) to enable
 * regime-stratified ML accuracy analysis — e.g. "v13 edge in BULL vs BEAR".
 */
public enum MarketRegime {

    /** Trending up: greed, price well above EMA200, positive momentum. */
    BULL,

    /** No clear direction: mixed or insufficient signals. */
    SIDEWAYS,

    /** Trending down: fear, price below EMA200, negative momentum. */
    BEAR;

    /** Convenience: return the string stored in DB (same as name()). */
    public String dbValue() {
        return name();
    }
}
