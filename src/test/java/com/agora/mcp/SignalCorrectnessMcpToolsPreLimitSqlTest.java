package com.agora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class SignalCorrectnessMcpToolsPreLimitSqlTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void runtimePredicateUsesJoinedSideAndSnakeCaseIntentBeforeLimit() throws Exception {
        try (Connection connection = connection("runtime")) {
            createRuntimeTables(connection);
            LocalDateTime base = LocalDateTime.parse("2026-07-18T00:00:00");
            try (PreparedStatement live = connection.prepareStatement(
                    "INSERT INTO live_signal(id, side) VALUES (?, ?)");
                 PreparedStatement evidence = connection.prepareStatement("""
                         INSERT INTO runtime_evidence(
                           id, evidence_time, live_signal_id, side, selected_action, decision, signal_source,
                           terminal_blocker, suppression_reason, policy_mode, blocker_reason, intent_created,
                           policy_inputs_json, execution_preview_json, features_snapshot_json,
                           final_outcome, order_sent)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """)) {
                for (int i = 0; i < 500; i++) {
                    long id = 10_000L + i;
                    live.setLong(1, id);
                    live.setString(2, i % 2 == 0 ? "SELL" : "SHORT");
                    live.addBatch();
                    insertRuntime(evidence, id, base.plusSeconds(i + 1L), id, null,
                            "BLOCK", "BUY", "FILTER_BLOCK", "TradePlanQualityGate", 1, "{}");
                }
                live.executeBatch();
                evidence.executeBatch();
                insertRuntime(evidence, 9_999L, base, null, "LONG",
                        "BLOCK", "BUY", "FILTER_BLOCK", "TradePlanQualityGate", 0,
                        "{\"intent_created\":true}");
                evidence.executeBatch();
            }

            assertThat(runtimeIds(connection)).containsExactly(9_999L);
        }
    }

    @Test
    void auditPredicateUsesOutcomeAsSelectedActionAndProtectsOlderEligibleRowFromQuota() throws Exception {
        try (Connection connection = connection("audit")) {
            createAuditTables(connection);
            LocalDateTime base = LocalDateTime.parse("2026-07-18T00:00:00");
            try (PreparedStatement audit = connection.prepareStatement("""
                    INSERT INTO decision_audit(
                      id, event_time, live_signal_id, event_type, outcome, blocker, reason, context_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (int i = 0; i < 500; i++) {
                    insertAudit(audit, 20_000L + i, base.plusSeconds(i + 1L), "FILTER_BLOCK", "SELL",
                            "TradePlanQualityGate", "{\"decision\":\"BUY\",\"intentCreated\":true,\"side\":\"LONG\"}");
                }
                insertAudit(audit, 19_999L, base, "FILTER_BLOCK", "BUY",
                        "TradePlanQualityGate", "{\"intent_created\":true,\"side\":\"LONG\"}");
                audit.executeBatch();
            }

            assertThat(auditIds(connection)).containsExactly(19_999L);
        }
    }

    @Test
    void runtimePlaceholderBlockersDoNotConsumePreLimitQuota() throws Exception {
        try (Connection connection = connection("runtime_placeholders")) {
            createRuntimeTables(connection);
            LocalDateTime base = LocalDateTime.parse("2026-07-18T00:00:00");
            List<String> placeholders = List.of("NA", "NULL", "UNKNOWN", "NOT_APPLICABLE", "PENDING");
            try (PreparedStatement live = connection.prepareStatement(
                    "INSERT INTO live_signal(id, side) VALUES (?, ?)");
                 PreparedStatement evidence = connection.prepareStatement("""
                         INSERT INTO runtime_evidence(
                           id, evidence_time, live_signal_id, side, selected_action, decision, signal_source,
                           terminal_blocker, suppression_reason, policy_mode, blocker_reason, intent_created,
                           policy_inputs_json, execution_preview_json, features_snapshot_json,
                           final_outcome, order_sent)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """)) {
                for (int i = 0; i < 500; i++) {
                    long id = 30_000L + i;
                    live.setLong(1, id);
                    live.setString(2, "LONG");
                    live.addBatch();
                    insertRuntime(evidence, id, base.plusSeconds(i + 1L), id, "LONG",
                            "BUY", "BUY", "SIGNAL_BUY",
                            placeholders.get(i % placeholders.size()), 1, "{}");
                }
                live.executeBatch();
                evidence.executeBatch();
                try (Statement neutralizeOutcome = connection.createStatement()) {
                    neutralizeOutcome.executeUpdate(
                            "UPDATE runtime_evidence SET final_outcome='PENDING' WHERE id >= 30000");
                }
                insertRuntime(evidence, 29_999L, base, null, "LONG",
                        "BUY", "BUY", "SIGNAL_BUY", "TradePlanQualityGate", 1, "{}");
                evidence.executeBatch();
            }

            assertThat(runtimeIds(connection)).containsExactly(29_999L);
        }
    }

    @Test
    void auditPlaceholderBlockersDoNotConsumePreLimitQuota() throws Exception {
        try (Connection connection = connection("audit_placeholders")) {
            createAuditTables(connection);
            LocalDateTime base = LocalDateTime.parse("2026-07-18T00:00:00");
            List<String> placeholders = List.of("NA", "NULL", "UNKNOWN", "NOT_APPLICABLE", "PENDING");
            try (PreparedStatement audit = connection.prepareStatement("""
                    INSERT INTO decision_audit(
                      id, event_time, live_signal_id, event_type, outcome, blocker, reason, context_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (int i = 0; i < 500; i++) {
                    long id = 40_000L + i;
                    audit.setLong(1, id);
                    audit.setObject(2, base.plusSeconds(i + 1L));
                    audit.setObject(3, null);
                    audit.setString(4, "SIGNAL_BUY");
                    audit.setString(5, "BUY");
                    audit.setString(6, placeholders.get(i % placeholders.size()));
                    audit.setString(7, null);
                    audit.setString(8, "{\"decision\":\"BUY\",\"intentCreated\":true,\"side\":\"LONG\"}");
                    audit.addBatch();
                }
                audit.executeBatch();
                insertAudit(audit, 39_999L, base, "FILTER_BLOCK", "BUY",
                        "TradePlanQualityGate", "{\"intent_created\":true,\"side\":\"LONG\"}");
                audit.executeBatch();
            }

            assertThat(auditIds(connection)).containsExactly(39_999L);
        }
    }

    @Test
    void runtimeBlockLikeActionAndOutcomeCannotRescuePlaceholderBlockerIntoQuota() throws Exception {
        try (Connection connection = connection("runtime_placeholder_block_like")) {
            createRuntimeTables(connection);
            LocalDateTime base = LocalDateTime.parse("2026-07-18T00:00:00");
            List<String> placeholders = List.of("NA", "NULL", "UNKNOWN", "NOT_APPLICABLE", "PENDING");
            try (PreparedStatement evidence = connection.prepareStatement("""
                    INSERT INTO runtime_evidence(
                      id, evidence_time, live_signal_id, side, selected_action, decision, signal_source,
                      terminal_blocker, suppression_reason, policy_mode, blocker_reason, intent_created,
                      policy_inputs_json, execution_preview_json, features_snapshot_json,
                      final_outcome, order_sent)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (int i = 0; i < 501; i++) {
                    insertRuntime(evidence, 70_000L + i, base.plusSeconds(i + 1L), null, "LONG",
                            "BLOCK", "BUY", "SIGNAL_BUY", placeholders.get(i % placeholders.size()), 1, "{}");
                }
                evidence.executeBatch();
                insertRuntime(evidence, 69_999L, base, null, "LONG",
                        "BUY", "BUY", "SIGNAL_BUY", "TradePlanQualityGate", 1, "{}");
                evidence.executeBatch();
            }

            assertThat(runtimeIds(connection)).containsExactly(69_999L);
        }
    }

    @Test
    void auditBlockLikeOutcomeCannotRescuePlaceholderBlockerIntoQuota() throws Exception {
        try (Connection connection = connection("audit_placeholder_block_like")) {
            createAuditTables(connection);
            LocalDateTime base = LocalDateTime.parse("2026-07-18T00:00:00");
            List<String> placeholders = List.of("NA", "NULL", "UNKNOWN", "NOT_APPLICABLE", "PENDING");
            try (PreparedStatement audit = connection.prepareStatement("""
                    INSERT INTO decision_audit(
                      id, event_time, live_signal_id, event_type, outcome, blocker, reason, context_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (int i = 0; i < 501; i++) {
                    insertAudit(audit, 80_000L + i, base.plusSeconds(i + 1L), "SIGNAL_BUY", "BLOCKED",
                            placeholders.get(i % placeholders.size()),
                            "{\"decision\":\"BUY\",\"intentCreated\":true,\"side\":\"LONG\"}");
                }
                insertAudit(audit, 79_999L, base, "SIGNAL_BUY", "BUY",
                        "TradePlanQualityGate", "{\"intentCreated\":true,\"side\":\"LONG\"}");
                audit.executeBatch();
            }

            assertThat(auditIds(connection)).containsExactly(79_999L);
        }
    }

    @Test
    void sharedJavaContractRejectsBlockLikeActionAndOutcomeButKeepsExactPolicyEvidence() {
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put("terminal_blocker", "PENDING");
        placeholder.put("selected_action", "BLOCK");
        placeholder.put("final_outcome", "BLOCKED");
        assertThat(com.agora.service.trading.EvidenceGovernanceSemantics.isFilterAttributionInput(placeholder))
                .isFalse();

        placeholder.put("policy_mode", "BLOCK");
        assertThat(com.agora.service.trading.EvidenceGovernanceSemantics.isFilterAttributionInput(placeholder))
                .as("exact policy mode is explicitly independent blocker evidence")
                .isTrue();
    }

    @Test
    void runtimeRawAndJoinedSideConflictFailsClosedBeforeLimit() throws Exception {
        try (Connection connection = connection("runtime_side_conflict")) {
            createRuntimeTables(connection);
            LocalDateTime base = LocalDateTime.parse("2026-07-18T00:00:00");
            try (PreparedStatement live = connection.prepareStatement(
                    "INSERT INTO live_signal(id, side) VALUES (?, ?)");
                 PreparedStatement evidence = connection.prepareStatement("""
                         INSERT INTO runtime_evidence(
                           id, evidence_time, live_signal_id, side, selected_action, decision, signal_source,
                           terminal_blocker, suppression_reason, policy_mode, blocker_reason, intent_created,
                           policy_inputs_json, execution_preview_json, features_snapshot_json,
                           final_outcome, order_sent)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """)) {
                for (int i = 0; i < 500; i++) {
                    long id = 50_000L + i;
                    live.setLong(1, id);
                    live.setString(2, "SELL");
                    live.addBatch();
                    insertRuntime(evidence, id, base.plusSeconds(i + 1L), id, "LONG",
                            "BLOCK", "BUY", "FILTER_BLOCK", "TradePlanQualityGate", 1, "{}");
                }
                live.executeBatch();
                evidence.executeBatch();
                insertRuntime(evidence, 49_999L, base, null, "LONG",
                        "BLOCK", "BUY", "FILTER_BLOCK", "TradePlanQualityGate", 1, "{}");
                evidence.executeBatch();
            }

            assertThat(runtimeIds(connection)).containsExactly(49_999L);
        }
    }

    @Test
    void auditRawSellSideCannotBeOverriddenByJoinedBuySideBeforeLimit() throws Exception {
        try (Connection connection = connection("audit_side_conflict")) {
            createAuditTables(connection);
            LocalDateTime base = LocalDateTime.parse("2026-07-18T00:00:00");
            try (PreparedStatement live = connection.prepareStatement(
                    "INSERT INTO live_signal(id, side) VALUES (?, ?)");
                 PreparedStatement audit = connection.prepareStatement("""
                    INSERT INTO decision_audit(
                      id, event_time, live_signal_id, event_type, outcome, blocker, reason, context_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (int i = 0; i < 500; i++) {
                    long id = 60_000L + i;
                    live.setLong(1, id);
                    live.setString(2, "LONG");
                    live.addBatch();
                    audit.setLong(1, id);
                    audit.setObject(2, base.plusSeconds(i + 1L));
                    audit.setLong(3, id);
                    audit.setString(4, "FILTER_BLOCK");
                    audit.setString(5, "BUY");
                    audit.setString(6, "TradePlanQualityGate");
                    audit.setString(7, null);
                    audit.setString(8, "{\"decision\":\"BUY\",\"intentCreated\":true,\"side\":\"SELL\"}");
                    audit.addBatch();
                }
                live.executeBatch();
                audit.executeBatch();
                insertAudit(audit, 59_999L, base, "FILTER_BLOCK", "BUY",
                        "TradePlanQualityGate", "{\"intent_created\":true,\"side\":\"LONG\"}");
                audit.executeBatch();
            }

            assertThat(auditIds(connection)).containsExactly(59_999L);
        }
    }

    @Test
    void runtimeAndAuditSqlIntentTruthMatchesJavaContract() throws Exception {
        List<IntentFixture> fixtures = List.of(
                new IntentFixture(1, "\"1\"", true),
                new IntentFixture(2, "1", true),
                new IntentFixture(3, "2", false),
                new IntentFixture(4, "true", true),
                new IntentFixture(5, "false", false),
                new IntentFixture(6, "null", false),
                new IntentFixture(7, "\"\"", false),
                new IntentFixture(8, "\"TrUe\"", true),
                new IntentFixture(9, "\" false \"", false),
                new IntentFixture(10, "1.0", false));

        try (Connection connection = connection("intent_truth")) {
            createRuntimeTables(connection);
            try (PreparedStatement evidence = connection.prepareStatement("""
                    INSERT INTO runtime_evidence(
                      id, evidence_time, live_signal_id, side, selected_action, decision, signal_source,
                      terminal_blocker, suppression_reason, policy_mode, blocker_reason, intent_created,
                      policy_inputs_json, execution_preview_json, features_snapshot_json,
                      final_outcome, order_sent)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (IntentFixture fixture : fixtures) {
                    insertRuntime(evidence, fixture.id(), LocalDateTime.parse("2026-07-18T00:00:00").plusSeconds(fixture.id()),
                            null, "LONG", "BLOCK", "BUY", "FILTER_BLOCK", "TradePlanQualityGate", 0,
                            "{\"intentCreated\":" + fixture.jsonValue() + "}");
                }
                evidence.executeBatch();
            }
            assertThat(runtimeIds(connection)).containsExactlyInAnyOrderElementsOf(
                    fixtures.stream().filter(IntentFixture::expected).map(f -> (long) f.id()).toList());
        }

        try (Connection connection = connection("audit_intent_truth")) {
            createAuditTables(connection);
            try (PreparedStatement audit = connection.prepareStatement("""
                    INSERT INTO decision_audit(
                      id, event_time, live_signal_id, event_type, outcome, blocker, reason, context_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (IntentFixture fixture : fixtures) {
                    insertAudit(audit, fixture.id(), LocalDateTime.parse("2026-07-18T00:00:00").plusSeconds(fixture.id()),
                            "FILTER_BLOCK", "BUY", "TradePlanQualityGate",
                            "{\"intentCreated\":" + fixture.jsonValue() + ",\"side\":\"LONG\"}");
                }
                audit.executeBatch();
            }
            assertThat(auditIds(connection)).containsExactlyInAnyOrderElementsOf(
                    fixtures.stream().filter(IntentFixture::expected).map(f -> (long) f.id()).toList());
        }

        for (IntentFixture fixture : fixtures) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("intent_created", false);
            row.put("policy_inputs_json", "{\"intentCreated\":" + fixture.jsonValue() + "}");
            assertThat(com.agora.service.trading.EvidenceGovernanceSemantics.hasExplicitIntent(row))
                    .as("intent fixture %s", fixture)
                    .isEqualTo(fixture.expected());
        }
    }

    private Connection connection(String name) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:pre_limit_" + name + ";MODE=MySQL");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE ALIAS IF NOT EXISTS JSON_EXTRACT FOR 'com.agora.mcp.SignalCorrectnessMcpToolsPreLimitSqlTest.jsonExtract'");
            statement.execute("CREATE ALIAS IF NOT EXISTS JSON_UNQUOTE FOR 'com.agora.mcp.SignalCorrectnessMcpToolsPreLimitSqlTest.jsonUnquote'");
        }
        return connection;
    }

    private void createRuntimeTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE live_signal(id BIGINT PRIMARY KEY, side VARCHAR(32), auto_traded INT)");
            statement.execute("""
                    CREATE TABLE runtime_evidence(
                      id BIGINT PRIMARY KEY, evidence_time TIMESTAMP, live_signal_id BIGINT, side VARCHAR(32),
                      selected_action VARCHAR(64), decision VARCHAR(64), signal_source VARCHAR(64),
                      terminal_blocker VARCHAR(255), suppression_reason VARCHAR(255), policy_mode VARCHAR(64),
                      blocker_reason VARCHAR(255), intent_created INT, policy_inputs_json VARCHAR(2000),
                      execution_preview_json VARCHAR(2000), features_snapshot_json VARCHAR(2000),
                      final_outcome VARCHAR(64), order_sent INT)
                    """);
        }
    }

    private void createAuditTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE live_signal(id BIGINT PRIMARY KEY, side VARCHAR(32), auto_traded INT)");
            statement.execute("""
                    CREATE TABLE decision_audit(
                      id BIGINT PRIMARY KEY, event_time TIMESTAMP, live_signal_id BIGINT, event_type VARCHAR(64),
                      outcome VARCHAR(64), blocker VARCHAR(255), reason VARCHAR(255), context_json VARCHAR(2000))
                    """);
        }
    }

    private void insertRuntime(PreparedStatement statement,
                               long id,
                               LocalDateTime time,
                               Long liveSignalId,
                               String side,
                               String action,
                               String decision,
                               String source,
                               String blocker,
                               int intentCreated,
                               String policyInputs) throws Exception {
        statement.setLong(1, id);
        statement.setObject(2, time);
        statement.setObject(3, liveSignalId);
        statement.setString(4, side);
        statement.setString(5, action);
        statement.setString(6, decision);
        statement.setString(7, source);
        statement.setString(8, blocker);
        statement.setString(9, null);
        statement.setString(10, null);
        statement.setString(11, null);
        statement.setInt(12, intentCreated);
        statement.setString(13, policyInputs);
        statement.setString(14, "{}");
        statement.setString(15, "{}");
        statement.setString(16, "BLOCKED");
        statement.setInt(17, 0);
        statement.addBatch();
    }

    private void insertAudit(PreparedStatement statement,
                             long id,
                             LocalDateTime time,
                             String eventType,
                             String outcome,
                             String blocker,
                             String context) throws Exception {
        statement.setLong(1, id);
        statement.setObject(2, time);
        statement.setObject(3, null);
        statement.setString(4, eventType);
        statement.setString(5, outcome);
        statement.setString(6, blocker);
        statement.setString(7, null);
        statement.setString(8, context);
        statement.addBatch();
    }

    private List<Long> runtimeIds(Connection connection) throws Exception {
        String predicate = SignalCorrectnessMcpTools.runtimeFilterPopulationSql();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.id
                FROM runtime_evidence e
                LEFT JOIN live_signal s ON s.id = e.live_signal_id
                WHERE 1 = 1
                """ + predicate + " ORDER BY e.evidence_time DESC, e.id DESC LIMIT 500");
             ResultSet rows = statement.executeQuery()) {
            return ids(rows);
        }
    }

    private List<Long> auditIds(Connection connection) throws Exception {
        String predicate = SignalCorrectnessMcpTools.auditFilterPopulationSql();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id
                FROM decision_audit a
                LEFT JOIN live_signal s ON s.id = a.live_signal_id
                WHERE 1 = 1
                """ + predicate + " ORDER BY a.event_time DESC, a.id DESC LIMIT 500");
             ResultSet rows = statement.executeQuery()) {
            return ids(rows);
        }
    }

    private List<Long> ids(ResultSet rows) throws Exception {
        List<Long> ids = new ArrayList<>();
        while (rows.next()) ids.add(rows.getLong(1));
        return ids;
    }

    public static String jsonExtract(String rawJson, String path) {
        if (rawJson == null || path == null || !path.startsWith("$.")) return null;
        try {
            JsonNode value = JSON.readTree(rawJson).path(path.substring(2));
            return value.isMissingNode() || value.isNull() ? null : value.asText();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String jsonUnquote(String value) {
        return value;
    }

    private record IntentFixture(int id, String jsonValue, boolean expected) {
    }
}
