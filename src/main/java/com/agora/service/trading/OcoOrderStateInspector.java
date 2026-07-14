package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only, fail-closed inspection of an OKX OCO parent and every visible child order. */
@Service
@RequiredArgsConstructor
public class OcoOrderStateInspector {

    private static final Set<String> ACTIVE_STATES = Set.of(
            "live", "effective", "partially_effective", "pause");

    private final OkxTradingService okxTradingService;

    public Inspection inspectSpot(String symbol, Long algoId) {
        return inspect(symbol, algoId, false);
    }

    public Inspection inspectSwap(String symbol, Long algoId) {
        return inspect(symbol, algoId, true);
    }

    private Inspection inspect(String symbol, Long algoId, boolean swap) {
        if (symbol == null || symbol.isBlank() || algoId == null) {
            return Inspection.failed("missing", "INVALID_OCO_REFERENCE");
        }

        JsonNode parent;
        try {
            parent = swap
                    ? okxTradingService.getSwapAlgoOrder(symbol, algoId)
                    : okxTradingService.getAlgoOrder(symbol, algoId);
        } catch (Exception e) {
            return Inspection.failed("query_failed", "PARENT_QUERY_FAILED:" + errorMessage(e));
        }
        if (parent == null || parent.isMissingNode() || parent.isNull() || !parent.isObject()) {
            return Inspection.failed("missing", "PARENT_NOT_FOUND");
        }

        String parentState = normalizeState(parent.path("state").asText(""));
        BigDecimal parentFillPrice = positiveDecimal(parent.path("avgPx").asText(null));
        List<String> errors = new ArrayList<>();
        List<String> childOrderIds = childOrderIds(parent.path("ordIdList"));
        String filledChildOrderId = null;
        BigDecimal childFillPrice = null;

        for (String childOrderId : childOrderIds) {
            try {
                JsonNode child = swap
                        ? okxTradingService.querySwapOrderDetail(symbol, childOrderId)
                        : okxTradingService.querySpotOrderDetail(symbol, childOrderId);
                if (child == null || child.isMissingNode() || child.isNull() || !child.isObject()) {
                    errors.add("CHILD_NOT_FOUND:" + childOrderId);
                    continue;
                }
                if ("filled".equals(normalizeState(child.path("state").asText("")))
                        && filledChildOrderId == null) {
                    filledChildOrderId = childOrderId;
                    childFillPrice = positiveDecimal(child.path("avgPx").asText(null));
                }
            } catch (Exception e) {
                errors.add("CHILD_QUERY_FAILED:" + childOrderId + ":" + errorMessage(e));
            }
        }

        boolean filled = "filled".equals(parentState)
                || filledChildOrderId != null
                || ("canceled".equals(parentState) && parentFillPrice != null);
        boolean queryComplete = errors.isEmpty();
        boolean active = !filled && queryComplete && ACTIVE_STATES.contains(parentState);
        boolean canceled = !filled && queryComplete && "canceled".equals(parentState);
        BigDecimal fillPrice = childFillPrice != null ? childFillPrice : parentFillPrice;
        return new Inspection(parentState, queryComplete, active, filled, canceled,
                fillPrice, filledChildOrderId, childOrderIds, errors,
                text(parent, "cTime"), text(parent, "tpTriggerPx"),
                text(parent, "slTriggerPx"), text(parent, "sz"));
    }

    private List<String> childOrderIds(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String id = item.asText("").trim();
            if (!id.isBlank()) ids.add(id);
        }
        return List.copyOf(ids);
    }

    private String normalizeState(String state) {
        if (state == null || state.isBlank()) return "unknown";
        return state.trim().toLowerCase(Locale.ROOT);
    }

    private BigDecimal positiveDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal parsed = new BigDecimal(value);
            return parsed.signum() > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private String errorMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() <= 160 ? message : message.substring(0, 160);
    }

    public record Inspection(
            String parentState,
            boolean queryComplete,
            boolean active,
            boolean filled,
            boolean canceled,
            BigDecimal fillPrice,
            String filledChildOrderId,
            List<String> childOrderIds,
            List<String> errors,
            String parentCreatedTimeMillis,
            String takeProfitTriggerPrice,
            String stopLossTriggerPrice,
            String size
    ) {
        public Inspection {
            childOrderIds = childOrderIds == null ? List.of() : List.copyOf(childOrderIds);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        static Inspection failed(String state, String error) {
            return new Inspection(state, false, false, false, false,
                    null, null, List.of(), List.of(error), null, null, null, null);
        }

        public String effectiveState() {
            if (filled) return "filled";
            if (canceled) return "canceled";
            return parentState;
        }
    }
}
