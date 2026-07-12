package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.Strategy508TimeExitCandidateService;
import com.agora.service.trading.Strategy508TimeExitReadinessService;
import com.agora.service.trading.StrategyNetPnlAttributionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Strategy508TimeExitMcpTools {

    private final Strategy508TimeExitCandidateService candidateService;
    private final Strategy508TimeExitReadinessService readinessService;
    private final StrategyNetPnlAttributionService pnlAttributionService;

    @Tool(description = "Read-only fixed-policy analysis for strategy 508 BTCUSDT 4h BUY entries with " +
            "one 10 USDT position, +6% TP, -12% disaster SL, and a 24h time exit. " +
            "Uses the first post-close OKX 1m open, requires >=99% 1m coverage, includes fees, slippage, " +
            "90/120/180/270/365d windows and chronological walk-forward. It never authorizes live trading. " +
            "params: symbol optional (BTCUSDT only), detailLimit default 50.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    public String analyzeStrategy508TimeExitCandidate(String symbol, Integer detailLimit) {
        return candidateService.analyze(symbol, detailLimit);
    }

    @Tool(description = "Read-only strategy 508 4h/24h lane readiness. Reports fixed historical gate, " +
            "rolling 30-day forward shadow evidence, live-pilot caps, fee coverage, loss fuse, and blockers. " +
            "A READY verdict is evidence only and never authorizes or sends an order. params: symbol optional.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    public String getStrategy508TimeExitReadiness(String symbol) {
        return readinessService.report(symbol);
    }

    @Tool(description = "Read-only fee-aware strategy PnL attribution. Separates legacy gross realized PnL, " +
            "entry/exit fees from runtime evidence, exact known net PnL, unknown-fee rows, and open " +
            "mark-to-market. Missing fee evidence fails closed and cannot be claimed as exact profit. " +
            "params: strategyId required, symbol default BTCUSDT, days default 90.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    public String getStrategyNetPnlAttribution(Long strategyId, String symbol, Integer days) {
        return pnlAttributionService.report(strategyId, symbol, days);
    }
}
