package com.agora.service.backtest;

import com.agora.model.MdKline;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Score-based buy point detection strategy.
 *
 * Indicators (computed by BacktestEngine):
 *   rsi, bollMid, bollUp, bollLow, macdLine, macdSignal, sma200, volumeMa20
 *
 * Step 1 — derived features:
 *   isRelativeLow   : wicked below prevShortLow(10) but closed above it
 *   isPotentialLow  : wicked below prevMedLowest(63) but closed above it
 *   nearLowerBB     : close < bollLow + (bollMid - bollLow) * 0.3
 *   volumeBreakout  : volume > volumeMa20 * 1.5
 *   macdReverse     : hist > prevHist && prevHist <= prevPrevHist
 *
 * Step 2 — weighted score (all features normalised to [0,1]):
 *   rsi×0.20 + bbPct×0.20 + volRatio×0.15 + macd×0.10 + downDays×0.10
 *   + yearDrop×0.10 + priceDrop×0.075 + volChange×0.075
 *
 * Step 3 — sigmoid(score × scoreScale - scoreShift)
 *   default: scale=8, shift=4  →  need score ≥ 0.67 to produce nnOutput > 0.8
 *
 * Step 4 — buy gate:
 *   nnOutput > buyThreshold(0.8)
 *   && (isRelativeLow || isPotentialLow || (allowMacdAsLowProxy && macdReverse))
 *   && nearLowerBB
 *   && rsi < rsiOversold(40)
 *   && volumeBreakout
 *
 *   allowMacdAsLowProxy (default false): set true for assets like ETH that
 *   rarely show a clean wick-based low pattern but do exhibit MACD reversals.
 *
 * Sentiment bonus (live only, via SentimentContext ThreadLocal):
 *   fearGreedNorm = (100 - fearGreedValue) / 100  × weight 0.10
 *   whaleBuyNorm  = whaleBuyRatio                 × weight 0.05
 *   → added to score before sigmoid; backtest path is unaffected (ThreadLocal is null).
 *
 * Diagnostics:
 *   When score ≥ scoreDiagThreshold (0.4) but gate blocks, the failing
 *   condition is recorded via BacktestDiagnosticCollector so the response
 *   shows exactly what prevented a buy signal.
 *
 * Important config notes:
 *   yearLookbackBars: 252 for daily data (default).
 *                     For 1h data use 8760 (365 × 24).
 */
@Component
public class ScoreBuyStrategy implements Strategy {

    public static final String TYPE = "SCORE_BUY";

    // Custom diagnostic codes (string-based, no enum entry needed)
    private static final String D_WARMUP         = "SCORE_BUY_WARMUP";
    private static final String D_INDICATOR_NAN  = "SCORE_BUY_INDICATOR_NAN";
    private static final String D_LOW_SCORE      = "SCORE_BUY_LOW_SCORE";
    private static final String D_NO_LOW_PATTERN = "SCORE_BUY_NO_LOW_PATTERN";
    private static final String D_NOT_NEAR_BB    = "SCORE_BUY_NOT_NEAR_BB";
    private static final String D_RSI_HIGH       = "SCORE_BUY_RSI_HIGH";
    private static final String D_NO_VOL_BREAK   = "SCORE_BUY_NO_VOL_BREAKOUT";
    private static final String D_BELOW_SMA200   = "SCORE_BUY_BELOW_SMA200";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
        int index = context.getIndex();
        List<MdKline> klines = context.getKlines();
        BacktestDiagnosticCollector diag = BacktestDiagnosticCollector.fromConfig(config);

        // --- Warmup guard ---
        int minBars = getInt(config, "minWarmupBars", 200);
        if (index < minBars || index < 2) {
            record(diag, D_WARMUP, context, "index=" + index + " < minWarmupBars=" + minBars);
            return StrategySignal.HOLD;
        }

        // --- Pull indicators ---
        double[] rsi        = context.getIndicators().get("rsi");
        double[] bollMid    = context.getIndicators().get("bollMid");
        double[] bollUp     = context.getIndicators().get("bollUp");
        double[] bollLow    = context.getIndicators().get("bollLow");
        double[] sma200     = context.getIndicators().get("sma200");
        double[] volumeMa20 = context.getIndicators().get("volumeMa20");
        double[] macdLine   = context.getIndicators().get("macdLine");
        double[] macdSig    = context.getIndicators().get("macdSignal");

        if (!allValid(index, rsi, bollMid, bollUp, bollLow, sma200, volumeMa20, macdLine, macdSig)
                || Double.isNaN(macdLine[index - 1]) || Double.isNaN(macdLine[index - 2])
                || Double.isNaN(macdSig[index - 1])  || Double.isNaN(macdSig[index - 2])) {
            record(diag, D_INDICATOR_NAN, context,
                    "sma200=" + fmt(sma200, index) + " macdLine=" + fmt(macdLine, index));
            return StrategySignal.HOLD;
        }

        double close      = context.getCurrent().getClosePrice().doubleValue();
        double low        = context.getCurrent().getLowPrice().doubleValue();
        double volume     = context.getCurrent().getVolume().doubleValue();
        double closePrev  = klines.get(index - 1).getClosePrice().doubleValue();
        double volumePrev = klines.get(index - 1).getVolume().doubleValue();

        // --- Lookback windows ---
        int shortLookback = getInt(config, "shortLookbackBars",  10);
        int medLookback   = getInt(config, "medLookbackBars",    63);
        // 252 trading days = 1 year, correct for 1d bars.
        int yearLookback  = getInt(config, "yearLookbackBars", 252);

        double prevShortLow  = minLow(klines, Math.max(0, index - shortLookback), index - 1);
        double prevMedLowest = minLow(klines, Math.max(0, index - medLookback),   index - 1);
        double yearHigh      = maxHigh(klines, Math.max(0, index - yearLookback), index - 1);

        // --- Step 1: Derived features ---
        boolean isRelativeLow  = low <= prevShortLow  && close > prevShortLow;
        boolean isPotentialLow = low <= prevMedLowest && close > prevMedLowest;
        boolean nearLowerBB    = close < bollLow[index] + (bollMid[index] - bollLow[index]) * 0.3;

        double avgVol20        = volumeMa20[index];
        double volBreakMult    = getDouble(config, "volumeBreakoutMultiplier", 1.5);
        boolean volumeBreakout = avgVol20 > 0 && volume > avgVol20 * volBreakMult;

        double macdHist         = macdLine[index]     - macdSig[index];
        double prevMacdHist     = macdLine[index - 1] - macdSig[index - 1];
        double prevPrevMacdHist = macdLine[index - 2] - macdSig[index - 2];
        boolean macdReverse     = macdHist > prevMacdHist && prevMacdHist <= prevPrevMacdHist;

        int maxConsec        = getInt(config, "maxConsecDownDays", 10);
        int consecutiveDown  = calcConsecutiveDownBars(klines, index, maxConsec);
        double dropFromYear  = yearHigh > 0 ? (yearHigh - close) / yearHigh : 0.0;

        // --- Step 2: Normalise to [0,1] ---
        double rsiNorm       = clamp((100.0 - rsi[index]) / 100.0, 0.0, 1.0);
        double bbRange       = bollUp[index] - bollLow[index];
        double bbPct         = bbRange > 0 ? 1.0 - (close - bollLow[index]) / bbRange : 0.5;
        bbPct = clamp(bbPct, 0.0, 1.0);
        double volRatio      = avgVol20 > 0 ? clamp(volume / avgVol20 / 3.0, 0.0, 1.0) : 0.0;
        double macdNorm      = macdReverse ? 0.8 : (macdHist > 0 ? 0.5 : 0.2);
        double downNorm      = clamp((double) consecutiveDown / maxConsec, 0.0, 1.0);
        double yearDropNorm  = clamp(dropFromYear, 0.0, 1.0);
        double priceDropNorm = closePrev > 0
                ? clamp((closePrev - close) / closePrev / 0.10, 0.0, 1.0) : 0.0;
        double volChangeNorm = volumePrev > 0
                ? clamp((volume - volumePrev) / volumePrev, 0.0, 1.0) : 0.0;

        // --- Step 3: Weighted score + scaled sigmoid ---
        double score = rsiNorm      * 0.200
                     + bbPct        * 0.200
                     + volRatio     * 0.150
                     + macdNorm     * 0.100
                     + downNorm     * 0.100
                     + yearDropNorm * 0.100
                     + priceDropNorm* 0.075
                     + volChangeNorm* 0.075;

        // Sentiment bonus（只在 live 模式中由 SentimentContext 填入，backtest 時 = null）
        SentimentContext.Snapshot sentiment = SentimentContext.get();
        if (sentiment != null) {
            double fgNorm    = clamp((100.0 - sentiment.fearGreedValue) / 100.0, 0.0, 1.0);
            double whaleNorm = clamp(sentiment.whaleBuyRatio, 0.0, 1.0);
            score += fgNorm * 0.10 + whaleNorm * 0.05;
        }

        // sigmoid(score×8−3.5): need score≥0.575 for nnOutput>0.75 (with default params)
        double scoreScale = getDouble(config, "scoreScale", 8.0);
        double scoreShift = getDouble(config, "scoreShift", 4.0);
        double nnOutput   = sigmoid(score * scoreScale - scoreShift);

        double buyThreshold   = getDouble(config, "buyThreshold",  0.80);
        double rsiOversold    = getDouble(config, "rsiOversold",   40.0);
        double diagScoreFloor = getDouble(config, "diagScoreFloor", 0.40);
        // 15m 以下的短週期 bar 上，sma200 代表 ~50 小時均線，無趨勢意義，自動停用
        String runInterval = (String) config.get("runIntervalCode");
        boolean isShortInterval = "15m".equals(runInterval) || "5m".equals(runInterval) || "1m".equals(runInterval);
        boolean requireAboveSma200 = !isShortInterval && getBoolean(config, "requireAboveSma200", true);

        // Publish computed values so LiveSignalEvaluator can read them without
        // changing the Strategy.evaluate() return type.
        LiveSignalContext.set(score, nnOutput, rsi[index]);

        // --- Step 4: Gate conditions ---
        boolean scoreOk   = nnOutput > buyThreshold;
        // allowMacdAsLowProxy=true 時，macdReverse 可作為 isRelativeLow/isPotentialLow 的替代條件
        // 適用於 ETH 等不常出現明顯低點形態的資產
        boolean allowMacdAsLowProxy = getBoolean(config, "allowMacdAsLowProxy", false);
        boolean lowOk     = isRelativeLow || isPotentialLow || (allowMacdAsLowProxy && macdReverse);
        boolean bbOk      = nearLowerBB;
        boolean rsiOk     = rsi[index] < rsiOversold;
        boolean volOk     = volumeBreakout;
        boolean sma200Ok  = !requireAboveSma200 || close > sma200[index];

        boolean buySignal = scoreOk && lowOk && bbOk && rsiOk && volOk && sma200Ok;

        if (buySignal) {
            return StrategySignal.BUY;
        }

        // --- Diagnostics: report why gate blocked (only when score is meaningful) ---
        if (diag != null && score >= diagScoreFloor) {
            String scoreInfo = String.format(
                    "score=%.3f nn=%.3f rsi=%.1f bb%%=%.2f volRatio=%.2f yearDrop=%.2f",
                    score, nnOutput, rsi[index], bbPct, volRatio, dropFromYear);

            if (!scoreOk) {
                record(diag, D_LOW_SCORE, context,
                        scoreInfo + String.format(" → nnOutput(%.3f) <= threshold(%.2f)",
                                nnOutput, buyThreshold));
            }
            if (!lowOk) {
                record(diag, D_NO_LOW_PATTERN, context,
                        scoreInfo + String.format(
                                " relLow=%b potLow=%b macdRev=%b(proxy=%b) low=%.2f prevShort=%.2f prevMed=%.2f",
                                isRelativeLow, isPotentialLow, macdReverse, allowMacdAsLowProxy,
                                low, prevShortLow, prevMedLowest));
            }
            if (!bbOk) {
                record(diag, D_NOT_NEAR_BB, context,
                        scoreInfo + String.format(
                                " close=%.2f bollLow=%.2f threshold=%.2f",
                                close, bollLow[index],
                                bollLow[index] + (bollMid[index] - bollLow[index]) * 0.3));
            }
            if (!rsiOk) {
                record(diag, D_RSI_HIGH, context,
                        scoreInfo + String.format(" rsi=%.1f >= rsiOversold=%.1f",
                                rsi[index], rsiOversold));
            }
            if (!volOk) {
                record(diag, D_NO_VOL_BREAK, context,
                        scoreInfo + String.format(" vol=%.2f avgVol20=%.2f mult=%.1f",
                                volume, avgVol20, volBreakMult));
            }
            if (!sma200Ok) {
                record(diag, D_BELOW_SMA200, context,
                        scoreInfo + String.format(" close=%.2f sma200=%.2f",
                                close, sma200[index]));
            }
        }

        // --- Sell signal (secondary — engine SL/TP is primary) ---
        double rsiOverbought = getDouble(config, "rsiOverbought", 70.0);
        if (rsi[index] > rsiOverbought || close > bollUp[index]) {
            return StrategySignal.SELL;
        }

        return StrategySignal.HOLD;
    }

    // ---- Helpers ----

    private void record(BacktestDiagnosticCollector diag, String code,
                        StrategyContext context, String detail) {
        if (diag == null) return;
        diag.record(code, context.getCurrent().getOpenTime(), detail);
    }

    private boolean allValid(int index, double[]... arrays) {
        for (double[] arr : arrays) {
            if (arr == null || index >= arr.length || Double.isNaN(arr[index])) return false;
        }
        return true;
    }

    private int calcConsecutiveDownBars(List<MdKline> klines, int index, int maxLookback) {
        int count = 0;
        for (int i = index; i > Math.max(0, index - maxLookback); i--) {
            if (klines.get(i).getClosePrice().doubleValue()
                    < klines.get(i - 1).getClosePrice().doubleValue()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private double minLow(List<MdKline> klines, int start, int end) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            min = Math.min(min, klines.get(i).getLowPrice().doubleValue());
        }
        return min;
    }

    private double maxHigh(List<MdKline> klines, int start, int end) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            max = Math.max(max, klines.get(i).getHighPrice().doubleValue());
        }
        return max;
    }

    private String fmt(double[] arr, int index) {
        if (arr == null || index >= arr.length) return "null";
        double v = arr[index];
        return Double.isNaN(v) ? "NaN" : String.format("%.4f", v);
    }

    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int getInt(Map<String, Object> config, String key, int defaultValue) {
        Object v = config.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { return defaultValue; }
    }

    private double getDouble(Map<String, Object> config, String key, double defaultValue) {
        Object v = config.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return defaultValue; }
    }

    private boolean getBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        Object v = config.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
