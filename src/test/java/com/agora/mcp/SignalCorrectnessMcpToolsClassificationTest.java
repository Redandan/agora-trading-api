package com.agora.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SignalCorrectnessMcpToolsClassificationTest {

    private final SignalCorrectnessMcpTools tools = new SignalCorrectnessMcpTools(
            mock(JdbcTemplate.class),
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
    void realTradePlanBlockRemainsActionableAndBlocked() {
        Map<String, Object> row = baseRow();
        row.put("signal_source", "FILTER_BLOCK");
        row.put("selected_action", "BLOCK");
        row.put("decision", "BUY");
        row.put("terminal_blocker", "TradePlanQualityGate");
        row.put("final_outcome", "BLOCKED");

        assertThat(tools.isPriceActionable(row)).isTrue();
        assertThat(tools.decisionPath(row)).isEqualTo("BLOCK");
    }

    @Test
    void donchianShadowStateAdvanceIsNotPriceActionable() {
        Map<String, Object> row = baseRow();
        row.put("signal_source", "DONCHIAN_BREAKOUT");
        row.put("selected_action", "DONCHIAN_SHADOW_STATE_ADVANCE");

        assertThat(tools.isPriceActionable(row)).isFalse();
    }

    private Map<String, Object> baseRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_sent", false);
        row.put("intent_created", false);
        row.put("final_outcome", "PENDING");
        return row;
    }
}
