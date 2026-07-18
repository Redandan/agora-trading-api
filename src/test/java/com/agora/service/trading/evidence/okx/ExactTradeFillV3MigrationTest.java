package com.agora.service.trading.evidence.okx;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExactTradeFillV3MigrationTest {
    @Test
    void freshV3AndV2UpgradeBothSucceedWithoutChangingV2Rows() throws Exception {
        try (Connection fresh = dataSource("fresh").getConnection()) {
            apply(fresh, "V3__immutable_trade_fill_evidence.sql");
            assertThat(tableCount(fresh, "IMMUTABLE_TRADE_FILL")).isEqualTo(1);
        }
        try (Connection upgrade = dataSource("upgrade").getConnection()) {
            apply(upgrade, "V2__append_only_execution_evidence.sql");
            upgrade.createStatement().executeUpdate("""
                    INSERT INTO fill_fee_ledger
                    (dedupe_key,provider,account_ref_hash,symbol,instrument_type,order_id,trade_id,event_at,
                     provider_at,received_at,ingested_at,signed_fee_amount,fee_currency,fee_sign_semantic,
                     source_mode,raw_payload_sha256,retention_class)
                    VALUES ('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','okx',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','BTC-USDT','SPOT',
                    'o','t',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,-0.1,'USDT',
                    'COST_NEGATIVE_REBATE_POSITIVE','FORWARD',
                    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','TRADING_EVIDENCE_LONG')
                    """);
            apply(upgrade, "V3__immutable_trade_fill_evidence.sql");
            try (var rs = upgrade.createStatement().executeQuery("SELECT COUNT(*) FROM fill_fee_ledger")) {
                assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void migrationIsStrictlyAdditive() throws Exception {
        String sql = resource("V3__immutable_trade_fill_evidence.sql").toLowerCase(Locale.ROOT);
        assertThat(sql).contains("create table if not exists exact_trade_fill_collection_run",
                "create table if not exists exact_trade_fill_page_manifest",
                "create table if not exists immutable_trade_fill");
        assertThat(sql).doesNotContainPattern("(?m)^\\s*(alter|update|delete|drop|replace|truncate)\\b");
        assertThat(sql).doesNotContain("insert select");
    }

    private static DriverManagerDataSource dataSource(String prefix) {
        return new DriverManagerDataSource("jdbc:h2:mem:" + prefix + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1", "sa", "");
    }
    private static void apply(Connection c, String name) throws Exception {
        ScriptUtils.executeSqlScript(c, new ByteArrayResource(resource(name).getBytes(StandardCharsets.UTF_8)));
    }
    private static String resource(String name) throws Exception {
        try (InputStream in = Objects.requireNonNull(ExactTradeFillV3MigrationTest.class.getClassLoader()
                .getResourceAsStream("db/migration/" + name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static int tableCount(Connection c, String table) throws Exception {
        try (var rs = c.getMetaData().getTables(null, null, table.toLowerCase(Locale.ROOT), new String[]{"TABLE"})) {
            int count = 0; while (rs.next()) count++; return count;
        }
    }
}
