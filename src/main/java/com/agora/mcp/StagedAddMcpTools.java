package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.StagedAddPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class StagedAddMcpTools {

    private final StagedAddPolicyService stagedAddPolicyService;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Read-only generic staged-add readiness. Explains whether an EntryDedup-blocked LONG candidate is an exact duplicate, " +
            "budget-exhausted, hard-safety blocked, or a distinct staged-add candidate. It never places orders, modifies OCO, changes strategy/grid/fund/Earn state, " +
            "sends Telegram, or writes Runtime Evidence. params: symbol default BTCUSDT, strategyId required when known, side default LONG, intervalCode default 1h, optional expectedR/tqsBand/entry/tp/sl overrides.")
    public String getStagedAddReadiness(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "Strategy id") Long strategyId,
            @ToolParam(required = false, description = "Side, default LONG/BUY") String side,
            @ToolParam(required = false, description = "Interval code, default 1h") String intervalCode,
            @ToolParam(required = false, description = "Expected R override") Double expectedR,
            @ToolParam(required = false, description = "TQS band override") String tqsBand,
            @ToolParam(required = false, description = "Entry price override") BigDecimal entry,
            @ToolParam(required = false, description = "Take-profit price override") BigDecimal tp,
            @ToolParam(required = false, description = "Stop-loss price override") BigDecimal sl) {
        return stagedAddPolicyService.getStagedAddReadiness(symbol, strategyId, side, intervalCode,
                expectedR, tqsBand, entry, tp, sl);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Read-only EntryDedup governance dashboard. Groups recent EntryDedup skips by strategy/interval and shows whether each group is exact duplicate, " +
            "budget-blocked, hard-safety blocked, or a distinct staged-add candidate. No trading/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior is changed. " +
            "params: symbol default BTCUSDT, hours default 24 max 168.")
    public String getEntryDedupGovernanceDashboard(
            @ToolParam(required = false, description = "Symbol, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "Lookback hours, default 24 max 168") Integer hours) {
        return stagedAddPolicyService.getEntryDedupGovernanceDashboard(symbol, hours);
    }
}
