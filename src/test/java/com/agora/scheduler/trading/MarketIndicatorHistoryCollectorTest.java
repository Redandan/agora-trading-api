package com.agora.scheduler.trading;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketIndicatorHistoryCollectorTest {

    @Test
    void oiDeltaRequiresSameKnownProvider() {
        assertThat(MarketIndicatorHistoryCollector.sameOiProvider(
                "OKX_PUBLIC_SWAP_FALLBACK", "OKX_PUBLIC_SWAP_FALLBACK")).isTrue();
        assertThat(MarketIndicatorHistoryCollector.sameOiProvider(
                "BINANCE_FUTURES", "OKX_PUBLIC_SWAP_FALLBACK")).isFalse();
        assertThat(MarketIndicatorHistoryCollector.sameOiProvider(
                "LEGACY_OR_UNKNOWN_PROVIDER", "OKX_PUBLIC_SWAP_FALLBACK")).isFalse();
        assertThat(MarketIndicatorHistoryCollector.sameOiProvider(
                null, "OKX_PUBLIC_SWAP_FALLBACK")).isFalse();
    }
}
