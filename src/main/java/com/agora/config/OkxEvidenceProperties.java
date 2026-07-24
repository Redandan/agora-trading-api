package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Fail-closed switches for the isolated OKX read-only evidence path. */
@Data
@Configuration
@ConfigurationProperties(prefix = "trading.evidence.okx")
public class OkxEvidenceProperties {

    /** No scheduler is supplied by this package; this gate defaults to disabled. */
    private boolean collectorEnabled = false;

    /** Authenticated provider reads require a separate production authorization. */
    private boolean authenticatedIngestionEnabled = false;

}
