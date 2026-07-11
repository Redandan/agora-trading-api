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

    @Test
    void liveSemanticsExecutesOneOrderPerBarAndKeepsAdditionalIntentsAsShadow() {
        List<BtcBaseShadowBacktestSimulator.Bar> bars = List.of(
                bar("2026-01-01T00:00", 100.0),
                bar("2026-01-02T00:00", 110.0));
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = List.of(
                intent("2026-01-01T00:00", "AI_BUY"),
                intent("2026-01-01T00:00", "RELATIVE_LOW"),
                intent("2026-01-01T00:00", "POTENTIAL_LOW"));

        BtcBaseShadowBacktestSimulator.Result result = BtcBaseShadowBacktestSimulator.run(
                bars, intents, new BtcBaseShadowBacktestSimulator.Config(
                        10.0, 250.0, 1.0, 0.001, 0.0, 0.0, 0.12, 0.0),
                BtcBaseShadowBacktestSimulator.ExecutionSemantics.LIVE_ONE_ORDER_PER_BAR);

        assertThat(result.executionSemantics())
                .isEqualTo(BtcBaseShadowBacktestSimulator.ExecutionSemantics.LIVE_ONE_ORDER_PER_BAR);
        assertThat(result.orderIntentCount()).isEqualTo(3);
        assertThat(result.orderBarCount()).isEqualTo(1);
        assertThat(result.executedBuys()).isEqualTo(1);
        assertThat(result.shadowOnlyIntentCount()).isEqualTo(2);
        assertThat(result.totalGrossBuys()).isEqualTo(10.0);
        assertThat(result.events()).extracting(BtcBaseShadowBacktestSimulator.Event::type)
                .containsExactly("BUY", "SHADOW_ONLY_INTENT", "SHADOW_ONLY_INTENT");
    }

    @Test
    void aggregateSemanticsExecutesOneAggregatedShadowOrderPerBar() {
        List<BtcBaseShadowBacktestSimulator.Bar> bars = List.of(
                bar("2026-01-01T00:00", 100.0));
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = List.of(
                intent("2026-01-01T00:00", "AI_BUY"),
                intent("2026-01-01T00:00", "RELATIVE_LOW"),
                intent("2026-01-01T00:00", "POTENTIAL_LOW"));

        BtcBaseShadowBacktestSimulator.Result result = BtcBaseShadowBacktestSimulator.run(
                bars, intents, BtcBaseShadowBacktestSimulator.Config.defaults(),
                BtcBaseShadowBacktestSimulator.ExecutionSemantics.SHADOW_AGGREGATE_PER_BAR);

        assertThat(result.executionSemantics())
                .isEqualTo(BtcBaseShadowBacktestSimulator.ExecutionSemantics.SHADOW_AGGREGATE_PER_BAR);
        assertThat(result.orderIntentCount()).isEqualTo(3);
        assertThat(result.executedBuys()).isEqualTo(1);
        assertThat(result.aggregatedOrderBars()).isEqualTo(1);
        assertThat(result.shadowOnlyIntentCount()).isEqualTo(2);
        assertThat(result.totalGrossBuys()).isEqualTo(30.0);
        assertThat(result.events().get(0).reason()).isEqualTo("AGGREGATED_INTENTS(3)");
    }

    @Test
    void fixedTenUsdtSliceSkipsCapacityRemainderBelowOneFullOrder() {
        List<BtcBaseShadowBacktestSimulator.Bar> bars = List.of(
                bar("2026-01-01T00:00", 100.0),
                bar("2026-01-02T00:00", 100.0),
                bar("2026-01-03T00:00", 100.0));
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = List.of(
                intent("2026-01-01T00:00", "FIRST"),
                intent("2026-01-02T00:00", "SECOND"),
                intent("2026-01-03T00:00", "CAP_REMAINDER"));

        BtcBaseShadowBacktestSimulator.Result result = BtcBaseShadowBacktestSimulator.run(
                bars, intents, new BtcBaseShadowBacktestSimulator.Config(
                        10.0, 25.0, 10.0, 0.001, 0.0, 0.0, 0.12, 0.0),
                BtcBaseShadowBacktestSimulator.ExecutionSemantics.LIVE_ONE_ORDER_PER_BAR);

        assertThat(result.executedBuys()).isEqualTo(2);
        assertThat(result.skippedByCap()).isEqualTo(1);
        assertThat(result.cappedBuys()).isZero();
        assertThat(result.totalGrossBuys()).isEqualTo(20.0);
        assertThat(result.maxCostBasis()).isEqualTo(20.0);
    }

    @Test
    void pineQuantityTierKeepsOneOrderPerBarAndUsesOriginalOneTwoFiveRatios() {
        List<BtcBaseShadowBacktestSimulator.Bar> bars = List.of(
                bar("2026-01-01T00:00", 100.0),
                bar("2026-01-02T00:00", 100.0),
                bar("2026-01-03T00:00", 100.0));
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = List.of(
                intentWithQuantity("2026-01-01T00:00", "RELATIVE_LOW", 1000.0),
                intentWithQuantity("2026-01-02T00:00", "RELATIVE_LOW", 1000.0),
                intentWithQuantity("2026-01-02T00:00", "POTENTIAL_LOW", 2000.0),
                intentWithQuantity("2026-01-03T00:00", "AI_BUY", 5000.0));

        BtcBaseShadowBacktestSimulator.Result result = BtcBaseShadowBacktestSimulator.run(
                bars, intents, new BtcBaseShadowBacktestSimulator.Config(
                        10.0, 100.0, 10.0, 0.001, 0.0, 0.0, 0.12, 0.0),
                BtcBaseShadowBacktestSimulator.ExecutionSemantics.SHADOW_PINE_QUANTITY_TIERED_PER_BAR);

        assertThat(result.orderIntentCount()).isEqualTo(4);
        assertThat(result.orderBarCount()).isEqualTo(3);
        assertThat(result.executedBuys()).isEqualTo(3);
        assertThat(result.shadowOnlyIntentCount()).isEqualTo(1);
        assertThat(result.totalGrossBuys()).isEqualTo(80.0);
        assertThat(result.events()).filteredOn(event -> "BUY".equals(event.type()))
                .extracting(BtcBaseShadowBacktestSimulator.Event::notional)
                .containsExactly(10.0, 20.0, 50.0);
        assertThat(result.events().get(1).reason()).contains("tvQty=2000").contains("multiplier=2.00");
    }

    private static BtcBaseShadowBacktestSimulator.Bar bar(String time, double close) {
        return new BtcBaseShadowBacktestSimulator.Bar(LocalDateTime.parse(time), close);
    }

    private static BtcBaseShadowBacktestSimulator.BuyIntent intent(String time, String reason) {
        return new BtcBaseShadowBacktestSimulator.BuyIntent(
                LocalDateTime.parse(time), 1000.0, reason, reason + "_LABEL", "BUY");
    }

    private static BtcBaseShadowBacktestSimulator.BuyIntent intentWithQuantity(
            String time, String reason, double quantity) {
        return new BtcBaseShadowBacktestSimulator.BuyIntent(
                LocalDateTime.parse(time), quantity, reason, reason + "_LABEL", "BUY");
    }
}
