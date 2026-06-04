package com.agora.config;

import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.backtest.OiFundingDivergenceStrategy;
import com.agora.service.market.UniswapDexFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * On startup: if fewer than 100 rows of dex_wbtc_net_flow_usd_1h exist,
 * trigger backfill for the last 365 days in a plain background thread.
 * Dispatches the coverage check itself to a daemon thread so Spring readiness
 * is not held hostage by a 365-day row-count query.
 * Idempotent — safe to redeploy.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "meta-control.startup-backfill.dex-flow.enabled",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@AsyncStartup("dex flow backfill — new Thread (#361)")
public class DexFlowBackfillRunner implements ApplicationRunner {

    private static final int MIN_ROWS_TO_SKIP = 8500; // 365d × 24h = 8760; skip only when ~complete
    private static final int BACKFILL_DAYS = 365;
    private static final long DELAY_MS = 150;

    private final MarketIndicatorHistoryRepository historyRepo;
    private final UniswapDexFlowService uniswapDexFlowService;
    private final OiFundingDivergenceStrategy oifStrategy;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[DexFlowBackfill] ApplicationRunner.run() called");
        Thread startupThread = new Thread(this::backfillIfNeeded, "dex-backfill-startup");
        startupThread.setDaemon(true);
        startupThread.start();
        log.info("[DexFlowBackfill] startup check thread started: {}", startupThread.getName());
    }

    private void backfillIfNeeded() {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(BACKFILL_DAYS);
        long count = historyRepo
                .countBySymbolAndIndicatorAndCapturedAtAfter(
                        "BTCUSDT", UniswapDexFlowService.INDICATOR, since);

        if (count >= MIN_ROWS_TO_SKIP) {
            log.info("[DexFlowBackfill] {} rows present — skipping startup backfill", count);
            return;
        }

        LocalDateTime from = since;
        LocalDateTime to   = LocalDateTime.now(ZoneOffset.UTC);
        long hours = ChronoUnit.HOURS.between(from, to);
        log.info("[DexFlowBackfill] {} rows found — launching {}-day backfill ({} hours) in background",
                count, BACKFILL_DAYS, hours);

        Thread backfillThread = new Thread(() -> runBackfill(from, to), "dex-backfill");
        backfillThread.setDaemon(true);
        backfillThread.start();
        log.info("[DexFlowBackfill] thread started: {}", backfillThread.getName());
    }

    private void runBackfill(LocalDateTime from, LocalDateTime to) {
        log.info("[DexFlowBackfill] runBackfill thread started: {} → {}", from, to);
        LocalDateTime cursor = from.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime end    = to.truncatedTo(ChronoUnit.HOURS);
        int imported = 0, skipped = 0, errors = 0;
        long t0 = System.currentTimeMillis();

        while (!cursor.isAfter(end)) {
            if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt(
                    "BTCUSDT", UniswapDexFlowService.INDICATOR, cursor)) {
                skipped++;
                cursor = cursor.plusHours(1);
                continue;
            }
            try {
                long fromTs = cursor.toEpochSecond(ZoneOffset.UTC);
                Double value = uniswapDexFlowService.fetchForWindow(fromTs, fromTs + 3600);
                // Retry once on null (API error) — distinguishes transient errors from
                // genuine "no swaps" hours (which return 0.0, not null)
                if (value == null) {
                    Thread.sleep(1000);
                    value = uniswapDexFlowService.fetchForWindow(fromTs, fromTs + 3600);
                }
                if (value != null) {
                    MarketIndicatorHistory row = new MarketIndicatorHistory();
                    row.setCapturedAt(cursor);
                    row.setSymbol("BTCUSDT");
                    row.setIndicator(UniswapDexFlowService.INDICATOR);
                    row.setValue(BigDecimal.valueOf(value));
                    historyRepo.save(row);
                    imported++;
                } else {
                    errors++;
                }
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[DexFlowBackfill] interrupted at {}", cursor);
                break;
            } catch (Exception e) {
                log.warn("[DexFlowBackfill] error at {}: {}", cursor, e.getMessage());
                errors++;
            }
            cursor = cursor.plusHours(1);
        }

        log.info("[DexFlowBackfill] done — imported={} skipped={} errors={} elapsed={}s",
                imported, skipped, errors, (System.currentTimeMillis() - t0) / 1000);
        if (imported > 0) {
            oifStrategy.invalidateCache();
        }
    }
}
