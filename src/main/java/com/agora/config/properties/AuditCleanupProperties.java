package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** #378 — Decision audit cleanup scheduler (3 keys, 1 class). */
@Validated
@ConfigurationProperties(prefix = "meta-control.audit")
public record AuditCleanupProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("120") @Positive int retentionDays,
        @DefaultValue("10000") @Positive int cleanupBatchSize
) {}
