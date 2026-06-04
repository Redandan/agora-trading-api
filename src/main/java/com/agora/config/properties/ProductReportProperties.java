package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "product-report")
public record ProductReportProperties(
        @DefaultValue("5") @Positive int autoHideThreshold,
        @DefaultValue("true") boolean autoHideEnabled,
        @DefaultValue("true") boolean notifyAdminEnabled,
        @DefaultValue("10") @Positive int dailyRateLimitPerUser
) {}
