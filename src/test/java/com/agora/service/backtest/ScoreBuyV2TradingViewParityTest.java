package com.agora.service.backtest;

import com.agora.model.MdKline;
import com.agora.service.ml.MlTrainingOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ScoreBuyV2TradingViewParityTest {

    @AfterEach
    void clearContext() {
        LiveSignalContext.clear();
    }

    @Test
    void defaultsToTradingViewParityInsteadOfLegacyMlGate() {
        ScoreBuyV2Strategy strategy = new ScoreBuyV2Strategy(
                mock(JdbcTemplate.class),
                mock(MlTrainingOrchestrator.class),
                new ObjectMapper(),
                new ScoreBuyStrategy());

        List<MdKline> bars = bars(30, 100, 102, 100, 101, 1000);
        bars.set(25, bar(25, 101, 102, 99, 101, 1200));

        Map<String, Object> legacyV2Config = new HashMap<>();
        legacyV2Config.put("minWarmupBars", 5);
        legacyV2Config.put("rsiOversold", 35.0);
        legacyV2Config.put("buyThreshold", 0.33);
        legacyV2Config.put("yearLookbackBars", 8760);
        legacyV2Config.put("volumeBreakoutMultiplier", 1.3);
        legacyV2Config.put("tradingViewAllowIncompleteHistoryShadowIntents", true);

        StrategySignal signal = strategy.evaluate(
                context(bars, indicators(30, 38, 140, 100, 1000), 25),
                legacyV2Config);

        assertThat(signal).isEqualTo(StrategySignal.BUY);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("tradingview_order_reason", ScoreBuyStrategy.ORDER_RELATIVE_LOW)
                .containsEntry("tradingview_order_count", 2)
                .containsEntry("tradingview_rsi_oversold", 40.0)
                .containsEntry("tradingview_volume_threshold", 1.5);
        assertThat(LiveSignalContext.getOrderIntents())
                .extracting(LiveSignalContext.OrderIntent::reason)
                .containsExactly(
                        ScoreBuyStrategy.ORDER_RELATIVE_LOW,
                        ScoreBuyStrategy.ORDER_POTENTIAL_LOW);
    }

    private StrategyContext context(List<MdKline> bars, Map<String, double[]> indicators, int index) {
        LiveSignalContext.clear();
        return new StrategyContext(index, bars.get(index), bars.get(index - 1), bars, indicators);
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
        bar.setIntervalCode("1d");
        bar.setOpenTime(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(offset));
        bar.setCloseTime(bar.getOpenTime().plusDays(1));
        bar.setOpenPrice(BigDecimal.valueOf(open));
        bar.setHighPrice(BigDecimal.valueOf(high));
        bar.setLowPrice(BigDecimal.valueOf(low));
        bar.setClosePrice(BigDecimal.valueOf(close));
        bar.setVolume(BigDecimal.valueOf(volume));
        return bar;
    }
}
