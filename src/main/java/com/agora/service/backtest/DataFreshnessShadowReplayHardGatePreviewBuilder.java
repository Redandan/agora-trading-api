package com.agora.service.backtest;

import com.agora.service.trading.TradeQualityEngine;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
class DataFreshnessShadowReplayHardGatePreviewBuilder {

    static final String PREVIEW_ONLY_NOT_REPLAYABLE = "PREVIEW_ONLY_NOT_REPLAYABLE";
    static final String STATUS_NOT_EVALUATED = "NOT_EVALUATED_REPLAY_INPUT_ONLY";

    void enrich(Map<String, Object> context) {
        if (context == null || !context.containsKey("candidateEntry")) {
            return;
        }
        context.put("shadowReplayHardGatePreviewStatus", PREVIEW_ONLY_NOT_REPLAYABLE);
        context.put("candidateContinuedToEv", false);
        context.put("candidateContinuedToTqs", false);
        context.put("gate_enabled", true);
        context.put("ev_reason", "not_evaluated_data_freshness_terminal_block");
        context.put("ev_result", gate("ExpectedValueGate", STATUS_NOT_EVALUATED,
                "candidate plan snapshot exists, but expected R is not evaluated before DataFreshnessGuard"));
        context.put("oco_preflight", gate("OcoPreflight", STATUS_NOT_EVALUATED,
                "entry/tp/sl prices exist, but no OCO dry-run or exchange preflight was executed"));
        context.put("duplicate_gate", gate("DuplicateGate", STATUS_NOT_EVALUATED,
                "duplicate/open-signal state was not queried by the replay input collector"));
        context.put("daily_cap", gate("DailyCap", STATUS_NOT_EVALUATED,
                "daily cap and loss-budget state were not queried by the replay input collector"));
        context.put("exposure_gate", gate("ExposureGate", STATUS_NOT_EVALUATED,
                "portfolio exposure was not queried by the replay input collector"));
        context.put("event_risk", gate("EventRisk", STATUS_NOT_EVALUATED,
                "event risk was not queried by the replay input collector"));
        context.put("open_position", gate("OpenPosition", STATUS_NOT_EVALUATED,
                "open position state was not queried by the replay input collector"));
        context.put("loss_budget", gate("LossBudget", STATUS_NOT_EVALUATED,
                "loss-budget state was not queried by the replay input collector"));
        context.put("riskGateResult", "NOT_EVALUATED_REPLAY_INPUT_ONLY");
        context.put("ocoCapable", true);
        context.put("ocoPlanCreated", false);
        context.put("orderSent", false);
        context.put("intentCreated", false);
        context.put("suppressionReason", "DATA_FRESHNESS_TERMINAL_BLOCK_REPLAY_INPUT_ONLY");
        TradeQualityEngine.applyV0Score(context, "DataFreshnessGuard");
        context.put("tqs_result", context.get("tqs"));
    }

    private Map<String, Object> gate(String name, String status, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("status", status);
        result.put("reason", reason);
        result.put("evidenceOnly", true);
        result.put("liveDecisionChanged", false);
        return result;
    }
}
