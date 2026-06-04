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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Order(1)
@Slf4j
class CoinGeckoExchangeRateProvider implements ExchangeRateProvider {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    CoinGeckoExchangeRateProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public String name() { return "CoinGecko"; }

    @Override
    public Map<String, BigDecimal> fetchRates(List<String> supportedCurrencies) throws IOException {
        String url = "https://api.coingecko.com/api/v3/simple/price?ids=tether&vs_currencies="
                + String.join(",", supportedCurrencies);
        Request request = new Request.Builder().url(url).addHeader("Accept", "application/json").build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("CoinGecko API 請求失敗: " + response.code());
            }
            return parse(response.body().string(), supportedCurrencies);
        }
    }

    private Map<String, BigDecimal> parse(String json, List<String> currencies) throws IOException {
        try {
            JsonNode tether = objectMapper.readTree(json).get("tether");
            if (tether == null || !tether.isObject()) {
                throw new IOException("CoinGecko 響應中未找到 tether 數據");
            }
            Map<String, BigDecimal> rates = new HashMap<>();
            for (String currency : currencies) {
                JsonNode node = tether.get(currency.toLowerCase());
                if (node != null && node.isNumber()) {
                    BigDecimal rate = node.decimalValue();
                    if (rate.compareTo(BigDecimal.ZERO) > 0) rates.put(currency, rate);
                }
            }
            if (rates.isEmpty()) throw new IOException("未能從 CoinGecko 解析到任何匯率數據");
            return rates;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("解析 CoinGecko 響應失敗: " + e.getMessage(), e);
        }
    }
}
