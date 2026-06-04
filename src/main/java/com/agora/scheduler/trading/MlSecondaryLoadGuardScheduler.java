package com.agora.scheduler.trading;

import com.agora.config.properties.MlProtectionProperties;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.ml.MlTrainingOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects long-running HeatWave SECONDARY_LOAD operations and trips ML circuit
 * before they starve the application connection pool.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MlSecondaryLoadGuardScheduler {

    private final JdbcTemplate jdbc;
    private final MlTrainingOrchestrator orchestrator;
    private final NotificationPort notificationPort;
    private final MlProtectionProperties props;

    private final AtomicLong lastAlertAtMs = new AtomicLong(0L);

    @Scheduled(fixedDelayString = "${meta-control.ml-protection.scan-interval-seconds:60}000",
            initialDelayString = "30000")
    public void scanAndProtect() {
        if (!props.enabled()) return;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT ID, COMMAND, TIME, STATE, LEFT(INFO, 180) AS info " +
                            "FROM information_schema.PROCESSLIST " +
                            "WHERE ID <> CONNECTION_ID() " +
                            "  AND COMMAND <> 'Sleep' " +
                            "  AND (INFO LIKE 'ALTER TABLE% SECONDARY_LOAD' " +
                            "       OR INFO LIKE 'SET rapid_ml_operation = JSON_OBJECT%') " +
                            "  AND TIME >= ? " +
                            "ORDER BY TIME DESC",
                    props.secondaryLoadTimeoutSeconds());
            List<Map<String, Object>> activeRows = rows.stream()
                    .filter(row -> !"Killed".equalsIgnoreCase(asString(row.get("COMMAND"))))
                    .toList();
            int killedRemnants = rows.size() - activeRows.size();
            if (activeRows.size() < props.minSecondaryLoadRowsToTrip()) {
                if (killedRemnants > 0) {
                    log.info("[MlSecondaryLoadGuard] ignoring killed SECONDARY_LOAD remnants rows={}",
                            killedRemnants);
                }
                return;
            }

            int oldestSec = activeRows.stream()
                    .map(r -> asNumber(r.get("TIME")))
                    .filter(n -> n != null)
                    .mapToInt(Number::intValue)
                    .max()
                    .orElse(0);
            orchestrator.tripMlCircuit("SECONDARY_LOAD rows=" + activeRows.size() + " oldestSec=" + oldestSec);

            int killed = 0;
            if (props.autoKillSecondaryLoad()) {
                for (Map<String, Object> row : activeRows) {
                    Number id = asNumber(row.get("ID"));
                    if (id == null) continue;
                    try {
                        jdbc.execute("KILL CONNECTION " + id.longValue());
                        killed++;
                    } catch (Exception killErr) {
                        log.warn("[MlSecondaryLoadGuard] kill failed id={}: {}", id, killErr.getMessage());
                    }
                }
            }

            maybeAlert(activeRows.size(), oldestSec, killed, killedRemnants);
            log.warn("[MlSecondaryLoadGuard] tripped rows={} oldestSec={} killed={} ignoredKilledRemnants={}",
                    activeRows.size(), oldestSec, killed, killedRemnants);
        } catch (Exception e) {
            log.warn("[MlSecondaryLoadGuard] scan failed: {}", e.getMessage());
        }
    }

    private void maybeAlert(int rows, int oldestSec, int killed, int killedRemnants) {
        long now = System.currentTimeMillis();
        long cooldownMs = props.alertCooldownMinutes() * 60_000L;
        long prev = lastAlertAtMs.get();
        if (now - prev < cooldownMs) return;
        if (!lastAlertAtMs.compareAndSet(prev, now)) return;

        String msg = String.format(
                "🚨 <b>ML 保護器觸發</b>%n時間: %s UTC%n" +
                        "偵測到 SECONDARY_LOAD 卡住 rows=%d, oldest=%ds%n" +
                        "動作: trip ML circuit%s%s%n" +
                        "影響: 暫時跳過 ML 推理/載入，優先保護交易主流程與 TG 查詢",
                Instant.now().toString(),
                rows,
                oldestSec,
                props.autoKillSecondaryLoad() ? (", killed=" + killed) : "",
                killedRemnants > 0 ? (", ignoredKilledRemnants=" + killedRemnants) : "");
        try {
            notificationPort.broadcast(msg, true);
        } catch (Exception e) {
            log.warn("[MlSecondaryLoadGuard] alert failed: {}", e.getMessage());
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Number asNumber(Object value) {
        return value instanceof Number n ? n : null;
    }
}
