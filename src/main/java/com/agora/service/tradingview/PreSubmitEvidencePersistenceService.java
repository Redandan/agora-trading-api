package com.agora.service.tradingview;

import com.agora.model.BtDecisionAudit;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists the complete pre-submit evidence pair before provider submission. */
@Service
@RequiredArgsConstructor
public class PreSubmitEvidencePersistenceService {

    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;

    /**
     * The separate bean is intentional: calling a {@code REQUIRES_NEW} method on
     * {@link LocalTradingViewExecutionService} itself would bypass Spring's proxy.
     * Returning proves both flushes completed; the transaction interceptor commits
     * before control returns to the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long persist(BtDecisionAudit audit, RuntimeDecisionEvidence evidence) {
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
}
