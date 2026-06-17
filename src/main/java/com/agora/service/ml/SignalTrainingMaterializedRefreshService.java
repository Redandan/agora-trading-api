package com.agora.service.ml;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * #444 — Refresh service for {@code bt_signal_training_v8_mat}, the materialized
 * snapshot of {@code vw_signal_training_v8_dedup}.
 *
 * <p>The view itself is correct but slow:
 * <ul>
 *   <li>30-day window: {@code SELECT COUNT(*) FROM vw_signal_training_v8_dedup
 *       WHERE entry_time >= NOW() - INTERVAL 30 DAY} took 2m36s in prod (5/6).
 *   <li>Cause: {@code GROUP BY (strategy_id, entry_time, side, symbol, interval)}
 *       over {@code bt_backtest_trade JOIN bt_backtest_result}, plus 8
 *       correlated subqueries against {@code market_indicator_history} per
 *       resulting group.
 *   <li>Symptom: every MCP tool that materialises the view to a CTAS
 *       ({@link MlTrainingOrchestrator#evaluateOnWindow}, etc.) hits Cloudflare's
 *       30s timeout. {@code v20} ML model has been READY since 5/4 with no
 *       evalOnHoldout result → no promote decision possible.
 * </ul>
 *
 * <p>Refresh strategy:
 * <ol>
 *   <li>{@link #refresh()} runs {@code TRUNCATE bt_signal_training_v8_mat}
 *       then {@code INSERT INTO bt_signal_training_v8_mat SELECT * FROM
 *       vw_signal_training_v8_dedup} — single transaction so MCP queries
 *       never see a partial table.
 *   <li>No nightly orchestrator exists in this split repo yet; refresh is an
 *       explicit MCP/operator action unless a future opt-in scheduler is added.
 *   <li>{@link #ensurePopulatedOnStartup()} runs once on application start; if
 *       the table is empty after a Trading-owned schema migration or baseline
 *       adoption, it kicks off an async refresh so MCP eval tools work without
 *       waiting for the first nightly cron.
 * </ol>
 *
 * <p>The view definition remains the schema source-of-truth. If columns change
 * the materialized table must be ALTERed, or dropped and recreated, in a
 * Trading-owned V2+ Flyway migration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalTrainingMaterializedRefreshService {

    private static final String TABLE = "bt_signal_training_v8_mat";
    private static final String VIEW  = "vw_signal_training_v8_dedup";

    private final JdbcTemplate jdbc;
    @Value("${meta-control.ml-materialized-refresh.startup-check-enabled:false}")
    private boolean startupCheckEnabled;

    /**
     * Use {@link ApplicationReadyEvent} (post-startup) + {@link Async} to defer
     * the empty-check off the boot thread. {@code @PostConstruct} would either
     * block JVM startup for ~2-3 minutes during the initial INSERT or, if
     * combined with {@code @Async} on the same bean, fail to dispatch async
     * (Spring proxies don't intercept self-invocation from {@code @PostConstruct}).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void ensurePopulatedOnStartup() {
        if (!startupCheckEnabled) {
            log.info("[MlMatRefresh] startup check disabled");
            return;
        }
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + TABLE, Integer.class);
            if (count != null && count > 0) {
                log.info("[MlMatRefresh] startup check: table {} already has {} rows, skipping",
                        TABLE, count);
                return;
            }
            log.info("[MlMatRefresh] startup check: table {} empty, kicking off initial refresh",
                    TABLE);
            refresh();
        } catch (Exception e) {
            // Common cause: V109 migration not yet applied (fresh dev DB).
            // Don't fail startup — table will be empty and MCP eval tools
            // surface the underlying error.
            log.warn("[MlMatRefresh] startup check failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Truncate + reinsert. Single transaction so concurrent
     * {@code SELECT * FROM bt_signal_training_v8_mat} either sees the old
     * snapshot or the new one, never a partial state.
     */
    @Transactional
    public RefreshStats refresh() {
        long t0 = System.currentTimeMillis();
        log.info("[MlMatRefresh] start refresh: TRUNCATE + INSERT FROM {}", VIEW);

        Integer beforeCount;
        try {
            beforeCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + TABLE, Integer.class);
        } catch (Exception e) {
            log.warn("[MlMatRefresh] pre-count failed: {}", e.getMessage());
            beforeCount = -1;
        }

        // TRUNCATE doesn't roll back inside a transaction in MySQL, so use
        // DELETE for transactional consistency. On 1k-3k rows this is fast.
        int deleted = jdbc.update("DELETE FROM " + TABLE);

        long t1 = System.currentTimeMillis();
        int inserted = jdbc.update(
                "INSERT INTO " + TABLE + " SELECT * FROM " + VIEW);

        long elapsed = System.currentTimeMillis() - t0;
        long insertElapsed = System.currentTimeMillis() - t1;
        log.info("[MlMatRefresh] complete: before={} deleted={} inserted={} totalMs={} insertMs={}",
                beforeCount, deleted, inserted, elapsed, insertElapsed);

        return new RefreshStats(beforeCount == null ? -1 : beforeCount,
                deleted, inserted, elapsed);
    }

    /** Read-only count for MCP / status checks. */
    public Integer currentRowCount() {
        try {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Integer.class);
        } catch (Exception e) {
            return null;
        }
    }

    public record RefreshStats(int beforeCount, int deleted, int inserted, long elapsedMs) {}
}
