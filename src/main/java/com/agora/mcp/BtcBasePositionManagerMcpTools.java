package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.BtcBasePositionManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/** Read-only MCP surface for BTC_BASE_POSITION_MANAGER_V1. */
@Service
@RequiredArgsConstructor
public class BtcBasePositionManagerMcpTools {

    private final BtcBasePositionManagerService managerService;

    @Tool(description = "Read-only BTC_BASE position-manager status. Lists recorded open BTCUSDT OCO candidates " +
            "and existing BTC_BASE no-OCO slices without using wallet BTC as position ownership. " +
            "V1 does not persist adoption, cancel/modify OCO, place/close orders, or send Telegram. " +
            "params: symbol optional, default BTCUSDT.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING})
    public String getBtcBasePositionManagerStatus(
            @ToolParam(required = false, description = "Symbol, BTCUSDT only in V1") String symbol) {
        return managerService.status(symbol);
    }

    @Tool(description = "Read-only BTC_BASE adoption preview for explicit comma-separated open OCO position IDs. " +
            "Validates BTCUSDT spot LONG scope, exact tradedQty/ocoQty ownership, live exchange OCO state, " +
            "aggregate cost and fee-adjusted break-even, and heuristic EV. Existing OCO remains active. " +
            "No adoption is persisted and no live action occurs. params: positionIds required, horizonHours default 168.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String previewBtcBasePositionAdoption(
            @ToolParam(description = "Comma-separated bt_live_signal IDs, for example 260,261,262") String positionIds,
            @ToolParam(required = false, description = "Informational EV horizon, default 168") Integer horizonHours) {
        return managerService.previewAdoption(positionIds, horizonHours);
    }

    @Tool(description = "Read-only BTC_BASE disposition preview for explicit comma-separated open OCO position IDs. " +
            "Returns KEEP_OCO, RECOVERY_EXIT_REVIEW, RETIRE_CLOSE_REVIEW, or fail-closed DO_NOT_ADOPT. " +
            "The EV input is heuristic, recovery TTL is risk governance rather than proven edge, and every live action " +
            "requires a separate protected authorization. No OCO/order/database/Telegram mutation occurs. " +
            "params: positionIds required, horizonHours default 168.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String previewBtcBasePositionDisposition(
            @ToolParam(description = "Comma-separated bt_live_signal IDs, for example 260,261,262") String positionIds,
            @ToolParam(required = false, description = "Informational EV horizon, default 168") Integer horizonHours) {
        return managerService.previewDisposition(positionIds, horizonHours);
    }
}
