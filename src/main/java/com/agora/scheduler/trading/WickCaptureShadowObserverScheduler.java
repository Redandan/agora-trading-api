package com.agora.scheduler.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.meta.DecisionAuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wick-capture observer (shadow-only, disabled by default):
 * - detect 15m intrabar wick-recovery candidates
 * - write audit + shadow table
 * - send deduped TG context notification
 * - backfill 1h/4h/24h return and 24h MFE/MAE
 *
 * Never places orders, never modifies strategy/OCO/grid/funds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WickCaptureShadowObserverScheduler {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TFMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final JdbcTemplate jdbc;
    private final DecisionAuditWriter auditWriter;
    private final NotificationPort notificationPort;
    private final TgNotificationDeduper deduper;

    private final AtomicBoolean bootstrapAttempted = new AtomicBoolean(false);

    @Value("${wick-capture.shadow.enabled:false}")
    private boolean enabled;

    @Value("${wick-capture.shadow.symbol:BTCUSDT}")
    private String symbol;

    @Value("${wick-capture.shadow.min-lower-wick-pct:0.35}")
    private double minLowerWickPct;

    @Value("${wick-capture.shadow.min-recovery-pct:0.20}")
    private double minRecoveryPct;

    @Value("${wick-capture.shadow.lookback-hours:12}")
    private int lookbackHours;

    @Value("${wick-capture.shadow.bootstrap-enabled:false}")
    private boolean bootstrapEnabled;

    @Value("${wick-capture.shadow.bootstrap-days:180}")
    private int bootstrapDays;

    @Value("${wick-capture.shadow.tg-cooldown-hours:24}")
    private int tgCooldownHours;

    @Scheduled(fixedDelay = 300_000L, initialDelay = 120_000L)
    public void tick() {
        if (!enabled) return;
        try {
            bootstrapHistoricalCandidates();
            detectCandidates();
            backfillOutcomes();
        } catch (Throwable t) {
            log.error("[WickCaptureShadow] tick failed: {}", t.getMessage(), t);
        }
    }

    private void bootstrapHistoricalCandidates() {
        if (!bootstrapEnabled || !bootstrapAttempted.compareAndSet(false, true)) return;
        String sym = symbol == null ? "BTCUSDT" : symbol.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int days = Math.max(1, Math.min(180, bootstrapDays));
        LocalDateTime since = now.minusDays(days);
        try {
            int inserted = detectCandidates(sym, since, now, false, "BOOTSTRAP");
            if (inserted > 0) {
                log.info("[WickCaptureShadow] bootstrap inserted {} shadow candidate(s) for {} over {}d",
                        inserted, sym, days);
            }
        } catch (Exception e) {
            log.warn("[WickCaptureShadow] bootstrap failed for {} over {}d: {}", sym, days, e.getMessage());
        }
    }

    private void detectCandidates() {
        String sym = symbol == null ? "BTCUSDT" : symbol.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime since = now.minusHours(Math.max(2, lookbackHours));
        detectCandidates(sym, since, now, true, "LIVE");
    }

    private int detectCandidates(String sym, LocalDateTime since, LocalDateTime now,
                                 boolean notifyFreshRows, String runLabel) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT open_time, open_price, high_price, low_price, close_price
                FROM md_kline
                WHERE symbol = ?
                  AND source = 'okx'
                  AND interval_code = '15m'
                  AND open_time >= ?
                  AND open_time < ?
                ORDER BY open_time ASC
                """, sym, since, now);
        int insertedCount = 0;
        for (Map<String, Object> row : rows) {
            LocalDateTime openTime = asDateTime(row.get("open_time"));
            if (openTime == null) continue;
            if (ChronoUnit.MINUTES.between(openTime, now) < 15) continue;
            BigDecimal open = asDecimal(row.get("open_price"));
            BigDecimal high = asDecimal(row.get("high_price"));
            BigDecimal low = asDecimal(row.get("low_price"));
            BigDecimal close = asDecimal(row.get("close_price"));
            if (open == null || high == null || low == null || close == null) continue;
            BigDecimal bodyLow = open.min(close);
            BigDecimal denomLow = low.compareTo(BigDecimal.ZERO) > 0 ? low : BigDecimal.ONE;
            double lowerWickPct = pct(bodyLow.subtract(low), denomLow);
            double recoveryPct = pct(close.subtract(low), denomLow);
            double rangePct = pct(high.subtract(low), denomLow);
            if (lowerWickPct < minLowerWickPct || recoveryPct < minRecoveryPct) continue;

            int inserted = jdbc.update("""
                    INSERT IGNORE INTO bt_wick_capture_shadow
                    (symbol, bar_open_time, source_label, open_price, high_price, low_price, close_price,
                     lower_wick_pct, recovery_pct, range_pct, status, created_at, updated_at)
                    VALUES (?, ?, 'okx:15m', ?, ?, ?, ?, ?, ?, ?, 'OPEN', UTC_TIMESTAMP(), UTC_TIMESTAMP())
                    """, sym, openTime, open, high, low, close,
                    scale6(lowerWickPct), scale6(recoveryPct), scale6(rangePct));
            if (inserted <= 0) continue;
            insertedCount++;

            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("observer", "WickCaptureShadow");
            ctx.put("runLabel", runLabel);
            ctx.put("barOpenTimeUtc", openTime.toString());
            ctx.put("lowerWickPct", scale6(lowerWickPct));
            ctx.put("recoveryPct", scale6(recoveryPct));
            ctx.put("rangePct", scale6(rangePct));
            ctx.put("shadowOnly", true);
            auditWriter.logAttentionHit(null, sym, "15m", "WickCaptureShadow", "INFO", ctx);

            String dedupKey = "WickCaptureShadow:" + sym + ":" + openTime;
            if (notifyFreshRows && deduper.shouldSend(dedupKey, Duration.ofHours(Math.max(1, tgCooldownHours)),
                    TgNotificationDeduper.Severity.FYI)) {
                sendTg(sym, openTime, low, close, lowerWickPct, recoveryPct, rangePct, dedupKey);
                jdbc.update("""
                        UPDATE bt_wick_capture_shadow
                           SET tg_notified_at = UTC_TIMESTAMP(),
                               tg_source_key = ?,
                               updated_at = UTC_TIMESTAMP()
                         WHERE symbol = ? AND bar_open_time = ? AND source_label = 'okx:15m'
                        """, dedupKey, sym, openTime);
            }
        }
        return insertedCount;
    }

    private void backfillOutcomes() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Map<String, Object>> openRows = jdbc.queryForList("""
                SELECT id, symbol, bar_open_time, close_price
                FROM bt_wick_capture_shadow
                WHERE status = 'OPEN'
                ORDER BY bar_open_time ASC
                LIMIT 500
                """);
        for (Map<String, Object> row : openRows) {
            Long id = asLong(row.get("id"));
            String sym = String.valueOf(row.get("symbol"));
            LocalDateTime t0 = asDateTime(row.get("bar_open_time"));
            BigDecimal close = asDecimal(row.get("close_price"));
            if (id == null || sym == null || t0 == null || close == null || close.compareTo(BigDecimal.ZERO) <= 0) continue;

            Double ret1h = forwardReturnPct(sym, t0, close, 1);
            Double ret4h = forwardReturnPct(sym, t0, close, 4);
            Double ret24h = forwardReturnPct(sym, t0, close, 24);

            LocalDateTime until24h = t0.plusHours(24);
            BigDecimal windowHigh = querySingleDecimal("""
                    SELECT MAX(high_price) FROM md_kline
                    WHERE symbol = ? AND source = 'okx' AND interval_code = '15m'
                      AND open_time > ? AND open_time <= ?
                    """, sym, t0, until24h);
            BigDecimal windowLow = querySingleDecimal("""
                    SELECT MIN(low_price) FROM md_kline
                    WHERE symbol = ? AND source = 'okx' AND interval_code = '15m'
                      AND open_time > ? AND open_time <= ?
                    """, sym, t0, until24h);
            Double mfe = (windowHigh != null && windowHigh.compareTo(BigDecimal.ZERO) > 0)
                    ? pct(windowHigh.subtract(close), close) : null;
            Double mae = (windowLow != null && windowLow.compareTo(BigDecimal.ZERO) > 0)
                    ? pct(windowLow.subtract(close), close) : null;

            boolean closed = !now.isBefore(until24h);
            jdbc.update("""
                    UPDATE bt_wick_capture_shadow
                       SET ret_1h_pct = COALESCE(?, ret_1h_pct),
                           ret_4h_pct = COALESCE(?, ret_4h_pct),
                           ret_24h_pct = COALESCE(?, ret_24h_pct),
                           mfe_24h_pct = COALESCE(?, mfe_24h_pct),
                           mae_24h_pct = COALESCE(?, mae_24h_pct),
                           status = CASE WHEN ? THEN 'CLOSED' ELSE status END,
                           updated_at = UTC_TIMESTAMP()
                     WHERE id = ?
                    """, scaleNullable(ret1h), scaleNullable(ret4h), scaleNullable(ret24h),
                    scaleNullable(mfe), scaleNullable(mae), closed, id);
        }
    }

    private void sendTg(String sym, LocalDateTime openTimeUtc, BigDecimal low, BigDecimal close,
                        double lowerWickPct, double recoveryPct, double rangePct, String sourceKey) {
        LocalDateTime tp = openTimeUtc.atZone(ZoneOffset.UTC).withZoneSameInstant(TAIPEI).toLocalDateTime();
        String msg = String.format(
                "🟡 <b>Wick Capture Shadow</b>%n" +
                        "<code>%s @ 15m</code>%n" +
                        "bar: <code>%s Taipei</code>%n" +
                        "low=%s close=%s%n" +
                        "wick=%s recovery=%s range=%s%n%n" +
                        "shadow-only: logged for research, no order / no OCO / no strategy changes.",
                sym,
                tp.format(TFMT),
                plain(low),
                plain(close),
                fmtPct(lowerWickPct),
                fmtPct(recoveryPct),
                fmtPct(rangePct));
        try {
            notificationPort.alert(msg, true, sourceKey, "INFO");
        } catch (Exception e) {
            log.warn("[WickCaptureShadow] TG send failed: {}", e.getMessage());
        }
    }

    private Double forwardReturnPct(String sym, LocalDateTime t0, BigDecimal close, int hoursAhead) {
        LocalDateTime target = t0.plusHours(hoursAhead);
        BigDecimal futureClose = querySingleDecimal("""
                SELECT close_price
                FROM md_kline
                WHERE symbol = ? AND source = 'okx' AND interval_code = '15m'
                  AND open_time >= ?
                ORDER BY open_time ASC
                LIMIT 1
                """, sym, target);
        if (futureClose == null || futureClose.compareTo(BigDecimal.ZERO) <= 0) return null;
        return pct(futureClose.subtract(close), close);
    }

    private BigDecimal querySingleDecimal(String sql, Object... args) {
        try {
            List<BigDecimal> rows = jdbc.query(sql, (rs, rn) -> rs.getBigDecimal(1), args);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static LocalDateTime asDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dt) return dt;
        return null;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static double pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private static String fmtPct(double v) {
        return String.format("%+.2f%%", v);
    }

    private static String plain(BigDecimal v) {
        if (v == null) return "N/A";
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal scale6(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleNullable(Double v) {
        return v == null ? null : scale6(v);
    }
}
