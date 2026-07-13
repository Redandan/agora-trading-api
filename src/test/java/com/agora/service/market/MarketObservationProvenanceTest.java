package com.agora.service.market;

import com.agora.service.trading.OkxTradingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MarketObservationProvenanceTest {

    @Test
    void openInterestPrefersProviderTimestampOverLocalReceiptTime() {
        Instant providerTime = Instant.parse("2026-07-12T11:01:00Z");
        LocalDateTime observedAt = LocalDateTime.of(2026, 7, 12, 11, 1, 5);
        BinanceFuturesService.OpenInterestObservation observation =
                new BinanceFuturesService.OpenInterestObservation(
                        123.45, "OKX_PUBLIC_SWAP_FALLBACK", observedAt,
                        providerTime.toEpochMilli());

        assertThat(observation.effectiveCapturedAt())
                .isEqualTo(LocalDateTime.ofInstant(providerTime, ZoneOffset.UTC));
    }

    @Test
    void fundingPrefersProviderTimestampOverLocalReceiptTime() {
        Instant providerTime = Instant.parse("2026-07-12T11:01:00Z");
        OkxTradingService.FundingRateObservation observation =
                new OkxTradingService.FundingRateObservation(
                        0.00005, "OKX_PUBLIC_FUNDING_RATE",
                        providerTime.plusSeconds(5), providerTime.toEpochMilli());

        assertThat(observation.effectiveCapturedAt()).isEqualTo(providerTime);
    }

    @Test
    void observationsFallBackToReceiptTimeWhenProviderTimestampIsMissing() {
        LocalDateTime oiObservedAt = LocalDateTime.of(2026, 7, 12, 11, 1, 5);
        Instant fundingObservedAt = Instant.parse("2026-07-12T11:01:05Z");

        assertThat(new BinanceFuturesService.OpenInterestObservation(
                123.45, "OKX_PUBLIC_SWAP_FALLBACK", oiObservedAt, null)
                .effectiveCapturedAt()).isEqualTo(oiObservedAt);
        assertThat(new OkxTradingService.FundingRateObservation(
                0.00005, "OKX_PUBLIC_FUNDING_RATE", fundingObservedAt, null)
                .effectiveCapturedAt()).isEqualTo(fundingObservedAt);
    }
}
