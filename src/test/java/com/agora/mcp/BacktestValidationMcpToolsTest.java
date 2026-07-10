package com.agora.mcp;

import com.agora.model.MdKline;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestValidationMcpToolsTest {

    @Test
    void tradingViewCoverageLineFlagsPartialRequestedWindow() {
        LocalDateTime visibleStart = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<MdKline> klines = List.of(
                kline(LocalDateTime.of(2026, 1, 11, 0, 0)),
                kline(LocalDateTime.of(2026, 1, 12, 0, 0)));

        String line = BacktestValidationMcpTools.buildTradingViewDataCoverageLine(
                klines, visibleStart, LocalDateTime.of(2026, 1, 13, 12, 0));
        BacktestValidationMcpTools.DataCoverage coverage = BacktestValidationMcpTools.inspectTradingViewDataCoverage(
                klines, visibleStart, LocalDateTime.of(2026, 1, 13, 12, 0));

        assertThat(line)
                .contains("dataStart=2026-01-11T00:00")
                .contains("dataEnd=2026-01-12T00:00")
                .contains("dataClose=2026-01-13T00:00")
                .contains("visibleBars=2")
                .contains("coverage=PARTIAL")
                .contains("trailingGapHours=36")
                .contains("trailingCloseGapHours=12")
                .contains("freshnessStatus=CURRENT_CLOSED_BAR")
                .contains("coverageWarning=REQUESTED_WINDOW_PARTIAL missingLeadDays=10");
        assertThat(coverage.qualityGatePassed()).isFalse();
    }

    @Test
    void tradingViewCoverageLineReportsOkWhenDataStartsBeforeVisibleWindow() {
        LocalDateTime visibleStart = LocalDateTime.of(2026, 1, 10, 0, 0);
        List<MdKline> klines = List.of(
                kline(LocalDateTime.of(2026, 1, 1, 0, 0)),
                kline(LocalDateTime.of(2026, 1, 10, 0, 0)));

        String line = BacktestValidationMcpTools.buildTradingViewDataCoverageLine(
                klines, visibleStart, LocalDateTime.of(2026, 1, 10, 12, 0));
        BacktestValidationMcpTools.DataCoverage coverage = BacktestValidationMcpTools.inspectTradingViewDataCoverage(
                klines, visibleStart, LocalDateTime.of(2026, 1, 10, 12, 0));

        assertThat(line)
                .contains("visibleBars=1")
                .contains("coverage=OK")
                .contains("coverageWarning=NONE");
        assertThat(coverage.qualityGatePassed()).isTrue();
    }

    @Test
    void tradingViewCoverageLineSeparatesDailyOpenGapFromClosedBarFreshness() {
        LocalDateTime visibleStart = LocalDateTime.of(2026, 7, 1, 0, 0);
        List<MdKline> klines = List.of(
                kline(LocalDateTime.of(2026, 6, 30, 16, 0)),
                kline(LocalDateTime.of(2026, 7, 1, 16, 0)),
                kline(LocalDateTime.of(2026, 7, 2, 16, 0)));

        String line = BacktestValidationMcpTools.buildTradingViewDataCoverageLine(
                klines, visibleStart, LocalDateTime.of(2026, 7, 4, 5, 35), "1d");
        BacktestValidationMcpTools.DataCoverage coverage = BacktestValidationMcpTools.inspectTradingViewDataCoverage(
                klines, visibleStart, LocalDateTime.of(2026, 7, 4, 5, 35), "1d");

        assertThat(line)
                .contains("dataEnd=2026-07-02T16:00")
                .contains("dataClose=2026-07-03T16:00")
                .contains("coverage=OK")
                .contains("trailingGapHours=37")
                .contains("trailingCloseGapHours=13")
                .contains("freshnessStatus=CURRENT_CLOSED_BAR")
                .contains("coverageWarning=NONE");
        assertThat(coverage.qualityGatePassed()).isTrue();
    }

    @Test
    void productionSemanticsExcludeFormingBars() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 13, 0);
        MdKline closed = kline(LocalDateTime.of(2026, 7, 9, 0, 0));
        closed.setCloseTime(LocalDateTime.of(2026, 7, 9, 23, 59, 59));
        MdKline forming = kline(LocalDateTime.of(2026, 7, 10, 0, 0));
        forming.setCloseTime(LocalDateTime.of(2026, 7, 10, 23, 59, 59));

        List<MdKline> result = BacktestValidationMcpTools.closedKlinesOnly(
                List.of(closed, forming), now, "1d");

        assertThat(result).containsExactly(closed);
    }

    private static MdKline kline(LocalDateTime openTime) {
        MdKline kline = new MdKline();
        kline.setOpenTime(openTime);
        return kline;
    }
}
