package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Nested {@code coinalyze.api-key} via {@link Coinalyze}. */
@Validated
@ConfigurationProperties(prefix = "trading.market-data")
public record MarketDataProperties(@DefaultValue Coinalyze coinalyze) {
    public record Coinalyze(@DefaultValue("") String apiKey) {}
}
