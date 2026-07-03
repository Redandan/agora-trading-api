package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "trading.tradingview.local")
public record TradingViewLocalSignalProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("485") @Positive long strategyId,
        @DefaultValue("BTCUSDT") String allowedSymbols,
        @DefaultValue("1d") String allowedIntervals,
        @DefaultValue("") String allowedSources,
        @DefaultValue("320") @Positive int historyBars,
        @DefaultValue("10.0") @Positive BigDecimal defaultNotionalUsdt,
        @DefaultValue("10.0") @Positive BigDecimal maxNotionalUsdt
) {
}
