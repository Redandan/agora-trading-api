package com.agora.service.tradingview;

import com.agora.model.BtDecisionAudit;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreSubmitEvidencePersistenceServiceTest {

    @Test
    void usesIndependentTransactionAndFlushesCompletePairInOrder() throws Exception {
        BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        PreSubmitEvidencePersistenceService service =
                new PreSubmitEvidencePersistenceService(auditRepository, evidenceRepository);
        BtDecisionAudit audit = new BtDecisionAudit();
        BtDecisionAudit savedAudit = new BtDecisionAudit();
        savedAudit.setId(91L);
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        when(auditRepository.saveAndFlush(audit)).thenReturn(savedAudit);
        when(evidenceRepository.saveAndFlush(evidence)).thenReturn(evidence);

        assertThat(service.persist(audit, evidence)).isEqualTo(91L);
        assertThat(evidence.getDecisionId()).isEqualTo(91L);
        var order = inOrder(auditRepository, evidenceRepository);
        order.verify(auditRepository).saveAndFlush(audit);
        order.verify(evidenceRepository).saveAndFlush(evidence);

        Transactional transactional = PreSubmitEvidencePersistenceService.class
                .getMethod("persist", BtDecisionAudit.class, RuntimeDecisionEvidence.class)
                .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void firstFlushFailureStopsBeforeEvidenceWrite() {
        BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        PreSubmitEvidencePersistenceService service =
                new PreSubmitEvidencePersistenceService(auditRepository, evidenceRepository);
        when(auditRepository.saveAndFlush(any())).thenThrow(new IllegalStateException("constraint"));

        assertThatThrownBy(() -> service.persist(new BtDecisionAudit(), new RuntimeDecisionEvidence()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("constraint");
        verify(evidenceRepository, never()).saveAndFlush(any());
    }
}
