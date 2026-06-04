package com.agora.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "execution-event")
public record ExecutionEventProperties(

        /** Enables scheduled read-only execution-event detection. */
        @DefaultValue("false") boolean enabled,

        /** UTC cron for scheduled detection. */
        @DefaultValue("0 */5 * * * *") @NotBlank String cron,

        /** Max rows returned by scheduled logging/reporting helpers. */
        @DefaultValue("20") @Min(1) @Max(100) int listLimit,

        /** Sends a compact Telegram card after scheduled detection when active events exist. */
        @DefaultValue("true") boolean notificationEnabled,

        /** When true, scheduled notification renders/logs but does not send Telegram. */
        @DefaultValue("true") boolean notificationDryRun,

        /** Suppress repeated Telegram cards with the same active-event fingerprint set. */
        @DefaultValue("180") @Min(1) @Max(1440) int notificationTtlMinutes
) {
}
