package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Explicit fail-closed runtime switch for BTC DRA V1.
 *
 * <p>LIVE is a bounded OKX spot canary: one lot within 30 USDT, no leverage,
 * no loss exit, and no OCO/Grid/fund dependency.</p>
 */
@Validated
@ConfigurationProperties(prefix = "trading.btc-dra")
public record BtcDraRuntimeProperties(
        @DefaultValue("OFF") Mode mode,
        @DefaultValue("30.00") @Positive BigDecimal liveNotionalUsdt,
        @DefaultValue("30.00") @Positive BigDecimal maxLiveExposureUsdt,
        @DefaultValue("15") @Positive long liveMaxSignalAgeMinutes,
        @DefaultValue("AGGRESSIVE") @NotBlank @Pattern(regexp = "AGGRESSIVE|CONSERVATIVE") String riskMode
) {
    @ConstructorBinding
    public BtcDraRuntimeProperties { }

    public BtcDraRuntimeProperties(Mode mode, BigDecimal liveNotionalUsdt,
                                 BigDecimal maxLiveExposureUsdt, long liveMaxSignalAgeMinutes) {
        this(mode, liveNotionalUsdt, maxLiveExposureUsdt, liveMaxSignalAgeMinutes, RiskMode.AGGRESSIVE);
    }

    public BtcDraRuntimeProperties(Mode mode, BigDecimal liveNotionalUsdt,
                                 BigDecimal maxLiveExposureUsdt, long liveMaxSignalAgeMinutes, RiskMode riskMode) {
        this(mode, liveNotionalUsdt, maxLiveExposureUsdt, liveMaxSignalAgeMinutes,
                riskMode == null ? null : riskMode.name());
    }

    public boolean validRiskMode() {
        return "AGGRESSIVE".equals(riskMode) || "CONSERVATIVE".equals(riskMode);
    }

    /** Applies only to a new buy; existing lot quantities and exits are never resized. */
    public BigDecimal newBuyNotionalUsdt() {
        return liveNotionalUsdt.multiply(RiskMode.valueOf(riskMode).newBuyFraction)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

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

    public enum RiskMode {
        AGGRESSIVE("1.00"),
        CONSERVATIVE("0.50");

        private final BigDecimal newBuyFraction;

        RiskMode(String newBuyFraction) {
            this.newBuyFraction = new BigDecimal(newBuyFraction);
        }
    }
}
