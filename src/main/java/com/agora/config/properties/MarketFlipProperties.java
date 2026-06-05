package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — Market-flip analysis + auto-escalate + AI consensus config (5 keys).
 *
 * <p>Nested record {@link AiConsensus} maps the {@code ai-consensus.*} sub-prefix.
 */
@Validated
@ConfigurationProperties(prefix = "meta-control.market-flip")
public record MarketFlipProperties(
        @DefaultValue("false") boolean analysisEnabled,
        @DefaultValue("10") @Positive int analysisBatchSize,
        @DefaultValue("false") boolean autoEscalateEnabled,
        @DefaultValue("60") @Positive int escalateAgeMinutes,
        @DefaultValue AiConsensus aiConsensus
) {
    public record AiConsensus(@DefaultValue("false") boolean enabled) {}
}
