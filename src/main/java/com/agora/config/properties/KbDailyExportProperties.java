package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** #378 — KB daily export scheduler (3 keys, 1 class). */
@Validated
@ConfigurationProperties(prefix = "kb.daily-export")
public record KbDailyExportProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("/home/ubuntu/AgoraMarketAPI/kb-snapshots") @NotBlank String exportPath,
        @DefaultValue("true") boolean gitCommit
) {}
