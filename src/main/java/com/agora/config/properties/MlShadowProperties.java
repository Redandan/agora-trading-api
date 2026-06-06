package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** #378 — ML inference logger / shadow mode (3 keys, 1 class). */
@Validated
@ConfigurationProperties(prefix = "meta-control.ml-shadow")
public record MlShadowProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("signal_scorer") @NotBlank String modelName,
        @DefaultValue("0.5") double threshold
) {}
