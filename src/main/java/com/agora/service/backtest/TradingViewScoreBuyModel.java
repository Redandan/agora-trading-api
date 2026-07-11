package com.agora.service.backtest;

import com.agora.model.MdKline;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Deterministic replay of the online-learning network in strategy 485's Pine source. */
public final class TradingViewScoreBuyModel {

    public static final LocalDateTime BTCUSDT_1D_REPLAY_START_UTC =
            LocalDateTime.of(2017, 8, 17, 0, 0);
    public static final String REPLAY_START_CONFIG = "tradingViewReplayStartTime";
    public static final String REQUIRE_FULL_HISTORY_CONFIG = "tradingViewRequireFullHistory";
    public static final String NN_OUTPUT_KEY = "tradingViewNnOutput";
    public static final String NN_SUM_KEY = "tradingViewNnInputSum";
    public static final String NN_WEIGHT_KEY = "tradingViewNnWeight";
    public static final String NN_BIAS_KEY = "tradingViewNnBias";
    public static final String HISTORY_COMPLETE_KEY = "tradingViewHistoryComplete";

    private static final int INPUT_COUNT = 8;
    private static final int YEAR_LOOKBACK = 252;

    private TradingViewScoreBuyModel() {
    }

    public static Series replay(List<MdKline> klines,
                                Map<String, double[]> indicators,
                                Map<String, Object> config) {
        int size = klines == null ? 0 : klines.size();
        double[] output = nanArray(size);
        double[] inputSum = nanArray(size);
        double[] weight = nanArray(size);
        double[] biasSeries = nanArray(size);
        double[] historyCompleteSeries = new double[size];

        boolean historyComplete = hasCompleteReplayHistory(klines, config);
        Arrays.fill(historyCompleteSeries, historyComplete ? 1.0 : 0.0);
        if (size == 0) {
            return new Series(output, inputSum, weight, biasSeries, historyCompleteSeries, historyComplete);
        }

        double[] rsi = indicator(indicators, "rsi");
        double[] bollUpper = indicator(indicators, "bollUp");
        double[] bollLower = indicator(indicators, "bollLow");
        double[] volumeAverage = indicator(indicators, "volumeMa20");
        double[] macdLine = indicator(indicators, "macdLine");
        double[] macdSignal = indicator(indicators, "macdSignal");

        double learningRate = getBoolean(config, "tradingViewParityMode", false)
                ? 0.01
                : getDouble(config, "learningRate", 0.01);
        double[] weights = new double[INPUT_COUNT];
        Arrays.fill(weights, 0.5);
        double bias = 0.0;
        double previousClose = Double.NaN;
        double previousOutput = Double.NaN;
        int consecutiveDownDays = 0;
        Deque<Integer> yearHighIndices = new ArrayDeque<>();

        for (int i = 0; i < size; i++) {
            MdKline bar = klines.get(i);
            double close = bar.getClosePrice().doubleValue();
            double volume = bar.getVolume().doubleValue();
            double high = bar.getHighPrice().doubleValue();

            while (!yearHighIndices.isEmpty()
                    && yearHighIndices.peekFirst() < i - YEAR_LOOKBACK + 1) {
                yearHighIndices.removeFirst();
            }
            while (!yearHighIndices.isEmpty()
                    && klines.get(yearHighIndices.peekLast()).getHighPrice().doubleValue() <= high) {
                yearHighIndices.removeLast();
            }
            yearHighIndices.addLast(i);

            if (i > 0 && close < klines.get(i - 1).getClosePrice().doubleValue()) {
                consecutiveDownDays++;
            } else {
                consecutiveDownDays = 0;
            }

            weight[i] = weights[0];
            biasSeries[i] = bias;

            if (i > 0 && valuesAvailable(i, rsi, bollUpper, bollLower,
                    volumeAverage, macdLine, macdSignal)) {
                double previousBarClose = klines.get(i - 1).getClosePrice().doubleValue();
                double previousVolume = klines.get(i - 1).getVolume().doubleValue();
                double yearHigh = klines.get(yearHighIndices.peekFirst()).getHighPrice().doubleValue();
                double priceChangeRate = (close - previousBarClose) / previousBarClose * 100.0;
                double volumeChangeRate = (volume - previousVolume) / previousVolume * 100.0;

                double[] inputs = new double[]{
                        rsi[i] / 100.0,
                        (close - bollLower[i]) / (bollUpper[i] - bollLower[i]),
                        volume / volumeAverage[i],
                        (macdLine[i] - macdSignal[i]) / close * 100.0,
                        Math.min(consecutiveDownDays, 10) / 10.0,
                        Math.min((yearHigh - close) / yearHigh * 100.0, 50.0) / 50.0,
                        Math.min(Math.abs(priceChangeRate), 10.0) / 10.0,
                        Math.min(volumeChangeRate, 100.0) / 100.0
                };

                if (allFinite(inputs)) {
                    double sum = bias;
                    for (int inputIndex = 0; inputIndex < INPUT_COUNT; inputIndex++) {
                        sum += inputs[inputIndex] * weights[inputIndex];
                    }
                    inputSum[i] = sum;
                    output[i] = sigmoid(sum);
                }
            }

            // Pine calculates nnOutput first, then trains on the previous prediction.
            if (Double.isFinite(previousClose) && Double.isFinite(previousOutput)) {
                double realOutput = close > previousClose ? 1.0 : 0.0;
                double error = realOutput - previousOutput;
                for (int inputIndex = 0; inputIndex < INPUT_COUNT; inputIndex++) {
                    weights[inputIndex] = weights[inputIndex]
                            + learningRate * error * weights[inputIndex];
                }
                bias += learningRate * error;
            }

            previousClose = close;
            previousOutput = output[i];
        }

        return new Series(output, inputSum, weight, biasSeries, historyCompleteSeries, historyComplete);
    }

    public static boolean hasCompleteReplayHistory(List<MdKline> klines, Map<String, Object> config) {
        if (!getBoolean(config, REQUIRE_FULL_HISTORY_CONFIG, false)) {
            return true;
        }
        LocalDateTime replayStart = parseDateTime(config == null ? null : config.get(REPLAY_START_CONFIG));
        if (replayStart == null || klines == null || klines.isEmpty()) {
            return false;
        }

        int anchorIndex = -1;
        for (int i = 0; i < klines.size(); i++) {
            if (replayStart.equals(klines.get(i).getOpenTime())) {
                anchorIndex = i;
                break;
            }
        }
        if (anchorIndex < 0) {
            return false;
        }

        Duration interval = intervalDuration(getString(config, "runIntervalCode", "1d"));
        if (interval == null) {
            return false;
        }
        for (int i = anchorIndex + 1; i < klines.size(); i++) {
            LocalDateTime expected = klines.get(i - 1).getOpenTime().plus(interval);
            if (!expected.equals(klines.get(i).getOpenTime())) {
                return false;
            }
        }
        return true;
    }

    private static double[] indicator(Map<String, double[]> indicators, String name) {
        return indicators == null ? null : indicators.get(name);
    }

    private static boolean valuesAvailable(int index, double[]... values) {
        for (double[] series : values) {
            if (series == null || index >= series.length || !Double.isFinite(series[index])) {
                return false;
            }
        }
        return true;
    }

    private static boolean allFinite(double[] values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static double sigmoid(double value) {
        return 1.0 / (1.0 + Math.exp(-value));
    }

    private static double[] nanArray(int size) {
        double[] values = new double[size];
        Arrays.fill(values, Double.NaN);
        return values;
    }

    private static LocalDateTime parseDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Duration intervalDuration(String intervalCode) {
        if (intervalCode == null) {
            return null;
        }
        String code = intervalCode.trim().toLowerCase();
        try {
            if (code.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(code.substring(0, code.length() - 1)));
            }
            if (code.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(code.substring(0, code.length() - 1)));
            }
            if (code.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(code.substring(0, code.length() - 1)));
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static double getDouble(Map<String, Object> config, String key, double defaultValue) {
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

    private static boolean getBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config == null ? null : config.get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String getString(Map<String, Object> config, String key, String defaultValue) {
        Object value = config == null ? null : config.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public record Series(double[] nnOutput,
                         double[] inputSum,
                         double[] weight,
                         double[] bias,
                         double[] historyComplete,
                         boolean completeReplayHistory) {
    }
}
