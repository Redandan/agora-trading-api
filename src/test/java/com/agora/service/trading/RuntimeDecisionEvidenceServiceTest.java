package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeDecisionEvidenceServiceTest {

    @Mock
    private RuntimeDecisionEvidenceRepository repository;
    @Mock
    private BtDecisionAuditRepository decisionAuditRepository;
    @Mock
    private AutopilotPolicyService autopilotPolicyService;
    @Mock
    private ProbePositionExecutorDryRunService probePositionExecutorDryRunService;

    private RuntimeDecisionEvidenceService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeDecisionEvidenceService(
                repository,
                decisionAuditRepository,
                new ObjectMapper(),
                autopilotPolicyService,
                probePositionExecutorDryRunService);
    }

    @Test
    void autonomousDashboardSeparatesGridOrdersFromOrderSentEvidence() {
        RuntimeDecisionEvidence gridOrder = evidence("BTCUSDT");
        gridOrder.setSelectedAction("EXECUTED_EXISTING_AUTOTRADE");
        gridOrder.setOrderSent(true);
        gridOrder.setFeaturesSnapshotJson("{\"qty\":\"0.00008096\",\"source\":\"GRID_BUY\",\"grid_id\":10}");

        RuntimeDecisionEvidence shadow = evidence("BTCUSDT");
        shadow.setStrategyId(574L);
        shadow.setSelectedAction("SHADOW_EXECUTION_INTENT");
        shadow.setOrderSent(false);
        shadow.setIntentCreated(true);
        shadow.setSuppressionReason("SHADOW_MODE");
        shadow.setWarningsJson("{\"fearGreedWarning\":true}");
        shadow.setEvResultJson("{\"expected_r\":1.2}");
        shadow.setTqsResultJson("{\"qualityScore\":80}");

        when(repository.findRecent(any(LocalDateTime.class), eq("BTCUSDT"), any(Pageable.class)))
                .thenReturn(List.of(gridOrder, shadow));

        String dashboard = service.autonomousReadinessDashboard("BTCUSDT", 43200);

        assertThat(dashboard)
                .contains("orderSentEvidence=0")
                .contains("nonAutonomousOrderEvidence=1")
                .contains("unexpected trades: orderSentEvidence=0, shadowOrderViolations=0, nonAutonomousOrderEvidence=1")
                .doesNotContain("no-unintended-order-proof");
    }

    @Test
    void autonomousDashboardStillCountsStrategyOrdersAsOrderSentEvidence() {
        RuntimeDecisionEvidence strategyOrder = evidence("BTCUSDT");
        strategyOrder.setStrategyId(574L);
        strategyOrder.setLiveSignalId(123L);
        strategyOrder.setSelectedAction("EXECUTED_EXISTING_AUTOTRADE");
        strategyOrder.setOrderSent(true);
        strategyOrder.setFeaturesSnapshotJson("{\"source\":\"LOCAL_TRADINGVIEW\",\"orderSent\":true}");

        when(repository.findRecent(any(LocalDateTime.class), eq("BTCUSDT"), any(Pageable.class)))
                .thenReturn(List.of(strategyOrder));

        String dashboard = service.autonomousReadinessDashboard("BTCUSDT", 43200);

        assertThat(dashboard)
                .contains("orderSentEvidence=1")
                .contains("nonAutonomousOrderEvidence=0")
                .contains("no-unintended-order-proof")
                .contains("riskScalingMode=BLOCKED_SAFETY");
    }

    @Test
    void writeFromDecisionAuditCopiesBudgetSnapshotsToExposureSnapshot() {
        ReflectionTestUtils.setField(service, "enabled", true);
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setId(99L);
        audit.setEventTime(LocalDateTime.parse("2026-07-05T06:00:00"));
        audit.setStrategyId(508L);
        audit.setSymbol("BTCUSDT");
        audit.setIntervalCode("1h");
        audit.setEventType("ENTRY_SKIP");
        audit.setOutcome("BLOCKED");
        audit.setBlocker("EntryDedup");
        audit.setReason("same strategy/symbol/interval LONG exposure already exists");
        audit.setContextJson("{"
                + "\"side\":\"LONG\","
                + "\"selectedAction\":\"ENTRY_DEDUP_SHADOW_CANDIDATE_SNAPSHOT\","
                + "\"executionMode\":\"SHADOW\","
                + "\"expected_r\":0.42,"
                + "\"min_expected_r\":0.2,"
                + "\"riskGateResult\":\"ENTRY_DEDUP_OR_EXPOSURE_BLOCK_WITH_CANDIDATE_SNAPSHOT\","
                + "\"entryPrice\":\"101000\","
                + "\"tpPrice\":\"106050\","
                + "\"slPrice\":\"95950\","
                + "\"dailyCapScope\":\"LIVE_AUTO_TRADE\","
                + "\"dailyCapUsed\":0,"
                + "\"dailyCapLimit\":1,"
                + "\"dailyCapRemaining\":1,"
                + "\"dailyCapSnapshot\":\"scope=LIVE_AUTO_TRADE;liveUsed=0;liveLimit=1;liveRemaining=1\","
                + "\"openMaxLoss\":\"12\","
                + "\"openMaxLossUsdt\":\"12\","
                + "\"openMaxLossCapUsdt\":1000.0,"
                + "\"candidateMaxLossUsdt\":0.25,"
                + "\"maxLossIfWrongUsdt\":0.25,"
                + "\"projectedOpenMaxLossUsdt\":\"12.25\","
                + "\"maxLossCapRemainingUsdt\":\"988\","
                + "\"maxLossSnapshot\":\"open=12;cap=1000;candidate=0.25;projected=12.25;remaining=988\","
                + "\"orderSent\":false,"
                + "\"intentCreated\":true,"
                + "\"ocoPlanCreated\":true,"
                + "\"suppressionReason\":\"SHADOW_MODE\","
                + "\"runtimeEvidencePolicyMode\":\"BLOCK\","
                + "\"runtimeEvidencePolicyReason\":\"ExposureOptimizer/EntryDedup kept original block\""
                + "}");

        when(repository.findByDecisionId(99L)).thenReturn(Optional.empty());
        when(repository.save(any(RuntimeDecisionEvidence.class))).thenAnswer(inv -> inv.getArgument(0));
        when(autopilotPolicyService.decide(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AutopilotPolicyService.Decision("BLOCK", "test policy", "{}"));
        when(probePositionExecutorDryRunService.previewJson(any())).thenReturn("{}");

        Optional<RuntimeDecisionEvidence> written = service.writeFromDecisionAudit(audit);

        assertThat(written).isPresent();
        assertThat(written.get().getExposureSnapshotJson())
                .contains("\"dailyCapSnapshot\":\"scope=LIVE_AUTO_TRADE;liveUsed=0;liveLimit=1;liveRemaining=1\"")
                .contains("\"maxLossSnapshot\":\"open=12;cap=1000;candidate=0.25;projected=12.25;remaining=988\"")
                .contains("\"dailyCapLimit\":1")
                .contains("\"candidateMaxLossUsdt\":0.25")
                .contains("\"maxLossIfWrongUsdt\":0.25");
        assertThat(written.get().getOrderSent()).isFalse();
        assertThat(written.get().getSuppressionReason()).isEqualTo("SHADOW_MODE");
    }

    private RuntimeDecisionEvidence evidence(String symbol) {
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setEvidenceTime(LocalDateTime.now());
        evidence.setSymbol(symbol);
        evidence.setFinalOutcome("PENDING");
        evidence.setEvResultJson("{\"status\":\"NOT_EVALUATED\"}");
        evidence.setTqsResultJson("{\"status\":\"NOT_EVALUATED\"}");
        evidence.setRiskGateResultJson("{\"status\":\"NOT_EVALUATED\"}");
        evidence.setWarningsJson("{\"status\":\"NONE\"}");
        return evidence;
    }
}
