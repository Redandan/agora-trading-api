package com.agora.scheduler.trading;

import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.market.AlchemyService;
import com.agora.service.market.BinanceFuturesService;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.market.CoinalyzeService;
import com.agora.service.market.UniswapDexFlowService;
import com.agora.service.market.BlockchainInfoService;
import com.agora.service.market.CoinGeckoGlobalService;
import com.agora.service.market.CoinMetricsService;
import com.agora.service.market.DeribitService;
import com.agora.service.market.DefiLlamaService;
import com.agora.service.market.DydxService;
import com.agora.service.market.EtherscanService;
import com.agora.service.market.FearGreedService;
import com.agora.service.market.FredEconomicService;
import com.agora.service.market.GoldApiService;
import com.agora.service.market.HyperliquidService;
import com.agora.service.market.KrakenPublicService;
import com.agora.service.market.MempoolSpaceService;
import com.agora.service.market.OrderbookImbalanceService;
import com.agora.service.market.PythNetworkService;
import com.agora.service.market.WhaleFlowService;
import com.agora.service.trading.OkxTradingService;
import com.agora.config.properties.IndicatorHistoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 每小時 01 分 snapshot 所有關鍵市場指標到 {@code market_indicator_history} 表(V040)。
 *
 * <p>累積 3-6 個月後可:
 * <ul>
 *   <li>回測 MarketFlip trigger 在歷史上是否有 predictive power</li>
 *   <li>訓練 AI 做更 informed 判斷(第 N 個 flip 在 1 小時內是正常嗎?)</li>
 *   <li>未來若加 ML 模型有 feature 用</li>
 * </ul>
 *
 * <p>錯開整點:MarketFlip 相關 scheduler 在 :00 / :05 跑,此 collector 在 :01 跑
 * 以避免同時大量呼叫外部 API。
 *
 * <p>每個 indicator 獨立 try/catch,失敗只 warn 不中斷其他寫入。外部 API 本來就
 * 會偶爾 503,一小時一筆的 granularity 對偶爾 gap 有容錯空間。
 *
 * <p><b>並行策略（2026-04-24 改善）</b>:
 * F&G 全域值仍順序取得(1 次),per-symbol 的 4 個指標(whale/funding/ls/orderbook)
 * 改用 {@link #IO_POOL} 並行執行。耗時從串行 ~5s 降至 ~1s（等最慢的 API）。
 * IO_POOL 為 daemon thread，不佔用 Spring scheduler 主 pool 的 thread slot。
 *
 * <p>Config:
 * <ul>
 *   <li>{@code meta-control.indicator-history.enabled}(預設 true)— 關閉整個 collector</li>
 *   <li>{@code meta-control.indicator-history.symbols}(預設 BTCUSDT,ETHUSDT)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketIndicatorHistoryCollector {

    /** 並行 I/O 專用 executor（4 執行緒，daemon；不佔用 Spring scheduler 主 pool）。 */
    private static final Executor IO_POOL = Executors.newFixedThreadPool(4,
            r -> {
                Thread t = new Thread(r, "indicator-io");
                t.setDaemon(true);
                return t;
            });

    private final MarketIndicatorHistoryRepository historyRepo;
    private final FearGreedService fearGreedService;
    private final WhaleFlowService whaleFlowService;
    private final OrderbookImbalanceService orderbookImbalanceService;
    private final OkxTradingService okxTradingService;
    private final BinanceFuturesService binanceFuturesService;
    private final CoinGeckoGlobalService coinGeckoGlobalService;
    private final FredEconomicService fredEconomicService;
    private final EtherscanService etherscanService;
    private final MempoolSpaceService mempoolSpaceService;
    private final DefiLlamaService defiLlamaService;
    private final HyperliquidService hyperliquidService;
    private final DydxService dydxService;
    private final GoldApiService goldApiService;
    private final BlockchainInfoService blockchainInfoService;
    private final PythNetworkService pythNetworkService;
    private final KrakenPublicService krakenPublicService;
    private final AlchemyService alchemyService;
    private final DeribitService deribitService;
    private final UniswapDexFlowService uniswapDexFlowService;
    private final CoinMetricsService coinMetricsService;
    private final CoinalyzeService coinalyzeService;
    private final MdKlineRepository klineRepository;
    private final IndicatorHistoryProperties props;

    // @Scheduled 已移至 HourlyOrchestrator（UTC :00 串行執行，step 1）
    public void collect() {
        if (!props.enabled()) {
            log.debug("[IndicatorHistory] disabled by config");
            return;
        }

        List<String> symbols = Arrays.stream(props.symbols().split(","))
                .map(String::trim)
                .toList();

        LocalDateTime capturedAt = LocalDateTime.now(ZoneOffset.UTC);
        long t0 = System.currentTimeMillis();
        AtomicInteger written = new AtomicInteger(0);

        // F&G 全域值（非 symbol-specific）— 一次取得即可，節省 API quota
        Integer fgValue = safeInt(fearGreedService::getFearGreedValue, "fear_greed");

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String symbol : symbols) {
            String sym = symbol == null ? null : symbol.trim();
            if (sym == null || sym.isEmpty()) continue;

            final String s = sym;
            final Integer fg = fgValue;

            // F&G 寫 DB（無外部 API，快速，一起並行）
            if (fg != null) {
                futures.add(CompletableFuture.runAsync(
                        () -> written.addAndGet(
                                write(capturedAt, s, "fear_greed", BigDecimal.valueOf(fg), null)),
                        IO_POOL));
            }

            // 4 個外部 API 並行（各自獨立，互不依賴）
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(() -> whaleFlowService.getBuyRatio(s), "whale_buy_ratio");
                if (v != null) {
                    written.addAndGet(write(capturedAt, s, "whale_buy_ratio", BigDecimal.valueOf(v), null));
                    // #245: also write 3h moving average to reduce noise (whale ratio can jump 64pp/1h)
                    try {
                        java.time.LocalDateTime threeHoursAgo = capturedAt.minusHours(3);
                        java.util.List<com.agora.model.MarketIndicatorHistory> recent =
                                historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                                        s, "whale_buy_ratio", threeHoursAgo);
                        if (!recent.isEmpty()) {
                            double sum3h = recent.stream().mapToDouble(r -> r.getValue().doubleValue()).sum() + v;
                            double avg3h = sum3h / (recent.size() + 1);
                            written.addAndGet(write(capturedAt, s, "whale_buy_ratio_3h_ma",
                                    BigDecimal.valueOf(avg3h).setScale(6, java.math.RoundingMode.HALF_UP), null));
                        }
                    } catch (Exception e) {
                        log.debug("[Collector] whale_buy_ratio_3h_ma failed for {}: {}", s, e.getMessage());
                    }
                }
            }, IO_POOL));

            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(() -> okxTradingService.getCurrentFundingRate(s), "funding_rate");
                if (v != null) written.addAndGet(
                        write(capturedAt, s, "funding_rate", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(() -> okxTradingService.getLongShortRatio(s), "long_short_ratio");
                // getLongShortRatio 失敗回 -1（service 約定）；負值視為無資料不寫
                if (v != null && v >= 0) written.addAndGet(
                        write(capturedAt, s, "long_short_ratio", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(() -> orderbookImbalanceService.getImbalance(s), "orderbook_imbalance");
                if (v != null) written.addAndGet(
                        write(capturedAt, s, "orderbook_imbalance", BigDecimal.valueOf(v), null));
            }, IO_POOL));
        }

        // ── BTC-only global indicators (OI + dominance) ────────────────────
        // These are not per-symbol; we tag them as BTCUSDT.
        // Read the previous OI value BEFORE kicking off the async write, so the
        // "findTop" query always sees last hour's row rather than the one we're
        // about to insert.
        boolean collectBtcGlobal = symbols.stream()
                .anyMatch(s -> "BTCUSDT".equalsIgnoreCase(s == null ? "" : s.trim()));
        if (collectBtcGlobal) {
            final Double prevOiValue = historyRepo
                    .findTopCleanBySymbolAndIndicator("BTCUSDT", "btc_open_interest")
                    .map(h -> h.getValue() != null ? h.getValue().doubleValue() : null)
                    .orElse(null);

            // Binance Futures OI + 1h delta
            futures.add(CompletableFuture.runAsync(() -> {
                Double oi = safeDouble(
                        () -> binanceFuturesService.getOpenInterest("BTCUSDT"), "btc_open_interest");
                if (oi != null) {
                    written.addAndGet(write(capturedAt, "BTCUSDT", "btc_open_interest",
                            BigDecimal.valueOf(oi), null));
                    // Compute 1-hour OI change % using the previous snapshot.
                    // Guard: skip if delta > 50% — likely a source-unit boundary jump
                    // (e.g. switching from OKX-rubik USD-millions to Binance BTC-contracts
                    // would produce a false +1300% spike). Real 1h OI changes are < 10%.
                    if (prevOiValue != null && prevOiValue > 0) {
                        double changePct = (oi - prevOiValue) / prevOiValue * 100.0;
                        if (Math.abs(changePct) > 50.0) {
                            log.warn("[IndicatorHistory] BTC OI change {}% > 50% threshold — skipping (possible source-unit boundary). prevOi={} newOi={}",
                                    String.format("%.1f", changePct), prevOiValue, oi);
                        } else {
                            written.addAndGet(write(capturedAt, "BTCUSDT", "oi_change_pct_1h",
                                    BigDecimal.valueOf(changePct), null));
                            log.debug("[IndicatorHistory] BTC OI prevOi={} newOi={} changePct={}%",
                                    prevOiValue, oi, String.format("%.2f", changePct));
                        }
                    }
                }
            }, IO_POOL));

            // CoinGecko BTC dominance
            futures.add(CompletableFuture.runAsync(() -> {
                Double dom = safeDouble(
                        coinGeckoGlobalService::getBtcDominancePct, "btc_dominance_pct");
                if (dom != null) written.addAndGet(
                        write(capturedAt, "BTCUSDT", "btc_dominance_pct",
                                BigDecimal.valueOf(dom), null));
            }, IO_POOL));

            // ── V073: FRED U.S. macro indicators ───────────────────────────
            // 4 daily series; 30-min in-memory cache in FredEconomicService
            // dampens the once-per-hour collector to ≤ 2 actual API hits/hr
            // per series (well within FRED's 120 req/min free tier).
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(fredEconomicService::getUs10yYield, "us_10y_yield");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "us_10y_yield", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(fredEconomicService::getFedFundsRate, "us_fed_funds_rate");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "us_fed_funds_rate", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(fredEconomicService::getDxy, "us_dxy");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "us_dxy", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(fredEconomicService::getBreakeven10y, "us_breakeven_10y");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "us_breakeven_10y", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── V074: Etherscan on-chain (USDT/USDC supply + ETH gas) ──────
            // Stablecoin supply on Ethereum is a leading liquidity indicator;
            // 24h delta % captures the meaningful "new fiat onboarding" signal.
            // Read prev row (~24h ago) synchronously BEFORE async write tasks
            // to avoid the query racing our own insert.
            // Window: oldest row captured >= NOW - 25h (1h tolerance for gaps).
            final Double prevStablecoinB = historyRepo
                    .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                            "BTCUSDT", "stablecoin_supply_b",
                            capturedAt.minusHours(25))
                    .stream()
                    .findFirst()
                    .map(h -> h.getValue() != null ? h.getValue().doubleValue() : null)
                    .orElse(null);

            // Single async task does usdt + usdc together so we can write the
            // composite stablecoin_supply_b atomically alongside its parts and
            // compute the 24h change inline.
            futures.add(CompletableFuture.runAsync(() -> {
                Double usdt = safeDouble(
                        etherscanService::getUsdtSupplyBillions, "usdt_supply_b");
                Double usdc = safeDouble(
                        etherscanService::getUsdcSupplyBillions, "usdc_supply_b");
                if (usdt != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "usdt_supply_b", BigDecimal.valueOf(usdt), null));
                if (usdc != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "usdc_supply_b", BigDecimal.valueOf(usdc), null));
                if (usdt != null && usdc != null) {
                    double total = usdt + usdc;
                    written.addAndGet(write(capturedAt, "BTCUSDT",
                            "stablecoin_supply_b", BigDecimal.valueOf(total), null));
                    if (prevStablecoinB != null && prevStablecoinB > 0) {
                        double changePct = (total - prevStablecoinB) / prevStablecoinB * 100.0;
                        written.addAndGet(write(capturedAt, "BTCUSDT",
                                "stablecoin_supply_change_pct_24h",
                                BigDecimal.valueOf(changePct), null));
                        log.debug("[IndicatorHistory] stablecoin prev={}B new={}B change={}%",
                                prevStablecoinB, total, String.format("%.3f", changePct));
                    }
                }
            }, IO_POOL));

            futures.add(CompletableFuture.runAsync(() -> {
                Double gas = safeDouble(etherscanService::getEthGasGwei, "eth_gas_gwei");
                if (gas != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "eth_gas_gwei", BigDecimal.valueOf(gas), null));
            }, IO_POOL));

            // ── V075: mempool.space BTC network metrics (no key) ───────────
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(mempoolSpaceService::getMempoolCount, "btc_mempool_count");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_mempool_count", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(mempoolSpaceService::getMempoolVsizeMb, "btc_mempool_vsize_mb");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_mempool_vsize_mb", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(mempoolSpaceService::getFastFeeSatVb, "btc_fast_fee_sat_vb");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_fast_fee_sat_vb", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(mempoolSpaceService::getHashrateEh, "btc_hashrate_eh");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_hashrate_eh", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── V075: DefiLlama TVL + cross-chain stablecoin mcap (no key) ─
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(defiLlamaService::getTotalDefiTvlBillions, "defi_tvl_total_b");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "defi_tvl_total_b", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(defiLlamaService::getTotalStablecoinMcapBillions,
                        "stablecoin_total_mcap_b");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "stablecoin_total_mcap_b", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── V076: CoinGecko Demo-tier (treasury + alt breadth) ─────────
            // Reuses CoinGeckoGlobalService (demo API key adds reliability,
            // unlocks public_treasury + per-coin endpoints, 10K/mo quota).
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(
                        coinGeckoGlobalService::getBtcTreasuryHoldingsKBtc,
                        "btc_treasury_holdings_kbtc");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_treasury_holdings_kbtc", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(
                        coinGeckoGlobalService::getBtcTreasuryDominancePct,
                        "btc_treasury_dominance_pct");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_treasury_dominance_pct", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(
                        coinGeckoGlobalService::getAltBreadth24hPct,
                        "alt_breadth_24h_pct");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "alt_breadth_24h_pct", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── V077: DefiLlama /protocols categorical TVL (no key) ────────
            // One /protocols fetch (~7.8MB, 30-min cached) fills all 3 below.
            // Order matters in async submission only for log ordering; the
            // first task triggers the heavy fetch, the rest hit warm cache.
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(defiLlamaService::getDefiTvlCexBillions, "defi_tvl_cex_b");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "defi_tvl_cex_b", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(defiLlamaService::getDefiTvlLendingBillions, "defi_tvl_lending_b");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "defi_tvl_lending_b", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(defiLlamaService::getDefiTvlRestakingBillions, "defi_tvl_restaking_b");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "defi_tvl_restaking_b", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── V078: DEX perps + macro hedge + chain stats (all no-key) ──
            // Hyperliquid + dYdX = DEX-perp BTC OI cross-check vs CEX (Binance OI).
            // Gold-API XAU = macro hedge correlation context.
            // Blockchain.info /stats = chain-level supply + block production rate.
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(hyperliquidService::getBtcOi, "hyperliquid_btc_oi");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "hyperliquid_btc_oi", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(hyperliquidService::getBtcFundingHrPct,
                        "hyperliquid_btc_funding_hr_pct");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "hyperliquid_btc_funding_hr_pct", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(dydxService::getBtcOi, "dydx_btc_oi");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "dydx_btc_oi", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(goldApiService::getGoldPriceUsd, "gold_price_usd");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "gold_price_usd", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(blockchainInfoService::getBtcSupplyCirculatingMillions,
                        "btc_supply_circulating_m");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_supply_circulating_m", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(blockchainInfoService::getBtcBlockTimeAvgMin,
                        "btc_block_time_avg_min");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_block_time_avg_min", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── V079: Independent third-party BTC price feeds (no key) ─────
            // Pyth (oracle network, cross-chain) and Kraken (3rd CEX) provide
            // sources distinct from our OKX/Binance K-lines. Cross-source
            // divergence is a reliability + arbitrage signal for ML.
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(pythNetworkService::getBtcUsdPrice, "pyth_btc_usd_price");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "pyth_btc_usd_price", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(krakenPublicService::getBtcUsdPrice, "kraken_btc_usd_price");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "kraken_btc_usd_price", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── V080: Alchemy Ethereum block-level activity (key required) ─
            // Single eth_getBlockByNumber("latest", false) call fills both
            // indicators via service-side cache. Complements V074 eth_gas_gwei
            // (price) with utilization (gas_used_pct) and raw demand (tx_count).
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(alchemyService::getEthBlockTxCount, "eth_block_tx_count");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "eth_block_tx_count", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(alchemyService::getEthBlockGasUsedPct, "eth_block_gas_used_pct");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "eth_block_gas_used_pct", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── V081: Options vol + equity macro + multi-chain stablecoin ──
            // Deribit BTC DVOL (crypto-VIX equivalent from BTC options market).
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(deribitService::getBtcDvol, "btc_dvol");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_dvol", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(deribitService::getBtcPutCallRatio, "btc_put_call_ratio");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_put_call_ratio", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── CoinMetrics community (no key) — daily on-chain activity ──────
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(coinMetricsService::getBtcActiveAddresses, "btc_active_addr_cnt");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_active_addr_cnt", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(coinMetricsService::getBtcTxCount, "btc_tx_cnt");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "btc_tx_cnt", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            // FRED equity / vol indices (no extra key, reuses FRED_API_KEY).
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(fredEconomicService::getUsVix, "us_vix");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "us_vix", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(fredEconomicService::getUsSp500, "us_sp500");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "us_sp500", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(fredEconomicService::getUsNasdaq, "us_nasdaq");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "us_nasdaq", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            // Etherscan v2 multi-chain — same key, different chainid.
            // Polygon + Arbitrum free-tier supported; BSC/Base/Optimism are paid.
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(etherscanService::getUsdtSupplyPolygonBillions,
                        "usdt_supply_polygon_b");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "usdt_supply_polygon_b", BigDecimal.valueOf(v), null));
            }, IO_POOL));
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(etherscanService::getUsdtSupplyArbitrumBillions,
                        "usdt_supply_arbitrum_b");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "usdt_supply_arbitrum_b", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── V082: Uniswap v3 on-chain DEX flow (The Graph, API key required) ──
            // dex_wbtc_net_flow_usd_1h: net USD value of WBTC bought vs sold on
            // Uniswap v3 WBTC/USDC pool in the past hour. Positive = accumulation.
            // Complements OiFundingDivergenceStrategy: on-chain buying confirms
            // the "real accumulation" hypothesis independently of OKX data.
            // MEV filter: swaps < $10k excluded. Null when API key not configured.
            futures.add(CompletableFuture.runAsync(() -> {
                Double v = safeDouble(uniswapDexFlowService::getWbtcNetFlowUsd,
                        "dex_wbtc_net_flow_usd_1h");
                if (v != null) written.addAndGet(write(capturedAt, "BTCUSDT",
                        "dex_wbtc_net_flow_usd_1h", BigDecimal.valueOf(v), null));
            }, IO_POOL));

            // ── V083: Coinalyze — 清算歷史（多交易所聚合，免費 40req/min）──────────
            // btc_short_liq_usd_1h / btc_long_liq_usd_1h：空頭/多頭清算金額（USD）
            // btc_short_liq_ratio_1h：空頭清算佔比（> 0.6 = 殺空頭進行中）
            // 這三個指標填補現有系統最大的「短倉擠壓偵測」缺口。
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    // 取當前 BTC 價格用於 BTC→USD 換算
                    Double btcPx = null;
                    try {
                        java.math.BigDecimal px = okxTradingService.getLastPrice("BTCUSDT");
                        btcPx = px != null ? px.doubleValue() : null;
                    } catch (Exception ignored) {}

                    CoinalyzeService.LiquidationBar bar =
                            coinalyzeService.getLatestLiquidation1h(btcPx);
                    if (bar != null) {
                        written.addAndGet(write(capturedAt, "BTCUSDT",
                                "btc_long_liq_usd_1h",
                                BigDecimal.valueOf(bar.longLiqUsd()), null));
                        written.addAndGet(write(capturedAt, "BTCUSDT",
                                "btc_short_liq_usd_1h",
                                BigDecimal.valueOf(bar.shortLiqUsd()), null));
                        written.addAndGet(write(capturedAt, "BTCUSDT",
                                "btc_total_liq_usd_1h",
                                BigDecimal.valueOf(bar.totalLiqUsd()), null));
                        written.addAndGet(write(capturedAt, "BTCUSDT",
                                "btc_short_liq_ratio_1h",
                                BigDecimal.valueOf(bar.shortLiqRatio()), null));
                        log.info("[IndicatorHistory] Coinalyze liquidation: " +
                                "total=${:.0f} short=${:.0f} long=${:.0f} ratio={:.3f}",
                                bar.totalLiqUsd(), bar.shortLiqUsd(), bar.longLiqUsd(), bar.shortLiqRatio());
                    }

                    // ── btc_price_vs_30d_low_pct: 距 30 日低點的 % ────────────────
                    // 0 = 剛好在 30 日低點; 5 = 高於 30 日低點 5%
                    // 當 < 3% 時表示「價格在前低附近」→ 短倉擠壓風險高（Review 建議）
                    if (btcPx != null) {
                        try {
                            LocalDateTime since30d = capturedAt.minusDays(30);
                            // 用 1h klines 找 30 日最低價
                            List<com.agora.model.MdKline> klines30d =
                                    klineRepository.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                                            "BTCUSDT", "1h", since30d, capturedAt);
                            double low30d = klines30d.stream()
                                    .filter(k -> k.getLowPrice() != null)
                                    .mapToDouble(k -> k.getLowPrice().doubleValue())
                                    .min()
                                    .orElse(0);
                            if (low30d > 0) {
                                double distPct = (btcPx - low30d) / low30d * 100.0;
                                written.addAndGet(write(capturedAt, "BTCUSDT",
                                        "btc_price_vs_30d_low_pct",
                                        BigDecimal.valueOf(distPct), null));
                                log.debug("[IndicatorHistory] btc_price_vs_30d_low_pct={:.2f}% (px={:.0f} low30d={:.0f})",
                                        distPct, btcPx, low30d);
                            }
                        } catch (Exception e) {
                            log.warn("[IndicatorHistory] btc_price_vs_30d_low_pct failed: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("[IndicatorHistory] Coinalyze liquidation failed: {}", e.getMessage());
                }
            }, IO_POOL));
        }

        // 等待所有並行任務完成（max 30s，各 API connectTimeout=5s + readTimeout=10s，留有餘裕）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[IndicatorHistory] parallel fetch timed out after 30s, some indicators may be missing");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[IndicatorHistory] interrupted during parallel fetch");
        } catch (ExecutionException e) {
            log.warn("[IndicatorHistory] parallel fetch execution error: {}",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }

        // ── Post-parallel: cross-source computed indicators ────────────────────
        // These need both sources to be written first (sequential, not in futures).
        if (collectBtcGlobal) {
            // funding_rate_cex_dex_spread: OKX hourly - Hyperliquid hourly
            // OKX funding settles every 8h → per-hour rate = funding_rate / 8
            // Hyperliquid fundingHrPct is already per-hour
            // Positive spread = OKX longs paying more (CEX more bullish than DEX)
            try {
                historyRepo.findTopCleanBySymbolAndIndicator("BTCUSDT", "funding_rate")
                    .flatMap(okxRow -> historyRepo
                        .findTopCleanBySymbolAndIndicator("BTCUSDT", "hyperliquid_btc_funding_hr_pct")
                        .map(hlRow -> {
                            double okxHr = okxRow.getValue().doubleValue() / 8.0;
                            double hlHr  = hlRow.getValue().doubleValue();
                            return okxHr - hlHr;
                        }))
                    .ifPresent(spread -> {
                        if (!historyRepo.existsBySymbolAndIndicatorAndCapturedAt(
                                "BTCUSDT", "funding_rate_cex_dex_spread", capturedAt)) {
                            written.addAndGet(write(capturedAt, "BTCUSDT",
                                    "funding_rate_cex_dex_spread", BigDecimal.valueOf(spread), null));
                            log.debug("[IndicatorHistory] funding_rate_cex_dex_spread={}", spread);
                        }
                    });
            } catch (Exception e) {
                log.warn("[IndicatorHistory] cex_dex_spread compute failed: {}", e.getMessage());
            }
        }

        // ── #260: btc_basis_pct = (SWAP last - spot last) / spot last × 100 ──
        // Zero new API cost: reuses existing OKX ticker endpoint.
        // Positive = SWAP premium (bullish); negative = contango discount (bearish).
        if (collectBtcGlobal) {
            try {
                BigDecimal spotPx = okxTradingService.getLastPrice("BTCUSDT");
                BigDecimal swapPx = okxTradingService.getSwapLastPrice("BTCUSDT");
                if (spotPx != null && swapPx != null && spotPx.compareTo(BigDecimal.ZERO) > 0) {
                    double basis = swapPx.subtract(spotPx)
                            .divide(spotPx, 8, java.math.RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .doubleValue();
                    written.addAndGet(write(capturedAt, "BTCUSDT", "btc_basis_pct",
                            BigDecimal.valueOf(basis), null));
                    log.debug("[IndicatorHistory] btc_basis_pct={} spot={} swap={}", basis, spotPx, swapPx);
                }
            } catch (Exception e) {
                log.warn("[IndicatorHistory] btc_basis_pct compute failed: {}", e.getMessage());
            }
        }

        log.info("[IndicatorHistory] captured_at={} symbols={} written={} elapsed={}ms",
                capturedAt, symbols, written.get(), System.currentTimeMillis() - t0);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private int write(LocalDateTime capturedAt, String symbol, String indicator,
                      BigDecimal value, String metadataJson) {
        // #410 idempotent guard：scheduler 排程 + 手動 trigger 撞同 capturedAt，
        // 舊版裸 save 會產生 (symbol, indicator, captured_at) 重複 row（觀察到
        // long_short_ratio / bt_long_liq_usd_1h / bt_short_liq_usd_1h dup）。
        // V104 加 UK 後 SQL 層也會擋；blue/green drain 期間新舊 JVM 可在整點
        // 同時 collect，因此必須用 atomic INSERT IGNORE，不能只靠 exists+save。
        if (metadataJson == null || metadataJson.isBlank()) {
            return historyRepo.insertIgnore(symbol, indicator, capturedAt, value);
        }
        try {
            if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt(symbol, indicator, capturedAt)) {
                return 0;
            }
            MarketIndicatorHistory row = new MarketIndicatorHistory();
            row.setCapturedAt(capturedAt);
            row.setSymbol(symbol);
            row.setIndicator(indicator);
            row.setValue(value);
            row.setMetadataJson(metadataJson);
            historyRepo.save(row);
            return 1;
        } catch (Exception e) {
            log.warn("[IndicatorHistory] save failed symbol={} indicator={}: {}",
                    symbol, indicator, e.getMessage());
            return 0;
        }
    }

    private Integer safeInt(Supplier<Integer> probe, String label) {
        try {
            return probe.get();
        } catch (Exception e) {
            log.warn("[IndicatorHistory] {} fetch failed: {}", label, e.getMessage());
            return null;
        }
    }

    private Double safeDouble(Supplier<Double> probe, String label) {
        try {
            return probe.get();
        } catch (Exception e) {
            log.warn("[IndicatorHistory] {} fetch failed: {}", label, e.getMessage());
            return null;
        }
    }
}
