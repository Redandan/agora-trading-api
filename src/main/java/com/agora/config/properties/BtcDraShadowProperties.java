package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit runtime switch for the evidence-only BTC DRA candidate. */
@ConfigurationProperties(prefix = "trading.btc-dra-shadow")
public record BtcDraShadowProperties(
        @DefaultValue("OFF") Mode mode
) {
    public boolean enabled() {
        return mode == Mode.SHADOW;
    }

    public enum Mode {
        OFF,
        SHADOW
    }
}
