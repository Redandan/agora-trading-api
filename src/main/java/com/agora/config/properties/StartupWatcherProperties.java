package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — Startup readiness budget watcher config (4 keys, 1 class).
 */
@Validated
@ConfigurationProperties(prefix = "meta-control.startup-watcher")
public record StartupWatcherProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30") @Positive long warnSeconds,
        @DefaultValue("180") @Positive long errorSeconds,
        @DefaultValue("10") @Positive long tickSeconds
) {
}
