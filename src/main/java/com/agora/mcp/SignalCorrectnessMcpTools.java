package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.EvidenceEventCanonicalizer;
import com.agora.service.trading.EvidenceGovernanceSemantics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class SignalCorrectnessMcpTools {

    private static final String BOUNDARY = "boundary: READ_ONLY; no signal/order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence write behavior changed.";
    private static final double MISSED_ALPHA_THRESHOLD_PCT = 1.0;
    private static final double FALSE_POSITIVE_THRESHOLD_PCT = -0.5;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Semaphore GOVERNANCE_DRIFT_PERMIT = new Semaphore(1);
    private static final int GOVERNANCE_DRIFT_SAMPLE_LIMIT = 500;
    private static final Duration GOVERNANCE_DRIFT_CACHE_TTL = Duration.ofMinutes(5);
    private static final Map<GovernanceDriftCacheKey, GovernanceDriftCacheEntry> GOVERNANCE_DRIFT_CACHE = new LinkedHashMap<>();

    private final JdbcTemplate jdbc;
    private final DiagnosticMcpTools diagnosticMcpTools;
    private final MarketDataMcpTools marketDataMcpTools;
    private final RuntimeEvidenceMcpTools runtimeEvidenceMcpTools;
    private final MetaControlMcpTools metaControlMcpTools;
    private final StrategyManagementMcpTools strategyManagementMcpTools;
    private final IndicatorMcpTools indicatorMcpTools;
    private final TradingMlMcpTools tradingMlMcpTools;
    private final EnsembleMcpTools ensembleMcpTools;
    private final PositionMcpTools positionMcpTools;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Signal Correctness Dashboard v0. Read-only unified attribution dashboard for signal accuracy, missed alpha, filters, indicators, ML calibration, data quality, runtime evidence, and autonomous execution health. params: symbol default BTCUSDT, hours default 24 max 720, strategyId optional.")
    public String getSignalCorrectnessDashboard(String symbol,
                                                Integer hours,
                                                @ToolParam(required = false) Long strategyId) {
        String sym = normalizeSymbol(symbol);
        int h = normalizeHours(hours);
        int days = Math.max(1, (int) Math.ceil(h / 24.0));
        StringBuilder sb = new StringBuilder();
        sb.append("=== Signal Correctness Dashboard v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" hours=").append(h)
                .append(" strategyId=").append(strategyId == null ? "ALL" : strategyId).append("\n\n");

        Summary summary = loadSummary(sym, h, strategyId);
        sb.append("signalAccuracySummary:\n")
                .append("  auditCandidates=").append(summary.auditCandidates()).append("\n")
                .append("  signalEval=").append(summary.signalEval()).append("\n")
                .append("  pass=").append(summary.pass()).append("\n")
                .append("  blocked=").append(summary.blocked()).append("\n")
                .append("  executed=").append(summary.executed()).append("\n")
                .append("missedAlphaSummary:\n")
                .append("  falseBlockProxy=").append(summary.falseBlockProxy()).append("\n")
                .append("  missedAlphaProxy=").append(summary.missedAlphaProxy()).append("\n")
                .append("filterCorrectness:\n")
                .append("  topFalseBlocks=").append(joinList(summary.topFalseBlocks())).append("\n")
                .append("  topGovernanceSuppressors=").append(joinList(summary.topSuppressors())).append("\n")
                .append("runtimeEvidenceSummary:\n")
                .append("  evidenceRows=").append(summary.evidenceRows()).append("\n")
                .append("  canonicalExecutions=").append(summary.runtimeExecutions()).append("\n")
                .append("  runtimeBlocks=").append(summary.runtimeBlocks()).append("\n")
                .append("autonomousExecutionSummary:\n")
                .append("  tinyLiveExecutions=").append(summary.tinyLiveExecutions()).append("\n")
                .append("  tinyLiveOcoAttached=").append(summary.tinyLiveOcoAttached()).append("\n")
                .append("  tinyLiveOrderSent=").append(summary.tinyLiveOrderSent()).append("\n\n");

        sb.append("outcomeLabelSummary:\n").append(indent(excerpt(safeCall(() -> getSignalOutcomeLabelerStatus(sym, h, "1h", false), "outcome labeler"), 18), 2)).append("\n");
        sb.append("governanceDriftSummary:\n").append(indent(excerpt(safeCall(() -> getGovernanceDriftDashboardInternal(sym, days, "1h"), "governance drift"), 18), 2)).append("\n");
        sb.append("blockerAttributionSummary:\n").append(indent(excerpt(safeCall(() -> getBlockerDriftMatrixInternal(sym, days, "1h"), "blocker drift matrix"), 18), 2)).append("\n");
        sb.append("strongestAlphaSources / topMissedAlphaSources:\n").append(indent(excerpt(safeCall(() -> findGovernanceRelaxationCandidatesInternal(sym, days, "1h"), "relaxation candidates"), 18), 2)).append("\n");
        sb.append("strongestNoiseSources / strongestContraSources:\n").append(indent(excerpt(safeCall(() -> findGovernanceTighteningCandidatesInternal(sym, days, "1h"), "tightening candidates"), 18), 2)).append("\n");
        sb.append("indicatorAccuracySummary:\n")
                .append("  deferredTo=getIndicatorSignalScorecard / scanIndicatorAccuracy\n")
                .append("  reason=dashboard uses bounded outcome/governance aggregates to avoid long-running DB scans\n");
        sb.append("modelCalibrationSummary:\n").append(indent(excerpt(safeCall(() -> tradingMlMcpTools.getModelCalibration(null, Math.max(7, days)), "model calibration"), 14), 2)).append("\n");
        sb.append("dataQualitySummary:\n").append(indent(excerpt(safeCall(() -> indicatorMcpTools.getCollectionFreshness(sym), "collection freshness"), 16), 2)).append("\n");
        sb.append("freshnessRisk:\n").append(indent(excerpt(safeCall(() -> marketDataMcpTools.validateKlineQuality(sym, "1h", Math.max(1, days), "okx"), "kline quality"), 10), 2)).append("\n");
        sb.append("autonomousExecutionHealth:\n").append(indent(excerpt(safeCall(() -> runtimeEvidenceMcpTools.getTinyLiveAutoExecutionTriggerStatus(sym, strategyId == null ? 574L : strategyId, "LONG"), "auto trigger"), 16), 2)).append("\n");

        sb.append("recommendationSummary:\n")
                .append("  - Use getSignalTruthTable for candidate-level path tracing.\n")
                .append("  - Use getFilterAttributionMatrix to identify over-blocking governance layers.\n")
                .append("  - Use getIndicatorSignalScorecard before promoting or removing indicator rules.\n")
                .append("  - Treat proxy labels as diagnostic until forward-return samples settle.\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Signal Truth Table v0. Read-only candidate table tracing signal -> EV/TQS -> policy -> suppression/execution -> forward outcome. params: symbol default BTCUSDT, hours default 24 max 720, strategyId optional, limit default 50 max 100, labelHorizon 1h|4h|24h default 1h, includeNonActionable default false.")
    public String getSignalTruthTable(String symbol,
                                      Integer hours,
                                      @ToolParam(required = false) Long strategyId,
                                      Integer limit,
                                      @ToolParam(required = false) String labelHorizon,
                                      @ToolParam(required = false) Boolean includeNonActionable) {
        String sym = normalizeSymbol(symbol);
        int h = normalizeHours(hours);
        int lim = normalizeLimit(limit, 50, 100);
        LabelHorizon horizon = normalizeLabelHorizon(labelHorizon);
        boolean includeAll = Boolean.TRUE.equals(includeNonActionable);
        CanonicalLabelRows canonical = loadCanonicalLabelRows(sym, h, strategyId, lim, includeAll, horizon);
        List<Map<String, Object>> rows = canonical.rows();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Signal Truth Table v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" hours=").append(h)
                .append(" strategyId=").append(strategyId == null ? "ALL" : strategyId)
                .append(" limit=").append(lim)
                .append(" labelHorizon=").append(horizon.code)
                .append(" includeNonActionable=").append(includeAll).append("\n")
                .append(canonical.diagnostics()).append("\n");
        if (rows.isEmpty()) {
            return sb.append("No signal candidates found in runtime evidence or decision audit.\n").toString();
        }

        int i = 1;
        Map<String, ForwardStats> forwardCache = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDateTime eventTime = asTime(row.get("evidence_time"));
            PricePlan plan = pricePlan(row);
            ForwardStats fwd = loadForwardStatsCached(forwardCache, sym, eventTime, plan, horizon);
            String path = decisionPath(row);
            OutcomeLabel label = label(row, path, fwd, horizon);
            GovernanceClassification governanceClassification = governanceClassification(row);
            String correctness = label.correctness();
            String attribution = attributionLabel(row, correctness, fwd);
            sb.append(i++).append(". auditId=").append(value(row, "audit_id"))
                    .append(" decisionId=").append(value(row, "decision_id"))
                    .append(" runtimeEvidenceId=").append(value(row, "runtime_evidence_id"))
                    .append(" rowSource=").append(value(row, "row_source"))
                    .append(" strategyId=").append(value(row, "strategy_id"))
                    .append(" side=").append(value(row, "side"))
                    .append(" timeframe=").append(value(row, "interval_code"))
                    .append(" signalSource=").append(value(row, "signal_source"))
                    .append(" decisionPath=").append(path)
                    .append(" governanceClassification=").append(governanceClassification)
                    .append(" actionable=").append(isPriceActionable(row))
                    .append(" labelSource=").append(label.labelSource())
                    .append(" labelConfidence=").append(label.labelConfidence())
                    .append(" priceWindowComplete=").append(fwd.priceWindowComplete())
                    .append(" correctness=").append(correctness)
                    .append(" attribution=").append(attribution).append("\n")
                    .append("   evidenceRefs=auditId:").append(value(row, "audit_id"))
                    .append(",runtimeEvidenceId:").append(value(row, "runtime_evidence_id"))
                    .append(",liveSignalId:").append(value(row, "live_signal_id"))
                    .append(",allSources:").append(value(row, "source_ids")).append("\n")
                    .append("   canonicalMergeEligible=").append(value(row, "canonical_merge_eligible"))
                    .append(" identityConflict=").append(value(row, "identity_conflict"))
                    .append(" semanticConflict=").append(value(row, "semantic_conflict"))
                    .append(" semanticConflictReasons=").append(value(row, "semantic_conflict_reasons")).append("\n")
                    .append("   evSnapshot=").append(shortJson(row.get("ev_result_json"))).append("\n")
                    .append("   tqsSnapshot=").append(shortJson(row.get("tqs_result_json"))).append("\n")
                    .append("   ensembleSnapshot=").append(shortJson(row.get("policy_inputs_json"))).append("\n")
                    .append("   blockers=").append(firstNonBlank(row.get("terminal_blocker"), row.get("blocker_reason"), row.get("suppression_reason"), "N/A"))
                    .append(" warnings=").append(shortJson(row.get("warnings_json"))).append("\n")
                    .append("   forwardReturn1h=").append(fmtPct(fwd.return1hPct()))
                    .append(" forwardReturn4h=").append(fmtPct(fwd.return4hPct()))
                    .append(" forwardReturn24h=").append(fmtPct(fwd.return24hPct()))
                    .append(" MFE24h=").append(fmtPct(fwd.mfe24hPct()))
                    .append(" MAE24h=").append(fmtPct(fwd.mae24hPct()))
                    .append(" firstTouchOutcome=").append(fwd.firstTouchOutcome()).append("\n")
                    .append("   entrySource=").append(fwd.entrySource())
                    .append(" outcomeReason=").append(label.outcomeReason()).append("\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Trading Signal Outcome Labeler status v0. Read-only coverage report for signal outcome labels. params: symbol default BTCUSDT, hours default 168 max 720, labelHorizon 1h|4h|24h default 1h, includeNonActionable default false.")
    public String getSignalOutcomeLabelerStatus(String symbol,
                                                Integer hours,
                                                @ToolParam(required = false) String labelHorizon,
                                                @ToolParam(required = false) Boolean includeNonActionable) {
        String sym = normalizeSymbol(symbol);
        int h = normalizeHours(hours == null ? 168 : hours);
        LabelHorizon horizon = normalizeLabelHorizon(labelHorizon);
        boolean includeAll = Boolean.TRUE.equals(includeNonActionable);
        CanonicalLabelRows canonical = loadCanonicalLabelRows(sym, h, null, 500, true, horizon);
        List<Map<String, Object>> rows = canonical.rows();
        LabelStats stats = labelStats(sym, rows, horizon, includeAll);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Signal Outcome Labeler Status v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" hours=").append(h)
                .append(" labelHorizon=").append(horizon.code)
                .append(" includeNonActionable=").append(includeAll).append("\n")
                .append(canonical.diagnostics())
                .append("totalCandidates=").append(stats.totalCandidates).append("\n")
                .append("actionableCandidates=").append(stats.actionableCandidates).append("\n")
                .append("nonActionableCandidates=").append(stats.nonActionableCandidates).append("\n")
                .append("excludedNonActionableCount=").append(stats.excludedNonActionableCount).append("\n")
                .append("labeledCandidates=").append(stats.labeledCandidates).append("\n")
                .append("unresolvedCandidates=").append(stats.unresolvedCandidates).append("\n")
                .append("missingKlineCount=").append(stats.missingKlineCount).append("\n")
                .append("missingEntryPriceCount=").append(stats.missingEntryPriceCount).append("\n")
                .append("missingForwardWindowCount=").append(stats.missingForwardWindowCount).append("\n")
                .append("pendingForwardWindowCount=").append(stats.pendingForwardWindowCount).append("\n")
                .append("matureForwardWindowCount=").append(stats.matureForwardWindowCount).append("\n")
                .append("matureLabeledCandidates=").append(stats.matureLabeledCandidates).append("\n")
                .append("matureLabelCoveragePct=").append(stats.matureForwardWindowCount == 0 ? "N/A" : fmtPct(stats.matureLabeledCandidates * 100.0 / stats.matureForwardWindowCount)).append("\n")
                .append("explicitEntryPriceCount=").append(stats.explicitEntryPriceCount).append("\n")
                .append("entryPriceDerivedCount=").append(stats.entryPriceDerivedCount).append("\n")
                .append("labelCoveragePct=").append(stats.actionableCandidates == 0 ? "N/A" : fmtPct(stats.labeledCandidates * 100.0 / stats.actionableCandidates)).append("\n")
                .append("rowsByCorrectnessLabel=").append(stats.correctnessCounts).append("\n")
                .append("rowsByAttributionLabel=").append(stats.attributionCounts).append("\n")
                .append("rowsByUnresolvedReason=").append(stats.unresolvedReasonCounts).append("\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Trading Signal Outcome Labeler backfill preview v0. Read-only preview of labels that can be computed now; does not write DB. params: symbol default BTCUSDT, hours default 168 max 720, limit default 50 max 100, labelHorizon 1h|4h|24h default 1h, includeNonActionable default false.")
    public String backfillSignalOutcomeLabelsPreview(String symbol,
                                                     Integer hours,
                                                     Integer limit,
                                                     @ToolParam(required = false) String labelHorizon,
                                                     @ToolParam(required = false) Boolean includeNonActionable) {
        String sym = normalizeSymbol(symbol);
        int h = normalizeHours(hours == null ? 168 : hours);
        int lim = normalizeLimit(limit, 50, 100);
        LabelHorizon horizon = normalizeLabelHorizon(labelHorizon);
        boolean includeAll = Boolean.TRUE.equals(includeNonActionable);
        CanonicalLabelRows canonical = loadCanonicalLabelRows(sym, h, null, lim, includeAll, horizon);
        List<Map<String, Object>> rows = canonical.rows();
        int labelable = 0;
        StringBuilder examples = new StringBuilder();
        Map<String, ForwardStats> forwardCache = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            PricePlan plan = pricePlan(row);
            ForwardStats fwd = loadForwardStatsCached(forwardCache, sym, asTime(row.get("evidence_time")), plan, horizon);
            String path = decisionPath(row);
            OutcomeLabel label = label(row, path, fwd, horizon);
            String attribution = attributionLabel(row, label.correctness(), fwd);
            if (!"UNRESOLVED".equals(label.correctness())) {
                labelable++;
            }
            if (examples.length() < 5000) {
                examples.append("  - auditId=").append(value(row, "audit_id"))
                        .append(" decisionId=").append(value(row, "decision_id"))
                        .append(" runtimeEvidenceId=").append(value(row, "runtime_evidence_id"))
                        .append(" reason=").append(label.outcomeReason())
                        .append(" proposedCorrectness=").append(label.correctness())
                        .append(" proposedAttribution=").append(attribution)
                        .append(" entrySource=").append(fwd.entrySource())
                        .append(" forwardReturn1h=").append(fmtPct(fwd.return1hPct()))
                        .append(" forwardReturn4h=").append(fmtPct(fwd.return4hPct()))
                        .append(" forwardReturn24h=").append(fmtPct(fwd.return24hPct()))
                        .append(" MFE24h=").append(fmtPct(fwd.mfe24hPct()))
                        .append(" MAE24h=").append(fmtPct(fwd.mae24hPct()))
                        .append("\n");
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== Signal Outcome Labels Backfill Preview v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" hours=").append(h).append(" limit=").append(lim)
                .append(" labelHorizon=").append(horizon.code)
                .append(" includeNonActionable=").append(includeAll).append("\n")
                .append(canonical.diagnostics())
                .append("candidateCount=").append(rows.size()).append("\n")
                .append("labelableNow=").append(labelable).append("\n")
                .append("stillUnresolved=").append(rows.size() - labelable).append("\n")
                .append("examples:\n").append(examples.isEmpty() ? "  none\n" : examples);
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Outcome/Governance Drift Dashboard v0. Read-only summary of whether autonomous governance is normal, too strict, too loose, or insufficient. params: symbol default BTCUSDT, days default 7 max 90, labelHorizon 1h|4h|24h default 1h.")
    public String getGovernanceDriftDashboard(String symbol, Integer days, @ToolParam(required = false) String labelHorizon) {
        return runGovernanceDriftTool(() -> getGovernanceDriftDashboardInternal(symbol, days, labelHorizon));
    }

    private String getGovernanceDriftDashboardInternal(String symbol, Integer days, String labelHorizon) {
        String sym = normalizeSymbol(symbol);
        int d = normalizeDays(days, 7, 90);
        LabelHorizon horizon = normalizeLabelHorizon(labelHorizon);
        List<LabeledOutcome> outcomes = cachedLabeledOutcomes(sym, d, horizon);
        GovernanceStats stats = governanceStats(outcomes);
        String mode = governanceMode(stats);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Governance Drift Dashboard v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" days=").append(d)
                .append(" labelHorizon=").append(horizon.code).append("\n")
                .append("governanceMode=").append(mode).append("\n")
                .append("labelCoveragePct=").append(stats.actionableCandidates == 0 ? "N/A" : fmtPct(stats.labeledCandidates * 100.0 / stats.actionableCandidates)).append("\n")
                .append("actionableCandidates=").append(stats.actionableCandidates).append("\n")
                .append("labeledCandidates=").append(stats.labeledCandidates).append("\n")
                .append("unresolvedCandidates=").append(stats.unresolvedCandidates).append("\n")
                .append("trueBlockCount=").append(stats.trueBlockCount).append("\n")
                .append("falseBlockCount=").append(stats.falseBlockCount).append("\n")
                .append("lowEdgeCount=").append(stats.lowEdgeCount).append("\n")
                .append("truePositiveCount=").append(stats.truePositiveCount).append("\n")
                .append("falsePositiveCount=").append(stats.falsePositiveCount).append("\n")
                .append("trueBlockRate=").append(fmtRate(stats.trueBlockCount, stats.labeledCandidates)).append("\n")
                .append("falseBlockRate=").append(fmtRate(stats.falseBlockCount, stats.labeledCandidates)).append("\n")
                .append("lowEdgeRate=").append(fmtRate(stats.lowEdgeCount, stats.labeledCandidates)).append("\n")
                .append("truePositiveRate=").append(fmtRate(stats.truePositiveCount, stats.labeledCandidates)).append("\n")
                .append("falsePositiveRate=").append(fmtRate(stats.falsePositiveCount, stats.labeledCandidates)).append("\n")
                .append("governanceAggressiveness=").append(fmtRate(stats.blockLikeCount, Math.max(1, stats.actionableCandidates))).append("\n")
                .append("verdict=").append(verdictForMode(mode, stats)).append("\n")
                .append("recommendationSummary=").append(recommendationForMode(mode)).append("\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Blocker Drift Matrix v0. Read-only blocker-level TRUE_BLOCK/FALSE_BLOCK/LOW_EDGE distribution and recommendations. params: symbol default BTCUSDT, days default 7 max 90, labelHorizon 1h|4h|24h default 1h.")
    public String getBlockerDriftMatrix(String symbol, Integer days, @ToolParam(required = false) String labelHorizon) {
        return runGovernanceDriftTool(() -> getBlockerDriftMatrixInternal(symbol, days, labelHorizon));
    }

    private String getBlockerDriftMatrixInternal(String symbol, Integer days, String labelHorizon) {
        String sym = normalizeSymbol(symbol);
        int d = normalizeDays(days, 7, 90);
        LabelHorizon horizon = normalizeLabelHorizon(labelHorizon);
        List<LabeledOutcome> current = cachedLabeledOutcomes(sym, d, horizon);
        List<LabeledOutcome> previous = cachedLabeledOutcomes(sym, d * 2, horizon).stream()
                .filter(o -> o.eventTime != null && o.eventTime.isBefore(LocalDateTime.now(ZoneOffset.UTC).minusDays(d)))
                .toList();
        Map<String, DriftBucket> currentBuckets = blockerBuckets(current);
        Map<String, DriftBucket> previousBuckets = blockerBuckets(previous);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Blocker Drift Matrix v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" days=").append(d)
                .append(" labelHorizon=").append(horizon.code).append("\n\n");
        sb.append(String.format(Locale.US, "%-24s | %6s | %9s | %10s | %7s | %11s | %10s | %9s | %8s | %8s | %-12s | %s%n",
                "blocker", "blocks", "trueBlock", "falseBlock", "lowEdge", "missedAlpha", "falseRate", "trueRate", "avgRet", "trend", "recommend", "examples"));
        sb.append("-".repeat(160)).append("\n");
        currentBuckets.values().stream()
                .sorted((a, b) -> Integer.compare(b.blockCount, a.blockCount))
                .forEach(bucket -> {
                    DriftBucket prev = previousBuckets.get(bucket.blocker);
                    sb.append(bucket.line(prev)).append("\n");
                });
        if (currentBuckets.isEmpty()) sb.append("No labeled blocker outcomes found.\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Outcome Drift Timeline v0. Read-only daily buckets for outcome/governance drift. params: symbol default BTCUSDT, days default 7 max 90, labelHorizon 1h|4h|24h default 1h.")
    public String getOutcomeDriftTimeline(String symbol, Integer days, @ToolParam(required = false) String labelHorizon) {
        return runGovernanceDriftTool(() -> getOutcomeDriftTimelineInternal(symbol, days, labelHorizon));
    }

    private String getOutcomeDriftTimelineInternal(String symbol, Integer days, String labelHorizon) {
        String sym = normalizeSymbol(symbol);
        int d = normalizeDays(days, 7, 90);
        LabelHorizon horizon = normalizeLabelHorizon(labelHorizon);
        List<LabeledOutcome> outcomes = cachedLabeledOutcomes(sym, d, horizon);
        Map<java.time.LocalDate, List<LabeledOutcome>> byDay = new LinkedHashMap<>();
        outcomes.stream()
                .filter(o -> o.eventTime != null)
                .sorted((a, b) -> a.eventTime.compareTo(b.eventTime))
                .forEach(o -> byDay.computeIfAbsent(o.eventTime.toLocalDate(), ignored -> new ArrayList<>()).add(o));
        StringBuilder sb = new StringBuilder();
        sb.append("=== Outcome Drift Timeline v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" days=").append(d)
                .append(" labelHorizon=").append(horizon.code).append("\n\n");
        sb.append("date | actionableCandidates | labeledCandidates | TRUE_BLOCK | FALSE_BLOCK | LOW_EDGE | TRUE_POSITIVE | FALSE_POSITIVE | labelCoveragePct | governanceAggressiveness | falseBlockRate | falsePositiveRate | trueBlockRate\n");
        for (Map.Entry<java.time.LocalDate, List<LabeledOutcome>> entry : byDay.entrySet()) {
            GovernanceStats stats = governanceStats(entry.getValue());
            sb.append(entry.getKey()).append(" | ")
                    .append(stats.actionableCandidates).append(" | ")
                    .append(stats.labeledCandidates).append(" | ")
                    .append(stats.trueBlockCount).append(" | ")
                    .append(stats.falseBlockCount).append(" | ")
                    .append(stats.lowEdgeCount).append(" | ")
                    .append(stats.truePositiveCount).append(" | ")
                    .append(stats.falsePositiveCount).append(" | ")
                    .append(stats.actionableCandidates == 0 ? "N/A" : fmtPct(stats.labeledCandidates * 100.0 / stats.actionableCandidates)).append(" | ")
                    .append(fmtRate(stats.blockLikeCount, Math.max(1, stats.actionableCandidates))).append(" | ")
                    .append(fmtRate(stats.falseBlockCount, stats.labeledCandidates)).append(" | ")
                    .append(fmtRate(stats.falsePositiveCount, stats.labeledCandidates)).append(" | ")
                    .append(fmtRate(stats.trueBlockCount, stats.labeledCandidates)).append("\n");
        }
        if (byDay.isEmpty()) sb.append("No labeled outcomes found.\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Find Governance Relaxation Candidates v0. Read-only scan for blockers that may be too strict based on labeled outcomes. params: symbol default BTCUSDT, days default 7 max 90, labelHorizon 1h|4h|24h default 1h.")
    public String findGovernanceRelaxationCandidates(String symbol, Integer days, @ToolParam(required = false) String labelHorizon) {
        return runGovernanceDriftTool(() -> findGovernanceRelaxationCandidatesInternal(symbol, days, labelHorizon));
    }

    private String findGovernanceRelaxationCandidatesInternal(String symbol, Integer days, String labelHorizon) {
        String sym = normalizeSymbol(symbol);
        int d = normalizeDays(days, 7, 90);
        LabelHorizon horizon = normalizeLabelHorizon(labelHorizon);
        Map<String, DriftBucket> buckets = blockerBuckets(cachedLabeledOutcomes(sym, d, horizon));
        StringBuilder sb = new StringBuilder();
        sb.append("=== Governance Relaxation Candidates v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" days=").append(d)
                .append(" labelHorizon=").append(horizon.code).append("\n")
                .append("criteria: falseBlockRate>=20%, missedAlphaCount>=3, sampleSize>=10\n\n");
        int emitted = 0;
        for (DriftBucket b : buckets.values().stream().sorted((a, z) -> Double.compare(z.falseBlockRate(), a.falseBlockRate())).toList()) {
            if (b.blockCount >= 10 && b.missedAlphaCount >= 3 && b.falseBlockRate() >= 20.0) {
                emitted++;
                sb.append("- blocker=").append(b.blocker)
                        .append(" falseBlockRate=").append(fmtPct(b.falseBlockRate()))
                        .append(" missedAlphaCount=").append(b.missedAlphaCount)
                        .append(" sampleSize=").append(b.blockCount)
                        .append(" avgMissedForwardReturn=").append(fmtPct(b.avgMissedForwardReturn()))
                        .append(" examples=").append(joinList(b.examples))
                        .append(" suggestedChange=").append(suggestRelaxation(b.blocker))
                        .append(" riskLevel=").append(b.falseBlockRate() >= 40.0 ? "MEDIUM" : "LOW")
                        .append("\n");
            }
        }
        if (emitted == 0) sb.append("No relaxation candidates met thresholds from labeled evidence.\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Find Governance Tightening Candidates v0. Read-only scan for sources/rules that may be too loose based on false positives. params: symbol default BTCUSDT, days default 7 max 90, labelHorizon 1h|4h|24h default 1h.")
    public String findGovernanceTighteningCandidates(String symbol, Integer days, @ToolParam(required = false) String labelHorizon) {
        return runGovernanceDriftTool(() -> findGovernanceTighteningCandidatesInternal(symbol, days, labelHorizon));
    }

    private String findGovernanceTighteningCandidatesInternal(String symbol, Integer days, String labelHorizon) {
        String sym = normalizeSymbol(symbol);
        int d = normalizeDays(days, 7, 90);
        LabelHorizon horizon = normalizeLabelHorizon(labelHorizon);
        Map<String, DriftBucket> buckets = sourceBuckets(cachedLabeledOutcomes(sym, d, horizon));
        StringBuilder sb = new StringBuilder();
        sb.append("=== Governance Tightening Candidates v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" days=").append(d)
                .append(" labelHorizon=").append(horizon.code).append("\n")
                .append("criteria: falsePositiveRate>=30%, sampleSize>=5\n\n");
        int emitted = 0;
        for (DriftBucket b : buckets.values().stream().sorted((a, z) -> Double.compare(z.falsePositiveRate(), a.falsePositiveRate())).toList()) {
            if (b.blockCount >= 5 && b.falsePositiveRate() >= 30.0) {
                emitted++;
                sb.append("- source=").append(b.blocker)
                        .append(" strategyId=").append(b.firstStrategyId())
                        .append(" indicator=N/A")
                        .append(" policyMode=").append(b.firstPolicyMode())
                        .append(" falsePositiveRate=").append(fmtPct(b.falsePositiveRate()))
                        .append(" examples=").append(joinList(b.examples))
                        .append(" suggestedTightening=raise EV/TQS threshold or require stronger confirmation for this source")
                        .append(" riskLevel=").append(b.falsePositiveRate() >= 50.0 ? "HIGH" : "MEDIUM")
                        .append("\n");
            }
        }
        if (emitted == 0) sb.append("No tightening candidates met thresholds from labeled evidence.\n");
        return sb.toString();
    }

    private String runGovernanceDriftTool(Supplier<String> work) {
        if (!GOVERNANCE_DRIFT_PERMIT.tryAcquire()) {
            return "=== Outcome/Governance Drift v0 ===\n"
                    + BOUNDARY + "\n"
                    + "status=BUSY_RETRY_LATER\n"
                    + "reason=another governance drift report is already running; concurrency is capped to protect the trading DB pool\n";
        }
        try {
            return work.get();
        } finally {
            GOVERNANCE_DRIFT_PERMIT.release();
        }
    }

    private List<LabeledOutcome> cachedLabeledOutcomes(String symbol, int days, LabelHorizon horizon) {
        GovernanceDriftCacheKey key = new GovernanceDriftCacheKey(symbol, days, horizon.code);
        Instant now = Instant.now();
        synchronized (GOVERNANCE_DRIFT_CACHE) {
            GovernanceDriftCacheEntry cached = GOVERNANCE_DRIFT_CACHE.get(key);
            if (cached != null && Duration.between(cached.createdAt(), now).compareTo(GOVERNANCE_DRIFT_CACHE_TTL) < 0) {
                return cached.outcomes();
            }
        }
        List<LabeledOutcome> loaded = List.copyOf(loadLabeledOutcomes(symbol, days, horizon, GOVERNANCE_DRIFT_SAMPLE_LIMIT));
        synchronized (GOVERNANCE_DRIFT_CACHE) {
            GOVERNANCE_DRIFT_CACHE.put(key, new GovernanceDriftCacheEntry(now, loaded));
            if (GOVERNANCE_DRIFT_CACHE.size() > 24) {
                GovernanceDriftCacheKey oldest = GOVERNANCE_DRIFT_CACHE.keySet().iterator().next();
                GOVERNANCE_DRIFT_CACHE.remove(oldest);
            }
        }
        return loaded;
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Filter Attribution Matrix v0. Read-only blocker/group matrix for DuplicateBar, EventRisk, EV, OCO, daily cap, open-position guard, exposure, DataFreshness, Runtime Evidence missing. params: symbol default BTCUSDT, days default 5 max 30.")
    public String getFilterAttributionMatrix(String symbol, Integer days) {
        String sym = normalizeSymbol(symbol);
        int d = normalizeDays(days, 5, 30);
        CanonicalLabelRows canonical = loadCanonicalLabelRows(
                sym, d * 24, null, 500, true, LabelHorizon.H24, true);
        List<Map<String, Object>> rows = canonical.rows().stream()
                .filter(this::isFilterAttributionInput)
                .toList();
        Map<String, FilterBucket> buckets = new LinkedHashMap<>();
        Map<GovernanceClassification, Integer> classificationCounts = new LinkedHashMap<>();
        int nonPriceActionableExcluded = 0;
        int nonGovernanceEligibleExcluded = 0;
        for (Map<String, Object> row : rows) {
            GovernanceClassification classification = governanceClassification(row);
            classificationCounts.merge(classification, 1, Integer::sum);
            if (!isPriceActionable(row)) {
                nonPriceActionableExcluded++;
                continue;
            }
            if (classification != GovernanceClassification.TERMINAL_GUARD_BLOCK) {
                nonGovernanceEligibleExcluded++;
                continue;
            }
            String blocker = resolveFilterBlocker(row) + scopeSuffix(row);
            ForwardStats fwd = loadForwardStats(sym, asTime(row.get("evidence_time")), pricePlan(row), LabelHorizon.H24);
            buckets.computeIfAbsent(blocker, FilterBucket::new).add(row, fwd);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== Filter Attribution Matrix v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" days=").append(d).append("\n")
                .append(canonical.diagnostics()).append("\n")
                .append("governanceClassificationCounts=").append(classificationCounts).append("\n")
                .append("nonPriceActionableExcluded=").append(nonPriceActionableExcluded)
                .append(" (strategy no-entry/watch-only rows are not counted as governance false blocks)\n")
                .append("nonGovernanceEligibleExcluded=").append(nonGovernanceEligibleExcluded)
                .append(" (execution/capacity and unclassified blocks remain visible in classification counts, not false-block samples)\n")
                .append("classification: falseBlock/missedAlpha proxy uses forward24h or MFE24h >= ")
                .append(fmtPct(MISSED_ALPHA_THRESHOLD_PCT)).append("; precision is diagnostic, not a trade instruction.\n\n");
        sb.append(String.format(Locale.US, "%-28s | %8s | %12s | %11s | %11s | %9s | examples%n",
                "blocker", "blocks", "correctBlock", "falseBlock", "missedAlpha", "precision"));
        sb.append("-".repeat(120)).append("\n");
        buckets.values().stream()
                .sorted((a, b) -> Integer.compare(b.blockCount, a.blockCount))
                .forEach(b -> sb.append(b.line()).append("\n"));
        if (buckets.isEmpty()) {
            sb.append("No blocker rows found.\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Shadow daily-cap learning recommendation. Read-only diagnosis for shadow/notifyOnly daily-cap rows that may be limiting learning samples without implying live trading changes. params: symbol default BTCUSDT, days default 2 max 30, strategyId optional.")
    public String getShadowDailyCapLearningRecommendation(String symbol,
                                                          Integer days,
                                                          @ToolParam(required = false) Long strategyId) {
        String sym = normalizeSymbol(symbol);
        int d = normalizeDays(days, 2, 30);
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        List<Map<String, Object>> rows = loadFilterRows(sym, since);

        int shadowDailyCapRows = 0;
        int falseBlockProxy = 0;
        int correctBlockProxy = 0;
        int lowQualityRows = 0;
        int currentCap = 1;
        List<String> examples = new ArrayList<>();
        Map<String, Integer> strategies = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            if (!isPriceActionable(row)) continue;
            Long rowStrategyId = nullableLong(row.get("strategy_id"));
            if (strategyId != null && !Objects.equals(strategyId, rowStrategyId)) continue;
            if (!"daily cap".equals(resolveFilterBlocker(row))) continue;
            if (!scopeSuffix(row).contains("SHADOW_ONLY")) continue;

            shadowDailyCapRows++;
            if (rowStrategyId != null) {
                strategies.merge(String.valueOf(rowStrategyId), 1, Integer::sum);
            }
            Integer cap = jsonInt(row.get("features_snapshot_json"),
                    "shadow_daily_new_entry_cap", "daily_new_entry_cap", "maxOrdersPerDay", "max_orders_per_day");
            if (cap != null && cap > currentCap) currentCap = cap;

            ForwardStats fwd = loadForwardStats(sym, asTime(row.get("evidence_time")), pricePlan(row), LabelHorizon.H24);
            boolean missed = isMissedAlphaProxy(fwd);
            if (missed) {
                falseBlockProxy++;
            } else if (fwd.return24hPct() != null || fwd.mfe24hPct() != null) {
                correctBlockProxy++;
            } else {
                lowQualityRows++;
            }
            if (examples.size() < 5) {
                examples.add(String.format(Locale.US,
                        "decisionId=%s strategy=%s blocker=daily_cap shadow=true missedAlphaProxy=%s forward1h=%s forward4h=%s forward24h=%s mfe24h=%s mae24h=%s",
                        row.get("decision_id"),
                        rowStrategyId == null ? "N/A" : rowStrategyId,
                        missed,
                        fmtPct(fwd.return1hPct()),
                        fmtPct(fwd.return4hPct()),
                        fmtPct(fwd.return24hPct()),
                        fmtPct(fwd.mfe24hPct()),
                        fmtPct(fwd.mae24hPct())));
            }
        }

        double falseBlockRate = shadowDailyCapRows == 0 ? 0.0 : falseBlockProxy * 100.0 / shadowDailyCapRows;
        boolean recommendIncrease = shadowDailyCapRows >= 3 && falseBlockProxy >= 3 && falseBlockRate >= 50.0;
        int recommendedCap = recommendIncrease ? Math.min(3, Math.max(currentCap + 1, 2)) : currentCap;
        String recommendation = shadowDailyCapRows == 0
                ? "NO_SHADOW_DAILY_CAP_PRESSURE"
                : recommendIncrease
                ? "INCREASE_SHADOW_SAMPLE_CAP_REVIEW"
                : "WATCH_SHADOW_DAILY_CAP";

        StringBuilder sb = new StringBuilder();
        sb.append("=== Shadow Daily-Cap Learning Recommendation ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" days=").append(d)
                .append(" strategyId=").append(strategyId == null ? "ALL" : strategyId).append("\n")
                .append("scope=SHADOW_ONLY_DIAGNOSTIC\n")
                .append("recommendation=").append(recommendation).append("\n")
                .append("liveTradingChangeRecommended=false\n")
                .append("shadowOnlyCapChangeRecommended=").append(recommendIncrease).append("\n")
                .append("currentObservedShadowDailyCap=").append(currentCap).append("\n")
                .append("recommendedShadowDailyCap=").append(recommendedCap).append("\n")
                .append("shadowDailyCapRows=").append(shadowDailyCapRows).append("\n")
                .append("falseBlockProxy=").append(falseBlockProxy).append("\n")
                .append("correctBlockProxy=").append(correctBlockProxy).append("\n")
                .append("lowQualityRows=").append(lowQualityRows).append("\n")
                .append(String.format(Locale.US, "falseBlockProxyRate=%.1f%%%n", falseBlockRate))
                .append("strategies=").append(strategies.isEmpty() ? "N/A" : strategies).append("\n")
                .append("guardrails=do not change live orders; do not bypass OCO/DataFreshness/RuntimeEvidence/system health/exposure/loss budget\n")
                .append("examples:\n");
        if (examples.isEmpty()) {
            sb.append("  none\n");
        } else {
            examples.forEach(example -> sb.append("  - ").append(example).append("\n"));
        }
        sb.append("orderSent=false\n")
                .append("ocoModified=false\n")
                .append("strategyModified=false\n")
                .append("writesRuntimeEvidence=false\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Indicator Signal Scorecard v0. Read-only indicator alpha/noise/contra scorecard using existing indicator outcome scanners. params: symbol default BTCUSDT, days default 30 max 180, minSampleN default 3.")
    public String getIndicatorSignalScorecard(String symbol, Integer days, Integer minSampleN) {
        String sym = normalizeSymbol(symbol);
        int d = normalizeDays(days, 30, 180);
        int minN = minSampleN == null || minSampleN <= 0 ? 3 : Math.min(minSampleN, 100);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Indicator Signal Scorecard v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" days=").append(d).append(" minSampleN=").append(minN).append("\n\n");
        sb.append("groups: alpha / noise / contra / insufficient-sample are inherited from scanIndicatorAccuracy tiers.\n");
        sb.append("includedFamilies: FearGreed, whale_buy_ratio, funding_rate, long_short_ratio, market_flip, polymarket, OI, basis, liquidation, orderbook imbalance, gemini hints when samples exist.\n\n");
        sb.append("indicatorAccuracySummary:\n")
                .append(indent(safeCall(() -> diagnosticMcpTools.scanIndicatorAccuracy(d, 24, 0.5, sym, minN, "hit_rate", true), "scanIndicatorAccuracy"), 2))
                .append("\n");
        sb.append("hourBucketExamples:\n")
                .append(indent(safeCall(() -> diagnosticMcpTools.indicatorAccuracyHourMatrix("mih_threshold", "whale_buy_ratio:gt:0.65", 24, 0.5, d, sym, minN, true), "whale hour matrix"), 2))
                .append("\n");
        sb.append("volatilitySensitivity/regimeDependency: use hourBucketExamples plus getRecentHints/market-sentiment excerpts below.\n");
        sb.append("geminiHints:\n").append(indent(excerpt(safeCall(() -> marketDataMcpTools.getRecentHints(d, null), "recent hints"), 16), 2));
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Autonomous Execution Attribution v0. Read-only analysis of executed/blocked autonomous trades, overrides, missed alpha due to governance, daily-cap/DuplicateBar/EventRisk suppressions, OCO attach rate, realized EV vs expected EV. params: symbol default BTCUSDT, days default 7 max 90.")
    public String getAutonomousExecutionAttribution(String symbol, Integer days) {
        String sym = normalizeSymbol(symbol);
        int d = normalizeDays(days, 7, 90);
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        List<Map<String, Object>> execRows = jdbc.queryForList("""
                SELECT status, approval_mode, event_risk_override_used, order_sent, oco_attached,
                       max_loss_usdt, expected_r, denial_reason, blockers_json
                FROM bt_tiny_live_execution_audit
                WHERE symbol = ? AND created_at >= ?
                ORDER BY created_at DESC
                """, sym, since);
        long executed = execRows.stream().filter(r -> bool(r.get("order_sent"))).count();
        long ocoAttached = execRows.stream().filter(r -> bool(r.get("oco_attached"))).count();
        long overrides = execRows.stream().filter(r -> bool(r.get("event_risk_override_used"))).count();
        BigDecimal avgMaxLoss = execRows.stream()
                .map(r -> asDecimal(r.get("max_loss_usdt")))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!execRows.isEmpty()) {
            avgMaxLoss = avgMaxLoss.divide(BigDecimal.valueOf(execRows.size()), 8, RoundingMode.HALF_UP);
        }

        List<Map<String, Object>> suppressionRows = jdbc.queryForList("""
                SELECT COALESCE(NULLIF(terminal_blocker,''), NULLIF(suppression_reason,''), NULLIF(blocker_reason,''), 'NONE') blocker,
                       COUNT(*) cnt
                FROM bt_runtime_decision_evidence
                WHERE symbol = ? AND evidence_time >= ? AND (order_sent = 0 OR order_sent IS NULL)
                GROUP BY blocker ORDER BY cnt DESC LIMIT 10
                """, sym, since);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Autonomous Execution Attribution v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" days=").append(d).append("\n\n")
                .append("executedAutonomousTrades=").append(executed).append("\n")
                .append("blockedAutonomousTrades=").append(Math.max(0, execRows.size() - executed)).append("\n")
                .append("overriddenTrades=").append(overrides).append("\n")
                .append("successfulOcoAttachRate=").append(executed == 0 ? "N/A" : fmtPct(ocoAttached * 100.0 / executed)).append("\n")
                .append("autonomousWinRate=N/A_UNTIL_TINY_LIVE_CLOSED\n")
                .append("autonomousMissedAlphaRate=proxy_from_getFilterAttributionMatrix\n")
                .append("governanceFalseBlockRate=proxy_from_getFilterAttributionMatrix\n")
                .append("OCOProtectionEffectiveness=").append(executed == 0 ? "N/A" : (ocoAttached == executed ? "PASS_ALL_EXECUTIONS_PROTECTED" : "REVIEW_UNPROTECTED_EXECUTION")).append("\n")
                .append("averageMaxLossIfWrong=").append(execRows.isEmpty() ? "N/A" : avgMaxLoss.toPlainString()).append("\n")
                .append("overrideEffectiveness=").append(overrides == 0 ? "NO_OVERRIDE_SAMPLE" : "REVIEW_OVERRIDE_OUTCOMES").append("\n\n");
        sb.append("topSuppressionReasons:\n");
        if (suppressionRows.isEmpty()) {
            sb.append("  none\n");
        } else {
            for (Map<String, Object> row : suppressionRows) {
                sb.append("  - ").append(row.get("blocker")).append(": ").append(row.get("cnt")).append("\n");
            }
        }
        sb.append("\nexecutionAuditExcerpt:\n")
                .append(indent(excerpt(safeCall(() -> runtimeEvidenceMcpTools.listTinyLiveExecutions(sym, d * 24 * 60, 20), "tiny live executions"), 14), 2));
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Signal Quality Timeline v0. Read-only timeline for TQS drift, EV drift, calibration drift, block precision drift, missed-alpha drift, event-risk drift, and governance aggressiveness drift. params: symbol default BTCUSDT, days default 7 max 90.")
    public String getSignalQualityTimeline(String symbol, Integer days) {
        String sym = normalizeSymbol(symbol);
        int d = normalizeDays(days, 7, 90);
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DATE(evidence_time) day,
                       COUNT(*) evidence_rows,
                       AVG(score) avg_score,
                       SUM(CASE WHEN ev_result_json IS NOT NULL AND ev_result_json LIKE '%PASS%' THEN 1 ELSE 0 END) ev_pass,
                       SUM(CASE WHEN order_sent = 1 THEN 1 ELSE 0 END) order_sent,
                       SUM(CASE WHEN terminal_blocker IS NOT NULL OR blocker_reason IS NOT NULL OR suppression_reason IS NOT NULL THEN 1 ELSE 0 END) blocked
                FROM bt_runtime_decision_evidence
                WHERE symbol = ? AND evidence_time >= ?
                GROUP BY DATE(evidence_time)
                ORDER BY day DESC
                """, sym, since);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Signal Quality Timeline v0 ===\n")
                .append(BOUNDARY).append("\n")
                .append("symbol=").append(sym).append(" days=").append(d).append("\n\n")
                .append("day | evidenceRows | avgTqsScore | evPass | orderSent | blocked | governanceAggressiveness\n");
        if (rows.isEmpty()) {
            sb.append("No runtime evidence timeline rows.\n");
        } else {
            for (Map<String, Object> row : rows) {
                int total = asInt(row.get("evidence_rows"));
                int blocked = asInt(row.get("blocked"));
                sb.append(row.get("day")).append(" | ")
                        .append(total).append(" | ")
                        .append(row.get("avg_score") == null ? "N/A" : row.get("avg_score")).append(" | ")
                        .append(row.get("ev_pass")).append(" | ")
                        .append(row.get("order_sent")).append(" | ")
                        .append(blocked).append(" | ")
                        .append(total == 0 ? "N/A" : fmtPct(blocked * 100.0 / total)).append("\n");
            }
        }
        sb.append("\ncalibrationDrift:\n").append(indent(excerpt(safeCall(() -> tradingMlMcpTools.getModelCalibration(null, Math.max(7, d)), "model calibration"), 12), 2));
        sb.append("\nmissedAlphaDrift:\n").append(indent(excerpt(safeCall(() -> diagnosticMcpTools.getMissedAlphaAttributionReport(sym, Math.min(d * 24, 168), MISSED_ALPHA_THRESHOLD_PCT), "missed alpha"), 12), 2));
        sb.append("\neventRiskDrift:\n").append(indent(excerpt(safeCall(() -> diagnosticMcpTools.getEventRiskControlStatus(sym), "event risk"), 12), 2));
        return sb.toString();
    }

    private Summary loadSummary(String symbol, int hours, Long strategyId) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(hours);
        List<Object> params = new ArrayList<>();
        params.add(symbol);
        params.add(since);
        String strategySql = "";
        if (strategyId != null) {
            strategySql = " AND strategy_id = ? ";
            params.add(strategyId);
        }
        Map<String, Object> audit = firstRow(jdbc.queryForList("""
                SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(5000) */
                       COUNT(*) audit_candidates,
                       SUM(CASE WHEN event_type='SIGNAL_EVAL' THEN 1 ELSE 0 END) signal_eval,
                       SUM(CASE WHEN outcome='PASS' THEN 1 ELSE 0 END) pass_count,
                       SUM(CASE WHEN outcome='BLOCKED' THEN 1 ELSE 0 END) blocked_count,
                       SUM(CASE WHEN event_type='AUTOTRADE_OK' THEN 1 ELSE 0 END) executed_count
                FROM bt_decision_audit FORCE INDEX (idx_audit_symbol_time)
                WHERE symbol = ? AND event_time >= ?
                """ + strategySql, params.toArray()));

        List<Object> evidenceParams = new ArrayList<>(params);
        Map<String, Object> evidence = firstRow(jdbc.queryForList("""
                SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(5000) */
                       COUNT(*) evidence_rows,
                       SUM(CASE WHEN order_sent = 1 THEN 1 ELSE 0 END) runtime_executions,
                       SUM(CASE WHEN terminal_blocker IS NOT NULL OR blocker_reason IS NOT NULL OR suppression_reason IS NOT NULL THEN 1 ELSE 0 END) runtime_blocks
                FROM bt_runtime_decision_evidence FORCE INDEX (idx_rt_decision_evidence_symbol_time)
                WHERE symbol = ? AND evidence_time >= ?
                """ + strategySql, evidenceParams.toArray()));

        List<Object> execParams = new ArrayList<>();
        execParams.add(symbol);
        execParams.add(since);
        if (strategyId != null) execParams.add(strategyId);
        Map<String, Object> tiny = firstRow(jdbc.queryForList("""
                SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(5000) */
                       COUNT(*) tiny_live_executions,
                       SUM(CASE WHEN order_sent = 1 THEN 1 ELSE 0 END) tiny_live_order_sent,
                       SUM(CASE WHEN oco_attached = 1 THEN 1 ELSE 0 END) tiny_live_oco_attached
                FROM bt_tiny_live_execution_audit
                WHERE symbol = ? AND created_at >= ?
                """ + (strategyId == null ? "" : " AND strategy_id = ? "), execParams.toArray()));

        List<String> topSuppressors = topGroups(symbol, since, strategyId);
        return new Summary(
                asInt(audit.get("audit_candidates")),
                asInt(audit.get("signal_eval")),
                asInt(audit.get("pass_count")),
                asInt(audit.get("blocked_count")),
                asInt(audit.get("executed_count")),
                asInt(evidence.get("evidence_rows")),
                asInt(evidence.get("runtime_executions")),
                asInt(evidence.get("runtime_blocks")),
                asInt(tiny.get("tiny_live_executions")),
                asInt(tiny.get("tiny_live_order_sent")),
                asInt(tiny.get("tiny_live_oco_attached")),
                "see getFilterAttributionMatrix",
                "see getMissedAlphaAttributionReport",
                topSuppressors,
                topSuppressors);
    }

    private List<String> topGroups(String symbol, LocalDateTime since, Long strategyId) {
        List<Object> params = new ArrayList<>();
        params.add(symbol);
        params.add(since);
        String strategySql = "";
        if (strategyId != null) {
            strategySql = " AND strategy_id = ? ";
            params.add(strategyId);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(5000) */
                       COALESCE(NULLIF(terminal_blocker,''), NULLIF(suppression_reason,''), NULLIF(blocker_reason,''), NULLIF(policy_mode,''), 'NONE') name,
                       COUNT(*) cnt
                FROM bt_runtime_decision_evidence FORCE INDEX (idx_rt_decision_evidence_symbol_time)
                WHERE symbol = ? AND evidence_time >= ?
                """ + strategySql + " GROUP BY name ORDER BY cnt DESC LIMIT 5", params.toArray());
        return rows.stream().map(r -> r.get("name") + "=" + r.get("cnt")).toList();
    }

    private List<Map<String, Object>> loadFilterRows(String symbol, LocalDateTime since) {
        return jdbc.queryForList("""
                SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(10000) */
                       e.id, e.decision_id, e.evidence_time, e.strategy_id, e.signal_source,
                       e.terminal_blocker, e.blocker_reason, e.suppression_reason,
                       e.policy_mode, e.selected_action, e.final_outcome, e.order_sent, e.features_snapshot_json,
                       e.execution_preview_json, e.live_signal_id, s.actual_entry_price, s.entry_price live_entry_price,
                       s.suggested_tp, s.suggested_sl, s.realized_pnl
                FROM bt_runtime_decision_evidence e FORCE INDEX (idx_rt_decision_evidence_symbol_time)
                LEFT JOIN bt_live_signal s ON s.id = e.live_signal_id
                WHERE e.symbol = ? AND e.evidence_time >= ?
                  AND (e.terminal_blocker IS NOT NULL OR e.blocker_reason IS NOT NULL OR e.suppression_reason IS NOT NULL OR e.policy_mode IN ('BLOCK','READ_ONLY','HALT_TRADING','ALLOW_RISK_REDUCING_ONLY'))
                ORDER BY e.evidence_time DESC
                LIMIT 500
                """, symbol, since);
    }

    private boolean isFilterAttributionInput(Map<String, Object> row) {
        return hasMeaningfulBlockValue(row.get("terminal_blocker"))
                || hasMeaningfulBlockValue(row.get("blocker_reason"))
                || hasMeaningfulBlockValue(row.get("suppression_reason"))
                || List.of("BLOCK", "READ_ONLY", "HALT_TRADING", "ALLOW_RISK_REDUCING_ONLY")
                .contains(upper(row.get("policy_mode")));
    }

    private List<Map<String, Object>> loadLabelRows(String symbol,
                                                    int hours,
                                                    Long strategyId,
                                                    int limit,
                                                    boolean includeNonActionable,
                                                    LabelHorizon horizon) {
        return loadCanonicalLabelRows(symbol, hours, strategyId, limit, includeNonActionable, horizon).rows();
    }

    private CanonicalLabelRows loadCanonicalLabelRows(String symbol,
                                                       int hours,
                                                       Long strategyId,
                                                       int limit,
                                                       boolean includeNonActionable,
                                                       LabelHorizon horizon) {
        return loadCanonicalLabelRows(symbol, hours, strategyId, limit, includeNonActionable, horizon, false);
    }

    private CanonicalLabelRows loadCanonicalLabelRows(String symbol,
                                                       int hours,
                                                       Long strategyId,
                                                       int limit,
                                                       boolean includeNonActionable,
                                                       LabelHorizon horizon,
                                                       boolean filterAttributionOnly) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(hours);
        LocalDateTime latestKline = latestKlineTime(symbol);
        LocalDateTime completeCutoff = latestKline == null
                ? LocalDateTime.now(ZoneOffset.UTC).minusHours(horizon.hours)
                : latestKline.minusHours(horizon.hours);
        int sourceLimit = Math.max(limit, 250);
        List<Object> evidenceParams = new ArrayList<>();
        evidenceParams.add(symbol);
        evidenceParams.add(since);
        String evidenceStrategySql = "";
        if (strategyId != null) {
            evidenceStrategySql = " AND e.strategy_id = ? ";
            evidenceParams.add(strategyId);
        }
        String evidencePopulationSql = filterAttributionOnly
                ? runtimeFilterPopulationSql()
                : "";
        evidenceParams.add(sourceLimit);
        QueryRows runtimeQuery;
        try {
            runtimeQuery = QueryRows.success(jdbc.queryForList("""
                SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(15000) */ 'RUNTIME_EVIDENCE' row_source,
                       e.id row_id, e.id runtime_evidence_id, e.decision_id, e.evidence_time, e.symbol,
                       e.strategy_id, COALESCE(e.side, s.side) side, e.interval_code, s.bar_open_time,
                       e.signal_source, e.selected_action, e.decision, e.score,
                       e.threshold_value, e.policy_mode, e.freshness_state, e.terminal_blocker,
                       e.blocker_reason, e.reason, e.final_outcome, e.execution_mode, e.order_sent,
                       e.suppression_reason, e.intent_created, e.oco_plan_created,
                       e.ev_result_json, e.tqs_result_json, e.policy_inputs_json,
                       e.features_snapshot_json, e.execution_preview_json, e.warnings_json, e.live_signal_id,
                       s.actual_entry_price, s.entry_price live_entry_price, s.suggested_tp, s.suggested_sl, s.realized_pnl,
                       NULL audit_id
                FROM (
                    SELECT *
                    FROM bt_runtime_decision_evidence FORCE INDEX (idx_rt_decision_evidence_symbol_time)
                    WHERE symbol = ? AND evidence_time >= ?
                """ + evidencePopulationSql + evidenceStrategySql.replace("e.", "") + """
                    ORDER BY evidence_time DESC, id DESC
                    LIMIT ?
                ) e
                LEFT JOIN bt_live_signal s ON s.id = e.live_signal_id
                """, evidenceParams.toArray()));
        } catch (Exception e) {
            runtimeQuery = QueryRows.failure("RUNTIME_QUERY_FAILED", e);
        }
        List<Map<String, Object>> rows = new ArrayList<>(runtimeQuery.rows());
        int runtimeRowsFetched = rows.size();

        List<Object> auditParams = new ArrayList<>();
        auditParams.add(symbol);
        auditParams.add(since);
        String auditStrategySql = "";
        if (strategyId != null) {
            auditStrategySql = " AND a.strategy_id = ? ";
            auditParams.add(strategyId);
        }
        String auditPopulationSql = filterAttributionOnly
                ? auditFilterPopulationSql()
                : "";
        auditParams.add(sourceLimit);
        QueryRows auditQuery;
        try {
            auditQuery = QueryRows.success(jdbc.queryForList("""
                SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(15000) */ 'DECISION_AUDIT' row_source,
                       a.id row_id, NULL runtime_evidence_id, a.id decision_id, a.event_time evidence_time, a.symbol,
                       a.strategy_id,
                       COALESCE(s.side, JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.side'))) side,
                       a.interval_code, COALESCE(a.bar_open_time, s.bar_open_time) bar_open_time,
                       a.event_type signal_source, a.outcome selected_action,
                       JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.decision')) decision, NULL score,
                       NULL threshold_value, NULL policy_mode, NULL freshness_state, a.blocker terminal_blocker,
                       a.reason blocker_reason, a.reason, a.outcome final_outcome, 'AUDIT_ONLY' execution_mode,
                       COALESCE(s.auto_traded, 0) order_sent,
                       CASE WHEN a.outcome = 'PASS' THEN NULL ELSE COALESCE(a.blocker, a.reason) END suppression_reason,
                       NULL intent_created, NULL oco_plan_created,
                       NULL ev_result_json, NULL tqs_result_json, a.context_json policy_inputs_json,
                       a.context_json features_snapshot_json, NULL execution_preview_json, NULL warnings_json, a.live_signal_id,
                       s.actual_entry_price, s.entry_price live_entry_price, s.suggested_tp, s.suggested_sl, s.realized_pnl,
                       a.id audit_id
                FROM (
                    SELECT *
                    FROM bt_decision_audit FORCE INDEX (idx_audit_symbol_time)
                    WHERE symbol = ? AND event_time >= ?
                      AND event_type IN ('SIGNAL_EVAL','SIGNAL_BUY','SIGNAL_SELL','FILTER_BLOCK','AUTOTRADE_OK','AUTOTRADE_FAIL','ENTRY_SKIP')
                """ + auditPopulationSql + auditStrategySql.replace("a.", "") + """
                    ORDER BY event_time DESC, id DESC
                    LIMIT ?
                ) a
                LEFT JOIN bt_live_signal s ON s.id = a.live_signal_id
                """, auditParams.toArray()));
        } catch (Exception e) {
            auditQuery = QueryRows.failure("AUDIT_QUERY_FAILED", e);
        }
        List<Map<String, Object>> auditRows = auditQuery.rows();
        int auditRowsFetched = auditRows.size();
        rows.addAll(auditRows);
        EvidenceEventCanonicalizer.MergeResult merge = EvidenceEventCanonicalizer.merge(rows);
        rows = new ArrayList<>(merge.rows());

        if (!includeNonActionable) {
            rows = rows.stream().filter(this::isPriceActionable).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        rows.sort((left, right) -> {
            LocalDateTime lt = asTime(left.get("evidence_time"));
            LocalDateTime rt = asTime(right.get("evidence_time"));
            boolean lc = lt != null && !lt.isAfter(completeCutoff);
            boolean rc = rt != null && !rt.isAfter(completeCutoff);
            if (lc != rc) return lc ? -1 : 1;
            int timeCompare = compareNullableTimeDesc(lt, rt);
            if (timeCompare != 0) return timeCompare;
            return Long.compare(asLong(right.get("decision_id")), asLong(left.get("decision_id")));
        });
        List<Map<String, Object>> limited = rows.size() <= limit ? rows : new ArrayList<>(rows.subList(0, limit));
        return new CanonicalLabelRows(List.copyOf(limited), merge, runtimeRowsFetched, auditRowsFetched, sourceLimit,
                runtimeQuery.succeeded(), auditQuery.succeeded(),
                queryErrors(runtimeQuery, auditQuery));
    }

    private String runtimeFilterPopulationSql() {
        return """
                 AND /* evidence_filter_eligible = 1 */
                     (
                       (
                         (NULLIF(TRIM(COALESCE(terminal_blocker, '')), '') IS NOT NULL
                           AND UPPER(TRIM(terminal_blocker)) NOT IN ('NONE','N/A','PASS','INFO'))
                         OR (NULLIF(TRIM(COALESCE(suppression_reason, '')), '') IS NOT NULL
                           AND UPPER(TRIM(suppression_reason)) NOT IN ('NONE','N/A','PASS','INFO'))
                         OR policy_mode IN ('BLOCK','READ_ONLY','HALT_TRADING','ALLOW_RISK_REDUCING_ONLY')
                         OR UPPER(COALESCE(blocker_reason, '')) REGEXP
                           'TRADE.?PLAN.?QUALITY|EXPECTED.?VALUE|EV.?GATE|DATA.?FRESHNESS|ENTRY.?DEDUP|DUPLICATE.?BAR|EVENT.?RISK|FEAR.?GREED|OCO|DAILY.?CAP|CAPACITY|OPEN.?POSITION|EXPOSURE|RISK.?BUDGET|MAX.?LOSS|NOTIONAL|INSUFFICIENT.?BALANCE|PROVIDER|ORDER.?NOT.?SENT|EXECUTION.?NOT.?SENT|RUNTIME.?EVIDENCE'
                       )
                       AND (
                         COALESCE(intent_created, 0) = 1
                         OR LOWER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(policy_inputs_json, '$.intentCreated')), 'false')) IN ('true','1')
                         OR LOWER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(execution_preview_json, '$.intentCreated')), 'false')) IN ('true','1')
                         OR LOWER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(features_snapshot_json, '$.intentCreated')), 'false')) IN ('true','1')
                       )
                       AND UPPER(CONCAT_WS(' ', selected_action, decision, signal_source)) REGEXP 'BUY|ENTRY'
                       AND UPPER(CONCAT_WS(' ', selected_action, decision, signal_source, side)) NOT REGEXP 'SELL|SHORT|EXIT'
                     )
                """;
    }

    private String auditFilterPopulationSql() {
        return """
                 AND /* audit_filter_eligible = 1 */
                     (
                       (
                         (NULLIF(TRIM(COALESCE(blocker, '')), '') IS NOT NULL
                           AND UPPER(TRIM(blocker)) NOT IN ('NONE','N/A','PASS','INFO'))
                         OR outcome IN ('BLOCK','BLOCKED','READ_ONLY','HALT_TRADING','ALLOW_RISK_REDUCING_ONLY')
                         OR UPPER(COALESCE(reason, '')) REGEXP
                           'TRADE.?PLAN.?QUALITY|EXPECTED.?VALUE|EV.?GATE|DATA.?FRESHNESS|ENTRY.?DEDUP|DUPLICATE.?BAR|EVENT.?RISK|FEAR.?GREED|OCO|DAILY.?CAP|CAPACITY|OPEN.?POSITION|EXPOSURE|RISK.?BUDGET|MAX.?LOSS|NOTIONAL|INSUFFICIENT.?BALANCE|PROVIDER|ORDER.?NOT.?SENT|EXECUTION.?NOT.?SENT|RUNTIME.?EVIDENCE'
                       )
                       AND LOWER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(context_json, '$.intentCreated')), 'false')) IN ('true','1')
                       AND UPPER(CONCAT_WS(' ', event_type,
                           JSON_UNQUOTE(JSON_EXTRACT(context_json, '$.selectedAction')),
                           JSON_UNQUOTE(JSON_EXTRACT(context_json, '$.decision')))) REGEXP 'BUY|ENTRY'
                       AND UPPER(CONCAT_WS(' ', event_type,
                           JSON_UNQUOTE(JSON_EXTRACT(context_json, '$.selectedAction')),
                           JSON_UNQUOTE(JSON_EXTRACT(context_json, '$.decision')),
                           JSON_UNQUOTE(JSON_EXTRACT(context_json, '$.side')))) NOT REGEXP 'SELL|SHORT|EXIT'
                     )
                """;
    }

    private LocalDateTime latestKlineTime(String symbol) {
        try {
            return jdbc.queryForObject("""
                    SELECT MAX(open_time) FROM md_kline
                    WHERE symbol = ? AND interval_code='1h' AND source='okx'
                    """, LocalDateTime.class, symbol);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LabelStats labelStats(String symbol, List<Map<String, Object>> rows, LabelHorizon horizon, boolean includeNonActionable) {
        LabelStats stats = new LabelStats();
        stats.totalCandidates = rows.size();
        Map<String, ForwardStats> forwardCache = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (isPriceActionable(row)) {
                stats.actionableCandidates++;
            } else {
                stats.nonActionableCandidates++;
                if (!includeNonActionable) {
                    stats.excludedNonActionableCount++;
                    continue;
                }
                stats.correctnessCounts.merge("NOT_PRICE_ACTIONABLE", 1, Integer::sum);
                stats.attributionCounts.merge("unscorable", 1, Integer::sum);
                continue;
            }
            PricePlan plan = pricePlan(row);
            ForwardStats fwd = loadForwardStatsCached(forwardCache, symbol, asTime(row.get("evidence_time")), plan, horizon);
            OutcomeLabel label = label(row, decisionPath(row), fwd, horizon);
            String attribution = attributionLabel(row, label.correctness(), fwd);
            if ("UNRESOLVED".equals(label.correctness())) {
                stats.unresolvedCandidates++;
                stats.unresolvedReasonCounts.merge(label.outcomeReason(), 1, Integer::sum);
            } else {
                stats.labeledCandidates++;
            }
            if (fwd.missingKline()) stats.missingKlineCount++;
            if ("MISSING_ENTRY_PRICE".equals(fwd.reason())) stats.missingEntryPriceCount++;
            if (!fwd.priceWindowComplete()) {
                stats.missingForwardWindowCount++;
                stats.pendingForwardWindowCount++;
            }
            if (fwd.priceWindowComplete()) {
                stats.matureForwardWindowCount++;
                if (!"UNRESOLVED".equals(label.correctness())) {
                    stats.matureLabeledCandidates++;
                }
            }
            if ("EXPLICIT".equals(fwd.entrySource())) stats.explicitEntryPriceCount++;
            if (fwd.entrySource().startsWith("DERIVED")) stats.entryPriceDerivedCount++;
            stats.correctnessCounts.merge(label.correctness(), 1, Integer::sum);
            stats.attributionCounts.merge(attribution, 1, Integer::sum);
        }
        return stats;
    }

    private ForwardStats loadForwardStatsCached(Map<String, ForwardStats> cache,
                                                String symbol,
                                                LocalDateTime eventTime,
                                                PricePlan plan,
                                                LabelHorizon horizon) {
        String key = symbol + "|" + horizon.code + "|" + eventTime + "|"
                + (plan == null ? "null" : Objects.toString(plan.entry(), "") + "|" + Objects.toString(plan.tp(), "") + "|" + Objects.toString(plan.sl(), ""));
        ForwardStats cached = cache.get(key);
        if (cached != null) return cached;
        ForwardStats loaded = loadForwardStats(symbol, eventTime, plan, horizon);
        if (cache.size() > 512) cache.clear();
        cache.put(key, loaded);
        return loaded;
    }

    private ForwardStats loadForwardStats(String symbol, LocalDateTime eventTime, PricePlan plan, LabelHorizon horizon) {
        if (symbol == null || symbol.isBlank()) return ForwardStats.empty("MISSING_SYMBOL", false);
        if (eventTime == null) return ForwardStats.empty("MISSING_DECISION_TIMESTAMP", false);
        try {
            BigDecimal entry = plan.entry();
            String entrySource = "EXPLICIT";
            if (entry == null) {
                entry = nearestCloseAtOrBefore(symbol, eventTime);
                entrySource = "DERIVED_NEAREST_OKX_CLOSE_AT_OR_BEFORE";
            }
            if (entry == null) {
                entry = closeAfter(symbol, eventTime);
                entrySource = "DERIVED_FIRST_OKX_CLOSE_AFTER";
            }
            if (entry == null || entry.signum() <= 0) return ForwardStats.empty("MISSING_ENTRY_PRICE", false);
            LocalDateTime latest = jdbc.queryForObject("""
                    SELECT MAX(open_time) FROM md_kline
                    WHERE symbol = ? AND interval_code='1h' AND source='okx'
                    """, LocalDateTime.class, symbol);
            if (latest == null) return ForwardStats.empty("MISSING_KLINE", true);
            boolean complete = !latest.isBefore(eventTime.plusHours(horizon.hours));
            BigDecimal c1 = closeAfter(symbol, eventTime.plusHours(1));
            BigDecimal c4 = closeAfter(symbol, eventTime.plusHours(4));
            BigDecimal c24 = closeAfter(symbol, eventTime.plusHours(24));
            List<Map<String, Object>> bars = jdbc.queryForList("""
                    SELECT open_time, high_price, low_price
                    FROM md_kline
                    WHERE symbol = ? AND interval_code='1h' AND source='okx'
                      AND open_time > ? AND open_time <= ?
                    ORDER BY open_time ASC
                    """, symbol, eventTime, eventTime.plusHours(horizon.hours));
            Map<String, Object> extreme = firstRow(jdbc.queryForList("""
                    SELECT MAX(high_price) max_high, MIN(low_price) min_low
                    FROM md_kline
                    WHERE symbol = ? AND interval_code='1h' AND source='okx'
                      AND open_time > ? AND open_time <= ?
                    """, symbol, eventTime, eventTime.plusHours(24)));
            String touch = firstTouch(plan, bars);
            return new ForwardStats(
                    pct(c1, entry),
                    pct(c4, entry),
                    pct(c24, entry),
                    pct(asDecimal(extreme.get("max_high")), entry),
                    pct(asDecimal(extreme.get("min_low")), entry),
                    touch,
                    complete,
                    false,
                    complete ? "OKX_1H_COMPLETE" : "FORWARD_WINDOW_INCOMPLETE",
                    entrySource);
        } catch (Exception ignored) {
            return ForwardStats.empty("MISSING_KLINE", true);
        }
    }

    private BigDecimal nearestCloseAtOrBefore(String symbol, LocalDateTime at) {
        try {
            return jdbc.queryForObject("""
                    SELECT close_price FROM md_kline
                    WHERE symbol = ? AND interval_code='1h' AND source='okx' AND open_time <= ?
                    ORDER BY open_time DESC LIMIT 1
                    """, BigDecimal.class, symbol, at);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal closeAfter(String symbol, LocalDateTime at) {
        try {
            return jdbc.queryForObject("""
                    SELECT close_price FROM md_kline
                    WHERE symbol = ? AND interval_code='1h' AND source='okx' AND open_time >= ?
                    ORDER BY open_time ASC LIMIT 1
                    """, BigDecimal.class, symbol, at);
        } catch (Exception e) {
            return null;
        }
    }

    private PricePlan pricePlan(Map<String, Object> row) {
        BigDecimal entry = firstDecimal(
                row.get("candidate_entry"),
                row.get("actual_entry_price"),
                row.get("live_entry_price"),
                jsonDecimal(row.get("execution_preview_json"), "entryPrice", "entry", "price", "signalPrice", "currentPrice"),
                jsonDecimal(row.get("features_snapshot_json"), "entryPrice", "entry", "price", "signalPrice", "currentPrice", "close", "closePrice"),
                jsonDecimal(row.get("policy_inputs_json"), "entryPrice", "entry", "price", "signalPrice", "currentPrice", "close", "closePrice"));
        BigDecimal tp = firstDecimal(
                row.get("candidate_tp"),
                row.get("suggested_tp"),
                jsonDecimal(row.get("execution_preview_json"), "tpPrice", "tp"),
                jsonDecimal(row.get("features_snapshot_json"), "tpPrice", "tp", "suggestedTp"),
                jsonDecimal(row.get("policy_inputs_json"), "tpPrice", "tp", "suggestedTp"));
        BigDecimal sl = firstDecimal(
                row.get("candidate_sl"),
                row.get("suggested_sl"),
                jsonDecimal(row.get("execution_preview_json"), "slPrice", "sl"),
                jsonDecimal(row.get("features_snapshot_json"), "slPrice", "sl", "suggestedSl"),
                jsonDecimal(row.get("policy_inputs_json"), "slPrice", "sl", "suggestedSl"));
        return new PricePlan(entry, tp, sl);
    }

    private BigDecimal firstDecimal(Object... values) {
        for (Object value : values) {
            BigDecimal bd = asDecimal(value);
            if (bd != null && bd.signum() > 0) return bd;
        }
        return null;
    }

    private BigDecimal jsonDecimal(Object rawJson, String... keys) {
        if (rawJson == null || rawJson.toString().isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(rawJson.toString());
            for (String key : keys) {
                JsonNode value = node.path(key);
                if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                    return new BigDecimal(value.asText());
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String firstTouch(PricePlan plan, List<Map<String, Object>> bars) {
        if (plan.entry() == null || plan.tp() == null || plan.sl() == null || bars == null || bars.isEmpty()) {
            return "NEITHER";
        }
        boolean longSide = plan.tp().compareTo(plan.entry()) >= 0;
        for (Map<String, Object> bar : bars) {
            BigDecimal high = asDecimal(bar.get("high_price"));
            BigDecimal low = asDecimal(bar.get("low_price"));
            if (high == null || low == null) continue;
            boolean tpHit = longSide ? high.compareTo(plan.tp()) >= 0 : low.compareTo(plan.tp()) <= 0;
            boolean slHit = longSide ? low.compareTo(plan.sl()) <= 0 : high.compareTo(plan.sl()) >= 0;
            if (tpHit && !slHit) return "TP_FIRST";
            if (slHit && !tpHit) return "SL_FIRST";
            if (tpHit) return "TP_FIRST";
        }
        return "NEITHER";
    }

    private Double pct(BigDecimal value, BigDecimal entry) {
        if (value == null || entry == null || entry.signum() == 0) return null;
        return value.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    String decisionPath(Map<String, Object> row) {
        if (isStrategyNoEntryIntent(row)) return "PASS";
        if (bool(row.get("order_sent"))) return "EXECUTED";
        String selected = upper(row.get("selected_action"));
        String policyMode = upper(row.get("policy_mode"));
        String outcome = upper(row.get("final_outcome"));
        if (selected.contains("BLOCK") || "BLOCK".equals(policyMode)
                || hasMeaningfulBlockValue(row.get("terminal_blocker"))) {
            return "BLOCK";
        }
        if (hasMeaningfulBlockValue(row.get("suppression_reason"))
                || hasExplicitIntent(row)
                || outcome.contains("SUPPRESS")
                || outcome.contains("REJECT")
                || outcome.contains("SKIP")
                || outcome.contains("FAIL")) {
            return "SUPPRESSED";
        }
        return "PASS";
    }

    private String correctnessLabel(Map<String, Object> row, ForwardStats fwd) {
        return label(row, decisionPath(row), fwd, LabelHorizon.H24).correctness();
    }

    private OutcomeLabel label(Map<String, Object> row, String path, ForwardStats fwd, LabelHorizon horizon) {
        GovernanceClassification governanceClassification = governanceClassification(row);
        if (governanceClassification == GovernanceClassification.STRATEGY_NO_ENTRY_INTENT
                || !isPriceActionable(row)) {
            return new OutcomeLabel("NOT_PRICE_ACTIONABLE", "NON_ACTIONABLE_SIGNAL", "HIGH", "NOT_PRICE_ACTIONABLE_SIGNAL");
        }
        if (isBlockLike(path)
                && governanceClassification != GovernanceClassification.TERMINAL_GUARD_BLOCK) {
            return new OutcomeLabel("NOT_GOVERNANCE_ELIGIBLE", "GOVERNANCE_CLASSIFICATION", "HIGH",
                    governanceClassification.name());
        }
        BigDecimal pnl = asDecimal(row.get("realized_pnl"));
        if (pnl != null && pnl.signum() != 0) {
            if (("EXECUTED".equals(path) || "PASS".equals(path))) {
                return new OutcomeLabel(pnl.signum() > 0 ? "TRUE_POSITIVE" : "FALSE_POSITIVE",
                        "REALIZED_TRADE", "HIGH", "realizedPnl=" + pnl.toPlainString());
            }
        }
        if (!fwd.priceWindowComplete()) {
            return new OutcomeLabel("UNRESOLVED", fwd.labelSource(), "LOW", fwd.reason());
        }
        if ("TP_FIRST".equals(fwd.firstTouchOutcome())) {
            return new OutcomeLabel("EXECUTED".equals(path) || "PASS".equals(path) ? "TRUE_POSITIVE" : "FALSE_BLOCK",
                    "FIRST_TOUCH", "HIGH", "TP_FIRST");
        }
        if ("SL_FIRST".equals(fwd.firstTouchOutcome())) {
            return new OutcomeLabel("EXECUTED".equals(path) || "PASS".equals(path) ? "FALSE_POSITIVE" : "TRUE_BLOCK",
                    "FIRST_TOUCH", "HIGH", "SL_FIRST");
        }
        Double r24 = fwd.horizonReturn(horizon);
        Double mfe = fwd.mfe24hPct();
        Double mae = fwd.mae24hPct();
        if (r24 == null) {
            return new OutcomeLabel("UNRESOLVED", fwd.labelSource(), "LOW", "MISSING_FORWARD_RETURN_" + horizon.code.toUpperCase(Locale.ROOT));
        }
        boolean passLike = "EXECUTED".equals(path) || "PASS".equals(path);
        if (passLike) {
            if (r24 >= MISSED_ALPHA_THRESHOLD_PCT) {
                return new OutcomeLabel("TRUE_POSITIVE", "FORWARD_RETURN_" + horizon.code.toUpperCase(Locale.ROOT), "MEDIUM", "forwardReturn" + horizon.code + " >= +1.0%");
            }
            if (r24 <= FALSE_POSITIVE_THRESHOLD_PCT) {
                return new OutcomeLabel("FALSE_POSITIVE", "FORWARD_RETURN_" + horizon.code.toUpperCase(Locale.ROOT), "MEDIUM", "forwardReturn" + horizon.code + " <= -0.5%");
            }
            return new OutcomeLabel("LOW_EDGE", "FORWARD_RETURN_" + horizon.code.toUpperCase(Locale.ROOT), "MEDIUM", "abs forward move too small");
        }
        boolean favorable = r24 >= MISSED_ALPHA_THRESHOLD_PCT || (mfe != null && mfe >= MISSED_ALPHA_THRESHOLD_PCT
                && (mae == null || Math.abs(mae) < mfe));
        if (favorable) {
            return new OutcomeLabel("FALSE_BLOCK", "FORWARD_WINDOW_" + horizon.code.toUpperCase(Locale.ROOT), "MEDIUM", "favorable forward move after block");
        }
        if (r24 <= 0 || (mae != null && mfe != null && Math.abs(mae) > Math.max(MISSED_ALPHA_THRESHOLD_PCT, mfe * 1.5))) {
            return new OutcomeLabel("TRUE_BLOCK", "FORWARD_WINDOW_" + horizon.code.toUpperCase(Locale.ROOT), "MEDIUM", "non-positive or adverse forward move after block");
        }
        return new OutcomeLabel("LOW_EDGE", "FORWARD_WINDOW_" + horizon.code.toUpperCase(Locale.ROOT), "MEDIUM", "movement too small to classify alpha");
    }

    private String attributionLabel(Map<String, Object> row, String correctness, ForwardStats fwd) {
        return switch (correctness) {
            case "TRUE_POSITIVE" -> "alpha";
            case "FALSE_POSITIVE" -> "noise";
            case "FALSE_BLOCK" -> "missed-alpha";
            case "TRUE_BLOCK" -> "blocked-correct";
            case "LOW_EDGE" -> "low-edge";
            default -> fwd.return24hPct() != null && fwd.return24hPct() < -MISSED_ALPHA_THRESHOLD_PCT ? "contra" : "unscorable";
        };
    }

    private List<LabeledOutcome> loadLabeledOutcomes(String symbol, int days, LabelHorizon horizon, int limit) {
        List<Map<String, Object>> rows = loadLabelRows(symbol, days * 24, null, limit, false, horizon);
        KlineOutcomeCache klineCache = buildKlineOutcomeCache(symbol, rows, horizon);
        List<LabeledOutcome> outcomes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ForwardStats fwd = loadForwardStatsFromKlineCache(klineCache, symbol, asTime(row.get("evidence_time")), pricePlan(row), horizon);
            String path = decisionPath(row);
            OutcomeLabel label = label(row, path, fwd, horizon);
            String correctness = label.correctness();
            if ("NOT_PRICE_ACTIONABLE".equals(correctness)
                    || "NOT_GOVERNANCE_ELIGIBLE".equals(correctness)) continue;
            outcomes.add(new LabeledOutcome(
                    asTime(row.get("evidence_time")),
                    Objects.toString(row.get("audit_id"), "N/A"),
                    Objects.toString(row.get("decision_id"), "N/A"),
                    Objects.toString(row.get("strategy_id"), "N/A"),
                    normalizeBlocker(firstNonBlank(row.get("terminal_blocker"), row.get("blocker_reason"), row.get("suppression_reason"), row.get("selected_action"), "UNKNOWN")),
                    firstNonBlank(row.get("signal_source"), row.get("selected_action"), "UNKNOWN"),
                    firstNonBlank(row.get("policy_mode"), "N/A"),
                    path,
                    correctness,
                    attributionLabel(row, correctness, fwd),
                    fwd.horizonReturn(horizon),
                    fwd.mfe24hPct(),
                    fwd.mae24hPct()));
        }
        return outcomes;
    }

    private KlineOutcomeCache buildKlineOutcomeCache(String symbol, List<Map<String, Object>> rows, LabelHorizon horizon) {
        if (rows == null || rows.isEmpty()) return KlineOutcomeCache.emptyCache();
        LocalDateTime min = null;
        LocalDateTime max = null;
        for (Map<String, Object> row : rows) {
            LocalDateTime t = asTime(row.get("evidence_time"));
            if (t == null) continue;
            if (min == null || t.isBefore(min)) min = t;
            if (max == null || t.isAfter(max)) max = t;
        }
        if (min == null || max == null) return KlineOutcomeCache.emptyCache();
        LocalDateTime from = min.minusHours(2);
        LocalDateTime to = max.plusHours(Math.max(24, horizon.hours) + 2L);
        try {
            List<Map<String, Object>> bars = jdbc.queryForList("""
                    SELECT /*+ SET_VAR(use_secondary_engine=OFF) MAX_EXECUTION_TIME(10000) */ open_time, high_price, low_price, close_price
                    FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open)
                    WHERE symbol = ? AND interval_code='1h' AND source='okx'
                      AND open_time >= ? AND open_time <= ?
                    ORDER BY open_time ASC
                    """, symbol, from, to);
            NavigableMap<LocalDateTime, KlineBar> byTime = new TreeMap<>();
            for (Map<String, Object> bar : bars) {
                LocalDateTime openTime = asTime(bar.get("open_time"));
                BigDecimal high = asDecimal(bar.get("high_price"));
                BigDecimal low = asDecimal(bar.get("low_price"));
                BigDecimal close = asDecimal(bar.get("close_price"));
                if (openTime == null || high == null || low == null || close == null) continue;
                byTime.put(openTime, new KlineBar(openTime, high, low, close));
            }
            if (byTime.isEmpty()) return KlineOutcomeCache.emptyCache();
            return new KlineOutcomeCache(byTime, byTime.lastKey());
        } catch (Exception ignored) {
            return KlineOutcomeCache.emptyCache();
        }
    }

    private ForwardStats loadForwardStatsFromKlineCache(KlineOutcomeCache cache,
                                                        String symbol,
                                                        LocalDateTime eventTime,
                                                        PricePlan plan,
                                                        LabelHorizon horizon) {
        if (symbol == null || symbol.isBlank()) return ForwardStats.empty("MISSING_SYMBOL", false);
        if (eventTime == null) return ForwardStats.empty("MISSING_DECISION_TIMESTAMP", false);
        if (cache == null || cache.empty()) return loadForwardStats(symbol, eventTime, plan, horizon);
        BigDecimal entry = plan.entry();
        String entrySource = "EXPLICIT";
        if (entry == null) {
            KlineBar floor = cache.floor(eventTime);
            if (floor != null) {
                entry = floor.close();
                entrySource = "DERIVED_NEAREST_OKX_CLOSE_AT_OR_BEFORE";
            }
        }
        if (entry == null) {
            KlineBar ceiling = cache.ceiling(eventTime);
            if (ceiling != null) {
                entry = ceiling.close();
                entrySource = "DERIVED_FIRST_OKX_CLOSE_AFTER";
            }
        }
        if (entry == null || entry.signum() <= 0) return ForwardStats.empty("MISSING_ENTRY_PRICE", false);

        boolean complete = cache.latestOpenTime() != null && !cache.latestOpenTime().isBefore(eventTime.plusHours(horizon.hours));
        BigDecimal c1 = closeFromCache(cache, eventTime.plusHours(1));
        BigDecimal c4 = closeFromCache(cache, eventTime.plusHours(4));
        BigDecimal c24 = closeFromCache(cache, eventTime.plusHours(24));
        List<KlineBar> horizonBars = cache.bars(eventTime, eventTime.plusHours(horizon.hours));
        List<KlineBar> dayBars = cache.bars(eventTime, eventTime.plusHours(24));
        BigDecimal maxHigh = null;
        BigDecimal minLow = null;
        for (KlineBar bar : dayBars) {
            if (maxHigh == null || bar.high().compareTo(maxHigh) > 0) maxHigh = bar.high();
            if (minLow == null || bar.low().compareTo(minLow) < 0) minLow = bar.low();
        }
        return new ForwardStats(
                pct(c1, entry),
                pct(c4, entry),
                pct(c24, entry),
                pct(maxHigh, entry),
                pct(minLow, entry),
                firstTouchCached(plan, horizonBars),
                complete,
                false,
                complete ? "OKX_1H_COMPLETE_BATCH" : "FORWARD_WINDOW_INCOMPLETE",
                entrySource);
    }

    private BigDecimal closeFromCache(KlineOutcomeCache cache, LocalDateTime at) {
        KlineBar bar = cache.ceiling(at);
        return bar == null ? null : bar.close();
    }

    private String firstTouchCached(PricePlan plan, List<KlineBar> bars) {
        if (plan.entry() == null || plan.tp() == null || plan.sl() == null || bars == null || bars.isEmpty()) {
            return "NEITHER";
        }
        boolean longSide = plan.tp().compareTo(plan.entry()) >= 0;
        for (KlineBar bar : bars) {
            boolean tpHit = longSide ? bar.high().compareTo(plan.tp()) >= 0 : bar.low().compareTo(plan.tp()) <= 0;
            boolean slHit = longSide ? bar.low().compareTo(plan.sl()) <= 0 : bar.high().compareTo(plan.sl()) >= 0;
            if (tpHit && !slHit) return "TP_FIRST";
            if (slHit && !tpHit) return "SL_FIRST";
            if (tpHit) return "TP_FIRST";
        }
        return "NEITHER";
    }

    private GovernanceStats governanceStats(List<LabeledOutcome> outcomes) {
        GovernanceStats stats = new GovernanceStats();
        for (LabeledOutcome outcome : outcomes) {
            stats.actionableCandidates++;
            if ("UNRESOLVED".equals(outcome.correctness)) {
                stats.unresolvedCandidates++;
                continue;
            }
            stats.labeledCandidates++;
            if (isBlockLike(outcome.path)) stats.blockLikeCount++;
            switch (outcome.correctness) {
                case "TRUE_BLOCK" -> stats.trueBlockCount++;
                case "FALSE_BLOCK" -> stats.falseBlockCount++;
                case "LOW_EDGE" -> stats.lowEdgeCount++;
                case "TRUE_POSITIVE" -> stats.truePositiveCount++;
                case "FALSE_POSITIVE" -> stats.falsePositiveCount++;
                default -> {
                }
            }
        }
        return stats;
    }

    private String governanceMode(GovernanceStats stats) {
        int sample = stats.labeledCandidates;
        double falseBlockRate = rate(stats.falseBlockCount, sample);
        double falsePositiveRate = rate(stats.falsePositiveCount, sample);
        double trueBlockRate = rate(stats.trueBlockCount, sample);
        if (sample < 10) return "INSUFFICIENT_DATA";
        if (falseBlockRate >= 20.0 && sample >= 20) return "TOO_STRICT";
        if (falsePositiveRate >= 30.0 && sample >= 10) return "TOO_LOOSE";
        if (trueBlockRate >= 50.0 && falseBlockRate < 20.0) return "NORMAL";
        return "NORMAL";
    }

    private Map<String, DriftBucket> blockerBuckets(List<LabeledOutcome> outcomes) {
        Map<String, DriftBucket> buckets = new LinkedHashMap<>();
        for (LabeledOutcome outcome : outcomes) {
            if ("UNRESOLVED".equals(outcome.correctness)) continue;
            buckets.computeIfAbsent(outcome.blocker, DriftBucket::new).add(outcome);
        }
        return buckets;
    }

    private Map<String, DriftBucket> sourceBuckets(List<LabeledOutcome> outcomes) {
        Map<String, DriftBucket> buckets = new LinkedHashMap<>();
        for (LabeledOutcome outcome : outcomes) {
            if ("UNRESOLVED".equals(outcome.correctness)) continue;
            String key = outcome.source + "|strategy=" + outcome.strategyId + "|policy=" + outcome.policyMode;
            buckets.computeIfAbsent(key, DriftBucket::new).add(outcome);
        }
        return buckets;
    }

    private boolean isBlockLike(String path) {
        return "BLOCK".equals(path) || "SUPPRESSED".equals(path);
    }

    private double rate(int count, int total) {
        return total <= 0 ? 0.0 : count * 100.0 / total;
    }

    private String fmtRate(int count, int total) {
        return total <= 0 ? "N/A" : fmtPct(rate(count, total));
    }

    private String verdictForMode(String mode, GovernanceStats stats) {
        return switch (mode) {
            case "TOO_STRICT" -> "Governance may be suppressing labeled alpha; inspect relaxation candidates before changing execution caps.";
            case "TOO_LOOSE" -> "Passed/executed signals show too many false positives; tighten confirmation before increasing live autonomy.";
            case "INSUFFICIENT_DATA" -> "Not enough labeled outcomes for drift judgement.";
            default -> "Governance appears within expected bounds for the selected horizon.";
        };
    }

    private String recommendationForMode(String mode) {
        return switch (mode) {
            case "TOO_STRICT" -> "Run findGovernanceRelaxationCandidates and review blocker-level falseBlock evidence.";
            case "TOO_LOOSE" -> "Run findGovernanceTighteningCandidates and inspect false-positive sources.";
            case "INSUFFICIENT_DATA" -> "Use 1h horizon or wait for more mature labels before policy changes.";
            default -> "Keep current governance; monitor blocker drift matrix and timeline.";
        };
    }

    private String suggestRelaxation(String blocker) {
        if (blocker.contains("DuplicateBar")) return "use distinctOpportunityKey instead of coarse same-bar blocking";
        if (blocker.contains("EventRisk")) return "treat R2 as warning for capped $5 tiny-live; keep R3 override explicit";
        if (blocker.contains("daily cap")) return "consider 2/day only if loss budget and OCO protections remain healthy";
        if (blocker.contains("risk budget")) return "review max loss budget sizing; do not bypass OCO/loss-budget hard gates";
        if (blocker.contains("FearGreed")) return "keep as TQS penalty/WARN, not terminal block";
        if (blocker.contains("EntryDedup")) return "review whether dedup key is too coarse for distinct opportunities";
        return "review blocker threshold with tiny-live cap; do not bypass hard safety gates";
    }

    private String resolveFilterBlocker(Map<String, Object> row) {
        String terminal = firstNonBlank(row.get("terminal_blocker"), "");
        String reason = firstNonBlank(row.get("blocker_reason"), "");
        String suppression = firstNonBlank(row.get("suppression_reason"), "");
        String policy = firstNonBlank(row.get("policy_mode"), "");
        String selected = firstNonBlank(row.get("selected_action"), "");
        String combined = (terminal + " " + reason + " " + suppression + " " + policy + " " + selected)
                .toLowerCase(Locale.ROOT);
        if (combined.contains("daily new auto-entry cap")
                || combined.contains("daily cap")
                || combined.contains("shadow daily learning cap")
                || combined.contains("max_tiny_live_orders")) {
            return "daily cap";
        }
        if (combined.contains("open max loss")
                || combined.contains("max loss")
                || combined.contains("loss budget")
                || combined.contains("risk budget")) {
            return "risk budget cap";
        }
        if (combined.contains("open tiny-live position")
                || combined.contains("open_tiny_live_position")
                || combined.contains("same strategy/symbol/interval")
                || combined.contains("position already open")) {
            return "open-position guard";
        }
        return normalizeBlocker(firstNonBlank(row.get("terminal_blocker"), row.get("blocker_reason"), row.get("suppression_reason"), row.get("policy_mode"), "UNKNOWN"));
    }

    private String scopeSuffix(Map<String, Object> row) {
        String suppression = firstNonBlank(row.get("suppression_reason"), "");
        String policy = firstNonBlank(row.get("policy_mode"), "");
        String features = Objects.toString(row.get("features_snapshot_json"), "").toLowerCase(Locale.ROOT);
        String combined = (suppression + " " + policy + " " + features).toLowerCase(Locale.ROOT);
        if (combined.contains("shadow_mode")
                || combined.contains("\"write_mode\": false")
                || combined.contains("\"write_mode\":false")
                || combined.contains("\"notify_only\": true")
                || combined.contains("\"notify_only\":true")) {
            return " [SHADOW_ONLY]";
        }
        return "";
    }

    private boolean isMissedAlphaProxy(ForwardStats fwd) {
        return (fwd.return24hPct() != null && fwd.return24hPct() >= MISSED_ALPHA_THRESHOLD_PCT)
                || (fwd.mfe24hPct() != null && fwd.mfe24hPct() >= MISSED_ALPHA_THRESHOLD_PCT);
    }

    private String normalizeBlocker(String raw) {
        String s = raw == null ? "UNKNOWN" : raw;
        if (s.contains("DuplicateBar")) return "DuplicateBar";
        if (s.contains("EVENT_RISK") || s.contains("EventRisk")) return "EventRisk";
        if (s.contains("EXPECTED_VALUE") || s.contains("EV")) return "EV gate";
        if (s.contains("OCO")) return "OCO";
        if (s.contains("DAILY_CAP") || s.contains("MAX_TINY_LIVE_ORDERS")) return "daily cap";
        if (s.contains("OPEN") || s.contains("POSITION")) return "open-position guard";
        if (s.toLowerCase(Locale.ROOT).contains("max loss")) return "risk budget cap";
        if (s.contains("EXPOSURE")) return "exposure cap";
        if (s.contains("DataFreshness")) return "DataFreshness";
        if (s.contains("RUNTIME_EVIDENCE")) return "Runtime Evidence missing";
        return s.length() > 28 ? s.substring(0, 28) : s;
    }

    private String safeCall(ThrowingSupplier supplier, String label) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return label + " unavailable: " + e.getMessage();
        }
    }

    private String excerpt(String text, int maxLines) {
        if (text == null || text.isBlank()) return "N/A\n";
        String[] lines = text.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(maxLines, lines.length); i++) {
            sb.append(lines[i]).append("\n");
        }
        if (lines.length > maxLines) sb.append("... truncated; call source MCP tool for full detail\n");
        return sb.toString();
    }

    private String indent(String text, int spaces) {
        String prefix = " ".repeat(Math.max(0, spaces));
        return prefix + text.replace("\n", "\n" + prefix);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private LabelHorizon normalizeLabelHorizon(String value) {
        if (value == null || value.isBlank()) return LabelHorizon.H1;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "4h", "h4" -> LabelHorizon.H4;
            case "24h", "h24", "1d" -> LabelHorizon.H24;
            default -> LabelHorizon.H1;
        };
    }

    boolean isPriceActionable(Map<String, Object> row) {
        if (row == null) return false;
        if (row.containsKey("canonical_merge_eligible") && !bool(row.get("canonical_merge_eligible"))) return false;
        if (governanceClassification(row) == GovernanceClassification.STRATEGY_NO_ENTRY_INTENT) return false;
        if (isInformationalPass(row) || isNonBuyStateTransition(row) || isExplicitNonBuyEvaluation(row)) return false;
        if (bool(row.get("order_sent")) || hasMeaningfulIdentifier(row.get("live_signal_id"))) return true;
        if (hasExplicitIntent(row)) return true;
        String signal = upper(row.get("signal_source"));
        String selected = upper(row.get("selected_action"));
        String decision = upper(row.get("decision"));
        String blocker = upper(firstNonBlank(row.get("terminal_blocker"), row.get("blocker_reason"), row.get("suppression_reason"), ""));
        String compactBlocker = blocker.replaceAll("[^A-Z0-9]", "");
        if (signal.contains("ENTRY") || signal.contains("AUTOTRADE") || signal.contains("SIGNAL_BUY") || signal.contains("SIGNAL_SELL")) {
            return true;
        }
        if (signal.contains("FILTER_BLOCK")) return true;
        if (selected.contains("BUY") || selected.contains("SELL") || selected.contains("BLOCK")
                || decision.contains("BUY") || decision.contains("SELL") || decision.contains("BLOCK")) return true;
        if (compactBlocker.contains("DUPLICATEBAR") || compactBlocker.contains("EVENTRISK")
                || compactBlocker.contains("EXPECTEDVALUE") || compactBlocker.contains("FEARGREED")
                || compactBlocker.contains("DATAFRESHNESS") || compactBlocker.contains("OCO")
                || compactBlocker.contains("TRADEPLANQUALITY") || compactBlocker.contains("ENTRYDEDUP")
                || compactBlocker.contains("POSITIONSIZING") || compactBlocker.contains("OPENPOSITION")
                || compactBlocker.contains("EXPOSURE")) {
            return true;
        }
        return false;
    }

    GovernanceClassification governanceClassification(Map<String, Object> row) {
        if (row == null) return GovernanceClassification.OTHER;
        if (isStrategyNoEntryIntent(row)) return GovernanceClassification.STRATEGY_NO_ENTRY_INTENT;
        if (bool(row.get("order_sent"))) return GovernanceClassification.ALLOWED_OR_EXECUTED;

        String path = decisionPathWithoutClassification(row);
        if (!isBlockLike(path)) return GovernanceClassification.ALLOWED_OR_EXECUTED;
        if (!hasExecutableBuyEntryIntent(row)) return GovernanceClassification.STRATEGY_NO_ENTRY_INTENT;

        String blocker = governanceSemanticText(row);
        if (containsAny(blocker,
                "DAILY_CAP", "DAILY CAP", "DAILY NEW AUTO-ENTRY CAP", "MAX_TINY_LIVE", "CAPACITY", "OPEN_POSITION",
                "OPEN POSITION", "POSITION_ALREADY_OPEN", "POSITION ALREADY OPEN", "EXPOSURE",
                "RISK_BUDGET", "RISK BUDGET", "MAX_LOSS", "MAX LOSS", "NOTIONAL",
                "INSUFFICIENT_BALANCE", "INSUFFICIENT BALANCE", "PROVIDER", "ORDER_NOT_SENT",
                "EXECUTION_NOT_SENT", "RUNTIME_EVIDENCE")) {
            return GovernanceClassification.EXECUTION_CAPACITY_BLOCK;
        }
        if (containsAny(blocker,
                "TRADEPLANQUALITY", "TRADE_PLAN_QUALITY", "EXPECTED_VALUE", "EV_GATE",
                "DATAFRESHNESS", "DATA_FRESHNESS", "ENTRYDEDUP", "ENTRY_DEDUP", "DUPLICATEBAR",
                "DUPLICATE_BAR", "EVENTRISK", "EVENT_RISK", "FEARGREED", "FEAR_GREED", "OCO")) {
            return GovernanceClassification.TERMINAL_GUARD_BLOCK;
        }
        return GovernanceClassification.UNCLASSIFIED_BLOCK;
    }

    private boolean isStrategyNoEntryIntent(Map<String, Object> row) {
        return EvidenceGovernanceSemantics.isStrategyNoEntryIntent(row);
    }

    private boolean hasExecutableBuyEntryIntent(Map<String, Object> row) {
        return EvidenceGovernanceSemantics.hasExecutableBuyEntryIntent(row);
    }

    private String governanceSemanticText(Map<String, Object> row) {
        return String.join(" ",
                upper(row.get("terminal_blocker")), upper(row.get("blocker_reason")),
                upper(row.get("suppression_reason")), upper(row.get("selected_action")),
                upper(row.get("decision")), upper(row.get("signal_source")), upper(row.get("final_outcome")),
                upper(row.get("policy_inputs_json")), upper(row.get("execution_preview_json")),
                upper(row.get("features_snapshot_json")));
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) return true;
        }
        return false;
    }

    private String decisionPathWithoutClassification(Map<String, Object> row) {
        if (bool(row.get("order_sent"))) return "EXECUTED";
        String selected = upper(row.get("selected_action"));
        String policyMode = upper(row.get("policy_mode"));
        String outcome = upper(row.get("final_outcome"));
        if (selected.contains("BLOCK") || "BLOCK".equals(policyMode)
                || hasMeaningfulBlockValue(row.get("terminal_blocker"))) return "BLOCK";
        if (hasMeaningfulBlockValue(row.get("suppression_reason"))
                || hasExplicitIntent(row) || outcome.contains("SUPPRESS") || outcome.contains("REJECT")
                || outcome.contains("SKIP") || outcome.contains("FAIL")) return "SUPPRESSED";
        return "PASS";
    }

    private boolean isInformationalPass(Map<String, Object> row) {
        if (bool(row.get("order_sent")) || hasExplicitIntent(row)) {
            return false;
        }
        String selected = upper(row.get("selected_action"));
        String decision = upper(row.get("decision"));
        String combined = selected + " " + decision + " " + upper(row.get("terminal_blocker")) + " "
                + upper(row.get("blocker_reason")) + " " + upper(row.get("suppression_reason"));
        return (combined.contains("EXPECTEDVALUEGATEPASS") && combined.contains("INFO"))
                || ("SMALL_DRY_RUN".equals(selected) && ("PASS".equals(decision) || combined.contains("INFO")));
    }

    private boolean isNonBuyStateTransition(Map<String, Object> row) {
        String signal = upper(row.get("signal_source"));
        String selected = upper(row.get("selected_action"));
        return selected.contains("DONCHIAN_SHADOW_STATE_ADVANCE")
                || (signal.contains("DONCHIAN") && selected.contains("STATE_ADVANCE"));
    }

    private boolean isExplicitNonBuyEvaluation(Map<String, Object> row) {
        if (bool(row.get("order_sent")) || hasExplicitIntent(row)) return false;
        String selected = upper(row.get("selected_action"));
        String decision = upper(row.get("decision"));
        return decision.contains("HOLD")
                || ((selected.contains("HOLD") || selected.contains("EVALUATED_ONLY"))
                && !decision.contains("BUY") && !decision.contains("SELL"));
    }

    private boolean hasExplicitIntent(Map<String, Object> row) {
        return EvidenceGovernanceSemantics.hasExplicitIntent(row);
    }

    private boolean jsonBoolean(Object rawJson, String... keys) {
        if (rawJson == null || rawJson.toString().isBlank()) return false;
        try {
            JsonNode node = rawJson instanceof JsonNode jsonNode ? jsonNode : JSON.readTree(rawJson.toString());
            for (String key : keys) {
                JsonNode value = node.path(key);
                if ((value.isBoolean() && value.asBoolean())
                        || (value.isNumber() && value.asInt() != 0)
                        || (value.isTextual() && Boolean.parseBoolean(value.asText()))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private boolean hasMeaningfulIdentifier(Object value) {
        if (value == null) return false;
        String normalized = value.toString().trim();
        return !normalized.isBlank() && !"0".equals(normalized) && !"N/A".equalsIgnoreCase(normalized);
    }

    private boolean hasMeaningfulBlockValue(Object value) {
        String normalized = upper(value);
        if (normalized.isBlank()
                || List.of("NONE", "N/A", "NA", "NULL", "UNKNOWN", "NOT_APPLICABLE", "PASS", "INFO", "PENDING")
                .contains(normalized)) {
            return false;
        }
        return !(normalized.contains("GATEPASS") && normalized.contains("INFO"));
    }

    private String upper(Object value) {
        return value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private int normalizeHours(Integer hours) {
        return hours == null || hours <= 0 ? 24 : Math.min(hours, 720);
    }

    private int normalizeDays(Integer days, int def, int max) {
        return days == null || days <= 0 ? def : Math.min(days, max);
    }

    private int normalizeLimit(Integer limit, int def, int max) {
        return limit == null || limit <= 0 ? def : Math.min(limit, max);
    }

    private Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private String value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "N/A" : value.toString();
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        return "N/A";
    }

    private String shortJson(Object json) {
        if (json == null) return "N/A";
        String s = json.toString().replace("\n", " ");
        return s.length() > 240 ? s.substring(0, 240) + "..." : s;
    }

    private int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private Long nullableLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null || value.toString().isBlank()) return null;
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private int compareNullableTimeDesc(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return right.compareTo(left);
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal b) return b;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer jsonInt(Object rawJson, String... keys) {
        BigDecimal decimal = jsonDecimal(rawJson, keys);
        return decimal == null ? null : decimal.intValue();
    }

    private LocalDateTime asTime(Object value) {
        if (value instanceof LocalDateTime t) return t;
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return null;
    }

    private String fmtPct(Double value) {
        return value == null ? "N/A" : String.format(Locale.US, "%+.2f%%", value);
    }

    private String joinList(List<String> values) {
        return values == null || values.isEmpty() ? "N/A" : String.join(", ", values);
    }

    private static List<String> queryErrors(QueryRows... queries) {
        return java.util.Arrays.stream(queries).map(QueryRows::error)
                .filter(Objects::nonNull).toList();
    }

    private record QueryRows(List<Map<String, Object>> rows, boolean succeeded, String error) {
        static QueryRows success(List<Map<String, Object>> rows) {
            return new QueryRows(rows == null ? List.of() : List.copyOf(rows), true, null);
        }

        static QueryRows failure(String code, Exception error) {
            String detail = error == null || error.getClass().getSimpleName().isBlank()
                    ? code : code + ":" + error.getClass().getSimpleName();
            return new QueryRows(List.of(), false, detail);
        }
    }

    private record CanonicalLabelRows(List<Map<String, Object>> rows,
                                      EvidenceEventCanonicalizer.MergeResult merge,
                                      int runtimeRowsFetched,
                                      int auditRowsFetched,
                                      int sourceLimit,
                                      boolean runtimeQuerySucceeded,
                                      boolean auditQuerySucceeded,
                                      List<String> queryErrors) {
        String diagnostics() {
            boolean queryTruncated = runtimeRowsFetched >= sourceLimit || auditRowsFetched >= sourceLimit;
            return "runtimeRowsFetched=" + runtimeRowsFetched + "\n"
                    + "auditRowsFetched=" + auditRowsFetched + "\n"
                    + "sourceLimit=" + sourceLimit + "\n"
                    + "runtimeQuerySucceeded=" + runtimeQuerySucceeded + "\n"
                    + "auditQuerySucceeded=" + auditQuerySucceeded + "\n"
                    + "queryErrors=" + queryErrors + "\n"
                    + "queryTruncated=" + queryTruncated + "\n"
                    + "requestedWindowComplete="
                    + (runtimeQuerySucceeded && auditQuerySucceeded && !queryTruncated) + "\n"
                    + "fetchedRawObservationCount=" + merge.rawObservationCount() + "\n"
                    + "rawObservationCount=" + merge.rawObservationCount() + "\n"
                    + "uniqueMergedEventCount=" + merge.uniqueMergedEventCount() + "\n"
                    + "returnedEventCount=" + rows.size() + "\n"
                    + "duplicateRepresentationCount=" + merge.duplicateRepresentationCount() + "\n"
                    + "identityConflictCount=" + merge.identityConflictCount() + "\n"
                    + "fieldConflictCount=" + merge.fieldConflictCount() + "\n"
                    + "semanticConflictCount=" + merge.semanticConflictCount() + "\n"
                    + "duplicateSuspectCount=" + merge.duplicateSuspectCount() + "\n"
                    + "fetchedRawCountConserved=" + merge.conservesRawCount() + "\n"
                    + "rawCountConserved=" + merge.conservesRawCount() + "\n";
        }
    }

    private record Summary(int auditCandidates,
                           int signalEval,
                           int pass,
                           int blocked,
                           int executed,
                           int evidenceRows,
                           int runtimeExecutions,
                           int runtimeBlocks,
                           int tinyLiveExecutions,
                           int tinyLiveOrderSent,
                           int tinyLiveOcoAttached,
                           String falseBlockProxy,
                           String missedAlphaProxy,
                           List<String> topFalseBlocks,
                           List<String> topSuppressors) {
    }

    private record PricePlan(BigDecimal entry, BigDecimal tp, BigDecimal sl) {
    }

    private record OutcomeLabel(String correctness, String labelSource, String labelConfidence, String outcomeReason) {
    }

    private record LabeledOutcome(LocalDateTime eventTime,
                                  String auditId,
                                  String decisionId,
                                  String strategyId,
                                  String blocker,
                                  String source,
                                  String policyMode,
                                  String path,
                                  String correctness,
                                  String attribution,
                                  Double forwardReturn,
                                  Double mfe,
                                  Double mae) {
    }

    private record GovernanceDriftCacheKey(String symbol, int days, String horizon) {
    }

    private record GovernanceDriftCacheEntry(Instant createdAt, List<LabeledOutcome> outcomes) {
    }

    private record KlineBar(LocalDateTime openTime, BigDecimal high, BigDecimal low, BigDecimal close) {
    }

    private record KlineOutcomeCache(NavigableMap<LocalDateTime, KlineBar> bars, LocalDateTime latestOpenTime) {
        static KlineOutcomeCache emptyCache() {
            return new KlineOutcomeCache(new TreeMap<>(), null);
        }

        boolean empty() {
            return bars == null || bars.isEmpty();
        }

        KlineBar floor(LocalDateTime at) {
            if (empty() || at == null) return null;
            Map.Entry<LocalDateTime, KlineBar> entry = bars.floorEntry(at);
            return entry == null ? null : entry.getValue();
        }

        KlineBar ceiling(LocalDateTime at) {
            if (empty() || at == null) return null;
            Map.Entry<LocalDateTime, KlineBar> entry = bars.ceilingEntry(at);
            return entry == null ? null : entry.getValue();
        }

        List<KlineBar> bars(LocalDateTime fromExclusive, LocalDateTime toInclusive) {
            if (empty() || fromExclusive == null || toInclusive == null || !fromExclusive.isBefore(toInclusive)) {
                return Collections.emptyList();
            }
            return new ArrayList<>(bars.subMap(fromExclusive, false, toInclusive, true).values());
        }
    }

    private static final class GovernanceStats {
        private int actionableCandidates;
        private int labeledCandidates;
        private int unresolvedCandidates;
        private int blockLikeCount;
        private int trueBlockCount;
        private int falseBlockCount;
        private int lowEdgeCount;
        private int truePositiveCount;
        private int falsePositiveCount;
    }

    private final class DriftBucket {
        private final String blocker;
        private int blockCount;
        private int trueBlockCount;
        private int falseBlockCount;
        private int lowEdgeCount;
        private int missedAlphaCount;
        private int falsePositiveCount;
        private double forwardReturnSum;
        private int forwardReturnN;
        private double mfeSum;
        private int mfeN;
        private double maeSum;
        private int maeN;
        private final List<String> examples = new ArrayList<>();
        private final List<String> strategyIds = new ArrayList<>();
        private final List<String> policyModes = new ArrayList<>();
        private double missedForwardReturnSum;
        private int missedForwardReturnN;

        private DriftBucket(String blocker) {
            this.blocker = blocker;
        }

        private void add(LabeledOutcome outcome) {
            blockCount++;
            if ("TRUE_BLOCK".equals(outcome.correctness)) trueBlockCount++;
            if ("FALSE_BLOCK".equals(outcome.correctness)) {
                falseBlockCount++;
                missedAlphaCount++;
                if (outcome.forwardReturn != null) {
                    missedForwardReturnSum += outcome.forwardReturn;
                    missedForwardReturnN++;
                }
            }
            if ("LOW_EDGE".equals(outcome.correctness)) lowEdgeCount++;
            if ("FALSE_POSITIVE".equals(outcome.correctness)) falsePositiveCount++;
            if (outcome.forwardReturn != null) {
                forwardReturnSum += outcome.forwardReturn;
                forwardReturnN++;
            }
            if (outcome.mfe != null) {
                mfeSum += outcome.mfe;
                mfeN++;
            }
            if (outcome.mae != null) {
                maeSum += outcome.mae;
                maeN++;
            }
            if (examples.size() < 5) examples.add(outcome.auditId);
            if (strategyIds.size() < 3 && outcome.strategyId != null && !"N/A".equals(outcome.strategyId) && !strategyIds.contains(outcome.strategyId)) {
                strategyIds.add(outcome.strategyId);
            }
            if (policyModes.size() < 3 && outcome.policyMode != null && !"N/A".equals(outcome.policyMode) && !policyModes.contains(outcome.policyMode)) {
                policyModes.add(outcome.policyMode);
            }
        }

        private double falseBlockRate() {
            return blockCount == 0 ? 0.0 : falseBlockCount * 100.0 / blockCount;
        }

        private double trueBlockRate() {
            return blockCount == 0 ? 0.0 : trueBlockCount * 100.0 / blockCount;
        }

        private double falsePositiveRate() {
            return blockCount == 0 ? 0.0 : falsePositiveCount * 100.0 / blockCount;
        }

        private double avgForwardReturn() {
            return forwardReturnN == 0 ? Double.NaN : forwardReturnSum / forwardReturnN;
        }

        private double avgMfe() {
            return mfeN == 0 ? Double.NaN : mfeSum / mfeN;
        }

        private double avgMae() {
            return maeN == 0 ? Double.NaN : maeSum / maeN;
        }

        private double avgMissedForwardReturn() {
            return missedForwardReturnN == 0 ? Double.NaN : missedForwardReturnSum / missedForwardReturnN;
        }

        private String firstStrategyId() {
            return strategyIds.isEmpty() ? "N/A" : String.join(",", strategyIds);
        }

        private String firstPolicyMode() {
            return policyModes.isEmpty() ? "N/A" : String.join(",", policyModes);
        }

        private String recommendation() {
            if (blockCount < 5) return "INSUFFICIENT_SAMPLE";
            if (falsePositiveRate() >= 30.0) return "TIGHTEN_CANDIDATE";
            if (blockCount >= 10 && falseBlockRate() >= 20.0 && missedAlphaCount >= 3) return "RELAX_CANDIDATE";
            if (falseBlockRate() >= 10.0 || lowEdgeCount * 100.0 / blockCount >= 30.0) return "WATCH";
            return "KEEP";
        }

        private String trend(DriftBucket previous) {
            if (previous == null || previous.blockCount < 5) return "NO_BASELINE";
            double delta = falseBlockRate() - previous.falseBlockRate();
            if (delta >= 10.0) return "WORSE_FALSE_BLOCKS";
            if (delta <= -10.0) return "IMPROVED_FALSE_BLOCKS";
            return "STABLE";
        }

        private String line(DriftBucket previous) {
            return String.format(Locale.US,
                    "%-24s | %6d | %9d | %10d | %7d | %11d | %10s | %9s | %8s | %8s | %-12s | %s",
                    blocker.length() > 24 ? blocker.substring(0, 24) : blocker,
                    blockCount,
                    trueBlockCount,
                    falseBlockCount,
                    lowEdgeCount,
                    missedAlphaCount,
                    fmtPct(falseBlockRate()),
                    fmtPct(trueBlockRate()),
                    Double.isNaN(avgForwardReturn()) ? "N/A" : fmtPct(avgForwardReturn()),
                    trend(previous),
                    recommendation(),
                    joinList(examples));
        }
    }

    private enum LabelHorizon {
        H1("1h", 1),
        H4("4h", 4),
        H24("24h", 24);

        private final String code;
        private final int hours;

        LabelHorizon(String code, int hours) {
            this.code = code;
            this.hours = hours;
        }
    }

    enum GovernanceClassification {
        STRATEGY_NO_ENTRY_INTENT,
        TERMINAL_GUARD_BLOCK,
        EXECUTION_CAPACITY_BLOCK,
        UNCLASSIFIED_BLOCK,
        ALLOWED_OR_EXECUTED,
        OTHER
    }

    private static final class LabelStats {
        private int totalCandidates;
        private int actionableCandidates;
        private int nonActionableCandidates;
        private int excludedNonActionableCount;
        private int labeledCandidates;
        private int unresolvedCandidates;
        private int missingKlineCount;
        private int missingEntryPriceCount;
        private int missingForwardWindowCount;
        private int pendingForwardWindowCount;
        private int matureForwardWindowCount;
        private int matureLabeledCandidates;
        private int explicitEntryPriceCount;
        private int entryPriceDerivedCount;
        private final Map<String, Integer> correctnessCounts = new LinkedHashMap<>();
        private final Map<String, Integer> attributionCounts = new LinkedHashMap<>();
        private final Map<String, Integer> unresolvedReasonCounts = new LinkedHashMap<>();
    }

    private record ForwardStats(Double return1hPct, Double return4hPct, Double return24hPct,
                                 Double mfe24hPct, Double mae24hPct, String firstTouchOutcome,
                                 boolean priceWindowComplete, boolean missingKline, String reason,
                                 String entrySource) {
        static ForwardStats empty(String reason, boolean missingKline) {
            return new ForwardStats(null, null, null, null, null, "NEITHER", false, missingKline, reason, "MISSING");
        }

        String labelSource() {
            return missingKline ? "MISSING_OKX_KLINE" : "OKX_MD_KLINE";
        }

        Double horizonReturn(LabelHorizon horizon) {
            return switch (horizon) {
                case H1 -> return1hPct;
                case H4 -> return4hPct;
                case H24 -> return24hPct;
            };
        }
    }

    private static final class FilterBucket {
        private final String blocker;
        private int blockCount;
        private int correctBlockCount;
        private int falseBlockCount;
        private int missedAlphaCount;
        private final List<String> examples = new ArrayList<>();

        private FilterBucket(String blocker) {
            this.blocker = blocker;
        }

        private void add(Map<String, Object> row, ForwardStats fwd) {
            blockCount++;
            boolean missed = (fwd.return24hPct() != null && fwd.return24hPct() >= MISSED_ALPHA_THRESHOLD_PCT)
                    || (fwd.mfe24hPct() != null && fwd.mfe24hPct() >= MISSED_ALPHA_THRESHOLD_PCT);
            if (missed) {
                falseBlockCount++;
                missedAlphaCount++;
            } else {
                correctBlockCount++;
            }
            if (examples.size() < 3) examples.add(String.valueOf(row.get("decision_id")));
        }

        private String line() {
            double precision = blockCount == 0 ? 0.0 : correctBlockCount * 100.0 / blockCount;
            return String.format(Locale.US, "%-28s | %8d | %12d | %11d | %11d | %8.1f%% | %s",
                    blocker, blockCount, correctBlockCount, falseBlockCount, missedAlphaCount, precision,
                    String.join(",", examples));
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }
}
