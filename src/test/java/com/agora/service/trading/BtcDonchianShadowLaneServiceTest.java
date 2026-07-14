package com.agora.service.trading;

import com.agora.config.properties.BtcDonchianShadowProperties;
import com.agora.model.BtDecisionAudit;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_FIRST_OPEN_TIME;
import static com.agora.service.trading.BtcDonchianShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.POLICY_MODE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SOURCE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SYMBOL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BtcDonchianShadowLaneServiceTest {

    @Test
    void offModeHasNoDatabaseSideEffect() {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.OFF, true);

        fixture.service.evaluate(bar(GOLDEN_FIRST_OPEN_TIME));

        verify(fixture.auditRepository, never()).save(any());
        verify(fixture.evidenceRepository, never()).save(any());
        verify(fixture.klineRepository, never())
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void evidenceDisabledDropsBarWithoutCreatingPartialAudit() {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.SHADOW, false);

        fixture.service.evaluate(bar(GOLDEN_FIRST_OPEN_TIME));

        verify(fixture.auditRepository, never()).save(any());
        verify(fixture.evidenceRepository, never()).save(any());
    }

    @Test
    void bootstrapWritesOneShadowOnlyStateAndNeverClaimsOrderCapability() throws Exception {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.SHADOW, true);
        List<MdKline> firstDay = bars(GOLDEN_FIRST_OPEN_TIME, 24);
        MdKline event = firstDay.get(firstDay.size() - 1);
        when(fixture.klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        SYMBOL, INTERVAL, SOURCE, GOLDEN_FIRST_OPEN_TIME, event.getOpenTime()))
                .thenReturn(firstDay);

        fixture.service.evaluate(event);

        ArgumentCaptor<BtDecisionAudit> audit = ArgumentCaptor.forClass(BtDecisionAudit.class);
        verify(fixture.auditRepository).save(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(BtcDonchianShadowLaneService.EVENT_TYPE);
        assertThat(audit.getValue().getContextJson()).contains("\"bootstrap\":true", "\"orderSent\":false");

        ArgumentCaptor<RuntimeDecisionEvidence> evidence = ArgumentCaptor.forClass(RuntimeDecisionEvidence.class);
        verify(fixture.evidenceRepository).save(evidence.capture());
        RuntimeDecisionEvidence saved = evidence.getValue();
        assertThat(saved.getPolicyMode()).isEqualTo(POLICY_MODE);
        assertThat(saved.getExecutionMode()).isEqualTo("SHADOW_ONLY");
        assertThat(saved.getOrderSent()).isFalse();
        assertThat(saved.getOcoPlanCreated()).isFalse();
        assertThat(saved.getSuppressionReason()).isEqualTo("SHADOW_ONLY_NO_ORDER_CAPABILITY");
        JsonNode snapshot = fixture.objectMapper.readTree(saved.getFeaturesSnapshotJson());
        assertThat(snapshot.path("bootstrap").asBoolean()).isTrue();
        assertThat(snapshot.path("stateAfter").path("processedBars").asLong()).isEqualTo(24);
        assertThat(snapshot.path("stateAfterSha256").asText()).isNotBlank();
        assertThat(snapshot.path("liveImplementationPresent").asBoolean(true)).isFalse();
    }

    @Test
    void restartRestoresHashCheckedStateAndPersistsEveryCatchUpBarInOrder() throws Exception {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.SHADOW, true);
        List<MdKline> firstDay = bars(GOLDEN_FIRST_OPEN_TIME, 24);
        BtcDonchianShadowEngine.State state = fixture.engine.initialState();
        for (MdKline row : firstDay) fixture.engine.step(state, row);
        RuntimeDecisionEvidence prior = evidenceForState(fixture, state, firstDay.get(firstDay.size() - 1));
        when(fixture.evidenceRepository.findByPolicyModeAndSymbolAndIntervalCodeOrderByIdDesc(
                eq(POLICY_MODE), eq(SYMBOL), eq(INTERVAL), any(Pageable.class)))
                .thenReturn(List.of(prior));

        List<MdKline> catchUp = bars(GOLDEN_FIRST_OPEN_TIME.plusDays(1), 3);
        MdKline event = catchUp.get(catchUp.size() - 1);
        when(fixture.klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        SYMBOL, INTERVAL, SOURCE, catchUp.get(0).getOpenTime(), event.getOpenTime()))
                .thenReturn(catchUp);

        fixture.service.evaluate(event);

        ArgumentCaptor<RuntimeDecisionEvidence> captor = ArgumentCaptor.forClass(RuntimeDecisionEvidence.class);
        verify(fixture.evidenceRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        List<RuntimeDecisionEvidence> saved = captor.getAllValues();
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getExecutionMode()).isEqualTo("SHADOW_ONLY");
            assertThat(row.getOrderSent()).isFalse();
        });
        assertThat(saved.get(0).getFreshnessState()).isEqualTo("CAUSAL_CATCH_UP_COMPLETE");
        assertThat(saved.get(1).getFreshnessState()).isEqualTo("CAUSAL_CATCH_UP_COMPLETE");
        assertThat(saved.get(2).getFreshnessState()).isEqualTo("CURRENT_CLOSED_BAR");
        JsonNode last = fixture.objectMapper.readTree(saved.get(2).getFeaturesSnapshotJson());
        assertThat(last.path("stateAfter").path("processedBars").asLong()).isEqualTo(27);
        assertThat(LocalDateTime.parse(last.path("stateAfter").path("lastProcessedBarOpenTime").asText()))
                .isEqualTo(event.getOpenTime());
    }

    @Test
    void missingCatchUpBarWritesRecoverableBlockerWithoutAdvancingState() throws Exception {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.SHADOW, true);
        List<MdKline> firstDay = bars(GOLDEN_FIRST_OPEN_TIME, 24);
        BtcDonchianShadowEngine.State state = fixture.engine.initialState();
        for (MdKline row : firstDay) fixture.engine.step(state, row);
        RuntimeDecisionEvidence prior = evidenceForState(fixture, state, firstDay.get(firstDay.size() - 1));
        when(fixture.evidenceRepository.findByPolicyModeAndSymbolAndIntervalCodeOrderByIdDesc(
                eq(POLICY_MODE), eq(SYMBOL), eq(INTERVAL), any(Pageable.class)))
                .thenReturn(List.of(prior));
        MdKline event = bar(GOLDEN_FIRST_OPEN_TIME.plusDays(1).plusHours(2));
        when(fixture.klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        eq(SYMBOL), eq(INTERVAL), eq(SOURCE), any(), eq(event.getOpenTime())))
                .thenReturn(List.of(bar(GOLDEN_FIRST_OPEN_TIME.plusDays(1)), event));

        fixture.service.evaluate(event);

        ArgumentCaptor<BtDecisionAudit> audit = ArgumentCaptor.forClass(BtDecisionAudit.class);
        verify(fixture.auditRepository).save(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(BtcDonchianShadowLaneService.BLOCK_EVENT_TYPE);
        assertThat(audit.getValue().getBlocker()).isEqualTo("CATCH_UP_HISTORY_INCOMPLETE");
        ArgumentCaptor<RuntimeDecisionEvidence> evidence = ArgumentCaptor.forClass(RuntimeDecisionEvidence.class);
        verify(fixture.evidenceRepository).save(evidence.capture());
        assertThat(evidence.getValue().getFinalOutcome()).isEqualTo("BLOCKED_DATA_QUALITY");
        assertThat(evidence.getValue().getOrderSent()).isFalse();
        JsonNode snapshot = fixture.objectMapper.readTree(evidence.getValue().getFeaturesSnapshotJson());
        assertThat(LocalDateTime.parse(snapshot.path("stateAfter").path("lastProcessedBarOpenTime").asText()))
                .isEqualTo(firstDay.get(firstDay.size() - 1).getOpenTime());
    }

    @Test
    void duplicateCanonicalAuditIsIdempotent() {
        Fixture fixture = fixture(BtcDonchianShadowProperties.Mode.SHADOW, true);
        MdKline event = bar(GOLDEN_FIRST_OPEN_TIME);
        when(fixture.auditRepository.existsBySymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                SYMBOL, INTERVAL, event.getOpenTime(), BtcDonchianShadowLaneService.EVENT_TYPE))
                .thenReturn(true);

        fixture.service.evaluate(event);

        verify(fixture.auditRepository, never()).save(any());
        verify(fixture.evidenceRepository, never()).save(any());
    }

    private RuntimeDecisionEvidence evidenceForState(Fixture fixture,
                                                      BtcDonchianShadowEngine.State state,
                                                      MdKline bar) throws Exception {
        String hash = fixture.engine.stateSha256(state);
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setId(10L);
        evidence.setPolicyMode(POLICY_MODE);
        evidence.setSymbol(SYMBOL);
        evidence.setIntervalCode(INTERVAL);
        evidence.setExecutionMode("SHADOW_ONLY");
        evidence.setOrderSent(false);
        evidence.setFinalOutcome("SHADOW_OBSERVED");
        evidence.setFeaturesSnapshotJson(fixture.objectMapper.writeValueAsString(java.util.Map.of(
                "evidenceSchemaVersion", BtcDonchianShadowPolicy.EVIDENCE_SCHEMA_VERSION,
                "policyMode", POLICY_MODE,
                "barOpenTime", bar.getOpenTime(),
                "barCloseTime", bar.getCloseTime(),
                "stateAfterSha256", hash,
                "stateAfter", state)));
        return evidence;
    }

    private Fixture fixture(BtcDonchianShadowProperties.Mode mode, boolean evidenceEnabled) {
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        BtDecisionAuditRepository auditRepository = mock(BtDecisionAuditRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        RuntimeDecisionEvidenceService runtimeEvidenceService = mock(RuntimeDecisionEvidenceService.class);
        when(runtimeEvidenceService.isEnabled()).thenReturn(evidenceEnabled);
        when(evidenceRepository.findByPolicyModeAndSymbolAndIntervalCodeOrderByIdDesc(
                eq(POLICY_MODE), eq(SYMBOL), eq(INTERVAL), any(Pageable.class)))
                .thenReturn(List.of());
        AtomicLong ids = new AtomicLong(100);
        when(auditRepository.save(any(BtDecisionAudit.class))).thenAnswer(invocation -> {
            BtDecisionAudit audit = invocation.getArgument(0);
            audit.setId(ids.incrementAndGet());
            return audit;
        });
        when(evidenceRepository.save(any(RuntimeDecisionEvidence.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        BtcDonchianShadowEngine engine = new BtcDonchianShadowEngine(objectMapper);
        BtcDonchianShadowLaneService service = new BtcDonchianShadowLaneService(
                new BtcDonchianShadowProperties(mode), klineRepository, auditRepository,
                evidenceRepository, runtimeEvidenceService, engine, objectMapper);
        return new Fixture(service, engine, objectMapper, klineRepository, auditRepository, evidenceRepository);
    }

    private List<MdKline> bars(LocalDateTime start, int count) {
        List<MdKline> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(bar(start.plusHours(i)));
        return result;
    }

    private MdKline bar(LocalDateTime openTime) {
        MdKline bar = new MdKline();
        bar.setSymbol(SYMBOL);
        bar.setIntervalCode(INTERVAL);
        bar.setSource(SOURCE);
        bar.setOpenTime(openTime);
        bar.setCloseTime(openTime.plusHours(1));
        bar.setOpenPrice(new BigDecimal("100"));
        bar.setHighPrice(new BigDecimal("101"));
        bar.setLowPrice(new BigDecimal("99"));
        bar.setClosePrice(new BigDecimal("100"));
        bar.setVolume(BigDecimal.ONE);
        return bar;
    }

    private record Fixture(
            BtcDonchianShadowLaneService service,
            BtcDonchianShadowEngine engine,
            ObjectMapper objectMapper,
            MdKlineRepository klineRepository,
            BtDecisionAuditRepository auditRepository,
            RuntimeDecisionEvidenceRepository evidenceRepository
    ) {
    }
}
