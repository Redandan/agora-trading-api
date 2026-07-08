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
        @DefaultValue("") String allowedSources,
        @DefaultValue("320") @Positive int historyBars,
        @DefaultValue("3") @Positive int catchUpBars,
        @DefaultValue("72") @PositiveOrZero long maxSignalAgeHours,
        @DefaultValue("10.0") @Positive BigDecimal defaultNotionalUsdt,
        @DefaultValue("10.0") @Positive BigDecimal maxNotionalUsdt,
        @DefaultValue("LEGACY") ExecutionMode executionMode,
        @DefaultValue("false") boolean executionEnabled,
        @DefaultValue("true") boolean executionDryRun,
        @DefaultValue("false") boolean executionLiveOrderEnabled,
        @DefaultValue("3") @Positive int executionMaxOrdersPerBar,
        @DefaultValue("1") @Positive int executionMaxOrdersPerDay,
        @DefaultValue("1") @Positive int executionMaxOpenPositions,
        @DefaultValue("0.0300") @Positive BigDecimal executionTakeProfitPct,
        @DefaultValue("0.1200") @Positive BigDecimal executionStopLossPct,
        @DefaultValue("250.0") @Positive BigDecimal btcBaseMaxExposureUsdt
) {
    public boolean effectiveExecutionEnabled() {
        return switch (executionMode) {
            case OFF -> false;
            case DRY_RUN, BTC_BASE_DRY_RUN, LIVE_MICRO, BTC_BASE_LIVE_MICRO -> true;
            case LEGACY -> executionEnabled;
        };
    }

    public boolean effectiveExecutionDryRun() {
        return switch (executionMode) {
            case LIVE_MICRO, BTC_BASE_LIVE_MICRO -> false;
            case OFF, DRY_RUN, BTC_BASE_DRY_RUN -> true;
            case LEGACY -> executionDryRun;
        };
    }

    public boolean effectiveExecutionLiveOrderEnabled() {
        return switch (executionMode) {
            case LIVE_MICRO, BTC_BASE_LIVE_MICRO -> true;
            case OFF, DRY_RUN, BTC_BASE_DRY_RUN -> false;
            case LEGACY -> executionLiveOrderEnabled;
        };
    }

    public enum ExecutionMode {
        LEGACY,
        OFF,
        DRY_RUN,
        BTC_BASE_DRY_RUN,
        BTC_BASE_LIVE_MICRO,
        LIVE_MICRO
    }
}
