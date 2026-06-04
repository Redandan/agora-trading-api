package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — TG group AI chat service config (4 keys, 1 class).
 */
@Validated
@ConfigurationProperties(prefix = "ai.group")
public record AiGroupProperties(
        @DefaultValue("0.30") @PositiveOrZero double minQuotaRatio,
        @DefaultValue("5") @Positive int contextSize,
        @DefaultValue("5") @Positive int ragHistorySize,
        @DefaultValue("3") @Positive int ragKnowledgeSize
) {
}
