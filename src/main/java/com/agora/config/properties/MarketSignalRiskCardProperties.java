package com.agora.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "market-signal.risk-card")
public record MarketSignalRiskCardProperties(

        /** Enables scheduled market-signal summary cards. */
        @DefaultValue("false") boolean enabled,

        /** When true, scheduler logs/skips TG broadcast and only renders the card. */
        @DefaultValue("true") boolean dryRun,

        /** UTC cron for scheduled checks. */
        @DefaultValue("0 10 */4 * * *") @NotBlank String cron,

        /** Lookback window in hours. */
        @DefaultValue("24") @Min(1) @Max(168) int windowHours,

        /** Optional symbol filter; blank means all symbols. */
        @DefaultValue("BTCUSDT") String symbol,

        /** Minimum MARKET_SIGNAL rows before a scheduled card is eligible. */
        @DefaultValue("3") @Min(0) @Max(100) int minMarketSignals,

        /** Minimum route families before a scheduled card is eligible. */
        @DefaultValue("2") @Min(0) @Max(20) int minRouteFamilies,

        /** If true, only emit when status/top-route fingerprint changes. */
        @DefaultValue("true") boolean sendOnStatusChangeOnly
) {
}
