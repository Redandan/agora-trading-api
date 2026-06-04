package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 POC #1 — AI strategy discovery scheduler config.
 *
 * <p>Replaces 6 @Value injections in
 * {@code AiStrategyDiscoveryScheduler}. Note: the cron expression on
 * {@code @Scheduled} cannot be record-bound and stays as SpEL
 * {@code "${ai.strategy.discovery.cron:0 30 5 * * ?}"}.
 *
 * <p>YAML key mapping (kebab-case → camelCase):
 * <ul>
 *   <li>{@code ai.strategy.discovery.interval-code}  → {@link #intervalCode()}</li>
 *   <li>{@code ai.strategy.discovery.lookback-days}  → {@link #lookbackDays()}</li>
 *   <li>{@code ai.strategy.discovery.candidate-count}→ {@link #candidateCount()}</li>
 *   <li>{@code ai.strategy.discovery.initial-capital}→ {@link #initialCapital()}</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "ai.strategy.discovery")
public record AiStrategyDiscoveryProperties(

        /** 是否啟用排程探勘（預設 false — 須 ops 手動開啟）。 */
        @DefaultValue("false") boolean enabled,

        /** 探勘交易對。 */
        @DefaultValue("BTCUSDT") @NotBlank String symbol,

        /** K 線週期（1m / 15m / 1h / 4h / 1d）。 */
        @DefaultValue("1h") @NotBlank String intervalCode,

        /** 回測回看天數。 */
        @DefaultValue("90") @Positive int lookbackDays,

        /** 每次探勘生成幾個候選策略。 */
        @DefaultValue("3") @Positive int candidateCount,

        /** 回測初始資金（保留為 String，下游轉 BigDecimal）。 */
        @DefaultValue("10000") @NotBlank String initialCapital
) {
}
