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
 * Blockchain.info {@code /stats} client — Bitcoin chain-level statistics.
 * <b>No API key required.</b>
 *
 * <p>Complements V075's mempool.space (which gives mempool/fees/hashrate
 * snapshots) with chain-level aggregates: circulating supply and average
 * block production rate.
 *
 * <h2>Provided indicators</h2>
 * <ul>
 *   <li>{@code btc_supply_circulating_m} — total mined BTC, in <b>millions</b>
 *       (e.g. 20.02 = 20,020,000 BTC). Scaled by 10^6 to fit DECIMAL(12,6) —
 *       same gotcha as V076 btc_treasury_holdings_kbtc.</li>
 *   <li>{@code btc_block_time_avg_min} — average minutes between blocks over
 *       the recent window (e.g. 9.36). Bitcoin targets 10 min; lower = miners
 *       added hashrate faster than difficulty adjusted; higher = miners left
 *       (often during price stress).</li>
 * </ul>
 *
 * <h2>Source field mapping</h2>
 * <ul>
 *   <li>{@code totalbc}: total BTC in satoshis (1 BTC = 10^8 sat).
 *       Convert to millions: {@code totalbc / 1e8 / 1e6}.</li>
 *   <li>{@code minutes_between_blocks}: float, already in minutes.</li>
 * </ul>
 *
 * <p>Cache 30 min — these aggregates update slowly, no need to hammer.
 */
@Slf4j
@Service
public class BlockchainInfoService {

    private static final String STATS_URL = "https://api.blockchain.info/stats";
    private static final long CACHE_TTL_MS = 30L * 60L * 1000L;
    private static final double SAT_PER_BTC = 1.0e8;
    private static final double BTC_PER_MILLION = 1.0e6;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();

    public BlockchainInfoService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Total mined BTC in millions (e.g. 20.02). */
    public Double getBtcSupplyCirculatingMillions() {
        return cached("btc_supply_circulating_m", () -> {
            JsonNode stats = fetchStats();
            if (stats == null) return null;
            JsonNode v = stats.get("totalbc");
            if (v == null || v.isNull() || !v.isNumber()) return null;
            return v.asDouble() / SAT_PER_BTC / BTC_PER_MILLION;
        });
    }

    /** Average minutes between blocks (e.g. 9.36). */
    public Double getBtcBlockTimeAvgMin() {
        return cached("btc_block_time_avg_min", () -> {
            JsonNode stats = fetchStats();
            if (stats == null) return null;
            JsonNode v = stats.get("minutes_between_blocks");
            if (v == null || v.isNull() || !v.isNumber()) return null;
            return v.asDouble();
        });
    }

    @FunctionalInterface
    private interface NodeFetcher { Double fetch(); }

    private Double cached(String key, NodeFetcher fn) {
        long now = System.currentTimeMillis();
        CachedValue hit = cache.get(key);
        if (hit != null && (now - hit.fetchedAtMs) < CACHE_TTL_MS) return hit.value;
        Double fresh = fn.fetch();
        cache.put(key, new CachedValue(fresh, now));
        return fresh;
    }

    private JsonNode fetchStats() {
        try (Response resp = HTTP.newCall(new Request.Builder().url(STATS_URL).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[BlockchainInfo] /stats HTTP {}", resp.code());
                return null;
            }
            return objectMapper.readTree(resp.body().string());
        } catch (Exception e) {
            log.warn("[BlockchainInfo] /stats error: {}", e.getMessage());
            return null;
        }
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
}
