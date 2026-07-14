package com.agora.service.trading;

import com.agora.config.properties.Strategy508TimeExitProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
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

    private static final String TEST_CONFIG_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void historicalAndThirtyDayForwardEvidenceCanBecomeReadyButNeverAuthorizeOrder() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot = fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.blockers()).containsExactly(
                "LIVE_EXACT_FILL_PROVENANCE_NOT_IMPLEMENTED");
        assertThat(snapshot.node().path("historicalGatePassed").asBoolean()).isTrue();
        assertThat(snapshot.node().path("forwardShadow").path("observationDays").asLong()).isGreaterThanOrEqualTo(30);
        assertThat(snapshot.node().path("forwardShadow").path("finalizedEvents").asInt()).isEqualTo(5);
        assertThat(snapshot.node().path("forwardShadow").path("gatePassed").asBoolean()).isTrue();
        assertThat(snapshot.node().path("liveOrderAllowed").asBoolean()).isFalse();
        assertThat(snapshot.node().path("exactLiveFillEvidenceImplemented").asBoolean()).isFalse();
        assertThat(snapshot.node().path("verdict").asText()).isEqualTo("SHADOW_COLLECTING_NOT_LIVE");
    }

    @Test
    void oldHardBlockerOutsideRollingThirtyDayWindowDoesNotPoisonFutureForever() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence oldBlocked = evidence(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(60), "HARD_BLOCKED",
                "{\"hardBlockers\":[\"OLD_DEPLOYMENT_BLOCKER\"]}");
        oldBlocked.setSelectedAction("STRATEGY_508_TIME_EXIT_SHADOW_BLOCKED");
        evidence.add(oldBlocked);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot = fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.node().path("forwardShadow").path("gatePassed").asBoolean()).isTrue();
        assertThat(snapshot.blockers()).doesNotContain("FORWARD_SHADOW_GATE_NOT_READY");
        assertThat(snapshot.node().path("forwardShadow").path("hardGateBlockedEvents").asLong()).isZero();
    }

    @Test
    void currentHardBlockerOrParityGapFailsForwardGate() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        setContext(evidence.get(2),
                "{\"hardBlockers\":[\"EVENT_RISK_R2_OR_HIGHER\"],\"feeCoverageComplete\":true," +
                        "\"netPnlUsdt\":\"0.10\"}");
        setContext(evidence.get(3),
                "{\"hardBlockers\":[],\"exitParityGap\":true,\"feeCoverageComplete\":true," +
                        "\"netPnlUsdt\":\"0.10\"}");
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot = fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.blockers()).contains("FORWARD_SHADOW_GATE_NOT_READY");
        assertThat(snapshot.node().path("forwardShadow").path("hardGateBlockedEvents").asLong()).isEqualTo(1);
        assertThat(snapshot.node().path("forwardShadow").path("entryExitParityGapCount").asLong()).isEqualTo(1);
    }

    @Test
    void hardBlockedRawCounterfactualIsMeasuredButNeverPoisonsExecutablePromotionCohort() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence blocked = evidence(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(3), "TIME_EXIT_24H",
                "{\"hardBlockers\":[\"GLOBAL_SAME_SYMBOL_EXPOSURE\"],"
                        + "\"feeCoverageComplete\":true,\"netPnlUsdt\":\"-0.10\"}");
        blocked.setSelectedAction("STRATEGY_508_TIME_EXIT_SHADOW_BLOCKED");
        evidence.add(blocked);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.node().path("forwardShadow").path("gatePassed").asBoolean()).isTrue();
        assertThat(snapshot.node().path("forwardShadow").path("finalizedEvents").asInt()).isEqualTo(5);
        assertThat(snapshot.node().path("forwardShadow").path("hardGateBlockedEventsAffectPromotion")
                .asBoolean()).isFalse();
        assertThat(snapshot.node().path("rawSignalCounterfactual").path("finalizedEvents").asInt())
                .isEqualTo(6);
        assertThat(snapshot.node().path("rawSignalCounterfactual").path("hardGateBlockedEvents").asInt())
                .isEqualTo(1);
        assertThat(snapshot.node().path("rawSignalCounterfactual").path("livePromotionEligible")
                .asBoolean()).isFalse();
    }

    @Test
    void legacyHardBlockedRowWithoutCohortMetadataIsSafelyRawOnly() throws Exception {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence legacyBlocked = evidence(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(2), "HARD_BLOCKED",
                "{\"hardBlockers\":[\"GLOBAL_OPEN_POSITION_CAP\"]}");
        legacyBlocked.setSelectedAction("STRATEGY_508_TIME_EXIT_SHADOW_BLOCKED");
        removeCohortMetadata(legacyBlocked);
        evidence.add(legacyBlocked);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.node().path("forwardShadow").path("gatePassed").asBoolean()).isTrue();
        assertThat(snapshot.node().path("rawSignalCounterfactual")
                .path("legacyHardBlockedRawOnlyRows").asInt()).isEqualTo(1);
        assertThat(snapshot.node().path("rawSignalCounterfactual")
                .path("unboundPotentialExecutableRows").asInt()).isZero();
    }

    @Test
    void legacyClearRowWithoutCohortMetadataCannotEnterPromotionCohort() throws Exception {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence legacyClear = evidence(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(2), "TIME_EXIT_24H", passingNetContext());
        removeCohortMetadata(legacyClear);
        evidence.add(legacyClear);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.node().path("forwardShadow").path("gatePassed").asBoolean()).isFalse();
        assertThat(snapshot.node().path("forwardShadow")
                .path("unboundPotentialExecutableRows").asInt()).isEqualTo(1);
        assertThat(snapshot.blockers()).contains("FORWARD_SHADOW_GATE_NOT_READY");
    }

    @Test
    void cumulativeLiveLossAtThreeUsdtTriggersExperimentFuse() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < 5; i++) {
            RuntimeDecisionEvidence row = evidence(now.minusDays(4L - i), "TIME_EXIT_24H",
                    "{\"hardBlockers\":[],\"feeCoverageComplete\":true," +
                            "\"fillAggregationComplete\":true,\"feeSignPreserved\":true," +
                            "\"netPnlUsdt\":\"-0.60\"}");
            row.setSelectedAction("STRATEGY_508_TIME_EXIT_LIVE_EXECUTED");
            row.setExecutionMode("LIVE_MICRO");
            row.setLiveSignalId(900L + i);
            row.setOrderSent(true);
            evidence.add(row);
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
    void syntheticExactFieldsCannotBypassUnavailableLiveEvidenceProducerContract() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence firstProbe = evidence(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1), "TIME_EXIT_24H",
                "{\"hardBlockers\":[],\"entryParityGap\":false,\"exitParityGap\":false," +
                        "\"feeCoverageComplete\":true,\"fillAggregationComplete\":true," +
                        "\"feeSignPreserved\":true,\"netPnlUsdt\":\"0.05\"}");
        firstProbe.setId(7001L);
        firstProbe.setSelectedAction("STRATEGY_508_TIME_EXIT_LIVE_EXECUTED");
        firstProbe.setExecutionMode("LIVE_MICRO");
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

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.blockers()).contains(
                "LIVE_EXACT_FILL_PROVENANCE_NOT_IMPLEMENTED", "FIRST_PROBE_EXECUTION_NOT_VERIFIED");
        assertThat(snapshot.node().path("livePilot").path("firstProbeVerified").asBoolean()).isFalse();
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
        firstProbe.setSelectedAction("STRATEGY_508_TIME_EXIT_LIVE_EXECUTED");
        firstProbe.setExecutionMode("LIVE_MICRO");
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

    @Test
    void duplicateShadowEventFailsClosedInsteadOfInflatingSample() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence original = evidence.get(2);
        RuntimeDecisionEvidence duplicate = evidence(
                original.getEvidenceTime(), original.getFinalOutcome(),
                "{\"hardBlockers\":[],\"feeCoverageComplete\":true," +
                        "\"netPnlUsdt\":\"0.10\"}");
        duplicate.setId(8001L);
        evidence.add(duplicate);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.node().path("forwardShadow").path("duplicateEventRows").asInt())
                .isEqualTo(1);
        assertThat(snapshot.node().path("forwardShadow").path("finalizedEvents").asInt())
                .isEqualTo(5);
    }

    @Test
    void incompleteMinuteLatticeShadowOutcomeFailsForwardGate() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        setContextDecimal(evidence.get(2), "oneMinuteCoverage", "0.9993");
        setContextBoolean(evidence.get(2), "minuteLatticeExact", false);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.node().path("forwardShadow").path("incompleteFeeEvidenceEvents").asInt())
                .isEqualTo(1);
        assertThat(snapshot.node().path("forwardShadow").path("gatePassed").asBoolean()).isFalse();
    }

    @Test
    void recentPendingShadowWithoutExactEntryTimeFailsClosed() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        evidence.add(evidence(LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
                "PENDING_24H", "{\"hardBlockers\":[]}"));
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.node().path("forwardShadow").path("canonicalTimingGapRows").asInt())
                .isEqualTo(1);
        assertThat(snapshot.node().path("forwardShadow").path("gatePassed").asBoolean()).isFalse();
    }

    @Test
    void pendingRowWhoseContextClaimsFinalizedOutcomeFailsClosed() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence pending = evidence.get(0);
        setContext(pending, "{\"hardBlockers\":[],\"finalized\":true," +
                "\"outcome\":\"TIME_EXIT_24H\"}");
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.node().path("forwardShadow").path("outcomeContextMismatchRows").asInt())
                .isEqualTo(1);
        assertThat(snapshot.node().path("forwardShadow").path("gatePassed").asBoolean()).isFalse();
    }

    @Test
    void malformedOrMissingFeeShadowEvidenceFailsClosed() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        evidence.get(2).setPolicyInputsJson("{bad-json");
        setContext(evidence.get(3), "{\"hardBlockers\":[],\"netPnlUsdt\":\"0.10\"}");
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.node().path("forwardShadow").path("malformedContextRows").asInt())
                .isEqualTo(1);
        assertThat(snapshot.node().path("forwardShadow").path("incompleteFeeEvidenceEvents").asInt())
                .isEqualTo(1);
    }

    @Test
    void nonShadowExecutionRowCannotCountTowardForwardGate() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        evidence.get(2).setExecutionMode("LIVE_MICRO");
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.node().path("forwardShadow").path("nonShadowExecutionModeRows").asInt())
                .isEqualTo(1);
        assertThat(snapshot.node().path("forwardShadow").path("finalizedEvents").asInt())
                .isEqualTo(4);
    }

    @Test
    void exactFeeButNonPositiveFirstProbeCannotAdvancePilot() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence firstProbe = evidence(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1), "TIME_EXIT_24H",
                "{\"hardBlockers\":[],\"entryParityGap\":false,\"exitParityGap\":false," +
                        "\"feeCoverageComplete\":true,\"fillAggregationComplete\":true," +
                        "\"feeSignPreserved\":true,\"netPnlUsdt\":\"-0.01\"}");
        firstProbe.setId(7003L);
        firstProbe.setSelectedAction("STRATEGY_508_TIME_EXIT_LIVE_EXECUTED");
        firstProbe.setExecutionMode("LIVE_MICRO");
        firstProbe.setOrderSent(true);
        firstProbe.setLiveSignalId(9003L);
        evidence.add(firstProbe);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);
        when(fixture.liveSignalRepository.findByStrategyIdAndCreatedAtAfter(eq(508L), any()))
                .thenReturn(List.of(policySignal(9003L, true)));

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.blockers()).contains(
                "FIRST_PROBE_EXECUTION_NOT_VERIFIED", "FIRST_PROBE_NET_NOT_POSITIVE");
        assertThat(snapshot.node().path("livePilot").path("firstProbeNetPositive").asBoolean())
                .isFalse();
    }

    @Test
    void forwardShadowUsesSameSinglePositionAndDailyCapCohort() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        LocalDateTime dayA = LocalDateTime.now(ZoneOffset.UTC).toLocalDate().minusDays(3).atStartOfDay();
        RuntimeDecisionEvidence dayAFirst = evidence(dayA, "TIME_EXIT_24H", passingNetContext());
        setContextTime(dayAFirst, "exitTime", dayA.plusMinutes(30));
        RuntimeDecisionEvidence dayASecond = evidence(dayA.plusHours(2), "TIME_EXIT_24H", passingNetContext());
        LocalDateTime dayB = dayA.plusDays(2);
        RuntimeDecisionEvidence dayBFirst = evidence(dayB, "TIME_EXIT_24H", passingNetContext());
        RuntimeDecisionEvidence dayBSecond = evidence(dayB.plusHours(1), "TIME_EXIT_24H", passingNetContext());
        evidence.addAll(List.of(dayAFirst, dayASecond, dayBFirst, dayBSecond));
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        JsonNode forward = snapshot.node().path("forwardShadow");
        assertThat(forward.path("allObservedEntryEvents").asInt()).isEqualTo(10);
        assertThat(forward.path("canonicalAdmittedEntryEvents").asInt()).isEqualTo(8);
        assertThat(forward.path("dailyCapSkippedEvents").asInt()).isEqualTo(1);
        assertThat(forward.path("overlapSkippedEvents").asInt()).isEqualTo(1);
    }

    @Test
    void finalizedShadowWithoutExactEntryExitTimingFailsClosed() throws Exception {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence row = evidence.get(2);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode context = (ObjectNode) mapper.readTree(row.getPolicyInputsJson());
        context.remove(List.of("entryTime", "exitTime"));
        row.setPolicyInputsJson(context.toString());
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.liveEntryReady()).isFalse();
        assertThat(snapshot.node().path("forwardShadow").path("canonicalTimingGapRows").asInt())
                .isGreaterThan(0);
    }

    @Test
    void aToBToAConfigHistoryStartsANewContiguousCurrentCohort() throws Exception {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence row = evidence.get(2);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode context = (ObjectNode) mapper.readTree(row.getPolicyInputsJson());
        context.put("effectivePolicyConfigSha256",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        row.setPolicyInputsJson(context.toString());
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        JsonNode forward = snapshot.node().path("forwardShadow");
        assertThat(forward.path("effectiveConfigMismatchRows").asInt()).isZero();
        assertThat(forward.path("configTransitionRows").asInt()).isEqualTo(1);
        assertThat(forward.path("preResetEvidenceRowsExcluded").asInt()).isEqualTo(3);
        assertThat(forward.path("finalizedEvents").asInt()).isEqualTo(3);
        assertThat(forward.path("observationDays").asInt()).isLessThan(30);
        assertThat(forward.path("configCohortResetAtUtc").asText()).isNotBlank();
        assertThat(forward.path("configCohortSemantics").asText())
                .isEqualTo("CONTIGUOUS_SUFFIX_AFTER_LAST_DIFFERENT_EFFECTIVE_CONFIG_HASH");
        assertThat(forward.path("gatePassed").asBoolean()).isFalse();
    }

    @Test
    void finalizedTimingGapOlderThanWindowAndSeedLookbackDoesNotPoisonCurrentGate() throws Exception {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        List<RuntimeDecisionEvidence> evidence = passingForwardEvidence();
        RuntimeDecisionEvidence old = evidence(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(60),
                "TIME_EXIT_24H", passingNetContext());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode context = (ObjectNode) mapper.readTree(old.getPolicyInputsJson());
        context.remove(List.of("entryTime", "exitTime"));
        old.setPolicyInputsJson(context.toString());
        evidence.add(old);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(evidence);

        Strategy508TimeExitReadinessService.ReadinessSnapshot snapshot =
                fixture.service.snapshot("BTCUSDT", true);

        assertThat(snapshot.node().path("forwardShadow").path("canonicalTimingGapRows").asInt())
                .isZero();
        assertThat(snapshot.node().path("forwardShadow").path("gatePassed").asBoolean()).isTrue();
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
        historical.put("effectivePolicyConfigSha256", TEST_CONFIG_HASH);
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
                    "{\"hardBlockers\":[],\"feeCoverageComplete\":true," +
                            "\"netPnlUsdt\":\"0.10\"}"));
        }
        return rows;
    }

    private String passingNetContext() {
        return "{\"hardBlockers\":[],\"feeCoverageComplete\":true,\"netPnlUsdt\":\"0.10\"}";
    }

    private RuntimeDecisionEvidence evidence(LocalDateTime at, String outcome, String json) {
        RuntimeDecisionEvidence row = new RuntimeDecisionEvidence();
        row.setDecisionId(Math.abs(at.hashCode()) + 1L);
        row.setEvidenceTime(at);
        row.setSymbol("BTCUSDT");
        row.setStrategyId(508L);
        row.setIntervalCode("4h");
        row.setPolicyMode(Strategy508TimeExitPolicy.POLICY_MODE);
        row.setSelectedAction("STRATEGY_508_TIME_EXIT_SHADOW");
        row.setExecutionMode("SHADOW");
        row.setFinalOutcome(outcome);
        setContext(row, json);
        return row;
    }

    private void setContext(RuntimeDecisionEvidence row, String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode context = (ObjectNode) mapper.readTree(json);
            context.put("policyMode", Strategy508TimeExitPolicy.POLICY_MODE);
            context.put("strategyId", 508L);
            context.put("symbol", "BTCUSDT");
            context.put("intervalCode", "4h");
            context.put("source", "okx");
            context.put("barOpenTime", row.getEvidenceTime().minusHours(4).toString());
            context.put("decisionTime", row.getEvidenceTime().toString());
            context.put("notionalUsdt", "10.00");
            context.put("takeProfitPct", "0.06");
            context.put("stopLossPct", "0.12");
            context.put("holdHours", 24);
            context.put("effectivePolicyConfigSha256", TEST_CONFIG_HASH);
            boolean hardBlocked = context.path("hardBlockers").isArray()
                    && !context.path("hardBlockers").isEmpty();
            context.put("cohortSchemaVersion", Strategy508TimeExitPolicy.COHORT_SCHEMA_VERSION);
            context.put("rawSignalCounterfactualEligible", true);
            context.put("counterfactualOutcomeTracked", true);
            context.put("executableCohortEligible", !hardBlocked);
            context.put("promotionCohort", hardBlocked
                    ? Strategy508TimeExitPolicy.RAW_COUNTERFACTUAL_COHORT
                    : Strategy508TimeExitPolicy.EXECUTABLE_SHADOW_COHORT);
            context.put("executionGateOutcome", hardBlocked ? "HARD_BLOCKED" : "PASSED");
            if ("TIME_EXIT_24H".equals(row.getFinalOutcome())
                    || "TP_HIT".equals(row.getFinalOutcome())
                    || "SL_HIT".equals(row.getFinalOutcome())
                    || "OCO_TP".equals(row.getFinalOutcome())
                    || "OCO_SL".equals(row.getFinalOutcome())) {
                context.put("entryTime", row.getEvidenceTime().toString());
                context.put("exitTime", row.getEvidenceTime().plusHours(24).toString());
                context.put("finalized", true);
                context.put("outcome", row.getFinalOutcome());
                context.put("oneMinuteCoverage", 1.0);
                context.put("minuteLatticeExact", true);
                context.put("feeEvidenceSemantics",
                        "DETERMINISTIC_MODELED_FEE_AND_SLIPPAGE_NOT_EXCHANGE_FILL");
                context.put("modeledFeeFieldsComplete", true);
                context.put("entryAndExitFeesUsdt", "0.02");
                context.put("netReturnPct", "1.0");
            }
            row.setPolicyInputsJson(context.toString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void setContextDecimal(RuntimeDecisionEvidence row, String field, String value) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode context = (ObjectNode) mapper.readTree(row.getPolicyInputsJson());
            context.put(field, new BigDecimal(value));
            row.setPolicyInputsJson(context.toString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void setContextBoolean(RuntimeDecisionEvidence row, String field, boolean value) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode context = (ObjectNode) mapper.readTree(row.getPolicyInputsJson());
            context.put(field, value);
            row.setPolicyInputsJson(context.toString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void setContextTime(RuntimeDecisionEvidence row, String field, LocalDateTime value) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode context = (ObjectNode) mapper.readTree(row.getPolicyInputsJson());
            context.put(field, value.toString());
            row.setPolicyInputsJson(context.toString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void removeCohortMetadata(RuntimeDecisionEvidence row) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode context = (ObjectNode) mapper.readTree(row.getPolicyInputsJson());
        context.remove(List.of(
                "cohortSchemaVersion",
                "rawSignalCounterfactualEligible",
                "counterfactualOutcomeTracked",
                "executableCohortEligible",
                "promotionCohort",
                "executionGateOutcome"));
        row.setPolicyInputsJson(context.toString());
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
