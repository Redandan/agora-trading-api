package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.BtDecisionAudit;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trading manager one-stop read-only cockpit.
 *
 * <p>This tool intentionally composes existing MCP read/diagnostic tools instead
 * of duplicating business logic. It gives an AI operator a stable first call
 * for routine trading oversight, while keeping heavy checks behind {@code deep}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingManagerMcpTools {
    private static final String REGIME_FIX_UTC = "2026-05-06T07:21:41Z";
    private static final Pattern DECISION_ID_PATTERN = Pattern.compile("decisionId\\s*[=:]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSITION_ID_PATTERN = Pattern.compile("positionId\\s*[=:]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGNAL_ID_PATTERN = Pattern.compile("liveSignalId\\s*[=:]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final ReportMcpTools reportMcpTools;
    private final PositionMcpTools positionMcpTools;
    private final StrategyManagementMcpTools strategyManagementMcpTools;
    private final MarketDataMcpTools marketDataMcpTools;
    private final IndicatorMcpTools indicatorMcpTools;
    private final DiagnosticMcpTools diagnosticMcpTools;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final ObjectMapper objectMapper;
    private final com.agora.service.trading.PositionSizingService positionSizingService;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.REPORTING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.META})
    @Tool(description = "交易經理一站式巡檢（只讀）：彙總帳戶/倉位、OCO、系統健康、指標新鮮度、Grid、Gemini Advisor、AI quota。" +
            "deep=true 時額外跑 verifyStrategyExecution 與 analyzeBlockedSignalOutcomes，適合每日/部署後深度檢查；" +
            "deep=false 適合快速看盤。params: deep=false, days=深度分析回溯天數(預設1,最多14), symbol=BTCUSDT")
    public String getTradingManagerDigest(
            @ToolParam(required = false, description = "是否執行較重的策略驗證與 blocked signal 事後分析") Boolean deep,
            @ToolParam(required = false, description = "deep=true 時回溯天數，預設 1，最多 14") Integer days,
            @ToolParam(required = false, description = "巡檢交易對，預設 BTCUSDT") String symbol) {
        boolean deepMode = Boolean.TRUE.equals(deep);
        int d = days != null ? Math.min(Math.max(days, 1), 14) : 1;
        String sym = (symbol == null || symbol.isBlank()) ? "BTCUSDT" : symbol.trim().toUpperCase();

        List<SectionResult> sections = new ArrayList<>();
        sections.add(section("1. Current Portfolio", () -> reportMcpTools.getReport("now", "trading", "tg")));
        sections.add(section("2. OCO Protection", positionMcpTools::getOcoHealth));
        sections.add(section("3. System Health", marketDataMcpTools::getSystemHealth));
        sections.add(section("4. Indicator Freshness", () -> indicatorMcpTools.getCollectionFreshness(sym)));
        sections.add(section("7. Opportunity Scan", () -> positionMcpTools.scanOpportunities(7, "MODERATE")));
        sections.add(section("8. Gemini Advisor", marketDataMcpTools::getGeminiAdvisorStatus));
        sections.add(section("9. AI Quota", reportMcpTools::checkAiQuota));
        sections.add(section("10. Monthly PnL", () -> reportMcpTools.getMonthlyPnlOverview(3)));
        sections.add(section("11. Strategy Regime Filters", () -> strategyManagementMcpTools.getStrategyRegimeFilterStatus(true)));
        sections.add(section("12. Current Startup Log Issues", () -> diagnosticMcpTools.getCurrentStartupLogIssues(20)));
        sections.add(section("13. Shadow Readiness", () -> strategyManagementMcpTools.getShadowReadinessDashboard(30, 0.33)));
        sections.add(section("14. Intraday Extremes", () -> diagnosticMcpTools.getIntradayExtremesDigest(sym)));
        sections.add(section("15. Missed Opportunity Check",
                () -> diagnosticMcpTools.analyzeMissedTradingOpportunities(sym, 24, 1.0, null, null)));
        sections.add(section("16. Stop Sweep Risk", () -> positionMcpTools.analyzeStopSweepRisk(sym, d, null, null)));
        sections.add(section("17. TP Stretch Protection", () -> positionMcpTools.analyzeTpStretchProtection(sym)));
        sections.add(section("18. Spot Wick-Aware Exit Plan", () -> positionMcpTools.analyzeSpotWickAwarePlan(sym, 2, null)));
        sections.add(section("19. Spot Anti-Wick Policy Coverage", () -> positionMcpTools.analyzeSpotAntiWickPolicyCoverage(sym)));

        if (deepMode) {
            sections.add(section("20. Strategy Execution Verification", () -> diagnosticMcpTools.verifyStrategyExecution(d)));
            sections.add(section("21. Blocked Signal Outcomes", () -> getBlockedSignalOutcomeDigest(d, sym)));
            sections.add(section("22. Backfill Status", indicatorMcpTools::getBackfillStatus));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Trading Manager Digest ===\n");
        sb.append("generated_at: ").append(LocalDateTime.now(ZoneOffset.UTC)).append(" UTC\n");
        sb.append("mode: ").append(deepMode ? "DEEP" : "FAST").append(" | symbol: ").append(sym).append("\n\n");
        sb.append(buildActionSummaryText(sections, deepMode, d)).append("\n");
        for (SectionResult result : sections) {
            appendSection(sb, result);
        }
        if (!deepMode) {
            sb.append("=== 19. Deep Checks Skipped ===\n");
            sb.append("Use getTradingManagerDigest(deep=true, days=").append(d)
                    .append(") for strategy verification, blocked-signal outcomes, and backfill status.\n");
        }

        return sb.toString();
    }

    private SectionResult section(String title, Supplier<String> supplier) {
        long start = System.currentTimeMillis();
        try {
            String result = supplier.get();
            String text = result == null || result.isBlank() ? "(empty)" : result.trim();
            return new SectionResult(title, text, System.currentTimeMillis() - start, false);
        } catch (Exception e) {
            log.warn("[TradingManagerDigest] section '{}' failed: {}", title, e.getMessage(), e);
            return new SectionResult(title, "ERROR: " + e.getMessage(), System.currentTimeMillis() - start, true);
        }
    }

    private String getBlockedSignalOutcomeDigest(int days, String symbol) {
        String rolling = marketDataMcpTools.analyzeBlockedSignalOutcomes(days, symbol, null);
        String postFix = marketDataMcpTools.analyzeBlockedSignalOutcomesWindow(REGIME_FIX_UTC, null, symbol, null);
        BlockedOutcomeStatus status = classifyBlockedOutcomeStatus(rolling, postFix);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Mixed-Window Guard ===\n");
        sb.append("regimeFixUtc: ").append(REGIME_FIX_UTC).append("\n");
        sb.append("status: ").append(status.name()).append("\n");
        sb.append("currentRecurrence: ").append(status.currentRecurrence).append("\n");
        sb.append("operatorAction: ").append(status.operatorAction).append("\n\n");
        sb.append("=== Full Rolling Window ===\n");
        sb.append(rolling).append("\n");
        sb.append("=== Post-RegimeFix Window ===\n");
        sb.append(postFix);
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.REPORTING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.META})
    @Tool(description = "交易執行經理只讀行動摘要：只輸出 CRITICAL/WARN/ACTIONS/WATCH/DO_NOT/INFO，不附完整巡檢明細。" +
            "適合每日報告內嵌；不下單、不改 OCO/策略/Grid/資金。params: deep=false, days=1, symbol=BTCUSDT")
    public String getTradingManagerActionSummary(
            @ToolParam(required = false, description = "是否執行較重的策略驗證與 blocked signal 事後分析") Boolean deep,
            @ToolParam(required = false, description = "deep=true 時回溯天數，預設 1，最多 14") Integer days,
            @ToolParam(required = false, description = "巡檢交易對，預設 BTCUSDT") String symbol) {
        boolean deepMode = Boolean.TRUE.equals(deep);
        int d = days != null ? Math.min(Math.max(days, 1), 14) : 1;
        String sym = (symbol == null || symbol.isBlank()) ? "BTCUSDT" : symbol.trim().toUpperCase();

        List<SectionResult> sections = buildDigestSections(deepMode, d, sym);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Trading Manager Action Summary ===\n");
        sb.append("generated_at: ").append(LocalDateTime.now(ZoneOffset.UTC)).append(" UTC\n");
        sb.append("mode: ").append(deepMode ? "DEEP" : "FAST").append(" | symbol: ").append(sym).append("\n");
        sb.append("boundary: READ_ONLY; no trading, OCO, strategy, grid, or fund behavior changed.\n\n");
        sb.append(buildActionSummaryText(sections, deepMode, d));
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.REPORTING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.META})
    @Tool(description = "交易複盤聚合包（read-only MVP）：單次返回 summary/metrics/evidenceRefs/generatedAtUtc。" +
            "內含 strategy execution、filter stats、signal accuracy、blocked outcomes、OCO health。params: days=1..30, symbol=BTCUSDT, deep=false")
    public String postmortemBundle(
            @ToolParam(required = false, description = "回溯天數，預設 1，最多 30") Integer days,
            @ToolParam(required = false, description = "交易對，預設 BTCUSDT") String symbol,
            @ToolParam(required = false, description = "是否啟用較重檢查，預設 false") Boolean deep) {
        int d = days != null ? Math.min(Math.max(days, 1), 30) : 1;
        String sym = (symbol == null || symbol.isBlank()) ? "BTCUSDT" : symbol.trim().toUpperCase();
        boolean deepMode = Boolean.TRUE.equals(deep);

        List<SectionResult> sections = new ArrayList<>();
        sections.add(section("strategyExecution", () -> diagnosticMcpTools.verifyStrategyExecution(d)));
        sections.add(section("filterStats", () -> marketDataMcpTools.analyzeFilterStats(d)));
        sections.add(section("signalAccuracy", () -> marketDataMcpTools.getSignalAccuracyReport(d)));
        sections.add(section("blockedOutcomes", () -> getBlockedSignalOutcomeDigest(d, sym)));
        sections.add(section("ocoHealth", positionMcpTools::getOcoHealth));
        if (deepMode) {
            sections.add(section("tradingManagerDigest", () -> getTradingManagerDigest(true, d, sym)));
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("summary", buildPostmortemSummary(sections, sym, d, deepMode));

        ObjectNode metrics = root.putObject("metrics");
        metrics.put("symbol", sym);
        metrics.put("days", d);
        metrics.put("deep", deepMode);
        metrics.put("sectionCount", sections.size());
        metrics.put("failedSectionCount", sections.stream().filter(SectionResult::failed).count());
        metrics.put("durationMsTotal", sections.stream().mapToLong(SectionResult::elapsedMs).sum());

        ArrayNode sectionMetrics = metrics.putArray("sections");
        for (SectionResult section : sections) {
            ObjectNode sec = sectionMetrics.addObject();
            sec.put("name", section.title());
            sec.put("durationMs", section.elapsedMs());
            sec.put("failed", section.failed());
        }

        ObjectNode evidenceRefs = root.putObject("evidenceRefs");
        evidenceRefs.set("decisionIds", extractIds(sections, DECISION_ID_PATTERN));
        evidenceRefs.set("positionIds", extractIds(sections, POSITION_ID_PATTERN));
        evidenceRefs.set("signalIds", extractIds(sections, SIGNAL_ID_PATTERN));

        ObjectNode raw = root.putObject("raw");
        for (SectionResult section : sections) {
            raw.put(section.title(), section.text());
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[postmortemBundle] serialization failed: {}", e.getMessage(), e);
            return "{ \"error\": \"postmortemBundle serialization failed\", \"message\": \"" + e.getMessage() + "\" }";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "事件窗口決策回放（read-only）：依 UTC 視窗列出 decision audit，支援 symbol/strategyId/eventTags(event_type) 篩選。" +
            "回傳 summary/metrics/evidenceRefs/generatedAtUtc。params: sinceUtc, untilUtc, symbol optional, strategyId optional, eventTags optional(comma-separated), limit=1..500")
    public String listDecisionWindow(
            @ToolParam(required = true, description = "視窗起始 UTC，ISO-8601，例如 2026-05-15T00:00:00Z") String sinceUtc,
            @ToolParam(required = true, description = "視窗結束 UTC，ISO-8601，例如 2026-05-16T00:00:00Z") String untilUtc,
            @ToolParam(required = false, description = "交易對，預設 ALL") String symbol,
            @ToolParam(required = false, description = "策略 ID，預設 ALL") Long strategyId,
            @ToolParam(required = false, description = "事件標籤（event_type）逗號分隔，例如 FILTER_BLOCK,ENTRY_SKIP") String eventTags,
            @ToolParam(required = false, description = "最多回傳筆數，預設 200，最大 500") Integer limit) {
        LocalDateTime since = parseUtcOrNull(sinceUtc);
        LocalDateTime until = parseUtcOrNull(untilUtc);
        if (since == null || until == null) {
            return "❌ sinceUtc/untilUtc must be valid ISO-8601 UTC timestamps (e.g. 2026-05-15T00:00:00Z)";
        }
        if (until.isBefore(since)) {
            return "❌ untilUtc must be after sinceUtc";
        }
        String sym = (symbol == null || symbol.isBlank()) ? null : symbol.trim().toUpperCase();
        int lim = limit == null ? 200 : Math.min(Math.max(limit, 1), 500);
        List<String> eventTypes = parseEventTypes(eventTags);
        boolean emptyEventTypes = eventTypes.isEmpty();

        List<BtDecisionAudit> rows = decisionAuditRepository.findWindow(
                since, until, sym, strategyId, emptyEventTypes, emptyEventTypes ? Collections.singletonList("__NONE__") : eventTypes,
                org.springframework.data.domain.PageRequest.of(0, lim));

        ObjectNode root = objectMapper.createObjectNode();
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("summary", "listDecisionWindow collected " + rows.size() + " decision row(s).");

        ObjectNode metrics = root.putObject("metrics");
        metrics.put("sinceUtc", since.toString() + "Z");
        metrics.put("untilUtc", until.toString() + "Z");
        metrics.put("symbol", sym == null ? "ALL" : sym);
        metrics.put("strategyId", strategyId == null ? -1 : strategyId);
        metrics.put("eventTagFilter", emptyEventTypes ? "ALL" : String.join(",", eventTypes));
        metrics.put("count", rows.size());
        metrics.put("limit", lim);

        ObjectNode evidence = root.putObject("evidenceRefs");
        ArrayNode decisionIds = evidence.putArray("decisionIds");
        ArrayNode signalIds = evidence.putArray("signalIds");
        ArrayNode positionIds = evidence.putArray("positionIds");
        Set<Long> seenSignal = new LinkedHashSet<>();
        for (BtDecisionAudit row : rows) {
            if (row.getId() != null) {
                decisionIds.add(row.getId());
            }
            if (row.getLiveSignalId() != null && seenSignal.add(row.getLiveSignalId())) {
                signalIds.add(row.getLiveSignalId());
            }
        }
        // Decision audit does not directly persist positionId; keep shape stable.
        positionIds.removeAll();

        ArrayNode items = root.putArray("items");
        for (BtDecisionAudit row : rows) {
            ObjectNode item = items.addObject();
            item.put("decisionId", row.getId() == null ? -1 : row.getId());
            item.put("eventTimeUtc", row.getEventTime() == null ? "" : row.getEventTime().toString() + "Z");
            item.put("strategyId", row.getStrategyId() == null ? -1 : row.getStrategyId());
            item.put("symbol", row.getSymbol() == null ? "" : row.getSymbol());
            item.put("intervalCode", row.getIntervalCode() == null ? "" : row.getIntervalCode());
            item.put("eventType", row.getEventType() == null ? "" : row.getEventType());
            item.put("outcome", row.getOutcome() == null ? "" : row.getOutcome());
            item.put("blocker", row.getBlocker() == null ? "" : row.getBlocker());
            item.put("reason", row.getReason() == null ? "" : row.getReason());
            item.put("liveSignalId", row.getLiveSignalId() == null ? -1 : row.getLiveSignalId());
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[listDecisionWindow] serialization failed: {}", e.getMessage(), e);
            return "❌ listDecisionWindow serialization failed: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "事件窗口標準化複盤（read-only MVP）：輸出市場摘要、系統風險訊號、實際動作、missed-profit/missed-protection 歸因與修復建議。" +
            "params: sinceUtc, untilUtc, symbol, eventName optional")
    public String runEventPostmortem(
            @ToolParam(required = true, description = "視窗起始 UTC，ISO-8601，例如 2026-05-15T00:00:00Z") String sinceUtc,
            @ToolParam(required = true, description = "視窗結束 UTC，ISO-8601，例如 2026-05-16T00:00:00Z") String untilUtc,
            @ToolParam(required = true, description = "交易對，例如 BTCUSDT") String symbol,
            @ToolParam(required = false, description = "事件名稱（可選）") String eventName) {
        LocalDateTime since = parseUtcOrNull(sinceUtc);
        LocalDateTime until = parseUtcOrNull(untilUtc);
        if (since == null || until == null) {
            return "❌ sinceUtc/untilUtc must be valid ISO-8601 UTC timestamps (e.g. 2026-05-15T00:00:00Z)";
        }
        if (until.isBefore(since)) {
            return "❌ untilUtc must be after sinceUtc";
        }
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        if (sym.isBlank()) {
            return "❌ symbol is required";
        }

        long hours = Math.max(1L, java.time.Duration.between(since, until).toHours());
        int days = (int) Math.min(30L, Math.max(1L, (hours + 23L) / 24L));

        List<BtDecisionAudit> rows = decisionAuditRepository.findWindow(
                since, until, sym, null, true, Collections.singletonList("__NONE__"),
                org.springframework.data.domain.PageRequest.of(0, 500));

        long filterBlocks = rows.stream().filter(r -> "FILTER_BLOCK".equals(r.getEventType())).count();
        long entrySkips = rows.stream().filter(r -> "ENTRY_SKIP".equals(r.getEventType())).count();
        long autoTradeOk = rows.stream().filter(r -> "AUTOTRADE_OK".equals(r.getEventType())).count();
        long autoTradeFail = rows.stream().filter(r -> "AUTOTRADE_FAIL".equals(r.getEventType())).count();
        long exitCount = rows.stream().filter(r -> "EXIT".equals(r.getEventType())).count();
        long blockedLike = rows.stream().filter(r -> "BLOCKED".equals(r.getOutcome())).count();

        String blockedOutcomes = marketDataMcpTools.analyzeBlockedSignalOutcomesWindow(
                since.toString() + "Z", until.toString() + "Z", sym, "1h");
        String opportunity = diagnosticMcpTools.analyzeMissedTradingOpportunities(sym, (int) Math.min(hours, 72L), 1.0, null, null);
        String ocoHealth = positionMcpTools.getOcoHealth();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("summary", buildEventPostmortemSummary(eventName, sym, since, until, rows.size(), autoTradeOk, blockedLike));

        ObjectNode metrics = root.putObject("metrics");
        metrics.put("eventName", eventName == null || eventName.isBlank() ? "N/A" : eventName.trim());
        metrics.put("symbol", sym);
        metrics.put("sinceUtc", since.toString() + "Z");
        metrics.put("untilUtc", until.toString() + "Z");
        metrics.put("windowHours", hours);
        metrics.put("derivedDaysForSupportingChecks", days);
        metrics.put("decisionCount", rows.size());
        metrics.put("filterBlockCount", filterBlocks);
        metrics.put("entrySkipCount", entrySkips);
        metrics.put("autoTradeOkCount", autoTradeOk);
        metrics.put("autoTradeFailCount", autoTradeFail);
        metrics.put("exitCount", exitCount);
        metrics.put("blockedOutcomeCount", blockedLike);

        ObjectNode evidenceRefs = root.putObject("evidenceRefs");
        ArrayNode decisionIds = evidenceRefs.putArray("decisionIds");
        ArrayNode signalIds = evidenceRefs.putArray("signalIds");
        ArrayNode positionIds = evidenceRefs.putArray("positionIds");
        Set<Long> seenSignal = new LinkedHashSet<>();
        for (BtDecisionAudit row : rows) {
            if (row.getId() != null) {
                decisionIds.add(row.getId());
            }
            if (row.getLiveSignalId() != null && seenSignal.add(row.getLiveSignalId())) {
                signalIds.add(row.getLiveSignalId());
            }
        }
        positionIds.removeAll();

        ObjectNode sections = root.putObject("sections");
        sections.put("marketMoveSummary", blockedOutcomes);
        sections.put("riskSignalsSeen", summarizeRiskSignals(rows));
        sections.put("actionsExecuted", summarizeActions(rows));
        sections.put("missedAttribution", summarizeMissedAttribution(rows, opportunity));
        sections.put("repairSuggestions", buildRepairSuggestions(rows, ocoHealth, blockedOutcomes, opportunity));
        sections.put("ocoHealth", ocoHealth);
        sections.put("windowDecisionSampleTop", buildWindowSample(rows));

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[runEventPostmortem] serialization failed: {}", e.getMessage(), e);
            return "❌ runEventPostmortem serialization failed: " + e.getMessage();
        }
    }

    private List<SectionResult> buildDigestSections(boolean deepMode, int d, String sym) {
        List<SectionResult> sections = new ArrayList<>();
        sections.add(section("1. Current Portfolio", () -> reportMcpTools.getReport("now", "trading", "tg")));
        sections.add(section("2. OCO Protection", positionMcpTools::getOcoHealth));
        sections.add(section("3. System Health", marketDataMcpTools::getSystemHealth));
        sections.add(section("4. Indicator Freshness", () -> indicatorMcpTools.getCollectionFreshness(sym)));
        sections.add(section("7. Opportunity Scan", () -> positionMcpTools.scanOpportunities(7, "MODERATE")));
        sections.add(section("8. Gemini Advisor", marketDataMcpTools::getGeminiAdvisorStatus));
        sections.add(section("9. AI Quota", reportMcpTools::checkAiQuota));
        sections.add(section("10. Monthly PnL", () -> reportMcpTools.getMonthlyPnlOverview(3)));
        sections.add(section("11. Strategy Regime Filters", () -> strategyManagementMcpTools.getStrategyRegimeFilterStatus(true)));
        sections.add(section("12. Current Startup Log Issues", () -> diagnosticMcpTools.getCurrentStartupLogIssues(20)));
        sections.add(section("13. Shadow Readiness", () -> strategyManagementMcpTools.getShadowReadinessDashboard(30, 0.33)));
        sections.add(section("14. Intraday Extremes", () -> diagnosticMcpTools.getIntradayExtremesDigest(sym)));
        sections.add(section("15. Missed Opportunity Check",
                () -> diagnosticMcpTools.analyzeMissedTradingOpportunities(sym, 24, 1.0, null, null)));
        sections.add(section("16. Stop Sweep Risk", () -> positionMcpTools.analyzeStopSweepRisk(sym, d, null, null)));
        sections.add(section("17. TP Stretch Protection", () -> positionMcpTools.analyzeTpStretchProtection(sym)));
        sections.add(section("18. Spot Wick-Aware Exit Plan", () -> positionMcpTools.analyzeSpotWickAwarePlan(sym, 2, null)));
        sections.add(section("19. Spot Anti-Wick Policy Coverage", () -> positionMcpTools.analyzeSpotAntiWickPolicyCoverage(sym)));

        if (deepMode) {
            sections.add(section("20. Strategy Execution Verification", () -> diagnosticMcpTools.verifyStrategyExecution(d)));
            sections.add(section("21. Blocked Signal Outcomes", () -> getBlockedSignalOutcomeDigest(d, sym)));
            sections.add(section("22. Backfill Status", indicatorMcpTools::getBackfillStatus));
        }
        return sections;
    }

    private String buildPostmortemSummary(List<SectionResult> sections, String symbol, int days, boolean deepMode) {
        long failed = sections.stream().filter(SectionResult::failed).count();
        return "postmortemBundle(" + symbol + ", days=" + days + ", deep=" + deepMode + ") collected "
                + sections.size() + " sections; failed=" + failed
                + (failed > 0 ? " (check raw for failed section details)." : " (all sections collected).");
    }

    private ArrayNode extractIds(List<SectionResult> sections, Pattern pattern) {
        Set<Long> ids = new LinkedHashSet<>();
        for (SectionResult section : sections) {
            Matcher matcher = pattern.matcher(section.text());
            while (matcher.find()) {
                try {
                    ids.add(Long.parseLong(matcher.group(1)));
                } catch (NumberFormatException ignore) {
                    // Keep parsing other matches.
                }
            }
        }
        ArrayNode arr = objectMapper.createArrayNode();
        ids.forEach(arr::add);
        return arr;
    }

    private LocalDateTime parseUtcOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(text.trim()).atOffset(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseEventTypes(String eventTags) {
        if (eventTags == null || eventTags.isBlank()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String raw : eventTags.split(",")) {
            String v = raw == null ? "" : raw.trim().toUpperCase();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private String buildEventPostmortemSummary(String eventName,
                                               String symbol,
                                               LocalDateTime since,
                                               LocalDateTime until,
                                               int rowCount,
                                               long autoTradeOk,
                                               long blockedLike) {
        String event = (eventName == null || eventName.isBlank()) ? "N/A" : eventName.trim();
        return "runEventPostmortem(" + event + ", " + symbol + ", " + since + "Z.." + until + "Z) rows=" + rowCount
                + ", autotraded=" + autoTradeOk + ", blocked=" + blockedLike + ".";
    }

    private String summarizeRiskSignals(List<BtDecisionAudit> rows) {
        long filter = rows.stream().filter(r -> "FILTER_BLOCK".equals(r.getEventType())).count();
        long skip = rows.stream().filter(r -> "ENTRY_SKIP".equals(r.getEventType())).count();
        long fail = rows.stream().filter(r -> "AUTOTRADE_FAIL".equals(r.getEventType())).count();
        return "FILTER_BLOCK=" + filter + ", ENTRY_SKIP=" + skip + ", AUTOTRADE_FAIL=" + fail;
    }

    private String summarizeActions(List<BtDecisionAudit> rows) {
        long ok = rows.stream().filter(r -> "AUTOTRADE_OK".equals(r.getEventType())).count();
        long exit = rows.stream().filter(r -> "EXIT".equals(r.getEventType())).count();
        long override = rows.stream().filter(r -> "OVERRIDE_APPLIED".equals(r.getEventType())).count();
        return "AUTOTRADE_OK=" + ok + ", EXIT=" + exit + ", OVERRIDE_APPLIED=" + override;
    }

    private String summarizeMissedAttribution(List<BtDecisionAudit> rows, String opportunityText) {
        long blocked = rows.stream().filter(r -> "BLOCKED".equals(r.getOutcome())).count();
        if (blocked == 0) {
            return "No blocked decision outcome inside the window; missed cases are not prominent in this sample.";
        }
        return "Blocked outcomes detected (" + blocked + "). Correlate with opportunity scan: " + opportunityText;
    }

    private String buildRepairSuggestions(List<BtDecisionAudit> rows,
                                          String ocoHealth,
                                          String blockedOutcomes,
                                          String opportunityText) {
        List<String> suggestions = new ArrayList<>();
        long autoTradeFail = rows.stream().filter(r -> "AUTOTRADE_FAIL".equals(r.getEventType())).count();
        long filterBlocks = rows.stream().filter(r -> "FILTER_BLOCK".equals(r.getEventType())).count();
        if (autoTradeFail > 0) {
            suggestions.add("Prioritize AUTOTRADE_FAIL root-cause and retry policy tightening.");
        }
        if (filterBlocks > 0 && blockedOutcomes.contains("過濾器可能過度保守")) {
            suggestions.add("Run filter calibration issue flow before relaxing live filters.");
        }
        if (hasOcoIssue(ocoHealth)) {
            suggestions.add("Review OCO sync errors before enabling larger risk in similar event windows.");
        }
        if (opportunityText.contains("FILTER_BLOCK_REVIEW") || opportunityText.contains("MISSED_CANDIDATE")) {
            suggestions.add("Open a postmortem follow-up issue for missed-profit/missed-protection evidence chain.");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("No immediate repair trigger from MVP heuristics; continue monitoring with the next event window.");
        }
        return String.join(" ", suggestions);
    }

    private String buildWindowSample(List<BtDecisionAudit> rows) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (BtDecisionAudit row : rows) {
            if (shown >= 10) break;
            sb.append("#").append(row.getId() == null ? "N/A" : row.getId())
                    .append(" ").append(row.getEventTime() == null ? "N/A" : row.getEventTime()).append("Z")
                    .append(" ").append(row.getEventType() == null ? "-" : row.getEventType())
                    .append("/").append(row.getOutcome() == null ? "-" : row.getOutcome())
                    .append(" ").append(row.getReason() == null ? "" : row.getReason())
                    .append("\n");
            shown++;
        }
        return sb.toString().trim();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "只讀 preview：依 entry/tp/sl/nnOutput/freeUsdt 計算 Position Sizing 建議，不下單、不改 OCO/策略/資金。" +
            "params: symbol=BTCUSDT, strategyId optional, entry, tp, sl, nnOutput, legacyAmountUsdt(default current base path), availableUsdt optional")
    public String previewPositionSizing(
            @ToolParam(required = false, description = "交易對，預設 BTCUSDT") String symbol,
            @ToolParam(required = false, description = "策略 ID，僅用於輸出標示") Long strategyId,
            @ToolParam(required = true, description = "預估/實際 entry price") BigDecimal entry,
            @ToolParam(required = true, description = "take profit price") BigDecimal tp,
            @ToolParam(required = true, description = "stop loss price") BigDecimal sl,
            @ToolParam(required = false, description = "信心分數 nnOutput，預設 0.80") Double nnOutput,
            @ToolParam(required = false, description = "現行 legacy 下單金額，預設 50") Double legacyAmountUsdt,
            @ToolParam(required = false, description = "可用 USDT；提供後會套用 free buffer cap") Double availableUsdt) {
        String sym = (symbol == null || symbol.isBlank()) ? "BTCUSDT" : symbol.trim().toUpperCase();
        double nn = nnOutput == null ? 0.80 : nnOutput;
        double legacy = legacyAmountUsdt != null && legacyAmountUsdt > 0 ? legacyAmountUsdt : 50.0;
        com.agora.service.trading.PositionSizingService.PositionSizingDecision d =
                positionSizingService.calculate(sym, strategyId, entry, tp, sl, nn, legacy, availableUsdt);

        return "=== Position Sizing Preview ===\n"
                + "boundary: READ_ONLY; no trading, OCO, strategy, grid, or fund behavior changed.\n"
                + "symbol=" + sym + " strategyId=" + (strategyId != null ? strategyId : "N/A") + "\n"
                + "entry=" + entry + " tp=" + tp + " sl=" + sl + " nnOutput=" + nn + "\n"
                + "legacyAmountUsdt=" + fmt(d.legacyAmountUsdt()) + "\n"
                + "recommendedAmountUsdt=" + fmt(d.recommendedAmountUsdt()) + "\n"
                + "finalAmountUsdt=" + fmt(d.finalAmountUsdt()) + " mode=" + (d.liveEnabled() ? "LIVE" : "SHADOW") + "\n"
                + "slDistancePct=" + fmt(d.slDistancePct() * 100.0) + "% tpDistancePct=" + fmt(d.tpDistancePct() * 100.0)
                + "% rr=" + fmt(d.riskReward()) + "\n"
                + "riskBudgetUsdt=" + fmt(d.riskBudgetUsdt()) + " availableUsdt="
                + (d.availableUsdt() != null ? fmt(d.availableUsdt()) : "N/A") + "\n"
                + "recommendedSlRiskUsdt=" + fmt(d.recommendedAmountUsdt() * d.slDistancePct()) + "\n"
                + "reason=" + d.reason() + "\n"
                + "explain=" + d.explain() + "\n"
                + "operatorAction=" + (d.liveEnabled()
                    ? "LIVE sizing enabled; verify caps before allowing new exposure."
                    : "Shadow only; compare recommended vs actual over several trades before enabling live sizing.");
    }

    private BlockedOutcomeStatus classifyBlockedOutcomeStatus(String rolling, String postFix) {
        int postSamples = extractAnalyzedSampleCount(postFix);
        boolean postHasNoRows = postFix.contains("無符合條件的 FILTER_BLOCK");
        boolean postSuggestsOverConservative = postFix.contains("過濾器可能過度保守") || postFix.contains("falseKill 100.0%");
        boolean rollingSuggestsOverConservative = rolling.contains("過濾器可能過度保守") || rolling.contains("falseKill 100.0%");

        if (postHasNoRows || postSamples == 0) {
            return rollingSuggestsOverConservative
                    ? BlockedOutcomeStatus.HISTORICAL_ONLY
                    : BlockedOutcomeStatus.NO_SIGNAL;
        }
        if (postSamples >= 30 && postSuggestsOverConservative) {
            return BlockedOutcomeStatus.CURRENT_CONFIRMED;
        }
        return BlockedOutcomeStatus.CURRENT_WATCH;
    }

    private int extractAnalyzedSampleCount(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("分析\\s+(\\d+)\\s+筆").matcher(text);
        if (!matcher.find()) {
            return -1;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String buildActionSummaryText(List<SectionResult> sections, boolean deepMode, int days) {
        List<String> critical = new ArrayList<>();
        List<String> warn = new ArrayList<>();
        List<String> info = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> watch = new ArrayList<>();
        List<String> doNot = new ArrayList<>();

        for (SectionResult section : sections) {
            classifySection(section, critical, warn, info, actions, watch, doNot);
        }
        if (!deepMode) {
            info.add("Deep checks skipped; use deep=true, days=" + days + " for execution verification and blocked-signal outcomes.");
            watch.add("Run deep=true before changing strategy parameters or activating shadow strategies.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Action Summary ===\n");
        appendBucket(sb, "CRITICAL", critical, "No immediate critical trading/system issue detected.");
        appendBucket(sb, "WARN", warn, "No warning-level issue detected.");
        appendBucket(sb, "ACTIONS", actions, "No read-only scanner action requires execution right now.");
        appendBucket(sb, "WATCH", watch, "No watch item.");
        appendBucket(sb, "DO_NOT", doNot, "No explicit avoid item.");
        appendBucket(sb, "INFO", info, "No informational note.");
        sb.append("\n");
        return sb.toString();
    }

    private void classifySection(SectionResult section, List<String> critical, List<String> warn, List<String> info,
                                 List<String> actions, List<String> watch, List<String> doNot) {
        String title = section.title();
        String text = section.text();
        if (section.failed()) {
            critical.add(title + " failed while collecting digest data.");
        }
        if (title.contains("OCO") && hasOcoIssue(text)) {
            critical.add("OCO protection needs review: non-zero sync/error count.");
        }
        if (title.contains("System Health") && (text.contains("❌") || text.contains("DOWN") || text.contains("REFUSING_TRAFFIC"))) {
            critical.add("System health reports a failing dependency or readiness issue.");
        }
        if (title.contains("Current Startup Log Issues")) {
            if (hasNonZeroMetric(text, "Connection leak") || hasNonZeroMetric(text, "Auth rejected")) {
                critical.add("Current startup log has connection-leak or auth/security hits after cutoff.");
            } else if (hasNonZeroMetric(text, "WARN") || hasNonZeroMetric(text, "Slow startup")
                    || hasNonZeroMetric(text, "ERROR") || hasNonZeroMetric(text, "AI 429")
                    || hasNonZeroMetric(text, "Duplicate key")) {
                warn.add(describeStartupWarning(text));
            } else {
                info.add("Startup log is clean after current JVM cutoff.");
            }
        }
        if (title.contains("Indicator Freshness")) {
            String staleSummary = describeStaleIndicators(text);
            if (staleSummary != null) {
                warn.add(staleSummary);
                watch.add("Check stale market data collector before relying on affected indicator signals.");
            }
        }
        if (title.contains("Grid Alignment") && (text.contains("OUT_OF_RANGE") || text.contains("越界") || text.contains("需調整"))) {
            warn.add("Grid alignment may need range review.");
        }
        if (title.contains("Opportunity Scan")) {
            classifyOpportunityScan(text, actions, watch, doNot, warn);
        }
        if (title.contains("Shadow Readiness")) {
            if (hasReadyShadowStrategy(text)) {
                warn.add("At least one shadow strategy may be near activation; require manual assessActivationRisk before enabling.");
                watch.add("Review READY? shadow strategy with assessActivationRisk before any activation.");
            } else {
                info.add("No shadow strategy is ready for activation yet.");
                doNot.add("Do not activate shadow strategies yet; readiness dashboard has no READY? row.");
            }
        }
        if (title.contains("Missed Opportunity Check")) {
            classifyMissedOpportunityCheck(text, warn, info, actions, watch);
        }
        if (title.contains("Stop Sweep Risk")) {
            classifyStopSweepRisk(text, warn, info, actions, watch);
        }
        if (title.contains("TP Stretch Protection")) {
            classifyTpStretchProtection(text, warn, info, actions, watch);
        }
        if (title.contains("Spot Wick-Aware Exit Plan")) {
            classifySpotWickAwarePlan(text, warn, info, actions, watch, doNot);
        }
        if (title.contains("Spot Anti-Wick Policy Coverage")) {
            classifySpotAntiWickPolicyCoverage(text, warn, info, actions, watch);
        }
        if (title.contains("AI Quota") && (text.contains("429") || text.contains("exhaust") || text.contains("耗盡"))) {
            warn.add("AI quota/provider limit may affect advisor output.");
        }
        if (title.contains("Current Portfolio") && text.contains("有OCO")) {
            info.add("Open BTCUSDT position has OCO protection.");
        }
        if (title.contains("Blocked Signal Outcomes")) {
            classifyBlockedSignalOutcomeSection(text, warn, info, actions, watch);
        }
    }

    private void classifyMissedOpportunityCheck(String text, List<String> warn, List<String> info,
                                                List<String> actions, List<String> watch) {
        int missed = extractMetric(text, "missedOpportunityCount");
        int filterReview = extractMetric(text, "filterBlockReviewCount");
        int entrySkipReview = extractMetric(text, "entrySkipReviewCount");
        int executionFailureReview = extractMetric(text, "executionFailureReviewCount");
        int blockedCorrect = extractMetric(text, "blockedButCorrectCount");
        if (missed > 0) {
            warn.add("Missed opportunity checker found " + missed + " BUY row(s) with forward upside and no correlated execution/blocker.");
            actions.add("Review missedOpportunity candidates before changing strategy gates; do not chase current price from the daily report.");
        } else {
            info.add("Missed opportunity checker found no true missed trade candidate in the daily window.");
        }
        if (filterReview > 0) {
            watch.add("Missed opportunity checker found " + filterReview + " FILTER_BLOCK row(s) with forward upside; use blocked-signal outcome analysis before relaxing filters.");
        }
        if (entrySkipReview > 0) {
            watch.add("Missed opportunity checker found " + entrySkipReview + " ENTRY_SKIP row(s) with forward upside; review the named terminal gate instead of treating them as uncorrelated missed trades.");
        }
        if (executionFailureReview > 0) {
            warn.add("Missed opportunity checker found " + executionFailureReview + " AUTOTRADE_FAIL row(s); inspect exchange/order failure evidence.");
            actions.add("Review executionFailure candidates before changing strategy gates or retrying an order path.");
        }
        if (blockedCorrect > 0) {
            info.add("EntryDedup/existing exposure explained " + blockedCorrect + " BUY row(s); counted as risk control, not missed trades.");
        }
    }

    private void classifyTpStretchProtection(String text, List<String> warn, List<String> info,
                                             List<String> actions, List<String> watch) {
        int stretched = extractMetric(text, "stretched");
        int watched = extractMetric(text, "watch");
        if (stretched > 0) {
            warn.add("TP stretch protection flagged " + stretched + " OCO position(s): recent high got close to TP, missed it, then pulled back.");
            actions.add("Review TP_STRETCHED position(s) against recentExtremeTpCap plus read-only SL preview before any OCO change.");
        } else {
            info.add("TP stretch protection found no confirmed too-high TP pattern.");
        }
        if (watched > 0) {
            watch.add("TP stretch protection has " + watched + " watch item(s); re-check if pullback deepens or P(TP) drops.");
        }
    }

    private void classifyStopSweepRisk(String text, List<String> warn, List<String> info,
                                       List<String> actions, List<String> watch) {
        int tight = extractMetric(text, "initialSlTooTight");
        int swept = extractMetric(text, "stopSwept");
        int profitLocked = extractMetric(text, "profitLocked");
        if (tight > 0) {
            warn.add("Stop-sweep scanner found " + tight + " open position(s) whose initial SL is above BTC spot anti-wick policy stop.");
            actions.add("For next BTC spot entries, use ultra-low disaster SL plus risk-based sizing; skip if recommended amount is below min trade.");
        }
        if (swept > 0) {
            warn.add("Stop-sweep postmortem found " + swept + " recent SL trade(s) that recovered after stop-out.");
            actions.add("Review STOP_SWEPT trade(s); prefer BTC spot disaster SL with smaller size over tight wick-prone SL.");
        }
        if (tight == 0 && swept == 0) {
            info.add("Stop-sweep scanner found no immediate structural SL problem.");
        }
        if (profitLocked > 0) {
            watch.add("Stop-sweep scanner sees " + profitLocked + " profit-locked position(s); do not loosen those SLs.");
        }
    }

    private void classifySpotWickAwarePlan(String text, List<String> warn, List<String> info,
                                           List<String> actions, List<String> watch, List<String> doNot) {
        int vulnerable = extractMetric(text, "hardOcoWickVulnerable");
        int wickOnly = extractMetric(text, "wickOnly");
        int dca = extractMetric(text, "dcaCandidates");
        int softExit = extractMetric(text, "softExitReviews");
        if (softExit > 0) {
            warn.add("Wick-aware plan found " + softExit + " close-confirmed structural breakdown review(s).");
            actions.add("Review soft-exit candidate(s); do not average down into confirmed breakdown.");
            doNot.add("Do not DCA while wick-aware status is SOFT_EXIT_REVIEW.");
        }
        if (dca > 0) {
            watch.add("Wick-aware plan found " + dca + " DCA candidate(s); exposure/macro checks required before any add.");
        }
        if (vulnerable > 0) {
            warn.add("Wick-aware plan found " + vulnerable + " OCO position(s) with hard SL inside the wick zone.");
            actions.add("Use disaster/structural SL for next BTC spot entries; current live OCO changes still require explicit write approval.");
        }
        if (wickOnly > 0) {
            watch.add("Wick-aware plan saw " + wickOnly + " wick-only touch(es); avoid treating wick lows as confirmed exits.");
        }
        if (vulnerable == 0 && wickOnly == 0 && dca == 0 && softExit == 0) {
            info.add("Wick-aware plan found no current anti-wick action.");
        }
    }

    private void classifySpotAntiWickPolicyCoverage(String text, List<String> warn, List<String> info,
                                                    List<String> actions, List<String> watch) {
        int review = extractMetric(text, "review");
        int liveCovered = extractMetric(text, "liveCovered");
        int shadowCovered = extractMetric(text, "shadowCovered");
        int shortReview = extractMetric(text, "shortReview");
        if (review > 0) {
            warn.add("Anti-wick policy coverage found " + review + " enabled BTC spot strategy policy gap(s).");
            actions.add("Review BTC spot anti-wick policy gaps before relying on those strategies for live entries.");
        } else {
            info.add("BTC spot anti-wick policy coverage has no live LONG policy gap.");
        }
        if (liveCovered > 0) {
            info.add("Anti-wick policy covers " + liveCovered + " live BTC spot strategy/strategies.");
        }
        if (shadowCovered > 0) {
            watch.add("Anti-wick policy pre-covers " + shadowCovered + " shadow BTC spot strategy/strategies before promotion.");
        }
        if (shortReview > 0) {
            watch.add("Anti-wick coverage skipped " + shortReview + " SHORT/SWAP-capable strategy/strategies; separate leverage risk design required.");
        }
    }

    private void classifyBlockedSignalOutcomeSection(String text, List<String> warn, List<String> info,
                                                     List<String> actions, List<String> watch) {
        if (text.contains("status: CURRENT_CONFIRMED")) {
            warn.add("Post-fix blocked-signal false-kill recurrence is confirmed; calibration review is needed.");
            actions.add("Open approval-gated calibration work before changing live RegimeFilter or EnsembleGate settings.");
        } else if (text.contains("status: CURRENT_WATCH")) {
            watch.add("Post-fix blocked-signal samples exist but are not approval-ready; keep collecting evidence.");
        } else if (text.contains("status: HISTORICAL_ONLY")) {
            info.add("Blocked-signal false-kill warning is historical/mixed-window only; no current filter relaxation signal.");
        } else if (text.contains("status: NO_SIGNAL")) {
            info.add("No blocked-signal sample currently supports filter calibration.");
        }
    }

    private void classifyOpportunityScan(String text, List<String> actions, List<String> watch,
                                         List<String> doNot, List<String> warn) {
        if (text.contains("PORTFOLIO IDLE WARNING")) {
            warn.add("Opportunity scanner reports portfolio idle warning versus baseline.");
            watch.add("Portfolio idle warning: compare wait/regime flip, light spot, and Earn floor before allocating capital.");
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.contains("[SUBSCRIBE_EARN]")) {
                doNot.add(extractOpportunityLabel(trimmed)
                        + " — free USDT is reserved for upcoming investment deployment, not idle Earn allocation.");
                watch.add("Keep free USDT available for investment deployment instead of subscribing to Earn.");
            } else if (trimmed.contains("[HOLD_GRID]")) {
                watch.add(extractOpportunityLabel(trimmed) + ".");
            } else if (trimmed.contains("[HOLD_OCO]")) {
                watch.add(extractOpportunityLabel(trimmed) + ".");
            } else if (trimmed.contains("[WAIT_STRATEGY]") || trimmed.contains("[RESERVE_GRID]")) {
                watch.add(extractOpportunityLabel(trimmed) + ".");
            } else if (trimmed.startsWith("❌ [")) {
                doNot.add(extractOpportunityLabel(trimmed) + ".");
            }
        }
        if (text.contains("OVER BUDGET")) {
            warn.add("Opportunity scanner capital solver is over budget; trim any new-capital recommendation.");
            doNot.add("Do not execute all ranked opportunities together; capital solver is over budget.");
        }
    }

    private String extractOpportunityLabel(String line) {
        int idx = line.indexOf("]:");
        if (idx >= 0 && idx + 2 < line.length()) {
            return line.substring(idx + 2).trim();
        }
        int bracket = line.indexOf("]: ");
        if (bracket >= 0) {
            return line.substring(bracket + 3).trim();
        }
        return line;
    }

    private boolean hasOcoIssue(String text) {
        return (text.contains("SYNC_ERROR") && !text.contains("0 SYNC_ERROR"))
                || (text.contains("異常") && !text.contains("0 異常"));
    }

    private String describeStartupWarning(String text) {
        List<String> reasons = new ArrayList<>();
        if (hasNonZeroMetric(text, "ERROR")) reasons.add("ERROR hits");
        if (hasNonZeroMetric(text, "WARN")) reasons.add("WARN hits");
        if (hasNonZeroMetric(text, "Slow startup")) reasons.add("slow startup >120s");
        if (hasNonZeroMetric(text, "AI 429")) reasons.add("AI 429");
        if (hasNonZeroMetric(text, "Duplicate key")) reasons.add("duplicate-key hits");
        return "Current startup log needs review: " + String.join(", ", reasons) + ".";
    }

    private String describeStaleIndicators(String text) {
        if (text.contains("0 stale indicator")) {
            return null;
        }
        List<String> stale = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.contains("⚠️")) continue;
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            stale.add(trimmed);
            if (stale.size() >= 3) break;
        }
        if (stale.isEmpty()) {
            return null;
        }
        return "Indicator freshness has stale required data: " + String.join("; ", stale) + ".";
    }

    private boolean hasReadyShadowStrategy(String text) {
        for (String line : text.split("\\R")) {
            if (line.contains("| READY?")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNonZeroMetric(String text, String label) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(label + ":")) continue;
            String digits = trimmed.replaceFirst("^" + java.util.regex.Pattern.quote(label + ":") + "\\s*", "")
                    .replaceFirst("[^0-9].*$", "");
            return !digits.isBlank() && Integer.parseInt(digits) > 0;
        }
        return false;
    }

    private int extractMetric(String text, String label) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(label + "=") && !trimmed.startsWith(label + ":")) continue;
            String digits = trimmed.replaceFirst("^" + java.util.regex.Pattern.quote(label) + "\\s*[:=]\\s*", "")
                    .replaceFirst("[^0-9].*$", "");
            if (digits.isBlank()) {
                return 0;
            }
            return Integer.parseInt(digits);
        }
        return 0;
    }

    private void appendBucket(StringBuilder sb, String label, List<String> items, String emptyText) {
        sb.append(label).append(":\n");
        if (items.isEmpty()) {
            sb.append("  - ").append(emptyText).append("\n");
        } else {
            items.forEach(item -> sb.append("  - ").append(item).append("\n"));
        }
    }

    private void appendSection(StringBuilder sb, SectionResult result) {
        sb.append("=== ").append(result.title()).append(" ===\n");
        sb.append(result.text());
        sb.append("\n");
        sb.append("-- section_ms=").append(result.elapsedMs()).append(" --\n\n");
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private record SectionResult(String title, String text, long elapsedMs, boolean failed) {}

    private enum BlockedOutcomeStatus {
        CURRENT_CONFIRMED(true, "Open an approval-gated calibration issue before changing live filters."),
        CURRENT_WATCH(false, "Keep collecting post-fix samples; do not relax live filters yet."),
        HISTORICAL_ONLY(false, "Treat rolling-window warnings as historical/pre-fix context; do not relax live filters."),
        NO_SIGNAL(false, "No blocked-signal calibration action is available from this sample.");

        private final boolean currentRecurrence;
        private final String operatorAction;

        BlockedOutcomeStatus(boolean currentRecurrence, String operatorAction) {
            this.currentRecurrence = currentRecurrence;
            this.operatorAction = operatorAction;
        }
    }
}
