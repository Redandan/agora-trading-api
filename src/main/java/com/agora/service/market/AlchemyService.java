package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * Alchemy Ethereum mainnet RPC client — block-level chain activity metrics.
 *
 * <p>Free tier: 300M Compute Units/month, generous enough for hourly polling
 * (one {@code eth_getBlockByNumber} ≈ 16 CU, hourly = ~12K CU/month).
 *
 * <h2>Provided indicators</h2>
 * Both derived from a single {@code eth_getBlockByNumber("latest", false)} RPC
 * call (cached 60s); written to {@code market_indicator_history} with
 * {@code symbol=BTCUSDT} (BTC-only block, since these are macro Ethereum
 * activity signals not BTC-specific):
 * <ul>
 *   <li>{@code eth_block_tx_count} — number of transactions in the latest
 *       mined Ethereum block. Direct on-chain demand indicator. Typical 100-300
 *       under normal load; bursts to 400+ during airdrops, NFT mints,
 *       liquidation cascades.</li>
 *   <li>{@code eth_block_gas_used_pct} — {@code gasUsed / gasLimit * 100}.
 *       Block utilization percentage. Sustained &gt; 95% = network congested,
 *       fee market hot; &lt; 50% = quiet chain.</li>
 * </ul>
 *
 * <h2>Why these matter for ML</h2>
 * Stablecoin supply (V074/V075) tells you "dollars are queued"; Ethereum block
 * activity tells you "users are actually transacting". Combined with
 * {@code eth_gas_gwei} (V074, Etherscan gas oracle), this gives a 3-pronged
 * Ethereum demand picture: <i>price</i> (gas gwei), <i>utilization</i> (gas
 * used %), and <i>raw count</i> (tx count). Each captures a different
 * dimension of network demand.
 *
 * <h2>Why same-key handling</h2>
 * The user's Alchemy app must have ETH_MAINNET enabled in their dashboard.
 * If only the original chain (e.g. Stable Network) is enabled, RPC returns
 * "ETH_MAINNET is not enabled for this app" — handled as a null result here.
 *
 * <p>Returns {@code null} on any failure (no exception propagated).
 */
@Slf4j
@Service
public class AlchemyService {

    private static final String ETH_MAINNET_BASE = "https://eth-mainnet.g.alchemy.com/v2/";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final long CACHE_TTL_MS = 60L * 1000L;  // 1 minute

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;

    @Value("${external.alchemy.api-key:}")
    private String apiKey;

    /** Cached snapshot of the latest block — both indicators derive from one fetch. */
    private final AtomicReference<BlockSnapshot> snap = new AtomicReference<>();

    public AlchemyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Number of transactions in the latest mined block (e.g. 168). */
    public Double getEthBlockTxCount() {
        BlockSnapshot s = currentSnapshot();
        return s == null ? null : s.txCount;
    }

    /** Latest block gasUsed / gasLimit * 100 (e.g. 73.5). */
    public Double getEthBlockGasUsedPct() {
        BlockSnapshot s = currentSnapshot();
        return s == null ? null : s.gasUsedPct;
    }

    // ── implementation ────────────────────────────────────────────────────────

    /** Get cached snapshot or refresh if expired/missing. */
    private BlockSnapshot currentSnapshot() {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("[Alchemy] api-key not configured — skipping");
            return null;
        }
        long now = System.currentTimeMillis();
        BlockSnapshot existing = snap.get();
        if (existing != null && (now - existing.fetchedAtMs) < CACHE_TTL_MS) {
            return existing;
        }
        BlockSnapshot fresh = fetchLatestBlock(now);
        if (fresh != null) snap.set(fresh);
        return fresh;
    }

    /**
     * Single {@code eth_getBlockByNumber("latest", false)} call. {@code false}
     * means "transaction hashes only, not full objects" — keeps response small
     * (~5KB vs 1MB+ for full).
     */
    private BlockSnapshot fetchLatestBlock(long fetchedAtMs) {
        String url = ETH_MAINNET_BASE + apiKey;
        String body = "{\"id\":1,\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\","
                + "\"params\":[\"latest\",false]}";
        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response resp = HTTP.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[Alchemy] eth_getBlockByNumber HTTP {}", resp.code());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            // Alchemy may return text body for "ETH_MAINNET not enabled" — readTree handles
            // that case by reading whatever is parseable; check for "result" object.
            JsonNode result = root.get("result");
            if (result == null || result.isNull() || !result.isObject()) {
                JsonNode err = root.get("error");
                String msg = (err != null) ? err.toString() : root.toString();
                log.warn("[Alchemy] eth_getBlockByNumber no result: {}",
                        msg.length() > 200 ? msg.substring(0, 200) : msg);
                return null;
            }
            JsonNode txs = result.get("transactions");
            int txCount = (txs != null && txs.isArray()) ? txs.size() : 0;

            String gasUsedHex = result.path("gasUsed").asText("0x0");
            String gasLimitHex = result.path("gasLimit").asText("0x0");
            long gasUsed = parseHex(gasUsedHex);
            long gasLimit = parseHex(gasLimitHex);
            double gasUsedPct = (gasLimit > 0) ? (100.0 * gasUsed / gasLimit) : 0.0;

            BlockSnapshot s = new BlockSnapshot((double) txCount, gasUsedPct, fetchedAtMs);
            log.debug("[Alchemy] block tx_count={} gas_used_pct={}%",
                    txCount, String.format("%.2f", gasUsedPct));
            return s;
        } catch (Exception e) {
            log.warn("[Alchemy] eth_getBlockByNumber error: {}", e.getMessage());
            return null;
        }
    }

    private long parseHex(String hex) {
        if (hex == null || hex.isBlank() || !hex.startsWith("0x")) return 0L;
        try {
            return Long.parseUnsignedLong(hex.substring(2), 16);
        } catch (NumberFormatException nfe) {
            return 0L;
        }
    }

    private record BlockSnapshot(Double txCount, Double gasUsedPct, long fetchedAtMs) {}
}
