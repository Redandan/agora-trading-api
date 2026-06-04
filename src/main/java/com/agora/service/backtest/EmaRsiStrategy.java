package com.agora.service.backtest;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EmaRsiStrategy implements Strategy {

    public static final String TYPE = "EMA_RSI";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
        int index = context.getIndex();
        if (index <= 0) {
            return StrategySignal.HOLD;
        }

        double[] emaFast = context.getIndicators().get("emaFast");
        double[] emaSlow = context.getIndicators().get("emaSlow");
        double[] rsi = context.getIndicators().get("rsi");

        if (emaFast == null || emaSlow == null || rsi == null) {
            return StrategySignal.HOLD;
        }
        if (Double.isNaN(emaFast[index]) || Double.isNaN(emaSlow[index]) || Double.isNaN(rsi[index])
                || Double.isNaN(emaFast[index - 1]) || Double.isNaN(emaSlow[index - 1])) {
            return StrategySignal.HOLD;
        }

        double buyThreshold = getDouble(config, "rsiBuyThreshold", 55.0);
        double sellThreshold = getDouble(config, "rsiSellThreshold", 45.0);

        boolean goldenCross = emaFast[index - 1] <= emaSlow[index - 1]
                && emaFast[index] > emaSlow[index];
        boolean deathCross = emaFast[index - 1] >= emaSlow[index - 1]
                && emaFast[index] < emaSlow[index];

        // Publish snapshot so LiveSignalEvaluator / MarketSignalCache / analyzeMarket
        // have non-zero rsi + a synthetic confidence score. Previously only
        // ScoreBuyStrategy populated this — other strategy types produced misleading
        // "NN=0.00 RSI=0.0" in analyzeMarket. nnOutput stays 0 because NN is a
        // ScoreBuy-specific concept; score is a rough EMA-spread proxy.
        double emaSpread = (emaFast[index] - emaSlow[index]) / emaSlow[index];
        double score = Math.max(0.0, Math.min(1.0, 0.5 + emaSpread * 10.0));
        LiveSignalContext.set(score, 0.0, rsi[index]);

        if (goldenCross && rsi[index] >= buyThreshold) {
            return StrategySignal.BUY;
        }
        if (deathCross || rsi[index] <= sellThreshold) {
            return StrategySignal.SELL;
        }
        return StrategySignal.HOLD;
    }

    private double getDouble(Map<String, Object> config, String key, double defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
