package com.agora.service.trading;

import com.agora.config.BinanceTradingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Binance 現貨下單服務（備用，美國 IP 無法使用 Binance 國際版）。
 *
 * <p>實作 {@link TradingService} 介面。OKX 為 @Primary 實作，
 * 此服務僅保留供日後切換或測試用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceTradingService implements TradingService {

    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");

    private final BinanceTradingProperties props;
    private final ObjectMapper objectMapper;

    private OkHttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public TradeResult placeMarketBuy(String symbol, double usdtAmount) {
        checkEnabled();
        String params = buildParams(
                "symbol", symbol,
                "side", "BUY",
                "type", "MARKET",
                "quoteOrderQty", String.format("%.2f", usdtAmount)
        );
        JsonNode resp = executePost("/api/v3/order", params);
        return parseTradeResult(resp);
    }

    @Override
    public Long placeOco(String symbol, BigDecimal qty, BigDecimal tp, BigDecimal sl) {
        checkEnabled();
        BigDecimal stopLimitPrice = sl.multiply(BigDecimal.valueOf(0.997))
                .setScale(2, RoundingMode.HALF_DOWN);
        String params = buildParams(
                "symbol", symbol,
                "side", "SELL",
                "quantity", qty.toPlainString(),
                "price", tp.toPlainString(),
                "stopPrice", sl.toPlainString(),
                "stopLimitPrice", stopLimitPrice.toPlainString(),
                "stopLimitTimeInForce", "GTC"
        );
        JsonNode resp = executePost("/api/v3/order/oco", params);
        return resp.path("orderListId").asLong();
    }

    @Override
    public void cancelOco(String symbol, Long orderListId) {
        checkEnabled();
        String params = buildParams(
                "symbol", symbol,
                "orderListId", String.valueOf(orderListId)
        );
        executeDelete("/api/v3/orderList", params);
        log.info("[Binance] OCO cancelled: symbol={} orderListId={}", symbol, orderListId);
    }

    @Override
    public BigDecimal placeMarketSell(String symbol, BigDecimal qty) {
        checkEnabled();
        String params = buildParams(
                "symbol", symbol,
                "side", "SELL",
                "type", "MARKET",
                "quantity", qty.toPlainString()
        );
        JsonNode resp = executePost("/api/v3/order", params);
        return calcAvgPrice(resp);
    }

    // ── 內部工具 ─────────────────────────────────

    private TradeResult parseTradeResult(JsonNode resp) {
        TradeResult r = new TradeResult();
        r.setOrderId(resp.path("orderId").asText());
        r.setQty(new BigDecimal(resp.path("executedQty").asText()));
        r.setAvgPrice(calcAvgPrice(resp));
        return r;
    }

    private BigDecimal calcAvgPrice(JsonNode resp) {
        BigDecimal quote = new BigDecimal(resp.path("cummulativeQuoteQty").asText("0"));
        BigDecimal qty   = new BigDecimal(resp.path("executedQty").asText("1"));
        if (qty.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return quote.divide(qty, 8, RoundingMode.HALF_UP);
    }

    private String buildParams(String... keyValues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(keyValues[i]).append('=').append(keyValues[i + 1]);
        }
        sb.append("&timestamp=").append(System.currentTimeMillis());
        String unsigned = sb.toString();
        return unsigned + "&signature=" + hmacSha256(unsigned, props.getSecretKey());
    }

    private JsonNode executePost(String path, String signedParams) {
        RequestBody body = RequestBody.create(signedParams, FORM);
        Request request = new Request.Builder()
                .url(props.getSpotRestBaseUrl() + path)
                .header("X-MBX-APIKEY", props.getApiKey())
                .post(body)
                .build();
        return execute(request, path);
    }

    private JsonNode executeDelete(String path, String signedParams) {
        Request request = new Request.Builder()
                .url(props.getSpotRestBaseUrl() + path + "?" + signedParams)
                .header("X-MBX-APIKEY", props.getApiKey())
                .delete()
                .build();
        return execute(request, path);
    }

    private JsonNode execute(Request request, String path) {
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("Binance API error [" + path + "] HTTP " + response.code() + ": " + body);
            }
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Binance API call failed [" + path + "]: " + e.getMessage(), e);
        }
    }

    private String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    private void checkEnabled() {
        if (!props.isEnabled()) {
            throw new IllegalStateException("Binance auto-trade disabled (trading.binance.enabled=false)");
        }
    }
}
