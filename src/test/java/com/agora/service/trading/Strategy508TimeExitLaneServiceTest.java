package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.Strategy508TimeExitProperties;
import com.agora.model.BtDecisionAudit;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.BtStrategyService;
import com.agora.service.TelegramService;
import com.agora.service.backtest.BacktestEngine;
import com.agora.service.backtest.Strategy;
import com.agora.service.backtest.StrategyContext;
import com.agora.service.backtest.StrategyRegistry;
import com.agora.service.backtest.StrategySignal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Strategy508TimeExitLaneServiceTest {

    @Test
    void policyForcesCloseTimeFreshnessFailClosedAtNinetyMinutes() {
        Map<String, Object> config = new HashMap<>();
        config.put("marketFeatureFreshnessFailClosed", false);
        config.put("fundingMaxAgeMinutes", 999);

        Strategy508TimeExitPolicy.applyMarketFeatureFreshnessPolicy(config);

        assertThat(config)
                .containsEntry("marketFeatureFreshnessFailClosed", true)
                .containsEntry("marketFeatureReferenceTimeMode", "BAR_CLOSE")
                .containsEntry("fundingMaxAgeMinutes", 90)
                .containsEntry("oiMaxAgeMinutes", 90)
                .containsEntry("dexFlowMaxAgeMinutes", 90)
                .containsEntry("spreadMaxAgeMinutes", 90);
    }

    @Test
    void offModeDoesNothing() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.OFF, false);

        fixture.service.evaluate(eventNow());

        verifyNoInteractions(fixture.strategyService, fixture.evidenceRepository, fixture.okx);
    }

    @Test
    void ignoresWrongIntervalAndSource() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.SHADOW, false);
        MdKline wrongInterval = eventNow();
        wrongInterval.setIntervalCode("1h");
        MdKline wrongSource = eventNow();
        wrongSource.setSource("binance");

        fixture.service.evaluate(wrongInterval);
        fixture.service.evaluate(wrongSource);

        verifyNoInteractions(fixture.strategyService, fixture.evidenceRepository, fixture.okx);
    }

    @Test
    void eligibleShadowBuyStaysPendingAndNeverPlacesOrderOrTelegram() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.SHADOW, false);

        fixture.service.evaluate(eventNow());

        RuntimeDecisionEvidence evidence = finalEvidence(fixture);
        assertThat(evidence.getSelectedAction()).isEqualTo("STRATEGY_508_TIME_EXIT_SHADOW");
        assertThat(evidence.getFinalOutcome()).isEqualTo("PENDING_24H");
        assertThat(evidence.getOrderSent()).isFalse();
        assertThat(evidence.getEvResultJson()).contains("OBSERVE_ONLY")
                .contains("\"wouldBlock\":true")
                .contains("\"pWinTrusted\":false")
                .contains("UNAVAILABLE_STRATEGY_508_HAS_NO_CALIBRATED_WIN_PROBABILITY")
                .contains("\"blocksEntry\":false");
        assertThat(evidence.getTqsResultJson()).contains("OBSERVE_ONLY").contains("false");
        assertThat(evidence.getWarningsJson()).contains("OBSERVE_ONLY").contains("false");
        assertThat(evidence.getPolicyInputsJson())
                .contains("\"effectivePolicyConfigSha256\":\"")
                .contains("CURRENT_DB_CONFIG_PLUS_FIXED_VERSIONED_POLICY_AT_DECISION_TIME")
                .contains("\"cohortSchemaVersion\":\"STRATEGY_508_TIME_EXIT_COHORT_V1\"")
                .contains("\"executableCohortEligible\":true")
                .contains("\"promotionCohort\":\"EXECUTABLE_SHADOW\"");
        verify(fixture.okx, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
        verifyNoInteractions(fixture.telegram);
    }

    @Test
    void hardBlockedShadowTracksRawCounterfactualButCannotEnterExecutableCohort() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.SHADOW, false);
        MdKline stale = eventNow();
        stale.setOpenTime(LocalDateTime.now(ZoneOffset.UTC).minusHours(6));
        stale.setCloseTime(stale.getOpenTime().plusHours(4));
        fixture.stubBars(stale);

        fixture.service.evaluate(stale);

        RuntimeDecisionEvidence evidence = finalEvidence(fixture);
        assertThat(evidence.getSelectedAction()).isEqualTo("STRATEGY_508_TIME_EXIT_SHADOW_BLOCKED");
        assertThat(evidence.getFinalOutcome()).isEqualTo("PENDING_24H");
        assertThat(evidence.getReason()).isEqualTo("SHADOW_HARD_GATE_BLOCKED_COUNTERFACTUAL_PENDING");
        assertThat(evidence.getBlockerReason()).contains("DATA_FRESHNESS_STALE");
        assertThat(evidence.getPolicyInputsJson())
                .contains("\"rawSignalCounterfactualEligible\":true")
                .contains("\"counterfactualOutcomeTracked\":true")
                .contains("\"executableCohortEligible\":false")
                .contains("\"promotionCohort\":\"RAW_SIGNAL_COUNTERFACTUAL\"")
                .contains("\"executionGateOutcome\":\"HARD_BLOCKED\"");
        verify(fixture.okx, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void oldStrategy508PositionIsNotCountedAsExperimentPositionOrDailyOrder() throws Exception {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.SHADOW, false);
        BtLiveSignal legacy = new BtLiveSignal();
        legacy.setId(260L);
        legacy.setStrategyId(508L);
        legacy.setSymbol("BTCUSDT");
        legacy.setIntervalCode("1h");
        legacy.setAutoTraded(true);
        legacy.setFilterReason("OI_FUNDING_DIVERGENCE");
        legacy.setOcoOrderListId(2600L);
        when(fixture.liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(508L))
                .thenReturn(List.of(legacy));
        when(fixture.liveSignalRepository.findByStrategyIdAndCreatedAtAfter(eq(508L), any()))
                .thenReturn(List.of(legacy));
        when(fixture.liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull())
                .thenReturn(List.of(legacy));
        when(fixture.okx.getAlgoOrder("BTCUSDT", 2600L))
                .thenReturn(new ObjectMapper().readTree("{\"state\":\"live\",\"ordIdList\":[]}"));

        fixture.service.evaluate(eventNow());

        RuntimeDecisionEvidence evidence = finalEvidence(fixture);
        assertThat(evidence.getFinalOutcome()).isEqualTo("PENDING_24H");
        assertThat(evidence.getPolicyInputsJson()).contains("\"experimentOrdersToday\":0");
        assertThat(evidence.getPolicyInputsJson()).doesNotContain("EXPERIMENT_OPEN_POSITION_CAP");
        assertThat(evidence.getPolicyInputsJson()).doesNotContain("EXPERIMENT_DAILY_ORDER_CAP");
    }

    @Test
    void liveReadinessBlockerIsBoundBeforeAuditAndEvidenceCohortMetadata() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        when(fixture.readinessService.snapshot("BTCUSDT", true))
                .thenReturn(blockedSnapshot("FORWARD_SHADOW_GATE_NOT_READY"));

        fixture.service.evaluate(eventNow());

        RuntimeDecisionEvidence evidence = finalEvidence(fixture);
        assertThat(evidence.getSelectedAction()).isEqualTo("STRATEGY_508_TIME_EXIT_LIVE_BLOCKED");
        assertThat(evidence.getExecutionMode()).isEqualTo("LIVE_MICRO_BLOCKED");
        assertThat(evidence.getTerminalBlocker()).isEqualTo("FORWARD_SHADOW_GATE_NOT_READY");
        assertThat(evidence.getPolicyInputsJson())
                .contains("FORWARD_SHADOW_GATE_NOT_READY")
                .contains("\"hardGateClear\":false")
                .contains("\"executableCohortEligible\":false")
                .contains("\"promotionCohort\":\"RAW_SIGNAL_COUNTERFACTUAL\"")
                .contains("\"executionGateOutcome\":\"HARD_BLOCKED\"");
        verify(fixture.okx, never()).placeMarketBuy(any(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void liveMicroMockPathBuysTenUsdtAttachesOcoAndNotifiesFill() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        when(fixture.readinessService.snapshot("BTCUSDT", true)).thenReturn(readySnapshot());
        when(fixture.okx.placeMarketBuy("BTCUSDT", 10.0)).thenReturn(buyFill());
        when(fixture.okx.placeOco(eq("BTCUSDT"), eq(bd("0.0000999")), any(), any())).thenReturn(777L);

        fixture.service.evaluate(eventNow());

        RuntimeDecisionEvidence evidence = finalEvidence(fixture);
        assertThat(evidence.getSelectedAction()).isEqualTo("STRATEGY_508_TIME_EXIT_LIVE_EXECUTED");
        assertThat(evidence.getFinalOutcome()).isEqualTo("PENDING_24H");
        assertThat(evidence.getOrderSent()).isTrue();
        assertThat(evidence.getLiveSignalId()).isEqualTo(901L);
        verify(fixture.okx).placeMarketBuy("BTCUSDT", 10.0);
        verify(fixture.okx).placeOco(eq("BTCUSDT"), eq(bd("0.0000999")), any(), any());
        verify(fixture.telegram).sendAlert(any(), eq(false), eq("Strategy508TimeExit"), eq("INFO"));
    }

    @Test
    void ocoAttachFailureImmediatelyAttemptsEmergencyClose() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        when(fixture.readinessService.snapshot("BTCUSDT", true)).thenReturn(readySnapshot());
        when(fixture.okx.placeMarketBuy("BTCUSDT", 10.0)).thenReturn(buyFill());
        when(fixture.okx.placeOco(eq("BTCUSDT"), eq(bd("0.0000999")), any(), any()))
                .thenThrow(new RuntimeException("oco rejected"));
        when(fixture.closeService.closeAtMarket(901L, "ENTRY_OCO_ATTACH_FAILED"))
                .thenReturn(new SpotPositionCloseService.CloseResult(
                        901L, "CLOSED", "ENTRY_OCO_ATTACH_FAILED", bd("0.0000999"),
                        bd("0.0000999"), BigDecimal.ZERO, bd("100000"), BigDecimal.ZERO,
                        bd("0.01"), "USDT", null));

        fixture.service.evaluate(eventNow());

        RuntimeDecisionEvidence evidence = finalEvidence(fixture);
        assertThat(evidence.getFinalOutcome()).isEqualTo("CRITICAL_ORDER_SENT_OCO_ATTACH_FAILED");
        assertThat(evidence.getOrderSent()).isTrue();
        verify(fixture.closeService).closeAtMarket(901L, "ENTRY_OCO_ATTACH_FAILED");
        verify(fixture.telegram).sendAlert(any(), eq(false), eq("Strategy508TimeExit"), eq("CRITICAL"));
    }

    @Test
    void acceptedOrderWithUnconfirmedFillBlocksRetryAndSendsCriticalAlert() {
        Fixture fixture = fixture(Strategy508TimeExitProperties.Mode.LIVE_MICRO, true);
        when(fixture.readinessService.snapshot("BTCUSDT", true)).thenReturn(readySnapshot());
        when(fixture.okx.placeMarketBuy("BTCUSDT", 10.0))
                .thenThrow(new RuntimeException("OKX order not filled after retries: ordId=5089001"));

        fixture.service.evaluate(eventNow());

        RuntimeDecisionEvidence evidence = finalEvidence(fixture);
        assertThat(evidence.getFinalOutcome()).isEqualTo("CRITICAL_ORDER_SENT_FILL_UNCONFIRMED");
        assertThat(evidence.getOrderSent()).isTrue();
        assertThat(evidence.getPolicyInputsJson()).contains("\"submittedOrderId\":\"5089001\"");
        verify(fixture.okx, never()).placeOco(any(), any(), any(), any());
        verify(fixture.telegram).sendAlert(any(), eq(false), eq("Strategy508TimeExit"), eq("CRITICAL"));
    }

    private Fixture fixture(Strategy508TimeExitProperties.Mode mode, boolean liveOrderEnabled) {
        Strategy508TimeExitProperties properties = new Strategy508TimeExitProperties(mode, liveOrderEnabled);
        BtStrategyService strategyService = mock(BtStrategyService.class);
        BacktestEngine backtestEngine = mock(BacktestEngine.class);
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        RuntimeDecisionEvidenceService runtimeEvidenceService = mock(RuntimeDecisionEvidenceService.class);
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        OkxTradingService okx = mock(OkxTradingService.class);
        OkxTradingProperties okxProperties = mock(OkxTradingProperties.class);
        DailyLossGuard dailyLossGuard = mock(DailyLossGuard.class);
        EventRiskLevelEngine eventRisk = mock(EventRiskLevelEngine.class);
        Strategy508TimeExitReadinessService readinessService = mock(Strategy508TimeExitReadinessService.class);
        SpotPositionCloseService closeService = mock(SpotPositionCloseService.class);
        TelegramService telegram = mock(TelegramService.class);
        ObjectMapper mapper = new ObjectMapper();

        Strategy strategy = new Strategy() {
            @Override
            public String getType() {
                return "TEST_508_BUY";
            }

            @Override
            public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
                return StrategySignal.BUY;
            }
        };
        BtStrategy entity = new BtStrategy();
        entity.setId(508L);
        entity.setName("Strategy 508");
        entity.setStrategyType(strategy.getType());
        entity.setConfigJson("{}");
        when(strategyService.getRequired(508L)).thenReturn(entity);
        when(strategyService.parseConfig("{}")).thenReturn(new HashMap<>());
        when(backtestEngine.buildIndicators(any(), anyMap())).thenReturn(new HashMap<>());
        when(auditRepository.existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                any(), any(), any(), any(), any())).thenReturn(false);
        AtomicLong ids = new AtomicLong(100);
        when(auditRepository.save(any(BtDecisionAudit.class))).thenAnswer(invocation -> {
            BtDecisionAudit audit = invocation.getArgument(0);
            if (audit.getId() == null) audit.setId(ids.incrementAndGet());
            return audit;
        });
        when(evidenceRepository.save(any(RuntimeDecisionEvidence.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runtimeEvidenceService.isEnabled()).thenReturn(true);
        when(eventRisk.evaluate("BTCUSDT")).thenReturn(new EventRiskLevelEngine.Snapshot(
                "BTCUSDT", 0, EventRiskLevelEngine.RiskLevel.R0, List.of(), Map.of(), LocalDateTime.now(ZoneOffset.UTC)));
        when(dailyLossGuard.check()).thenReturn(new DailyLossGuard.GuardResult(true, "OK", 0));
        when(okxProperties.getMaxOpenPositions()).thenReturn(10);
        when(okxProperties.isAllowConcurrentOnSameSymbol()).thenReturn(true);
        when(okxProperties.isEnabled()).thenReturn(true);
        when(okxProperties.hasPrivateCredentials()).thenReturn(true);
        when(liveSignalRepository.countByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(0L);
        when(liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(508L)).thenReturn(List.of());
        when(liveSignalRepository.findByStrategyIdAndCreatedAtAfter(eq(508L), any())).thenReturn(List.of());
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of());
        when(liveSignalRepository.save(any(BtLiveSignal.class))).thenAnswer(invocation -> {
            BtLiveSignal signal = invocation.getArgument(0);
            if (signal.getId() == null) signal.setId(901L);
            return signal;
        });

        Strategy508TimeExitLaneService service = new Strategy508TimeExitLaneService(
                properties, strategyService, new StrategyRegistry(List.of(strategy)), backtestEngine,
                klineRepository, auditRepository, evidenceRepository, runtimeEvidenceService,
                liveSignalRepository, okx, okxProperties, dailyLossGuard, eventRisk, readinessService,
                closeService, mapper, telegram);
        Fixture fixture = new Fixture(service, strategyService, klineRepository, evidenceRepository,
                liveSignalRepository, okx, readinessService, closeService, telegram);
        fixture.stubBars(eventNow());
        return fixture;
    }

    private RuntimeDecisionEvidence finalEvidence(Fixture fixture) {
        ArgumentCaptor<RuntimeDecisionEvidence> captor = ArgumentCaptor.forClass(RuntimeDecisionEvidence.class);
        verify(fixture.evidenceRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private Strategy508TimeExitReadinessService.ReadinessSnapshot readySnapshot() {
        return new Strategy508TimeExitReadinessService.ReadinessSnapshot(
                new ObjectMapper().createObjectNode(), true, List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private Strategy508TimeExitReadinessService.ReadinessSnapshot blockedSnapshot(String blocker) {
        return new Strategy508TimeExitReadinessService.ReadinessSnapshot(
                new ObjectMapper().createObjectNode(), false, List.of(blocker),
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private TradeResult buyFill() {
        TradeResult result = new TradeResult();
        result.setOrderId("buy-508");
        result.setAvgPrice(bd("100000"));
        result.setQty(bd("0.0000999"));
        result.setNetQty(bd("0.0000999"));
        result.setGrossQty(bd("0.0001"));
        result.setFeeAmount(bd("-0.0000001"));
        result.setFeeCurrency("BTC");
        result.setFeeUsdt(bd("0.01"));
        return result;
    }

    private static MdKline eventNow() {
        LocalDateTime close = LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        MdKline event = new MdKline();
        event.setSymbol("BTCUSDT");
        event.setIntervalCode("4h");
        event.setSource("okx");
        event.setOpenTime(close.minusHours(4));
        event.setCloseTime(close);
        event.setOpenPrice(bd("100000"));
        event.setHighPrice(bd("101000"));
        event.setLowPrice(bd("99000"));
        event.setClosePrice(bd("100000"));
        return event;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record Fixture(Strategy508TimeExitLaneService service,
                           BtStrategyService strategyService,
                           MdKlineRepository klineRepository,
                           RuntimeDecisionEvidenceRepository evidenceRepository,
                           BtLiveSignalRepository liveSignalRepository,
                           OkxTradingService okx,
                           Strategy508TimeExitReadinessService readinessService,
                           SpotPositionCloseService closeService,
                           TelegramService telegram) {
        void stubBars(MdKline event) {
            List<MdKline> bars = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                MdKline row = new MdKline();
                row.setSymbol("BTCUSDT");
                row.setIntervalCode("4h");
                row.setSource("okx");
                row.setOpenTime(event.getOpenTime().minusHours(i * 4L));
                row.setCloseTime(row.getOpenTime().plusHours(4));
                row.setOpenPrice(bd("100000"));
                row.setHighPrice(bd("101000"));
                row.setLowPrice(bd("99000"));
                row.setClosePrice(bd("100000"));
                bars.add(row);
            }
            bars.set(0, event);
            when(klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                    eq("BTCUSDT"), eq("4h"), eq("okx"), any(Pageable.class))).thenReturn(bars);
        }
    }
}
