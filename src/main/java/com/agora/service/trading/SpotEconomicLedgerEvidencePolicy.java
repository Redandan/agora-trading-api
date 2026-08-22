package com.agora.service.trading;

import com.agora.model.SpotExecutionAttempt;
import com.agora.model.SpotExecutionAttempt.FeeReconciliationStatus;
import com.agora.model.SpotExecutionAttempt.State;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Fail-closed provider-fee evidence policy for the read-only spot ledger. */
public final class SpotEconomicLedgerEvidencePolicy {

    private SpotEconomicLedgerEvidencePolicy() {
    }

    public static Evidence evaluateDraLifecycle(
            List<SpotExecutionAttempt> buyAttempts,
            List<SpotExecutionAttempt> sellAttempts) {
        List<SpotExecutionAttempt> buys = safe(buyAttempts);
        List<SpotExecutionAttempt> sells = safe(sellAttempts);
        if (buys.isEmpty()) return Evidence.missing("MISSING_BUY_ATTEMPT");
        if (sells.isEmpty()) return Evidence.missing("MISSING_SELL_ATTEMPT");

        List<SpotExecutionAttempt> all = new ArrayList<>(buys.size() + sells.size());
        all.addAll(buys);
        all.addAll(sells);
        if (all.stream().anyMatch(attempt -> !terminal(attempt.getState()))) {
            return Evidence.missing("NON_TERMINAL_ATTEMPT");
        }

        List<SpotExecutionAttempt> filledBuys = filled(buys);
        List<SpotExecutionAttempt> filledSells = filled(sells);
        if (filledBuys.isEmpty()) return Evidence.missing("MISSING_APPLIED_BUY_FILL");
        if (filledSells.isEmpty()) return Evidence.missing("MISSING_APPLIED_SELL_FILL");

        List<SpotExecutionAttempt> filled = new ArrayList<>(
                filledBuys.size() + filledSells.size());
        filled.addAll(filledBuys);
        filled.addAll(filledSells);
        if (filled.stream().anyMatch(attempt ->
                attempt.getFeeReconciliationStatus() != FeeReconciliationStatus.RECONCILED
                        || attempt.getAppliedFeeUsdt() == null
                        || attempt.getAppliedFeeUsdt().signum() < 0)) {
            return Evidence.missing("FEE_RECONCILIATION_INCOMPLETE");
        }

        BigDecimal feeUsdt = filled.stream()
                .map(SpotExecutionAttempt::getAppliedFeeUsdt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Evidence(true, feeUsdt, "PROVIDER_RECEIPTS_RECONCILED");
    }

    private static List<SpotExecutionAttempt> safe(List<SpotExecutionAttempt> attempts) {
        return attempts == null ? List.of() : attempts;
    }

    private static List<SpotExecutionAttempt> filled(List<SpotExecutionAttempt> attempts) {
        return attempts.stream()
                .filter(attempt -> attempt.getAppliedFillQuantity() != null
                        && attempt.getAppliedFillQuantity().signum() > 0)
                .toList();
    }

    private static boolean terminal(State state) {
        return state == State.RECONCILED_FILLED
                || state == State.RECONCILED_PARTIAL
                || state == State.REJECTED;
    }

    public record Evidence(boolean exactNet, BigDecimal lifecycleFeeUsdt, String reason) {
        private static Evidence missing(String reason) {
            return new Evidence(false, null, reason);
        }
    }
}
