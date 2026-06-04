package com.agora.service.market;

import com.agora.model.MdKline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/**
 * Atomic, idempotent {@code md_kline} writer for the dual-source WS streams.
 *
 * <h2>Why this exists</h2>
 * Both {@link BinanceWsKlineService} and {@link OkxWsKlineService} race against
 * {@link com.agora.scheduler.trading.KlineGapDetector} (and against the parallel WS
 * source on its own row) to write the same {@code (symbol, interval, openTime,
 * source)} bar. The {@code uk_md_kline_symbol_interval_open_time_source} unique
 * key ensures correctness, but the loser of the race triggers a JPA
 * {@link org.springframework.dao.DataIntegrityViolationException}.
 *
 * <p>Even though the WS service catches that exception and downgrades it to
 * {@code DEBUG}, Hibernate's {@code SqlExceptionHelper} <i>still</i> logs the
 * underlying SQL error at {@code ERROR} level <b>before</b> bubbling the
 * exception to our catch — that's where the ~3000 noise lines per day come
 * from, drowning out legitimate ERRORs in {@code app.log}.
 *
 * <h2>Solution</h2>
 * Use a plain JDBC {@code INSERT IGNORE} via {@link JdbcTemplate}. MySQL silently
 * ignores duplicate-key conflicts, no SQLException is thrown, no Hibernate
 * ERROR logged. {@link JdbcTemplate#update} returns affected row count: 1 if
 * inserted, 0 if duplicate. We can then publish {@code KlineClosedEvent} only
 * when the value is 1 (true new insert), preserving downstream signalling.
 *
 * <h2>Transactional behaviour</h2>
 * Marked {@link Propagation#REQUIRES_NEW} so the insert runs in its own
 * transaction — keeps any caller-level transaction unaffected if there are
 * multiple downstream calls during one WS message handling.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MdKlineInsertHelper {

    private static final String INSERT_IGNORE_SQL =
            "INSERT IGNORE INTO md_kline " +
            "(symbol, interval_code, open_time, close_time, " +
            " open_price, high_price, low_price, close_price, " +
            " volume, source, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Insert {@code kline} into {@code md_kline}. Returns {@code true} iff a
     * new row was actually written (1 affected row). {@code false} on duplicate
     * (0 affected rows) — the conflicting row already exists for this
     * {@code (symbol, interval, openTime, source)} tuple, which is harmless
     * because the unique key guarantees identical content for that tuple.
     *
     * <p>{@link Propagation#REQUIRES_NEW} so this insert is its own commit
     * boundary; safe to call from inside other transactions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean insertIgnore(MdKline kline) {
        try {
            int rows = jdbcTemplate.update(INSERT_IGNORE_SQL,
                    kline.getSymbol(),
                    kline.getIntervalCode(),
                    Timestamp.valueOf(kline.getOpenTime()),
                    Timestamp.valueOf(kline.getCloseTime()),
                    kline.getOpenPrice(),
                    kline.getHighPrice(),
                    kline.getLowPrice(),
                    kline.getClosePrice(),
                    kline.getVolume(),
                    kline.getSource() != null ? kline.getSource() : "binance"
            );
            return rows > 0;
        } catch (Exception e) {
            // Truly unexpected — log loud. INSERT IGNORE shouldn't surface dup
            // errors, so anything reaching here is a real failure (DB down,
            // schema mismatch, NULL on a non-null column, etc.).
            log.error("[MdKlineInsert] failed for {} {}@{} source={}: {}",
                    kline.getSymbol(), kline.getIntervalCode(),
                    kline.getOpenTime(), kline.getSource(), e.getMessage());
            return false;
        }
    }
}
