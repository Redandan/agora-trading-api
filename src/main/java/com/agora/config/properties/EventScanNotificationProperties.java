package com.agora.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "event-scan.notification")
public record EventScanNotificationProperties(

        /** Enables the scheduled outbound notification hook. */
        @DefaultValue("false") boolean enabled,

        /** When true, scheduler logs the message but does not broadcast it. */
        @DefaultValue("true") boolean dryRun,

        /** UTC cron for the scheduled scan. */
        @DefaultValue("0 5 * * * *") @NotBlank String cron,

        /** Lookback window in minutes. */
        @DefaultValue("90") @Min(1) @Max(1440) int windowMinutes,

        /** Optional symbol filter; blank means all symbols. */
        @DefaultValue("BTCUSDT") String symbol,

        /** Max decision audit rows to scan. */
        @DefaultValue("120") @Min(1) @Max(200) int scanLimit,

        /** Max event lines included in the outbound message. */
        @DefaultValue("12") @Min(1) @Max(50) int maxEvents,

        /** Suppress repeated no-action blocked-buy-pressure scheduled cards for this many minutes. */
        @DefaultValue("360") @Min(0) @Max(1440) int suppressRepeatMinutes
) {
}
