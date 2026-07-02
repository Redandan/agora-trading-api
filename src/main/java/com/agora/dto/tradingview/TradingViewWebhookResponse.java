package com.agora.dto.tradingview;

import java.math.BigDecimal;
import java.util.List;

public record TradingViewWebhookResponse(
        String status,
        boolean accepted,
        boolean wouldExecute,
        boolean orderSent,
        boolean dryRun,
        boolean duplicate,
        String action,
        String symbol,
        String timeframe,
        String idempotencyKey,
        BigDecimal requestedNotionalUsdt,
        BigDecimal effectiveNotionalUsdt,
        List<String> blockers,
        String reason
) {
}
