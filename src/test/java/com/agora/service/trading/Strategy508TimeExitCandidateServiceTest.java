package com.agora.service.trading;

import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.BtStrategyService;
import com.agora.service.backtest.BacktestEngine;
import com.agora.service.backtest.StrategyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class Strategy508TimeExitCandidateServiceTest {

    private final Strategy508TimeExitCandidateService service = new Strategy508TimeExitCandidateService(
            mock(BtStrategyService.class),
            mock(StrategyRegistry.class),
            mock(BacktestEngine.class),
            mock(MdKlineRepository.class),
            new ObjectMapper());

    @Test
    void nonBtcSymbolFailsClosedBeforeReadingStrategyOrMarketData() {
        ObjectNode report = service.analyzeNode("ETH-USDT", 5);

        assertThat(report.path("status").asText()).isEqualTo("UNSUPPORTED_SYMBOL");
        assertThat(report.path("supportedSymbol").asText()).isEqualTo("BTCUSDT");
        assertThat(report.path("historicalGatePassed").asBoolean()).isFalse();
        assertThat(report.path("livePromotionAllowed").asBoolean()).isFalse();
    }

    @Test
    void usesFirstMinuteOpenAfterDecisionAndExitsAt24HourOpenWithCosts() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0, 30);
        LocalDateTime entryTime = LocalDateTime.of(2026, 1, 1, 0, 1);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(entryTime, 1440, bd("100"));
        bars.add(bar(entryTime.plusHours(24), "101", "101", "101", "101"));

        Strategy508TimeExitCandidateService.EventResult result = service.simulateSingle(
                entry(decision), bars, entryTime.plusHours(25));

        assertThat(result.finalized()).isTrue();
        assertThat(result.outcome()).isEqualTo("TIME_EXIT_24H");
        assertThat(result.entryTime()).isEqualTo(entryTime);
        assertThat(result.exitTime()).isEqualTo(entryTime.plusHours(24));
        assertThat(result.entryPrice()).isEqualByComparingTo("100.0500");
        assertThat(result.exitPrice()).isEqualByComparingTo("100.9495");
        assertThat(result.feesUsdt()).isPositive();
        assertThat(result.pnlUsdt()).isPositive();
        assertThat(result.coverage()).isEqualTo(1.0);
    }

    @Test
    void tpAndSlResolveBeforeTimeExit() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> tpBars = flatBars(decision, 31, bd("100"));
        tpBars.set(30, bar(decision.plusMinutes(30), "100", "107", "100", "106"));
        List<Strategy508TimeExitCandidateService.MinuteBar> slBars = flatBars(decision, 31, bd("100"));
        slBars.set(30, bar(decision.plusMinutes(30), "100", "100", "87", "88"));

        Strategy508TimeExitCandidateService.EventResult tp = service.simulateSingle(
                entry(decision), tpBars, decision.plusHours(25));
        Strategy508TimeExitCandidateService.EventResult sl = service.simulateSingle(
                entry(decision), slBars, decision.plusHours(25));

        assertThat(tp.finalized()).isTrue();
        assertThat(tp.outcome()).isEqualTo("TP_HIT");
        assertThat(tp.exitTime()).isEqualTo(decision.plusMinutes(30));
        assertThat(sl.finalized()).isTrue();
        assertThat(sl.outcome()).isEqualTo("SL_HIT");
        assertThat(sl.exitTime()).isEqualTo(decision.plusMinutes(30));
    }

    @Test
    void sameMinuteTpAndSlIsAmbiguousAndNeverFinalized() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(decision, 2, bd("100"));
        bars.set(1, bar(decision.plusMinutes(1), "100", "107", "87", "100"));

        Strategy508TimeExitCandidateService.EventResult result = service.simulateSingle(
                entry(decision), bars, decision.plusHours(25));

        assertThat(result.finalized()).isFalse();
        assertThat(result.outcome()).isEqualTo("AMBIGUOUS_SAME_MINUTE");
    }

    @Test
    void minuteCoverageBelow99PercentFailsClosed() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(decision, 1440, bd("100"));
        bars.subList(100, 120).clear();
        bars.add(bar(decision.plusHours(24), "101", "101", "101", "101"));

        Strategy508TimeExitCandidateService.EventResult result = service.simulateSingle(
                entry(decision), bars, decision.plusHours(25));

        assertThat(result.finalized()).isFalse();
        assertThat(result.outcome()).isEqualTo("INSUFFICIENT_1M_COVERAGE");
        assertThat(result.coverage()).isLessThan(0.99);
    }

    @Test
    void exactly24HourBarIsTimeExitAndCannotRetroactivelyTriggerOco() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(decision, 1440, bd("100"));
        bars.add(bar(decision.plusHours(24), "101", "107", "87", "101"));

        Strategy508TimeExitCandidateService.EventResult result = service.simulateSingle(
                entry(decision), bars, decision.plusHours(25));

        assertThat(result.finalized()).isTrue();
        assertThat(result.outcome()).isEqualTo("TIME_EXIT_24H");
    }

    @Test
    void overlappingBuyIsSkippedWhileFirstPositionIsOpen() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(decision, 1440, bd("100"));
        bars.add(bar(decision.plusHours(24), "101", "101", "101", "101"));

        ObjectNode report = service.analyzePreparedForTest("BTCUSDT", decision.plusHours(25),
                List.of(entry(decision), entry(decision.plusHours(1))), bars, 10);

        assertThat(report.path("eligibleEvents").asInt()).isEqualTo(1);
        assertThat(report.path("overlapSkippedEvents").asInt()).isEqualTo(1);
        assertThat(report.path("finalizedEvents").asInt()).isEqualTo(1);
    }

    @Test
    void thirtyProfitableChronologicalEventsCanPassEveryHistoricalGate() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime first = now.minusDays(90);
        List<Strategy508TimeExitCandidateService.EntryIntent> entries = new ArrayList<>();
        Set<LocalDateTime> timeExits = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            LocalDateTime decision = first.plusDays(i * 3L);
            entries.add(entry(decision));
            timeExits.add(decision.plusHours(24));
        }
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = new ArrayList<>();
        for (LocalDateTime at = first; !at.isAfter(now); at = at.plusMinutes(1)) {
            BigDecimal price = timeExits.contains(at) ? bd("101") : bd("100");
            bars.add(new Strategy508TimeExitCandidateService.MinuteBar(at, price, price, price, price));
        }

        ObjectNode report = service.analyzePreparedForTest("BTCUSDT", now, entries, bars, 5);

        assertThat(report.path("finalizedEvents").asInt()).isEqualTo(30);
        assertThat(report.path("benchmark72hPairedEvents").asInt()).isEqualTo(30);
        assertThat(report.path("walkForward").path("positiveFolds").asInt()).isEqualTo(5);
        assertThat(report.path("candidateImprovementVs72hPp").asDouble()).isGreaterThan(0.5);
        assertThat(report.path("historicalGatePassed").asBoolean()).isTrue();
        assertThat(report.path("verdict").asText())
                .isEqualTo("READY_FOR_SINGLE_10_USDT_PROBE_REVIEW_NOT_AUTHORIZED");
        assertThat(report.path("livePromotionAllowed").asBoolean()).isFalse();
    }

    private Strategy508TimeExitCandidateService.EntryIntent entry(LocalDateTime decision) {
        return new Strategy508TimeExitCandidateService.EntryIntent(decision, decision, "RAW_BUY_4H");
    }

    private List<Strategy508TimeExitCandidateService.MinuteBar> flatBars(
            LocalDateTime start, int count, BigDecimal price) {
        List<Strategy508TimeExitCandidateService.MinuteBar> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Strategy508TimeExitCandidateService.MinuteBar(
                    start.plusMinutes(i), price, price, price, price));
        }
        return rows;
    }

    private Strategy508TimeExitCandidateService.MinuteBar bar(
            LocalDateTime at, String open, String high, String low, String close) {
        return new Strategy508TimeExitCandidateService.MinuteBar(
                at, bd(open), bd(high), bd(low), bd(close));
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
