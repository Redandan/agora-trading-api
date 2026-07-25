package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit runtime switch for the evidence-only MEI directional candidate. */
@ConfigurationProperties(prefix = "trading.btc-mei-directional-shadow")
public record BtcMeiDirectionalShadowProperties(
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
