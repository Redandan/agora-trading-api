package com.agora.mcp;

import com.agora.config.properties.TradingSignalSourceProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.repository.system.ServerStartupLogRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.service.BacktestService;
import com.agora.service.diagnostic.AlphaPromotionTracker;
import com.agora.service.diagnostic.DbSlowQueryMonitorService;
import com.agora.service.diagnostic.IndicatorAccuracyScanner;
import com.agora.service.diagnostic.IndicatorHourMatrixService;
import com.agora.service.diagnostic.IndicatorOutcomeService;
import com.agora.service.diagnostic.OrphanTradeReconcilerService;
import com.agora.service.trading.EventRiskLevelEngine;
import com.agora.service.trading.PositionSizingService;
import com.agora.service.trading.TradingSignalSourcePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DiagnosticMcpToolsMissedOpportunityTest {

    @Test
    void correlatesNullIntervalAutoTradeByResolvedLiveSignalId() {
        ScenarioJdbcTemplate jdbc = new ScenarioJdbcTemplate(Scenario.EXECUTED);

        String report = tools(jdbc).analyzeMissedTradingOpportunities("BTCUSDT", 24, 1.0, null, null);

        assertThat(report)
                .contains("correlationPolicy: LIVE_SIGNAL_ID_THEN_EXACT_STRATEGY_SYMBOL_INTERVAL_BAR")
                .contains("missedOpportunityCount=0")
                .contains("executedCount=1")
                .contains("class=EXECUTED")
                .doesNotContain("no correlated execution/blocker");
        assertThat(jdbc.buySql)
                .contains("LEFT JOIN bt_live_signal s_direct")
                .contains("LEFT JOIN bt_live_signal s_bar")
                .contains("COALESCE(a.live_signal_id, s_bar.id) AS live_signal_id");
        assertThat(jdbc.relatedSql)
                .contains("live_signal_id = ?")
                .contains("bar_open_time <=> ? AND interval_code = ?")
                .doesNotContain("AND symbol = ?\n                      AND interval_code = ?");
    }

    @Test
    void namedEntrySkipWithForwardUpsideIsGateReviewNotMissed() {
        ScenarioJdbcTemplate jdbc = new ScenarioJdbcTemplate(Scenario.ENTRY_SKIP);
        DiagnosticMcpTools tools = tools(jdbc);

        String report = tools.analyzeMissedTradingOpportunities("BTCUSDT", 24, 1.0, null, null);
        String attribution = tools.getMissedAlphaAttributionReport("BTCUSDT", 24, 1.0);

        assertThat(report)
                .contains("missedOpportunityCount=0")
                .contains("entrySkipReviewCount=1")
                .contains("class=ENTRY_SKIP_REVIEW")
                .contains("blocker=ENTRY_SKIP/TradePlanQualityGate")
                .contains("named ENTRY_SKIP gate ended the order path");
        assertThat(attribution)
                .contains("missedCandidates=0")
                .contains("entrySkipReview=1")
                .contains("TradePlanQualityGate total=1 missed=0 review=1")
                .contains("class=ENTRY_SKIP_REVIEW");
    }

    @Test
    void forwardUpsideWithoutTerminalEvidenceRemainsMissedCandidate() {
        String report = tools(new ScenarioJdbcTemplate(Scenario.NO_TERMINAL))
                .analyzeMissedTradingOpportunities("BTCUSDT", 24, 1.0, null, null);

        assertThat(report)
                .contains("missedOpportunityCount=1")
                .contains("entrySkipReviewCount=0")
                .contains("executedCount=0")
                .contains("class=MISSED_CANDIDATE")
                .contains("no correlated execution/blocker");
    }

    private DiagnosticMcpTools tools(JdbcTemplate jdbc) {
        TradingSignalSourceProperties sourceProperties = new TradingSignalSourceProperties(
                "LOCAL_TRADINGVIEW", false, false, "", BigDecimal.ZERO);
        TradingViewLocalSignalProperties localProperties = new TradingViewLocalSignalProperties(
                true, 485L, "BTCUSDT", "1d", "binance", 320, 3, 72,
                new BigDecimal("10.0"), new BigDecimal("10.0"),
                TradingViewLocalSignalProperties.ExecutionMode.BTC_BASE_DRY_RUN,
                true, true, false, 3, 1, 1,
                new BigDecimal("0.0300"), new BigDecimal("0.1200"),
                new BigDecimal("250.0"));
        return new DiagnosticMcpTools(
                mock(ServerStartupLogRepository.class),
                mock(BtStrategyRepository.class),
                mock(BtLiveSignalRepository.class),
                mock(BacktestService.class),
                new ObjectMapper(),
                jdbc,
                mock(IndicatorOutcomeService.class),
                mock(IndicatorAccuracyScanner.class),
                mock(IndicatorHourMatrixService.class),
                mock(AlphaPromotionTracker.class),
                mock(OrphanTradeReconcilerService.class),
                mock(DbSlowQueryMonitorService.class),
                mock(PositionSizingService.class),
                mock(com.agora.mcp.auth.McpApiKeyFilter.class),
                mock(EventRiskLevelEngine.class),
                mock(McpRegistryVersionService.class),
                new TradingSignalSourcePolicy(sourceProperties),
                localProperties);
    }

    private enum Scenario {
        EXECUTED,
        ENTRY_SKIP,
        NO_TERMINAL
    }

    private static class ScenarioJdbcTemplate extends JdbcTemplate {
        private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 7, 10, 8, 0);
        private final Scenario scenario;
        private String buySql;
        private String relatedSql;

        private ScenarioJdbcTemplate(Scenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("event_type IN ('SIGNAL_EVAL','SIGNAL_BUY')")) {
                buySql = sql;
                return List.of(buyRow());
            }
            if (sql.contains("event_type IN ('ENTRY_SKIP','FILTER_BLOCK','AUTOTRADE_OK','AUTOTRADE_FAIL')")) {
                relatedSql = sql;
                return switch (scenario) {
                    case EXECUTED -> List.of(auditRow("AUTOTRADE_OK", "PASS", null));
                    case ENTRY_SKIP -> List.of(auditRow("ENTRY_SKIP", "BLOCKED", "TradePlanQualityGate"));
                    case NO_TERMINAL -> List.of();
                };
            }
            if (sql.contains("SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ open_time, close_price")) {
                return List.of(Map.of("open_time", EVENT_TIME, "close_price", new BigDecimal("100.00")));
            }
            if (sql.contains("MAX(high_price) AS max_high")) {
                return List.of(Map.of(
                        "max_high", new BigDecimal("102.00"),
                        "min_low", new BigDecimal("99.00")));
            }
            return List.of();
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            if (sql.contains("FROM bt_runtime_decision_evidence")) {
                return Map.of(
                        "total", 0,
                        "fg_warn", 0,
                        "fg_terminal", 0,
                        "continued_ev", 0,
                        "continued_tqs", 0,
                        "order_sent", 0,
                        "shadow_suppressed", 0);
            }
            return Map.of();
        }

        private Map<String, Object> buyRow() {
            Map<String, Object> row = new HashMap<>();
            row.put("id", 77384L);
            row.put("strategy_id", 508L);
            row.put("symbol", "BTCUSDT");
            row.put("interval_code", "4h");
            row.put("bar_open_time", EVENT_TIME);
            row.put("event_time", EVENT_TIME);
            row.put("outcome", "INFO");
            row.put("reason", "BUY");
            row.put("context_json", "{}");
            row.put("live_signal_id", 261L);
            row.put("entry_price", new BigDecimal("100.00"));
            row.put("suggested_tp", new BigDecimal("106.00"));
            row.put("suggested_sl", new BigDecimal("88.00"));
            row.put("nn_output", new BigDecimal("0.85"));
            return row;
        }

        private Map<String, Object> auditRow(String eventType, String outcome, String blocker) {
            Map<String, Object> row = new HashMap<>();
            row.put("strategy_id", 508L);
            row.put("interval_code", "AUTOTRADE_OK".equals(eventType) ? null : "4h");
            row.put("bar_open_time", "AUTOTRADE_OK".equals(eventType) ? null : EVENT_TIME);
            row.put("event_time", EVENT_TIME.plusSeconds(5));
            row.put("event_type", eventType);
            row.put("outcome", outcome);
            row.put("blocker", blocker);
            row.put("reason", blocker == null ? null : "blocked by " + blocker);
            row.put("context_json", "{}");
            row.put("distance_seconds", 5L);
            return row;
        }
    }
}
