package com.agora.service.ml;

import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.backtest.EntryFeatureSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfills the V047 indicator snapshot columns (adx14, rsi14, atr_pct, …)
 * on historical {@code bt_backtest_trade} rows that pre-date V047.
 *
 * <p>Approach: for each trade lacking a snapshot, load the last N bars of
 * {@code md_kline} ending at or just before the trade's entry_time, then run
 * {@link EntryFeatureSnapshot#compute} — identical logic to the live backtest
 * engine, so features match what a retrained backtest would produce.
 *
 * <p>Bounded: runs one batch at a time (limit parameter, default 200). Called
 * via MCP {@code backfillTradeIndicators} for controllable, observable runs.
 *
 * <p>Source choice: each trade's source comes from
 * {@code bt_backtest_result.kline_source} (V041). Legacy rows without source
 * fall back to {@code 'binance'} (wider history coverage).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlFeatureBackfillService {

    /** Lookback window — enough bars for EMA200 (V049) + buffer. */
    private static final int LOOKBACK_BARS = 250;
    private static final String FALLBACK_SOURCE = "binance";

    private final JdbcTemplate jdbc;
    private final MdKlineRepository klineRepository;

    /**
     * Backfill up to {@code limit} trades that don't yet have a snapshot.
     *
     * @return summary map: processed / updated / skipped / errors
     */
    @Transactional
    public Map<String, Integer> backfill(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        // Pick rows missing ANY of: V047 indicators, V049 regime, V050 HTF features.
        // Each subsequent ALTER set requires re-touching every row; UPDATE is
        // idempotent (same input klines = same outputs), so re-runs are safe.
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT t.id AS trade_id, t.entry_time, "
                        + "       r.symbol, r.interval_code, r.kline_source "
                        + "FROM bt_backtest_trade t "
                        + "JOIN bt_backtest_result r ON r.id = t.backtest_id "
                        + "WHERE (t.adx14 IS NULL OR t.dd_20bar_pct IS NULL "
                        + "       OR t.htf_dist_ema50_pct IS NULL) "
                        + "  AND t.entry_time IS NOT NULL "
                        + "ORDER BY t.id LIMIT ?",
                safeLimit);

        int processed = 0, updated = 0, skipped = 0, errors = 0;
        for (Map<String, Object> row : candidates) {
            processed++;
            Long tradeId = ((Number) row.get("trade_id")).longValue();
            // DATETIME(6) may come back as either java.sql.Timestamp (JDBC default)
            // or java.time.LocalDateTime (mysql-connector-j 8.0.23+ with time-zone
            // handling). Handle both paths.
            Object raw = row.get("entry_time");
            LocalDateTime entryTime;
            if (raw instanceof LocalDateTime) {
                entryTime = (LocalDateTime) raw;
            } else if (raw instanceof java.sql.Timestamp) {
                entryTime = ((java.sql.Timestamp) raw).toLocalDateTime();
            } else {
                log.warn("[MlBackfill] unexpected entry_time type {} for id={}", raw.getClass(), tradeId);
                skipped++;
                continue;
            }
            String symbol = (String) row.get("symbol");
            String interval = (String) row.get("interval_code");
            String source = (String) row.get("kline_source");
            if (source == null || source.isBlank()) source = FALLBACK_SOURCE;

            try {
                // Load same-TF klines up to entry_time. Source-aware.
                // 60d covers 250 bars on 4h (~42d) and on 1h (~10d) with margin.
                List<MdKline> klines = klineRepository
                        .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                                symbol, interval, source,
                                entryTime.minusDays(60),
                                entryTime);
                if (klines.size() < 28) {
                    skipped++;
                    continue;
                }
                int idx = klines.size() - 1;  // last bar at/before entry_time

                // V050: load HTF klines (4h for 1h trades, 1d for 4h trades).
                // 60-bar HTF lookback covers EMA50 + buffer.
                String htfInterval = htfFor(interval);
                List<MdKline> htfKlines = null;
                if (htfInterval != null) {
                    // Need ~60 HTF bars: 60×4h ≈ 10d, 60×1d = 60d. Window 90d covers both.
                    htfKlines = klineRepository
                            .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                                    symbol, htfInterval, source,
                                    entryTime.minusDays(90),
                                    entryTime);
                    if (htfKlines.size() < 50) htfKlines = null;  // not enough; leave HTF NULL
                }

                Map<String, Double> snap = EntryFeatureSnapshot.compute(klines, idx, htfKlines);
                if (snap.isEmpty()) {
                    skipped++;
                    continue;
                }
                Double htfTrendUp = snap.get(EntryFeatureSnapshot.HTF_TREND_UP);
                int rowsChanged = jdbc.update(
                        "UPDATE bt_backtest_trade SET "
                                + "  adx14=?, rsi14=?, atr_pct=?, volume_ratio_ma20=?, "
                                + "  close_vs_ema50_pct=?, ema20_slope_pct=?, bb_width_pct=?, "
                                + "  dd_20bar_pct=?, dd_50bar_pct=?, momentum_50bar_pct=?, "
                                + "  realized_vol_20bar=?, dist_from_ema200_pct=?, range_pct_50bar=?, "
                                + "  htf_momentum_50bar_pct=?, htf_trend_up=?, htf_dist_ema50_pct=? "
                                + "WHERE id=?",
                        toBd(snap.get(EntryFeatureSnapshot.ADX14), 4),
                        toBd(snap.get(EntryFeatureSnapshot.RSI14), 4),
                        toBd(snap.get(EntryFeatureSnapshot.ATR_PCT), 8),
                        toBd(snap.get(EntryFeatureSnapshot.VOLUME_RATIO_MA20), 6),
                        toBd(snap.get(EntryFeatureSnapshot.CLOSE_VS_EMA50_PCT), 8),
                        toBd(snap.get(EntryFeatureSnapshot.EMA20_SLOPE_PCT), 8),
                        toBd(snap.get(EntryFeatureSnapshot.BB_WIDTH_PCT), 8),
                        toBd(snap.get(EntryFeatureSnapshot.DD_20BAR_PCT), 6),
                        toBd(snap.get(EntryFeatureSnapshot.DD_50BAR_PCT), 6),
                        toBd(snap.get(EntryFeatureSnapshot.MOMENTUM_50BAR_PCT), 8),
                        toBd(snap.get(EntryFeatureSnapshot.REALIZED_VOL_20BAR), 8),
                        toBd(snap.get(EntryFeatureSnapshot.DIST_FROM_EMA200_PCT), 8),
                        toBd(snap.get(EntryFeatureSnapshot.RANGE_PCT_50BAR), 6),
                        toBd(snap.get(EntryFeatureSnapshot.HTF_MOMENTUM_50BAR_PCT), 8),
                        htfTrendUp == null ? null : (htfTrendUp > 0.5 ? 1 : 0),
                        toBd(snap.get(EntryFeatureSnapshot.HTF_DIST_EMA50_PCT), 8),
                        tradeId);
                if (rowsChanged == 1) updated++;
            } catch (Exception e) {
                errors++;
                log.warn("[MlBackfill] trade_id={} failed: {}", tradeId, e.getMessage());
            }
        }

        Map<String, Integer> summary = new HashMap<>();
        summary.put("processed", processed);
        summary.put("updated", updated);
        summary.put("skipped_no_history", skipped);
        summary.put("errors", errors);
        summary.put("remaining_estimate", countRemaining());
        log.info("[MlBackfill] {}", summary);
        return summary;
    }

    public int countRemaining() {
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bt_backtest_trade "
                        + "WHERE (adx14 IS NULL OR dd_20bar_pct IS NULL "
                        + "       OR htf_dist_ema50_pct IS NULL) "
                        + "  AND entry_time IS NOT NULL",
                Integer.class);
        return v == null ? 0 : v;
    }

    /**
     * One-step-up timeframe mapping for HTF features.
     * Returns null if no sensible HTF exists (e.g. 1d trades — would need 1w
     * which we don't store).
     */
    private String htfFor(String interval) {
        if (interval == null) return null;
        return switch (interval) {
            case "1h"  -> "4h";
            case "4h"  -> "1d";
            default    -> null;   // 1d / 1w / etc. — no HTF
        };
    }

    private static BigDecimal toBd(Double v, int scale) {
        if (v == null || Double.isNaN(v) || Double.isInfinite(v)) return null;
        return BigDecimal.valueOf(v).setScale(scale, java.math.RoundingMode.HALF_UP);
    }
}
