package com.agora.service.ml;

import com.agora.model.MdKline;
import com.agora.service.backtest.EntryFeatureSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SHADOW-mode ML inference logger.
 *
 * <p>Hooks into {@code LiveSignalEvaluator} at the BUY/SELL signal point. For
 * every emitted signal we:
 * <ol>
 *   <li>Look up the currently PROMOTED model_version_id for {@code signal_scorer}
 *       (cached, refreshed on a TTL — promotion changes rare).</li>
 *   <li>Compute the V047 + V049 entry-feature snapshot from the same kline
 *       series the strategy saw (HTF V050 features stay NULL until live HTF
 *       wiring is added — HW handles missing features as null).</li>
 *   <li>Async call {@code MlTrainingOrchestrator.predictOne} to get P(win).</li>
 *   <li>Insert into {@code ml_inference_log} with {@code SHADOW_PASS} (p_win ≥
 *       threshold) or {@code SHADOW_BLOCK}. Decision is observational only —
 *       the live trade goes through regardless.</li>
 * </ol>
 *
 * <h3>Why async + try/catch everything</h3>
 * The signal evaluation path is high-frequency (every WS kline close × strategy).
 * Any failure here MUST NOT block the trade — log warn and continue. HeatWave
 * predict can occasionally hit 1-2s latency; running it on the trading thread
 * would block other strategies.
 *
 * <h3>Disabled-by-default safety</h3>
 * Controlled by {@code meta-control.ml-shadow.enabled} (default false). Set
 * true to enable ML inference logging without redeployment.
 */
@Slf4j
@Service
@EnableAsync
@RequiredArgsConstructor
public class MlInferenceLogger {

    /** Cache PROMOTED model lookup for this many ms (60s). Promotion changes are rare. */
    private static final long PROMOTED_CACHE_TTL_MS = 60_000;
    /** Default decision threshold — picks p_win ≥ 0.5 as SHADOW_PASS. */
    private static final double DEFAULT_THRESHOLD = 0.5;

    /**
     * Sentiment feature cache TTL (30 min). Fear & Greed updates daily; whale / funding
     * update every hour. 30 min balances freshness vs extra DB round-trips.
     */
    private static final long SENT_CACHE_TTL_MS = 30 * 60 * 1_000L;

    private final JdbcTemplate jdbc;
    private final MlTrainingOrchestrator orchestrator;
    private final ObjectMapper objectMapper;
    private final RegimeClassifier regimeClassifier;
    private final com.agora.config.properties.MlShadowProperties props;

    /** Cached lookup of {modelVersionId, heatwaveHandle, cachedAtMs}. Null = no PROMOTED. */
    private final AtomicReference<PromotedRef> cached = new AtomicReference<>(null);

    /**
     * Per-symbol sentiment feature cache: fear_greed + whale_buy_ratio + funding_rate.
     * Queried from market_indicator_history; refreshed every {@value #SENT_CACHE_TTL_MS} ms.
     * An empty map is cached on query failure (negative cache) to avoid hammering the DB.
     */
    private final ConcurrentHashMap<String, SentCache> sentCache = new ConcurrentHashMap<>();

    /**
     * Fire ML inference + log result asynchronously. NEVER blocks the caller.
     * If anything fails (no PROMOTED model, HW timeout, parse error) — log and
     * swallow.
     *
     * @param strategyId    bt_strategy.id
     * @param symbol        e.g. "BTCUSDT"
     * @param intervalCode  e.g. "1h"
     * @param side          "LONG" or "SHORT"
     * @param klines        same-TF kline series strategy saw (chronological asc)
     * @param lastIndex     bar index where signal fired (typically klines.size()-1)
     * @param liveSignalId  optional FK back to bt_live_signal.id (may be null at
     *                      signal-emit point; can be backfilled later via JOIN
     *                      on (predicted_at, symbol, side, strategy_id))
     */
    @Async
    public void logShadow(long strategyId, String symbol, String intervalCode, String side,
                          List<MdKline> klines, int lastIndex, Long liveSignalId) {
        if (!props.enabled()) return;
        try {
            PromotedRef ref = getPromotedRef();
            if (ref == null) {
                // No PROMOTED model — silent skip (debug log only to avoid noise)
                log.debug("[MlShadow] no PROMOTED model for {} — skip inference", props.modelName());
                return;
            }

            // Build feature map matching vw_signal_training_v5_dedup columns
            Map<String, Object> features = buildFeatures(strategyId, symbol, intervalCode, side, klines, lastIndex);

            String predJson;
            try {
                predJson = orchestrator.predictOne(ref.heatwaveHandle, features);
            } catch (Exception predErr) {
                log.warn("[MlShadow] predict failed for v{} symbol={}: {}",
                        ref.modelVersionId, symbol, predErr.getMessage());
                return;
            }

            Double pWin = extractPWin(predJson);
            String decision = (pWin != null && pWin >= props.threshold()) ? "SHADOW_PASS" : "SHADOW_BLOCK";

            // Classify market regime for this inference (V069)
            MarketRegime regime = regimeClassifier.classify(symbol, features);

            String featuresJson;
            try {
                featuresJson = objectMapper.writeValueAsString(features);
            } catch (Exception jsonErr) {
                featuresJson = "{}";
            }

            try {
                jdbc.update(
                        "INSERT INTO ml_inference_log "
                                + "(model_version_id, predicted_at, live_signal_id, score, decision, regime, features_json) "
                                + "VALUES (?, NOW(6), ?, ?, ?, ?, CAST(? AS JSON))",
                        ref.modelVersionId, liveSignalId, pWin, decision, regime.dbValue(), featuresJson);
                log.info("[MlShadow] v{} {} {} {} → p_win={} decision={} regime={}",
                        ref.modelVersionId, symbol, intervalCode, side, pWin, decision, regime);
            } catch (Exception dbErr) {
                log.warn("[MlShadow] insert failed: {}", dbErr.getMessage());
            }

        } catch (Throwable t) {
            // Belt-and-braces — must not propagate to live signal flow
            log.warn("[MlShadow] outer catch: {}", t.getMessage());
        }
    }

    /**
     * Build feature map matching {@code vw_signal_training_v6_dedup} (V068) columns,
     * minus row_id / entry_time / target_return / replica_count / profitable
     * (which are excluded from training; HeatWave ignores extras not in its training set).
     *
     * <h3>Sentiment features (V070)</h3>
     * {@code fear_greed}, {@code whale_buy_ratio}, {@code funding_rate} are now appended
     * to every inference row so they are captured in {@code features_json}.  The current
     * PROMOTED model (v13, trained on v6_dedup) did <em>not</em> include these columns,
     * so HeatWave silently ignores them during prediction — no ML003011 error.  When the
     * training source switches to {@code ml_inference_log} (Phase 2, ≥ 200 actual_outcomes)
     * the next retrain will naturally include these macro signals as first-class features.
     *
     * <h3>V050 HTF features</h3>
     * htf_* features are NOT computed here yet — the live engine doesn't load 4h klines
     * in this path. Sent as null; HW imputes missing values.
     */
    private Map<String, Object> buildFeatures(long strategyId, String symbol, String intervalCode,
                                                String side, List<MdKline> klines, int lastIndex) {
        Map<String, Object> f = new HashMap<>();
        // Static / categorical
        f.put("strategy_id", strategyId);
        f.put("is_short", "SHORT".equalsIgnoreCase(side) ? 1 : 0);
        f.put("is_btc", "BTCUSDT".equalsIgnoreCase(symbol) ? 1 : 0);
        f.put("is_1h", "1h".equalsIgnoreCase(intervalCode) ? 1 : 0);
        // Entry context from current bar
        if (lastIndex >= 0 && lastIndex < klines.size()) {
            MdKline bar = klines.get(lastIndex);
            f.put("entry_price", bar.getClosePrice().doubleValue());
            LocalDateTime t = bar.getOpenTime();
            f.put("hour_of_day", t.getHour());
            // MySQL DAYOFWEEK: 1=Sunday, 2=Monday, ..., 7=Saturday
            // Java DayOfWeek.getValue(): 1=Monday, ..., 7=Sunday
            // Match MySQL convention since training data was MySQL-derived
            int javaDow = t.getDayOfWeek().getValue();  // 1=Mon..7=Sun
            int mysqlDow = (javaDow == 7) ? 1 : javaDow + 1;  // 1=Sun..7=Sat
            f.put("day_of_week", mysqlDow);
        }
        // V047 + V049 features from kline series (HTF features absent — live engine doesn't load HTF yet)
        Map<String, Double> snap = EntryFeatureSnapshot.compute(klines, lastIndex);
        for (Map.Entry<String, Double> e : snap.entrySet()) {
            if (e.getValue() != null) f.put(e.getKey(), e.getValue());
        }
        // HW ML003011: input columns must strictly match trained columns. Backfill
        // any missing V050 HTF keys (live engine writes nulls for those) and any
        // V049 keys that EntryFeatureSnapshot omitted (insufficient history) with null.
        for (String k : EntryFeatureSnapshot.ALL_FEATURE_KEYS) {
            f.putIfAbsent(k, null);
        }
        for (String k : EntryFeatureSnapshot.STATIC_FEATURE_KEYS) {
            f.putIfAbsent(k, null);
        }
        // V070: Market sentiment indicators — stored in features_json for future training.
        // HeatWave ignores keys not in its training column set (no ML003011 for extras).
        // V072: Added btc_open_interest, oi_change_pct_1h, btc_dominance_pct.
        // V073: Added FRED U.S. macro — us_10y_yield, us_fed_funds_rate, us_dxy, us_breakeven_10y.
        // V074: Added Etherscan on-chain — usdt_supply_b, usdc_supply_b,
        //       stablecoin_supply_b, stablecoin_supply_change_pct_24h, eth_gas_gwei.
        // V075: Added mempool.space + DefiLlama (no-key public APIs):
        //       btc_mempool_count/vsize_mb/fast_fee_sat_vb/hashrate_eh,
        //       defi_tvl_total_b, stablecoin_total_mcap_b.
        // V076: Added CoinGecko Demo-tier — btc_treasury_holdings_kbtc,
        //       btc_treasury_dominance_pct, alt_breadth_24h_pct.
        // V077: Added DefiLlama /protocols categorical TVL —
        //       defi_tvl_cex_b, defi_tvl_lending_b, defi_tvl_restaking_b.
        // V078: Added DEX perps + macro hedge + chain stats —
        //       hyperliquid_btc_oi, hyperliquid_btc_funding_hr_pct, dydx_btc_oi,
        //       gold_price_usd, btc_supply_circulating_m, btc_block_time_avg_min.
        // V079: Added independent 3rd-party BTC price feeds —
        //       pyth_btc_usd_price (oracle), kraken_btc_usd_price (3rd CEX).
        // V080: Added Alchemy Ethereum block activity —
        //       eth_block_tx_count, eth_block_gas_used_pct.
        // V081: Added options vol + equity macro + multi-chain stablecoin —
        //       btc_dvol, us_vix, us_sp500, us_nasdaq,
        //       usdt_supply_polygon_b, usdt_supply_arbitrum_b.
        Map<String, Double> sent = getSentimentFeatures(symbol);
        f.put("fear_greed",          sent.get("fear_greed"));
        f.put("whale_buy_ratio",       sent.get("whale_buy_ratio"));
        f.put("whale_buy_ratio_3h_ma", sent.get("whale_buy_ratio_3h_ma")); // #251 V082 3h MA
        f.put("funding_rate",          sent.get("funding_rate"));
        f.put("btc_open_interest",   sent.get("btc_open_interest"));
        f.put("oi_change_pct_1h",    sent.get("oi_change_pct_1h"));
        f.put("btc_dominance_pct",   sent.get("btc_dominance_pct"));
        f.put("us_10y_yield",        sent.get("us_10y_yield"));
        f.put("us_fed_funds_rate",   sent.get("us_fed_funds_rate"));
        f.put("us_dxy",              sent.get("us_dxy"));
        f.put("us_breakeven_10y",    sent.get("us_breakeven_10y"));
        f.put("usdt_supply_b",                     sent.get("usdt_supply_b"));
        f.put("usdc_supply_b",                     sent.get("usdc_supply_b"));
        f.put("stablecoin_supply_b",               sent.get("stablecoin_supply_b"));
        f.put("stablecoin_supply_change_pct_24h",  sent.get("stablecoin_supply_change_pct_24h"));
        f.put("eth_gas_gwei",                      sent.get("eth_gas_gwei"));
        f.put("btc_mempool_count",        sent.get("btc_mempool_count"));
        f.put("btc_mempool_vsize_mb",     sent.get("btc_mempool_vsize_mb"));
        f.put("btc_fast_fee_sat_vb",      sent.get("btc_fast_fee_sat_vb"));
        f.put("btc_hashrate_eh",          sent.get("btc_hashrate_eh"));
        f.put("defi_tvl_total_b",         sent.get("defi_tvl_total_b"));
        f.put("stablecoin_total_mcap_b",  sent.get("stablecoin_total_mcap_b"));
        f.put("btc_treasury_holdings_kbtc",  sent.get("btc_treasury_holdings_kbtc"));
        f.put("btc_treasury_dominance_pct",  sent.get("btc_treasury_dominance_pct"));
        f.put("alt_breadth_24h_pct",         sent.get("alt_breadth_24h_pct"));
        f.put("defi_tvl_cex_b",              sent.get("defi_tvl_cex_b"));
        f.put("defi_tvl_lending_b",          sent.get("defi_tvl_lending_b"));
        f.put("defi_tvl_restaking_b",        sent.get("defi_tvl_restaking_b"));
        f.put("hyperliquid_btc_oi",          sent.get("hyperliquid_btc_oi"));
        f.put("hyperliquid_btc_funding_hr_pct", sent.get("hyperliquid_btc_funding_hr_pct"));
        f.put("dydx_btc_oi",                 sent.get("dydx_btc_oi"));
        f.put("gold_price_usd",              sent.get("gold_price_usd"));
        f.put("btc_supply_circulating_m",    sent.get("btc_supply_circulating_m"));
        f.put("btc_block_time_avg_min",      sent.get("btc_block_time_avg_min"));
        f.put("pyth_btc_usd_price",          sent.get("pyth_btc_usd_price"));
        f.put("kraken_btc_usd_price",        sent.get("kraken_btc_usd_price"));
        f.put("eth_block_tx_count",          sent.get("eth_block_tx_count"));
        f.put("eth_block_gas_used_pct",      sent.get("eth_block_gas_used_pct"));
        f.put("btc_dvol",                    sent.get("btc_dvol"));
        f.put("us_vix",                      sent.get("us_vix"));
        f.put("us_sp500",                    sent.get("us_sp500"));
        f.put("us_nasdaq",                   sent.get("us_nasdaq"));
        f.put("usdt_supply_polygon_b",       sent.get("usdt_supply_polygon_b"));
        f.put("usdt_supply_arbitrum_b",      sent.get("usdt_supply_arbitrum_b"));
        // V083 mih_* aliases — HeatWave v18 (trained on vw_signal_training_v8_dedup) requires
        // these exact column names. Previous models (v13/v17) used plain names; HW ignores extras.
        f.put("mih_fear_greed",        sent.get("fear_greed"));
        f.put("mih_funding_rate",      sent.get("funding_rate"));
        f.put("mih_oi_change_pct_1h",  sent.get("oi_change_pct_1h"));
        f.put("mih_whale_buy_ratio",   sent.get("whale_buy_ratio"));
        f.put("mih_dex_wbtc_net_flow", sent.get("dex_wbtc_net_flow_usd_1h"));
        f.put("mih_us_10y_yield",      sent.get("us_10y_yield"));
        f.put("mih_us_vix",            sent.get("us_vix"));
        f.put("mih_btc_dvol",          sent.get("btc_dvol"));
        return f;
    }

    /**
     * Query the latest sentiment &amp; market-structure indicators for {@code symbol}
     * from {@code market_indicator_history}.  Results are cached per symbol for
     * {@value #SENT_CACHE_TTL_MS} ms to avoid per-inference DB hits.
     * Returns an empty map on failure (negative cache applied so the next query
     * waits for TTL expiry, avoiding hot-loop DB hammering).
     *
     * <p>V072 additions: {@code btc_open_interest}, {@code oi_change_pct_1h},
     * {@code btc_dominance_pct} written hourly by
     * {@link com.agora.scheduler.trading.MarketIndicatorHistoryCollector}.
     *
     * <p>V073 additions (FRED U.S. macro, daily values polled hourly):
     * {@code us_10y_yield}, {@code us_fed_funds_rate}, {@code us_dxy},
     * {@code us_breakeven_10y}.
     *
     * <p>V074 additions (Etherscan on-chain, hourly): {@code usdt_supply_b},
     * {@code usdc_supply_b}, {@code stablecoin_supply_b},
     * {@code stablecoin_supply_change_pct_24h}, {@code eth_gas_gwei}.
     *
     * <p>V075 additions (no-key public APIs): mempool.space BTC network —
     * {@code btc_mempool_count}, {@code btc_mempool_vsize_mb},
     * {@code btc_fast_fee_sat_vb}, {@code btc_hashrate_eh}; DefiLlama —
     * {@code defi_tvl_total_b}, {@code stablecoin_total_mcap_b}.
     */
    private Map<String, Double> getSentimentFeatures(String symbol) {
        long now = System.currentTimeMillis();
        SentCache entry = sentCache.get(symbol);
        if (entry != null && (now - entry.cachedAtMs) < SENT_CACHE_TTL_MS) {
            return entry.values();
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ mih.indicator, mih.value " +
                    "FROM market_indicator_history mih " +
                    "INNER JOIN (" +
                    "  SELECT indicator, MAX(captured_at) AS max_at " +
                    "  FROM market_indicator_history " +
                    "  WHERE symbol = ? AND indicator IN (" +
                    "    'fear_greed','whale_buy_ratio','funding_rate'," +
                    "    'btc_open_interest','oi_change_pct_1h','btc_dominance_pct'," +
                    "    'us_10y_yield','us_fed_funds_rate','us_dxy','us_breakeven_10y'," +
                    "    'usdt_supply_b','usdc_supply_b','stablecoin_supply_b'," +
                    "    'stablecoin_supply_change_pct_24h','eth_gas_gwei'," +
                    "    'btc_mempool_count','btc_mempool_vsize_mb'," +
                    "    'btc_fast_fee_sat_vb','btc_hashrate_eh'," +
                    "    'defi_tvl_total_b','stablecoin_total_mcap_b'," +
                    "    'btc_treasury_holdings_kbtc','btc_treasury_dominance_pct'," +
                    "    'alt_breadth_24h_pct'," +
                    "    'defi_tvl_cex_b','defi_tvl_lending_b','defi_tvl_restaking_b'," +
                    "    'hyperliquid_btc_oi','hyperliquid_btc_funding_hr_pct'," +
                    "    'dydx_btc_oi','gold_price_usd'," +
                    "    'btc_supply_circulating_m','btc_block_time_avg_min'," +
                    "    'pyth_btc_usd_price','kraken_btc_usd_price'," +
                    "    'eth_block_tx_count','eth_block_gas_used_pct'," +
                    "    'btc_dvol','us_vix','us_sp500','us_nasdaq'," +
                    "    'usdt_supply_polygon_b','usdt_supply_arbitrum_b'," +
                    "    'dex_wbtc_net_flow_usd_1h'" +
                    "  ) GROUP BY indicator" +
                    ") latest ON mih.indicator = latest.indicator AND mih.captured_at = latest.max_at " +
                    "WHERE mih.symbol = ?",
                    symbol, symbol);
            Map<String, Double> values = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String ind = (String) row.get("indicator");
                Object val = row.get("value");
                if (val != null) values.put(ind, ((Number) val).doubleValue());
            }
            sentCache.put(symbol, new SentCache(values, now));
            return values;
        } catch (Exception e) {
            log.warn("[MlShadow] sentiment query failed for {}: {}", symbol, e.getMessage());
            sentCache.put(symbol, new SentCache(Map.of(), now));  // negative cache
            return Map.of();
        }
    }

    /** Parse HW {@code ml_results.probabilities."1"} → P(win). Reuses logic from SignalScorerEnsemble. */
    private Double extractPWin(String predJson) {
        try {
            JsonNode root = objectMapper.readTree(predJson);
            JsonNode probs = root.path("ml_results").path("probabilities");
            if (probs.isMissingNode() || probs.isNull()) {
                probs = root.path("probabilities");
            }
            if (probs.isMissingNode() || probs.isNull()) return null;
            JsonNode p1 = probs.get("1");
            return p1 == null ? null : p1.asDouble();
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetch + cache the PROMOTED model_version_id + handle for the configured modelName. */
    private PromotedRef getPromotedRef() {
        PromotedRef cur = cached.get();
        long now = System.currentTimeMillis();
        if (cur != null && (now - cur.cachedAtMs) < PROMOTED_CACHE_TTL_MS) {
            return cur.modelVersionId == 0 ? null : cur;  // negative cache
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, heatwave_handle FROM ml_model_registry "
                            + "WHERE model_name = ? AND status = 'PROMOTED' LIMIT 1",
                    props.modelName());
            if (rows.isEmpty()) {
                cached.set(new PromotedRef(0, null, now));  // negative cache
                return null;
            }
            Map<String, Object> r = rows.get(0);
            long id = ((Number) r.get("id")).longValue();
            String handle = (String) r.get("heatwave_handle");
            if (handle == null || handle.isBlank()) {
                cached.set(new PromotedRef(0, null, now));
                return null;
            }
            // Pre-load the HW model (idempotent; warms HeatWave cluster cache)
            try { orchestrator.loadModel(handle); } catch (Exception ignored) {}
            PromotedRef fresh = new PromotedRef(id, handle, now);
            cached.set(fresh);
            return fresh;
        } catch (Exception e) {
            log.warn("[MlShadow] PROMOTED lookup failed: {}", e.getMessage());
            cached.set(new PromotedRef(0, null, now));
            return null;
        }
    }

    /** Manual cache invalidation hook (e.g. after promoteModel). */
    public void invalidateCache() {
        cached.set(null);
    }

    /**
     * After a {@code BtLiveSignal} is persisted, link its ID to the most-recent
     * {@code ml_inference_log} row for the same strategy that still has
     * {@code live_signal_id = NULL}. Uses a 60-second time window to avoid
     * matching stale rows from prior signals.
     *
     * <p>Called asynchronously from {@code LiveSignalEvaluator} immediately after
     * {@code liveSignalRepository.save(record)}. Swallows all errors.
     */
    @Async
    public void linkLiveSignal(long liveSignalId, long strategyId) {
        if (!props.enabled()) return;
        try {
            int rows = jdbc.update(
                    "UPDATE ml_inference_log "
                            + "SET live_signal_id = ? "
                            + "WHERE live_signal_id IS NULL AND actual_outcome IS NULL "
                            + "  AND predicted_at >= DATE_SUB(NOW(6), INTERVAL 60 SECOND) "
                            + "  AND JSON_EXTRACT(features_json, '$.strategy_id') = ? "
                            + "ORDER BY predicted_at DESC LIMIT 1",
                    liveSignalId, strategyId);
            if (rows > 0) {
                log.debug("[MlShadow] linked live_signal_id={} → ml_inference_log (strategyId={})",
                        liveSignalId, strategyId);
            }
        } catch (Exception e) {
            log.warn("[MlShadow] linkLiveSignal failed: {}", e.getMessage());
        }
    }

    /**
     * When a position closes, backfill {@code actual_outcome} and {@code actual_pnl}
     * in the matching {@code ml_inference_log} row (matched by {@code live_signal_id}).
     *
     * <p>{@code outcome}: 1 = profitable exit, 0 = loss.
     * {@code pnlPct}: signed decimal fraction (e.g. 0.031 = +3.1%).
     *
     * <p>Called asynchronously from {@code LiveSignalEvaluator.handleExitSignal}.
     * Swallows all errors — position closure is never blocked by this call.
     */
    @Async
    public void backfillOutcome(long liveSignalId, int outcome, double pnlPct) {
        try {
            int rows = jdbc.update(
                    "UPDATE ml_inference_log SET actual_outcome = ?, actual_pnl = ? "
                            + "WHERE live_signal_id = ?",
                    outcome, pnlPct, liveSignalId);
            if (rows > 0) {
                log.info("[MlShadow] backfill outcome={} pnl={}% for live_signal_id={}",
                        outcome, String.format("%.2f", pnlPct * 100), liveSignalId);
            }
        } catch (Exception e) {
            log.warn("[MlShadow] backfillOutcome failed: {}", e.getMessage());
        }
    }

    /**
     * Synchronous one-shot preview — build features from latest klines + predict + return
     * structured result. Used by MCP {@code previewMlFilter} tool for ad-hoc "what does
     * v-PROMOTED say right now?" queries. Does NOT write to {@code ml_inference_log}.
     *
     * @param strategyIdHint optional strategy_id to use as a feature (default 0)
     * @return result with versionId, pWin, decision, threshold, features echo; throws on
     *         config error, never on transient HW failure (returns result with error flag)
     */
    public PreviewResult previewSync(String symbol, String intervalCode, String side,
                                      long strategyIdHint,
                                      List<MdKline> klines, int lastIndex) {
        if (!props.enabled()) return PreviewResult.error("ML shadow disabled");
        PromotedRef ref = getPromotedRef();
        if (ref == null) return PreviewResult.error("no PROMOTED model for " + props.modelName());
        try {
            Map<String, Object> features = buildFeatures(strategyIdHint, symbol, intervalCode, side, klines, lastIndex);
            String predJson = orchestrator.predictOne(ref.heatwaveHandle, features);
            Double pWin = extractPWin(predJson);
            if (pWin == null) return PreviewResult.error("no p_win in HW response");
            String decision = pWin >= props.threshold() ? "PASS" : "BLOCK";
            return new PreviewResult(ref.modelVersionId, pWin, decision, props.threshold(), features, null);
        } catch (Exception e) {
            log.warn("[MlShadow] preview failed: {}", e.getMessage());
            return PreviewResult.error(e.getMessage());
        }
    }

    /** Result of {@link #previewSync}. errorMessage != null indicates a transient failure. */
    public record PreviewResult(long modelVersionId, Double pWin, String decision,
                                 double threshold, Map<String, Object> features,
                                 String errorMessage) {
        static PreviewResult error(String msg) {
            return new PreviewResult(0L, null, "ERROR", 0.0, Map.of(), msg);
        }
    }

    /**
     * Synchronous ML pre-trade gate check — called from {@code LiveSignalEvaluator} when
     * strategy config has {@code mlGateEnabled=true} and an actual trade is about to fire
     * (i.e., {@code tradingProperties.isEnabled() && !notifyOnly}).
     *
     * <p>Unlike {@link #logShadow} (async, observational), this method:
     * <ol>
     *   <li>Calls {@code predictOne} <b>synchronously</b> (~1-2s HW latency — acceptable
     *       because this gate only fires on real trade entry, ~2-5× per month for active
     *       strategies).</li>
     *   <li>Updates the shadow log row for this {@code liveSignalId} from
     *       {@code SHADOW_PASS/BLOCK} → {@code GATE_PASS/GATE_BLOCK}.</li>
     *   <li>Returns a {@link GateResult} indicating whether the trade should proceed.</li>
     * </ol>
     *
     * <p><b>Failure mode: fail-open.</b> If no PROMOTED model, predict throws, or p_win
     * cannot be parsed → returns PASS so infrastructure issues never silence real signals.
     */
    /** Overload with explicit threshold override (0 = use global config threshold). */
    public GateResult gateCheck(long liveSignalId, String symbol, String intervalCode,
                                 String side, long strategyId,
                                 List<MdKline> klines, int lastIndex, double thresholdOverride) {
        double effective = thresholdOverride > 0 ? thresholdOverride : props.threshold();
        return gateCheckInternal(liveSignalId, symbol, intervalCode, side, strategyId,
                klines, lastIndex, effective);
    }

    public GateResult gateCheck(long liveSignalId, String symbol, String intervalCode,
                                 String side, long strategyId,
                                 List<MdKline> klines, int lastIndex) {
        return gateCheckInternal(liveSignalId, symbol, intervalCode, side, strategyId,
                klines, lastIndex, props.threshold());
    }

    private GateResult gateCheckInternal(long liveSignalId, String symbol, String intervalCode,
                                          String side, long strategyId,
                                          List<MdKline> klines, int lastIndex, double effectiveThreshold) {
        if (!props.enabled()) return GateResult.passDisabled();
        PromotedRef ref = getPromotedRef();
        if (ref == null) {
            log.debug("[MlGate] no PROMOTED model — pass-through for live_signal_id={}", liveSignalId);
            return GateResult.passDisabled();
        }
        try {
            Map<String, Object> features = buildFeatures(strategyId, symbol, intervalCode, side, klines, lastIndex);
            String predJson = orchestrator.predictOne(ref.heatwaveHandle, features);
            Double pWin = extractPWin(predJson);
            if (pWin == null) {
                log.warn("[MlGate] no p_win in HW response for {}/{} — fail-open", symbol, intervalCode);
                return new GateResult(true, -1.0, "no-pwin:pass");
            }
            boolean pass = pWin >= effectiveThreshold;
            String decision = pass ? "GATE_PASS" : "GATE_BLOCK";
            // Update the shadow row decision to reflect the gate verdict
            try {
                jdbc.update("UPDATE ml_inference_log SET decision = ?, score = ?, effective_threshold = ? WHERE live_signal_id = ?",
                        decision, pWin, effectiveThreshold, liveSignalId);
            } catch (Exception dbErr) {
                log.warn("[MlGate] update decision failed: {}", dbErr.getMessage());
            }
            log.info("[MlGate] {} {}/{} side={} p_win={} threshold={} live_signal_id={}",
                    decision, symbol, intervalCode, side, pWin, effectiveThreshold, liveSignalId);
            return new GateResult(pass, pWin, decision);
        } catch (Exception e) {
            log.warn("[MlGate] predictOne error for {}/{}: {} — fail-open", symbol, intervalCode, e.getMessage());
            return new GateResult(true, -1.0, "predict-error:pass");
        }
    }

    /** Result of {@link #gateCheck}. */
    public record GateResult(boolean pass, double pWin, String reason) {
        static GateResult passDisabled() { return new GateResult(true, -1.0, "disabled:pass"); }
    }

    /** Exposes the configured ML decision threshold (used for TG message formatting). */
    public double getThreshold() { return props.threshold(); }

    private record PromotedRef(long modelVersionId, String heatwaveHandle, long cachedAtMs) {}

    /** Cached sentiment indicators for a given symbol. {@code values} may be empty on query failure. */
    private record SentCache(Map<String, Double> values, long cachedAtMs) {}
}
