package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Poll OCI Console Announcements for tenancy-specific maintenance notices.
 *
 * <p>Public OCI status does not include customer-specific maintenance. This
 * notifier reads the account's Announcements API through instance principal
 * and sends TG only for MySQL/HeatWave maintenance-like notices.
 */
@Validated
@ConfigurationProperties(prefix = "oci.maintenance-notifier")
public record OciMaintenanceNotifierProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String compartmentId,
        @DefaultValue("900000") @Positive long fixedDelayMs,
        @DefaultValue("180000") @Positive long initialDelayMs,
        @DefaultValue("50") @Positive int pageLimit,
        @DefaultValue("48") @Positive long lookbackHours,
        @DefaultValue("336") @Positive long lookaheadHours,
        @DefaultValue("10080") @Positive long dedupTtlMinutes,
        @DefaultValue("Oracle HeatWave MySQL,Oracle Cloud Infrastructure HeatWave MySQL,MySQL")
        String serviceKeywords,
        @DefaultValue("Maintenance,Planned Change,Emergency Maintenance,urgent maintenance")
        String summaryKeywords
) {
}
