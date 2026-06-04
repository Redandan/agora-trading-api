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
 * Kraken Public REST client — third major CEX price feed. <b>No API key required.</b>
 *
 * <p>Kraken is the largest US-/EU-regulated CEX outside Binance/Coinbase.
 * Adding it as a third independent venue gives us a price triangle for
 * cross-exchange divergence detection — when OKX/Binance/Kraken all agree
 * within a few bps, the consensus price is reliable; when one diverges,
 * it's a signal of either liquidity issues or front-running.
 *
 * <h2>Provided indicator</h2> (written to {@code market_indicator_history}
 * with {@code symbol=BTCUSDT}, BTC-only block):
 * <ul>
 *   <li>{@code kraken_btc_usd_price} — Kraken last-trade price for XBTUSDT pair
 *       (Kraken uses XBT as BTC ticker for compliance reasons). Source:
 *       {@code result.XBTUSDT.c[0]}.</li>
 * </ul>
 *
 * <h2>Endpoint</h2>
 * {@code GET /0/public/Ticker?pair=XBTUSDT} — returns
 * {@code {"error":[],"result":{"XBTUSDT":{"a":[ask,...],"b":[bid,...],
 * "c":[lastPrice,lastVol]}}}}.
 *
 * <p>Cache 5 min — Kraken's public endpoint has no published rate limit but
 * be polite.
 */
@Slf4j
@Service
public class KrakenPublicService {

    private static final String TICKER_URL =
            "https://api.kraken.com/0/public/Ticker?pair=XBTUSDT";
    private static final long CACHE_TTL_MS = 5L * 60L * 1000L;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();

    public KrakenPublicService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Kraken BTC/USDT last-trade price, in USD (e.g. 77397.20). */
    public Double getBtcUsdPrice() {
        return cached("btc_usd", this::fetchBtcLastPrice);
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

    private Double fetchBtcLastPrice() {
        try (Response resp = HTTP.newCall(new Request.Builder().url(TICKER_URL).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Kraken] /Ticker HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode err = root.get("error");
            if (err != null && err.isArray() && !err.isEmpty()) {
                log.warn("[Kraken] error: {}", err);
                return null;
            }
            JsonNode pair = root.path("result").path("XBTUSDT");
            JsonNode c = pair.get("c");
            if (c == null || !c.isArray() || c.isEmpty()) {
                log.warn("[Kraken] XBTUSDT.c missing");
                return null;
            }
            String priceStr = c.get(0).asText("");
            if (priceStr.isBlank()) return null;
            return Double.parseDouble(priceStr);
        } catch (Exception e) {
            log.warn("[Kraken] fetch error: {}", e.getMessage());
            return null;
        }
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
}
