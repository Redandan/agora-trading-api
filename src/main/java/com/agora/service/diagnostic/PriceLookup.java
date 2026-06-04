package com.agora.service.diagnostic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * #337 統一價格查詢 — 對 BTC/ETH 事件做事後正確率驗證時，需要在事件 ts 取「最近價格」與
 * "ts + horizonHours" 取「事後價格」。
 *
 * <p>底層查 {@code market_indicator_history.kraken_btc_usd_price}（hourly 解析度，與既有
 * {@code analyzeBlockedSignalOutcomes} 一致）。
 *
 * <p>性能：先一次性把 30-180d 的 price 撈進 {@code Map<HourBucket, Double>} cache，後續
 * 每個 event 兩次 lookup O(1)。
 *
 * <p><b>TZ 設計</b>：JVM 在 server 上是 UTC，所有 {@code LocalDateTime} 在 Java 端都代表
 * UTC wall-clock。JDBC URL 有 {@code serverTimezone=Asia/Taipei} → DB raw 值 = UTC - 8h，
 * 但 LocalDateTime round-trip preserves the value（write/read 都會自動 ±8h 補償）。
 *
 * <p>只要：(1) 寫入用 LocalDateTime；(2) 讀出也用 LocalDateTime；(3) 比較用 LocalDateTime
 * 參數而非 SQL {@code NOW()}，整個查詢就在「Java UTC space」中工作，**不需 CONVERT_TZ**。
 *
 * <p>只有用到 SQL {@code NOW()} 比較 {@code captured_at} 時才需要 +8h workaround（issue
 * #323 完成後可移除全部 workaround）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceLookup {

    private final JdbcTemplate jdbc;

    /**
     * 建立一個 price cache 供後續查詢；推薦每個 verify 一個 instance。
     *
     * @param symbol BTCUSDT / ETHUSDT
     * @param from   起點（Java UTC LocalDateTime，會額外多撈前後 2h 緩衝）
     * @param to     終點（Java UTC LocalDateTime）
     */
    public Cache buildCache(String symbol, LocalDateTime from, LocalDateTime to) {
        String indicator = resolvePriceIndicator(symbol);
        if (indicator == null) {
            log.warn("[PriceLookup] no price indicator mapping for symbol={}", symbol);
            return new Cache(Map.of(), symbol);
        }
        // 多撈 ±2h 緩衝，避免邊界 event 找不到對應 price
        LocalDateTime fromBuf = from.minusHours(2);
        LocalDateTime toBuf = to.plusHours(2);
        try {
            // 純 LocalDateTime 參數比較 — JDBC 自動處理 round-trip，不需 CONVERT_TZ
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT captured_at, value " +
                    "FROM market_indicator_history FORCE INDEX (idx_mih_sym_ind_err_captured_value) " +
                    "WHERE symbol=? AND indicator=? " +
                    "  AND captured_at >= ? AND captured_at <= ? " +
                    "  AND error_flag = 0 " +
                    "ORDER BY captured_at ASC",
                    symbol, indicator, fromBuf, toBuf);
            Map<LocalDateTime, Double> map = new HashMap<>(rows.size() * 2);
            for (Map<String, Object> row : rows) {
                Object tsObj = row.get("captured_at");
                Object valObj = row.get("value");
                if (tsObj == null || valObj == null) continue;
                LocalDateTime ts;
                if (tsObj instanceof java.sql.Timestamp tsq) {
                    ts = tsq.toLocalDateTime();
                } else if (tsObj instanceof LocalDateTime ldt) {
                    ts = ldt;
                } else {
                    continue;
                }
                ts = ts.truncatedTo(ChronoUnit.HOURS);
                double v = ((Number) valObj).doubleValue();
                // 同一 hour 多筆取最後（ASC ordering 已確保最後一筆為該小時最新）
                map.put(ts, v);
            }
            log.debug("[PriceLookup] cache built: symbol={} indicator={} hours={}", symbol, indicator, map.size());
            return new Cache(map, symbol);
        } catch (Exception e) {
            log.warn("[PriceLookup] cache build failed: symbol={} reason={}", symbol, e.getMessage());
            return new Cache(Map.of(), symbol);
        }
    }

    private String resolvePriceIndicator(String symbol) {
        if (symbol == null) return "kraken_btc_usd_price";
        return switch (symbol.toUpperCase()) {
            case "BTCUSDT", "BTC" -> "kraken_btc_usd_price";
            case "ETHUSDT", "ETH" -> "kraken_eth_usd_price"; // 若無此 indicator，cache 為 empty
            default -> null;
        };
    }

    /**
     * Cache 物件 — 一次性建立，後續 lookup O(1)。Thread-safe（HashMap 不可變視同唯讀使用）。
     */
    public static final class Cache {
        private final Map<LocalDateTime, Double> hourMap;
        private final String symbol;

        Cache(Map<LocalDateTime, Double> hourMap, String symbol) {
            this.hourMap = hourMap;
            this.symbol = symbol;
        }

        public boolean isEmpty() { return hourMap.isEmpty(); }
        public int size()        { return hourMap.size(); }
        public String symbol()   { return symbol; }

        /**
         * 找 ts 最近的 hourly bucket price，誤差 ≤ 1 小時（前後嘗試）才回。
         */
        public Optional<Double> priceAt(LocalDateTime ts) {
            if (ts == null || hourMap.isEmpty()) return Optional.empty();
            LocalDateTime hourBucket = ts.truncatedTo(ChronoUnit.HOURS);
            Double v = hourMap.get(hourBucket);
            if (v != null) return Optional.of(v);
            // 嘗試前後一小時（事件可能落在 H:55，hour 收尾尚未到）
            v = hourMap.get(hourBucket.plusHours(1));
            if (v != null) return Optional.of(v);
            v = hourMap.get(hourBucket.minusHours(1));
            return Optional.ofNullable(v);
        }

        /**
         * 算 (price@(ts+horizon) - price@ts) / price@ts。
         * 任一端缺資料 → empty。
         */
        public Optional<Double> returnOver(LocalDateTime from, int horizonHours) {
            Optional<Double> p0 = priceAt(from);
            Optional<Double> p1 = priceAt(from.plusHours(horizonHours));
            if (p0.isEmpty() || p1.isEmpty() || p0.get() == 0.0) return Optional.empty();
            return Optional.of((p1.get() - p0.get()) / p0.get());
        }
    }
}
