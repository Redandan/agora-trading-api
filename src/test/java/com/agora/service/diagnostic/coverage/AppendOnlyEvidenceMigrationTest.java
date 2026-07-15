package com.agora.service.diagnostic.coverage;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AppendOnlyEvidenceMigrationTest {

    @Test
    void migrationIsCreateOnlyAndContainsAllAppendOnlyEvidenceTables() throws Exception {
        String sql = migration();
        String normalized = sql.toLowerCase(Locale.ROOT);

        assertThat(normalized).contains(
                "create table `executable_quote_snapshot`",
                "create table `fill_fee_ledger`",
                "create table `funding_bill_ledger`",
                "create table `margin_snapshot`");
        assertThat(normalized).doesNotContainPattern("\\b(alter|drop|delete|update|truncate|replace)\\b");
        assertThat(normalized).doesNotContain("foreign key");
    }

    @Test
    void everyTableCarriesFailClosedProvenanceAndGapFields() throws Exception {
        String sql = migration().toLowerCase(Locale.ROOT);

        assertThat(occurrences(sql, "`dedupe_key` char(64) not null")).isEqualTo(4);
        assertThat(occurrences(sql, "`event_at` datetime(6) not null")).isEqualTo(4);
        assertThat(occurrences(sql, "`provider_at` datetime(6) not null")).isEqualTo(4);
        assertThat(occurrences(sql, "`received_at` datetime(6) not null")).isEqualTo(4);
        assertThat(occurrences(sql, "`ingested_at` datetime(6) not null")).isEqualTo(4);
        assertThat(occurrences(sql, "`raw_payload_sha256` char(64) not null")).isEqualTo(4);
        assertThat(occurrences(sql, "`provider_cursor` varchar(255) default null")).isEqualTo(4);
        assertThat(occurrences(sql, "`provider_page_key` varchar(128) default null")).isEqualTo(4);
        assertThat(occurrences(sql, "`gap_manifest_id` char(64) default null")).isEqualTo(4);
        assertThat(occurrences(sql, "'forward','historical_backfill'")).isEqualTo(4);
        assertThat(occurrences(sql, "trading_evidence_long")).isEqualTo(4);
    }

    @Test
    void signedLedgerSemanticsArePinnedByConstraints() throws Exception {
        String sql = migration();

        assertThat(sql).contains(
                "`signed_fee_amount` decimal(30,12) NOT NULL",
                "`fee_sign_semantic` = 'COST_NEGATIVE_REBATE_POSITIVE'",
                "`signed_funding_amount` decimal(30,12) NOT NULL",
                "`funding_sign_semantic` = 'PAID_NEGATIVE_RECEIVED_POSITIVE'");
    }

    private String migration() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V2__append_only_execution_evidence.sql")) {
            assertThat(input).as("V2 migration resource").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private long occurrences(String value, String needle) {
        return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1L;
    }
}
