package com.agora.scheduler.trading;

import com.agora.repository.trading.SignalOutcomeVerificationRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.mcp.MarketDataMcpTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * #272 信號結果驗證器
 *
 * <p>每 30 分鐘：一條 SQL UPDATE 判定 WATCHING → CORRECT / WRONG / EXPIRED
 * <p>每日 UTC 09:00：計算各層 7 天滾動正確率，低於門檻發 TG
 *
 * <h3>三種結果</h3>
 * <ul>
 *   <li>CORRECT：現價觸及 TP（信號方向正確）</li>
 *   <li>WRONG：現價觸及 SL（信號方向錯誤）</li>
 *   <li>WATCHING：未觸發，或移動幅度 < minMovementPct（雜訊）</li>
 *   <li>EXPIRED：超過 maxWatchingDays 仍未觸發</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "signal-verification.scheduler.enabled", havingValue = "true", matchIfMissing = false)
public class SignalOutcomeVerifierScheduler {

    private final JdbcTemplate jdbc;
    private final SignalOutcomeVerificationRepository repo;
    private final NotificationPort notificationPort;
    private final com.agora.config.properties.SignalVerificationProperties props;

    @Value("${signal-verification.scheduler.enabled:false}")
    private boolean enabled;

    // #323: NOW() 是 MySQL UTC 而 created_at 是 JDBC 寫入的 Taipei wall-clock，
    //       原本 `created_at < NOW() - INTERVAL ? DAY` 有 8h 偏移；改用 Java
    //       LocalDateTime 參數，與儲存欄位走相同 JDBC round-trip，比較才正確。
    private static final String VERIFY_SQL = """
        UPDATE signal_outcome_verification sov
        JOIN (
            SELECT k1.symbol, k1.close_price AS current_price
            FROM md_kline k1
            JOIN (
                SELECT symbol, MAX(open_time) AS max_open_time
                FROM md_kline
                WHERE interval_code = '1m'
                  AND source = 'okx'
                GROUP BY symbol
            ) latest_time
              ON latest_time.symbol = k1.symbol
             AND latest_time.max_open_time = k1.open_time
            WHERE k1.interval_code = '1m'
              AND k1.source = 'okx'
        ) latest ON latest.symbol = sov.symbol
        SET
          sov.last_price      = latest.current_price,
          sov.last_checked_at = ?,
          sov.outcome = CASE
              WHEN sov.created_at < ?
                  THEN 'EXPIRED'
              WHEN ABS(latest.current_price - sov.entry_price)
                       / sov.entry_price * 100 < ?
                  THEN 'WATCHING'
              WHEN sov.side = 'LONG'  AND latest.current_price >= sov.tp_price THEN 'CORRECT'
              WHEN sov.side = 'LONG'  AND latest.current_price <= sov.sl_price THEN 'WRONG'
              WHEN sov.side = 'SHORT' AND latest.current_price <= sov.tp_price THEN 'CORRECT'
              WHEN sov.side = 'SHORT' AND latest.current_price >= sov.sl_price THEN 'WRONG'
              ELSE 'WATCHING'
          END,
          sov.finalized_at = CASE
              WHEN sov.finalized_at IS NULL
               AND (
                   sov.created_at < ?
                   OR (
                       ABS(latest.current_price - sov.entry_price)
                               / sov.entry_price * 100 >= ?
                       AND (
                           (sov.side = 'LONG'  AND (latest.current_price >= sov.tp_price
                                                  OR latest.current_price <= sov.sl_price))
                        OR (sov.side = 'SHORT' AND (latest.current_price <= sov.tp_price
                                                  OR latest.current_price >= sov.sl_price))
                       )
                   )
               )
                  THEN ?
              ELSE sov.finalized_at
          END
        WHERE sov.outcome = 'WATCHING'
          AND sov.tp_price IS NOT NULL
          AND sov.sl_price IS NOT NULL
        """;

    @Scheduled(cron = "0 */30 * * * *", zone = "UTC")
    public void verifyOutcomes() {
        if (!enabled) {
            log.debug("[SignalVerifier] disabled by signal-verification.scheduler.enabled=false");
            return;
        }
        try {
            // #323: 用 Java 端 LocalDateTime 參數消除 JDBC serverTimezone=Asia/Taipei 與 MySQL UTC NOW() 的 8h 偏移
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime expireBefore = now.minusDays(props.maxWatchingDays());
            int updated = jdbc.update(VERIFY_SQL,
                    now,                  // last_checked_at = ?
                    expireBefore,         // created_at < ? (outcome CASE)
                    props.minMovementPct(),
                    expireBefore,         // created_at < ? (finalized_at CASE)
                    props.minMovementPct(),
                    now);                 // finalized_at THEN ?
            if (updated > 0) {
                log.info("[SignalVerifier] updated {} records", updated);
            }
        } catch (Exception e) {
            log.warn("[SignalVerifier] verify failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "UTC")
    public void dailyAccuracyAlert() {
        if (!enabled) {
            log.debug("[SignalVerifier] daily alert disabled by signal-verification.scheduler.enabled=false");
            return;
        }
        try {
            LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
            List<Object[]> rows = repo.accuracyByLayerSinceDedup(since);
            if (rows.isEmpty()) return;

            StringBuilder sb = new StringBuilder();
            boolean anyAlert = false;

            for (Object[] r : rows) {
                String layer    = (String) r[0];
                String decision = (String) r[1];
                long correct    = ((Number) r[2]).longValue();
                long wrong      = ((Number) r[3]).longValue();
                long watching   = ((Number) r[4]).longValue();
                long total      = correct + wrong;
                if (total < props.minSampleSize()) continue;

                String line = MarketDataMcpTools.renderDecisionAccuracyLine(
                        layer, decision, correct, wrong, watching, 0,
                        props.minSampleSize(), props.accuracyAlertThreshold());
                sb.append(line).append("\n");
                if (line.startsWith("⚠️")) anyAlert = true;
            }

            if (sb.length() == 0) return;
            String header = anyAlert
                    ? "⚠️ <b>信號正確率警告（近 7 天）</b>\n"
                    : "📊 <b>信號正確率報告（近 7 天）</b>\n";
            notificationPort.broadcast(header + sb, true);

        } catch (Exception e) {
            log.warn("[SignalVerifier] daily alert failed: {}", e.getMessage());
        }
    }
}
