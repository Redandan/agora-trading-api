package com.agora.service.trading;

import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only reconciliation of cohort identity, hard gate, and canonical metrics. */
@Service
@RequiredArgsConstructor
public class VersionedProfitStartActivationReadinessService {

    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    public Readiness assess(VersionedProfitStartCohortService.Snapshot cohort,
                            VersionedProfitStartHardGateSnapshotService.Snapshot hardGate) {
        if (cohort == null || !cohort.identityReady() || cohort.effectiveFrom() == null) {
            return new Readiness("NOT_STARTED", false, false, false, false, false,
                    CurrentCohortCanonicalMetricReader.Classification.NOT_MEASURABLE,
                    0, 0, 0, List.of("COHORT_IDENTITY_OR_EFFECTIVE_FROM_NOT_READY"));
        }

        List<String> blockers = new ArrayList<>();
        List<CurrentCohortCanonicalMetricReader.ClosedEpisode> episodes = new ArrayList<>();
        try {
            List<RuntimeDecisionEvidenceRepository.CanonicalEpisodeBinding> rows =
                    evidenceRepository.findCanonicalEpisodeBindings(
                            cohort.strategyId(), cohort.effectiveFrom().atZone(ZoneOffset.UTC).toLocalDateTime());
            if (rows == null) {
                blockers.add("CANONICAL_EPISODE_BINDING_QUERY_RETURNED_NULL");
            } else {
                Set<String> explicitBindings = new HashSet<>();
                for (RuntimeDecisionEvidenceRepository.CanonicalEpisodeBinding row : rows) {
                    if (row != null && row.getLiveSignalId() != null && !blank(row.getProviderOrderId())
                            && !explicitBindings.add(row.getLiveSignalId() + ":" + row.getProviderOrderId())) {
                        blockers.add("DUPLICATE_LIVE_SIGNAL_PROVIDER_ORDER_BINDING_CONFLICT");
                        continue;
                    }
                    CurrentCohortCanonicalMetricReader.ClosedEpisode episode = episode(cohort, row, blockers);
                    if (episode != null) episodes.add(episode);
                }
            }
        } catch (Exception e) {
            blockers.add("CANONICAL_EPISODE_BINDING_READ_FAILED");
        }

        CurrentCohortCanonicalMetricReader.Result metrics = new CurrentCohortCanonicalMetricReader().aggregate(
                metricIdentity(cohort), cohort.effectiveFrom(), blockers.isEmpty() ? episodes : null);
        blockers.addAll(metrics.blockers());
        if (metrics.closedEpisodes() > 0
                && metrics.classification() == CurrentCohortCanonicalMetricReader.Classification.NOT_MEASURABLE) {
            blockers.add("CURRENT_COHORT_METRICS_NOT_MEASURABLE");
        }
        boolean hardGateReady = hardGate != null
                && hardGate.decision() == VersionedProfitStartHardGateSnapshotService.Decision.READY;
        if (hardGateReady && (hardGate.inputs() == null || hardGate.inputs().limits() == null
                || hardGate.inputs().limits().currentCohortClosedEpisodes() != metrics.closedEpisodes())) {
            blockers.add("HARD_GATE_CANONICAL_CLOSED_EPISODE_COUNT_MISMATCH");
        }
        if (!hardGateReady) blockers.add("FRESH_HARD_GATE_SNAPSHOT_NOT_READY");
        blockers.add(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER);
        boolean tinyLiveEligible = hardGateReady && blockers.size() == 1;
        return new Readiness("ACTIVATION_BLOCKED", true, hardGateReady, tinyLiveEligible, false, false,
                metrics.classification(), metrics.closedEpisodes(), metrics.exactFeeEpisodes(),
                metrics.positiveExactNetEpisodes(), blockers.stream().distinct().toList());
    }

    private CurrentCohortCanonicalMetricReader.ClosedEpisode episode(
            VersionedProfitStartCohortService.Snapshot cohort,
            RuntimeDecisionEvidenceRepository.CanonicalEpisodeBinding row,
            List<String> blockers) {
        if (row == null || row.getDecisionId() == null || row.getLiveSignalId() == null
                || blank(row.getProviderOrderId()) || row.getEvidenceTime() == null || row.getExitTime() == null) {
            blockers.add("EXPLICIT_DECISION_SIGNAL_PROVIDER_ORDER_BINDING_INCOMPLETE");
            return null;
        }
        try {
            JsonNode bound = objectMapper.readTree(row.getExecutionPreviewJson()).path("versionedProfitStartCohort");
            if (!matches(cohort, bound)) {
                blockers.add("BOUND_COHORT_IDENTITY_MISMATCH:" + row.getDecisionId());
                return null;
            }
        } catch (Exception e) {
            blockers.add("BOUND_COHORT_IDENTITY_UNREADABLE:" + row.getDecisionId());
            return null;
        }
        String episodeId = row.getDecisionId() + ":" + row.getLiveSignalId() + ":" + row.getProviderOrderId();
        return new CurrentCohortCanonicalMetricReader.ClosedEpisode(
                episodeId, metricIdentity(cohort), row.getEvidenceTime().toInstant(ZoneOffset.UTC),
                row.getExitTime().toInstant(ZoneOffset.UTC), row.getRealizedPnl(), null, null,
                new CurrentCohortCanonicalMetricReader.EvidenceCompleteness(
                        row.getRealizedPnl() != null, false, false));
    }

    private boolean matches(VersionedProfitStartCohortService.Snapshot c, JsonNode n) {
        return n != null && !n.isMissingNode()
                && c.cohortId().equals(n.path("cohortId").asText())
                && c.strategyId() == n.path("strategyId").asLong(-1)
                && c.strategyFamily().equals(n.path("strategyFamily").asText())
                && c.symbol().equals(n.path("symbol").asText())
                && c.codeCommit().equals(n.path("codeCommit").asText())
                && c.configSha256().equals(n.path("configSha256").asText())
                && c.modelVersion().equals(n.path("modelVersion").asText())
                && c.signalSource().equals(n.path("signalSource").asText())
                && c.executionMode().equals(n.path("executionMode").asText())
                && c.effectiveFrom().toString().equals(n.path("effectiveFrom").asText());
    }

    private CurrentCohortCanonicalMetricReader.CohortIdentity metricIdentity(
            VersionedProfitStartCohortService.Snapshot c) {
        return new CurrentCohortCanonicalMetricReader.CohortIdentity(
                c.cohortId(), c.strategyId(), c.strategyFamily(), c.symbol(), c.codeCommit(),
                c.configSha256(), c.modelVersion(), c.signalSource(), c.executionMode());
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record Readiness(String state, boolean cohortStarted, boolean hardGateReady,
                            boolean tinyLiveEligible, boolean activationAllowed,
                            boolean exactNetAcceptanceAllowed,
                            CurrentCohortCanonicalMetricReader.Classification classification,
                            int canonicalClosedEpisodeCount, int exactFeeEpisodeCount,
                            int positiveExactNetEpisodeCount, List<String> blockers) { }
}
