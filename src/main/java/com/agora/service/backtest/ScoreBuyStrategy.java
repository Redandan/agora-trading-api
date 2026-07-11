package com.agora.service.backtest;

import com.agora.model.MdKline;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Exact Java port of strategy 485's TradingView Pine entry logic. */
@Component
public class ScoreBuyStrategy implements Strategy {

    public static final String TYPE = "SCORE_BUY";
    public static final String PINE_SOURCE_SHA256 =
            "e144024f8972b2b624bfc05888cdb0fac52feb17c9376f647aa9517ef6de0715";

    private static final String D_WARMUP = "SCORE_BUY_WARMUP";
    private static final String D_INDICATOR_NAN = "SCORE_BUY_INDICATOR_NAN";
    private static final String D_LOW_SCORE = "SCORE_BUY_LOW_SCORE";
    private static final String D_NO_LOW_PATTERN = "SCORE_BUY_NO_LOW_PATTERN";
    private static final String D_NOT_NEAR_BB = "SCORE_BUY_NOT_NEAR_BB";
    private static final String D_RSI_HIGH = "SCORE_BUY_RSI_HIGH";
    private static final String D_NO_VOL_BREAK = "SCORE_BUY_NO_VOL_BREAKOUT";

    static final String ORDER_RELATIVE_LOW = "TRADINGVIEW_RELATIVE_LOW";
    static final String ORDER_POTENTIAL_LOW = "TRADINGVIEW_POTENTIAL_LOW";
    static final String ORDER_AI_BUY = "TRADINGVIEW_AI_BUY_SIGNAL";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> defaultExecutionConfig() {
        return Map.of(
                "tradingViewOrderIntentExecution", true,
                "tradingViewParityMode", true,
                TradingViewScoreBuyModel.REPLAY_START_CONFIG,
                TradingViewScoreBuyModel.BTCUSDT_1D_REPLAY_START_UTC.toString(),
                TradingViewScoreBuyModel.REQUIRE_FULL_HISTORY_CONFIG, true);
    }

    @Override
    public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
        int index = context.getIndex();
        List<MdKline> klines = context.getKlines();
        BacktestDiagnosticCollector diagnostic = BacktestDiagnosticCollector.fromConfig(config);
        if (index < 1 || klines == null || index >= klines.size()) {
            record(diagnostic, D_WARMUP, context, "Pine low patterns require one previous bar");
            return StrategySignal.HOLD;
        }

        Map<String, double[]> indicators = context.getIndicators();
        TradingViewScoreBuyModel.Series replay = null;
        if (indicators.get(TradingViewScoreBuyModel.NN_OUTPUT_KEY) == null) {
            replay = TradingViewScoreBuyModel.replay(klines, indicators, config);
        }

        double[] rsi = indicators.get("rsi");
        double[] bollMiddle = indicators.get("bollMid");
        double[] bollUpper = indicators.get("bollUp");
        double[] bollLower = indicators.get("bollLow");
        double[] volumeAverage = indicators.get("volumeMa20");
        double[] nnOutputs = series(indicators, replay, TradingViewScoreBuyModel.NN_OUTPUT_KEY);
        double[] nnSums = series(indicators, replay, TradingViewScoreBuyModel.NN_SUM_KEY);
        double[] nnWeights = series(indicators, replay, TradingViewScoreBuyModel.NN_WEIGHT_KEY);
        double[] nnBias = series(indicators, replay, TradingViewScoreBuyModel.NN_BIAS_KEY);
        double[] historyCoverage = series(indicators, replay, TradingViewScoreBuyModel.HISTORY_COMPLETE_KEY);

        MdKline current = klines.get(index);
        double close = current.getClosePrice().doubleValue();
        double low = current.getLowPrice().doubleValue();
        double volume = current.getVolume().doubleValue();

        boolean parityMode = getBoolean(config, "tradingViewParityMode", true);
        int shortLookback = parityMode ? 20 : getInt(config, "shortLookbackBars", 20);
        int potentialLookback = parityMode ? 63 : getInt(config, "medLookbackBars", 63);
        double previousShortLow = minLow(klines, Math.max(0, index - shortLookback), index - 1);
        double currentPotentialLow = minLow(
                klines, Math.max(0, index - potentialLookback + 1), index);
        double previousPotentialLow = minLow(
                klines, Math.max(0, index - potentialLookback), index - 1);

        boolean isRelativeLow = low <= previousShortLow && close > previousShortLow;
        boolean isPotentialLow = low <= currentPotentialLow && close > previousPotentialLow;

        double rsiValue = valueAt(rsi, index);
        double bollMiddleValue = valueAt(bollMiddle, index);
        double bollUpperValue = valueAt(bollUpper, index);
        double bollLowerValue = valueAt(bollLower, index);
        double averageVolume = valueAt(volumeAverage, index);
        double nnOutput = valueAt(nnOutputs, index);
        double nnInputSum = valueAt(nnSums, index);
        double nnWeight = valueAt(nnWeights, index);
        double nnBiasValue = valueAt(nnBias, index);
        boolean historyComplete = valueAt(historyCoverage, index, 1.0) >= 0.5;

        boolean nearLowerBollinger = Double.isFinite(bollMiddleValue)
                && Double.isFinite(bollLowerValue)
                && close < bollLowerValue + (bollMiddleValue - bollLowerValue) * 0.3;
        double volumeThreshold = parityMode
                ? 1.5
                : getDouble(config, "volumeBreakoutMultiplier", 1.5);
        boolean volumeBreakout = Double.isFinite(averageVolume)
                && volume > averageVolume * volumeThreshold;
        double buyThreshold = parityMode ? 0.8 : getDouble(config, "buyThreshold", 0.8);
        double rsiOversold = parityMode ? 40.0 : getDouble(config, "rsiOversold", 40.0);
        boolean scoreOk = Double.isFinite(nnOutput) && nnOutput > buyThreshold;
        boolean lowOk = isRelativeLow || isPotentialLow;
        boolean bollingerOk = nearLowerBollinger;
        boolean rsiOk = Double.isFinite(rsiValue) && rsiValue < rsiOversold;
        boolean buySignal = scoreOk && lowOk && bollingerOk && rsiOk && volumeBreakout;

        double publishedNn = Double.isFinite(nnOutput) ? nnOutput : 0.0;
        double publishedRsi = Double.isFinite(rsiValue) ? rsiValue : 0.0;
        LiveSignalContext.set(publishedNn, publishedNn, publishedRsi);
        putTradingViewDetails(isRelativeLow, isPotentialLow, nearLowerBollinger,
                volumeBreakout, scoreOk, lowOk, bollingerOk, rsiOk, buySignal,
                rsiValue, rsiOversold, nnOutput, nnInputSum, nnWeight, nnBiasValue,
                buyThreshold, volume, averageVolume, volumeThreshold, historyComplete);

        boolean fullHistoryRequired = getBoolean(
                config, TradingViewScoreBuyModel.REQUIRE_FULL_HISTORY_CONFIG, false);
        boolean allowIncompleteShadowIntents = getBoolean(
                config, "tradingViewAllowIncompleteHistoryShadowIntents", false);
        if (fullHistoryRequired && !historyComplete && !allowIncompleteShadowIntents) {
            return StrategySignal.HOLD;
        }

        // Keep the exact Pine statement order: relative, potential, then AI.
        List<LiveSignalContext.OrderIntent> orders = new ArrayList<>();
        if (isRelativeLow) {
            orders.add(new LiveSignalContext.OrderIntent(
                    ORDER_RELATIVE_LOW, "相对低点买入", 1000));
        }
        if (isPotentialLow) {
            orders.add(new LiveSignalContext.OrderIntent(
                    ORDER_POTENTIAL_LOW, "潜在低点买入", 2000));
        }
        if (buySignal) {
            orders.add(new LiveSignalContext.OrderIntent(
                    ORDER_AI_BUY, "AI买点买入", 5000));
        }
        if (!orders.isEmpty()) {
            putTradingViewOrders(orders);
            return StrategySignal.BUY;
        }

        recordDiagnostics(diagnostic, context, scoreOk, lowOk, bollingerOk, rsiOk,
                volumeBreakout, nnOutput, buyThreshold, rsiValue, rsiOversold,
                volume, averageVolume, volumeThreshold);
        return StrategySignal.HOLD;
    }

    private void recordDiagnostics(BacktestDiagnosticCollector diagnostic,
                                   StrategyContext context,
                                   boolean scoreOk,
                                   boolean lowOk,
                                   boolean bollingerOk,
                                   boolean rsiOk,
                                   boolean volumeOk,
                                   double nnOutput,
                                   double buyThreshold,
                                   double rsi,
                                   double rsiOversold,
                                   double volume,
                                   double averageVolume,
                                   double volumeThreshold) {
        if (diagnostic == null) {
            return;
        }
        if (!Double.isFinite(nnOutput)) {
            record(diagnostic, D_INDICATOR_NAN, context, "Pine NN inputs are not available");
        } else if (!scoreOk) {
            record(diagnostic, D_LOW_SCORE, context,
                    String.format("nnOutput=%.9f <= %.3f", nnOutput, buyThreshold));
        }
        if (!lowOk) {
            record(diagnostic, D_NO_LOW_PATTERN, context, "no relative/potential low");
        }
        if (!bollingerOk) {
            record(diagnostic, D_NOT_NEAR_BB, context, "close is not near lower Bollinger band");
        }
        if (!rsiOk) {
            record(diagnostic, D_RSI_HIGH, context,
                    String.format("rsi=%.4f threshold=%.4f", rsi, rsiOversold));
        }
        if (!volumeOk) {
            record(diagnostic, D_NO_VOL_BREAK, context,
                    String.format("volume=%.4f average=%.4f threshold=%.4f",
                            volume, averageVolume, volumeThreshold));
        }
    }

    private void putTradingViewDetails(boolean isRelativeLow,
                                       boolean isPotentialLow,
                                       boolean nearLowerBollinger,
                                       boolean volumeBreakout,
                                       boolean scoreOk,
                                       boolean lowOk,
                                       boolean bollingerOk,
                                       boolean rsiOk,
                                       boolean buySignal,
                                       double rsi,
                                       double rsiOversold,
                                       double nnOutput,
                                       double nnInputSum,
                                       double nnWeight,
                                       double nnBias,
                                       double buyThreshold,
                                       double volume,
                                       double averageVolume,
                                       double volumeThreshold,
                                       boolean historyComplete) {
        LiveSignalContext.putDetail("tradingview_is_relative_low", isRelativeLow);
        LiveSignalContext.putDetail("tradingview_is_potential_low", isPotentialLow);
        LiveSignalContext.putDetail("tradingview_near_lower_bb", nearLowerBollinger);
        LiveSignalContext.putDetail("tradingview_volume_breakout", volumeBreakout);
        LiveSignalContext.putDetail("tradingview_score_ok", scoreOk);
        LiveSignalContext.putDetail("tradingview_low_ok", lowOk);
        LiveSignalContext.putDetail("tradingview_bb_ok", bollingerOk);
        LiveSignalContext.putDetail("tradingview_rsi_ok", rsiOk);
        LiveSignalContext.putDetail("tradingview_buy_signal", buySignal);
        LiveSignalContext.putDetail("tradingview_rsi", finiteOrNull(rsi));
        LiveSignalContext.putDetail("tradingview_rsi_oversold", rsiOversold);
        LiveSignalContext.putDetail("tradingview_nn_output", finiteOrNull(nnOutput));
        LiveSignalContext.putDetail("tradingview_nn_input_sum", finiteOrNull(nnInputSum));
        LiveSignalContext.putDetail("tradingview_nn_weight", finiteOrNull(nnWeight));
        LiveSignalContext.putDetail("tradingview_nn_bias", finiteOrNull(nnBias));
        LiveSignalContext.putDetail("tradingview_buy_threshold", buyThreshold);
        LiveSignalContext.putDetail("tradingview_volume", volume);
        LiveSignalContext.putDetail("tradingview_average_volume_20", finiteOrNull(averageVolume));
        LiveSignalContext.putDetail("tradingview_volume_threshold", volumeThreshold);
        LiveSignalContext.putDetail("tradingview_history_complete", historyComplete);
        LiveSignalContext.putDetail("tradingview_replay_start_utc",
                TradingViewScoreBuyModel.BTCUSDT_1D_REPLAY_START_UTC.toString());
        LiveSignalContext.putDetail("tradingview_pine_source_sha256", PINE_SOURCE_SHA256);
        LiveSignalContext.putDetail("tradingview_nn_evidence_status",
                !historyComplete ? "INCOMPLETE_REPLAY_HISTORY"
                        : Double.isFinite(nnOutput) ? "EXACT_ONLINE_REPLAY" : "INDICATOR_NA");
    }

    private void putTradingViewOrders(List<LiveSignalContext.OrderIntent> orders) {
        for (LiveSignalContext.OrderIntent order : orders) {
            LiveSignalContext.addOrderIntent(order.reason(), order.label(), order.quantity());
        }
        LiveSignalContext.OrderIntent primary = orders.get(0);
        LiveSignalContext.putDetail("tradingview_order_reason", primary.reason());
        LiveSignalContext.putDetail("tradingview_order_label", primary.label());
        LiveSignalContext.putDetail("tradingview_order_qty", primary.quantity());
        LiveSignalContext.putDetail("tradingview_order_count", orders.size());
        LiveSignalContext.putDetail("tradingview_order_reasons", orders.stream()
                .map(LiveSignalContext.OrderIntent::reason)
                .collect(Collectors.joining(",")));
        LiveSignalContext.putDetail("tradingview_order_qtys", orders.stream()
                .map(order -> String.valueOf(order.quantity()))
                .collect(Collectors.joining(",")));
    }

    private double[] series(Map<String, double[]> indicators,
                            TradingViewScoreBuyModel.Series replay,
                            String key) {
        double[] existing = indicators.get(key);
        if (existing != null) {
            return existing;
        }
        if (replay == null) {
            return null;
        }
        return switch (key) {
            case TradingViewScoreBuyModel.NN_OUTPUT_KEY -> replay.nnOutput();
            case TradingViewScoreBuyModel.NN_SUM_KEY -> replay.inputSum();
            case TradingViewScoreBuyModel.NN_WEIGHT_KEY -> replay.weight();
            case TradingViewScoreBuyModel.NN_BIAS_KEY -> replay.bias();
            case TradingViewScoreBuyModel.HISTORY_COMPLETE_KEY -> replay.historyComplete();
            default -> null;
        };
    }

    private double minLow(List<MdKline> klines, int start, int end) {
        double result = Double.POSITIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            result = Math.min(result, klines.get(i).getLowPrice().doubleValue());
        }
        return result;
    }

    private double valueAt(double[] values, int index) {
        return valueAt(values, index, Double.NaN);
    }

    private double valueAt(double[] values, int index, double defaultValue) {
        return values == null || index < 0 || index >= values.length ? defaultValue : values[index];
    }

    private Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private void record(BacktestDiagnosticCollector diagnostic,
                        String code,
                        StrategyContext context,
                        String detail) {
        if (diagnostic != null && context != null && context.getCurrent() != null) {
            diagnostic.record(code, context.getCurrent().getOpenTime(), detail);
        }
    }

    private int getInt(Map<String, Object> config, String key, int defaultValue) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private double getDouble(Map<String, Object> config, String key, double defaultValue) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? defaultValue : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private boolean getBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config == null ? null : config.get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }
}
