package com.agora.service.backtest;

import com.agora.model.MdKline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreBuyStrategyTest {

    private final ScoreBuyStrategy strategy = new ScoreBuyStrategy();

    @AfterEach
    void clearContext() {
        LiveSignalContext.clear();
    }

    @Test
    void relativeLowTriggersTradingViewRelativeLowOrderWithoutFullBuySignal() {
        List<MdKline> bars = bars(30, 100, 102, 100, 101, 1000);
        bars.get(0).setLowPrice(bd(90));
        bars.set(25, bar(25, 101, 102, 99, 101, 1000));

        StrategySignal signal = strategy.evaluate(context(bars, indicators(30, 80, 200, 100, 1000), 25),
                config());

        assertThat(signal).isEqualTo(StrategySignal.BUY);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("tradingview_order_reason", ScoreBuyStrategy.ORDER_RELATIVE_LOW)
                .containsEntry("tradingview_order_qty", 1000.0)
                .containsEntry("tradingview_order_count", 1)
                .containsEntry("tradingview_buy_signal", false)
                .containsEntry("tradingview_is_relative_low", true)
                .containsEntry("tradingview_is_potential_low", false);
        assertThat(LiveSignalContext.getOrderIntents())
                .extracting(LiveSignalContext.OrderIntent::reason)
                .containsExactly(ScoreBuyStrategy.ORDER_RELATIVE_LOW);
    }

    @Test
    void potentialLowTriggersTradingViewPotentialLowOrderWithoutFullBuySignal() {
        List<MdKline> bars = bars(30, 100, 102, 100, 101, 1000);
        bars.get(0).setLowPrice(bd(80));
        bars.set(25, bar(25, 90, 95, 79, 90, 1000));

        StrategySignal signal = strategy.evaluate(context(bars, indicators(30, 80, 200, 100, 1000), 25),
                config());

        assertThat(signal).isEqualTo(StrategySignal.BUY);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("tradingview_order_reason", ScoreBuyStrategy.ORDER_POTENTIAL_LOW)
                .containsEntry("tradingview_order_qty", 2000.0)
                .containsEntry("tradingview_order_count", 1)
                .containsEntry("tradingview_buy_signal", false)
                .containsEntry("tradingview_is_relative_low", false)
                .containsEntry("tradingview_is_potential_low", true);
        assertThat(LiveSignalContext.getOrderIntents())
                .extracting(LiveSignalContext.OrderIntent::reason)
                .containsExactly(ScoreBuyStrategy.ORDER_POTENTIAL_LOW);
    }

    @Test
    void fullTradingViewBuySignalEmitsAllPineOrdersInScriptOrder() {
        List<MdKline> bars = bars(30, 100, 102, 100, 101, 1000);
        bars.get(0).setLowPrice(bd(90));
        bars.set(25, bar(25, 101, 102, 89, 101, 2500));

        Map<String, double[]> indicators = indicators(30, 35, 140, 100, 1000);
        Map<String, Object> config = config();
        config.put("buyThreshold", 0.10);
        config.put("scoreScale", 8.0);
        config.put("scoreShift", 1.0);

        StrategySignal signal = strategy.evaluate(context(bars, indicators, 25), config);

        assertThat(signal).isEqualTo(StrategySignal.BUY);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("tradingview_order_reason", ScoreBuyStrategy.ORDER_AI_BUY)
                .containsEntry("tradingview_order_qty", 5000.0)
                .containsEntry("tradingview_order_count", 3)
                .containsEntry("tradingview_order_reasons",
                        "TRADINGVIEW_AI_BUY_SIGNAL,TRADINGVIEW_RELATIVE_LOW,TRADINGVIEW_POTENTIAL_LOW")
                .containsEntry("tradingview_buy_signal", true)
                .containsEntry("tradingview_near_lower_bb", true)
                .containsEntry("tradingview_volume_breakout", true);
        assertThat(LiveSignalContext.getOrderIntents())
                .extracting(LiveSignalContext.OrderIntent::reason)
                .containsExactly(
                        ScoreBuyStrategy.ORDER_AI_BUY,
                        ScoreBuyStrategy.ORDER_RELATIVE_LOW,
                        ScoreBuyStrategy.ORDER_POTENTIAL_LOW);
    }

    private StrategyContext context(List<MdKline> bars, Map<String, double[]> indicators, int index) {
        LiveSignalContext.clear();
        return new StrategyContext(index, bars.get(index), bars.get(index - 1), bars, indicators);
    }

    private Map<String, Object> config() {
        Map<String, Object> config = new HashMap<>();
        config.put("minWarmupBars", 5);
        config.put("shortLookbackBars", 20);
        config.put("medLookbackBars", 63);
        config.put("yearLookbackBars", 252);
        config.put("rsiOversold", 40.0);
        config.put("volumeBreakoutMultiplier", 1.5);
        return config;
    }

    private Map<String, double[]> indicators(int size, double rsi, double bollMid, double bollLow, double volMa20) {
        Map<String, double[]> indicators = new HashMap<>();
        indicators.put("rsi", fill(size, rsi));
        indicators.put("bollMid", fill(size, bollMid));
        indicators.put("bollUp", fill(size, bollMid + 40));
        indicators.put("bollLow", fill(size, bollLow));
        indicators.put("sma200", fill(size, 200));
        indicators.put("volumeMa20", fill(size, volMa20));
        indicators.put("macdLine", fill(size, 1));
        indicators.put("macdSignal", fill(size, 1));
        return indicators;
    }

    private double[] fill(int size, double value) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = value;
        }
        return values;
    }

    private List<MdKline> bars(int size, double open, double high, double low, double close, double volume) {
        List<MdKline> bars = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            bars.add(bar(i, open, high, low, close, volume));
        }
        return bars;
    }

    private MdKline bar(int offset, double open, double high, double low, double close, double volume) {
        MdKline bar = new MdKline();
        bar.setSymbol("BTCUSDT");
        bar.setIntervalCode("1D");
        bar.setOpenTime(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(offset));
        bar.setCloseTime(bar.getOpenTime().plusDays(1));
        bar.setOpenPrice(bd(open));
        bar.setHighPrice(bd(high));
        bar.setLowPrice(bd(low));
        bar.setClosePrice(bd(close));
        bar.setVolume(bd(volume));
        return bar;
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }
}
