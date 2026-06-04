package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 從 OKX 訂單簿計算委託量不平衡率（orderbook imbalance）。
 *
 * <p><b>定義</b>：{@code imbalance = (Σ top-N bid size − Σ top-N ask size) / total}
 * <br>範圍 [-1, +1]：
 * <ul>
 *   <li>+1 → 全部是買單（極端買盤堆積）</li>
 *   <li>0  → 平衡</li>
 *   <li>-1 → 全部是賣單（極端賣盤堆積）</li>
 * </ul>
 *
 * <p><b>相對於 Taker Volume 的優勢</b>：Taker volume 是「已成交」的資金流，是 lagging
 * indicator。Orderbook imbalance 反映「掛單意圖」，在價格移動前 seconds ~ minutes 就會
 * 出現堆積/抽單，是相對 leading 的指標。
 *
 * <p><b>取樣深度</b>：top 20 levels（約涵蓋 BTC ±0.3%、ETH ±0.5% 的價格帶）。深度太淺
 * 易受單筆掛單影響、太深則稀釋短期意圖信號。
 *
 * <p><b>快取</b>：5 分鐘。訂單簿變化快但 5 分鐘粒度已足夠讓 1h bar 的決策使用。
 *
 * <p><b>限制</b>：OKX 只提供當前快照，無歷史 API。回測（applyFilters）中此規則自動跳過。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderbookImbalanceService {

    private static final String OKX_BASE = "https://www.okx.com";
    private static final int DEPTH_LEVELS = 20;

    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    /**
     * 取得當前 orderbook imbalance。
     *
     * @param symbol 交易對（如 BTCUSDT）
     * @return imbalance 值 [-1, +1]；API 失敗時回傳 0（中立，不觸發封鎖）
     */
    @Cacheable(value = "orderbookImbalance", key = "#symbol")
    public double getImbalance(String symbol) {
        String instId = toOkxSpotInstId(symbol);
        String url = OKX_BASE + "/api/v5/market/books?instId=" + instId + "&sz=" + DEPTH_LEVELS;
        try {
            Request req = new Request.Builder().url(url).build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("[Orderbook] OKX HTTP {} for {}", resp.code(), symbol);
                    return 0;
                }
                JsonNode root = objectMapper.readTree(resp.body().string());
                if (!"0".equals(root.path("code").asText())) {
                    log.warn("[Orderbook] OKX error for {}: {}", symbol, root.path("msg").asText());
                    return 0;
                }
                JsonNode data = root.path("data").path(0);
                if (data.isMissingNode()) return 0;

                double bidSize = sumSize(data.path("bids"));
                double askSize = sumSize(data.path("asks"));
                double total = bidSize + askSize;
                if (total <= 0) return 0;

                double imbalance = (bidSize - askSize) / total;
                log.info("[Orderbook] {} imbalance={} (bid={} ask={} top={})",
                        symbol, String.format("%.3f", imbalance),
                        String.format("%.3f", bidSize), String.format("%.3f", askSize),
                        DEPTH_LEVELS);
                return imbalance;
            }
        } catch (Exception e) {
            log.warn("[Orderbook] Failed to fetch {}: {}", symbol, e.getMessage());
            return 0;
        }
    }

    /**
     * Estimates the market-order slippage for buying {@code usdtAmount} of {@code symbol}.
     *
     * <p>Walks through the top ask levels in the order book and computes the volume-weighted
     * average fill price. Returns the slippage as a fraction of the best ask price.
     *
     * <p>Example: bestAsk=$100,000, avgFill=$100,050 → slippage = 0.0005 (0.05%)
     *
     * @param symbol      trading pair (e.g., BTCUSDT)
     * @param usdtAmount  size of the intended market buy in USDT
     * @return estimated slippage [0, ∞); returns 0 on API failure (fail-open)
     */
    public double estimateSlippagePct(String symbol, double usdtAmount) {
        String instId = toOkxSpotInstId(symbol);
        String url = OKX_BASE + "/api/v5/market/books?instId=" + instId + "&sz=" + DEPTH_LEVELS;
        try {
            Request req = new Request.Builder().url(url).build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return 0;
                JsonNode root = objectMapper.readTree(resp.body().string());
                if (!"0".equals(root.path("code").asText())) return 0;
                JsonNode data = root.path("data").path(0);
                if (data.isMissingNode()) return 0;

                JsonNode asks = data.path("asks");
                if (!asks.isArray() || asks.size() == 0) return 0;

                double bestAsk = asks.get(0).get(0).asDouble(0);
                if (bestAsk <= 0) return 0;

                // Walk through ask levels to simulate filling usdtAmount
                double remaining = usdtAmount;
                double totalCost = 0;
                double totalQty  = 0;
                for (JsonNode lvl : asks) {
                    if (!lvl.isArray() || lvl.size() < 2 || remaining <= 0) break;
                    double price   = lvl.get(0).asDouble(0);
                    double qty     = lvl.get(1).asDouble(0);
                    double lvlCost = price * qty;
                    if (lvlCost >= remaining) {
                        double fillQty = remaining / price;
                        totalCost += remaining;
                        totalQty  += fillQty;
                        remaining  = 0;
                    } else {
                        totalCost += lvlCost;
                        totalQty  += qty;
                        remaining -= lvlCost;
                    }
                }

                if (totalQty <= 0) return 0;
                double avgFillPrice = totalCost / totalQty;
                double slippage     = (avgFillPrice - bestAsk) / bestAsk;
                log.debug("[Orderbook] {} slippage={:.5f} ({:.4f}%) for ${} (bestAsk={} avgFill={})",
                        symbol, slippage, slippage * 100, usdtAmount,
                        String.format("%.2f", bestAsk),
                        String.format("%.2f", avgFillPrice));
                return Math.max(0, slippage);  // clamp negatives (shouldn't happen)
            }
        } catch (Exception e) {
            log.warn("[Orderbook] estimateSlippagePct failed for {}: {}", symbol, e.getMessage());
            return 0;
        }
    }

    private double sumSize(JsonNode levels) {
        double sum = 0;
        if (!levels.isArray()) return 0;
        for (JsonNode lvl : levels) {
            if (lvl.isArray() && lvl.size() >= 2) {
                sum += lvl.get(1).asDouble(0);
            }
        }
        return sum;
    }

    /** BTCUSDT → BTC-USDT */
    private String toOkxSpotInstId(String symbol) {
        if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 4) + "-USDT";
        if (symbol.endsWith("BUSD")) return symbol.substring(0, symbol.length() - 4) + "-BUSD";
        return symbol;
    }
}
