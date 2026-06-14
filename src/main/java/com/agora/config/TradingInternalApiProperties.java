package com.agora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.internal")
public record TradingInternalApiProperties(String apiKey) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
