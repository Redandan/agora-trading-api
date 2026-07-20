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
import java.util.List;

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
}
