package com.agora.service.trading;

import com.agora.model.SpotExecutionAttempt;
import com.agora.model.SpotExecutionAttempt.FeeReconciliationStatus;
import com.agora.model.SpotExecutionAttempt.Side;
import com.agora.model.SpotExecutionAttempt.State;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotEconomicLedgerEvidencePolicyTest {

    @Test
    void acceptsOnlyTerminalBuyAndSellWithReconciledFees() {
        SpotEconomicLedgerEvidencePolicy.Evidence evidence =
                SpotEconomicLedgerEvidencePolicy.evaluateDraLifecycle(
                        List.of(filled(Side.BUY, "0.01")),
                        List.of(filled(Side.SELL, "0.02")));

        assertTrue(evidence.exactNet());
        assertEquals(new BigDecimal("0.03"), evidence.lifecycleFeeUsdt());
        assertEquals("PROVIDER_RECEIPTS_RECONCILED", evidence.reason());
    }

    @Test
    void rejectsPendingFeeOrNonTerminalAttempt() {
        SpotExecutionAttempt pendingFee = filled(Side.SELL, "0.02");
        pendingFee.setFeeReconciliationStatus(FeeReconciliationStatus.PENDING);
        SpotEconomicLedgerEvidencePolicy.Evidence feeEvidence =
                SpotEconomicLedgerEvidencePolicy.evaluateDraLifecycle(
                        List.of(filled(Side.BUY, "0.01")),
                        List.of(pendingFee));

        assertFalse(feeEvidence.exactNet());
        assertEquals("FEE_RECONCILIATION_INCOMPLETE", feeEvidence.reason());

        SpotExecutionAttempt submitting = filled(Side.SELL, "0.02");
        submitting.setState(State.SUBMITTING);
        SpotEconomicLedgerEvidencePolicy.Evidence stateEvidence =
                SpotEconomicLedgerEvidencePolicy.evaluateDraLifecycle(
                        List.of(filled(Side.BUY, "0.01")),
                        List.of(submitting));

        assertFalse(stateEvidence.exactNet());
        assertEquals("NON_TERMINAL_ATTEMPT", stateEvidence.reason());
    }

    private static SpotExecutionAttempt filled(Side side, String feeUsdt) {
        SpotExecutionAttempt attempt = new SpotExecutionAttempt();
        attempt.setSide(side);
        attempt.setState(State.RECONCILED_FILLED);
        attempt.setAppliedFillQuantity(BigDecimal.ONE);
        attempt.setAppliedFeeUsdt(new BigDecimal(feeUsdt));
        attempt.setFeeReconciliationStatus(FeeReconciliationStatus.RECONCILED);
        return attempt;
    }
}
