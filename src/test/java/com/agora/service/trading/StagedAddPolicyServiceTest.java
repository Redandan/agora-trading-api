package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StagedAddPolicyServiceTest {

    private final BtStrategyRepository strategyRepository = mock(BtStrategyRepository.class);
    private final BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
    private final BtDecisionAuditRepository decisionAuditRepository = mock(BtDecisionAuditRepository.class);
    private final RuntimeDecisionEvidenceService runtimeDecisionEvidenceService = mock(RuntimeDecisionEvidenceService.class);
    private final StagedAddPolicyService service = new StagedAddPolicyService(
            strategyRepository,
            liveSignalRepository,
            decisionAuditRepository,
            runtimeDecisionEvidenceService,
            new ObjectMapper());

    @Test
    void crossIntervalSameStrategyPositionCountsAsStagedAddBaseWhenEnabled() {
        BtStrategy strategy = strategy("""
                {
                  "stagedAddAllowSameStrategyCrossInterval": true,
                  "stagedAddSameStrategyExposureLimitUsdt": 20,
                  "stagedAddNotionalUsdt": 10,
                  "stagedAddMaxOrdersPerDay": 2,
                  "stagedAddMaxOpenPositions": 2,
                  "stagedAddOpenMaxLossCapUsdt": 1000,
                  "stagedAddLiveEnabled": true
                }
                """);
        BtLiveSignal open4h = openLong(508L, "BTCUSDT", "4h", "100000", "0.0001");
        when(strategyRepository.findById(508L)).thenReturn(Optional.of(strategy));
        when(liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(508L))
                .thenReturn(List.of(open4h));
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of(open4h));
        when(liveSignalRepository.countByStrategyIdAndAutoTradedIsTrueAndCreatedAtAfter(any(), any()))
                .thenReturn(1L);
        when(decisionAuditRepository.findWindow(any(), any(), anyString(), any(), anyBoolean(), anyList(), any()))
                .thenReturn(List.of());
        when(runtimeDecisionEvidenceService.listRecent(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        StagedAddPolicyService.Evaluation evaluation = service.evaluate(
                "BTCUSDT",
                508L,
                "LONG",
                "1h",
                0.42,
                "PROBE_DRY_RUN",
                new BigDecimal("101000"),
                new BigDecimal("107060"),
                new BigDecimal("88880"));

        assertThat(evaluation.openSameStrategyPositions()).isEqualTo(1);
        assertThat(evaluation.sameStrategyExposureUsed()).isEqualByComparingTo("10");
        assertThat(evaluation.remainingAddBudget()).isEqualByComparingTo("10");
        assertThat(evaluation.blockers()).doesNotContain("NO_EXISTING_POSITION_FOR_STAGED_ADD");
    }

    private BtStrategy strategy(String configJson) {
        BtStrategy strategy = new BtStrategy();
        strategy.setId(508L);
        strategy.setName("OI-Funding-Divergence-BTC-1h-v1");
        strategy.setStrategyType("OI_FUNDING_DIVERGENCE");
        strategy.setConfigJson(configJson);
        return strategy;
    }

    private BtLiveSignal openLong(long strategyId, String symbol, String interval, String entry, String qty) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setId(260L);
        signal.setStrategyId(strategyId);
        signal.setSymbol(symbol);
        signal.setIntervalCode(interval);
        signal.setSide("LONG");
        signal.setAutoTraded(true);
        signal.setActualEntryPrice(new BigDecimal(entry));
        signal.setOcoQty(new BigDecimal(qty));
        signal.setSuggestedSl(new BigDecimal(entry).multiply(new BigDecimal("0.88")));
        signal.setSuggestedTp(new BigDecimal(entry).multiply(new BigDecimal("1.06")));
        signal.setOcoOrderListId(3727763466544136192L);
        signal.setCreatedAt(LocalDateTime.parse("2026-07-09T12:00:00"));
        signal.setBarOpenTime(LocalDateTime.parse("2026-07-09T08:00:00"));
        return signal;
    }
}
