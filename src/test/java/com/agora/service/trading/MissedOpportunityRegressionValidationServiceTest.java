package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MissedOpportunityRegressionValidationServiceTest {

    private final TinyLiveMinimumOrderPreviewService tinyLivePreviewService = mock(TinyLiveMinimumOrderPreviewService.class);
    private final ExplorationPolicyService explorationPolicyService = mock(ExplorationPolicyService.class);
    private final AutonomousExplorationLoopService loopService = mock(AutonomousExplorationLoopService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MissedOpportunityRegressionValidationService service =
            new MissedOpportunityRegressionValidationService(
                    tinyLivePreviewService,
                    explorationPolicyService,
                    loopService,
                    mock(ScoreBuyPrePositionExecutionPolicyPreviewService.class),
                    mock(ScoreBuyPrePositionAutoExecutionService.class),
                    mock(ScoreBuyPostScoutAutoAddExecutionService.class),
                    mock(ScoreBuyConfirmedDeployAutoExecutionService.class),
                    mock(CapitalAllocationPolicyPreviewService.class),
                    mock(StagedAddPolicyService.class),
                    jdbc,
                    objectMapper);

    @Test
    void autonomousOpportunityReadinessIncludesIneligibleContractFields() throws Exception {
        when(tinyLivePreviewService.preview("BTCUSDT", 574L, "LONG")).thenReturn(preview(
                List.of("NO_CURRENT_BUY_CANDIDATE"),
                List.of("signalProximityState=NEAR_BUY_THRESHOLD")));
        when(explorationPolicyService.getExplorationReadiness("BTCUSDT", 574L, "LONG"))
                .thenReturn("eligible=false blockers=[NO_CURRENT_BUY_CANDIDATE]");
        when(loopService.getAutonomousExplorationLoopStatus("BTCUSDT", 574L, "LONG"))
                .thenReturn("currentState=HALT_AND_NOTIFY blockers=[NO_CURRENT_BUY_CANDIDATE]");

        JsonNode root = objectMapper.readTree(
                service.validateAutonomousOpportunityReadiness("BTCUSDT", 574L, "LONG"));

        assertThat(root.path("eligible").asBoolean()).isFalse();
        assertThat(root.path("orderSent").asBoolean()).isFalse();
        assertThat(root.path("reason").asText()).contains("not allowed to buy yet");
    }

    @Test
    void autonomousOpportunityReadinessMarksEligibleOnlyForUnblockedMissedOpportunityRisk() throws Exception {
        when(tinyLivePreviewService.preview("BTCUSDT", 574L, "LONG")).thenReturn(preview(List.of(), List.of()));
        when(explorationPolicyService.getExplorationReadiness("BTCUSDT", 574L, "LONG"))
                .thenReturn("eligible=true\nblockers=[]");
        when(loopService.getAutonomousExplorationLoopStatus("BTCUSDT", 574L, "LONG"))
                .thenReturn("currentState=READY_TO_EXPLORE\nblockers=[]");

        JsonNode root = objectMapper.readTree(
                service.validateAutonomousOpportunityReadiness("BTCUSDT", 574L, "LONG"));

        assertThat(root.path("eligible").asBoolean()).isTrue();
        assertThat(root.path("orderSent").asBoolean()).isFalse();
        assertThat(root.path("readinessClassification").asText()).isEqualTo("MISSED_OPPORTUNITY_RISK");
    }

    @Test
    void autonomousOpportunityTreatsMissingOcoInputsAsWarningUntilBuyCandidateExists() throws Exception {
        when(tinyLivePreviewService.preview("BTCUSDT", 574L, "LONG")).thenReturn(preview(
                List.of("NO_CURRENT_BUY_CANDIDATE", "OCO_PREFLIGHT_FAILED"),
                List.of("signalProximityState=NEAR_BUY_THRESHOLD"),
                "NOT_READY_MISSING_ENTRY_TP_SL"));
        when(explorationPolicyService.getExplorationReadiness("BTCUSDT", 574L, "LONG"))
                .thenReturn("eligible=false blockers=[NO_CURRENT_BUY_CANDIDATE, OCO_PREFLIGHT_FAILED]");
        when(loopService.getAutonomousExplorationLoopStatus("BTCUSDT", 574L, "LONG"))
                .thenReturn("currentState=HALT_AND_NOTIFY blockers=[NO_CURRENT_BUY_CANDIDATE, OCO_PREFLIGHT_FAILED]");

        JsonNode root = objectMapper.readTree(
                service.validateAutonomousOpportunityReadiness("BTCUSDT", 574L, "LONG"));

        assertThat(root.path("blockers").toString()).doesNotContain("OCO_PREFLIGHT_FAILED");
        assertThat(root.path("warnings").toString())
                .contains("ocoPreflightPendingUntilBuyCandidate=NOT_READY_MISSING_ENTRY_TP_SL");
        assertThat(root.path("readinessClassification").asText()).isEqualTo("WATCH_SIGNAL_NEAR_BUY_THRESHOLD");
    }

    @Test
    void highForwardScanDeduplicatesAndExcludesHoldRepresentations() {
        LocalDateTime eventTime = matureEventTime();
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 77375L, eventTime);
        runtime.put("selected_action", "EVALUATED_ONLY");
        runtime.put("decision", "HOLD");
        runtime.put("signal_source", "LOCAL_TRADINGVIEW");

        Map<String, Object> audit = row("DECISION_AUDIT", 77375L, eventTime);
        audit.put("selected_action", "HOLD");
        audit.put("decision", "HOLD");
        audit.put("signal_source", "SIGNAL_EVAL");
        audit.put("policy_inputs_json", "{\"intentCreated\":false}");
        stubNoBuyQueries(List.of(runtime), List.of(audit), List.of());

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.rawObservationCount()).isEqualTo(2);
        assertThat(scan.uniqueObservationCount()).isEqualTo(1);
        assertThat(scan.duplicateRepresentationCount()).isEqualTo(1);
        assertThat(scan.excludedNonBuyObservationCount()).isEqualTo(1);
        assertThat(scan.eligibleBlockedBuyIntentCount()).isZero();
        assertThat(scan.count()).isZero();
    }

    @Test
    void highForwardScanExcludesLocalTradingViewNoBuyEvenWhenEvaluationIntentFlagIsTrue() {
        LocalDateTime eventTime = matureEventTime();
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 77376L, eventTime);
        runtime.put("selected_action", "HOLD");
        runtime.put("decision", "HOLD");
        runtime.put("side", "HOLD");
        runtime.put("intent_created", true);
        runtime.put("policy_inputs_json", "{\"decision\":\"LOCAL_TRADINGVIEW_NO_BUY\",\"blockers\":\"LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE\"}");
        stubNoBuyQueries(List.of(runtime), List.of(), List.of());

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.uniqueObservationCount()).isEqualTo(1);
        assertThat(scan.excludedNonBuyObservationCount()).isEqualTo(1);
        assertThat(scan.eligibleBlockedBuyIntentCount()).isZero();
        assertThat(scan.count()).isZero();
    }

    @Test
    void highForwardScanExcludesExplicitIntentThatNeverEnteredBuyLane() {
        LocalDateTime eventTime = matureEventTime();
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 77378L, eventTime);
        runtime.put("selected_action", "EVALUATED_ONLY");
        runtime.put("decision", "PASS");
        runtime.put("signal_source", "SIGNAL_EVAL");
        runtime.put("intent_created", true);
        runtime.put("terminal_blocker", "TradePlanQualityGate");
        stubNoBuyQueries(List.of(runtime), List.of(), positiveKlines(eventTime));

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.eligibleBlockedBuyIntentCount()).isZero();
        assertThat(scan.count()).isZero();
    }

    @Test
    void highForwardScanKeepsCapacityBlockOutsideGovernanceFalseBlockPopulation() {
        LocalDateTime eventTime = matureEventTime();
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 77377L, eventTime);
        runtime.put("selected_action", "BLOCK");
        runtime.put("decision", "BUY");
        runtime.put("intent_created", true);
        runtime.put("terminal_blocker", "daily new auto-entry cap reached");
        stubNoBuyQueries(List.of(runtime), List.of(), positiveKlines(eventTime));

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.eligibleBlockedBuyIntentCount()).isZero();
        assertThat(scan.otherObservationCount()).isEqualTo(1);
        assertThat(scan.count()).isZero();
    }

    @Test
    void highForwardScanExcludesDonchianShadowStateAdvance() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 90001L, matureEventTime());
        runtime.put("selected_action", "DONCHIAN_SHADOW_STATE_ADVANCE");
        runtime.put("signal_source", "DONCHIAN_BREAKOUT");
        stubNoBuyQueries(List.of(runtime), List.of(), List.of());

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.rawObservationCount()).isEqualTo(1);
        assertThat(scan.uniqueObservationCount()).isEqualTo(1);
        assertThat(scan.excludedNonBuyObservationCount()).isEqualTo(1);
        assertThat(scan.eligibleBlockedBuyIntentCount()).isZero();
        assertThat(scan.count()).isZero();
    }

    @Test
    void highForwardScanExcludesInformationalPass() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 90002L, matureEventTime());
        runtime.put("selected_action", "SMALL_DRY_RUN");
        runtime.put("decision", "PASS");
        runtime.put("final_outcome", "INFO");
        runtime.put("blocker_reason", "AttentionRule: ExpectedValueGatePass / INFO");
        runtime.put("suppression_reason", "NONE");
        stubNoBuyQueries(List.of(runtime), List.of(), List.of());

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.rawObservationCount()).isEqualTo(1);
        assertThat(scan.uniqueObservationCount()).isEqualTo(1);
        assertThat(scan.excludedNonBuyObservationCount()).isEqualTo(1);
        assertThat(scan.eligibleBlockedBuyIntentCount()).isZero();
        assertThat(scan.count()).isZero();
    }

    @Test
    void highForwardScanExcludesUnblockedIntent() {
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 90003L, matureEventTime());
        runtime.put("selected_action", "ALLOW_ORDER_AFTER_EVIDENCE");
        runtime.put("decision", "BUY");
        runtime.put("intent_created", true);
        runtime.put("final_outcome", "PASS");
        runtime.put("terminal_blocker", "NONE");
        runtime.put("suppression_reason", "NONE");
        stubNoBuyQueries(List.of(runtime), List.of(), List.of());

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.excludedNonBuyObservationCount()).isZero();
        assertThat(scan.eligibleBlockedBuyIntentCount()).isZero();
        assertThat(scan.otherObservationCount()).isEqualTo(1);
        assertThat(scan.classificationCountConserved()).isTrue();
        assertThat(scan.count()).isZero();
    }

    @Test
    void highForwardScanCountsBlockedIntentOnceAcrossRuntimeAndAudit() {
        LocalDateTime eventTime = matureEventTime();
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 91001L, eventTime);
        runtime.put("selected_action", "ALLOW_ORDER_AFTER_EVIDENCE");
        runtime.put("decision", "BUY");
        runtime.put("intent_created", true);
        runtime.put("terminal_blocker", "AutonomousExecutionIntent: evidence required");
        runtime.put("suppression_reason", "NONE");

        Map<String, Object> audit = row("DECISION_AUDIT", 91001L, eventTime);
        audit.put("selected_action", "BLOCK");
        audit.put("signal_source", "ENTRY_SKIP");
        audit.put("terminal_blocker", "EntryDedup");
        audit.put("policy_inputs_json", candidatePlanJson(true));
        stubNoBuyQueries(List.of(runtime), List.of(audit), positiveKlines(eventTime));

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.rawObservationCount()).isEqualTo(2);
        assertThat(scan.uniqueObservationCount()).isEqualTo(1);
        assertThat(scan.duplicateRepresentationCount()).isEqualTo(1);
        assertThat(scan.excludedNonBuyObservationCount()).isZero();
        assertThat(scan.eligibleBlockedBuyIntentCount()).isEqualTo(1);
        assertThat(scan.count()).isEqualTo(1);
        assertThat(scan.examples().path(0).path("intentCreated").asBoolean()).isTrue();
        assertThat(scan.rawCountConserved()).isTrue();
        assertThat(scan.classificationCountConserved()).isTrue();
    }

    @Test
    void highForwardScanMergesWeakRuntimeHoldWithRichBlockedBuyAudit() {
        LocalDateTime eventTime = matureEventTime();
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 91501L, eventTime);
        runtime.put("selected_action", "EVALUATED_ONLY");
        runtime.put("decision", "HOLD");
        runtime.put("signal_source", "LOCAL_TRADINGVIEW");

        Map<String, Object> audit = row("DECISION_AUDIT", 91501L, eventTime);
        audit.put("selected_action", "BLOCK");
        audit.put("signal_source", "ENTRY_SKIP");
        audit.put("terminal_blocker", "TradePlanQualityGate");
        audit.put("policy_inputs_json", candidatePlanJson(true));
        stubNoBuyQueries(List.of(runtime), List.of(audit), positiveKlines(eventTime));

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.rawObservationCount()).isEqualTo(2);
        assertThat(scan.uniqueObservationCount()).isEqualTo(1);
        assertThat(scan.duplicateRepresentationCount()).isEqualTo(1);
        assertThat(scan.excludedNonBuyObservationCount()).isZero();
        assertThat(scan.eligibleBlockedBuyIntentCount()).isEqualTo(1);
        assertThat(scan.otherObservationCount()).isZero();
        assertThat(scan.count()).isEqualTo(1);
        assertThat(scan.rawCountConserved()).isTrue();
        assertThat(scan.classificationCountConserved()).isTrue();
    }

    @Test
    void highForwardScanUnionsSplitPlanButAnyOrderSentKeepsEventOutOfMissedOpportunity() {
        LocalDateTime eventTime = matureEventTime();
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 91601L, eventTime);
        runtime.put("selected_action", "BUY");
        runtime.put("intent_created", true);
        runtime.put("order_sent", true);
        runtime.put("execution_preview_json", "{\"candidateEntry\":100}");

        Map<String, Object> audit = row("DECISION_AUDIT", 91601L, eventTime);
        audit.put("selected_action", "BLOCK");
        audit.put("signal_source", "ENTRY_SKIP");
        audit.put("terminal_blocker", "TradePlanQualityGate");
        audit.put("policy_inputs_json", "{\"candidateTp\":106,\"candidateSl\":88}");
        stubNoBuyQueries(List.of(runtime), List.of(audit), positiveKlines(eventTime));

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.eligibleBlockedBuyIntentCount()).isZero();
        assertThat(scan.excludedNonBuyObservationCount()).isZero();
        assertThat(scan.otherObservationCount()).isEqualTo(1);
        assertThat(scan.count()).isZero();
        assertThat(scan.classificationCountConserved()).isTrue();
    }

    @Test
    void highForwardScanAcceptsAuditEntrySkipWithCompleteCandidatePlan() {
        LocalDateTime eventTime = matureEventTime();
        Map<String, Object> audit = row("DECISION_AUDIT", 92001L, eventTime);
        audit.put("selected_action", "BLOCK");
        audit.put("signal_source", "ENTRY_SKIP");
        audit.put("terminal_blocker", "TradePlanQualityGate");
        audit.put("policy_inputs_json", candidatePlanJson(true));
        stubNoBuyQueries(List.of(), List.of(audit), positiveKlines(eventTime));

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.eligibleBlockedBuyIntentCount()).isEqualTo(1);
        assertThat(scan.count()).isEqualTo(1);
        assertThat(scan.examples().path(0).path("candidatePlanPresent").asBoolean()).isTrue();
    }

    @Test
    void highForwardScanMarksRequestedWindowIncompleteWhenSourceLimitIsHit() {
        LocalDateTime eventTime = matureEventTime();
        List<Map<String, Object>> runtimeRows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 100000L + i, eventTime.minusSeconds(i));
            runtime.put("selected_action", "HOLD");
            runtime.put("decision", "HOLD");
            runtime.put("signal_source", "SIGNAL_EVAL");
            runtimeRows.add(runtime);
        }
        stubNoBuyQueries(runtimeRows, List.of(), List.of());

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.runtimeRowsFetched()).isEqualTo(500);
        assertThat(scan.auditRowsFetched()).isZero();
        assertThat(scan.sourceLimit()).isEqualTo(500);
        assertThat(scan.queryTruncated()).isTrue();
        assertThat(scan.requestedWindowComplete()).isFalse();
        assertThat(scan.rawCountConserved()).isTrue();
        assertThat(scan.classificationCountConserved()).isTrue();
    }

    @Test
    void highForwardScanExposesIndependentQueryFailureTruthTable() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM bt_runtime_decision_evidence")) {
                throw new IllegalStateException("runtime unavailable");
            }
            if (sql.contains("FROM bt_decision_audit")) return List.of();
            return List.of();
        });

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.runtimeQuerySucceeded()).isFalse();
        assertThat(scan.auditQuerySucceeded()).isTrue();
        assertThat(scan.queryErrors()).containsExactly("RUNTIME_QUERY_FAILED:IllegalStateException");
        assertThat(scan.queryTruncated()).isFalse();
        assertThat(scan.requestedWindowComplete()).isFalse();
    }

    @Test
    void highForwardScanExposesAuditQueryFailureIndependently() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM bt_runtime_decision_evidence")) return List.of();
            if (sql.contains("FROM bt_decision_audit")) throw new IllegalStateException("audit unavailable");
            return List.of();
        });

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.runtimeQuerySucceeded()).isTrue();
        assertThat(scan.auditQuerySucceeded()).isFalse();
        assertThat(scan.queryErrors()).containsExactly("AUDIT_QUERY_FAILED:IllegalStateException");
        assertThat(scan.requestedWindowComplete()).isFalse();
    }

    @Test
    void highForwardScanFailsClosedForSameLiveSignalWithDifferentDecisionIds() {
        LocalDateTime eventTime = matureEventTime();
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 120001L, eventTime);
        runtime.put("live_signal_id", 44001L);
        runtime.put("selected_action", "HOLD");
        runtime.put("decision", "HOLD");
        Map<String, Object> audit = row("DECISION_AUDIT", 120002L, eventTime);
        audit.put("live_signal_id", 44001L);
        audit.put("selected_action", "BLOCK");
        audit.put("decision", "BUY");
        audit.put("signal_source", "ENTRY_SKIP");
        audit.put("terminal_blocker", "TradePlanQualityGate");
        audit.put("policy_inputs_json", candidatePlanJson(true));
        stubNoBuyQueries(List.of(runtime), List.of(audit), positiveKlines(eventTime));

        MissedOpportunityRegressionValidationService.HighForwardReturnNoBuyScan scan =
                service.scanHighForwardReturnNoBuy("BTCUSDT", 24);

        assertThat(scan.uniqueObservationCount()).isEqualTo(1);
        assertThat(scan.identityConflictCount()).isEqualTo(1);
        assertThat(scan.eligibleBlockedBuyIntentCount()).isZero();
        assertThat(scan.otherObservationCount()).isEqualTo(1);
        assertThat(scan.rawCountConserved()).isTrue();
        assertThat(scan.classificationCountConserved()).isTrue();
    }

    private TinyLiveMinimumOrderPreviewService.PreviewResult preview(List<String> denialReasons, List<String> warnings) {
        return preview(denialReasons, warnings, "PASS");
    }

    private TinyLiveMinimumOrderPreviewService.PreviewResult preview(List<String> denialReasons,
                                                                    List<String> warnings,
                                                                    String ocoPreflightStatus) {
        return new TinyLiveMinimumOrderPreviewService.PreviewResult(
                "BTCUSDT",
                574L,
                "LONG",
                "AUTO",
                denialReasons.isEmpty() ? "READY" : "NOT_READY_NO_CURRENT_BUY_CANDIDATE",
                false,
                denialReasons,
                warnings,
                denialReasons.isEmpty() ? "BUY" : "HOLD",
                "BT_DECISION_AUDIT",
                denialReasons.isEmpty() ? "ready" : "no_threshold_hit",
                123L,
                456L,
                "2026-06-20T00:00:00Z",
                "1h",
                1L,
                denialReasons.isEmpty() ? "" : "LATEST_SIGNAL_HOLD",
                BigDecimal.valueOf(5),
                BigDecimal.valueOf(5),
                BigDecimal.valueOf(0.00005),
                BigDecimal.valueOf(0.00001),
                BigDecimal.valueOf(0.00001),
                BigDecimal.valueOf(0.1),
                BigDecimal.valueOf(100000),
                BigDecimal.valueOf(101000),
                BigDecimal.valueOf(99000),
                "NO_DUPLICATE_BAR",
                "NO_DUPLICATE",
                "STRICT",
                "none",
                "BTCUSDT:574:LONG",
                "BTCUSDT:574:LONG:OLD",
                true,
                "PASS",
                "R0",
                ocoPreflightStatus,
                "AVAILABLE_CANONICAL_SHADOW_EVIDENCE",
                BigDecimal.valueOf(100),
                0,
                0L,
                0L,
                BigDecimal.ZERO,
                0L,
                1L,
                null,
                null,
                null,
                Instant.parse("2026-06-20T00:05:00Z"));
    }

    private LocalDateTime matureEventTime() {
        return LocalDateTime.now(ZoneOffset.UTC).minusHours(2).truncatedTo(ChronoUnit.MINUTES);
    }

    private Map<String, Object> row(String source, long decisionId, LocalDateTime eventTime) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("row_source", source);
        row.put("decision_id", decisionId);
        row.put("evidence_time", eventTime);
        row.put("symbol", "BTCUSDT");
        row.put("strategy_id", 508L);
        row.put("interval_code", "4h");
        row.put("side", "LONG");
        row.put("bar_open_time", eventTime.truncatedTo(ChronoUnit.HOURS));
        row.put("runtime_evidence_id", "RUNTIME_EVIDENCE".equals(source) ? decisionId + 100000 : null);
        row.put("audit_id", "DECISION_AUDIT".equals(source) ? decisionId : null);
        row.put("selected_action", "HOLD");
        row.put("final_outcome", "PENDING");
        row.put("order_sent", false);
        return row;
    }

    private String candidatePlanJson(boolean intentCreated) {
        return "{\"intentCreated\":" + intentCreated
                + ",\"candidateEntry\":100,\"candidateTp\":106,\"candidateSl\":88}";
    }

    private List<Map<String, Object>> positiveKlines(LocalDateTime eventTime) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("open_time", eventTime);
        entry.put("close_price", new BigDecimal("100"));
        Map<String, Object> horizon = new LinkedHashMap<>();
        horizon.put("open_time", eventTime.plusHours(1));
        horizon.put("close_price", new BigDecimal("102"));
        return List.of(entry, horizon);
    }

    private void stubNoBuyQueries(List<Map<String, Object>> runtimeRows,
                                  List<Map<String, Object>> auditRows,
                                  List<Map<String, Object>> klineRows) {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM bt_runtime_decision_evidence")) {
                return runtimeRows;
            }
            if (sql.contains("FROM bt_decision_audit")) {
                return auditRows;
            }
            if (sql.contains("FROM md_kline")) {
                return klineRows;
            }
            return List.of();
        });
    }
}
