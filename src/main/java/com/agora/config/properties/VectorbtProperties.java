package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "trading.vectorbt")
public record VectorbtProperties(
        @DefaultValue("vectorbt/output") @NotBlank String candidatesDir,
        @DefaultValue("30") @Positive int maxAgeDays
) {}
