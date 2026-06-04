package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ai.budget")
public record AiBudgetProperties(
        @DefaultValue("1200") @Positive int geminiFlashDailyReq,
        @DefaultValue("13000") @Positive int groqLlamaDailyReq
) {}
