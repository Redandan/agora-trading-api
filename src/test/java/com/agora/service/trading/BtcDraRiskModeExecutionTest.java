package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.BtcDraRuntimeProperties.RiskMode;
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

/** Uses in-memory repositories and provider doubles only. Never starts Spring or sends an order. */
class BtcDraRiskModeExecutionTest {
    @Test void selectedAmountIsPersistedBeforeSubmissionAndReachesTheProviderUnchanged() throws Exception {
        for (RiskMode mode : RiskMode.values()) {
            var f = new Fixture(mode);
            BigDecimal expected = mode == RiskMode.AGGRESSIVE ? new BigDecimal("30.00") : new BigDecimal("15.00");
            assertEquals("BUY_PATH_HANDLED", f.buy());
            assertEquals(expected, f.reservedAmount);
            assertEquals(expected.doubleValue(), f.submittedAmount);
            assertEquals(mode.name(), f.mapper.readTree(f.evidence.getFeaturesSnapshotJson())
                    .path("liveExecution").path("riskModeAtEvaluation").asText());
            assertEquals(List.of("reserve", "evidence", "lookup", "claim", "buy", "apply", "evidence"), f.calls);
        }
    }

    @Test void conservativeMinimumSizeFailureNeverUpsizesOrReservesAnOrder() throws Exception {
        var f = new Fixture(RiskMode.CONSERVATIVE);
        f.minSize = new BigDecimal("0.20"); // 15 / 100 = 0.15 < provider minimum.
        assertEquals("BLOCKED:DRA_OKX_MINIMUM_SIZE_NOT_MET", f.buy());
        assertTrue(f.calls.isEmpty());
        assertNull(f.reservedAmount);
    }

    @Test void existingLotBlocksBothModesWithoutToppingUpOrResizing() throws Exception {
        var f = new Fixture(RiskMode.AGGRESSIVE);
        f.open = List.of(lot("0.15")); // A smaller lot survives a switch to AGGRESSIVE.
        assertEquals("BLOCKED:DRA_SINGLE_LOT_ALREADY_OPEN", f.buy());
        assertTrue(f.calls.isEmpty());
        assertEquals(new BigDecimal("0.15"), f.open.getFirst().getTradedQty());
        f = new Fixture(RiskMode.CONSERVATIVE);
        f.open = List.of(lot("0.30")); // A 30 USDT lot survives a switch to CONSERVATIVE.
        assertEquals("BLOCKED:DRA_SINGLE_LOT_ALREADY_OPEN", f.buy());
        assertTrue(f.calls.isEmpty());
        assertEquals(new BigDecimal("0.30"), f.open.getFirst().getTradedQty());
    }

    @Test void severeLossAndOldAgeNeverCreateLossExitUnderEitherMode() throws Exception {
        for (RiskMode mode : RiskMode.values()) {
            var f = new Fixture(mode); var lot = lot("0.30");
            lot.setCreatedAt(LocalDateTime.of(2020, 1, 1, 0, 0)); f.open = List.of(lot);
            f.price = new BigDecimal("20");
            assertEquals(false, f.exit());
            assertNull(f.sellReservedQty);
            assertNull(lot.getExitTime());
            assertEquals(new BigDecimal("0.30"), lot.getTradedQty());
        }
    }

    @Test void profitExitUsesTheWholeExistingOwnedLotAfterModeSwitch() throws Exception {
        for (RiskMode mode : RiskMode.values()) {
            var f = new Fixture(mode); f.open = List.of(lot("0.30")); f.price = new BigDecimal("106");
            // Stop at reservation with an intentional diagnostic exception; no provider sell exists in the double.
            f.exit();
            assertEquals(0, new BigDecimal("0.30").compareTo(f.sellReservedQty));
        }
    }

    @Test void aDifferentDurableReservedAmountBlocksBeforeLookupOrSubmission() throws Exception {
        var f = new Fixture(RiskMode.CONSERVATIVE); f.overrideReserved = new BigDecimal("30");
        assertEquals("UNCONFIRMED_RESERVED_NOTIONAL_MISMATCH", f.buy());
        assertEquals(List.of("reserve"), f.calls);
        assertNull(f.submittedAmount);
    }

    @Test void protectedExitPersistsPriceBeforeSubmissionAndPreservesCanceledReceipt() throws Exception {
        var f = new Fixture(RiskMode.AGGRESSIVE); f.open = List.of(lot("0.30"));
        f.price = new BigDecimal("106"); f.completeSell = true;
        assertEquals(true, f.exit());
        assertEquals(List.of("reserveSell", "evidence", "lookup", "claim", "ioc", "applySell", "evidence"), f.calls);
        var context = f.mapper.readTree(f.evidence.getFeaturesSnapshotJson()).path("liveExecution");
        assertEquals("ioc", context.path("orderType").asText());
        assertEquals("106", context.path("minimumSellPrice").asText());
        assertEquals("canceled", context.path("providerState").asText());
        assertEquals("REJECTED", context.path("attemptState").asText());
        assertEquals("DRA_LIVE_SELL_PROVIDER_RESPONSE", f.evidence.getSelectedAction());
        assertNull(f.open.getFirst().getExitTime());
    }

    @Test void missingFeeOrRoundedFloorAboveQuoteBlocksBeforeReservation() throws Exception {
        var f = new Fixture(RiskMode.AGGRESSIVE); f.open = List.of(lot("0.30"));
        f.price = new BigDecimal("105.5"); // old +5% estimate qualifies, but integer tick floor is 106.
        assertEquals(false, f.exit()); assertTrue(f.calls.isEmpty());
        f.price = new BigDecimal("106"); f.fee = null;
        assertEquals(false, f.exit()); assertTrue(f.calls.isEmpty());
    }

    @Test void inconsistentDurableSellQuantityNeverReachesLookupOrProviderSubmission() throws Exception {
        var f = new Fixture(RiskMode.AGGRESSIVE); f.open = List.of(lot("0.30"));
        f.price = new BigDecimal("106"); f.completeSell = true; f.overrideSellQty = new BigDecimal("0.15");
        assertEquals(false, f.exit());
        assertEquals(List.of("reserveSell"), f.calls);
    }

    private static BtLiveSignal lot(String qty) {
        var p = new BtLiveSignal(); p.setId(7L); p.setAutoTraded(true);
        p.setEntryPrice(new BigDecimal("100")); p.setTradedQty(new BigDecimal(qty));
        p.setFilterReason(BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX + "OPEN:fixture");
        return p;
    }

    private static class Fixture {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final List<String> calls = new ArrayList<>();
        final RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        final BtcDraLiveExecutionService service;
        final BtcDraRuntimeLaneService.RuntimeObservation observation;
        List<BtLiveSignal> open = List.of();
        BigDecimal price = new BigDecimal("100"), minSize = new BigDecimal("0.01");
        BigDecimal reservedAmount, overrideReserved, sellReservedQty;
        Double submittedAmount;
        boolean completeSell;
        BigDecimal fee = new BigDecimal("0.001");
        BigDecimal overrideSellQty;

        Fixture(RiskMode mode) {
            var props = BtcDraRiskModeTest.properties(mode, "30", "30");
            evidence.setPolicyMode(BtcDraPolicy.POLICY_MODE); evidence.setFeaturesSnapshotJson("{}");
            var positions = proxy(BtLiveSignalRepository.class, (name, args) -> {
                if (name.equals("findByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTime")) return Optional.empty();
                if (name.equals("findByStrategyIdAndSymbolAndIntervalCodeAndExitTimeIsNullAndNotifiedAtIsNotNull")) return open;
                if (name.equals("saveAndFlush")) { var p = (BtLiveSignal) args[0]; p.setId(7L); return p; }
                throw new AssertionError(name);
            });
            var evidenceRepo = proxy(RuntimeDecisionEvidenceRepository.class, (name, args) -> {
                if (name.equals("findById")) return Optional.of(evidence);
                if (name.equals("saveAndFlush")) { calls.add("evidence"); return args[0]; }
                throw new AssertionError(name);
            });
            var provider = new OkxTradingService(new OkxTradingProperties(), mapper) {
                @Override public String getUsdtBalance() { return "30"; }
                @Override public BigDecimal getLastPrice(String symbol) { return price; }
                @Override public BigDecimal getSpotTakerFeeRate(String symbol) { return fee; }
                @Override public SpotInstrumentRules getSpotInstrumentRules(String symbol) {
                    return new SpotInstrumentRules(symbol, minSize, new BigDecimal("0.00000001"), BigDecimal.ONE);
                }
                @Override public List<SpotHolding> getFreshSpotHoldings() {
                    var holding = new SpotHolding("BTC", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
                    return List.of(holding);
                }
                @Override public SpotOrderLookup lookupSpotOrderByClientOrderId(String symbol, String id) {
                    calls.add("lookup"); return new SpotOrderLookup(SpotOrderLookupStatus.NOT_FOUND, null);
                }
                @Override public TradeResult placeMarketBuy(String symbol, double amount, String id) {
                    calls.add("buy"); submittedAmount = amount;
                    var f = new TradeResult(); f.setOrderId("fixture-order"); f.setAvgPrice(price);
                    f.setQty(new BigDecimal("0.15")); f.setGrossQty(f.getQty()); f.setNetQty(f.getQty());
                    f.setFeeAmount(BigDecimal.ZERO); f.setFeeCurrency("USDT"); f.setFeeUsdt(BigDecimal.ZERO);
                    return f;
                }
                @Override public TradeResult placeMarketSellWithFill(String symbol, BigDecimal qty, String id) {
                    throw new AssertionError("Unexpected sell submission");
                }
                @Override public SpotOrderSnapshot placeIocSellWithPriceFloor(String symbol, BigDecimal qty, BigDecimal floor, String id) {
                    if (!completeSell) throw new AssertionError("Unexpected protected sell submission");
                    calls.add("ioc");
                    assertTrue(evidence.getFeaturesSnapshotJson().contains("minimumSellPrice"));
                    assertEquals("106", floor.toPlainString());
                    return new SpotOrderSnapshot("fixture-order", id, "sell", "canceled", null,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USDT", BigDecimal.ZERO, "{}", LocalDateTime.now());
                }
            };
            var attempts = new BtcDraExecutionAttemptService(null, null) {
                @Override public Reservation reserveBuy(Long id, String contract, LocalDateTime bar, BigDecimal amount) {
                    calls.add("reserve"); reservedAmount = amount;
                    var a = new SpotExecutionAttempt(); a.setId(8L); a.setLiveSignalId(id); a.setAttemptSequence(1);
                    a.setRequestedQuoteAmount(overrideReserved == null ? amount : overrideReserved);
                    return new Reservation(a, true, null);
                }
                @Override public boolean claimForSubmission(Long id, LocalDateTime now) { calls.add("claim"); return true; }
                @Override public ApplyResult applyBuySnapshot(Long id, ProviderFillSnapshot snapshot) {
                    calls.add("apply"); return new ApplyResult(id, SpotExecutionAttempt.State.RECONCILED_FILLED,
                            snapshot.netQuantity(), reservedAmount, BigDecimal.ZERO, BigDecimal.ZERO,
                            snapshot.netQuantity(), SpotExecutionAttempt.FeeReconciliationStatus.RECONCILED);
                }
                @Override public Optional<SpotExecutionAttempt> findOutstandingSell() { return Optional.empty(); }
                @Override public Reservation reserveSell(Long id, String contract, LocalDateTime bar, BigDecimal qty) {
                    sellReservedQty = qty;
                    if (!completeSell) throw new IllegalStateException("fixture stops at sell reservation");
                    calls.add("reserveSell");
                    var a = new SpotExecutionAttempt(); a.setId(8L); a.setLiveSignalId(id); a.setAttemptSequence(1);
                    a.setState(SpotExecutionAttempt.State.RESERVED); a.setRequestedBaseQuantity(overrideSellQty == null ? qty : overrideSellQty); a.setClientOrderId("DRA1");
                    return new Reservation(a, true, null);
                }
                @Override public ApplyResult applySellSnapshot(Long id, ProviderFillSnapshot snapshot) {
                    calls.add("applySell");
                    return new ApplyResult(id, SpotExecutionAttempt.State.REJECTED, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, sellReservedQty, SpotExecutionAttempt.FeeReconciliationStatus.NOT_APPLICABLE);
                }
            };
            var audit = new DecisionAuditWriter(null, null, null) {
                @Override public void logAutoTradeOk(Long id, String symbol, Long signal, Map<String, Object> context) { }
                @Override public void logAutoTradeFail(Long id, String symbol, String reason, Map<String, Object> context) { }
                @Override public void logEntrySkip(Long id, String symbol, String interval, LocalDateTime bar,
                        String blocker, String reason, Map<String, Object> context, Long signal) { }
            };
            service = new BtcDraLiveExecutionService(props, null, provider, attempts, positions, evidenceRepo, audit, null, mapper);
            var bar = new MdKline(); bar.setOpenTime(LocalDateTime.of(2024, 1, 10, 23, 0));
            bar.setCloseTime(bar.getOpenTime().plusHours(1)); bar.setClosePrice(price);
            var step = new BtcDraShadowEngine.StepResult(null, null,
                    List.of(BtcDraExecutionContractTest.event("VIRTUAL_ENTRY_QUEUED")));
            observation = new BtcDraRuntimeLaneService.RuntimeObservation(bar, step, false, false, 1, 0, 1L);
        }

        Object buy() throws Exception { return invoke("executeBuy"); }
        Object exit() throws Exception { return invoke("executeEligibleExit"); }
        private Object invoke(String method) throws Exception {
            var m = BtcDraLiveExecutionService.class.getDeclaredMethod(method, BtcDraRuntimeLaneService.RuntimeObservation.class);
            m.setAccessible(true); return m.invoke(service, observation);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method.getName(), args));
    }
    private interface Invocation { Object invoke(String method, Object[] args); }
}
