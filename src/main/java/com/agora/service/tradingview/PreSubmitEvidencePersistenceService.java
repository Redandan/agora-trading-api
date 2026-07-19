package com.agora.service.tradingview;

import com.agora.model.BtDecisionAudit;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.trading.VersionedProfitStartCohortService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;

/** Persists the complete pre-submit evidence pair before provider submission. */
@Service
@RequiredArgsConstructor
public class PreSubmitEvidencePersistenceService {

    private static final String RESERVATION_POLICY = "VERSIONED_PROFIT_START_HARD_GATE";
    private static final String RESERVATION_ACTION = "VERSIONED_PROFIT_START_HARD_GATE_READY_PRE_SUBMIT";
    private static final String RESERVATION_OUTCOME = "PRE_SUBMIT_SNAPSHOT_BOUND";

    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final BtStrategyRepository strategyRepository;
    private final ObjectMapper objectMapper;

    /**
     * The separate bean is intentional: calling a {@code REQUIRES_NEW} method on
     * {@link LocalTradingViewExecutionService} itself would bypass Spring's proxy.
     * Returning proves both flushes completed; the transaction interceptor commits
     * before control returns to the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long reserve(VersionedProfitStartCohortService.Snapshot cohort,
                        BtDecisionAudit audit,
                        RuntimeDecisionEvidence evidence) {
        if (cohort == null || !cohort.identityReady() || cohort.effectiveFrom() == null
                || audit == null || evidence == null || audit.getStrategyId() == null
                || !audit.getStrategyId().equals(cohort.strategyId())
                || !audit.getStrategyId().equals(evidence.getStrategyId())) {
            throw new IllegalStateException("bootstrap reservation inputs are not cohort bound");
        }
        if (!RESERVATION_POLICY.equals(evidence.getPolicyMode())
                || evidence.getEvidenceTime() == null
                || evidence.getEvidenceTime().isBefore(
                        cohort.effectiveFrom().atZone(ZoneOffset.UTC).toLocalDateTime())
                || !RESERVATION_ACTION.equals(evidence.getSelectedAction())
                || !RESERVATION_OUTCOME.equals(evidence.getFinalOutcome())
                || !VersionedProfitStartCohortService.matchesExplicitBinding(
                        objectMapper, cohort, boundCohort(evidence))) {
            throw new IllegalStateException("bootstrap reservation evidence is not explicitly cohort bound");
        }
        strategyRepository.findByIdForBootstrapReservation(cohort.strategyId())
                .orElseThrow(() -> new IllegalStateException("bootstrap reservation owner row is unavailable"));

        List<RuntimeDecisionEvidence> existing = evidenceRepository
                .findByPolicyModeAndStrategyIdAndEvidenceTimeGreaterThanEqualOrderByEvidenceTimeAsc(
                        RESERVATION_POLICY,
                        cohort.strategyId(),
                        cohort.effectiveFrom().atZone(ZoneOffset.UTC).toLocalDateTime());
        if (existing == null) {
            throw new IllegalStateException("bootstrap reservation query returned null");
        }
        for (RuntimeDecisionEvidence row : existing) {
            if (row == null
                    || !RESERVATION_ACTION.equals(row.getSelectedAction())
                    || !RESERVATION_OUTCOME.equals(row.getFinalOutcome())) {
                continue;
            }
            if (VersionedProfitStartCohortService.matchesExplicitBinding(
                    objectMapper, cohort, boundCohort(row))) {
                throw new IllegalStateException("current cohort bootstrap reservation already exists");
            }
        }

        BtDecisionAudit savedAudit = decisionAuditRepository.saveAndFlush(audit);
        if (savedAudit == null || savedAudit.getId() == null) {
            throw new IllegalStateException("pre-submit decision audit was not persisted");
        }
        evidence.setDecisionId(savedAudit.getId());
        RuntimeDecisionEvidence savedEvidence = evidenceRepository.saveAndFlush(evidence);
        if (savedEvidence == null) {
            throw new IllegalStateException("pre-submit runtime evidence was not persisted");
        }
        return savedAudit.getId();
    }

    private JsonNode boundCohort(RuntimeDecisionEvidence row) {
        try {
            JsonNode root = objectMapper.readTree(row.getFeaturesSnapshotJson());
            if (root != null && root.isTextual()) {
                root = objectMapper.readTree(root.asText());
            }
            JsonNode bound = root == null ? null : root.path("versionedProfitStartCohort");
            if (bound == null || bound.isMissingNode() || bound.isNull()) {
                throw new IllegalStateException("bootstrap reservation cohort binding is missing");
            }
            return bound;
        } catch (Exception e) {
            throw new IllegalStateException("bootstrap reservation cohort binding is unreadable", e);
        }
    }

}
