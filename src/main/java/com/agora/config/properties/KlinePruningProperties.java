package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "kline-pruning")
public record KlinePruningProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("30") @Positive int retentionDays
) {}
