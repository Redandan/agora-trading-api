package com.agora.service.trading;

import com.agora.model.BtBacktestResult;
import com.agora.model.BtBacktestTrade;
import com.agora.model.MdKline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrailingStopReplayServiceTest {

    private final TrailingStopReplayService service = new TrailingStopReplayService();

    @Test
    void trailingReplayCanImproveLosingLongTrade() {
        LocalDateTime entryTime = LocalDateTime.parse("2026-06-01T00:00:00");
        BtBacktestTrade trade = trade(entryTime, entryTime.plusMinutes(3), new BigDecimal("-10.00000000"));
        trade.setExitPrice(new BigDecimal("90"));

        var result = service.replayBacktestTrade(trade, List.of(
                bar(entryTime, "100", "100.5", "99.8", "100"),
                bar(entryTime.plusMinutes(1), "100", "103", "101", "102"),
                bar(entryTime.plusMinutes(2), "102", "103", "100.9", "101")
        ));

        assertThat(result.replayed()).isTrue();
        assertThat(result.exitedByTrailing()).isTrue();
        assertThat(result.exitReason()).isEqualTo("TRAILING_STOP");
        assertThat(result.finalState()).isEqualTo("TRAILING");
        assertThat(result.trailingNetPnl()).isGreaterThan(result.originalNetPnl());
        assertThat(result.deltaPnl()).isGreaterThan(BigDecimal.TEN);
        assertThat(result.improvementPct()).isGreaterThan(new BigDecimal("1.0"));
    }

    @Test
    void trailingReplayKeepsOriginalPnlWhenNoTriggerIsTouched() {
        LocalDateTime entryTime = LocalDateTime.parse("2026-06-01T00:00:00");
        BtBacktestTrade trade = trade(entryTime, entryTime.plusMinutes(2), new BigDecimal("2.50000000"));
        trade.setExitPrice(new BigDecimal("102.5"));

        var result = service.replayBacktestTrade(trade, List.of(
                bar(entryTime, "100", "100.2", "99.9", "100.1"),
                bar(entryTime.plusMinutes(1), "100.1", "100.4", "99.8", "100.2")
        ));

        assertThat(result.replayed()).isTrue();
        assertThat(result.exitedByTrailing()).isFalse();
        assertThat(result.exitReason()).isEqualTo("ORIGINAL_EXIT");
        assertThat(result.trailingNetPnl()).isEqualByComparingTo("2.50000000");
        assertThat(result.deltaPnl()).isZero();
    }

    @Test
    void trailingReplayCanImproveLosingShortTrade() {
        LocalDateTime entryTime = LocalDateTime.parse("2026-06-01T00:00:00");
        BtBacktestTrade trade = trade(entryTime, entryTime.plusMinutes(3), new BigDecimal("-10.00000000"));
        trade.setSide(BtBacktestTrade.Side.SHORT);
        trade.setExitPrice(new BigDecimal("110"));

        var result = service.replayBacktestTrade(trade, List.of(
                bar(entryTime, "100", "100.2", "99.0", "99.5"),
                bar(entryTime.plusMinutes(1), "99.5", "99.0", "97.0", "98.0"),
                bar(entryTime.plusMinutes(2), "98.0", "99.1", "97.8", "98.8")
        ));

        assertThat(result.replayed()).isTrue();
        assertThat(result.exitedByTrailing()).isTrue();
        assertThat(result.exitReason()).isEqualTo("TRAILING_STOP");
        assertThat(result.trailingNetPnl()).isGreaterThan(result.originalNetPnl());
        assertThat(result.deltaPnl()).isGreaterThan(new BigDecimal("9.0"));
    }

    @Test
    void trailingReplayMarksSameBarTriggerAndStopAsAmbiguous() {
        LocalDateTime entryTime = LocalDateTime.parse("2026-06-01T00:00:00");
        BtBacktestTrade trade = trade(entryTime, entryTime.plusMinutes(1), new BigDecimal("-10.00000000"));
        trade.setExitPrice(new BigDecimal("90"));

        var result = service.replayBacktestTrade(trade, List.of(
                bar(entryTime, "100", "103", "99", "101")
        ));

        assertThat(result.replayed()).isTrue();
        assertThat(result.exitedByTrailing()).isTrue();
        assertThat(result.ambiguousSameBar()).isTrue();
        assertThat(result.exitReason()).isEqualTo("TRAILING_STOP");
    }

    @Test
    void trailingReplayMarksSameBarRatchetAndStopAsAmbiguous() {
        LocalDateTime entryTime = LocalDateTime.parse("2026-06-01T00:00:00");
        BtBacktestTrade trade = trade(entryTime, entryTime.plusMinutes(3), new BigDecimal("-10.00000000"));
        trade.setExitPrice(new BigDecimal("90"));

        var result = service.replayBacktestTrade(trade, List.of(
                bar(entryTime, "100", "101", "100.5", "100.8"),
                bar(entryTime.plusMinutes(1), "100.8", "102", "101.5", "101.8"),
                bar(entryTime.plusMinutes(2), "101.8", "104", "101.8", "102.5")
        ));

        assertThat(result.replayed()).isTrue();
        assertThat(result.exitedByTrailing()).isTrue();
        assertThat(result.finalState()).isEqualTo("TRAILING");
        assertThat(result.ambiguousSameBar()).isTrue();
        assertThat(result.exitPrice()).isEqualByComparingTo("101.92000000");
    }

    @Test
    void trailingReplayMarksSameBarShortRatchetAndStopAsAmbiguous() {
        LocalDateTime entryTime = LocalDateTime.parse("2026-06-01T00:00:00");
        BtBacktestTrade trade = trade(entryTime, entryTime.plusMinutes(3), new BigDecimal("-10.00000000"));
        trade.setSide(BtBacktestTrade.Side.SHORT);
        trade.setExitPrice(new BigDecimal("110"));

        var result = service.replayBacktestTrade(trade, List.of(
                bar(entryTime, "100", "99.5", "99", "99.2"),
                bar(entryTime.plusMinutes(1), "99.2", "98.5", "98", "98.2"),
                bar(entryTime.plusMinutes(2), "98.2", "98.1", "96", "97.5")
        ));

        assertThat(result.replayed()).isTrue();
        assertThat(result.exitedByTrailing()).isTrue();
        assertThat(result.finalState()).isEqualTo("TRAILING");
        assertThat(result.ambiguousSameBar()).isTrue();
        assertThat(result.exitPrice()).isEqualByComparingTo("97.92000000");
    }

    private BtBacktestTrade trade(LocalDateTime entryTime, LocalDateTime exitTime, BigDecimal originalPnl) {
        BtBacktestResult backtest = new BtBacktestResult();
        backtest.setFeeRate(new BigDecimal("0.001"));

        BtBacktestTrade trade = new BtBacktestTrade();
        trade.setBacktest(backtest);
        trade.setEntryTime(entryTime);
        trade.setExitTime(exitTime);
        trade.setEntryPrice(new BigDecimal("100"));
        trade.setQuantity(BigDecimal.ONE);
        trade.setNetPnl(originalPnl);
        trade.setAtrPct(new BigDecimal("0.02"));
        trade.setSide(BtBacktestTrade.Side.LONG);
        trade.setBorrowingCost(BigDecimal.ZERO);
        return trade;
    }

    private MdKline bar(LocalDateTime openTime, String open, String high, String low, String close) {
        MdKline bar = new MdKline();
        bar.setOpenTime(openTime);
        bar.setCloseTime(openTime.plusMinutes(1));
        bar.setOpenPrice(new BigDecimal(open));
        bar.setHighPrice(new BigDecimal(high));
        bar.setLowPrice(new BigDecimal(low));
        bar.setClosePrice(new BigDecimal(close));
        bar.setVolume(BigDecimal.ONE);
        bar.setSymbol("BTCUSDT");
        bar.setIntervalCode("1m");
        bar.setSource("okx");
        return bar;
    }
}
