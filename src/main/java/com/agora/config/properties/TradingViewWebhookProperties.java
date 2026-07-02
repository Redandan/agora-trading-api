package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "trading.tradingview.webhook")
public record TradingViewWebhookProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean dryRun,
        @DefaultValue("") String secret,
        @DefaultValue("BTCUSDT") String allowedSymbols,
        @DefaultValue("10.0") @Positive BigDecimal defaultNotionalUsdt,
        @DefaultValue("10.0") @Positive BigDecimal maxNotionalUsdt,
        @DefaultValue("24") @Positive int idempotencyTtlHours
) {
}
