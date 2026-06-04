package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — Flutter desktop app deployment config (3 keys, 2 classes).
 *
 * <p>Nested {@link Security} maps {@code flutter.deployment.security.*}.
 */
@Validated
@ConfigurationProperties(prefix = "flutter.deployment")
public record FlutterDeploymentProperties(
        @DefaultValue("windows-app/") @NotBlank String filePrefix,
        @DefaultValue("windows") @NotBlank String defaultPlatform,
        @DefaultValue Security security
) {
    public record Security(@DefaultValue("32") @Positive int tokenLength) {}
}
