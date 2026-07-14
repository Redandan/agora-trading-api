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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    void secondEntryOnSameUtcDayIsSkippedEvenAfterFirstPositionExits() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(decision, 181, bd("100"));
        bars.set(30, bar(decision.plusMinutes(30), "100", "107", "100", "106"));

        ObjectNode report = service.analyzePreparedForTest("BTCUSDT", decision.plusHours(25),
                List.of(entry(decision), entry(decision.plusHours(1))), bars, 10);

        assertThat(report.path("eligibleEvents").asInt()).isEqualTo(1);
        assertThat(report.path("dailyCapSkippedEvents").asInt()).isEqualTo(1);
        assertThat(report.path("outcomeBreakdown").path("SKIPPED_DAILY_ORDER_CAP").asInt())
                .isEqualTo(1);
    }

    @Test
    void entryAtExactPriorExitMinuteFailsClosedAsOverlap() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(decision, 2881, bd("100"));

        ObjectNode report = service.analyzePreparedForTest("BTCUSDT", decision.plusHours(49),
                List.of(entry(decision), entry(decision.plusHours(24))), bars, 10);

        assertThat(report.path("overlapSkippedEvents").asInt()).isEqualTo(1);
        assertThat(report.path("eligibleEvents").asInt()).isEqualTo(1);
    }

    @Test
    void recentConcentratedSampleCannotPassCalendarOrIndependentBenchmarkGate() {
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
        assertThat(report.path("benchmark72hPairedEvents").asInt()).isLessThan(30);
        assertThat(report.path("promotionGates").path("minimumCalendarSpan").asBoolean()).isFalse();
        assertThat(report.path("walkForward").path("nonEmptyFolds").asInt()).isLessThan(5);
        assertThat(report.path("historicalGatePassed").asBoolean()).isFalse();
        assertThat(report.path("sampleStatus").asText()).isEqualTo("HISTORICAL_SAMPLE_UNTRUSTED");
        assertThat(report.path("verdict").asText())
                .isEqualTo("REJECTED_NO_LIVE_NO_MORE_PARAMETER_TUNING");
        assertThat(report.path("livePromotionAllowed").asBoolean()).isFalse();
    }

    @Test
    void distributedProfitableSampleCanPassStrictHistoricalGates() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 1, 0, 0);
        PreparedSample sample = distributedProfitableSample(now);

        ObjectNode report = service.analyzePreparedForTest(
                "BTCUSDT", now, sample.entries(), sample.bars(), 5);

        assertThat(report.path("finalizedEvents").asInt()).isEqualTo(30);
        assertThat(report.path("benchmark72hPairedEvents").asInt()).isEqualTo(30);
        assertThat(report.path("walkForward").path("positiveFolds").asInt()).isEqualTo(5);
        assertThat(report.path("walkForward").path("nonEmptyFolds").asInt()).isEqualTo(5);
        assertThat(report.path("candidateImprovementVs72hPp").asDouble()).isGreaterThan(0.5);
        assertThat(report.path("promotionGates").path("outcomeFinalizationRate").asBoolean()).isTrue();
        assertThat(report.path("historicalGatePassed").asBoolean()).isTrue();
    }

    @Test
    void unresolvedAttritionCannotBeHiddenBehindThirtyWinningEvents() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 1, 0, 0);
        PreparedSample sample = distributedProfitableSample(now);
        List<Strategy508TimeExitCandidateService.EntryIntent> entries = new ArrayList<>(sample.entries());
        for (int i = 0; i < 70; i++) {
            entries.add(entry(now.minusHours(47).plusMinutes(i)));
        }

        ObjectNode report = service.analyzePreparedForTest("BTCUSDT", now, entries, sample.bars(), 5);

        assertThat(report.path("finalizedEvents").asInt()).isEqualTo(30);
        assertThat(report.path("matureUnresolvedEvents").asInt()).isEqualTo(70);
        assertThat(report.path("outcomeBreakdown").path("MISSING_NEXT_1M_ENTRY").asInt())
                .isEqualTo(70);
        assertThat(report.path("promotionGates").path("outcomeFinalizationRate").asBoolean()).isFalse();
        assertThat(report.path("historicalGatePassed").asBoolean()).isFalse();
        assertThat(report.path("replayQualityStatus").asText()).isEqualTo("BLOCKED_OUTCOME_ATTRITION");
    }

    @Test
    void singleMissingMinuteMayFinalizeButCannotPassCompleteLatticePromotionGate() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(decision, 1440, bd("100"));
        bars.remove(100);
        bars.add(bar(decision.plusHours(24), "101", "101", "101", "101"));

        Strategy508TimeExitCandidateService.EventResult result = service.simulateSingle(
                entry(decision), bars, decision.plusHours(25));
        ObjectNode report = service.analyzePreparedForTest(
                "BTCUSDT", decision.plusHours(25), List.of(entry(decision)), bars, 5);

        assertThat(result.finalized()).isTrue();
        assertThat(result.coverage()).isGreaterThan(0.99).isLessThan(1.0);
        assertThat(report.path("promotionGates").path("completeMinuteCoverage").asBoolean()).isFalse();
        assertThat(report.path("replayQualityStatus").asText())
                .isEqualTo("BLOCKED_INCOMPLETE_MINUTE_LATTICE");
    }

    @Test
    void offGridBarCannotReplaceMissingCanonicalMinute() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(
                decision, 1440, bd("100"));
        bars.remove(100);
        bars.add(bar(decision.plusMinutes(100).plusSeconds(30),
                "100", "107", "100", "106"));
        bars.add(bar(decision.plusHours(24), "101", "101", "101", "101"));

        Strategy508TimeExitCandidateService.EventResult result = service.simulateSingle(
                entry(decision), bars, decision.plusHours(25));

        assertThat(result.finalized()).isFalse();
        assertThat(result.outcome()).isEqualTo("INVALID_1M_SOURCE_ROW");
    }

    @Test
    void invalidOhlcBarCannotReplaceMissingCanonicalMinute() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(
                decision, 1440, bd("100"));
        bars.set(100, bar(decision.plusMinutes(100), "100", "99", "87", "100"));
        bars.add(bar(decision.plusHours(24), "101", "101", "101", "101"));

        Strategy508TimeExitCandidateService.EventResult result = service.simulateSingle(
                entry(decision), bars, decision.plusHours(25));

        assertThat(result.finalized()).isFalse();
        assertThat(result.outcome()).isEqualTo("INVALID_1M_SOURCE_ROW");
    }

    @Test
    void completeCanonicalLatticeWithExtraRejectedRowStillFailsClosed() {
        LocalDateTime decision = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = flatBars(
                decision, 1440, bd("100"));
        bars.add(bar(decision.plusMinutes(10).plusSeconds(30),
                "100", "107", "100", "106"));
        bars.add(bar(decision.plusHours(24), "101", "101", "101", "101"));

        Strategy508TimeExitCandidateService.EventResult result = service.simulateSingle(
                entry(decision), bars, decision.plusHours(25));
        ObjectNode report = service.analyzePreparedForTest(
                "BTCUSDT", decision.plusHours(25), List.of(entry(decision)), bars, 5);

        assertThat(result.finalized()).isFalse();
        assertThat(result.outcome()).isEqualTo("INVALID_1M_SOURCE_ROW");
        assertThat(report.path("rejectedMinuteRows").asLong()).isEqualTo(1);
        assertThat(report.path("promotionGates").path("sourceMinuteRowsValid").asBoolean())
                .isFalse();
        assertThat(report.path("replayQualityStatus").asText())
                .isEqualTo("BLOCKED_INVALID_MINUTE_SOURCE_ROWS");
    }

    @Test
    void effectiveConfigHashIsRecursiveOrderIndependentAndChangesWithConfig() {
        Map<String, Object> leftNested = new LinkedHashMap<>();
        leftNested.put("z", 2);
        leftNested.put("a", 1);
        Map<String, Object> rightNested = new LinkedHashMap<>();
        rightNested.put("a", 1);
        rightNested.put("z", 2);
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("nested", leftNested);
        left.put("threshold", "0.55");
        Map<String, Object> right = new LinkedHashMap<>();
        right.put("threshold", "0.55");
        right.put("nested", rightNested);

        String leftHash = Strategy508TimeExitPolicy.effectiveConfigSha256(new ObjectMapper(), left);
        String rightHash = Strategy508TimeExitPolicy.effectiveConfigSha256(new ObjectMapper(), right);
        right.put("threshold", "0.56");
        String changedHash = Strategy508TimeExitPolicy.effectiveConfigSha256(new ObjectMapper(), right);

        assertThat(leftHash).hasSize(64).isEqualTo(rightHash);
        assertThat(changedHash).hasSize(64).isNotEqualTo(leftHash);
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

    private PreparedSample distributedProfitableSample(LocalDateTime now) {
        LocalDateTime first = now.minusDays(354);
        List<Strategy508TimeExitCandidateService.EntryIntent> entries = new ArrayList<>();
        List<Strategy508TimeExitCandidateService.MinuteBar> bars = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            LocalDateTime decision = first.plusDays(i * 12L);
            entries.add(entry(decision));
            for (int minute = 0; minute <= 72 * 60; minute++) {
                LocalDateTime at = decision.plusMinutes(minute);
                BigDecimal price = minute == 24 * 60 ? bd("101") : bd("100");
                bars.add(new Strategy508TimeExitCandidateService.MinuteBar(
                        at, price, price, price, price));
            }
        }
        return new PreparedSample(entries, bars);
    }

    private record PreparedSample(List<Strategy508TimeExitCandidateService.EntryIntent> entries,
                                  List<Strategy508TimeExitCandidateService.MinuteBar> bars) {
    }
}
