package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** #378 — JVM heap memory monitor (3 keys, 1 class). */
@Validated
@ConfigurationProperties(prefix = "meta-control.memory-monitor")
public record MemoryMonitorProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500") @Positive long thresholdMb,
        @DefaultValue("30") @Positive long cooldownMinutes
) {}
