package com.agora.service.trading;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Read-only evidence classification. A review flag is never exchange-order authority. */
public final class BtcDraPositionReviewPolicy {
    private BtcDraPositionReviewPolicy() { }

    public static Map<String, Object> review(Map<String, Object> observation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "DRA_POSITION_REVIEW_V1");
        out.put("positionAction", "KEEP_EXISTING_RULES");
        out.put("riskSellAuthorized", false);
        out.put("highConfidenceStatus", "NO_VALIDATED_RISK_EXIT_RULE");
        out.put("requirementsForFutureRiskExit", List.of("VERSIONED_ACCEPTED_RULE",
                "FRESH_SOURCE_MATCHED_EVIDENCE", "RECONCILED_OWNED_QUANTITY_AND_COST",
                "PREDECLARED_CONFIRMATION_AND_CONFLICT_RULES"));
        String status;
        if (!(observation.get("openOwnedLots") instanceof Number lots)
                || !(observation.get("pendingFeeAttempts") instanceof Number fees)) {
            status = "MISSING_PROOF_INVENTORY_OR_FEES";
        } else if (fees.longValue() > 0) {
            status = "REVIEW_ACCOUNTING_PENDING";
        } else if (lots.longValue() == 0) {
            status = "NO_OPEN_DRA_POSITION";
        } else if (lots.longValue() != 1 || !"RECORDED_COST_NOT_FEE_EXACT".equals(observation.get("openCostEvidenceStatus"))) {
            status = "REVIEW_INVENTORY_INCOMPLETE";
        } else if (!"CURRENT".equals(observation.get("marketDataStatus"))
                || !Boolean.TRUE.equals(observation.get("decisionMatchesLatestClosedBar"))) {
            status = "REVIEW_STALE_OR_UNMATCHED_EVIDENCE";
        } else if (!(observation.get("lastSignalConditions") instanceof Map<?, ?> signal)
                || !Boolean.TRUE.equals(signal.get("ready"))) {
            status = "MISSING_PROOF_SIGNAL_NOT_READY";
        } else if (!Boolean.TRUE.equals(signal.get("dailyDecision"))) {
            status = "WAIT_FOR_CONFIRMED_DAILY_REVIEW";
        } else if (!List.of("closeAboveEma", "emaRisingFiveDays", "momentumPositive").stream()
                .allMatch(k -> signal.get(k) instanceof Boolean)) {
            status = "MISSING_PROOF_DAILY_CONDITIONS";
        } else {
            status = List.of("closeAboveEma", "emaRisingFiveDays", "momentumPositive").stream()
                    .allMatch(k -> Boolean.TRUE.equals(signal.get(k)))
                    ? "ENTRY_CONDITIONS_STILL_PRESENT" : "ENTRY_CONDITIONS_CHANGED_REVIEW_ONLY";
        }
        out.put("evidenceStatus", status);
        out.put("holdingAgeAction", "OBSERVE_ONLY_NO_TIME_EXIT");
        return out;
    }
}
