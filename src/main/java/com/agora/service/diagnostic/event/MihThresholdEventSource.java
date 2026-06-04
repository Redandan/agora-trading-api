package com.agora.service.diagnostic.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * #337 EventSource: mih_threshold — 從 {@code market_indicator_history} 抽取「指標跨越閾值」事件。
 *
 * <p>filter 格式：{@code "indicator:operator:value"}，例如：
 * <ul>
 *   <li>{@code funding_rate:lte:-0.0003}</li>
 *   <li>{@code long_short_ratio:lt:0.85}</li>
 *   <li>{@code sqi:gte:40}</li>
 *   <li>{@code whale_buy_ratio:gt:0.65}</li>
 * </ul>
 * operator 支援 gt / gte / lt / lte / eq。
 *
 * <p><b>State-transition 模式（V1 改進）</b>：原本「持續匹配」算法在 720 hourly bar 中可能
 * 回 200 個 events（持續 200 小時都低於閾值），統計被「該指標期間整體報酬率」污染。
 * V1 改成只抓「**從 不滿足 → 滿足**」的轉折瞬間，hit_rate 才反映「事件後反應」而非
 * 「整體 bias」。
 *
 * <p>方向由 {@link IndicatorDirectionResolver} 依 (indicator, operator) 提供，例如
 * funding_rate:lt 是 LONG，funding_rate:gt 是 SHORT — 不再硬編 LONG。
 *
 * <p>TZ：純 LocalDateTime 參數比較，JDBC round-trip 自動處理（見 PriceLookup javadoc）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MihThresholdEventSource implements EventSource {

    private final JdbcTemplate jdbc;
    private final IndicatorDirectionResolver directionResolver;

    @Override
    public String name() { return "mih_threshold"; }

    @Override
    public List<Event> fetch(String filter, LocalDateTime from, LocalDateTime to) {
        if (filter == null || filter.isBlank()) {
            log.warn("[MihThresholdEventSource] filter required, format: 'indicator:operator:value'");
            return List.of();
        }
        String[] parts = filter.split(":");
        if (parts.length != 3) {
            log.warn("[MihThresholdEventSource] invalid filter (need indicator:operator:value): {}", filter);
            return List.of();
        }
        String indicator = parts[0].trim();
        String op = parts[1].trim().toLowerCase();
        double threshold;
        try {
            threshold = Double.parseDouble(parts[2].trim());
        } catch (NumberFormatException e) {
            log.warn("[MihThresholdEventSource] non-numeric value in filter: {}", filter);
            return List.of();
        }
        if (!isValidOperator(op)) {
            log.warn("[MihThresholdEventSource] invalid operator (need gt/gte/lt/lte/eq): {}", op);
            return List.of();
        }
        String direction = directionResolver.forMihThreshold(indicator, op);

        try {
            // 拉時間範圍內所有 (ts, value)，Java 端做 transition 判定（state-machine）
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT captured_at, value " +
                    "FROM market_indicator_history FORCE INDEX (idx_mih_sym_ind_err_captured_value) " +
                    "WHERE symbol='BTCUSDT' AND indicator=? AND error_flag=0 " +
                    "  AND captured_at >= ? AND captured_at <= ? " +
                    "ORDER BY captured_at ASC",
                    indicator, from, to);
            List<Event> events = new ArrayList<>();
            boolean prevSatisfied = false;
            boolean firstRow = true;
            for (Map<String, Object> row : rows) {
                Object tsObj = row.get("captured_at");
                Object vObj = row.get("value");
                if (tsObj == null || vObj == null) continue;
                LocalDateTime ts = (tsObj instanceof Timestamp tsq) ? tsq.toLocalDateTime() : (LocalDateTime) tsObj;
                double val = ((Number) vObj).doubleValue();
                boolean satisfied = matches(val, op, threshold);
                // 第一筆若已滿足，視為「進入態」事件（避免 30d 窗起點掉資料）
                if (firstRow) {
                    if (satisfied) {
                        events.add(new Event(ts, direction, val,
                                buildLabel(indicator, op, threshold, val)));
                    }
                    firstRow = false;
                    prevSatisfied = satisfied;
                    continue;
                }
                // 從 false → true 才算 transition event
                if (!prevSatisfied && satisfied) {
                    events.add(new Event(ts, direction, val,
                            buildLabel(indicator, op, threshold, val)));
                }
                prevSatisfied = satisfied;
            }
            return events;
        } catch (Exception e) {
            log.warn("[MihThresholdEventSource] query failed: filter={} reason={}", filter, e.getMessage());
            return List.of();
        }
    }

    private static boolean isValidOperator(String op) {
        return switch (op) {
            case "gt", "gte", "ge", "lt", "lte", "le", "eq" -> true;
            default -> false;
        };
    }

    private static boolean matches(double value, String op, double threshold) {
        return switch (op) {
            case "gt"          -> value > threshold;
            case "gte", "ge"   -> value >= threshold;
            case "lt"          -> value < threshold;
            case "lte", "le"   -> value <= threshold;
            case "eq"          -> value == threshold;
            default            -> false;
        };
    }

    private static String buildLabel(String indicator, String op, double threshold, double actual) {
        String sym = switch (op) {
            case "gt" -> ">";
            case "gte", "ge" -> "≥";
            case "lt" -> "<";
            case "lte", "le" -> "≤";
            case "eq" -> "=";
            default -> op;
        };
        return String.format("%s %s %s (=%.4f)", indicator, sym, threshold, actual);
    }
}
