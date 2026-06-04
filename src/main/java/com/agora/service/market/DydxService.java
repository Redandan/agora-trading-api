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
 * dYdX v4 Indexer public API client. <b>No API key required.</b>
 *
 * <p>Older / smaller DEX-perp than Hyperliquid (~10% of DEX-perp share in 2026)
 * but provides a sanity check on Hyperliquid's signal — when both DEX OIs move
 * together, the signal is more credible.
 *
 * <h2>Provided indicators</h2>
 * <ul>
 *   <li>{@code dydx_btc_oi} — dYdX BTC-USD perp open interest in BTC units.
 *       Source: {@code GET /v4/perpetualMarkets} → {@code markets["BTC-USD"].openInterest}.</li>
 * </ul>
 *
 * <h2>Cache</h2>
 * 5-min in-memory.
 */
@Slf4j
@Service
public class DydxService {

    private static final String MARKETS_URL =
            "https://indexer.dydx.trade/v4/perpetualMarkets";
    private static final long CACHE_TTL_MS = 5L * 60L * 1000L;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();

    public DydxService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Double getBtcOi() {
        return cached("btc_oi", this::fetchBtcOi);
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

    private Double fetchBtcOi() {
        try (Response resp = HTTP.newCall(new Request.Builder().url(MARKETS_URL).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[dYdX] /perpetualMarkets HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode btc = root.path("markets").path("BTC-USD");
            JsonNode oi = btc.get("openInterest");
            if (oi == null || oi.isNull()) {
                log.warn("[dYdX] openInterest missing for BTC-USD");
                return null;
            }
            return Double.parseDouble(oi.asText());
        } catch (Exception e) {
            log.warn("[dYdX] fetch error: {}", e.getMessage());
            return null;
        }
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
}
