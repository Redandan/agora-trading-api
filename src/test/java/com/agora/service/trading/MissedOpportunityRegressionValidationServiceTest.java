package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MissedOpportunityRegressionValidationServiceTest {

    private final TinyLiveMinimumOrderPreviewService tinyLivePreviewService = mock(TinyLiveMinimumOrderPreviewService.class);
    private final ExplorationPolicyService explorationPolicyService = mock(ExplorationPolicyService.class);
    private final AutonomousExplorationLoopService loopService = mock(AutonomousExplorationLoopService.class);
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
                    mock(JdbcTemplate.class),
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
}
