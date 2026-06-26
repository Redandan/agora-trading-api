package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.CapitalAllocationPolicyPreviewService;
import com.agora.service.trading.PanicBottomContextPreviewService;
import com.agora.service.trading.ScoreBuyConfirmedDeployAutoExecutionService;
import com.agora.service.trading.ScoreBuyConfirmedDeployPreviewService;
import com.agora.service.trading.ScoreBuyConvictionPreviewService;
import com.agora.service.trading.ScoreBuyFormingDayObserverService;
import com.agora.service.trading.ScoreBuyMlGateDiagnosticService;
import com.agora.service.trading.ScoreBuyPrePositionAutoExecutionService;
import com.agora.service.trading.ScoreBuyPrePositionApprovalPreviewService;
import com.agora.service.trading.ScoreBuyPrePositionExecutionPolicyPreviewService;
import com.agora.service.trading.ScoreBuyPrePositionPreviewService;
import com.agora.service.trading.ScoreBuyPostScoutAutoAddExecutionService;
import com.agora.service.trading.ScoreBuyPostScoutManagementPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScoreBuyMcpTools {

    private final ScoreBuyConvictionPreviewService previewService;
    private final ScoreBuyFormingDayObserverService formingDayObserverService;
    private final ScoreBuyPrePositionPreviewService prePositionPreviewService;
    private final ScoreBuyPrePositionApprovalPreviewService prePositionApprovalPreviewService;
    private final ScoreBuyPrePositionExecutionPolicyPreviewService prePositionExecutionPolicyPreviewService;
    private final ScoreBuyPrePositionAutoExecutionService prePositionAutoExecutionService;
    private final CapitalAllocationPolicyPreviewService capitalAllocationPolicyPreviewService;
    private final ScoreBuyConfirmedDeployPreviewService confirmedDeployPreviewService;
    private final ScoreBuyConfirmedDeployAutoExecutionService confirmedDeployAutoExecutionService;
    private final ScoreBuyPostScoutManagementPolicyService postScoutManagementPolicyService;
    private final ScoreBuyPostScoutAutoAddExecutionService postScoutAutoAddExecutionService;
    private final PanicBottomContextPreviewService panicBottomContextPreviewService;
    private final PositionMcpTools positionMcpTools;
    private final ScoreBuyMlGateDiagnosticService scoreBuyMlGateDiagnosticService;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only SCORE_BUY conviction preview. Explains #485 SCORE_BUY_V2 daily gate, 1h/15m intraday proxy state, capital-aware bounded sizing preview, and why no buy is currently triggered. params: symbol default BTCUSDT, strategyId default 485.")
    public String previewScoreBuyConviction(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId) {
        return previewService.preview(symbol, strategyId)
                + "\n\npanicBottomContext="
                + panicBottomContextPreviewService.preview(symbol, safeOcoHealth());
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only BTC panic-bottom context preview. Uses md_kline, market_indicator_history fear_greed, 200WMA reference, OCO health text, and 1h/4h trend guards to label WATCH/SCOUT_PRE_POSITION/CONFIRMED_DEPLOY_REVIEW without placing orders or changing OCO/grid/strategy/fund/Earn state. params: symbol default BTCUSDT.")
    public String previewPanicBottomContext(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol) {
        return panicBottomContextPreviewService.preview(symbol, safeOcoHealth());
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MODEL_OPS})
    @Tool(description = "Read-only SCORE_BUY ML gate diagnostic for issue #16. Builds the ScoreBuyV2 feature vector, checks the PROMOTED model, runs a no-write HeatWave preview, and reports p_win/schema mismatch/missing requirements without placing orders or changing strategy/OCO/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 485, intervalCode default 1d.")
    public String diagnoseScoreBuyMlGate(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId,
            @ToolParam(required = false, description = "Kline interval, default 1d") String intervalCode) {
        return scoreBuyMlGateDiagnosticService.diagnose(symbol, strategyId, intervalCode);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only SCORE_BUY forming-day observer. Evaluates 15m/current-day pre-trigger state for #485 without placing orders or changing OCO/strategy/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 485.")
    public String getScoreBuyFormingDayStatus(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId) {
        return formingDayObserverService.getStatus(symbol, strategyId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only SCORE_BUY pre-position preflight. Aggregates forming-day observer, conviction, capital, runtime evidence, and price-shape OCO preview without placing orders or changing OCO/strategy/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 485.")
    public String previewScoreBuyPrePosition(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId) {
        return prePositionPreviewService.preview(symbol, strategyId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only SCORE_BUY pre-position approval preview. Converts pre-position preflight into bounded approval eligibility and staged-add budget status without creating tokens, placing orders, or changing OCO/strategy/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 485.")
    public String previewScoreBuyPrePositionApproval(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId) {
        return prePositionApprovalPreviewService.preview(symbol, strategyId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only SCORE_BUY pre-position execution policy preview. Explains whether coarse EntryDedup would block while staged add-budget would allow a bounded pre-position. It never creates tokens, places orders, or changes OCO/strategy/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 485.")
    public String previewScoreBuyPrePositionExecution(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId) {
        return prePositionExecutionPolicyPreviewService.preview(symbol, strategyId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only status for bounded SCORE_BUY early-recovery pre-position auto execution. Shows whether the scheduler would execute without placing orders or changing OCO/strategy/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 485.")
    public String getScoreBuyPrePositionAutoExecutionStatus(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId) {
        return prePositionAutoExecutionService.status(symbol, strategyId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only SCORE_BUY post-scout management policy. Explains whether an open #485 scout should be held, watched for pullback, considered for bounded add-on, or treated as invalidated/protective. It never places orders, modifies OCO, writes Runtime Evidence, sends Telegram, or changes strategy/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 485.")
    public String getScoreBuyPostScoutManagementStatus(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId) {
        return postScoutManagementPolicyService.getStatus(symbol, strategyId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only status for bounded SCORE_BUY post-scout auto add execution. Shows whether the scheduler would execute a capped OCO-protected add-on through the internal write path. It does not place orders or change OCO/strategy/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 485.")
    public String getScoreBuyPostScoutAutoAddStatus(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId) {
        return postScoutAutoAddExecutionService.status(symbol, strategyId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only SCORE_BUY confirmed daily deploy policy preview. Separates scout/pre-position from larger daily-confirmed staged deployment, with reserve-aware sizing, event-risk scaling, and required write-path safety checks. params: symbol default BTCUSDT, strategyId default 485.")
    public String previewScoreBuyConfirmedDeploy(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId) {
        return confirmedDeployPreviewService.preview(symbol, strategyId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "Read-only status for bounded SCORE_BUY confirmed daily deploy auto execution. Shows whether the scheduler would execute the first capped confirmed tranche after daily SCORE_BUY confirmation. It does not place orders or change OCO/strategy/grid/fund/Earn state. params: symbol default BTCUSDT, strategyId default 485.")
    public String getScoreBuyConfirmedDeployAutoExecutionStatus(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "SCORE_BUY strategy id, default 485") Long strategyId) {
        return confirmedDeployAutoExecutionService.status(symbol, strategyId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Read-only capital allocation policy preview. Separates free USDT, Earn flexible capital, grid/auto exposure, SCORE_BUY reserve, panic-buy reserve, and capital-segmentation missed-opportunity risk. Does not redeem Earn, move funds, place orders, or modify OCO/strategy/grid state. params: symbol default BTCUSDT.")
    public String previewCapitalAllocationPolicy(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol) {
        return capitalAllocationPolicyPreviewService.preview(symbol);
    }

    private String safeOcoHealth() {
        try {
            return positionMcpTools.getOcoHealth();
        } catch (Exception e) {
            return "OCO_HEALTH_READ_FAILED: " + e.getMessage();
        }
    }
}
