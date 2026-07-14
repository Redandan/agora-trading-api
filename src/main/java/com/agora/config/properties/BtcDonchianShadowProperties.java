package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Runtime switch for the evidence-only BTC Donchian lane. */
@ConfigurationProperties(prefix = "trading.btc-donchian-shadow")
public record BtcDonchianShadowProperties(
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
