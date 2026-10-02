package com.agora.service.tradingview;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.infra.notification.NotificationPort;
import com.agora.mcp.ExecutionSafetyMcpTools;
import com.agora.model.*;
import com.agora.repository.trading.*;
import com.agora.service.backtest.LiveSignalContext;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.trading.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TradingViewLiveRecoveryTest {
    static final String PREFIX = BtcBasePositionStatePolicy.TV509_POSITION_PREFIX;
    static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 2, 0, 0);
    static BigDecimal n(String x) { return new BigDecimal(x); }
    static BtStrategy strategy() { var s = new BtStrategy(); s.setId(485L); return s; }
    static MdKline bar(LocalDateTime time) {
        var b = new MdKline(); b.setSymbol("BTCUSDT"); b.setSource("binance"); b.setIntervalCode("1d");
        b.setOpenTime(time.minusDays(1)); b.setCloseTime(time); b.setClosePrice(n("11000")); return b;
    }
    static BtLiveSignal lot(long id) {
        var l = new BtLiveSignal(); l.setId(id); l.setStrategyId(485L); l.setSymbol("BTCUSDT");
        l.setIntervalCode("1d"); l.setSide("LONG"); l.setAutoTraded(true); l.setEntryPrice(n("10000"));
        l.setActualEntryPrice(n("10000")); l.setTradedQty(n("0.001")); l.setOcoQty(n("0.001"));
        l.setCreatedAt(TIME.minusDays(1)); l.setNotifiedAt(TIME.minusDays(1)); l.setBarOpenTime(TIME.minusDays(id));
        l.setFilterReason(PREFIX + "OPEN:CL=offline" + id); return l;
    }
    static TradeResult fill(String qty, String fee) {
        var f = new TradeResult(); f.setOrderId("offline-order"); f.setAvgPrice(n("11000"));
        f.setGrossQty(n(qty)); f.setQty(n(qty)); f.setNetQty(n(qty));
        if (fee != null) { f.setFeeAmount(n(fee).negate()); f.setFeeCurrency("USDT"); f.setFeeUsdt(n(fee)); }
        return f;
    }

    @Test void partialAggregateReturnsUntouchedLotAndPreventsSameBarResubmission() throws Exception {
        var f = new Fixture(lot(1), lot(2)); f.provider.fill = fill("0.001", "0");
        assertTrue(f.exit(TIME));
        assertNotNull(f.row(1).getExitTime()); assertTrue(TradingViewLivePositionStore.open(f.row(2)));
        assertEquals(0, n("1").compareTo(f.row(1).getRealizedPnl()));
        assertFalse(f.exit(TIME)); assertEquals(1, f.provider.orders);
        assertTrue(f.exit(TIME.plusDays(1))); assertEquals(2, f.provider.orders);
        assertNotNull(f.row(2).getExitTime());
    }
    @Test void cumulativeReceiptReplayCannotApplyPnlTwice() {
        var f = new Fixture(lot(1), lot(2)); f.reserve("TV509S1");
        var first = f.store.applySell(485L,"TV509S1",fill("0.0015","0.0165"),TIME);
        assertEquals(2, first.allocations().size());
        assertEquals(0,n("0.0005").compareTo(f.row(2).getTradedQty()));
        var pnl = f.row(2).getRealizedPnl();
        assertTrue(f.store.applySell(485L,"TV509S1",fill("0.0015","0.0165"),TIME).allocations().isEmpty());
        assertEquals(pnl,f.row(2).getRealizedPnl());
    }
    @Test void missingFeeStaysPendingThenExactZeroFeeReconciles() {
        var f = new Fixture(lot(1)); f.reserve("TV509S1");
        assertThrows(IllegalArgumentException.class,()->f.store.applySell(485L,"TV509S1",fill("0.001",null),TIME));
        assertTrue(TradingViewLivePositionStore.pendingV2(f.row(1))); assertNull(f.row(1).getRealizedPnl());
        f.store.applySell(485L,"TV509S1",fill("0.001","0"),TIME);
        assertEquals(0,n("1").compareTo(f.row(1).getRealizedPnl()));
    }
    @Test void failedReservationFlushRollsBackEveryLotAndSendsNothing() throws Exception {
        var f = new Fixture(lot(1),lot(2)); f.failFlush=true;
        assertFalse(f.exit(TIME)); assertEquals(0,f.provider.orders);
        assertTrue(TradingViewLivePositionStore.open(f.row(1))); assertTrue(TradingViewLivePositionStore.open(f.row(2)));
        assertEquals(1,f.rollbacks);
    }
    @Test void failedFillFlushRollsBackAllAllocationsAndCanRecoverOnce() {
        var f = new Fixture(lot(1),lot(2)); f.reserve("TV509S1"); f.failFlush=true;
        assertThrows(IllegalStateException.class,()->f.store.applySell(485L,"TV509S1",fill("0.0015","0"),TIME));
        assertTrue(TradingViewLivePositionStore.pendingV2(f.row(1))); assertTrue(TradingViewLivePositionStore.pendingV2(f.row(2)));
        assertNull(f.row(1).getExitTime()); assertNull(f.row(2).getRealizedPnl());
        f.failFlush=false; f.store.applySell(485L,"TV509S1",fill("0.0015","0"),TIME);
        assertNotNull(f.row(1).getExitTime()); assertEquals(0,n("0.0005").compareTo(f.row(2).getTradedQty()));
    }
    @Test void timeoutThenProcessRestartQueriesAndSettlesWithoutAnotherOrder() throws Exception {
        var f = new Fixture(lot(1),lot(2)); f.provider.timeout=true;
        assertFalse(f.exit(TIME)); assertEquals(1,f.provider.orders);
        f.provider.lookupFill=fill("0.001", "0"); f.provider.state="filled";
        f.live=f.newLive(); // durable state only, no prior service memory
        f.live.evaluate(strategy(),bar(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)),"binance",List.of(),Map.of());
        assertEquals(1,f.provider.orders); assertEquals(1,f.provider.lookups);
        assertNotNull(f.row(1).getExitTime()); assertTrue(TradingViewLivePositionStore.open(f.row(2)));
    }
    @Test void notFoundLiveAndPartialProviderStatesNeverReleaseOrRetry() throws Exception {
        for (String state : List.of("NOT_FOUND","live","partially_filled")) {
            var f = new Fixture(lot(1)); f.reserve("TV509S1"); f.provider.state=state;
            f.live.evaluate(strategy(),bar(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)),"binance",List.of(),Map.of());
            assertEquals(0,f.provider.orders,state); assertTrue(TradingViewLivePositionStore.pendingV2(f.row(1)),state);
        }
    }
    @Test void canceledUnfilledSellReturnsAllLotsToOpenWithoutNewOrderOnReconciliationBar() {
        var f = new Fixture(lot(1),lot(2)); f.reserve("TV509S1");
        f.provider.state="canceled"; f.provider.lookupFill=fill("0",null);
        f.live.evaluate(strategy(),bar(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)),"binance",List.of(),Map.of());
        assertEquals(0,f.provider.orders); assertTrue(TradingViewLivePositionStore.open(f.row(1)));
        assertTrue(TradingViewLivePositionStore.open(f.row(2))); assertNull(f.row(1).getRealizedPnl());
    }
    @Test void canceledPartialSellBooksOnlyProvenFilledQuantity() {
        var f = new Fixture(lot(1),lot(2)); f.reserve("TV509S1");
        f.provider.state="canceled"; f.provider.lookupFill=fill("0.0005","0.0055");
        f.live.evaluate(strategy(),bar(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)),"binance",List.of(),Map.of());
        assertEquals(0,n("0.0005").compareTo(f.row(1).getTradedQty()));
        assertEquals(0,n("0.4945").compareTo(f.row(1).getRealizedPnl()));
        assertTrue(TradingViewLivePositionStore.open(f.row(2))); assertEquals(0,f.provider.orders);
    }
    @Test void wrongSideOrClientAndOverfillCannotMutateInventory() {
        for (String mismatch : List.of("SIDE","CLIENT","OVERFILL")) {
            var f = new Fixture(lot(1)); f.reserve("TV509S1"); f.provider.state="filled"; f.provider.mismatch=mismatch;
            f.provider.lookupFill=fill(mismatch.equals("OVERFILL")?"0.002":"0.001","0");
            f.live.evaluate(strategy(),bar(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)),"binance",List.of(),Map.of());
            assertTrue(TradingViewLivePositionStore.pendingV2(f.row(1)),mismatch); assertNull(f.row(1).getRealizedPnl());
        }
    }
    @Test void legacyReservationIsVisibleButNeverAssumedAtomicOrResubmitted() {
        var legacy=lot(1); legacy.setFilterReason(PREFIX+"SELL_SUBMISSION_UNCONFIRMED:CL=old");
        var f=new Fixture(legacy);
        f.live.evaluate(strategy(),bar(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)),"binance",List.of(),Map.of());
        assertEquals(0,f.provider.orders); assertEquals(0,f.provider.lookups);
        var safety=new ExecutionSafetyMcpTools(f.positions,null,null,null,null).getExecutionSafetyStatus();
        assertTrue(safety.contains("EXECUTION_RECONCILIATION_REQUIRED")); assertTrue(safety.endsWith("status=REVIEW"));
    }
    @Test void otherOwnersCannotBeReservedOrModified() {
        var dra=lot(1); dra.setFilterReason(BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX+"OPEN:CL=x");
        var f=new Fixture(dra);
        assertThrows(IllegalArgumentException.class,()->f.reserve("TV509S1"));
        assertEquals(BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX+"OPEN:CL=x",f.row(1).getFilterReason());
    }
    @Test void buyTimeoutReconcilesExactlyOnceAndMissingFeesStayBlocked() {
        var f=new Fixture(); f.provider.timeout=true;
        var now=LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        f.live.evaluate(strategy(),bar(now),"binance",List.of(new LiveSignalContext.OrderIntent("fixture","fixture",1000)),Map.of());
        assertEquals(1,f.provider.orders); var pending=f.db.values().iterator().next();
        assertTrue(TradingViewLivePositionStore.pendingV2(pending));
        assertTrue(new ExecutionSafetyMcpTools(f.positions,null,null,null,null).getExecutionSafetyStatus().endsWith("status=REVIEW"));
        f.provider.side="buy"; f.provider.state="filled"; f.provider.lookupFill=fill("0.0009",null);
        f.live=f.newLive(); f.live.evaluate(strategy(),bar(now),"binance",List.of(),Map.of());
        assertFalse(Boolean.TRUE.equals(f.row(pending.getId()).getAutoTraded()));
        f.provider.lookupFill=fill("0.0009","0");
        f.live.evaluate(strategy(),bar(now),"binance",List.of(),Map.of());
        assertEquals(1,f.provider.orders); assertTrue(TradingViewLivePositionStore.open(f.row(pending.getId())));
        assertEquals(0,n("11000").compareTo(f.row(pending.getId()).getEntryPrice()));
    }
    @Test void canceledEmptyBuyClosesReservationWithoutInventingHolding() {
        var pending=lot(1); pending.setAutoTraded(false); pending.setFilterReason(PREFIX+"BUY_RESERVED:V=2:CL=TV509B1");
        var f=new Fixture(pending); f.provider.side="buy"; f.provider.state="canceled"; f.provider.lookupFill=fill("0",null);
        f.live.evaluate(strategy(),bar(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)),"binance",List.of(),Map.of());
        assertFalse(f.row(1).getAutoTraded()); assertNotNull(f.row(1).getExitTime()); assertEquals(0,f.provider.orders);
    }
    @Test void baseFeePrecisionCannotRoundOwnedQuantityAboveProviderNet() {
        var pending=lot(1); pending.setAutoTraded(false); pending.setFilterReason(PREFIX+"BUY_RESERVED:V=2:CL=TV509B1");
        var f=new Fixture(pending); var receipt=fill("0.001","0");
        receipt.setFeeAmount(n("-0.000001004")); receipt.setFeeCurrency("BTC"); receipt.setFeeUsdt(n("0.01104400"));
        receipt.setQty(n("0.000998996")); receipt.setNetQty(receipt.getQty());
        f.store.applyBuy(485L,1L,"TV509B1",receipt,TIME);
        assertEquals(n("0.00099899"),f.row(1).getTradedQty());
        assertTrue(f.row(1).getTradedQty().compareTo(receipt.getQty())<0);
        assertTrue(f.row(1).getEntryPrice().multiply(f.row(1).getTradedQty()).subtract(n("11")).abs().compareTo(n("0.00000001"))<=0);
    }
    @Test void missingGroupMemberCannotReleaseRemainingReservation() {
        var f=new Fixture(lot(1),lot(2)); f.reserve("TV509S1"); f.db.remove(2L);
        assertThrows(IllegalStateException.class,()->f.store.applySell(485L,"TV509S1",fill("0.001","0"),TIME));
        assertTrue(TradingViewLivePositionStore.pendingV2(f.row(1)));
    }

    static class Provider extends OkxTradingService {
        int orders,lookups; boolean timeout; String state="NOT_FOUND",side="sell",mismatch="";
        TradeResult fill=fill("0.001","0"),lookupFill=fill("0.001","0");
        Provider(){super(new OkxTradingProperties(),new ObjectMapper());}
        @Override public BigDecimal getLastPrice(String symbol){return n("11000");}
        @Override public String getUsdtBalance(){return "1000";}
        @Override public SpotInstrumentRules getSpotInstrumentRules(String symbol){return new SpotInstrumentRules("BTC-USDT",n("0.0001"),n("0.00000001"),n("0.1"));}
        @Override public List<SpotHolding> getFreshSpotHoldings(){return List.of(new SpotHolding("BTC",n("1"),n("1"),n("11000")));}
        @Override public TradeResult placeMarketSellWithFill(String s,BigDecimal q,String id){orders++; if(timeout)throw new IllegalStateException("offline timeout");return fill;}
        @Override public TradeResult placeMarketBuy(String s,double q,String id){orders++; if(timeout)throw new IllegalStateException("offline timeout");return fill;}
        @Override public SpotOrderLookup lookupSpotOrderByClientOrderId(String symbol,String id){
            lookups++; if(state.equals("NOT_FOUND"))return new SpotOrderLookup(SpotOrderLookupStatus.NOT_FOUND,null);
            var f=lookupFill; return new SpotOrderLookup(SpotOrderLookupStatus.FOUND,new SpotOrderSnapshot(f.getOrderId(),
                    mismatch.equals("CLIENT")?"different":id,mismatch.equals("SIDE")?"buy":side,state,f.getAvgPrice(),
                    f.getGrossQty(),f.getQty(),f.getFeeAmount(),f.getFeeCurrency(),f.getFeeUsdt(),"{}",TIME));
        }
    }
    static class Writer extends DecisionAuditWriter {
        Writer(){super(null,null,null);}
        @Override public void logExit(Long s,String sym,Long id,String why,Map<String,Object> c){}
        @Override public void logAutoTradeFail(Long s,String sym,String why,Map<String,Object> c){}
        @Override public void logAutoTradeOk(Long s,String sym,Long id,Map<String,Object> c){}
        @Override public void logSpotFillEvidence(Long s,Long id,LocalDateTime t,Map<String,Object> c){}
        @Override public void logSpotEntryEvaluation(Long s,String interval,LocalDateTime t,Map<String,Object> c){}
        @Override public void logEntrySkip(Long s,String sym,String interval,LocalDateTime t,String blocker,String why,Map<String,Object> c,Long id){}
    }
    static class Fixture {
        Map<Long,BtLiveSignal> db=new LinkedHashMap<>(); final Provider provider=new Provider();
        final BtLiveSignalRepository positions; final TradingViewLivePositionStore store;
        TradingViewScoreBuyAutoExitLiveService live; boolean failFlush; int rollbacks;
        Fixture(BtLiveSignal... lots){
            for(var l:lots)db.put(l.getId(),l);
            positions=proxy(BtLiveSignalRepository.class,(p,m,a)->switch(m.getName()){
                case "findByStrategyIdAndSymbol" -> new ArrayList<>(db.values());
                case "findByIdForUpdate" -> {assertTrue(TransactionSynchronizationManager.isActualTransactionActive());yield Optional.ofNullable(db.get(a[0]));}
                case "findByStrategyIdAndSymbolAndIntervalCodeAndExitTimeIsNullAndNotifiedAtIsNotNull",
                     "findByExitTimeIsNullAndFilterReasonStartingWith" -> db.values().stream().filter(l->l.getExitTime()==null).toList();
                case "findByAutoTradedIsTrueAndExitTimeIsNull" -> db.values().stream().filter(l->Boolean.TRUE.equals(l.getAutoTraded())&&l.getExitTime()==null).toList();
                case "findByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTime" -> db.values().stream().filter(l->Objects.equals(l.getBarOpenTime(),a[3])).findFirst();
                case "saveAndFlush" -> {if(failFlush)throw new IllegalStateException("offline flush failed");var l=(BtLiveSignal)a[0];if(l.getId()==null)l.setId(100L);db.put(l.getId(),l);yield l;}
                case "saveAllAndFlush" -> {if(failFlush)throw new IllegalStateException("offline flush failed");yield a[0];}
                default -> throw new AssertionError("Unexpected repository call: "+m.getName());
            });
            var strategies=proxy(BtStrategyRepository.class,(p,m,a)->{
                assertEquals("findByIdForBootstrapReservation",m.getName());
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return Optional.of(strategy());});
            var manager=new AbstractPlatformTransactionManager(){
                @Override protected Object doGetTransaction(){return new LinkedHashMap<Long,BtLiveSignal>();}
                @Override protected void doBegin(Object tx,TransactionDefinition d){
                    @SuppressWarnings("unchecked") var snapshot=(Map<Long,BtLiveSignal>)tx;
                    db.forEach((id,l)->{var copy=new BtLiveSignal();BeanUtils.copyProperties(l,copy);snapshot.put(id,copy);});
                }
                @Override protected void doCommit(DefaultTransactionStatus s){}
                @Override protected void doRollback(DefaultTransactionStatus s){
                    @SuppressWarnings("unchecked") var snapshot=(Map<Long,BtLiveSignal>)s.getTransaction();db=new LinkedHashMap<>(snapshot);rollbacks++;
                }
            };
            var pf=new ProxyFactory(new TradingViewLivePositionStore(positions,strategies));
            pf.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
            store=(TradingViewLivePositionStore)pf.getProxy();live=newLive();
        }
        TradingViewScoreBuyAutoExitLiveService newLive(){
            var properties=new TradingViewLocalSignalProperties(true,485,"BTCUSDT","1d","binance",320,3,72,n("10"),n("80"),
                    TradingViewLocalSignalProperties.ExecutionMode.BTC_BASE_LIVE,n("250"),15);
            var okx=new OkxTradingProperties();okx.setEnabled(true);okx.setApiKey("offline");okx.setSecretKey("offline");okx.setPassphrase("offline");
            return new TradingViewScoreBuyAutoExitLiveService(properties,okx,provider,positions,new Writer(),proxy(NotificationPort.class,(p,m,a)->null),store);
        }
        BtLiveSignal row(long id){return db.get(id);}
        void reserve(String id){store.reserveSell(485L,new ArrayList<>(db.values()),id,db.values().stream().map(BtLiveSignal::getTradedQty).reduce(BigDecimal.ZERO,BigDecimal::add));}
        boolean exit(LocalDateTime time)throws Exception{
            var method=TradingViewScoreBuyAutoExitLiveService.class.getDeclaredMethod("executeEligibleExits",BtStrategy.class,MdKline.class);
            method.setAccessible(true);return (boolean)method.invoke(live,strategy(),bar(time));
        }
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
}
