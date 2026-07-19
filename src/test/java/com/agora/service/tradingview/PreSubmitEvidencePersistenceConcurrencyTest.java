package com.agora.service.tradingview;

import com.agora.model.BtDecisionAudit;
import com.agora.model.BtStrategy;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.trading.VersionedProfitStartCohortService;
import com.agora.trading.TradingApiApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({PreSubmitEvidencePersistenceService.class,
        PreSubmitEvidencePersistenceConcurrencyTest.Config.class})
@ContextConfiguration(classes = TradingApiApplication.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PreSubmitEvidencePersistenceConcurrencyTest {

    @Autowired private PreSubmitEvidencePersistenceService service;
    @Autowired private BtStrategyRepository strategyRepository;
    @Autowired private BtDecisionAuditRepository auditRepository;
    @Autowired private RuntimeDecisionEvidenceRepository evidenceRepository;
    private Long strategyId;

    @BeforeEach
    void createReservationOwner() {
        evidenceRepository.deleteAll();
        auditRepository.deleteAll();
        strategyRepository.deleteAll();
        BtStrategy strategy = new BtStrategy();
        strategy.setName("reservation-owner");
        strategy.setStrategyType("SCORE_BUY");
        strategy.setConfigJson("{}");
        strategy.setEnabled(false);
        strategy.setAiGenerated(false);
        strategy.setSymbols("BTCUSDT");
        strategy.setKlineSource("okx");
        strategyId = strategyRepository.saveAndFlush(strategy).getId();
    }

    @Test
    void concurrentTransactionsHaveAtMostOneWinnerAtOrderBoundary() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Long> contender = () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return service.reserve(cohort(), audit(), evidence());
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(contender);
            var second = executor.submit(contender);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int winners = 0;
            for (var future : List.of(first, second)) {
                try {
                    assertThat(future.get(10, TimeUnit.SECONDS)).isPositive();
                    winners++;
                } catch (java.util.concurrent.ExecutionException expectedLoser) {
                    assertThat(expectedLoser.getCause())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("reservation already exists");
                }
            }
            assertThat(winners).isEqualTo(1);
        }

        assertThat(auditRepository.count()).isEqualTo(1);
        assertThat(evidenceRepository.count()).isEqualTo(1);
    }

    @Test
    void failedEvidenceFlushRollsBackReservationPair() {
        RuntimeDecisionEvidence invalid = evidence();
        invalid.setSignalSource("X".repeat(65));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.reserve(cohort(), audit(), invalid))
                .isInstanceOf(RuntimeException.class);

        assertThat(auditRepository.count()).isZero();
        assertThat(evidenceRepository.count()).isZero();
    }

    @Test
    void failedEvidenceFlushRollsBackAndAValidRetrySucceeds() {
        RuntimeDecisionEvidence invalid = evidence();
        invalid.setSignalSource("X".repeat(65));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.reserve(cohort(), audit(), invalid))
                .isInstanceOf(RuntimeException.class);
        assertThat(auditRepository.count()).isZero();
        assertThat(evidenceRepository.count()).isZero();

        assertThat(service.reserve(cohort(), audit(), evidence())).isPositive();
        assertThat(auditRepository.count()).isEqualTo(1);
        assertThat(evidenceRepository.count()).isEqualTo(1);
    }

    @Test
    void reservationExactlyAtEffectiveFromBlocksASecondWinner() {
        BtDecisionAudit existingAudit = auditRepository.saveAndFlush(audit());
        RuntimeDecisionEvidence existingEvidence = evidence();
        existingEvidence.setEvidenceTime(LocalDateTime.of(2026, 7, 17, 0, 0));
        existingEvidence.setDecisionId(existingAudit.getId());
        evidenceRepository.saveAndFlush(existingEvidence);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.reserve(cohort(), audit(), evidence()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation already exists");
        assertThat(auditRepository.count()).isEqualTo(1);
        assertThat(evidenceRepository.count()).isEqualTo(1);
    }

    private BtDecisionAudit audit() {
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(LocalDateTime.now());
        audit.setStrategyId(strategyId);
        audit.setSymbol("BTCUSDT");
        audit.setIntervalCode("1d");
        audit.setEventType("LOCAL_TV_EXECUTION");
        audit.setOutcome("PASS");
        audit.setContextJson("{}");
        return audit;
    }

    private RuntimeDecisionEvidence evidence() {
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setEvidenceTime(LocalDateTime.now());
        evidence.setStrategyId(strategyId);
        evidence.setSymbol("BTCUSDT");
        evidence.setSelectedAction("VERSIONED_PROFIT_START_HARD_GATE_READY_PRE_SUBMIT");
        evidence.setFinalOutcome("PRE_SUBMIT_SNAPSHOT_BOUND");
        evidence.setPolicyMode("VERSIONED_PROFIT_START_HARD_GATE");
        evidence.setFeaturesSnapshotJson(boundCompleteJson());
        return evidence;
    }

    private VersionedProfitStartCohortService.Snapshot cohort() {
        return new VersionedProfitStartCohortService.Snapshot(
                VersionedProfitStartCohortService.CONTRACT_VERSION,
                "COHORT_IDENTITY_READY_ACTIVATION_BLOCKED", true, true, false,
                "VPSTART1-485-BTCUSDT-TEST", strategyId, "SCORE_BUY_V2", "BTCUSDT",
                "4".repeat(40), "5".repeat(64), "local-tradingview-parity-v1",
                "LOCAL_TRADINGVIEW", "LIVE_MICRO", Instant.parse("2026-07-17T00:00:00Z"),
                List.of(), List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER),
                List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER),
                true, true, false, false);
    }

    private String boundCompleteJson() {
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.set("versionedProfitStartCohort",
                VersionedProfitStartCohortService.explicitBinding(new ObjectMapper(), cohort()));
        return root.toString();
    }

    @TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
