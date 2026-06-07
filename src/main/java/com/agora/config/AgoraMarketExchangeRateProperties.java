package com.agora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agora-market")
public record AgoraMarketExchangeRateProperties(
        String baseUrl,
        String internalApiKey,
        Duration timeoutMs
) {

    public AgoraMarketExchangeRateProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:8080";
        }
        if (timeoutMs == null) {
            timeoutMs = Duration.ofSeconds(3);
        }
    }

    public boolean isConfigured() {
        return internalApiKey != null && !internalApiKey.isBlank();
    }

    public Duration timeout() {
        return timeoutMs;
    }
}
