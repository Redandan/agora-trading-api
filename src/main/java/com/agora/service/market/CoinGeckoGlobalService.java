package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * CoinGecko API client for crypto macro / adoption metrics.
 *
 * <h2>Auth tier</h2>
 * Calls go through CoinGecko's <b>Demo</b> tier when {@code external.coingecko.demo-api-key}
 * is configured (10 000 calls/month, 30 calls/min). When the key is missing,
 * we fall back to the public no-key tier (rate-limited, prone to 429 spikes).
 * Authenticated calls are strongly preferred under sustained polling.
 *
 * <h2>Provided indicators</h2> (all written to {@code market_indicator_history}
 * with {@code symbol=BTCUSDT} once per hour by
 * {@link com.agora.scheduler.trading.MarketIndicatorHistoryCollector}):
 * <ul>
 *   <li>{@link #getBtcDominancePct()} — V072. BTC market-cap dominance %
 *       (e.g. 52.4 = 52.4%). Source: {@code /global}.</li>
 *   <li>{@link #getBtcTreasuryHoldingsKBtc()} — V076. Total BTC held by all
 *       publicly-traded companies, in <b>thousands of BTC</b> (e.g.
 *       {@code 1225.83} = 1,225,830 BTC). Scaled down to fit
 *       {@code market_indicator_history.value DECIMAL(12,6)}. Source:
 *       {@code /companies/public_treasury/bitcoin}.</li>
 *   <li>{@link #getBtcTreasuryDominancePct()} — V076. Public-company BTC
 *       holdings as % of total BTC supply (e.g. 5.84 = 5.84%). Same endpoint
 *       as above; cached as a tuple.</li>
 *   <li>{@link #getAltBreadth24hPct()} — V076. % of top-50 altcoins (BTC and
 *       USD-pegged stables excluded) with positive 24h price change. Captures
 *       risk-on / alt-season vs flight-to-BTC dynamics. Source:
 *       {@code /coins/markets}.</li>
 * </ul>
 *
 * <h2>Why these matter for ML</h2>
 * <ul>
 *   <li><b>BTC dominance</b>: high (≥55%) = risk-off flight-to-BTC; low (≤45%) =
 *       alt-season risk-on.</li>
 *   <li><b>Treasury holdings</b>: corporate adoption gauge. Fast accumulation
 *       (rising holdings) historically precedes bullish trend continuation.</li>
 *   <li><b>Alt breadth</b>: complement to dominance. Breadth low + dominance up
 *       = alts capitulating, BTC absorbing flow.</li>
 * </ul>
 *
 * <h2>Caching</h2>
 * 30-min in-memory cache per indicator. Treasury/alt-breadth endpoints are heavy
 * (~5KB / ~30KB respectively); cache prevents per-MCP-call hammering.
 */
@Slf4j
@Service
public class CoinGeckoGlobalService {

    private static final String GLOBAL_URL          = "https://api.coingecko.com/api/v3/global";
    private static final String TREASURY_URL        = "https://api.coingecko.com/api/v3/companies/public_treasury/bitcoin";
    private static final String COINS_MARKETS_URL   = "https://api.coingecko.com/api/v3/coins/markets";
    private static final long   CACHE_TTL_MS        = 30L * 60L * 1000L;

    /** Stablecoin ids excluded from alt-breadth (their 24h move is always ~0). */
    private static final Set<String> STABLECOIN_IDS = Set.of(
            "tether", "usd-coin", "dai", "first-digital-usd", "true-usd",
            "binance-usd", "paxos-standard", "frax", "usdd", "usds",
            "ethena-usde", "paypal-usd", "pyusd", "fdusd", "tusd"
    );

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;

    @Value("${external.coingecko.demo-api-key:}")
    private String demoApiKey;

    /** key → (value, fetchedAtMs); negative results also cached to soak failures. */
    private final java.util.Map<String, CachedDouble> cache = new ConcurrentHashMap<>();

    public CoinGeckoGlobalService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── public getters ────────────────────────────────────────────────────────

    /**
     * Return BTC market-cap dominance in percent (e.g. 52.4). {@code null} on failure.
     * Endpoint: {@code GET /api/v3/global}.
     */
    public Double getBtcDominancePct() {
        return cached("btc_dominance_pct", this::fetchBtcDominance);
    }

    /**
     * Total BTC held by all public-treasury companies, in <b>thousands of BTC</b>
     * (raw count ÷ 1000). Demo-tier endpoint. Scaled to fit DECIMAL(12,6).
     */
    public Double getBtcTreasuryHoldingsKBtc() {
        return cached("btc_treasury_holdings_kbtc", () -> {
            Double raw = fetchTreasury()[0];
            return raw == null ? null : raw / 1000.0;
        });
    }

    /** Public-treasury holdings as % of total BTC supply (e.g. 5.84). */
    public Double getBtcTreasuryDominancePct() {
        return cached("btc_treasury_dominance_pct", () -> fetchTreasury()[1]);
    }

    /**
     * % of top-50 altcoins (BTC + USD-pegged stablecoins excluded) with positive
     * 24h price change. Range 0-100. {@code null} on parse failure.
     */
    public Double getAltBreadth24hPct() {
        return cached("alt_breadth_24h_pct", this::fetchAltBreadth);
    }

    // ── implementation ────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface DoubleFetcher { Double fetch(); }

    private Double cached(String key, DoubleFetcher fn) {
        long now = System.currentTimeMillis();
        CachedDouble hit = cache.get(key);
        if (hit != null && (now - hit.fetchedAtMs) < CACHE_TTL_MS) {
            return hit.value;
        }
        Double fresh = fn.fetch();
        cache.put(key, new CachedDouble(fresh, now));
        return fresh;
    }

    /** Append demo key as query param when configured; no-op otherwise. */
    private HttpUrl.Builder withAuth(HttpUrl.Builder b) {
        if (demoApiKey != null && !demoApiKey.isBlank()) {
            b.addQueryParameter("x_cg_demo_api_key", demoApiKey);
        }
        return b;
    }

    private Double fetchBtcDominance() {
        HttpUrl url = withAuth(HttpUrl.parse(GLOBAL_URL).newBuilder()).build();
        try (Response resp = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[CoinGeckoGlobal] /global HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode btcPct = root.path("data").path("market_cap_percentage").get("btc");
            if (btcPct == null || btcPct.isNull()) {
                log.warn("[CoinGeckoGlobal] /global btc dominance missing");
                return null;
            }
            return btcPct.asDouble();
        } catch (Exception e) {
            log.warn("[CoinGeckoGlobal] /global error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Single fetch of {@code /companies/public_treasury/bitcoin}; returns
     * {@code [total_holdings_btc, market_cap_dominance_pct]}. The two cache
     * entries call this through {@link #cached} but the wasted second call
     * within the TTL window is fine (Demo tier has 10K/month).
     */
    private Double[] fetchTreasury() {
        HttpUrl url = withAuth(HttpUrl.parse(TREASURY_URL).newBuilder()).build();
        try (Response resp = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[CoinGeckoGlobal] treasury HTTP {}", resp.code());
                return new Double[]{null, null};
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode th = root.get("total_holdings");
            JsonNode md = root.get("market_cap_dominance");
            Double holdings = (th == null || th.isNull()) ? null : th.asDouble();
            Double dom = (md == null || md.isNull()) ? null : md.asDouble();
            return new Double[]{holdings, dom};
        } catch (Exception e) {
            log.warn("[CoinGeckoGlobal] treasury error: {}", e.getMessage());
            return new Double[]{null, null};
        }
    }

    /**
     * Fetch top 50 by market cap with 24h pct change, count alts (BTC + stables
     * excluded) where {@code price_change_percentage_24h > 0}, return ratio %.
     */
    private Double fetchAltBreadth() {
        HttpUrl url = withAuth(HttpUrl.parse(COINS_MARKETS_URL).newBuilder()
                .addQueryParameter("vs_currency", "usd")
                .addQueryParameter("order", "market_cap_desc")
                .addQueryParameter("per_page", "50")
                .addQueryParameter("page", "1")
                .addQueryParameter("price_change_percentage", "24h")
        ).build();
        try (Response resp = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[CoinGeckoGlobal] /coins/markets HTTP {}", resp.code());
                return null;
            }
            JsonNode arr = objectMapper.readTree(resp.body().string());
            if (!arr.isArray() || arr.isEmpty()) return null;
            int considered = 0;
            int up = 0;
            for (JsonNode coin : arr) {
                String id = coin.path("id").asText("");
                if ("bitcoin".equals(id) || STABLECOIN_IDS.contains(id)) continue;
                JsonNode chg = coin.get("price_change_percentage_24h");
                if (chg == null || chg.isNull()) continue;
                considered++;
                if (chg.asDouble() > 0) up++;
            }
            if (considered == 0) return null;
            return 100.0 * up / considered;
        } catch (Exception e) {
            log.warn("[CoinGeckoGlobal] /coins/markets error: {}", e.getMessage());
            return null;
        }
    }

    private record CachedDouble(Double value, long fetchedAtMs) {}
}
