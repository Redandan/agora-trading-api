package com.agora.service.meta;

import com.agora.model.BtBacktestResult;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtBacktestResultRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 彙整「交易策略 vs ML 模型 vs 被動基準」的統一 scorecard。
 *
 * <p>回答「我們目前到底賺不賺錢、哪套值得部署」:
 * <ol>
 *   <li>交易策略:bt_strategy × 最新 bt_backtest_result,附 enable 狀態與累計 live trades</li>
 *   <li>ML 模型:ml_model_registry 近版,holdout 指標從 metrics_json 解,顯示 bootstrap CI</li>
 *   <li>被動基準:HODL BTC YTD(md_kline 對比)+ 常數化的 Nexo / OKX Earn / Funding Arb 估值</li>
 * </ol>
 *
 * <p>輸出為單一 plain-text 報告 (TG friendly,不走 HTML),供 MCP tool 與 WeeklyScorecardDigest 共用。
 *
 * <p>容錯:任何單一 row 解析失敗都 log + 以「-」佔位,避免整份報告因一筆壞資料卡住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScorecardReportService {

    private static final String BTC_SYMBOL = "BTCUSDT";
    private static final String BENCHMARK_INTERVAL = "1h";
    private static final String BENCHMARK_SOURCE = "okx";

    /** 最多展示幾列 ML 版本(避免 TG 4000 字元爆限)。 */
    private static final int ML_MAX_ROWS = 8;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final BtStrategyRepository strategyRepo;
    private final BtBacktestResultRepository backtestResultRepo;
    private final BtLiveSignalRepository liveSignalRepo;
    private final MdKlineRepository klineRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // ─── Public API ───────────────────────────────────────────────────────

    /** 一站式報告(MCP + scheduler 共用)。 */
    public String formatAsText() {
        return formatAsText(false);
    }

    public String formatAsText(boolean enabledOnly) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Strategy Scorecard (").append(today.format(DATE_FMT)).append(")\n\n");

        sb.append("=== Trading Strategies").append(enabledOnly ? " (啟用中)" : "").append(" ===\n");
        appendStrategyTable(sb, enabledOnly);
        sb.append('\n');

        sb.append("=== ML Models ===\n");
        appendMlTable(sb);
        sb.append('\n');

        sb.append("=== Benchmarks (annualized) ===\n");
        appendBenchmarks(sb, today);
        sb.append('\n');

        sb.append("=== Current edge ===\n");
        appendCurrentEdge(sb);

        return sb.toString();
    }

    // ─── Strategy section ─────────────────────────────────────────────────

    private void appendStrategyTable(StringBuilder sb) {
        appendStrategyTable(sb, false);
    }

    private void appendStrategyTable(StringBuilder sb, boolean enabledOnly) {
        List<BtStrategy> all;
        try {
            all = strategyRepo.findAll();
        } catch (DataAccessException e) {
            log.warn("[Scorecard] strategy fetch failed: {}", e.getMessage());
            sb.append("(strategy query failed: ").append(e.getMessage()).append(")\n");
            return;
        }
        if (enabledOnly) {
            // #397 — Stream.toList() returns an immutable list; the subsequent
            // List.sort() below would throw UnsupportedOperationException.
            // Wrap in a mutable ArrayList so the sort below works in both branches.
            all = all.stream()
                    .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        all.sort(Comparator
                .comparing((BtStrategy s) -> Boolean.TRUE.equals(s.getEnabled()) ? 0 : 1)
                .thenComparing(BtStrategy::getId));

        sb.append(String.format("%-4s| %-26s| %-14s| %-7s| %-11s| %-20s| %-7s| %-6s| Verdict%n",
                "ID", "Name", "Type", "Enabled", "Live f/t", "Backtest", "WF", "Robust"));
        sb.append("    (f=fired signals  t=auto-traded orders; f>t means filter/F&G blocked)\n");

        for (BtStrategy s : all) {
            try {
                appendStrategyRow(sb, s);
            } catch (Exception rowErr) {
                log.warn("[Scorecard] strategy row {} failed: {}", s.getId(), rowErr.getMessage());
                sb.append(String.format("%-4d| (row failed: %s)%n",
                        s.getId(), trunc(rowErr.getMessage(), 40)));
            }
        }
    }

    private void appendStrategyRow(StringBuilder sb, BtStrategy s) {
        boolean enabled = Boolean.TRUE.equals(s.getEnabled());
        // fired = 策略觸發的訊號數(含被 filter 擋下的);traded = 真的下單的
        // 兩者分開顯示:操作員能看出「策略都沒觸發」vs「策略觸發但全被擋」
        long fired = liveSignalRepo.countByStrategyId(s.getId());
        long traded = liveSignalRepo.countByStrategyIdAndAutoTradedIsTrue(s.getId());
        String liveCell = String.format("%d/%d", fired, traded);

        BtBacktestResult latest = backtestResultRepo
                .findTopByStrategy_IdOrderByCreatedAtDesc(s.getId())
                .orElse(null);

        String backtest;
        String verdict;
        if (latest == null) {
            backtest = "-";
            verdict = enabled ? "⚠️ enabled w/o backtest" : "📚 training data only";
        } else {
            int tc = latest.getTradeCount() != null ? latest.getTradeCount() : 0;
            double ret = latest.getTotalReturn() != null ? latest.getTotalReturn().doubleValue() : 0.0;
            double dd = latest.getMaxDrawdown() != null ? latest.getMaxDrawdown().doubleValue() : 0.0;
            if (tc == 0) {
                backtest = "no_data";
            } else if (tc < 5) {
                backtest = String.format("insufficient (n=%d)", tc);
            } else if (ret < 0 || dd > 0.20) {
                backtest = String.format("fail (n=%d, DD=%.0f%%)", tc, dd * 100);
            } else {
                backtest = String.format("%+.2f%%", ret * 100);
            }
            if (enabled) {
                verdict = "✅ LIVE";
            } else if (ret > 0 && tc >= 5) {
                verdict = "💡 candidate";
            } else {
                verdict = "📚 training data only";
            }
        }

        // WF / Robustness verdict: extracted from strategy.notes if present
        // (enableStrategy gate stamps e.g. "[WF:STABLE robustness:SMOOTH]" or similar).
        String wf = extractTaggedVerdict(s.getNotes(), "WF");
        String robust = extractTaggedVerdict(s.getNotes(), "robustness");

        String name = s.getName() != null ? s.getName() : "-";
        String type = s.getStrategyType() != null ? s.getStrategyType() : "-";

        sb.append(String.format("%-4d| %-26s| %-14s|   %s   | %-11s| %-20s| %-7s| %-6s| %s%n",
                s.getId(),
                trunc(name, 26),
                trunc(type, 14),
                enabled ? "✅" : "❌",
                liveCell,
                trunc(backtest, 20),
                trunc(wf, 7),
                trunc(robust, 6),
                verdict));
    }

    /**
     * 從 notes 抽 "WF:XXX" / "robustness:XXX" 這類標記(若 enable gate 寫入過);
     * 沒寫則回 "-"。pattern 容忍 "[WF:STABLE]"、"WF=STABLE"、"WF: STABLE"。
     */
    private String extractTaggedVerdict(String notes, String tag) {
        if (notes == null) return "-";
        String lower = notes.toLowerCase();
        int idx = lower.indexOf(tag.toLowerCase());
        if (idx < 0) return "-";
        int start = idx + tag.length();
        while (start < notes.length()
                && (notes.charAt(start) == ':' || notes.charAt(start) == '=' || notes.charAt(start) == ' ')) {
            start++;
        }
        int end = start;
        while (end < notes.length()) {
            char c = notes.charAt(end);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') end++;
            else break;
        }
        if (end == start) return "-";
        return notes.substring(start, end);
    }

    // ─── ML section ───────────────────────────────────────────────────────

    private void appendMlTable(StringBuilder sb) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT id, model_name, version, status, metrics_json " +
                            "FROM ml_model_registry " +
                            "ORDER BY trained_at DESC LIMIT ?",
                    ML_MAX_ROWS);
        } catch (DataAccessException e) {
            log.warn("[Scorecard] ml_model_registry fetch failed: {}", e.getMessage());
            sb.append("(ml_model_registry query failed: ")
                    .append(trunc(e.getMessage(), 120)).append(")\n");
            return;
        }
        if (rows.isEmpty()) {
            sb.append("(no ML models registered yet — run trainSignalScorer)\n");
            return;
        }
        sb.append(String.format("%-7s| %-15s| %-9s| %-11s| %-22s| Verdict%n",
                "Version", "Model", "Status", "Holdout acc", "Bootstrap CI"));
        for (Map<String, Object> r : rows) {
            try {
                String modelName = String.valueOf(r.get("model_name"));
                String version = "v" + r.get("version");
                String status = String.valueOf(r.get("status"));
                MlMetricSummary m = parseMetrics(r.get("metrics_json"));
                String acc = m.accuracyPct != null
                        ? String.format("%.1f%%", m.accuracyPct)
                        : "-";
                String ci;
                String verdict;
                if (m.ciLoPp != null && m.ciHiPp != null) {
                    ci = String.format("[%+.1f, %+.1f]pp", m.ciLoPp, m.ciHiPp);
                    if (m.ciLoPp > 5) verdict = "🟢 strong edge";
                    else if (m.ciLoPp > 0) verdict = "🟡 weak edge";
                    else if (m.ciHiPp > 0) verdict = "⚠️ weak edge";
                    else verdict = "❌ no edge";
                } else if (m.accuracyPct != null && m.baselinePct != null) {
                    double edge = m.accuracyPct - m.baselinePct;
                    ci = "-";
                    if (edge >= 5) verdict = "🟢 strong edge";
                    else if (edge >= 2) verdict = "🟡 thin edge";
                    else verdict = "❌ no edge";
                } else {
                    ci = "-";
                    // HeatWave sys.ML_TRAIN logs training quality, not holdout scores.
                    // Run evalOnHoldout / bootstrapTopNLift MCP tools for true edge.
                    verdict = m.quality != null
                            ? "ℹ️ training quality=" + m.quality + " (run evalOnHoldout for edge)"
                            : "ℹ️ no holdout metrics (run evalOnHoldout)";
                }
                sb.append(String.format("%-7s| %-15s| %-9s| %-11s| %-22s| %s%n",
                        trunc(version, 7),
                        trunc(modelName, 15),
                        trunc(status, 9),
                        acc,
                        trunc(ci, 22),
                        verdict));
            } catch (Exception rowErr) {
                log.warn("[Scorecard] ml row {} failed: {}", r.get("id"), rowErr.getMessage());
                sb.append(String.format("v%s  (row failed: %s)%n",
                        r.get("version"), trunc(rowErr.getMessage(), 40)));
            }
        }
    }

    /** 寬鬆解 metrics_json — 不同模型 schema 不一致,抓得到就抓。 */
    private MlMetricSummary parseMetrics(Object rawJson) {
        MlMetricSummary out = new MlMetricSummary();
        if (rawJson == null) return out;
        try {
            JsonNode root = objectMapper.readTree(rawJson.toString());
            // Holdout-style keys (only present if eval tool wrote them back into metrics_json).
            out.accuracyPct = extractPct(root, "holdout_accuracy_pct", "accuracy_pct", "accuracy");
            out.baselinePct = extractPct(root, "baseline_pct", "baseline_accuracy_pct");
            out.ciLoPp = extractDouble(root, "bootstrap_ci95_lo_pp", "ci95_lo_pp");
            out.ciHiPp = extractDouble(root, "bootstrap_ci95_hi_pp", "ci95_hi_pp");
            if (out.ciLoPp == null || out.ciHiPp == null) {
                JsonNode boot = root.path("bootstrap_top_n_lift");
                if (!boot.isMissingNode()) {
                    if (out.ciLoPp == null) out.ciLoPp = extractDouble(boot, "ci95_lo_pp");
                    if (out.ciHiPp == null) out.ciHiPp = extractDouble(boot, "ci95_hi_pp");
                }
            }
            // HeatWave training metrics — always present, qualitative (low/medium/high).
            JsonNode q = root.get("model_quality");
            if (q != null && q.isTextual()) out.quality = q.asText();
        } catch (Exception e) {
            log.debug("[Scorecard] metrics_json parse: {}", e.getMessage());
        }
        return out;
    }

    private Double extractPct(JsonNode root, String... keys) {
        Double raw = extractDouble(root, keys);
        if (raw == null) return null;
        // Heuristic: if value looks like fraction (0..1), scale to %.
        return raw <= 1.0 && raw >= 0.0 ? raw * 100.0 : raw;
    }

    private Double extractDouble(JsonNode root, String... keys) {
        for (String k : keys) {
            JsonNode n = root.get(k);
            if (n != null && !n.isNull() && n.isNumber()) return n.asDouble();
        }
        return null;
    }

    private static class MlMetricSummary {
        Double accuracyPct;
        Double baselinePct;
        Double ciLoPp;
        Double ciHiPp;
        String quality;
    }

    // ─── Benchmarks ──────────────────────────────────────────────────────

    private void appendBenchmarks(StringBuilder sb, LocalDate today) {
        Double hodlYtdPct = computeHodlBtcYtdPct(today);
        sb.append(String.format("HODL BTC (YTD)      : %s%n",
                hodlYtdPct != null ? String.format("%+.1f%%", hodlYtdPct) : "-"));
        sb.append("Nexo USDT (passive) : +8.0%\n");
        sb.append("OKX Earn BTC        : +4.5%\n");
        sb.append("Funding Arb (dry)   : +2.3% (est. 7d funding accrual)\n");
    }

    /** 取 Jan 1 vs today 的 BTC OKX 1h close — 失敗回 null 讓 caller 印 "-"。 */
    private Double computeHodlBtcYtdPct(LocalDate today) {
        try {
            LocalDate jan1 = LocalDate.of(today.getYear(), 1, 1);
            BigDecimal startClose = firstCloseOn(jan1);
            BigDecimal endClose = firstCloseOn(today);
            if (startClose == null || endClose == null
                    || startClose.signum() == 0) {
                return null;
            }
            return endClose.subtract(startClose)
                    .divide(startClose, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        } catch (Exception e) {
            log.debug("[Scorecard] HODL BTC YTD compute failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 取指定日期 OKX 1h K 線的第一根 close。若當日無資料,往前推最多 7 天找最近可用值,
     * 確保即使 Jan 1 server down 也能回傳近 Jan 2 / Jan 3 的價格。
     */
    private BigDecimal firstCloseOn(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<MdKline> rows = klineRepo
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        BTC_SYMBOL, BENCHMARK_INTERVAL, BENCHMARK_SOURCE, start, end);
        if (!rows.isEmpty()) return rows.get(0).getClosePrice();
        // Fallback — most recent bar at or before `date` (within 7d).
        List<MdKline> fallback = klineRepo
                .findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                        BTC_SYMBOL, BENCHMARK_INTERVAL, BENCHMARK_SOURCE,
                        PageRequest.of(0, 1));
        if (fallback.isEmpty()) return null;
        MdKline bar = fallback.get(0);
        if (bar.getOpenTime().isBefore(start.minusDays(7))) return null;
        return bar.getClosePrice();
    }

    // ─── Current edge ────────────────────────────────────────────────────

    private void appendCurrentEdge(StringBuilder sb) {
        List<BtStrategy> enabled;
        try {
            enabled = strategyRepo.findByEnabled(true);
        } catch (Exception e) {
            sb.append("(cannot resolve enabled strategies: ").append(e.getMessage()).append(")\n");
            return;
        }
        if (enabled.isEmpty()) {
            sb.append("No strategy currently enabled → edge = 0 vs benchmark.\n");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (BtStrategy s : enabled) {
            long fired = liveSignalRepo.countByStrategyId(s.getId());
            long traded = liveSignalRepo.countByStrategyIdAndAutoTradedIsTrue(s.getId());
            BtBacktestResult bt = backtestResultRepo
                    .findTopByStrategy_IdOrderByCreatedAtDesc(s.getId())
                    .orElse(null);
            if (bt == null || bt.getTotalReturn() == null) {
                lines.add(String.format("Strategy %d (%s): enabled, fired=%d traded=%d, no backtest → edge inconclusive",
                        s.getId(), trunc(s.getName(), 22), fired, traded));
                continue;
            }
            double ret = bt.getTotalReturn().doubleValue() * 100;
            // Nexo 比較用 traded(實際下單數)而非 fired,只有真的下單才會產生 P&L
            String comparison;
            if (traded < 10) {
                comparison = String.format(
                        "→ vs Nexo 8%%: inconclusive (traded n=%d < 10%s)",
                        traded,
                        fired > traded ? String.format(", fired=%d but %d blocked", fired, fired - traded) : "");
            } else if (ret > 8) {
                comparison = "→ vs Nexo 8%: ahead (backtest proxy)";
            } else if (ret > 0) {
                comparison = "→ vs Nexo 8%: behind (backtest proxy)";
            } else {
                comparison = "→ negative backtest return — DO NOT TRUST live edge";
            }
            lines.add(String.format("Strategy %d (%s): backtest %+.2f%%, fired=%d traded=%d %s",
                    s.getId(), trunc(s.getName(), 22), ret, fired, traded, comparison));
        }
        for (String line : lines) {
            sb.append(line).append('\n');
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private String trunc(String s, int max) {
        if (s == null) return "-";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(max - 1, 1)) + "…";
    }
}
