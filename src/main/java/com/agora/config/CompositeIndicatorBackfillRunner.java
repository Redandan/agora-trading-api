package com.agora.config;

import com.agora.model.MarketIndicatorHistory;
import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.indicator.CompositeIndicator;
import com.agora.service.indicator.CompositeResult;
import com.agora.service.indicator.SubDimension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * CMI Framework 統一 Backfill Runner。
 *
 * 啟動時自動掃描所有 {@link CompositeIndicator} 實作：
 *   1. 檢查主分數歷史筆數（< backfillDays × 0.8 = 需要 rebuild）
 *   2. 需要 rebuild 時：清除舊數據 → 重算歷史
 *   3. 以 btc_short_liq_usd_1h 時間軸為骨架（最多 58 天）
 *
 * 取代：SqiBackfillRunner（保留作為 fallback，待確認新 runner 穩定後可刪除）
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "meta-control.startup-backfill.composite-indicator.enabled",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@AsyncStartup("composite indicator backfill — CompletableFuture.runAsync (#361)")
public class CompositeIndicatorBackfillRunner implements ApplicationRunner {

    private final List<CompositeIndicator> indicators;
    private final MarketIndicatorHistoryRepository historyRepo;
    private final MdKlineRepository               klineRepo;

    @Override
    public void run(ApplicationArguments args) {
        // 非同步執行 backfill，避免阻塞 Spring Boot ReadinessState。
        // ApplicationRunner 同步執行時會讓 /actuator/health 回傳 OUT_OF_SERVICE，
        // 導致 blue-green deploy health check 失敗。背景執行不影響 deploy 時序。
        CompletableFuture.runAsync(() -> {
            for (CompositeIndicator ind : indicators) {
                try {
                    backfillIfNeeded(ind);
                } catch (Exception e) {
                    log.error("[CMIBackfill] {} failed: {}", ind.getName(), e.getMessage());
                }
            }
        });
    }

    private void backfillIfNeeded(CompositeIndicator ind) {
        int days = ind.getBackfillDays();
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
        int minRows = (int) (days * 24 * 0.5); // 至少 50% 覆蓋率

        long existing = historyRepo
                .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                        ind.getSymbol(), ind.getName(), since)
                .size();

        if (existing >= minRows) {
            log.info("[CMIBackfill] {} has {} rows (>= {}), skipping", ind.getName(), existing, minRows);
            return;
        }

        log.info("[CMIBackfill] {} needs rebuild ({} rows < {})", ind.getName(), existing, minRows);

        // 清除舊數據
        historyRepo.deleteBySymbolAndIndicatorAfter(ind.getSymbol(), ind.getName(), since.minusDays(2));
        for (SubDimension dim : ind.getDimensions()) {
            historyRepo.deleteBySymbolAndIndicatorAfter(ind.getSymbol(), dim.mhiKey(), since.minusDays(2));
        }

        // 以 liq 時間軸為骨架重算
        long written = rebuild(ind, since);
        log.info("[CMIBackfill] {} done — written {} rows", ind.getName(), written);
    }

    private long rebuild(CompositeIndicator ind, LocalDateTime since) {
        // 使用 1h kline 時間軸（最可靠且覆蓋最廣）
        List<MdKline> klines = klineRepo.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                ind.getSymbol(), "1h", since.minusDays(2),
                LocalDateTime.now(ZoneOffset.UTC));

        long written = 0;
        for (MdKline kline : klines) {
            LocalDateTime t = kline.getOpenTime();
            if (t.isBefore(since)) continue;

            try {
                CompositeResult result = ind.calculateHistorical(t);
                written += saveIfAbsent(ind.getSymbol(), ind.getName(), result.score(), t);
                for (SubDimension dim : ind.getDimensions()) {
                    double val = result.dimValues().getOrDefault(dim.mhiKey(), 0.0);
                    written += saveIfAbsent(ind.getSymbol(), dim.mhiKey(), val, t);
                }
                // 額外非聲明指標（如 SQI result 附帶 short_build_index）
                for (Map.Entry<String, Double> entry : result.dimValues().entrySet()) {
                    boolean declared = ind.getDimensions().stream()
                            .anyMatch(d -> d.mhiKey().equals(entry.getKey()));
                    if (!declared && !ind.getName().equals(entry.getKey())) {
                        written += saveIfAbsent(ind.getSymbol(), entry.getKey(), entry.getValue(), t);
                    }
                }
            } catch (Exception e) {
                log.debug("[CMIBackfill] {} skip {}: {}", ind.getName(), t, e.getMessage());
            }
        }
        return written;
    }

    private int saveIfAbsent(String symbol, String indicator, double value, LocalDateTime capturedAt) {
        if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt(symbol, indicator, capturedAt)) return 0;
        try {
            MarketIndicatorHistory row = new MarketIndicatorHistory();
            row.setSymbol(symbol);
            row.setIndicator(indicator);
            row.setValue(BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP));
            row.setCapturedAt(capturedAt);
            historyRepo.save(row);
            return 1;
        } catch (Exception e) {
            log.warn("[CMIBackfill] save failed {}/{}: {}", indicator, capturedAt, e.getMessage());
            return 0;
        }
    }
}
