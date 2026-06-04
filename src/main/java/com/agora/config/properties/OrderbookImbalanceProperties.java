package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 1 key shared between LongAiFilter + ShortAiFilter. */
@Validated
@ConfigurationProperties(prefix = "trading.orderbook-imbalance")
public record OrderbookImbalanceProperties(
        @DefaultValue("0.5") double threshold
) {}
