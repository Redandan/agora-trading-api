package com.agora.service.market;

import com.agora.config.properties.MarketDataProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * Coinalyze API — 清算歷史 / 多空比（免費，40 req/min）。
 *
 * <h2>新增指標（寫入 market_indicator_history）</h2>
 * <ul>
 *   <li>{@code btc_short_liq_usd_1h} — 空頭清算金額（USD，過去 1h）。
 *       大幅攀升表示空頭被迫平倉（「殺空頭」信號）。</li>
 *   <li>{@code btc_long_liq_usd_1h} — 多頭清算金額（USD，過去 1h）。
 *       作為基準比較；若 short_liq >> long_liq 則擠壓信號更強。</li>
 *   <li>{@code btc_short_liq_ratio_1h} — short_liq / (short_liq + long_liq)。
 *       > 0.6 表示空頭清算主導（擠壓進行中）。</li>
 * </ul>
 *
 * <h2>Symbol 格式</h2>
 * Coinalyze 使用 {@code BTCUSDT_PERP.A} 代表全交易所聚合 BTC 永續合約。
 * ".A" 後綴 = Aggregated（Binance + OKX + Bybit 等主流所加總）。
 *
 * <h2>清算值單位</h2>
 * Coinalyze liquidation history 的 l/s 欄位單位為 **BTC 數量**（非 USD）。
 * 本 service 在回傳前乘以當前 BTC 價格轉換為 USD（由 MarketIndicatorHistoryCollector 提供）。
 * 若無法取得價格則直接以 BTC 數量存儲（需呼叫方注意）。
 *
 * <h2>Rate limit</h2>
 * 免費 40 req/min。hourly collector 每次呼叫 1-2 次，遠低於限制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoinalyzeService {

    private static final String SYMBOL = "BTCUSDT_PERP.A";
    private static final String BASE_URL = "https://api.coinalyze.net/v1";

    private final MarketDataProperties marketDataProps;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper om = new ObjectMapper();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * 清算歷史結構（最近一根已完成的 1h bar）。
     *
     * @param btcPriceUsd 用於將 BTC 數量轉換為 USD，若為 null 則使用 BTC 數量
     * @return LiquidationBar 或 null（API 失敗時）
     */
    public LiquidationBar getLatestLiquidation1h(Double btcPriceUsd) {
        String apiKey = marketDataProps.coinalyze().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("[Coinalyze] api-key not configured, skipping");
            return null;
        }
        try {
            // 查最近 2h，取倒數第 2 根（最近完整 bar）
            long to   = Instant.now(java.time.Clock.systemUTC()).getEpochSecond();
            long from = to - 7200; // 2 hours ago

            String url = BASE_URL + "/liquidation-history?symbols=" + SYMBOL
                    + "&interval=1hour&from=" + from + "&to=" + to;

            JsonNode data = get(url);
            if (data == null || !data.isArray() || data.isEmpty()) return null;

            JsonNode history = data.get(0).path("history");
            if (history.isEmpty()) return null;

            // Take the last completed bar (second-to-last if >1 bar, else last)
            int idx = history.size() > 1 ? history.size() - 2 : history.size() - 1;
            JsonNode bar = history.get(idx);

            double longLiqBtc  = bar.path("l").asDouble(0);
            double shortLiqBtc = bar.path("s").asDouble(0);
            long   ts          = bar.path("t").asLong();

            // Convert to USD if price available
            double multiplier  = btcPriceUsd != null && btcPriceUsd > 0 ? btcPriceUsd : 1.0;
            double longLiqUsd  = longLiqBtc  * multiplier;
            double shortLiqUsd = shortLiqBtc * multiplier;
            double total       = longLiqUsd  + shortLiqUsd;
            double shortRatio  = total > 0 ? shortLiqUsd / total : 0.5;

            double totalLiqUsd = longLiqUsd + shortLiqUsd;
            log.debug("[Coinalyze] liquidation bar t={} total={:.0f}USD short={:.0f}USD ratio={:.3f}",
                    ts, totalLiqUsd, shortLiqUsd, shortRatio);
            return new LiquidationBar(ts, longLiqUsd, shortLiqUsd, totalLiqUsd, shortRatio);

        } catch (Exception e) {
            log.warn("[Coinalyze] getLiquidation1h failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 多空比（最近完整 1h bar）。
     * r = long_accounts / short_accounts。
     * < 1.0 表示空頭帳戶佔多數（擠壓燃料充足）。
     *
     * @return ratio 值或 null
     */
    public Double getLatestLongShortRatio1h() {
        String apiKey = marketDataProps.coinalyze().apiKey();
        if (apiKey == null || apiKey.isBlank()) return null;
        try {
            long to   = Instant.now(java.time.Clock.systemUTC()).getEpochSecond();
            long from = to - 7200;

            String url = BASE_URL + "/long-short-ratio-history?symbols=" + SYMBOL
                    + "&interval=1hour&from=" + from + "&to=" + to;

            JsonNode data = get(url);
            if (data == null || !data.isArray() || data.isEmpty()) return null;

            JsonNode history = data.get(0).path("history");
            if (history.isEmpty()) return null;

            int idx   = history.size() > 1 ? history.size() - 2 : history.size() - 1;
            double r  = history.get(idx).path("r").asDouble(-1);
            return r < 0 ? null : r;

        } catch (Exception e) {
            log.warn("[Coinalyze] getLongShortRatio1h failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private JsonNode get(String url) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("api-key", marketDataProps.coinalyze().apiKey())
                .header("Accept", "application/json")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 429) {
                log.warn("[Coinalyze] rate limit hit (429), back off");
                return null;
            }
            if (!resp.isSuccessful()) {
                log.warn("[Coinalyze] HTTP {} for {}", resp.code(), url);
                return null;
            }
            String body = resp.body() != null ? resp.body().string() : "";
            return om.readTree(body);
        }
    }

    // ── Data record ───────────────────────────────────────────────────────────

    public record LiquidationBar(
            long    timestampEpochSec,
            double  longLiqUsd,
            double  shortLiqUsd,
            double  totalLiqUsd,    // longLiqUsd + shortLiqUsd (新增：總清算量判斷是否放大)
            double  shortLiqRatio   // shortLiq / totalLiq; > 0.6 = squeeze signal
    ) {}
}
