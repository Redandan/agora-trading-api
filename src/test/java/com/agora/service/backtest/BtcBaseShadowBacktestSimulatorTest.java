package com.agora.service.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BtcBaseShadowBacktestSimulatorTest {

    @Test
    void accumulatesTradingViewBuyIntentsUntilBaseExposureCapAndTakesProfitInTranches() {
        List<BtcBaseShadowBacktestSimulator.Bar> bars = List.of(
                bar("2026-01-01T00:00", 100.0),
                bar("2026-01-02T00:00", 90.0),
                bar("2026-01-03T00:00", 80.0),
                bar("2026-01-04T00:00", 70.0),
                bar("2026-01-05T00:00", 120.0));
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = List.of(
                intent("2026-01-01T00:00", "AI_BUY"),
                intent("2026-01-02T00:00", "RELATIVE_LOW"),
                intent("2026-01-03T00:00", "POTENTIAL_LOW"),
                intent("2026-01-04T00:00", "CAP_SKIP"));
        BtcBaseShadowBacktestSimulator.Config config =
                new BtcBaseShadowBacktestSimulator.Config(10.0, 25.0, 1.0, 0.001,
                        0.06, 0.50, 0.12, 0.0);

        BtcBaseShadowBacktestSimulator.Result result =
                BtcBaseShadowBacktestSimulator.run(bars, intents, config);

        assertThat(result.orderIntentCount()).isEqualTo(4);
        assertThat(result.executedBuys()).isEqualTo(3);
        assertThat(result.cappedBuys()).isEqualTo(1);
        assertThat(result.skippedByCap()).isEqualTo(1);
        assertThat(result.maxCostBasis()).isEqualTo(25.0);
        assertThat(result.takeProfitReductions()).isGreaterThanOrEqualTo(1);
        assertThat(result.totalPnl()).isPositive();
        assertThat(result.events()).extracting(BtcBaseShadowBacktestSimulator.Event::type)
                .contains("BUY", "SKIP_CAP", "REDUCE_TAKE_PROFIT");
    }

    @Test
    void reportsEmergencyDrawdownWithoutSellingWhenReductionFractionIsZero() {
        List<BtcBaseShadowBacktestSimulator.Bar> bars = List.of(
                bar("2026-01-01T00:00", 100.0),
                bar("2026-01-02T00:00", 80.0));
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = List.of(
                intent("2026-01-01T00:00", "AI_BUY"));
        BtcBaseShadowBacktestSimulator.Config config =
                new BtcBaseShadowBacktestSimulator.Config(10.0, 100.0, 1.0, 0.001,
                        0.0, 0.0, 0.12, 0.0);

        BtcBaseShadowBacktestSimulator.Result result =
                BtcBaseShadowBacktestSimulator.run(bars, intents, config);

        assertThat(result.executedBuys()).isEqualTo(1);
        assertThat(result.emergencyWarnings()).isEqualTo(1);
        assertThat(result.emergencyReductions()).isZero();
        assertThat(result.remainingCostBasis()).isEqualTo(10.0);
        assertThat(result.maxInventoryDrawdownPct()).isGreaterThan(0.12);
    }

    private static BtcBaseShadowBacktestSimulator.Bar bar(String time, double close) {
        return new BtcBaseShadowBacktestSimulator.Bar(LocalDateTime.parse(time), close);
    }

    private static BtcBaseShadowBacktestSimulator.BuyIntent intent(String time, String reason) {
        return new BtcBaseShadowBacktestSimulator.BuyIntent(
                LocalDateTime.parse(time), 1000.0, reason, reason + "_LABEL", "BUY");
    }
}
