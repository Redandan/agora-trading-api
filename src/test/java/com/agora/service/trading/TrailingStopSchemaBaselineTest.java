package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrailingStopSchemaBaselineTest {

    @Test
    void sharedDbBaselineKeepsSingleReviewedV1Migration() throws Exception {
        URI migrationUri = getClass().getClassLoader()
                .getResource("db/migration")
                .toURI();
        List<String> migrations;
        try (var stream = Files.list(Path.of(migrationUri))) {
            migrations = stream
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }

        assertThat(migrations).containsExactly("V1__baseline.sql");

        String baseline = readBaseline();
        assertThat(baseline).doesNotContain("DROP TABLE");
        assertThat(baseline).doesNotContain("CREATE TABLE `flyway_schema_history`");
        assertThat(baseline).doesNotContain("CREATE TABLE `trading_flyway_schema_history`");
    }

    @Test
    void trailingStopColumnsStayMappedInEntityAndFlywayBaseline() throws Exception {
        assertColumn("trailingState", "trailing_state", 20);
        assertColumn("trailingAtr", "trailing_atr", 0);
        assertColumn("trailingHigh", "trailing_high", 0);
        assertColumn("trailingLastTransitionAt", "trailing_last_transition_at", 0);

        String baseline = readBaseline();
        assertThat(baseline).contains("CREATE TABLE `bt_live_signal`");
        assertThat(baseline).contains("`trailing_state` varchar(20) NOT NULL DEFAULT 'ENTERED'");
        assertThat(baseline).contains("`trailing_atr` decimal(12,6) DEFAULT NULL");
        assertThat(baseline).contains("`trailing_high` decimal(20,8) DEFAULT NULL");
        assertThat(baseline).contains("`trailing_last_transition_at` datetime DEFAULT NULL");
    }

    private void assertColumn(String fieldName, String columnName, int length) throws Exception {
        Field field = BtLiveSignal.class.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo(columnName);
        if (length > 0) {
            assertThat(column.length()).isEqualTo(length);
        }
    }

    private String readBaseline() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V1__baseline.sql")) {
            assertThat(input).as("V1__baseline.sql resource").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
