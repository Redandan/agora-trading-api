package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "meta-control.indicator-history")
public record IndicatorHistoryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("BTCUSDT,ETHUSDT") @NotBlank String symbols
) {}
