package com.agora.service.market;

import com.agora.service.ai.AiStrategyDiscoveryService.MarketSnapshot;

/**
 * Rule-based market regime classifier — deterministic alternative to GeminiMarketAdvisor.
 *
 * <p>Produces the same output fields as the Gemini 3-persona voting system but uses
 * purely deterministic technical-indicator rules: ADX, RSI, ATR%, EMA trend direction,
 * MACD histogram — all already computed in {@link MarketSnapshot}.
 *
 * <p><b>Why this exists</b>: Gemini's 3-persona prompts contain structural bias:
 * the "risk" persona explicitly prefers CONSERVATIVE/DISABLE over TREND/HIGH_FREQ,
 * and the "contrarian" persona suggests CONSERVATIVE in strong trends — giving
 * 2-of-3 personas a conservative tilt regardless of actual market conditions.
 * Over 30 days of production data, Gemini output TREND/HIGH_FREQ exactly 0 times.
 *
 * <p><b>Output compatibility</b>: {@link Result#regime()} uses the same labels as
 * {@code GeminiMarketHint.regime} (TRENDING_UP / TRENDING_DOWN / SIDEWAYS /
 * VOLATILE / RECOVERY) so {@code TradeDecisionEngine} can consume either source
 * without modification.
 *
 * <p>Designed to run alongside Gemini in A/B tracking mode (embedded in
 * {@code GeminiMarketHint.personaVotes} under key {@code "deterministic"}) and
 * eventually replace the Gemini advisor entirely once agreement-rate validation passes.
 */
public final class DeterministicRegimeClassifier {

    private DeterministicRegimeClassifier() {}

    /**
     * Classifier output — mirrors the fields written to {@code gemini_market_hint}.
     *
     * @param regime       TRENDING_UP | TRENDING_DOWN | SIDEWAYS | VOLATILE | RECOVERY
     * @param styleHint    TREND | CONSERVATIVE | DISABLE  (HIGH_FREQ intentionally excluded)
     * @param adxAdjust    ADX entry-threshold delta (mirrors P1 regime-filter values: see
     *                     {@code LiveSignalEvaluator.applyRegimeConfigOverrides})
     * @param slMultiplier SL% multiplier (1.0 = no change; >1 = wider; <1 = tighter)
     * @param tpMultiplier TP% multiplier
     * @param allowShort   true only in confirmed multi-timeframe downtrend
     * @param confidence   1.0 for clear signals; 0.8 when ADX is in the 22-25 borderline zone
     */
    public record Result(
            String  regime,
            String  styleHint,
            double  adxAdjust,
            double  slMultiplier,
            double  tpMultiplier,
            boolean allowShort,
            double  confidence
    ) {}

    /**
     * Slim subset of {@link MarketSnapshot} fields actually used by the classifier.
     *
     * <p>Exposes the contract so callers without a full {@code MarketSnapshot}
     * (e.g. backtest engine reading from precomputed indicator arrays — see
     * {@code BacktestRegimeFilter} #392) can still drive the same logic.
     *
     * @param trendDirection BULLISH | BEARISH | SIDEWAYS (derived from EMA20 vs close)
     * @param rsi14          RSI(14) on closes
     * @param adx14          ADX(14) Wilder smoothed
     * @param atrPct         ATR(14) / close × 100
     * @param macdHistogram  MACD line − signal line
     */
    public record Inputs(
            String trendDirection,
            double rsi14,
            double adx14,
            double atrPct,
            double macdHistogram
    ) {
        public static Inputs from(MarketSnapshot s) {
            return new Inputs(s.trendDirection(), s.rsi14(), s.adx14(), s.atrPct(), s.macdHistogram());
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Classify regime from two timeframe snapshots.
     *
     * @param primary the strategy's own timeframe (e.g. 1h for a 1h strategy)
     * @param context the complementary timeframe used for trend confirmation (e.g. 4h)
     */
    public static Result classify(MarketSnapshot primary, MarketSnapshot context) {
        return classify(Inputs.from(primary), Inputs.from(context));
    }

    /**
     * Classify regime from slim indicator inputs — same logic as
     * {@link #classify(MarketSnapshot, MarketSnapshot)} but without requiring
     * the wider {@code MarketSnapshot} record (used by backtest engine).
     */
    public static Result classify(Inputs primary, Inputs context) {

        // ── Raw indicator values ───────────────────────────────────────────────
        String trendP = primary.trendDirection();  // BULLISH | BEARISH | SIDEWAYS
        String trendC = context.trendDirection();
        double rsi    = primary.rsi14();
        double adxP   = primary.adx14();
        double adxC   = context.adx14();
        double atr    = primary.atrPct();
        double macd   = primary.macdHistogram();

        // ── Step 1: Regime classification ─────────────────────────────────────
        double confidence = 1.0;
        final String regime;

        if (atr > 2.5) {
            // High volatility — regime is unstable regardless of trend
            regime = "VOLATILE";

        } else if ("BULLISH".equals(trendP) && "BULLISH".equals(trendC)
                   && (adxP >= 22 || adxC >= 22)) {
            // Both timeframes bullish + meaningful trend strength
            regime = "TRENDING_UP";
            if (adxP < 25 && adxC < 25) confidence = 0.8; // weak trend, borderline

        } else if ("BEARISH".equals(trendP) && "BEARISH".equals(trendC)
                   && (adxP >= 22 || adxC >= 22)) {
            // Both timeframes bearish + meaningful trend strength
            regime = "TRENDING_DOWN";
            if (adxP < 25 && adxC < 25) confidence = 0.8;

        } else if (rsi < 35 && macd > 0 && !"BEARISH".equals(trendP)) {
            // RSI oversold + MACD turning positive + primary not in downtrend
            // → classic recovery/mean-reversion setup
            regime = "RECOVERY";

        } else {
            regime = "SIDEWAYS";
        }

        // ── Step 2: Style hint derived from regime ────────────────────────────
        final String style = switch (regime) {
            case "TRENDING_UP"   -> "TREND";         // strong uptrend → go with it
            case "TRENDING_DOWN" -> "CONSERVATIVE";  // be cautious for LONG, look for SHORT
            case "VOLATILE"      -> "DISABLE";       // too risky for systematic entry
            case "RECOVERY"      -> "CONSERVATIVE";  // cautious — recovery can reverse
            default              -> "CONSERVATIVE";  // SIDEWAYS — wait for breakout
        };

        // ── Step 3: Allow short — confirmed multi-TF downtrend with strength ──
        final boolean allowShort = "TRENDING_DOWN".equals(regime)
                                   && adxP >= 25
                                   && "BEARISH".equals(trendP);

        // ── Step 4: ADX entry-threshold adjustment ────────────────────────────
        // Values mirror P1 regime filter deltas (applyRegimeConfigOverrides):
        //   TRENDING_DOWN +8 / SIDEWAYS +5 / VOLATILE +3 / TRENDING_UP -2 / RECOVERY 0
        final double adxAdj = switch (regime) {
            case "TRENDING_DOWN" -> 8.0;
            case "SIDEWAYS"      -> 5.0;
            case "VOLATILE"      -> 3.0;
            case "TRENDING_UP"   -> -2.0;
            default              -> 0.0;   // RECOVERY — neutral
        };

        // ── Step 5: SL / TP multipliers ──────────────────────────────────────
        // Wider SL in high volatility; let profits run in confirmed uptrend
        final double slMult = (atr > 1.5) ? 1.2 : 1.0;
        final double tpMult = "TRENDING_UP".equals(regime) ? 1.1 : 1.0;

        return new Result(regime, style, adxAdj, slMult, tpMult, allowShort, confidence);
    }
}
