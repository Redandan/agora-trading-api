package com.agora.service.advice;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import static com.agora.service.advice.BtcOrderAdviceEngine.*;
import static org.junit.jupiter.api.Assertions.*;

class BtcOrderAdviceEngineTest {
    static final Instant NOW = Instant.parse("2026-10-07T06:30:00Z");
    final BtcOrderAdviceEngine engine = new BtcOrderAdviceEngine();
    static BigDecimal d(String n) { return new BigDecimal(n); }
    static Input input(Instant now, String mark, String available, List<PendingOrder> orders, List<String> blockers) {
        var bars = new ArrayList<Bar>();
        Instant start = now.truncatedTo(ChronoUnit.HOURS).minus(168, ChronoUnit.HOURS);
        for (int i = 0; i < 168; i++) bars.add(new Bar(start.plus(i, ChronoUnit.HOURS), d("84000"), d("86000"), d("82000"), d("84000")));
        return new Input(now, now.minusSeconds(1), d(mark).subtract(d("1")), d(mark).add(d("1")), d(mark), bars,
                d("0.001"), d("0.00001"), d("0.00000001"), d("0.1"), d("0.0004709"), d("30"), d(available),
                d("0.0003536"), orders, "inventory-v1", blockers);
    }
    static Input input() { return input(NOW, "84000", "0.0008871693", List.of(), List.of()); }

    @Test void bothGoalsAreFundedFromOneCycleAfterBothFeesAndLotRounding() {
        Advice a = engine.evaluate(input(), Policy.defaults());
        assertEquals("PLAN", a.status());
        Leg sell = a.orders().getFirst(), buy = a.orders().getLast();
        assertEquals("ONLY_AFTER_SELL_FILL_AND_FRESH_REVIEW", buy.condition());
        assertTrue(sell.quantity().compareTo(input().legacyQuantity().multiply(d("0.25"))) <= 0);
        assertTrue(a.retainedBtc().compareTo(input().legacyQuantity().multiply(d("0.75"))) >= 0);
        assertEquals(0, sell.quantity().remainder(input().quantityStep()).signum());
        assertEquals(0, buy.quantity().remainder(input().quantityStep()).signum());
        assertEquals(0, sell.price().remainder(input().priceTick()).signum());
        assertEquals(0, buy.price().remainder(input().priceTick()).signum());
        assertTrue(a.economics().retainedUsdt().signum() > 0);
        assertTrue(a.economics().additionalBtc().signum() > 0);
        assertEquals(0, a.economics().saleNetUsdt().compareTo(buy.quantity().multiply(buy.price()).add(a.economics().retainedUsdt())));
        assertEquals(0, a.economics().additionalBtc().compareTo(buy.quantity().multiply(d("0.999")).subtract(sell.quantity())));
    }

    @Test void cannotSellProtectedStrategyOrUnavailableBtc() {
        Advice a = engine.evaluate(input(NOW, "84000", "0.00036", List.of(), List.of()), Policy.defaults());
        assertEquals("WAIT", a.status()); assertTrue(a.orders().isEmpty());
        Advice capped = engine.evaluate(input(NOW, "84000", "0.0003736", List.of(), List.of()), Policy.defaults());
        assertEquals(0, capped.orders().getFirst().quantity().compareTo(d("0.00002")));
    }

    @Test void pendingOrdersProduceReferenceLevelsWithoutAdditionalQuantity() {
        Advice a = engine.evaluate(input(NOW, "84000", "0.0008871693",
                List.of(new PendingOrder("order:123", "limit", "sell", d("85000"), d("0.0001"), "live")), List.of()), Policy.defaults());
        assertEquals("REVIEW_EXISTING_ORDERS", a.status());
        assertTrue(a.orders().stream().allMatch(l -> l.quantity().signum() == 0)); assertNull(a.economics());
    }

    @Test void missingDuplicateFutureAndOldCandlesNeverYieldOrders() {
        for (String fault : List.of("missing", "duplicate", "future", "old")) {
            Input in = input(); var bars = new ArrayList<>(in.bars());
            switch (fault) {
                case "missing" -> bars.removeFirst();
                case "duplicate" -> bars.set(100, bars.get(99));
                case "future" -> bars.set(167, new Bar(NOW.truncatedTo(ChronoUnit.HOURS), d("84000"), d("86000"), d("82000"), d("84000")));
                case "old" -> bars.replaceAll(b -> new Bar(b.openAt().minus(1, ChronoUnit.HOURS), b.open(), b.high(), b.low(), b.close()));
            }
            var bad = new Input(in.asOf(), in.quoteAt(), in.bid(), in.ask(), in.last(), bars, in.fee(), in.minQuantity(), in.quantityStep(),
                    in.priceTick(), in.legacyQuantity(), in.legacyCost(), in.availableBtc(), in.protectedBtc(), in.pendingOrders(), in.inventoryKey(), in.blockers());
            assertEquals("DATA_UNAVAILABLE", engine.evaluate(bad, Policy.defaults()).status(), fault);
        }
    }

    @Test void staleQuoteAndUnknownOwnershipInvalidateOldAdvice() {
        Input in = input();
        var old = new Input(in.asOf(), NOW.minusSeconds(61), in.bid(), in.ask(), in.last(), in.bars(), in.fee(), in.minQuantity(),
                in.quantityStep(), in.priceTick(), in.legacyQuantity(), in.legacyCost(), in.availableBtc(), in.protectedBtc(), List.of(), in.inventoryKey(), List.of());
        assertEquals("DATA_UNAVAILABLE", engine.evaluate(old, Policy.defaults()).status());
        assertEquals("DATA_UNAVAILABLE", engine.evaluate(input(NOW, "84000", "0.001", List.of(), List.of("未確認持倉")), Policy.defaults()).status());
    }

    @Test void suddenBreakoutWaitsForClosedEvidence() {
        Advice a = engine.evaluate(input(NOW, "92000", "0.001", List.of(), List.of()), Policy.defaults());
        assertEquals("WAIT", a.status()); assertTrue(a.reason().contains("突破")); assertTrue(a.orders().isEmpty());
    }

    @Test void feesOrLossOnExistingInventoryCanEliminateThePlan() {
        Input in = input();
        var losing = new Input(in.asOf(), in.quoteAt(), in.bid(), in.ask(), in.last(), in.bars(), in.fee(), in.minQuantity(),
                in.quantityStep(), in.priceTick(), in.legacyQuantity(), d("100"), in.availableBtc(), in.protectedBtc(), List.of(), in.inventoryKey(), List.of());
        assertEquals("WAIT", engine.evaluate(losing, Policy.defaults()).status());
    }

    @Test void downtrendDoesNotKeepIssuingBuybackOrders() {
        Input in = input(); var bars = new ArrayList<Bar>();
        for (int i = 0; i < 168; i++) {
            BigDecimal close = d("100000").subtract(BigDecimal.valueOf(i * 100L));
            bars.add(new Bar(in.bars().get(i).openAt(), close, close.add(d("100")), close.subtract(d("100")), close));
        }
        var down = new Input(in.asOf(), in.quoteAt(), d("83299"), d("83301"), d("83300"), bars, in.fee(), in.minQuantity(),
                in.quantityStep(), in.priceTick(), in.legacyQuantity(), in.legacyCost(), in.availableBtc(), in.protectedBtc(), List.of(), in.inventoryKey(), List.of());
        Advice a = engine.evaluate(down, Policy.defaults());
        assertEquals("DOWNTREND", a.regime()); assertEquals("WAIT", a.status()); assertTrue(a.orders().isEmpty());
    }

    @Test void uptrendOrderTargetsAreInsideTheirOwnValidityBounds() {
        Input in = input(); var bars = new ArrayList<Bar>();
        for (int i = 0; i < 168; i++) {
            BigDecimal close = d("83300").add(BigDecimal.valueOf(i * 100L));
            bars.add(new Bar(in.bars().get(i).openAt(), close, close.add(d("100")), close.subtract(d("100")), close));
        }
        var up = new Input(in.asOf(), in.quoteAt(), d("99999"), d("100001"), d("100000"), bars, in.fee(), in.minQuantity(),
                in.quantityStep(), in.priceTick(), in.legacyQuantity(), in.legacyCost(), in.availableBtc(), in.protectedBtc(), List.of(), in.inventoryKey(), List.of());
        Advice a = engine.evaluate(up, Policy.defaults());
        assertEquals("UPTREND", a.regime()); assertEquals("PLAN", a.status());
        for (Leg leg : a.orders()) {
            assertTrue(leg.price().compareTo(a.market().invalidateAbove()) < 0);
            assertTrue(leg.price().compareTo(a.market().invalidateBelow()) > 0);
        }
    }

    @Test void quoteNearInvalidationCannotProduceUnreachableOrderTargets() {
        Advice a = engine.evaluate(input(NOW, "89990", "0.001", List.of(), List.of()), Policy.defaults());
        assertEquals("WAIT", a.status()); assertTrue(a.orders().isEmpty());
    }
}
