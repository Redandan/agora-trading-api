package com.agora.service.backtest;

import com.agora.model.MdKline;
import com.agora.service.market.DeterministicRegimeClassifier;

import java.util.Map;

/**
 * #392 Option B — Lightweight RegimeFilter parity for backtest.
 *
 * <p>{@link LiveSignalEvaluator#evaluateOnBarClose} blocks LONG entries when the
 * deterministic regime classifier votes {@code TRENDING_DOWN} (with optional
 * RSI-extreme-oversold and low-confidence bypasses). Backtest goes through
 * {@link BacktestEngine#run} which never enters that path, so backtest results
 * are systematically more optimistic than live — see issue #392 for details.
 *
 * <p>This helper closes the gap by re-running the same filter logic against
 * the strategy's <b>primary timeframe indicators</b> at each bar. The simpler,
 * single-TF approach is intentional ("lightweight"): re-using the live's
 * dual-TF lookup would require loading and aligning a second kline series in
 * backtest, which is what Option A would do (1-2 days). The accuracy tradeoff:
 * single-TF classification is a touch more aggressive than dual-TF (more
 * BLOCK calls), erring conservative — desirable since the original bias is
 * <i>over</i>-optimistic.
 *
 * <h3>Activation</h3>
 * <ul>
 *   <li>Default OFF — {@code config.applyRegimeFilter} must be {@code true}</li>
 *   <li>{@code BacktestEngine} flips it on automatically when the run config
 *       carries any regime-related parameter (so {@code runBacktestSweep} of
 *       {@code regimeFilterMinConfidence} actually shows differentiated PnL)</li>
 * </ul>
 *
 * <h3>Parity scope</h3>
 * <p>Implements the LONG-suppression branch of LiveSignalEvaluator:545-568:
 * <ul>
 *   <li>{@code TRENDING_DOWN} blocks LONG by default</li>
 *   <li>{@code allowLongInBearRegime=true} disables the block</li>
 *   <li>{@code allowRsiBypassRegime=true} + RSI &lt; {@code regimeBypassRsiThreshold}
 *       (default 20) bypasses the block (panic-bottom strategies)</li>
 *   <li>{@code regimeFilterMinConfidence} &gt; {@code classifier.confidence}
 *       bypasses the block (low-confidence regime read)</li>
 * </ul>
 *
 * <p>NOT covered: P1 {@code applyRegimeConfigOverrides} (ADX threshold deltas
 * by regime). Those mutate strategy params in live and would change every
 * existing backtest baseline if applied here — out of scope.
 */
public final class BacktestRegimeFilter {

    private BacktestRegimeFilter() {}

    public enum BlockReason {
        ALLOW,
        TRENDING_DOWN_LONG_BLOCKED
    }

    public record Decision(
            BlockReason reason,
            String  regime,
            double  confidence,
            boolean rsiBypassed,
            boolean lowConfBypassed
    ) {
        public static final Decision ALLOW =
                new Decision(BlockReason.ALLOW, null, 0.0, false, false);
    }

    /**
     * Evaluate a LONG entry at the strategy's current bar.
     *
     * @return a {@link Decision} — {@code reason == ALLOW} if the trade should
     *         proceed; {@code TRENDING_DOWN_LONG_BLOCKED} if regime filter
     *         vetoes it.
     */
    public static Decision evaluateLongEntry(StrategyContext context,
                                             Map<String, Object> config) {
        if (context == null || config == null) return Decision.ALLOW;

        DeterministicRegimeClassifier.Inputs primary = buildInputs(context);
        if (primary == null) return Decision.ALLOW;  // insufficient indicator data

        // Primary-only mode: pass the same Inputs as both primary AND context.
        // Effect: the dual-TF AND-gates in DeterministicRegimeClassifier collapse
        // to single-TF checks (e.g. "BEARISH && BEARISH" = "BEARISH"). This is
        // more aggressive than live's dual-TF requirement — over-blocks slightly,
        // which is the conservative direction for backtest (see class javadoc).
        DeterministicRegimeClassifier.Result regime =
                DeterministicRegimeClassifier.classify(primary, primary);

        // The regime filter only suppresses LONG entries in TRENDING_DOWN.
        if (!"TRENDING_DOWN".equalsIgnoreCase(regime.regime())) {
            return Decision.ALLOW;
        }

        // Strategy explicitly opts out (e.g. mean-reversion / contrarian).
        if (getBoolean(config, "allowLongInBearRegime", false)) {
            return Decision.ALLOW;
        }

        // Bypass 1: RSI extreme oversold — panic-bottom strategies opt-in.
        boolean strategyAllowsRsiBypass = getBoolean(config, "allowRsiBypassRegime", false);
        double regimeBypassThreshold = getDouble(config, "regimeBypassRsiThreshold", 20.0);
        boolean rsiBypassed = strategyAllowsRsiBypass
                && primary.rsi14() < regimeBypassThreshold;
        if (rsiBypassed) {
            return new Decision(BlockReason.ALLOW, regime.regime(),
                    regime.confidence(), true, false);
        }

        // Bypass 2: low-confidence regime read.
        // LiveSignalEvaluator:545-548 — only block when classifier confidence
        // ≥ minConf. Default 0 = always block (back-compat).
        double regimeMinConf = getDouble(config, "regimeFilterMinConfidence", 0.0);
        boolean lowConfBypassed = regimeMinConf > 0 && regime.confidence() < regimeMinConf;
        if (lowConfBypassed) {
            return new Decision(BlockReason.ALLOW, regime.regime(),
                    regime.confidence(), false, true);
        }

        return new Decision(BlockReason.TRENDING_DOWN_LONG_BLOCKED,
                regime.regime(), regime.confidence(), false, false);
    }

    /**
     * Build classifier inputs from the strategy's primary-TF indicator arrays
     * at the current bar index. Returns {@code null} when essential indicators
     * are missing or NaN (early bars before warmup completes).
     */
    private static DeterministicRegimeClassifier.Inputs buildInputs(StrategyContext context) {
        Map<String, double[]> ind = context.getIndicators();
        int i = context.getIndex();
        MdKline current = context.getCurrent();
        if (ind == null || current == null || i < 0) return null;

        double[] ema20Arr = ind.get("ema20");
        double[] rsiArr   = ind.get("rsi");
        double[] adxArr   = ind.get("adx");
        double[] atrArr   = ind.get("atr");
        double[] macdLine   = ind.get("macdLine");
        double[] macdSignal = ind.get("macdSignal");

        double close = current.getClosePrice() == null ? 0.0
                : current.getClosePrice().doubleValue();
        if (close <= 0.0) return null;

        double ema20 = readAt(ema20Arr, i);
        double rsi   = readAt(rsiArr, i);
        double adx   = readAt(adxArr, i);
        double atr   = readAt(atrArr, i);
        double macdH = readAt(macdLine, i) - readAt(macdSignal, i);
        if (Double.isNaN(ema20) || Double.isNaN(rsi) || Double.isNaN(adx) || Double.isNaN(atr)) {
            return null;  // not enough warmup for any of the core indicators
        }

        // Trend direction — same threshold as MarketSnapshot (±1% band around EMA20)
        String trendDir = close > ema20 * 1.01 ? "BULLISH"
                        : close < ema20 * 0.99 ? "BEARISH"
                        : "SIDEWAYS";
        double atrPct = atr / close * 100.0;
        if (Double.isNaN(macdH)) macdH = 0.0;

        return new DeterministicRegimeClassifier.Inputs(trendDir, rsi, adx, atrPct, macdH);
    }

    private static double readAt(double[] arr, int i) {
        if (arr == null || i < 0 || i >= arr.length) return Double.NaN;
        return arr[i];
    }

    private static boolean getBoolean(Map<String, Object> config, String key, boolean def) {
        Object v = config.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s);
        return def;
    }

    private static double getDouble(Map<String, Object> config, String key, double def) {
        Object v = config.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException e) { return def; }
    }
}
