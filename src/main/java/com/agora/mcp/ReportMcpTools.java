package com.agora.mcp;

import com.agora.dto.ai.GroqUsageStatsDTO;
import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.AiTokenUsageDaily;
import com.agora.service.ai.AiTokenUsageService;
import com.agora.service.ai.GeminiApiClient;
import com.agora.service.ai.GroqApiClient;
import com.agora.scheduler.trading.DailyReportScheduler;
import com.agora.service.backtest.TradingAnalysisService;
import com.agora.service.ml.MlPipelineDigestService;
import com.agora.service.trading.TradingManagerService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * MCP 交易報告工具集。
 * 提供即時倉位報告、7 日績效週報、AI 市場機會分析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportMcpTools {

    private final TradingManagerService tradingManagerService;
    private final TradingAnalysisService tradingAnalysisService;
    private final GroqApiClient groqApiClient;
    private final GeminiApiClient geminiApiClient;
    private final AiTokenUsageService aiTokenUsageService;
    private final DailyReportScheduler dailyReportScheduler;
    private final MlPipelineDigestService mlPipelineDigestService;
    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.REPORTING})
    @Tool(description = "#222 月度 PnL 概覽：整合 SPOT 已平倉 + Grid 已實現 + OKX SWAP，按月彙總損益。" +
            "快速回答「這個月我們賺/虧了多少」。" +
            "param: months=回溯月數（預設 6，最多 24）")
    public String getMonthlyPnlOverview(Integer months) {
        int m = months != null ? Math.min(Math.max(months, 1), 24) : 6;
        StringBuilder sb = new StringBuilder();
        sb.append("=== 月度 PnL 概覽（最近 ").append(m).append(" 個月）===\n\n");
        sb.append(String.format("%-8s| %-12s| %-12s| %-12s| %-12s| %s%n",
                "月份", "SPOT(USDT)", "Grid(USDT)", "SWAP(USDT)", "合計(USDT)", "筆數"));
        sb.append("-".repeat(74)).append("\n");

        try {
            // Use Java-side cutoff so JDBC auto-converts to DB timezone (avoids MySQL NOW()
            // returning UTC while exit_time/closed_at are stored as Taipei wall-clock).
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMonths(m);

            // SPOT: bt_live_signal LONG trades, grouped by month of exit_time
            List<java.util.Map<String, Object>> spotRows = jdbc.queryForList(
                    "SELECT DATE_FORMAT(exit_time, '%Y-%m') as ym, " +
                    "SUM(COALESCE(realized_pnl, 0)) as pnl, COUNT(*) as cnt " +
                    "FROM bt_live_signal WHERE auto_traded=1 AND side='LONG' " +
                    "AND exit_time IS NOT NULL AND realized_pnl IS NOT NULL " +
                    "AND exit_time >= ? " +
                    "GROUP BY ym ORDER BY ym DESC", cutoff);

            // Grid: #422 — bt_grid_level.realized_pnl is rarely populated (data layer only fills
            // 1 in 43 levels live). The grid-level aggregate lives in bt_grid.total_realized_pnl.
            // Query that instead, grouped by closed_at month. Open grids (closed_at NULL) are
            // excluded — their PnL appears in the month they close.
            List<java.util.Map<String, Object>> gridRows = jdbc.queryForList(
                    "SELECT DATE_FORMAT(closed_at, '%Y-%m') as ym, " +
                    "SUM(COALESCE(total_realized_pnl, 0)) as pnl " +
                    "FROM bt_grid WHERE total_realized_pnl IS NOT NULL " +
                    "AND total_realized_pnl != 0 " +
                    "AND closed_at IS NOT NULL " +
                    "AND closed_at >= ? " +
                    "GROUP BY ym ORDER BY ym DESC", cutoff);

            // SWAP: bt_live_signal SHORT trades, grouped by exit_time
            List<java.util.Map<String, Object>> swapRows = jdbc.queryForList(
                    "SELECT DATE_FORMAT(exit_time, '%Y-%m') as ym, " +
                    "SUM(COALESCE(realized_pnl, 0)) as pnl " +
                    "FROM bt_live_signal WHERE auto_traded=1 AND side='SHORT' " +
                    "AND exit_time IS NOT NULL AND realized_pnl IS NOT NULL " +
                    "AND exit_time >= ? " +
                    "GROUP BY ym ORDER BY ym DESC", cutoff);

            // Build month-keyed maps
            java.util.Map<String, Double> spotMap = new java.util.LinkedHashMap<>();
            java.util.Map<String, Long> cntMap = new java.util.LinkedHashMap<>();
            for (var r : spotRows) {
                String ym = (String) r.get("ym");
                spotMap.put(ym, r.get("pnl") != null ? ((Number) r.get("pnl")).doubleValue() : 0.0);
                cntMap.put(ym, r.get("cnt") != null ? ((Number) r.get("cnt")).longValue() : 0L);
            }
            java.util.Map<String, Double> gridMap = new java.util.LinkedHashMap<>();
            for (var r : gridRows) {
                String ym = (String) r.get("ym");
                gridMap.put(ym, r.get("pnl") != null ? ((Number) r.get("pnl")).doubleValue() : 0.0);
            }
            java.util.Map<String, Double> swapMap = new java.util.LinkedHashMap<>();
            for (var r : swapRows) {
                String ym = (String) r.get("ym");
                swapMap.put(ym, r.get("pnl") != null ? ((Number) r.get("pnl")).doubleValue() : 0.0);
            }

            // Generate rows for last N months
            java.time.YearMonth now = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
            double totalAll = 0.0;
            for (int i = 0; i < m; i++) {
                String ym = now.minusMonths(i).toString();
                double spot = spotMap.getOrDefault(ym, 0.0);
                double grid = gridMap.getOrDefault(ym, 0.0);
                double swap = swapMap.getOrDefault(ym, 0.0);
                double total = spot + grid + swap;
                totalAll += total;
                long cnt = cntMap.getOrDefault(ym, 0L);
                sb.append(String.format("%-8s| %+11.2f | %+11.2f | %+11.2f | %+11.2f | %d%n",
                        ym, spot, grid, swap, total, cnt));
            }
            sb.append("-".repeat(74)).append("\n");
            sb.append(String.format("%-8s| %60s%n", "合計", String.format("%+.2f USDT", totalAll)));
            sb.append("\n💡 SWAP=bt_live_signal side=SHORT 已平倉(策略觸發)；Grid=bt_grid.total_realized_pnl 已關閉的網格(#422 修)。\n")
              .append("    手動 OKX SWAP 交易不在此 view — 用 getSwapPnlHistory 取得 OKX 端完整紀錄。");
        } catch (Exception e) {
            sb.append("❌ 查詢失敗: ").append(e.getMessage());
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.REPORTING, Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "#506 read-only weekly performance attribution. Compares natural week and rolling 7d realized PnL, "
            + "then explains active/unrealized Grid/OCO and EntryDedup context. No AI and no trading writes. "
            + "params: symbol(default BTCUSDT), includeDiagnostics(default true)")
    public String getWeeklyPerformanceAttribution(String symbol, Boolean includeDiagnostics) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        boolean diagnostics = includeDiagnostics == null || includeDiagnostics;

        ZonedDateTime nowTpe = ZonedDateTime.now(TAIPEI);
        ZonedDateTime thisWeekStart = nowTpe.toLocalDate()
                .atStartOfDay(TAIPEI)
                .minusDays(nowTpe.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
        WindowSpec lastWeek = new WindowSpec("last_week", thisWeekStart.minusDays(7).toLocalDateTime(),
                thisWeekStart.toLocalDateTime());
        WindowSpec thisWeek = new WindowSpec("this_week", thisWeekStart.toLocalDateTime(),
                nowTpe.toLocalDateTime());
        WindowSpec previous7d = new WindowSpec("previous_7d", nowTpe.minusDays(14).toLocalDateTime(),
                nowTpe.minusDays(7).toLocalDateTime());
        WindowSpec current7d = new WindowSpec("current_7d", nowTpe.minusDays(7).toLocalDateTime(),
                nowTpe.toLocalDateTime());

        WindowAttribution naturalPrev = loadWindowAttribution(sym, lastWeek);
        WindowAttribution naturalCurrent = loadWindowAttribution(sym, thisWeek);
        WindowAttribution rollingPrev = loadWindowAttribution(sym, previous7d);
        WindowAttribution rollingCurrent = loadWindowAttribution(sym, current7d);
        BigDecimal lastPrice = latestPrice(sym);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Weekly Performance Attribution ===\n");
        sb.append("symbol: ").append(sym).append(" | timezone: Asia/Taipei | mode=READ_ONLY\n");
        sb.append("boundary: realized PnL only; open position/Grid estimates are shown separately and are not counted.\n\n");

        BigDecimal naturalDelta = naturalCurrent.total().subtract(naturalPrev.total());
        BigDecimal rollingDelta = rollingCurrent.total().subtract(rollingPrev.total());
        sb.append("Summary:\n");
        sb.append(String.format("- Natural week delta: %s USDT (this %s vs last %s)%n",
                money(naturalDelta), money(naturalCurrent.total()), money(naturalPrev.total())));
        sb.append(String.format("- Rolling 7d delta: %s USDT (current %s vs previous %s)%n",
                money(rollingDelta), money(rollingCurrent.total()), money(rollingPrev.total())));
        sb.append("- Primary cause: ").append(primaryCause(naturalPrev, naturalCurrent)).append("\n");
        sb.append("- Safety: no trading behavior changed.\n\n");

        appendComparison(sb, "Natural Week", naturalPrev, naturalCurrent);
        appendComparison(sb, "Rolling 7d", rollingPrev, rollingCurrent);

        sb.append("Active / Not Realized\n");
        appendOpenPositions(sb, sym, lastPrice);
        appendActiveGrids(sb, sym, lastPrice);

        if (diagnostics) {
            appendSignalGuardContext(sb, sym, thisWeek);
        }

        sb.append("\nOperator Read\n");
        sb.append("- This report separates booked realized PnL from open exposure.\n");
        if (naturalCurrent.total().compareTo(BigDecimal.ZERO) == 0 && naturalPrev.total().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("- This week is worse because no trade/grid has closed yet; prior week had realized gains.\n");
        }
        sb.append("- Use this as diagnosis only; do not disable EntryDedup or change OCO/Grid from this report alone.\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.REPORTING, Category.ANALYTICS})
    @Tool(description = "統一報表入口 — 依 (scope, focus) dispatch 到對應既有 service，取代舊 4 個 get*Report 工具。" +
            "scope: 'now'(即時倉位) | 'day'(過去 24h 摘要) | 'week'(近 7 日週報)。" +
            "focus: 'trading'(交易報表，預設) | 'ml'(ML pipeline digest，僅 scope=day 有效) | 'all'(交易 + ML，scope=day 才會合併兩段)。" +
            "format: 'tg'(預設，TG markdown 字串) | 'json'(JSON envelope，含 scope/focus/generated_at/content，給 AI agent 結構化 parse)。" +
            "底層直接呼叫既有 reportCurrentSituation / reportWeekly / DailyReportScheduler.buildSummary / MlPipelineDigestService.buildDigest，不改業務邏輯。")
    public String getReport(String scope, String focus, String format) {
        String sc = (scope == null || scope.isBlank()) ? "now" : scope.trim().toLowerCase();
        String fc = (focus == null || focus.isBlank()) ? "trading" : focus.trim().toLowerCase();
        String fmt = (format == null || format.isBlank()) ? "tg" : format.trim().toLowerCase();

        if (!sc.equals("now") && !sc.equals("day") && !sc.equals("week")) {
            return "❌ scope 需為 now | day | week";
        }
        if (!fc.equals("trading") && !fc.equals("ml") && !fc.equals("all")) {
            return "❌ focus 需為 trading | ml | all";
        }
        if (!fmt.equals("tg") && !fmt.equals("json")) {
            return "❌ format 需為 tg | json";
        }
        // ml focus 只對 day scope 有意義（digest 是日級）
        if (fc.equals("ml") && !sc.equals("day")) {
            return "❌ focus=ml 目前僅支援 scope=day（digest 為日級資料）";
        }

        try {
            String trading = null;
            String ml = null;

            if (fc.equals("trading") || fc.equals("all")) {
                switch (sc) {
                    case "now":
                        trading = tradingManagerService.reportCurrentSituation();
                        break;
                    case "day": {
                        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
                        LocalDateTime start = end.minusHours(24);
                        trading = dailyReportScheduler.buildSummary(start, end);
                        break;
                    }
                    case "week":
                        trading = tradingManagerService.reportWeekly(false);
                        break;
                }
            }

            if ((fc.equals("ml") || fc.equals("all")) && sc.equals("day")) {
                ml = mlPipelineDigestService.buildDigest(false);
            }

            if (fmt.equals("json")) {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("scope", sc);
                envelope.put("focus", fc);
                envelope.put("generated_at", LocalDateTime.now(ZoneOffset.UTC).toString() + "Z");
                if (trading != null && ml != null) {
                    Map<String, String> parts = new LinkedHashMap<>();
                    parts.put("trading", trading);
                    parts.put("ml", ml);
                    envelope.put("parts", parts);
                } else {
                    envelope.put("content", trading != null ? trading : ml);
                }
                return objectMapper.writeValueAsString(envelope);
            }

            // tg format
            if (trading != null && ml != null) {
                return trading + "\n\n— — — — —\n\n" + ml;
            }
            return trading != null ? trading : ml;
        } catch (Exception e) {
            log.error("[ReportMcpTools] getReport scope={} focus={} format={} failed", sc, fc, fmt, e);
            return "❌ 無法生成報表（scope=" + sc + ", focus=" + fc + "）：" + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.MEMBER)
    @McpCategory({Category.REPORTING, Category.ANALYTICS})
    @Tool(description = "DEPRECATED, use getReport(scope='now', focus='trading'). " +
            "生成當前倉位狀況 AI 分析報告。" +
            "即時查詢 OKX 現價、OCO 狀態、浮動損益，並由 AI 生成中文點評。" +
            "包含：USDT 餘額、各幣種持倉、帳面盈虧、止損止盈距離、AI 建議。")
    public String getCurrentReport() {

        try {
            return tradingManagerService.reportCurrentSituation();
        } catch (Exception e) {
            log.error("[ReportMcpTools] getCurrentReport failed", e);
            return "❌ 無法生成倉位報告，請稍後重試";
        }
    }

    @McpAuth(McpAuthLevel.MEMBER)
    @McpCategory({Category.REPORTING})
    @Tool(description = "DEPRECATED, use getReport(scope='week', focus='trading'). " +
            "生成近 7 日交易績效週報。" +
            "包含：總交易筆數、勝率、總損益、最佳/最差交易、平均持倉時間，由 AI 評估交易表現。")
    public String getWeeklyReport() {

        try {
            return tradingManagerService.reportWeekly(false);
        } catch (Exception e) {
            log.error("[ReportMcpTools] getWeeklyReport failed", e);
            return "❌ 無法生成週報，請稍後重試";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.REPORTING})
    @Tool(description = "DEPRECATED, use getReport(scope='day', focus='trading'). " +
            "即時生成過去 24 小時交易摘要（每日 00:00 UTC 自動發 TG 的同一內容）。" +
            "包含：多/空信號數、已執行筆數、當日平倉勝率與 PnL、未平倉筆數、啟用策略數。")
    public String getDailyReport() {
        try {
            LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime start = end.minusHours(24);
            return dailyReportScheduler.buildSummary(start, end);
        } catch (Exception e) {
            log.error("[ReportMcpTools] getDailyReport failed", e);
            return "❌ 無法生成每日摘要：" + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.MEMBER)
    @McpCategory({Category.REPORTING, Category.MARKET_DATA})
    @Tool(description = "AI 市場機會分析。彙整所有監控幣種的最新技術信號（NN 分數、RSI、EMA 趨勢）、" +
            "Fear & Greed 指數、鯨魚流向，由 Groq AI 生成繁體中文交易機會建議（約 150 字）。")
    public String analyzeMarket() {

        try {
            return tradingAnalysisService.analyze();
        } catch (Exception e) {
            log.error("[ReportMcpTools] analyzeMarket failed", e);
            return "❌ 無法生成市場分析，請稍後重試";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META, Category.DIAGNOSTIC})
    @Tool(description = "查詢所有 AI 服務今日用量（Groq、Gemini、Jina）及 Groq Rate Limit 狀態。" +
            "顯示今日 DB 累計用量（重啟不歸零）＋ Groq 當前分鐘剩餘配額。")
    public String checkAiQuota() {
        GroqUsageStatsDTO s = groqApiClient.getUsageStats();

        StringBuilder sb = new StringBuilder();
        sb.append("🤖 AI 資源狀態\n\n");

        // 今日所有服務 DB 累計
        java.util.List<AiTokenUsageDaily> todayAll = aiTokenUsageService.getAllToday();
        sb.append("📊 今日累計（DB，重啟不歸零）\n");
        if (todayAll.isEmpty()) {
            sb.append("  今日尚無記錄\n");
        } else {
            long grandTotal = 0;
            for (AiTokenUsageDaily r : todayAll) {
                long total = r.getPromptTok() + r.getCompleteTok();
                grandTotal += total;
                sb.append(String.format("  [%s]\n", r.getModel()));
                sb.append(String.format("    請求：%d 次 | 錯誤：%d 次\n", r.getReqCount(), r.getErrorCount()));
                if (r.getCompleteTok() > 0) {
                    sb.append(String.format("    Prompt：%,d | Completion：%,d | 合計：%,d\n",
                            r.getPromptTok(), r.getCompleteTok(), total));
                } else {
                    sb.append(String.format("    Tokens：%,d\n", r.getPromptTok()));
                }
            }
            sb.append(String.format("  ── 全服務合計：%,d tokens ──\n", grandTotal));
        }
        sb.append("\n");

        // Rate Limit（來自各服務最後一次回應 headers）
        sb.append("📡 當前分鐘剩餘\n");

        // Groq
        if (!s.isEnabled()) {
            sb.append("  Groq：未啟用\n");
        } else if (s.getRateLimitRequestsPerMin() != null) {
            int remReq = s.getRemainingRequestsPerMin() != null ? s.getRemainingRequestsPerMin() : -1;
            int limReq = s.getRateLimitRequestsPerMin();
            int remTok = s.getRemainingTokensPerMin() != null ? s.getRemainingTokensPerMin() : -1;
            int limTok = s.getRateLimitTokensPerMin() != null ? s.getRateLimitTokensPerMin() : 0;
            sb.append(String.format("  Groq 請求：%d/%d %s | Token：%,d/%,d %s",
                    remReq, limReq, remReq > limReq * 0.2 ? "✅" : "⚠️",
                    remTok, limTok, remTok > limTok * 0.2 ? "✅" : "⚠️"));
            if (s.getResetRequestsIn() != null)
                sb.append(String.format(" | 重置 %s", s.getResetRequestsIn()));
            sb.append("\n");
        } else {
            sb.append("  Groq：尚無資料（本次啟動後尚未呼叫）\n");
        }

        // Gemini
        if (!geminiApiClient.isEnabled()) {
            sb.append("  Gemini：未啟用\n");
        } else if (geminiApiClient.getLimitRequestsPerMin() != null) {
            int remReq = geminiApiClient.getRemainingRequestsPerMin() != null ? geminiApiClient.getRemainingRequestsPerMin() : -1;
            int limReq = geminiApiClient.getLimitRequestsPerMin();
            int remTok = geminiApiClient.getRemainingTokensPerMin() != null ? geminiApiClient.getRemainingTokensPerMin() : -1;
            int limTok = geminiApiClient.getLimitTokensPerMin() != null ? geminiApiClient.getLimitTokensPerMin() : 0;
            sb.append(String.format("  Gemini 請求：%d/%d %s | Token：%,d/%,d %s\n",
                    remReq, limReq, remReq > limReq * 0.2 ? "✅" : "⚠️",
                    remTok, limTok, remTok > limTok * 0.2 ? "✅" : "⚠️"));
        } else {
            sb.append("  Gemini：尚無資料（本次啟動後尚未呼叫）\n");
        }

        // Jina 月度配額（免費 1M tokens/月）
        long jinaMonthly = aiTokenUsageService.getMonthlyTokens("jina-embeddings-v3");
        long jinaRemaining = 1_000_000L - jinaMonthly;
        sb.append(String.format("  Jina 月度：已用 %,d / 1,000,000 | 剩餘 %,d %s\n",
                jinaMonthly, jinaRemaining, jinaRemaining > 100_000 ? "✅" : "⚠️"));

        // Groq 本次 session
        sb.append(String.format("\n⚙️ Groq 本次 session\n  請求：%d 次 | Tokens：%,d\n",
                s.getTotalRequests(), s.getTotalTokensUsed()));
        if (s.getLastCallAt() != null)
            sb.append("  最後呼叫：").append(s.getLastCallAt());

        return sb.toString();
    }

    private WindowAttribution loadWindowAttribution(String symbol, WindowSpec window) {
        return new WindowAttribution(
                window,
                loadLiveSignalPnl("SPOT", symbol, "LONG", window),
                loadLiveSignalPnl("SWAP", symbol, "SHORT", window),
                loadGridPnl(symbol, window));
    }

    private ComponentPnl loadLiveSignalPnl(String component, String symbol, String side, WindowSpec window) {
        String sideFilter = "LONG".equals(side) ? "COALESCE(side, 'LONG') = 'LONG'" : "side = 'SHORT'";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT COUNT(*) cnt, COALESCE(SUM(realized_pnl), 0) pnl " +
                        "FROM bt_live_signal WHERE auto_traded = 1 AND " + sideFilter + " " +
                        "AND symbol = ? AND exit_time IS NOT NULL AND realized_pnl IS NOT NULL " +
                        "AND exit_time >= ? AND exit_time < ?",
                symbol, window.startTpe(), window.endTpe());
        return componentPnl(component, rows);
    }

    private ComponentPnl loadGridPnl(String symbol, WindowSpec window) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT COUNT(*) cnt, COALESCE(SUM(total_realized_pnl), 0) pnl " +
                        "FROM bt_grid WHERE symbol = ? AND total_realized_pnl IS NOT NULL " +
                        "AND total_realized_pnl != 0 AND closed_at IS NOT NULL " +
                        "AND closed_at >= ? AND closed_at < ?",
                symbol, window.startTpe(), window.endTpe());
        return componentPnl("Grid", rows);
    }

    private ComponentPnl componentPnl(String component, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ComponentPnl(component, 0, BigDecimal.ZERO);
        }
        Map<String, Object> row = rows.get(0);
        return new ComponentPnl(component, longVal(row.get("cnt")), bd(row.get("pnl")));
    }

    private BigDecimal latestPrice(String symbol) {
        try {
            for (String interval : List.of("1m", "15m", "1h")) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ close_price " +
                                "FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open) " +
                                "WHERE symbol = ? AND interval_code = ? AND source = 'okx' " +
                                "ORDER BY open_time DESC LIMIT 1",
                        symbol, interval);
                if (rows != null && !rows.isEmpty()) {
                    return bd(rows.get(0).get("close_price"));
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("[WeeklyPerformanceAttribution] latest price unavailable for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private void appendComparison(StringBuilder sb, String title,
                                  WindowAttribution previous, WindowAttribution current) {
        sb.append(title).append("\n");
        sb.append("Window                         SPOT        SWAP        Grid        Total       Count\n");
        appendWindowRow(sb, previous);
        appendWindowRow(sb, current);
        sb.append(String.format("Delta                                                     %s%n%n",
                money(current.total().subtract(previous.total()))));
    }

    private void appendWindowRow(StringBuilder sb, WindowAttribution w) {
        sb.append(String.format("%-30s %10s  %10s  %10s  %10s  %5d%n",
                w.window().label() + " " + shortWindow(w.window()),
                money(w.spot().pnl()),
                money(w.swap().pnl()),
                money(w.grid().pnl()),
                money(w.total()),
                w.count()));
    }

    private String shortWindow(WindowSpec w) {
        return w.startTpe().toLocalDate() + "~" + w.endTpe().toLocalDate();
    }

    private String primaryCause(WindowAttribution previous, WindowAttribution current) {
        if (current.total().compareTo(BigDecimal.ZERO) == 0 && previous.total().compareTo(BigDecimal.ZERO) > 0) {
            String cause = previous.grid().pnl().compareTo(BigDecimal.ZERO) > 0 ? "prior period had Grid close gains" : "prior period had realized closes";
            return "no realized closes in current period; " + cause + ".";
        }
        BigDecimal delta = current.total().subtract(previous.total());
        if (delta.compareTo(BigDecimal.ZERO) > 0) return "current period realized more PnL than the comparison period.";
        if (delta.compareTo(BigDecimal.ZERO) < 0) return "current period realized less PnL than the comparison period.";
        return "realized PnL is flat versus the comparison period.";
    }

    private void appendOpenPositions(StringBuilder sb, String symbol, BigDecimal lastPrice) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, symbol, COALESCE(side, 'LONG') side, COALESCE(actual_entry_price, entry_price) entry_price, " +
                        "suggested_tp, suggested_sl, oco_order_list_id " +
                        "FROM bt_live_signal WHERE auto_traded = 1 AND symbol = ? AND exit_time IS NULL " +
                        "ORDER BY created_at DESC LIMIT 5",
                symbol);
        if (rows.isEmpty()) {
            sb.append("- Open positions: none\n");
            return;
        }
        for (Map<String, Object> row : rows) {
            BigDecimal entry = bd(row.get("entry_price"));
            BigDecimal pct = pct(lastPrice, entry, String.valueOf(row.get("side")));
            sb.append(String.format("- Position #%s %s %s entry=%s last=%s unrealized=%s OCO=%s TP=%s SL=%s%n",
                    row.get("id"),
                    row.get("symbol"),
                    row.get("side"),
                    plain(entry),
                    plain(lastPrice),
                    pct == null ? "N/A" : money(pct) + "%",
                    row.get("oco_order_list_id") != null ? "active" : "local-field-missing",
                    plain(bd(row.get("suggested_tp"))),
                    plain(bd(row.get("suggested_sl")))));
        }
    }

    private void appendActiveGrids(StringBuilder sb, String symbol, BigDecimal lastPrice) {
        List<Map<String, Object>> grids = jdbc.queryForList(
                "SELECT id, symbol, price_lower, price_upper, total_realized_pnl, closed_pair_count, paused_at, enabled " +
                        "FROM bt_grid WHERE symbol = ? AND closed_at IS NULL ORDER BY id DESC LIMIT 5",
                symbol);
        if (grids.isEmpty()) {
            sb.append("- Active grids: none\n\n");
            return;
        }
        for (Map<String, Object> grid : grids) {
            long gridId = longVal(grid.get("id"));
            Map<String, Long> counts = loadGridLevelCounts(gridId);
            BigDecimal lower = bd(grid.get("price_lower"));
            BigDecimal upper = bd(grid.get("price_upper"));
            String rangeState = gridRangeState(lastPrice, lower, upper);
            sb.append(String.format("- Grid #%d %s range=%s~%s current=%s %s pending=%d holding=%d closed=%d sell_failed=%d realized=%s pairs=%d%n",
                    gridId,
                    grid.get("symbol"),
                    plain(lower),
                    plain(upper),
                    plain(lastPrice),
                    rangeState,
                    counts.getOrDefault("PENDING", 0L),
                    counts.getOrDefault("HOLDING", 0L),
                    counts.getOrDefault("CLOSED", 0L),
                    counts.getOrDefault("SELL_FAILED", 0L),
                    money(bd(grid.get("total_realized_pnl"))),
                    longVal(grid.get("closed_pair_count"))));
            appendGridTargets(sb, gridId, lastPrice);
        }
        sb.append("\n");
    }

    private Map<String, Long> loadGridLevelCounts(long gridId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, COUNT(*) cnt FROM bt_grid_level WHERE grid_id = ? GROUP BY status",
                gridId);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(String.valueOf(row.get("status")), longVal(row.get("cnt")));
        }
        return counts;
    }

    private void appendGridTargets(StringBuilder sb, long gridId, BigDecimal lastPrice) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT level_index, status, filled_price, filled_qty, paired_sell_price, realized_pnl " +
                        "FROM bt_grid_level WHERE grid_id = ? " +
                        "AND status IN ('HOLDING', 'SELL_FAILED', 'SELL_PARTIAL') " +
                        "ORDER BY level_index ASC LIMIT 5",
                gridId);
        for (Map<String, Object> row : rows) {
            BigDecimal sell = bd(row.get("paired_sell_price"));
            BigDecimal filledPrice = bd(row.get("filled_price"));
            BigDecimal qty = bd(row.get("filled_qty"));
            BigDecimal distance = pctDistance(lastPrice, sell);
            BigDecimal roughPnl = lastPrice != null && filledPrice != null && qty != null
                    ? lastPrice.subtract(filledPrice).multiply(qty).setScale(8, RoundingMode.HALF_UP)
                    : null;
            sb.append(String.format("  target L%s status=%s filled=%s sell=%s distance=%s rough_unrealized=%s%n",
                    row.get("level_index"),
                    row.get("status"),
                    plain(filledPrice),
                    plain(sell),
                    distance == null ? "N/A" : money(distance) + "%",
                    roughPnl == null ? "N/A" : money(roughPnl)));
        }
    }

    private void appendSignalGuardContext(StringBuilder sb, String symbol, WindowSpec window) {
        sb.append("Signal / Guard Context\n");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_type, outcome, COALESCE(blocker, '-') blocker, reason, COUNT(*) cnt " +
                        "FROM bt_decision_audit WHERE symbol = ? AND event_time >= ? AND event_time < ? " +
                        "AND event_type IN ('SIGNAL_EVAL','ENTRY_SKIP','FILTER_BLOCK','AUTOTRADE_OK','AUTOTRADE_FAIL') " +
                        "GROUP BY event_type, outcome, COALESCE(blocker, '-'), reason ORDER BY cnt DESC LIMIT 12",
                symbol, window.startTpe(), window.endTpe());
        long buyEval = 0;
        long entryDedup = 0;
        long autoFail = 0;
        for (Map<String, Object> row : rows) {
            String event = String.valueOf(row.get("event_type"));
            String reason = String.valueOf(row.get("reason"));
            String blocker = String.valueOf(row.get("blocker"));
            long cnt = longVal(row.get("cnt"));
            if ("SIGNAL_EVAL".equals(event) && "BUY".equalsIgnoreCase(reason)) buyEval += cnt;
            if ("ENTRY_SKIP".equals(event) && "EntryDedup".equals(blocker)) entryDedup += cnt;
            if ("AUTOTRADE_FAIL".equals(event)) autoFail += cnt;
        }
        sb.append(String.format("- BUY evaluations: %d%n", buyEval));
        sb.append(String.format("- EntryDedup skips: %d%n", entryDedup));
        sb.append(String.format("- Autotrade failures: %d%n", autoFail));
        if (buyEval > 0 && entryDedup > 0) {
            sb.append("- Interpretation: BUY pressure existed, but additional exposure was blocked by EntryDedup/existing LONG guard.\n");
        } else if (buyEval == 0) {
            sb.append("- Interpretation: no BUY-style evaluation in this natural-week window.\n");
        }
        if (autoFail > 0) {
            sb.append("- Warning: autotrade failures need follow-up.\n");
        }
        sb.append("\n");
    }

    private String gridRangeState(BigDecimal lastPrice, BigDecimal lower, BigDecimal upper) {
        if (lastPrice == null || lower == null || upper == null) return "(range unknown)";
        if (lastPrice.compareTo(lower) < 0) return "(below range)";
        if (lastPrice.compareTo(upper) > 0) return "(above range)";
        return "(in range)";
    }

    private BigDecimal pct(BigDecimal lastPrice, BigDecimal entry, String side) {
        if (lastPrice == null || entry == null || entry.signum() == 0) return null;
        BigDecimal diff = "SHORT".equals(side) ? entry.subtract(lastPrice) : lastPrice.subtract(entry);
        return diff.multiply(BigDecimal.valueOf(100)).divide(entry, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal pctDistance(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) return null;
        return to.subtract(from).multiply(BigDecimal.valueOf(100)).divide(from, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal b) return b;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private long longVal(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String money(BigDecimal value) {
        if (value == null) return "N/A";
        return String.format("%+.4f", value.doubleValue());
    }

    private String plain(BigDecimal value) {
        if (value == null) return "N/A";
        return value.stripTrailingZeros().toPlainString();
    }

    private record WindowSpec(String label, LocalDateTime startTpe, LocalDateTime endTpe) {}

    private record ComponentPnl(String component, long count, BigDecimal pnl) {}

    private record WindowAttribution(WindowSpec window, ComponentPnl spot, ComponentPnl swap, ComponentPnl grid) {
        BigDecimal total() {
            return safe(spot.pnl()).add(safe(swap.pnl())).add(safe(grid.pnl()));
        }

        long count() {
            return spot.count() + swap.count() + grid.count();
        }

        private static BigDecimal safe(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}
