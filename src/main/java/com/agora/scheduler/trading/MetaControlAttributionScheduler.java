package com.agora.scheduler.trading;

import com.agora.model.MetaControlAttribution;
import com.agora.model.StrategyOverride;
import com.agora.repository.trading.StrategyOverrideRepository;
import com.agora.service.meta.MetaControlAttributionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Meta-Control attribution 排程。
 *
 * <h3>行為</h3>
 * <ul>
 *   <li>Hourly cron :15 ─ 掃 last 2h 結束的 PAUSE override,對每筆算 attribution</li>
 *   <li>Startup backfill ─ app 啟動 5 分鐘後掃 last 24h,避免重啟期間有 override 到期被漏算</li>
 * </ul>
 *
 * <h3>容錯</h3>
 * 單筆 override 算失敗 log warn 不影響後續,重算安全(attribution 表有
 * UNIQUE (override_type, override_id))。
 *
 * <h3>為何 :15</h3>
 * 避開 :00 的 OcoPoll / KlineGapDetector / DailyReport 擁擠時段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetaControlAttributionScheduler {

    private final StrategyOverrideRepository overrideRepository;
    private final MetaControlAttributionService attributionService;
    private final TaskScheduler taskScheduler;
    private final com.agora.config.properties.AttributionProperties props;

    // @Scheduled 已移至 HourlyOrchestrator（UTC :00 串行執行，step 4）
    public void computeHourly() {
        if (!props.enabled()) {
            log.debug("[Attribution] disabled by config");
            return;
        }
        compute(props.scanHours(), "hourly");
    }

    /**
     * App 啟動完成後延遲 N 分鐘執行 startup backfill。
     *
     * <p>改用 Spring {@link TaskScheduler#schedule} 取代 raw {@code new Thread()}，
     * 讓任務受 pool 管控、可被 {@code listSchedulers} 監控、不需手動處理 InterruptedException。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        if (!props.enabled()) return;
        Instant fireAt = Instant.now().plusSeconds(props.startupDelayMinutes() * 60L);
        taskScheduler.schedule(
                () -> {
                    try {
                        compute(props.startupBackfillHours(), "startup");
                    } catch (Throwable th) {
                        log.warn("[Attribution/startup] unexpected error: {}", th.getMessage());
                    }
                },
                fireAt);
        log.info("[Attribution/startup] backfill scheduled at {} (delay={}min)",
                fireAt, props.startupDelayMinutes());
    }

    private void compute(int hours, String label) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime since = now.minusHours(hours);
        List<StrategyOverride> candidates = overrideRepository.findRecentlyEnded("PAUSE", since, now);
        if (candidates.isEmpty()) {
            log.debug("[Attribution/{}] no candidates in last {}h", label, hours);
            return;
        }

        long startMs = System.currentTimeMillis();
        int ok = 0, skip = 0, fail = 0;
        for (StrategyOverride ov : candidates) {
            try {
                MetaControlAttribution a = attributionService.computePauseAttribution(ov);
                switch (a.getComputationStatus()) {
                    case SUCCESS -> ok++;
                    case BACKTEST_FAILED -> fail++;
                    default -> skip++;  // INSUFFICIENT_DATA / SCOPE_TOO_BROAD
                }
            } catch (Throwable t) {
                fail++;
                log.warn("[Attribution/{}] unexpected failure override={}: {}",
                        label, ov.getId(), t.getMessage());
            }
        }
        log.info("[Attribution/{}] done: total={} ok={} skip={} fail={} elapsed={}ms",
                label, candidates.size(), ok, skip, fail,
                System.currentTimeMillis() - startMs);
    }
}
