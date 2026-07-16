package com.agora.repository.trading.evidence;

import com.agora.service.diagnostic.coverage.CoverageProfiler;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.Provenance;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.QuoteAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.Timestamps;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcOkxEvidenceAppendRepositoryTest {

    @Test
    void realV2SchemaAcceptsInsertAndIdenticalDuplicateWithoutOverwrite() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(migratedV2DataSource());
        JdbcOkxEvidenceAppendRepository repository = new JdbcOkxEvidenceAppendRepository(jdbc);
        QuoteAppend command = quote();

        assertThat(repository.append(command)).isEqualTo(OkxEvidenceAppendRepository.AppendResult.APPENDED);
        assertThat(repository.append(command)).isEqualTo(OkxEvidenceAppendRepository.AppendResult.DUPLICATE_IDENTICAL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM executable_quote_snapshot", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT raw_payload_sha256 FROM executable_quote_snapshot WHERE dedupe_key = ?",
                String.class, command.dedupeKey())).isEqualTo(command.provenance().rawPayloadSha256());
    }

    @Test
    void publicAppendContractHasNoMutationOrUpsertSurface() {
        List<String> methods = Arrays.stream(OkxEvidenceAppendRepository.class.getMethods())
                .map(Method::getName).toList();

        assertThat(methods).containsExactly("append");
        assertThat(methods).noneMatch(name -> name.matches("(?i).*(update|delete|save|upsert|replace).*"));
    }

    private QuoteAppend quote() {
        Instant event = Instant.parse("2026-07-15T00:00:00Z");
        Timestamps timestamps = new Timestamps(event, event, event.plusMillis(100), event.plusMillis(200),
                event.plusMillis(300), event.plusMillis(400));
        String dedupe = "a".repeat(64);
        String rawHash = "b".repeat(64);
        Provenance provenance = new Provenance("okx", CoverageProfiler.Provenance.FORWARD, rawHash,
                "cursor-1", "page-1", null, null, null, null,
                "TRADING_EVIDENCE_LONG", null);
        CoverageProfiler.CoverageRecord coverage = new CoverageProfiler.CoverageRecord(
                dedupe, event, event, event.plusMillis(100), event.plusMillis(400), event.plusMillis(300),
                "okx", CoverageProfiler.Provenance.FORWARD,
                CoverageProfiler.DataKind.DEPTH, CoverageProfiler.Usage.EXECUTABLE_DEPTH);
        return new QuoteAppend(dedupe, timestamps, provenance, "BTC-USDT", "SPOT", "DEPTH",
                new BigDecimal("50000"), new BigDecimal("1"), new BigDecimal("50001"),
                new BigDecimal("1"), "{\"bids\":[[\"50000\",\"1\"]],\"asks\":[[\"50001\",\"1\"]]}",
                "9001", coverage);
    }

    private DataSource migratedV2DataSource() throws Exception {
        String databaseName = "evidence_append_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        String migration;
        try (InputStream input = Objects.requireNonNull(getClass().getClassLoader()
                .getResourceAsStream("db/migration/V2__append_only_execution_evidence.sql"))) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String h2CompatibleMigration = migration.replaceAll(
                "(?i) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[^']*'", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ByteArrayResource(h2CompatibleMigration.getBytes(StandardCharsets.UTF_8)));
        }
        return dataSource;
    }
}
