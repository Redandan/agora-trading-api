package com.agora.service.trading;

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

import java.time.LocalDateTime;
import java.util.List;

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
