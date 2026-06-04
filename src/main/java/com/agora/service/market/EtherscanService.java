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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Etherscan V2 API client for on-chain Ethereum stablecoin supply + gas metrics.
 *
 * <p>Free tier: 5 calls/sec, 100 000 calls/day. We poll once per hour (well under
 * the cap) plus a 30-min in-memory cache.
 *
 * <h2>Why Ethereum stablecoin supply matters for BTC ML</h2>
 * USDT + USDC supply changes are a leading indicator of fiat-to-crypto liquidity
 * flow. Tether/Circle minting tokens means new dollars entering exchanges (bullish
 * onboarding); large redemptions (supply down) typically precede bear-market
 * deepening or de-risk events. Ethereum hosts the dominant supply share of both;
 * directional changes correlate across chains (TRON USDT moves the same way).
 *
 * <h2>Provided indicators</h2> (all written to {@code market_indicator_history}
 * with {@code symbol=BTCUSDT} once per hour by
 * {@link com.agora.scheduler.trading.MarketIndicatorHistoryCollector}):
 * <ul>
 *   <li>{@code usdt_supply_b} — USDT total supply on Ethereum, billions</li>
 *   <li>{@code usdc_supply_b} — USDC total supply on Ethereum, billions</li>
 *   <li>{@code stablecoin_supply_b} — USDT + USDC, billions</li>
 *   <li>{@code stablecoin_supply_change_pct_24h} — % delta vs the row 24h ago
 *       (computed by collector, not this service)</li>
 *   <li>{@code eth_gas_gwei} — current propose gas price in gwei (network demand
 *       proxy; high gas usually correlates with risk-on activity)</li>
 * </ul>
 *
 * <h2>Decimal handling</h2>
 * USDT and USDC are both 6-decimal ERC-20 tokens, so raw supply ÷ 10^6 → "tokens",
 * then ÷ 10^9 → "billions of tokens". One decimal place granularity is plenty for
 * features (supply moves at 0.1B+ per significant event).
 *
 * <h2>Note on chain coverage</h2>
 * V1 only — Ethereum mainnet (chainid=1). Tron USDT (~50 B) is excluded; if it
 * matters later we add TronGrid endpoint as a sister service.
 */
@Slf4j
@Service
public class EtherscanService {

    public static final String CONTRACT_USDT = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
    public static final String CONTRACT_USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
    /** V081 — Polygon (PoS) USDT (PoS), 6 decimals. */
    public static final String CONTRACT_USDT_POLYGON  = "0xc2132D05D31c914a87C6611C10748AEb04B58e8F";
    /** V081 — Arbitrum One USDT, 6 decimals. */
    public static final String CONTRACT_USDT_ARBITRUM = "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9";

    private static final String BASE_URL    = "https://api.etherscan.io/v2/api";
    private static final String CHAIN_ETH   = "1";       // Ethereum mainnet
    private static final String CHAIN_POLY  = "137";     // Polygon (PoS)
    private static final String CHAIN_ARB   = "42161";   // Arbitrum One
    private static final BigDecimal SCALE_BILLION_6DEC = new BigDecimal("1000000000000000");  // 10^15
    private static final long CACHE_TTL_MS = 30L * 60L * 1000L;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;

    @Value("${external.etherscan.api-key:}")
    private String apiKey;

    /** key → (value, fetchedAtMs); negative result also cached. */
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    public EtherscanService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── public getters ────────────────────────────────────────────────────────

    /** USDT total supply on Ethereum, billions of tokens (e.g. 99.08). */
    public Double getUsdtSupplyBillions() {
        return cached("usdt_supply_b", () -> tokenSupplyBillions(CONTRACT_USDT));
    }

    /** USDC total supply on Ethereum, billions of tokens (e.g. 54.81). */
    public Double getUsdcSupplyBillions() {
        return cached("usdc_supply_b", () -> tokenSupplyBillions(CONTRACT_USDC));
    }

    /** ETH gas oracle "propose" price in gwei (median realistic gas to land). */
    public Double getEthGasGwei() {
        return cached("eth_gas_gwei", this::fetchProposeGas);
    }

    /** V081 — USDT total supply on Polygon (PoS), billions. */
    public Double getUsdtSupplyPolygonBillions() {
        return cached("usdt_supply_polygon_b",
                () -> tokenSupplyBillions(CHAIN_POLY, CONTRACT_USDT_POLYGON));
    }

    /** V081 — USDT total supply on Arbitrum One, billions. */
    public Double getUsdtSupplyArbitrumBillions() {
        return cached("usdt_supply_arbitrum_b",
                () -> tokenSupplyBillions(CHAIN_ARB, CONTRACT_USDT_ARBITRUM));
    }

    /**
     * Bulk fetch — keys match the indicator names actually written to
     * {@code market_indicator_history}. Used by the MCP tool for one-shot view.
     * Note: {@code stablecoin_supply_change_pct_24h} is omitted — that one is
     * computed by the collector against the previous DB row.
     */
    public Map<String, Double> getAllOnchainIndicators() {
        Map<String, Double> out = new LinkedHashMap<>();
        Double usdt = getUsdtSupplyBillions();
        Double usdc = getUsdcSupplyBillions();
        out.put("usdt_supply_b", usdt);
        out.put("usdc_supply_b", usdc);
        out.put("stablecoin_supply_b",
                (usdt != null && usdc != null) ? usdt + usdc : null);
        out.put("eth_gas_gwei", getEthGasGwei());
        return out;
    }

    // ── implementation ────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Fetcher { Double fetch(); }

    private Double cached(String key, Fetcher fn) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("[Etherscan] api-key not configured — skipping {}", key);
            return null;
        }
        long now = System.currentTimeMillis();
        CachedValue hit = cache.get(key);
        if (hit != null && (now - hit.fetchedAtMs) < CACHE_TTL_MS) {
            return hit.value;
        }
        Double fresh = fn.fetch();
        cache.put(key, new CachedValue(fresh, now));
        return fresh;
    }

    /** Eth-mainnet overload — most callers want this. */
    private Double tokenSupplyBillions(String contractAddress) {
        return tokenSupplyBillions(CHAIN_ETH, contractAddress);
    }

    /**
     * Call {@code action=tokensupply}; result is total raw units (string ≤ 78 digits
     * because 10^77 fits a uint256). Convert to billions assuming 6 decimals.
     * Chainid parameterized for V081 multi-chain support (Polygon/Arbitrum).
     */
    private Double tokenSupplyBillions(String chainid, String contractAddress) {
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("chainid", chainid)
                .addQueryParameter("module", "stats")
                .addQueryParameter("action", "tokensupply")
                .addQueryParameter("contractaddress", contractAddress)
                .addQueryParameter("apikey", apiKey)
                .build();
        try (Response resp = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Etherscan] tokenSupply chainid={} HTTP {} for {}", chainid, resp.code(), contractAddress);
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            if (!"1".equals(root.path("status").asText())) {
                log.warn("[Etherscan] tokenSupply chainid={} error for {}: {}",
                        chainid, contractAddress, root.path("result").asText());
                return null;
            }
            String raw = root.path("result").asText("");
            if (raw.isBlank()) return null;
            BigInteger rawSupply = new BigInteger(raw);
            BigDecimal billions = new BigDecimal(rawSupply)
                    .divide(SCALE_BILLION_6DEC, 4, RoundingMode.HALF_UP);
            return billions.doubleValue();
        } catch (Exception e) {
            log.warn("[Etherscan] tokenSupply error for {}: {}", contractAddress, e.getMessage());
            return null;
        }
    }

    /** Call {@code module=gastracker&action=gasoracle}; pull {@code ProposeGasPrice}. */
    private Double fetchProposeGas() {
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("chainid", CHAIN_ETH)
                .addQueryParameter("module", "gastracker")
                .addQueryParameter("action", "gasoracle")
                .addQueryParameter("apikey", apiKey)
                .build();
        try (Response resp = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Etherscan] gasoracle HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            if (!"1".equals(root.path("status").asText())) {
                log.warn("[Etherscan] gasoracle error: {}", root.path("result").asText());
                return null;
            }
            JsonNode result = root.path("result");
            String propose = result.path("ProposeGasPrice").asText("");
            if (propose.isBlank()) return null;
            return Double.parseDouble(propose);
        } catch (Exception e) {
            log.warn("[Etherscan] gasoracle error: {}", e.getMessage());
            return null;
        }
    }

    private record CachedValue(Double value, long fetchedAtMs) {}
}
