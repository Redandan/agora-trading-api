package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "security.audit")
public record SecurityAuditProperties(
        @DefaultValue("30") @Positive int hydrateDays,
        @DefaultValue("90") @Positive int retentionDays
) {}
