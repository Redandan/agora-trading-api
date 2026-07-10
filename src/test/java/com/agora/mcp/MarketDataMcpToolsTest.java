package com.agora.mcp;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataMcpToolsTest {

    @Test
    void signalAccuracyReportCarriesReadOnlyBoundary() {
        String output = MarketDataMcpTools.buildSignalAccuracyReport(7, List.of());

        assertThat(output)
                .contains("mode=READ_ONLY")
                .contains("no signal/order/OCO/strategy/grid/fund/Earn/Telegram behavior changed");
    }

    @Test
    void okxBackfillPlanSuggestsExplicitRangeForPartialCoverage() {
        String plan = MarketDataMcpTools.buildOkxKlineBackfillPlan(
                "BTCUSDT", "1d", 730,
                LocalDateTime.of(2024, 7, 2, 0, 0),
                LocalDateTime.of(2026, 7, 2, 13, 0),
                LocalDateTime.of(2025, 4, 30, 16, 0),
                LocalDateTime.of(2026, 6, 30, 16, 0),
                427);

        assertThat(plan)
                .contains("boundary: READ_ONLY")
                .contains("coverage=PARTIAL")
                .contains("missingLeadDays=302")
                .contains("batchCount=1")
                .contains("backfillOkxKlinesRange(symbol=\"BTCUSDT\", intervalCode=\"1d\", startUtc=\"2024-07-02T00:00:00\", endUtc=\"2025-04-30T16:00:00\")")
                .contains("TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=true")
                .contains("notAuthorization");
    }

    @Test
    void okxBackfillPlanSplitsDenseIntervalsIntoSafeBatches() {
        List<MarketDataMcpTools.BackfillBatch> batches = MarketDataMcpTools.buildOkxBackfillBatches(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 2, 1, 0, 0),
                "1m");

        assertThat(MarketDataMcpTools.maxOkxRangeBackfillDays("1m")).isEqualTo(12);
        assertThat(batches).hasSize(3);
        assertThat(batches.get(0).start()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(batches.get(0).end()).isEqualTo(LocalDateTime.of(2026, 1, 13, 0, 0));
        assertThat(batches.get(2).end()).isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
    }

    @Test
    void okxBackfillRangeCapacityCoversDailyAndHourlyParityWindows() {
        assertThat(MarketDataMcpTools.maxOkxRangeBackfillDays("1d")).isEqualTo(730);
        assertThat(MarketDataMcpTools.maxOkxRangeBackfillDays("1h")).isEqualTo(730);
        assertThat(MarketDataMcpTools.maxOkxRangeBackfillDays("15m")).isEqualTo(187);
    }

    @Test
    void binanceBackfillRangeCapacitySupportsGoldenDailyWindowButCapsDenseIntervals() {
        assertThat(MarketDataMcpTools.maxBinanceRangeBackfillDays("1d")).isEqualTo(730);
        assertThat(MarketDataMcpTools.maxBinanceRangeBackfillDays("1h")).isEqualTo(730);
        assertThat(MarketDataMcpTools.maxBinanceRangeBackfillDays("1m")).isEqualTo(41);
    }
}
