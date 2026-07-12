package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.service.trading.AutoApprovalPolicyService;
import com.agora.service.trading.AutoExplorationRolloutControllerService;
import com.agora.service.trading.AutonomousExplorationLoopService;
import com.agora.service.trading.AutonomousExplorationMonitorService;
import com.agora.service.trading.AutopilotPolicyService;
import com.agora.service.trading.DailyAutonomousTradingDigestService;
import com.agora.service.trading.ExplorationRolloutService;
import com.agora.service.trading.ExplorationPolicyService;
import com.agora.service.trading.MissedOpportunityRegressionValidationService;
import com.agora.service.trading.ProbePositionExecutorDryRunService;
import com.agora.service.trading.RuntimeDecisionEvidenceService;
import com.agora.service.trading.Strategy508HoldCounterfactualService;
import com.agora.service.trading.TinyLiveExecutionService;
import com.agora.service.trading.TinyLiveMinimumOrderPreviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RuntimeEvidenceMcpTools {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final RuntimeDecisionEvidenceService evidenceService;
    private final TinyLiveMinimumOrderPreviewService tinyLiveMinimumOrderPreviewService;
    private final AutopilotPolicyService autopilotPolicyService;
    private final ProbePositionExecutorDryRunService probePositionExecutorDryRunService;
    private final TinyLiveExecutionService tinyLiveExecutionService;
    private final AutoApprovalPolicyService autoApprovalPolicyService;
    private final ExplorationPolicyService explorationPolicyService;
    private final AutonomousExplorationMonitorService autonomousExplorationMonitorService;
    private final AutonomousExplorationLoopService autonomousExplorationLoopService;
    private final ExplorationRolloutService explorationRolloutService;
    private final AutoExplorationRolloutControllerService autoExplorationRolloutControllerService;
    private final DailyAutonomousTradingDigestService dailyAutonomousTradingDigestService;
    private final MissedOpportunityRegressionValidationService missedOpportunityRegressionValidationService;
    private final Strategy508HoldCounterfactualService strategy508HoldCounterfactualService;
    private final ObjectMapper objectMapper;

    @Tool(description = "List recent Runtime Evidence Store rows for controlled autonomous-trading validation. " +
            "Read-only. No trading, OCO, strategy, grid, or fund behavior is changed. " +
            "params: symbol optional, minutes default 1440, limit default 50.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String listRuntimeDecisionEvidence(String symbol, Integer minutes, Integer limit) {
        List<RuntimeDecisionEvidence> rows = evidenceService.listRecent(symbol, minutes, limit);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Runtime Decision Evidence ===\n")
                .append("enabled=").append(evidenceService.isEnabled()).append("\n")
                .append("boundary: READ_ONLY report; no trading/OCO/strategy/grid/fund behavior changed.\n")
                .append("filters: symbol=").append(symbol == null || symbol.isBlank() ? "ALL" : symbol.trim().toUpperCase())
                .append(" minutes=").append(minutes == null ? 1440 : minutes)
                .append(" limit=").append(limit == null ? 50 : limit)
                .append("\n\n");
        if (rows.isEmpty()) {
            sb.append("No runtime evidence rows found.");
            return sb.toString();
        }
        int i = 1;
        for (RuntimeDecisionEvidence row : rows) {
            sb.append(i++).append(". #").append(row.getId())
                    .append(" decisionId=").append(row.getDecisionId())
                    .append(" ").append(formatTime(row))
                    .append(" ").append(nullToNA(row.getSymbol()))
                    .append(" side=").append(nullToNA(row.getSide()))
                    .append(" strategy=").append(row.getStrategyId() == null ? "N/A" : row.getStrategyId())
                    .append(" action=").append(row.getSelectedAction())
                    .append(" decision=").append(nullToNA(row.getDecision()))
                    .append(" score=").append(row.getScore() == null ? "N/A" : row.getScore())
                    .append(" threshold=").append(row.getThreshold() == null ? "N/A" : row.getThreshold())
                    .append(" policy=").append(nullToNA(row.getPolicyMode()))
                    .append(" policyReason=").append(nullToNA(row.getPolicyReason()))
                    .append(" fgMode=").append(nullToNA(row.getFearGreedMode()))
                    .append(" freshness=").append(nullToNA(row.getFreshnessState()))
                    .append(" outcome=").append(nullToNA(row.getFinalOutcome()))
                    .append(" exec=").append(nullToNA(row.getExecutionMode()))
                    .append(" orderSent=").append(row.getOrderSent() == null ? "N/A" : row.getOrderSent())
                    .append(" suppression=").append(nullToNA(row.getSuppressionReason()))
                    .append("\n   blocker=").append(nullToNA(row.getBlockerReason()))
                    .append("\n   terminalBlocker=").append(nullToNA(row.getTerminalBlocker()))
                    .append("\n   reason=").append(nullToNA(row.getReason()))
                    .append(capDeferredSummary(row))
                    .append("\n   ev=").append(nullToNA(row.getEvResultJson()))
                    .append("\n   tqs=").append(nullToNA(row.getTqsResultJson()))
                    .append("\n   policyInputs=").append(nullToNA(row.getPolicyInputsJson()))
                    .append("\n   risk=").append(nullToNA(row.getRiskGateResultJson()))
                    .append("\n   executionPreview=").append(nullToNA(row.getExecutionPreviewJson()))
                    .append("\n   warnings=").append(nullToNA(row.getWarningsJson()))
                    .append("\n");
        }
        return sb.toString();
    }

    private String capDeferredSummary(RuntimeDecisionEvidence row) {
        String json = row.getFeaturesSnapshotJson();
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.path("dailyCapDeferredOpportunity").asBoolean(false)) {
                return "";
            }
            return "\n   capDeferred=dailyCapDeferredOpportunity=true"
                    + " eligibleAfterDailyCapResetPreview=" + node.path("eligibleAfterDailyCapResetPreview").asBoolean(false)
                    + " wouldExecuteAfterDailyCapReset=" + node.path("wouldExecuteAfterDailyCapReset").asBoolean(false)
                    + " nextDailyCapResetAtUtc=" + text(node, "nextDailyCapResetAtUtc")
                    + " nextDailyCapResetAtAsiaTaipei=" + text(node, "nextDailyCapResetAtAsiaTaipei")
                    + " minutesRemaining=" + text(node, "dailyCapResetMinutesRemaining")
                    + " action=" + text(node, "dailyCapResetAction")
                    + " countSinceUtc=" + text(node, "dailyCapCountSinceUtc")
                    + " countSinceAsiaTaipei=" + text(node, "dailyCapCountSinceAsiaTaipei")
                    + " ordersToday=" + text(node, "ordersToday")
                    + " maxOrdersPerDay=" + text(node, "maxOrdersPerDay")
                    + " suggestedAddNotionalUsdt=" + text(node, "suggestedAddNotionalUsdt")
                    + " maxLossIfWrongUsdt=" + text(node, "maxLossIfWrongUsdt")
                    + " entry=" + text(node, "entryPrice")
                    + " tp=" + text(node, "tpPrice")
                    + " sl=" + text(node, "slPrice");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return "N/A";
        }
        return value.asText("N/A");
    }

    @Tool(description = "Read-only autonomous trading readiness dashboard. Summarizes Runtime Evidence, EV/TQS samples, " +
            "FearGreed WARN_ONLY visibility, shadow execution intent, order suppression, OCO plan evidence, exposure/freshness " +
            "signals, unexpected order evidence, and final readiness verdict. Does not place orders or change OCO/strategy/grid/funds. " +
            "params: symbol optional, minutes default 1440, strategyId optional, side optional LONG/SHORT.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String getAutonomousReadinessDashboard(String symbol, Integer minutes, Long strategyId, String side) {
        return evidenceService.autonomousReadinessDashboard(symbol, minutes, strategyId, side);
    }

    @Tool(description = "Read-only tiny-live minimum-order manual approval preflight preview for controlled autonomous trading. " +
            "Does not place orders, attach/modify OCO, change strategy/grid/fund/Earn state, or enable autonomous execution. " +
            "params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String previewTinyLiveMinimumOrder(String symbol, Long strategyId, String side) {
        return tinyLiveMinimumOrderPreviewService.previewTinyLiveMinimumOrder(symbol, strategyId, side);
    }

    @Tool(description = "Read-only tiny-live execution readiness. Shows daily cap, open tiny-live positions, last preview hash/token id, blockers, and warnings. " +
            "Does not place orders, attach/modify OCO, change strategy/grid/fund/Earn state, or enable autonomous execution. " +
            "params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String listTinyLiveExecutionReadiness(String symbol, Long strategyId, String side) {
        return tinyLiveExecutionService.listReadiness(symbol, strategyId, side);
    }

    @Tool(description = "Read-only tiny-live execution/audit history. No order/OCO/strategy/grid/fund/Earn behavior changed. " +
            "params: symbol optional, minutes default 1440, limit default 50.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String listTinyLiveExecutions(String symbol, Integer minutes, Integer limit) {
        return tinyLiveExecutionService.listExecutions(symbol, minutes, limit);
    }

    @Tool(description = "Read-only tiny-live event-risk override token audit history. Returns token ids/status only, never token values. " +
            "No order/OCO/strategy/grid/fund/Earn behavior changed. params: symbol optional, minutes default 1440, limit default 50.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String listTinyLiveEventRiskOverrideTokens(String symbol, Integer minutes, Integer limit) {
        return tinyLiveExecutionService.listEventRiskOverrideTokens(symbol, minutes, limit);
    }

    @Tool(description = "Read-only controlled tiny-live auto-execution trigger status. Shows whether the scheduler is installed, enabled, dry-run, " +
            "and the current preview summary. Does not place orders, attach/modify OCO, change strategy/grid/fund/Earn state, or enable execution. " +
            "params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String getTinyLiveAutoExecutionTriggerStatus(String symbol, Long strategyId, String side) {
        return tinyLiveExecutionService.autoExecutionTriggerStatus(symbol, strategyId, side);
    }

    @Tool(description = "Read-only AutoApprovalPolicy v0 preview for BTCUSDT strategy 574 LONG $5 tiny-live probes. " +
            "Returns BLOCKED, HUMAN_APPROVAL_REQUIRED, or AUTO_APPROVED_TINY_LIVE plus blockers/warnings and an autoApprovalToken when allowed. " +
            "Does not place orders, write Runtime Evidence, attach/modify OCO, change strategy/grid/fund/Earn state, or enable full autonomous execution. " +
            "params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String previewTinyLiveAutoApproval(String symbol, Long strategyId, String side) {
        return autoApprovalPolicyService.previewTinyLiveAutoApproval(symbol, strategyId, side);
    }

    @Tool(description = "Read-only preview for the controlled tiny-live autonomous execution path. " +
            "Evaluates whether current preview + AutoApprovalPolicy would be eligible to execute, without consuming tokens, " +
            "placing orders, writing Runtime Evidence, sending Telegram, attaching/modifying OCO, changing strategy/grid/fund/Earn state, " +
            "or enabling full autonomous execution. params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY, eventRiskOverrideToken optional.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String previewTinyLiveAutoExecution(String symbol, Long strategyId, String side, String eventRiskOverrideToken) {
        return tinyLiveExecutionService.previewAutoExecution(symbol, strategyId, side, eventRiskOverrideToken);
    }

    @Tool(description = "Read-only Controlled Exploration v1 readiness for BTCUSDT strategy 574 LONG tiny-live learning probes. " +
            "Evaluates exploration budget, open position guard, EV/TQS/runtime evidence/OCO gates, event risk, and learning value. " +
            "Does not place orders, write Runtime Evidence, send Telegram, attach/modify OCO, change strategy/grid/fund/Earn state, " +
            "or enable autonomous execution. params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String getExplorationReadiness(String symbol, Long strategyId, String side) {
        return explorationPolicyService.getExplorationReadiness(symbol, strategyId, side);
    }

    @Tool(description = "Read-only Controlled Exploration v1 candidate preview. Shows whether the current tiny-live candidate would be used " +
            "for bounded learning, including learning value, risk cost, label goal, and required audit fields. No order/OCO/strategy/grid/fund/Earn behavior changed. " +
            "params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String previewExplorationCandidate(String symbol, Long strategyId, String side) {
        return explorationPolicyService.previewExplorationCandidate(symbol, strategyId, side);
    }

    @Tool(description = "Read-only Autonomous Exploration Monitor v0 status. Aggregates exploration readiness, tiny-live execution status, " +
            "OCO health, outcome labeler coverage, and governance drift into a single monitor status. Does not place orders, write Runtime Evidence, " +
            "send Telegram, attach/modify OCO, change strategy/grid/fund/Earn state, or relax any safety gate. " +
            "params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String getAutonomousExplorationMonitorStatus(String symbol, Long strategyId, String side) {
        return autonomousExplorationMonitorService.getAutonomousExplorationMonitorStatus(symbol, strategyId, side);
    }

    @Tool(description = "Read-only Unattended Autonomous Exploration Loop v0 status. Shows loop state, previous transition, " +
            "daily cap, exploration budget, readiness, governance drift, and whether the loop would execute now. " +
            "This status tool never runs the loop tick, never places orders, never writes Runtime Evidence, never sends Telegram, " +
            "and never changes OCO/strategy/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String getAutonomousExplorationLoopStatus(String symbol, Long strategyId, String side) {
        return autonomousExplorationLoopService.getAutonomousExplorationLoopStatus(symbol, strategyId, side);
    }

    @Tool(description = "Read-only Aggressive-but-Bounded Exploration Rollout v1 status. Shows dry-run loop enablement, " +
            "production promotion gates, consecutive READY ticks, tiny-live sample/OCO metrics, daily-cap recommendation, " +
            "and blockers/warnings. This tool never enables production, never places orders, never writes Runtime Evidence, " +
            "never sends Telegram, and never changes OCO/strategy/grid/fund/Earn state. " +
            "params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String getExplorationRolloutStatus(String symbol, Long strategyId, String side) {
        return explorationRolloutService.getExplorationRolloutStatus(symbol, strategyId, side);
    }

    @Tool(description = "Read-only Auto Exploration Rollout Controller v0 status. Shows current rollout stage, previous stage, " +
            "promotion/cap-increase gates, effective loop/production/cap settings, blockers, warnings, and recommendation. " +
            "This status tool never advances rollout, never places orders, never writes Runtime Evidence, never sends Telegram, " +
            "and never changes OCO/strategy/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String getAutoExplorationRolloutStatus(String symbol, Long strategyId, String side) {
        return autoExplorationRolloutControllerService.getAutoExplorationRolloutStatus(symbol, strategyId, side);
    }

    @Tool(description = "Read-only Daily Autonomous Trading Digest v0. Aggregates rollout/loop/monitor status, tiny-live execution audit, " +
            "OCO health, exposure, recent OKX trades, outcome labels, governance drift, system health, startup issues, freshness, and kline quality. " +
            "This MCP tool never runs the exploration loop, never promotes rollout, never places orders, never writes Runtime Evidence, never sends Telegram, " +
            "and never changes OCO/strategy/grid/fund/Earn state. Defaults to the latest scheduler/refreshed snapshot; pass refresh=true for a live recompute. " +
            "params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY, refresh optional default false.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    public String getDailyAutonomousTradingDigest(String symbol, Long strategyId, String side, Boolean refresh) {
        return dailyAutonomousTradingDigestService.getDailyAutonomousTradingDigest(symbol, strategyId, side, refresh);
    }

    @Tool(description = "Read-only missed-opportunity regression report. Classifies no-buy reasons for #574 tiny-live and #485 SCORE_BUY paths " +
            "as expected waits, hard safety blocks, cap-scope leaks, stale-data suspects, capital misreads, coarse EntryDedup suspects, or missed-opportunity risk. " +
            "This tool never places orders, never modifies OCO, never changes strategy/grid/fund/Earn state, and never writes Runtime Evidence. " +
            "params: symbol default BTCUSDT, hours default 24.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    public String getMissedOpportunityRegressionReport(String symbol, Integer hours) {
        return missedOpportunityRegressionValidationService.getMissedOpportunityRegressionReport(symbol, hours);
    }

    @Tool(description = "Read-only current autonomous opportunity readiness validator. Explains whether #574 tiny-live or #485 SCORE_BUY no-buy state is expected " +
            "or suspicious. Does not place orders, modify OCO, change strategy/grid/fund/Earn state, send Telegram, or write Runtime Evidence. " +
            "params: symbol default BTCUSDT, strategyId default 574, side default LONG/BUY.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    public String validateAutonomousOpportunityReadiness(String symbol, Long strategyId, String side) {
        return missedOpportunityRegressionValidationService.validateAutonomousOpportunityReadiness(symbol, strategyId, side);
    }

    @Tool(description = "Read-only no-buy reason truth table for recent autonomous trading paths. Returns #574 tiny-live and #485 SCORE_BUY current no-buy classifications " +
            "with blockers, warnings, and scope evidence. No order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior changed. " +
            "params: symbol default BTCUSDT, hours default 24, limit default 20.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    public String getNoBuyReasonTruthTable(String symbol, Integer hours, Integer limit) {
        return missedOpportunityRegressionValidationService.getNoBuyReasonTruthTable(symbol, hours, limit);
    }

    @Tool(description = "Read-only strategy 508 HOLD/EVALUATED_ONLY counterfactual shadow report. " +
            "Deduplicates by strategy/symbol/side/interval/bar, requires proven all-gates-passed BUY evidence, " +
            "excludes the whole event when any hard-safety blocker or order evidence exists, and simulates one fixed 10 USDT order " +
            "with +6% TP, -12% disaster SL, and both entry/exit fees on OKX 1m bars. " +
            "Fewer than 30 unique finalized events is always INSUFFICIENT_DATA and never authorizes live relaxation. " +
            "No order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior is changed. " +
            "params: symbol default BTCUSDT, hours default 720 max 2160, detailLimit default 50 max 200.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    public String analyzeStrategy508HoldCounterfactual(String symbol, Integer hours, Integer detailLimit) {
        return strategy508HoldCounterfactualService.analyze(symbol, hours, detailLimit);
    }

    @Tool(description = "LOCAL_ONLY control: create a one-time event-risk-only override token for controlled BTCUSDT strategy 574 LONG $5 tiny-live. " +
            "This may authorize bypassing only R3 event risk in later preview/execution checks; it does not place orders, modify OCO, " +
            "change strategy/grid/fund/Earn state, or bypass DuplicateBar/EV/OCO/Runtime Evidence/daily cap/open-position hard gates. " +
            "params: symbol, strategyId, side, notionalUsdt, previewHash, reason.")
    @McpAuth(McpAuthLevel.LOCAL_ONLY)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    public String createTinyLiveEventRiskOverrideToken(String symbol,
                                                       Long strategyId,
                                                       String side,
                                                       BigDecimal notionalUsdt,
                                                       String previewHash,
                                                       String reason) {
        return tinyLiveExecutionService.createEventRiskOverrideToken(symbol, strategyId, side, notionalUsdt, previewHash, reason);
    }

    @Tool(description = "Protected write: execute exactly one BTCUSDT $5 tiny-live order only after server-side preview recheck and explicit human approval token. " +
            "Also accepts a valid AutoApprovalPolicy autoApprovalToken. Requires LOCAL_ONLY access. Does not enable full autonomous execution, " +
            "does not modify existing OCO, does not change strategy/grid/fund/Earn state. " +
            "params: previewToken, approvalToken or autoApprovalToken, symbol, strategyId, side, expectedPreviewHash, humanReason, eventRiskOverrideToken optional.")
    @McpAuth(McpAuthLevel.LOCAL_ONLY)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    public String executeTinyLiveWithApproval(String previewToken,
                                              String approvalToken,
                                              String symbol,
                                              Long strategyId,
                                              String side,
                                              String expectedPreviewHash,
                                              String humanReason,
                                              String eventRiskOverrideToken) {
        return tinyLiveExecutionService.executeWithApproval(
                previewToken, approvalToken, symbol, strategyId, side, expectedPreviewHash, humanReason, eventRiskOverrideToken);
    }

    @Tool(name = "previewAutopilotPolicy",
            description = "Read-only AutopilotPolicyService v0 mapping preview. " +
            "Does not place orders, write Runtime Evidence, send Telegram, modify OCO, change strategy/grid/fund/Earn state, " +
            "or enable autonomous execution. params: symbol, strategyId, side, tqsBand, qualityScore, recommendedAction, " +
            "evPass, expectedR, ocoCapable, ocoHealthy, exposureCapHit, dailyBreaker, duplicateBar, dataFreshnessHardFail, " +
            "eventRiskBlocked, strategyNotifyOnly, strategyAllowlisted, autonomousExecutionEnabled, probePositionExecutorEnabled.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String previewAutopilotPolicy(String symbol,
                                         Long strategyId,
                                         String side,
                                         String tqsBand,
                                         Integer qualityScore,
                                         String recommendedAction,
                                         Boolean evPass,
                                         Double expectedR,
                                         Boolean ocoCapable,
                                         Boolean ocoHealthy,
                                         Boolean exposureCapHit,
                                         Boolean dailyBreaker,
                                         Boolean duplicateBar,
                                         Boolean dataFreshnessHardFail,
                                         Boolean eventRiskBlocked,
                                         Boolean strategyNotifyOnly,
                                         Boolean strategyAllowlisted,
                                         Boolean autonomousExecutionEnabled,
                                         Boolean probePositionExecutorEnabled) {
        AutopilotPolicyService.Decision decision = autopilotPolicyService.decidePreview(
                new AutopilotPolicyService.PreviewInput(
                        symbol,
                        strategyId,
                        side,
                        tqsBand,
                        qualityScore,
                        recommendedAction,
                        evPass,
                        expectedR,
                        ocoCapable,
                        ocoHealthy,
                        exposureCapHit,
                        dailyBreaker,
                        duplicateBar,
                        dataFreshnessHardFail,
                        eventRiskBlocked,
                        strategyNotifyOnly,
                        strategyAllowlisted,
                        autonomousExecutionEnabled,
                        probePositionExecutorEnabled));
        return "=== Autopilot Policy Preview ===\n"
                + "boundary: READ_ONLY; no trading/OCO/strategy/grid/fund behavior changed.\n"
                + "writesRuntimeEvidence=false\n"
                + "orderSent=false\n"
                + "telegramSent=false\n"
                + "policyMode=" + decision.policyMode() + "\n"
                + "policyReason=" + decision.policyReason() + "\n"
                + "policyInputs=" + decision.policyInputsJson();
    }

    @Tool(name = "previewProbeExecutionPlan",
            description = "Read-only ProbePositionExecutor dry-run planner preview. Does not place orders, write Runtime Evidence, " +
                    "send Telegram, modify OCO, change strategy/grid/fund/Earn state, create live execution events, or enable autonomous execution. " +
                    "params: symbol, strategyId, side, policyMode, qualityScore, tqsBand, expectedR, exposureCapHit, ocoHealthy, " +
                    "entryPrice, tpPrice, slPrice, availableUsdt, optional duplicateBar, dataFreshnessHardFail, ocoCapable, dailyBreaker.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String previewProbeExecutionPlan(String symbol,
                                            Long strategyId,
                                            String side,
                                            String policyMode,
                                            Integer qualityScore,
                                            String tqsBand,
                                            Double expectedR,
                                            Boolean exposureCapHit,
                                            Boolean ocoHealthy,
                                            BigDecimal entryPrice,
                                            BigDecimal tpPrice,
                                            BigDecimal slPrice,
                                            BigDecimal availableUsdt,
                                            Boolean duplicateBar,
                                            Boolean dataFreshnessHardFail,
                                            Boolean ocoCapable,
                                            Boolean dailyBreaker) {
        ProbePositionExecutorDryRunService.Plan plan = probePositionExecutorDryRunService.preview(
                new ProbePositionExecutorDryRunService.PreviewInput(
                        symbol,
                        strategyId,
                        side,
                        policyMode,
                        qualityScore,
                        tqsBand,
                        expectedR,
                        exposureCapHit,
                        ocoCapable == null ? ocoHealthy : ocoCapable,
                        ocoHealthy,
                        dailyBreaker,
                        duplicateBar,
                        dataFreshnessHardFail,
                        entryPrice,
                        tpPrice,
                        slPrice,
                        availableUsdt));
        return "=== Probe Position Executor Dry-Run Preview ===\n"
                + "boundary: READ_ONLY; no trading/OCO/strategy/grid/fund behavior changed.\n"
                + "writesRuntimeEvidence=false\n"
                + "orderSent=false\n"
                + "telegramSent=false\n"
                + "executionMode=" + plan.executionMode() + "\n"
                + "probeNotionalUsdt=" + plan.probeNotionalUsdt() + "\n"
                + "maxLossUsdt=" + plan.maxLossUsdt() + "\n"
                + "riskReward=" + plan.riskReward() + "\n"
                + "capitalCapCheck=" + plan.capitalCapCheck() + "\n"
                + "executionSuppressionReason=" + plan.executionSuppressionReason() + "\n"
                + "executionPreview=" + plan.executionPreviewJson();
    }

    private String formatTime(RuntimeDecisionEvidence row) {
        if (row.getEvidenceTime() == null) {
            return "time=N/A";
        }
        return row.getEvidenceTime().atZone(ZoneId.of("UTC")).withZoneSameInstant(TAIPEI).format(FMT) + " Taipei";
    }

    private String nullToNA(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }
}
