package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TinyLiveMinimumOrderPreviewServiceTest {

    private final RuntimeDecisionEvidenceService evidenceService = mock(RuntimeDecisionEvidenceService.class);
    private final BtDecisionAuditRepository decisionAuditRepository = mock(BtDecisionAuditRepository.class);
    private final BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
    private final EventRiskLevelEngine eventRiskLevelEngine = mock(EventRiskLevelEngine.class);
    private final OkxTradingService okxTradingService = mock(OkxTradingService.class);
    private final AutoExplorationRolloutStateService rolloutStateService =
            mock(AutoExplorationRolloutStateService.class);

    @Test
    void missingBuyCandidateKeepsOcoPreflightPendingAsWarning() {
        TinyLiveMinimumOrderPreviewService service = service();

        TinyLiveMinimumOrderPreviewService.PreviewResult preview =
                service.preview("BTCUSDT", 574L, "LONG");

        assertThat(preview.status()).isEqualTo("NOT_READY_NO_CURRENT_BUY_CANDIDATE");
        assertThat(preview.denialReasons())
                .contains("NO_CURRENT_BUY_CANDIDATE", "RUNTIME_EVIDENCE_NOT_AVAILABLE")
                .doesNotContain("OCO_PREFLIGHT_FAILED");
        assertThat(preview.ocoPreflightStatus()).isEqualTo("NOT_READY_MISSING_ENTRY_TP_SL");
        assertThat(preview.warnings())
                .contains("ocoPreflightPendingUntilBuyCandidate=NOT_READY_MISSING_ENTRY_TP_SL");
    }

    @Test
    void ocoPreflightBlocksFilledSecondChild() throws Exception {
        TinyLiveMinimumOrderPreviewService service = service();
        BtLiveSignal position = new BtLiveSignal();
        position.setId(260L);
        position.setSymbol("BTCUSDT");
        position.setSide("LONG");
        position.setOcoOrderListId(1260L);
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of(position));
        when(okxTradingService.getAlgoOrder("BTCUSDT", 1260L)).thenReturn(new ObjectMapper().readTree(
                "{\"state\":\"effective\",\"ordIdList\":[\"tp-260\",\"sl-260\"]}"));
        when(okxTradingService.querySpotOrderDetail("BTCUSDT", "tp-260"))
                .thenReturn(new ObjectMapper().readTree("{\"state\":\"live\"}"));
        when(okxTradingService.querySpotOrderDetail("BTCUSDT", "sl-260"))
                .thenReturn(new ObjectMapper().readTree("{\"state\":\"filled\",\"avgPx\":\"88\"}"));

        String status = ReflectionTestUtils.invokeMethod(service, "ocoPreflightStatus",
                new BigDecimal("100"), new BigDecimal("106"), new BigDecimal("88"), 1L);

        assertThat(status).isEqualTo("NOT_READY_EXISTING_OCO_SYNC_ERROR");
    }

    private TinyLiveMinimumOrderPreviewService service() {
        when(evidenceService.isEnabled()).thenReturn(true);
        when(evidenceService.listRecent(eq("BTCUSDT"), eq(1440), eq(100))).thenReturn(List.of());
        when(decisionAuditRepository.findRecent(any(LocalDateTime.class), eq("BTCUSDT"), any(PageRequest.class)))
                .thenReturn(List.of());
        when(liveSignalRepository.findByStrategyIdAndCreatedAtAfter(eq(574L), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of());
        when(liveSignalRepository.countTinyLiveAutoTradesSince(eq(574L), eq("BTCUSDT"), any(LocalDateTime.class)))
                .thenReturn(0L);
        when(okxTradingService.getLastPrice(eq("BTCUSDT"))).thenReturn(new BigDecimal("60000"));
        when(okxTradingService.getSpotInstrumentRules(eq("BTCUSDT"))).thenReturn(
                new OkxTradingService.SpotInstrumentRules(
                        "BTC-USDT", new BigDecimal("0.00001"), new BigDecimal("0.00000001"), new BigDecimal("0.10")));
        when(okxTradingService.getSpotHoldings()).thenReturn(List.of(
                new OkxTradingService.SpotHolding("USDT", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"))));
        when(eventRiskLevelEngine.evaluate(eq("BTCUSDT"))).thenReturn(new EventRiskLevelEngine.Snapshot(
                "BTCUSDT", 0, EventRiskLevelEngine.RiskLevel.R0, List.of(), Map.of(), LocalDateTime.now(ZoneOffset.UTC)));
        when(rolloutStateService.effectiveMaxOrdersPerDay(anyString(), any(Long.class), anyString())).thenReturn(1L);

        return new TinyLiveMinimumOrderPreviewService(
                evidenceService,
                decisionAuditRepository,
                liveSignalRepository,
                eventRiskLevelEngine,
                okxTradingService,
                new OcoOrderStateInspector(okxTradingService),
                rolloutStateService,
                new ObjectMapper(),
                new MockEnvironment());
    }
}
