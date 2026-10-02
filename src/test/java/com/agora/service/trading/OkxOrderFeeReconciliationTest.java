package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.SpotExecutionAttempt;
import com.agora.model.SpotExecutionAttempt.FeeReconciliationStatus;
import com.agora.model.SpotExecutionAttempt.Side;
import com.agora.model.SpotExecutionAttempt.State;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.SpotExecutionAttemptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OkxOrderFeeReconciliationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    // No Spring or init(): there is no HTTP client and no exchange credential in these tests.
    private final OkxTradingService parser = new OkxTradingService(new OkxTradingProperties(), mapper);

    @Test
    void parsesSavedDraReceiptUsingCumulativeFeeAndNotOptionalLastFillFields() throws Exception {
        var snapshot = parse(receipt());
        assertEquals("USDT", snapshot.feeCurrency());
        assertEquals(new BigDecimal("-0.031611633036"), snapshot.signedFeeAmount());
        assertEquals(new BigDecimal("0.031611633036"), snapshot.feeUsdt());
        assertEquals(new BigDecimal("0.00045767"), snapshot.netQuantity());
    }

    @Test
    void usesOrderTotalForMultipleFillsAndConservativelyHandlesMissingFee() throws Exception {
        ObjectNode order = receipt();
        order.put("side", "buy").put("accFillSz", "0.01")
                .put("fee", "-0.00001").put("feeCcy", "BTC")
                .put("fillFee", "-0.000001").put("fillFeeCcy", "BTC");
        assertEquals(new BigDecimal("0.00999"), parse(order).netQuantity());
        order.remove("fee");
        var missing = parse(order);
        assertNull(missing.feeCurrency());
        assertNull(missing.feeUsdt());
        assertEquals(new BigDecimal("0.00998000"), missing.netQuantity());
        order.put("fee", "0");
        assertEquals(BigDecimal.ZERO.setScale(8), parse(order).feeUsdt());
    }

    @Test
    void unsupportedRebateAndCurrencyRemainPendingAndIdentityMismatchFailsClosed() throws Exception {
        ObjectNode order = receipt();
        order.put("fee", "0.01");
        assertNull(parse(order).feeCurrency());
        order.put("fee", "-0.01").put("rebate", "0.01");
        assertNull(parse(order).feeCurrency());
        order.put("rebate", "0").put("feeCcy", "OKB");
        assertNull(parse(order).feeCurrency());
        order.put("clOrdId", "foreign");
        assertThrows(IllegalStateException.class, () -> parse(order));
    }

    @Test
    void lateSellFeeUpdatesClosedLotOnceWithoutAnotherFillOrProviderCall() throws Exception {
        var snapshot = parse(receipt());
        BigDecimal gross = snapshot.cumulativeGrossQuantity();
        BigDecimal quote = snapshot.averagePrice().multiply(gross);
        var lot = new BtLiveSignal();
        lot.setId(263L);
        lot.setStrategyId(BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID);
        lot.setFilterReason(BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX + "CLOSED");
        lot.setEntryPrice(new BigDecimal("65000"));
        lot.setTradedQty(gross);
        lot.setExitTime(LocalDateTime.parse("2026-08-19T21:00:00"));
        lot.setRealizedPnl(new BigDecimal("1.61218858"));
        var attempt = new SpotExecutionAttempt();
        attempt.setId(1L);
        attempt.setLiveSignalId(263L);
        attempt.setStrategyContract(BtcDraPolicy.POLICY_MODE);
        attempt.setSide(Side.SELL);
        attempt.setProviderOrderId(snapshot.providerOrderId());
        attempt.setState(State.RECONCILED_FILLED);
        attempt.setFeeReconciliationStatus(FeeReconciliationStatus.PENDING);
        attempt.setAppliedFillQuantity(gross);
        attempt.setAppliedGrossQuoteAmount(quote);
        attempt.setGrossQuoteAmount(quote);
        attempt.setGrossFillQuantity(gross);
        attempt.setRemainingLotQuantity(BigDecimal.ZERO);
        var attempts = proxy(SpotExecutionAttemptRepository.class, (method, args) -> switch (method) {
            case "findById", "findByIdForUpdate" -> Optional.of(attempt);
            case "findByLiveSignalIdAndSideOrderByAttemptSequenceAsc", "findByStrategyContractAndSideOrderByCreatedAtAsc" -> List.of(attempt);
            case "saveAndFlush" -> args[0];
            default -> throw new AssertionError("Unexpected write or query: " + method);
        });
        var lots = proxy(BtLiveSignalRepository.class, (method, args) -> switch (method) {
            case "findByIdForUpdate" -> Optional.of(lot);
            case "saveAndFlush" -> args[0];
            default -> throw new AssertionError("Unexpected write or query: " + method);
        });
        var service = new BtcDraExecutionAttemptService(attempts, lots);
        assertTrue(service.findOutstandingSell().isPresent());
        var provider = new BtcDraExecutionAttemptService.ProviderFillSnapshot(snapshot.providerOrderId(),
                snapshot.providerState(), snapshot.averagePrice(), gross, snapshot.netQuantity(),
                snapshot.signedFeeAmount(), snapshot.feeCurrency(), snapshot.feeUsdt(),
                snapshot.providerReceiptJson(), snapshot.providerAt());
        var first = service.applySellSnapshot(1L, provider);
        assertEquals(0, first.appliedFillQuantity().signum());
        assertEquals(new BigDecimal("-0.03161163"), first.realizedPnlDelta());
        assertEquals(new BigDecimal("1.58057695"), lot.getRealizedPnl());
        assertEquals(FeeReconciliationStatus.RECONCILED, attempt.getFeeReconciliationStatus());
        assertTrue(service.findOutstandingSell().isEmpty());
        var repeat = service.applySellSnapshot(1L, provider);
        assertEquals(0, repeat.realizedPnlDelta().signum());
        assertEquals(new BigDecimal("1.58057695"), lot.getRealizedPnl());
        assertEquals(LocalDateTime.parse("2026-08-19T21:00:00"), lot.getExitTime());
        assertEquals(gross, lot.getTradedQty());
    }

    private ObjectNode receipt() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"ordId":"fixture-order","clOrdId":"DRA1S20260726230000","instId":"BTC-USDT",
                 "side":"sell","state":"filled","avgPx":"69070.8","accFillSz":"0.00045767",
                 "fee":"-0.031611633036","feeCcy":"USDT","uTime":"1787173207000"}
                """);
    }

    private OkxTradingService.SpotOrderSnapshot parse(ObjectNode order) {
        ObjectNode response = mapper.createObjectNode().put("code", "0");
        response.putArray("data").add(order);
        return parser.parseSpotOrderLookup(response, "DRA1S20260726230000", "BTC-USDT").snapshot();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method.getName(), args));
    }

    @FunctionalInterface
    private interface Invocation { Object invoke(String method, Object[] args); }
}
