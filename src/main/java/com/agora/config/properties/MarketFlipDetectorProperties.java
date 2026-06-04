package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Distinct from {@code meta-control.market-flip} (analyzer); this is the detector that produces flip events. */
@Validated
@ConfigurationProperties(prefix = "meta-control.market-flip-detector")
public record MarketFlipDetectorProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("SHADOW") @NotBlank String mode
) {}
