package com.agora.service.advice;

import com.agora.config.OkxTradingProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.SpotExecutionAttemptRepository;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import static com.agora.service.advice.BtcOrderAdviceEngine.*;
import static com.agora.service.advice.BtcOrderAdviceEngineTest.d;
import static org.junit.jupiter.api.Assertions.*;

class BtcOrderAdviceSnapshotReaderTest {
    final ObjectMapper mapper = new ObjectMapper();
    String available = "0.0008871693";
    String instrumentState = "live";
    boolean pendingAttempt;
    List<String> calls = new ArrayList<>();

    JsonNode json(String value) { try { return mapper.readTree(value); } catch (Exception e) { throw new AssertionError(e); } }
    BtcOrderAdviceSnapshotReader reader() {
        var okx = new OkxTradingService(new OkxTradingProperties(), mapper) {
            @Override public JsonNode getBtcAdvicePendingOrders(String type) { calls.add("orders:" + type); return json("[]"); }
            @Override public JsonNode getBtcAdviceBalances() {
                calls.add("balances"); return json("[{\"details\":[{\"ccy\":\"BTC\",\"availBal\":\"" + available + "\",\"cashBal\":\"" + available + "\"}]}]");
            }
            @Override public BigDecimal getSpotTakerFeeRate(String symbol) { calls.add("fees"); return d("0.001"); }
            @Override public JsonNode getBtcAdviceInstrument() {
                calls.add("rules"); return json("{\"instId\":\"BTC-USDT\",\"instType\":\"SPOT\",\"state\":\"" + instrumentState + "\",\"minSz\":\"0.00001\",\"lotSz\":\"0.00000001\",\"tickSz\":\"0.1\"}");
            }
            @Override public JsonNode getSpotTickerSnapshot(String symbol) {
                calls.add("ticker"); return json("{\"instId\":\"BTC-USDT\",\"bidPx\":\"83999\",\"askPx\":\"84001\",\"last\":\"84000\",\"ts\":\"" + Instant.now().toEpochMilli() + "\"}");
            }
        };
        var p1 = position(260L, "0.0004709", "63707.793586748779", BtcBasePositionStatePolicy.ADOPTED_FROM_OCO_PREFIX + "123");
        var p2 = position(264L, "0.0003536", "84755", BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX + "OPEN:x");
        var positions = proxy(BtLiveSignalRepository.class, (method, args) -> {
            if (method.equals("findByAutoTradedIsTrueAndExitTimeIsNull") || method.equals("findByExitTimeIsNullAndFilterReasonStartingWith")) return List.of(p1, p2);
            throw new AssertionError("No position writes allowed: " + method);
        });
        var klines = proxy(MdKlineRepository.class, (method, args) -> {
            assertEquals("findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc", method);
            assertEquals("okx", args[2]);
            var result = new ArrayList<MdKline>();
            LocalDateTime start = (LocalDateTime) args[3];
            for (int i = 0; i < 168; i++) {
                var b = new MdKline(); b.setSymbol("BTCUSDT"); b.setIntervalCode("1h"); b.setSource("okx");
                b.setOpenTime(start.plusHours(i)); b.setCloseTime(start.plusHours(i + 1).minusNanos(1));
                b.setOpenPrice(d("84000")); b.setClosePrice(d("84000")); b.setHighPrice(d("86000")); b.setLowPrice(d("82000"));
                result.add(b);
            }
            return result;
        });
        var attempts = proxy(SpotExecutionAttemptRepository.class, (method, args) -> {
            assertEquals("existsByStateIn", method); return pendingAttempt;
        });
        return new BtcOrderAdviceSnapshotReader(okx, klines, positions, attempts);
    }
    @Test void ownershipIsDeduplicatedAndOnlyLegacyBtcIsEligible() {
        Input input = reader().read();
        assertEquals(0, d("0.0004709").compareTo(input.legacyQuantity()));
        assertEquals(0, d("0.0003536").compareTo(input.protectedBtc()));
        assertEquals(9, calls.size()); assertEquals("PLAN", new BtcOrderAdviceEngine().evaluate(input, Policy.defaults()).status());
    }
    @Test void manualSaleMismatchAndUnsettledAttemptsCannotGenerateAnotherSell() {
        available = "0.0007";
        assertEquals("DATA_UNAVAILABLE", new BtcOrderAdviceEngine().evaluate(reader().read(), Policy.defaults()).status());
        available = "0.0008871693"; pendingAttempt = true;
        assertEquals("DATA_UNAVAILABLE", new BtcOrderAdviceEngine().evaluate(reader().read(), Policy.defaults()).status());
    }
    @Test void haltedInstrumentCannotGenerateAdvice() {
        instrumentState = "suspend";
        assertEquals("DATA_UNAVAILABLE", new BtcOrderAdviceEngine().evaluate(reader().read(), Policy.defaults()).status());
    }
    @Test void repositoryDerivedQueryResolvesActualEntityProperties() {
        assertDoesNotThrow(() -> new org.springframework.data.repository.query.parser.PartTree(
                "existsByStateIn", com.agora.model.SpotExecutionAttempt.class));
    }
    static BtLiveSignal position(long id, String qty, String price, String owner) {
        var p = new BtLiveSignal(); p.setId(id); p.setSymbol("BTCUSDT"); p.setAutoTraded(true);
        p.setTradedQty(d(qty)); p.setActualEntryPrice(d(price)); p.setFilterReason(owner); return p;
    }
    interface Invocation { Object call(String method, Object[] args); }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, Invocation action) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (p, m, a) -> action.call(m.getName(), a));
    }
}
