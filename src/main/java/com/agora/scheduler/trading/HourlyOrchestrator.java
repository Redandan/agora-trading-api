package com.agora.scheduler.trading;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每小時資料收集與維護任務統一排程器（UTC :00）。
 *
 * <h3>為何集中排程</h3>
 * 原本 4 個任務分散在 :01 / :05 / :07 / :15，造成每小時內 4 個 thread slot
 * 分別被佔用（pool.size=6 下佔比達 67%）。集中後串行執行，最差耗時 ~35s，
 * 遠小於 60 分鐘，不影響下次觸發。
 *
 * <h3>執行順序（各 step 獨立 try/catch，任一失敗不影響其餘）</h3>
 * <ol>
 *   <li>marketIndicatorCollect：抓 F&G/Whale/Funding/LS/Orderbook 並行寫入（~1s）</li>
 *   <li>klineGapDetect：掃描過去 25h 缺口並從 OKX 補齊（~2s）</li>
 *   <li>metaAttribution：計算 PAUSE override 的 alpha 貢獻（2-30s）</li>
 * </ol>
 *
 * <h3>@Transactional 注意</h3>
 * 各 step 的方法透過 Spring bean proxy 呼叫，原有的 {@code @Transactional}
 * 仍正常生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HourlyOrchestrator {

    private final MarketIndicatorHistoryCollector marketIndicatorHistoryCollector;
    private final KlineGapDetector klineGapDetector;
    private final MetaControlAttributionScheduler metaControlAttributionScheduler;
    private final LiveSignalHealthScheduler liveSignalHealthScheduler;

    /**
     * 每小時 UTC +1 分鐘觸發。
     *
     * <p>避免與整點 bar close 的即時訊號評估（KlineClosedEvent → LiveSignalEvaluator）
     * 在同一秒爭用 DB 連線池。
     */
    @Scheduled(cron = "0 1 * * * *", zone = "UTC")
    public void runHourlyTasks() {
        log.info("[HourlyOrchestrator] ===== Start hourly task sequence =====");
        long t0 = System.currentTimeMillis();

        // Step 1: 市場指標 snapshot（F&G/Whale/Funding/LS/Orderbook，並行 API）
        safeRun("marketIndicatorCollect", marketIndicatorHistoryCollector::collect);

        // Step 2: K 線缺口偵測與補齊（OKX REST 回填）
        safeRun("klineGapDetect", klineGapDetector::detectAndBackfill);

        // Step 3: Meta-Control attribution 計算（最耗時，放最後）
        safeRun("metaAttribution", metaControlAttributionScheduler::computeHourly);

        // Step 4: Wide-TP 掃描（持倉 ≥ 48h + TP ≥ 15% + ATR 恢復正常 → TG 建議收斂）
        // 每小時跑確保在 48h 門檻後第一個整點即時通知，無需等到 daily 00:00
        safeRun("wideTpScan", liveSignalHealthScheduler::runWideTpScan);

        log.info("[HourlyOrchestrator] ===== Complete. total elapsed={}ms =====",
                System.currentTimeMillis() - t0);
    }

    /**
     * 執行單一 step，捕捉所有異常確保後續 step 不受影響。
     */
    private void safeRun(String label, Runnable task) {
        long t = System.currentTimeMillis();
        try {
            log.debug("[HourlyOrchestrator] >> step: {}", label);
            task.run();
            log.debug("[HourlyOrchestrator] << step: {} OK ({}ms)", label, System.currentTimeMillis() - t);
        } catch (Exception e) {
            log.error("[HourlyOrchestrator] << step: {} FAILED ({}ms): {}",
                    label, System.currentTimeMillis() - t, e.getMessage(), e);
        }
    }
}
