package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
        @DefaultValue("binance") String allowedSources,
        @DefaultValue("320") @Positive int historyBars,
        @DefaultValue("3") @Positive int catchUpBars,
        @DefaultValue("72") @PositiveOrZero long maxSignalAgeHours,
        @DefaultValue("10.0") @Positive BigDecimal defaultNotionalUsdt,
        @DefaultValue("80.0") @Positive BigDecimal maxNotionalUsdt,
        @DefaultValue("BTC_BASE_PAPER") ExecutionMode executionMode,
        @DefaultValue("250.0") @Positive BigDecimal btcBaseMaxExposureUsdt,
        @DefaultValue("15") @Positive long liveMaxSignalAgeMinutes
) {
    public boolean effectiveExecutionEnabled() {
        return executionMode == ExecutionMode.BTC_BASE_PAPER
                || executionMode == ExecutionMode.BTC_BASE_LIVE;
    }

    public boolean effectiveExecutionDryRun() {
        return executionMode != ExecutionMode.BTC_BASE_LIVE;
    }

    public boolean effectiveExecutionLiveOrderEnabled() {
        return enabled && executionMode == ExecutionMode.BTC_BASE_LIVE;
    }

    public enum ExecutionMode {
        OFF,
        BTC_BASE_PAPER,
        BTC_BASE_LIVE
    }
}
