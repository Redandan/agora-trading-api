package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — Digital order timeout / cleanup config (5 keys, 2 classes).
 *
 * <p>Excludes encryption-key / encryption-salt — those stay as @Value in
 * EncryptedStringConverter (JPA AttributeConverter context, sensitive).
 */
@Validated
@ConfigurationProperties(prefix = "digital-order")
public record DigitalOrderProperties(
        @DefaultValue("90") @Positive int proofRetentionDays,
        @DefaultValue("true") boolean proofCleanupEnabled,
        @DefaultValue("72") @Positive int purchaseTimeoutHours,
        @DefaultValue("7") @Positive int confirmTimeoutDays,
        @DefaultValue("true") boolean timeoutSchedulerEnabled
) {
}
