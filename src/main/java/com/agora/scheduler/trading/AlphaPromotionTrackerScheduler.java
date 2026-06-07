package com.agora.scheduler.trading;

import com.agora.service.diagnostic.AlphaPromotionTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agora.alpha-tracker.enabled", havingValue = "true", matchIfMissing = false)
public class AlphaPromotionTrackerScheduler {

    private final AlphaPromotionTracker tracker;

    @Value("${agora.alpha-tracker.enabled:false}")
    private boolean enabled;

    /** Cron：每週日 09:00 UTC */
    @Scheduled(cron = "0 0 9 * * SUN", zone = "UTC")
    public void weeklyScan() {
        if (!enabled) {
            log.debug("[AlphaPromotionTracker] disabled by agora.alpha-tracker.enabled=false");
            return;
        }
        log.info("[AlphaPromotionTracker] weekly scan starting");
        try {
            tracker.scanAndCompare();
        } catch (Exception e) {
            log.warn("[AlphaPromotionTracker] weekly scan failed", e);
        }
    }
}
