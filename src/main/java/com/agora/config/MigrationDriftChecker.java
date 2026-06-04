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
 * Startup sanity check: repo V*.sql count vs db_migration_history row count.
 * WARN-only; never crashes the app. Disable via
 * meta-control.migration-drift-check.enabled=false.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "meta-control.migration-drift-check.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class MigrationDriftChecker {

    private static final Pattern MIGRATION_FILE = Pattern.compile("^V[^_]+__.+\\.sql$");

    private final JdbcTemplate jdbcTemplate;

    @Value("${meta-control.migration-drift-check.directory:db/migrations}")
    private String migrationDir;

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
                dbCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM db_migration_history", Integer.class);
            } catch (Exception e) {
                log.warn("MigrationDriftChecker: db_migration_history unavailable ({}). "
                        + "Has V040 been applied? Skipping drift check.", e.getMessage());
                return;
            }

            int db = dbCount == null ? 0 : dbCount;
            if (fileCount == db) {
                log.info("MigrationDriftChecker OK: {} V*.sql files, {} history rows", fileCount, db);
            } else {
                log.warn("MigrationDriftChecker MISMATCH: repo has {} V*.sql files "
                                + "but db_migration_history has {} rows. "
                                + "Reconcile with: `ls {}/V*.sql` vs `SELECT version FROM db_migration_history ORDER BY version;`",
                        fileCount, db, migrationDir);
            }
        } catch (Exception e) {
            log.warn("MigrationDriftChecker: unexpected error ({}), continuing startup", e.toString());
        }
    }
}
