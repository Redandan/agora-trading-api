package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** #378 — ML edge staleness watcher (3 keys, 1 class). */
@Validated
@ConfigurationProperties(prefix = "meta-control.ml-edge-watcher")
public record MlEdgeWatcherProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("7") @Positive int consecutiveDays,
        @DefaultValue("7") @Positive int cooldownDays
) {}
