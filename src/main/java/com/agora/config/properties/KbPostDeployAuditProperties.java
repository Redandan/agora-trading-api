package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** #378 — KB post-deploy audit listener (3 keys, 1 class). */
@Validated
@ConfigurationProperties(prefix = "kb.post-deploy-audit")
public record KbPostDeployAuditProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("45") @PositiveOrZero long delaySeconds,
        @DefaultValue("1440") @Positive long cooldownMinutes
) {}
