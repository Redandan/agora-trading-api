package com.agora.service.market;

import com.agora.config.properties.FredProperties;
import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.backtest.OiFundingDivergenceStrategy;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.market.HyperliquidService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Historical backfill for 4 market_indicator_history indicators (issue #202):
 * <ol>
 *   <li>{@code fear_greed}    — alternative.me, 365 daily values</li>
 *   <li>{@code funding_rate}  — OKX swap, 8h settlements, up to 100 records</li>
 *   <li>FRED macro series     — us_10y_yield / us_vix / us_sp500 / us_dxy, daily</li>
 *   <li>{@code btc_open_interest} — OKX rubik/stat, 1H granularity</li>
 * </ol>
 *
 * <p>Each method is idempotent: skips rows that already exist.
 * All methods block until completion (use via ApplicationRunner / MCP tool async wrapper).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorHistoryBackfillService {

    private final MarketIndicatorHistoryRepository historyRepo;
    private final FearGreedService fearGreedService;
    private final OkxTradingService okxTradingService;
    private final HyperliquidService hyperliquidService;
    private final ObjectMapper objectMapper;
    private final FredProperties fredProps;
    private final OiFundingDivergenceStrategy oiFundingDivergenceStrategy;

    private static final String FRED_BASE = "https://api.stlouisfed.org/fred/series/observations";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    // ── 1. Fear & Greed ───────────────────────────────────────────────────────

    public String backfillFearGreed(int days) {
        var entries = fearGreedService.getHistoricalFearGreed(Math.min(days, 365));
        int imported = 0, skipped = 0;
        for (var e : entries) {
            LocalDateTime ts = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(e.timestamp()), ZoneOffset.UTC)
                    .toLocalDate().atStartOfDay();
            if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt("BTCUSDT", "fear_greed", ts)) {
                skipped++;
                continue;
            }
            save("BTCUSDT", "fear_greed", ts, e.value());
            imported++;
        }
        return fmt("fear_greed", imported, skipped);
    }

    // ── 2. OKX Funding Rate (8h settlements) ─────────────────────────────────

    public String backfillFundingRate(String symbol, int limit) {
        int imported = 0, skipped = 0;
        try {
            JsonNode data = okxTradingService.getFundingRateHistory(symbol, Math.min(limit, 100));
            if (!data.isArray()) return "❌ no data from OKX";
            for (JsonNode item : data) {
                long ms = item.path("fundingTime").asLong(0);
                if (ms == 0) continue;
                double rate = item.path("fundingRate").asDouble(0);
                LocalDateTime ts = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(ms), ZoneOffset.UTC);
                if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt("BTCUSDT", "funding_rate", ts)) {
                    skipped++;
                    continue;
                }
                save("BTCUSDT", "funding_rate", ts, rate);
                imported++;
            }
        } catch (Exception e) {
            log.warn("[BackfillSvc] funding_rate failed: {}", e.getMessage());
            return "❌ " + e.getMessage();
        }
        if (imported > 0) oiFundingDivergenceStrategy.invalidateCache();
        return fmt("funding_rate", imported, skipped);
    }

    // ── #309 OKX long_short_ratio 歷史（1H，最多 1440 筆 = 60 天）────────────────

    public String backfillLongShortRatio(String symbol, int limit) {
        int lim = Math.min(limit, 1440);
        int imported = 0, skipped = 0;
        try {
            JsonNode data = okxTradingService.getLongShortRatioHistory(symbol, lim);
            if (!data.isArray()) return "❌ no data from OKX";
            for (JsonNode item : data) {
                long ms = item.get(0).asLong(0);
                if (ms == 0) continue;
                double ratio = item.get(1).asDouble(0);
                LocalDateTime ts = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
                if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt(symbol, "long_short_ratio", ts)) {
                    skipped++;
                    continue;
                }
                save(symbol, "long_short_ratio", ts, ratio);
                imported++;
            }
        } catch (Exception e) {
            log.warn("[BackfillSvc] long_short_ratio failed: {}", e.getMessage());
            return "❌ " + e.getMessage();
        }
        return fmt("long_short_ratio", imported, skipped);
    }

    // ── 3. FRED macro — 4 series ──────────────────────────────────────────────

    public String backfillFredSeries(int years) {
        if (fredProps.apiKey() == null || fredProps.apiKey().isBlank()) return "❌ external.fred.api-key not configured";
        String start = LocalDate.now(ZoneOffset.UTC).minusYears(years).toString();
        int totalImported = 0, totalSkipped = 0;
        String[][] series = {
            {"DGS10",     "us_10y_yield"},
            {"VIXCLS",    "us_vix"},
            {"SP500",     "us_sp500"},
            {"DTWEXBGS",  "us_dxy"},
        };
        for (String[] s : series) {
            int[] r = fetchAndSaveFredSeries(s[0], s[1], start);
            totalImported += r[0];
            totalSkipped  += r[1];
        }
        return String.format("=== FRED macro backfill ===\n%d years (%s)\n✅ imported: %d  ⏭ skipped: %d",
                years, start, totalImported, totalSkipped);
    }

    private int[] fetchAndSaveFredSeries(String seriesId, String indicator, String startDate) {
        HttpUrl url = HttpUrl.parse(FRED_BASE).newBuilder()
                .addQueryParameter("series_id", seriesId)
                .addQueryParameter("api_key", fredProps.apiKey())
                .addQueryParameter("file_type", "json")
                .addQueryParameter("observation_start", startDate)
                .addQueryParameter("sort_order", "asc")
                .addQueryParameter("limit", "10000")
                .build();
        int imported = 0, skipped = 0;
        try (Response resp = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[BackfillSvc] FRED {} HTTP {}", seriesId, resp.code());
                return new int[]{0, 0};
            }
            JsonNode root = objectMapper.readTree(resp.body().string());
            for (JsonNode obs : root.path("observations")) {
                String dateStr = obs.path("date").asText("");
                String valStr  = obs.path("value").asText(".");
                if (dateStr.isBlank() || ".".equals(valStr)) continue;
                double val;
                try { val = Double.parseDouble(valStr); } catch (Exception e) { continue; }
                LocalDateTime ts = LocalDate.parse(dateStr, DateTimeFormatter.ISO_DATE)
                        .atStartOfDay();
                if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt("BTCUSDT", indicator, ts)) {
                    skipped++;
                    continue;
                }
                save("BTCUSDT", indicator, ts, val);
                imported++;
            }
        } catch (Exception e) {
            log.warn("[BackfillSvc] FRED {} failed: {}", seriesId, e.getMessage());
        }
        log.info("[BackfillSvc] FRED {} → imported={} skipped={}", seriesId, imported, skipped);
        return new int[]{imported, skipped};
    }

    // ── 4. OKX Open Interest history (1H) ─────────────────────────────────────

    public String backfillOpenInterest(String symbol, int limit) {
        // OKX rubik stat: /api/v5/rubik/stat/contracts/open-interest-volume?ccy=BTC&period=1H&limit=N
        String ccy = symbol.replace("USDT", "");
        int imported = 0, skipped = 0;
        try {
            JsonNode data = okxTradingService.getOpenInterestVolumeHistory(ccy, "1H", Math.min(limit, 288));
            if (!data.isArray()) return "❌ no data from OKX rubik";
            Double prevOi = null;
            for (JsonNode item : data) {
                long ms = item.path(0).asLong(0);
                // OKX rubik OI is in USD (e.g. 3.35B). Divide by 1e6 to store
                // in millions of USD (e.g. 3348.26), fitting DECIMAL(12,6).
                double oi = item.path(1).asDouble(0) / 1_000_000.0;
                if (ms == 0 || oi == 0) continue;
                LocalDateTime ts = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(ms), ZoneOffset.UTC);

                if (!historyRepo.existsBySymbolAndIndicatorAndCapturedAt("BTCUSDT", "btc_open_interest_usd_m", ts)) {
                    save("BTCUSDT", "btc_open_interest_usd_m", ts, oi);
                    imported++;
                } else { skipped++; }

                if (prevOi != null && prevOi > 0) {
                    double changePct = (oi - prevOi) / prevOi * 100.0;
                    if (!historyRepo.existsBySymbolAndIndicatorAndCapturedAt("BTCUSDT", "oi_change_pct_1h", ts)) {
                        save("BTCUSDT", "oi_change_pct_1h", ts, changePct);
                        imported++;
                    } else { skipped++; }
                }
                prevOi = oi;
            }
        } catch (Exception e) {
            log.warn("[BackfillSvc] btc_open_interest failed: {}", e.getMessage());
            return "❌ " + e.getMessage();
        }
        if (imported > 0) oiFundingDivergenceStrategy.invalidateCache();
        return fmt("btc_open_interest + oi_change_pct_1h", imported, skipped);
    }

    // ── 5. Hyperliquid funding rate history ───────────────────────────────────

    public String backfillHyperliquidFunding(int days) {
        long startMs = Instant.now().minusSeconds((long) days * 86400).toEpochMilli();
        int imported = 0, skipped = 0, errors = 0;
        try {
            JsonNode data = hyperliquidService.getFundingHistory("BTC", startMs);
            if (data == null || !data.isArray()) return "❌ no data from Hyperliquid";
            for (JsonNode item : data) {
                long ms = item.path("time").asLong(0);
                double rate = item.path("fundingRate").asDouble(0);
                if (ms == 0) continue;
                LocalDateTime ts = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), java.time.ZoneOffset.UTC);
                if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt("BTCUSDT", "hyperliquid_btc_funding_hr_pct", ts)) {
                    skipped++;
                    continue;
                }
                // Hyperliquid fundingRate is decimal per hour; convert to pct: × 100
                save("BTCUSDT", "hyperliquid_btc_funding_hr_pct", ts, rate * 100.0);
                imported++;
            }
        } catch (Exception e) {
            log.warn("[BackfillSvc] hyperliquid_funding failed: {}", e.getMessage());
            return "❌ " + e.getMessage();
        }
        return fmt("hyperliquid_btc_funding_hr_pct", imported, skipped);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void save(String symbol, String indicator, LocalDateTime ts, double value) {
        MarketIndicatorHistory row = new MarketIndicatorHistory();
        row.setSymbol(symbol);
        row.setIndicator(indicator);
        row.setCapturedAt(ts);
        row.setValue(BigDecimal.valueOf(value));
        historyRepo.save(row);
    }

    private static String fmt(String indicator, int imported, int skipped) {
        return String.format("=== %s backfill ===\n✅ imported: %d  ⏭ skipped: %d",
                indicator, imported, skipped);
    }
}
