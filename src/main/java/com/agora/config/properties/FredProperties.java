package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 1 key shared by FredEconomicService + IndicatorHistoryBackfillService. */
@Validated
@ConfigurationProperties(prefix = "external.fred")
public record FredProperties(
        @DefaultValue("") String apiKey
) {}
