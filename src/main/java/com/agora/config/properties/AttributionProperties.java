package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * #378 — Meta-control attribution config (7 keys, 2 classes:
 * MetaControlAttributionScheduler + MetaControlAttributionService).
 */
@Validated
@ConfigurationProperties(prefix = "meta-control.attribution")
public record AttributionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2") @Positive int scanHours,
        @DefaultValue("24") @PositiveOrZero int startupBackfillHours,
        @DefaultValue("5") @PositiveOrZero int startupDelayMinutes,
        @DefaultValue("okx") @NotBlank String klineSource,
        @DefaultValue("10000") BigDecimal initialCapital,
        @DefaultValue("0.001") BigDecimal feeRate
) {
}
