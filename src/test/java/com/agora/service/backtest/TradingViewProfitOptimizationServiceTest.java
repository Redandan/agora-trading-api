package com.agora.service.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradingViewProfitOptimizationServiceTest {

    private final TradingViewProfitOptimizationService service = new TradingViewProfitOptimizationService();

    @Test
    void reportUsesProductionBaselineFixedWindowsAndRejectsUnprofitableAggregateCandidate() {
        LocalDateTime start = LocalDateTime.parse("2025-01-01T00:00:00");
        List<BtcBaseShadowBacktestSimulator.Bar> bars = new ArrayList<>();
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = new ArrayList<>();
        for (int day = 0; day <= 365; day++) {
            LocalDateTime time = start.plusDays(day);
            bars.add(new BtcBaseShadowBacktestSimulator.Bar(time, 200.0 - day * 0.25));
            if (day % 30 == 0) {
                intents.add(intent(time, "AI_BUY"));
                intents.add(intent(time, "RELATIVE_LOW"));
            }
        }

        String report = service.compareAggregateCandidate("BTCUSDT", "binance", 0.001, bars, intents);

        assertThat(report)
                .contains("boundary=READ_ONLY")
                .contains("baseline=LIVE_ONE_ORDER_PER_BAR candidate=SHADOW_AGGREGATE_PER_BAR")
                .contains("buyPointPolicy=PRESERVE_ALL_TRADINGVIEW_INTENTS")
                .contains("window=90d")
                .contains("window=180d")
                .contains("window=270d")
                .contains("window=365d")
                .contains("walkForwardPositiveFolds=")
                .contains("candidateVerdict=REJECTED")
                .contains("candidatePromotionAllowed=false")
                .contains("nextCandidate=DEEP_DROP_TIERED_ADD_SHADOW_ONLY");
    }

    private BtcBaseShadowBacktestSimulator.BuyIntent intent(LocalDateTime time, String reason) {
        return new BtcBaseShadowBacktestSimulator.BuyIntent(time, 1000.0, reason, reason, "BUY");
    }
}
