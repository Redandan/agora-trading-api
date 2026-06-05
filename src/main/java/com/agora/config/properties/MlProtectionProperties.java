package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** ML runtime protection guardrails (SECONDARY_LOAD / pool meltdown defense). */
@Validated
@ConfigurationProperties(prefix = "meta-control.ml-protection")
public record MlProtectionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("60") int scanIntervalSeconds,
        @DefaultValue("120") int secondaryLoadTimeoutSeconds,
        @DefaultValue("1") int minSecondaryLoadRowsToTrip,
        @DefaultValue("true") boolean autoKillSecondaryLoad,
        @DefaultValue("30") int alertCooldownMinutes
) {}
