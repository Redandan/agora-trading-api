package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "trading.signal-source")
public record TradingSignalSourceProperties(
        @DefaultValue("TRADINGVIEW") String primary,
        @DefaultValue("false") boolean legacyLiveEvaluatorEnabled,
        @DefaultValue("false") boolean legacySecondaryEvaluatorEnabled,
        @DefaultValue("") String legacySecondaryAllowedStrategyIds,
        @DefaultValue("0") BigDecimal legacySecondaryMaxNotionalUsdt
) {
}
