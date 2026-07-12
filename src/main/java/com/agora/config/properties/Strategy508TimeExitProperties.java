package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "trading.strategy508-time-exit")
public record Strategy508TimeExitProperties(
        @DefaultValue("OFF") Mode mode,
        @DefaultValue("false") boolean liveOrderEnabled
) {
    public boolean enabled() {
        return mode != Mode.OFF;
    }

    public boolean shadowOnly() {
        return mode == Mode.SHADOW || !liveOrderEnabled;
    }

    public boolean liveMicroArmed() {
        return mode == Mode.LIVE_MICRO && liveOrderEnabled;
    }

    public enum Mode {
        OFF,
        SHADOW,
        LIVE_MICRO
    }
}
