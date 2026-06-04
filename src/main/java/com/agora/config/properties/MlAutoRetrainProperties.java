package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "meta-control.ml-autoretrain")
public record MlAutoRetrainProperties(
        @DefaultValue("30") @Positive int holdoutDays,
        @DefaultValue("80") @Positive int minHoldoutTrades
) {}
