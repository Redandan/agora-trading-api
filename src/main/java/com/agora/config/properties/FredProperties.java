package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** FRED API configuration used by {@code FredEconomicService}. */
@Validated
@ConfigurationProperties(prefix = "external.fred")
public record FredProperties(
        @DefaultValue("") String apiKey
) {}
