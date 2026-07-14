package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.BtcBasePositionAdoptionService;
import com.agora.service.trading.BtcBasePositionManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** BTC_BASE manager previews plus one disabled-by-default protected adoption write. */
@Service
@RequiredArgsConstructor
public class BtcBasePositionManagerMcpTools {

    private final BtcBasePositionManagerService managerService;
    private final BtcBasePositionAdoptionService adoptionService;

    @Tool(description = "Read-only BTC_BASE position-manager status. Lists recorded open BTCUSDT OCO candidates " +
            "and existing BTC_BASE no-OCO slices, including managed quantity, cost, weighted average entry, " +
            "estimated fee-adjusted break-even, mark-to-market value, and PnL. Managed cost uses explicit open " +
            "bt_live_signal actualEntryPrice/entryPrice and tradedQty; it does not require OCO or use wallet BTC. " +
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
            "Returns ADOPT_KEEP_BTC, ADOPT_KEEP_BTC_RISK_REVIEW, or fail-closed DO_NOT_ADOPT. " +
            "The EV input is heuristic and only changes the risk label; it never authorizes a BTC sale. Every live " +
            "action requires a separate protected authorization. No OCO/order/database/Telegram mutation occurs. " +
            "params: positionIds required, horizonHours default 168.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    public String previewBtcBasePositionDisposition(
            @ToolParam(description = "Comma-separated bt_live_signal IDs, for example 260,261,262") String positionIds,
            @ToolParam(required = false, description = "Informational EV horizon, default 168") Integer horizonHours) {
        return managerService.previewDisposition(positionIds, horizonHours);
    }

    @Tool(description = "Protected BTC_BASE adoption write for explicit BTCUSDT spot position IDs. " +
            "Dry-run by default. The execution path persists ADOPTION_PENDING, cancels and confirms each exact OCO, " +
            "then records ADOPTED_FROM_OCO while retaining BTC. It never places a sell, closes a position, sends " +
            "Telegram, moves funds, or changes Grid/Earn. execute=true requires both disabled-by-default server gates, " +
            "exact expectedTotalQty, and the exact dynamic confirmText returned by dry-run. Partial exchange outcomes " +
            "remain recoverable PENDING and fail closed. params: positionIds, expectedTotalQty, execute, confirmText.")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    public String adoptBtcBasePositionsKeepBtc(
            @ToolParam(description = "Comma-separated explicit bt_live_signal IDs") String positionIds,
            @ToolParam(required = false, description = "Exact aggregate traded BTC quantity; required for execute=true") BigDecimal expectedTotalQty,
            @ToolParam(required = false, description = "False/null for dry-run; true requests guarded execution") Boolean execute,
            @ToolParam(required = false, description = "Exact dynamic confirmation string returned by dry-run") String confirmText) {
        return adoptionService.previewOrExecute(positionIds, expectedTotalQty, execute, confirmText);
    }
}
