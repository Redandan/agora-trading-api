package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "auth.code")
public record AuthCodeProperties(
        @DefaultValue("300") @Positive int expiration,
        @DefaultValue("1000") @Positive int maxCodes
) {}
