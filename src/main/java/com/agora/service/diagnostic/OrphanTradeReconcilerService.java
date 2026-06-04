package com.agora.service.diagnostic;

import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * #355 V1 — 對齊 OKX SPOT trade history 與 DB 端 (bt_grid_level / bt_live_signal) 的記錄，
 * 抓 distributed-tx gap 造成的孤兒。觸發來自 #340 反覆 fire 的 reconcile alert：OKX BUY 成功
 * 但 Java 端寫 DB 失敗，造成永久不一致，現有 reconcileHoldings 只能算總差不能指認哪筆。
 *
 * <p>V1 邏輯：
 * <ol>
 *   <li>從 OkxTradingService.getRecentFills("SPOT", 100) 抓最近 N 小時的 BUY/SELL</li>
 *   <li>從 bt_grid_level + bt_live_signal 撈時間窗內的 filled records</li>
 *   <li>對每筆 OKX trade 找最佳 DB 匹配（price + qty + time tolerance scoring）</li>
 *   <li>輸出 ✅ matched / ❌ orphan OKX / ⚠️ orphan DB，附 untracked 數量總計</li>
 * </ol>
 *
 * <p>注意：DB 的 filled_at 是 JDBC serverTimezone=Asia/Taipei 寫入的 wall-clock，比 OKX UTC ts 多 8h。
 * matching 時要做 -8h 還原。
 *
 * <p>V1 不做：multi-currency 一次掃 / 自動修補（人工 review SQL 後手動執行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanTradeReconcilerService {

    private final OkxTradingService okxTradingService;
    private final JdbcTemplate jdbc;

    private static final DateTimeFormatter UTC_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm 'UTC'");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    public String reconcile(String currency, int hoursBack,
                             double priceTolerance, double qtyTolerancePct,
                             int timeToleranceMinutes,
                             boolean includeFixSuggestion) {
        if (currency == null || currency.isBlank()) currency = "BTC";
        if (hoursBack <= 0 || hoursBack > 168) hoursBack = 24;
        if (priceTolerance <= 0) priceTolerance = 10.0;
        if (qtyTolerancePct <= 0) qtyTolerancePct = 0.5;
        if (timeToleranceMinutes <= 0) timeToleranceMinutes = 5;

        String instId = currency.toUpperCase() + "-USDT";
        Instant windowEnd = Instant.now();
        Instant windowStart = windowEnd.minus(Duration.ofHours(hoursBack));
        Instant historyStart = windowStart.minus(Duration.ofHours(168));

        // 1. OKX SPOT fills
        List<OkxTrade> okxTrades;
        try {
            okxTrades = loadOkxSpotTrades(instId, historyStart);
            okxTrades = aggregateSplitFillsByOrder(okxTrades);
        } catch (Exception e) {
            return "❌ 取 OKX trade history 失敗: " + e.getMessage();
        }

        // 2. DB candidates (bt_grid_level + bt_live_signal)
        String symbol = currency.toUpperCase() + "USDT";
        // window 涵蓋 +8h Taipei 偏移時也能撈到（保險擴大窗口 -8h）
        LocalDateTime sqlSince = LocalDateTime.ofInstant(
                historyStart.minus(Duration.ofHours(9)), ZoneOffset.UTC);

        List<DbRec> gridFills = loadGridLevelFills(symbol, sqlSince);
        List<DbRec> sigFills  = loadLiveSignalFills(symbol, sqlSince);
        List<DbRec> all = new ArrayList<>();
        all.addAll(gridFills);
        all.addAll(sigFills);

        // 3. Match OKX → DB
        Set<String> usedDbKeys = new HashSet<>();
        List<MatchRow> rows = new ArrayList<>();
        for (OkxTrade t : okxTrades) {
            DbRec best = null;
            int bestScore = 0;
            for (DbRec d : all) {
                if (usedDbKeys.contains(d.key())) continue;
                if (!sideMatches(t.side, d.kind)) continue;
                int score = scoreMatch(t, d, priceTolerance, qtyTolerancePct, timeToleranceMinutes);
                if (score > bestScore) {
                    bestScore = score;
                    best = d;
                }
            }
            if (bestScore >= 2 && best != null) {
                usedDbKeys.add(best.key());
                rows.add(new MatchRow(t, best, true));
            } else {
                rows.add(new MatchRow(t, null, false));
            }
        }

        // 4. Format
        return formatReport(currency, hoursBack, rows, all, usedDbKeys, windowStart, windowEnd,
                timeToleranceMinutes, qtyTolerancePct, includeFixSuggestion);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private List<OkxTrade> loadOkxSpotTrades(String instIdFilter, Instant since) {
        JsonNode fills = okxTradingService.getRecentFills("SPOT", 100);
        List<OkxTrade> result = new ArrayList<>();
        if (!fills.isArray()) return result;
        for (JsonNode f : fills) {
            String instId = f.path("instId").asText("");
            if (!instId.equalsIgnoreCase(instIdFilter)) continue;
            long ts = f.path("ts").asLong(0);
            if (ts <= 0) continue;
            Instant when = Instant.ofEpochMilli(ts);
            if (when.isBefore(since)) continue;
            String side = f.path("side").asText("");
            BigDecimal px;
            BigDecimal sz;
            try {
                px = new BigDecimal(f.path("fillPx").asText("0"));
                sz = new BigDecimal(f.path("fillSz").asText("0"));
            } catch (NumberFormatException nfe) { continue; }
            if (sz.signum() <= 0 || px.signum() <= 0) continue;
            result.add(new OkxTrade(when, side, px, sz, f.path("ordId").asText(""), 1));
        }
        return result;
    }

    private List<OkxTrade> aggregateSplitFillsByOrder(List<OkxTrade> fills) {
        Map<String, SplitFillAccumulator> grouped = new LinkedHashMap<>();
        List<OkxTrade> withoutOrderId = new ArrayList<>();
        for (OkxTrade fill : fills) {
            if (fill.ordId == null || fill.ordId.isBlank()) {
                withoutOrderId.add(fill);
                continue;
            }
            String key = fill.ordId + "|" + fill.side.toUpperCase();
            grouped.computeIfAbsent(key, ignored -> new SplitFillAccumulator(fill.ordId, fill.side))
                    .add(fill);
        }
        List<OkxTrade> aggregated = new ArrayList<>(withoutOrderId);
        grouped.values().forEach(acc -> aggregated.add(acc.toTrade()));
        aggregated.sort((a, b) -> b.when.compareTo(a.when));
        return aggregated;
    }

    private List<DbRec> loadGridLevelFills(String symbol, LocalDateTime sqlSince) {
        try {
            // bt_grid_level FK to bt_grid by grid_id; join to filter symbol
            String sql = """
                SELECT l.id, g.symbol, l.status, l.filled_qty, l.filled_price, l.filled_at
                FROM bt_grid_level l
                JOIN bt_grid g ON g.id = l.grid_id
                WHERE g.symbol = ?
                  AND l.filled_at IS NOT NULL
                  AND l.filled_at >= ?
                  AND l.filled_qty IS NOT NULL
                  AND l.filled_price IS NOT NULL
                ORDER BY l.filled_at DESC
                """;
            return jdbc.query(sql, (rs, n) -> new DbRec(
                    "grid_level",
                    rs.getLong("id"),
                    "BUY",  // grid level "fill" 永遠是 BUY 端 (sell 透過 OCO 另記)
                    rs.getBigDecimal("filled_price"),
                    rs.getBigDecimal("filled_qty"),
                    rs.getTimestamp("filled_at").toLocalDateTime(),
                    "Grid level id=" + rs.getLong("id") + " status=" + rs.getString("status")
            ), symbol, sqlSince);
        } catch (Exception e) {
            log.warn("[OrphanReconciler] grid_level query failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<DbRec> loadLiveSignalFills(String symbol, LocalDateTime sqlSince) {
        try {
            String sql = """
                SELECT id, side, traded_qty, actual_entry_price, notified_at, exit_time, exit_price
                FROM bt_live_signal
                WHERE symbol = ?
                  AND auto_traded = 1
                  AND traded_qty IS NOT NULL
                  AND notified_at >= ?
                ORDER BY notified_at DESC
                """;
            List<DbRec> out = new ArrayList<>();
            jdbc.query(sql, rs -> {
                long id = rs.getLong("id");
                String side = rs.getString("side");
                BigDecimal qty = rs.getBigDecimal("traded_qty");
                BigDecimal entryPx = rs.getBigDecimal("actual_entry_price");
                java.sql.Timestamp notifiedAt = rs.getTimestamp("notified_at");
                java.sql.Timestamp exitTime = rs.getTimestamp("exit_time");
                BigDecimal exitPx = rs.getBigDecimal("exit_price");
                if (entryPx != null && qty != null && notifiedAt != null) {
                    out.add(new DbRec("live_signal_entry", id,
                            "LONG".equals(side) ? "BUY" : "SELL",
                            entryPx, qty, notifiedAt.toLocalDateTime(),
                            "LiveSignal id=" + id + " entry " + side));
                }
                if (exitTime != null && exitPx != null && qty != null) {
                    out.add(new DbRec("live_signal_exit", id,
                            "LONG".equals(side) ? "SELL" : "BUY",
                            exitPx, qty, exitTime.toLocalDateTime(),
                            "LiveSignal id=" + id + " exit " + side));
                }
            }, symbol, sqlSince);
            return out;
        } catch (Exception e) {
            log.warn("[OrphanReconciler] live_signal query failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean sideMatches(String okxSide, String dbKind) {
        if (okxSide == null) return false;
        return okxSide.equalsIgnoreCase(dbKind);
    }

    private static int scoreMatch(OkxTrade t, DbRec d,
                                   double priceTol, double qtyTolPct, int timeTolMin) {
        int score = 0;
        if (d.price.subtract(t.price).abs().doubleValue() <= priceTol) score++;
        BigDecimal denom = t.qty.signum() == 0 ? BigDecimal.ONE : t.qty;
        double qtyDiffPct = d.qty.subtract(t.qty).abs()
                .divide(denom, 10, java.math.RoundingMode.HALF_UP)
                .doubleValue() * 100.0;
        if (qtyDiffPct <= qtyTolPct) score++;
        // DB filled_at 是 Taipei wall-clock，OKX ts 是 UTC；DB time 視為 UTC+8 wall-clock 還原回 UTC 比對
        Instant dbAdjusted = d.time.atZone(TAIPEI).toInstant();
        long deltaMin = Math.abs(Duration.between(t.when, dbAdjusted).toMinutes());
        if (deltaMin <= timeTolMin) score++;
        return score;
    }

    private static Instant dbAdjustedInstant(DbRec d) {
        // DB timestamps are stored as Taipei wall-clock values; convert them back to UTC for OKX-window checks.
        return d.time.atZone(TAIPEI).toInstant();
    }

    private static boolean insideOkxReportWindow(DbRec d, Instant windowStart,
                                                 Instant windowEnd, int timeToleranceMinutes) {
        Instant adjusted = dbAdjustedInstant(d);
        Duration tolerance = Duration.ofMinutes(Math.max(0, timeToleranceMinutes));
        return !adjusted.isBefore(windowStart.minus(tolerance))
                && !adjusted.isAfter(windowEnd.plus(tolerance));
    }

    private String formatReport(String currency, int hoursBack,
                                 List<MatchRow> allRows, List<DbRec> dbCandidates,
                                 Set<String> usedDbKeys,
                                 Instant windowStart, Instant windowEnd,
                                 int timeToleranceMinutes,
                                 double qtyTolerancePct,
                                 boolean includeFix) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Orphan Trade Reconciliation ===\n");
        sb.append("Currency: ").append(currency).append("  Window: last ").append(hoursBack).append("h\n\n");

        List<MatchRow> rows = allRows.stream()
                .filter(r -> !r.t.when.isBefore(windowStart) && !r.t.when.isAfter(windowEnd))
                .toList();
        OffsetPairs offsetPairs = offsetUntrackedBuySellPairs(allRows, qtyTolerancePct);

        sb.append(String.format("📋 OKX SPOT trades vs DB records (n=%d):\n", rows.size()));
        sb.append("OKX time              side  qty           price        → DB match\n");
        sb.append("─────────────────────────────────────────────────────────────────\n");
        // #374 — 拆 BUY/SELL orphan counter，detail 與 summary 對齊
        int matched = 0, orphanBuy = 0, orphanSell = 0;
        int offsetPairCountInWindow = 0;
        BigDecimal orphanBuyQty = BigDecimal.ZERO;
        BigDecimal orphanBuyValueUsd = BigDecimal.ZERO;
        BigDecimal orphanSellQty = BigDecimal.ZERO;
        BigDecimal orphanSellValueUsd = BigDecimal.ZERO;
        for (MatchRow r : rows) {
            String time = UTC_FMT.format(r.t.when.atZone(ZoneOffset.UTC));
            OkxTrade offsetBuy = offsetPairs.sellToBuy.get(r.t);
            boolean offsetBuyClosed = offsetPairs.closedBuys.contains(r.t);
            String splitFillTag = r.t.fillCount > 1
                    ? " (split fills x" + r.t.fillCount + " ordId=" + r.t.ordId + ")"
                    : "";
            String matchTag = r.matched
                    ? "✅ " + r.dbRec.label + splitFillTag
                    : offsetBuy != null
                    ? "✅ closes earlier untracked BUY " + UTC_FMT.format(offsetBuy.when.atZone(ZoneOffset.UTC)) + splitFillTag
                    : offsetBuyClosed
                    ? "↔ untracked BUY later closed by OKX SELL" + splitFillTag
                    : "❌ ORPHAN (no DB record)" + splitFillTag;
            sb.append(String.format("%s  %-4s  %-12s  %-10s   %s%n",
                    time, r.t.side, r.t.qty.toPlainString(),
                    r.t.price.toPlainString(), matchTag));
            if (r.matched) {
                matched++;
            } else if (offsetBuy != null) {
                offsetPairCountInWindow++;
            } else if (offsetBuyClosed) {
                // The untracked inventory was closed by a later OKX SELL, so it
                // should not be treated as current extra BTC exposure.
            } else if ("BUY".equalsIgnoreCase(r.t.side)) {
                orphanBuy++;
                orphanBuyQty = orphanBuyQty.add(r.t.qty);
                orphanBuyValueUsd = orphanBuyValueUsd.add(r.t.qty.multiply(r.t.price));
            } else if ("SELL".equalsIgnoreCase(r.t.side)) {
                orphanSell++;
                orphanSellQty = orphanSellQty.add(r.t.qty);
                orphanSellValueUsd = orphanSellValueUsd.add(r.t.qty.multiply(r.t.price));
            }
        }
        sb.append("\n");

        // DB records not matched
        List<DbRec> unmatchedDb = new ArrayList<>();
        List<DbRec> ignoredOutsideWindow = new ArrayList<>();
        for (DbRec d : dbCandidates) {
            if (usedDbKeys.contains(d.key())) continue;
            if (insideOkxReportWindow(d, windowStart, windowEnd, timeToleranceMinutes)) {
                unmatchedDb.add(d);
            } else {
                ignoredOutsideWindow.add(d);
            }
        }
        sb.append(String.format("📋 DB records without OKX match: %d 筆%n", unmatchedDb.size()));
        for (DbRec d : unmatchedDb) {
            sb.append(String.format("  ⚠️  %s (qty=%s px=%s at=%s)%n",
                    d.label, d.qty.toPlainString(), d.price.toPlainString(), d.time));
        }
        if (!ignoredOutsideWindow.isEmpty()) {
            sb.append(String.format("📋 DB records outside OKX window ignored: %d 筆%n",
                    ignoredOutsideWindow.size()));
        }
        sb.append("\n");

        sb.append("🚨 Summary\n");
        sb.append("  ✅ Matched OKX trades:               ").append(matched).append("\n");
        sb.append("  ↔ Offset orphan BUY/SELL pairs:      ").append(offsetPairCountInWindow).append("\n");
        sb.append("  ❌ Orphan OKX BUY (DB 沒記，帳戶多 BTC):  ").append(orphanBuy).append("\n");
        sb.append("  ❌ Orphan OKX SELL (DB 仍 HOLDING，DB 卡住): ").append(orphanSell).append("\n");
        sb.append("  ⚠️  Orphan DB (DB 有但 OKX 沒成交):       ").append(unmatchedDb.size()).append("\n");
        if (orphanBuy > 0) {
            sb.append(String.format("  💰 Untracked BUY qty: %s  (~$%.2f)%n",
                    orphanBuyQty.toPlainString(), orphanBuyValueUsd.doubleValue()));
        }
        if (orphanSell > 0) {
            sb.append(String.format("  💰 Stuck SELL qty:    %s  (~$%.2f)%n",
                    orphanSellQty.toPlainString(), orphanSellValueUsd.doubleValue()));
        }

        if (includeFix && (orphanBuy > 0 || orphanSell > 0)) {
            sb.append("\n🛠 Suggested fix (人工 review 後執行):\n");
            if (orphanBuy > 0) {
                sb.append("  BUY orphan (帳戶多 BTC):\n");
                sb.append("    A: 補登記 bt_grid_level INSERT（需確認 grid_id / level_index）\n");
                sb.append("    B: 手動 SELL 還掉 untracked qty 對齊 DB\n");
            }
            if (orphanSell > 0) {
                sb.append("  SELL orphan (DB 卡 HOLDING):\n");
                sb.append("    UPDATE bt_grid_level SET status='CLOSED', closed_at=NOW(), realized_pnl=...\n");
                sb.append("    WHERE id=<對應 HOLDING level>; -- 比對 paired_sell_price 與 OKX SELL price\n");
            }
        }
        return sb.toString();
    }

    private OffsetPairs offsetUntrackedBuySellPairs(List<MatchRow> allRows, double qtyTolerancePct) {
        List<OkxTrade> orphanBuys = new ArrayList<>();
        Map<OkxTrade, OkxTrade> sellToBuy = new java.util.LinkedHashMap<>();
        Set<OkxTrade> closedBuys = new HashSet<>();
        for (MatchRow row : allRows.stream().sorted((a, b) -> a.t.when.compareTo(b.t.when)).toList()) {
            if (row.matched) {
                continue;
            }
            if ("BUY".equalsIgnoreCase(row.t.side)) {
                orphanBuys.add(row.t);
                continue;
            }
            if (!"SELL".equalsIgnoreCase(row.t.side)) {
                continue;
            }
            OkxTrade buy = orphanBuys.stream()
                    .filter(candidate -> !closedBuys.contains(candidate))
                    .filter(candidate -> !candidate.when.isAfter(row.t.when))
                    .filter(candidate -> qtyClose(candidate.qty, row.t.qty, qtyTolerancePct))
                    .findFirst()
                    .orElse(null);
            if (buy != null) {
                closedBuys.add(buy);
                sellToBuy.put(row.t, buy);
            }
        }
        return new OffsetPairs(sellToBuy, closedBuys);
    }

    private boolean qtyClose(BigDecimal a, BigDecimal b, double qtyTolerancePct) {
        if (a == null || b == null || b.signum() == 0) {
            return false;
        }
        double diffPct = a.subtract(b).abs()
                .divide(b.abs(), 10, java.math.RoundingMode.HALF_UP)
                .doubleValue() * 100.0;
        return diffPct <= Math.max(0.0, qtyTolerancePct);
    }

    // ─── records ─────────────────────────────────────────────────────────────

    private record OkxTrade(Instant when, String side, BigDecimal price, BigDecimal qty,
                            String ordId, int fillCount) {}

    private static class SplitFillAccumulator {
        private final String ordId;
        private final String side;
        private Instant latestWhen;
        private BigDecimal qty = BigDecimal.ZERO;
        private BigDecimal notional = BigDecimal.ZERO;
        private int fillCount = 0;

        private SplitFillAccumulator(String ordId, String side) {
            this.ordId = ordId;
            this.side = side;
        }

        private void add(OkxTrade fill) {
            if (latestWhen == null || fill.when.isAfter(latestWhen)) {
                latestWhen = fill.when;
            }
            qty = qty.add(fill.qty);
            notional = notional.add(fill.qty.multiply(fill.price));
            fillCount += Math.max(1, fill.fillCount);
        }

        private OkxTrade toTrade() {
            BigDecimal averagePrice = qty.signum() == 0
                    ? BigDecimal.ZERO
                    : notional.divide(qty, 8, java.math.RoundingMode.HALF_UP);
            return new OkxTrade(latestWhen, side, averagePrice, qty, ordId, fillCount);
        }
    }

    private record DbRec(String source, long id, String kind,
                          BigDecimal price, BigDecimal qty,
                          LocalDateTime time, String label) {
        String key() { return source + ":" + id + ":" + kind; }
    }

    private record MatchRow(OkxTrade t, DbRec dbRec, boolean matched) {}

    private record OffsetPairs(Map<OkxTrade, OkxTrade> sellToBuy,
                               Set<OkxTrade> closedBuys) {}
}
