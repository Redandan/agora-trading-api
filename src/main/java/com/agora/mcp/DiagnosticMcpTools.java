package com.agora.mcp;

import com.agora.dto.backtest.BacktestResultResponse;
import com.agora.dto.backtest.BacktestRunRequest;
import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.mcp.auth.McpApiKeyFilter;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.ServerStartupLog;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.system.ServerStartupLogRepository;
import com.agora.service.BacktestService;
import com.agora.service.market.BinanceKlineImportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 診斷工具集。
 * 提供策略執行驗證：先補齊 K 線資料，以回測為基準，比對啟動後實際觸發的買入訊號，排查漏單。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticMcpTools {

    private static final ZoneId TZ = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String APP_LOG_PATH = "/home/ubuntu/agora-trading-api/app.log";
    private static final String APP_STARTED_MARKER = "Started TradingApiApplication";
    private static final String APP_STARTING_MARKER = "Starting TradingApiApplication";

    private final ServerStartupLogRepository startupLogRepo;
    private final BtStrategyRepository strategyRepo;
    private final BtLiveSignalRepository liveSignalRepo;
    private final BacktestService backtestService;
    private final BinanceKlineImportService klineImportService;
    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.agora.service.diagnostic.IndicatorOutcomeService outcomeService;
    private final com.agora.service.diagnostic.IndicatorAccuracyScanner accuracyScanner;
    private final com.agora.service.diagnostic.IndicatorHourMatrixService hourMatrixService;
    private final com.agora.service.diagnostic.AlphaPromotionTracker promotionTracker;
    private final com.agora.service.diagnostic.OrphanTradeReconcilerService orphanReconciler;
    private final com.agora.service.diagnostic.DbSlowQueryMonitorService dbSlowQueryMonitorService;
    private final com.agora.service.trading.PositionSizingService positionSizingService;
    private final McpApiKeyFilter mcpApiKeyFilter;
    private final com.agora.service.trading.EventRiskLevelEngine eventRiskLevelEngine;
    private final McpRegistryVersionService mcpRegistryVersionService;

    private java.io.File resolveAppLogFile() {
        java.io.File logFile = new java.io.File(APP_LOG_PATH);
        if (!logFile.exists()) {
            logFile = new java.io.File("logs/app.log");
        }
        return logFile;
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.META})
    @Tool(description = "Read-only MCP registry version fingerprint. Returns server version, git commit, startedAt, tool/resource counts, resourceNamesHash, and registryVersion. No trading/OCO/strategy/grid/fund/Earn behavior is changed.")
    public String getMcpRegistryVersion() {
        try {
            return objectMapper.writeValueAsString(mcpRegistryVersionService.buildVersionInfo());
        } catch (Exception e) {
            return "{\"boundary\":\"READ_ONLY\",\"error\":\"failed to serialize MCP registry version\"}";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC})
    @Tool(description = "MCP auth probe (safe diagnostics): returns matched key type, session fingerprint, external-ai detection, approval status, and deny reason for a target tool. No raw secret is exposed. "
            + "External-AI key should call this probe before protected tool calls and pass arguments.requestedTools as the batch authorization plan. "
            + "After one TG approval, only tools listed in requestedTools are allowed within TTL; unplanned tools return BATCH_PLAN_REQUIRED. "
            + "params: requestedTools (string array, required for SESSION_BATCH)")
    public String getMcpAuthProbe(
            @ToolParam(description = "Required batch tool plan for SESSION_BATCH, e.g. [\"getSystemHealth\",\"getCurrentExposure\"]")
            java.util.List<String> requestedTools
    ) {
        String toolName = "getSessionBrief";
        if (requestedTools != null) {
            for (String tool : requestedTools) {
                if (tool != null && !tool.isBlank()) {
                    toolName = tool.trim();
                    break;
                }
            }
        }
        try {
            Map<String, Object> probe = mcpApiKeyFilter.buildAuthProbe(toolName);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(probe);
        } catch (Exception e) {
            return "❌ auth probe failed: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "Read-only DB slow query monitor report. Checks active app slow queries, hot indicator/kline queries, active HeatWave SECONDARY_LOAD/rapid_ml_operation, and ignored killed HeatWave remnants. params: minSeconds optional default config watch threshold, maxRows optional")
    public String getDbSlowQueryReport(
            @ToolParam(description = "Minimum active query age in seconds; null uses configured watch threshold")
            Integer minSeconds,
            @ToolParam(description = "Maximum rows to show; null uses configured max rows")
            Integer maxRows
    ) {
        try {
            var report = (minSeconds == null && maxRows == null)
                    ? dbSlowQueryMonitorService.scan()
                    : dbSlowQueryMonitorService.scan(
                            minSeconds == null ? 30 : minSeconds,
                            maxRows == null ? 10 : maxRows);
            return dbSlowQueryMonitorService.render(report);
        } catch (Exception e) {
            log.warn("[MCP getDbSlowQueryReport] failed: {}", e.getMessage(), e);
            return "❌ getDbSlowQueryReport failed: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC})
    @Tool(description = "#227 查詢 Flyway 已套用的 migration 版本清單。" +
            "快速確認 DB 目前跑到哪個版本、有無失敗記錄，不需要 SSH。" +
            "param: failedOnly=true 只顯示失敗記錄（預設 false 顯示全部）")
    public String getAppliedMigrations(Boolean failedOnly) {
        boolean failOnly = Boolean.TRUE.equals(failedOnly);
        try {
            String sql = failOnly
                    ? "SELECT version, description, type, installed_on, success, execution_time " +
                      "FROM flyway_schema_history WHERE success=0 ORDER BY installed_rank DESC"
                    : "SELECT version, description, type, installed_on, success, execution_time " +
                      "FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 20";
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(sql);
            if (rows.isEmpty()) return failOnly ? "✅ 無失敗的 migration" : "ℹ️ flyway_schema_history 無記錄";

            StringBuilder sb = new StringBuilder();
            sb.append(failOnly ? "=== 失敗的 Migrations ===\n\n" : "=== 最近 20 個已套用 Migrations ===\n\n");
            sb.append(String.format("%-8s| %-45s| %-8s| %-5s%n", "Version", "Description", "Applied", "OK"));
            sb.append("-".repeat(72)).append("\n");
            for (var row : rows) {
                // MySQL JDBC 8.0+ maps tinyint(1) to Boolean; older drivers / explicit
                // CAST may return Integer. Accept both rather than assume one shape.
                Object successVal = row.get("success");
                boolean ok = successVal instanceof Boolean b ? b
                        : successVal instanceof Number n && n.intValue() == 1;
                String applied = String.valueOf(row.get("installed_on")).substring(0, 10);
                String desc = String.valueOf(row.get("description"));
                if (desc.length() > 44) desc = desc.substring(0, 41) + "...";
                sb.append(String.format("%-8s| %-45s| %-8s| %s%n",
                        row.get("version"), desc, applied, ok ? "✅" : "❌"));
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC})
    @Tool(description = "驗證策略執行情況，找出漏單或漏評估的潛在 Bug。" +
            "先自動補齊所需 K 線資料，再對每個啟用策略執行回測，比對回測 BUY 信號數與實際觸發數。" +
            "days 參數指定往回驗證的天數（例如 days=7 表示近 7 天）；" +
            "不傳 days 則預設從本次伺服器啟動時間起算。" +
            "MTF 策略會同時補齊 1h 與 4h K 線資料。")
    public String verifyStrategyExecution(Integer days) {
        LocalDateTime now = LocalDateTime.now(TZ);
        LocalDateTime since;
        String sinceLabel;

        if (days != null && days > 0) {
            since = now.minusDays(days);
            sinceLabel = String.format("近 %d 天（%s）", days, since.format(FMT));
        } else {
            // 1. 取最新啟動時間
            List<ServerStartupLog> logs = startupLogRepo.findTop10ByOrderByStartedAtDesc();
            if (logs.isEmpty()) {
                return "❌ 尚無啟動記錄，請確認 server_startup_log 表是否存在（或傳入 days 參數指定驗證區間）";
            }
            ServerStartupLog latest = logs.get(0);
            since = latest.getFirstEvalAt() != null
                    ? latest.getFirstEvalAt() : latest.getStartedAt();
            sinceLabel = String.format("本次啟動（%s）", since.format(FMT));
        }

        LocalDateTime backtestStart = since.minusDays(30); // 暖機資料起點

        StringBuilder sb = new StringBuilder();
        sb.append("🔍 策略執行驗證報告\n");
        sb.append(String.format("驗證起點：%s\n", sinceLabel));
        sb.append(String.format("驗證區間：%s → %s\n\n", since.format(FMT), now.format(FMT)));

        // 2. 取所有啟用策略，解析 symbol + intervalCode
        List<BtStrategy> strategies = strategyRepo.findAll().stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .toList();

        if (strategies.isEmpty()) {
            return sb.append("⚠️ 無啟用策略").toString();
        }

        // 3. 先補齊所有策略需要的 K 線資料（去重，同 symbol+interval 只 import 一次）
        sb.append("📥 補齊 K 線資料...\n");
        Set<String> imported = new HashSet<>();
        for (BtStrategy strategy : strategies) {
            String symbol = resolveSymbol(strategy);
            if (symbol == null) continue;

            // MTF 策略需同時補齊主週期與 4h 資料
            Set<String> intervals = resolveAllIntervals(strategy);
            for (String intervalCode : intervals) {
                String key = symbol + ":" + intervalCode;
                if (imported.contains(key)) continue;
                imported.add(key);

                try {
                    var resp = klineImportService.importHistorical(
                            symbol.toUpperCase(), intervalCode, backtestStart, now);
                    sb.append(String.format("  %s@%s → 新增 %d 筆，略過 %d 筆\n",
                            symbol, intervalCode,
                            resp.getImportedCount(), resp.getSkippedCount()));
                } catch (Exception e) {
                    sb.append(String.format("  %s@%s → import 失敗：%s\n", symbol, intervalCode, e.getMessage()));
                }
            }
        }
        sb.append("\n");

        // 4. 逐策略回測 + 比對
        int bugCount = 0;
        int warnCount = 0;

        for (BtStrategy strategy : strategies) {
            sb.append(String.format("─── 策略 %d｜%s ───\n", strategy.getId(), strategy.getName()));

            String symbol = resolveSymbol(strategy);
            String intervalCode = resolveInterval(strategy);
            boolean notifyOnly = resolveNotifyOnly(strategy);

            if (symbol == null) {
                sb.append("  ⚠️ 無法取得 symbol，跳過\n\n");
                continue;
            }

            sb.append(String.format("  幣種：%s｜週期：%s｜模式：%s\n",
                    symbol, intervalCode, notifyOnly ? "通知Only" : "自動下單"));

            // 回測（資料已補齊）
            int backtestBuyCount = 0;
            try {
                BacktestRunRequest req = new BacktestRunRequest();
                req.setStrategyId(strategy.getId());
                req.setSymbol(symbol.toUpperCase());
                req.setIntervalCode(intervalCode);
                req.setStartTime(backtestStart);
                req.setEndTime(now);
                req.setInitialCapital(new BigDecimal("10000"));
                req.setFeeRate(new BigDecimal("0.001"));

                BacktestResultResponse result = backtestService.runForExploration(req);
                if (result.getTrades() != null) {
                    backtestBuyCount = (int) result.getTrades().stream()
                            .filter(t -> t.getEntryTime() != null && t.getEntryTime().isAfter(since))
                            .count();
                }
            } catch (Exception e) {
                sb.append(String.format("  ⚠️ 回測失敗：%s\n\n", e.getMessage()));
                continue;
            }

            // Live 訊號
            List<BtLiveSignal> liveSignals = liveSignalRepo
                    .findByStrategyIdAndCreatedAtAfter(strategy.getId(), since);
            int liveCount = liveSignals.size();
            long autoTradedCount = liveSignals.stream()
                    .filter(s -> Boolean.TRUE.equals(s.getAutoTraded()))
                    .count();
            long blockedCount = notifyOnly ? 0 : liveSignals.stream()
                    .filter(s -> !Boolean.TRUE.equals(s.getAutoTraded()))
                    .filter(s -> hasText(s.getFilterReason()))
                    .count();
            long unclassifiedSkips = notifyOnly ? 0 : liveSignals.stream()
                    .filter(s -> !Boolean.TRUE.equals(s.getAutoTraded()))
                    .filter(s -> !hasText(s.getFilterReason()))
                    .count();
            Map<String, Integer> entrySkipAudits = notifyOnly
                    ? Map.of()
                    : countEntrySkipAudits(strategy.getId(), since);
            int entrySkipCount = entrySkipAudits.values().stream().mapToInt(Integer::intValue).sum();
            SignalEvalAuditStats signalEvalStats = countSignalEvalAudits(strategy.getId(), since);

            sb.append(String.format("  回測 BUY 信號：%d 次\n", backtestBuyCount));
            sb.append(String.format("  Live 實際信號：%d 次\n", liveCount));
            if (signalEvalStats.total() > 0) {
                sb.append(String.format("  SIGNAL_EVAL audit：%d 次（BUY/LONG=%d，HOLD/其他=%d）\n",
                        signalEvalStats.total(),
                        signalEvalStats.buyLike(),
                        Math.max(0, signalEvalStats.total() - signalEvalStats.buyLike())));
            }
            if (!notifyOnly && entrySkipCount > 0) {
                sb.append(String.format("  ENTRY_SKIP audit：%d 次（%s）\n",
                        entrySkipCount, summarizeCounts(entrySkipAudits)));
            }
            if (!notifyOnly && liveCount > 0) {
                sb.append(String.format("  自動下單狀態：已下單 %d｜合理阻擋 %d｜未標記 %d\n",
                        autoTradedCount, blockedCount, unclassifiedSkips));
            }

            if (backtestBuyCount > 0 && liveCount == 0 && entrySkipCount == 0 && signalEvalStats.total() == 0) {
                sb.append("  ❌ 回測有信號但 Live 無記錄 → 疑似漏評估 Bug\n");
                bugCount++;
            } else if (backtestBuyCount > 0 && liveCount == 0 && signalEvalStats.total() > 0 && signalEvalStats.buyLike() == 0) {
                sb.append("  ℹ️ 回測有信號但 Live 僅見 HOLD 型 SIGNAL_EVAL；較像口徑/時間窗差異，非漏評估\n");
            } else if (backtestBuyCount > 0 && liveCount == 0) {
                sb.append("  ✅ 回測有信號但 Live 無 live_signal；ENTRY_SKIP 已記錄原因（非漏單）\n");
            } else if (backtestBuyCount == 0 && liveCount == 0) {
                sb.append("  ✅ 市場條件未達標（HOLD 正常）\n");
            } else if (unclassifiedSkips > 0) {
                if (entrySkipCount > 0) {
                    sb.append(String.format("  ℹ️ %d 筆 live_signal 無 filter_reason，但同窗有 ENTRY_SKIP audit；若為部署前舊資料可忽略，若新資料重現再追\n",
                            unclassifiedSkips));
                } else {
                    sb.append(String.format("  ⚠️ %d 筆 BUY 信號未下單且無 filter_reason → 多半是舊資料或早退分支，需觀察新部署後是否再出現\n",
                            unclassifiedSkips));
                    warnCount++;
                }
            } else {
                sb.append("  ✅ 正常\n");
            }
            sb.append("\n");
        }

        sb.append("─────────────────────────\n");
        if (bugCount == 0) {
            sb.append("✅ 未發現漏評估/漏單 Bug");
            if (warnCount > 0) {
                sb.append(String.format("；另有 %d 個策略存在舊式未標記 skip，需觀察新資料", warnCount));
            }
        } else {
            sb.append(String.format("⚠️ 發現 %d 個策略有潛在問題，請確認 app.log 是否有 ERROR", bugCount));
        }

        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.READ_TRADING, Category.META})
    @Tool(description = "#517 Phase A read-only event-risk-control status. "
            + "Returns R0-R3 risk level, score, reasons, latest volatility inputs, recent MARKET_SIGNAL route counts, "
            + "and the new-entry gate policy. Does not change trading/OCO/strategy/funds. params: symbol default BTCUSDT")
    public String getEventRiskControlStatus(
            @ToolParam(description = "Trading symbol, default BTCUSDT") String symbol
    ) {
        try {
            return eventRiskLevelEngine.render(eventRiskLevelEngine.evaluate(symbol));
        } catch (Exception e) {
            log.warn("[MCP getEventRiskControlStatus] failed: {}", e.getMessage(), e);
            return "❌ getEventRiskControlStatus failed: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC})
    @Tool(description = "#482 read-only report for live_signal rows that skipped auto-trade without filter_reason. " +
            "Groups missing-reason rows by strategy and correlates each row with nearby ENTRY_SKIP / DuplicateBar / EntryDedup / FILTER_BLOCK audit events. " +
            "days defaults to 7; limit defaults to 50. Does not write or change strategy behavior.")
    public String listUnmarkedLiveSignalSkips(Integer days, Integer limit) {
        int d = days != null && days > 0 ? Math.min(days, 90) : 7;
        int lim = limit != null && limit > 0 ? Math.min(limit, 200) : 50;
        LocalDateTime since = LocalDateTime.now(TZ).minusDays(d);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Unmarked live_signal skip audit (#482) ===\n");
        sb.append(String.format("window: last %d days since %s | limit=%d | mode=READ_ONLY%n%n",
                d, since.format(FMT), lim));

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    SELECT s.id, s.strategy_id, COALESCE(st.name, CONCAT('strategy#', s.strategy_id)) AS strategy_name,
                           s.symbol, s.interval_code, s.bar_open_time, s.created_at, s.entry_price,
                           COALESCE(s.auto_traded, 0) AS auto_traded
                    FROM bt_live_signal s
                    LEFT JOIN bt_strategy st ON st.id = s.strategy_id
                    WHERE s.created_at >= ?
                      AND COALESCE(s.auto_traded, 0) = 0
                      AND (s.filter_reason IS NULL OR TRIM(s.filter_reason) = '')
                      AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(st.config_json, '$.notifyOnly')), 'false') NOT IN ('true', '1')
                    ORDER BY s.created_at DESC
                    LIMIT ?
                    """, since, lim);
        } catch (Exception e) {
            return "❌ unmarked live_signal query failed: " + e.getMessage();
        }

        if (rows.isEmpty()) {
            return sb.append("✅ No unmarked skipped live_signal rows in this window.\n").toString();
        }

        Map<String, Integer> byStrategy = new LinkedHashMap<>();
        Map<String, Integer> byClass = new LinkedHashMap<>();
        List<String> details = new java.util.ArrayList<>();
        int correlated = 0;
        int uncorrelated = 0;
        int new24h = 0;
        LocalDateTime last24h = LocalDateTime.now(TZ).minusHours(24);

        for (Map<String, Object> row : rows) {
            Long id = asLong(row.get("id"));
            Long strategyId = asLong(row.get("strategy_id"));
            String strategyName = String.valueOf(row.get("strategy_name"));
            String symbol = String.valueOf(row.get("symbol"));
            String interval = String.valueOf(row.get("interval_code"));
            LocalDateTime barOpen = asDateTime(row.get("bar_open_time"));
            LocalDateTime createdAt = asDateTime(row.get("created_at"));
            String key = strategyId + " " + strategyName;
            byStrategy.merge(key, 1, Integer::sum);
            if (createdAt != null && createdAt.isAfter(last24h)) new24h++;

            AuditMatch match = nearestSkipAudit(strategyId, symbol, interval, barOpen, createdAt);
            String classification = classifyUnmarkedSkip(match);
            byClass.merge(classification, 1, Integer::sum);
            if (match.hasAudit()) correlated++; else uncorrelated++;

            details.add(String.format(
                    "[%s] live_signal=%d strategy=%d %s %s@%s bar=%s created=%s entry=%s "
                            + "| canonicalSkipReasonFromAudit=%s | skipReasonSource=%s "
                            + "| skipReasonConfidence=%s | blockedByExistingLiveSignalId=%s -> %s%s",
                    classification, id, strategyId, truncate(strategyName, 42), symbol, interval,
                    fmtTime(barOpen), fmtTime(createdAt), row.get("entry_price"),
                    match.canonicalSkipReasonFromAudit(), match.skipReasonSource(),
                    match.skipReasonConfidence(barOpen), match.blockedByExistingLiveSignalId(),
                    match.summary(), match.recommendation()));
        }

        sb.append("summary:\n");
        sb.append(String.format("  rows: %d%n", rows.size()));
        sb.append(String.format("  audit-correlated: %d%n", correlated));
        sb.append(String.format("  no-nearby-audit: %d%n", uncorrelated));
        sb.append(String.format("  created_in_last_24h: %d%n", new24h));
        String status = uncorrelated > 0 && new24h > 0
                ? "WARN_CURRENT_RECURRENCE"
                : uncorrelated > 0 ? "HISTORICAL_LINKAGE_DEBT" : "CLEAN";
        String action = switch (status) {
            case "WARN_CURRENT_RECURRENCE" -> "investigate current no-audit skip before trusting execution completeness";
            case "HISTORICAL_LINKAGE_DEBT" -> "treat as old audit-labeling debt unless new rows appear";
            default -> "no action";
        };
        sb.append(String.format("  data_quality_status: %s%n", status));
        sb.append(String.format("  operator_action: %s%n", action));
        sb.append("\nby classification:\n");
        byClass.forEach((k, v) -> sb.append(String.format("  %s: %d%n", k, v)));
        sb.append("\nby strategy:\n");
        byStrategy.forEach((k, v) -> sb.append(String.format("  %s: %d%n", k, v)));

        sb.append("\nrows:\n");
        details.forEach(line -> sb.append("  ").append(line).append("\n"));

        sb.append("\ninterpretation:\n");
        if (uncorrelated == 0) {
            sb.append("  ✅ All sampled unmarked rows correlate with nearby audit events; this looks audit-labeling debt, not missing execution.\n");
        } else {
            sb.append("  ⚠️ Some rows have no nearby audit event; inspect logs before treating them as intentional skips.\n");
        }
        if (new24h == 0) {
            sb.append("  ✅ No new unmarked rows in the last 24h within this sample window.\n");
        } else {
            sb.append("  ⚠️ New unmarked rows appeared in the last 24h; consider a follow-up guardrail to persist machine-readable skip reasons.\n");
        }
        sb.append("  Boundary: read-only report only; no strategy/order/risk behavior changed.\n");

        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "#493 read-only intraday high/low timing and missed-long explanation. "
            + "Uses OKX 1m md_kline for the Asia/Taipei local day, then correlates nearby "
            + "SIGNAL_EVAL / FILTER_BLOCK / ENTRY_SKIP audit rows. Does not change strategy/order behavior.")
    public String getIntradayExtremesDigest(String symbol) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        LocalDate taipeiDay = LocalDate.now(TZ);
        ZonedDateTime startTaipei = taipeiDay.atStartOfDay(TZ);
        ZonedDateTime endTaipei = startTaipei.plusDays(1);
        LocalDateTime startUtc = startTaipei.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime endUtc = endTaipei.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        StringBuilder sb = new StringBuilder();
        sb.append("Intraday Extremes & Missed-Long Reasoning\n");
        sb.append("source: md_kline OKX 1m | symbol: ").append(sym)
                .append(" | day: ").append(taipeiDay).append(" Asia/Taipei\n");
        sb.append("boundary: read-only report; no strategy/order/OCO/fund behavior changed.\n\n");

        KlineExtreme low = findExtreme(sym, startUtc, endUtc, true);
        KlineExtreme high = findExtreme(sym, startUtc, endUtc, false);
        if (low == null && high == null) {
            sb.append("No OKX 1m kline rows found for this local day.\n");
            sb.append("next_action: verify md_kline OKX 1m collector/backfill freshness before drawing market-timing conclusions.\n");
            return sb.toString();
        }

        appendExtreme(sb, "LOW", low, sym);
        appendExtreme(sb, "HIGH", high, sym);
        appendMissedLongSummary(sb, low, high, sym);
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING, Category.ANALYTICS})
    @Tool(description = "Read-only missed trading opportunity check. Scans BUY audit rows, correlates ENTRY_SKIP/FILTER_BLOCK/AUTOTRADE, "
            + "looks at forward 24h price opportunity, and previews risk sizing when entry/tp/sl are available. "
            + "Does not create signals, orders, OCO, strategy changes, or fund movements. "
            + "params: symbol(default BTCUSDT), hours(1~168 default 24), minForwardMovePct(default 1.0), availableUsdt optional, reservedUsdt optional")
    public String analyzeMissedTradingOpportunities(
            @ToolParam(required = false, description = "交易對，預設 BTCUSDT") String symbol,
            @ToolParam(required = false, description = "回溯小時數，預設 24，最多 168") Integer hours,
            @ToolParam(required = false, description = "forward 24h 最大上行超過此百分比才視為有機會，預設 1.0") Double minForwardMovePct,
            @ToolParam(required = false, description = "可用 USDT；若提供，會先扣 reservedUsdt 再做風險定倉 preview") Double availableUsdt,
            @ToolParam(required = false, description = "執行經理保留資金，例如 Grid reserve；只影響 preview，不改資金") Double reservedUsdt) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        int h = hours == null || hours <= 0 ? 24 : Math.min(hours, 168);
        double threshold = minForwardMovePct == null || minForwardMovePct <= 0 ? 1.0 : minForwardMovePct;
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime start = end.minusHours(h);
        Double adjustedAvailable = availableUsdt == null ? null : Math.max(0.0, availableUsdt - Math.max(0.0, reservedUsdt == null ? 0.0 : reservedUsdt));

        List<BuyAuditCandidate> buys = loadBuyAuditCandidates(sym, start, end);
        List<MissedOpportunityRow> rows = new ArrayList<>();
        for (BuyAuditCandidate buy : buys) {
            List<AuditEvent> related = findRelatedExecutionAudits(buy, 90);
            ForwardWindow forward = loadForwardWindow(sym, buy.eventTime(), 24);
            rows.add(classifyMissedOpportunity(buy, related, forward, threshold, adjustedAvailable));
        }

        long missed = rows.stream().filter(r -> "MISSED_CANDIDATE".equals(r.classification())).count();
        long blockedCorrect = rows.stream().filter(r -> "BLOCKED_BUT_CORRECT".equals(r.classification())).count();
        long lateOrLowEdge = rows.stream().filter(r -> "LATE_OR_LOW_EDGE".equals(r.classification())).count();
        long filterReview = rows.stream().filter(r -> "FILTER_BLOCK_REVIEW".equals(r.classification())).count();
        long executed = rows.stream().filter(r -> "EXECUTED".equals(r.classification())).count();

        StringBuilder sb = new StringBuilder();
        sb.append("Missed Trading Opportunity Check\n");
        sb.append("symbol: ").append(sym)
                .append(" | window: ").append(h).append("h")
                .append(" | minForwardMovePct=").append(fmtPct(threshold)).append("\n");
        sb.append("boundary: READ_ONLY; no trading, OCO, strategy, grid, or fund behavior changed.\n");
        sb.append("capitalPolicy: availableUsdt=").append(availableUsdt != null ? fmtMoney(availableUsdt) : "N/A")
                .append(" reservedUsdt=").append(reservedUsdt != null ? fmtMoney(reservedUsdt) : "N/A")
                .append(" sizingAvailableUsdt=").append(adjustedAvailable != null ? fmtMoney(adjustedAvailable) : "N/A")
                .append("\n\n");

        sb.append("Summary:\n");
        sb.append("  buyEvaluations=").append(buys.size()).append("\n");
        sb.append("  missedOpportunityCount=").append(missed).append("\n");
        sb.append("  blockedButCorrectCount=").append(blockedCorrect).append("\n");
        sb.append("  lateOrLowEdgeCount=").append(lateOrLowEdge).append("\n");
        sb.append("  filterBlockReviewCount=").append(filterReview).append("\n");
        sb.append("  executedCount=").append(executed).append("\n");

        if (rows.isEmpty()) {
            sb.append("\nNo BUY audit rows found in this window.\n");
            sb.append("operatorConclusion=NO_MISSED_OPPORTUNITY_SAMPLE\n");
            return sb.toString();
        }

        rows.stream()
                .filter(r -> "MISSED_CANDIDATE".equals(r.classification()) || "FILTER_BLOCK_REVIEW".equals(r.classification()))
                .findFirst()
                .ifPresent(r -> sb.append("  firstReviewCandidateSizing=").append(r.sizingLine()).append("\n"));

        sb.append("\nRecent BUY rows:\n");
        rows.stream()
                .sorted((a, b) -> b.buy().eventTime().compareTo(a.buy().eventTime()))
                .limit(12)
                .forEach(r -> sb.append("  - ").append(r.oneLine()).append("\n"));

        sb.append("\nOperator Conclusion:\n");
        if (missed > 0) {
            sb.append("- ACTION: review missed candidates before changing strategy gates; do not chase current price from this report alone.\n");
        } else if (filterReview > 0) {
            sb.append("- WATCH: FILTER_BLOCK rows had forward upside; use blocked-signal outcome analysis before relaxing filters.\n");
        } else {
            sb.append("- OK: no true missed trading opportunity detected in this window.\n");
        }
        if (blockedCorrect > 0) {
            sb.append("- EntryDedup/existing exposure blocks are counted as correct risk control, not missed trades.\n");
        }
        sb.append("- Risk sizing is hypothetical only and only applies if a future NEW_ENTRY is allowed by the execution manager.\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING, Category.ANALYTICS})
    @Tool(description = "Read-only Missed Alpha Attribution MVP. Groups recent BUY decision candidates by terminal blocker/warning source, "
            + "classifies missed/review/correct/low-edge/unscorable cases, and joins Runtime Evidence summary when available. "
            + "Does not create signals, orders, OCO, strategy changes, grid changes, Earn actions, or fund movements. "
            + "params: symbol(default BTCUSDT), hours(1~168 default 168), minForwardMovePct(default 1.0)")
    public String getMissedAlphaAttributionReport(
            @ToolParam(required = false, description = "交易對，預設 BTCUSDT") String symbol,
            @ToolParam(required = false, description = "回溯小時數，預設 168，最多 168") Integer hours,
            @ToolParam(required = false, description = "forward 24h 最大上行超過此百分比才視為有 alpha review，預設 1.0") Double minForwardMovePct) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        int h = hours == null || hours <= 0 ? 168 : Math.min(hours, 168);
        double threshold = minForwardMovePct == null || minForwardMovePct <= 0 ? 1.0 : minForwardMovePct;
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime start = end.minusHours(h);

        List<BuyAuditCandidate> buys = loadBuyAuditCandidates(sym, start, end);
        List<MissedOpportunityRow> rows = new ArrayList<>();
        for (BuyAuditCandidate buy : buys) {
            List<AuditEvent> related = findRelatedExecutionAudits(buy, 90);
            ForwardWindow forward = loadForwardWindow(sym, buy.eventTime(), 24);
            rows.add(classifyMissedOpportunity(buy, related, forward, threshold, null));
        }

        Map<String, AttributionBucket> byBlocker = new LinkedHashMap<>();
        for (MissedOpportunityRow row : rows) {
            String blocker = normalizeAttributionBlocker(row);
            byBlocker.computeIfAbsent(blocker, AttributionBucket::new).add(row);
        }
        RuntimeEvidenceAttributionSummary evidence = loadRuntimeEvidenceAttributionSummary(sym, start);

        long missed = rows.stream().filter(r -> "MISSED_CANDIDATE".equals(attributionClassification(r))).count();
        long review = rows.stream().filter(r -> "FILTER_BLOCK_REVIEW".equals(attributionClassification(r))).count();
        long correct = rows.stream().filter(r -> "BLOCKED_BUT_CORRECT".equals(attributionClassification(r))).count();
        long lowEdge = rows.stream().filter(r -> "LATE_OR_LOW_EDGE".equals(attributionClassification(r))).count();
        long executed = rows.stream().filter(r -> "EXECUTED".equals(attributionClassification(r))).count();
        long unscorable = rows.stream().filter(this::isUnscorableAttributionRow).count();

        String verdict;
        if (rows.isEmpty()) {
            verdict = "PENDING_NO_BUY_SAMPLE";
        } else if (unscorable > rows.size() / 2) {
            verdict = "NEEDS_INSTRUMENTATION";
        } else if (missed > 0 || review > 0) {
            verdict = "WATCH_POSSIBLE_ALPHA_DESTROYER";
        } else {
            verdict = "OK_NO_MISSED_ALPHA_SIGNAL";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Missed Alpha Attribution MVP ===\n")
                .append("boundary: READ_ONLY diagnostic; no signal/order/OCO/strategy/grid/fund/Earn behavior changed.\n")
                .append("symbol=").append(sym)
                .append(" hours=").append(h)
                .append(" minForwardMovePct=").append(fmtPct(threshold))
                .append("\n\n");

        sb.append("Summary:\n")
                .append("  buyEvaluations=").append(rows.size()).append("\n")
                .append("  missedCandidates=").append(missed).append("\n")
                .append("  filterBlockReview=").append(review).append("\n")
                .append("  blockedButCorrect=").append(correct).append("\n")
                .append("  lateOrLowEdge=").append(lowEdge).append("\n")
                .append("  unscorable=").append(unscorable).append("\n")
                .append("  executed=").append(executed).append("\n")
                .append("  verdict=").append(verdict).append("\n\n");

        sb.append("Runtime Evidence Join:\n")
                .append("  evidenceRows=").append(evidence.total()).append("\n")
                .append("  fearGreedWarnOnly=").append(evidence.fearGreedWarnOnly()).append("\n")
                .append("  fearGreedTerminal=").append(evidence.fearGreedTerminal()).append("\n")
                .append("  continuedToEv=").append(evidence.continuedToEv()).append("\n")
                .append("  continuedToTqs=").append(evidence.continuedToTqs()).append("\n")
                .append("  orderSentEvidence=").append(evidence.orderSent()).append("\n")
                .append("  shadowSuppressed=").append(evidence.shadowSuppressed()).append("\n")
                .append("  evidenceStatus=").append(evidence.status()).append("\n\n");

        sb.append("By Blocker:\n");
        if (byBlocker.isEmpty()) {
            sb.append("  no blockers/candidates in window\n");
        } else {
            byBlocker.values().stream()
                    .sorted((a, b) -> Integer.compare(b.total, a.total))
                    .forEach(b -> sb.append("  - ").append(b.oneLine()).append("\n"));
        }

        sb.append("\nTop Review Candidates:\n");
        List<MissedOpportunityRow> reviewRows = rows.stream()
                .filter(r -> "MISSED_CANDIDATE".equals(r.classification())
                        || "FILTER_BLOCK_REVIEW".equals(r.classification())
                        || isUnscorableAttributionRow(r))
                .sorted((a, b) -> b.buy().eventTime().compareTo(a.buy().eventTime()))
                .limit(10)
                .toList();
        if (reviewRows.isEmpty()) {
            sb.append("  none\n");
        } else {
            for (MissedOpportunityRow row : reviewRows) {
                sb.append("  - decisionId=").append(row.buy().id())
                        .append(" strategy=").append(row.buy().strategyId())
                        .append(" blocker=").append(normalizeAttributionBlocker(row))
                        .append(" class=").append(attributionClassification(row))
                        .append(" forwardMaxUp=").append(fmtPct(row.forward().maxUpPct()))
                        .append(" forwardMaxDown=").append(fmtPct(row.forward().maxDownPct()))
                        .append(" tradePlan=").append(hasTradePlan(row) ? "AVAILABLE" : "MISSING")
                        .append(" sizing=").append(row.sizingLine())
                        .append(" reason=").append(row.reason())
                        .append("\n");
            }
        }

        sb.append("\nInterpretation:\n");
        sb.append("- UNSCORABLE means missing trade plan or forward kline evidence; do not treat it as alpha or protection.\n");
        sb.append("- FILTER_BLOCK_REVIEW means forward upside exists, but blocker contribution must be judged with TP/SL and risk context.\n");
        sb.append("- Runtime Evidence is joined as observability context only; this report does not write evidence or alter execution.\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "Read-only wick-capture shadow detector. Scans OKX 15m md_kline for intrabar washout/recovery candidates "
            + "and evaluates 1h/4h/24h forward returns. Does not create signals, orders, OCO, strategy changes, or fund movements. "
            + "params: symbol(default BTCUSDT), days(1~180 default 7), minLowerWickPct(default 0.35), minRecoveryPct(default 0.20), limit(1~50 default 20)")
    public String analyzeWickCaptureShadow(String symbol, Integer days, Double minLowerWickPct,
                                           Double minRecoveryPct, Integer limit) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        int lookbackDays = days == null || days <= 0 ? 7 : Math.min(days, 180);
        int maxRows = limit == null || limit <= 0 ? 20 : Math.min(limit, 50);
        double wickThreshold = minLowerWickPct == null || minLowerWickPct <= 0 ? 0.35 : minLowerWickPct;
        double recoveryThreshold = minRecoveryPct == null || minRecoveryPct <= 0 ? 0.20 : minRecoveryPct;

        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays);
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC).plusHours(24);
        WickBarLoad load = loadWickBars(sym, start, end);
        List<WickBar> bars = load.bars();

        StringBuilder sb = new StringBuilder();
        sb.append("Wick Capture Shadow Detector\n")
                .append("source: ").append(load.sourceLabel()).append(" | symbol: ").append(sym)
                .append(" | lookbackDays=").append(lookbackDays)
                .append(" | mode=READ_ONLY\n")
                .append("criteria: lowerWickPct >= ").append(fmtPct(wickThreshold))
                .append(" and recoveryFromLowPct >= ").append(fmtPct(recoveryThreshold))
                .append("\n")
                .append("boundary: no signal/order/OCO/strategy/fund behavior changed.\n\n");

        if (bars.isEmpty()) {
            sb.append("No OKX 15m rows or aggregatable OKX 1m rows found. Verify collector/backfill before evaluating wick capture.\n");
            return sb.toString();
        }

        List<WickCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            WickBar bar = bars.get(i);
            if (bar.openTime().isBefore(start) || bar.openTime().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
                continue;
            }
            WickCandidate candidate = toWickCandidate(bars, i, wickThreshold, recoveryThreshold);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        if (candidates.isEmpty()) {
            sb.append("No wick-capture candidates matched this window.\n");
            sb.append("next_action: keep detector as shadow; do not loosen live strategy until enough candidates exist.\n");
            return sb.toString();
        }

        appendWickOutcomeSummary(sb, candidates);
        sb.append("\nRecent candidates:\n");
        candidates.stream()
                .sorted((a, b) -> b.openTime().compareTo(a.openTime()))
                .limit(maxRows)
                .forEach(c -> sb.append("  - ").append(c.oneLine()).append("\n"));

        sb.append("\nInterpretation:\n");
        sb.append("- This is shadow evidence only; candidates are not trade instructions.\n");
        sb.append("- A live rule would need enough samples, positive 4h/24h expectancy, and separate risk limits.\n");
        sb.append("- If sample size is small or outcomes are mixed, keep using it as a context tag for missed intrabar moves.\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Read-only Wick Capture threshold review. Compares 15m lower-wick/recovery thresholds "
            + "for sample count, 1h/4h/24h expectancy, MFE/MAE, concentration, recency, and returns a shadow-only verdict. "
            + "Does not create signals, orders, OCO, strategy/grid/fund/Earn changes, or Telegram. "
            + "params: symbol(default BTCUSDT), days(1~180 default 180)")
    public String reviewWickCaptureThresholds(String symbol, Integer days) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        int lookbackDays = days == null || days <= 0 ? 180 : Math.min(days, 180);
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays);
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC).plusHours(24);
        WickBarLoad load = loadWickBars(sym, start, end);
        List<WickBar> bars = load.bars();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Wick Capture Threshold Review v1 ===\n")
                .append("boundary=READ_ONLY; no signal/order/OCO/strategy/grid/fund/Earn/Telegram behavior changed.\n")
                .append("symbol=").append(sym).append(" days=").append(lookbackDays).append("\n")
                .append("source=").append(load.sourceLabel()).append("\n")
                .append("policy=current detector stays shadow-only; this tool does not retune or enable trading.\n\n");
        if (bars.isEmpty()) {
            sb.append("verdict=REJECT_WICK_CAPTURE\n");
            sb.append("reason=no OKX 15m rows or aggregatable 1m rows available for review.\n");
            return sb.toString();
        }

        double[][] thresholds = {
                {0.30, 0.20},
                {0.35, 0.20},
                {0.40, 0.20},
                {0.45, 0.20},
                {0.35, 0.30}
        };
        List<WickThresholdReview> reviews = new ArrayList<>();
        for (double[] t : thresholds) {
            List<WickCandidate> candidates = scanWickCandidates(bars, start, t[0], t[1]);
            reviews.add(buildWickThresholdReview(t[0], t[1], candidates));
        }

        WickThresholdReview current = reviews.stream()
                .filter(r -> nearlyEqual(r.wickThreshold(), 0.35) && nearlyEqual(r.recoveryThreshold(), 0.20))
                .findFirst()
                .orElse(reviews.get(0));
        WickThresholdReview bestAlternate = reviews.stream()
                .filter(r -> !(nearlyEqual(r.wickThreshold(), 0.35) && nearlyEqual(r.recoveryThreshold(), 0.20)))
                .filter(this::passesShadowRetuneQuality)
                .max((a, b) -> Double.compare(scoreThresholdReview(a), scoreThresholdReview(b)))
                .orElse(null);
        String verdict = wickThresholdReviewVerdict(current, bestAlternate);

        sb.append("verdict=").append(verdict).append("\n");
        sb.append("currentThreshold=wick>=0.35% recovery>=0.20%\n");
        sb.append("currentStatus=").append(current.status()).append("\n");
        if (bestAlternate != null) {
            sb.append("bestRetuneCandidate=wick>=").append(fmtPct(bestAlternate.wickThreshold()))
                    .append(" recovery>=").append(fmtPct(bestAlternate.recoveryThreshold()))
                    .append(" status=").append(bestAlternate.status()).append("\n");
        } else {
            sb.append("bestRetuneCandidate=N/A\n");
        }
        sb.append("recommendedAction=").append(wickThresholdReviewAction(verdict)).append("\n\n");

        sb.append("threshold matrix:\n");
        for (WickThresholdReview r : reviews) {
            sb.append("  - wick>=").append(fmtPct(r.wickThreshold()))
                    .append(" recovery>=").append(fmtPct(r.recoveryThreshold()))
                    .append(" samples=").append(r.sampleCount())
                    .append(" mature24h=").append(r.stats24h().n())
                    .append(" 1h[win=").append(fmtPct(r.stats1h().winRatePct()))
                    .append(" avg=").append(fmtPct(r.stats1h().avgPct()))
                    .append("]")
                    .append(" 4h[win=").append(fmtPct(r.stats4h().winRatePct()))
                    .append(" avg=").append(fmtPct(r.stats4h().avgPct()))
                    .append("]")
                    .append(" 24h[win=").append(fmtPct(r.stats24h().winRatePct()))
                    .append(" avg=").append(fmtPct(r.stats24h().avgPct()))
                    .append(" best=").append(fmtPct(r.stats24h().bestPct()))
                    .append(" worst=").append(fmtPct(r.stats24h().worstPct()))
                    .append("]")
                    .append(" mfeMae[avgMFE=").append(fmtPct(r.avgMfe24hPct()))
                    .append(" avgMAE=").append(fmtPct(r.avgMae24hPct()))
                    .append(" worstMAE=").append(fmtPct(r.worstMae24hPct()))
                    .append("]")
                    .append(" days=").append(r.distinctCandidateDays())
                    .append(" topDay=").append(r.topDaySampleCount())
                    .append(" latest=").append(r.latestCandidateAt() == null ? "N/A" : r.latestCandidateAt())
                    .append(" status=").append(r.status())
                    .append("\n");
        }

        sb.append("\nacceptance rule used by this review:\n");
        sb.append("- RETUNE shadow candidate requires samples>=20, 24h win>=55%, avg24h>0, avgMAE>-1.5%, and no severe sample concentration.\n");
        sb.append("- REVIEW_SMALL_LIVE requires the current threshold itself to pass those checks; this tool still does not approve live trading.\n");
        sb.append("- Any retune/live move must be a separate patch/issue and remain bounded by existing trading safety gates.\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "Read-only report for persisted wick-capture shadow candidates from bt_wick_capture_shadow. "
            + "Summarizes OPEN/CLOSED counts and recent rows with 1h/4h/24h + MFE/MAE outcomes. "
            + "Does not create signals, orders, OCO, or strategy/fund changes. params: symbol(optional), days(1~180 default 14), limit(1~50 default 20)")
    public String getWickCaptureShadowStatus(String symbol, Integer days, Integer limit) {
        String sym = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        int lookback = days == null || days <= 0 ? 14 : Math.min(days, 180);
        int maxRows = limit == null || limit <= 0 ? 20 : Math.min(limit, 50);
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(lookback);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Wick Capture Shadow Status ===\n")
                .append("window: last ").append(lookback).append(" day(s)")
                .append(" since ").append(since).append(" UTC\n")
                .append("mode: READ_ONLY\n");
        if (sym != null) sb.append("symbol filter: ").append(sym).append("\n");
        sb.append("\n");

        try {
            String countSql = """
                    SELECT COUNT(*) AS total,
                           SUM(CASE WHEN status='OPEN' THEN 1 ELSE 0 END) AS open_cnt,
                           SUM(CASE WHEN status='CLOSED' THEN 1 ELSE 0 END) AS closed_cnt
                    FROM bt_wick_capture_shadow
                    WHERE bar_open_time >= ?
                    """ + (sym == null ? "" : " AND symbol = ?");
            List<Map<String, Object>> countRows = sym == null
                    ? jdbc.queryForList(countSql, since)
                    : jdbc.queryForList(countSql, since, sym);
            Map<String, Object> c = countRows.isEmpty() ? Map.of() : countRows.get(0);
            int total = asInt(c.get("total"));
            int openCnt = asInt(c.get("open_cnt"));
            int closedCnt = asInt(c.get("closed_cnt"));
            sb.append(String.format("summary: total=%d open=%d closed=%d\n\n", total, openCnt, closedCnt));
        } catch (Exception e) {
            return sb.append("❌ count query failed: ").append(e.getMessage()).toString();
        }

        try {
            String aggregateSql = """
                    SELECT COUNT(*) AS sample_count,
                           SUM(CASE WHEN ret_1h_pct IS NOT NULL THEN 1 ELSE 0 END) AS sample_1h,
                           SUM(CASE WHEN ret_1h_pct > 0 THEN 1 ELSE 0 END) AS wins_1h,
                           AVG(ret_1h_pct) AS avg_1h,
                           SUM(CASE WHEN ret_4h_pct IS NOT NULL THEN 1 ELSE 0 END) AS sample_4h,
                           SUM(CASE WHEN ret_4h_pct > 0 THEN 1 ELSE 0 END) AS wins_4h,
                           AVG(ret_4h_pct) AS avg_4h,
                           SUM(CASE WHEN ret_24h_pct IS NOT NULL THEN 1 ELSE 0 END) AS sample_24h,
                           SUM(CASE WHEN ret_24h_pct > 0 THEN 1 ELSE 0 END) AS wins_24h,
                           AVG(ret_24h_pct) AS avg_24h,
                           AVG(mfe_24h_pct) AS avg_mfe,
                           AVG(mae_24h_pct) AS avg_mae
                    FROM bt_wick_capture_shadow
                    WHERE bar_open_time >= ?
                    """ + (sym == null ? "" : " AND symbol = ?");
            List<Map<String, Object>> aggregateRows = sym == null
                    ? jdbc.queryForList(aggregateSql, since)
                    : jdbc.queryForList(aggregateSql, since, sym);
            Map<String, Object> aggregate = aggregateRows.isEmpty() ? Map.of() : aggregateRows.get(0);
            appendWickReadiness(sb, aggregate);
            appendWickReadinessDiagnostics(sb, sym, since, lookback, aggregate);

            String dailySql = """
                    SELECT DATE(bar_open_time) AS event_day,
                           COUNT(*) AS cnt,
                           AVG(ret_1h_pct) AS avg_1h,
                           AVG(ret_4h_pct) AS avg_4h,
                           AVG(ret_24h_pct) AS avg_24h,
                           AVG(mfe_24h_pct) AS avg_mfe,
                           AVG(mae_24h_pct) AS avg_mae
                    FROM bt_wick_capture_shadow
                    WHERE bar_open_time >= ?
                    """ + (sym == null ? "" : " AND symbol = ?") + """
                    GROUP BY DATE(bar_open_time)
                    ORDER BY event_day DESC
                    LIMIT 7
                    """;
            List<Map<String, Object>> dailyRows = sym == null
                    ? jdbc.queryForList(dailySql, since)
                    : jdbc.queryForList(dailySql, since, sym);
            if (!dailyRows.isEmpty()) {
                sb.append("daily aggregate:\n");
                for (Map<String, Object> r : dailyRows) {
                    sb.append("  - ")
                            .append(stringVal(r.get("event_day")))
                            .append(" n=").append(asInt(r.get("cnt")))
                            .append(" avg1h=").append(fmtPct(asDoubleObj(r.get("avg_1h"))))
                            .append(" avg4h=").append(fmtPct(asDoubleObj(r.get("avg_4h"))))
                            .append(" avg24h=").append(fmtPct(asDoubleObj(r.get("avg_24h"))))
                            .append(" avgMFE=").append(fmtPct(asDoubleObj(r.get("avg_mfe"))))
                            .append(" avgMAE=").append(fmtPct(asDoubleObj(r.get("avg_mae"))))
                            .append("\n");
                }
                sb.append("\n");
            }

            String rowSql = """
                    SELECT symbol, bar_open_time, lower_wick_pct, recovery_pct, range_pct,
                           ret_1h_pct, ret_4h_pct, ret_24h_pct, mfe_24h_pct, mae_24h_pct,
                           status, tg_notified_at
                    FROM bt_wick_capture_shadow
                    WHERE bar_open_time >= ?
                    """ + (sym == null ? "" : " AND symbol = ?") + """
                    ORDER BY bar_open_time DESC
                    LIMIT ?
                    """;
            List<Map<String, Object>> rows = sym == null
                    ? jdbc.queryForList(rowSql, since, maxRows)
                    : jdbc.queryForList(rowSql, since, sym, maxRows);
            if (rows.isEmpty()) {
                sb.append("no shadow rows in this window.\n");
                return sb.toString();
            }
            sb.append("recent rows:\n");
            for (Map<String, Object> r : rows) {
                sb.append("  - ")
                        .append(stringVal(r.get("symbol"))).append(" ")
                        .append(fmtTaipeiFromUtc(asDateTime(r.get("bar_open_time")))).append(" Taipei")
                        .append(" | wick=").append(fmtPct(asDouble(r.get("lower_wick_pct"))))
                        .append(" recovery=").append(fmtPct(asDouble(r.get("recovery_pct"))))
                        .append(" range=").append(fmtPct(asDouble(r.get("range_pct"))))
                        .append(" | 1h=").append(fmtPct(asDoubleObj(r.get("ret_1h_pct"))))
                        .append(" 4h=").append(fmtPct(asDoubleObj(r.get("ret_4h_pct"))))
                        .append(" 24h=").append(fmtPct(asDoubleObj(r.get("ret_24h_pct"))))
                        .append(" | MFE=").append(fmtPct(asDoubleObj(r.get("mfe_24h_pct"))))
                        .append(" MAE=").append(fmtPct(asDoubleObj(r.get("mae_24h_pct"))))
                        .append(" | status=").append(stringVal(r.get("status")))
                        .append(" notified=").append(asDateTime(r.get("tg_notified_at")) != null ? "Y" : "N")
                        .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return sb.append("❌ detail query failed: ").append(e.getMessage()).toString();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "Read-only runtime acceptance report for preTradeExpectedValueGate. "
            + "Returns candidate counts since a UTC timestamp or recent days window, pass/block split, expectedR stats, blocked audit ids, "
            + "whether blocked rows accidentally produced orders, and whether notify-only/shadow paths were affected. "
            + "params: sinceUtc(optional ISO-8601 UTC), days(optional default 1), symbol(optional), strategyId(optional)")
    public String getExpectedValueGateStats(String sinceUtc, Integer days, String symbol, Long strategyId) {
        LocalDateTime since = parseUtcOrNull(sinceUtc);
        if (since == null) {
            int d = days == null || days <= 0 ? 1 : Math.min(days, 30);
            since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        }
        String sym = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();

        StringBuilder where = new StringBuilder(" a.event_time >= ? ");
        List<Object> params = new ArrayList<>();
        params.add(since);
        if (sym != null) {
            where.append(" AND a.symbol = ? ");
            params.add(sym);
        }
        if (strategyId != null && strategyId > 0) {
            where.append(" AND a.strategy_id = ? ");
            params.add(strategyId);
        }

        String blockedWhere = where + " AND a.event_type='FILTER_BLOCK' AND a.blocker='ExpectedValueGate' ";
        String passWhere = where + " AND a.event_type='ATTENTION_HIT' AND a.reason LIKE 'ExpectedValueGatePass%' ";

        int blocked = asInt(jdbc.queryForObject("SELECT COUNT(*) FROM bt_decision_audit a WHERE " + blockedWhere, Integer.class, params.toArray()));
        int passed = asInt(jdbc.queryForObject("SELECT COUNT(*) FROM bt_decision_audit a WHERE " + passWhere, Integer.class, params.toArray()));
        int candidates = blocked + passed;

        List<Map<String, Object>> expectedRows = new ArrayList<>();
        expectedRows.addAll(jdbc.queryForList(
                "SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json,'$.expected_r')) AS DECIMAL(10,6)) AS expected_r, " +
                        "CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json,'$.min_expected_r')) AS DECIMAL(10,6)) AS min_expected_r " +
                        "FROM bt_decision_audit a WHERE " + blockedWhere,
                params.toArray()));
        expectedRows.addAll(jdbc.queryForList(
                "SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json,'$.expected_r')) AS DECIMAL(10,6)) AS expected_r, " +
                        "CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json,'$.min_expected_r')) AS DECIMAL(10,6)) AS min_expected_r " +
                        "FROM bt_decision_audit a WHERE " + passWhere,
                params.toArray()));

        double avgExpectedR = expectedRows.stream()
                .map(r -> asDoubleObj(r.get("expected_r")))
                .filter(v -> v != null)
                .mapToDouble(Double::doubleValue)
                .average().orElse(Double.NaN);
        Double minThreshold = expectedRows.stream()
                .map(r -> asDoubleObj(r.get("min_expected_r")))
                .filter(v -> v != null)
                .findFirst().orElse(null);

        List<Map<String, Object>> blockedAuditRows = jdbc.queryForList(
                "SELECT a.id, a.live_signal_id, a.strategy_id, a.symbol, a.interval_code, " +
                        "CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json,'$.expected_r')) AS DECIMAL(10,6)) AS expected_r " +
                        "FROM bt_decision_audit a WHERE " + blockedWhere + " ORDER BY a.event_time DESC LIMIT 20",
                params.toArray());
        List<Long> blockedAuditIds = blockedAuditRows.stream()
                .map(r -> asLong(r.get("id")))
                .filter(id -> id != null)
                .toList();

        long blockedWithOrder = blockedAuditRows.stream()
                .map(r -> asLong(r.get("live_signal_id")))
                .filter(id -> id != null && id > 0)
                .map(id -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM bt_live_signal WHERE id=? AND COALESCE(auto_traded,0)=1",
                        Integer.class, id))
                .filter(cnt -> cnt != null && cnt > 0)
                .count();

        int blockedOnNotifyOnly = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bt_decision_audit a " +
                        "JOIN bt_strategy s ON s.id=a.strategy_id " +
                        "WHERE " + blockedWhere +
                        " AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(s.config_json,'$.notifyOnly')),'false') IN ('true','1')",
                Integer.class, params.toArray());

        StringBuilder sb = new StringBuilder();
        sb.append("=== ExpectedValueGate Runtime Stats ===\n");
        sb.append("sinceUtc: ").append(since).append(" (UTC)\n");
        if (sym != null) sb.append("symbol: ").append(sym).append("\n");
        if (strategyId != null) sb.append("strategyId: ").append(strategyId).append("\n");
        sb.append("\n");
        sb.append("sinceDeployCandidates=").append(candidates).append("\n");
        sb.append("passedExpectedValueGate=").append(passed).append("\n");
        sb.append("blockedByExpectedValueGate=").append(blocked).append("\n");
        sb.append("minExpectedR=").append(minThreshold != null ? String.format("%.4f", minThreshold) : "N/A").append("\n");
        sb.append("avgExpectedR=").append(Double.isNaN(avgExpectedR) ? "N/A" : String.format("%.4f", avgExpectedR)).append("\n");
        sb.append("blockedAuditIds=").append(blockedAuditIds).append("\n");
        sb.append("blockedCandidateProducedOrder=").append(blockedWithOrder > 0 ? "YES" : "NO").append("\n");
        sb.append("notifyOnlyOrShadowAffected=").append(blockedOnNotifyOnly > 0 ? "YES" : "NO").append("\n");
        sb.append("\n");
        if (candidates == 0) {
            sb.append("acceptance: PENDING_RUNTIME_SAMPLE (no ExpectedValueGate candidate sample yet)\n");
        } else if (blockedWithOrder > 0) {
            sb.append("acceptance: FAIL (blocked sample still produced order)\n");
        } else if (blocked == 0) {
            sb.append("acceptance: PASS_RUNTIME_SAMPLE (ExpectedValueGate pass sample observed without order side effects)\n");
        } else {
            sb.append("acceptance: PASS (runtime blocked sample observed without order side effects)\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "Read-only RCA for FILTER_BLOCK/DataFreshnessGuard rows. "
            + "Groups recent stale-kline blocks by strategy/interval/source and surfaces audit context fields. "
            + "No trading, strategy, OCO, grid, or fund behavior changed. "
            + "params: days(optional default 5), symbol(optional default BTCUSDT), limit(optional default 50)")
    public String diagnoseDataFreshnessGuardBlocks(Integer days, String symbol, Integer limit) {
        int d = days == null || days <= 0 ? 5 : Math.min(days, 30);
        int max = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    SELECT id, strategy_id, symbol, interval_code, bar_open_time, event_time,
                           reason, context_json
                    FROM bt_decision_audit
                    WHERE event_time >= ?
                      AND symbol = ?
                      AND event_type = 'FILTER_BLOCK'
                      AND blocker = 'DataFreshnessGuard'
                    ORDER BY event_time DESC
                    LIMIT ?
                    """, since, sym, max);
        } catch (Exception e) {
            log.warn("[diagnoseDataFreshnessGuardBlocks] query failed: {}", e.getMessage());
            return "❌ diagnoseDataFreshnessGuardBlocks failed: " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== DataFreshnessGuard RCA ===\n");
        sb.append("boundary: READ_ONLY; no trading, OCO, strategy, grid, or fund behavior changed.\n");
        sb.append("symbol: ").append(sym).append(" | days=").append(d).append(" | limit=").append(max).append("\n");
        sb.append("sinceUtc: ").append(since).append("\n\n");
        if (rows.isEmpty()) {
            sb.append("No FILTER_BLOCK/DataFreshnessGuard rows in window.\n");
            sb.append("acceptance: PASS_NO_CURRENT_SAMPLE\n");
            return sb.toString();
        }

        Map<String, DataFreshnessGroup> groups = new LinkedHashMap<>();
        List<String> samples = new ArrayList<>();
        int legacyContext = 0;
        int trueStale = 0;
        int review = 0;
        Set<String> klineKeys = new HashSet<>();

        for (Map<String, Object> row : rows) {
            JsonNode ctx = parseContext(row.get("context_json"));
            Long strategyId = asLong(row.get("strategy_id"));
            String interval = stringVal(row.get("interval_code"));
            String source = text(ctx, "kline_source", "unknown");
            Long staleMinutes = longFrom(ctx, "stale_minutes");
            Long threshold = longFrom(ctx, "threshold_minutes");
            if (threshold == null) {
                Long intervalMinutes = longFrom(ctx, "interval_minutes");
                threshold = intervalMinutes != null ? 2L * intervalMinutes + 15L : null;
            }
            String classification;
            if (staleMinutes != null && threshold != null && staleMinutes > threshold) {
                classification = "TRUE_STALE_KLINE";
                trueStale++;
            } else {
                classification = "CONTEXT_MISMATCH_REVIEW";
                review++;
            }
            if (ctx == null || ctx.isMissingNode() || ctx.isNull() || !ctx.has("now_utc")) {
                legacyContext++;
            }

            String key = strategyId + "|" + interval + "|" + source + "|" + classification;
            groups.computeIfAbsent(key, ignored -> new DataFreshnessGroup(strategyId, interval, source, classification))
                    .add(staleMinutes, threshold);
            klineKeys.add(interval + "|" + source);

            if (samples.size() < 12) {
                samples.add(String.format("- auditId=%s strategy=%s interval=%s class=%s stale=%s threshold=%s latestOpen=%s closeEstimate=%s nowUtc=%s source=%s reason=%s",
                        row.get("id"), strategyId, interval, classification,
                        staleMinutes != null ? staleMinutes : "N/A",
                        threshold != null ? threshold : "N/A",
                        text(ctx, "latest_bar_open", String.valueOf(row.get("bar_open_time"))),
                        text(ctx, "latest_bar_close_estimate", "N/A"),
                        text(ctx, "now_utc", String.valueOf(row.get("event_time"))),
                        source,
                        stringVal(row.get("reason"))));
            }
        }

        sb.append("Summary:\n");
        sb.append("  rows=").append(rows.size()).append("\n");
        sb.append("  trueStaleKline=").append(trueStale).append("\n");
        sb.append("  contextMismatchReview=").append(review).append("\n");
        sb.append("  legacyOrMissingContext=").append(legacyContext).append("\n\n");

        sb.append("Grouped blockers:\n");
        for (DataFreshnessGroup g : groups.values()) {
            sb.append(String.format("- strategy=%s interval=%s source=%s class=%s count=%d maxStale=%s maxThreshold=%s%n",
                    g.strategyId, g.interval, g.source, g.classification, g.count,
                    g.maxStale != null ? g.maxStale : "N/A",
                    g.maxThreshold != null ? g.maxThreshold : "N/A"));
        }

        sb.append("\nCurrent kline source snapshot:\n");
        for (String key : klineKeys) {
            String[] parts = key.split("\\|", -1);
            sb.append(loadKlineFreshnessLine(sym, parts[0], parts.length > 1 ? parts[1] : "unknown")).append("\n");
        }

        sb.append("\nRecent samples:\n");
        samples.forEach(s -> sb.append(s).append("\n"));
        sb.append("\nOperator conclusion: keep DataFreshnessGuard strict; fix collector/event cadence only if TRUE_STALE_KLINE rows persist after source snapshot confirms missing recent bars.\n");
        sb.append("acceptance: PASS_RCA_CLASSIFIED\n");
        return sb.toString();
    }

    private String loadKlineFreshnessLine(String symbol, String interval, String source) {
        if (interval == null || interval.isBlank()) interval = "unknown";
        String sql = """
                SELECT open_time
                FROM md_kline
                WHERE symbol = ? AND interval_code = ?
                """ + (!"unknown".equalsIgnoreCase(source) ? " AND source = ? " : " ") +
                "ORDER BY open_time DESC LIMIT 1";
        try {
            List<Map<String, Object>> rows = "unknown".equalsIgnoreCase(source)
                    ? jdbc.queryForList(sql, symbol, interval)
                    : jdbc.queryForList(sql, symbol, interval, source);
            if (rows.isEmpty()) {
                return "- " + symbol + "@" + interval + "/" + source + " latestOpen=N/A";
            }
            LocalDateTime latest = asDateTime(rows.get(0).get("open_time"));
            long ageMin = latest != null ? java.time.Duration.between(latest, LocalDateTime.now(ZoneOffset.UTC)).toMinutes() : -1;
            return "- " + symbol + "@" + interval + "/" + source + " latestOpen=" + latest + " ageMin=" + ageMin;
        } catch (Exception e) {
            return "- " + symbol + "@" + interval + "/" + source + " latestOpen=query_failed: " + e.getMessage();
        }
    }

    private JsonNode parseContext(Object raw) {
        if (raw == null) return null;
        try {
            return objectMapper.readTree(String.valueOf(raw));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.path(field).isNull()) return fallback;
        return node.path(field).asText(fallback);
    }

    private Long longFrom(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.path(field).isNull()) return null;
        if (node.path(field).isNumber()) return node.path(field).asLong();
        try {
            return Long.parseLong(node.path(field).asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static class DataFreshnessGroup {
        private final Long strategyId;
        private final String interval;
        private final String source;
        private final String classification;
        private int count;
        private Long maxStale;
        private Long maxThreshold;

        private DataFreshnessGroup(Long strategyId, String interval, String source, String classification) {
            this.strategyId = strategyId;
            this.interval = interval;
            this.source = source;
            this.classification = classification;
        }

        private void add(Long stale, Long threshold) {
            count++;
            if (stale != null && (maxStale == null || stale > maxStale)) maxStale = stale;
            if (threshold != null && (maxThreshold == null || threshold > maxThreshold)) maxThreshold = threshold;
        }
    }

    private List<BuyAuditCandidate> loadBuyAuditCandidates(String symbol, LocalDateTime start, LocalDateTime end) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT a.id, a.strategy_id, a.symbol, a.interval_code, a.bar_open_time, a.event_time,
                           a.outcome, a.reason, a.context_json, a.live_signal_id,
                           COALESCE(s.actual_entry_price, s.entry_price) AS entry_price,
                           s.suggested_tp, s.suggested_sl, s.nn_output
                    FROM (
                        SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(5000) */
                               id, strategy_id, symbol, interval_code, bar_open_time, event_time,
                               outcome, reason, context_json, live_signal_id
                        FROM bt_decision_audit FORCE INDEX (idx_audit_symbol_time)
                        WHERE symbol = ?
                          AND event_time >= ?
                          AND event_time < ?
                          AND event_type IN ('SIGNAL_EVAL','SIGNAL_BUY')
                          AND (UPPER(COALESCE(reason, '')) LIKE '%BUY%'
                               OR UPPER(COALESCE(outcome, '')) LIKE '%BUY%')
                        ORDER BY event_time ASC
                        LIMIT 200
                    ) a
                    LEFT JOIN bt_live_signal s ON s.id = a.live_signal_id
                    """, symbol, start, end);
            List<BuyAuditCandidate> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                out.add(new BuyAuditCandidate(
                        asLong(row.get("id")),
                        asLong(row.get("strategy_id")),
                        stringVal(row.get("symbol")),
                        stringVal(row.get("interval_code")),
                        asDateTime(row.get("bar_open_time")),
                        asDateTime(row.get("event_time")),
                        stringVal(row.get("outcome")),
                        stringVal(row.get("reason")),
                        stringVal(row.get("context_json")),
                        asLong(row.get("live_signal_id")),
                        asBigDecimal(row.get("entry_price")),
                        asBigDecimal(row.get("suggested_tp")),
                        asBigDecimal(row.get("suggested_sl")),
                        asBigDecimal(row.get("nn_output"))));
            }
            return out;
        } catch (Exception e) {
            log.warn("[analyzeMissedTradingOpportunities] failed to load BUY audit rows for {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }

    private List<AuditEvent> findRelatedExecutionAudits(BuyAuditCandidate buy, int minutesAfter) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT strategy_id, interval_code, bar_open_time, event_time,
                           event_type, outcome, blocker, reason, context_json,
                           ABS(TIMESTAMPDIFF(SECOND, event_time, ?)) AS distance_seconds
                    FROM bt_decision_audit
                    WHERE strategy_id = ?
                      AND symbol = ?
                      AND interval_code = ?
                      AND event_time BETWEEN ? AND ?
                      AND event_type IN ('ENTRY_SKIP','FILTER_BLOCK','AUTOTRADE_OK','AUTOTRADE_FAIL')
                    ORDER BY
                      CASE WHEN bar_open_time <=> ? THEN 0 ELSE 1 END,
                      distance_seconds ASC
                    LIMIT 5
                    """,
                    buy.eventTime(), buy.strategyId(), buy.symbol(), buy.interval(),
                    buy.eventTime().minusMinutes(5), buy.eventTime().plusMinutes(minutesAfter), buy.barOpenTime());
            List<AuditEvent> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                out.add(new AuditEvent(
                        asLong(row.get("strategy_id")),
                        stringVal(row.get("interval_code")),
                        asDateTime(row.get("bar_open_time")),
                        asDateTime(row.get("event_time")),
                        stringVal(row.get("event_type")),
                        stringVal(row.get("outcome")),
                        stringVal(row.get("blocker")),
                        stringVal(row.get("reason")),
                        stringVal(row.get("context_json")),
                        asLong(row.get("distance_seconds"))));
            }
            return out;
        } catch (Exception e) {
            log.warn("[analyzeMissedTradingOpportunities] related audit lookup failed for auditId={}: {}", buy.id(), e.getMessage());
            return List.of(new AuditEvent(buy.strategyId(), buy.interval(), buy.barOpenTime(), buy.eventTime(),
                    "AUDIT_QUERY_ERROR", "ERROR", "QUERY_ERROR", e.getMessage(), null, null));
        }
    }

    private void appendWickReadiness(StringBuilder sb, Map<String, Object> aggregate) {
        int sampleTarget = 20;
        int samples = asInt(aggregate.get("sample_count"));
        int sample1h = asInt(aggregate.get("sample_1h"));
        int wins1h = asInt(aggregate.get("wins_1h"));
        int sample4h = asInt(aggregate.get("sample_4h"));
        int wins4h = asInt(aggregate.get("wins_4h"));
        int sample24h = asInt(aggregate.get("sample_24h"));
        int wins24h = asInt(aggregate.get("wins_24h"));
        Double winRate1h = pctObj(wins1h, sample1h);
        Double winRate4h = pctObj(wins4h, sample4h);
        Double winRate24h = pctObj(wins24h, sample24h);
        Double avg24h = asDoubleObj(aggregate.get("avg_24h"));
        Double avgMae = asDoubleObj(aggregate.get("avg_mae"));
        boolean sampleReady = samples >= sampleTarget;
        boolean outcomeReady = sample24h >= sampleTarget;
        boolean expectancyReady = outcomeReady
                && winRate24h != null && winRate24h >= 55.0
                && avg24h != null && avg24h > 0
                && (avgMae == null || avgMae > -1.5);
        String status;
        String nextAction;
        if (samples <= 0) {
            status = "NO_PERSISTED_SAMPLES";
            nextAction = "Wait for bootstrap/live shadow detector to persist candidates.";
        } else if (!sampleReady) {
            status = "SAMPLE_INSUFFICIENT_N_LT_20";
            nextAction = "Keep shadow-only; need " + (sampleTarget - samples) + " more persisted sample(s).";
        } else if (!outcomeReady) {
            status = "FORWARD_24H_INCOMPLETE";
            nextAction = "Keep shadow-only until at least 20 candidates have mature 24h outcomes.";
        } else if (!expectancyReady) {
            status = "SHADOW_ONLY_EXPECTANCY_WEAK";
            nextAction = "Do not propose small-live; compare filters/regimes and wait for stronger risk-adjusted evidence.";
        } else {
            status = "REVIEW_READY_NOT_LIVE_APPROVED";
            nextAction = "Open a separate small-live proposal issue; this status tool never enables trading.";
        }

        sb.append("readiness:\n")
                .append("  sampleTarget=20\n")
                .append("  persistedSamples=").append(samples)
                .append(" sampleGap=").append(Math.max(0, sampleTarget - samples)).append("\n")
                .append("  1h: n=").append(sample1h).append(" winRate=").append(fmtPct(winRate1h))
                .append(" avg=").append(fmtPct(asDoubleObj(aggregate.get("avg_1h")))).append("\n")
                .append("  4h: n=").append(sample4h).append(" winRate=").append(fmtPct(winRate4h))
                .append(" avg=").append(fmtPct(asDoubleObj(aggregate.get("avg_4h")))).append("\n")
                .append("  24h: n=").append(sample24h).append(" winRate=").append(fmtPct(winRate24h))
                .append(" avg=").append(fmtPct(avg24h))
                .append(" avgMFE=").append(fmtPct(asDoubleObj(aggregate.get("avg_mfe"))))
                .append(" avgMAE=").append(fmtPct(avgMae)).append("\n")
                .append("  shadowAcceptanceStatus=").append(status).append("\n")
                .append("  smallLiveProposalPrereqMet=").append(expectancyReady).append("\n")
                .append("  liveTradingEnabledByThisTool=false\n")
                .append("  nextAction=").append(nextAction).append("\n\n");
    }

    private void appendWickReadinessDiagnostics(StringBuilder sb, String sym, LocalDateTime since,
                                                int lookbackDays, Map<String, Object> aggregate) {
        String diagSymbol = sym == null ? "BTCUSDT" : sym;
        int persistedSamples = asInt(aggregate.get("sample_count"));
        int sample24h = asInt(aggregate.get("sample_24h"));
        int wins24h = asInt(aggregate.get("wins_24h"));
        Double winRate24h = pctObj(wins24h, sample24h);
        Double avgMae = asDoubleObj(aggregate.get("avg_mae"));
        Double avg24h = asDoubleObj(aggregate.get("avg_24h"));

        WickPersistenceDiagnostics persistence = loadWickPersistenceDiagnostics(diagSymbol, since);
        LocalDateTime scanStart = LocalDateTime.now(ZoneOffset.UTC).minusDays(Math.max(1, Math.min(180, lookbackDays)));
        LocalDateTime scanEnd = LocalDateTime.now(ZoneOffset.UTC).plusHours(24);
        List<WickBar> bars = loadWickBars(diagSymbol, scanStart, scanEnd).bars();
        List<WickCandidate> directCandidates = scanWickCandidates(bars, scanStart, 0.35, 0.20);
        int directCount = directCandidates.size();
        boolean integrityPass = directCount == persistedSamples;

        sb.append("readiness diagnostics:\n")
                .append("  integritySymbol=").append(diagSymbol).append("\n")
                .append("  latestCandidateAt=").append(persistence.latestCandidateAt() == null ? "N/A" : persistence.latestCandidateAt()).append(" UTC\n")
                .append("  daysSinceLatestCandidate=").append(persistence.daysSinceLatestCandidate() == null ? "N/A" : persistence.daysSinceLatestCandidate()).append("\n")
                .append("  directScanCandidateCount=").append(directCount).append("\n")
                .append("  persistedCandidateCount=").append(persistedSamples).append("\n")
                .append("  persistenceIntegrity=").append(integrityPass ? "PASS" : "MISMATCH").append("\n")
                .append("  distinctCandidateDays=").append(persistence.distinctCandidateDays()).append("\n")
                .append("  topDaySampleCount=").append(persistence.topDaySampleCount()).append("\n")
                .append("  sampleConcentrationWarning=").append(sampleConcentrationWarning(persistedSamples, persistence)).append("\n")
                .append("  liveObserverLikelyHealthy=").append(integrityPass ? "LIKELY_TRUE" : "REVIEW_PERSISTENCE_MISMATCH").append("\n")
                .append("  notReadyReasons=").append(wickNotReadyReasons(persistedSamples, sample24h, winRate24h, avg24h, avgMae, persistence, integrityPass)).append("\n");

        appendWickThresholdSweep(sb, bars, scanStart);
        sb.append("\n");
    }

    private WickPersistenceDiagnostics loadWickPersistenceDiagnostics(String symbol, LocalDateTime since) {
        LocalDateTime latest = null;
        int distinctDays = 0;
        int topDayCount = 0;
        try {
            List<Map<String, Object>> latestRows = jdbc.queryForList("""
                    SELECT MAX(bar_open_time) AS latest_candidate_at,
                           COUNT(DISTINCT DATE(bar_open_time)) AS distinct_candidate_days
                    FROM bt_wick_capture_shadow
                    WHERE bar_open_time >= ?
                      AND symbol = ?
                    """, since, symbol);
            if (!latestRows.isEmpty()) {
                Map<String, Object> row = latestRows.get(0);
                latest = asDateTime(row.get("latest_candidate_at"));
                distinctDays = asInt(row.get("distinct_candidate_days"));
            }
            List<Map<String, Object>> topRows = jdbc.queryForList("""
                    SELECT COUNT(*) AS day_count
                    FROM bt_wick_capture_shadow
                    WHERE bar_open_time >= ?
                      AND symbol = ?
                    GROUP BY DATE(bar_open_time)
                    ORDER BY day_count DESC
                    LIMIT 1
                    """, since, symbol);
            if (!topRows.isEmpty()) {
                topDayCount = asInt(topRows.get(0).get("day_count"));
            }
        } catch (Exception e) {
            log.warn("[WickCaptureShadowStatus] persistence diagnostics failed for {}: {}", symbol, e.getMessage());
        }
        Long daysSinceLatest = latest == null
                ? null
                : ChronoUnit.DAYS.between(latest, LocalDateTime.now(ZoneOffset.UTC));
        return new WickPersistenceDiagnostics(latest, daysSinceLatest, distinctDays, topDayCount);
    }

    private String sampleConcentrationWarning(int samples, WickPersistenceDiagnostics d) {
        if (samples <= 0) return "NO_SAMPLES";
        if (d.distinctCandidateDays() <= 1 && samples >= 3) return "HIGH_SINGLE_DAY_CONCENTRATION";
        if (d.topDaySampleCount() > 0 && d.topDaySampleCount() * 100.0 / Math.max(1, samples) >= 50.0) {
            return "HIGH_TOP_DAY_CONCENTRATION";
        }
        return "LOW";
    }

    private String wickNotReadyReasons(int samples, int sample24h, Double winRate24h, Double avg24h,
                                       Double avgMae, WickPersistenceDiagnostics d, boolean integrityPass) {
        List<String> reasons = new ArrayList<>();
        if (!integrityPass) reasons.add("PERSISTENCE_MISMATCH");
        if (samples < 20) reasons.add("SAMPLE_INSUFFICIENT_N_LT_20");
        if (sample24h < 20) reasons.add("MATURE_24H_SAMPLE_INSUFFICIENT");
        if (winRate24h != null && winRate24h < 55.0) reasons.add("WATCH_24H_WIN_RATE_LT_55");
        if (avg24h != null && avg24h <= 0) reasons.add("AVG_24H_NOT_POSITIVE");
        if (avgMae != null && avgMae <= -1.5) reasons.add("AVG_MAE_AT_OR_BELOW_MINUS_1_5");
        if (d.daysSinceLatestCandidate() != null && d.daysSinceLatestCandidate() >= 14) {
            reasons.add("LOW_RECENT_CANDIDATE_RATE");
        }
        if (reasons.isEmpty()) reasons.add("REVIEW_READY_NOT_LIVE_APPROVED");
        return String.join(",", reasons);
    }

    private void appendWickThresholdSweep(StringBuilder sb, List<WickBar> bars, LocalDateTime start) {
        sb.append("  thresholdSweep:\n");
        double[][] thresholds = {
                {0.30, 0.20},
                {0.35, 0.20},
                {0.40, 0.20},
                {0.45, 0.20},
                {0.35, 0.30}
        };
        for (double[] pair : thresholds) {
            List<WickCandidate> candidates = scanWickCandidates(bars, start, pair[0], pair[1]);
            WickStats stats24h = wickStats(candidates.stream().map(WickCandidate::ret24hPct).toList());
            WickStats stats4h = wickStats(candidates.stream().map(WickCandidate::ret4hPct).toList());
            sb.append("    - wick>=").append(fmtPct(pair[0]))
                    .append(" recovery>=").append(fmtPct(pair[1]))
                    .append(" candidates=").append(candidates.size())
                    .append(" 4h[n=").append(stats4h.n())
                    .append(" win=").append(fmtPct(stats4h.winRatePct()))
                    .append(" avg=").append(fmtPct(stats4h.avgPct()))
                    .append("]")
                    .append(" 24h[n=").append(stats24h.n())
                    .append(" win=").append(fmtPct(stats24h.winRatePct()))
                    .append(" avg=").append(fmtPct(stats24h.avgPct()))
                    .append("]")
                    .append("\n");
        }
    }

    private WickThresholdReview buildWickThresholdReview(double wickThreshold, double recoveryThreshold,
                                                         List<WickCandidate> candidates) {
        WickStats stats1h = wickStats(candidates.stream().map(WickCandidate::ret1hPct).toList());
        WickStats stats4h = wickStats(candidates.stream().map(WickCandidate::ret4hPct).toList());
        WickStats stats24h = wickStats(candidates.stream().map(WickCandidate::ret24hPct).toList());
        List<Double> mfe = candidates.stream().map(WickCandidate::mfe24hPct).filter(v -> v != null && !v.isNaN()).toList();
        List<Double> mae = candidates.stream().map(WickCandidate::mae24hPct).filter(v -> v != null && !v.isNaN()).toList();
        double avgMfe = mfe.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double avgMae = mae.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double worstMae = mae.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
        Map<LocalDate, Integer> byDay = new LinkedHashMap<>();
        LocalDateTime latest = null;
        for (WickCandidate c : candidates) {
            if (c.openTime() == null) continue;
            LocalDate day = c.openTime().toLocalDate();
            byDay.put(day, byDay.getOrDefault(day, 0) + 1);
            if (latest == null || c.openTime().isAfter(latest)) latest = c.openTime();
        }
        int topDay = byDay.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        String status = thresholdStatus(candidates.size(), stats24h, avgMae, topDay);
        return new WickThresholdReview(
                wickThreshold,
                recoveryThreshold,
                candidates.size(),
                stats1h,
                stats4h,
                stats24h,
                Double.isNaN(avgMfe) ? null : avgMfe,
                Double.isNaN(avgMae) ? null : avgMae,
                Double.isNaN(worstMae) ? null : worstMae,
                byDay.size(),
                topDay,
                latest,
                status);
    }

    private String thresholdStatus(int samples, WickStats stats24h, Double avgMae, int topDaySampleCount) {
        if (samples < 20) return "SAMPLE_INSUFFICIENT";
        if (stats24h.n() < 20) return "MATURE_24H_SAMPLE_INSUFFICIENT";
        if (stats24h.winRatePct() == null || stats24h.winRatePct() < 55.0) return "WATCH_24H_WIN_RATE_LT_55";
        if (stats24h.avgPct() == null || stats24h.avgPct() <= 0) return "AVG_24H_NOT_POSITIVE";
        if (avgMae != null && avgMae <= -1.5) return "AVG_MAE_TOO_DEEP";
        if (topDaySampleCount > 0 && topDaySampleCount * 100.0 / Math.max(1, samples) >= 50.0) {
            return "SAMPLE_CONCENTRATION_REVIEW";
        }
        return "SHADOW_RETUNE_CANDIDATE";
    }

    private boolean passesShadowRetuneQuality(WickThresholdReview review) {
        return "SHADOW_RETUNE_CANDIDATE".equals(review.status());
    }

    private double scoreThresholdReview(WickThresholdReview review) {
        double win = review.stats24h().winRatePct() == null ? 0.0 : review.stats24h().winRatePct();
        double avg = review.stats24h().avgPct() == null ? 0.0 : review.stats24h().avgPct();
        double maePenalty = review.avgMae24hPct() == null ? 0.0 : Math.abs(Math.min(0.0, review.avgMae24hPct()));
        return review.sampleCount() * 0.1 + win + avg * 5.0 - maePenalty * 3.0;
    }

    private String wickThresholdReviewVerdict(WickThresholdReview current, WickThresholdReview bestAlternate) {
        if (passesShadowRetuneQuality(current)) return "REVIEW_SMALL_LIVE";
        if (bestAlternate != null) return "RETUNE_SHADOW_TO_" + thresholdSlug(bestAlternate);
        if (current.sampleCount() <= 0) return "REJECT_WICK_CAPTURE";
        if (current.sampleCount() < 20) return "KEEP_SHADOW_CURRENT";
        return "KEEP_SHADOW_CURRENT";
    }

    private String wickThresholdReviewAction(String verdict) {
        if (verdict.startsWith("RETUNE_SHADOW")) {
            return "Open a separate retune patch for shadow detector only; do not enable live trading.";
        }
        if ("REVIEW_SMALL_LIVE".equals(verdict)) {
            return "Open a separate small-live proposal issue; this tool does not approve or enable execution.";
        }
        if ("REJECT_WICK_CAPTURE".equals(verdict)) {
            return "Do not use Wick Capture as a trading input until data quality and samples improve.";
        }
        return "Keep current shadow detector and continue collecting samples.";
    }

    private String thresholdSlug(WickThresholdReview review) {
        return String.format("%.2f_RECOVERY_%.2f", review.wickThreshold(), review.recoveryThreshold())
                .replace(".", "_");
    }

    private boolean nearlyEqual(double a, double b) {
        return Math.abs(a - b) < 0.000001;
    }

    private List<WickCandidate> scanWickCandidates(String symbol, int lookbackDays,
                                                   double wickThreshold, double recoveryThreshold) {
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).minusDays(Math.max(1, Math.min(180, lookbackDays)));
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC).plusHours(24);
        List<WickBar> bars = loadWickBars(symbol, start, end).bars();
        return scanWickCandidates(bars, start, wickThreshold, recoveryThreshold);
    }

    private List<WickCandidate> scanWickCandidates(List<WickBar> bars, LocalDateTime start,
                                                   double wickThreshold, double recoveryThreshold) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<WickCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            WickBar bar = bars.get(i);
            if (bar.openTime() == null || bar.openTime().isBefore(start) || bar.openTime().isAfter(now)) {
                continue;
            }
            WickCandidate candidate = toWickCandidate(bars, i, wickThreshold, recoveryThreshold);
            if (candidate != null) candidates.add(candidate);
        }
        return candidates;
    }

    private WickStats wickStats(List<Double> values) {
        List<Double> done = values.stream().filter(v -> v != null && !v.isNaN()).toList();
        if (done.isEmpty()) {
            return new WickStats(0, null, null, null, null);
        }
        long wins = done.stream().filter(v -> v > 0).count();
        double avg = done.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double best = done.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double worst = done.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        return new WickStats(done.size(), wins * 100.0 / done.size(), avg, best, worst);
    }

    private ForwardWindow loadForwardWindow(String symbol, LocalDateTime eventTime, int hoursAhead) {
        if (eventTime == null) {
            return ForwardWindow.empty();
        }
        try {
            List<Map<String, Object>> entryRows = jdbc.queryForList("""
                    SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ open_time, close_price
                    FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open)
                    WHERE symbol = ? AND interval_code = '1m' AND source = 'okx'
                      AND open_time >= ?
                    ORDER BY open_time ASC
                    LIMIT 1
                    """, symbol, eventTime);
            if (entryRows.isEmpty()) {
                return ForwardWindow.empty();
            }
            LocalDateTime entryTime = asDateTime(entryRows.get(0).get("open_time"));
            BigDecimal entryPrice = asBigDecimal(entryRows.get(0).get("close_price"));
            List<Map<String, Object>> forwardRows = jdbc.queryForList("""
                    SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ MAX(high_price) AS max_high, MIN(low_price) AS min_low
                    FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open)
                    WHERE symbol = ? AND interval_code = '1m' AND source = 'okx'
                      AND open_time > ?
                      AND open_time <= ?
                    """, symbol, entryTime, entryTime.plusHours(hoursAhead));
            if (forwardRows.isEmpty()) {
                return new ForwardWindow(entryTime, entryPrice, null, null, null, null);
            }
            BigDecimal maxHigh = asBigDecimal(forwardRows.get(0).get("max_high"));
            BigDecimal minLow = asBigDecimal(forwardRows.get(0).get("min_low"));
            Double maxUp = entryPrice != null && maxHigh != null ? pct(maxHigh.subtract(entryPrice), entryPrice) : null;
            Double maxDown = entryPrice != null && minLow != null ? pct(minLow.subtract(entryPrice), entryPrice) : null;
            return new ForwardWindow(entryTime, entryPrice, maxHigh, minLow, maxUp, maxDown);
        } catch (Exception e) {
            log.warn("[analyzeMissedTradingOpportunities] forward window lookup failed near {}: {}", eventTime, e.getMessage());
            return ForwardWindow.empty();
        }
    }

    private MissedOpportunityRow classifyMissedOpportunity(BuyAuditCandidate buy, List<AuditEvent> related,
                                                           ForwardWindow forward, double threshold,
                                                           Double adjustedAvailable) {
        boolean executed = related.stream().anyMatch(e -> "AUTOTRADE_OK".equals(e.eventType()));
        boolean dedup = related.stream().anyMatch(AuditEvent::isDedupSkip);
        boolean filterBlock = related.stream().anyMatch(e -> "FILTER_BLOCK".equals(e.eventType()));
        boolean hasUpside = forward.maxUpPct() != null && forward.maxUpPct() >= threshold;

        String classification;
        String reason;
        if (executed) {
            classification = "EXECUTED";
            reason = "AUTOTRADE_OK correlated; not missed.";
        } else if (dedup) {
            classification = "BLOCKED_BUT_CORRECT";
            reason = "EntryDedup/existing exposure guard blocked additional risk.";
        } else if (filterBlock && hasUpside) {
            classification = "FILTER_BLOCK_REVIEW";
            reason = "Filter blocked a BUY row with forward upside; needs blocked-signal outcome review.";
        } else if (hasUpside) {
            classification = "MISSED_CANDIDATE";
            reason = "BUY row had forward upside and no correlated execution/blocker.";
        } else {
            classification = "LATE_OR_LOW_EDGE";
            reason = "Forward upside did not clear threshold.";
        }

        com.agora.service.trading.PositionSizingService.PositionSizingDecision sizing = null;
        BigDecimal entry = buy.entryPrice() != null ? buy.entryPrice() : forward.entryPrice();
        if (entry != null && buy.suggestedTp() != null && buy.suggestedSl() != null) {
            sizing = positionSizingService.calculate(
                    buy.symbol(),
                    buy.strategyId(),
                    entry,
                    buy.suggestedTp(),
                    buy.suggestedSl(),
                    buy.nnOutput() != null ? buy.nnOutput().doubleValue() : 0.85,
                    50.0,
                    adjustedAvailable);
        }

        return new MissedOpportunityRow(buy, related, forward, classification, reason, sizing);
    }

    private WickBarLoad loadWickBars(String symbol, LocalDateTime start, LocalDateTime end) {
        List<WickBar> direct15m = loadWickBars(symbol, "15m", start, end);
        if (!direct15m.isEmpty()) {
            return new WickBarLoad(direct15m, "md_kline OKX 15m");
        }
        List<WickBar> oneMinute = loadWickBars(symbol, "1m", start, end);
        List<WickBar> aggregated = aggregateTo15m(oneMinute);
        return new WickBarLoad(aggregated, "md_kline OKX 1m aggregated to 15m");
    }

    private List<WickBar> loadWickBars(String symbol, String intervalCode, LocalDateTime start, LocalDateTime end) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ open_time, open_price, high_price, low_price, close_price, volume, source
                    FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open)
                    WHERE symbol = ? AND interval_code = ? AND source = 'okx'
                      AND open_time >= ? AND open_time <= ?
                    ORDER BY open_time ASC
                    """, symbol, intervalCode, start, end);
            List<WickBar> bars = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                bars.add(new WickBar(
                        asDateTime(row.get("open_time")),
                        asBigDecimal(row.get("open_price")),
                        asBigDecimal(row.get("high_price")),
                        asBigDecimal(row.get("low_price")),
                        asBigDecimal(row.get("close_price")),
                        asBigDecimal(row.get("volume")),
                        stringVal(row.get("source"))));
            }
            return bars;
        } catch (Exception e) {
            log.warn("[analyzeWickCaptureShadow] failed to load {} bars for {}: {}", intervalCode, symbol, e.getMessage());
            return List.of();
        }
    }

    private List<WickBar> aggregateTo15m(List<WickBar> oneMinute) {
        if (oneMinute == null || oneMinute.isEmpty()) {
            return List.of();
        }
        List<WickBar> aggregated = new ArrayList<>();
        LocalDateTime bucketStart = null;
        WickBar first = null;
        WickBar last = null;
        BigDecimal high = null;
        BigDecimal low = null;
        BigDecimal volume = BigDecimal.ZERO;
        for (WickBar bar : oneMinute) {
            LocalDateTime bucket = bar.openTime()
                    .withMinute((bar.openTime().getMinute() / 15) * 15)
                    .withSecond(0)
                    .withNano(0);
            if (bucketStart != null && !bucketStart.equals(bucket)) {
                aggregated.add(new WickBar(bucketStart, first.openPrice(), high, low, last.closePrice(), volume, "okx:1m_agg_15m"));
                first = null;
                last = null;
                high = null;
                low = null;
                volume = BigDecimal.ZERO;
            }
            bucketStart = bucket;
            if (first == null) {
                first = bar;
            }
            last = bar;
            high = high == null ? bar.highPrice() : high.max(bar.highPrice());
            low = low == null ? bar.lowPrice() : low.min(bar.lowPrice());
            if (bar.volume() != null) {
                volume = volume.add(bar.volume());
            }
        }
        if (bucketStart != null && first != null && last != null) {
            aggregated.add(new WickBar(bucketStart, first.openPrice(), high, low, last.closePrice(), volume, "okx:1m_agg_15m"));
        }
        return aggregated;
    }

    private WickCandidate toWickCandidate(List<WickBar> bars, int index,
                                          double wickThreshold, double recoveryThreshold) {
        WickBar bar = bars.get(index);
        if (bar.openPrice() == null || bar.highPrice() == null || bar.lowPrice() == null || bar.closePrice() == null) {
            return null;
        }
        if (bar.lowPrice().signum() <= 0) {
            return null;
        }
        BigDecimal bodyLow = bar.openPrice().min(bar.closePrice());
        double lowerWickPct = pct(bodyLow.subtract(bar.lowPrice()), bar.lowPrice());
        double recoveryPct = pct(bar.closePrice().subtract(bar.lowPrice()), bar.lowPrice());
        double rangePct = pct(bar.highPrice().subtract(bar.lowPrice()), bar.lowPrice());
        if (lowerWickPct < wickThreshold || recoveryPct < recoveryThreshold) {
            return null;
        }
        Double ret1h = forwardReturnPct(bars, index, 4);
        Double ret4h = forwardReturnPct(bars, index, 16);
        Double ret24h = forwardReturnPct(bars, index, 96);
        Double mfe24h = forwardExcursionPct(bars, index, 96, true);
        Double mae24h = forwardExcursionPct(bars, index, 96, false);
        return new WickCandidate(
                bar.openTime(), bar.lowPrice(), bar.closePrice(),
                lowerWickPct, recoveryPct, rangePct, ret1h, ret4h, ret24h, mfe24h, mae24h);
    }

    private Double forwardReturnPct(List<WickBar> bars, int index, int barsAhead) {
        int target = index + barsAhead;
        if (target >= bars.size()) {
            return null;
        }
        BigDecimal entry = bars.get(index).closePrice();
        BigDecimal exit = bars.get(target).closePrice();
        if (entry == null || exit == null || entry.signum() <= 0) {
            return null;
        }
        return pct(exit.subtract(entry), entry);
    }

    private Double forwardExcursionPct(List<WickBar> bars, int index, int barsAhead, boolean favorable) {
        int target = index + barsAhead;
        if (target >= bars.size()) {
            return null;
        }
        BigDecimal entry = bars.get(index).closePrice();
        if (entry == null || entry.signum() <= 0) {
            return null;
        }
        BigDecimal extreme = null;
        for (int i = index + 1; i <= target; i++) {
            WickBar bar = bars.get(i);
            BigDecimal value = favorable ? bar.highPrice() : bar.lowPrice();
            if (value == null) continue;
            extreme = extreme == null
                    ? value
                    : favorable ? extreme.max(value) : extreme.min(value);
        }
        if (extreme == null) return null;
        return pct(extreme.subtract(entry), entry);
    }

    private void appendWickOutcomeSummary(StringBuilder sb, List<WickCandidate> candidates) {
        sb.append("Summary:\n");
        sb.append("  candidates: ").append(candidates.size()).append("\n");
        appendHorizonStats(sb, "1h", candidates.stream().map(WickCandidate::ret1hPct).toList());
        appendHorizonStats(sb, "4h", candidates.stream().map(WickCandidate::ret4hPct).toList());
        appendHorizonStats(sb, "24h", candidates.stream().map(WickCandidate::ret24hPct).toList());
    }

    private void appendHorizonStats(StringBuilder sb, String label, List<Double> values) {
        List<Double> done = values.stream().filter(v -> v != null && !v.isNaN()).toList();
        if (done.isEmpty()) {
            sb.append("  ").append(label).append(": no completed horizon yet\n");
            return;
        }
        long wins = done.stream().filter(v -> v > 0).count();
        double avg = done.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double best = done.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double worst = done.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        sb.append(String.format("  %s: n=%d winRate=%.0f%% avg=%s best=%s worst=%s%n",
                label, done.size(), wins * 100.0 / done.size(), fmtPct(avg), fmtPct(best), fmtPct(worst)));
    }

    private KlineExtreme findExtreme(String symbol, LocalDateTime startUtc, LocalDateTime endUtc, boolean low) {
        try {
            String priceColumn = low ? "low_price" : "high_price";
            String direction = low ? "ASC" : "DESC";
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ open_time, high_price, low_price, close_price, source " +
                    "FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open) " +
                    "WHERE symbol = ? AND interval_code = '1m' AND source = 'okx' " +
                    "  AND open_time >= ? AND open_time < ? " +
                    "ORDER BY " + priceColumn + " " + direction + ", open_time ASC LIMIT 1",
                    symbol, startUtc, endUtc);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            return new KlineExtreme(
                    asDateTime(row.get("open_time")),
                    asBigDecimal(row.get("low_price")),
                    asBigDecimal(row.get("high_price")),
                    asBigDecimal(row.get("close_price")),
                    stringVal(row.get("source")));
        } catch (Exception e) {
            log.warn("[getIntradayExtremesDigest] failed to load {} extreme for {}: {}",
                    low ? "low" : "high", symbol, e.getMessage());
            return null;
        }
    }

    private void appendExtreme(StringBuilder sb, String label, KlineExtreme extreme, String symbol) {
        sb.append("=== ").append(label).append(" ===\n");
        if (extreme == null) {
            sb.append("  unavailable: no OKX 1m row found.\n\n");
            return;
        }
        sb.append(String.format("  time: %s Taipei (%s UTC)%n",
                fmtTaipeiFromUtc(extreme.openTime()), fmtUtc(extreme.openTime())));
        sb.append(String.format("  price: low=%s high=%s close=%s source=%s%n",
                plain(extreme.lowPrice()), plain(extreme.highPrice()), plain(extreme.closePrice()), extreme.source()));
        List<AuditEvent> events = findAuditEventsNear(symbol, extreme.openTime(), 120);
        if (events.isEmpty()) {
            sb.append("  nearby_audit: none within +/-120 minutes.\n");
            sb.append("  interpretation: likely intrabar movement was not represented by a nearby strategy audit row; review bar-close cadence before calling it a missed trade.\n\n");
            return;
        }
        sb.append("  nearby_audit_summary: ").append(summarizeAuditEvents(events)).append("\n");
        sb.append("  nearby_audit_rows:\n");
        for (AuditEvent event : events) {
            sb.append("    - ").append(event.oneLine()).append("\n");
        }
        sb.append("  interpretation: ").append(explainExtreme(label, events)).append("\n\n");
    }

    private List<AuditEvent> findAuditEventsNear(String symbol, LocalDateTime extremeUtc, int minutes) {
        try {
            LocalDateTime from = extremeUtc.minusMinutes(minutes);
            LocalDateTime to = extremeUtc.plusMinutes(minutes);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT strategy_id, symbol, interval_code, bar_open_time, event_time,
                           event_type, outcome, blocker, reason, context_json,
                           live_signal_id,
                           ABS(TIMESTAMPDIFF(SECOND, event_time, ?)) AS distance_seconds
                    FROM bt_decision_audit
                    WHERE symbol = ?
                      AND event_time BETWEEN ? AND ?
                      AND event_type IN ('SIGNAL_EVAL','SIGNAL_BUY','SIGNAL_SELL','FILTER_BLOCK','ENTRY_SKIP','AUTOTRADE_OK','AUTOTRADE_FAIL')
                    ORDER BY distance_seconds ASC, event_time ASC
                    LIMIT 12
                    """, extremeUtc, symbol, from, to);
            List<AuditEvent> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                out.add(new AuditEvent(
                        asLong(row.get("strategy_id")),
                        stringVal(row.get("interval_code")),
                        asDateTime(row.get("bar_open_time")),
                        asDateTime(row.get("event_time")),
                        stringVal(row.get("event_type")),
                        stringVal(row.get("outcome")),
                        stringVal(row.get("blocker")),
                        stringVal(row.get("reason")),
                        stringVal(row.get("context_json")),
                        asLong(row.get("distance_seconds"))));
            }
            return out;
        } catch (Exception e) {
            log.warn("[getIntradayExtremesDigest] audit lookup failed near {}: {}",
                    extremeUtc, e.getMessage());
            return List.of(new AuditEvent(null, null, null, null,
                    "AUDIT_QUERY_ERROR", "ERROR", "QUERY_ERROR", e.getMessage(), null, null));
        }
    }

    private String summarizeAuditEvents(List<AuditEvent> events) {
        long buyEval = events.stream().filter(AuditEvent::isBuyEval).count();
        long entrySkip = events.stream().filter(e -> "ENTRY_SKIP".equals(e.eventType())).count();
        long filterBlock = events.stream().filter(e -> "FILTER_BLOCK".equals(e.eventType())).count();
        long autoTrade = events.stream().filter(e -> e.eventType() != null && e.eventType().startsWith("AUTOTRADE")).count();
        return String.format("BUY_EVAL=%d ENTRY_SKIP=%d FILTER_BLOCK=%d AUTOTRADE=%d rows=%d",
                buyEval, entrySkip, filterBlock, autoTrade, events.size());
    }

    private String explainExtreme(String label, List<AuditEvent> events) {
        boolean hasBuy = events.stream().anyMatch(AuditEvent::isBuyEval);
        boolean hasDedup = events.stream().anyMatch(AuditEvent::isDedupSkip);
        boolean hasFilter = events.stream().anyMatch(e -> "FILTER_BLOCK".equals(e.eventType()));
        boolean hasAutoTrade = events.stream().anyMatch(e -> "AUTOTRADE_OK".equals(e.eventType()));
        if ("LOW".equals(label) && hasBuy && hasDedup) {
            return "bar-close BUY was generated near the low, but ENTRY_SKIP/EntryDedup indicates existing LONG exposure or duplicate-entry guard blocked adding another entry.";
        }
        if ("LOW".equals(label) && !hasBuy) {
            return "no nearby BUY evaluation; this looks like a missed intrabar wick unless another higher-timeframe bar later produced a signal.";
        }
        if (hasFilter) {
            return "strategy evaluation existed, but FILTER_BLOCK rows explain why it was not actionable.";
        }
        if (hasAutoTrade) {
            return "nearby audit includes AUTOTRADE_OK; verify position/OCO rather than treating the extreme as missed.";
        }
        if ("HIGH".equals(label) && hasBuy && hasDedup) {
            return "later BUY interest existed after the move, but dedup/exposure guard prevented adding risk at the high.";
        }
        return "nearby audit rows exist; inspect outcomes above to distinguish bar-close signal timing from intrabar price movement.";
    }

    private void appendMissedLongSummary(StringBuilder sb, KlineExtreme low, KlineExtreme high, String symbol) {
        sb.append("=== Operator Summary ===\n");
        if (low == null) {
            sb.append("- Cannot judge missed-long timing because the day low is unavailable.\n");
            return;
        }
        List<AuditEvent> lowEvents = findAuditEventsNear(symbol, low.openTime(), 120);
        boolean lowBuy = lowEvents.stream().anyMatch(AuditEvent::isBuyEval);
        boolean lowDedup = lowEvents.stream().anyMatch(AuditEvent::isDedupSkip);
        if (lowBuy && lowDedup) {
            sb.append("- Low explanation: not ignored; a BUY-style bar-close evaluation appeared near the low but was blocked by existing exposure/dedup guard.\n");
        } else if (!lowBuy) {
            sb.append("- Low explanation: no nearby BUY-style audit row; likely intrabar wick or data/strategy cadence gap.\n");
        } else {
            sb.append("- Low explanation: BUY-style audit row exists; review nearby blockers/actions for execution reason.\n");
        }
        if (high != null) {
            long minutes = Math.abs(ChronoUnit.MINUTES.between(low.openTime(), high.openTime()));
            sb.append(String.format("- Range timing: low=%s Taipei, high=%s Taipei, separation=%d minutes.%n",
                    fmtTaipeiFromUtc(low.openTime()), fmtTaipeiFromUtc(high.openTime()), minutes));
        }
        sb.append("- Safety: report is diagnostic only; do not disable EntryDedup or add exposure from this digest without explicit strategy review.\n");
    }

    private AuditMatch nearestSkipAudit(Long strategyId, String symbol, String interval,
                                        LocalDateTime barOpen, LocalDateTime createdAt) {
        try {
            LocalDateTime from = createdAt != null ? createdAt.minusMinutes(90)
                    : LocalDateTime.now(TZ).minusDays(7);
            LocalDateTime to = createdAt != null ? createdAt.plusMinutes(90)
                    : LocalDateTime.now(TZ);
            List<Map<String, Object>> audits = jdbc.queryForList("""
                    SELECT event_type, outcome, blocker, reason, event_time, bar_open_time, live_signal_id,
                           ABS(TIMESTAMPDIFF(SECOND, event_time, ?)) AS distance_seconds
                    FROM bt_decision_audit
                    WHERE strategy_id = ?
                      AND symbol = ?
                      AND interval_code = ?
                      AND event_time BETWEEN ? AND ?
                      AND event_type IN ('ENTRY_SKIP','FILTER_BLOCK','AUTOTRADE_OK','AUTOTRADE_FAIL')
                    ORDER BY
                      CASE WHEN bar_open_time <=> ? THEN 0 ELSE 1 END,
                      distance_seconds ASC
                    LIMIT 1
                    """, createdAt, strategyId, symbol, interval, from, to, barOpen);
            if (audits.isEmpty()) return AuditMatch.none();
            Map<String, Object> a = audits.get(0);
            return new AuditMatch(
                    stringVal(a.get("event_type")),
                    stringVal(a.get("outcome")),
                    stringVal(a.get("blocker")),
                    stringVal(a.get("reason")),
                    asDateTime(a.get("event_time")),
                    asDateTime(a.get("bar_open_time")),
                    asLong(a.get("live_signal_id")),
                    asLong(a.get("distance_seconds")));
        } catch (Exception e) {
            log.warn("[listUnmarkedLiveSignalSkips] audit correlation failed for strategy {}: {}",
                    strategyId, e.getMessage());
            return AuditMatch.error(e.getMessage());
        }
    }

    private String classifyUnmarkedSkip(AuditMatch match) {
        if (!match.hasAudit()) return "NO_NEARBY_AUDIT";
        String blocker = match.blocker() != null ? match.blocker() : "";
        String event = match.eventType() != null ? match.eventType() : "";
        if ("ENTRY_SKIP".equals(event)
                && ("EntryDedup".equalsIgnoreCase(blocker)
                || "DuplicateBar".equalsIgnoreCase(blocker)
                || "LegacyEntrySkip".equalsIgnoreCase(blocker))) {
            return "ENTRY_SKIP_" + blocker;
        }
        if ("FILTER_BLOCK".equals(event)) return "FILTER_BLOCK_AUDIT";
        if ("AUTOTRADE_FAIL".equals(event)) return "AUTOTRADE_FAIL_AUDIT";
        return "AUDIT_CORRELATED_SKIP";
    }

    private record AuditMatch(String eventType, String outcome, String blocker,
                              String reason, LocalDateTime eventTime, LocalDateTime barOpenTime,
                              Long liveSignalId, Long distanceSeconds) {
        static AuditMatch none() {
            return new AuditMatch(null, null, null, null, null, null, null, null);
        }

        static AuditMatch error(String message) {
            return new AuditMatch("AUDIT_QUERY_ERROR", "ERROR", "QUERY_ERROR", message, null, null, null, null);
        }

        boolean hasAudit() {
            return eventType != null;
        }

        String canonicalSkipReasonFromAudit() {
            if (!hasAudit()) return "N/A";
            if ("ENTRY_SKIP".equals(eventType) && blocker != null && !blocker.isBlank()) {
                return "ENTRY_SKIP_" + blocker;
            }
            if ("FILTER_BLOCK".equals(eventType)) return "FILTER_BLOCK_AUDIT";
            if ("AUTOTRADE_FAIL".equals(eventType)) return "AUTOTRADE_FAIL_AUDIT";
            return "AUDIT_CORRELATED_SKIP";
        }

        String skipReasonSource() {
            return hasAudit() ? "bt_decision_audit_nearby_correlation" : "none";
        }

        String skipReasonConfidence(LocalDateTime rowBarOpen) {
            if (!hasAudit()) return "LOW";
            if ("AUDIT_QUERY_ERROR".equals(eventType)) return "LOW";
            if (rowBarOpen != null && rowBarOpen.equals(barOpenTime)) return "HIGH";
            return "MEDIUM";
        }

        String blockedByExistingLiveSignalId() {
            return liveSignalId == null ? "N/A" : String.valueOf(liveSignalId);
        }

        String summary() {
            if (!hasAudit()) return "NO_NEARBY_AUDIT";
            String distance = distanceSeconds != null ? " distance=" + distanceSeconds + "s" : "";
            return String.format("%s/%s blocker=%s reason=%s at=%s%s",
                    eventType, outcome, blocker, truncate(reason, 80), fmtTime(eventTime), distance);
        }

        String recommendation() {
            if (!hasAudit()) return " | action=investigate_missing_audit";
            if ("EntryDedup".equalsIgnoreCase(blocker) || "DuplicateBar".equalsIgnoreCase(blocker)) {
                return " | action=label_as_dedup_skip";
            }
            if ("LegacyEntrySkip".equalsIgnoreCase(blocker)) {
                return " | action=legacy_artifact_watch_new_rows";
            }
            return " | action=review_reason_mapping";
        }
    }

    private static Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static LocalDateTime asDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime t) return t;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(String.valueOf(value));
    }

    private static double asDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return value == null ? 0.0 : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static Double asDoubleObj(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "-";
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String fmtTime(LocalDateTime value) {
        return value == null ? "-" : value.format(FMT);
    }

    private static String fmtTaipeiFromUtc(LocalDateTime utcValue) {
        if (utcValue == null) return "-";
        return utcValue.atZone(ZoneOffset.UTC).withZoneSameInstant(TZ).format(FMT);
    }

    private String normalizeAttributionBlocker(MissedOpportunityRow row) {
        if ("MISSED_CANDIDATE".equals(row.classification())) {
            return "NO_TERMINAL_BLOCKER";
        }
        return row.related().stream()
                .filter(e -> e.eventType() != null && !"AUTOTRADE_OK".equals(e.eventType()))
                .findFirst()
                .map(e -> {
                    String blocker = e.blocker() == null || e.blocker().isBlank() ? e.eventType() : e.blocker();
                    if ("FearGreedFilter".equalsIgnoreCase(blocker)
                            && e.reason() != null
                            && e.reason().toUpperCase(java.util.Locale.ROOT).contains("WARN")) {
                        return "FearGreedFilter/WARN_ONLY";
                    }
                    return blocker;
                })
                .orElse("NO_TERMINAL_BLOCKER");
    }

    private String attributionClassification(MissedOpportunityRow row) {
        return isUnscorableAttributionRow(row) ? "UNSCORABLE" : row.classification();
    }

    private boolean isUnscorableAttributionRow(MissedOpportunityRow row) {
        return row.forward().entryPrice() == null
                || row.forward().maxUpPct() == null
                || !hasTradePlan(row);
    }

    private boolean hasTradePlan(MissedOpportunityRow row) {
        return firstTradePlanValue(row, "entry", "candidateEntry", "candidate_entry") != null
                && firstTradePlanValue(row, "tp", "candidateTp", "candidate_tp") != null
                && firstTradePlanValue(row, "sl", "candidateSl", "candidate_sl") != null;
    }

    private BigDecimal firstTradePlanValue(MissedOpportunityRow row, String primary, String... aliases) {
        if (("entry".equals(primary) || "candidateEntry".equals(primary)) && row.buy().entryPrice() != null) {
            return row.buy().entryPrice();
        }
        if (("tp".equals(primary) || "candidateTp".equals(primary)) && row.buy().suggestedTp() != null) {
            return row.buy().suggestedTp();
        }
        if (("sl".equals(primary) || "candidateSl".equals(primary)) && row.buy().suggestedSl() != null) {
            return row.buy().suggestedSl();
        }
        BigDecimal fromBuyContext = firstJsonBigDecimal(row.buy().contextJson(), primary, aliases);
        if (fromBuyContext != null) {
            return fromBuyContext;
        }
        for (AuditEvent event : row.related()) {
            BigDecimal value = firstJsonBigDecimal(event.contextJson(), primary, aliases);
            if (value != null) {
                return value;
            }
        }
        if ("entry".equals(primary) || "candidateEntry".equals(primary)) {
            return row.forward().entryPrice();
        }
        return null;
    }

    private BigDecimal firstJsonBigDecimal(String json, String primary, String... aliases) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            BigDecimal value = jsonBigDecimal(node, primary);
            if (value != null) {
                return value;
            }
            for (String alias : aliases) {
                value = jsonBigDecimal(node, alias);
                if (value != null) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private BigDecimal jsonBigDecimal(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        try {
            if (value.isNumber()) {
                return value.decimalValue();
            }
            String text = value.asText(null);
            if (text == null || text.isBlank() || "NOT_SIZED".equalsIgnoreCase(text)) {
                return null;
            }
            return new BigDecimal(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private RuntimeEvidenceAttributionSummary loadRuntimeEvidenceAttributionSummary(String symbol, LocalDateTime since) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT COUNT(*) AS total,
                           SUM(CASE WHEN UPPER(COALESCE(fear_greed_mode,''))='WARN_ONLY'
                                     OR LOWER(COALESCE(warnings_json,'')) LIKE '%feargreedwarning%' THEN 1 ELSE 0 END) AS fg_warn,
                           SUM(CASE WHEN LOWER(COALESCE(terminal_blocker,'')) LIKE '%feargreed%' THEN 1 ELSE 0 END) AS fg_terminal,
                           SUM(CASE WHEN LOWER(COALESCE(ev_result_json,'')) LIKE '%candidatecontinuedtoev%true%' THEN 1 ELSE 0 END) AS continued_ev,
                           SUM(CASE WHEN LOWER(COALESCE(tqs_result_json,'')) LIKE '%candidatecontinuedtotqs%true%'
                                     OR LOWER(COALESCE(warnings_json,'')) LIKE '%candidatecontinuedtotqs%true%' THEN 1 ELSE 0 END) AS continued_tqs,
                           SUM(CASE WHEN order_sent = TRUE THEN 1 ELSE 0 END) AS order_sent,
                           SUM(CASE WHEN UPPER(COALESCE(suppression_reason,''))='SHADOW_MODE' THEN 1 ELSE 0 END) AS shadow_suppressed
                    FROM bt_runtime_decision_evidence
                    WHERE evidence_time >= ?
                      AND (? IS NULL OR symbol = ?)
                    """, since, symbol, symbol);
            int total = asInt(row.get("total"));
            return new RuntimeEvidenceAttributionSummary(
                    total,
                    asInt(row.get("fg_warn")),
                    asInt(row.get("fg_terminal")),
                    asInt(row.get("continued_ev")),
                    asInt(row.get("continued_tqs")),
                    asInt(row.get("order_sent")),
                    asInt(row.get("shadow_suppressed")),
                    total == 0 ? "NO_RUNTIME_EVIDENCE_ROWS" : "JOINED");
        } catch (Exception e) {
            return new RuntimeEvidenceAttributionSummary(0, 0, 0, 0, 0, 0, 0,
                    "UNAVAILABLE: " + truncate(e.getMessage(), 160));
        }
    }

    private static String fmtUtc(LocalDateTime value) {
        return value == null ? "-" : value.format(FMT);
    }

    private static LocalDateTime parseUtcOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(value.trim())
                    .atZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static double pct(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return 0.0;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 8, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String fmtPct(Double value) {
        if (value == null || value.isNaN()) {
            return "pending";
        }
        return String.format("%.2f%%", value);
    }

    private static Double pctObj(int numerator, int denominator) {
        if (denominator <= 0) return null;
        return numerator * 100.0 / denominator;
    }

    private static String fmtMoney(Double value) {
        return value == null || value.isNaN() ? "N/A" : String.format("%.2f", value);
    }

    private record KlineExtreme(LocalDateTime openTime, BigDecimal lowPrice, BigDecimal highPrice,
                                BigDecimal closePrice, String source) {}

    private record RuntimeEvidenceAttributionSummary(int total, int fearGreedWarnOnly, int fearGreedTerminal,
                                                     int continuedToEv, int continuedToTqs, int orderSent,
                                                     int shadowSuppressed, String status) {}

    private class AttributionBucket {
        private final String blocker;
        private int total;
        private int missed;
        private int review;
        private int correct;
        private int lowEdge;
        private int unscorable;
        private int executed;

        private AttributionBucket(String blocker) {
            this.blocker = blocker;
        }

        private void add(MissedOpportunityRow row) {
            total++;
            if (isUnscorableAttributionRow(row)) {
                unscorable++;
                return;
            }
            switch (row.classification()) {
                case "MISSED_CANDIDATE" -> missed++;
                case "FILTER_BLOCK_REVIEW" -> review++;
                case "BLOCKED_BUT_CORRECT" -> correct++;
                case "LATE_OR_LOW_EDGE" -> lowEdge++;
                case "EXECUTED" -> executed++;
                default -> unscorable++;
            }
        }

        private String oneLine() {
            return blocker
                    + " total=" + total
                    + " missed=" + missed
                    + " review=" + review
                    + " correct=" + correct
                    + " lowEdge=" + lowEdge
                    + " unscorable=" + unscorable
                    + " executed=" + executed;
        }
    }

    private record BuyAuditCandidate(Long id, Long strategyId, String symbol, String interval,
                                     LocalDateTime barOpenTime, LocalDateTime eventTime,
                                     String outcome, String reason, String contextJson, Long liveSignalId,
                                     BigDecimal entryPrice, BigDecimal suggestedTp, BigDecimal suggestedSl,
                                     BigDecimal nnOutput) {}

    private record ForwardWindow(LocalDateTime entryTime, BigDecimal entryPrice, BigDecimal maxHigh,
                                 BigDecimal minLow, Double maxUpPct, Double maxDownPct) {
        static ForwardWindow empty() {
            return new ForwardWindow(null, null, null, null, null, null);
        }
    }

    private record MissedOpportunityRow(BuyAuditCandidate buy, List<AuditEvent> related, ForwardWindow forward,
                                        String classification, String reason,
                                        com.agora.service.trading.PositionSizingService.PositionSizingDecision sizing) {
        String oneLine() {
            return String.format("auditId=%s strategy=%s %s@%s event=%s Taipei class=%s forwardMaxUp=%s forwardMaxDown=%s blocker=%s sizing=%s reason=%s",
                    buy.id() != null ? buy.id() : "-",
                    buy.strategyId() != null ? buy.strategyId() : "-",
                    buy.symbol(),
                    buy.interval(),
                    fmtTaipeiFromUtc(buy.eventTime()),
                    classification,
                    fmtPct(forward.maxUpPct()),
                    fmtPct(forward.maxDownPct()),
                    blockerLabel(),
                    sizingLine(),
                    reason);
        }

        String sizingLine() {
            if (sizing == null) {
                return "N/A(missing entry/tp/sl)";
            }
            return String.format("recommended=%.2f final=%.2f mode=%s reason=%s",
                    sizing.recommendedAmountUsdt(),
                    sizing.finalAmountUsdt(),
                    sizing.liveEnabled() ? "LIVE" : "SHADOW",
                    sizing.reason());
        }

        private String blockerLabel() {
            return related.stream()
                    .filter(e -> e.eventType() != null && !"AUTOTRADE_OK".equals(e.eventType()))
                    .findFirst()
                    .map(e -> e.eventType() + "/" + (e.blocker() != null ? e.blocker() : "-"))
                    .orElse("-");
        }
    }

    private record WickBar(LocalDateTime openTime, BigDecimal openPrice, BigDecimal highPrice,
                           BigDecimal lowPrice, BigDecimal closePrice, BigDecimal volume, String source) {}

    private record WickBarLoad(List<WickBar> bars, String sourceLabel) {}

    private record WickPersistenceDiagnostics(LocalDateTime latestCandidateAt,
                                              Long daysSinceLatestCandidate,
                                              int distinctCandidateDays,
                                              int topDaySampleCount) {}

    private record WickStats(int n, Double winRatePct, Double avgPct, Double bestPct, Double worstPct) {}

    private record WickThresholdReview(double wickThreshold,
                                       double recoveryThreshold,
                                       int sampleCount,
                                       WickStats stats1h,
                                       WickStats stats4h,
                                       WickStats stats24h,
                                       Double avgMfe24hPct,
                                       Double avgMae24hPct,
                                       Double worstMae24hPct,
                                       int distinctCandidateDays,
                                       int topDaySampleCount,
                                       LocalDateTime latestCandidateAt,
                                       String status) {}

    private record WickCandidate(LocalDateTime openTime, BigDecimal lowPrice, BigDecimal closePrice,
                                 double lowerWickPct, double recoveryPct, double rangePct,
                                 Double ret1hPct, Double ret4hPct, Double ret24hPct,
                                 Double mfe24hPct, Double mae24hPct) {
        String oneLine() {
            return String.format("%s Taipei low=%s close=%s wick=%s recovery=%s range=%s -> 1h=%s 4h=%s 24h=%s",
                    fmtTaipeiFromUtc(openTime),
                    plain(lowPrice),
                    plain(closePrice),
                    fmtPct(lowerWickPct),
                    fmtPct(recoveryPct),
                    fmtPct(rangePct),
                    fmtPct(ret1hPct),
                    fmtPct(ret4hPct),
                    fmtPct(ret24hPct));
        }
    }

    private record AuditEvent(Long strategyId, String interval, LocalDateTime barOpenTime,
                              LocalDateTime eventTime, String eventType, String outcome,
                              String blocker, String reason, String contextJson, Long distanceSeconds) {
        boolean isBuyEval() {
            if ("SIGNAL_BUY".equals(eventType)) return true;
            if (!"SIGNAL_EVAL".equals(eventType)) return false;
            String r = reason != null ? reason.toUpperCase() : "";
            return r.contains("BUY") || r.contains("LONG");
        }

        boolean isDedupSkip() {
            return "ENTRY_SKIP".equals(eventType)
                    && ("EntryDedup".equalsIgnoreCase(blocker)
                    || "DuplicateBar".equalsIgnoreCase(blocker)
                    || "LegacyEntrySkip".equalsIgnoreCase(blocker));
        }

        String oneLine() {
            String distance = distanceSeconds != null ? " distance=" + distanceSeconds + "s" : "";
            return String.format("strategy=%s interval=%s event=%s/%s blocker=%s event_time=%s Taipei (%s UTC) bar=%s Taipei reason=%s%s",
                    strategyId != null ? strategyId : "-",
                    interval != null ? interval : "-",
                    eventType != null ? eventType : "-",
                    outcome != null ? outcome : "-",
                    blocker != null ? blocker : "-",
                    fmtTaipeiFromUtc(eventTime),
                    fmtUtc(eventTime),
                    fmtTaipeiFromUtc(barOpenTime),
                    truncate(reason, 120),
                    distance);
        }
    }

    // ── 解析 configJson 工具方法 ──────────────────────────────────────────────

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Map<String, Integer> countEntrySkipAudits(Long strategyId, LocalDateTime since) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT blocker, COUNT(*) AS cnt " +
                    "FROM bt_decision_audit FORCE INDEX (idx_audit_event_strategy_time_blocker) " +
                    "WHERE event_type = 'ENTRY_SKIP' " +
                    "  AND strategy_id = ? " +
                    "  AND event_time >= ? " +
                    "GROUP BY blocker ORDER BY cnt DESC LIMIT 20",
                    strategyId, since);
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String blocker = String.valueOf(row.getOrDefault("blocker", "UNKNOWN"));
                Object cnt = row.get("cnt");
                int n = cnt instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(cnt));
                out.put(blocker, n);
            }
            return out;
        } catch (Exception e) {
            log.warn("[verifyStrategyExecution] ENTRY_SKIP audit count failed for strategy {}: {}",
                    strategyId, e.getMessage());
            return Map.of();
        }
    }

    private String summarizeCounts(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) return "-";
        return counts.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("-");
    }

    /** 回傳此策略需要的所有 K 線週期。MTF 策略額外補 4h。 */
    private Set<String> resolveAllIntervals(BtStrategy strategy) {
        Set<String> intervals = new HashSet<>();
        intervals.add(resolveInterval(strategy));
        // SOP_MTF_ADX 等多週期策略需要 4h 資料
        String type = strategy.getStrategyType();
        if (type != null && (type.contains("MTF") || type.contains("SOP"))) {
            intervals.add("4h");
        }
        return intervals;
    }

    private String resolveSymbol(BtStrategy strategy) {
        String symbol = strategy.getSymbols();
        try {
            JsonNode cfg = objectMapper.readTree(strategy.getConfigJson());
            if (cfg.has("symbol") && !cfg.get("symbol").asText("").isEmpty()) {
                symbol = cfg.get("symbol").asText();
            }
        } catch (Exception ignored) {}
        if (symbol == null || symbol.isBlank()) return null;
        // 取第一個幣種（strategies.symbols 可能為逗號分隔的多幣種字串）
        int comma = symbol.indexOf(',');
        return comma > 0 ? symbol.substring(0, comma).trim() : symbol.trim();
    }

    private String resolveInterval(BtStrategy strategy) {
        try {
            JsonNode cfg = objectMapper.readTree(strategy.getConfigJson());
            if (cfg.has("runIntervalCode")) return cfg.get("runIntervalCode").asText("1h");
        } catch (Exception ignored) {}
        return "1h";
    }

    private boolean resolveNotifyOnly(BtStrategy strategy) {
        try {
            JsonNode cfg = objectMapper.readTree(strategy.getConfigJson());
            if (cfg.has("notifyOnly")) return cfg.get("notifyOnly").asBoolean(false);
        } catch (Exception ignored) {}
        return false;
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC})
    @Tool(description = "#228 直接讀取 app.log 最後 N 行，支援關鍵字過濾。不需要 SSH。" +
            "params: lines=行數(預設 50,最多 200), filter=關鍵字過濾(可選,如 DexFlowBackfill/ML003011/Started)")
    public String tailAppLog(Integer lines, String filter) {
        int n = lines != null ? Math.min(Math.max(lines, 1), 200) : 50;
        java.io.File logFile = resolveAppLogFile();
        if (!logFile.exists()) {
            return "⚠️ Log file not found at " + APP_LOG_PATH;
        }
        try {
            // Read last N lines using RandomAccessFile
            java.util.Deque<String> lastLines = new java.util.ArrayDeque<>();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
                long fileLen = raf.length();
                long pos = fileLen - 1;
                int lineCount = 0;
                StringBuilder current = new StringBuilder();
                while (pos >= 0 && lineCount < n) {
                    raf.seek(pos);
                    char c = (char) raf.read();
                    if (c == '\n' && current.length() > 0) {
                        String line = current.reverse().toString();
                        if (filter == null || filter.isBlank() || line.contains(filter)) {
                            lastLines.addFirst(line);
                            lineCount++;
                        }
                        current = new StringBuilder();
                    } else if (c != '\n') {
                        current.append(c);
                    }
                    pos--;
                }
            }
            if (lastLines.isEmpty()) return String.format("ℹ️ 無匹配記錄（filter=%s）", filter);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== app.log tail (%d lines%s) ===\n\n",
                    lastLines.size(), filter != null && !filter.isBlank() ? " filter=" + filter : ""));
            lastLines.forEach(l -> sb.append(l).append("\n"));
            return sb.toString();
        } catch (Exception e) {
            return "❌ 讀取失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC})
    @Tool(description = "#232 近期 deploy 紀錄：從 app.log 解析 TradingApiApplication started 條目，" +
            "顯示最近 N 次啟動時間及 HEAD commit（從 git log 獲取）。" +
            "param: limit=筆數(預設 5)")
    public String getDeployHistory(Integer limit) {
        int lim = limit != null ? Math.min(Math.max(limit, 1), 20) : 5;
        java.io.File logFile = resolveAppLogFile();
        try {
            java.util.List<String> startups = new java.util.ArrayList<>();
            if (logFile.exists()) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(logFile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.contains(APP_STARTED_MARKER)) startups.add(line);
                    }
                }
            }
            StringBuilder sb = new StringBuilder("=== Deploy History (最近 " + lim + " 次啟動) ===\n\n");
            if (startups.isEmpty()) {
                sb.append("ℹ️ 無啟動記錄\n");
            } else {
                int start = Math.max(0, startups.size() - lim);
                for (int i = startups.size() - 1; i >= start; i--) {
                    sb.append(startups.get(i), 0, Math.min(startups.get(i).length(), 100)).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ 讀取失敗: " + e.getMessage();
        }
    }

    private SignalEvalAuditStats countSignalEvalAudits(Long strategyId, LocalDateTime since) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT COUNT(*) AS total, " +
                    "SUM(CASE " +
                    "      WHEN event_type = 'SIGNAL_BUY' THEN 1 " +
                    "      WHEN event_type = 'SIGNAL_EVAL' AND (" +
                    "           UPPER(COALESCE(reason, '')) LIKE '%BUY%' OR UPPER(COALESCE(reason, '')) LIKE '%LONG%'" +
                    "      ) THEN 1 " +
                    "      ELSE 0 " +
                    "    END) AS buy_like " +
                    "FROM bt_decision_audit " +
                    "WHERE strategy_id = ? " +
                    "  AND event_time >= ? " +
                    "  AND event_type IN ('SIGNAL_EVAL', 'SIGNAL_BUY')",
                    strategyId, since);
            int total = asInt(row.get("total"));
            int buyLike = asInt(row.get("buy_like"));
            return new SignalEvalAuditStats(total, buyLike);
        } catch (Exception e) {
            log.warn("[verifyStrategyExecution] SIGNAL_EVAL audit count failed for strategy {}: {}",
                    strategyId, e.getMessage());
            return new SignalEvalAuditStats(0, 0);
        }
    }

    private int asInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number num) return num.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record SignalEvalAuditStats(int total, int buyLike) {}

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "#460 啟動效能診斷：解析 app.log 最近 N 次啟動的 Spring startup 秒數、" +
            "StartupBeanTiming 慢 bean、Readiness 接流量時間與啟動期間 log 空洞。" +
            "params: limit=最近幾次啟動(預設3,最多10), minGapSeconds=顯示幾秒以上空洞(預設5)")
    public String getStartupPerformanceReport(Integer limit, Integer minGapSeconds) {
        int lim = limit != null ? Math.min(Math.max(limit, 1), 10) : 3;
        int gapThreshold = minGapSeconds != null ? Math.min(Math.max(minGapSeconds, 1), 60) : 5;
        java.io.File logFile = resolveAppLogFile();
        if (!logFile.exists()) return "⚠️ app.log not found";

        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath());
            java.util.List<Integer> startupIndexes = new java.util.ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(APP_STARTED_MARKER)) {
                    startupIndexes.add(i);
                }
            }
            if (startupIndexes.isEmpty()) {
                return "ℹ️ app.log has no " + APP_STARTED_MARKER + " lines";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Startup Performance Report ===\n\n");
            sb.append("source: ").append(logFile.getPath()).append("\n");
            sb.append("limit: ").append(lim)
                    .append(" | min_gap_seconds: ").append(gapThreshold).append("\n\n");

            int emitted = 0;
            for (int idx = startupIndexes.size() - 1; idx >= 0 && emitted < lim; idx--, emitted++) {
                int startupIdx = startupIndexes.get(idx);
                int startBoundary = findStartupBeginIndex(lines, startupIdx);
                int endBoundaryExclusive = findNextStartupBeginIndex(lines, startupIdx + 1);
                java.util.List<String> window = lines.subList(startBoundary, endBoundaryExclusive);
                appendStartupWindowReport(sb, emitted + 1, window, lines.get(startupIdx), gapThreshold);
                sb.append("\n");
            }
            sb.append("notes:\n");
            sb.append("- Bean timings are cumulative from pre-instantiation to post-initialization; dependent beans can inherit upstream JPA/DI delay.\n");
            sb.append("- Readiness after Started is still important for deploy.sh because blue/green waits for an HTTP-ready instance before nginx swap.\n");
            sb.append("- Use this report before changing @Lazy/ApplicationReadyEvent behavior so trading-critical startup order stays explicit.\n");
            return sb.toString();
        } catch (Exception e) {
            return "❌ startup performance report failed: " + e.getMessage();
        }
    }

    private int findStartupBeginIndex(java.util.List<String> lines, int beforeOrAt) {
        for (int i = Math.min(beforeOrAt, lines.size() - 1); i >= 0; i--) {
            if (lines.get(i).contains(APP_STARTING_MARKER)) {
                return i;
            }
        }
        return Math.max(0, beforeOrAt - 2000);
    }

    private int findNextStartupBeginIndex(java.util.List<String> lines, int after) {
        for (int i = Math.max(0, after); i < lines.size(); i++) {
            if (lines.get(i).contains(APP_STARTING_MARKER)) {
                return i;
            }
        }
        return lines.size();
    }

    private void appendStartupWindowReport(StringBuilder sb, int ordinal, java.util.List<String> window,
                                           String startupLine, int gapThreshold) {
        java.util.Optional<Double> startupSeconds = parseStartupSeconds(startupLine);
        sb.append("--- Startup #").append(ordinal).append(" ---\n");
        sb.append("started_line: ").append(trimLine(startupLine, 220)).append("\n");
        startupSeconds.ifPresent(seconds ->
                sb.append(String.format("spring_startup_seconds: %.3f%n", seconds)));

        java.util.List<BeanTimingHit> beanHits = new java.util.ArrayList<>();
        java.util.List<String> phaseHits = new java.util.ArrayList<>();
        java.util.List<GapHit> gaps = new java.util.ArrayList<>();
        java.time.LocalDateTime previousTs = null;
        String previousLine = null;
        java.time.LocalDateTime startedTs = parseLogTimestamp(startupLine);
        java.time.LocalDateTime readinessTs = null;

        for (String line : window) {
            parseBeanTiming(line).ifPresent(beanHits::add);
            if (isStartupPhaseLine(line)) {
                phaseHits.add(trimLine(line, 180));
            }
            if (line.contains("readiness changed to ACCEPTING_TRAFFIC")) {
                readinessTs = parseLogTimestamp(line);
            }
            java.time.LocalDateTime ts = parseLogTimestamp(line);
            if (ts != null) {
                if (previousTs != null) {
                    long gap = java.time.Duration.between(previousTs, ts).getSeconds();
                    if (gap >= gapThreshold) {
                        gaps.add(new GapHit(gap, previousLine, line));
                    }
                }
                previousTs = ts;
                previousLine = line;
            }
        }

        if (startedTs != null && readinessTs != null) {
            long readinessAfterStarted = java.time.Duration.between(startedTs, readinessTs).getSeconds();
            sb.append("readiness_after_started_seconds: ").append(readinessAfterStarted).append("\n");
        } else {
            sb.append("readiness_after_started_seconds: n/a\n");
        }

        beanHits.sort(java.util.Comparator.comparingLong(BeanTimingHit::millis).reversed());
        sb.append("top_slow_beans:\n");
        if (beanHits.isEmpty()) {
            sb.append("  none captured\n");
        } else {
            int max = Math.min(beanHits.size(), 12);
            for (int i = 0; i < max; i++) {
                BeanTimingHit hit = beanHits.get(i);
                sb.append(String.format("  %2d. %6dms  %s  (%s)%n",
                        i + 1, hit.millis(), hit.bean(), compactClassName(hit.type())));
            }
        }

        sb.append("startup_phase_hits:\n");
        if (phaseHits.isEmpty()) {
            sb.append("  none captured\n");
        } else {
            int start = Math.max(0, phaseHits.size() - 16);
            for (int i = start; i < phaseHits.size(); i++) {
                sb.append("  ").append(phaseHits.get(i)).append("\n");
            }
        }

        sb.append("log_gaps:\n");
        if (gaps.isEmpty()) {
            sb.append("  none >= ").append(gapThreshold).append("s\n");
        } else {
            int max = Math.min(gaps.size(), 10);
            for (int i = 0; i < max; i++) {
                GapHit gap = gaps.get(i);
                sb.append(String.format("  %2d. %2ds after: %s%n",
                        i + 1, gap.seconds(), trimLine(gap.previousLine(), 140)));
                sb.append("       next: ").append(trimLine(gap.nextLine(), 140)).append("\n");
            }
        }
    }

    private java.util.Optional<BeanTimingHit> parseBeanTiming(String line) {
        if (line == null || !line.contains("[StartupBeanTiming]")) {
            return java.util.Optional.empty();
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("bean=([^ ]+) type=([^ ]+) took=([0-9]+)ms")
                .matcher(line);
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new BeanTimingHit(
                matcher.group(1), matcher.group(2), Long.parseLong(matcher.group(3))));
    }

    private java.util.Optional<Double> parseStartupSeconds(String line) {
        if (line == null) return java.util.Optional.empty();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(APP_STARTED_MARKER + " in ([0-9]+(?:\\.[0-9]+)?) seconds")
                .matcher(line);
        return matcher.find()
                ? java.util.Optional.of(Double.parseDouble(matcher.group(1)))
                : java.util.Optional.empty();
    }

    private java.time.LocalDateTime parseLogTimestamp(String line) {
        if (line == null || line.length() < 19) return null;
        try {
            return java.time.LocalDateTime.parse(line.substring(0, 19),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isStartupPhaseLine(String line) {
        if (line == null) return false;
        return line.contains("Tomcat started")
                || line.contains(APP_STARTED_MARKER)
                || line.contains("[StartupBudget]")
                || line.contains("ApplicationRunner.run() called")
                || line.contains("ApplicationReadyEvent")
                || line.contains("[McpTools]")
                || line.contains("Registered tools:")
                || line.contains("Exposing ") && line.contains("endpoints beneath base path")
                || line.contains("readiness changed to ACCEPTING_TRAFFIC")
                || line.contains("[StartupLog]");
    }

    private String compactClassName(String type) {
        if (type == null) return "";
        int idx = type.lastIndexOf('.');
        return idx >= 0 ? type.substring(idx + 1) : type;
    }

    private record BeanTimingHit(String bean, String type, long millis) {}

    private record GapHit(long seconds, String previousLine, String nextLine) {}

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "本次 JVM 啟動後 app.log WARN/ERROR 摘要。以最後一條 TradingApiApplication started marker 為切點，" +
            "避免把舊 deploy 失敗、舊 429、舊 duplicate key 誤判為當前問題。params: limit=最多顯示幾行(預設40,最多120)")
    public String getCurrentStartupLogIssues(Integer limit) {
        int lim = limit != null ? Math.min(Math.max(limit, 1), 120) : 40;
        java.io.File logFile = resolveAppLogFile();
        if (!logFile.exists()) return "⚠️ app.log not found";

        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath());
            int startIdx = -1;
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).contains(APP_STARTED_MARKER)) {
                    startIdx = i;
                    break;
                }
            }
            LocalDateTime startupFallback = null;
            if (startIdx < 0) {
                List<ServerStartupLog> logs = startupLogRepo.findTop10ByOrderByStartedAtDesc();
                if (!logs.isEmpty()) {
                    startupFallback = logs.get(0).getStartedAt();
                }
            }
            int from = startIdx >= 0 ? startIdx : Math.max(0, lines.size() - 2000);
            java.util.List<String> current = lines.subList(from, lines.size());
            boolean readinessRecovered = current.stream()
                    .anyMatch(line -> line.contains("readiness changed to ACCEPTING_TRAFFIC"));
            Set<String> returnedLeakConnections = current.stream()
                    .filter(line -> line.contains("Previously reported leaked connection")
                            && line.contains("was returned to the pool"))
                    .map(this::extractHikariConnectionId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(java.util.stream.Collectors.toSet());

            java.util.Map<String, Integer> buckets = new java.util.LinkedHashMap<>();
            buckets.put("ERROR", 0);
            buckets.put("WARN", 0);
            buckets.put("Known noise", 0);
            buckets.put("Slow startup", 0);
            buckets.put("Duplicate key", 0);
            buckets.put("AI 429", 0);
            buckets.put("AI degraded", 0);
            buckets.put("Connection leak", 0);
            buckets.put("Auth rejected", 0);

            java.util.List<String> hits = new java.util.ArrayList<>();
            if (startIdx >= 0 && isSlowStartup(lines.get(startIdx))) {
                buckets.computeIfPresent("Slow startup", (k, v) -> v + 1);
                hits.add("SLOW_STARTUP: " + trimLine(lines.get(startIdx), 220));
            }
            boolean inRecoveredLeakStack = false;
            boolean inKnownNoiseStack = false;
            for (int lineIndex = 0; lineIndex < current.size(); lineIndex++) {
                String line = current.get(lineIndex);
                if (startsWithLogTimestamp(line)) {
                    inRecoveredLeakStack = false;
                    inKnownNoiseStack = false;
                } else if (inRecoveredLeakStack) {
                    continue;
                } else if (inKnownNoiseStack) {
                    continue;
                }
                if (startIdx < 0 && startupFallback != null && isBeforeStartupFallback(line, startupFallback)) {
                    continue;
                }
                if (isRecoveredConnectionLeakTrigger(line, returnedLeakConnections)) {
                    buckets.computeIfPresent("Known noise", (k, v) -> v + 1);
                    inRecoveredLeakStack = true;
                    continue;
                }
                if (isRecoveredStartupBudgetWarning(line, readinessRecovered)) {
                    buckets.computeIfPresent("Known noise", (k, v) -> v + 1);
                    continue;
                }
                if (isRecoveredWsFailure(line, current)) {
                    buckets.computeIfPresent("Known noise", (k, v) -> v + 1);
                    continue;
                }
                if (isRecoveredKlineGap(line, current)) {
                    buckets.computeIfPresent("Known noise", (k, v) -> v + 1);
                    continue;
                }
                if (isKnownSecurityAsyncNoise(line, current, lineIndex)) {
                    buckets.computeIfPresent("Known noise", (k, v) -> v + 1);
                    inKnownNoiseStack = true;
                    continue;
                }
                if (isKnownSecurityErrorPageNoise(line, current, lineIndex)) {
                    buckets.computeIfPresent("Known noise", (k, v) -> v + 1);
                    inKnownNoiseStack = true;
                    continue;
                }
                if (isLocalMcpAuthDenied(line)) {
                    buckets.computeIfPresent("Known noise", (k, v) -> v + 1);
                    continue;
                }
                if (isKnownStartupLogNoise(line)) {
                    buckets.computeIfPresent("Known noise", (k, v) -> v + 1);
                    inKnownNoiseStack = line.contains(" WARN ")
                            || line.contains(" ERROR ")
                            || line.contains("Exception");
                    continue;
                }
                if (isAuthRejection(line)) {
                    buckets.computeIfPresent("Auth rejected", (k, v) -> v + 1);
                    continue;
                }
                if (isAiProviderDegraded(line)) {
                    buckets.computeIfPresent("AI degraded", (k, v) -> v + 1);
                    if (line.contains("HTTP 429") || line.contains("API HTTP 429")
                            || line.toLowerCase().contains("rate limit")) {
                        buckets.computeIfPresent("AI 429", (k, v) -> v + 1);
                    }
                    hits.add("AI_PROVIDER_DEGRADED: " + line);
                    continue;
                }
                boolean warn = line.contains(" WARN ");
                boolean error = line.contains(" ERROR ") || line.contains("Exception") || line.contains("APPLICATION FAILED");
                if (!warn && !error) continue;
                if (error) buckets.computeIfPresent("ERROR", (k, v) -> v + 1);
                if (warn) buckets.computeIfPresent("WARN", (k, v) -> v + 1);
                if (line.contains("Duplicate entry")) buckets.computeIfPresent("Duplicate key", (k, v) -> v + 1);
                if (line.contains("HTTP 429") || line.contains("API HTTP 429")) buckets.computeIfPresent("AI 429", (k, v) -> v + 1);
                if (line.contains("Apparent connection leak")) buckets.computeIfPresent("Connection leak", (k, v) -> v + 1);
                hits.add(line);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Current Startup Log Issues ===\n\n");
            sb.append("cutoff: ");
            if (startIdx >= 0) {
                sb.append(trimLine(lines.get(startIdx), 160));
            } else if (startupFallback != null) {
                sb.append("server_startup_log.started_at >= ").append(startupFallback)
                        .append(" (cutoffSource=server_startup_log; app.log start marker not found)");
            } else {
                sb.append("last 2000 lines fallback (cutoffSource=log_tail)");
            }
            sb.append("\n");
            sb.append("scanned_lines: ").append(current.size()).append("\n\n");
            sb.append("summary:\n");
            buckets.forEach((k, v) -> sb.append(String.format("  %-16s %d%n", k + ":", v)));

            sb.append("\nrecent hits:\n");
            if (hits.isEmpty()) {
                if (buckets.getOrDefault("Auth rejected", 0) > 0) {
                    sb.append("  ✅ No unclassified WARN/ERROR hits after current startup cutoff.\n");
                    sb.append("  ℹ️ Auth rejected lines are counted separately as expected security rejects.\n");
                } else {
                    sb.append("  ✅ No WARN/ERROR hits after current startup cutoff.\n");
                }
            } else {
                int start = Math.max(0, hits.size() - lim);
                for (int i = start; i < hits.size(); i++) {
                    sb.append("  ").append(trimLine(hits.get(i), 220)).append("\n");
                }
            }
            sb.append("\nnotes: blue/green old-JVM shutdown hook noise is excluded; ");
            sb.append("recovered startup-budget warnings are excluded; ");
            sb.append("AI_PROVIDER_DEGRADED lines are non-core AI advisory/provider degradation, not trading/OCO failure; ");
            sb.append("server_startup_log cutoff is an intentional fallback when app.log does not contain the Spring started marker; ");
            sb.append("localhost MCP auth-denied lines are security rejects, not app health failures; ");
            sb.append("Spring MVC malformed-request lines such as missing request body are request noise, not startup/trading failure.");
            return sb.toString();
        } catch (Exception e) {
            return "❌ 讀取本次啟動 log 失敗: " + e.getMessage();
        }
    }

    private boolean isSlowStartup(String line) {
        if (line == null) return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(APP_STARTED_MARKER + " in ([0-9]+(?:\\.[0-9]+)?) seconds")
                .matcher(line);
        return matcher.find() && Double.parseDouble(matcher.group(1)) > 120.0;
    }

    private boolean isRecoveredStartupBudgetWarning(String line, boolean readinessRecovered) {
        if (line == null || !readinessRecovered) return false;
        return line.contains("[StartupBudget]")
                && line.contains("stuck REFUSING_TRAFFIC")
                && line.contains("possible blocked ApplicationRunner");
    }

    private boolean isRecoveredWsFailure(String line, java.util.List<String> current) {
        if (line == null || current == null) return false;
        if (!line.contains("WS failure") || !line.contains("@")) return false;
        String sub = extractSubscription(line);
        if (sub == null) return false;
        String okxConnected = "Connected: SPOT " + sub;
        String binanceConnected = "Connected: SPOT " + sub.replace("@", "@kline_");
        return current.stream().anyMatch(l -> l.contains(okxConnected) || l.contains(binanceConnected));
    }

    private boolean isRecoveredKlineGap(String line, java.util.List<String> current) {
        if (line == null || current == null) return false;
        if (!line.contains("[KlineGap]") || !line.contains(" missing ")) return false;
        return current.stream().anyMatch(l -> l.contains("[KlineGap]")
                && (l.contains(" backfilled ") || l.contains("Scan complete, backfilled")));
    }

    private boolean isRecoveredConnectionLeakTrigger(String line, Set<String> returnedLeakConnections) {
        if (line == null || returnedLeakConnections == null || returnedLeakConnections.isEmpty()) return false;
        if (!line.contains("Connection leak detection triggered")) return false;
        String id = extractHikariConnectionId(line);
        return id != null && returnedLeakConnections.contains(id);
    }

    private String extractHikariConnectionId(String line) {
        if (line == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("ConnectionImpl@([0-9a-fA-F]+)")
                .matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean startsWithLogTimestamp(String line) {
        return line != null && line.length() >= 19
                && java.util.regex.Pattern.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*", line);
    }

    private String extractSubscription(String line) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([A-Z0-9]+@[A-Za-z0-9]+)")
                .matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    boolean isKnownStartupLogNoise(String line) {
        if (line == null) return false;
        // During blue/green deploy, the old JVM may write shutdown-hook warnings
        // after the new JVM's "Started" line because both instances append to the
        // same app.log. These are not current-instance health issues.
        return line.contains("SpringApplicationShutdownHook")
                || line.contains("GracefulShutdownCallback")
                || line.contains("Lifecycle$SingleUse")
                || line.contains("mcpSyncServer' propagated an exception")
                || line.contains("webMvcSseServerTransportProvider' propagated an exception")
                || line.contains("io/modelcontextprotocol/spec/McpServerSession")
                // Browser/client disconnects can emit container-level stack frames
                // after the application has already cleaned up the request.
                || line.contains("AsyncRequestNotUsableException")
                || line.contains("Servlet container error notification for disconnected client")
                || line.contains("Caused by: java.io.IOException: Broken pipe")
                || line.contains("AuthorizationDeniedException: Access Denied")
                || line.contains("ExceptionTranslationFilter.doFilter")
                || line.contains("Unable to handle the Spring Security Exception because the response is already committed")
                || line.contains("PositionAgingMonitor") && line.contains("[OcoPoll] Aging position:")
                || line.contains("RequestMappingInfoHandlerMapping$PartialMatchHelper$PartialMatch")
                // OkHttp may report a synthetic HTTP/2 task class while an old JVM
                // is winding down during blue/green deploy; it is not a trading issue.
                || line.contains("Exception: java.lang.NoClassDefFoundError thrown from the UncaughtExceptionHandler in thread \"OkHttp TaskRunner\"")
                || line.contains("OkHttp TaskRunner") && line.contains("writeSynResetLater")
                || line.contains("ClassNotFoundException: okhttp3.internal.http2.Http2Connection$writeSynResetLater")
                || line.contains("NoClassDefFoundError: okhttp3/internal/http2/StreamResetException")
                || line.contains("ClassNotFoundException: okhttp3.internal.http2.StreamResetException")
                // Old schedulers can still fire during the 60s blue/green drain.
                // Keep real hit-counter DB failures visible; only suppress the
                // shutdown/classloader ThrowableProxy variant.
                || line.contains("[AttentionRule] hit counter update failed: ch/qos/logback/classic/spi/ThrowableProxy")
                || line.contains("[MarketFlip]") && line.contains("TelegramServiceImpl$QueuedMessage")
                || line.contains("[IndicatorHistory] parallel fetch execution error: com/agora/service/market/CoinGeckoGlobalService$CachedDouble")
                || line.contains("Scheduled 任務發生異常: ExchangeRateServiceImpl.scheduledRefreshRates()")
                // a4469c7b replaced the unbounded "async-vt-" executor with a
                // bounded "async-" executor. Any async-vt EntityManagerFactory
                // closed line after that deploy is a drained old JVM task, not
                // a current-instance production failure.
                || line.contains("[async-vt-") && line.contains("EntityManagerFactory is closed")
                // Malformed client/API/MCP calls can be logged after startup by
                // Spring MVC as WARN + exception class. They are request-level
                // 4xx noise and must not block trading rollout/digest health.
                || line.contains("DefaultHandlerExceptionResolver")
                && line.contains("HttpMessageNotReadableException")
                && line.contains("Required request body is missing");
    }

    boolean isAiProviderDegraded(String line) {
        if (line == null) return false;
        String lower = line.toLowerCase();
        return line.contains("AI_PROVIDER_DEGRADED")
                || lower.contains("all providers exhausted for task: market-advisor-persona")
                || lower.contains("allprovidersfailedexception: all providers exhausted for task: market-advisor-persona")
                || line.contains("[AiTaskRouter] provider")
                && line.contains("market-advisor-persona")
                && (line.contains("HTTP 429") || lower.contains("rate limit") || lower.contains("resource_exhausted"));
    }

    boolean isKnownSecurityAsyncNoise(String line, java.util.List<String> current, int lineIndex) {
        if (line == null || current == null) return false;
        boolean servletHeader = line.contains(" ERROR ")
                && line.contains("dispatcherServlet")
                && line.contains("Servlet.service()")
                && line.contains("threw exception");
        if (!servletHeader) return false;

        int end = Math.min(current.size(), lineIndex + 8);
        for (int i = lineIndex + 1; i < end; i++) {
            String next = current.get(i);
            if (next == null) continue;
            if (startsWithLogTimestamp(next)) break;
            if (next.contains("AuthorizationDeniedException: Access Denied")
                    || next.contains("Unable to handle the Spring Security Exception because the response is already committed")) {
                return true;
            }
        }
        return false;
    }

    boolean isKnownSecurityErrorPageNoise(String line, java.util.List<String> current, int lineIndex) {
        if (line == null || current == null) return false;
        boolean errorPageHeader = line.contains(" ERROR ")
                && line.contains("Tomcat")
                && line.contains("Exception Processing [ErrorPage");
        if (!errorPageHeader) return false;

        int start = Math.max(0, lineIndex - 8);
        int end = Math.min(current.size(), lineIndex + 8);
        for (int i = start; i < end; i++) {
            String nearby = current.get(i);
            if (nearby == null) continue;
            if (nearby.contains("AuthorizationDeniedException: Access Denied")
                    || nearby.contains("Unable to handle the Spring Security Exception because the response is already committed")) {
                return true;
            }
        }
        return false;
    }

    boolean isLocalMcpAuthDenied(String line) {
        if (line == null) return false;
        return line.contains(" WARN ")
                && line.contains("com.agora.mcp.auth.McpApiKeyFilter")
                && line.contains("[McpAuth] DENIED")
                && (line.contains("ip=127.0.0.1") || line.contains("ip=::1")
                || line.contains("ip=0:0:0:0:0:0:0:1"));
    }

    boolean isAuthRejection(String line) {
        if (line == null) return false;
        return line.contains("[McpAuth] DENIED")
                || line.contains("[McpAuthDebug]")
                || line.contains("AuthorizationDeniedException: Access Denied");
    }

    private boolean isBeforeStartupFallback(String line, LocalDateTime startupFallback) {
        if (line == null || line.length() < 19 || startupFallback == null) return false;
        try {
            LocalDateTime ts = LocalDateTime.parse(line.substring(0, 19),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return ts.isBefore(startupFallback);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String trimLine(String line, int max) {
        if (line == null) return "";
        return line.length() <= max ? line : line.substring(0, max - 1) + "…";
    }

    // ==========================================================================
    // #337 verifyIndicatorOutcome — 統一事後正確性檢驗
    // V1 支援 source: tg_indicator / mih_threshold（其他 4 source 後續加）
    // ==========================================================================

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "#337 V1 — 對指定指標 / 訊號 source 抽歷史事件，逐一查事後 horizon 報酬，" +
            "產出 hit_rate + 95% CI + 報酬分布 + 最近 5 筆事件詳情。" +
            "用途：驗證「該指標警報後，價格真的照預期方向走嗎？」 " +
            "V1 支援 source: tg_indicator (filter=TG source LIKE pattern, 如 'SqiIndicator'), " +
            "mih_threshold (filter='indicator:operator:value', 如 'funding_rate:lte:-0.0003' / 'sqi:gte:40' / 'whale_buy_ratio:gt:0.65'). " +
            "operator: gt/gte/lt/lte/eq. mih 用 state-transition 模式只抓「從不滿足→滿足」轉折瞬間。 " +
            "params: source (必填), filter (必填), horizonHours (預設 24), hitThresholdPct (預設 0.5), days (預設 30, 最多 180), symbol (預設 BTCUSDT)")
    public String verifyIndicatorOutcome(String source, String filter, Integer horizonHours,
                                          Double hitThresholdPct, Integer days, String symbol) {
        try {
            return outcomeService.verify(source, filter, horizonHours, hitThresholdPct, days, symbol);
        } catch (Exception e) {
            log.warn("[verifyIndicatorOutcome] failed source={} filter={}", source, filter, e);
            return "❌ 失敗: " + e.getMessage();
        }
    }

    // ==========================================================================
    // #338 scanIndicatorAccuracy — 全指標事後正確率排行榜
    // V1：自動枚舉 TG distinct sources + 16 mih_threshold preset
    // ==========================================================================

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "#338 V1 — 自動枚舉所有 indicator source，批次跑事後正確率，產出 Tier 分組排行榜。" +
            "可視為 verifyIndicatorOutcome 的廣掃版本（找 alpha sources / 砍 noise sources）。 " +
            "V1 候選：TG sources（過去 days 內 distinct，過濾 system/scheduler）+ 16 條 mih_threshold preset。 " +
            "輸出：Tier 1 微觀結構 / Tier 2 情緒技術 / Other 三組排行榜 + Summary 摘要 alpha/noise/contra 數量。 " +
            "params: days (預設 30, 最多 180), horizonHours (預設 24), hitThresholdPct (預設 0.5), " +
            "symbol (預設 BTCUSDT), minSampleN (預設 3, 低於此標 ⏳), " +
            "sortBy (hit_rate/avg_return/sample_n/tier, 預設 hit_rate), groupByTier (預設 true)")
    public String scanIndicatorAccuracy(Integer days, Integer horizonHours, Double hitThresholdPct,
                                         String symbol, Integer minSampleN, String sortBy, Boolean groupByTier) {
        try {
            return accuracyScanner.scan(days, horizonHours, hitThresholdPct, symbol, minSampleN, sortBy, groupByTier);
        } catch (Exception e) {
            log.warn("[scanIndicatorAccuracy] failed", e);
            return "❌ 失敗: " + e.getMessage();
        }
    }

    // ==========================================================================
    // #339 indicatorAccuracyHourMatrix — 對單 source 拆 24 hour-of-day bucket
    // ==========================================================================

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "#339 V2 — 對指定 source/filter 拆 24 個 hour-of-day (UTC) bucket 各自算事後正確率，" +
            "揭露『BTC 漲跌是否有時段偏好』。配 #338 leaderboard 用：先 scan 找 alpha 候選 → 再用此工具看哪些時段最強。 " +
            "params: source (必填，tg_indicator/mih_threshold), filter (必填), horizonHours (預設 24), " +
            "hitThresholdPct (預設 0.5), days (預設 30, 最多 180), symbol (預設 BTCUSDT), " +
            "minSampleN (V2: 每 hour bucket 最少 sample 才算 best/worst, 預設 3), " +
            "showSessionBreakdown (V2: ASIA/EU/US 三段彙總, 預設 true). " +
            "輸出：24-row 表 + Best/Worst hour + spread + Session breakdown")
    public String indicatorAccuracyHourMatrix(String source, String filter, Integer horizonHours,
                                               Double hitThresholdPct, Integer days, String symbol,
                                               Integer minSampleN, Boolean showSessionBreakdown) {
        try {
            return hourMatrixService.matrix(source, filter, horizonHours, hitThresholdPct, days, symbol,
                    minSampleN, showSessionBreakdown);
        } catch (Exception e) {
            log.warn("[indicatorAccuracyHourMatrix] failed source={} filter={}", source, filter, e);
            return "❌ 失敗: " + e.getMessage();
        }
    }

    // ==========================================================================
    // #352 runAlphaPromotionTracker — 手動觸發（cron 每週日 09:00 UTC 自動跑）
    // ==========================================================================

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "#352 V1 — 手動觸發 alpha promotion tracker（cron 每週日 09:00 UTC 自動）。" +
            "比對上週 snapshot 找：① 新升級 alpha (lowN→n≥10 hit≥60%) ② 新淪為 contra (alpha→hit<40% 反向)。" +
            "第一次跑只寫 baseline。有變更則發 TG。")
    public String runAlphaPromotionTracker() {
        try {
            return promotionTracker.scanAndCompare();
        } catch (Exception e) {
            log.warn("[runAlphaPromotionTracker] failed", e);
            return "❌ 失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC})
    @Tool(description = "#355 V1 — 對齊 OKX SPOT trade history 與 DB (bt_grid_level + bt_live_signal) 的記錄，" +
            "抓 distributed-tx gap 造成的孤兒（OKX 有成交但 DB 沒寫入，或反之）。" +
            "支援 #340 真因排查：每筆 OKX trade 用 price+qty+time tolerance scoring 找最佳 DB 匹配，標 ✅ matched / ❌ orphan OKX / ⚠️ orphan DB。" +
            "params: currency(預設BTC), hoursBack(預設24，最多168), priceTolerance(預設10 USDT), qtyTolerancePct(預設0.5), timeToleranceMinutes(預設5), includeFixSuggestion(預設false)")
    public String reconcileOrphanTrades(String currency, Integer hoursBack,
                                          Double priceTolerance, Double qtyTolerancePct,
                                          Integer timeToleranceMinutes,
                                          Boolean includeFixSuggestion) {
        try {
            return orphanReconciler.reconcile(
                    currency != null ? currency : "BTC",
                    hoursBack != null ? hoursBack : 24,
                    priceTolerance != null ? priceTolerance : 10.0,
                    qtyTolerancePct != null ? qtyTolerancePct : 0.5,
                    timeToleranceMinutes != null ? timeToleranceMinutes : 5,
                    Boolean.TRUE.equals(includeFixSuggestion));
        } catch (Exception e) {
            log.warn("[reconcileOrphanTrades] failed", e);
            return "❌ 失敗: " + e.getMessage();
        }
    }
}
