package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** An in-process OkHttp interceptor supplies receipts. No sockets, credentials or Spring. */
class OkxProtectedExitTransportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void fullPartialAndZeroFillProviderReceiptsRemainDistinct() throws Exception {
        for (String state : List.of("filled", "partially_filled", "canceled")) {
            List<String> calls = new ArrayList<>();
            var provider = provider(calls, state, false);
            var result = provider.placeIocSellWithPriceFloor("BTCUSDT", new BigDecimal("0.001"),
                    new BigDecimal("90000.1"), "DRA1");
            assertEquals(state, result.providerState());
            assertEquals(List.of("POST:90000.1", "GET:receipt"), calls);
            if (state.equals("canceled")) assertEquals(0, result.cumulativeGrossQuantity().signum());
        }
    }

    @Test void unconfirmedReceiptNeverTriggersAnotherPostOrMarketFallback() throws Exception {
        List<String> calls = new ArrayList<>();
        var provider = provider(calls, "filled", true);
        assertThrows(IllegalStateException.class, () -> provider.placeIocSellWithPriceFloor("BTCUSDT", new BigDecimal("0.001"),
                new BigDecimal("90000.1"), "DRA1"));
        assertEquals(List.of("POST:90000.1", "GET:receipt"), calls);
    }

    @Test void freshFundingIncludesFrozenBalanceAndRejectsMissingPayload() throws Exception {
        var properties = properties();
        var provider = new OkxTradingService(properties, mapper);
        setClient(provider, new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(),
                "{\"code\":\"0\",\"data\":[{\"ccy\":\"USDT\",\"bal\":\"50\",\"availBal\":\"20\",\"frozenBal\":\"30\"}]}" )).build());
        var funding = provider.getFreshFundingHoldings();
        assertEquals(new BigDecimal("50"), funding.getFirst().cashBal);
        assertEquals(new BigDecimal("20"), funding.getFirst().availBal);
        assertEquals(new BigDecimal("50"), funding.getFirst().eqUsd);
        setClient(provider, new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(), "{\"code\":\"0\"}")).build());
        assertThrows(IllegalStateException.class, provider::getFreshFundingHoldings); // No stale fallback.
        assertThrows(RuntimeException.class, provider::getFreshSpotHoldings);
    }

    @Test void tradingCashAndEquityKeepTheirOwnValuationIncludingBotOnlyCurrency() throws Exception {
        var provider = new OkxTradingService(properties(), mapper);
        setClient(provider, new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(), """
                {"code":"0","data":[{"details":[
                  {"ccy":"USDT","cashBal":"439","availBal":"439","eq":"448.5","eqUsd":"448.5","stgyEq":"9.5"},
                  {"ccy":"BTC","cashBal":"0","availBal":"0","eq":"0.00005","eqUsd":"4.25","stgyEq":"0.00005"}
                ]}]}
                """)).build());
        var balances = provider.getFreshSpotHoldings();
        assertEquals(2, balances.size());
        var usdt = balances.getFirst();
        assertEquals(new BigDecimal("439"), usdt.cashBal);
        assertEquals(0, new BigDecimal("439").compareTo(usdt.eqUsd));
        assertEquals(new BigDecimal("448.5"), usdt.equityQuantity);
        assertEquals(new BigDecimal("448.5"), usdt.equityUsd);
        assertEquals(new BigDecimal("9.5"), usdt.strategyEquityQuantity);
        var report = SpotAccountRiskPolicy.snapshot(balances, List.of());
        assertEquals(new BigDecimal("452.75"), report.get("observedBalanceEquityUsd"));
        assertEquals(new BigDecimal("4.25"), report.get("observedBtcExposureUsd"));
        assertEquals(new BigDecimal("439"), report.get("availableTradingUsdt"));
        assertTrue(report.get("botEquityAccounting").toString().contains("DO_NOT_ADD"));
    }

    @Test void missingEquityCannotSilentlyFallBackToCashAndWrongScopeCannotProduceRiskTotals() throws Exception {
        var provider = new OkxTradingService(properties(), mapper);
        setClient(provider, new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(),
                "{\"code\":\"0\",\"data\":[{\"details\":[{\"ccy\":\"BTC\",\"cashBal\":\"0\",\"availBal\":\"0\",\"eqUsd\":\"4\",\"stgyEq\":\"0.1\"}]}]}" )).build());
        assertThrows(IllegalStateException.class, provider::getFreshSpotHoldings);
        var invalid = new OkxTradingService.SpotHolding("BTC", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("4"), new BigDecimal("0.1"));
        var report = SpotAccountRiskPolicy.snapshot(List.of(invalid), List.of());
        assertFalse(report.containsKey("observedBalanceEquityUsd"));
        assertTrue(report.get("valuationStatus").toString().startsWith("MISSING_PROOF"));
    }

    private OkxTradingService provider(List<String> calls, String state, boolean missing) throws Exception {
        var provider = new OkxTradingService(properties(), mapper);
        setClient(provider, new OkHttpClient.Builder().addInterceptor(chain -> {
            var request = chain.request();
            assertEquals("fixture.invalid", request.url().host());
            if (request.method().equals("POST")) {
                var buffer = new Buffer(); request.body().writeTo(buffer);
                var body = mapper.readTree(buffer.readUtf8());
                assertEquals("ioc", body.path("ordType").asText());
                assertEquals("0", body.path("pxAmendType").asText());
                calls.add("POST:" + body.path("px").asText());
                return response(request, "{\"code\":\"0\",\"data\":[{\"sCode\":\"0\",\"ordId\":\"123\"}]}");
            }
            calls.add("GET:receipt");
            assertEquals("DRA1", request.url().queryParameter("clOrdId"));
            if (missing) return response(request, "{\"code\":\"51603\"}");
            String quantity = state.equals("canceled") ? "0" : state.equals("partially_filled") ? "0.0004" : "0.001";
            return response(request, "{\"code\":\"0\",\"data\":[{\"instId\":\"BTC-USDT\",\"ordId\":\"123\",\"clOrdId\":\"DRA1\","
                    + "\"side\":\"sell\",\"state\":\"" + state + "\",\"avgPx\":\"90001\",\"accFillSz\":\"" + quantity
                    + "\",\"fee\":\"0\",\"feeCcy\":\"USDT\"}]}");
        }).build());
        return provider;
    }

    private OkxTradingProperties properties() {
        var p = new OkxTradingProperties(); p.setEnabled(true); p.setApiKey("fixture");
        p.setSecretKey("fixture"); p.setPassphrase("fixture"); p.setBaseUrl("https://fixture.invalid"); return p;
    }
    private void setClient(OkxTradingService provider, OkHttpClient client) throws Exception {
        var field = OkxTradingService.class.getDeclaredField("httpClient"); field.setAccessible(true); field.set(provider, client);
    }
    private Response response(Request request, String json) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(ResponseBody.create(json, MediaType.get("application/json"))).build();
    }
}
