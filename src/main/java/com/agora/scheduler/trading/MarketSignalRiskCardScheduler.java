package com.agora.scheduler.trading;

import com.agora.config.properties.MarketSignalRiskCardProperties;
import com.agora.mcp.MetaControlMcpTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * #504 — scheduled market-signal summary card.
 *
 * <p>Read-only by design: this scheduler only compresses recent TG market
 * signals into an operator summary. It must never mutate trading strategy,
 * OCO, orders, grid state, trailing stops, or funds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "market-signal.risk-card.enabled", havingValue = "true", matchIfMissing = false)
public class MarketSignalRiskCardScheduler {

    private final MetaControlMcpTools metaControlMcpTools;
    private final MarketSignalRiskCardProperties properties;

    @Scheduled(cron = "${market-signal.risk-card.cron:0 10 */4 * * *}", zone = "UTC")
    public void sendMarketSignalRiskCard() {
        try {
            String result = metaControlMcpTools.sendScheduledMarketSignalRiskCard(
                    properties.windowHours(),
                    properties.symbol(),
                    properties.minMarketSignals(),
                    properties.minRouteFamilies(),
                    properties.sendOnStatusChangeOnly(),
                    properties.dryRun());
            log.info("[MarketSignalRiskCard] scheduled run complete: {}", result);
        } catch (Throwable t) {
            log.error("[MarketSignalRiskCard] scheduled run failed: {}", t.getMessage(), t);
        }
    }
}
