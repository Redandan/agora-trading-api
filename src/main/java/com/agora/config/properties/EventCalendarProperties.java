package com.agora.config.properties;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** #378 — Macro event calendar block windows (3 keys, 1 class). */
@Validated
@ConfigurationProperties(prefix = "trading.event-calendar")
public record EventCalendarProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2") @PositiveOrZero int windowBeforeHours,
        @DefaultValue("4") @PositiveOrZero int windowAfterHours
) {}
