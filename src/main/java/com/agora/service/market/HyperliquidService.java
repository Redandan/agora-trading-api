package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Hyperliquid public REST API client for DEX perpetual market data.
 * <b>No API key required.</b>
 *
 * <p>Hyperliquid is the dominant DEX-perp venue (~70% of all DEX-perp volume in
 * 2026). Its OI/funding tells a different story from CEX (Binance/OKX) — DEX
 * users are typically retail-heavy, lower-leveraged, more reactive to macro.
 *
 * <h2>Provided indicators</h2> (written to {@code market_indicator_history}
 * with {@code symbol=BTCUSDT} once per hour by
 * {@link com.agora.scheduler.trading.MarketIndicatorHistoryCollector}):
 * <ul>
 *   <li>{@code hyperliquid_btc_oi} — BTC perp open interest in BTC units.</li>
 *   <li>{@code hyperliquid_btc_funding_hr_pct} — current funding rate per hour
 *       in percent (Hyperliquid quotes hourly; CEX is typically 8h).</li>
 * </ul>
 *
 * <h2>Endpoint</h2>
 * Single POST to {@code /info} with body {@code {"type":"metaAndAssetCtxs"}};
 * response is a 2-element array: {@code [{universe:[...]}, [assetCtxs...]]}.
 * BTC is index 0 in both. {@code assetCtxs[0]} has {@code openInterest},
 * {@code funding}, {@code markPx}, etc.
 *
 * <h2>Cache</h2>
 * 5-min in-memory; OI/funding updates every block (~1s) but hourly poll
 * doesn't need that resolution.
 */
@Slf4j
@Service
public class HyperliquidService {

    private static final String INFO_URL = "https://api.hyperliquid.xyz/info";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final long CACHE_TTL_MS = 5L * 60L * 1000L;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();

    public HyperliquidService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** BTC perp open interest (in BTC units). */
    public Double getBtcOi() {
        return cached("btc_oi", () -> fetchBtcCtx("openInterest"));
    }

    /** BTC perp current funding rate per hour, in percent (e.g. 0.001 = 0.001%/hr). */
    public Double getBtcFundingHrPct() {
        return cached("btc_funding_hr_pct", () -> {
            Double raw = fetchBtcCtx("funding");
            return raw == null ? null : raw * 100.0;  // decimal → percent
        });
    }

    /**
     * BTC perp funding rate history.
     * Returns list of [timestamp_ms, fundingRate] pairs sorted ascending.
     * Fetches up to 500 records starting from {@code startTimeMs}.
     */
    public com.fasterxml.jackson.databind.JsonNode getFundingHistory(String coin, long startTimeMs) {
        String body = String.format("{\"type\":\"fundingHistory\",\"coin\":\"%s\",\"startTime\":%d}", coin, startTimeMs);
        Request req = new Request.Builder()
                .url(INFO_URL)
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response resp = HTTP.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            return objectMapper.readTree(resp.body().string());
        } catch (Exception e) {
            log.warn("[Hyperliquid] fundingHistory failed: {}", e.getMessage());
            return null;
        }
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

    /** POST /info with metaAndAssetCtxs, extract BTC's named field from assetCtxs[0]. */
    private Double fetchBtcCtx(String field) {
        Request req = new Request.Builder()
                .url(INFO_URL)
                .post(RequestBody.create("{\"type\":\"metaAndAssetCtxs\"}", JSON))
                .build();
        try (Response resp = HTTP.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Hyperliquid] /info HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            if (!root.isArray() || root.size() < 2) {
                log.warn("[Hyperliquid] /info unexpected shape");
                return null;
            }
            // Find BTC index in universe (first element of array)
            JsonNode universe = root.get(0).path("universe");
            int btcIdx = -1;
            for (int i = 0; i < universe.size(); i++) {
                if ("BTC".equals(universe.get(i).path("name").asText())) {
                    btcIdx = i;
                    break;
                }
            }
            if (btcIdx < 0) {
                log.warn("[Hyperliquid] BTC not in universe");
                return null;
            }
            JsonNode ctxs = root.get(1);
            if (!ctxs.isArray() || ctxs.size() <= btcIdx) {
                log.warn("[Hyperliquid] assetCtxs missing BTC index");
                return null;
            }
            JsonNode v = ctxs.get(btcIdx).get(field);
            if (v == null || v.isNull()) return null;
            // Hyperliquid returns numbers as JSON strings — parse defensively
            String text = v.asText();
            if (text.isBlank()) return null;
            return Double.parseDouble(text);
        } catch (Exception e) {
            log.warn("[Hyperliquid] fetch field={} error: {}", field, e.getMessage());
            return null;
        }
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
}
