package com.agora.service.impl;

import com.agora.config.AgoraMarketExchangeRateProperties;
import com.agora.dto.ExchangeRateInfo;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgoraMarketExchangeRateServiceImplTest {

    private final StaticExchangeRateServiceImpl fallback = new StaticExchangeRateServiceImpl();

    @Test
    void usesStaticFallbackWhenInternalApiKeyIsMissing() {
        AgoraMarketExchangeRateServiceImpl service = new AgoraMarketExchangeRateServiceImpl(
                new AgoraMarketExchangeRateProperties("http://127.0.0.1:1", "", Duration.ofMillis(100)),
                fallback
        );

        ExchangeRateInfo rate = service.getRateByCurrency("TWD");

        assertThat(rate.getFromCurrency()).isEqualTo("USDT");
        assertThat(rate.getToCurrency()).isEqualTo("TWD");
        assertThat(rate.getRate()).isEqualByComparingTo("32.00");
    }

    @Test
    void fetchesRatesFromAgoraMarketInternalApi() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            [
                              {
                                "fromCurrency": "USDT",
                                "toCurrency": "TWD",
                                "rate": 31.44,
                                "symbol": "NT$",
                                "currencyName": "新台幣",
                                "lastUpdated": "2026-06-04T15:13:50.938819754"
                              }
                            ]
                            """));
            server.start();

            AgoraMarketExchangeRateServiceImpl service = new AgoraMarketExchangeRateServiceImpl(
                    new AgoraMarketExchangeRateProperties(
                            server.url("/").toString(),
                            "test-key",
                            Duration.ofSeconds(1)),
                    fallback
            );

            List<ExchangeRateInfo> rates = service.getAllUsdtRates();

            assertThat(rates).hasSize(1);
            assertThat(rates.getFirst().getToCurrency()).isEqualTo("TWD");
            assertThat(rates.getFirst().getRate()).isEqualByComparingTo("31.44");

            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/api/internal/exchange-rates/usdt");
            assertThat(request.getHeader("X-Internal-Api-Key")).isEqualTo("test-key");
        }
    }

    @Test
    void fallsBackWhenAgoraMarketInternalApiFails() throws IOException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401));
            server.start();

            AgoraMarketExchangeRateServiceImpl service = new AgoraMarketExchangeRateServiceImpl(
                    new AgoraMarketExchangeRateProperties(
                            server.url("/").toString(),
                            "bad-key",
                            Duration.ofSeconds(1)),
                    fallback
            );

            ExchangeRateInfo rate = service.getRateByCurrency("TWD");

            assertThat(rate.getToCurrency()).isEqualTo("TWD");
            assertThat(rate.getRate()).isEqualByComparingTo("32.00");
        }
    }
}
