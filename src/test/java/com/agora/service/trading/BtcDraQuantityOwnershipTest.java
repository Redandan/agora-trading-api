package com.agora.service.trading;

import com.agora.model.*;
import com.agora.repository.trading.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BtcDraQuantityOwnershipTest {
    private static final LocalDateTime BAR = LocalDateTime.of(2026, 10, 3, 23, 0);
    private static BigDecimal n(String x) { return new BigDecimal(x); }

    @Test void realOctoberReceiptPersistsOnlyTradableQuantityAndKeepsExactProviderEvidence() {
        var f = new Fixture();
        f.service.applyBuySnapshot(2L, f.receipt());
        assertEquals(n("0.00035360"), f.lot.getTradedQty());
        assertEquals(f.lot.getTradedQty(), f.lot.getOcoQty());
        assertEquals(0, n("0.00035360604").compareTo(f.buy.getNetFillQuantity()));
        assertEquals(0, n("29.9998798").compareTo(f.buy.getGrossQuoteAmount()));
        assertEquals(n("84839.83983984"), f.lot.getEntryPrice());
        var repeat = f.service.applyBuySnapshot(2L, f.receipt());
        assertEquals(0, repeat.appliedFillQuantity().signum());
        assertEquals(n("0.00035360"), f.lot.getTradedQty());
    }

    @Test void everySubSatoshiTailIsFlooredNeverRoundedUp() {
        for (String q : List.of("0.00035360001", "0.00035360499", "0.000353605", "0.00035360604", "0.00035360999")) {
            assertEquals(n("0.00035360"), SpotExecutionAttemptPolicy.positionQuantity(n(q)));
        }
        assertThrows(IllegalArgumentException.class, () -> SpotExecutionAttemptPolicy.positionQuantity(n("-1")));
    }

    @Test void existingRoundedRowIsCorrectedOnceWithoutRewritingPriceFeesOrPnl() {
        var f = new Fixture(); f.roundedOpenLot();
        BigDecimal entry = f.lot.getEntryPrice(), fee = f.buy.getFeeUsdt();
        String originalReceipt = f.buy.getProviderReceiptJson();
        assertEquals(2L, f.service.findOpenBuyQuantityCorrection().orElseThrow().getId());
        var correction = f.service.correctOpenBuyQuantity(2L, f.receipt()).orElseThrow();
        assertEquals(n("0.00035361"), correction.previousQuantity());
        assertEquals(n("0.00035360"), correction.tradableQuantity());
        assertEquals(0, n("0.00000000604").compareTo(correction.untradableDustQuantity()));
        assertEquals(entry, f.lot.getEntryPrice()); assertEquals(fee, f.buy.getFeeUsdt());
        assertEquals(originalReceipt, f.buy.getProviderReceiptJson());
        assertNull(f.lot.getRealizedPnl()); assertNull(f.lot.getExitTime());
        assertTrue(f.service.findOpenBuyQuantityCorrection().isEmpty());
        assertTrue(f.service.correctOpenBuyQuantity(2L, f.receipt()).isEmpty());
    }

    @Test void sellReservationRejectsRoundedExcessEvenIfTheAccountHasOtherBtc() {
        var f = new Fixture(); f.roundedOpenLot();
        assertThrows(IllegalStateException.class, () -> f.sell("0.00035361"));
        assertTrue(f.sells.isEmpty());
        f.service.correctOpenBuyQuantity(2L, f.receipt());
        assertEquals(n("0.00035360"), f.sell("0.00035360").attempt().getRequestedBaseQuantity());
    }

    @Test void existingMaintenanceClockFetchesFreshReceiptAndWritesOneCorrectionAuditWithoutOrders() {
        var f = new Fixture(); f.roundedOpenLot(); int[] counts = {0, 0};
        var props = new com.agora.config.OkxTradingProperties();
        props.setEnabled(true); props.setApiKey("fixture"); props.setSecretKey("fixture"); props.setPassphrase("fixture");
        var provider = new OkxTradingService(props, new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override public SpotOrderLookup lookupSpotOrderByClientOrderId(String symbol, String client) {
                counts[0]++; assertEquals("BTC-USDT", symbol); assertEquals(f.buy.getClientOrderId(), client);
                var r = f.receipt();
                return new SpotOrderLookup(SpotOrderLookupStatus.FOUND, new SpotOrderSnapshot(r.providerOrderId(), client,
                        "buy", r.providerState(), r.averagePrice(), r.cumulativeGrossQuantity(), r.netQuantity(),
                        r.signedFeeAmount(), r.feeCurrency(), r.feeUsdt(), r.providerReceiptJson(), r.providerAt()));
            }
            @Override public TradeResult placeMarketBuy(String symbol, double amount, String id) { throw new AssertionError("no order"); }
            @Override public SpotOrderSnapshot placeIocSellWithPriceFloor(String symbol, BigDecimal qty, BigDecimal price, String id) { throw new AssertionError("no order"); }
        };
        var audit = new com.agora.service.meta.DecisionAuditWriter(null, null, null) {
            @Override public void logPositionQuantityCorrection(Long owner, String symbol, Long lotId, Map<String,Object> context) {
                counts[1]++; assertEquals(false, context.get("orderSent")); assertEquals(264L, lotId);
            }
        };
        var maintenance = new BtcDraOrderReconciliationService(props, provider, f.service, audit);
        maintenance.reconcile(); maintenance.reconcile();
        assertArrayEquals(new int[]{1, 1}, counts);
        assertEquals(n("0.00035360"), f.lot.getTradedQty());
    }

    @Test void correctionsRefuseChangedProviderEvidenceForeignOwnershipAndMaterialDifferences() {
        for (String fault : List.of("fee", "order", "closed", "foreign", "quantity", "oco", "pending")) {
            var f = new Fixture(); f.roundedOpenLot();
            switch (fault) {
                case "fee" -> f.buy.setFeeUsdt(n("0.05"));
                case "order" -> f.buy.setProviderOrderId("different");
                case "closed" -> f.lot.setExitTime(BAR.plusDays(1));
                case "foreign" -> f.lot.setStrategyId(508L);
                case "quantity" -> { f.lot.setTradedQty(n("0.1")); f.lot.setOcoQty(n("0.1")); }
                case "oco" -> f.lot.setOcoQty(n("0.00035360"));
                case "pending" -> f.buy.setFeeReconciliationStatus(SpotExecutionAttempt.FeeReconciliationStatus.PENDING);
            }
            BigDecimal before = f.lot.getTradedQty();
            assertThrows(IllegalStateException.class, () -> f.service.correctOpenBuyQuantity(2L, f.receipt()), fault);
            assertEquals(before, f.lot.getTradedQty(), fault);
        }
    }

    @Test void partialSellsAreSubtractedAndBuyReplayCannotRestoreSoldQuantity() {
        var f = new Fixture(); f.roundedOpenLot(); f.service.correctOpenBuyQuantity(2L, f.receipt());
        var sell = f.sell("0.00035360").attempt();
        sell.setState(SpotExecutionAttempt.State.RECONCILED_PARTIAL);
        sell.setFeeReconciliationStatus(SpotExecutionAttempt.FeeReconciliationStatus.RECONCILED);
        sell.setAppliedFillQuantity(n("0.0001"));
        f.lot.setTradedQty(n("0.00025360")); f.lot.setOcoQty(n("0.00025360"));
        assertThrows(IllegalStateException.class, () -> f.service.applyBuySnapshot(2L, f.receipt()));
        assertThrows(IllegalStateException.class, () -> f.service.correctOpenBuyQuantity(2L, f.receipt()));
        assertTrue(f.service.findOpenBuyQuantityCorrection().isEmpty());
        // Simulate a stale DB quantity: provider-owned guard still caps the second reservation.
        f.lot.setTradedQty(n("0.00035360"));
        assertThrows(IllegalStateException.class, () -> f.service.reserveSell(264L, BtcDraPolicy.POLICY_MODE,
                BAR.plusHours(3), n("0.00035360")));
        assertTrue(f.service.reserveSell(264L, BtcDraPolicy.POLICY_MODE,
                BAR.plusHours(3), n("0.00025360")).created());
    }

    private static class Fixture {
        final BtLiveSignal lot = new BtLiveSignal();
        final SpotExecutionAttempt buy = new SpotExecutionAttempt();
        final List<SpotExecutionAttempt> sells = new ArrayList<>();
        final BtcDraExecutionAttemptService service;
        Fixture() {
            lot.setId(264L); lot.setStrategyId(BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID); lot.setSymbol("BTCUSDT");
            lot.setBarOpenTime(BAR); lot.setFilterReason(BtcDraLiveExecutionService.POSITION_PREFIX + "BUY_RESERVED");
            buy.setId(2L); buy.setLiveSignalId(264L); buy.setSide(SpotExecutionAttempt.Side.BUY);
            buy.setStrategyContract(BtcDraPolicy.POLICY_MODE); buy.setState(SpotExecutionAttempt.State.SUBMITTING);
            buy.setClientOrderId("DRA1B20261003230000"); buy.setAttemptSequence(1);
            var attempts = proxy(SpotExecutionAttemptRepository.class, (method, args) -> switch (method) {
                case "findById", "findByIdForUpdate" -> Optional.of(buy);
                case "findByLiveSignalIdAndSideOrderByAttemptSequenceAsc" -> args[1] == SpotExecutionAttempt.Side.BUY ? List.of(buy) : List.copyOf(sells);
                case "findByStrategyContractAndSideOrderByCreatedAtAsc" -> args[1] == SpotExecutionAttempt.Side.BUY ? List.of(buy) : List.copyOf(sells);
                case "findTopByLiveSignalIdAndSideOrderByAttemptSequenceDesc" -> sells.isEmpty() ? Optional.empty() : Optional.of(sells.getLast());
                case "saveAndFlush" -> { var a = (SpotExecutionAttempt) args[0]; if (a.getId() == null) { a.setId(10L + sells.size()); sells.add(a); } yield a; }
                default -> throw new AssertionError(method);
            });
            var lots = proxy(BtLiveSignalRepository.class, (method, args) -> switch (method) {
                case "findByIdForUpdate" -> Optional.of(lot);
                case "findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull" -> lot.getExitTime() == null ? List.of(lot) : List.of();
                case "saveAndFlush" -> args[0];
                default -> throw new AssertionError(method);
            });
            service = new BtcDraExecutionAttemptService(attempts, lots);
        }
        void roundedOpenLot() { service.applyBuySnapshot(2L, receipt()); lot.setTradedQty(n("0.00035361")); lot.setOcoQty(n("0.00035361")); }
        BtcDraExecutionAttemptService.Reservation sell(String qty) { return service.reserveSell(264L, BtcDraPolicy.POLICY_MODE, BAR.plusHours(2), n(qty)); }
        BtcDraExecutionAttemptService.ProviderFillSnapshot receipt() {
            return new BtcDraExecutionAttemptService.ProviderFillSnapshot("3978535656630571008", "filled", n("84755"),
                    n("0.00035396"), n("0.00035360604"), n("-0.00000035396"), "BTC", n("0.02999988"), "{\"fixture\":true}", BAR.plusHours(1));
        }
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Invocation body) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> body.invoke(m.getName(), a));
    }
    private interface Invocation { Object invoke(String method, Object[] args); }
}
