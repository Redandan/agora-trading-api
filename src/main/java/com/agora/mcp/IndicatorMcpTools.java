package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.trading.WashoutAccumulationIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Indicator data management tools — extracted from MarketDataMcpTools (#248).
 * Covers: data quality flagging, coverage/gaps/freshness analysis, backfill status, anomaly detection.
 * Dependencies: indicatorHistoryRepository + jdbc only.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IndicatorMcpTools {

    private final MarketIndicatorHistoryRepository indicatorHistoryRepository;
    private final JdbcTemplate jdbc;
    private final WashoutAccumulationIndexService waiService;

    // ─── Data quality ─────────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "#234 標記 market_indicator_history 資料錯誤：將指定 indicator/capturedAt 設為 error_flag=1，" +
            "下次 ML retrain 時該筆會被排除在 vw_signal_training_v8_dedup 之外。" +
            "適用於 API 回傳明顯錯誤值（如 stablecoin_total_mcap_b -8.49σ = 128B vs 正常 316B）。" +
            "params: indicator(必填), capturedAt=ISO時間(必填,格式2026-04-28T03:00), " +
            "symbol=BTCUSDT(預設), reason=錯誤原因描述")
    public String flagDataError(String indicator, String capturedAt, String symbol, String reason) {
        String _e = com.agora.mcp.util.McpParamValidator.requireNonNull(indicator, "indicator");
        if (_e != null) return _e;
        _e = com.agora.mcp.util.McpParamValidator.requireNonNull(capturedAt, "capturedAt");
        if (_e != null) return _e;
        String sym = symbol != null ? symbol.toUpperCase() : "BTCUSDT";
        String rsn = reason != null ? reason : "手動標記資料錯誤";
        try {
            java.time.LocalDateTime ts = java.time.LocalDateTime.parse(capturedAt.replace(" ", "T"));
            int updated = indicatorHistoryRepository.flagAsError(sym, indicator, ts, rsn);
            if (updated == 0) {
                return String.format("⚠️ 未找到對應記錄：symbol=%s indicator=%s capturedAt=%s", sym, indicator, capturedAt);
            }
            return String.format("✅ 已標記 %d 筆為 error_flag=1\n  symbol=%s indicator=%s capturedAt=%s\n  reason=%s\n" +
                    "下次 ML retrain 將自動排除此筆。", updated, sym, indicator, capturedAt, rsn);
        } catch (Exception e) {
            return "❌ 標記失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "#234 自動偵測 market_indicator_history 中的資料錯誤：找出孤立尖峰（|z|≥zThreshold 且前後值正常）。" +
            "區分「真實市場極端值（持續多點）」vs「API 回傳錯誤（孤立單點）」。" +
            "params: days=回溯天數(預設7), zThreshold=z分數門檻(預設5.0), symbol=BTCUSDT(預設)")
    public String autoDetectDataErrors(Integer days, Double zThreshold, String symbol) {
        int d = days != null ? Math.min(Math.max(days, 1), 30) : 7;
        double zThr = zThreshold != null ? zThreshold : 5.0;
        String sym = symbol != null ? symbol.toUpperCase() : "BTCUSDT";
        java.time.LocalDateTime since = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(d);
        try {
            // V2 — pre-aggregate per-indicator stats once via CTE, then JOIN. O(N) instead
            // of O(N²) from the original two-nested-subquery approach (which timed out at 90s).
            List<java.util.Map<String, Object>> candidates = jdbc.queryForList(
                    "WITH stats AS (" +
                    "  SELECT indicator, AVG(CAST(value AS DOUBLE)) AS m, STDDEV(CAST(value AS DOUBLE)) AS s " +
                    "  FROM market_indicator_history " +
                    "  WHERE symbol=? AND captured_at >= ? AND error_flag=0 " +
                    "  GROUP BY indicator HAVING s > 0" +
                    ") " +
                    "SELECT mih.indicator, mih.captured_at, mih.value, " +
                    "       stats.m AS window_mean, stats.s AS window_std " +
                    "FROM market_indicator_history mih " +
                    "JOIN stats ON mih.indicator = stats.indicator " +
                    "WHERE mih.symbol=? AND mih.captured_at >= ? AND mih.error_flag=0 " +
                    "  AND ABS((CAST(mih.value AS DOUBLE) - stats.m) / stats.s) >= ? " +
                    "ORDER BY ABS((CAST(mih.value AS DOUBLE) - stats.m) / stats.s) DESC LIMIT 20",
                    sym, since, sym, since, zThr);
            if (candidates.isEmpty()) return String.format("✅ 無 |z|≥%.1f 的極端值（%dd, %s）", zThr, d, sym);
            StringBuilder sb = new StringBuilder(String.format("=== 潛在資料錯誤 (|z|≥%.1f, %dd, %s) ===\n\n", zThr, d, sym));
            for (var row : candidates) {
                double val = row.get("value") != null ? ((Number) row.get("value")).doubleValue() : 0;
                double mean = row.get("window_mean") != null ? ((Number) row.get("window_mean")).doubleValue() : 0;
                double std = row.get("window_std") != null ? ((Number) row.get("window_std")).doubleValue() : 1;
                double z = std > 0 ? (val - mean) / std : 0;
                sb.append(String.format("⚠️ %s @ %s\n   value=%.4f  z=%+.2f  mean=%.4f\n", row.get("indicator"), row.get("captured_at"), val, z, mean));
                sb.append(String.format("   → flagDataError(indicator=\"%s\", capturedAt=\"%s\") 標記排除\n\n", row.get("indicator"), row.get("captured_at")));
            }
            sb.append("💡 孤立單點（前後值正常）為高可信度資料錯誤，持續多點可能是真實市場事件。");
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    // ─── Indicator history & coverage ─────────────────────────────────────────

    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "查詢 market_indicator_history 近 N 小時的時間序列:顯示每筆 captured_at + value + z-score。" +
            "用來快速審視指標趨勢、判斷最新值是否異常。" +
            "(fear_greed / whale_buy_ratio / funding_rate / long_short_ratio / orderbook_imbalance)," +
            "hours=回溯小時數(預設 72,最多 2160=90天)。")
    public String getIndicatorHistory(String symbol, String indicator, Integer hours) {
        { String _e = com.agora.mcp.util.McpParamValidator.requireNonBlank(symbol, "symbol"); if (_e != null) return _e; }
        { String _e = com.agora.mcp.util.McpParamValidator.requireNonBlank(indicator, "indicator"); if (_e != null) return _e; }
        int h = (hours == null || hours <= 0) ? 72 : Math.min(hours, 2160);
        String sym = symbol.toUpperCase().trim();
        String ind = indicator.toLowerCase().trim();
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(h);
        List<MarketIndicatorHistory> rows = indicatorHistoryRepository
                .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(sym, ind, since);
        if (rows.isEmpty()) return String.format("無 %s / %s 過去 %d 小時的歷史資料。\n(V040 上線起才累積,若剛上線請稍候;或確認 indicator 拼字)", sym, ind, h);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        for (MarketIndicatorHistory r : rows) { double v = r.getValue().doubleValue(); if (v < min) min = v; if (v > max) max = v; sum += v; }
        double mean = sum / rows.size();
        double varSum = 0;
        for (MarketIndicatorHistory r : rows) { double dv = r.getValue().doubleValue() - mean; varSum += dv * dv; }
        double stdev = rows.size() > 1 ? Math.sqrt(varSum / (rows.size() - 1)) : 0;
        BigDecimal latest = rows.get(rows.size() - 1).getValue();
        double zLatest = stdev > 0 ? (latest.doubleValue() - mean) / stdev : 0;
        StringBuilder sb = new StringBuilder(String.format("=== %s / %s 近 %d 小時歷史 ===%n", sym, ind, h));
        sb.append(String.format("樣本數: %d  |  latest: %.6f  (z=%+.2fσ)%n", rows.size(), latest.doubleValue(), zLatest));
        sb.append(String.format("min=%.6f  max=%.6f  mean=%.6f  stdev=%.6f%n%n", min, max, mean, stdev));
        int showStart = Math.max(0, rows.size() - 30);
        sb.append("── 最近 ").append(rows.size() - showStart).append(" 筆 ──\n");
        sb.append("時間(UTC)             value         z\n").append("-".repeat(50)).append("\n");
        for (int i = showStart; i < rows.size(); i++) {
            MarketIndicatorHistory r = rows.get(i);
            double v = r.getValue().doubleValue();
            double z = stdev > 0 ? (v - mean) / stdev : 0;
            sb.append(String.format("%s %-19s %11.6f %+5.2fσ%n", Math.abs(z) >= 2 ? "⚠️" : "  ", r.getCapturedAt().toString(), v, z));
        }
        if (showStart > 0) sb.append(String.format("(省略較舊 %d 筆)%n", showStart));
        sb.append("\n💡 z=(current-mean)/stdev,|z|≥2 標示為異常(⚠️)。");
        return sb.toString();
    }

    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "查詢 market_indicator_history 中 ETF/複合指標的歷史時間序列，顯示 captured_at + value + z-score。" +
            "適用指標：etf_pressure_index / short_build_index / sqi / sqi_short_crowding / " +
            "sqi_liquidation_anomaly / sqi_price_confirmation / short_squeeze_readiness / " +
            "market_phase / squeeze_fuel_score / squeeze_trigger_score。" +
            "也可查詢任何 market_indicator_history 中已收集的指標名稱（不限 ETF）。" +
            "params: indicatorName=指標名稱(必填), hours=回溯小時數(預設 168=7天, 最多 2160=90天)")
    public String getMihIndicatorHistory(String indicatorName, Integer hours) {
        String _e = com.agora.mcp.util.McpParamValidator.requireNonBlank(indicatorName, "indicatorName");
        if (_e != null) return _e;
        return getIndicatorHistory("BTCUSDT", indicatorName.toLowerCase().trim(), hours);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "#231 特定 indicator 的時間缺口分析：找出連續缺失超過 N 小時的時段。" +
            "比 getIndicatorCoverage 更細粒度，適合分析 dex_wbtc_net_flow_usd_1h 等 backfill 後仍有缺口的 indicator。" +
            "params: indicator(必填), symbol=BTCUSDT(預設), days=回溯天數(預設 30), gapThresholdHours=缺口門檻小時數(預設 3)")
    public String getIndicatorGaps(String indicator, String symbol, Integer days, Integer gapThresholdHours) {
        if (indicator == null || indicator.isBlank()) return "❌ indicator 必填";
        int d = days != null ? Math.min(Math.max(days, 1), 90) : 30;
        int gapThr = gapThresholdHours != null ? Math.max(gapThresholdHours, 1) : 3;
        String sym = symbol != null ? symbol.toUpperCase() : "BTCUSDT";
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        List<MarketIndicatorHistory> rows = indicatorHistoryRepository
                .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(sym, indicator, since);
        if (rows.isEmpty()) return String.format("ℹ️ %s/%s 在過去 %dd 無資料", sym, indicator, d);
        StringBuilder sb = new StringBuilder(String.format("=== Indicator Gaps: %s/%s (past %dd, gap≥%dh) ===\n\n", sym, indicator, d, gapThr));
        List<String> gaps = new ArrayList<>();
        LocalDateTime prev = rows.get(0).getCapturedAt();
        for (int i = 1; i < rows.size(); i++) {
            LocalDateTime curr = rows.get(i).getCapturedAt();
            // #353 fix: 用 minutes/60.0 避免整數截斷（1h59m → 1h underestimate），
            // 且 > 嚴格大於（避免 hourly indicator 正常 1h 鄰居被誤報為 gap）
            double hoursDiff = java.time.Duration.between(prev, curr).toMinutes() / 60.0;
            if (hoursDiff > gapThr) gaps.add(String.format("  %s → %s (%.1fh gap)", prev, curr, hoursDiff));
            prev = curr;
        }
        sb.append(String.format("總行數: %d  缺口數: %d\n\n", rows.size(), gaps.size()));
        if (gaps.isEmpty()) { sb.append(String.format("✅ 無 ≥%dh 的連續缺口\n", gapThr)); }
        else { gaps.forEach(g -> sb.append(g).append("\n")); }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "#224 Indicator 覆蓋率報告：對每個 market_indicator_history indicator 顯示" +
            "近 N 天的 row count、最後更新時間、預期頻率、缺口數量。" +
            "快速確認哪些 indicator 嚴重落後或有缺口（不用 SSH 進 DB）。" +
            "param: symbol=BTCUSDT(預設), days=回溯天數(預設 7)")
    public String getIndicatorCoverage(String symbol, Integer days) {
        // V2 (#325 follow-up) — 改用 density-based freq detection + SLA freshness check，
        // 與 getBackfillStatus / getCollectionFreshness 一致。原硬編碼 expect=d*24 無法
        // 處理 backfill 起始日晚於 since 的 indicator（會誤報 ❌ 即使資料完整）。
        int d = days != null ? Math.min(Math.max(days, 1), 90) : 7;
        String sym = symbol != null ? symbol.toUpperCase() : "BTCUSDT";
        try {
            // #323 V099 完成後 captured_at 已是 UTC，與 NOW() 同 timezone，無需 CONVERT_TZ。
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ indicator, COUNT(*) as cnt, " +
                    "       MAX(captured_at) as last_at, MIN(captured_at) as first_at, " +
                    "       SUM(error_flag) as error_cnt, " +
                    "       TIMESTAMPDIFF(HOUR, MIN(captured_at), MAX(captured_at)) as span_hours, " +
                    "       TIMESTAMPDIFF(MINUTE, MAX(captured_at), NOW()) as minutes_ago " +
                    "FROM market_indicator_history FORCE INDEX (idx_mih_sym_ind_captured) " +
                    "WHERE symbol=? AND captured_at >= NOW() - INTERVAL ? DAY " +
                    "GROUP BY indicator ORDER BY cnt DESC", sym, d);
            if (rows.isEmpty()) return "⚠️ 無資料：" + sym;
            StringBuilder sb = new StringBuilder(String.format("=== Indicator Coverage (%s, 過去 %dd) ===\n\n", sym, d));
            sb.append(String.format("%-35s| %5s | %-16s | %5s | %-5s | density | %s%n",
                    "indicator", "rows", "last_captured", "errs", "freq", "status"))
                .append("-".repeat(96)).append("\n");
            int unhealthy = 0;
            for (var row : rows) {
                long cnt = ((Number) row.get("cnt")).longValue();
                long errs = row.get("error_cnt") != null ? ((Number) row.get("error_cnt")).longValue() : 0;
                Object lastAt = row.get("last_at");
                String lastStr = lastAt != null ? lastAt.toString().substring(0, Math.min(16, lastAt.toString().length())) : "N/A";
                long spanHr = row.get("span_hours") == null ? 0 : ((Number) row.get("span_hours")).longValue();
                long minutesAgo = row.get("minutes_ago") == null ? 0 : ((Number) row.get("minutes_ago")).longValue();
                // density = rows / span_hours within window; ~60/minute, ~1 hourly, ~0.04 daily
                double density = spanHr > 0 ? (double) cnt / spanHr : (cnt >= 1 ? 1.0 : 0);
                String freq = inferCadenceByDensity(density);
                // SLA: minute < 15m, hourly < 2h, daily < 25h；超過 3× SLA → ❌ stale
                long warnMin = warnMinutesForCadence(freq);
                String indicator = (String) row.get("indicator");
                boolean isConditional = CONDITIONAL_INDICATORS.contains(indicator);
                String status = isConditional ? "ℹ️ conditional"
                        : minutesAgo > warnMin * 3 ? "❌ stale"
                        : minutesAgo > warnMin ? "⚠️ late" : "✅ fresh";
                if (!isConditional && !status.startsWith("✅")) unhealthy++;
                sb.append(String.format("%-35s| %5d | %-16s | %5d | %-5s | %5.2f/h | %s%n",
                        indicator, cnt, lastStr, errs, isConditional ? "cond." : freq, density, status));
            }
            sb.append(String.format("%n%d unhealthy / %d total | freq 由 density 自動推斷 | ✅<SLA ⚠️<3×SLA ❌≥3×SLA", unhealthy, rows.size()));
            sb.append("\n💡 errs=error_flag=1 已標記的資料錯誤數；ℹ️ conditional 不代表資料異常");
            return sb.toString();
        } catch (Exception e) { return "❌ 查詢失敗: " + e.getMessage(); }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "#236 即時收集新鮮度檢查：哪些 indicator 現在正在落後（最後更新時間 > 預期間隔）。" +
            "hourly indicator 超過 2h 未更新、daily indicator 超過 25h 未更新 → 標記警告。" +
            "param: symbol=BTCUSDT(預設)")
    public String getCollectionFreshness(String symbol) {
        String sym = symbol != null ? symbol.toUpperCase() : "BTCUSDT";
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            // #323 V099 完成後 captured_at 已是 UTC；移除 CONVERT_TZ workaround 與 +8h offset。
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ indicator, MAX(captured_at) as last_at, " +
                    "       TIMESTAMPDIFF(MINUTE, MAX(captured_at), NOW()) as minutes_ago, " +
                    "       COUNT(*) as total_7d " +
                    "FROM market_indicator_history FORCE INDEX (idx_mih_sym_ind_captured) " +
                    "WHERE symbol=? AND captured_at >= NOW() - INTERVAL 7 DAY " +
                    "GROUP BY indicator ORDER BY minutes_ago DESC", sym);
            if (rows.isEmpty()) return "⚠️ 無資料：" + sym;
            StringBuilder sb = new StringBuilder(String.format("=== Collection Freshness (%s @ %s UTC) ===\n\n", sym, now.toString().substring(0, 16)));
            int stale = 0;
            for (var row : rows) {
                Object lastAt = row.get("last_at");
                if (lastAt == null) continue;
                long minutesAgo = ((Number) row.get("minutes_ago")).longValue();
                long total7d = ((Number) row.get("total_7d")).longValue();
                String cadence = inferCadenceBySevenDayRows(total7d);
                long warnAfterMin = warnMinutesForCadence(cadence);
                String indicator = (String) row.get("indicator");
                // #381 — conditional indicators: only written when their producer's
                // trigger condition fires (e.g. ShortSqueezeAlertScheduler path A/B).
                // Prolonged staleness is expected during non-squeezing markets, so
                // they emit "ℹ️" (info) rather than "⚠️" / "❌" stale warnings.
                boolean isConditional = CONDITIONAL_INDICATORS.contains(indicator);
                String status;
                if (isConditional) {
                    status = "ℹ️"; // conditional — not a true freshness signal
                } else if (minutesAgo > warnAfterMin) {
                    status = minutesAgo > warnAfterMin * 3 ? "❌" : "⚠️";
                    stale++;
                } else {
                    status = "✅";
                }
                String age = minutesAgo < 60 ? minutesAgo + "m" : (minutesAgo / 60) + "h" + (minutesAgo % 60) + "m";
                String type = isConditional ? "cond." : cadence;
                sb.append(String.format("%-35s | %-7s ago | %-6s | %s%n", indicator, age, type, status));
            }
            sb.append(String.format("\n%d stale indicator(s) — ℹ️ conditional indicators excluded", stale));
            return sb.toString();
        } catch (Exception e) { return "❌ 查詢失敗: " + e.getMessage(); }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Run WAI (Washout Accumulation Index) indicator calculation now for Phase 1 BTCUSDT 1h. " +
            "Writes only wai_* market_indicator_history rows; does not place orders, modify OCO, change strategy/grid/fund/Earn state, " +
            "or enable live trading. params: symbol default BTCUSDT, intervalCode default 1h.")
    public String calculateWaiNow(String symbol, String intervalCode) {
        try {
            WashoutAccumulationIndexService.CalculationResult result =
                    waiService.calculateAndPersistLatest(symbol, intervalCode);
            if (result.skipped()) {
                return """
                        === WAI Calculation ===
                        boundary=MARKET_DATA_WRITE_ONLY; no trading/OCO/strategy/grid/fund/Earn behavior changed.
                        status=SKIPPED
                        symbol=%s
                        intervalCode=%s
                        reason=%s
                        orderSent=false
                        """.formatted(result.symbol(), result.intervalCode(), result.skipReason());
            }
            var s = result.snapshot();
            return """
                    === WAI Calculation ===
                    boundary=MARKET_DATA_WRITE_ONLY; writes wai_* indicator rows only; no trading/OCO/strategy/grid/fund/Earn behavior changed.
                    status=OK
                    symbol=%s
                    intervalCode=%s
                    capturedAt=%s
                    rowsWritten=%d
                    wai_score=%d
                    wai_stage=%d
                    wai_volume_dryup_score=%d
                    wai_price_stability_score=%d
                    wai_stop_hunt_score=%d
                    wai_probe_pump_score=%d
                    wai_structure_confirm_score=%d
                    wai_invalidated=%s
                    wai_breakout_ready=%s
                    proxyTurnoverFallback=quote_volume_missing_in_md_kline
                    orderSent=false
                    """.formatted(result.symbol(), result.intervalCode(), result.capturedAt(), result.rowsWritten(),
                    s.waiScore(), s.waiStage(), s.waiVolumeDryupScore(), s.waiPriceStabilityScore(),
                    s.waiStopHuntScore(), s.waiProbePumpScore(), s.waiStructureConfirmScore(),
                    s.waiInvalidated(), s.waiBreakoutReady());
        } catch (Exception e) {
            log.warn("[calculateWaiNow] failed", e);
            return "WAI calculation failed: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Read-only WAI (Washout Accumulation Index) accuracy scan. " +
            "Recomputes WAI on historical BTCUSDT 1h OKX klines and reports threshold sweep, stage breakdown, future returns/drawdowns, and false positives. " +
            "Does not write indicators, place orders, modify OCO, change strategy/grid/fund/Earn state, or enable live trading. " +
            "params: symbol default BTCUSDT, days supports 90/180, intervalCode default 1h.")
    public String scanWaiAccuracy(String symbol, Integer days, String intervalCode) {
        try {
            return waiService.scanWaiAccuracy(symbol, days, intervalCode);
        } catch (Exception e) {
            log.warn("[scanWaiAccuracy] failed", e);
            return "scanWaiAccuracy failed: " + e.getMessage();
        }
    }

    /**
     * #381 — indicators that are written conditionally rather than on a fixed
     * cadence. Excluded from stale-warning logic; flagged "ℹ️" in display.
     *
     * <p>Adding a new indicator here means: "this indicator may legitimately
     * go {@code N} hours without an update during normal operation".
     *
     * <ul>
     *   <li>{@code squeeze_*}, {@code short_squeeze_readiness} —
     *       {@code ShortSqueezeAlertScheduler} writes them only when path A/B
     *       trigger conditions fire. Long staleness = no squeeze regime, normal.
     *   <li>{@code btc_open_interest_usd_m} — backfill-only via MCP
     *       {@code backfillOpenInterest} for ad-hoc analysis. No scheduler.
     *       Use {@code btc_open_interest} (BTC contract count, hourly) for
     *       real-time OI tracking.
     * </ul>
     */
    private static final java.util.Set<String> CONDITIONAL_INDICATORS = java.util.Set.of(
            "squeeze_fuel_score",
            "squeeze_trigger_score",
            "squeeze_readiness_b",
            "short_squeeze_readiness",
            "btc_open_interest_usd_m"
    );

    private static String inferCadenceByDensity(double rowsPerHour) {
        if (rowsPerHour >= 30.0) return "1m";
        if (rowsPerHour >= 0.5) return "1h";
        if (rowsPerHour >= 0.02) return "daily";
        return "?";
    }

    private static String inferCadenceBySevenDayRows(long rows) {
        if (rows >= 1_000) return "1m";
        if (rows > 14) return "1h";
        return "daily";
    }

    private static long warnMinutesForCadence(String cadence) {
        return switch (cadence) {
            case "1m" -> 15;
            case "1h" -> 120;
            default -> 1500;
        };
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "#229 各 backfill job 進度彙總：顯示關鍵 hourly indicators 的實際 row count vs 預期。" +
            "部署後快速確認 DexFlow / FRED / Hyperliquid / OpenInterest 等 backfill 完成度。")
    public String getBackfillStatus() {
        // V2 (#325) — 改用 SLA 檢查（每筆 indicator 動態 expect = MIN(captured_at) ~ NOW 的真實 hours）
        // 而非 hardcode 90/365 days。避免「資料完整但報 0%」的誤報。
        String[] watchedIndicators = {
            "dex_wbtc_net_flow_usd_1h", "fear_greed", "us_10y_yield", "us_vix", "btc_dvol",
            "hyperliquid_btc_funding_hr_pct", "funding_rate", "oi_change_pct_1h", "whale_buy_ratio",
            "us_sp500", "us_dxy", "btc_open_interest"
        };
        StringBuilder sb = new StringBuilder("=== Backfill Status (SLA-based v2) ===\n\n");
        sb.append(String.format("%-32s| %5s | %-13s | %5s | %-12s | density | status%n",
                "indicator", "rows", "earliest", "errs", "last", "")).append("-".repeat(100)).append("\n");
        try {
            for (String name : watchedIndicators) {
                List<java.util.Map<String, Object>> r = jdbc.queryForList(
                        "SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(10000) */ " +
                        "       COUNT(*) as cnt, SUM(error_flag) as errs, " +
                        "       MIN(captured_at) as first_at, MAX(captured_at) as last_at, " +
                        "       TIMESTAMPDIFF(HOUR, MIN(captured_at), MAX(captured_at)) as span_hours, " +
                        "       TIMESTAMPDIFF(MINUTE, MAX(captured_at), UTC_TIMESTAMP()) as minutes_since_last " +
                        "FROM market_indicator_history FORCE INDEX (idx_mih_sym_ind_captured) " +
                        "WHERE symbol='BTCUSDT' AND indicator=?", name);
                if (r.isEmpty() || r.get(0).get("cnt") == null) {
                    sb.append(String.format("%-32s| %5s | %-13s |       |              |         | ❌ no-data%n", name, "0", "-"));
                    continue;
                }
                long cnt = ((Number) r.get(0).get("cnt")).longValue();
                long errs = r.get(0).get("errs") == null ? 0 : ((Number) r.get(0).get("errs")).longValue();
                Object firstAt = r.get(0).get("first_at");
                Object lastAt = r.get(0).get("last_at");
                long spanHr = r.get(0).get("span_hours") == null ? 0 : ((Number) r.get(0).get("span_hours")).longValue();
                if (cnt == 0) {
                    sb.append(String.format("%-32s| %5d | %-13s |       |              |         | ❌ no-data%n", name, cnt, "-"));
                    continue;
                }
                // density = rows / span_hours; ~1.0 = hourly, ~0.04 = daily
                double density = spanHr > 0 ? (double) cnt / spanHr : 0;
                String freq = density >= 0.5 ? "1h" : density >= 0.02 ? "daily" : "?";
                // SLA: hourly should have last < 2h ago; daily < 25h ago
                long minutesSinceLast = r.get(0).get("minutes_since_last") == null
                        ? 0 : ((Number) r.get(0).get("minutes_since_last")).longValue();
                long warnMin = "1h".equals(freq) ? 120 : 1500;
                String status = minutesSinceLast > warnMin * 3 ? "❌ stale" : minutesSinceLast > warnMin ? "⚠️ late" : "✅ fresh";
                String firstStr = firstAt != null ? firstAt.toString().substring(0, Math.min(13, firstAt.toString().length())) : "-";
                String lastShort = lastAt != null ? lastAt.toString().substring(11, Math.min(16, lastAt.toString().length())) : "-";
                sb.append(String.format("%-32s| %5d | %-13s | %5d | %-12s | %5.2f/h | %s%n",
                        name, cnt, firstStr, errs, lastShort, density, status));
            }
            sb.append("\n💡 status: ✅ fresh (last < SLA) / ⚠️ late / ❌ stale (> 3× SLA)");
            sb.append("\n💡 density ~1.0 = hourly | ~0.04 = daily | 偏離可能 collector 中斷或頻率改變");
        } catch (Exception e) { sb.append("❌ 查詢失敗: ").append(e.getMessage()); }
        return sb.toString();
    }

    @Tool(description = "掃 market_indicator_history 近 N 天內單小時變化 > 2σ 的異常點," +
            "判斷當前 MarketFlip events 裡有幾個是真異常 vs 6-period aggregation noise。" +
            "每個 (symbol, indicator) 組合獨立計算 σ,列出該組合內的異常點(最多 10 筆)。" +
            "param: days=回溯天數(預設 7,最多 90); suppressBatchUpdate=true(預設)抑制 FRED 等批次更新假警報。" +
            "至少需 V040 上線 > 1 天資料才有意義。")
    public String getIndicatorAnomalies(Integer days) {
        return getIndicatorAnomaliesImpl(days, true);
    }

    private String getIndicatorAnomaliesImpl(Integer days, boolean suppressBatchUpdate) {
        int d = (days == null || days <= 0) ? 7 : Math.min(days, 90);
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        java.util.Set<String> fredIndicators = java.util.Set.of(
                "us_10y_yield", "us_vix", "us_sp500", "us_nasdaq", "us_dxy", "us_breakeven_10y",
                "us_fed_funds_rate", "btc_dvol", "usdt_supply_b", "usdc_supply_b", "stablecoin_supply_b",
                "stablecoin_total_mcap_b", "usdt_supply_polygon_b", "usdt_supply_arbitrum_b");
        List<MarketIndicatorHistory> all = indicatorHistoryRepository.findByCapturedAtAfterOrderByCapturedAtAsc(since);
        java.util.Set<java.time.LocalDateTime> batchHours = new java.util.HashSet<>();
        if (suppressBatchUpdate) {
            java.util.Map<java.time.LocalDateTime, Long> fredUpdatesByHour = all.stream()
                    .filter(r -> fredIndicators.contains(r.getIndicator()))
                    .collect(java.util.stream.Collectors.groupingBy(
                            r -> r.getCapturedAt().truncatedTo(java.time.temporal.ChronoUnit.HOURS),
                            java.util.stream.Collectors.counting()));
            fredUpdatesByHour.forEach((hour, count) -> { if (count >= 3) batchHours.add(hour); });
        }
        if (all.isEmpty()) return String.format("無近 %d 天歷史資料(V040 可能剛上線)。", d);
        java.util.Map<String, List<MarketIndicatorHistory>> bySymInd = new java.util.LinkedHashMap<>();
        for (MarketIndicatorHistory r : all) bySymInd.computeIfAbsent(r.getSymbol() + "/" + r.getIndicator(), k -> new ArrayList<>()).add(r);
        StringBuilder sb = new StringBuilder(String.format("=== Indicator Anomalies 近 %d 天(|z| ≥ 2σ)===%n%n總樣本: %d 筆,共 %d 個 (symbol, indicator) 組合%n%n", d, all.size(), bySymInd.size()));
        int totalAnomalies = 0;
        for (var entry : bySymInd.entrySet()) {
            List<MarketIndicatorHistory> series = entry.getValue();
            if (series.size() < 3) continue;
            double sum = 0; for (MarketIndicatorHistory r : series) sum += r.getValue().doubleValue();
            double mean = sum / series.size();
            double varSum = 0; for (MarketIndicatorHistory r : series) { double dv = r.getValue().doubleValue() - mean; varSum += dv * dv; }
            double stdev = Math.sqrt(varSum / (series.size() - 1));
            if (stdev <= 0) continue;
            List<MarketIndicatorHistory> anomalies = new ArrayList<>();
            for (MarketIndicatorHistory r : series) {
                double z = (r.getValue().doubleValue() - mean) / stdev;
                if (Math.abs(z) >= 2.0) {
                    if (suppressBatchUpdate && batchHours.contains(r.getCapturedAt().truncatedTo(java.time.temporal.ChronoUnit.HOURS))) continue;
                    anomalies.add(r);
                }
            }
            if (anomalies.isEmpty()) continue;
            totalAnomalies += anomalies.size();
            sb.append(String.format("── %s(n=%d,mean=%.4f,σ=%.4f)── %d 個異常點%n", entry.getKey(), series.size(), mean, stdev, anomalies.size()));
            int show = Math.min(anomalies.size(), 10);
            for (int i = 0; i < show; i++) {
                MarketIndicatorHistory r = anomalies.get(i);
                double v = r.getValue().doubleValue(); double z = (v - mean) / stdev;
                sb.append(String.format("    %s  value=%.6f  z=%+.2fσ%n", r.getCapturedAt().toString(), v, z));
            }
            if (anomalies.size() > 10) sb.append(String.format("    ...(省略 %d 筆)%n", anomalies.size() - 10));
            sb.append("\n");
        }
        if (totalAnomalies == 0) { sb.append("✅ 近 ").append(d).append(" 天內無 |z| ≥ 2σ 的異常點(市場相對穩定,或樣本太少)。"); }
        else { sb.append(String.format("共 %d 個異常點。|z| ≥ 2 約佔正態分布 ~5%%,若某組合異常比例顯著高於 5%%,可能是指標本身有肥尾或近期市場真的極端。", totalAnomalies)); }
        return sb.toString();
    }
}
