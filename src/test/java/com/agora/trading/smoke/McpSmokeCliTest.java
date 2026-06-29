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

                market symbol=BTCUSDT lookbackHours=72 trend=DOWN_STRONG trendPct=-3.88% atrPct=0.91% current=$61768.00 trendAlignment=DOWN_CONFIRMED
                trend1h=DOWN_STRONG bars=72 source=md_kline:okx trendPct=-3.88% atrPct=0.91% latestBar=2026-06-25T05:00
                trend4h=DOWN bars=18 source=md_kline:okx trendPct=-1.88% atrPct=1.22% latestBar=2026-06-25T04:00

                decisionSet=KEEP,PAUSE,WATCH,REBUILD_REVIEW,RESIZE_REVIEW
                automationAllowed=false; closeGridAllowed=false; createGridAllowed=false; autoRebalanceAllowed=false

                activeGridCount=0
                recommendation=WATCH
                decisionBlockers=[NO_ACTIVE_GRID]
                reason=no active grid for symbol
                """;

        McpSmokeCli.GridTrendSmokeResult result = McpSmokeCli.validateGridTrendReview(review);

        assertThat(result.status()).isEqualTo("NO_ACTIVE_GRID_NOT_MUTATION");
        assertThat(result.recommendation()).isEqualTo("WATCH");
        assertThat(result.activeGridCount()).isEqualTo("0");
        assertThat(result.trend()).isEqualTo("DOWN_STRONG");
        assertThat(result.trend1h()).isEqualTo("DOWN_STRONG");
        assertThat(result.trend4h()).isEqualTo("DOWN");
        assertThat(result.trendAlignment()).isEqualTo("DOWN_CONFIRMED");
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
                trend1h=SIDEWAYS
                trend4h=SIDEWAYS
                trendAlignment=SIDEWAYS
                decisionSet=KEEP,PAUSE,WATCH,REBUILD_REVIEW,RESIZE_REVIEW
                automationAllowed=false
                decisionBlockers=[]
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
                trend1h=SIDEWAYS
                trend4h=SIDEWAYS
                trendAlignment=SIDEWAYS
                decisionSet=KEEP,PAUSE,WATCH,REBUILD_REVIEW,RESIZE_REVIEW
                automationAllowed=false
                decisionBlockers=[]
                recommendation=EXECUTE_GRID_NOW
                """;

        assertThatThrownBy(() -> McpSmokeCli.validateGridTrendReview(review))
                .hasMessageContaining("Unexpected recommendation=EXECUTE_GRID_NOW");
    }
}
