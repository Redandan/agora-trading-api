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
