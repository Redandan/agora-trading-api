package com.agora.mcp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GridMcpToolsTest {

    @Test
    void trendAdjustmentReviewDoesNotRecommendActionWithoutActiveGrid() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "NO_GRID", "UP_STRONG", new BigDecimal("4.2"), new BigDecimal("0.8"),
                null, null, 0, 72, 0, 0);

        assertThat(action).isEqualTo("NO_ACTION_NO_ACTIVE_GRID");
    }

    @Test
    void trendAdjustmentReviewRequiresEnoughEvidence() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE", "UP", new BigDecimal("1.5"), new BigDecimal("0.5"),
                new BigDecimal("8"), new BigDecimal("50"), 0, 12, 0, 1);

        assertThat(action).isEqualTo("NO_ACTION_INSUFFICIENT_EVIDENCE");
    }

    @Test
    void trendAdjustmentReviewPrioritizesMaterialFailures() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE", "SIDEWAYS", BigDecimal.ZERO, new BigDecimal("0.5"),
                new BigDecimal("8"), new BigDecimal("50"), 1, 72, 2, 5);

        assertThat(action).isEqualTo("CLOSE_REVIEW_FAILURE_FIRST");
    }

    @Test
    void trendAdjustmentReviewFlagsStrongUpBreakoutAboveRange() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "OUT_ABOVE", "UP_STRONG", new BigDecimal("5.1"), new BigDecimal("0.8"),
                new BigDecimal("7"), new BigDecimal("120"), 0, 72, 2, 5);

        assertThat(action).isEqualTo("REBUILD_HIGHER_REVIEW");
    }

    @Test
    void trendAdjustmentReviewWidensNarrowRangeInHighVolatility() {
        String action = GridMcpTools.classifyGridTrendAdjustment(
                "IN_RANGE", "SIDEWAYS", new BigDecimal("0.2"), new BigDecimal("1.30"),
                new BigDecimal("4"), new BigDecimal("50"), 0, 72, 2, 5);

        assertThat(action).isEqualTo("WIDEN_RANGE_REVIEW");
    }
}
