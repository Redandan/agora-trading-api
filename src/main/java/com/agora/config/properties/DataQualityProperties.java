package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — Data quality monitor config (7 keys, 1 class).
 */
@Validated
@ConfigurationProperties(prefix = "meta-control.data-quality")
public record DataQualityProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30.0") double fgDeltaThreshold,
        @DefaultValue("0.40") double whaleDeltaThreshold,
        @DefaultValue("0.0005") double fundingDeltaThreshold,
        @DefaultValue("0.8") double orderbookDeltaThreshold,
        @DefaultValue("3") @Positive int oscillationWindowHours,
        @DefaultValue("3") @Positive int oscillationCountThreshold
) {
}
