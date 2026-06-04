package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — Short-squeeze alert + Binance taker-buy collector + SQI indicator config.
 *
 * <p>Replaces @Value injections across 4 classes:
 * <ul>
 *   <li>{@code ShortSqueezeAlertScheduler} (6 keys)</li>
 *   <li>{@code BinanceSpotTakerBuyCollector} (1 key — taker-buy-collector-enabled)</li>
 *   <li>{@code SqiIndicator} (1 key — path-b-fallback-threshold, shared)</li>
 *   <li>{@code SqueezeIndicatorService} (1 key — path-b-fallback-threshold, shared)</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "trading.short-squeeze-alert")
public record ShortSqueezeAlertProperties(

        /** Master switch for {@code ShortSqueezeAlertScheduler.tick()}. */
        @DefaultValue("true") boolean enabled,

        /** Master switch for {@code BinanceSpotTakerBuyCollector}. */
        @DefaultValue("true") boolean takerBuyCollectorEnabled,

        /** Funding rate lower bound (negative — short-heavy regime). */
        @DefaultValue("-0.00005") double fundingRateThreshold,

        /** SPOT taker-buy USD threshold for path A trigger. */
        @DefaultValue("5000000") double spotTakerBuyThreshold,

        /** Cooldown minutes between TG alerts. */
        @DefaultValue("60") @PositiveOrZero int cooldownMinutes,

        /** Path B min ratio (taker-buy / liquidation). */
        @DefaultValue("3.0") @Positive double pathBMinRatio,

        /** Path B fallback threshold (USD; shared with SqiIndicator + SqueezeIndicatorService). */
        @DefaultValue("20000000") double pathBFallbackThreshold
) {
}
