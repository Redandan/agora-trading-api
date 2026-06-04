package com.agora.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Order(3)
@Slf4j
class CoinMarketCapExchangeRateProvider implements ExchangeRateProvider {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    CoinMarketCapExchangeRateProvider(
            ObjectMapper objectMapper,
            @Value("${exchange-rate.coinmarketcap.api-key:}") String apiKey) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Override
    public String name() { return "CoinMarketCap"; }

    @Override
    public Map<String, BigDecimal> fetchRates(List<String> supportedCurrencies) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("CoinMarketCap API Key 未配置");
        }
        String url = "https://pro-api.coinmarketcap.com/v1/cryptocurrency/quotes/latest?symbol=USDT&convert="
                + String.join(",", supportedCurrencies);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("X-CMC_PRO_API_KEY", apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("CoinMarketCap API 請求失敗: " + response.code());
            }
            return parse(response.body().string(), supportedCurrencies);
        }
    }

    private Map<String, BigDecimal> parse(String json, List<String> currencies) throws IOException {
        try {
            JsonNode data = objectMapper.readTree(json).get("data");
            if (data == null || !data.isObject()) {
                throw new IOException("CoinMarketCap 響應中未找到 data 對象");
            }
            JsonNode usdtNode = null;
            for (JsonNode node : data) {
                if (node.has("symbol") && "USDT".equals(node.get("symbol").asText())) {
                    usdtNode = node;
                    break;
                }
            }
            if (usdtNode == null) throw new IOException("CoinMarketCap 響應中未找到 USDT 數據");

            JsonNode quote = usdtNode.get("quote");
            if (quote == null || !quote.isObject()) {
                throw new IOException("CoinMarketCap 響應中未找到 quote 對象");
            }
            Map<String, BigDecimal> rates = new HashMap<>();
            for (String currency : currencies) {
                JsonNode cq = quote.get(currency);
                if (cq != null && cq.has("price")) {
                    BigDecimal rate = cq.get("price").decimalValue();
                    if (rate.compareTo(BigDecimal.ZERO) > 0) rates.put(currency, rate);
                }
            }
            if (rates.isEmpty()) throw new IOException("未能從 CoinMarketCap 解析到任何匯率數據");
            return rates;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("解析 CoinMarketCap 響應失敗: " + e.getMessage(), e);
        }
    }
}
