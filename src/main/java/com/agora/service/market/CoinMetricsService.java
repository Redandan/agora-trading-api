package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * CoinMetrics community API client — free tier, no API key required.
 *
 * <p>Community tier provides daily on-chain metrics for BTC. MVRV and SOPR
 * require the paid tier; the free tier covers network activity metrics which
 * are still useful as leading indicators of on-chain health.
 *
 * <p>Provided indicators:
 * <ul>
 *   <li>{@code btc_active_addr_cnt} — unique addresses active in last 24h.
 *       Spike = adoption/activity; sustained decline = network contraction.</li>
 *   <li>{@code btc_tx_cnt} — confirmed on-chain transactions per day.
 *       Corroborates AdrActCnt; divergence may signal fee spikes or L2 migration.</li>
 * </ul>
 *
 * <p>Rate limit: 6000 requests / 20-second sliding window (community plan).
 * We poll once per hour so this is never an issue.
 */
@Slf4j
@Service
public class CoinMetricsService {

    private static final String BASE_URL =
            "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics" +
            "?assets=btc&metrics=AdrActCnt,TxCnt&frequency=1d&limit_per_asset=1";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;

    /** Simple in-memory cache — CoinMetrics daily values don't change within a day. */
    private volatile CachedResult cache = null;
    private static final long CACHE_TTL_MS = 60L * 60L * 1_000L; // 1h

    public CoinMetricsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Daily active BTC address count. Null on API failure. */
    public Double getBtcActiveAddresses() {
        return getOrFetch().activeAddresses;
    }

    /** Daily BTC on-chain transaction count. Null on API failure. */
    public Double getBtcTxCount() {
        return getOrFetch().txCount;
    }

    private synchronized CachedResult getOrFetch() {
        long now = System.currentTimeMillis();
        if (cache != null && (now - cache.fetchedAtMs) < CACHE_TTL_MS) return cache;
        cache = fetch(now);
        return cache;
    }

    private CachedResult fetch(long now) {
        try (Response resp = HTTP.newCall(
                new Request.Builder().url(BASE_URL).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[CoinMetrics] HTTP {}", resp.code());
                return new CachedResult(null, null, now);
            }
            JsonNode data = objectMapper.readTree(resp.body().string()).path("data");
            if (!data.isArray() || data.isEmpty()) return new CachedResult(null, null, now);
            JsonNode row = data.get(data.size() - 1);
            Double addr = parseDouble(row, "AdrActCnt");
            Double tx   = parseDouble(row, "TxCnt");
            log.debug("[CoinMetrics] AdrActCnt={} TxCnt={}", addr, tx);
            return new CachedResult(addr, tx, now);
        } catch (Exception e) {
            log.warn("[CoinMetrics] fetch error: {}", e.getMessage());
            return new CachedResult(null, null, now);
        }
    }

    private Double parseDouble(JsonNode row, String field) {
        String v = row.path(field).asText(null);
        if (v == null || v.isEmpty()) return null;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return null; }
    }

    private record CachedResult(Double activeAddresses, Double txCount, long fetchedAtMs) {}
}
