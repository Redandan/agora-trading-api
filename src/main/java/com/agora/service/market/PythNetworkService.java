package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Pyth Network Hermes API client — independent on-/off-chain oracle prices.
 * <b>No API key required.</b>
 *
 * <p>Pyth aggregates first-party data from 90+ publishers (exchanges, market
 * makers, trading firms) and exposes a unified price feed used across 50+
 * blockchains. As an independent third party (not a CEX), it provides a clean
 * cross-source benchmark for detecting OKX/Binance price anomalies.
 *
 * <h2>Provided indicator</h2> (written to {@code market_indicator_history}
 * with {@code symbol=BTCUSDT}, BTC-only block):
 * <ul>
 *   <li>{@code pyth_btc_usd_price} — Pyth BTC/USD feed price in USD. Compare to
 *       our OKX/Binance feeds to detect divergences (could indicate exchange
 *       liquidity issues, listing front-running, oracle attacks, etc).</li>
 * </ul>
 *
 * <h2>Endpoint shape</h2>
 * <pre>{@code
 *   GET /api/latest_price_feeds?ids[]=<feed_id_hex>
 *
 *   [{ "id":"e62d...43",
 *      "price":{"price":"7740364000000","expo":-8,"conf":"...","publish_time":...},
 *      "ema_price":{...} }]
 * }</pre>
 * Real value = {@code price.price} × 10^{@code price.expo}. Pyth always returns
 * integer-string + integer expo to avoid floating-point loss.
 *
 * <h2>Cache</h2>
 * 5-min in-memory. Pyth updates every ~400 ms upstream; we don't need that
 * resolution but cache prevents per-MCP-call hammering.
 */
@Slf4j
@Service
public class PythNetworkService {

    /** BTC/USD price feed id on Pyth. Same id across all chains. */
    public static final String FEED_ID_BTC_USD =
            "e62df6c8b4a85fe1a67db44dc12de5db330f7ac66b72dc658afedf0f4a415b43";

    private static final String BASE_URL = "https://hermes.pyth.network/api/latest_price_feeds";
    private static final long CACHE_TTL_MS = 5L * 60L * 1000L;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            // Hermes occasionally stalls over HTTP/2 from the OCI host while
            // plain curl succeeds. Force HTTP/1.1 to match curl-like behavior.
            .protocols(java.util.List.of(Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();

    public PythNetworkService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Pyth BTC/USD oracle price, in USD (e.g. 77403.64). */
    public Double getBtcUsdPrice() {
        return cached("btc_usd", () -> fetchPrice(FEED_ID_BTC_USD));
    }

    @FunctionalInterface
    private interface Fetcher { Double fetch(); }

    private Double cached(String key, Fetcher fn) {
        long now = System.currentTimeMillis();
        CachedValue hit = cache.get(key);
        if (hit != null && (now - hit.fetchedAtMs) < CACHE_TTL_MS) return hit.value;
        Double fresh = fn.fetch();
        if (fresh == null) {
            return hit != null ? hit.value : null;
        }
        cache.put(key, new CachedValue(fresh, now));
        return fresh;
    }

    /**
     * Fetch one feed and apply {@code expo} scaling. Pyth wraps the response
     * in a 1-element array even for single-id queries.
     */
    private Double fetchPrice(String feedId) {
        String url = BASE_URL + "?ids[]=" + feedId;
        try (Response resp = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Pyth] feed={} HTTP {}", feedId, resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            if (!root.isArray() || root.isEmpty()) {
                log.warn("[Pyth] feed={} empty response", feedId);
                return null;
            }
            JsonNode priceObj = root.get(0).path("price");
            String priceStr = priceObj.path("price").asText("");
            if (priceStr.isBlank()) return null;
            int expo = priceObj.path("expo").asInt(0);
            try {
                long rawPrice = Long.parseLong(priceStr);
                return rawPrice * Math.pow(10, expo);
            } catch (NumberFormatException nfe) {
                log.warn("[Pyth] feed={} unparseable price '{}'", feedId, priceStr);
                return null;
            }
        } catch (Exception e) {
            log.warn("[Pyth] feed={} error: {}", feedId, e.getMessage());
            return null;
        }
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
}
