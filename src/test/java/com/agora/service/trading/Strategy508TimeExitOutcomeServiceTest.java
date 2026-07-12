package com.agora.service.trading;

import com.agora.config.properties.Strategy508TimeExitProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.TelegramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class Strategy508TimeExitOutcomeServiceTest {

    @Test
    void offModeDoesNotReadOrMutateEvidence() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.OFF);

        fixture.service.processPending();

        verifyNoInteractions(fixture.evidenceRepository, fixture.liveSignalRepository, fixture.closeService);
    }

    @Test
    void shadowOutcomeUsesExactMinuteSimulatorAndNeverSendsTelegram() throws Exception {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.SHADOW);
        LocalDateTime decision = LocalDateTime.now(ZoneOffset.UTC).minusHours(25);
        RuntimeDecisionEvidence evidence = evidence(null, decision);
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(List.of(evidence));
        Strategy508TimeExitCandidateService.EntryIntent entry =
                new Strategy508TimeExitCandidateService.EntryIntent(decision.minusHours(4), decision, "RAW_BUY_4H");
        when(fixture.candidateService.simulateSingle(any(), any(), any())).thenReturn(
                Strategy508TimeExitCandidateService.EventResult.finalized(
                        entry, "TIME_EXIT_24H", decision, decision.plusHours(24),
                        bd("100.05"), bd("100.95"), bd("0.08"), bd("0.06"),
                        bd("0.02"), bd("0.8"), bd("1.0"), bd("-0.5"), bd("-0.3"), 1.0));

        fixture.service.processPending();

        assertThat(evidence.getFinalOutcome()).isEqualTo("TIME_EXIT_24H");
        assertThat(evidence.getReason()).isEqualTo("SHADOW_OUTCOME_FINALIZED");
        JsonNode context = fixture.mapper.readTree(evidence.getPolicyInputsJson());
        assertThat(context.path("netPnlUsdt").asText()).isEqualTo("0.08000000");
        assertThat(context.path("feeCoverageComplete").asBoolean()).isTrue();
        verifyNoInteractions(fixture.telegram);
    }

    @Test
    void live24HourCloseStoresExactNetPnlAndNotifiesRealExit() throws Exception {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO);
        LocalDateTime decision = LocalDateTime.now(ZoneOffset.UTC).minusHours(25);
        RuntimeDecisionEvidence evidence = evidence(901L, decision);
        evidence.setPolicyInputsJson("{\"entryFeeUsdt\":\"0.01\"}");
        BtLiveSignal open = policySignal(901L, decision);
        BtLiveSignal closed = policySignal(901L, decision);
        closed.setExitTime(LocalDateTime.now(ZoneOffset.UTC));
        closed.setExitPrice(bd("101"));
        closed.setExitReason("TIME_EXIT_24H");
        closed.setRealizedPnl(bd("0.10"));
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(List.of(evidence));
        when(fixture.liveSignalRepository.findById(901L))
                .thenReturn(Optional.of(open), Optional.of(closed));
        when(fixture.closeService.closeAtMarket(901L, "TIME_EXIT_24H"))
                .thenReturn(new SpotPositionCloseService.CloseResult(
                        901L, "CLOSED", "TIME_EXIT_24H", bd("0.001"), bd("0.001"),
                        BigDecimal.ZERO, bd("101"), bd("0.10"), bd("0.01"), "USDT", null));

        fixture.service.processPending();

        assertThat(evidence.getFinalOutcome()).isEqualTo("TIME_EXIT_24H");
        JsonNode context = fixture.mapper.readTree(evidence.getPolicyInputsJson());
        assertThat(context.path("grossPnlUsdt").asText()).isEqualTo("0.10000000");
        assertThat(context.path("entryFeeUsdt").asText()).isEqualTo("0.01");
        assertThat(context.path("totalExitFeeUsdt").asText()).isEqualTo("0.01000000");
        assertThat(context.path("netPnlUsdt").asText()).isEqualTo("0.08000000");
        assertThat(context.path("feeCoverageComplete").asBoolean()).isTrue();
        verify(fixture.telegram).sendAlert(any(), eq(false), eq("Strategy508TimeExit"), eq("INFO"));
    }

    @Test
    void missingFeeOnAnyPartialExitKeepsFinalNetPnlFailClosed() throws Exception {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO);
        LocalDateTime decision = LocalDateTime.now(ZoneOffset.UTC).minusHours(25);
        RuntimeDecisionEvidence evidence = evidence(903L, decision);
        evidence.setPolicyInputsJson("{\"entryFeeUsdt\":\"0.01\"}");
        BtLiveSignal open = policySignal(903L, decision);
        BtLiveSignal closed = policySignal(903L, decision);
        closed.setExitTime(LocalDateTime.now(ZoneOffset.UTC));
        closed.setExitPrice(bd("101"));
        closed.setExitReason("TIME_EXIT_24H");
        closed.setRealizedPnl(bd("0.06"));
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(List.of(evidence));
        when(fixture.liveSignalRepository.findById(903L))
                .thenReturn(Optional.of(open), Optional.of(open), Optional.of(closed));
        when(fixture.closeService.closeAtMarket(903L, "TIME_EXIT_24H"))
                .thenReturn(
                        new SpotPositionCloseService.CloseResult(
                                903L, "PARTIAL", "PARTIAL_FILL_REPROTECTED", bd("0.001"), bd("0.0004"),
                                bd("0.0006"), bd("101"), bd("0.04"), null, null, 9903L),
                        new SpotPositionCloseService.CloseResult(
                                903L, "CLOSED", "TIME_EXIT_24H", bd("0.0006"), bd("0.0006"),
                                BigDecimal.ZERO, bd("101"), bd("0.06"), bd("0.01"), "USDT", null));

        fixture.service.processPending();
        fixture.service.processPending();

        JsonNode context = fixture.mapper.readTree(evidence.getPolicyInputsJson());
        assertThat(context.path("grossPnlUsdt").asText()).isEqualTo("0.10000000");
        assertThat(context.path("partialExitFeeCoverageComplete").asBoolean()).isFalse();
        assertThat(context.path("feeCoverageComplete").asBoolean()).isFalse();
        assertThat(context.path("netPnlUsdt").isNull()).isTrue();
        assertThat(evidence.getReason()).isEqualTo("LIVE_OUTCOME_FINALIZED_FEE_GAP");
    }

    @Test
    void legacyStrategy508PositionTagMismatchCanNeverBeClosedByThisLane() throws Exception {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO);
        LocalDateTime decision = LocalDateTime.now(ZoneOffset.UTC).minusHours(25);
        RuntimeDecisionEvidence evidence = evidence(260L, decision);
        BtLiveSignal legacy = policySignal(260L, decision);
        legacy.setFilterReason("OI_FUNDING_DIVERGENCE");
        when(fixture.evidenceRepository.findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(eq(
                Strategy508TimeExitPolicy.POLICY_MODE), any())).thenReturn(List.of(evidence));
        when(fixture.liveSignalRepository.findById(260L)).thenReturn(Optional.of(legacy));

        fixture.service.processPending();

        assertThat(evidence.getFinalOutcome()).isEqualTo("POLICY_POSITION_TAG_MISMATCH");
        JsonNode context = fixture.mapper.readTree(evidence.getPolicyInputsJson());
        assertThat(context.path("exitParityGap").asBoolean()).isTrue();
        verify(fixture.closeService, never()).closeAtMarket(any(), any());
    }

    private Fixture fixture(Strategy508TimeExitProperties.Mode mode) {
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        Strategy508TimeExitCandidateService candidateService = mock(Strategy508TimeExitCandidateService.class);
        SpotPositionCloseService closeService = mock(SpotPositionCloseService.class);
        OkxTradingService okx = mock(OkxTradingService.class);
        TelegramService telegram = mock(TelegramService.class);
        ObjectMapper mapper = new ObjectMapper();
        when(evidenceRepository.save(any(RuntimeDecisionEvidence.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Strategy508TimeExitOutcomeService service = new Strategy508TimeExitOutcomeService(
                new Strategy508TimeExitProperties(mode, mode == Strategy508TimeExitProperties.Mode.LIVE_MICRO),
                evidenceRepository, liveSignalRepository, klineRepository, candidateService,
                closeService, okx, mapper, telegram);
        return new Fixture(service, evidenceRepository, liveSignalRepository, candidateService,
                closeService, telegram, mapper);
    }

    private RuntimeDecisionEvidence evidence(Long liveSignalId, LocalDateTime decision) {
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setId(1L);
        evidence.setDecisionId(1L);
        evidence.setEvidenceTime(decision);
        evidence.setSymbol("BTCUSDT");
        evidence.setStrategyId(508L);
        evidence.setIntervalCode("4h");
        evidence.setPolicyMode(Strategy508TimeExitPolicy.POLICY_MODE);
        evidence.setSelectedAction("STRATEGY_508_TIME_EXIT_SHADOW");
        evidence.setFinalOutcome("PENDING_24H");
        evidence.setLiveSignalId(liveSignalId);
        evidence.setPolicyInputsJson("{\"barOpenTime\":\"" + decision.minusHours(4)
                + "\",\"decisionTime\":\"" + decision + "\"}");
        return evidence;
    }

    private BtLiveSignal policySignal(Long id, LocalDateTime createdAt) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setId(id);
        signal.setStrategyId(508L);
        signal.setSymbol("BTCUSDT");
        signal.setIntervalCode("4h");
        signal.setSide("LONG");
        signal.setAutoTraded(true);
        signal.setCreatedAt(createdAt);
        signal.setFilterReason(Strategy508TimeExitPolicy.POLICY_MODE);
        signal.setActualEntryPrice(bd("100"));
        signal.setEntryPrice(bd("100"));
        signal.setTradedQty(bd("0.001"));
        return signal;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record Fixture(Strategy508TimeExitOutcomeService service,
                           RuntimeDecisionEvidenceRepository evidenceRepository,
                           BtLiveSignalRepository liveSignalRepository,
                           Strategy508TimeExitCandidateService candidateService,
                           SpotPositionCloseService closeService,
                           TelegramService telegram,
                           ObjectMapper mapper) {
    }
}
