package com.agora.service.trading;

import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VersionedProfitStartActivationReadinessServiceTest {

    private final RuntimeDecisionEvidenceRepository repository = mock(RuntimeDecisionEvidenceRepository.class);
    private final VersionedProfitStartActivationReadinessService service =
            new VersionedProfitStartActivationReadinessService(repository, new ObjectMapper());

    @Test
    void notStartedDoesNotQueryLegacyOrCanonicalRows() {
        VersionedProfitStartActivationReadinessService.Readiness readiness =
                service.assess(cohort(false), null);

        assertThat(readiness.state()).isEqualTo("NOT_STARTED");
        assertThat(readiness.classification())
                .isEqualTo(CurrentCohortCanonicalMetricReader.Classification.NOT_MEASURABLE);
        assertThat(readiness.canonicalClosedEpisodeCount()).isZero();
        assertThat(readiness.activationAllowed()).isFalse();
        verify(repository, never()).findCanonicalEpisodeBindings(any(), any());
    }

    @Test
    void zeroClosedEpisodesKeepTinyLiveEligibilityButNotActivationOrExactNet() {
        when(repository.findCanonicalEpisodeBindings(eq(485L), any())).thenReturn(List.of());

        VersionedProfitStartActivationReadinessService.Readiness readiness =
                service.assess(cohort(true), readyHardGate(0));

        assertThat(readiness.hardGateReady()).isTrue();
        assertThat(readiness.tinyLiveEligible()).isTrue();
        assertThat(readiness.activationAllowed()).isFalse();
        assertThat(readiness.exactNetAcceptanceAllowed()).isFalse();
        assertThat(readiness.canonicalClosedEpisodeCount()).isZero();
        assertThat(readiness.exactFeeEpisodeCount()).isZero();
        assertThat(readiness.positiveExactNetEpisodeCount()).isZero();
        assertThat(readiness.blockers()).containsExactly(
                VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER);
    }

    @Test
    void explicitDecisionSignalAndProviderOrderBindingIsGrossOnlyUntilV3() {
        var row = mock(RuntimeDecisionEvidenceRepository.CanonicalEpisodeBinding.class);
        when(row.getDecisionId()).thenReturn(101L);
        when(row.getLiveSignalId()).thenReturn(77L);
        when(row.getProviderOrderId()).thenReturn("LOCAL_TV:provider-123");
        when(row.getEvidenceTime()).thenReturn(LocalDateTime.parse("2026-07-17T01:00:00"));
        when(row.getExitTime()).thenReturn(LocalDateTime.parse("2026-07-17T02:00:00"));
        when(row.getRealizedPnl()).thenReturn(new BigDecimal("2.50"));
        when(row.getExecutionPreviewJson()).thenReturn(boundIdentityJson());
        when(repository.findCanonicalEpisodeBindings(eq(485L), any())).thenReturn(List.of(row));

        VersionedProfitStartActivationReadinessService.Readiness readiness =
                service.assess(cohort(true), readyHardGate(1));

        assertThat(readiness.classification())
                .isEqualTo(CurrentCohortCanonicalMetricReader.Classification.GROSS_ONLY);
        assertThat(readiness.canonicalClosedEpisodeCount()).isEqualTo(1);
        assertThat(readiness.exactFeeEpisodeCount()).isZero();
        assertThat(readiness.positiveExactNetEpisodeCount()).isZero();
        assertThat(readiness.blockers()).containsExactly(
                VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER);
    }

    @Test
    void repositoryFailureFailsClosed() {
        when(repository.findCanonicalEpisodeBindings(eq(485L), any()))
                .thenThrow(new IllegalStateException("db unavailable"));

        VersionedProfitStartActivationReadinessService.Readiness readiness =
                service.assess(cohort(true), readyHardGate(0));

        assertThat(readiness.tinyLiveEligible()).isFalse();
        assertThat(readiness.activationAllowed()).isFalse();
        assertThat(readiness.blockers()).contains(
                "CANONICAL_EPISODE_BINDING_READ_FAILED",
                VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER);
    }

    private VersionedProfitStartCohortService.Snapshot cohort(boolean ready) {
        return new VersionedProfitStartCohortService.Snapshot(
                VersionedProfitStartCohortService.CONTRACT_VERSION,
                ready ? "COHORT_IDENTITY_READY_ACTIVATION_BLOCKED" : "NOT_STARTED",
                ready, ready, false, ready ? "VPSTART1-485-BTCUSDT-TEST" : "NOT_STARTED",
                485L, "SCORE_BUY_V2", "BTCUSDT",
                "748a69ea5b9254e9bd79099e460cefc2ab9297dd",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "local-tradingview-parity-v1", "LOCAL_TRADINGVIEW", "LIVE_MICRO",
                ready ? Instant.parse("2026-07-17T00:00:00Z") : null,
                ready ? List.of() : List.of("COHORT_NOT_ENABLED"),
                List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER),
                List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER), true, false);
    }

    private VersionedProfitStartHardGateSnapshotService.Snapshot readyHardGate(long closedEpisodes) {
        var snapshot = mock(VersionedProfitStartHardGateSnapshotService.Snapshot.class);
        var inputs = mock(VersionedProfitStartHardGateSnapshotService.Inputs.class);
        var limits = mock(VersionedProfitStartHardGateSnapshotService.Limits.class);
        when(snapshot.decision()).thenReturn(VersionedProfitStartHardGateSnapshotService.Decision.READY);
        when(snapshot.inputs()).thenReturn(inputs);
        when(inputs.limits()).thenReturn(limits);
        when(limits.currentCohortClosedEpisodes()).thenReturn(closedEpisodes);
        return snapshot;
    }

    private String boundIdentityJson() {
        return """
                {"versionedProfitStartCohort":{
                  "cohortId":"VPSTART1-485-BTCUSDT-TEST","strategyId":485,
                  "strategyFamily":"SCORE_BUY_V2","symbol":"BTCUSDT",
                  "codeCommit":"748a69ea5b9254e9bd79099e460cefc2ab9297dd",
                  "configSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "modelVersion":"local-tradingview-parity-v1","signalSource":"LOCAL_TRADINGVIEW",
                  "executionMode":"LIVE_MICRO","effectiveFrom":"2026-07-17T00:00:00Z"}}
                """;
    }
}
