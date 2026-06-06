package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — LongAiFilter (LONG entry guard) config.
 *
 * <p>Replaces 9 @Value injections in {@code LongAiFilter}. The single
 * non-prefix key {@code trading.orderbook-imbalance.threshold} stays as
 * @Value (different domain — owned by orderbook subsystem).
 */
@Validated
@ConfigurationProperties(prefix = "trading.long-ai-filter")
public record LongAiFilterProperties(

        /** Master switch. */
        @DefaultValue("false") boolean enabled,

        /** {@code shadow} = log only, {@code active} = enforce. */
        @DefaultValue("shadow") @NotBlank String mode,

        /**
         * SPOT-only mode skips rules 5 (funding rate) + 6 (long/short ratio)
         * — those are perp-specific. Default true since current LONG runs SPOT.
         */
        @DefaultValue("true") boolean spotMode,

        /** Fear & Greed upper bound (>= = extreme greed → block). */
        @DefaultValue("75") @Positive int fgThreshold,

        /** RSI upper bound (>= = overbought → block). */
        @DefaultValue("80") double rsiThreshold,

        /** Whale buy ratio lower bound (< = whales selling → block). */
        @DefaultValue("0.35") double whaleBuyRatioThreshold,

        /** Funding rate upper bound (>= = squeeze risk → block). */
        @DefaultValue("0.0005") double fundingRateThreshold,

        /** Long/short ratio upper bound (>= = squeeze risk → block). */
        @DefaultValue("1.5") double longShortRatioThreshold,

        /** Rule 8 — Chaikin Money Flow (CMF) > 0 confirmation. */
        @DefaultValue("false") boolean cmfFilter,

        /** Rule 9 — close > EMA(9) confirmation. */
        @DefaultValue("false") boolean ema9Filter,

        /** #432 Rule 10 — CMO(14) > 70 = overbought → block LONG. */
        @DefaultValue("false") boolean cmoFilter,

        /** #432 Rule 10 — CMO threshold (≥ = block). Default 70. */
        @DefaultValue("70") double cmoThreshold,

        /** #432 Rule 11 — EMA(9) < EMA(21) = short-term bearish cross → block LONG. */
        @DefaultValue("false") boolean emaCrossFilter,

        /** #432 Rule 12 — bearish RSI divergence (price HH + RSI LH) → block LONG. */
        @DefaultValue("false") boolean rsiDivergenceFilter,

        /** #432 Rule 12 — RSI period for divergence check. Default 14. */
        @DefaultValue("14") int rsiDivergencePeriod
) {
}
