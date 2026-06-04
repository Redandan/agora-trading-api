package com.agora.service.diagnostic;

import com.agora.config.properties.DbSlowQueryMonitorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DbSlowQueryMonitorService {

    private static final String HEATWAVE_SECONDARY_LOAD =
            "ALTER TABLE% SECONDARY_LOAD";
    private static final String HEATWAVE_SECONDARY_UNLOAD =
            "ALTER TABLE% SECONDARY_UNLOAD";
    private static final String HEATWAVE_RAPID_OPERATION =
            "SET rapid_ml_operation = JSON_OBJECT%";

    private final JdbcTemplate jdbc;
    private final DbSlowQueryMonitorProperties props;

    public Report scan() {
        return scan(props.watchSeconds(), props.maxRows());
    }

    public Report scan(int minSeconds, int maxRows) {
        int min = Math.max(1, minSeconds);
        int limit = Math.max(1, Math.min(maxRows, 50));
        List<Row> rows = jdbc.queryForList("""
                        SELECT ID, USER, DB, COMMAND, TIME, STATE,
                               LEFT(REPLACE(REPLACE(INFO, CHAR(10), ' '), CHAR(9), ' '), 500) AS info
                        FROM information_schema.PROCESSLIST
                        WHERE ID <> CONNECTION_ID()
                          AND COMMAND NOT IN ('Sleep', 'Killed')
                          AND (
                               (DB = DATABASE() AND TIME >= ?)
                            OR INFO LIKE ?
                            OR INFO LIKE ?
                            OR INFO LIKE ?
                            OR (INFO LIKE '%market_indicator_history%' AND TIME >= ?)
                            OR (INFO LIKE '%md_kline%' AND TIME >= ?)
                            OR (STATE LIKE '%metadata lock%' AND TIME >= ?)
                          )
                        ORDER BY TIME DESC
                        LIMIT ?
                        """,
                        min,
                        HEATWAVE_SECONDARY_LOAD,
                        HEATWAVE_SECONDARY_UNLOAD,
                        HEATWAVE_RAPID_OPERATION,
                        min,
                        min,
                        min,
                        limit)
                .stream()
                .map(Row::from)
                .toList();

        int killedHeatWaveRemnants = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.PROCESSLIST
                WHERE COMMAND = 'Killed'
                  AND (INFO LIKE ? OR INFO LIKE ?)
                """, Integer.class, HEATWAVE_SECONDARY_LOAD, HEATWAVE_SECONDARY_UNLOAD);

        Status status = classify(rows);
        int suppressedExpectedReportRows = (int) rows.stream()
                .filter(this::isSuppressedExpectedReportRow)
                .count();
        return new Report(Instant.now(), status, rows, killedHeatWaveRemnants,
                suppressedExpectedReportRows,
                props.watchSeconds(), props.warnSeconds(), props.criticalSeconds());
    }

    public List<SafeKillResult> killSafeExpectedReportQueries(Report report) {
        if (!props.safeKillEnabled()) return List.of();
        return report.rows().stream()
                .filter(row -> row.timeSeconds() >= props.safeKillExpectedReportSeconds())
                .filter(this::isSafeKillableExpectedReportRow)
                .map(this::killQuery)
                .toList();
    }

    public String renderSafeKillResults(List<SafeKillResult> results) {
        if (results == null || results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("safe auto-kill results:\n");
        for (SafeKillResult result : results) {
            sb.append(String.format(
                    "- id=%d time=%ds killed=%s category=%s%s%n  sql=%s%n",
                    result.id(),
                    result.timeSeconds(),
                    result.killed(),
                    result.category(),
                    result.errorMessage() == null ? "" : " error=" + abbreviate(result.errorMessage(), 120),
                    abbreviate(result.info(), 220)));
        }
        return sb.toString();
    }

    public String render(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DB Slow Query Monitor ===\n");
        sb.append("time: ").append(report.generatedAt()).append(" UTC\n");
        sb.append("status: ").append(report.status()).append('\n');
        sb.append("thresholds: watch>=").append(report.watchSeconds()).append("s")
                .append(" warn>=").append(report.warnSeconds()).append("s")
                .append(" critical>=").append(report.criticalSeconds()).append("s\n");
        sb.append("active rows: ").append(report.rows().size()).append('\n');
        sb.append("killed HeatWave remnants ignored: ")
                .append(report.killedHeatWaveRemnants()).append('\n');
        sb.append("expected report rows suppressed from TG: ")
                .append(report.suppressedExpectedReportRows()).append('\n');

        if (report.rows().isEmpty()) {
            sb.append("\nOK: no active app slow query or HeatWave query above threshold.\n");
            return sb.toString();
        }

        sb.append("\nRows:\n");
        for (Row row : report.rows()) {
            sb.append(String.format(
                    "- id=%d db=%s command=%s time=%ds state=%s category=%s%n  sql=%s%n",
                    row.id(),
                    nullToDash(row.db()),
                    nullToDash(row.command()),
                    row.timeSeconds(),
                    nullToDash(row.state()),
                    row.category() + (row.expectedReportQuery() ? "/EXPECTED_REPORT" : ""),
                    abbreviate(row.info(), 260)));
        }
        sb.append("\nRecommendation: ");
        sb.append(switch (report.status()) {
            case OK -> "no action.";
            case WATCH -> "watch only; expected report queries page only after critical threshold.";
            case WARN -> "ops audit; check whether the same query repeats.";
            case CRITICAL -> "incident; inspect DB pressure and HeatWave guard immediately.";
        });
        sb.append('\n');
        return sb.toString();
    }

    private Status classify(List<Row> rows) {
        if (rows.isEmpty()) return Status.OK;
        List<Row> pagingRows = rows.stream()
                .filter(row -> !isSuppressedExpectedReportRow(row))
                .toList();
        if (pagingRows.isEmpty()) return Status.WATCH;
        boolean hasHeatWave = pagingRows.stream().anyMatch(r -> r.category() == Category.HEATWAVE);
        boolean hasCritical = pagingRows.stream().anyMatch(r -> r.timeSeconds() >= props.criticalSeconds());
        boolean hasWarn = pagingRows.stream().anyMatch(r -> r.timeSeconds() >= props.warnSeconds());
        if (hasCritical || hasHeatWave || pagingRows.size() >= 3) return Status.CRITICAL;
        if (hasWarn) return Status.WARN;
        return Status.WATCH;
    }

    private boolean isSuppressedExpectedReportRow(Row row) {
        return row.expectedReportQuery() && row.timeSeconds() < props.criticalSeconds();
    }

    private boolean isSafeKillableExpectedReportRow(Row row) {
        if (!row.expectedReportQuery()) return false;
        if (!"Query".equalsIgnoreCase(row.command())) return false;
        String lower = row.info() == null ? "" : row.info().stripLeading().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select ")) return false;
        return lower.contains(" from market_indicator_history")
                || lower.contains(" from md_kline")
                || lower.contains(" from bt_runtime_decision_evidence")
                || lower.contains(" from bt_decision_audit");
    }

    private SafeKillResult killQuery(Row row) {
        try {
            jdbc.execute("KILL QUERY " + row.id());
            return SafeKillResult.success(row);
        } catch (Exception e) {
            return SafeKillResult.failure(row, e.getMessage());
        }
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) return "-";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max - 3) + "...";
    }

    public enum Status {
        OK,
        WATCH,
        WARN,
        CRITICAL
    }

    public enum Category {
        APP_SLOW,
        INDICATOR,
        KLINE,
        HEATWAVE,
        METADATA_LOCK
    }

    public record Report(
            Instant generatedAt,
            Status status,
            List<Row> rows,
            int killedHeatWaveRemnants,
            int suppressedExpectedReportRows,
            int watchSeconds,
            int warnSeconds,
            int criticalSeconds
    ) {}

    public record SafeKillResult(
            long id,
            int timeSeconds,
            Category category,
            boolean killed,
            String errorMessage,
            String info
    ) {
        static SafeKillResult success(Row row) {
            return new SafeKillResult(row.id(), row.timeSeconds(), row.category(), true, null, row.info());
        }

        static SafeKillResult failure(Row row, String errorMessage) {
            return new SafeKillResult(row.id(), row.timeSeconds(), row.category(), false, errorMessage, row.info());
        }
    }

    public record Row(
            long id,
            String user,
            String db,
            String command,
            int timeSeconds,
            String state,
            String info,
            Category category,
            boolean expectedReportQuery
    ) {
        static Row from(Map<String, Object> row) {
            String info = Objects.toString(row.get("info"), "");
            String state = Objects.toString(row.get("STATE"), null);
            Category category = categorize(info, state);
            return new Row(
                    ((Number) row.get("ID")).longValue(),
                    Objects.toString(row.get("USER"), null),
                    Objects.toString(row.get("DB"), null),
                    Objects.toString(row.get("COMMAND"), null),
                    ((Number) row.get("TIME")).intValue(),
                    state,
                    info,
                    category,
                    isExpectedReportQuery(info, category));
        }

        private static Category categorize(String info, String state) {
            String lower = info == null ? "" : info.toLowerCase();
            if (lower.contains("secondary_load")
                    || lower.contains("secondary_unload")
                    || lower.contains("rapid_ml_operation")) {
                return Category.HEATWAVE;
            }
            String stateLower = state == null ? "" : state.toLowerCase();
            if (lower.contains("market_indicator_history")) {
                return Category.INDICATOR;
            }
            if (lower.contains("md_kline")) {
                return Category.KLINE;
            }
            if (stateLower.contains("metadata lock")) {
                return Category.METADATA_LOCK;
            }
            return Category.APP_SLOW;
        }

        private static boolean isExpectedReportQuery(String info, Category category) {
            if (category == Category.HEATWAVE || info == null || info.isBlank()) {
                return false;
            }
            String lower = info.toLowerCase();
            if (category == Category.INDICATOR) {
                return lower.contains("from market_indicator_history")
                        && (lower.contains("group by indicator")
                        || lower.contains("count(*) as cnt")
                        || lower.contains("timestampdiff(hour")
                        || lower.contains("timestampdiff(minute"));
            }
            if (category == Category.KLINE) {
                return lower.contains("from md_kline")
                        && lower.contains("select close_price")
                        && lower.contains("order by open_time desc limit 1");
            }
            if (lower.contains("from bt_runtime_decision_evidence")) {
                return lower.contains("count(*)")
                        || lower.contains("group by name")
                        || lower.contains("'runtime_evidence' row_source");
            }
            if (lower.contains("from bt_decision_audit")) {
                return lower.contains("'decision_audit' row_source")
                        || (lower.contains("select blocker")
                        && lower.contains("count(*)")
                        && lower.contains("event_type = 'entry_skip'")
                        && lower.contains("strategy_id")
                        && lower.contains("group by blocker"))
                        || lower.contains("event_type in ('signal_eval','signal_buy','signal_sell','filter_block','autotrade_ok','autotrade_fail','entry_skip')");
            }
            return false;
        }
    }
}
