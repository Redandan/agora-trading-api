package com.agora.service.trading;

import com.agora.config.properties.TradingSignalSourceProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties.ExecutionMode;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtStrategyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionedProfitStartCohortServiceTest {

    private static final String COMMIT = "f91eb0d69baa3720c40eabe9164a979383ba99b6";

    @Test
    void disabledOrUnversionedRuntimeFailsClosed() {
        VersionedProfitStartCohortService service = service(
                props(ExecutionMode.BTC_BASE_DRY_RUN, new BigDecimal("10.0")),
                new MockEnvironment());

        VersionedProfitStartCohortService.Snapshot snapshot = service.snapshot();

        assertThat(snapshot.identityReady()).isFalse();
        assertThat(snapshot.cohortId()).isEqualTo("NOT_STARTED");
        assertThat(snapshot.identityBlockers()).contains(
                "COHORT_NOT_ENABLED",
                "DEPLOYED_CODE_COMMIT_UNAVAILABLE",
                "EFFECTIVE_FROM_NOT_CONFIGURED",
                "EXECUTION_MODE_NOT_TINY_LIVE");
        assertThat(service.liveExecutionBlocker(485L, "BTCUSDT", "BTC_BASE_DRY_RUN"))
                .startsWith("VERSIONED_PROFIT_START_COHORT_NOT_READY:");
        assertThat(service.currentCohortMarker()).isBlank();
    }

    @Test
    void completeRuntimeIdentityDerivesStableCohortAndKeepsExactNetFailClosed() {
        MockEnvironment environment = readyEnvironment();
        VersionedProfitStartCohortService service = service(
                props(ExecutionMode.BTC_BASE_LIVE_MICRO, new BigDecimal("10.0")), environment);

        VersionedProfitStartCohortService.Snapshot first = service.snapshot();
        VersionedProfitStartCohortService.Snapshot second = service.snapshot();

        assertThat(first.identityReady()).isTrue();
        assertThat(first.activationReady()).isFalse();
        assertThat(first.state()).isEqualTo("COHORT_IDENTITY_READY_ACTIVATION_BLOCKED");
        assertThat(first.cohortId()).startsWith("VPSTART1-485-BTCUSDT-F91EB0D6-");
        assertThat(first.cohortId()).isEqualTo(second.cohortId());
        assertThat(first.strategyFamily()).isEqualTo("SCORE_BUY_V2");
        assertThat(first.codeCommit()).isEqualTo(COMMIT);
        assertThat(first.configSha256()).matches("[0-9a-f]{64}");
        assertThat(first.modelVersion()).isEqualTo("local-tradingview-parity-v1");
        assertThat(first.signalSource()).isEqualTo("LOCAL_TRADINGVIEW");
        assertThat(first.executionMode()).isEqualTo("BTC_BASE_LIVE_MICRO");
        assertThat(first.legacyRowsExcluded()).isTrue();
        assertThat(first.exactNetAcceptanceAllowed()).isFalse();
        assertThat(first.activationBlockers())
                .containsExactly(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER);
        assertThat(first.finalAcceptanceBlockers())
                .containsExactly(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER);
        assertThat(service.liveExecutionBlocker(485L, "BTCUSDT", "BTC_BASE_LIVE_MICRO"))
                .isNull();
        assertThat(service.currentCohortMarker()).isEqualTo("|COHORT:" + first.cohortId());

        Map<String, Object> context = new LinkedHashMap<>();
        service.bind(context);
        assertThat(context).containsKey("versionedProfitStartCohort");
        assertThat(service.status())
                .contains("\"identityReady\" : true")
                .contains("\"activationReady\" : false")
                .contains("\"decimalScale\" : 4")
                .contains("\"roundingMode\" : \"HALF_EVEN\"")
                .contains("\"boundaryProbeNormalizedExpectedR\" : \"0.2000\"")
                .contains("\"boundaryProbeNormalizedMinExpectedR\" : \"0.2000\"")
                .contains("\"boundaryProbePassed\" : true")
                .contains("\"invalidInputsFailClosed\" : true")
                .contains("\"currentCohortClosedEpisodes\" : 0")
                .contains(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER);
    }

    @Test
    void runtimeConfigChangeProducesDifferentHashAndCohortIdWithoutResettingEffectiveFrom() {
        MockEnvironment environment = readyEnvironment();
        VersionedProfitStartCohortService baseline = service(
                props(ExecutionMode.LIVE_MICRO, new BigDecimal("10.0")), environment);
        VersionedProfitStartCohortService changed = service(
                props(ExecutionMode.LIVE_MICRO, new BigDecimal("5.0")), environment);

        assertThat(changed.snapshot().effectiveFrom()).isEqualTo(baseline.snapshot().effectiveFrom());
        assertThat(changed.snapshot().configSha256()).isNotEqualTo(baseline.snapshot().configSha256());
        assertThat(changed.snapshot().cohortId()).isNotEqualTo(baseline.snapshot().cohortId());
    }

    @Test
    void operationalBootstrapArmDoesNotChangeIdentityOrConfigHash() {
        MockEnvironment disarmedEnv = readyEnvironment();
        MockEnvironment armedEnv = readyEnvironment()
                .withProperty("trading.versioned-profit-start.cohort.bootstrap-order-authority-enabled", "true")
                .withProperty("trading.versioned-profit-start.cohort.bootstrap-oco-authority-enabled", "true");
        VersionedProfitStartCohortService disarmed = service(
                props(ExecutionMode.LIVE_MICRO, new BigDecimal("10.0")), disarmedEnv);
        VersionedProfitStartCohortService armed = service(
                props(ExecutionMode.LIVE_MICRO, new BigDecimal("10.0")), armedEnv);

        assertThat(disarmed.snapshot().bootstrapOrderAuthorityArmed()).isFalse();
        assertThat(armed.snapshot().bootstrapOrderAuthorityArmed()).isTrue();
        assertThat(disarmed.snapshot().bootstrapOcoAuthorityArmed()).isFalse();
        assertThat(armed.snapshot().bootstrapOcoAuthorityArmed()).isTrue();
        assertThat(armed.snapshot().configSha256()).isEqualTo(disarmed.snapshot().configSha256());
        assertThat(armed.snapshot().cohortId()).isEqualTo(disarmed.snapshot().cohortId());
    }

    @Test
    void futureEffectiveFromFailsClosed() {
        MockEnvironment environment = readyEnvironment()
                .withProperty("trading.versioned-profit-start.cohort.effective-from",
                        Instant.now().plusSeconds(3600).toString());
        VersionedProfitStartCohortService service = service(
                props(ExecutionMode.LIVE_MICRO, new BigDecimal("10.0")), environment);

        assertThat(service.snapshot().identityReady()).isFalse();
        assertThat(service.snapshot().identityBlockers()).contains("EFFECTIVE_FROM_IN_FUTURE");
    }

    private VersionedProfitStartCohortService service(TradingViewLocalSignalProperties props,
                                                       MockEnvironment environment) {
        TradingSignalSourcePolicy policy = new TradingSignalSourcePolicy(new TradingSignalSourceProperties(
                "LOCAL_TRADINGVIEW", false, false, "", BigDecimal.ZERO));
        BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
        when(strategyRepository.findById(485L)).thenReturn(Optional.of(strategy()));
        VersionedProfitStartActivationReadinessService readiness =
                mock(VersionedProfitStartActivationReadinessService.class);
        when(readiness.assess(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new VersionedProfitStartActivationReadinessService.Readiness(
                        "ACTIVATION_BLOCKED", true, false, false, false, false, false, false,
                        CurrentCohortCanonicalMetricReader.Classification.NOT_MEASURABLE,
                        0, 0, 0, java.util.List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER)));
        return new VersionedProfitStartCohortService(
                props, policy, strategyRepository, new ObjectMapper(), environment, readiness);
    }

    private MockEnvironment readyEnvironment() {
        return new MockEnvironment()
                .withProperty("trading.versioned-profit-start.cohort.enabled", "true")
                .withProperty("trading.versioned-profit-start.cohort.effective-from",
                        "2026-07-17T00:00:00Z")
                .withProperty("app.git.commit", COMMIT);
    }

    private TradingViewLocalSignalProperties props(ExecutionMode mode, BigDecimal maxNotional) {
        return new TradingViewLocalSignalProperties(
                true,
                485L,
                "BTCUSDT",
                "1d",
                "binance",
                320,
                3,
                72,
                new BigDecimal("5.0"),
                maxNotional,
                mode,
                false,
                true,
                false,
                1,
                1,
                1,
                new BigDecimal("0.0300"),
                new BigDecimal("0.1200"),
                new BigDecimal("25.0"));
    }

    private BtStrategy strategy() {
        BtStrategy strategy = new BtStrategy();
        strategy.setId(485L);
        strategy.setName("SCORE_BUY_V2-BTC-1d-MLGated-v1");
        strategy.setStrategyType("SCORE_BUY_V2");
        strategy.setConfigJson("{\"notifyOnly\":true,\"runInterval\":\"1d\"}");
        strategy.setEnabled(true);
        strategy.setSymbols("BTCUSDT");
        strategy.setKlineSource("okx");
        strategy.setConfigFingerprint("abc123");
        strategy.setAlphaSource("TradingView Pine parity");
        strategy.setTriggerConditions("daily score buy");
        return strategy;
    }
}
