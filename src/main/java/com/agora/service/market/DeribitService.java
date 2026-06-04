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
 * Deribit public REST API client — BTC options market data. <b>No API key required.</b>
 *
 * <p>Deribit holds 90%+ of BTC options open interest. Their DVOL index is the
 * crypto equivalent of equities' VIX — a single number capturing the market's
 * forward-looking implied volatility expectation derived from the full BTC
 * option chain.
 *
 * <h2>Why DVOL matters for ML</h2>
 * Options market makers price expected volatility. When DVOL is rising, the
 * options market is paying up for protection — historically a leading
 * indicator for spot moves (in either direction). DVOL ↑ alongside spot ↓ =
 * panic; DVOL ↑ alongside spot flat = stealth accumulation of upside calls.
 * None of our existing 37 indicators capture this options-market view.
 *
 * <h2>Provided indicator</h2>
 * <ul>
 *   <li>{@code btc_dvol} — Deribit BTC volatility index (annualized %, e.g.
 *       40.0 = 40% annualized implied vol). Source:
 *       {@code GET /api/v2/public/get_volatility_index_data?currency=BTC&...}.
 *       Returns OHLC tuples; we take the most recent {@code close} value.</li>
 * </ul>
 *
 * <h2>Endpoint shape</h2>
 * <pre>{@code
 *   {"jsonrpc":"2.0","result":{"data":[
 *     [timestamp_ms, open, high, low, close],
 *     ...
 *   ]}}
 * }</pre>
 *
 * <h2>Cache</h2>
 * 10-min in-memory. DVOL updates every minute upstream but hourly poll doesn't
 * need that resolution.
 */
@Slf4j
@Service
public class DeribitService {

    private static final String DVOL_URL_BASE =
            "https://www.deribit.com/api/v2/public/get_volatility_index_data";
    private static final String BOOK_SUMMARY_URL =
            "https://www.deribit.com/api/v2/public/get_book_summary_by_currency?currency=BTC&kind=option";
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;
    /** Poll a 24h window so we always have data even if Deribit had a brief gap. */
    private static final long WINDOW_MS = 24L * 60L * 60L * 1000L;
    /** 1h resolution candles inside the window. */
    private static final String RESOLUTION = "3600";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();

    public DeribitService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Latest BTC DVOL (annualized implied vol %, e.g. 40.0). */
    public Double getBtcDvol() {
        return cached("btc_dvol", this::fetchBtcDvol);
    }

    /**
     * BTC options Put/Call volume ratio (24h USD volume).
     * > 1.0 = put-heavy (市場在買保護，看跌傾向)
     * < 0.5 = call-heavy (市場樂觀，FOMO 傾向)
     * No API key required (Deribit public endpoint).
     */
    public Double getBtcPutCallRatio() {
        return cached("btc_put_call_ratio", this::fetchBtcPutCallRatio);
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

    /**
     * Fetch a 24h window of 1h DVOL candles, return the most recent close.
     * Each candle is a 5-element array: {@code [ts, open, high, low, close]}.
     */
    private Double fetchBtcDvol() {
        long endMs = System.currentTimeMillis();
        long startMs = endMs - WINDOW_MS;
        String url = DVOL_URL_BASE
                + "?currency=BTC"
                + "&start_timestamp=" + startMs
                + "&end_timestamp=" + endMs
                + "&resolution=" + RESOLUTION;
        try (Response resp = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Deribit] DVOL HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode data = root.path("result").path("data");
            if (!data.isArray() || data.isEmpty()) {
                log.warn("[Deribit] DVOL empty data array");
                return null;
            }
            // Last candle, close (index 4)
            JsonNode lastCandle = data.get(data.size() - 1);
            if (!lastCandle.isArray() || lastCandle.size() < 5) {
                log.warn("[Deribit] DVOL candle wrong shape: {}", lastCandle);
                return null;
            }
            JsonNode closeNode = lastCandle.get(4);
            if (closeNode == null || closeNode.isNull() || !closeNode.isNumber()) return null;
            return closeNode.asDouble();
        } catch (Exception e) {
            log.warn("[Deribit] DVOL error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetch put/call volume ratio from all active BTC option book summaries.
     * Sums volume_usd for puts (instrument ends in -P) and calls (ends in -C).
     * Returns null if API fails or total volume is 0.
     */
    private Double fetchBtcPutCallRatio() {
        try (Response resp = HTTP.newCall(
                new Request.Builder().url(BOOK_SUMMARY_URL).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Deribit] PutCallRatio HTTP {}", resp.code());
                return null;
            }
            JsonNode data = objectMapper.readTree(resp.body().string()).path("result");
            if (!data.isArray()) return null;
            double putVol = 0, callVol = 0;
            for (JsonNode item : data) {
                String name = item.path("instrument_name").asText("");
                double vol = item.path("volume_usd").asDouble(0);
                if (name.endsWith("-P")) putVol += vol;
                else if (name.endsWith("-C")) callVol += vol;
            }
            if (callVol <= 0) return null;
            return putVol / callVol;
        } catch (Exception e) {
            log.warn("[Deribit] PutCallRatio error: {}", e.getMessage());
            return null;
        }
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
}
