package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "cloudflare.turnstile")
public record TurnstileProperties(
        @DefaultValue("") String secretKey,
        @DefaultValue("") String siteKey
) {}
