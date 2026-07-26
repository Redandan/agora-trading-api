package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Explicit fail-closed runtime switch for BTC DRA V1.
 *
 * <p>LIVE is a bounded OKX spot canary: one 30 USDT lot, no leverage,
 * no loss exit, and no OCO/Grid/fund dependency.</p>
 */
@Validated
@ConfigurationProperties(prefix = "trading.btc-dra")
public record BtcDraRuntimeProperties(
        @DefaultValue("OFF") Mode mode,
        @DefaultValue("30.00") @Positive BigDecimal liveNotionalUsdt,
        @DefaultValue("30.00") @Positive BigDecimal maxLiveExposureUsdt,
        @DefaultValue("15") @Positive long liveMaxSignalAgeMinutes
) {
    public boolean enabled() {
        return mode == Mode.SHADOW || mode == Mode.LIVE;
    }

    public boolean liveOrderEnabled() {
        return mode == Mode.LIVE;
    }

    public enum Mode {
        OFF,
        SHADOW,
        LIVE
    }
}
