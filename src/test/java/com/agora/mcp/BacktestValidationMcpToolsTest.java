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

        assertThat(line)
                .contains("dataStart=2026-01-11T00:00")
                .contains("dataEnd=2026-01-12T00:00")
                .contains("visibleBars=2")
                .contains("coverage=PARTIAL")
                .contains("trailingGapHours=36")
                .contains("coverageWarning=REQUESTED_WINDOW_PARTIAL missingLeadDays=10");
    }

    @Test
    void tradingViewCoverageLineReportsOkWhenDataStartsBeforeVisibleWindow() {
        LocalDateTime visibleStart = LocalDateTime.of(2026, 1, 10, 0, 0);
        List<MdKline> klines = List.of(
                kline(LocalDateTime.of(2026, 1, 1, 0, 0)),
                kline(LocalDateTime.of(2026, 1, 10, 0, 0)));

        String line = BacktestValidationMcpTools.buildTradingViewDataCoverageLine(
                klines, visibleStart, LocalDateTime.of(2026, 1, 10, 12, 0));

        assertThat(line)
                .contains("visibleBars=1")
                .contains("coverage=OK")
                .contains("coverageWarning=NONE");
    }

    private static MdKline kline(LocalDateTime openTime) {
        MdKline kline = new MdKline();
        kline.setOpenTime(openTime);
        return kline;
    }
}
