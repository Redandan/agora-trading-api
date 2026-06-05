package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** #378 — Ephemeral strategy cleanup scheduler (3 keys, 1 class). */
@Validated
@ConfigurationProperties(prefix = "trading.ephemeral-cleanup")
public record EphemeralCleanupProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("7") @Positive int retainDays,
        @DefaultValue("AI-,EXT-") @NotBlank String prefixes
) {}
