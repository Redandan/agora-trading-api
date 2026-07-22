package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Disabled-by-default gates for the OKX-native Spot Grid write adapter. */
@ConfigurationProperties(prefix = "trading.okx-native-grid")
public record OkxNativeGridProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean liveActionEnabled
) {
    public boolean executionArmed() {
        return enabled && liveActionEnabled;
    }
}
