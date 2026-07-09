package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PositionSizingServiceTest {

    private final OkxTradingProperties props = new OkxTradingProperties();
    private final PositionSizingService service = new PositionSizingService(props);

    @Test
    void keepsDisasterSlSizingBelowMinimumAsReportOnlyWhenShadowMode() {
        props.setPositionSizingLiveEnabled(false);
        props.setPositionSizingMinNotionalUsdt(50.0);
        props.setPositionSizingMaxNotionalUsdt(150.0);
        props.setPositionSizingHardMaxRiskUsdt(5.0);

        var decision = service.calculate(
                "BTCUSDT",
                485L,
                new BigDecimal("100000"),
                new BigDecimal("103000"),
                new BigDecimal("88000"),
                0.90,
                100.0,
                null);

        assertThat(decision.slDistancePct()).isCloseTo(0.12, withinPct(0.00001));
        assertThat(decision.tpDistancePct()).isCloseTo(0.03, withinPct(0.00001));
        assertThat(decision.recommendedAmountUsdt()).isLessThan(50.0);
        assertThat(decision.finalAmountUsdt()).isEqualTo(100.0);
        assertThat(decision.belowMinNotional()).isTrue();
        assertThat(decision.liveEntryAllowed()).isFalse();
        assertThat(decision.reason()).contains("below_min_notional_skip");
        assertThat(decision.reason()).doesNotContain("min_notional_floor");
    }

    @Test
    void liveSizingBelowMinimumReturnsZeroFinalAmountForSkipGate() {
        props.setPositionSizingLiveEnabled(true);
        props.setPositionSizingMinNotionalUsdt(50.0);
        props.setPositionSizingMaxNotionalUsdt(150.0);
        props.setPositionSizingHardMaxRiskUsdt(5.0);

        var decision = service.calculate(
                "BTCUSDT",
                574L,
                new BigDecimal("100000"),
                new BigDecimal("103000"),
                new BigDecimal("88000"),
                0.90,
                100.0,
                null);

        assertThat(decision.recommendedAmountUsdt()).isLessThan(50.0);
        assertThat(decision.finalAmountUsdt()).isZero();
        assertThat(decision.liveEntryAllowed()).isFalse();
        assertThat(decision.tgLine()).contains("SKIP");
    }

    @Test
    void minNotionalFloorCanBridgeRiskSizedAmountWhenRiskIsBounded() {
        props.setPositionSizingLiveEnabled(true);
        props.setPositionSizingMinNotionalUsdt(50.0);
        props.setPositionSizingMinNotionalFloorEnabled(true);
        props.setPositionSizingMinNotionalFloorMaxRiskUsdt(6.25);
        props.setPositionSizingMaxNotionalUsdt(150.0);
        props.setPositionSizingHardMaxRiskUsdt(5.0);

        var decision = service.calculate(
                "BTCUSDT",
                508L,
                new BigDecimal("62056.20"),
                new BigDecimal("65779.57"),
                new BigDecimal("54609.46"),
                0.0,
                100.0,
                null);

        assertThat(decision.slDistancePct()).isCloseTo(0.12, withinPct(0.0001));
        assertThat(decision.riskBudgetUsdt()).isCloseTo(2.5, withinPct(0.0001));
        assertThat(decision.recommendedAmountUsdt()).isEqualTo(50.0);
        assertThat(decision.finalAmountUsdt()).isEqualTo(50.0);
        assertThat(decision.belowMinNotional()).isFalse();
        assertThat(decision.liveEntryAllowed()).isTrue();
        assertThat(decision.reason()).contains("min_notional_floor_applied");
        assertThat(decision.reason()).doesNotContain("below_min_notional_skip");
        assertThat(decision.explain()).contains("rawRiskSized=20.83").contains("floorApplied=true");
    }

    @Test
    void minNotionalFloorKeepsSkipWhenFloorRiskExceedsCap() {
        props.setPositionSizingLiveEnabled(true);
        props.setPositionSizingMinNotionalUsdt(50.0);
        props.setPositionSizingMinNotionalFloorEnabled(true);
        props.setPositionSizingMinNotionalFloorMaxRiskUsdt(5.0);
        props.setPositionSizingMaxNotionalUsdt(150.0);
        props.setPositionSizingHardMaxRiskUsdt(5.0);

        var decision = service.calculate(
                "BTCUSDT",
                508L,
                new BigDecimal("62056.20"),
                new BigDecimal("65779.57"),
                new BigDecimal("54609.46"),
                0.0,
                100.0,
                null);

        assertThat(decision.recommendedAmountUsdt()).isLessThan(50.0);
        assertThat(decision.finalAmountUsdt()).isZero();
        assertThat(decision.liveEntryAllowed()).isFalse();
        assertThat(decision.reason()).contains("min_notional_floor_risk_too_high");
        assertThat(decision.reason()).contains("below_min_notional_skip");
    }

    @Test
    void minNotionalFloorKeepsSkipWhenSpendableUsdtCannotFundMinimum() {
        props.setPositionSizingLiveEnabled(true);
        props.setPositionSizingMinNotionalUsdt(50.0);
        props.setPositionSizingMinNotionalFloorEnabled(true);
        props.setPositionSizingMinNotionalFloorMaxRiskUsdt(6.25);
        props.setPositionSizingFreeUsdtBuffer(20.0);
        props.setPositionSizingMaxNotionalUsdt(150.0);
        props.setPositionSizingHardMaxRiskUsdt(5.0);

        var decision = service.calculate(
                "BTCUSDT",
                508L,
                new BigDecimal("62056.20"),
                new BigDecimal("65779.57"),
                new BigDecimal("54609.46"),
                0.0,
                100.0,
                30.0);

        assertThat(decision.recommendedAmountUsdt()).isLessThan(50.0);
        assertThat(decision.finalAmountUsdt()).isZero();
        assertThat(decision.liveEntryAllowed()).isFalse();
        assertThat(decision.reason()).contains("free_usdt_buffer_cap");
        assertThat(decision.reason()).contains("min_notional_floor_insufficient_spendable_usdt");
        assertThat(decision.reason()).contains("below_min_notional_skip");
    }

    @Test
    void usesActualStopDistanceInsteadOfTakeProfitDistance() {
        props.setPositionSizingLiveEnabled(true);
        props.setPositionSizingMinNotionalUsdt(10.0);
        props.setPositionSizingMaxNotionalUsdt(150.0);
        props.setPositionSizingHardMaxRiskUsdt(5.0);

        var wideStop = service.calculate(
                "BTCUSDT",
                485L,
                new BigDecimal("100000"),
                new BigDecimal("103000"),
                new BigDecimal("88000"),
                0.90,
                100.0,
                null);

        var tightStop = service.calculate(
                "BTCUSDT",
                485L,
                new BigDecimal("100000"),
                new BigDecimal("103000"),
                new BigDecimal("97000"),
                0.90,
                100.0,
                null);

        assertThat(wideStop.slDistancePct()).isCloseTo(0.12, withinPct(0.00001));
        assertThat(tightStop.slDistancePct()).isCloseTo(0.03, withinPct(0.00001));
        assertThat(wideStop.recommendedAmountUsdt()).isLessThan(tightStop.recommendedAmountUsdt());
    }

    private static org.assertj.core.data.Offset<Double> withinPct(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
