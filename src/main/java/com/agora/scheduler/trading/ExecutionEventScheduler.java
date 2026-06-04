package com.agora.scheduler.trading;

import com.agora.config.properties.ExecutionEventProperties;
import com.agora.mcp.ExecutionEventMcpTools;
import com.agora.service.trading.execution.ExecutionEventNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Read-only execution-manager event detector.
 *
 * <p>This scheduler writes normalized recommendations into bt_execution_event.
 * It must never mutate trading strategy, OCO, orders, grid state, trailing-stop
 * config, or funds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "execution-event.enabled", havingValue = "true")
public class ExecutionEventScheduler {

    private final ExecutionEventMcpTools executionEventMcpTools;
    private final ExecutionEventNotificationService notificationService;
    private final ExecutionEventProperties properties;

    @Scheduled(cron = "${execution-event.cron:0 */5 * * * *}", zone = "UTC")
    public void scanExecutionEvents() {
        try {
            String result = executionEventMcpTools.scanExecutionEvents(false);
            log.info("[ExecutionEvent] scheduled scan complete: {}", result);
            if (properties.notificationEnabled()) {
                String notifyResult = notificationService.sendActiveEventNotification(
                        properties.notificationDryRun());
                log.info("[ExecutionEvent] scheduled notification complete: {}", notifyResult);
            }
        } catch (Throwable t) {
            log.error("[ExecutionEvent] scheduled scan failed: {}", t.getMessage(), t);
        }
    }

    @Scheduled(cron = "30 */15 * * * *", zone = "UTC")
    public void expireExecutionEvents() {
        try {
            String result = executionEventMcpTools.expireExecutionEvents();
            log.debug("[ExecutionEvent] scheduled expiry complete: {} limit={}",
                    result, properties.listLimit());
        } catch (Throwable t) {
            log.warn("[ExecutionEvent] scheduled expiry failed: {}", t.getMessage());
        }
    }
}
