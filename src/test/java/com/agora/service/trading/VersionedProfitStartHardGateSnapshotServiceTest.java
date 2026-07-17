package com.agora.service.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class VersionedProfitStartHardGateSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private final VersionedProfitStartHardGateSnapshotService service =
            new VersionedProfitStartHardGateSnapshotService();

    @Test
    void completeClearSnapshotIsReadyWithZeroClosedSamplesAndStableHash() {
        var inputs = clearInputs();

        var first = service.snapshot(inputs);
        var second = service.snapshot(inputs);

        assertThat(first.decision()).isEqualTo(VersionedProfitStartHardGateSnapshotService.Decision.READY);
        assertThat(first.reasons()).isEmpty();
        assertThat(first.sha256()).matches("[0-9a-f]{64}").isEqualTo(second.sha256());
        assertThat(first.snapshotId()).matches("VPSHG1-[0-9A-F]{20}").isEqualTo(second.snapshotId());
        assertThat(first.deterministicHashInput()).contains("currentCohortClosedEpisodes=0");
        assertThat(first.readOnly()).isTrue();
    }

    @Test
    void noCandidateWaitsWithoutCreatingAReadyDecision() {
        var base = clearInputs();
        var inputs = copy(base, new VersionedProfitStartHardGateSnapshotService.Candidate(
                false, null, false, null, null), base.primaryTrend1h(), base.primaryTrend4h(),
                base.eventRisk(), base.limits(), base.exposure(), base.oco(), base.runtimeWriter());

        var snapshot = service.evaluate(inputs);

        assertThat(snapshot.decision()).isEqualTo(VersionedProfitStartHardGateSnapshotService.Decision.WAIT_MARKET);
        assertThat(snapshot.reasons()).containsExactly("NO_VALID_CANDIDATE");
    }

    @Test
    void missingUnknownStaleMismatchCapsDuplicateBlockAndOverflowAllStop() {
        assertStop(copy(clearInputs(), clearInputs().candidate(), null, clearInputs().primaryTrend4h(),
                clearInputs().eventRisk(), clearInputs().limits(), clearInputs().exposure(), clearInputs().oco(),
                clearInputs().runtimeWriter()), "PRIMARY_TREND_1H_INPUT_MISSING");

        assertStop(copy(clearInputs(), clearInputs().candidate(), unknownGate(), clearInputs().primaryTrend4h(),
                clearInputs().eventRisk(), clearInputs().limits(), clearInputs().exposure(), clearInputs().oco(),
                clearInputs().runtimeWriter()), "PRIMARY_TREND_1H_UNKNOWN");

        assertStop(copy(clearInputs(), clearInputs().candidate(), staleClearGate(), clearInputs().primaryTrend4h(),
                clearInputs().eventRisk(), clearInputs().limits(), clearInputs().exposure(), clearInputs().oco(),
                clearInputs().runtimeWriter()), "PRIMARY_TREND_1H_STALE");

        var mismatched = clearInputs();
        mismatched = new VersionedProfitStartHardGateSnapshotService.Inputs(mismatched.observedAt(), mismatched.snapshotTtl(),
                identity("BTCUSDT"), identity("ETHUSDT"), mismatched.candidate(), mismatched.primaryTrend1h(),
                mismatched.primaryTrend4h(), mismatched.eventRisk(), mismatched.limits(), mismatched.exposure(),
                mismatched.oco(), mismatched.runtimeWriter());
        assertStop(mismatched, "COHORT_IDENTITY_MISMATCH");

        var base = clearInputs();
        var capped = new VersionedProfitStartHardGateSnapshotService.Limits(
                new BigDecimal("5"), new BigDecimal("5"), true, 1, 1, 1, 1,
                true, 1, 1, 1);
        var capSnapshot = service.evaluate(copy(base, base.candidate(), base.primaryTrend1h(), base.primaryTrend4h(),
                blockedGate(), capped, base.exposure(), base.oco(), base.runtimeWriter()));
        assertThat(capSnapshot.decision()).isEqualTo(VersionedProfitStartHardGateSnapshotService.Decision.STOP);
        assertThat(capSnapshot.reasons()).contains(
                "EVENT_RISK_BLOCKED", "DAILY_ORDER_CAP_REACHED", "OPEN_POSITION_CAP_REACHED",
                "STABLE_OPPORTUNITY_DUPLICATE", "CURRENT_COHORT_SEQUENTIAL_LOSS_CAP_REACHED");

        var overflow = new VersionedProfitStartHardGateSnapshotService.Exposure(
                new BigDecimal("999999999999999999999999999999999999999"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                new BigDecimal("999999999999999999999999999999999999999"));
        assertStop(copy(base, base.candidate(), base.primaryTrend1h(), base.primaryTrend4h(), base.eventRisk(),
                base.limits(), overflow, base.oco(), base.runtimeWriter()),
                "EXPOSURE_MISSING_MALFORMED_OR_OVERFLOW");
    }

    @Test
    void negativeScaleNotionalOverflowStopsWithExistingMalformedReason() {
        var base = clearInputs();
        var overflowingOrder = limitsWithNotional(base, new BigDecimal("1E+1000"), new BigDecimal("5"));
        var overflowingMinimum = limitsWithNotional(base, new BigDecimal("5"), new BigDecimal("1E+1000"));

        assertStop(copy(base, base.candidate(), base.primaryTrend1h(), base.primaryTrend4h(), base.eventRisk(),
                overflowingOrder, base.exposure(), base.oco(), base.runtimeWriter()),
                "ORDER_SIZE_MISSING_MALFORMED_OR_OVERFLOW");
        assertStop(copy(base, base.candidate(), base.primaryTrend1h(), base.primaryTrend4h(), base.eventRisk(),
                overflowingMinimum, base.exposure(), base.oco(), base.runtimeWriter()),
                "ORDER_SIZE_MISSING_MALFORMED_OR_OVERFLOW");
    }

    @Test
    void negativeScaleIntegerDigitBoundaryAndReasonableFractionRemainClear() {
        var base = clearInputs();
        var boundedNegativeScale = limitsWithNotional(base, new BigDecimal("1E+37"), new BigDecimal("1E+37"));
        var overflowingNegativeScale = limitsWithNotional(base, new BigDecimal("1E+38"), new BigDecimal("1E+37"));
        var reasonableFraction = limitsWithNotional(base, new BigDecimal("5.125"), new BigDecimal("5.00"));

        assertThat(service.evaluate(copy(base, base.candidate(), base.primaryTrend1h(), base.primaryTrend4h(),
                base.eventRisk(), boundedNegativeScale, base.exposure(), base.oco(), base.runtimeWriter())).decision())
                .isEqualTo(VersionedProfitStartHardGateSnapshotService.Decision.READY);
        assertStop(copy(base, base.candidate(), base.primaryTrend1h(), base.primaryTrend4h(), base.eventRisk(),
                overflowingNegativeScale, base.exposure(), base.oco(), base.runtimeWriter()),
                "ORDER_SIZE_MISSING_MALFORMED_OR_OVERFLOW");
        assertThat(service.evaluate(copy(base, base.candidate(), base.primaryTrend1h(), base.primaryTrend4h(),
                base.eventRisk(), reasonableFraction, base.exposure(), base.oco(), base.runtimeWriter())).decision())
                .isEqualTo(VersionedProfitStartHardGateSnapshotService.Decision.READY);
    }

    @Test
    void candidateHorizonCannotReplaceEitherPrimaryTrend() {
        var base = clearInputs();
        assertStop(copy(base, base.candidate(), blockedGate(), base.primaryTrend4h(), base.eventRisk(),
                base.limits(), base.exposure(), base.oco(), base.runtimeWriter()), "PRIMARY_TREND_1H_BLOCKED");
        assertStop(copy(base, base.candidate(), base.primaryTrend1h(), blockedGate(), base.eventRisk(),
                base.limits(), base.exposure(), base.oco(), base.runtimeWriter()), "PRIMARY_TREND_4H_BLOCKED");
    }

    @Test
    void ocoIsRequiredOnlyByPolicyAndDryRunRemainsNoOrder() {
        var base = clearInputs();
        var invalidRequired = new VersionedProfitStartHardGateSnapshotService.Oco(
                VersionedProfitStartHardGateSnapshotService.OcoPolicy.REQUIRED, false, false,
                clearGate(), clearGate());
        assertStop(copy(base, base.candidate(), base.primaryTrend1h(), base.primaryTrend4h(), base.eventRisk(),
                base.limits(), base.exposure(), invalidRequired, base.runtimeWriter()), "OCO_IDENTITY_MISMATCH");

        var noOco = new VersionedProfitStartHardGateSnapshotService.Oco(
                VersionedProfitStartHardGateSnapshotService.OcoPolicy.NOT_REQUIRED, true, false, null, null);
        assertThat(service.evaluate(copy(base, base.candidate(), base.primaryTrend1h(), base.primaryTrend4h(),
                base.eventRisk(), base.limits(), base.exposure(), noOco, base.runtimeWriter())).decision())
                .isEqualTo(VersionedProfitStartHardGateSnapshotService.Decision.READY);

        var dryRun = new VersionedProfitStartHardGateSnapshotService.Oco(
                VersionedProfitStartHardGateSnapshotService.OcoPolicy.NO_ORDER, true, false, null, null);
        var dryRunIdentity = identity("BTCUSDT", "BTC_BASE_DRY_RUN");
        var dryRunInputs = new VersionedProfitStartHardGateSnapshotService.Inputs(
                base.observedAt(), base.snapshotTtl(), dryRunIdentity, dryRunIdentity, base.candidate(),
                base.primaryTrend1h(), base.primaryTrend4h(), base.eventRisk(), base.limits(),
                base.exposure(), dryRun, base.runtimeWriter());
        var dryRunSnapshot = service.evaluate(dryRunInputs);
        assertThat(dryRunSnapshot.decision()).isEqualTo(VersionedProfitStartHardGateSnapshotService.Decision.WAIT_MARKET);
        assertThat(dryRunSnapshot.reasons()).containsExactly("NO_ORDER_EXECUTION_POLICY");

        assertStop(copy(base, base.candidate(), base.primaryTrend1h(), base.primaryTrend4h(), base.eventRisk(),
                base.limits(), base.exposure(), dryRun, base.runtimeWriter()),
                "NO_ORDER_POLICY_EXECUTION_MODE_MISMATCH");
    }

    @Test
    void finalBoundaryDetectsExpiryAndHashDrift() {
        var inputs = clearInputs();
        var snapshot = service.evaluate(inputs);

        assertThat(service.verifyAtOrderBoundary(snapshot, inputs, NOW.plusSeconds(10)).allowed()).isTrue();
        assertThat(service.verifyAtOrderBoundary(snapshot, inputs, NOW.plusSeconds(31)).reasons())
                .contains("SNAPSHOT_EXPIRED");

        var changedExposure = new VersionedProfitStartHardGateSnapshotService.Exposure(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("2"), new BigDecimal("25"));
        var changed = copy(inputs, inputs.candidate(), inputs.primaryTrend1h(), inputs.primaryTrend4h(),
                inputs.eventRisk(), inputs.limits(), changedExposure, inputs.oco(), inputs.runtimeWriter());
        assertThat(service.verifyAtOrderBoundary(snapshot, changed, NOW.plusSeconds(10)).reasons())
                .contains("SNAPSHOT_HASH_DRIFT");
    }

    private void assertStop(VersionedProfitStartHardGateSnapshotService.Inputs inputs, String reason) {
        var snapshot = service.evaluate(inputs);
        assertThat(snapshot.decision()).isEqualTo(VersionedProfitStartHardGateSnapshotService.Decision.STOP);
        assertThat(snapshot.reasons()).contains(reason);
    }

    private VersionedProfitStartHardGateSnapshotService.Inputs clearInputs() {
        var identity = identity("BTCUSDT");
        return new VersionedProfitStartHardGateSnapshotService.Inputs(
                NOW, Duration.ofSeconds(30), identity, identity,
                new VersionedProfitStartHardGateSnapshotService.Candidate(
                        true, "OPPORTUNITY-485-BTC-20260717", true, NOW.minusSeconds(5), Duration.ofMinutes(5)),
                clearGate(), clearGate(), clearGate(),
                new VersionedProfitStartHardGateSnapshotService.Limits(
                        new BigDecimal("5"), new BigDecimal("5"), true,
                        0, 1, 0, 1, false, 0, 0, 2),
                new VersionedProfitStartHardGateSnapshotService.Exposure(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ONE, new BigDecimal("25")),
                new VersionedProfitStartHardGateSnapshotService.Oco(
                        VersionedProfitStartHardGateSnapshotService.OcoPolicy.REQUIRED,
                        false, true, clearGate(), clearGate()),
                clearGate());
    }

    private VersionedProfitStartHardGateSnapshotService.Limits limitsWithNotional(
            VersionedProfitStartHardGateSnapshotService.Inputs base,
            BigDecimal orderNotional,
            BigDecimal minimumOrderNotional) {
        var limits = base.limits();
        return new VersionedProfitStartHardGateSnapshotService.Limits(
                orderNotional, minimumOrderNotional, limits.lotValid(),
                limits.ordersToday(), limits.maxOrdersPerDay(), limits.openPositions(), limits.maxOpenPositions(),
                limits.duplicateOpportunity(), limits.currentCohortClosedEpisodes(), limits.sequentialLosses(),
                limits.maxSequentialLosses());
    }

    private VersionedProfitStartHardGateSnapshotService.CohortIdentity identity(String symbol) {
        return identity(symbol, "LIVE_MICRO");
    }

    private VersionedProfitStartHardGateSnapshotService.CohortIdentity identity(String symbol, String executionMode) {
        return new VersionedProfitStartHardGateSnapshotService.CohortIdentity(
                "VPSTART1-485-BTCUSDT-748A69EA-ABCDEF123456-12345678",
                "748a69ea5b9254e9bd79099e460cefc2ab9297dd",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "LOCAL-TRADINGVIEW-PARITY-V1", "LOCAL_TRADINGVIEW", executionMode,
                485L, "SCORE_BUY_V2", symbol, NOW.minus(Duration.ofDays(1)));
    }

    private VersionedProfitStartHardGateSnapshotService.TimedGate clearGate() {
        return new VersionedProfitStartHardGateSnapshotService.TimedGate(
                VersionedProfitStartHardGateSnapshotService.GateState.CLEAR,
                NOW.minusSeconds(5), Duration.ofMinutes(5), "CLEAR");
    }

    private VersionedProfitStartHardGateSnapshotService.TimedGate staleClearGate() {
        return new VersionedProfitStartHardGateSnapshotService.TimedGate(
                VersionedProfitStartHardGateSnapshotService.GateState.CLEAR,
                NOW.minus(Duration.ofHours(1)), Duration.ofMinutes(5), "CLEAR");
    }

    private VersionedProfitStartHardGateSnapshotService.TimedGate unknownGate() {
        return new VersionedProfitStartHardGateSnapshotService.TimedGate(
                VersionedProfitStartHardGateSnapshotService.GateState.UNKNOWN,
                NOW.minusSeconds(5), Duration.ofMinutes(5), "UNKNOWN");
    }

    private VersionedProfitStartHardGateSnapshotService.TimedGate blockedGate() {
        return new VersionedProfitStartHardGateSnapshotService.TimedGate(
                VersionedProfitStartHardGateSnapshotService.GateState.BLOCK,
                NOW.minusSeconds(5), Duration.ofMinutes(5), "BLOCK");
    }

    private VersionedProfitStartHardGateSnapshotService.Inputs copy(
            VersionedProfitStartHardGateSnapshotService.Inputs base,
            VersionedProfitStartHardGateSnapshotService.Candidate candidate,
            VersionedProfitStartHardGateSnapshotService.TimedGate trend1h,
            VersionedProfitStartHardGateSnapshotService.TimedGate trend4h,
            VersionedProfitStartHardGateSnapshotService.TimedGate eventRisk,
            VersionedProfitStartHardGateSnapshotService.Limits limits,
            VersionedProfitStartHardGateSnapshotService.Exposure exposure,
            VersionedProfitStartHardGateSnapshotService.Oco oco,
            VersionedProfitStartHardGateSnapshotService.TimedGate writer) {
        return new VersionedProfitStartHardGateSnapshotService.Inputs(
                base.observedAt(), base.snapshotTtl(), base.expectedIdentity(), base.observedIdentity(),
                candidate, trend1h, trend4h, eventRisk, limits, exposure, oco, writer);
    }
}
