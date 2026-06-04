package com.agora.config.properties;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** #378 — Delivery fee calculation params (3 keys, 1 class). */
@Validated
@ConfigurationProperties(prefix = "delivery.fee")
public record DeliveryFeeProperties(
        @DefaultValue("60.0") @PositiveOrZero double base,
        @DefaultValue("15.0") @PositiveOrZero double perKm,
        @DefaultValue("80.0") @PositiveOrZero double min
) {}
