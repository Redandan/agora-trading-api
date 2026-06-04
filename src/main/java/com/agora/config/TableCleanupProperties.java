package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration-driven table retention policies.
 * Each entry defines one table to auto-clean nightly.
 * No Java code change needed to add/modify retention — just update application.yml.
 *
 * Example yml:
 * <pre>
 * nightly-cleanup:
 *   tables:
 *     - table: bt_live_signal
 *       column: created_at
 *       retention-days: 90
 *       batch-size: 5000
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "nightly-cleanup")
@Data
public class TableCleanupProperties {

    private List<TableRule> tables = List.of();

    @Data
    public static class TableRule {
        /** Target table name */
        private String table;
        /** Timestamp column to filter on */
        private String column;
        /** Rows older than this many days will be deleted */
        private int retentionDays = 90;
        /** Max rows to delete per nightly run (prevents lock contention) */
        private int batchSize = 2000;
    }
}
