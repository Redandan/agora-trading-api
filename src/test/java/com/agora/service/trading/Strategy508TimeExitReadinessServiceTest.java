package com.agora.service.trading;

import com.agora.config.properties.Strategy508TimeExitProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Strategy508TimeExitReadinessServiceTest {

    @Test
    void historicalAndThirtyDayForwardEvidenceCanBecomeReadyButNeverAuthorizeOrder() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot = fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isTrue();
        assertThat(snapshot.blockers()).isEmpty();
        assertThat(snapshot.node().path("historicalGatePassed").asBoolean()).isTrue();
        assertThat(snapshot.node().path("forwardShadow").path("observationDays").asLong()).isGreaterThanOrEqualTo(30);
        assertThat(snapshot.node().path("forwardShadow").path("finalizedEvents").asInt()).isEqualTo(5);
        assertThat(snapshot.node().path("forwardShadow").path("gatePassed").asBoolean()).isTrue();
        assertThat(snapshot.node().path("liveOrderAllowed").asBoolean()).isFalse();
        assertThat(snapshot.node().path("verdict").asText())
                .isEqualTo("READY_FOR_SINGLE_10_USDT_PROBE_NOT_AUTHORIZED");
    }

    @Test
    void oldHardBlockerOutsideRollingThirtyDayWindowDoesNotPoisonFutureForever() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        evidence.get(0).setPolicyInputsJson("{\"hardBlockers\":[\"OLD_DEPLOYMENT_BLOCKER\"]}");
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot = fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isTrue();
        assertThat(snapshot.node().path("forwardShadow").path("hardGateBlockedEvents").asLong()).isZero();
    }

    @Test
    void currentHardBlockerOrParityGapFailsForwardGate() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        evidence.get(2).setPolicyInputsJson(
                "{\"hardBlockers\":[\"EVENT_RISK_R2_OR_HIGHER\"],\"netPnlUsdt\":\"0.10\"}");
        evidence.get(3).setPolicyInputsJson(
                "{\"hardBlockers\":[],\"exitParityGap\":true,\"netPnlUsdt\":\"0.10\"}");
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot = fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.blockers()).contains("FORWARD_SHADOW_GATE_NOT_READY");
        assertThat(snapshot.node().path("forwardShadow").path("hardGateBlockedEvents").asLong()).isEqualTo(1);
        assertThat(snapshot.node().path("forwardShadow").path("entryExitParityGapCount").asLong()).isEqualTo(1);
    }

    @Test
    void cumulativeLiveLossAtThreeUsdtTriggersExperimentFuse() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        for (int i = 1; i < evidence.size(); i++) {
            RuntimeDecisionEvidence row = evidence.get(i);
            row.setLiveSignalId(900L + i);
            row.setPolicyInputsJson("{\"hardBlockers\":[],\"netPnlUsdt\":\"-0.60\"}");
        }
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot = fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.blockers()).contains("EXPERIMENT_CUMULATIVE_LOSS_FUSE");
        assertThat(snapshot.node().path("livePilot").path("netPnlUsdt").asText()).isEqualTo("-3.00000000");
        assertThat(snapshot.node().path("livePilot").path("lossFuseTriggered").asBoolean()).isTrue();
    }

    @Test
    void shadowModeCanCollectEvidenceButCannotBecomeLiveReady() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.SHADOW, false);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(passingForwardEvidence());

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot = fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.blockers()).contains("LIVE_MICRO_NOT_EXPLICITLY_ARMED");
        assertThat(snapshot.node().path("verdict").asText()).isEqualTo("SHADOW_COLLECTING_NOT_LIVE");
        assertThat(snapshot.node().path("liveOrderAllowed").asBoolean()).isFalse();
    }

    @Test
    void firstLiveProbeMustFinishWithExactFeesBeforeSecondEntryCanProceed() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence firstProbe = evidence(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1), "TIME_EXIT_24H",
                "{\"hardBlockers\":[],\"entryParityGap\":false,\"exitParityGap\":false," +
                        "\"feeCoverageComplete\":true,\"netPnlUsdt\":\"0.05\"}");
        firstProbe.setId(7001L);
        firstProbe.setOrderSent(true);
        firstProbe.setLiveSignalId(9001L);
        evidence.add(firstProbe);
        BtLiveSignal closed = policySignal(9001L, true);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);
        when(fixture.liveSignalRepository.findByStrategyIdAndCreatedAtAfter(eq(508L), any()))
                .thenReturn(List.of(closed));

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isTrue();
        assertThat(snapshot.blockers()).doesNotContain("FIRST_PROBE_EXECUTION_NOT_VERIFIED");
        assertThat(snapshot.node().path("livePilot").path("firstProbeVerified").asBoolean()).isTrue();
        assertThat(snapshot.node().path("livePilot").path("orderAttempts").asInt()).isEqualTo(1);
    }

    @Test
    void unresolvedFirstLiveAttemptBlocksAnySecondProbe() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence firstProbe = evidence(
                LocalDateTime.now(ZoneOffset.UTC).minusHours(2), "PENDING_24H",
                "{\"hardBlockers\":[]}");
        firstProbe.setId(7002L);
        firstProbe.setOrderSent(true);
        firstProbe.setLiveSignalId(9002L);
        evidence.add(firstProbe);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);
        when(fixture.liveSignalRepository.findByStrategyIdAndCreatedAtAfter(eq(508L), any()))
                .thenReturn(List.of(policySignal(9002L, false)));

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.blockers()).contains("FIRST_PROBE_EXECUTION_NOT_VERIFIED");
        assertThat(snapshot.node().path("livePilot").path("firstProbeVerified").asBoolean()).isFalse();
    }

    private Fixture fixture(Strategy508TimeExitProperties.Mode mode, boolean liveOrderEnabled) {
        Strategy508TimeExitCandidateService candidateService = mock(Strategy508TimeExitCandidateService.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode historical = mapper.createObjectNode();
        historical.put("historicalGatePassed", true);
        historical.put("verdict", "READY_FOR_SINGLE_10_USDT_PROBE_REVIEW_NOT_AUTHORIZED");
        historical.put("finalizedEvents", 30);
        when(candidateService.analyzeNode("BTCUSDT", 5)).thenReturn(historical);
        when(liveSignalRepository.findByStrategyIdAndCreatedAtAfter(eq(508L), any())).thenReturn(List.of());
        Strategy508TimeExitReadinessService service = new Strategy508TimeExitReadinessService(
                new Strategy508TimeExitProperties(mode, liveOrderEnabled), candidateService,
                evidenceRepository, liveSignalRepository, mapper);
        return new Fixture(service, evidenceRepository, liveSignalRepository);
    }

    private List<RuntimeDecisionEvidence> passingForwardEvidence() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<RuntimeDecisionEvidence> rows = new ArrayList<>();
        rows.add(evidence(now.minusDays(32), "PENDING_24H", "{\"hardBlockers\":[]}"));
        for (int i = 0; i < 5; i++) {
            rows.add(evidence(now.minusDays(25L - i * 5L), "TIME_EXIT_24H",
                    "{\"hardBlockers\":[],\"netPnlUsdt\":\"0.10\"}"));
        }
        return rows;
    }

    private RuntimeDecisionEvidence evidence(LocalDateTime at, String outcome, String json) {
        RuntimeDecisionEvidence row = new RuntimeDecisionEvidence();
        row.setDecisionId(Math.abs(at.hashCode()) + 1L);
        row.setEvidenceTime(at);
        row.setSymbol("BTCUSDT");
        row.setStrategyId(508L);
        row.setPolicyMode(Strategy508TimeExitPolicy.POLICY_MODE);
        row.setSelectedAction("STRATEGY_508_TIME_EXIT_SHADOW");
        row.setFinalOutcome(outcome);
        row.setPolicyInputsJson(json);
        return row;
    }

    private BtLiveSignal policySignal(Long id, boolean closed) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setId(id);
        signal.setStrategyId(508L);
        signal.setSymbol("BTCUSDT");
        signal.setAutoTraded(true);
        signal.setFilterReason(Strategy508TimeExitPolicy.POLICY_MODE);
        signal.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        if (closed) signal.setExitTime(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        return signal;
    }

    private record Fixture(Strategy508TimeExitReadinessService service,
                           RuntimeDecisionEvidenceRepository evidenceRepository,
                           BtLiveSignalRepository liveSignalRepository) {
    }
}
