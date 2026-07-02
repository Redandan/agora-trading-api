package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "trading.signal-source")
public record TradingSignalSourceProperties(
        @DefaultValue("TRADINGVIEW") String primary,
        @DefaultValue("false") boolean legacyLiveEvaluatorEnabled
) {
}
