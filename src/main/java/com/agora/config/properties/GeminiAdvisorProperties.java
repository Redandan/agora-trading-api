package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 Phase 2 — Gemini market advisor + hint detectors config.
 *
 * <p>Replaces {@code @Value} injections across 3 classes:
 * <ul>
 *   <li>{@code GeminiMarketAdvisor} — main advisor service (9 keys)</li>
 *   <li>{@code GeminiHintFlipDetector} — flip detection scheduler (3 keys, 2 shared)</li>
 *   <li>{@code GeminiHintStalenessDetector} — staleness detection scheduler (5 keys, 2 shared)</li>
 * </ul>
 * Shared `symbols` and `timeframes` are CSV strings consumed by all three.
 */
@Validated
@ConfigurationProperties(prefix = "trading.gemini-advisor")
public record GeminiAdvisorProperties(

        /** Master switch for {@code GeminiMarketAdvisor.scheduledAdvise()}. */
        @DefaultValue("true") boolean enabled,

        /** Cron expression for {@code GeminiMarketAdvisor.runOnSchedule()}. */
        @DefaultValue("0 5 */8 * * *") @NotBlank String cron,

        /** CSV of monitored symbols. */
        @DefaultValue("BTCUSDT,ETHUSDT") @NotBlank String symbols,

        /** CSV of monitored timeframes. */
        @DefaultValue("1h,4h") @NotBlank String timeframes,

        /** Hint TTL hours used by GeminiMarketAdvisor. Keep this above the 8h default cron gap. */
        @DefaultValue("9") @Positive int hintTtlHours,

        /** Delay between symbol/timeframe advisor groups to stay below provider RPM limits. */
        @DefaultValue("25000") @PositiveOrZero long requestGapMs,

        /** Delay between persona calls inside one advisor group to avoid short-window rate limits. */
        @DefaultValue("10000") @PositiveOrZero long personaGapMs,

        /** Whether to send TG summary after each advise cycle. */
        @DefaultValue("true") boolean tgSummary,

        /** Min existing hints before {@code skip-stuck} kicks in. */
        @DefaultValue("3") @Positive int skipStuckMinHints,

        /** Min confidence for skip-stuck. */
        @DefaultValue("0.9") double skipStuckConfMin,

        /** Master switch for skip-stuck. */
        @DefaultValue("true") boolean skipStuckEnabled,

        /** Whether to feed prior hint into LLM context. */
        @DefaultValue("true") boolean priorHintContextEnabled,

        /** Master switch for {@code GeminiHintFlipDetector.scheduledScan()}. */
        @DefaultValue("true") boolean flipDetectorEnabled,

        /** Master switch for {@code GeminiHintStalenessDetector.scheduledScan()}. */
        @DefaultValue("true") boolean stalenessDetectorEnabled,

        /** Min hints required before staleness alert. */
        @DefaultValue("24") @Positive int stalenessMinHints,

        /** Min confidence for staleness alert. */
        @DefaultValue("0.9") double stalenessConfMin,

        /** Cooldown hours between staleness alerts. */
        @DefaultValue("24") @PositiveOrZero long stalenessCooldownHours
) {
}
