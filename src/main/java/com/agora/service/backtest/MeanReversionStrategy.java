package com.agora.service.backtest;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 均值回歸策略 — 為 SIDEWAYS / RECOVERY 市況補空缺,SOP_MTF_ADX 在這兩種市況勝率偏低
 * (ID 27 BTC 1h 39% 即為證據)。
 *
 * <p><b>進場邏輯（LONG）</b>:
 * <ul>
 *   <li>RSI &lt; rsiOversold(預設 30,超賣)</li>
 *   <li>close ≤ bollLow ×(1 + bbBufferPct)（觸碰 / 跌破布林下軌附近）</li>
 *   <li>ADX &lt; adxMaxThreshold（預設 30,避免強趨勢期 mean reversion 接刀）</li>
 *   <li>close ≥ sma200 ×(1 - sma200BoundaryPct)（不在大跌段中接刀）</li>
 * </ul>
 *
 * <p><b>進場邏輯（SHORT）</b>:allowShort=true 時對稱反向。
 *
 * <p>SL/TP 由 BacktestEngine 既有機制處理（fixedStopLossPct / fixedTakeProfitPct 等 config）。
 *
 * <p><b>建議參數</b>:rsiOversold=30, rsiOverbought=70, bbBufferPct=0.005,
 * adxMaxThreshold=30, sma200BoundaryPct=0.05, allowShort=false。
 */
@Component
public class MeanReversionStrategy implements Strategy {

    public static final String TYPE = "MEAN_REVERSION";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
        int index = context.getIndex();
        if (index < 200) {
            return StrategySignal.HOLD; // sma200 warmup
        }

        double[] rsi      = context.getIndicators().get("rsi");
        double[] bollLow  = context.getIndicators().get("bollLow");
        double[] bollUp   = context.getIndicators().get("bollUp");
        double[] adx      = context.getIndicators().get("adx");
        double[] sma200   = context.getIndicators().get("sma200");

        if (rsi == null || bollLow == null || bollUp == null || adx == null || sma200 == null) {
            return StrategySignal.HOLD;
        }
        double r   = rsi[index];
        double bl  = bollLow[index];
        double bu  = bollUp[index];
        double a   = adx[index];
        double sma = sma200[index];
        if (Double.isNaN(r) || Double.isNaN(bl) || Double.isNaN(bu)
                || Double.isNaN(a) || Double.isNaN(sma)) {
            return StrategySignal.HOLD;
        }

        double close = context.getCurrent().getClosePrice().doubleValue();

        double rsiOversold       = getDouble(config, "rsiOversold", 30.0);
        double rsiOverbought     = getDouble(config, "rsiOverbought", 70.0);
        double bbBufferPct       = getDouble(config, "bbBufferPct", 0.005);
        double adxMaxThreshold   = getDouble(config, "adxMaxThreshold", 30.0);
        double sma200BoundaryPct = getDouble(config, "sma200BoundaryPct", 0.05);
        boolean allowShort       = getBool(config, "allowShort", false);

        // Publish snapshot so analyzeMarket / MarketSignalCache / LiveSignalEvaluator
        // see real rsi + a synthetic confidence. Previously only ScoreBuyStrategy
        // populated LiveSignalContext, which caused analyzeMarket to show
        // "NN=0.00 RSI=0.0" for non-ScoreBuy strategies. score = reversion proximity
        // (how close rsi is to the oversold/overbought band, clamped [0,1]).
        // nnOutput stays 0 (NN is a ScoreBuy-specific concept, not applicable here).
        double reversionScore;
        if (r <= rsiOversold) {
            reversionScore = Math.min(1.0, (rsiOversold - r) / rsiOversold + 0.5);
        } else if (r >= rsiOverbought) {
            reversionScore = Math.min(1.0, (r - rsiOverbought) / (100.0 - rsiOverbought) + 0.5);
        } else {
            reversionScore = 0.0;
        }
        LiveSignalContext.set(reversionScore, 0.0, r);

        // 強趨勢期不做 mean reversion(會被趨勢打)
        if (a >= adxMaxThreshold) {
            return StrategySignal.HOLD;
        }

        // LONG: RSI 超賣 + 觸碰布林下軌 + 不在大跌段中
        boolean longRsi  = r <= rsiOversold;
        boolean longBoll = close <= bl * (1.0 + bbBufferPct);
        boolean longTrend = close >= sma * (1.0 - sma200BoundaryPct);
        if (longRsi && longBoll && longTrend) {
            return StrategySignal.BUY;
        }

        // SHORT: 對稱反向(僅在 allowShort=true)
        if (allowShort) {
            boolean shortRsi  = r >= rsiOverbought;
            boolean shortBoll = close >= bu * (1.0 - bbBufferPct);
            boolean shortTrend = close <= sma * (1.0 + sma200BoundaryPct);
            if (shortRsi && shortBoll && shortTrend) {
                return StrategySignal.SELL;
            }
        }

        return StrategySignal.HOLD;
    }

    private double getDouble(Map<String, Object> config, String key, double defaultValue) {
        Object v = config.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException ex) { return defaultValue; }
    }

    private boolean getBool(Map<String, Object> config, String key, boolean defaultValue) {
        Object v = config.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean) return (Boolean) v;
        return "true".equalsIgnoreCase(String.valueOf(v));
    }
}
