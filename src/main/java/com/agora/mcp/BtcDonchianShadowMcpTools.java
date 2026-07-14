package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.BtcDonchianShadowGoldenParityService;
import com.agora.service.trading.BtcDonchianShadowReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/** Read-only diagnostics for BTC_DONCHIAN_20D_10D_V1. */
@Service
@RequiredArgsConstructor
public class BtcDonchianShadowMcpTools {

    private final BtcDonchianShadowGoldenParityService goldenParityService;
    private final BtcDonchianShadowReadinessService readinessService;

    @Tool(description = "Read-only exact golden parity for BTC_DONCHIAN_20D_10D_V1. "
            + "Replays the frozen OKX BTCUSDT 1h 2019-01-01..2026-07-13 window through the same "
            + "runtime engine and compares normal/stress signal, virtual-order, and trade ledger hashes. "
            + "Missing bars or any mismatch fail closed. It never sends an order, modifies OCO, sends "
            + "Telegram, or performs backfill. params: symbol optional (BTCUSDT only).")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    public String analyzeBtcDonchianShadowGoldenParity(String symbol) {
        return goldenParityService.report(symbol);
    }

    @Tool(description = "Read-only forward SHADOW readiness for BTC_DONCHIAN_20D_10D_V1. "
            + "Requires exact golden parity, at least 30 non-bootstrap days, five independent normal "
            + "entries and completed trades, non-negative stress economics, causal complete hourly "
            + "evidence, and zero order-sent violations. No live implementation exists and READY is "
            + "not authorization. params: symbol optional (BTCUSDT only).")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    public String getBtcDonchianShadowReadiness(String symbol) {
        return readinessService.report(symbol);
    }
}
