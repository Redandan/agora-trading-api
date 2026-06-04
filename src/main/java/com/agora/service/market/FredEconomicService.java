package com.agora.service.market;

import com.agora.config.properties.FredProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Federal Reserve Economic Data (FRED) API client for U.S. macro indicators.
 *
 * <p>Free API by St. Louis Fed — register at <a href="https://fred.stlouisfed.org/docs/api/api_key.html">
 * fred.stlouisfed.org</a>. Free tier: 120 req/min (well above our once-per-hour usage).
 *
 * <h2>Provided indicators (all written to {@code market_indicator_history} as
 * symbol={@code BTCUSDT} once per hour by
 * {@link com.agora.scheduler.trading.MarketIndicatorHistoryCollector})</h2>
 * <ul>
 *   <li>{@link #getUs10yYield()} — {@code DGS10} 10-year U.S. Treasury yield (%);
 *       indicator key {@code us_10y_yield}. Risk-free rate proxy; rising yields
 *       pressure risk assets including BTC.</li>
 *   <li>{@link #getFedFundsRate()} — {@code DFF} Effective Federal Funds Rate (%);
 *       indicator key {@code us_fed_funds_rate}. Central liquidity dial.</li>
 *   <li>{@link #getDxy()} — {@code DTWEXBGS} Trade-Weighted U.S. Dollar Index
 *       (broad goods+services); indicator key {@code us_dxy}. Strong USD ↔ weak BTC.</li>
 *   <li>{@link #getBreakeven10y()} — {@code T10YIE} 10-year breakeven inflation (%);
 *       indicator key {@code us_breakeven_10y}. Captures inflation-hedge narrative.</li>
 * </ul>
 *
 * <h2>Data freshness</h2>
 * Most series are <b>daily, business-day-only</b>. Hourly polls return the same
 * value for many hours; an in-memory 30-minute cache further dampens API hits.
 * Weekend / U.S. holiday polls return Friday's value (or null if FRED is delayed).
 *
 * <h2>API shape</h2>
 * <pre>{@code
 *   GET https://api.stlouisfed.org/fred/series/observations
 *       ?series_id=DGS10&api_key=XXX&file_type=json
 *       &sort_order=desc&limit=1
 *
 *   { "observations": [{ "date":"2026-04-25", "value":"4.32", ... }] }
 * }</pre>
 * {@code value} can be the literal string {@code "."} when the value is not yet
 * published — treated as null.
 *
 * <p>Returns the previous successful cached value for transient provider
 * failures (HTTP 408/429/5xx or network exceptions) when available. Permanent
 * failures still return {@code null}; no exception is propagated to callers.
 * The collector guards each fetch in its own try/catch and skips null rows.
 */
@Slf4j
@Service
public class FredEconomicService {

    public static final String SERIES_US_10Y_YIELD     = "DGS10";
    public static final String SERIES_US_FED_FUNDS     = "DFF";
    public static final String SERIES_US_DXY           = "DTWEXBGS";
    public static final String SERIES_US_BREAKEVEN_10Y = "T10YIE";
    /** V081 additions — equity / vol indices (replace blocked Yahoo Finance). */
    public static final String SERIES_US_VIX           = "VIXCLS";    // CBOE Volatility Index, daily
    public static final String SERIES_US_SP500         = "SP500";     // S&P 500 close, daily
    public static final String SERIES_US_NASDAQ        = "NASDAQCOM"; // Nasdaq Composite, daily

    static final String DEFAULT_BASE_URL = "https://api.stlouisfed.org/fred/series/observations";
    private static final long CACHE_TTL_MS = 30L * 60L * 1000L;  // 30 minutes
    private static final int MAX_ATTEMPTS = 2;

    private static final OkHttpClient DEFAULT_HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;
    private final FredProperties props;
    private final OkHttpClient httpClient;
    private final String baseUrl;

    /** series_id → (value, fetchedAtMs); negative result also cached to avoid hammering on outage. */
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    @Autowired
    public FredEconomicService(ObjectMapper objectMapper, FredProperties props) {
        this(objectMapper, props, DEFAULT_HTTP, DEFAULT_BASE_URL);
    }

    FredEconomicService(ObjectMapper objectMapper, FredProperties props,
                        OkHttpClient httpClient, String baseUrl) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

    // ── public series getters ─────────────────────────────────────────────────

    public Double getUs10yYield()      { return fetchLatest(SERIES_US_10Y_YIELD); }
    public Double getFedFundsRate()    { return fetchLatest(SERIES_US_FED_FUNDS); }
    public Double getDxy()             { return fetchLatest(SERIES_US_DXY); }
    public Double getBreakeven10y()    { return fetchLatest(SERIES_US_BREAKEVEN_10Y); }
    /** V081: CBOE Volatility Index ("VIX") — equities fear gauge, BTC correlation 0.6+ since 2022. */
    public Double getUsVix()           { return fetchLatest(SERIES_US_VIX); }
    /** V081: S&P 500 daily close — equity risk regime. */
    public Double getUsSp500()         { return fetchLatest(SERIES_US_SP500); }
    /** V081: Nasdaq Composite daily close — tech-heavy index, often strongest BTC correlate. */
    public Double getUsNasdaq()        { return fetchLatest(SERIES_US_NASDAQ); }

    /**
     * Bulk fetch — returns a map keyed by indicator name (the names actually
     * written to {@code market_indicator_history}). Each value may be null.
     * Used by the MCP {@code getMacroIndicators} tool for a single overview call.
     */
    public Map<String, Double> getAllMacroIndicators() {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("us_10y_yield",       getUs10yYield());
        out.put("us_fed_funds_rate",  getFedFundsRate());
        out.put("us_dxy",             getDxy());
        out.put("us_breakeven_10y",   getBreakeven10y());
        out.put("us_vix",             getUsVix());
        out.put("us_sp500",           getUsSp500());
        out.put("us_nasdaq",          getUsNasdaq());
        return out;
    }

    // ── implementation ────────────────────────────────────────────────────────

    /**
     * Fetch the latest published observation for {@code seriesId}. Caches the
     * (possibly null) result for {@value #CACHE_TTL_MS} ms.
     */
    private Double fetchLatest(String seriesId) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            log.debug("[FRED] api-key not configured — skipping {}", seriesId);
            return null;
        }
        long now = System.currentTimeMillis();
        CachedValue hit = cache.get(seriesId);
        if (hit != null && (now - hit.fetchedAtMs) < CACHE_TTL_MS) {
            return hit.value;
        }
        FetchResult fresh = doFetch(seriesId);
        if (fresh.value != null) {
            cache.put(seriesId, new CachedValue(fresh.value, now));
            return fresh.value;
        }
        if (fresh.transientFailure && hit != null && hit.value != null) {
            log.info("[FRED_PROVIDER_TRANSIENT] {} using cached value after transient provider failure", seriesId);
            cache.put(seriesId, new CachedValue(hit.value, now));
            return hit.value;
        }
        cache.put(seriesId, new CachedValue(null, now));
        return null;
    }

    private FetchResult doFetch(String seriesId) {
        HttpUrl url = HttpUrl.parse(baseUrl).newBuilder()
                .addQueryParameter("series_id", seriesId)
                .addQueryParameter("api_key", props.apiKey())
                .addQueryParameter("file_type", "json")
                .addQueryParameter("sort_order", "desc")
                .addQueryParameter("limit", "1")
                .build();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (Response resp = httpClient.newCall(new Request.Builder().url(url).get().build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (isTransientStatus(resp.code())) {
                        log.info("[FRED_PROVIDER_TRANSIENT] {} HTTP {} attempt={}/{}",
                                seriesId, resp.code(), attempt, MAX_ATTEMPTS);
                        if (attempt < MAX_ATTEMPTS) continue;
                        return FetchResult.transientFailure();
                    }
                    log.warn("[FRED] {} HTTP {}", seriesId, resp.code());
                    return FetchResult.permanentFailure();
                }
                String body = resp.body().string();
                JsonNode root = objectMapper.readTree(body);
                JsonNode obs = root.path("observations");
                if (!obs.isArray() || obs.isEmpty()) {
                    log.warn("[FRED] {} empty observations: {}", seriesId, body);
                    return FetchResult.permanentFailure();
                }
                JsonNode first = obs.get(0);
                String valueStr = first.path("value").asText("");
                // FRED returns "." for unpublished values
                if (valueStr.isBlank() || ".".equals(valueStr)) {
                    log.debug("[FRED] {} value not yet published (date={})",
                            seriesId, first.path("date").asText());
                    return FetchResult.permanentFailure();
                }
                try {
                    double value = Double.parseDouble(valueStr);
                    log.debug("[FRED] {} = {} (date={})", seriesId, value, first.path("date").asText());
                    return FetchResult.success(value);
                } catch (NumberFormatException e) {
                    log.warn("[FRED] {} unparseable value '{}'", seriesId, valueStr);
                    return FetchResult.permanentFailure();
                }
            } catch (Exception e) {
                log.info("[FRED_PROVIDER_TRANSIENT] {} fetch error attempt={}/{}: {}",
                        seriesId, attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt >= MAX_ATTEMPTS) {
                    return FetchResult.transientFailure();
                }
            }
        }
        return FetchResult.transientFailure();
    }

    private boolean isTransientStatus(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
    private static final class FetchResult {
        private final Double value;
        private final boolean transientFailure;

        private FetchResult(Double value, boolean transientFailure) {
            this.value = value;
            this.transientFailure = transientFailure;
        }

        static FetchResult success(Double value) { return new FetchResult(value, false); }
        static FetchResult transientFailure() { return new FetchResult(null, true); }
        static FetchResult permanentFailure() { return new FetchResult(null, false); }
    }
}
