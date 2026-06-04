package com.agora.config;

import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.backtest.OiFundingDivergenceStrategy;
import com.agora.service.market.IndicatorHistoryBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * On startup: if fewer than 1000 rows of hyperliquid_btc_funding_hr_pct exist,
 * trigger 90-day backfill. Also recomputes funding_rate_cex_dex_spread for any
 * newly added Hyperliquid rows.
 * Idempotent — safe to redeploy.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "meta-control.startup-backfill.hyperliquid-funding.enabled",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@AsyncStartup("hyperliquid funding backfill — CompletableFuture.runAsync (#361)")
public class HyperliquidFundingBackfillRunner implements ApplicationRunner {

    private final MarketIndicatorHistoryRepository historyRepo;
    private final IndicatorHistoryBackfillService backfillService;
    private final OiFundingDivergenceStrategy oifStrategy;

    @Override
    public void run(ApplicationArguments args) {
        // 非同步執行，避免阻塞 Spring Boot ReadinessState
        CompletableFuture.runAsync(() -> {
            LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(90);

            long hlCount = historyRepo
                    .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                            "BTCUSDT", "hyperliquid_btc_funding_hr_pct", since)
                    .size();

            if (hlCount >= 1500) {
                log.info("[HLFundingBackfill] {} rows present — skipping", hlCount);
            } else {
                log.info("[HLFundingBackfill] only {} rows — backfilling 90 days", hlCount);
                String result = backfillService.backfillHyperliquidFunding(90);
                log.info("[HLFundingBackfill] {}", result);
            }

            // Recompute funding_rate_cex_dex_spread for any gaps
            recomputeSpreadGaps(since);
        });
    }

    private void recomputeSpreadGaps(LocalDateTime since) {
        List<MarketIndicatorHistory> fundingRows =
                historyRepo.findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                        "BTCUSDT", "funding_rate", since);
        List<MarketIndicatorHistory> hlRows =
                historyRepo.findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                        "BTCUSDT", "hyperliquid_btc_funding_hr_pct", since);

        java.util.Map<LocalDateTime, Double> hlMap = new java.util.HashMap<>();
        for (MarketIndicatorHistory h : hlRows) {
            hlMap.put(h.getCapturedAt(), h.getValue().doubleValue());
        }

        int computed = 0;
        for (MarketIndicatorHistory f : fundingRows) {
            Double hlRate = hlMap.get(f.getCapturedAt());
            if (hlRate == null) continue;
            if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt(
                    "BTCUSDT", "funding_rate_cex_dex_spread", f.getCapturedAt())) continue;
            double spread = f.getValue().doubleValue() / 8.0 - hlRate;
            MarketIndicatorHistory row = new MarketIndicatorHistory();
            row.setSymbol("BTCUSDT");
            row.setIndicator("funding_rate_cex_dex_spread");
            row.setCapturedAt(f.getCapturedAt());
            row.setValue(BigDecimal.valueOf(spread));
            historyRepo.save(row);
            computed++;
        }
        if (computed > 0) {
            log.info("[HLFundingBackfill] computed {} new spread rows", computed);
            oifStrategy.invalidateCache();
        }
    }
}
