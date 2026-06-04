package com.agora.service.market;

import com.agora.config.properties.TheGraphProperties;
import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uniswap v3 WBTC/USDC pool — on-chain DEX flow signal.
 *
 * <p>Queries The Graph (decentralized network) for net WBTC buying pressure
 * in the past hour. Positive value = net buying (WBTC leaving pool);
 * negative = net selling. Unit: USD equivalent.
 *
 * <p>MEV filter: swaps below $10k USD are excluded to reduce sandwich-bot noise.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code external.thegraph.api-key} — The Graph Studio API key (required)</li>
 *   <li>{@code external.thegraph.uniswap-v3-subgraph-id} — subgraph deployment ID</li>
 * </ul>
 */
@Slf4j
@Service
public class UniswapDexFlowService {

    // WBTC/USDC 0.3% fee pool on Uniswap v3 Ethereum mainnet
    private static final String WBTC_USDC_POOL = "0x99ac8ca7087fa4a2a1fb6357269965a2014abc35";
    private static final double MIN_SWAP_USD = 10_000.0;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final TheGraphProperties props;

    private static final long BACKFILL_REQUEST_DELAY_MS = 150;
    public static final String INDICATOR = "dex_wbtc_net_flow_usd_1h";

    /** Prevents concurrent backfill runs. */
    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);

    private final ObjectMapper objectMapper;
    private final MarketIndicatorHistoryRepository historyRepo;
    private final ApplicationContext applicationContext;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    public UniswapDexFlowService(ObjectMapper objectMapper,
                                  MarketIndicatorHistoryRepository historyRepo,
                                  ApplicationContext applicationContext,
                                  TheGraphProperties props) {
        this.objectMapper = objectMapper;
        this.historyRepo = historyRepo;
        this.applicationContext = applicationContext;
        this.props = props;
    }

    /** Net WBTC flow for the past hour (called by MarketIndicatorHistoryCollector). */
    public Double getWbtcNetFlowUsd() {
        long now = Instant.now().getEpochSecond();
        return fetchForWindow(now - 3600, now);
    }

    /**
     * Non-blocking entry point for MCP tool — starts the backfill in a background
     * thread and returns immediately so the HTTP response completes within nginx
     * timeout. Monitor progress via getIndicatorHistory(indicator=dex_wbtc_net_flow_usd_1h).
     *
     * @return status message (started / already running / config missing)
     */
    public String startBackfillAsync(LocalDateTime from, LocalDateTime to) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return "❌ external.thegraph.api-key not configured";
        }
        if (!backfillRunning.compareAndSet(false, true)) {
            return "⚠️ Backfill already running — check getIndicatorHistory(indicator=dex_wbtc_net_flow_usd_1h) for progress";
        }
        long hours = ChronoUnit.HOURS.between(from, to);
        // Get proxy through ApplicationContext so @Async fires correctly
        applicationContext.getBean(UniswapDexFlowService.class).doBackfillAsync(from, to);
        return String.format(
                "✅ Backfill started in background\n" +
                "範圍：%s → %s (%d 小時)\n" +
                "預估耗時：%.0f 分鐘\n\n" +
                "監控進度：getIndicatorHistory(symbol=BTCUSDT, indicator=dex_wbtc_net_flow_usd_1h, hours=2160)\n" +
                "完成後可設定 OiFundingDivergenceStrategy.dexFlowFilter=true 進行回測。",
                from.toLocalDate(), to.toLocalDate(), hours,
                hours * BACKFILL_REQUEST_DELAY_MS / 60_000.0);
    }

    /**
     * Actual backfill logic — runs in Spring's async executor thread.
     * Writes hourly {@code dex_wbtc_net_flow_usd_1h} rows idempotently.
     */
    @Async
    public void doBackfillAsync(LocalDateTime from, LocalDateTime to) {
        LocalDateTime cursor = from.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime end    = to.truncatedTo(ChronoUnit.HOURS);
        long t0 = System.currentTimeMillis();
        int imported = 0, skipped = 0, errors = 0;
        try {
            while (!cursor.isAfter(end)) {
                if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt("BTCUSDT", INDICATOR, cursor)) {
                    skipped++;
                    cursor = cursor.plusHours(1);
                    continue;
                }
                long fromTs = cursor.toEpochSecond(ZoneOffset.UTC);
                long toTs   = fromTs + 3600;
                try {
                    Double value = fetchForWindow(fromTs, toTs);
                    if (value != null) {
                        MarketIndicatorHistory row = new MarketIndicatorHistory();
                        row.setCapturedAt(cursor);
                        row.setSymbol("BTCUSDT");
                        row.setIndicator(INDICATOR);
                        row.setValue(BigDecimal.valueOf(value));
                        historyRepo.save(row);
                        imported++;
                    } else {
                        errors++;
                    }
                    Thread.sleep(BACKFILL_REQUEST_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("[UniswapDex] backfill error at {}: {}", cursor, e.getMessage());
                    errors++;
                }
                cursor = cursor.plusHours(1);
            }
            long elapsed = System.currentTimeMillis() - t0;
            log.info("[UniswapDex] Backfill done: imported={} skipped={} errors={} elapsed={}s",
                    imported, skipped, errors, elapsed / 1000);
        } finally {
            backfillRunning.set(false);
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    /** Public for DexFlowBackfillRunner — queries one hour window from The Graph. */
    public Double fetchForWindow(long fromTs, long toTs) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            log.warn("[UniswapDex] external.thegraph.api-key not configured — skipping");
            return null;
        }

        String gql = String.format(
                "{ swaps(first: 1000 where: { pool: \"%s\" timestamp_gte: %d timestamp_lt: %d }) " +
                "{ amount0 amountUSD } }",
                WBTC_USDC_POOL, fromTs, toTs);

        try {
            ObjectNode reqBody = objectMapper.createObjectNode();
            reqBody.put("query", gql);
            String bodyStr = objectMapper.writeValueAsString(reqBody);

            String endpoint = "https://gateway.thegraph.com/api/" + props.apiKey()
                    + "/subgraphs/id/" + props.uniswapV3SubgraphId();

            Request request = new Request.Builder()
                    .url(endpoint)
                    .post(RequestBody.create(bodyStr, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[UniswapDex] Graph API returned {}", response.code());
                    return null;
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode swaps = root.path("data").path("swaps");
                if (!swaps.isArray() || swaps.isEmpty()) return 0.0;

                double netFlowUsd = 0.0;
                for (JsonNode swap : swaps) {
                    double amountUsd = swap.path("amountUSD").asDouble(0);
                    if (amountUsd < MIN_SWAP_USD) continue;
                    double amount0 = swap.path("amount0").asDouble(0);
                    netFlowUsd += amount0 < 0 ? amountUsd : -amountUsd;
                }
                return netFlowUsd;
            }
        } catch (Exception e) {
            log.warn("[UniswapDex] fetch failed [{}-{}]: {}", fromTs, toTs, e.getMessage());
            return null;
        }
    }
}
