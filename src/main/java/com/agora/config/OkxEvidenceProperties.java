package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

/** Fail-closed switches for the isolated OKX read-only evidence path. */
@Data
@Configuration
@ConfigurationProperties(prefix = "trading.evidence.okx")
public class OkxEvidenceProperties {

    /** No scheduler is supplied by this package; this gate defaults to disabled. */
    private boolean collectorEnabled = false;

    /** Authenticated provider reads require a separate production authorization. */
    private boolean authenticatedIngestionEnabled = false;

    /** Creates the one-shot bean only under a separately reviewed configuration. */
    private boolean exactFillOneShotEnabled = false;

    private String exactFillRunId;
    private String accountRefHash;
    private String instrumentId = "BTC-USDT";
    private String instrumentType = "SPOT";
    private int exactFillPageLimit = 100;
    private int exactFillMaxPages = 100;
    /** Mandatory operator-supplied forward-only cohort boundary; there is no default window. */
    private Instant exactFillEffectiveFrom;
    private List<ExactFillBinding> exactFillBindings = new ArrayList<>();

    @Data
    public static class ExactFillBinding {
        private String orderId;
        private String cohortId;
        private Long runtimeDecisionId;
        private Long liveSignalId;
        private Instant orderCreatedAt;
        private boolean ocoRequired;
        private String intendedChildOrderId;
        private String actualChildOrderId;
    }
}
