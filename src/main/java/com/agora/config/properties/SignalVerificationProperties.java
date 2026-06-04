package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "signal-verification")
public record SignalVerificationProperties(
        @DefaultValue("0.1") @PositiveOrZero double minMovementPct,
        @DefaultValue("30") @Positive int maxWatchingDays,
        @DefaultValue("0.40") @PositiveOrZero double accuracyAlertThreshold,
        @DefaultValue("5") @Positive int minSampleSize
) {}
