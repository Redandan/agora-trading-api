package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Lightweight processlist monitor for app slow queries and active HeatWave work. */
@Validated
@ConfigurationProperties(prefix = "meta-control.db-slow-query-monitor")
public record DbSlowQueryMonitorProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60") @Positive int scanIntervalSeconds,
        @DefaultValue("30") @Positive int watchSeconds,
        @DefaultValue("120") @Positive int warnSeconds,
        @DefaultValue("300") @Positive int criticalSeconds,
        @DefaultValue("10") @Positive int maxRows,
        @DefaultValue("30") @Positive int alertCooldownMinutes
) {}
