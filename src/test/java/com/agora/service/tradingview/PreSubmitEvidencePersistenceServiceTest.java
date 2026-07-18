package com.agora.service.tradingview;

import com.agora.model.BtDecisionAudit;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.trading.VersionedProfitStartCohortService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
        PreSubmitEvidencePersistenceService service =
                new PreSubmitEvidencePersistenceService(auditRepository, evidenceRepository,
                        strategyRepository, new ObjectMapper());
        BtDecisionAudit savedAudit = new BtDecisionAudit();
        savedAudit.setId(91L);
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setStrategyId(485L);
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setStrategyId(485L);
        when(strategyRepository.findByIdForBootstrapReservation(485L))
                .thenReturn(java.util.Optional.of(new com.agora.model.BtStrategy()));
        when(evidenceRepository.findByPolicyModeAndStrategyIdAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
                any(), any(), any()))
                .thenReturn(java.util.List.of());
        when(auditRepository.saveAndFlush(audit)).thenReturn(savedAudit);
        when(evidenceRepository.saveAndFlush(evidence)).thenReturn(evidence);

        assertThat(service.reserve(cohort(), audit, evidence)).isEqualTo(91L);
        assertThat(evidence.getDecisionId()).isEqualTo(91L);
        var order = inOrder(auditRepository, evidenceRepository);
        order.verify(auditRepository).saveAndFlush(audit);
        order.verify(evidenceRepository).saveAndFlush(evidence);

        Transactional transactional = PreSubmitEvidencePersistenceService.class
                .getMethod("reserve", VersionedProfitStartCohortService.Snapshot.class,
                        BtDecisionAudit.class, RuntimeDecisionEvidence.class)
                .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void firstFlushFailureStopsBeforeEvidenceWrite() {
        BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
        PreSubmitEvidencePersistenceService service =
                new PreSubmitEvidencePersistenceService(auditRepository, evidenceRepository,
                        strategyRepository, new ObjectMapper());
        when(strategyRepository.findByIdForBootstrapReservation(485L))
                .thenReturn(java.util.Optional.of(new com.agora.model.BtStrategy()));
        when(evidenceRepository.findByPolicyModeAndStrategyIdAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
                any(), any(), any()))
                .thenReturn(java.util.List.of());
        when(auditRepository.saveAndFlush(any())).thenThrow(new IllegalStateException("constraint"));

        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setStrategyId(485L);
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setStrategyId(485L);
        assertThatThrownBy(() -> service.reserve(cohort(), audit, evidence))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("constraint");
        verify(evidenceRepository, never()).saveAndFlush(any());
    }

    @Test
    void lockFailureFailsClosedBeforeEitherWrite() {
        BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
        PreSubmitEvidencePersistenceService service = new PreSubmitEvidencePersistenceService(
                auditRepository, evidenceRepository, strategyRepository, new ObjectMapper());
        when(strategyRepository.findByIdForBootstrapReservation(485L))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("busy"));
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setStrategyId(485L);
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setStrategyId(485L);

        assertThatThrownBy(() -> service.reserve(cohort(), audit, evidence))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
        verify(auditRepository, never()).saveAndFlush(any());
        verify(evidenceRepository, never()).saveAndFlush(any());
    }

    private VersionedProfitStartCohortService.Snapshot cohort() {
        return new VersionedProfitStartCohortService.Snapshot(
                VersionedProfitStartCohortService.CONTRACT_VERSION,
                "COHORT_IDENTITY_READY_ACTIVATION_BLOCKED", true, true, false,
                "VPSTART1-485-BTCUSDT-TEST", 485L, "SCORE_BUY_V2", "BTCUSDT",
                "4".repeat(40), "5".repeat(64), "local-tradingview-parity-v1",
                "LOCAL_TRADINGVIEW", "LIVE_MICRO", java.time.Instant.parse("2026-07-17T00:00:00Z"),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), true,
                true, false, false);
    }
}
