package com.agora.service.trading;

import com.agora.config.properties.BtcDonchianShadowProperties;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.agora.service.trading.BtcDonchianShadowPolicy.EVIDENCE_SCHEMA_VERSION;
import static com.agora.service.trading.BtcDonchianShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.NORMAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.POLICY_MODE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.STRESS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SYMBOL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BtcDonchianShadowReadinessServiceTest {

    @Test
    void completeThirtyOneDayForwardEvidenceCanOnlyReachShadowReview() {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.SHADOW, true, true);
        when(fixture.repository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
                eq(POLICY_MODE), any(LocalDateTime.class)))
                .thenReturn(forwardRows(fixture, false, false));

        JsonNode report = fixture.service.snapshot(SYMBOL);

        assertThat(report.path("status").asText())
                .isEqualTo("READY_FOR_SHADOW_EVIDENCE_REVIEW_NOT_LIVE");
        assertThat(report.path("forwardGatePassed").asBoolean()).isTrue();
        assertThat(report.path("normalForward").path("uniqueEntries").asInt()).isEqualTo(5);
        assertThat(report.path("normalForward").path("completedTrades").asInt()).isEqualTo(5);
        assertThat(report.path("normalForward").path("netPnlEquityUnits").decimalValue()).isPositive();
        assertThat(report.path("stressForward").path("netPnlEquityUnits").decimalValue()).isNotNegative();
        assertThat(report.path("orderSentViolations").asLong()).isZero();
        assertThat(report.path("liveImplementationPresent").asBoolean(true)).isFalse();
        assertThat(report.path("liveOrderAllowed").asBoolean(true)).isFalse();
        assertThat(report.path("promotionAuthorizationGranted").asBoolean(true)).isFalse();
        assertThat(report.path("blockers")).isEmpty();
    }

    @Test
    void anyOrderSentEvidenceFailsClosed() {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.SHADOW, true, true);
        when(fixture.repository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
                eq(POLICY_MODE), any(LocalDateTime.class)))
                .thenReturn(forwardRows(fixture, true, false));

        JsonNode report = fixture.service.snapshot(SYMBOL);

        assertThat(report.path("status").asText()).isEqualTo("FAIL_CLOSED_RUNTIME_EVIDENCE_INVALID");
        assertThat(report.path("forwardGatePassed").asBoolean()).isFalse();
        assertThat(report.path("orderSentViolations").asLong()).isEqualTo(1);
        assertThat(report.path("blockers").toString()).contains("SHADOW_ORDER_SENT_VIOLATION");
        assertThat(report.path("liveOrderAllowed").asBoolean(true)).isFalse();
    }

    @Test
    void stateHashMismatchFailsClosedEvenWithPositiveSample() {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.SHADOW, true, true);
        when(fixture.repository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
                eq(POLICY_MODE), any(LocalDateTime.class)))
                .thenReturn(forwardRows(fixture, false, true));

        JsonNode report = fixture.service.snapshot(SYMBOL);

        assertThat(report.path("status").asText()).isEqualTo("FAIL_CLOSED_RUNTIME_EVIDENCE_INVALID");
        assertThat(report.path("stateHashMismatchRows").asLong()).isEqualTo(1);
        assertThat(report.path("blockers").toString()).contains("STATE_HASH_MISMATCH");
    }

    @Test
    void offModeNeverReportsReadinessEvenWhenHistoricalParityExists() {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.OFF, true, true);
        when(fixture.repository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
                eq(POLICY_MODE), any(LocalDateTime.class))).thenReturn(List.of());

        JsonNode report = fixture.service.snapshot(SYMBOL);

        assertThat(report.path("status").asText()).isEqualTo("OFF_NOT_COLLECTING");
        assertThat(report.path("forwardGatePassed").asBoolean()).isFalse();
        assertThat(report.path("blockers").toString()).contains("SHADOW_MODE_OFF");
        assertThat(report.path("liveOrderAllowed").asBoolean(true)).isFalse();
    }

    @Test
    void repairedBlockerRemainsAuditableButOnlyUnresolvedBlockerFailsClosed() {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.SHADOW, true, true);
        List<RuntimeDecisionEvidence> resolvedRows = new ArrayList<>(forwardRows(fixture, false, false));
        LocalDateTime repairedOpen = resolvedRows.get(10).getEvidenceTime().minusHours(1);
        resolvedRows.add(blockedEvidence(fixture, 10_000L, repairedOpen));
        List<RuntimeDecisionEvidence> unresolvedRows = new ArrayList<>(resolvedRows);
        unresolvedRows.add(blockedEvidence(fixture, 10_001L, repairedOpen.minusMinutes(30)));
        when(fixture.repository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
                eq(POLICY_MODE), any(LocalDateTime.class))).thenReturn(resolvedRows, unresolvedRows);

        JsonNode repaired = fixture.service.snapshot(SYMBOL);
        JsonNode unresolved = fixture.service.snapshot(SYMBOL);

        assertThat(repaired.path("blockedDataQualityRows").asLong()).isEqualTo(1);
        assertThat(repaired.path("resolvedBlockedDataQualityRows").asLong()).isEqualTo(1);
        assertThat(repaired.path("unresolvedBlockedDataQualityRows").asLong()).isZero();
        assertThat(repaired.path("forwardGatePassed").asBoolean()).isTrue();
        assertThat(unresolved.path("unresolvedBlockedDataQualityRows").asLong()).isEqualTo(1);
        assertThat(unresolved.path("status").asText()).isEqualTo("FAIL_CLOSED_RUNTIME_EVIDENCE_INVALID");
        assertThat(unresolved.path("blockers").toString())
                .contains("UNRESOLVED_DATA_QUALITY_BLOCKER_PRESENT");
    }

    private List<RuntimeDecisionEvidence> forwardRows(Fixture fixture,
                                                      boolean orderSentViolation,
                                                      boolean stateHashViolation) {
        LocalDateTime lastOpen = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).minusHours(1);
        LocalDateTime firstOpen = lastOpen.minusDays(31);
        int count = Math.toIntExact(Duration.between(firstOpen, lastOpen).toHours() + 1);
        List<RuntimeDecisionEvidence> rows = new ArrayList<>(count);
        int[] entryIndexes = {24, 144, 288, 432, 576};
        Map<Integer, List<Map<String, Object>>> eventsByIndex = new LinkedHashMap<>();
        for (int i = 0; i < entryIndexes.length; i++) {
            int entryIndex = entryIndexes[i];
            String normalId = "NORMAL-FORWARD-" + (i + 1);
            String stressId = "STRESS-FORWARD-" + (i + 1);
            eventsByIndex.put(entryIndex, List.of(
                    event(NORMAL.name(), "ENTRY_SIGNAL", normalId, firstOpen.plusHours(entryIndex), Map.of()),
                    event(STRESS.name(), "ENTRY_SIGNAL", stressId, firstOpen.plusHours(entryIndex), Map.of())));
            eventsByIndex.put(entryIndex + 1, List.of(
                    event(NORMAL.name(), "VIRTUAL_TRADE_CLOSED", "NORMAL-EXIT-" + (i + 1),
                            firstOpen.plusHours(entryIndex + 1), Map.of(
                                    "entrySignalId", normalId, "profitLossEquityUnits", 0.01)),
                    event(STRESS.name(), "VIRTUAL_TRADE_CLOSED", "STRESS-EXIT-" + (i + 1),
                            firstOpen.plusHours(entryIndex + 1), Map.of(
                                    "entrySignalId", stressId, "profitLossEquityUnits", 0.005))));
        }
        for (int i = 0; i < count; i++) {
            LocalDateTime open = firstOpen.plusHours(i);
            boolean sent = orderSentViolation && i == count / 2;
            boolean badHash = stateHashViolation && i == count / 2;
            rows.add(evidence(fixture, i + 1L, open, eventsByIndex.getOrDefault(i, List.of()), sent, badHash));
        }
        return rows;
    }

    private RuntimeDecisionEvidence evidence(Fixture fixture,
                                             long id,
                                             LocalDateTime open,
                                             List<Map<String, Object>> events,
                                             boolean orderSent,
                                             boolean badHash) {
        try {
            BtcDonchianShadowEngine.State state = fixture.engine.initialState();
            state.setLastProcessedBarOpenTime(open);
            state.setProcessedBars(id);
            String hash = badHash ? "tampered" : fixture.engine.stateSha256(state);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
            snapshot.put("policyMode", POLICY_MODE);
            snapshot.put("barOpenTime", open);
            snapshot.put("barCloseTime", open.plusHours(1));
            snapshot.put("bootstrap", false);
            snapshot.put("catchUp", false);
            snapshot.put("timingCausal", true);
            snapshot.put("hourlyLatticeComplete", true);
            snapshot.put("feeModelComplete", true);
            snapshot.put("slippageModelComplete", true);
            snapshot.put("stateAfterSha256", hash);
            snapshot.put("stateAfter", state);
            snapshot.put("events", events);

            RuntimeDecisionEvidence row = new RuntimeDecisionEvidence();
            row.setId(id);
            row.setDecisionId(id);
            row.setEvidenceTime(open.plusHours(1));
            row.setSymbol(SYMBOL);
            row.setIntervalCode(INTERVAL);
            row.setPolicyMode(POLICY_MODE);
            row.setExecutionMode("SHADOW_ONLY");
            row.setOrderSent(orderSent);
            row.setFinalOutcome("SHADOW_OBSERVED");
            row.setFeaturesSnapshotJson(fixture.objectMapper.writeValueAsString(snapshot));
            return row;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RuntimeDecisionEvidence blockedEvidence(Fixture fixture, long id, LocalDateTime open) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
            snapshot.put("policyMode", POLICY_MODE);
            snapshot.put("barOpenTime", open);
            snapshot.put("barCloseTime", open.plusHours(1));
            snapshot.put("bootstrap", false);
            snapshot.put("catchUp", true);
            snapshot.put("terminalBlocker", "CATCH_UP_HISTORY_INCOMPLETE");
            snapshot.put("events", List.of());

            RuntimeDecisionEvidence row = new RuntimeDecisionEvidence();
            row.setId(id);
            row.setDecisionId(id);
            row.setEvidenceTime(open.plusHours(1));
            row.setSymbol(SYMBOL);
            row.setIntervalCode(INTERVAL);
            row.setPolicyMode(POLICY_MODE);
            row.setExecutionMode("SHADOW_ONLY");
            row.setOrderSent(false);
            row.setFinalOutcome("BLOCKED_DATA_QUALITY");
            row.setFeaturesSnapshotJson(fixture.objectMapper.writeValueAsString(snapshot));
            return row;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> event(String scenario,
                                      String eventType,
                                      String eventId,
                                      LocalDateTime eventTime,
                                      Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("scenario", scenario);
        event.put("eventType", eventType);
        event.put("eventId", eventId);
        event.put("eventTime", eventTime);
        event.put("payload", payload);
        return event;
    }

    private Fixture fixture(BtcDonchianShadowProperties.Mode mode,
                            boolean evidenceEnabled,
                            boolean goldenPassed) {
        RuntimeDecisionEvidenceRepository repository = mock(RuntimeDecisionEvidenceRepository.class);
        RuntimeDecisionEvidenceService runtimeEvidenceService = mock(RuntimeDecisionEvidenceService.class);
        when(runtimeEvidenceService.isEnabled()).thenReturn(evidenceEnabled);
        BtcDonchianShadowGoldenParityService golden = mock(BtcDonchianShadowGoldenParityService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ObjectNode goldenNode = objectMapper.createObjectNode();
        goldenNode.put("goldenParityPassed", goldenPassed);
        goldenNode.put("status", goldenPassed
                ? "PASS_EXACT_RESEARCH_RUNTIME_GOLDEN_PARITY" : "GOLDEN_DATASET_INCOMPLETE_FAIL_CLOSED");
        when(golden.analyzeNode(SYMBOL)).thenReturn(goldenNode);
        BtcDonchianShadowEngine engine = new BtcDonchianShadowEngine(objectMapper);
        BtcDonchianShadowReadinessService service = new BtcDonchianShadowReadinessService(
                new BtcDonchianShadowProperties(mode), repository, runtimeEvidenceService,
                golden, engine, objectMapper);
        return new Fixture(service, repository, engine, objectMapper);
    }

    private record Fixture(
            BtcDonchianShadowReadinessService service,
            RuntimeDecisionEvidenceRepository repository,
            BtcDonchianShadowEngine engine,
            ObjectMapper objectMapper
    ) {
    }
}
