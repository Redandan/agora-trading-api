package com.agora.mcp;

import com.agora.config.properties.TradingSignalSourceProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.dto.backtest.BacktestResultResponse;
import com.agora.model.BtStrategy;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiagnosticMcpToolsSignalSourcePolicyTest {

    @Test
    void tradingViewPrimaryDoesNotFlagPolicySuppressedLegacyStrategyAsMissingEvaluation() {
        BtStrategyRepository strategyRepo = mock(BtStrategyRepository.class);
        BtLiveSignalRepository liveSignalRepo = mock(BtLiveSignalRepository.class);
        BacktestService backtestService = mock(BacktestService.class);
        BtStrategy strategy = strategy(508L, "OI-Funding-Divergence-BTC-1h-v1", "OI_FUNDING_DIVERGENCE",
                "{\"symbol\":\"BTCUSDT\",\"runIntervalCode\":\"1h\",\"notifyOnly\":true}");

        when(strategyRepo.findAll()).thenReturn(List.of(strategy));
        when(liveSignalRepo.findByStrategyIdAndCreatedAtAfter(eq(508L), any())).thenReturn(List.of());
        when(backtestService.runForExploration(any())).thenReturn(backtestWithBuy());

        DiagnosticMcpTools tools = tools(
                new TradingSignalSourcePolicy(signalSourceProps("TRADINGVIEW", false)),
                localProps(true, 485L),
                strategyRepo,
                liveSignalRepo,
                backtestService);

        String report = tools.verifyStrategyExecution(5);

        assertThat(report)
                .contains("signal_source_policy_primary=TRADINGVIEW")
                .contains("POLICY_SUPPRESSED_NOT_MISSED_EVALUATION")
                .contains("legacy LiveSignalEvaluator 已由 signal-source policy 停用")
                .contains("MACHINE_STATUS no missing evaluation; no missed order")
                .doesNotContain("疑似漏評估 Bug");
    }

    @Test
    void localTradingViewConfiguredStrategyStillFlagsMissingEvaluationWhenNoAuditExists() {
        BtStrategyRepository strategyRepo = mock(BtStrategyRepository.class);
        BtLiveSignalRepository liveSignalRepo = mock(BtLiveSignalRepository.class);
        BacktestService backtestService = mock(BacktestService.class);
        BtStrategy strategy = strategy(485L, "ScoreBuyV2-BTC-1d", "SCORE_BUY_V2",
                "{\"symbol\":\"BTCUSDT\",\"runIntervalCode\":\"1d\",\"notifyOnly\":true,\"klineSource\":\"okx\"}");

        when(strategyRepo.findAll()).thenReturn(List.of(strategy));
        when(liveSignalRepo.findByStrategyIdAndCreatedAtAfter(eq(485L), any())).thenReturn(List.of());
        when(backtestService.runForExploration(any())).thenReturn(backtestWithBuy());

        DiagnosticMcpTools tools = tools(
                new TradingSignalSourcePolicy(signalSourceProps("LOCAL_TRADINGVIEW", false)),
                localProps(true, 485L),
                strategyRepo,
                liveSignalRepo,
                backtestService);

        String report = tools.verifyStrategyExecution(5);

        assertThat(report)
                .contains("signal_source_policy_primary=LOCAL_TRADINGVIEW")
                .contains("LOCAL_TRADINGVIEW parity evaluator active for configured strategyId=485")
                .contains("疑似漏評估 Bug")
                .contains("MACHINE_STATUS missing evaluation or missed order suspected")
                .doesNotContain("POLICY_SUPPRESSED_NOT_MISSED_EVALUATION");
    }

    @Test
    void secondaryLegacyAllowlistMakesStrategy508ExpectedInsteadOfPolicySuppressed() {
        BtStrategyRepository strategyRepo = mock(BtStrategyRepository.class);
        BtLiveSignalRepository liveSignalRepo = mock(BtLiveSignalRepository.class);
        BacktestService backtestService = mock(BacktestService.class);
        BtStrategy strategy = strategy(508L, "OI-Funding-Divergence-BTC-1h-v1", "OI_FUNDING_DIVERGENCE",
                "{\"symbol\":\"BTCUSDT\",\"runIntervalCode\":\"1h\",\"notifyOnly\":false}");

        when(strategyRepo.findAll()).thenReturn(List.of(strategy));
        when(liveSignalRepo.findByStrategyIdAndCreatedAtAfter(eq(508L), any())).thenReturn(List.of());
        when(backtestService.runForExploration(any())).thenReturn(backtestWithBuy());

        DiagnosticMcpTools tools = tools(
                new TradingSignalSourcePolicy(new TradingSignalSourceProperties(
                        "LOCAL_TRADINGVIEW", false, true, "508", new BigDecimal("10.0"))),
                localProps(true, 485L),
                strategyRepo,
                liveSignalRepo,
                backtestService);

        String report = tools.verifyStrategyExecution(5);

        assertThat(report)
                .contains("signal_source_policy_primary=LOCAL_TRADINGVIEW")
                .contains("legacy secondary LiveSignalEvaluator allowlist includes strategyId=508")
                .contains("疑似漏評估 Bug")
                .contains("MACHINE_STATUS missing evaluation or missed order suspected")
                .doesNotContain("POLICY_SUPPRESSED_NOT_MISSED_EVALUATION");
    }

    private DiagnosticMcpTools tools(TradingSignalSourcePolicy policy,
                                     TradingViewLocalSignalProperties localProps,
                                     BtStrategyRepository strategyRepo,
                                     BtLiveSignalRepository liveSignalRepo,
                                     BacktestService backtestService) {
        return new DiagnosticMcpTools(
                mock(ServerStartupLogRepository.class),
                strategyRepo,
                liveSignalRepo,
                backtestService,
                new ObjectMapper(),
                new FakeDiagnosticJdbcTemplate(),
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
                policy,
                localProps);
    }

    private TradingSignalSourceProperties signalSourceProps(String primary, boolean legacyLiveEvaluatorEnabled) {
        return new TradingSignalSourceProperties(primary, legacyLiveEvaluatorEnabled, false, "", BigDecimal.ZERO);
    }

    private BtStrategy strategy(Long id, String name, String strategyType, String configJson) {
        BtStrategy strategy = new BtStrategy();
        strategy.setId(id);
        strategy.setName(name);
        strategy.setStrategyType(strategyType);
        strategy.setSymbols("BTCUSDT");
        strategy.setEnabled(true);
        strategy.setKlineSource("okx");
        strategy.setConfigJson(configJson);
        return strategy;
    }

    private BacktestResultResponse backtestWithBuy() {
        BacktestResultResponse result = new BacktestResultResponse();
        BacktestResultResponse.TradeRecordDto trade = new BacktestResultResponse.TradeRecordDto();
        trade.setEntryTime(LocalDateTime.now(ZoneId.of("Asia/Taipei")).minusHours(1));
        trade.setEntryPrice(new BigDecimal("100.00"));
        result.setTrades(List.of(trade));
        return result;
    }

    private TradingViewLocalSignalProperties localProps(boolean enabled, long strategyId) {
        return new TradingViewLocalSignalProperties(
                enabled, strategyId, "BTCUSDT", "1d", "okx", 320, 3, 72,
                new BigDecimal("10.0"), new BigDecimal("10.0"),
                TradingViewLocalSignalProperties.ExecutionMode.DRY_RUN,
                false, true, false, 3, 1, 1,
                new BigDecimal("0.0300"), new BigDecimal("0.1200"));
    }

    private static class FakeDiagnosticJdbcTemplate extends JdbcTemplate {
        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            if (sql.contains("FROM md_kline")) {
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                return Map.of(
                        "cnt", 120,
                        "first_bar", now.minusDays(20),
                        "latest_bar", now.minusMinutes(30));
            }
            if (sql.contains("FROM bt_decision_audit")) {
                return Map.of("total", 0, "buy_like", 0);
            }
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return List.of();
        }
    }
}
