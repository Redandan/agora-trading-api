package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — Kline divergence config (5 keys, 3 classes:
 * Listener + Monitor + Alerter).
 */
@Validated
@ConfigurationProperties(prefix = "trading.kline-divergence")
public record KlineDivergenceProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3") @Positive int recentBars,
        @DefaultValue("0.5") @PositiveOrZero double warnPct,
        @DefaultValue("1.0") @PositiveOrZero double criticalPct,
        @DefaultValue("60") @PositiveOrZero long dedupWindowSeconds,
        @DefaultValue("true") boolean thinSourceDowngradeEnabled,
        @DefaultValue("1.0") @PositiveOrZero double thinSourceMinVolume,
        @DefaultValue("1000.0") @Positive double thinSourceMaxVolumeRatio
) {
}
