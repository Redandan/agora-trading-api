package com.agora.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignalCorrectnessMcpToolsClassificationTest {

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
    void expectedValuePassInfoIsNotActionableOrSuppressed() {
        Map<String, Object> row = baseRow();
        row.put("selected_action", "SMALL_DRY_RUN");
        row.put("decision", "pass");
        row.put("blocker_reason", "AttentionRule: ExpectedValueGatePass / INFO");
        row.put("suppression_reason", "NONE");
        row.put("live_signal_id", 258L);

        assertThat(tools.isPriceActionable(row)).isFalse();
        assertThat(tools.decisionPath(row)).isEqualTo("PASS");
    }

    @Test
    void holdEvaluationRemainsNonActionableEvenWithLinkedLiveSignal() {
        Map<String, Object> row = baseRow();
        row.put("signal_source", "SIGNAL_EVAL");
        row.put("selected_action", "EVALUATED_ONLY");
        row.put("decision", "HOLD");
        row.put("blocker_reason", "indicators_missing");
        row.put("live_signal_id", 258L);

        assertThat(tools.isPriceActionable(row)).isFalse();
        assertThat(tools.decisionPath(row)).isEqualTo("PASS");
    }

    @Test
    void pendingIntentWithoutPlaceholderSuppressionRemainsActionable() {
        Map<String, Object> row = baseRow();
        row.put("selected_action", "ALLOW_ORDER_AFTER_EVIDENCE");
        row.put("decision", "BUY");
        row.put("intent_created", true);
        row.put("suppression_reason", "NONE");

        assertThat(tools.isPriceActionable(row)).isTrue();
        assertThat(tools.decisionPath(row)).isEqualTo("SUPPRESSED");
    }

    @Test
    void explicitIntentOutsideBuyLaneIsNotGovernanceEligible() {
        Map<String, Object> row = baseRow();
        row.put("signal_source", "SIGNAL_EVAL");
        row.put("selected_action", "EVALUATED_ONLY");
        row.put("decision", "PASS");
        row.put("intent_created", true);
        row.put("terminal_blocker", "TradePlanQualityGate");

        assertThat(tools.governanceClassification(row))
                .isEqualTo(SignalCorrectnessMcpTools.GovernanceClassification.STRATEGY_NO_ENTRY_INTENT);
        assertThat(tools.isPriceActionable(row)).isFalse();
    }

    @Test
    void realTradePlanBlockRemainsActionableAndBlocked() {
        Map<String, Object> row = baseRow();
        row.put("signal_source", "FILTER_BLOCK");
        row.put("selected_action", "BLOCK");
        row.put("decision", "BUY");
        row.put("terminal_blocker", "TradePlanQualityGate");
        row.put("final_outcome", "BLOCKED");
        row.put("intent_created", true);

        assertThat(tools.isPriceActionable(row)).isTrue();
        assertThat(tools.decisionPath(row)).isEqualTo("BLOCK");
        assertThat(tools.governanceClassification(row))
                .isEqualTo(SignalCorrectnessMcpTools.GovernanceClassification.TERMINAL_GUARD_BLOCK);
    }

    @Test
    void sellAndShortFilterBlocksNeverEnterBuyGovernanceLane() {
        for (String sellMarker : List.of("SELL", "SHORT")) {
            Map<String, Object> row = baseRow();
            row.put("signal_source", "FILTER_BLOCK");
            row.put("selected_action", "BLOCK");
            row.put("decision", sellMarker);
            row.put("side", sellMarker);
            row.put("terminal_blocker", "TradePlanQualityGate");
            row.put("final_outcome", "BLOCKED");
            row.put("intent_created", true);

            assertThat(tools.governanceClassification(row))
                    .as("%s FILTER_BLOCK must not be a BUY terminal guard", sellMarker)
                    .isEqualTo(SignalCorrectnessMcpTools.GovernanceClassification.STRATEGY_NO_ENTRY_INTENT);
        }
    }

    @Test
    void rawOrJoinedSellConflictFailsClosedInJavaGovernanceSemantics() {
        for (Map.Entry<String, String> conflict : Map.of(
                "raw_side", "SELL",
                "joined_side", "SHORT").entrySet()) {
            Map<String, Object> row = baseRow();
            row.put("signal_source", "FILTER_BLOCK");
            row.put("selected_action", "BLOCK");
            row.put("decision", "BUY");
            row.put("side", "LONG");
            row.put("raw_side", "LONG");
            row.put("joined_side", "LONG");
            row.put(conflict.getKey(), conflict.getValue());
            row.put("terminal_blocker", "TradePlanQualityGate");
            row.put("intent_created", true);

            assertThat(tools.governanceClassification(row))
                    .as("%s conflict must fail closed", conflict.getKey())
                    .isEqualTo(SignalCorrectnessMcpTools.GovernanceClassification.STRATEGY_NO_ENTRY_INTENT);
        }
    }

    @Test
    void localTradingViewHoldWithEvaluationIntentIsStrategyNoEntryIntent() {
        Map<String, Object> row = baseRow();
        row.put("side", "HOLD");
        row.put("selected_action", "HOLD");
        row.put("decision", "HOLD");
        row.put("intent_created", true);
        row.put("policy_inputs_json", "{\"decision\":\"LOCAL_TRADINGVIEW_NO_BUY\",\"blockers\":\"LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE\"}");

        assertThat(tools.governanceClassification(row))
                .isEqualTo(SignalCorrectnessMcpTools.GovernanceClassification.STRATEGY_NO_ENTRY_INTENT);
        assertThat(tools.isPriceActionable(row)).isFalse();
        assertThat(tools.decisionPath(row)).isEqualTo("PASS");
    }

    @Test
    void executionCapacityBlockIsSeparateFromTerminalGuard() {
        Map<String, Object> row = baseRow();
        row.put("signal_source", "ENTRY_SKIP");
        row.put("selected_action", "BLOCK");
        row.put("decision", "BUY");
        row.put("intent_created", true);
        row.put("terminal_blocker", "daily new auto-entry cap reached");

        assertThat(tools.governanceClassification(row))
                .isEqualTo(SignalCorrectnessMcpTools.GovernanceClassification.EXECUTION_CAPACITY_BLOCK);
    }

    @Test
    void donchianShadowStateAdvanceIsNotPriceActionable() {
        Map<String, Object> row = baseRow();
        row.put("signal_source", "DONCHIAN_BREAKOUT");
        row.put("selected_action", "DONCHIAN_SHADOW_STATE_ADVANCE");

        assertThat(tools.isPriceActionable(row)).isFalse();
    }

    @Test
    void filterAttributionCountsDuplicateClosedBarRepresentationsOnce() {
        LocalDateTime eventTime = LocalDateTime.now(ZoneOffset.UTC).minusDays(2);
        Map<String, Object> first = blockedBuyRow(70001L, eventTime);
        Map<String, Object> second = blockedBuyRow(70002L, eventTime);

        when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(eventTime.plusDays(1));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM bt_runtime_decision_evidence")) return List.of(first, second);
            return List.of();
        });

        String report = tools.getFilterAttributionMatrix("BTCUSDT", 5);

        assertThat(report).containsPattern("(?m)^TradePlanQualityGate\\s+\\|\\s+1\\s+\\|");
        assertThat(report).contains("duplicateRepresentationCount=1");
    }

    @Test
    void filterAttributionFailsClosedForCanonicalIdentityConflict() {
        LocalDateTime eventTime = LocalDateTime.now(ZoneOffset.UTC).minusDays(2);
        Map<String, Object> first = blockedBuyRow(71001L, eventTime);
        Map<String, Object> second = blockedBuyRow(71002L, eventTime);
        first.put("live_signal_id", 99001L);
        second.put("live_signal_id", 99001L);

        when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(eventTime.plusDays(1));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM bt_runtime_decision_evidence")) return List.of(first, second);
            return List.of();
        });

        String report = tools.getFilterAttributionMatrix("BTCUSDT", 5);

        assertThat(report).contains("identityConflictCount=1");
        assertThat(report).contains("nonPriceActionableExcluded=1");
        assertThat(report).doesNotContainPattern("(?m)^TradePlanQualityGate\\s+\\|");
    }

    private Map<String, Object> blockedBuyRow(long decisionId, LocalDateTime evidenceTime) {
        Map<String, Object> row = baseRow();
        row.put("row_source", "RUNTIME_EVIDENCE");
        row.put("row_id", decisionId + 100000L);
        row.put("runtime_evidence_id", decisionId + 100000L);
        row.put("decision_id", decisionId);
        row.put("evidence_time", evidenceTime);
        row.put("symbol", "BTCUSDT");
        row.put("strategy_id", 508L);
        row.put("side", "LONG");
        row.put("interval_code", "4h");
        row.put("bar_open_time", evidenceTime.minusMinutes(evidenceTime.getMinute()));
        row.put("signal_source", "FILTER_BLOCK");
        row.put("selected_action", "BLOCK");
        row.put("decision", "BUY");
        row.put("terminal_blocker", "TradePlanQualityGate");
        row.put("final_outcome", "BLOCKED");
        row.put("intent_created", true);
        row.put("features_snapshot_json", "{\"intentCreated\":true}");
        return row;
    }

    private Map<String, Object> baseRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_sent", false);
        row.put("intent_created", false);
        row.put("final_outcome", "PENDING");
        return row;
    }
}
