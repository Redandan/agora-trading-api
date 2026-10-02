package com.agora.service.trading;

import com.agora.model.BtDecisionAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpotFillReceiptEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void baseBuyFeeIsNotDeductedTwiceAndPartialSellsSumOnce() {
        var buy = SpotFillReceiptEvidence.calculate("B", "BUY", d("100"), d("1"), d("0.999"),
                d("-0.001"), "BTC", d("0.1"), d("0.999"));
        var first = SpotFillReceiptEvidence.calculate("S1", "SELL", d("110"), d("0.4"), d("0.4"),
                d("-0.044"), "USDT", d("0.044"), d("0.4"));
        var second = SpotFillReceiptEvidence.calculate("S2", "SELL", d("120"), d("0.599"), d("0.599"),
                d("-0.07188"), "USDT", d("0.07188"), d("0.599"));
        var result = SpotEconomicLedgerEvidencePolicy.lifecycle(List.of(buy, first, second), false);
        assertTrue(result.exactNet());
        assertEquals(0, d("100").compareTo(result.cashCostUsdt()));
        assertEquals(0, d("15.76412").compareTo(result.providerNetPnl()));
        assertEquals(0, d("0.21588").compareTo(result.lifecycleFeeUsdt()));
    }

    @Test void quoteBuyFeeIsCashCostAndZeroFeeIsValid() {
        var buy = SpotFillReceiptEvidence.calculate("B", "BUY", d("100"), d("1"), d("1"),
                d("-0.1"), "USDT", d("0.1"), d("1"));
        var sell = SpotFillReceiptEvidence.calculate("S", "SELL", d("110"), d("1"), d("1"),
                d("0"), "USDT", d("0"), d("1"));
        var result = SpotEconomicLedgerEvidencePolicy.lifecycle(List.of(buy, sell), false);
        assertEquals(0, d("9.9").compareTo(result.providerNetPnl()));
    }

    @Test void missingFeesRebatesUnsupportedCurrencyAndOverAllocationFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> SpotFillReceiptEvidence.calculate("B", "BUY",
                d("100"), d("1"), d("1"), null, null, null, d("1")));
        assertThrows(IllegalArgumentException.class, () -> SpotFillReceiptEvidence.calculate("B", "BUY",
                d("100"), d("1"), d("1"), d("0.001"), "USDT", d("0.001"), d("1")));
        assertThrows(IllegalArgumentException.class, () -> SpotFillReceiptEvidence.calculate("S", "SELL",
                d("100"), d("1"), d("1"), d("-0.001"), "BTC", d("0.1"), d("1")));
        assertThrows(IllegalArgumentException.class, () -> SpotFillReceiptEvidence.calculate("S", "SELL",
                d("100"), d("1"), d("1"), d("0"), "USDT", d("0"), d("1.01")));
    }

    @Test void residualDustAndRepeatedProviderOrdersAreNotCompleteLifecycles() {
        var buy = SpotFillReceiptEvidence.calculate("B", "BUY", d("100"), d("1"), d("1"),
                d("0"), "USDT", d("0"), d("1"));
        var sell = SpotFillReceiptEvidence.calculate("S", "SELL", d("110"), d("0.99999999"), d("0.99999999"),
                d("0"), "USDT", d("0"), d("0.99999999"));
        assertEquals("UNRECONCILED_BASE_QUANTITY_OR_DUST:residualBtc=0.00000001",
                SpotEconomicLedgerEvidencePolicy.lifecycle(List.of(buy, sell), false).reason());
        assertEquals("DUPLICATE_OR_CONFLICTING_PROVIDER_ORDER",
                SpotEconomicLedgerEvidencePolicy.lifecycle(List.of(buy, buy, sell), false).reason());
    }

    @Test void tvAggregateSellAllocationsAndDuplicateAuditsAreReconciledPerLot() throws Exception {
        TradeResult buy = fill("B", "100", "1", "0");
        TradeResult sell = fill("S", "110", "2", "-0.22");
        BtDecisionAudit b = row(SpotFillReceiptEvidence.capture("BUY", "CB", buy, d("1")));
        BtDecisionAudit s = row(SpotFillReceiptEvidence.capture("SELL", "CS", sell, d("1")));
        var result = SpotEconomicLedgerEvidencePolicy.evaluateTvLifecycle(List.of(b, s, s), mapper);
        assertTrue(result.exactNet());
        assertEquals(0, d("9.89").compareTo(result.providerNetPnl()));
        BtDecisionAudit conflict = row(SpotFillReceiptEvidence.capture("SELL", "CS", sell, d("0.9")));
        assertFalse(SpotEconomicLedgerEvidencePolicy.evaluateTvLifecycle(List.of(b, s, conflict), mapper).exactNet());
    }

    @Test void normalizedBaseFeeRoundingIsAcceptedWithoutRoundingBaseQuantity() {
        var flow = SpotFillReceiptEvidence.calculate("B", "BUY", d("86418.2"), d("0.000347"),
                d("0.000346653"), d("-0.000000347"), "BTC", d("0.02998712"), d("0.000346653"));
        assertEquals(0, d("29.9871154").compareTo(flow.cashUsdt()));
    }

    @Test void exactLotRequiresTheWholeProviderOrderToBeAllocatedAcrossOwnersOnce() throws Exception {
        var buy1 = row(SpotFillReceiptEvidence.capture("BUY", "B1", fill("B1", "100", "1", "0"), d("1")));
        var buy2 = row(SpotFillReceiptEvidence.capture("BUY", "B2", fill("B2", "100", "1", "0"), d("1")));
        var sell = fill("S", "110", "2", "-0.22");
        var sell1 = row(SpotFillReceiptEvidence.capture("SELL", "CS", sell, d("1")));
        var sell2 = row(SpotFillReceiptEvidence.capture("SELL", "CS", sell, d("1")));
        buy1.setLiveSignalId(1L); sell1.setLiveSignalId(1L);
        buy2.setLiveSignalId(2L); sell2.setLiveSignalId(2L);
        assertFalse(SpotEconomicLedgerEvidencePolicy.evaluateTvLifecycle(1L, List.of(buy1, sell1), mapper).exactNet());
        assertTrue(SpotEconomicLedgerEvidencePolicy.evaluateTvLifecycle(1L,
                List.of(buy1, buy2, sell1, sell2, sell1), mapper).exactNet());
        var extra = row(SpotFillReceiptEvidence.capture("SELL", "CS", sell, d("1")));
        extra.setLiveSignalId(3L);
        assertFalse(SpotEconomicLedgerEvidencePolicy.evaluateTvLifecycle(1L,
                List.of(buy1, buy2, sell1, sell2, extra), mapper).exactNet());
    }

    private BtDecisionAudit row(java.util.Map<String, Object> fields) throws Exception {
        var row = new BtDecisionAudit(); row.setEventType(SpotFillReceiptEvidence.SCHEMA);
        row.setContextJson(mapper.writeValueAsString(fields)); return row;
    }
    private TradeResult fill(String id, String price, String qty, String fee) {
        var fill = new TradeResult(); fill.setOrderId(id); fill.setAvgPrice(d(price));
        fill.setGrossQty(d(qty)); fill.setQty(d(qty)); fill.setFeeAmount(d(fee));
        fill.setFeeCurrency("USDT"); fill.setFeeUsdt(d(fee).negate()); return fill;
    }
    private static BigDecimal d(String n) { return new BigDecimal(n); }
}
