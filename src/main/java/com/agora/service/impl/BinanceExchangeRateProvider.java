package com.agora.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Order(2)
@Slf4j
class BinanceExchangeRateProvider implements ExchangeRateProvider {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    BinanceExchangeRateProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private static final String[] STABLE_PAIRS = {"USDCUSDT", "DAIUSDT", "BUSDUSDT", "TUSDUSDT"};

    @Override
    public String name() { return "Binance"; }

    @Override
    public Map<String, BigDecimal> fetchRates(List<String> supportedCurrencies) throws IOException {
        try {
            BigDecimal usdtUsdRate = resolveUsdtUsdRate();
            Map<String, BigDecimal> rates = new HashMap<>();
            rates.put("USD", usdtUsdRate);
            rates.putAll(fetchForexRates(usdtUsdRate, supportedCurrencies));
            if (rates.isEmpty()) throw new IOException("Binance API 未能取得任何匯率");
            return rates;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Binance API 獲取失敗: " + e.getMessage(), e);
        }
    }

    private BigDecimal resolveUsdtUsdRate() {
        for (String symbol : STABLE_PAIRS) {
            try {
                String url = "https://api.binance.com/api/v3/ticker/price?symbol=" + symbol;
                Request req = new Request.Builder().url(url).addHeader("Accept", "application/json").build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) continue;
                    JsonNode node = objectMapper.readTree(resp.body().string());
                    if (!node.has("price")) continue;
                    BigDecimal pair = new BigDecimal(node.get("price").asText());
                    BigDecimal rate = symbol.endsWith("USDT")
                            ? BigDecimal.ONE.divide(pair, 8, RoundingMode.HALF_UP)
                            : pair;
                    if (rate.compareTo(BigDecimal.ZERO) > 0) {
                        log.debug("從 Binance {} 獲取 USDT/USD: {}", symbol, rate);
                        return rate;
                    }
                }
            } catch (Exception e) {
                log.debug("無法從 Binance 獲取 {}，嘗試下一個", symbol);
            }
        }
        log.warn("無法從 Binance 獲取 USDT/USD，使用默認值 1.0");
        return BigDecimal.ONE;
    }

    private Map<String, BigDecimal> fetchForexRates(BigDecimal usdtUsdRate, List<String> currencies) {
        Map<String, BigDecimal> result = new HashMap<>();
        try {
            Request req = new Request.Builder()
                    .url("https://api.exchangerate-api.com/v4/latest/USD")
                    .addHeader("Accept", "application/json").build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("外匯 API 請求失敗");
                    return result;
                }
                JsonNode ratesNode = objectMapper.readTree(resp.body().string()).get("rates");
                if (ratesNode == null) return result;
                for (String currency : currencies) {
                    if ("USD".equals(currency)) continue;
                    JsonNode node = ratesNode.get(currency);
                    if (node != null && node.isNumber()) {
                        BigDecimal rate = usdtUsdRate.multiply(node.decimalValue())
                                .setScale(8, RoundingMode.HALF_UP);
                        if (rate.compareTo(BigDecimal.ZERO) > 0) result.put(currency, rate);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("外匯 API 獲取失敗: {}，僅使用 USD 匯率", e.getMessage());
        }
        return result;
    }
}
