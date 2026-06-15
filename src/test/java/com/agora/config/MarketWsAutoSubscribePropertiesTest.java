package com.agora.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketWsAutoSubscribePropertiesTest {

    @Test
    void emptyProviderAllowlistKeepsLegacyAllProvidersBehavior() {
        MarketWsAutoSubscribeProperties properties = new MarketWsAutoSubscribeProperties();

        assertThat(properties.normalizedProviders()).isEmpty();
        assertThat(properties.isProviderEnabled("binance")).isTrue();
        assertThat(properties.isProviderEnabled("okx")).isTrue();
    }

    @Test
    void configuredProviderAllowlistIsCaseInsensitiveAndTrimmed() {
        MarketWsAutoSubscribeProperties properties = new MarketWsAutoSubscribeProperties();
        properties.setProviders(List.of(" OKX ", " "));

        assertThat(properties.normalizedProviders()).containsExactlyInAnyOrder("okx");
        assertThat(properties.isProviderEnabled("okx")).isTrue();
        assertThat(properties.isProviderEnabled("OKX")).isTrue();
        assertThat(properties.isProviderEnabled("binance")).isFalse();
    }
}
