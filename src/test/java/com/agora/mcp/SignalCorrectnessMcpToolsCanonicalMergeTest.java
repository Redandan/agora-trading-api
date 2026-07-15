package com.agora.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignalCorrectnessMcpToolsCanonicalMergeTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SignalCorrectnessMcpTools tools = new SignalCorrectnessMcpTools(
            jdbc,
            mock(DiagnosticMcpTools.class),
            mock(MarketDataMcpTools.class),
            mock(RuntimeEvidenceMcpTools.class),
            mock(MetaControlMcpTools.class),
            mock(StrategyManagementMcpTools.class),
            mock(IndicatorMcpTools.class),
            mock(TradingMlMcpTools.class),
            mock(EnsembleMcpTools.class),
            mock(PositionMcpTools.class));

    @Test
    void mergedBlockedBuyFeedsTruthCoverageGovernanceAndBlockerReportsOnce() {
        LocalDateTime eventTime = LocalDateTime.now(ZoneOffset.UTC).minusHours(2)
                .truncatedTo(ChronoUnit.MINUTES);
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 77101L, eventTime);
        runtime.put("runtime_evidence_id", 88001L);
        runtime.put("selected_action", "EVALUATED_ONLY");
        runtime.put("decision", "HOLD");
        runtime.put("final_outcome", "PENDING");

        Map<String, Object> audit = row("DECISION_AUDIT", 77101L, eventTime);
        audit.put("audit_id", 77101L);
        audit.put("signal_source", "ENTRY_SKIP");
        audit.put("selected_action", "BLOCK");
        audit.put("decision", "BUY");
        audit.put("final_outcome", "BLOCKED");
        audit.put("terminal_blocker", "TradePlanQualityGate");
        audit.put("policy_inputs_json", "{\"intentCreated\":true,\"candidateEntry\":100,\"candidateTp\":106,\"candidateSl\":88}");
        stubQueries(List.of(runtime), List.of(audit), eventTime);

        String truth = tools.getSignalTruthTable("MERGEUSDT", 24, 508L, 50, "1h", false);
        String coverage = tools.getSignalOutcomeLabelerStatus("MERGEUSDT", 24, "1h", false);
        String governance = tools.getGovernanceDriftDashboard("MERGEUSDT", 1, "1h");
        String blockers = tools.getBlockerDriftMatrix("MERGEUSDT", 1, "1h");

        assertThat(truth)
                .contains("rawObservationCount=2", "uniqueMergedEventCount=1",
                        "duplicateRepresentationCount=1", "rawCountConserved=true",
                        "decisionPath=BLOCK", "actionable=true",
                        "allSources:[DECISION_AUDIT:77101, RUNTIME_EVIDENCE:88001]");
        assertThat(coverage)
                .contains("rawObservationCount=2", "uniqueMergedEventCount=1",
                        "totalCandidates=1", "actionableCandidates=1");
        assertThat(governance).contains("actionableCandidates=1", "labeledCandidates=1",
                "unresolvedCandidates=0", "falseBlockCount=1");
        assertThat(blockers).contains("TradePlanQualityGate");
    }

    @Test
    void nearbyDifferentIntervalAndNullStrategyAuditAreNotSuppressed() {
        LocalDateTime eventTime = LocalDateTime.now(ZoneOffset.UTC).minusHours(2)
                .truncatedTo(ChronoUnit.MINUTES);
        Map<String, Object> runtime = row("RUNTIME_EVIDENCE", 77201L, eventTime);
        runtime.put("runtime_evidence_id", 88002L);
        runtime.put("interval_code", "4h");
        runtime.put("selected_action", "HOLD");
        runtime.put("decision", "HOLD");

        Map<String, Object> audit = row("DECISION_AUDIT", 77202L, eventTime.plusMinutes(2));
        audit.put("audit_id", 77202L);
        audit.put("strategy_id", null);
        audit.put("interval_code", "1h");
        audit.put("side", "SHORT");
        audit.put("bar_open_time", eventTime.minusHours(1));
        audit.put("signal_source", "ENTRY_SKIP");
        audit.put("selected_action", "BLOCK");
        audit.put("decision", "BUY");
        audit.put("terminal_blocker", "EntryDedup");
        audit.put("policy_inputs_json", "{\"intentCreated\":true,\"candidateEntry\":100,\"candidateTp\":106,\"candidateSl\":88}");
        stubQueries(List.of(runtime), List.of(audit), eventTime);

        String truth = tools.getSignalTruthTable("DISTINCTUSDT", 24, null, 50, "1h", true);

        assertThat(truth)
                .contains("rawObservationCount=2", "uniqueMergedEventCount=2",
                        "duplicateRepresentationCount=0", "rawCountConserved=true");
    }

    private void stubQueries(List<Map<String, Object>> runtimeRows,
                             List<Map<String, Object>> auditRows,
                             LocalDateTime eventTime) {
        when(jdbc.queryForList(any(String.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM bt_runtime_decision_evidence")) return runtimeRows;
            if (sql.contains("FROM bt_decision_audit")) return auditRows;
            if (sql.contains("FROM md_kline")) return positiveBars(eventTime);
            return List.of();
        });
        when(jdbc.queryForObject(any(String.class), eq(LocalDateTime.class), any(Object[].class)))
                .thenReturn(eventTime.plusHours(25));
    }

    private List<Map<String, Object>> positiveBars(LocalDateTime eventTime) {
        return List.of(
                bar(eventTime, "100", "100", "100"),
                bar(eventTime.plusHours(1), "103", "101", "102"),
                bar(eventTime.plusHours(4), "104", "102", "103"),
                bar(eventTime.plusHours(24), "105", "103", "104"));
    }

    private Map<String, Object> bar(LocalDateTime time, String high, String low, String close) {
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("open_time", time);
        bar.put("high_price", new BigDecimal(high));
        bar.put("low_price", new BigDecimal(low));
        bar.put("close_price", new BigDecimal(close));
        return bar;
    }

    private Map<String, Object> row(String source, long decisionId, LocalDateTime eventTime) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("row_source", source);
        row.put("row_id", decisionId);
        row.put("decision_id", decisionId);
        row.put("evidence_time", eventTime);
        row.put("symbol", source.equals("RUNTIME_EVIDENCE") ? "MERGEUSDT" : "MERGEUSDT");
        row.put("strategy_id", 508L);
        row.put("interval_code", "4h");
        row.put("side", "LONG");
        row.put("bar_open_time", eventTime.minusHours(4));
        row.put("signal_source", "SIGNAL_EVAL");
        row.put("order_sent", false);
        row.put("intent_created", false);
        return row;
    }
}
