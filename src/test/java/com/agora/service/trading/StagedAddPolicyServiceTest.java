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

    @Test
    void readinessIgnoresHoldAuditWithBuyThresholdWhenSelectingLatestBuyLikeCandidate() {
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
        when(runtimeDecisionEvidenceService.listRecent(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(decisionAuditRepository.findWindow(any(), any(), anyString(), any(), anyBoolean(), anyList(), any()))
                .thenReturn(List.of(
                        audit(77357L, "2026-07-09T12:00:04", null, "ATTENTION_HIT",
                                "AutonomousExecutionIntent", "Live autonomous execution evidence required",
                                """
                                        {
                                          "side":"LONG",
                                          "expected_r":0.43,
                                          "min_expected_r":0.2,
                                          "candidate_entry":62784.6,
                                          "candidate_tp":66551.68,
                                          "candidate_sl":55250.45,
                                          "tqs_band":"SMALL_DRY_RUN"
                                        }
                                        """),
                        audit(77360L, "2026-07-09T14:00:00", "SIGNAL_EVAL", null, "HOLD",
                                """
                                        {"buyThreshold":0.8,"tqsBand":"PROBE_DRY_RUN"}
                                        """),
                        audit(77353L, "2026-07-09T10:00:04", "SIGNAL_EVAL", null, "BUY",
                                """
                                        {"buyThreshold":0.8}
                                        """),
                        audit(77352L, "2026-07-09T10:00:04", "ENTRY_SKIP", "TradePlanQualityGate",
                                "risk_reward_below_min",
                                """
                                        {
                                          "side":"LONG",
                                          "expected_r":0.42,
                                          "min_expected_r":0.2,
                                          "candidateEntry":62952.9,
                                          "candidateTp":66730.07,
                                          "candidateSl":55398.55,
                                          "tqsBand":"PROBE_DRY_RUN"
                                        }
                                        """)
                ));

        StagedAddPolicyService.Evaluation evaluation = service.evaluate(
                "BTCUSDT",
                508L,
                "LONG",
                "1h",
                null,
                null,
                null,
                null,
                null);

        assertThat(evaluation.expectedR()).isEqualTo(0.43);
        assertThat(evaluation.tqsBand()).isEqualTo("SMALL_DRY_RUN");
        assertThat(evaluation.currentOpportunity().auditId()).isEqualTo(77357L);
        assertThat(evaluation.currentOpportunity().barOpenTime()).isEqualTo(LocalDateTime.parse("2026-07-09T12:00:04"));
        assertThat(evaluation.currentOpportunity().entry()).isEqualByComparingTo("62784.6");
        assertThat(evaluation.currentOpportunity().tp()).isEqualByComparingTo("66551.68");
        assertThat(evaluation.currentOpportunity().sl()).isEqualByComparingTo("55250.45");
        assertThat(evaluation.blockers()).doesNotContain("EV_UNKNOWN");
        assertThat(evaluation.blockers()).doesNotContain("TQS_UNKNOWN");
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

    private com.agora.model.BtDecisionAudit audit(long id,
                                                  String eventTime,
                                                  String eventType,
                                                  String blocker,
                                                  String reason,
                                                  String contextJson) {
        return audit(id, eventTime, "2026-07-09T09:00:00", eventType, blocker, reason, contextJson);
    }

    private com.agora.model.BtDecisionAudit audit(long id,
                                                  String eventTime,
                                                  String barOpenTime,
                                                  String eventType,
                                                  String blocker,
                                                  String reason,
                                                  String contextJson) {
        com.agora.model.BtDecisionAudit audit = new com.agora.model.BtDecisionAudit();
        audit.setId(id);
        audit.setStrategyId(508L);
        audit.setSymbol("BTCUSDT");
        audit.setIntervalCode("1h");
        audit.setBarOpenTime(barOpenTime == null ? null : LocalDateTime.parse(barOpenTime));
        audit.setEventTime(LocalDateTime.parse(eventTime));
        audit.setEventType(eventType);
        audit.setOutcome("INFO");
        audit.setBlocker(blocker);
        audit.setReason(reason);
        audit.setContextJson(contextJson);
        return audit;
    }
}
