package com.agora.scheduler.trading;

import com.agora.config.properties.EventScanNotificationProperties;
import com.agora.mcp.MetaControlMcpTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * #476 — outbound event-scan hook for operator follow-up.
 *
 * <p>This scheduler only reads decision audit rows and sends a compact
 * notification summary. It must never mutate trading strategy, OCO, orders,
 * grid state, or funds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "event-scan.notification.enabled", havingValue = "true")
public class EventScanNotificationScheduler {

    private final MetaControlMcpTools metaControlMcpTools;
    private final EventScanNotificationProperties properties;

    @Scheduled(cron = "${event-scan.notification.cron:0 5 * * * *}", zone = "UTC")
    public void sendHourlyEventScan() {
        try {
            String result = metaControlMcpTools.sendScheduledEventScanNotification(
                    properties.windowMinutes(),
                    properties.symbol(),
                    properties.scanLimit(),
                    properties.maxEvents(),
                    properties.suppressRepeatMinutes(),
                    properties.dryRun());
            log.info("[EventScanNotification] scheduled run complete: {}", result);
        } catch (Throwable t) {
            log.error("[EventScanNotification] scheduled run failed: {}", t.getMessage(), t);
        }
    }
}
