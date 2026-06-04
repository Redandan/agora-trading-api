package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * mempool.space public API — Bitcoin network metrics. <b>No API key required.</b>
 *
 * <h2>Provided indicators</h2> (all written to {@code market_indicator_history}
 * with {@code symbol=BTCUSDT} once per hour by
 * {@link com.agora.scheduler.trading.MarketIndicatorHistoryCollector}):
 * <ul>
 *   <li>{@code btc_mempool_count} — number of unconfirmed transactions in the
 *       BTC mempool. High = network demand spike.</li>
 *   <li>{@code btc_mempool_vsize_mb} — total mempool size in MB (vsize ÷ 1e6).
 *       Sustained high vsize = congestion → fee market activates.</li>
 *   <li>{@code btc_fast_fee_sat_vb} — recommended "fast" fee in sat/vB. Direct
 *       price-of-blockspace; high values correlate with retail/derivative
 *       activity bursts.</li>
 *   <li>{@code btc_hashrate_eh} — current network hashrate in EH/s (10^18 H/s).
 *       Long-trend miner conviction proxy; sharp drops can precede or follow
 *       price stress.</li>
 * </ul>
 *
 * <h2>Why these matter for ML</h2>
 * Mempool and fees are the most direct on-chain proxies for "is BTC actually
 * being used right now". Stablecoin supply tells you the dollars are queued;
 * fee/mempool tells you the demand for blockspace. Hashrate is a slower
 * confidence indicator. Together they triangulate the network-side narrative.
 *
 * <h2>Rate limits</h2>
 * mempool.space free public tier: ~5 req/sec soft cap. Hourly collector + 5-min
 * in-memory cache keeps us well under. They do enforce — be polite.
 *
 * <p>Returns {@code null} on any failure; collector skips the row.
 */
@Slf4j
@Service
public class MempoolSpaceService {

    private static final String BASE_URL = "https://mempool.space/api";
    private static final long CACHE_TTL_MS = 5L * 60L * 1000L;  // 5 minutes

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    public MempoolSpaceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── public getters ────────────────────────────────────────────────────────

    public Double getMempoolCount() {
        return cached("mempool_count", () -> mempoolField("count"));
    }

    public Double getMempoolVsizeMb() {
        Double vsize = cached("mempool_vsize", () -> mempoolField("vsize"));
        return vsize == null ? null : vsize / 1_000_000.0;
    }

    public Double getFastFeeSatVb() {
        return cached("fast_fee", () -> feeField("fastestFee"));
    }

    /** Network hashrate in EH/s (10^18 H/s). Source: /v1/mining/hashrate/3d. */
    public Double getHashrateEh() {
        return cached("hashrate_eh", this::fetchHashrateEh);
    }

    public Map<String, Double> getAllNetworkIndicators() {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("btc_mempool_count",     getMempoolCount());
        out.put("btc_mempool_vsize_mb",  getMempoolVsizeMb());
        out.put("btc_fast_fee_sat_vb",   getFastFeeSatVb());
        out.put("btc_hashrate_eh",       getHashrateEh());
        return out;
    }

    // ── implementation ────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Fetcher { Double fetch(); }

    private Double cached(String key, Fetcher fn) {
        long now = System.currentTimeMillis();
        CachedValue hit = cache.get(key);
        if (hit != null && (now - hit.fetchedAtMs) < CACHE_TTL_MS) {
            return hit.value;
        }
        Double fresh = fn.fetch();
        cache.put(key, new CachedValue(fresh, now));
        return fresh;
    }

    /** GET /mempool → {"count":N,"vsize":N,"total_fee":N,...}. */
    private Double mempoolField(String fieldName) {
        return fetchJsonNumber(BASE_URL + "/mempool", fieldName);
    }

    /** GET /v1/fees/recommended → {"fastestFee":N,...}. */
    private Double feeField(String fieldName) {
        return fetchJsonNumber(BASE_URL + "/v1/fees/recommended", fieldName);
    }

    private Double fetchJsonNumber(String url, String field) {
        try (Response resp = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Mempool] {} HTTP {}", url, resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode v = root.get(field);
            if (v == null || v.isNull() || !v.isNumber()) return null;
            return v.asDouble();
        } catch (Exception e) {
            log.warn("[Mempool] {} error: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * GET /v1/mining/hashrate/3d → {"currentHashrate": <H/s, scientific notation>}.
     * Convert H/s → EH/s by dividing 10^18.
     */
    private Double fetchHashrateEh() {
        try (Response resp = HTTP.newCall(new Request.Builder()
                .url(BASE_URL + "/v1/mining/hashrate/3d").get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Mempool] hashrate HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode v = root.get("currentHashrate");
            if (v == null || v.isNull() || !v.isNumber()) return null;
            double hashrate = v.asDouble();
            return hashrate / 1.0e18;  // → EH/s
        } catch (Exception e) {
            log.warn("[Mempool] hashrate error: {}", e.getMessage());
            return null;
        }
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
}
