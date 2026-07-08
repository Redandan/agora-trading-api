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
                scope);
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
