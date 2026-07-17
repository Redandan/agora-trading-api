package com.agora.service.trading;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VersionedProfitStartHardGateInputAssemblerTest {

    private final VersionedProfitStartHardGateInputAssembler assembler =
            new VersionedProfitStartHardGateInputAssembler();

    @Test
    void missingExplicitRuntimePacketFailsClosed() {
        assertThat(assembler.assemble(cohort(), Map.of())).isNull();
    }

    @Test
    void preservesObservedIdentityInsteadOfSynthesizingItFromExpectedCohort() {
        var observed = new VersionedProfitStartHardGateSnapshotService.CohortIdentity(
                "OTHER", "748a69ea5b9254e9bd79099e460cefc2ab9297dd",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "local-tradingview-parity-v1", "LOCAL_TRADINGVIEW", "LIVE_MICRO",
                485L, "SCORE_BUY_V2", "BTCUSDT", Instant.parse("2026-07-17T00:00:00Z"));
        var runtime = new VersionedProfitStartHardGateInputAssembler.RuntimeInputs(
                Instant.parse("2026-07-17T01:00:00Z"), Duration.ofSeconds(30), observed,
                null, null, null, null, null, null, null, null);

        var inputs = assembler.assemble(cohort(), Map.of(
                "versionedProfitStartHardGateRuntimeInputs", runtime));

        assertThat(inputs.expectedIdentity().cohortId()).isEqualTo("VPSTART1-485-BTCUSDT-TEST");
        assertThat(inputs.observedIdentity().cohortId()).isEqualTo("OTHER");
        assertThat(new VersionedProfitStartHardGateSnapshotService().evaluate(inputs).decision())
                .isEqualTo(VersionedProfitStartHardGateSnapshotService.Decision.STOP);
    }

    private VersionedProfitStartCohortService.Snapshot cohort() {
        return new VersionedProfitStartCohortService.Snapshot(
                VersionedProfitStartCohortService.CONTRACT_VERSION,
                "COHORT_IDENTITY_READY_ACTIVATION_BLOCKED", true, true, false,
                "VPSTART1-485-BTCUSDT-TEST", 485L, "SCORE_BUY_V2", "BTCUSDT",
                "748a69ea5b9254e9bd79099e460cefc2ab9297dd",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "local-tradingview-parity-v1", "LOCAL_TRADINGVIEW", "LIVE_MICRO",
                Instant.parse("2026-07-17T00:00:00Z"), List.of(),
                List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER),
                List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER), true, false);
    }
}
