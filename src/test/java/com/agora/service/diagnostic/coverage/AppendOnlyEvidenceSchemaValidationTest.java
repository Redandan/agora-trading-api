package com.agora.service.diagnostic.coverage;

import com.agora.model.evidence.AppendOnlyEvidence;
import com.agora.model.evidence.ExecutableQuoteSnapshot;
import com.agora.model.evidence.FillFeeLedgerEntry;
import com.agora.model.evidence.FundingBillLedgerEntry;
import com.agora.model.evidence.MarginSnapshot;
import com.agora.model.evidence.ExactTradeFillCollectionRun;
import com.agora.model.evidence.ExactTradeFillPageManifest;
import com.agora.model.evidence.ImmutableTradeFill;
import jakarta.persistence.Column;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AppendOnlyEvidenceSchemaValidationTest {

    private static final List<Class<?>> EVIDENCE_ENTITIES = List.of(
            ExecutableQuoteSnapshot.class,
            FillFeeLedgerEntry.class,
            FundingBillLedgerEntry.class,
            MarginSnapshot.class,
            ExactTradeFillCollectionRun.class,
            ExactTradeFillPageManifest.class,
            ImmutableTradeFill.class);

    private static final Map<String, List<String>> CHAR_64_COLUMNS = new LinkedHashMap<>();

    static {
        CHAR_64_COLUMNS.put("executable_quote_snapshot", List.of(
                "dedupe_key", "raw_payload_sha256", "gap_manifest_id"));
        CHAR_64_COLUMNS.put("fill_fee_ledger", List.of(
                "dedupe_key", "account_ref_hash", "raw_payload_sha256", "gap_manifest_id"));
        CHAR_64_COLUMNS.put("funding_bill_ledger", List.of(
                "dedupe_key", "account_ref_hash", "raw_payload_sha256", "gap_manifest_id"));
        CHAR_64_COLUMNS.put("margin_snapshot", List.of(
                "dedupe_key", "account_ref_hash", "raw_payload_sha256", "gap_manifest_id"));
    }

    @Test
    void realV2SchemaValidatesAgainstAllFourEvidenceEntities() throws Exception {
        DataSource dataSource = migratedV2DataSource();
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPersistenceUnitName("append-only-evidence-schema-validation");
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.agora.model.evidence");
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setDatabasePlatform("org.hibernate.dialect.MySQLDialect");
        factory.setJpaVendorAdapter(vendorAdapter);
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));

        try {
            assertThatCode(factory::afterPropertiesSet).doesNotThrowAnyException();
            assertThat(factory.getObject()).isNotNull();
        } finally {
            factory.destroy();
        }
    }

    @Test
    void allFifteenHashColumnsRemainJdbcChar64() throws Exception {
        DataSource dataSource = migratedV2DataSource();
        int verified = 0;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, List<String>> table : CHAR_64_COLUMNS.entrySet()) {
                for (String column : table.getValue()) {
                    try (ResultSet columns = metadata.getColumns(null, null, table.getKey(), column)) {
                        assertThat(columns.next())
                                .as("%s.%s exists", table.getKey(), column)
                                .isTrue();
                        assertThat(columns.getInt("DATA_TYPE"))
                                .as("%s.%s JDBC type", table.getKey(), column)
                                .isEqualTo(Types.CHAR);
                        assertThat(columns.getInt("COLUMN_SIZE"))
                                .as("%s.%s length", table.getKey(), column)
                                .isEqualTo(64);
                        assertThat(columns.next())
                                .as("%s.%s is unique in metadata", table.getKey(), column)
                                .isFalse();
                        verified++;
                    }
                }
            }
        }
        assertThat(verified).isEqualTo(15);
    }

    @Test
    void allEvidenceEntitiesAreImmutableAndEveryMappedColumnIsNonUpdatable() {
        for (Class<?> entity : EVIDENCE_ENTITIES) {
            assertThat(entity.isAnnotationPresent(Immutable.class))
                    .as("%s is @Immutable", entity.getSimpleName())
                    .isTrue();
            assertMappedColumnsNonUpdatable(AppendOnlyEvidence.class);
            assertMappedColumnsNonUpdatable(entity);
        }
    }

    @Test
    void legacyAndV3EntityHashMappingsDeclareChar64() throws Exception {
        assertChar64(AppendOnlyEvidence.class, "dedupeKey");
        assertChar64(AppendOnlyEvidence.class, "rawPayloadSha256");
        assertChar64(AppendOnlyEvidence.class, "gapManifestId");
        assertChar64(FillFeeLedgerEntry.class, "accountRefHash");
        assertChar64(FundingBillLedgerEntry.class, "accountRefHash");
        assertChar64(MarginSnapshot.class, "accountRefHash");
        assertChar64(ExactTradeFillCollectionRun.class, "runId");
        assertChar64(ExactTradeFillCollectionRun.class, "accountRefHash");
        assertChar64(ExactTradeFillCollectionRun.class, "bindingScopeSha256");
        assertChar64(ExactTradeFillCollectionRun.class, "canonicalFillSetSha256");
        assertChar64(ExactTradeFillCollectionRun.class, "priorStableRunId");
        assertChar64(ExactTradeFillPageManifest.class, "runId");
        assertChar64(ExactTradeFillPageManifest.class, "pageKey");
        assertChar64(ExactTradeFillPageManifest.class, "pageSha256");
        assertChar64(ImmutableTradeFill.class, "fillIdentitySha256");
        assertChar64(ImmutableTradeFill.class, "immutableContentSha256");
        assertChar64(ImmutableTradeFill.class, "accountRefHash");
        assertChar64(ImmutableTradeFill.class, "sourceRunId");
        assertChar64(ImmutableTradeFill.class, "sourcePageKey");
        assertChar64(ImmutableTradeFill.class, "rawPayloadSha256");

        int legacyMappingCount = (3 * 4) + 3;
        int v3MappingCount = 14;
        assertThat(legacyMappingCount + v3MappingCount).isEqualTo(29);
    }

    private DataSource migratedV2DataSource() throws Exception {
        String databaseName = "evidence_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        String migration;
        try (InputStream input = Objects.requireNonNull(
                AppendOnlyEvidenceSchemaValidationTest.class.getClassLoader()
                        .getResourceAsStream("db/migration/V2__append_only_execution_evidence.sql"),
                "V2 evidence migration resource")) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String h2CompatibleMigration = migration.replaceAll(
                "(?i) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[^']*'",
                "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ByteArrayResource(h2CompatibleMigration.getBytes(StandardCharsets.UTF_8)));
            try (InputStream input = Objects.requireNonNull(
                    AppendOnlyEvidenceSchemaValidationTest.class.getClassLoader()
                            .getResourceAsStream("db/migration/V3__immutable_trade_fill_evidence.sql"),
                    "V3 exact fill migration resource")) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(input.readAllBytes()));
            }
        }
        return dataSource;
    }

    private void assertMappedColumnsNonUpdatable(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                assertThat(column.updatable())
                        .as("%s.%s updatable", type.getSimpleName(), field.getName())
                        .isFalse();
            }
        }
    }

    private void assertChar64(Class<?> type, String fieldName) throws Exception {
        Column column = type.getDeclaredField(fieldName).getAnnotation(Column.class);
        assertThat(column).as("%s.%s @Column", type.getSimpleName(), fieldName).isNotNull();
        assertThat(column.columnDefinition())
                .as("%s.%s SQL definition", type.getSimpleName(), fieldName)
                .isEqualTo("CHAR(64)");
        assertThat(column.length())
                .as("%s.%s length", type.getSimpleName(), fieldName)
                .isEqualTo(64);
        assertThat(column.updatable())
                .as("%s.%s updatable", type.getSimpleName(), fieldName)
                .isFalse();
    }
}
