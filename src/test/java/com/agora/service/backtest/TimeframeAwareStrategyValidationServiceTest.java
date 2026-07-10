package com.agora.service.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeframeAwareStrategyValidationServiceTest {

    private final TimeframeAwareStrategyValidationService service =
            new TimeframeAwareStrategyValidationService();

    @Test
    void dailyProfileSeparatesParityRecentEdgeAndLongStress() {
        LocalDateTime start = LocalDateTime.parse("2025-01-01T00:00:00");
        List<TimeframeAwareStrategyValidationService.Bar> bars = dailyBars(start, 401, 100.0, 0.10);
        List<TimeframeAwareStrategyValidationService.EntryEvent> events = new ArrayList<>();
        for (int day = 30; day <= 390; day += 30) {
            events.add(event(start.plusDays(day), 100.0 + day * 0.10, "TRADINGVIEW_LOW"));
        }
        events.add(event(start.plusDays(390), 139.0, "TRADINGVIEW_SAME_BAR_SECOND_INTENT"));

        String report = service.render(
                request("1d", 90, 365, true),
                bars,
                events,
                new TimeframeAwareStrategyValidationService.ParityEvidence(
                        "GOLDEN_TRUTH_UNAVAILABLE", false, 0, 14, 0, 0,
                        "GOLDEN_TRUTH_PATH_NOT_CONFIGURED"));

        assertThat(report)
                .contains("boundary=READ_ONLY")
                .contains("profile=SWING_1D")
                .contains("[ENTRY_PARITY]")
                .contains("status=GOLDEN_TRUTH_UNAVAILABLE")
                .contains("actualIntents=14")
                .contains("uniqueEntryBars=13")
                .contains("[RECENT_EDGE]")
                .contains("horizon=1d")
                .contains("horizon=3d")
                .contains("horizon=7d")
                .contains("horizon=14d")
                .contains("minimumIndependentEvents=12")
                .contains("[LONG_STRESS]")
                .contains("absolutePositiveReturnRequired=false")
                .contains("finalAssessment=BLOCKED_ENTRY_PARITY")
                .contains("livePromotionAllowed=false");
    }

    @Test
    void intradayProfileUsesEventHorizonsAndCanOnlyReachShadowReview() {
        LocalDateTime start = LocalDateTime.parse("2026-01-01T00:00:00");
        List<TimeframeAwareStrategyValidationService.Bar> bars = hourlyBars(start, 2401, 100.0, 0.01);
        List<TimeframeAwareStrategyValidationService.EntryEvent> events = new ArrayList<>();
        for (int hour = 0; hour <= 2328; hour += 60) {
            events.add(event(start.plusHours(hour), 100.0 + hour * 0.01, "INTRADAY_BUY"));
        }

        String report = service.render(
                request("1h", 90, 90, false),
                bars,
                events,
                TimeframeAwareStrategyValidationService.ParityEvidence.notApplicable());

        assertThat(report)
                .contains("profile=INTRADAY_1H_4H")
                .contains("status=NOT_APPLICABLE_NON_TRADINGVIEW")
                .contains("horizon=4h")
                .contains("horizon=24h")
                .contains("horizon=72h")
                .contains("minimumIndependentEvents=30")
                .contains("status=POSITIVE_EDGE_CANDIDATE")
                .contains("status=PASS_RISK_SCREEN")
                .contains("finalAssessment=CANDIDATE_FOR_FORWARD_SHADOW_REVIEW_ONLY")
                .contains("recentEdgeLiveAuthorizationAllowed=false");
    }

    @Test
    void longWindowIsRiskVetoWithoutAbsolutePositiveReturnRequirement() {
        LocalDateTime start = LocalDateTime.parse("2025-01-01T00:00:00");
        List<TimeframeAwareStrategyValidationService.Bar> bars = dailyBars(start, 366, 200.0, -0.30);
        List<TimeframeAwareStrategyValidationService.EntryEvent> events = new ArrayList<>();
        for (int day = 0; day <= 330; day += 30) {
            events.add(event(start.plusDays(day), 200.0 - day * 0.30, "FALLING_MARKET_BUY"));
        }

        String report = service.render(
                request("1d", 90, 365, false),
                bars,
                events,
                TimeframeAwareStrategyValidationService.ParityEvidence.notApplicable());

        assertThat(report)
                .contains("absolutePositiveReturnRequired=false")
                .contains("maxDrawdownLimit=20.00%")
                .contains("drawdownVeto=true")
                .contains("status=VETO")
                .contains("longStressLiveAuthorizationAllowed=false");
    }

    private TimeframeAwareStrategyValidationService.Request request(
            String interval, int recentDays, int stressDays, boolean parityRequired) {
        return new TimeframeAwareStrategyValidationService.Request(
                485L, "ScoreBuy", "BTCUSDT", interval, "binance",
                recentDays, stressDays, 0.001, parityRequired);
    }

    private List<TimeframeAwareStrategyValidationService.Bar> dailyBars(
            LocalDateTime start, int count, double startPrice, double step) {
        List<TimeframeAwareStrategyValidationService.Bar> bars = new ArrayList<>();
        for (int day = 0; day < count; day++) {
            double close = startPrice + day * step;
            bars.add(new TimeframeAwareStrategyValidationService.Bar(
                    start.plusDays(day), close * 1.01, close * 0.99, close));
        }
        return bars;
    }

    private List<TimeframeAwareStrategyValidationService.Bar> hourlyBars(
            LocalDateTime start, int count, double startPrice, double step) {
        List<TimeframeAwareStrategyValidationService.Bar> bars = new ArrayList<>();
        for (int hour = 0; hour < count; hour++) {
            double close = startPrice + hour * step;
            bars.add(new TimeframeAwareStrategyValidationService.Bar(
                    start.plusHours(hour), close * 1.001, close * 0.999, close));
        }
        return bars;
    }

    private TimeframeAwareStrategyValidationService.EntryEvent event(
            LocalDateTime time, double price, String reason) {
        return new TimeframeAwareStrategyValidationService.EntryEvent(time, price, reason, 1);
    }
}
