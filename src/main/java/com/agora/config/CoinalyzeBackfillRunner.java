package com.agora.config;

import com.agora.config.properties.MarketDataProperties;
import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.market.CoinalyzeService;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * 啟動時自動回填 Coinalyze 清算歷史（V083）。
 *
 * <p>偵測條件：{@code btc_short_liq_usd_1h} 在過去 7 天內無資料，
 * 則視為首次部署，自動回填過去 {@value BACKFILL_DAYS} 天歷史。
 *
 * <p>冪等：若資料已存在（hourly timestamp 精確匹配）則跳過，
 * 因此重啟不會造成重複寫入。
 *
 * <p>失敗策略：整個回填在非同步執行緒中執行，失敗只 warn，不影響啟動。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "meta-control.startup-backfill.coinalyze.enabled",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@AsyncStartup("coinalyze backfill — new Thread (#361)")
public class CoinalyzeBackfillRunner implements ApplicationRunner {

    private static final int BACKFILL_DAYS = 30;
    private static final String SYM = "BTCUSDT";
    private static final String COINALYZE_URL = "https://api.coinalyze.net/v1/liquidation-history";

    private final MarketDataProperties marketDataProps;
    private final MarketIndicatorHistoryRepository indicatorRepo;
    private final OkxTradingService okxTradingService;

    @Override
    public void run(ApplicationArguments args) {
        String apiKey = marketDataProps.coinalyze().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.info("[CoinalyzeBackfill] api-key not configured, skipping auto-backfill");
            return;
        }
        // Run async so it doesn't delay startup
        Thread t = new Thread(this::doBackfillIfNeeded, "coinalyze-backfill");
        t.setDaemon(true);
        t.start();
    }

    private void doBackfillIfNeeded() {
        try {
            // Check if we have SUFFICIENT historical data (not just any row).
            // One manually-triggered row should not prevent the full backfill.
            // 30 days × 24h = 720 bars; require ≥ 100 before we consider it populated.
            LocalDateTime checkSince = LocalDateTime.now(ZoneOffset.UTC).minusDays(BACKFILL_DAYS);
            int existingRows = indicatorRepo
                    .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                            SYM, "btc_short_liq_usd_1h", checkSince)
                    .size();

            if (existingRows >= 100) {
                log.info("[CoinalyzeBackfill] Sufficient data present ({} rows), skipping auto-backfill",
                        existingRows);
                return;
            }
            log.info("[CoinalyzeBackfill] Only {} rows found (need ≥ 100), starting {}-day backfill",
                    existingRows, BACKFILL_DAYS);

            log.info("[CoinalyzeBackfill] No recent btc_short_liq_usd_1h data — starting {}-day backfill",
                    BACKFILL_DAYS);
            doBackfill(BACKFILL_DAYS);

        } catch (Exception e) {
            log.warn("[CoinalyzeBackfill] Auto-backfill failed (non-blocking): {}", e.getMessage());
        }
    }

    void doBackfill(int days) {
        // Get current BTC price for BTC→USD conversion
        double btcPx = 77000.0; // safe default
        try {
            BigDecimal px = okxTradingService.getLastPrice(SYM);
            if (px != null) btcPx = px.doubleValue();
        } catch (Exception ignored) {}

        long toEpoch   = Instant.now().getEpochSecond();
        long fromEpoch = toEpoch - (long) days * 86400;

        String url = COINALYZE_URL + "?symbols=BTCUSDT_PERP.A&interval=1hour"
                + "&from=" + fromEpoch + "&to=" + toEpoch;

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();

        Request req = new Request.Builder()
                .url(url)
                .header("api-key", marketDataProps.coinalyze().apiKey())
                .header("Accept", "application/json")
                .build();

        int written = 0, skipped = 0;
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                log.warn("[CoinalyzeBackfill] HTTP {}: {}", resp.code(),
                        resp.body() != null ? resp.body().string() : "");
                return;
            }
            String body = resp.body() != null ? resp.body().string() : "[]";
            JsonNode root = new ObjectMapper().readTree(body);
            if (!root.isArray() || root.isEmpty()) {
                log.warn("[CoinalyzeBackfill] Empty response");
                return;
            }

            JsonNode history = root.get(0).path("history");
            final double finalBtcPx = btcPx;

            for (JsonNode bar : history) {
                long   epochSec    = bar.path("t").asLong();
                double longLiqBtc  = bar.path("l").asDouble(0);
                double shortLiqBtc = bar.path("s").asDouble(0);
                double longLiqUsd  = longLiqBtc  * finalBtcPx;
                double shortLiqUsd = shortLiqBtc * finalBtcPx;
                double totalLiqUsd = longLiqUsd   + shortLiqUsd;
                double ratio       = totalLiqUsd > 0 ? shortLiqUsd / totalLiqUsd : 0.5;

                LocalDateTime ts = Instant.ofEpochSecond(epochSec)
                        .atZone(ZoneOffset.UTC).toLocalDateTime()
                        .truncatedTo(ChronoUnit.HOURS);

                // Skip if hour already stored (idempotent)
                if (!indicatorRepo.findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                        SYM, "btc_short_liq_usd_1h", ts.minusMinutes(5))
                        .stream()
                        .anyMatch(h -> h.getCapturedAt().truncatedTo(ChronoUnit.HOURS).equals(ts))) {

                    save(SYM, "btc_long_liq_usd_1h",    longLiqUsd,  ts);
                    save(SYM, "btc_short_liq_usd_1h",   shortLiqUsd, ts);
                    save(SYM, "btc_total_liq_usd_1h",   totalLiqUsd, ts);
                    save(SYM, "btc_short_liq_ratio_1h", ratio,       ts);
                    written += 4;
                } else {
                    skipped++;
                }
            }
            log.info("[CoinalyzeBackfill] Done: {} rows written, {} skipped (already existed)",
                    written, skipped);

        } catch (Exception e) {
            log.warn("[CoinalyzeBackfill] Backfill error: {}", e.getMessage());
        }
    }

    /** #314 saveIfAbsent：插入前再次確認，防止並發 backfill 產生重複記錄。 */
    private void save(String symbol, String indicator, double value, LocalDateTime ts) {
        if (indicatorRepo.existsBySymbolAndIndicatorAndCapturedAt(symbol, indicator, ts)) return;
        MarketIndicatorHistory h = new MarketIndicatorHistory();
        h.setSymbol(symbol);
        h.setIndicator(indicator);
        h.setValue(BigDecimal.valueOf(value));
        h.setCapturedAt(ts);
        indicatorRepo.save(h);
    }
}
