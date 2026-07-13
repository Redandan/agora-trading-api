package com.agora.service.backtest;

import com.agora.model.MarketIndicatorHistory;
import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OiFundingDivergenceStrategyTest {

    @Mock
    private MarketIndicatorHistoryRepository indicatorRepository;

    private OiFundingDivergenceStrategy strategy;

    @BeforeEach
    void setUp() {
        LiveSignalContext.clear();
        strategy = new OiFundingDivergenceStrategy(indicatorRepository, new ObjectMapper());
        lenient().when(indicatorRepository.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                eq("BTCUSDT"), any(), any())).thenReturn(List.of());
    }

    @Test
    void seesCollectorRowsAddedAfterInitialCacheLoadWithoutRestart() {
        LocalDateTime barHour = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        AtomicBoolean collectorCompleted = new AtomicBoolean(false);
        when(indicatorRepository.findTopCleanInCapturedAtWindow(
                eq("BTCUSDT"), any(), any(), any())).thenAnswer(invocation -> {
            if (!collectorCompleted.get()) {
                return Optional.empty();
            }
            String indicator = invocation.getArgument(1);
            if ("funding_rate".equals(indicator)) {
                return Optional.of(indicator(indicator, barHour.plusMinutes(1), 0.0));
            }
            if ("oi_change_pct_1h".equals(indicator)) {
                return Optional.of(indicator(indicator, barHour.plusMinutes(1), 0.5));
            }
            return Optional.empty();
        });

        StrategyContext context = context(barHour);

        assertThat(strategy.evaluate(context, new HashMap<>())).isEqualTo(StrategySignal.HOLD);

        collectorCompleted.set(true);

        assertThat(strategy.evaluate(context, new HashMap<>())).isEqualTo(StrategySignal.BUY);
        verify(indicatorRepository, times(2)).findTopCleanInCapturedAtWindow(
                "BTCUSDT", "funding_rate", barHour.minusHours(1), barHour.plusHours(1));
    }

    @Test
    void historicalMissCoveredByBulkSnapshotDoesNotQueryPerBar() {
        LocalDateTime historicalHour = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(30).truncatedTo(ChronoUnit.HOURS);

        assertThat(strategy.evaluate(context(historicalHour), new HashMap<>()))
                .isEqualTo(StrategySignal.HOLD);

        verify(indicatorRepository, never()).findTopCleanInCapturedAtWindow(any(), any(), any(), any());
    }

    @Test
    void strictFreshnessUsesLatestObservationAtOrBeforeBarClose() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);
        when(indicatorRepository.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                eq("BTCUSDT"), any(), eq(decisionTime))).thenAnswer(invocation -> {
            String indicator = invocation.getArgument(1);
            if ("funding_rate".equals(indicator)) {
                return Optional.of(indicator(indicator, decisionTime.minusMinutes(59), 0.00006548));
            }
            if ("oi_change_pct_1h".equals(indicator)) {
                return Optional.of(indicator(indicator, decisionTime.minusMinutes(59), 0.18792503));
            }
            return Optional.empty();
        });

        assertThat(strategy.evaluate(context(barOpen), strictConfig())).isEqualTo(StrategySignal.BUY);

        Map<String, Object> details = LiveSignalContext.getDetails();
        assertThat(details)
                .containsEntry("feature_reference_time", decisionTime.toString())
                .containsEntry("feature_funding_rate_age_minutes", 59L)
                .containsEntry("feature_funding_rate_freshness", "FRESH")
                .containsEntry("feature_oi_change_pct_1h_age_minutes", 59L)
                .containsEntry("feature_oi_change_pct_1h_freshness", "FRESH")
                .containsEntry("feature_dex_wbtc_net_flow_usd_1h_freshness",
                        "DISABLED_NOT_REQUIRED_NO_SAMPLE")
                .containsEntry("feature_freshness_clear", true)
                .containsEntry("feature_freshness_blockers", "NONE");
    }

    @Test
    void productionAudit77413RemainsBuyWithLatestFreshInputs() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);
        when(indicatorRepository.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                eq("BTCUSDT"), any(), eq(decisionTime))).thenAnswer(invocation -> {
            String indicator = invocation.getArgument(1);
            if ("funding_rate".equals(indicator)) {
                return Optional.of(indicator(indicator, decisionTime.minusMinutes(59), 0.00006548));
            }
            if ("oi_change_pct_1h".equals(indicator)) {
                return Optional.of(indicator(indicator, decisionTime.minusMinutes(59), 0.18792503));
            }
            if ("funding_rate_cex_dex_spread".equals(indicator)) {
                return Optional.of(indicator(indicator, decisionTime.minusMinutes(59), -0.00124182));
            }
            return Optional.empty();
        });

        StrategyContext context = context(barOpen, 64025.0, 817.98692816,
                415.83168664, 62705.4065);

        assertThat(strategy.evaluate(context, strictConfig())).isEqualTo(StrategySignal.BUY);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("funding_rate", 0.00006548)
                .containsEntry("oi_change_pct", 0.18792503)
                .containsEntry("feature_volume_value", 817.98692816)
                .containsEntry("feature_volume_ma_value", 415.83168664)
                .containsEntry("feature_sma200_value", 62705.4065)
                .containsEntry("feature_freshness_clear", true)
                .containsEntry("trigger_reason", "all_gates_passed");
    }

    @Test
    void strictFreshnessUsesRowProviderMetadataAndEffectiveTimestamp() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);
        when(indicatorRepository.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                eq("BTCUSDT"), any(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1);
            if (!"funding_rate".equals(name) && !"oi_change_pct_1h".equals(name)) {
                return List.of();
            }
            MarketIndicatorHistory row = indicator(name, decisionTime.minusMinutes(55),
                    "funding_rate".equals(name) ? 0.00005 : 0.2);
            row.setMetadataJson("{\"providerPath\":\"TEST_PROVIDER_" + name
                    + "\",\"effectiveCapturedAt\":\"" + decisionTime.minusMinutes(60) + "\"}");
            return List.of(row);
        });

        assertThat(strategy.evaluate(context(barOpen), strictConfig())).isEqualTo(StrategySignal.BUY);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("feature_funding_rate_provider_path", "TEST_PROVIDER_funding_rate")
                .containsEntry("feature_funding_rate_source_evidence", "ROW_METADATA")
                .containsEntry("feature_funding_rate_age_minutes", 60L)
                .containsEntry("feature_oi_change_pct_1h_provider_path",
                        "TEST_PROVIDER_oi_change_pct_1h")
                .containsEntry("feature_oi_change_pct_1h_age_minutes", 60L);
    }

    @Test
    void strictFreshnessBlocksStaleCoreFeature() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);
        when(indicatorRepository.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                eq("BTCUSDT"), any(), eq(decisionTime))).thenAnswer(invocation -> {
            String indicator = invocation.getArgument(1);
            if ("funding_rate".equals(indicator)) {
                return Optional.of(indicator(indicator, decisionTime.minusMinutes(91), 0.00005));
            }
            if ("oi_change_pct_1h".equals(indicator)) {
                return Optional.of(indicator(indicator, decisionTime.minusMinutes(59), 0.2));
            }
            return Optional.empty();
        });

        assertThat(strategy.evaluate(context(barOpen), strictConfig())).isEqualTo(StrategySignal.HOLD);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("feature_funding_rate_freshness", "STALE_REQUIRED")
                .containsEntry("feature_freshness_clear", false)
                .containsEntry("feature_freshness_blockers", "FUNDING_RATE_STALE")
                .containsEntry("hold_reason", "feature_freshness_blocked");
    }

    @Test
    void strictFreshnessUsesExactDurationBeyondNinetyMinuteBoundary() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);
        when(indicatorRepository.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                eq("BTCUSDT"), any(), eq(decisionTime))).thenAnswer(invocation -> {
            String indicator = invocation.getArgument(1);
            if ("funding_rate".equals(indicator)) {
                return Optional.of(indicator(indicator,
                        decisionTime.minusMinutes(90).minusSeconds(1), 0.00005));
            }
            if ("oi_change_pct_1h".equals(indicator)) {
                return Optional.of(indicator(indicator, decisionTime.minusMinutes(59), 0.2));
            }
            return Optional.empty();
        });

        assertThat(strategy.evaluate(context(barOpen), strictConfig())).isEqualTo(StrategySignal.HOLD);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("feature_funding_rate_age_minutes", 90L)
                .containsEntry("feature_funding_rate_age_seconds", 5401L)
                .containsEntry("feature_funding_rate_freshness", "STALE_REQUIRED")
                .containsEntry("feature_freshness_blockers", "FUNDING_RATE_STALE");
    }

    @Test
    void strictFreshnessBlocksMissingOiEvenWhenFundingExists() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);
        when(indicatorRepository.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                eq("BTCUSDT"), any(), eq(decisionTime))).thenAnswer(invocation -> {
            String indicator = invocation.getArgument(1);
            return "funding_rate".equals(indicator)
                    ? Optional.of(indicator(indicator, decisionTime.minusMinutes(30), 0.00005))
                    : Optional.empty();
        });

        assertThat(strategy.evaluate(context(barOpen), strictConfig())).isEqualTo(StrategySignal.HOLD);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("feature_oi_change_pct_1h_freshness", "MISSING_REQUIRED")
                .containsEntry("feature_freshness_blockers", "OI_CHANGE_PCT_1H_MISSING")
                .containsEntry("hold_reason", "feature_freshness_blocked");
    }

    @Test
    void enabledDexFilterFailsClosedWhenDexObservationIsMissing() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);
        when(indicatorRepository.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                eq("BTCUSDT"), any(), eq(decisionTime))).thenAnswer(invocation -> {
            String indicator = invocation.getArgument(1);
            if ("funding_rate".equals(indicator)) {
                return Optional.of(indicator(indicator, decisionTime.minusMinutes(30), 0.00005));
            }
            if ("oi_change_pct_1h".equals(indicator)) {
                return Optional.of(indicator(indicator, decisionTime.minusMinutes(30), 0.2));
            }
            return Optional.empty();
        });
        Map<String, Object> config = strictConfig();
        config.put("dexFlowFilter", true);

        assertThat(strategy.evaluate(context(barOpen), config)).isEqualTo(StrategySignal.HOLD);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("feature_dex_wbtc_net_flow_usd_1h_freshness", "MISSING_REQUIRED")
                .containsEntry("feature_freshness_blockers", "DEX_WBTC_FLOW_MISSING")
                .containsEntry("hold_reason", "feature_freshness_blocked");
    }

    @Test
    void strictFreshnessRejectsFutureObservationDefensively() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);
        when(indicatorRepository.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                eq("BTCUSDT"), any(), eq(decisionTime))).thenAnswer(invocation -> {
            String indicator = invocation.getArgument(1);
            return Optional.of(indicator(indicator, decisionTime.plusMinutes(1), 0.1));
        });

        assertThat(strategy.evaluate(context(barOpen), strictConfig())).isEqualTo(StrategySignal.HOLD);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("feature_funding_rate_freshness", "MISSING_REQUIRED")
                .containsEntry("feature_oi_change_pct_1h_freshness", "MISSING_REQUIRED")
                .containsEntry("hold_reason", "feature_freshness_blocked");
    }

    @Test
    void strictFreshnessRejectsProviderTimestampAfterDecisionEvenWhenRowWasStoredEarlier() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);
        MarketIndicatorHistory funding = indicator(
                "funding_rate", decisionTime.minusMinutes(1), 0.00005);
        funding.setMetadataJson("{\"providerPath\":\"TEST_PROVIDER\","
                + "\"effectiveCapturedAt\":\"" + decisionTime.plusMinutes(1) + "\"}");
        MarketIndicatorHistory oi = indicator(
                "oi_change_pct_1h", decisionTime.minusMinutes(30), 0.2);

        when(indicatorRepository.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                eq("BTCUSDT"), any(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1);
            if ("funding_rate".equals(name)) return List.of(funding);
            if ("oi_change_pct_1h".equals(name)) return List.of(oi);
            return List.of();
        });
        when(indicatorRepository.findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
                eq("BTCUSDT"), eq("funding_rate"), eq(decisionTime)))
                .thenReturn(Optional.of(funding));

        assertThat(strategy.evaluate(context(barOpen), strictConfig())).isEqualTo(StrategySignal.HOLD);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("feature_funding_rate_freshness", "FUTURE_REQUIRED")
                .containsEntry("feature_funding_rate_source_evidence", "ROW_METADATA")
                .containsEntry("feature_freshness_blockers", "FUNDING_RATE_FUTURE")
                .containsEntry("hold_reason", "feature_freshness_blocked");
    }

    @Test
    void strictFreshnessDoesNotUseObservationStoredAfterDecision() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);

        MarketIndicatorHistory priorFunding = indicator(
                "funding_rate", decisionTime.minusMinutes(59), 0.00005);
        MarketIndicatorHistory lateFunding = indicator(
                "funding_rate", decisionTime.plusMinutes(1), 0.0005);
        lateFunding.setMetadataJson("{\"providerPath\":\"LATE_PROVIDER\","
                + "\"effectiveCapturedAt\":\"" + decisionTime.minusMinutes(1) + "\"}");
        MarketIndicatorHistory oi = indicator(
                "oi_change_pct_1h", decisionTime.minusMinutes(59), 0.2);

        when(indicatorRepository.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                eq("BTCUSDT"), any(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1);
            if ("funding_rate".equals(name)) return List.of(priorFunding, lateFunding);
            if ("oi_change_pct_1h".equals(name)) return List.of(oi);
            return List.of();
        });

        assertThat(strategy.evaluate(context(barOpen), strictConfig())).isEqualTo(StrategySignal.BUY);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("funding_rate", 0.00005)
                .containsEntry("feature_funding_rate_captured_at",
                        decisionTime.minusMinutes(59).toString())
                .containsEntry("feature_funding_rate_available_at",
                        decisionTime.minusMinutes(59).toString())
                .containsEntry("feature_funding_rate_age_minutes", 59L)
                .containsEntry("feature_funding_rate_provider_path", "MIH:OKX_PUBLIC_FUNDING_RATE");
    }

    @Test
    void strictFreshnessDoesNotUseObservationAvailableAfterDecision() {
        LocalDateTime barOpen = LocalDateTime.now(ZoneOffset.UTC)
                .minusHours(4).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime decisionTime = barOpen.plusHours(4);

        MarketIndicatorHistory priorFunding = indicator(
                "funding_rate", decisionTime.minusMinutes(119), 0.00005);
        MarketIndicatorHistory lateFunding = indicator(
                "funding_rate", decisionTime.minusMinutes(1), 0.0005);
        lateFunding.setMetadataJson("{\"providerPath\":\"LATE_PROVIDER\","
                + "\"effectiveCapturedAt\":\"" + decisionTime.minusMinutes(2) + "\","
                + "\"availableAt\":\"" + decisionTime.plusMinutes(1) + "\"}");
        MarketIndicatorHistory oi = indicator(
                "oi_change_pct_1h", decisionTime.minusMinutes(59), 0.2);

        when(indicatorRepository.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                eq("BTCUSDT"), any(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1);
            if ("funding_rate".equals(name)) return List.of(priorFunding, lateFunding);
            if ("oi_change_pct_1h".equals(name)) return List.of(oi);
            return List.of();
        });

        assertThat(strategy.evaluate(context(barOpen), strictConfig())).isEqualTo(StrategySignal.HOLD);
        assertThat(LiveSignalContext.getDetails())
                .containsEntry("feature_funding_rate_captured_at",
                        decisionTime.minusMinutes(119).toString())
                .containsEntry("feature_funding_rate_age_minutes", 119L)
                .containsEntry("feature_funding_rate_freshness", "STALE_REQUIRED")
                .containsEntry("feature_freshness_blockers", "FUNDING_RATE_STALE")
                .containsEntry("hold_reason", "feature_freshness_blocked");
    }

    private StrategyContext context(LocalDateTime barHour) {
        return context(barHour, 101.0, 200.0, 100.0, 90.0);
    }

    private StrategyContext context(LocalDateTime barHour,
                                    double currentClose,
                                    double currentVolume,
                                    double currentVolumeMa,
                                    double currentSma200) {
        List<MdKline> bars = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            MdKline bar = new MdKline();
            bar.setSymbol("BTCUSDT");
            bar.setIntervalCode("4h");
            bar.setOpenTime(barHour.minusHours((20L - i) * 4));
            bar.setCloseTime(bar.getOpenTime().plusHours(4));
            bar.setOpenPrice(BigDecimal.valueOf(100));
            bar.setHighPrice(BigDecimal.valueOf(102));
            bar.setLowPrice(BigDecimal.valueOf(99));
            bar.setClosePrice(BigDecimal.valueOf(101));
            bar.setVolume(BigDecimal.valueOf(200));
            bars.add(bar);
        }
        bars.get(20).setClosePrice(BigDecimal.valueOf(currentClose));
        bars.get(20).setVolume(BigDecimal.valueOf(currentVolume));

        double[] volumeMa = filled(21, 100);
        double[] sma200 = filled(21, 90);
        volumeMa[20] = currentVolumeMa;
        sma200[20] = currentSma200;
        double[] rsi = filled(21, 40);
        Map<String, double[]> indicators = Map.of(
                "volumeMa", volumeMa,
                "sma200", sma200,
                "rsi", rsi);
        return new StrategyContext(20, bars.get(20), bars.get(19), bars, indicators);
    }

    private Map<String, Object> strictConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("marketFeatureFreshnessFailClosed", true);
        config.put("marketFeatureReferenceTimeMode", "BAR_CLOSE");
        config.put("fundingMaxAgeMinutes", 90);
        config.put("oiMaxAgeMinutes", 90);
        config.put("dexFlowMaxAgeMinutes", 90);
        config.put("spreadMaxAgeMinutes", 90);
        return config;
    }

    private MarketIndicatorHistory indicator(String name, LocalDateTime capturedAt, double value) {
        MarketIndicatorHistory row = new MarketIndicatorHistory();
        row.setSymbol("BTCUSDT");
        row.setIndicator(name);
        row.setCapturedAt(capturedAt);
        row.setValue(BigDecimal.valueOf(value));
        return row;
    }

    private double[] filled(int size, double value) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = value;
        }
        return values;
    }
}
