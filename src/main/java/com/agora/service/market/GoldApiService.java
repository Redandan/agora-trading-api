package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Gold-API public client — XAU (gold) spot price in USD. <b>No API key required.</b>
 *
 * <h2>Why this matters for ML</h2>
 * Gold is the canonical inflation hedge / safe haven. BTC has been narratively
 * positioned as "digital gold" since 2020. The BTC/Gold price ratio (or the gold
 * price level in dollar terms) provides macro context — gold rallying alongside
 * BTC = risk-off flight; BTC outperforming gold = risk-on adoption rotation.
 *
 * <h2>Provided indicators</h2>
 * <ul>
 *   <li>{@code gold_price_usd} — XAU spot price in USD per oz. Source:
 *       {@code GET /price/XAU}.</li>
 * </ul>
 *
 * <p>Updates every few seconds upstream; we cache 30 min (gold moves slowly
 * in dollar terms — pricing-precision TBD by hourly poll).
 */
@Slf4j
@Service
public class GoldApiService {

    private static final String XAU_URL = "https://api.gold-api.com/price/XAU";
    private static final long CACHE_TTL_MS = 30L * 60L * 1000L;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();

    public GoldApiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Double getGoldPriceUsd() {
        return cached("gold_price_usd", this::fetchGoldPrice);
    }

    @FunctionalInterface
    private interface Fetcher { Double fetch(); }

    private Double cached(String key, Fetcher fn) {
        long now = System.currentTimeMillis();
        CachedValue hit = cache.get(key);
        if (hit != null && (now - hit.fetchedAtMs) < CACHE_TTL_MS) return hit.value;
        Double fresh = fn.fetch();
        cache.put(key, new CachedValue(fresh, now));
        return fresh;
    }

    private Double fetchGoldPrice() {
        try (Response resp = HTTP.newCall(new Request.Builder().url(XAU_URL).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[GoldApi] /price/XAU HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode v = root.get("price");
            if (v == null || v.isNull() || !v.isNumber()) {
                log.warn("[GoldApi] price field missing");
                return null;
            }
            return v.asDouble();
        } catch (Exception e) {
            log.warn("[GoldApi] fetch error: {}", e.getMessage());
            return null;
        }
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
}
