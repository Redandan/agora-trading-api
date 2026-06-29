package com.agora.service.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DataFreshnessShadowReplayHardGatePreviewBuilderTest {

    private final DataFreshnessShadowReplayHardGatePreviewBuilder builder =
            new DataFreshnessShadowReplayHardGatePreviewBuilder();

    @Test
    void enrichesCandidatePlanWithReadOnlyGatePlaceholders() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("candidateEntry", new BigDecimal("65000.00"));
        context.put("candidateTp", new BigDecimal("71500.00"));
        context.put("candidateSl", new BigDecimal("61750.00"));

        builder.enrich(context);

        assertEquals(DataFreshnessShadowReplayHardGatePreviewBuilder.PREVIEW_ONLY_NOT_REPLAYABLE,
                context.get("shadowReplayHardGatePreviewStatus"));
        assertEquals(DataFreshnessShadowReplayHardGatePreviewBuilder.SCOPE_REPLAY_INPUT_PROXY_NOT_RUNTIME_EV,
                context.get("shadowReplayPreviewScope"));
        assertEquals(new BigDecimal("2.0000"), context.get("expectedRProxy"));
        assertEquals("INPUT_PLAN_PROXY_NOT_RUNTIME_EV", context.get("expectedRProxyStatus"));
        assertEquals(DataFreshnessShadowReplayHardGatePreviewBuilder.PLAN_SHAPE_VALID,
                context.get("ocoPlanShapeStatus"));
        assertEquals("OCO_ROUTE_NOT_PROVEN_EXCHANGE_DRY_RUN_REQUIRED", context.get("ocoRouteStatus"));
        assertEquals("not_evaluated_data_freshness_terminal_block", context.get("ev_reason"));
        assertGate(context, "ev_result", "ExpectedValueGate");
        assertGate(context, "oco_preflight", "OcoPreflight");
        assertGate(context, "duplicate_gate", "DuplicateGate");
        assertGate(context, "daily_cap", "DailyCap");
        assertGate(context, "exposure_gate", "ExposureGate");
        assertGate(context, "event_risk", "EventRisk");
        assertGate(context, "open_position", "OpenPosition");
        assertGate(context, "loss_budget", "LossBudget");
        assertEquals("NOT_EVALUATED_REPLAY_INPUT_ONLY", context.get("riskGateResult"));
        assertEquals(false, context.get("orderSent"));
        assertEquals(false, context.get("orderAllowed"));
        assertEquals(false, context.get("intentCreated"));
        assertEquals(false, context.get("ocoPlanCreated"));
        assertEquals(false, context.get("livePolicyRelaxationAllowed"));
        assertEquals(false, context.get("gridMutationAllowed"));
        assertEquals(false, context.get("schedulerEnablementAllowed"));
        assertEquals(false, context.get("telegramSendAllowed"));
        assertEquals(0, context.get("qualityScore"));
        assertEquals("BLOCK", context.get("tqsBand"));
        assertNotNull(context.get("tqs_result"));
    }

    @Test
    void marksInvalidPlanShapeAsNonReplayableProxyOnly() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("candidateEntry", new BigDecimal("65000.00"));
        context.put("candidateTp", new BigDecimal("64000.00"));
        context.put("candidateSl", new BigDecimal("61750.00"));

        builder.enrich(context);

        assertEquals(DataFreshnessShadowReplayHardGatePreviewBuilder.PLAN_SHAPE_INVALID,
                context.get("ocoPlanShapeStatus"));
        assertNull(context.get("expectedRProxy"));
        assertEquals("INPUT_PLAN_PROXY_NOT_AVAILABLE_INVALID_PLAN_SHAPE", context.get("expectedRProxyStatus"));
        assertEquals(false, context.get("orderAllowed"));
        assertEquals(false, context.get("livePolicyRelaxationAllowed"));
    }

    @Test
    void ignoresContextWithoutCandidatePlan() {
        Map<String, Object> context = new LinkedHashMap<>();

        builder.enrich(context);

        assertFalse(context.containsKey("shadowReplayHardGatePreviewStatus"));
    }

    @SuppressWarnings("unchecked")
    private void assertGate(Map<String, Object> context, String key, String name) {
        Map<String, Object> gate = (Map<String, Object>) context.get(key);
        assertEquals(name, gate.get("name"));
        assertEquals(DataFreshnessShadowReplayHardGatePreviewBuilder.STATUS_NOT_EVALUATED, gate.get("status"));
        assertEquals(true, gate.get("evidenceOnly"));
        assertEquals(false, gate.get("liveDecisionChanged"));
    }
}
