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
        strategyRepository.findByIdForBootstrapReservation(cohort.strategyId())
                .orElseThrow(() -> new IllegalStateException("bootstrap reservation owner row is unavailable"));

        List<RuntimeDecisionEvidence> existing = evidenceRepository
                .findByPolicyModeAndStrategyIdAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
                        "VERSIONED_PROFIT_START_HARD_GATE",
                        cohort.strategyId(),
                        cohort.effectiveFrom().atZone(ZoneOffset.UTC).toLocalDateTime());
        if (existing == null) {
            throw new IllegalStateException("bootstrap reservation query returned null");
        }
        for (RuntimeDecisionEvidence row : existing) {
            if (row == null
                    || !"VERSIONED_PROFIT_START_HARD_GATE_READY_PRE_SUBMIT".equals(row.getSelectedAction())
                    || !"PRE_SUBMIT_SNAPSHOT_BOUND".equals(row.getFinalOutcome())) {
                continue;
            }
            if (matches(cohort, boundCohort(row))) {
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

    private boolean matches(VersionedProfitStartCohortService.Snapshot c, JsonNode n) {
        return c.cohortId().equals(n.path("cohortId").asText())
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
}
