package com.agora.trading.smoke;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSmokeCliTest {

    @Test
    void validatesNoActiveGridReviewPacket() {
        String review = """
                === Grid Trend Adjustment Review ===
                boundary=READ_ONLY; mutationAllowed=false; orderAllowed=false; gridMutationAllowed=false; schedulerChangeAllowed=false; telegramSendAllowed=false
                purpose=operator review only; actions ending in _REVIEW are not execution authorization.

                market symbol=BTCUSDT interval=1h lookbackHours=72 bars=72 source=md_kline:okx trend=DOWN_STRONG trendPct=-3.88% atrPct=0.91% current=$61768.00 latestBar=2026-06-25T05:00

                activeGridCount=0
                recommendation=NO_ACTION_NO_ACTIVE_GRID
                reason=no active grid for symbol
                """;

        McpSmokeCli.GridTrendSmokeResult result = McpSmokeCli.validateGridTrendReview(review);

        assertThat(result.status()).isEqualTo("NO_ACTIVE_GRID_NOT_MUTATION");
        assertThat(result.recommendation()).isEqualTo("NO_ACTION_NO_ACTIVE_GRID");
        assertThat(result.activeGridCount()).isEqualTo("0");
        assertThat(result.trend()).isEqualTo("DOWN_STRONG");
        assertThat(result.trendPct()).isEqualTo("-3.88%");
        assertThat(result.atrPct()).isEqualTo("0.91%");
    }

    @Test
    void rejectsPacketWithoutReadOnlyBoundary() {
        String review = """
                === Grid Trend Adjustment Review ===
                mutationAllowed=false
                orderAllowed=false
                gridMutationAllowed=false
                schedulerChangeAllowed=false
                telegramSendAllowed=false
                operator review only
                market symbol=BTCUSDT trend=SIDEWAYS trendPct=0.10% atrPct=0.50%
                recommendation=KEEP_MONITOR
                """;

        assertThatThrownBy(() -> McpSmokeCli.validateGridTrendReview(review))
                .hasMessageContaining("Missing read-only boundary");
    }

    @Test
    void rejectsUnknownRecommendation() {
        String review = """
                === Grid Trend Adjustment Review ===
                boundary=READ_ONLY; mutationAllowed=false; orderAllowed=false; gridMutationAllowed=false; schedulerChangeAllowed=false; telegramSendAllowed=false
                operator review only
                market symbol=BTCUSDT trend=SIDEWAYS trendPct=0.10% atrPct=0.50%
                recommendation=EXECUTE_GRID_NOW
                """;

        assertThatThrownBy(() -> McpSmokeCli.validateGridTrendReview(review))
                .hasMessageContaining("Unexpected recommendation=EXECUTE_GRID_NOW");
    }
}
