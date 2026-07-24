package com.agora.scheduler.trading;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Minimal hourly market-data integrity task.
 *
 * <p>AI/ML indicators, meta-control attribution, and position suggestions are
 * intentionally absent. The only responsibility is read/repair of K-line gaps
 * used by strategy evaluation.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "meta-control.hourly-orchestrator.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class HourlyOrchestrator {

    private final KlineGapDetector klineGapDetector;

    @Value("${meta-control.hourly-orchestrator.enabled:false}")
    private boolean enabled;

    /**
     * 每小時 UTC +1 分鐘觸發。
     *
     * <p>避免與整點 closed K-line strategy dispatch
     * 在同一秒爭用 DB 連線池。
     */
    @Scheduled(cron = "0 1 * * * *", zone = "UTC")
    public void runHourlyTasks() {
        if (!enabled) {
            log.debug("[HourlyOrchestrator] disabled by meta-control.hourly-orchestrator.enabled=false");
            return;
        }
        log.info("[HourlyOrchestrator] ===== Start hourly task sequence =====");
        long t0 = System.currentTimeMillis();

        // K 線缺口偵測與補齊（行情完整性，不是交易決策）
        safeRun("klineGapDetect", klineGapDetector::detectAndBackfill);

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
