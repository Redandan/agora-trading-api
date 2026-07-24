package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.cache.annotation.Cacheable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * OKX 現貨自動交易服務（@Primary，為預設實作）。
 *
 * <p>OKX 與 Binance 的主要差異：
 * <ul>
 *   <li>簽名：Base64(HMAC-SHA256(timestamp + method + path + body))，而非 hex</li>
 *   <li>Timestamp：ISO 8601 格式（"2024-01-01T00:00:00.000Z"），而非 Unix 毫秒</li>
 *   <li>Body：JSON，而非 URL-encoded form</li>
 *   <li>Auth Header：需額外帶 OK-ACCESS-PASSPHRASE</li>
 *   <li>交易對格式：BTC-USDT（有連字號），而非 BTCUSDT</li>
 *   <li>市價單：下單後需輪詢查單以取得成交均價（不像 Binance 直接回傳）</li>
 *   <li>OCO：透過 Algo Order（/api/v5/trade/order-algo）實現</li>
 * </ul>
 * </p>
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class OkxTradingService implements TradingService {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_FILL_RETRIES = 6;
    private static final long FILL_RETRY_MS   = 500;
    private static final int OKX_FILLS_HISTORY_MAX_LIMIT = 100;
    private static final long ALGO_ORDER_SUCCESS_CACHE_TTL_MS = 15_000L;
    private static final long ALGO_ORDER_RATE_LIMIT_CACHE_TTL_MS = 120_000L;
    private static final long ALGO_ORDER_LOOKUP_MIN_INTERVAL_MS = 250L;
    private static final long LAST_PRICE_SUCCESS_CACHE_TTL_MS = 5_000L;
    private static final long LAST_PRICE_RATE_LIMIT_CACHE_TTL_MS = 60_000L;
    private static final long LAST_PRICE_LOOKUP_MIN_INTERVAL_MS = 100L;
    private static final long ACCOUNT_HOLDINGS_SUCCESS_CACHE_TTL_MS = 15_000L;
    private static final long ACCOUNT_HOLDINGS_STALE_CACHE_TTL_MS = 180_000L;
    private static final long ACCOUNT_HOLDINGS_LOOKUP_MIN_INTERVAL_MS = 500L;
    private static final BigDecimal UNKNOWN_BUY_FEE_QTY_BUFFER_RATE = new BigDecimal("0.002");
    private static final List<String> ALGO_ORDER_HISTORY_STATES = List.of("effective", "canceled", "order_failed");

    private final OkxTradingProperties props;
    private final ObjectMapper objectMapper;
    private final Map<String, AlgoOrderCacheEntry> algoOrderCache = new ConcurrentHashMap<>();
    private final Map<String, LastPriceCacheEntry> lastPriceCache = new ConcurrentHashMap<>();
    private final Object algoOrderLookupThrottleMonitor = new Object();
    private final Object lastPriceLookupThrottleMonitor = new Object();
    private final Object accountHoldingsLookupThrottleMonitor = new Object();

    private OkHttpClient httpClient;
    private long lastAlgoOrderLookupAtMs = 0L;
    private long lastPriceLookupAtMs = 0L;
    private long lastAccountHoldingsLookupAtMs = 0L;
    private volatile HoldingsCacheEntry spotHoldingsCache;
    private volatile HoldingsCacheEntry fundingHoldingsCache;

    private record AlgoOrderCacheEntry(JsonNode algo, long cachedAtMs) {
    }

    private record LastPriceCacheEntry(BigDecimal price, long cachedAtMs) {
    }

    private record HoldingsCacheEntry(List<SpotHolding> holdings, long cachedAtMs) {
    }

    @PostConstruct
    void init() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        logStartupStatus();
    }

    /**
     * 啟動時印出設定摘要，並在 enabled=true 時驗證 API Key 是否有效。
     */
    private void logStartupStatus() {
        boolean keyConfigured = props.getApiKey() != null && !props.getApiKey().isBlank();
        boolean secretConfigured = props.getSecretKey() != null && !props.getSecretKey().isBlank();
        boolean passphraseConfigured = props.getPassphrase() != null && !props.getPassphrase().isBlank();

        log.info("[OKX] ======================================");
        log.info("[OKX] Auto-trade enabled   : {}", props.isEnabled());
        log.info("[OKX] API Key configured   : {} ({}...)",
                keyConfigured,
                keyConfigured ? props.getApiKey().substring(0, Math.min(8, props.getApiKey().length())) : "N/A");
        log.info("[OKX] Secret configured    : {}", secretConfigured);
        log.info("[OKX] Passphrase configured: {}", passphraseConfigured);
        log.info("[OKX] Trade amount (USDT)  : {}", props.getTradeAmountUsdt());
        log.info("[OKX] Max open positions   : {}", props.getMaxOpenPositions());
        log.info("[OKX] ======================================");

        if (props.isEnabled()) {
            if (!props.hasPrivateCredentials()) {
                log.error("[OKX] enabled=true 但 API Key / Secret / Passphrase 未完整設定，自動交易將無法運作！");
                return;
            }
            CompletableFuture.runAsync(this::verifyApiKey);
        }
    }

    /**
     * 呼叫 GET /api/v5/account/balance 驗證 API Key 是否有效且有交易權限。
     */
    private void verifyApiKey() {
        try {
            String path = "/api/v5/account/balance?ccy=USDT";
            JsonNode resp = get(path);
            String code = resp.path("code").asText("0");
            if ("0".equals(code)) {
                JsonNode details = resp.path("data").path(0).path("details");
                String usdtBalance = "N/A";
                if (details.isArray()) {
                    for (JsonNode d : details) {
                        if ("USDT".equals(d.path("ccy").asText())) {
                            usdtBalance = d.path("availBal").asText();
                            break;
                        }
                    }
                }
                log.info("[OKX] API Key 驗證成功 ✅ — 可用 USDT 餘額: {}", usdtBalance);
            } else {
                log.error("[OKX] API Key 驗證失敗 ❌ — code={} msg={}",
                        code, resp.path("msg").asText());
            }
        } catch (Exception e) {
            log.error("[OKX] API Key 驗證時發生錯誤: {}", e.getMessage());
        }
    }

    public boolean hasPrivateCredentials() {
        return props.hasPrivateCredentials();
    }

    /**
     * Read-only OKX-native Spot Grid inventory. This is intentionally separate from the
     * deprecated local {@code bt_grid} state machine and never creates, amends, or stops a bot.
     */
    public JsonNode getNativeSpotGridOrders(boolean history) {
        String endpoint = history ? "orders-algo-history" : "orders-algo-pending";
        JsonNode response = get("/api/v5/tradingBot/grid/" + endpoint + "?algoOrdType=grid");
        assertOkxCode(response);
        return response.path("data");
    }

    /** Read-only detail for one provider-native Spot Grid bot. */
    public JsonNode getNativeSpotGridOrderDetails(String algoId) {
        requireDigits("algoId", algoId);
        JsonNode response = get("/api/v5/tradingBot/grid/orders-algo-details?algoOrdType=grid&algoId=" + algoId);
        assertOkxCode(response);
        return response.path("data");
    }

    /** Read-only filled or live child orders for one provider-native Spot Grid bot. */
    public JsonNode getNativeSpotGridSubOrders(String algoId, String type) {
        requireDigits("algoId", algoId);
        String normalizedType = type == null ? "" : type.trim().toLowerCase();
        if (!"filled".equals(normalizedType) && !"live".equals(normalizedType)) {
            throw new IllegalArgumentException("type must be filled or live");
        }
        JsonNode response = get("/api/v5/tradingBot/grid/sub-orders?algoOrdType=grid&algoId="
                + algoId + "&type=" + normalizedType + "&limit=100");
        assertOkxCode(response);
        return response.path("data");
    }

    private void requireDigits(String field, String value) {
        if (value == null || !value.matches("[0-9]+")) {
            throw new IllegalArgumentException(field + " must contain digits only");
        }
    }

    // ──────────────────────────────────────────────
    //  公開查詢方法
    // ──────────────────────────────────────────────

    /**
     * 查詢現貨最新成交價。回傳 null 表示查詢失敗。
     */
    public BigDecimal getLastPrice(String symbol) {
        return getLastPriceFromTicker(toInstId(symbol), symbol, "getLastPrice");
    }

    /**
     * Read-only OKX SPOT instrument rules used by preflight diagnostics.
     * No order/OCO/account state is changed.
     */
    public SpotInstrumentRules getSpotInstrumentRules(String symbol) {
        String instId = toInstId(symbol);
        String path = "/api/v5/public/instruments?instType=SPOT&instId=" + instId;
        JsonNode resp = getPublic(path);
        assertOkxCode(resp);
        JsonNode row = resp.path("data").path(0);
        if (row == null || row.isMissingNode() || row.isNull()) {
            throw new RuntimeException("OKX instrument not found: " + instId);
        }
        return new SpotInstrumentRules(
                row.path("instId").asText(instId),
                decimalOrNull(row.path("minSz").asText(null)),
                decimalOrNull(row.path("lotSz").asText(null)),
                decimalOrNull(row.path("tickSz").asText(null))
        );
    }

    /**
     * Reconstructs the quantity attributable to one legacy Grid BUY before a retirement SELL.
     * This is a read-only provider reconciliation. It prevents an old gross DB fill from
     * consuming unrelated account BTC when OKX charged the BUY fee in base currency.
     */
    public GridRetirementQuantity getGridRetirementQuantity(String symbol,
                                                             String buyOrderId,
                                                             BigDecimal databaseGrossQty) {
        if (buyOrderId == null || buyOrderId.isBlank()) {
            throw new IllegalArgumentException("Grid retirement requires the original buyOrderId");
        }
        if (databaseGrossQty == null || databaseGrossQty.signum() <= 0) {
            throw new IllegalArgumentException("Grid retirement requires a positive database gross quantity");
        }
        String instId = toInstId(symbol);
        JsonNode order = queryOrder(instId, buyOrderId);
        if (!"filled".equals(order.path("state").asText())) {
            throw new IllegalStateException("Grid retirement BUY order is not filled: " + buyOrderId);
        }
        if (!"buy".equals(order.path("side").asText())) {
            throw new IllegalStateException("Grid retirement order is not a BUY: " + buyOrderId);
        }
        BigDecimal providerGrossQty = decimalOrNull(order.path("accFillSz").asText(null));
        if (providerGrossQty == null || providerGrossQty.signum() <= 0) {
            throw new IllegalStateException("Grid retirement BUY order has no positive accFillSz: " + buyOrderId);
        }
        String feeCurrency = firstNonBlank(
                order.path("fillFeeCcy").asText(null),
                order.path("feeCcy").asText(null));
        BigDecimal signedFee = decimalOrNull(firstNonBlank(
                order.path("fillFee").asText(null),
                order.path("fee").asText(null)));
        if (signedFee == null) signedFee = BigDecimal.ZERO;
        SpotInstrumentRules rules = getSpotInstrumentRules(instId);
        return calculateGridRetirementQuantity(
                instId, buyOrderId, databaseGrossQty, providerGrossQty,
                signedFee, feeCurrency, rules.lotSize());
    }

    static GridRetirementQuantity calculateGridRetirementQuantity(String instId,
                                                                    String buyOrderId,
                                                                    BigDecimal databaseGrossQty,
                                                                    BigDecimal providerGrossQty,
                                                                    BigDecimal signedFee,
                                                                    String feeCurrency,
                                                                    BigDecimal lotSize) {
        if (lotSize == null || lotSize.signum() <= 0) {
            throw new IllegalStateException("OKX lot size is unavailable for " + instId);
        }
        if (databaseGrossQty.subtract(providerGrossQty).abs().compareTo(lotSize) > 0) {
            throw new IllegalStateException("Grid DB/provider gross quantity mismatch: db="
                    + databaseGrossQty + " provider=" + providerGrossQty + " lotSize=" + lotSize);
        }
        String baseCurrency = instId == null ? "" : instId.split("-")[0];
        BigDecimal fee = signedFee == null ? BigDecimal.ZERO : signedFee;
        BigDecimal netAttributableQty = providerGrossQty;
        if (feeCurrency != null && !feeCurrency.isBlank() && baseCurrency.equalsIgnoreCase(feeCurrency)) {
            netAttributableQty = providerGrossQty.add(fee);
        } else if (fee.signum() != 0 && (feeCurrency == null || feeCurrency.isBlank())) {
            throw new IllegalStateException("Grid retirement signed fee has no currency: " + buyOrderId);
        }
        if (netAttributableQty.signum() <= 0 || netAttributableQty.compareTo(providerGrossQty) > 0) {
            throw new IllegalStateException("Grid retirement net attributable quantity is invalid: "
                    + netAttributableQty);
        }
        BigDecimal sellQuantity = netAttributableQty.divide(lotSize, 0, java.math.RoundingMode.DOWN)
                .multiply(lotSize)
                .stripTrailingZeros();
        if (sellQuantity.signum() <= 0) {
            throw new IllegalStateException("Grid retirement quantity is below one OKX lot: " + netAttributableQty);
        }
        BigDecimal attributionDust = netAttributableQty.subtract(sellQuantity).max(BigDecimal.ZERO);
        return new GridRetirementQuantity(
                instId, buyOrderId, databaseGrossQty, providerGrossQty,
                fee, feeCurrency, netAttributableQty, sellQuantity, attributionDust, lotSize);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /** 查詢永續合約最新成交價（用於計算 basis）。回傳 null 表示查詢失敗。 */
    public BigDecimal getSwapLastPrice(String symbol) {
        return getLastPriceFromTicker(toSwapInstId(symbol), symbol, "getSwapLastPrice");
    }

    private BigDecimal getLastPriceFromTicker(String instId, String symbolForLog, String operation) {
        long nowMs = System.currentTimeMillis();
        BigDecimal cached = cachedLastPrice(instId, nowMs, LAST_PRICE_SUCCESS_CACHE_TTL_MS);
        if (cached != null) {
            return cached;
        }
        String path = "/api/v5/market/ticker?instId=" + instId;
        try {
            throttleLastPriceLookup();
            JsonNode resp = getPublic(path);
            assertOkxCode(resp);
            String last = resp.path("data").path(0).path("last").asText("");
            if (last.isEmpty()) {
                return null;
            }
            BigDecimal price = new BigDecimal(last);
            cacheLastPrice(instId, price);
            return price;
        } catch (Exception e) {
            if (isOkxRateLimit(e)) {
                BigDecimal stale = cachedLastPrice(instId, nowMs, LAST_PRICE_RATE_LIMIT_CACHE_TTL_MS);
                if (stale != null) {
                    log.info("[OKX] {} rate limited; using recent cached ticker: symbol={} instId={}",
                            operation, symbolForLog, instId);
                    return stale;
                }
            }
            log.warn("[OKX] {} failed for {}: {}", operation, symbolForLog, e.getMessage());
            return null;
        }
    }

    private BigDecimal cachedLastPrice(String instId, long nowMs, long ttlMs) {
        LastPriceCacheEntry entry = lastPriceCache.get(instId);
        if (entry == null || nowMs - entry.cachedAtMs() > ttlMs) {
            return null;
        }
        return entry.price();
    }

    private void cacheLastPrice(String instId, BigDecimal price) {
        if (instId == null || price == null) {
            return;
        }
        lastPriceCache.put(instId, new LastPriceCacheEntry(price, System.currentTimeMillis()));
    }

    /**
     * 查詢現貨 OCO Algo 訂單詳情（instId 用現貨格式 BTC-USDT）。
     * state: "live"=掛單中, "filled"=已成交（TP/SL 觸發）, "canceled"=已取消, "order_failed"=失敗。
     * 回傳 data[0]，若查無此單則回傳 MissingNode。
     */
    public JsonNode getAlgoOrder(String symbol, Long algoId) {
        String instId = toInstId(symbol);
        String cacheKey = algoOrderCacheKey(instId, algoId);
        long nowMs = System.currentTimeMillis();
        JsonNode cached = cachedAlgoOrder(cacheKey, nowMs, ALGO_ORDER_SUCCESS_CACHE_TTL_MS);
        if (cached != null) {
            return cached;
        }
        String path = "/api/v5/trade/order-algo?ordType=oco&algoId=" + algoId + "&instId=" + instId;
        try {
            throttleAlgoOrderLookup();
            JsonNode resp = get(path);
            assertOkxCode(resp);
            JsonNode algo = resp.path("data").path(0);
            cacheAlgoOrder(cacheKey, algo);
            return algo;
        } catch (RuntimeException e) {
            if (isOkxRateLimit(e)) {
                JsonNode stale = cachedAlgoOrder(cacheKey, nowMs, ALGO_ORDER_RATE_LIMIT_CACHE_TTL_MS);
                if (stale != null) {
                    log.info("[OKX] getAlgoOrder rate limited; using recent cached algo order: instId={} algoId={}",
                            instId, algoId);
                    return stale;
                }
            }
            throw e;
        }
    }

    static String algoOrderCacheKey(String instId, Long algoId) {
        return String.valueOf(instId) + "|" + String.valueOf(algoId);
    }

    /** Evicts one spot algo-order snapshot so a state transition can be confirmed against OKX. */
    public void invalidateAlgoOrderCache(String symbol, Long algoId) {
        if (symbol == null || symbol.isBlank() || algoId == null) return;
        algoOrderCache.remove(algoOrderCacheKey(toInstId(symbol), algoId));
    }

    static boolean isOkxRateLimit(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && (message.contains("OKX HTTP 429")
                    || message.contains("Too Many Requests")
                    || message.contains("code=50011")
                    || message.contains("\"code\":\"50011\""))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private JsonNode cachedAlgoOrder(String cacheKey, long nowMs, long ttlMs) {
        AlgoOrderCacheEntry entry = algoOrderCache.get(cacheKey);
        if (entry == null || nowMs - entry.cachedAtMs() > ttlMs) {
            return null;
        }
        return entry.algo().deepCopy();
    }

    private void cacheAlgoOrder(String cacheKey, JsonNode algo) {
        if (algo == null || algo.isMissingNode() || algo.isNull()) {
            return;
        }
        algoOrderCache.put(cacheKey, new AlgoOrderCacheEntry(algo.deepCopy(), System.currentTimeMillis()));
    }

    private JsonNode syntheticLiveAlgoOrder(String instId, Long algoId) {
        return objectMapper.createObjectNode()
                .put("algoId", String.valueOf(algoId))
                .put("instId", instId)
                .put("ordType", "oco")
                .put("side", "sell")
                .put("state", "live")
                .put("cacheSource", "local_after_place_oco");
    }

    private void throttleAlgoOrderLookup() {
        synchronized (algoOrderLookupThrottleMonitor) {
            long nowMs = System.currentTimeMillis();
            long waitMs = ALGO_ORDER_LOOKUP_MIN_INTERVAL_MS - (nowMs - lastAlgoOrderLookupAtMs);
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastAlgoOrderLookupAtMs = System.currentTimeMillis();
        }
    }

    private void throttleLastPriceLookup() {
        synchronized (lastPriceLookupThrottleMonitor) {
            long nowMs = System.currentTimeMillis();
            long waitMs = LAST_PRICE_LOOKUP_MIN_INTERVAL_MS - (nowMs - lastPriceLookupAtMs);
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastPriceLookupAtMs = System.currentTimeMillis();
        }
    }

    private void throttleAccountHoldingsLookup() {
        synchronized (accountHoldingsLookupThrottleMonitor) {
            long nowMs = System.currentTimeMillis();
            long waitMs = ACCOUNT_HOLDINGS_LOOKUP_MIN_INTERVAL_MS - (nowMs - lastAccountHoldingsLookupAtMs);
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastAccountHoldingsLookupAtMs = System.currentTimeMillis();
        }
    }

    /**
     * 列出所有 pending（live / effective / pause）的 OCO algo 訂單。
     * 用於 reconcile：OCO 鎖住的 sell-side qty 也算合法 managed 持倉,
     * 避免 manual /admin/oco/market-buy 創建的部位被誤判為「未追蹤」。
     *
     * <p>回傳 data 陣列;每個 item 主要欄位:algoId / instId / side / sz / state。
     */
    public JsonNode getPendingOcoAlgos() {
        String path = "/api/v5/trade/orders-algo-pending?ordType=oco";
        JsonNode resp = get(path);
        assertOkxCode(resp);
        return resp.path("data");
    }

    /**
     * 查詢近期成交記錄（SPOT 或 SWAP）。
     *
     * @param instType "SPOT" 或 "SWAP"
     * @param limit    筆數（最多 100）
     * @return fills 陣列 JsonNode
     */
    public JsonNode getRecentFills(String instType, int limit) {
        String path = "/api/v5/trade/fills-history?instType=" + instType
                + "&limit=" + normalizeRecentFillsLimit(limit);
        JsonNode resp = get(path);
        assertOkxCode(resp);
        return resp.path("data");
    }

    /**
     * Exact-evidence GET-only page. The cursor is the oldest billId from the prior page.
     * This method cannot place, cancel, or modify an order.
     */
    public JsonNode getFillHistoryPage(String instType, String instId, int limit, String afterBillId) {
        return getFillHistoryPage(instType, instId, null, limit, afterBillId);
    }

    /**
     * Exact-evidence GET-only page optionally restricted to one immutable provider order ID.
     * The order filter avoids mixing unrelated fills from the same instrument.
     */
    public JsonNode getFillHistoryPage(String instType, String instId, String orderId,
                                       int limit, String afterBillId) {
        String normalizedType = instType == null ? "" : instType.trim().toUpperCase();
        String normalizedId = instId == null ? "" : instId.trim().toUpperCase();
        if (!"SPOT".equals(normalizedType) || !normalizedId.matches("[A-Z0-9]+-[A-Z0-9]+")) {
            throw new IllegalArgumentException("exact fill history is restricted to a concrete SPOT instrument");
        }
        if (orderId != null && !orderId.matches("[0-9]+")) {
            throw new IllegalArgumentException("orderId must contain digits only");
        }
        if (afterBillId != null && !afterBillId.matches("[0-9]+")) {
            throw new IllegalArgumentException("after cursor must be an OKX billId");
        }
        String path = "/api/v5/trade/fills-history?instType=SPOT&instId=" + normalizedId
                + (orderId == null ? "" : "&ordId=" + orderId)
                + "&limit=" + normalizeRecentFillsLimit(limit)
                + (afterBillId == null ? "" : "&after=" + afterBillId);
        JsonNode response = get(path);
        assertOkxCode(response);
        return response;
    }

    /**
     * GET-only immutable order receipt used when OKX Grid child fills are absent from both
     * transaction-history endpoints. Callers must independently prove that fillSz equals
     * accFillSz before treating the latest-fill fields as complete all-fill evidence.
     */
    public JsonNode getSpotOrderDetail(String instId, String orderId) {
        String normalizedId = instId == null ? "" : instId.trim().toUpperCase();
        if (!normalizedId.matches("[A-Z0-9]+-[A-Z0-9]+")) {
            throw new IllegalArgumentException("spot order detail requires a concrete instrument");
        }
        if (orderId == null || !orderId.matches("[0-9]+")) {
            throw new IllegalArgumentException("orderId must contain digits only");
        }
        return queryOrder(normalizedId, orderId);
    }

    static int normalizeRecentFillsLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, OKX_FILLS_HISTORY_MAX_LIMIT);
    }

    /**
     * 查詢 SWAP 資金費率歷史（Public 端點，無需額外 Auth）。
     * OKX 每 8 小時結算一次；limit 最多 100 筆（約 33 天）。
     *
     * @param symbol 交易對（如 BTCUSDT）
     * @param limit  筆數（最多 100）
     */
    public JsonNode getFundingRateHistory(String symbol, int limit) {
        String instId = toSwapInstId(symbol);
        String path = "/api/v5/public/funding-rate-history?instId=" + instId + "&limit=" + limit;
        JsonNode resp = get(path);
        assertOkxCode(resp);
        return resp.path("data");
    }

    /** OKX Rubik stats: open interest + volume history (1H/4H/1D). */
    public JsonNode getOpenInterestVolumeHistory(String ccy, String period, int limit) {
        String path = "/api/v5/rubik/stat/contracts/open-interest-volume?ccy=" + ccy
                + "&period=" + period + "&limit=" + limit;
        return get(path).path("data");
    }

    /**
     * 取得目前資金費率（OKX SWAP 永續，每 8h 結算一次）。
     * > 0：多頭付費（市場偏多）；< 0：空頭付費（市場偏空，擠壓風險）。
     * API 失敗時回傳 0（中立）。快取 30 分鐘。
     */
    @Cacheable(value = "fundingRate", key = "#symbol")
    public double getCurrentFundingRate(String symbol) {
        FundingRateObservation observation = getCurrentFundingRateObservation(symbol);
        return observation == null ? 0 : observation.value();
    }

    /** Funding-rate observation with source metadata; returns {@code null} on provider failure. */
    public FundingRateObservation getCurrentFundingRateObservation(String symbol) {
        String instId = toSwapInstId(symbol);
        String path = "/api/v5/public/funding-rate?instId=" + instId;
        try {
            JsonNode resp = get(path);
            assertOkxCode(resp);
            JsonNode row = resp.path("data").path(0);
            JsonNode rate = row.get("fundingRate");
            if (row.isMissingNode() || rate == null || rate.isNull() || rate.asText("").isBlank()) return null;
            long sourceTimestampMs = row.path("ts").asLong(0L);
            return new FundingRateObservation(rate.asDouble(),
                    "OKX_PUBLIC_FUNDING_RATE", Instant.now(),
                    sourceTimestampMs > 0 ? sourceTimestampMs : null);
        } catch (Exception e) {
            log.warn("[OKX] getCurrentFundingRate failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    public record FundingRateObservation(double value,
                                         String provider,
                                         Instant observedAt,
                                         Long sourceTimestampMs) {
        public Instant effectiveCapturedAt() {
            return sourceTimestampMs != null && sourceTimestampMs > 0
                    ? Instant.ofEpochMilli(sourceTimestampMs)
                    : observedAt;
        }
    }

    /**
     * 取得多空帳戶比率（Long/Short Account Ratio，最新 5 分鐘週期）。
     * > 1：多頭佔多數；< 1：空頭佔多數（擠壓風險）；-1：取得失敗（中立）。
     * 快取 5 分鐘。
     */
    @Cacheable(value = "longShortRatio", key = "#symbol")
    public double getLongShortRatio(String symbol) {
        // OKX Rubik stat 使用 ccy（幣種代碼，如 BTC/ETH），非 instId
        String ccy = symbol.replace("USDT", "").replace("BUSD", "");
        // 回傳陣列 [[ts, longShortRatio], ...]，最新在 index 0
        String path = "/api/v5/rubik/stat/contracts/long-short-account-ratio?ccy=" + ccy + "&period=5m";
        try {
            JsonNode resp = get(path);
            assertOkxCode(resp);
            JsonNode row = resp.path("data").path(0);
            if (row.isArray() && row.size() >= 2)
                return row.get(1).asDouble(-1);
            return -1;
        } catch (Exception e) {
            log.warn("[OKX] getLongShortRatio failed for {}: {}", symbol, e.getMessage());
            return -1;
        }
    }

    /**
     * 取得多空帳戶比率歷史（最新在前）。
     * 回傳陣列 [[ts, longShortRatio], ...]；用於 HistoricalFilterEvaluator 回測重播。
     * period 預設 1H（每小時一筆），limit 最多 1440（60 日小時資料）。
     */
    public JsonNode getLongShortRatioHistory(String symbol, int limit) {
        String ccy = symbol.replace("USDT", "").replace("BUSD", "");
        String path = "/api/v5/rubik/stat/contracts/long-short-account-ratio?ccy=" + ccy
                + "&period=1H&limit=" + limit;
        JsonNode resp = get(path);
        assertOkxCode(resp);
        return resp.path("data");
    }

    /**
     * 取得 taker buy/sell 量歷史（最新在前）。
     * 回傳陣列 [[ts, sellVol, buyVol], ...]；用於 HistoricalFilterEvaluator 回測重播。
     * period 預設 1H，limit 最多 1440。
     */
    public JsonNode getTakerVolumeHistory(String symbol, int limit) {
        String ccy = symbol.replace("USDT", "").replace("BUSD", "");
        String path = "/api/v5/rubik/stat/taker-volume?ccy=" + ccy
                + "&instType=SPOT&period=1H&limit=" + limit;
        JsonNode resp = get(path);
        assertOkxCode(resp);
        return resp.path("data");
    }

    /**
     * 查詢 OCO Algo 訂單歷史（包含已觸發/已取消/失敗）。
     * OKX history endpoint requires either state or algoId; query every
     * documented terminal state and merge the latest rows for diagnostics.
     *
     * @param instType "SPOT" 或 "SWAP"
     * @param limit    筆數（最多 100）
     */
    public JsonNode getAlgoOrderHistory(String instType, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<JsonNode> rows = new ArrayList<>();
        for (String state : ALGO_ORDER_HISTORY_STATES) {
            String path = "/api/v5/trade/orders-algo-history?ordType=oco&instType="
                    + instType + "&state=" + state + "&limit=" + safeLimit;
            JsonNode resp = get(path);
            assertOkxCode(resp);
            JsonNode data = resp.path("data");
            if (data.isArray()) {
                data.forEach(rows::add);
            }
        }
        rows.sort(Comparator.comparingLong(OkxTradingService::algoCreatedAt).reversed());
        ArrayNode result = objectMapper.createArrayNode();
        rows.stream().limit(safeLimit).forEach(result::add);
        return result;
    }

    private static long algoCreatedAt(JsonNode order) {
        try {
            return Long.parseLong(order.path("cTime").asText("0"));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * 查詢 SWAP 已平倉歷史及 OKX 官方已實現損益。
     * 可用來交叉驗證系統計算的 realizedPnl 是否與交易所一致。
     *
     * @param limit 筆數（最多 100）
     */
    public JsonNode getSwapPositionsHistory(int limit) {
        String path = "/api/v5/account/positions-history?instType=SWAP&limit=" + limit;
        JsonNode resp = get(path);
        assertOkxCode(resp);
        return resp.path("data");
    }

    /**
     * 查詢帳戶爆倉風險狀態。
     * riskState: "0"=Normal, "1"=Warning, "2"=Danger（接近強制平倉）。
     * 無 SWAP 倉位時回傳空陣列。
     */
    public JsonNode getAccountRiskState() {
        JsonNode resp = get("/api/v5/account/risk-state");
        assertOkxCode(resp);
        return resp.path("data");
    }

    /**
     * 查詢 OKX 帳戶當前所有開倉中的 SWAP 合約持倉。
     *
     * @return 每筆持倉的 (instId, pos, avgPx) 記錄；無持倉時回傳空列表
     */
    public List<SwapPosition> getOpenSwapPositions() {
        JsonNode resp = get("/api/v5/account/positions?instType=SWAP");
        assertOkxCode(resp);
        List<SwapPosition> result = new ArrayList<>();
        for (JsonNode p : resp.path("data")) {
            String pos = p.path("pos").asText("0");
            if ("0".equals(pos) || pos.isEmpty()) continue;
            result.add(new SwapPosition(
                    p.path("instId").asText(),
                    pos,
                    p.path("avgPx").asText("0"),
                    p.path("upl").asText("0"),
                    p.path("posSide").asText("")
            ));
        }
        return result;
    }

    public record SwapPosition(String instId, String pos, String avgPx, String upl, String posSide) {
        /** 將 BTC-USDT-SWAP 轉換回 BTCUSDT */
        public String toSymbol() {
            return instId.replace("-USDT-SWAP", "USDT");
        }
    }

    /**
     * 查詢 SWAP OCO Algo 訂單詳情（instId 用合約格式 BTC-USDT-SWAP）。
     * 邏輯同 getAlgoOrder，僅 instId 格式不同。
     */
    public JsonNode getSwapAlgoOrder(String symbol, Long algoId) {
        String instId = toSwapInstId(symbol);
        String path = "/api/v5/trade/order-algo?ordType=oco&algoId=" + algoId + "&instId=" + instId;
        JsonNode resp = get(path);
        assertOkxCode(resp);
        return resp.path("data").path(0);
    }

    /**
     * 檢查帳戶的 SWAP 合約交易能力：帳戶模式、持倉模式、BTC/ETH 槓桿設定、USDT 保證金、現有 SWAP 持倉。
     * 回傳一個可讀的 Map，供管理端點直接回傳。
     */
    public java.util.LinkedHashMap<String, Object> checkSwapSupport() {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            // 帳戶模式
            JsonNode cfg = get("/api/v5/account/config");
            assertOkxCode(cfg);
            JsonNode acc = cfg.path("data").path(0);
            String acctLv = acc.path("acctLv").asText("?");
            String posMode = acc.path("posMode").asText("?");
            result.put("acctLv",  acctLv + " (1=Simple, 2=SingleCurrency, 3=MultiCurrency, 4=Portfolio)");
            result.put("posMode", posMode + " (long_short_mode=雙向 / net_mode=單向)");
            result.put("autoLoan", acc.path("autoLoan").asText("?"));
        } catch (Exception e) {
            result.put("acctConfig_error", e.getMessage());
        }

        // BTC SWAP 槓桿
        for (String instId : List.of("BTC-USDT-SWAP", "ETH-USDT-SWAP")) {
            try {
                JsonNode lev = get("/api/v5/account/leverage-info?instId=" + instId + "&mgnMode=cross");
                assertOkxCode(lev);
                java.util.List<String> rows = new java.util.ArrayList<>();
                for (JsonNode item : lev.path("data")) {
                    rows.add("posSide=" + item.path("posSide").asText() +
                             " lever=" + item.path("lever").asText() + "x");
                }
                result.put(instId + "_leverage_cross", rows);
            } catch (Exception e) {
                result.put(instId + "_leverage_error", e.getMessage());
            }
        }

        // USDT 保證金
        try {
            JsonNode bal = get("/api/v5/account/balance?ccy=USDT");
            assertOkxCode(bal);
            for (JsonNode d : bal.path("data").path(0).path("details")) {
                if ("USDT".equals(d.path("ccy").asText())) {
                    result.put("usdt_availBal", d.path("availBal").asText());
                    result.put("usdt_frozen",   d.path("frozenBal").asText());
                }
            }
        } catch (Exception e) {
            result.put("balance_error", e.getMessage());
        }

        // 現有 SWAP 持倉
        try {
            JsonNode pos = get("/api/v5/account/positions?instType=SWAP");
            assertOkxCode(pos);
            java.util.List<String> positions = new java.util.ArrayList<>();
            for (JsonNode p : pos.path("data")) {
                positions.add(p.path("instId").asText() + " pos=" + p.path("pos").asText() +
                              " avgPx=" + p.path("avgPx").asText() + " upl=" + p.path("upl").asText());
            }
            result.put("swap_positions", positions.isEmpty() ? List.of("（無 SWAP 持倉）") : positions);
        } catch (Exception e) {
            result.put("swap_positions_error", e.getMessage());
        }

        return result;
    }

    /** 查詢 Unified 交易帳戶可用 USDT 餘額（"N/A" 表示帳戶裡沒有 USDT）。 */
    public String getUsdtBalance() {
        String path = "/api/v5/account/balance?ccy=USDT";
        JsonNode resp = get(path);
        assertOkxCode(resp);
        JsonNode details = resp.path("data").path(0).path("details");
        if (details.isArray()) {
            for (JsonNode d : details) {
                if ("USDT".equals(d.path("ccy").asText())) {
                    return d.path("availBal").asText();
                }
            }
        }
        return "N/A";
    }

    /**
     * 查詢所有現貨持倉（cashBal > 0 的幣種），包含 USDT 及其他幣種。
     * 使用無 ccy 篩選的 /api/v5/account/balance 端點取得完整帳戶餘額。
     */
    public List<SpotHolding> getSpotHoldings() {
        return loadSpotHoldings(false);
    }

    /**
     * Bypasses both success and stale caches. Cancel-and-close flows use this after
     * releasing OCO-frozen quantity so they never decide from a pre-cancel balance.
     */
    public List<SpotHolding> getFreshSpotHoldings() {
        return loadSpotHoldings(true);
    }

    private List<SpotHolding> loadSpotHoldings(boolean forceRefresh) {
        long nowMs = System.currentTimeMillis();
        if (!forceRefresh) {
            List<SpotHolding> cached = cachedHoldings(
                    spotHoldingsCache, nowMs, ACCOUNT_HOLDINGS_SUCCESS_CACHE_TTL_MS);
            if (cached != null) {
                return cached;
            }
        }
        try {
            String path = "/api/v5/account/balance";
            throttleAccountHoldingsLookup();
            JsonNode resp = get(path);
            assertOkxCode(resp);
            JsonNode details = resp.path("data").path(0).path("details");
            List<SpotHolding> result = new ArrayList<>();
            if (details.isArray()) {
                for (JsonNode d : details) {
                    BigDecimal cashBal = new BigDecimal(d.path("cashBal").asText("0"));
                    if (cashBal.compareTo(BigDecimal.ZERO) > 0) {
                        result.add(new SpotHolding(
                                d.path("ccy").asText(),
                                new BigDecimal(d.path("availBal").asText("0")),
                                cashBal,
                                new BigDecimal(d.path("eqUsd").asText("0"))
                        ));
                    }
                }
            }
            spotHoldingsCache = new HoldingsCacheEntry(List.copyOf(result), System.currentTimeMillis());
            return List.copyOf(result);
        } catch (Exception e) {
            if (!forceRefresh) {
                List<SpotHolding> stale = cachedHoldings(
                        spotHoldingsCache, nowMs, ACCOUNT_HOLDINGS_STALE_CACHE_TTL_MS);
                if (stale != null) {
                    log.info("[OKX] getSpotHoldings failed; using recent cached holdings snapshot: {}", e.getMessage());
                    return stale;
                }
            }
            log.warn("[OKX] getSpotHoldings failed forceRefresh={}: {}", forceRefresh, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 查詢 OKX Funding Account（資金帳戶）所有幣種的可用餘額。
     * 路徑：/api/v5/asset/balances（無 ccy 篩選 = 所有幣種）。
     *
     * <p>資金帳戶與交易帳戶（Unified Trading Account, type=18）、賺幣帳戶（Earn）並列。
     * 回傳 SpotHolding 重用既有 record；availBal=cashBal=可用餘額（資金帳戶 API 沒有 cashBal 概念）；
     * eqUsd 用 getLastPrice 估算，USDT/USDC 直接 1:1。
     *
     * <p>回應 JSON：
     * <pre>{"code":"0","data":[{"ccy":"USDT","bal":"83.6","availBal":"83.6","frozenBal":"0"}, ...]}</pre>
     *
     * <p>Issue #155: getCurrentReport / getBalance 漏算這個帳戶導致總資產低估。
     */
    public List<SpotHolding> getFundingHoldings() {
        long nowMs = System.currentTimeMillis();
        List<SpotHolding> cached = cachedHoldings(fundingHoldingsCache, nowMs, ACCOUNT_HOLDINGS_SUCCESS_CACHE_TTL_MS);
        if (cached != null) {
            return cached;
        }
        try {
            throttleAccountHoldingsLookup();
            JsonNode resp = get("/api/v5/asset/balances");
            assertOkxCode(resp);
            JsonNode data = resp.path("data");
            List<SpotHolding> result = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode d : data) {
                    BigDecimal availBal = new BigDecimal(d.path("availBal").asText("0"));
                    if (availBal.compareTo(BigDecimal.ZERO) <= 0) continue;
                    String ccy = d.path("ccy").asText();
                    BigDecimal eqUsd = estimateUsdValue(ccy, availBal);
                    result.add(new SpotHolding(ccy, availBal, availBal, eqUsd));
                }
            }
            fundingHoldingsCache = new HoldingsCacheEntry(List.copyOf(result), System.currentTimeMillis());
            return List.copyOf(result);
        } catch (Exception e) {
            List<SpotHolding> stale = cachedHoldings(fundingHoldingsCache, nowMs, ACCOUNT_HOLDINGS_STALE_CACHE_TTL_MS);
            if (stale != null) {
                log.info("[OKX] getFundingHoldings failed; using recent cached holdings snapshot: {}", e.getMessage());
                return stale;
            }
            log.warn("[OKX] getFundingHoldings failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SpotHolding> cachedHoldings(HoldingsCacheEntry entry, long nowMs, long ttlMs) {
        if (entry == null || nowMs - entry.cachedAtMs() > ttlMs) {
            return null;
        }
        return List.copyOf(entry.holdings());
    }

    /** 將幣種數量估算為 USD：USDT/USDC 1:1，其他幣種透過 getLastPrice(ccy+"USDT") 換算。失敗回傳 0。 */
    private BigDecimal estimateUsdValue(String ccy, BigDecimal amount) {
        if (ccy == null || amount == null) return BigDecimal.ZERO;
        if ("USDT".equalsIgnoreCase(ccy) || "USDC".equalsIgnoreCase(ccy) || "USD".equalsIgnoreCase(ccy)) {
            return amount;
        }
        try {
            BigDecimal px = getLastPrice(ccy + "USDT");
            if (px == null) return BigDecimal.ZERO;
            return amount.multiply(px);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal decimalOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value);
    }

    /** 帳戶中單一幣種的現貨持倉快照。 */
    public static class SpotHolding {
        public final String ccy;
        public final BigDecimal availBal;
        public final BigDecimal cashBal;
        public final BigDecimal eqUsd;

        public SpotHolding(String ccy, BigDecimal availBal, BigDecimal cashBal, BigDecimal eqUsd) {
            this.ccy = ccy;
            this.availBal = availBal;
            this.cashBal = cashBal;
            this.eqUsd = eqUsd;
        }
    }

    public record SpotInstrumentRules(
            String instId,
            BigDecimal minSize,
            BigDecimal lotSize,
            BigDecimal tickSize
    ) {
    }

    public record GridRetirementQuantity(
            String instId,
            String buyOrderId,
            BigDecimal databaseGrossQty,
            BigDecimal providerGrossQty,
            BigDecimal signedBuyFee,
            String feeCurrency,
            BigDecimal netAttributableQty,
            BigDecimal sellQuantity,
            BigDecimal attributionDust,
            BigDecimal lotSize
    ) {
    }

    // ──────────────────────────────────────────────
    //  TradingService 實作
    // ──────────────────────────────────────────────

    /**
     * 市價買入（以 USDT 金額計）。
     * 下單後輪詢至 filled 狀態，取得實際成交均價與數量。
     */
    @Override
    public TradeResult placeMarketBuy(String symbol, double usdtAmount) {
        checkEnabled();
        String instId = toInstId(symbol);
        String body = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"cash\",\"side\":\"buy\"," +
                "\"ordType\":\"market\",\"tgtCcy\":\"quote_ccy\",\"sz\":\"%.2f\"}",
                instId, usdtAmount);

        JsonNode resp = post("/api/v5/trade/order", body);
        assertOkxCode(resp);

        String ordId = resp.path("data").path(0).path("ordId").asText();
        log.info("[OKX] Market buy placed: instId={} ordId={}", instId, ordId);
        return pollForFill(instId, ordId, SpotOrderSide.BUY);
    }

    /**
     * 掛 OCO Algo 訂單（止盈 market + 止損 market）。
     * 回傳 algoId（Long），儲存於 BtLiveSignal.ocoOrderListId。
     */
    @Override
    public Long placeOco(String symbol, BigDecimal qty, BigDecimal tp, BigDecimal sl) {
        checkEnabled();
        String instId = toInstId(symbol);
        String body = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"cash\",\"side\":\"sell\",\"ordType\":\"oco\"," +
                "\"sz\":\"%s\",\"tpTriggerPx\":\"%s\",\"tpOrdPx\":\"-1\"," +
                "\"slTriggerPx\":\"%s\",\"slOrdPx\":\"-1\"}",
                instId, qty.toPlainString(), tp.toPlainString(), sl.toPlainString());

        JsonNode resp = post("/api/v5/trade/order-algo", body);
        assertOkxCode(resp);

        String algoId = resp.path("data").path(0).path("algoId").asText();
        Long parsedAlgoId = Long.parseLong(algoId);
        spotHoldingsCache = null;
        cacheAlgoOrder(algoOrderCacheKey(instId, parsedAlgoId), syntheticLiveAlgoOrder(instId, parsedAlgoId));
        log.info("[OKX] OCO algo placed: instId={} algoId={} tp={} sl={}", instId, algoId, tp, sl);
        return parsedAlgoId;
    }

    /**
     * 取消 OCO Algo 訂單。
     * 若訂單已成交或不存在，拋出含 OKX sCode 的 RuntimeException（供 autoSell 判斷）。
     */
    @Override
    public void cancelOco(String symbol, Long ocoId) {
        checkEnabled();
        String instId = toInstId(symbol);
        String body = String.format("[{\"algoId\":\"%d\",\"instId\":\"%s\"}]", ocoId, instId);
        String cacheKey = algoOrderCacheKey(instId, ocoId);
        algoOrderCache.remove(cacheKey);
        try {
            JsonNode resp = post("/api/v5/trade/cancel-algos", body);
            assertOkxCode(resp);

            // cancel-algos 的個別錯誤在 data[0].sCode
            JsonNode item = resp.path("data").path(0);
            String sCode = item.path("sCode").asText("0");
            if (!"0".equals(sCode)) {
                String sMsg = item.path("sMsg").asText();
                throw new RuntimeException("OKX cancel algo [sCode=" + sCode + "]: " + sMsg);
            }
        } finally {
            // A cached pre-cancel live state must never be used as cancel confirmation.
            algoOrderCache.remove(cacheKey);
            spotHoldingsCache = null;
        }
        log.info("[OKX] OCO cancelled: instId={} algoId={}", instId, ocoId);
    }

    /**
     * 市價賣出（以數量計），輪詢取得成交均價後回傳。
     */
    @Override
    public BigDecimal placeMarketSell(String symbol, BigDecimal qty) {
        return placeMarketSellWithFill(symbol, qty).getAvgPrice();
    }

    /**
     * #399 — Same as {@link #placeMarketSell} but returns the full {@link TradeResult}
     * so callers can detect partial fills by comparing requested {@code qty} vs the
     * returned {@link TradeResult#getQty()}. Existing {@link #placeMarketSell} kept
     * for back-compat (callers that only need avgPrice).
     *
     * <p>{@link #pollForFill} only returns when OKX state == "filled". For market sells
     * with insufficient liquidity, OKX may report "filled" with a {@code accFillSz}
     * smaller than the requested {@code sz} — that's the partial-fill case.
     */
    public TradeResult placeMarketSellWithFill(String symbol, BigDecimal qty) {
        checkEnabled();
        String instId = toInstId(symbol);
        String body = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"cash\",\"side\":\"sell\"," +
                "\"ordType\":\"market\",\"sz\":\"%s\"}",
                instId, qty.toPlainString());

        JsonNode resp = post("/api/v5/trade/order", body);
        assertOkxCode(resp);

        String ordId = resp.path("data").path(0).path("ordId").asText();
        log.info("[OKX] Market sell placed: instId={} ordId={}", instId, ordId);
        return pollForFill(instId, ordId, SpotOrderSide.SELL);
    }

    // ──────────────────────────────────────────────
    //  SWAP 合約空單（做空）
    // ──────────────────────────────────────────────

    /**
     * SWAP 合約做空入場（市價賣空）。
     * 自動依 usdtAmount × swapLeverage 計算合約數量。
     * @return TradeResult：qty = 合約數（BigDecimal）、avgPrice = 成交均價
     */
    public TradeResult placeSwapShortEntry(String symbol, double usdtAmount) {
        checkEnabled();
        String instId   = toSwapInstId(symbol);
        String tdMode   = props.getSwapTdMode();
        BigDecimal lastPx = getLastPrice(symbol);
        if (lastPx == null) {
            throw new RuntimeException("[OKX SWAP] 無法取得最新價格: symbol=" + symbol);
        }
        double price    = lastPx.doubleValue();
        long contracts  = calcSwapContracts(symbol, usdtAmount, price);
        if (contracts < 1) {
            throw new IllegalArgumentException(
                    "[OKX SWAP] 倉位金額不足以開1張合約: symbol=" + symbol + " usdt=" + usdtAmount);
        }
        String body = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"%s\",\"side\":\"sell\",\"ordType\":\"market\",\"sz\":\"%d\"}",
                instId, tdMode, contracts);
        JsonNode resp = post("/api/v5/trade/order", body);
        assertOkxCode(resp);
        String ordId = resp.path("data").path(0).path("ordId").asText();
        log.info("[OKX] SWAP short entry: instId={} contracts={} ordId={}", instId, contracts, ordId);
        return pollForSwapFill(instId, ordId);
    }

    /**
     * SWAP 合約平空（市價買回）。
     * @param contractQty 要平掉的合約數（由 BtLiveSignal.tradedQty 取得）
     */
    public TradeResult placeSwapShortExit(String symbol, BigDecimal contractQty) {
        checkEnabled();
        String instId = toSwapInstId(symbol);
        String tdMode = props.getSwapTdMode();
        String body = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"%s\",\"side\":\"buy\",\"ordType\":\"market\",\"sz\":\"%d\"}",
                instId, tdMode, contractQty.longValue());
        JsonNode resp = post("/api/v5/trade/order", body);
        assertOkxCode(resp);
        String ordId = resp.path("data").path(0).path("ordId").asText();
        log.info("[OKX] SWAP short exit: instId={} contracts={} ordId={}", instId, contractQty, ordId);
        return pollForSwapFill(instId, ordId);
    }

    /**
     * SWAP 合約空單 OCO（買回方向：tp < entry，sl > entry）。
     * @return algoId 供後續取消或查詢
     */
    public Long placeSwapOco(String symbol, BigDecimal contractQty, BigDecimal tp, BigDecimal sl) {
        checkEnabled();
        String instId = toSwapInstId(symbol);
        String tdMode = props.getSwapTdMode();
        String body = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"%s\",\"side\":\"buy\",\"ordType\":\"oco\"," +
                "\"sz\":\"%d\",\"tpTriggerPx\":\"%s\",\"tpOrdPx\":\"-1\"," +
                "\"slTriggerPx\":\"%s\",\"slOrdPx\":\"-1\"}",
                instId, tdMode, contractQty.longValue(), tp.toPlainString(), sl.toPlainString());
        JsonNode resp = post("/api/v5/trade/order-algo", body);
        assertOkxCode(resp);
        String algoId = resp.path("data").path(0).path("algoId").asText();
        log.info("[OKX] SWAP OCO: instId={} contracts={} algoId={} tp={} sl={}",
                instId, contractQty, algoId, tp, sl);
        return Long.parseLong(algoId);
    }

    /** 取消 SWAP Algo 訂單（直接呼叫 cancel-algos，instId 使用 SWAP 格式）。 */
    public void cancelSwapOco(String symbol, Long algoId) {
        checkEnabled();
        String instId = toSwapInstId(symbol);
        String body = String.format("[{\"algoId\":\"%d\",\"instId\":\"%s\"}]", algoId, instId);
        JsonNode resp = post("/api/v5/trade/cancel-algos", body);
        assertOkxCode(resp);
        JsonNode item = resp.path("data").path(0);
        String sCode = item.path("sCode").asText("0");
        if (!"0".equals(sCode)) {
            String sMsg = item.path("sMsg").asText();
            throw new RuntimeException("OKX cancel SWAP algo [sCode=" + sCode + "]: " + sMsg);
        }
        log.info("[OKX] SWAP OCO cancelled: instId={} algoId={}", instId, algoId);
    }

    // ──────────────────────────────────────────────
    //  內部工具
    // ──────────────────────────────────────────────

    /**
     * 輪詢訂單直到 filled，最多 MAX_FILL_RETRIES 次，每次間隔 FILL_RETRY_MS。
     * 市價單通常 500ms 內成交；若超時拋出 RuntimeException。
     */
    private TradeResult pollForFill(String instId, String ordId, SpotOrderSide side) {
        for (int i = 0; i < MAX_FILL_RETRIES; i++) {
            try { Thread.sleep(FILL_RETRY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            JsonNode order = queryOrder(instId, ordId);
            String state = order.path("state").asText();
            if ("filled".equals(state)) {
                TradeResult r = new TradeResult();
                r.setOrderId(ordId);
                r.setAvgPrice(new BigDecimal(order.path("avgPx").asText()));

                // accFillSz 是扣費前毛量；fillFee 是手續費（買方為負數，幣別為 fillFeeCcy）。
                // 用 accFillSz + fillFee 取得實際入帳淨量，避免 OCO 掛單超過可用餘額。
                BigDecimal gross = new BigDecimal(order.path("accFillSz").asText());
                String feeCcyStr = order.path("fillFeeCcy").asText("");
                String feeStr    = order.path("fillFee").asText("0");

                // OKX 的費用欄位（fillFee/fillFeeCcy）有時非同步填入，
                // 在 state=filled 後立即查詢可能拿到空值。最多重試 5 次（各 300ms）。
                for (int feeRetry = 0; feeRetry < 5 && feeCcyStr.isEmpty(); feeRetry++) {
                    try { Thread.sleep(300); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    order = queryOrder(instId, ordId);
                    feeCcyStr = order.path("fillFeeCcy").asText("");
                    feeStr    = order.path("fillFee").asText("0");
                    log.info("[OKX] Fee retry {}: ordId={} feeCcy={} fee={}", feeRetry + 1, ordId, feeCcyStr, feeStr);
                }
                if (feeCcyStr.isEmpty()) {
                    log.warn("[OKX] Fee fields still empty after 5 retries, will use spot balance fallback: ordId={}", ordId);
                }

                String instBase  = instId.split("-")[0]; // "ETH-USDT" → "ETH"
                BigDecimal feeAmount;
                try {
                    feeAmount = new BigDecimal(feeStr);
                } catch (NumberFormatException ignored) {
                    feeAmount = BigDecimal.ZERO;
                }
                BigDecimal netQty;
                if (side == SpotOrderSide.SELL) {
                    // accFillSz is the base quantity sold. Quote-currency fees do not
                    // reduce that filled quantity and must not trigger the buy-side
                    // spot-balance fallback after the position has already been sold.
                    netQty = gross;
                } else if (instBase.equals(feeCcyStr) && !feeCcyStr.isEmpty()) {
                    // 費用以基幣扣收（最常見）：淨量 = gross + fee（fee 為負）
                    // 使用 gross.scale() 截尾以對齊 OKX lot size 精度，避免小數位過多被拒絕。
                    netQty = gross.add(feeAmount).setScale(gross.scale(), java.math.RoundingMode.DOWN);
                } else if (!feeCcyStr.isEmpty()) {
                    // Quote/discount-token fees do not reduce the base quantity received.
                    netQty = gross;
                } else {
                    // An account balance cannot identify which BTC belongs to this fill when
                    // other holdings exist. Reserve a conservative fee buffer instead of
                    // risking an oversized OCO that consumes pre-existing BTC.
                    netQty = gross.multiply(BigDecimal.ONE.subtract(UNKNOWN_BUY_FEE_QTY_BUFFER_RATE))
                            .setScale(gross.scale(), java.math.RoundingMode.DOWN);
                    log.warn("[OKX] Buy fee unavailable; using conservative net quantity: ordId={} gross={} netQty={}",
                            ordId, gross, netQty);
                }
                r.setQty(netQty);
                r.setGrossQty(gross);
                r.setNetQty(netQty);
                r.setFeeAmount(feeAmount);
                r.setFeeCurrency(feeCcyStr.isBlank() ? null : feeCcyStr);
                r.setFeeUsdt(normalizeSpotFeeUsdt(instBase, r.getAvgPrice(), feeAmount, feeCcyStr));
                spotHoldingsCache = null;
                log.info("[OKX] Order filled: ordId={} avgPx={} grossQty={} fee={} feeCcy={} netQty={}",
                        ordId, r.getAvgPrice(), gross, feeStr, feeCcyStr, netQty);
                return r;
            }
            log.debug("[OKX] Order not filled yet: ordId={} state={} attempt={}", ordId, state, i + 1);
        }
        throw new RuntimeException("OKX order not filled after " + MAX_FILL_RETRIES + " retries: ordId=" + ordId);
    }

    private BigDecimal normalizeSpotFeeUsdt(String baseCurrency,
                                            BigDecimal avgPrice,
                                            BigDecimal signedFee,
                                            String feeCurrency) {
        if (signedFee == null || feeCurrency == null || feeCurrency.isBlank()) {
            return null;
        }
        BigDecimal absolute = signedFee.abs();
        if ("USDT".equalsIgnoreCase(feeCurrency)) {
            return absolute;
        }
        if (baseCurrency.equalsIgnoreCase(feeCurrency) && avgPrice != null) {
            return absolute.multiply(avgPrice).setScale(8, java.math.RoundingMode.HALF_UP);
        }
        return null;
    }

    private enum SpotOrderSide {
        BUY,
        SELL
    }

    private JsonNode queryOrder(String instId, String ordId) {
        String path = "/api/v5/trade/order?instId=" + instId + "&ordId=" + ordId;
        JsonNode resp = get(path);
        assertOkxCode(resp);
        return resp.path("data").path(0);
    }

    /** BTCUSDT → BTC-USDT（OKX 現貨交易對格式） */
    static String toInstId(String symbol) {
        if (symbol != null && symbol.endsWith("-USDT")) {
            return symbol;
        }
        if (symbol != null && symbol.endsWith("USDT") && symbol.length() > 4) {
            return symbol.substring(0, symbol.length() - 4) + "-USDT";
        }
        return symbol;
    }

    /** BTCUSDT → BTC-USDT-SWAP（OKX 永續合約格式） */
    private String toSwapInstId(String symbol) {
        if (symbol != null && symbol.endsWith("USDT") && symbol.length() > 4) {
            return symbol.substring(0, symbol.length() - 4) + "-USDT-SWAP";
        }
        return symbol + "-SWAP";
    }

    /**
     * 查詢 SWAP 普通訂單詳情（用於確認 OCO 子單是否成交）。
     * 供 OcoPositionPollerScheduler 偵測 OKX SWAP OCO parent=effective / child=filled 的情形。
     */
    public JsonNode querySwapOrderDetail(String symbol, String ordId) {
        return queryOrder(toSwapInstId(symbol), ordId);
    }

    /**
     * 查詢 SPOT 普通訂單詳情（用於確認 OCO 子單是否成交）。
     * 供 OcoPositionPollerScheduler 偵測 OKX SPOT OCO parent=effective / child=filled 的情形。
     * (#285 root-cause fix: SPOT path had the same OKX "parent stays effective after fill" bug as SWAP,
     *  but the child-order cross-check was only implemented for SWAP.)
     */
    public JsonNode querySpotOrderDetail(String symbol, String ordId) {
        return queryOrder(toInstId(symbol), ordId);
    }

    /** 每張合約對應的基幣數量（BTC=0.01, ETH=0.1, 其餘預設 0.01） */
    public double getContractSizeInBase(String symbol) {
        if (symbol != null && symbol.startsWith("BTC")) return 0.01;
        if (symbol != null && symbol.startsWith("ETH")) return 0.1;
        return 0.01;
    }

    /** 計算合約張數：usdtAmount × leverage ÷ (price × contractSize per lot)，最少 1 張 */
    private long calcSwapContracts(String symbol, double usdtAmount, double price) {
        double contractValueUsdt = price * getContractSizeInBase(symbol);
        return Math.max(1L, (long) (usdtAmount * props.getSwapLeverage() / contractValueUsdt));
    }

    /**
     * 輪詢 SWAP 訂單直到 filled，回傳 qty = 合約張數（整數 BigDecimal）。
     * 邏輯同 pollForFill，但 SWAP 不扣基幣手續費，直接使用 accFillSz。
     */
    private TradeResult pollForSwapFill(String instId, String ordId) {
        for (int i = 0; i < MAX_FILL_RETRIES; i++) {
            try { Thread.sleep(FILL_RETRY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            JsonNode order = queryOrder(instId, ordId);
            String state = order.path("state").asText();
            if ("filled".equals(state)) {
                TradeResult r = new TradeResult();
                r.setOrderId(ordId);
                r.setAvgPrice(new BigDecimal(order.path("avgPx").asText()));
                r.setQty(new BigDecimal(order.path("accFillSz").asText()));
                log.info("[OKX] SWAP order filled: ordId={} avgPx={} contracts={}",
                        ordId, r.getAvgPrice(), r.getQty());
                return r;
            }
            log.debug("[OKX] SWAP order not filled yet: ordId={} state={} attempt={}", ordId, state, i + 1);
        }
        throw new RuntimeException("OKX SWAP order not filled after " + MAX_FILL_RETRIES + " retries: ordId=" + ordId);
    }

    /** 檢查 OKX 頂層 code，非 "0" 則拋出（包含 data[0].sCode 詳細原因）。 */
    private void assertOkxCode(JsonNode resp) {
        String code = resp.path("code").asText("0");
        if (!"0".equals(code)) {
            JsonNode first = resp.path("data").path(0);
            String sCode = first.path("sCode").asText("");
            String sMsg  = first.path("sMsg").asText("");
            String detail = sCode.isEmpty() ? "" : " [sCode=" + sCode + " sMsg=" + sMsg + "]";
            throw new RuntimeException("OKX API error [code=" + code + "]: " + resp.path("msg").asText() + detail);
        }
    }

    /** POST（JSON body） */
    private JsonNode post(String path, String jsonBody) {
        String ts = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        RequestBody body = RequestBody.create(jsonBody, JSON_TYPE);
        Request req = new Request.Builder()
                .url(props.getBaseUrl() + path)
                .headers(buildHeaders(ts, "POST", path, jsonBody))
                .post(body)
                .build();
        return execute(req, path);
    }

    /** GET（無 body） */
    private JsonNode get(String path) {
        String ts = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        Request req = new Request.Builder()
                .url(props.getBaseUrl() + path)
                .headers(buildHeaders(ts, "GET", path, ""))
                .get()
                .build();
        return execute(req, path);
    }

    /** GET for OKX public endpoints. Public market data does not require private API credentials. */
    private JsonNode getPublic(String path) {
        Request req = new Request.Builder()
                .url(props.getBaseUrl() + path)
                .get()
                .build();
        return execute(req, path);
    }

    private okhttp3.Headers buildHeaders(String timestamp, String method, String path, String body) {
        if (!props.hasPrivateCredentials()) {
            throw new IllegalStateException(
                    "OKX private API credentials are not configured (trading.okx.api-key/secret-key/passphrase)");
        }
        return new okhttp3.Headers.Builder()
                .add("OK-ACCESS-KEY",        props.getApiKey())
                .add("OK-ACCESS-SIGN",       sign(timestamp, method, path, body))
                .add("OK-ACCESS-TIMESTAMP",  timestamp)
                .add("OK-ACCESS-PASSPHRASE", props.getPassphrase())
                .add("Content-Type",         "application/json")
                .build();
    }

    private JsonNode execute(Request req, String path) {
        try (Response resp = httpClient.newCall(req).execute()) {
            String bodyStr = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new RuntimeException("OKX HTTP " + resp.code() + " [" + path + "]: " + bodyStr);
            }
            return objectMapper.readTree(bodyStr);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OKX call failed [" + path + "]: " + e.getMessage(), e);
        }
    }

    /**
     * OKX 簽名：Base64( HMAC-SHA256( timestamp + METHOD + requestPath + body ) )
     * 注意：是 Base64 編碼，不是 Binance 的 hex。
     */
    private String sign(String timestamp, String method, String path, String body) {
        try {
            String prehash = timestamp + method + path + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(prehash.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("OKX signing failed", e);
        }
    }

    private void checkEnabled() {
        if (!props.isEnabled()) {
            throw new IllegalStateException("OKX auto-trade disabled (trading.okx.enabled=false)");
        }
    }
}
