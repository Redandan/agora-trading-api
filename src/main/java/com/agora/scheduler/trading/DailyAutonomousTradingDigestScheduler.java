package com.agora.scheduler.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.trading.DailyAutonomousTradingDigestService;
import com.agora.service.trading.DailyAutonomousTradingDigestService.Digest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyAutonomousTradingDigestScheduler {

    private final DailyAutonomousTradingDigestService digestService;
    private final NotificationPort notificationPort;
    private final TgNotificationDeduper tgNotificationDeduper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> lastSevereFingerprint = new AtomicReference<>();

    @Value("${trading.autonomous.digest.enabled:true}")
    private boolean enabled;

    @Value("${trading.autonomous.digest.telegram-enabled:true}")
    private boolean telegramEnabled;

    @Value("${trading.autonomous.digest.severe-scan-enabled:false}")
    private boolean severeScanEnabled;

    @Value("${trading.autonomous.digest.snapshot-refresh-enabled:true}")
    private boolean snapshotRefreshEnabled;

    @Scheduled(
            cron = "${trading.autonomous.digest.cron:0 30 9 * * *}",
            zone = "${trading.autonomous.digest.timezone:Asia/Bangkok}")
    public void sendDailyDigest() {
        if (!enabled || !telegramEnabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[DailyAutonomousTradingDigest] previous run still active; skip");
            return;
        }
        try {
            Digest digest = digestService.generate("BTCUSDT", 574L, "LONG");
            notificationPort.alert(digestService.compactTelegramSummary(digest),
                    true, "DailyAutonomousTradingDigest", level(digest));
            rememberSevereFingerprint(digest);
            log.info("[DailyAutonomousTradingDigest] sent verdict={} anomalies={}",
                    digest.verdict(), digest.anomalies());
        } catch (Throwable t) {
            log.error("[DailyAutonomousTradingDigest] fatal: {}", t.getMessage(), t);
            try {
                notificationPort.alert("<b>每日自動交易摘要失敗</b>\n錯誤=" + safeErr(t),
                        true, "DailyAutonomousTradingDigest", "WARN");
            } catch (Exception ignored) {
            }
        } finally {
            running.set(false);
        }
    }

    @Scheduled(
            fixedDelayString = "${trading.autonomous.digest.snapshot-refresh-delay-ms:21600000}",
            initialDelayString = "${trading.autonomous.digest.snapshot-refresh-initial-delay-ms:120000}")
    public void refreshDigestSnapshot() {
        if (!enabled || !snapshotRefreshEnabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[DailyAutonomousTradingDigest] previous run still active; skip snapshot refresh");
            return;
        }
        try {
            Digest digest = digestService.generate("BTCUSDT", 574L, "LONG", "SNAPSHOT_REFRESH");
            rememberSevereFingerprint(digest);
            log.info("[DailyAutonomousTradingDigest] snapshot refreshed verdict={} anomalies={}",
                    digest.verdict(), digest.anomalies());
        } catch (Throwable t) {
            log.warn("[DailyAutonomousTradingDigest] snapshot refresh failed: {}", t.getMessage(), t);
        } finally {
            running.set(false);
        }
    }

    @Scheduled(
            fixedDelayString = "${trading.autonomous.digest.severe-scan-delay-ms:600000}",
            initialDelayString = "${trading.autonomous.digest.severe-scan-initial-delay-ms:180000}")
    public void sendSevereStateChangeDigest() {
        if (!enabled || !telegramEnabled || !severeScanEnabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[DailyAutonomousTradingDigest] previous run still active; skip severe scan");
            return;
        }
        try {
            Digest digest = digestService.generate("BTCUSDT", 574L, "LONG");
            if (!digestService.severe(digest)) {
                rememberSevereFingerprint(digest);
                return;
            }
            String fingerprint = digestService.severeFingerprint(digest);
            String previous = lastSevereFingerprint.getAndSet(fingerprint);
            if (fingerprint.equals(previous)) {
                return;
            }
            if (!tgNotificationDeduper.shouldSend("DailyAutonomousTradingDigest:severe:" + digest.verdict(),
                    Duration.ofHours(6), TgNotificationDeduper.Severity.WARN)) {
                return;
            }
            notificationPort.alert("<b>Autonomous Trading Digest severe state change</b>\n"
                            + digestService.compactTelegramSummary(digest),
                    true, "DailyAutonomousTradingDigest:severe", level(digest));
            log.warn("[DailyAutonomousTradingDigest] severe notification sent verdict={} anomalies={}",
                    digest.verdict(), digest.anomalies());
        } catch (Throwable t) {
            log.warn("[DailyAutonomousTradingDigest] severe scan failed: {}", t.getMessage(), t);
        } finally {
            running.set(false);
        }
    }

    private void rememberSevereFingerprint(Digest digest) {
        if (digestService.severe(digest)) {
            lastSevereFingerprint.set(digestService.severeFingerprint(digest));
        }
    }

    private String level(Digest digest) {
        return digestService.severe(digest) ? "WARN" : "INFO";
    }

    private String safeErr(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return message.length() <= 240 ? message : message.substring(0, 237) + "...";
    }
}
