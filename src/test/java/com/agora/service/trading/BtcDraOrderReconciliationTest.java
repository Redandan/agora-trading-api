package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.model.*;
import com.agora.repository.trading.*;
import com.agora.service.meta.DecisionAuditWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BtcDraOrderReconciliationTest {
    @Test void confirmedReceiptReconcilesWithoutAnyBarModeOrSizingConfiguration() {
        var f = new Fixture("filled", "0.001");
        f.service.reconcile();
        assertEquals(SpotExecutionAttempt.State.RECONCILED_FILLED, f.attempt.getState());
        assertNotNull(f.lot.getExitTime());
        assertEquals(new BigDecimal("10.00000000"), f.lot.getRealizedPnl());
        f.service.reconcile();
        assertEquals(1, f.lookups); // terminal receipt is no longer outstanding
        assertEquals(new BigDecimal("10.00000000"), f.lot.getRealizedPnl());
    }

    @Test void zeroFillIocCancellationPreservesPositionAndAllowsOnlyALaterBarSequence() {
        var f = new Fixture("canceled", "0");
        f.service.reconcile();
        assertEquals(SpotExecutionAttempt.State.REJECTED, f.attempt.getState());
        assertEquals(SpotExecutionAttempt.FeeReconciliationStatus.NOT_APPLICABLE, f.attempt.getFeeReconciliationStatus());
        assertNull(f.lot.getExitTime());
        assertEquals(new BigDecimal("0.001"), f.lot.getTradedQty());
        assertEquals(0, f.lot.getRealizedPnl().signum());
        assertFalse(f.attempts.reserveSell(7L, BtcDraPolicy.POLICY_MODE,
                f.attempt.getTriggerBarOpenTime(), f.lot.getTradedQty()).created());
        var next = f.attempts.reserveSell(7L, BtcDraPolicy.POLICY_MODE,
                f.attempt.getTriggerBarOpenTime().plusHours(1), f.lot.getTradedQty());
        assertTrue(next.created());
        assertEquals(2, next.attempt().getAttemptSequence());
    }

    @Test void canceledPartialFillOnlyConsumesTheConfirmedOwnedQuantityOnce() {
        var f = new Fixture("canceled", "0.0004");
        f.service.reconcile();
        assertEquals(SpotExecutionAttempt.State.RECONCILED_PARTIAL, f.attempt.getState());
        assertNull(f.lot.getExitTime());
        assertEquals(0, new BigDecimal("0.0006").compareTo(f.lot.getTradedQty()));
        assertEquals(new BigDecimal("4.00000000"), f.lot.getRealizedPnl());
        f.service.reconcile();
        assertEquals(1, f.lookups);
        assertEquals(new BigDecimal("4.00000000"), f.lot.getRealizedPnl());
    }

    @Test void missingAndWrongSideReceiptsStayUnresolvedWithoutAnotherSubmission() {
        for (boolean missing : List.of(true, false)) {
            var f = new Fixture("filled", "0.001"); f.missing = missing; f.wrongSide = !missing;
            f.service.reconcile(); f.service.reconcile();
            assertEquals(SpotExecutionAttempt.State.SUBMISSION_UNKNOWN, f.attempt.getState());
            assertEquals(2, f.lookups);
            assertNull(f.lot.getExitTime());
            assertNotNull(f.attempt.getLastReconciliationError());
        }
    }

    @Test void unsubmittedReservationAndMissingCredentialsNeverReachTheProvider() {
        var f = new Fixture("filled", "0.001");
        f.attempt.setState(SpotExecutionAttempt.State.RESERVED);
        f.service.reconcile(); assertEquals(0, f.lookups);
        f.attempt.setState(SpotExecutionAttempt.State.SUBMISSION_UNKNOWN);
        f.properties.setApiKey(""); f.service.reconcile(); assertEquals(0, f.lookups);
    }

    @Test void aBrokenBuyLookupDoesNotStarveSellMaintenance() {
        var f = new Fixture("filled", "0.001"); f.failBuyQuery = true;
        f.service.reconcile();
        assertEquals(SpotExecutionAttempt.State.RECONCILED_FILLED, f.attempt.getState());
    }

    @Test void foreignLotCannotBeAdoptedByAReconciliationAttempt() {
        var f = new Fixture("filled", "0.001");
        f.lot.setFilterReason("BTC_BASE:legacy");
        f.service.reconcile();
        assertNull(f.lot.getExitTime());
        assertEquals(new BigDecimal("0.001"), f.lot.getTradedQty());
        assertEquals(SpotExecutionAttempt.State.SUBMISSION_UNKNOWN, f.attempt.getState());
        assertNotNull(f.attempt.getLastReconciliationError());
    }

    private static class Fixture {
        final SpotExecutionAttempt attempt = new SpotExecutionAttempt();
        final BtLiveSignal lot = new BtLiveSignal();
        final OkxTradingProperties properties = new OkxTradingProperties();
        final BtcDraExecutionAttemptService attempts;
        final BtcDraOrderReconciliationService service;
        int lookups; boolean missing, wrongSide, failBuyQuery;

        Fixture(String state, String quantity) {
            lot.setId(7L); lot.setStrategyId(BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID);
            lot.setSymbol("BTCUSDT"); lot.setIntervalCode("1h"); lot.setAutoTraded(true);
            lot.setBarOpenTime(LocalDateTime.of(2026, 1, 1, 0, 0));
            lot.setEntryPrice(new BigDecimal("80000")); lot.setTradedQty(new BigDecimal("0.001"));
            lot.setFilterReason(BtcDraLiveExecutionService.POSITION_PREFIX + "OPEN:fixture");
            attempt.setId(8L); attempt.setLiveSignalId(7L); attempt.setStrategyContract(BtcDraPolicy.POLICY_MODE);
            attempt.setSide(SpotExecutionAttempt.Side.SELL); attempt.setState(SpotExecutionAttempt.State.SUBMISSION_UNKNOWN);
            attempt.setAttemptSequence(1); attempt.setTriggerBarOpenTime(lot.getBarOpenTime().plusHours(1));
            attempt.setRequestedBaseQuantity(lot.getTradedQty()); attempt.setClientOrderId("DRA1");
            attempt.setFeeReconciliationStatus(SpotExecutionAttempt.FeeReconciliationStatus.PENDING);
            var buy = new SpotExecutionAttempt();
            buy.setSide(SpotExecutionAttempt.Side.BUY); buy.setLiveSignalId(7L);
            buy.setStrategyContract(BtcDraPolicy.POLICY_MODE); buy.setState(SpotExecutionAttempt.State.RECONCILED_FILLED);
            buy.setFeeReconciliationStatus(SpotExecutionAttempt.FeeReconciliationStatus.RECONCILED);
            buy.setProviderOrderId("buy123"); buy.setNetFillQuantity(lot.getTradedQty());
            lot.setExchangeOrderId("OKX:buy123");
            var attemptRepo = proxy(SpotExecutionAttemptRepository.class, (name, args) -> switch (name) {
                case "findById", "findByIdForUpdate", "findTopByLiveSignalIdAndSideOrderByAttemptSequenceDesc" -> Optional.of(attempt);
                case "findByStrategyContractAndSideOrderByCreatedAtAsc" -> {
                    if (args[1] == SpotExecutionAttempt.Side.BUY && failBuyQuery) throw new IllegalStateException("fixture buy failure");
                    yield args[1] == SpotExecutionAttempt.Side.SELL ? List.of(attempt) : List.of();
                }
                case "findByLiveSignalIdAndSideOrderByAttemptSequenceAsc" ->
                        args[1] == SpotExecutionAttempt.Side.BUY ? List.of(buy) : List.of(attempt);
                case "saveAndFlush" -> args[0];
                default -> throw new AssertionError(name);
            });
            var lotRepo = proxy(BtLiveSignalRepository.class, (name, args) -> switch (name) {
                case "findByIdForUpdate" -> Optional.of(lot);
                case "findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull" -> List.of();
                case "saveAndFlush" -> args[0];
                default -> throw new AssertionError(name);
            });
            attempts = new BtcDraExecutionAttemptService(attemptRepo, lotRepo);
            properties.setEnabled(true); properties.setApiKey("fixture"); properties.setSecretKey("fixture"); properties.setPassphrase("fixture");
            var provider = new OkxTradingService(properties, new ObjectMapper()) {
                @Override public SpotOrderLookup lookupSpotOrderByClientOrderId(String symbol, String client) {
                    lookups++;
                    if (missing) return new SpotOrderLookup(SpotOrderLookupStatus.NOT_FOUND, null);
                    return new SpotOrderLookup(SpotOrderLookupStatus.FOUND, new SpotOrderSnapshot("123", "DRA1",
                            wrongSide ? "buy" : "sell", state, new BigDecimal("90000"), new BigDecimal(quantity),
                            new BigDecimal(quantity), BigDecimal.ZERO, "USDT", BigDecimal.ZERO, "{}", LocalDateTime.now()));
                }
                @Override public TradeResult placeMarketBuy(String symbol, double amount, String id) { throw new AssertionError("buy forbidden"); }
                @Override public TradeResult placeMarketSellWithFill(String symbol, BigDecimal qty, String id) { throw new AssertionError("sell forbidden"); }
                @Override public SpotOrderSnapshot placeIocSellWithPriceFloor(String symbol, BigDecimal qty, BigDecimal price, String id) { throw new AssertionError("sell forbidden"); }
            };
            var audit = new DecisionAuditWriter(null, null, null) {
                @Override public void logExit(Long id, String symbol, Long lot, String reason, Map<String, Object> context) { }
                @Override public void logAutoTradeFail(Long id, String symbol, String reason, Map<String, Object> context) { }
            };
            service = new BtcDraOrderReconciliationService(properties, provider, attempts, audit);
        }
    }

    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> invocation.invoke(m.getName(), a));
    }
    private interface Invocation { Object invoke(String name, Object[] args); }
}
