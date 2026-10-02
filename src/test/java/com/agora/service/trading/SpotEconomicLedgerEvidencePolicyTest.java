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
        assertEquals(0, new BigDecimal("0.03").compareTo(evidence.lifecycleFeeUsdt()));
        assertEquals("PROVIDER_CASH_FLOW_RECONCILED", evidence.reason());
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

    @Test void rejectedFillsDifferentOwnersAndUnappliedCashCannotBeClaimedExact() {
        var buy = filled(Side.BUY, "0.01"); var sell = filled(Side.SELL, "0.02");
        sell.setState(State.REJECTED);
        assertFalse(SpotEconomicLedgerEvidencePolicy.evaluateDraLifecycle(List.of(buy), List.of(sell)).exactNet());
        sell.setState(State.RECONCILED_FILLED); sell.setLiveSignalId(999L);
        assertFalse(SpotEconomicLedgerEvidencePolicy.evaluateDraLifecycle(List.of(buy), List.of(sell)).exactNet());
        sell.setLiveSignalId(263L); sell.setAppliedGrossQuoteAmount(BigDecimal.ZERO);
        assertFalse(SpotEconomicLedgerEvidencePolicy.evaluateDraLifecycle(List.of(buy), List.of(sell)).exactNet());
    }

    private static SpotExecutionAttempt filled(Side side, String feeUsdt) {
        SpotExecutionAttempt attempt = new SpotExecutionAttempt();
        attempt.setSide(side);
        attempt.setLiveSignalId(263L);
        attempt.setStrategyContract(BtcDraPolicy.POLICY_MODE);
        attempt.setProvider("OKX");
        attempt.setProviderOrderId(side.name());
        attempt.setProviderReceiptJson("{\"ordId\":\"" + side.name() + "\"}");
        attempt.setAveragePrice(new BigDecimal(side == Side.BUY ? "10" : "11.28"));
        attempt.setGrossFillQuantity(BigDecimal.ONE);
        attempt.setNetFillQuantity(BigDecimal.ONE);
        attempt.setGrossQuoteAmount(attempt.getAveragePrice());
        attempt.setAppliedGrossQuoteAmount(attempt.getAveragePrice());
        attempt.setFeeCurrency("USDT");
        attempt.setSignedFeeAmount(new BigDecimal(feeUsdt).negate());
        attempt.setFeeUsdt(new BigDecimal(feeUsdt));
        attempt.setState(State.RECONCILED_FILLED);
        attempt.setAppliedFillQuantity(BigDecimal.ONE);
        attempt.setAppliedFeeUsdt(new BigDecimal(feeUsdt));
        attempt.setFeeReconciliationStatus(FeeReconciliationStatus.RECONCILED);
        return attempt;
    }
}
