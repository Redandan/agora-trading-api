package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — TG mini-app market URL config (5 AI skill consumers).
 *
 * <p>3 unique keys deduplicated (base-url was @Value'd in 5 different
 * skill classes pre-migration).
 */
@Validated
@ConfigurationProperties(prefix = "app.market")
public record AppMarketProperties(

        /** Base mini-app URL. */
        @DefaultValue("https://t.me/agora_login_bot/store") @NotBlank String baseUrl,

        /** Product detail URL prefix (caller appends product id). */
        @DefaultValue("https://t.me/agora_login_bot/store?startapp=product_") @NotBlank String productUrl,

        /** Store detail URL prefix (caller appends store id). */
        @DefaultValue("https://t.me/agora_login_bot/store?startapp=seller_") @NotBlank String storeUrl
) {
}
