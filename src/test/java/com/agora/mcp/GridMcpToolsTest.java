package com.agora.mcp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GridMcpToolsTest {

    @Test
    void createGridUsesUpperPriceAsSellBoundaryNotBuyLevel() {
        BigDecimal lower = new BigDecimal("54067.59");
        BigDecimal upper = new BigDecimal("66082.61");

        List<BigDecimal> buyPrices = GridMcpTools.buildBuyLevelPrices(lower, upper, 2);
        BigDecimal step = GridMcpTools.calcGridStep(lower, upper, 2);

        assertThat(buyPrices)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("54067.59000000");
        assertThat(buyPrices).noneMatch(price -> price.compareTo(upper) == 0);
        assertThat(buyPrices.get(0).add(step)).isEqualByComparingTo(upper);
        assertThat(GridMcpTools.estimateCreateGridCapital(new BigDecimal("5"), 2))
                .isEqualByComparingTo("5");
    }

    @Test
    void createGridBuildsOneFewerBuyLevelThanPriceLines() {
        List<BigDecimal> buyPrices = GridMcpTools.buildBuyLevelPrices(
                new BigDecimal("100"), new BigDecimal("140"), 5);

        assertThat(buyPrices)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("100.00000000", "110.00000000", "120.00000000", "130.00000000");
        assertThat(buyPrices).noneMatch(price -> price.compareTo(new BigDecimal("140")) == 0);
        assertThat(GridMcpTools.estimateCreateGridCapital(new BigDecimal("10"), 5))
                .isEqualByComparingTo("40");
    }

    @Test
    void trendAdjustmentReviewDoesNotRecommendActionWithoutActiveGrid() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "NO_GRID", "UP_STRONG", new BigDecimal("4.2"), new BigDecimal("0.8"),
                null, null, 0, 72, 0, 0);

        assertThat(action).isEqualTo("WATCH");
    }

    @Test
    void trendAdjustmentReviewRequiresEnoughEvidence() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE", "UP", new BigDecimal("1.5"), new BigDecimal("0.5"),
                new BigDecimal("8"), new BigDecimal("50"), 0, 12, 0, 1);

        assertThat(action).isEqualTo("WATCH");
    }

    @Test
    void trendAdjustmentReviewPrioritizesMaterialFailures() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE", "SIDEWAYS", BigDecimal.ZERO, new BigDecimal("0.5"),
                new BigDecimal("8"), new BigDecimal("50"), 1, 72, 2, 5);

        assertThat(action).isEqualTo("PAUSE");
    }

    @Test
    void trendAdjustmentReviewFlagsStrongUpBreakoutAboveRange() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "OUT_ABOVE",
                "UP_STRONG", new BigDecimal("5.1"), new BigDecimal("0.8"),
                "UP", new BigDecimal("3.2"), new BigDecimal("1.2"),
                new BigDecimal("7"), new BigDecimal("120"),
                0, 72, 18, 2, 5, 60);

        assertThat(action).isEqualTo("REBUILD_REVIEW");
    }

    @Test
    void trendAdjustmentReviewWidensNarrowRangeInHighVolatility() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE", "SIDEWAYS", new BigDecimal("0.2"), new BigDecimal("1.30"),
                new BigDecimal("4"), new BigDecimal("50"), 0, 72, 2, 5);

        assertThat(action).isEqualTo("RESIZE_REVIEW");
    }

    @Test
    void trendAdjustmentReviewPausesConfirmedDirectionalBoundaryPressure() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE",
                "DOWN_STRONG", new BigDecimal("-3.5"), new BigDecimal("0.70"),
                "DOWN", new BigDecimal("-1.4"), new BigDecimal("1.10"),
                new BigDecimal("10"), new BigDecimal("8"),
                0, 72, 18, 2, 5, 70);

        assertThat(action).isEqualTo("PAUSE");
    }

    @Test
    void trendAdjustmentReviewWatchesMixedTimeframesInsteadOfRebuilding() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "OUT_ABOVE",
                "UP_STRONG", new BigDecimal("4.1"), new BigDecimal("0.80"),
                "DOWN", new BigDecimal("-1.2"), new BigDecimal("1.00"),
                new BigDecimal("10"), new BigDecimal("110"),
                0, 72, 18, 2, 5, 65);

        assertThat(action).isEqualTo("WATCH");
    }

    @Test
    void trendAlignmentRequiresFourHourConfirmation() {
        assertThat(GridMcpTools.trendAlignment("UP_STRONG", "UP", 18))
                .isEqualTo("UP_CONFIRMED");
        assertThat(GridMcpTools.trendAlignment("DOWN_STRONG", "SIDEWAYS", 18))
                .isEqualTo("DOWN_FORMING");
        assertThat(GridMcpTools.trendAlignment("UP_STRONG", "DOWN", 18))
                .isEqualTo("MIXED");
        assertThat(GridMcpTools.trendAlignment("UP_STRONG", "UP", 6))
                .isEqualTo("UP_UNCONFIRMED_4H");
    }
}
