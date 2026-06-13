package com.agora.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Startup sanity check: repo V*.sql count vs Flyway schema history row count.
 * WARN-only; never crashes the app. Enable after a trading migration baseline
 * exists via meta-control.migration-drift-check.enabled=true.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "meta-control.migration-drift-check.enabled",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
public class MigrationDriftChecker {

    private static final Pattern MIGRATION_FILE = Pattern.compile("^V[^_]+__.+\\.sql$");
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final JdbcTemplate jdbcTemplate;

    @Value("${meta-control.migration-drift-check.directory:src/main/resources/db/migration}")
    private String migrationDir;

    @Value("${meta-control.migration-drift-check.table:trading_flyway_schema_history}")
    private String historyTable;

    @PostConstruct
    public void check() {
        try {
            Path dir = Paths.get(migrationDir);
            if (!Files.isDirectory(dir)) {
                log.warn("MigrationDriftChecker: directory '{}' not found (cwd={}), skipping check",
                        migrationDir, Paths.get("").toAbsolutePath());
                return;
            }

            long fileCount;
            try (Stream<Path> files = Files.list(dir)) {
                fileCount = files
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> MIGRATION_FILE.matcher(name).matches())
                        .count();
            }

            Integer dbCount;
            try {
                if (!SQL_IDENTIFIER.matcher(historyTable).matches()) {
                    log.warn("MigrationDriftChecker: invalid history table name '{}', skipping drift check",
                            historyTable);
                    return;
                }
                dbCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + historyTable + " WHERE success = 1", Integer.class);
            } catch (Exception e) {
                log.warn("MigrationDriftChecker: {} unavailable ({}). Skipping drift check.",
                        historyTable, e.getMessage());
                return;
            }

            int db = dbCount == null ? 0 : dbCount;
            if (fileCount == db) {
                log.info("MigrationDriftChecker OK: {} V*.sql files, {} history rows", fileCount, db);
            } else {
                log.warn("MigrationDriftChecker MISMATCH: repo has {} V*.sql files "
                                + "but {} has {} successful rows. "
                                + "Reconcile with: `ls {}/V*.sql` vs `SELECT version FROM {} WHERE success = 1 ORDER BY installed_rank;`",
                        fileCount, historyTable, db, migrationDir, historyTable);
            }
        } catch (Exception e) {
            log.warn("MigrationDriftChecker: unexpected error ({}), continuing startup", e.toString());
        }
    }
}
