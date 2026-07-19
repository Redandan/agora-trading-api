package com.agora.service.tradingview;

import com.agora.model.BtDecisionAudit;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.trading.VersionedProfitStartCohortService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        RuntimeDecisionEvidence evidence = evidence();
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setStrategyId(485L);
        when(strategyRepository.findByIdForBootstrapReservation(485L))
                .thenReturn(java.util.Optional.of(new com.agora.model.BtStrategy()));
        when(evidenceRepository.findByPolicyModeAndStrategyIdAndEvidenceTimeGreaterThanEqualOrderByEvidenceTimeAsc(
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
        when(evidenceRepository.findByPolicyModeAndStrategyIdAndEvidenceTimeGreaterThanEqualOrderByEvidenceTimeAsc(
                any(), any(), any()))
                .thenReturn(java.util.List.of());
        when(auditRepository.saveAndFlush(any())).thenThrow(new IllegalStateException("constraint"));

        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setStrategyId(485L);
        RuntimeDecisionEvidence evidence = evidence();
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
        RuntimeDecisionEvidence evidence = evidence();

        assertThatThrownBy(() -> service.reserve(cohort(), audit, evidence))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
        verify(auditRepository, never()).saveAndFlush(any());
        verify(evidenceRepository, never()).saveAndFlush(any());
    }

    @Test
    void firstFlushFailureCanBeRetriedSafely() {
        BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
        PreSubmitEvidencePersistenceService service = new PreSubmitEvidencePersistenceService(
                auditRepository, evidenceRepository, strategyRepository, new ObjectMapper());
        BtDecisionAudit audit = audit();
        RuntimeDecisionEvidence evidence = evidence();
        BtDecisionAudit savedAudit = audit();
        savedAudit.setId(92L);
        when(strategyRepository.findByIdForBootstrapReservation(485L))
                .thenReturn(java.util.Optional.of(new com.agora.model.BtStrategy()));
        when(evidenceRepository.findByPolicyModeAndStrategyIdAndEvidenceTimeGreaterThanEqualOrderByEvidenceTimeAsc(
                any(), any(), any())).thenReturn(java.util.List.of());
        when(auditRepository.saveAndFlush(audit))
                .thenThrow(new IllegalStateException("constraint"))
                .thenReturn(savedAudit);
        when(evidenceRepository.saveAndFlush(evidence)).thenReturn(evidence);

        assertThatThrownBy(() -> service.reserve(cohort(), audit, evidence))
                .isInstanceOf(IllegalStateException.class).hasMessage("constraint");
        assertThat(service.reserve(cohort(), audit, evidence)).isEqualTo(92L);
    }

    @Test
    void lockFailureCanBeRetriedSafely() {
        BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
        PreSubmitEvidencePersistenceService service = new PreSubmitEvidencePersistenceService(
                auditRepository, evidenceRepository, strategyRepository, new ObjectMapper());
        BtDecisionAudit audit = audit();
        RuntimeDecisionEvidence evidence = evidence();
        BtDecisionAudit savedAudit = audit();
        savedAudit.setId(93L);
        when(strategyRepository.findByIdForBootstrapReservation(485L))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("busy"))
                .thenReturn(java.util.Optional.of(new com.agora.model.BtStrategy()));
        when(evidenceRepository.findByPolicyModeAndStrategyIdAndEvidenceTimeGreaterThanEqualOrderByEvidenceTimeAsc(
                any(), any(), any())).thenReturn(java.util.List.of());
        when(auditRepository.saveAndFlush(audit)).thenReturn(savedAudit);
        when(evidenceRepository.saveAndFlush(evidence)).thenReturn(evidence);

        assertThatThrownBy(() -> service.reserve(cohort(), audit, evidence))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
        assertThat(service.reserve(cohort(), audit, evidence)).isEqualTo(93L);
    }

    @Test
    void rejectsWrongPolicyTimeActionOutcomeAndEmbeddedCohortBeforeLockOrWrite() {
        for (java.util.function.Consumer<RuntimeDecisionEvidence> corruption
                : java.util.List.<java.util.function.Consumer<RuntimeDecisionEvidence>>of(
                value -> value.setPolicyMode("OTHER"),
                value -> value.setEvidenceTime(null),
                value -> value.setEvidenceTime(java.time.LocalDateTime.of(2026, 7, 16, 23, 59, 59)),
                value -> value.setSelectedAction("OTHER"),
                value -> value.setFinalOutcome("OTHER"),
                value -> value.setFeaturesSnapshotJson("{}"),
                value -> value.setFeaturesSnapshotJson(boundCompleteJson().replace("LIVE_MICRO", "SHADOW")))) {
            BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
            RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
            BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
            PreSubmitEvidencePersistenceService service = new PreSubmitEvidencePersistenceService(
                    auditRepository, evidenceRepository, strategyRepository, new ObjectMapper());
            RuntimeDecisionEvidence evidence = evidence();
            corruption.accept(evidence);

            assertThatThrownBy(() -> service.reserve(cohort(), audit(), evidence))
                    .isInstanceOf(IllegalStateException.class);
            verify(strategyRepository, never()).findByIdForBootstrapReservation(any());
            verify(auditRepository, never()).saveAndFlush(any());
            verify(evidenceRepository, never()).saveAndFlush(any());
        }
    }

    @Test
    void rejectsIncompleteTamperedOrNonCanonicalCompleteCohortBeforeLockOrWrite() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        java.util.List<java.util.function.Consumer<ObjectNode>> corruptions = java.util.List.of(
                value -> value.remove("contractVersion"),
                value -> value.put("contractVersion", "OTHER_CONTRACT"),
                value -> value.put("state", "NOT_STARTED"),
                value -> value.put("identityReady", false),
                value -> value.put("activationReady", true),
                value -> value.putArray("identityBlockers").add("TAMPERED"),
                value -> value.putArray("activationBlockers"),
                value -> value.putArray("finalAcceptanceBlockers"),
                value -> value.put("bootstrapOrderAuthorityArmed", false),
                value -> value.put("bootstrapOcoAuthorityArmed", true),
                value -> value.put("unexpected", true),
                value -> value.put("strategyId", "485")
        );
        for (java.util.function.Consumer<ObjectNode> corruption : corruptions) {
            assertRejectedBeforeLockOrWrite(mapper, corruption);
        }

        ObjectNode complete = (ObjectNode) mapper.readTree(boundCompleteJson())
                .path("versionedProfitStartCohort");
        ObjectNode reordered = mapper.createObjectNode();
        reordered.set("state", complete.get("state"));
        complete.fields().forEachRemaining(field -> {
            if (!"state".equals(field.getKey())) reordered.set(field.getKey(), field.getValue());
        });
        assertRejectedBeforeLockOrWrite(mapper, value -> {
            value.removeAll();
            value.setAll(reordered);
        });
    }

    private void assertRejectedBeforeLockOrWrite(ObjectMapper mapper,
                                                 java.util.function.Consumer<ObjectNode> corruption) {
        BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
        PreSubmitEvidencePersistenceService service = new PreSubmitEvidencePersistenceService(
                auditRepository, evidenceRepository, strategyRepository, mapper);
        RuntimeDecisionEvidence evidence = evidence();
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(evidence.getFeaturesSnapshotJson());
            corruption.accept((ObjectNode) root.path("versionedProfitStartCohort"));
            evidence.setFeaturesSnapshotJson(root.toString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThatThrownBy(() -> service.reserve(cohort(), audit(), evidence))
                .isInstanceOf(IllegalStateException.class);
        verify(strategyRepository, never()).findByIdForBootstrapReservation(any());
        verify(auditRepository, never()).saveAndFlush(any());
        verify(evidenceRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsWrongStrategyBindingBeforeLockOrWrite() {
        BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
        PreSubmitEvidencePersistenceService service = new PreSubmitEvidencePersistenceService(
                auditRepository, evidenceRepository, strategyRepository, new ObjectMapper());
        RuntimeDecisionEvidence evidence = evidence();
        evidence.setStrategyId(486L);

        assertThatThrownBy(() -> service.reserve(cohort(), audit(), evidence))
                .isInstanceOf(IllegalStateException.class);
        verify(strategyRepository, never()).findByIdForBootstrapReservation(any());
        verify(auditRepository, never()).saveAndFlush(any());
        verify(evidenceRepository, never()).saveAndFlush(any());
    }

    private BtDecisionAudit audit() {
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setStrategyId(485L);
        return audit;
    }

    private RuntimeDecisionEvidence evidence() {
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setStrategyId(485L);
        evidence.setEvidenceTime(java.time.LocalDateTime.of(2026, 7, 17, 0, 0));
        evidence.setPolicyMode("VERSIONED_PROFIT_START_HARD_GATE");
        evidence.setSelectedAction("VERSIONED_PROFIT_START_HARD_GATE_READY_PRE_SUBMIT");
        evidence.setFinalOutcome("PRE_SUBMIT_SNAPSHOT_BOUND");
        evidence.setFeaturesSnapshotJson(boundCompleteJson());
        return evidence;
    }

    private String boundCompleteJson() {
        return """
                {"versionedProfitStartCohort":{
                  "contractVersion":"VERSIONED_PROFIT_START_ACCEPTANCE_V1",
                  "state":"COHORT_IDENTITY_READY_ACTIVATION_BLOCKED",
                  "enabled":true,"identityReady":true,"activationReady":false,
                  "cohortId":"VPSTART1-485-BTCUSDT-TEST","strategyId":485,
                  "strategyFamily":"SCORE_BUY_V2","symbol":"BTCUSDT",
                  "codeCommit":"4444444444444444444444444444444444444444",
                  "configSha256":"5555555555555555555555555555555555555555555555555555555555555555",
                  "modelVersion":"local-tradingview-parity-v1","signalSource":"LOCAL_TRADINGVIEW",
                  "executionMode":"LIVE_MICRO","effectiveFrom":"2026-07-17T00:00:00Z",
                  "identityBlockers":[],
                  "activationBlockers":["EXACT_IMMUTABLE_ALL_FILL_SIGNED_FEE_BINDING_NOT_COMPLETE_STABLE"],
                  "finalAcceptanceBlockers":["EXACT_IMMUTABLE_ALL_FILL_SIGNED_FEE_BINDING_NOT_COMPLETE_STABLE"],
                  "legacyRowsExcluded":true,"bootstrapOrderAuthorityArmed":true,
                  "bootstrapOcoAuthorityArmed":false,"exactNetAcceptanceAllowed":false}}
                """;
    }

    private VersionedProfitStartCohortService.Snapshot cohort() {
        return new VersionedProfitStartCohortService.Snapshot(
                VersionedProfitStartCohortService.CONTRACT_VERSION,
                "COHORT_IDENTITY_READY_ACTIVATION_BLOCKED", true, true, false,
                "VPSTART1-485-BTCUSDT-TEST", 485L, "SCORE_BUY_V2", "BTCUSDT",
                "4".repeat(40), "5".repeat(64), "local-tradingview-parity-v1",
                "LOCAL_TRADINGVIEW", "LIVE_MICRO", java.time.Instant.parse("2026-07-17T00:00:00Z"),
                java.util.List.of(),
                java.util.List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER),
                java.util.List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER), true,
                true, false, false);
    }
}
