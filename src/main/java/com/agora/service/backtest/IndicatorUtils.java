package com.agora.service.backtest;

import java.util.Arrays;

public final class IndicatorUtils {

    private IndicatorUtils() {
    }

    public static double[] ema(double[] values, int period) {
        double[] output = new double[values.length];
        Arrays.fill(output, Double.NaN);
        if (period <= 0 || values.length < period) {
            return output;
        }

        double sum = 0.0;
        int i;
        for (i = 0; i < period; i++) {
            sum += values[i];
        }

        double emaPrev = sum / period;
        output[period - 1] = emaPrev;
        double multiplier = 2.0 / (period + 1.0);

        for (i = period; i < values.length; i++) {
            double emaValue = (values[i] - emaPrev) * multiplier + emaPrev;
            output[i] = emaValue;
            emaPrev = emaValue;
        }

        return output;
    }

    public static double[] sma(double[] values, int period) {
        double[] output = new double[values.length];
        Arrays.fill(output, Double.NaN);
        if (period <= 0 || values.length < period) {
            return output;
        }

        double sum = 0.0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= period) {
                sum -= values[i - period];
            }
            if (i >= period - 1) {
                output[i] = sum / period;
            }
        }
        return output;
    }

    public static double[] stddev(double[] values, int period) {
        double[] output = new double[values.length];
        Arrays.fill(output, Double.NaN);
        if (period <= 0 || values.length < period) {
            return output;
        }

        for (int i = period - 1; i < values.length; i++) {
            double mean = 0.0;
            for (int j = i - period + 1; j <= i; j++) {
                mean += values[j];
            }
            mean /= period;

            double variance = 0.0;
            for (int j = i - period + 1; j <= i; j++) {
                double d = values[j] - mean;
                variance += d * d;
            }
            output[i] = Math.sqrt(variance / period);
        }
        return output;
    }

    public static double[] bollingerMiddle(double[] values, int period) {
        return sma(values, period);
    }

    public static double[] bollingerUpper(double[] values, int period, double stdMultiplier) {
        double[] middle = sma(values, period);
        double[] dev = stddev(values, period);
        double[] upper = new double[values.length];
        Arrays.fill(upper, Double.NaN);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(middle[i]) && !Double.isNaN(dev[i])) {
                upper[i] = middle[i] + stdMultiplier * dev[i];
            }
        }
        return upper;
    }

    public static double[] bollingerLower(double[] values, int period, double stdMultiplier) {
        double[] middle = sma(values, period);
        double[] dev = stddev(values, period);
        double[] lower = new double[values.length];
        Arrays.fill(lower, Double.NaN);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(middle[i]) && !Double.isNaN(dev[i])) {
                lower[i] = middle[i] - stdMultiplier * dev[i];
            }
        }
        return lower;
    }

    public static double[] macdLine(double[] values, int fastPeriod, int slowPeriod) {
        double[] fast = ema(values, fastPeriod);
        double[] slow = ema(values, slowPeriod);
        double[] output = new double[values.length];
        Arrays.fill(output, Double.NaN);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(fast[i]) && !Double.isNaN(slow[i])) {
                output[i] = fast[i] - slow[i];
            }
        }
        return output;
    }

    public static double[] macdSignal(double[] macdLine, int signalPeriod) {
        double[] signal = new double[macdLine.length];
        Arrays.fill(signal, Double.NaN);
        if (signalPeriod <= 0 || macdLine.length < signalPeriod) {
            return signal;
        }

        int firstValid = -1;
        for (int i = 0; i < macdLine.length; i++) {
            if (!Double.isNaN(macdLine[i])) {
                firstValid = i;
                break;
            }
        }
        if (firstValid < 0 || firstValid + signalPeriod > macdLine.length) {
            return signal;
        }

        double sum = 0.0;
        for (int i = firstValid; i < firstValid + signalPeriod; i++) {
            sum += macdLine[i];
        }

        int seedIndex = firstValid + signalPeriod - 1;
        double emaPrev = sum / signalPeriod;
        signal[seedIndex] = emaPrev;
        double multiplier = 2.0 / (signalPeriod + 1.0);

        for (int i = seedIndex + 1; i < macdLine.length; i++) {
            if (Double.isNaN(macdLine[i])) {
                continue;
            }
            emaPrev = (macdLine[i] - emaPrev) * multiplier + emaPrev;
            signal[i] = emaPrev;
        }
        return signal;
    }

    public static double[] adx(double[] high, double[] low, double[] close, int period) {
        int n = close.length;
        double[] adx = new double[n];
        Arrays.fill(adx, Double.NaN);
        if (period <= 0 || n <= period) {
            return adx;
        }

        double[] tr = new double[n];
        double[] plusDm = new double[n];
        double[] minusDm = new double[n];

        for (int i = 1; i < n; i++) {
            double upMove = high[i] - high[i - 1];
            double downMove = low[i - 1] - low[i];

            plusDm[i] = (upMove > downMove && upMove > 0) ? upMove : 0.0;
            minusDm[i] = (downMove > upMove && downMove > 0) ? downMove : 0.0;

            double highLow = high[i] - low[i];
            double highClose = Math.abs(high[i] - close[i - 1]);
            double lowClose = Math.abs(low[i] - close[i - 1]);
            tr[i] = Math.max(highLow, Math.max(highClose, lowClose));
        }

        double trSum = 0.0;
        double plusDmSum = 0.0;
        double minusDmSum = 0.0;
        for (int i = 1; i <= period; i++) {
            trSum += tr[i];
            plusDmSum += plusDm[i];
            minusDmSum += minusDm[i];
        }

        double[] dx = new double[n];
        Arrays.fill(dx, Double.NaN);

        for (int i = period + 1; i < n; i++) {
            trSum = trSum - (trSum / period) + tr[i];
            plusDmSum = plusDmSum - (plusDmSum / period) + plusDm[i];
            minusDmSum = minusDmSum - (minusDmSum / period) + minusDm[i];

            if (trSum == 0.0) {
                continue;
            }

            double plusDi = 100.0 * (plusDmSum / trSum);
            double minusDi = 100.0 * (minusDmSum / trSum);
            double denom = plusDi + minusDi;
            if (denom == 0.0) {
                continue;
            }
            dx[i] = 100.0 * Math.abs(plusDi - minusDi) / denom;
        }

        double dxAvg = 0.0;
        int count = 0;
        for (int i = period + 1; i < n && count < period; i++) {
            if (!Double.isNaN(dx[i])) {
                dxAvg += dx[i];
                count++;
            }
        }
        if (count < period) {
            return adx;
        }

        int firstAdxIndex = period * 2;
        if (firstAdxIndex >= n) {
            return adx;
        }
        adx[firstAdxIndex] = dxAvg / period;

        for (int i = firstAdxIndex + 1; i < n; i++) {
            if (Double.isNaN(dx[i]) || Double.isNaN(adx[i - 1])) {
                continue;
            }
            adx[i] = ((adx[i - 1] * (period - 1)) + dx[i]) / period;
        }

        return adx;
    }

    public static double[] rsi(double[] closePrices, int period) {
        double[] output = new double[closePrices.length];
        Arrays.fill(output, Double.NaN);
        if (period <= 0 || closePrices.length <= period) {
            return output;
        }

        double gain = 0.0;
        double loss = 0.0;
        int i;
        for (i = 1; i <= period; i++) {
            double delta = closePrices[i] - closePrices[i - 1];
            if (delta >= 0) {
                gain += delta;
            } else {
                loss -= delta;
            }
        }

        double avgGain = gain / period;
        double avgLoss = loss / period;
        output[period] = calculateRsi(avgGain, avgLoss);

        for (i = period + 1; i < closePrices.length; i++) {
            double delta = closePrices[i] - closePrices[i - 1];
            double currentGain = Math.max(delta, 0.0);
            double currentLoss = Math.max(-delta, 0.0);
            avgGain = ((avgGain * (period - 1)) + currentGain) / period;
            avgLoss = ((avgLoss * (period - 1)) + currentLoss) / period;
            output[i] = calculateRsi(avgGain, avgLoss);
        }

        return output;
    }

    private static double calculateRsi(double avgGain, double avgLoss) {
        if (avgLoss == 0.0) {
            return 100.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * Average True Range (ATR) using Wilder's smoothing (factor = 1/period).
     * Returns NaN for indices where there is insufficient data.
     */
    public static double[] atr(double[] high, double[] low, double[] close, int period) {
        int n = close.length;
        double[] atrValues = new double[n];
        Arrays.fill(atrValues, Double.NaN);
        if (period <= 0 || n <= period) {
            return atrValues;
        }

        double[] tr = new double[n];
        tr[0] = high[0] - low[0];
        for (int i = 1; i < n; i++) {
            double hl = high[i] - low[i];
            double hc = Math.abs(high[i] - close[i - 1]);
            double lc = Math.abs(low[i] - close[i - 1]);
            tr[i] = Math.max(hl, Math.max(hc, lc));
        }

        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += tr[i];
        }
        atrValues[period - 1] = sum / period;

        for (int i = period; i < n; i++) {
            atrValues[i] = (atrValues[i - 1] * (period - 1) + tr[i]) / period;
        }

        return atrValues;
    }
}
