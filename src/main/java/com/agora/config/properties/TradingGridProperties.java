package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * #378 — Trading grid manager config (4 keys, 1 class).
 */
@Validated
@ConfigurationProperties(prefix = "trading.grid")
public record TradingGridProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean customCreateResumeEnabled,
        @DefaultValue("false") boolean legacyRetirementEnabled,
        @DefaultValue("false") boolean legacyRetirementLiveActionEnabled,
        @DefaultValue("24") @Positive int sellFailedAgingHours,
        @DefaultValue("300000") @Positive long checkIntervalMs,
        @DefaultValue("true") boolean recycleClosedLevels,
        @DefaultValue("5.0") @Positive BigDecimal minSellNotionalUsdt
) {
}
