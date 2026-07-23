package com.agora.service.trading;

import com.agora.mcp.DiagnosticMcpTools;
import com.agora.mcp.IndicatorMcpTools;
import com.agora.mcp.MarketDataMcpTools;
import com.agora.mcp.PositionMcpTools;
import com.agora.mcp.SignalCorrectnessMcpTools;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AutonomousTradingSnapshotService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 574L;
    private static final String DEFAULT_SIDE = "LONG";

    private final AutoExplorationRolloutControllerService rolloutControllerService;
    private final TinyLiveExecutionService tinyLiveExecutionService;
    private final PositionMcpTools positionMcpTools;
    private final MarketDataMcpTools marketDataMcpTools;
    private final IndicatorMcpTools indicatorMcpTools;
    private final DiagnosticMcpTools diagnosticMcpTools;
    private final ObjectProvider<SignalCorrectnessMcpTools> signalCorrectnessMcpToolsProvider;
    private final ObjectProvider<ScoreBuyConvictionPreviewService> scoreBuyConvictionPreviewServiceProvider;
    private final ObjectProvider<ScoreBuyFormingDayObserverService> scoreBuyFormingDayObserverServiceProvider;
    private final ObjectProvider<ScoreBuyPrePositionAutoExecutionService> scoreBuyPrePositionAutoExecutionServiceProvider;
    private final ObjectProvider<ScoreBuyPostScoutManagementPolicyService> scoreBuyPostScoutManagementPolicyServiceProvider;
    private final ObjectProvider<ScoreBuyPostScoutAutoAddExecutionService> scoreBuyPostScoutAutoAddExecutionServiceProvider;
    private final ObjectProvider<ScoreBuyConfirmedDeployAutoExecutionService> scoreBuyConfirmedDeployAutoExecutionServiceProvider;
    private final ObjectProvider<CapitalAllocationPolicyPreviewService> capitalAllocationPolicyPreviewServiceProvider;

    @Transactional(readOnly = true)
    public Snapshot capture(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);

        AutoExplorationRolloutControllerService.Status rolloutStatus = safeStatus("rollout", () ->
                rolloutControllerService.evaluate(sym, sid, normalizedSide));
        String rollout = rolloutStatus == null ? "rollout=UNAVAILABLE" : rolloutStatus.render();
        String loop = rolloutStatus == null || rolloutStatus.loop() == null
                ? "loop=UNAVAILABLE"
                : rolloutStatus.loop().render();
        String monitor = rolloutStatus == null || rolloutStatus.loop() == null || rolloutStatus.loop().monitor() == null
                ? "monitor=UNAVAILABLE"
                : rolloutStatus.loop().monitor().render();
        String executions = safe("tinyLiveExecutions", () -> tinyLiveExecutionService.listExecutions(sym, 24 * 60, 20));
        String oco = safe("ocoHealth", positionMcpTools::getOcoHealth);
        String exposure = "Custom Grid exposure runtime removed; use OKX native Grid status.";
        String trades = safe("okxTradeHistory", () -> positionMcpTools.getOkxTradeHistory(20));
        String tradeReconciliation = safe("tradeReconciliation", () ->
                diagnosticMcpTools.reconcileOrphanTrades("BTC", 24, 20.0, 1.0, 10, false));

        SignalCorrectnessMcpTools signal = signalCorrectnessMcpToolsProvider.getObject();
        String outcome = safe("outcomeLabeler", () -> signal.getSignalOutcomeLabelerStatus(sym, 24, "1h", false));
        String governance = safe("governanceDrift", () -> signal.getGovernanceDriftDashboard(sym, 7, "1h"));
        String relaxation = safe("governanceRelaxation", () -> signal.findGovernanceRelaxationCandidates(sym, 7, "1h"));
        String tightening = safe("governanceTightening", () -> signal.findGovernanceTighteningCandidates(sym, 7, "1h"));

        String system = safe("systemHealth", marketDataMcpTools::getSystemHealth);
        String startup = safe("startupLogIssues", () -> diagnosticMcpTools.getCurrentStartupLogIssues(80));
        String freshness = safe("collectionFreshness", () -> indicatorMcpTools.getCollectionFreshness(sym));
        String kline1h = safe("klineQuality1h", () -> marketDataMcpTools.validateKlineQuality(sym, "1h", 5, "okx"));
        String kline4h = safe("klineQuality4h", () -> marketDataMcpTools.validateKlineQuality(sym, "4h", 5, "okx"));
        String divergence = safe("klineDivergence", marketDataMcpTools::runKlineDivergenceScan);
        String eventRisk = safe("eventRisk", () -> diagnosticMcpTools.getEventRiskControlStatus(sym));
        String capitalAllocation = safe("capitalAllocation", () ->
                capitalAllocationPolicyPreviewServiceProvider.getObject().preview(sym));
        String scoreBuy = safe("scoreBuyConviction", () ->
                scoreBuyConvictionPreviewServiceProvider.getObject().preview(sym, 485L));
        String scoreBuyFormingDay = safe("scoreBuyFormingDay", () ->
                scoreBuyFormingDayObserverServiceProvider.getObject().getStatus(sym, 485L));
        String scoreBuyPrePositionAutoExecution = safe("scoreBuyPrePositionAutoExecution", () ->
                scoreBuyPrePositionAutoExecutionServiceProvider.getObject().status(sym, 485L));
        String scoreBuyPostScout = safe("scoreBuyPostScout", () ->
                scoreBuyPostScoutManagementPolicyServiceProvider.getObject().getStatus(sym, 485L));
        String scoreBuyPostScoutAutoAdd = safe("scoreBuyPostScoutAutoAdd", () ->
                scoreBuyPostScoutAutoAddExecutionServiceProvider.getObject().status(sym, 485L));
        String scoreBuyConfirmedDeployAutoExecution = safe("scoreBuyConfirmedDeployAutoExecution", () ->
                scoreBuyConfirmedDeployAutoExecutionServiceProvider.getObject().status(sym, 485L));

        return new Snapshot(
                sym,
                sid,
                normalizedSide,
                rollout,
                loop,
                monitor,
                executions,
                oco,
                exposure,
                trades,
                tradeReconciliation,
                tradeAttribution(executions, trades, tradeReconciliation),
                eventRisk,
                outcome,
                governance,
                relaxation,
                tightening,
                system,
                startup,
                freshness,
                kline1h,
                kline4h,
                divergence,
                capitalAllocation,
                scoreBuy,
                scoreBuyFormingDay,
                scoreBuyPrePositionAutoExecution,
                scoreBuyPostScout,
                scoreBuyPostScoutAutoAdd,
                scoreBuyConfirmedDeployAutoExecution);
    }

    private TradeAttribution tradeAttribution(String executions, String trades, String reconciliation) {
        int orphanOkxBuy = metric(reconciliation, "Orphan OKX BUY");
        int orphanOkxSell = metric(reconciliation, "Orphan OKX SELL");
        int orphanDb = metric(reconciliation, "Orphan DB");
        int matchedOkx = metric(reconciliation, "Matched OKX trades");
        int matchedGrid = countOccurrences(reconciliation, "✅ Grid level");
        int matchedLiveSignal = countOccurrences(reconciliation, "✅ LiveSignal");
        int matchedTinyLive = countExecutionOrderIdsInTrades(executions, trades);
        int orphanOkx = orphanOkxBuy + orphanOkxSell;
        return new TradeAttribution(
                orphanOkx > 0,
                orphanOkx,
                orphanDb,
                matchedOkx,
                matchedGrid,
                matchedLiveSignal,
                matchedTinyLive);
    }

    private int metric(String text, String label) {
        if (text == null) return 0;
        Pattern pattern = Pattern.compile(Pattern.quote(label) + "[^:\\n]*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? parseInt(matcher.group(1)) : 0;
    }

    private int countExecutionOrderIdsInTrades(String executions, String trades) {
        if (executions == null || trades == null) return 0;
        Pattern pattern = Pattern.compile("\\borderId=(\\d+)\\b");
        Matcher matcher = pattern.matcher(executions);
        int count = 0;
        while (matcher.find()) {
            if (trades.contains("ordId=" + matcher.group(1))) {
                count++;
            }
        }
        return count;
    }

    private int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String safe(String section, ThrowingSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return section + "=UNAVAILABLE error=" + truncate(e.getMessage(), 240);
        }
    }

    private AutoExplorationRolloutControllerService.Status safeStatus(String section, ThrowingStatusSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase();
    }

    private String normalizeSide(String side) {
        String s = side == null || side.isBlank() ? DEFAULT_SIDE : side.trim().toUpperCase();
        return "BUY".equals(s) ? "LONG" : s;
    }

    private String truncate(String value, int max) {
        if (value == null) return "N/A";
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingStatusSupplier {
        AutoExplorationRolloutControllerService.Status get() throws Exception;
    }

    public record Snapshot(String symbol,
                           long strategyId,
                           String side,
                           String rolloutRaw,
                           String loopRaw,
                           String monitorRaw,
                           String executionsRaw,
                           String ocoRaw,
                           String exposureRaw,
                           String tradesRaw,
                           String tradeReconciliationRaw,
                           TradeAttribution tradeAttribution,
                           String eventRiskRaw,
                           String outcomeRaw,
                           String governanceRaw,
                           String relaxationRaw,
                           String tighteningRaw,
                           String systemRaw,
                           String startupRaw,
                           String freshnessRaw,
                           String kline1hRaw,
                           String kline4hRaw,
                           String divergenceRaw,
                           String capitalAllocationRaw,
                           String scoreBuyRaw,
                           String scoreBuyFormingDayRaw,
                           String scoreBuyPrePositionAutoExecutionRaw,
                           String scoreBuyPostScoutRaw,
                           String scoreBuyPostScoutAutoAddRaw,
                           String scoreBuyConfirmedDeployAutoExecutionRaw) {
    }

    public record TradeAttribution(boolean unexpectedOrderDetected,
                                   int orphanOkxTradeCount,
                                   int orphanDbRecordCount,
                                   int matchedOkxTrades,
                                   int matchedGridTrades,
                                   int matchedLiveSignalTrades,
                                   int matchedTinyLiveAuditTrades) {
        public static TradeAttribution empty() {
            return new TradeAttribution(false, 0, 0, 0, 0, 0, 0);
        }
    }
}
