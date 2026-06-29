package com.agora.service.backtest;

import com.agora.service.trading.TradeQualityEngine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
class DataFreshnessShadowReplayHardGatePreviewBuilder {

    static final String PREVIEW_ONLY_NOT_REPLAYABLE = "PREVIEW_ONLY_NOT_REPLAYABLE";
    static final String STATUS_NOT_EVALUATED = "NOT_EVALUATED_REPLAY_INPUT_ONLY";
    static final String SCOPE_REPLAY_INPUT_PROXY_NOT_RUNTIME_EV = "READ_ONLY_REPLAY_INPUT_PROXY_NOT_RUNTIME_EV";
    static final String PLAN_SHAPE_VALID = "PLAN_SHAPE_VALID";
    static final String PLAN_SHAPE_INVALID = "PLAN_SHAPE_INVALID";
    static final String PLAN_SHAPE_UNKNOWN = "PLAN_SHAPE_UNKNOWN";

    void enrich(Map<String, Object> context) {
        if (context == null || !context.containsKey("candidateEntry")) {
            return;
        }
        context.put("shadowReplayHardGatePreviewStatus", PREVIEW_ONLY_NOT_REPLAYABLE);
        context.put("shadowReplayPreviewScope", SCOPE_REPLAY_INPUT_PROXY_NOT_RUNTIME_EV);
        context.put("expectedRProxy", expectedRProxy(context));
        context.put("expectedRProxyStatus", expectedRProxyStatus(context));
        context.put("ocoPlanShapeStatus", ocoPlanShapeStatus(context));
        context.put("ocoRouteStatus", "OCO_ROUTE_NOT_PROVEN_EXCHANGE_DRY_RUN_REQUIRED");
        context.put("candidateContinuedToEv", false);
        context.put("candidateContinuedToTqs", false);
        context.put("gate_enabled", true);
        context.put("ev_reason", "not_evaluated_data_freshness_terminal_block");
        context.put("ev_result", gate("ExpectedValueGate", STATUS_NOT_EVALUATED,
                "candidate plan snapshot exists, but runtime expected R is not evaluated before DataFreshnessGuard"));
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
        context.put("orderAllowed", false);
        context.put("intentCreated", false);
        context.put("livePolicyRelaxationAllowed", false);
        context.put("gridMutationAllowed", false);
        context.put("schedulerEnablementAllowed", false);
        context.put("telegramSendAllowed", false);
        context.put("suppressionReason", "DATA_FRESHNESS_TERMINAL_BLOCK_REPLAY_INPUT_ONLY");
        TradeQualityEngine.applyV0Score(context, "DataFreshnessGuard");
        context.put("tqs_result", context.get("tqs"));
    }

    private BigDecimal expectedRProxy(Map<String, Object> context) {
        BigDecimal entry = decimal(context.get("candidateEntry"));
        BigDecimal tp = decimal(context.get("candidateTp"));
        BigDecimal sl = decimal(context.get("candidateSl"));
        if (!hasValidLongPlanShape(entry, tp, sl)) {
            return null;
        }
        BigDecimal reward = tp.subtract(entry);
        BigDecimal risk = entry.subtract(sl);
        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return reward.divide(risk, 4, RoundingMode.HALF_UP);
    }

    private String expectedRProxyStatus(Map<String, Object> context) {
        return expectedRProxy(context) == null
                ? "INPUT_PLAN_PROXY_NOT_AVAILABLE_INVALID_PLAN_SHAPE"
                : "INPUT_PLAN_PROXY_NOT_RUNTIME_EV";
    }

    private String ocoPlanShapeStatus(Map<String, Object> context) {
        BigDecimal entry = decimal(context.get("candidateEntry"));
        BigDecimal tp = decimal(context.get("candidateTp"));
        BigDecimal sl = decimal(context.get("candidateSl"));
        if (entry == null || tp == null || sl == null) {
            return PLAN_SHAPE_UNKNOWN;
        }
        return hasValidLongPlanShape(entry, tp, sl) ? PLAN_SHAPE_VALID : PLAN_SHAPE_INVALID;
    }

    private boolean hasValidLongPlanShape(BigDecimal entry, BigDecimal tp, BigDecimal sl) {
        return entry != null
                && tp != null
                && sl != null
                && sl.compareTo(entry) < 0
                && entry.compareTo(tp) < 0;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
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
