package com.agora.service.backtest;

import com.agora.model.MdKline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestEngineTradingViewOrderIntentTest {

    @AfterEach
    void clearContext() {
        LiveSignalContext.clear();
    }

    @Test
    void tradeRecordCarriesTradingViewOrderIntentMetadata() {
        BacktestEngine engine = new BacktestEngine();
        Strategy strategy = new Strategy() {
            @Override
            public String getType() {
                return "TEST";
            }

            @Override
            public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
                if (context.getIndex() == 0) {
                    LiveSignalContext.addOrderIntent(
                            ScoreBuyStrategy.ORDER_AI_BUY, "AI买点买入", 5000);
                    LiveSignalContext.addOrderIntent(
                            ScoreBuyStrategy.ORDER_RELATIVE_LOW, "相对低点买入", 1000);
                    return StrategySignal.BUY;
                }
                return StrategySignal.HOLD;
            }
        };

        BacktestRunSummary summary = engine.run(
                List.of(
                        bar(0, 100, 101, 99, 100),
                        bar(1, 100, 102, 99, 101)),
                strategy,
                new HashMap<>(),
                new BigDecimal("10000"),
                BigDecimal.ZERO);

        assertThat(summary.getTrades()).hasSize(1);
        TradeRecord trade = summary.getTrades().get(0);
        assertThat(trade.getEntryReason()).isEqualTo(ScoreBuyStrategy.ORDER_AI_BUY);
        assertThat(trade.getEntryLabel()).isEqualTo("AI买点买入");
        assertThat(trade.getEntryRequestedQuantity()).isEqualTo(5000.0);
        assertThat(trade.getEntryOrderCount()).isEqualTo(2);
        assertThat(trade.getEntryOrderReasons())
                .isEqualTo("TRADINGVIEW_AI_BUY_SIGNAL,TRADINGVIEW_RELATIVE_LOW");
    }

    @Test
    void backtestTradeStartTimeBlocksWarmupEntriesOnly() {
        BacktestEngine engine = new BacktestEngine();
        Strategy strategy = new Strategy() {
            @Override
            public String getType() {
                return "TEST";
            }

            @Override
            public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
                if (context.getIndex() == 0 || context.getIndex() == 2) {
                    LiveSignalContext.addOrderIntent(
                            ScoreBuyStrategy.ORDER_RELATIVE_LOW, "相对低点买入", 1000);
                    return StrategySignal.BUY;
                }
                return StrategySignal.HOLD;
            }
        };

        Map<String, Object> config = new HashMap<>();
        config.put("backtestTradeStartTime", LocalDateTime.of(2026, 1, 3, 0, 0).toString());

        BacktestRunSummary summary = engine.run(
                List.of(
                        bar(0, 100, 101, 99, 100),
                        bar(1, 100, 101, 99, 100),
                        bar(2, 100, 102, 99, 101),
                        bar(3, 101, 103, 100, 102)),
                strategy,
                config,
                new BigDecimal("10000"),
                BigDecimal.ZERO);

        assertThat(summary.getTrades()).hasSize(1);
        assertThat(summary.getTrades().get(0).getEntryTime())
                .isEqualTo(LocalDateTime.of(2026, 1, 3, 0, 0));
        assertThat(summary.getTrades().get(0).getEntryReason())
                .isEqualTo(ScoreBuyStrategy.ORDER_RELATIVE_LOW);
    }

    private MdKline bar(int offset, double open, double high, double low, double close) {
        MdKline bar = new MdKline();
        bar.setSymbol("BTCUSDT");
        bar.setIntervalCode("1d");
        bar.setOpenTime(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(offset));
        bar.setCloseTime(bar.getOpenTime().plusDays(1));
        bar.setOpenPrice(BigDecimal.valueOf(open));
        bar.setHighPrice(BigDecimal.valueOf(high));
        bar.setLowPrice(BigDecimal.valueOf(low));
        bar.setClosePrice(BigDecimal.valueOf(close));
        bar.setVolume(BigDecimal.valueOf(1000));
        return bar;
    }
}
