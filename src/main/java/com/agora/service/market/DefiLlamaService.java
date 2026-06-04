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
 * DefiLlama public API client — DeFi TVL and cross-chain stablecoin breadth.
 * <b>No API key required.</b>
 *
 * <h2>Provided indicators</h2> (all written to {@code market_indicator_history}
 * with {@code symbol=BTCUSDT} once per hour by
 * {@link com.agora.scheduler.trading.MarketIndicatorHistoryCollector}):
 * <ul>
 *   <li>{@code defi_tvl_total_b} — total value locked across all DeFi
 *       protocols, all chains, in billions USD. Broad risk-on liquidity dial:
 *       rising TVL = capital flowing into yield/leverage; falling TVL = de-risk.</li>
 *   <li>{@code stablecoin_total_mcap_b} — sum of all USD-pegged stablecoin
 *       market caps across <i>all chains</i> (USDT/USDC/DAI/TUSD/FDUSD/PYUSD/...).
 *       Supersedes the V074 Etherscan-only USDT+USDC metric for breadth — Tron,
 *       Solana, BSC etc. are folded in. Captures the true "fiat backing" total.</li>
 * </ul>
 *
 * <h2>Why both stablecoin metrics matter</h2>
 * <ul>
 *   <li>{@code stablecoin_supply_b} (V074, Ethereum-only) — tells you where the
 *       new dollars are landing. Eth-share rising = DEX/CeFi flow heavy on ETH.</li>
 *   <li>{@code stablecoin_total_mcap_b} (V075, all-chain) — tells you the global
 *       fiat float. Total rising regardless of chain = true bullish liquidity.</li>
 * </ul>
 *
 * <h2>Rate limits</h2>
 * No published cap; community guidance ≤ 1 req/sec sustained. Hourly collector
 * + 30-min in-memory cache stays well below.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code https://api.llama.fi/charts} — historical totalLiquidityUSD,
 *       last entry = current.</li>
 *   <li>{@code https://stablecoins.llama.fi/stablecoincharts/all} — historical
 *       totalCirculating.peggedUSD, last entry = current.</li>
 *   <li>{@code https://api.llama.fi/protocols} — V077. Full list of ~7400
 *       protocols with metadata + current TVL. Heavy (~7.8 MB) but cached
 *       30-min so collector hits at most twice/hour. Used to derive
 *       categorical TVL aggregates.</li>
 * </ul>
 *
 * <h2>Categorical TVL aggregates (V077)</h2>
 * One {@code /protocols} fetch fills a {@code category → totalTvlBillions}
 * map covering every category. Per-method getters read that shared map:
 * <ul>
 *   <li>{@link #getDefiTvlCexBillions()} — CEX category (Binance/Coinbase wallet
 *       aggregates). Falling = coins moving to self-custody = bullish historical.</li>
 *   <li>{@link #getDefiTvlLendingBillions()} — Aave/Compound. Rising = leverage
 *       cycle on; falling = de-risking.</li>
 *   <li>{@link #getDefiTvlRestakingBillions()} — EigenLayer + LRTs. Bull-cycle
 *       proxy (post-2024 phenomenon).</li>
 * </ul>
 *
 * <p>Returns {@code null} on any failure.
 */
@Slf4j
@Service
public class DefiLlamaService {

    private static final String TVL_CHARTS_URL =
            "https://api.llama.fi/charts";
    private static final String STABLECOIN_CHARTS_URL =
            "https://stablecoins.llama.fi/stablecoincharts/all";
    private static final String PROTOCOLS_URL =
            "https://api.llama.fi/protocols";
    private static final long CACHE_TTL_MS = 30L * 60L * 1000L;  // 30 minutes

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)  // /charts payload can be a few MB
            .build();

    private final ObjectMapper objectMapper;
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    /** Composite cache for /protocols categorical aggregates. One fetch fills all
     *  categories; individual getters read this shared map. {@code null} entry
     *  means the last fetch failed — soaked for the cache TTL. */
    private volatile Map<String, Double> categoryTvlBillions = null;
    private volatile long categoryFetchedAtMs = 0L;

    public DefiLlamaService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── public getters ────────────────────────────────────────────────────────

    /** Total DeFi TVL (all protocols, all chains), billions USD. */
    public Double getTotalDefiTvlBillions() {
        return cached("defi_tvl_b", this::fetchTotalTvlBillions);
    }

    /** Total USD-pegged stablecoin market cap (all chains, all issuers), billions. */
    public Double getTotalStablecoinMcapBillions() {
        return cached("stablecoin_mcap_b", this::fetchTotalStablecoinMcapBillions);
    }

    /** CEX category TVL (centralized exchange wallet aggregates), billions USD. */
    public Double getDefiTvlCexBillions() {
        return categoryTvlBillions("CEX");
    }

    /** Lending category TVL (Aave/Compound/etc), billions USD. */
    public Double getDefiTvlLendingBillions() {
        return categoryTvlBillions("Lending");
    }

    /** Restaking category TVL (EigenLayer + LRTs), billions USD. */
    public Double getDefiTvlRestakingBillions() {
        return categoryTvlBillions("Restaking");
    }

    public Map<String, Double> getAllDefiBreadthIndicators() {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("defi_tvl_total_b",         getTotalDefiTvlBillions());
        out.put("stablecoin_total_mcap_b",  getTotalStablecoinMcapBillions());
        out.put("defi_tvl_cex_b",           getDefiTvlCexBillions());
        out.put("defi_tvl_lending_b",       getDefiTvlLendingBillions());
        out.put("defi_tvl_restaking_b",     getDefiTvlRestakingBillions());
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

    /**
     * GET /charts → JSON array of {date, totalLiquidityUSD}. We take the last
     * element (most recent daily snapshot) and convert to billions.
     */
    private Double fetchTotalTvlBillions() {
        try (Response resp = HTTP.newCall(new Request.Builder()
                .url(TVL_CHARTS_URL).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[DefiLlama] /charts HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            if (!root.isArray() || root.isEmpty()) {
                log.warn("[DefiLlama] /charts empty array");
                return null;
            }
            JsonNode latest = root.get(root.size() - 1);
            JsonNode v = latest.get("totalLiquidityUSD");
            if (v == null || v.isNull() || !v.isNumber()) return null;
            return v.asDouble() / 1.0e9;
        } catch (Exception e) {
            log.warn("[DefiLlama] /charts error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * GET /stablecoincharts/all → JSON array of {date, totalCirculating: {peggedUSD, ...}}.
     * Last element's peggedUSD ÷ 1e9.
     */
    private Double fetchTotalStablecoinMcapBillions() {
        try (Response resp = HTTP.newCall(new Request.Builder()
                .url(STABLECOIN_CHARTS_URL).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[DefiLlama] stablecoincharts HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            if (!root.isArray() || root.isEmpty()) {
                log.warn("[DefiLlama] stablecoincharts empty array");
                return null;
            }
            JsonNode latest = root.get(root.size() - 1);
            JsonNode v = latest.path("totalCirculating").get("peggedUSD");
            if (v == null || v.isNull() || !v.isNumber()) return null;
            return v.asDouble() / 1.0e9;
        } catch (Exception e) {
            log.warn("[DefiLlama] stablecoincharts error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Look up a single category's TVL (billions USD) from the cached
     * {@code /protocols} aggregate. Triggers a refresh if the cache is empty
     * or older than {@value #CACHE_TTL_MS} ms. Returns {@code null} if the
     * category has zero protocols (or fetch failed).
     */
    private Double categoryTvlBillions(String category) {
        long now = System.currentTimeMillis();
        if (categoryTvlBillions == null || (now - categoryFetchedAtMs) > CACHE_TTL_MS) {
            refreshCategoryAggregates();
        }
        Map<String, Double> snap = categoryTvlBillions;
        return snap == null ? null : snap.get(category);
    }

    /**
     * Fetch {@code /protocols} once, sum {@code tvl} per category, populate
     * {@link #categoryTvlBillions} as a {@code category → billionsUSD} map.
     * Heavy (~7.8 MB JSON, ~30 MB JsonNode tree); behind 30-min cache.
     * On failure leaves the previous cache intact (best-effort).
     */
    private synchronized void refreshCategoryAggregates() {
        long now = System.currentTimeMillis();
        // Re-check inside synchronized block — another thread may have refreshed
        if (categoryTvlBillions != null && (now - categoryFetchedAtMs) <= CACHE_TTL_MS) {
            return;
        }
        try (Response resp = HTTP.newCall(new Request.Builder()
                .url(PROTOCOLS_URL).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[DefiLlama] /protocols HTTP {}", resp.code());
                return;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            if (!root.isArray() || root.isEmpty()) {
                log.warn("[DefiLlama] /protocols empty array");
                return;
            }
            Map<String, Double> sums = new java.util.HashMap<>();
            for (JsonNode protocol : root) {
                String category = protocol.path("category").asText("");
                if (category.isEmpty()) continue;
                JsonNode tvlNode = protocol.get("tvl");
                if (tvlNode == null || tvlNode.isNull() || !tvlNode.isNumber()) continue;
                double tvl = tvlNode.asDouble();
                if (tvl <= 0) continue;
                sums.merge(category, tvl, Double::sum);
            }
            // Convert to billions
            Map<String, Double> billions = new java.util.HashMap<>();
            for (Map.Entry<String, Double> e : sums.entrySet()) {
                billions.put(e.getKey(), e.getValue() / 1.0e9);
            }
            this.categoryTvlBillions = billions;
            this.categoryFetchedAtMs = now;
            log.debug("[DefiLlama] /protocols cached {} categories, top: {}",
                    billions.size(),
                    billions.entrySet().stream()
                            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                            .limit(3)
                            .toList());
        } catch (Exception e) {
            log.warn("[DefiLlama] /protocols error: {}", e.getMessage());
        }
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
}
