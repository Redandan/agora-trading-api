package com.agora.service.backtest;

import com.agora.model.MdKline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the entry-time feature snapshot for ML signal_scorer.
 *
 * <p>V047 (current-bar local): adx14 / rsi14 / atr_pct / volume_ratio_ma20 /
 * close_vs_ema50_pct / ema20_slope_pct / bb_width_pct.
 *
 * <p>V049 (rolling regime / position-in-trend): dd_20bar_pct / dd_50bar_pct /
 * momentum_50bar_pct / realized_vol_20bar / dist_from_ema200_pct / range_pct_50bar.
 * These give the model regime context (bull/bear/chop, near-peak/near-bottom,
 * trending/squeezed) that V047 features completely lacked — Phase 2 walk-forward
 * showed both LightGBM and Gemini whiff on the 2026-03 holdout precisely because
 * they couldn't see "we're in a falling market right now".
 *
 * <p>Extracted from {@code BacktestEngine.attachEntrySnapshot} so both the
 * backtest engine (for new trades) and the backfill tool (for historical
 * trades) produce identical features from the same kline series.
 *
 * <p>NaN / Infinite / absurd values are nulled out so DECIMAL column
 * serialization never blows up. Missing-ness is expected by HeatWave ML.
 */
public final class EntryFeatureSnapshot {

    /** Returned keys (stable for schema). */
    public static final String ADX14 = "adx14";
    public static final String RSI14 = "rsi14";
    public static final String ATR_PCT = "atr_pct";
    public static final String VOLUME_RATIO_MA20 = "volume_ratio_ma20";
    public static final String CLOSE_VS_EMA50_PCT = "close_vs_ema50_pct";
    public static final String EMA20_SLOPE_PCT = "ema20_slope_pct";
    public static final String BB_WIDTH_PCT = "bb_width_pct";
    /** V049 regime keys. */
    public static final String DD_20BAR_PCT = "dd_20bar_pct";
    public static final String DD_50BAR_PCT = "dd_50bar_pct";
    public static final String MOMENTUM_50BAR_PCT = "momentum_50bar_pct";
    public static final String REALIZED_VOL_20BAR = "realized_vol_20bar";
    public static final String DIST_FROM_EMA200_PCT = "dist_from_ema200_pct";
    public static final String RANGE_PCT_50BAR = "range_pct_50bar";
    /** V050 cross-timeframe regime keys. */
    public static final String HTF_MOMENTUM_50BAR_PCT = "htf_momentum_50bar_pct";
    public static final String HTF_TREND_UP = "htf_trend_up";
    public static final String HTF_DIST_EMA50_PCT = "htf_dist_ema50_pct";

    /**
     * All V047+V049+V050 feature keys. Used by callers that need to ensure
     * every expected ML-input column is present (with null for missing) when
     * sending features to HeatWave ML_PREDICT_ROW — HW errors with ML003011
     * when input columns don't strictly match the trained schema.
     */
    public static final java.util.List<String> ALL_FEATURE_KEYS = java.util.List.of(
            ADX14, RSI14, ATR_PCT, VOLUME_RATIO_MA20,
            CLOSE_VS_EMA50_PCT, EMA20_SLOPE_PCT, BB_WIDTH_PCT,
            DD_20BAR_PCT, DD_50BAR_PCT, MOMENTUM_50BAR_PCT,
            REALIZED_VOL_20BAR, DIST_FROM_EMA200_PCT, RANGE_PCT_50BAR,
            HTF_MOMENTUM_50BAR_PCT, HTF_TREND_UP, HTF_DIST_EMA50_PCT);

    /**
     * Static / categorical feature keys (not derived from kline series — caller
     * supplies them). Together with {@link #ALL_FEATURE_KEYS} this is the full
     * v4/v5_dedup column set ML expects.
     */
    public static final java.util.List<String> STATIC_FEATURE_KEYS = java.util.List.of(
            "strategy_id", "is_short", "is_btc", "is_1h",
            "entry_price", "hour_of_day", "day_of_week");

    private EntryFeatureSnapshot() {}

    /**
     * @param klines chronological kline series, same (symbol, interval, source)
     *               as the strategy saw
     * @param idx    the bar index at which the trade was entered (typically
     *               the bar where the strategy emitted BUY/SELL signal)
     * @return map of feature name → Double (may be null if insufficient history
     *         for that indicator)
     */
    /**
     * No-HTF overload — V047 + V049 features only. Live engine path.
     */
    public static Map<String, Double> compute(List<MdKline> klines, int idx) {
        return compute(klines, idx, null);
    }

    /**
     * Full overload with optional HTF (higher-timeframe) klines for V050 features.
     *
     * @param klines     same-TF chronological kline series
     * @param idx        bar index of trade entry on the same-TF series
     * @param htfKlines  HTF chronological kline series ending at or before
     *                   {@code klines[idx].closeTime}; the last bar of htfKlines
     *                   is treated as the latest known HTF state at entry.
     *                   Pass {@code null} to skip V050 features (will be absent
     *                   from output map → backfill writes NULL).
     */
    public static Map<String, Double> compute(List<MdKline> klines, int idx, List<MdKline> htfKlines) {
        Map<String, Double> out = new LinkedHashMap<>();
        int n = klines == null ? 0 : klines.size();
        if (idx < 1 || idx >= n) return out;

        int len = idx + 1;
        double[] high = new double[len];
        double[] low = new double[len];
        double[] close = new double[len];
        double[] volume = new double[len];
        for (int i = 0; i < len; i++) {
            MdKline k = klines.get(i);
            high[i] = k.getHighPrice().doubleValue();
            low[i] = k.getLowPrice().doubleValue();
            close[i] = k.getClosePrice().doubleValue();
            volume[i] = k.getVolume().doubleValue();
        }
        double closeNow = close[idx];
        double entryPrice = closeNow;  // use entry_bar close as proxy when caller doesn't provide actual trade entry

        // ADX 14 — needs ~28 bars for stable Wilder smoothing
        if (len >= 28) {
            double[] adx = IndicatorUtils.adx(high, low, close, 14);
            out.put(ADX14, sanitize(adx[idx]));
        }
        // RSI 14 — needs 15+ bars
        if (len >= 15) {
            double[] rsi = IndicatorUtils.rsi(close, 14);
            out.put(RSI14, sanitize(rsi[idx]));
        }
        // ATR% — manual inline since we don't have the engine's helper here
        if (len >= 15) {
            double trSum = 0.0;
            int count = 0;
            for (int i = idx - 13; i <= idx; i++) {
                if (i < 1) continue;
                double tr = Math.max(high[i] - low[i],
                        Math.max(Math.abs(high[i] - close[i - 1]),
                                Math.abs(low[i] - close[i - 1])));
                trSum += tr;
                count++;
            }
            if (count > 0 && entryPrice > 0) {
                double atr = trSum / count;
                out.put(ATR_PCT, sanitize(atr / entryPrice));
            }
        }
        // Volume ratio vs 20-bar MA
        if (idx >= 20) {
            double sum = 0.0;
            for (int i = idx - 20; i < idx; i++) sum += volume[i];
            double ma20 = sum / 20.0;
            if (ma20 > 0) out.put(VOLUME_RATIO_MA20, sanitize(volume[idx] / ma20));
        }
        // close_vs_ema50
        if (len >= 50) {
            double[] ema50 = IndicatorUtils.ema(close, 50);
            double e = ema50[idx];
            if (e > 0) out.put(CLOSE_VS_EMA50_PCT, sanitize((closeNow - e) / e));
        }
        // ema20 slope (5-bar rate of change)
        if (len >= 25) {
            double[] ema20 = IndicatorUtils.ema(close, 20);
            double prev = ema20[idx - 5];
            double now = ema20[idx];
            if (prev > 0) out.put(EMA20_SLOPE_PCT, sanitize((now - prev) / prev));
        }
        // Bollinger Band width (4σ / mid, mid=EMA20)
        if (len >= 20) {
            double[] ema20 = IndicatorUtils.ema(close, 20);
            double mid = ema20[idx];
            double sumSq = 0.0;
            for (int i = idx - 19; i <= idx; i++) {
                double diff = close[i] - mid;
                sumSq += diff * diff;
            }
            double stdev = Math.sqrt(sumSq / 20.0);
            if (mid > 0) out.put(BB_WIDTH_PCT, sanitize(4.0 * stdev / mid));
        }

        // ─── V049 regime features ─────────────────────────────────────────────

        // dd_20bar_pct: drawdown from highest high in last 20 bars (positive = below peak)
        if (idx >= 19) {
            double hh = high[idx];
            for (int i = idx - 19; i <= idx; i++) hh = Math.max(hh, high[i]);
            if (hh > 0) out.put(DD_20BAR_PCT, sanitize((hh - closeNow) / hh));
        }
        // dd_50bar_pct: same on 50-bar window
        if (idx >= 49) {
            double hh = high[idx];
            for (int i = idx - 49; i <= idx; i++) hh = Math.max(hh, high[i]);
            if (hh > 0) out.put(DD_50BAR_PCT, sanitize((hh - closeNow) / hh));
        }
        // momentum_50bar_pct: close[idx] vs close[idx-50]
        if (idx >= 50 && close[idx - 50] > 0) {
            out.put(MOMENTUM_50BAR_PCT, sanitize((closeNow - close[idx - 50]) / close[idx - 50]));
        }
        // realized_vol_20bar: stdev of last 20 log returns (volatility regime)
        if (idx >= 20) {
            double[] rets = new double[20];
            int validCount = 0;
            for (int i = 0; i < 20; i++) {
                int b = idx - 19 + i;
                if (b >= 1 && close[b - 1] > 0 && close[b] > 0) {
                    rets[validCount++] = Math.log(close[b] / close[b - 1]);
                }
            }
            if (validCount >= 10) {
                double mean = 0;
                for (int i = 0; i < validCount; i++) mean += rets[i];
                mean /= validCount;
                double sumSq = 0;
                for (int i = 0; i < validCount; i++) {
                    double d = rets[i] - mean;
                    sumSq += d * d;
                }
                out.put(REALIZED_VOL_20BAR, sanitize(Math.sqrt(sumSq / validCount)));
            }
        }
        // dist_from_ema200_pct: long-term trend position
        if (len >= 200) {
            double[] ema200 = IndicatorUtils.ema(close, 200);
            double e = ema200[idx];
            if (e > 0) out.put(DIST_FROM_EMA200_PCT, sanitize((closeNow - e) / e));
        }
        // range_pct_50bar: (max_high50 - min_low50) / midpoint  (range tightness)
        if (idx >= 49) {
            double mh = high[idx], ml = low[idx];
            for (int i = idx - 49; i <= idx; i++) {
                if (high[i] > mh) mh = high[i];
                if (low[i] < ml) ml = low[i];
            }
            double mid = (mh + ml) / 2.0;
            if (mid > 0) out.put(RANGE_PCT_50BAR, sanitize((mh - ml) / mid));
        }

        // ─── V050 cross-timeframe (HTF) features ──────────────────────────────
        // htfKlines ending at or before this trade's entry; latest bar = HTF state.
        if (htfKlines != null && htfKlines.size() >= 50) {
            int hn = htfKlines.size();
            double[] hclose = new double[hn];
            for (int i = 0; i < hn; i++) hclose[i] = htfKlines.get(i).getClosePrice().doubleValue();
            int hi = hn - 1;  // last HTF bar index
            double hcloseNow = hclose[hi];

            // htf_momentum_50bar_pct
            if (hi >= 50 && hclose[hi - 50] > 0) {
                out.put(HTF_MOMENTUM_50BAR_PCT,
                        sanitize((hcloseNow - hclose[hi - 50]) / hclose[hi - 50]));
            }
            // htf_trend_up + htf_dist_ema50_pct (require 50+ HTF bars for stable EMA50)
            if (hi >= 50) {
                double[] hema50 = IndicatorUtils.ema(hclose, 50);
                double e = hema50[hi];
                if (e > 0) {
                    out.put(HTF_DIST_EMA50_PCT, sanitize((hcloseNow - e) / e));
                    // tinyint feature stored as Double; backfill writes 1/0 → BigDecimal
                    out.put(HTF_TREND_UP, hcloseNow > e ? 1.0 : 0.0);
                }
            }
        }

        return out;
    }

    private static Double sanitize(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        if (Math.abs(v) > 1_000_000) return null;
        return v;
    }
}
