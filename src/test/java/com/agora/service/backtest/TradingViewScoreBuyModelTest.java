package com.agora.service.backtest;

import com.agora.model.MdKline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TradingViewScoreBuyModelTest {

    @Test
    void replaysPineOutputBeforeApplyingPreviousPredictionUpdate() {
        List<MdKline> bars = List.of(
                bar(0, 100),
                bar(1, 90),
                bar(2, 99),
                bar(3, 98));
        Map<String, double[]> indicators = indicators(4);

        TradingViewScoreBuyModel.Series result = TradingViewScoreBuyModel.replay(
                bars, indicators, Map.of("learningRate", 0.01));

        double firstValidSum = 1.625;
        double firstValidOutput = sigmoid(firstValidSum);
        double secondValidSum = 1.5075;
        double secondValidOutput = sigmoid(secondValidSum);
        double updatedWeight = 0.5 + 0.01 * (1.0 - firstValidOutput) * 0.5;
        double updatedBias = 0.01 * (1.0 - firstValidOutput);
        double thirdInputTotal = 0.5 + 0.49 + 1.0 + 0.0 + 0.1 + 0.04
                + (Math.abs(98.0 - 99.0) / 99.0 * 100.0 / 10.0) + 0.0;
        double thirdValidOutput = sigmoid(thirdInputTotal * updatedWeight + updatedBias);

        assertThat(result.nnOutput()[0]).isNaN();
        assertThat(result.nnOutput()[1]).isCloseTo(firstValidOutput, within(1e-12));
        assertThat(result.nnOutput()[2]).isCloseTo(secondValidOutput, within(1e-12));
        assertThat(result.weight()[2]).isEqualTo(0.5);
        assertThat(result.weight()[3]).isCloseTo(updatedWeight, within(1e-12));
        assertThat(result.bias()[3]).isCloseTo(updatedBias, within(1e-12));
        assertThat(result.nnOutput()[3]).isCloseTo(thirdValidOutput, within(1e-12));
    }

    @Test
    void fullHistoryRequiresExactAnchorAndContinuousDailyBars() {
        LocalDateTime anchor = TradingViewScoreBuyModel.BTCUSDT_1D_REPLAY_START_UTC;
        Map<String, Object> config = Map.of(
                TradingViewScoreBuyModel.REQUIRE_FULL_HISTORY_CONFIG, true,
                TradingViewScoreBuyModel.REPLAY_START_CONFIG, anchor.toString(),
                "runIntervalCode", "1d");

        List<MdKline> continuous = List.of(barAt(anchor), barAt(anchor.plusDays(1)), barAt(anchor.plusDays(2)));
        List<MdKline> gap = List.of(barAt(anchor), barAt(anchor.plusDays(2)));
        List<MdKline> lateStart = List.of(barAt(anchor.plusDays(1)), barAt(anchor.plusDays(2)));

        assertThat(TradingViewScoreBuyModel.hasCompleteReplayHistory(continuous, config)).isTrue();
        assertThat(TradingViewScoreBuyModel.hasCompleteReplayHistory(gap, config)).isFalse();
        assertThat(TradingViewScoreBuyModel.hasCompleteReplayHistory(lateStart, config)).isFalse();
    }

    @Test
    void parityModePinsLearningRateAndIndicatorPeriodsToCapturedPineSource() {
        List<MdKline> bars = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            MdKline bar = barAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(i));
            bar.setClosePrice(BigDecimal.valueOf(100 + Math.sin(i / 3.0) * 10 + i));
            bar.setHighPrice(bar.getClosePrice().add(BigDecimal.ONE));
            bar.setLowPrice(bar.getClosePrice().subtract(BigDecimal.ONE));
            bar.setVolume(BigDecimal.valueOf(100 + i * 5L));
            bars.add(bar);
        }

        Map<String, Object> overridden = new HashMap<>();
        overridden.put("tradingViewParityMode", true);
        overridden.put("learningRate", 50.0);
        overridden.put("rsiPeriod", 2);
        overridden.put("bollPeriod", 2);
        overridden.put("bollStd", 9.0);
        overridden.put("macdFast", 2);
        overridden.put("macdSlow", 3);
        overridden.put("macdSignal", 2);

        BacktestEngine engine = new BacktestEngine();
        Map<String, double[]> actualIndicators = engine.buildIndicators(bars, overridden);
        double[] closes = bars.stream().mapToDouble(bar -> bar.getClosePrice().doubleValue()).toArray();
        double[] expectedRsi = IndicatorUtils.rsi(closes, 14);
        double[] expectedBollinger = IndicatorUtils.bollingerUpper(closes, 20, 2.0);
        double[] expectedMacdLine = IndicatorUtils.macdLine(closes, 12, 26);
        double[] expectedMacdSignal = IndicatorUtils.macdSignal(expectedMacdLine, 9);

        assertThat(actualIndicators.get("rsi")[39]).isCloseTo(expectedRsi[39], within(1e-12));
        assertThat(actualIndicators.get("bollUp")[39]).isCloseTo(expectedBollinger[39], within(1e-12));
        assertThat(actualIndicators.get("macdLine")[39]).isCloseTo(expectedMacdLine[39], within(1e-12));
        assertThat(actualIndicators.get("macdSignal")[39]).isCloseTo(expectedMacdSignal[39], within(1e-12));

        Map<String, Object> capturedRate = new HashMap<>(overridden);
        capturedRate.put("learningRate", 0.01);
        TradingViewScoreBuyModel.Series withOverride = TradingViewScoreBuyModel.replay(
                bars, actualIndicators, overridden);
        TradingViewScoreBuyModel.Series withCapturedRate = TradingViewScoreBuyModel.replay(
                bars, actualIndicators, capturedRate);
        assertThat(withOverride.nnOutput()).containsExactly(withCapturedRate.nnOutput());
    }

    @Test
    void parityModeWaitsForComplete252BarYearHighWarmup() {
        List<MdKline> bars = new ArrayList<>();
        for (int i = 0; i <= 252; i++) {
            bars.add(barAt(LocalDateTime.of(2025, 1, 1, 0, 0).plusDays(i)));
        }

        TradingViewScoreBuyModel.Series result = TradingViewScoreBuyModel.replay(
                bars, indicators(bars.size()), Map.of("tradingViewParityMode", true));

        assertThat(result.nnOutput()[251]).isNaN();
        assertThat(result.nnOutput()[252]).isFinite();
    }

    private Map<String, double[]> indicators(int size) {
        Map<String, double[]> values = new HashMap<>();
        values.put("rsi", fill(size, 50));
        values.put("bollUp", fill(size, 200));
        values.put("bollLow", fill(size, 0));
        values.put("volumeMa20", fill(size, 100));
        values.put("macdLine", fill(size, 0));
        values.put("macdSignal", fill(size, 0));
        return values;
    }

    private double[] fill(int size, double value) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = value;
        }
        return values;
    }

    private MdKline bar(int day, double close) {
        MdKline bar = barAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(day));
        bar.setHighPrice(BigDecimal.valueOf(100));
        bar.setClosePrice(BigDecimal.valueOf(close));
        return bar;
    }

    private MdKline barAt(LocalDateTime openTime) {
        MdKline bar = new MdKline();
        bar.setSymbol("BTCUSDT");
        bar.setIntervalCode("1d");
        bar.setSource("binance");
        bar.setOpenTime(openTime);
        bar.setCloseTime(openTime.plusDays(1));
        bar.setOpenPrice(BigDecimal.valueOf(100));
        bar.setHighPrice(BigDecimal.valueOf(100));
        bar.setLowPrice(BigDecimal.valueOf(80));
        bar.setClosePrice(BigDecimal.valueOf(100));
        bar.setVolume(BigDecimal.valueOf(100));
        return bar;
    }

    private double sigmoid(double value) {
        return 1.0 / (1.0 + Math.exp(-value));
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
