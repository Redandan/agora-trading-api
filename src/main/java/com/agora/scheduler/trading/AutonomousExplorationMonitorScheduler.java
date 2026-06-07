package com.agora.scheduler.trading;

import com.agora.service.TelegramService;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.trading.AutonomousExplorationMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@ConditionalOnProperty(name = "trading.exploration.monitor.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class AutonomousExplorationMonitorScheduler {

    private final AutonomousExplorationMonitorService monitorService;
    private final TelegramService telegramService;
    private final TgNotificationDeduper tgNotificationDeduper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> lastFingerprint = new AtomicReference<>();

    @Value("${trading.exploration.monitor.enabled:false}")
    private boolean enabled;

    @Value("${trading.exploration.monitor.telegram.enabled:false}")
    private boolean telegramEnabled;

    @Scheduled(
            fixedDelayString = "${trading.exploration.monitor.fixed-delay-ms:21600000}",
            initialDelayString = "${trading.exploration.monitor.initial-delay-ms:300000}")
    public void runAutonomousExplorationMonitor() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[AutonomousExplorationMonitor] previous run still active; skip");
            return;
        }
        try {
            AutonomousExplorationMonitorService.Report report =
                    monitorService.evaluate("BTCUSDT", 574L, "LONG");
            log.info("[AutonomousExplorationMonitor] status={} blockers={} warnings={}",
                    report.monitorStatus(), report.blockers(), report.warnings());
            maybeNotify(report);
        } catch (Throwable t) {
            log.error("[AutonomousExplorationMonitor] monitor failed: {}", t.getMessage(), t);
        } finally {
            running.set(false);
        }
    }

    private void maybeNotify(AutonomousExplorationMonitorService.Report report) {
        if (!telegramEnabled || report == null) {
            if (report != null) {
                lastFingerprint.compareAndSet(null, report.fingerprint());
            }
            return;
        }
        String current = report.fingerprint();
        String previous = lastFingerprint.getAndSet(current);
        if (current.equals(previous)) {
            return;
        }
        if (!isNotifiableTransition(previous, report)) {
            return;
        }
        String key = "AutonomousExplorationMonitor:" + report.monitorStatus();
        if (tgNotificationDeduper.shouldSend(key, Duration.ofHours(6), TgNotificationDeduper.Severity.FYI)) {
            telegramService.sendAlert(report.notificationMessage(), false,
                    "AutonomousExplorationMonitor", level(report.monitorStatus()));
        }
    }

    private boolean isNotifiableTransition(String previous, AutonomousExplorationMonitorService.Report report) {
        if (report.monitorStatus().equals("READY_TO_EXPLORE")) {
            return previous == null || !previous.startsWith("READY_TO_EXPLORE|");
        }
        if (report.monitorStatus().equals("WAIT_OCO_HEALTH")
                || report.monitorStatus().equals("ERROR_NEEDS_OPERATOR")
                || report.monitorStatus().equals("WATCH_GOVERNANCE_TOO_STRICT")
                || report.monitorStatus().equals("WATCH_GOVERNANCE_TOO_LOOSE")) {
            return true;
        }
        return previous != null && previous.startsWith("READY_TO_EXPLORE|");
    }

    private String level(String status) {
        if ("WAIT_OCO_HEALTH".equals(status) || "ERROR_NEEDS_OPERATOR".equals(status)) {
            return "WARN";
        }
        return "INFO";
    }
}
