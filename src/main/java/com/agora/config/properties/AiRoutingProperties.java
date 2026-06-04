package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — AI provider routing config (5 keys, 2 classes).
 *
 * <p>Two task-specific sub-prefixes:
 * <ul>
 *   <li>{@code ai.routing.analyze-market-flip.*} — MarketFlipConsensusService</li>
 *   <li>{@code ai.routing.score-signal.*} — SignalScorerEnsemble</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "ai.routing")
public record AiRoutingProperties(
        @DefaultValue AnalyzeMarketFlip analyzeMarketFlip,
        @DefaultValue ScoreSignal scoreSignal
) {

    public record AnalyzeMarketFlip(
            @DefaultValue("groq-llama-3.3-70b") @NotBlank String primary,
            @DefaultValue("groq-llama-3.3-70b,gemini-flash") @NotBlank String parallel
    ) {}

    public record ScoreSignal(
            @DefaultValue("gemini-flash,groq-llama-3.3-70b") @NotBlank String parallel,
            @DefaultValue("25") @Positive int timeoutSec,
            @DefaultValue("0.5") @PositiveOrZero double decisionThreshold
    ) {}
}
