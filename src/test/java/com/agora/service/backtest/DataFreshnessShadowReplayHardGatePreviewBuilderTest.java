package com.agora.service.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        assertEquals(false, context.get("intentCreated"));
        assertEquals(false, context.get("ocoPlanCreated"));
        assertEquals(0, context.get("qualityScore"));
        assertEquals("BLOCK", context.get("tqsBand"));
        assertNotNull(context.get("tqs_result"));
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
