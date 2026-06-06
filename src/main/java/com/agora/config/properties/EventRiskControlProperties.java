package com.agora.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "event-risk-control")
public record EventRiskControlProperties(

        /** Master switch for event-driven preemptive risk control. */
        @DefaultValue("true") boolean enabled,

        /** When true, R2/R3 risk gates can block new auto-trade entries. */
        @DefaultValue("true") boolean blockNewEntries,

        /** When true, risk-level changes can emit operator notifications. */
        @DefaultValue("false") boolean statusNotifyEnabled,

        /** Lookback window for MARKET_SIGNAL Telegram rows. */
        @DefaultValue("4") @Min(1) @Max(48) int tgWindowHours,

        /** Cooldown for repeated state-change summaries. */
        @DefaultValue("60") @Min(1) @Max(1440) int statusNotifyCooldownMinutes,

        /** Comma-separated strategy ids allowed to keep opening new entries at R2. */
        @DefaultValue("") String r2AllowlistStrategyIds,

        /** Comma-separated strategy ids allowed to keep opening new entries at R3. */
        @DefaultValue("") String r3AllowlistStrategyIds
) {
}
