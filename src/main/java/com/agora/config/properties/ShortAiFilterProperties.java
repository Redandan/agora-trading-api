package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — ShortAiFilter (SHORT entry guard) config (5 keys, 1 class).
 */
@Validated
@ConfigurationProperties(prefix = "trading.short-ai-filter")
public record ShortAiFilterProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("shadow") @NotBlank String mode,
        @DefaultValue("0.40") double macroRiskThreshold,
        @DefaultValue("-0.0003") double fundingRateThreshold,
        @DefaultValue("0.75") double longShortRatioThreshold
) {
}
