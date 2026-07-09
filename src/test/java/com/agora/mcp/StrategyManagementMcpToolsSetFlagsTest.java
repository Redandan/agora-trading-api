package com.agora.mcp;

import com.agora.dto.backtest.StrategyResponse;
import com.agora.repository.trading.BtBacktestResultRepository;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.BacktestService;
import com.agora.service.BtStrategyService;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.meta.ScorecardReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StrategyManagementMcpToolsSetFlagsTest {

    @Test
    void setStrategyFlagsAllowsEntryDedupOpenExposureScopeAndEvictsCache() {
        BtStrategyService strategyService = mock(BtStrategyService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StrategyManagementMcpTools tools = newTools(strategyService, jdbc);
        when(strategyService.getStrategy(508L)).thenReturn(strategy());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        String result = setScope(tools, "auto_traded_open_rows");

        assertThat(result)
                .contains("更新成功")
                .contains("entryDedupOpenExposureScope: AUTO_TRADED_OPEN_ROWS");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sqlCaptor.capture(), argsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("$.entryDedupOpenExposureScope");
        assertThat(argsCaptor.getValue())
                .contains("AUTO_TRADED_OPEN_ROWS", "operator note", 508L);
        verify(strategyService).evictEnabledStrategiesCache();
    }

    @Test
    void setStrategyFlagsRejectsInvalidEntryDedupOpenExposureScope() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StrategyManagementMcpTools tools = newTools(mock(BtStrategyService.class), jdbc);

        String result = setScope(tools, "ZERO_QTY_ROWS");

        assertThat(result).contains("entryDedupOpenExposureScope 只能是");
        verifyNoInteractions(jdbc);
    }

    @Test
    void setStrategyFlagsAllowsTradePlanQualityGateOverrides() {
        BtStrategyService strategyService = mock(BtStrategyService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StrategyManagementMcpTools tools = newTools(strategyService, jdbc);
        when(strategyService.getStrategy(508L)).thenReturn(strategy());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        String result = tools.setStrategyFlags(
                508L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Strategy 508 TradingView parity +6/-12 narrow trade-plan override",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                0.49,
                0.121,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThat(result)
                .contains("更新成功")
                .contains("tradePlanQualityGateEnabled: true")
                .contains("tradePlanMinRiskReward: 0.49")
                .contains("tradePlanMaxStopLossPct: 0.121");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sqlCaptor.capture(), argsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("$.tradePlanQualityGateEnabled")
                .contains("$.tradePlanMinRiskReward")
                .contains("$.tradePlanMaxStopLossPct");
        assertThat(argsCaptor.getValue())
                .contains(true, 0.49, 0.121,
                        "Strategy 508 TradingView parity +6/-12 narrow trade-plan override", 508L);
        verify(strategyService).evictEnabledStrategiesCache();
    }

    @Test
    void setStrategyFlagsRejectsOutOfRangeTradePlanQualityGateOverrides() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StrategyManagementMcpTools tools = newTools(mock(BtStrategyService.class), jdbc);

        String result = tools.setStrategyFlags(
                508L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "bad trade-plan override",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                0.49,
                0.80,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThat(result).contains("tradePlanMaxStopLossPct 必須介於");
        verifyNoInteractions(jdbc);
    }

    @Test
    void setStrategyFlagsAllowsStagedMicroAddFlags() {
        BtStrategyService strategyService = mock(BtStrategyService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StrategyManagementMcpTools tools = newTools(strategyService, jdbc);
        when(strategyService.getStrategy(508L)).thenReturn(strategy());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        String result = tools.setStrategyFlags(
                508L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Strategy 508 scoped cross-interval staged micro-add review",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "allow_staged_micro_add_live_if_ev_positive",
                true,
                10.0,
                20.0,
                2,
                true);

        assertThat(result)
                .contains("更新成功")
                .contains("entryDedupDecisionMode: ALLOW_STAGED_MICRO_ADD_LIVE_IF_EV_POSITIVE")
                .contains("microAddLiveEnabled: true")
                .contains("microAddNotionalUsdt: 10.0")
                .contains("microAddMaxSameStrategyExposureUsdt: 20.0")
                .contains("stagedAddMaxOrdersPerDay: 2")
                .contains("stagedAddAllowSameStrategyCrossInterval: true");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sqlCaptor.capture(), argsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("$.entryDedupDecisionMode")
                .contains("$.microAddLiveEnabled")
                .contains("$.microAddNotionalUsdt")
                .contains("$.microAddMaxSameStrategyExposureUsdt")
                .contains("$.stagedAddMaxOrdersPerDay")
                .contains("$.stagedAddAllowSameStrategyCrossInterval");
        assertThat(argsCaptor.getValue())
                .contains("ALLOW_STAGED_MICRO_ADD_LIVE_IF_EV_POSITIVE", true, 10.0, 20.0, 2,
                        "Strategy 508 scoped cross-interval staged micro-add review", 508L);
        verify(strategyService).evictEnabledStrategiesCache();
    }

    @Test
    void setStrategyFlagsRejectsInvalidStagedAddDecisionMode() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StrategyManagementMcpTools tools = newTools(mock(BtStrategyService.class), jdbc);

        String result = tools.setStrategyFlags(
                508L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "bad staged-add mode",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "ALLOW_EVERYTHING",
                null,
                null,
                null,
                null,
                null);

        assertThat(result).contains("entryDedupDecisionMode 只能是");
        verifyNoInteractions(jdbc);
    }

    private static String setScope(StrategyManagementMcpTools tools, String scope) {
        return tools.setStrategyFlags(
                508L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "operator note",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                scope,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static StrategyResponse strategy() {
        StrategyResponse strategy = new StrategyResponse();
        strategy.setId(508L);
        strategy.setName("OI-Funding-Divergence-BTC-1h-v1");
        strategy.setStrategyType("OI_FUNDING_DIVERGENCE");
        strategy.setEnabled(true);
        return strategy;
    }

    private static StrategyManagementMcpTools newTools(BtStrategyService strategyService, JdbcTemplate jdbc) {
        return new StrategyManagementMcpTools(
                strategyService,
                mock(BacktestService.class),
                mock(AiStrategyDiscoveryService.class),
                mock(BtBacktestResultRepository.class),
                mock(BtLiveSignalRepository.class),
                mock(BtDecisionAuditRepository.class),
                mock(DecisionAuditWriter.class),
                mock(ScorecardReportService.class),
                mock(ApplicationEventPublisher.class),
                jdbc);
    }
}
