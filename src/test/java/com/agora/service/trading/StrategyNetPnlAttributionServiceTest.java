package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategyNetPnlAttributionServiceTest {

    @Test
    void noClosedSampleCannotBeReportedAsExactProfit() throws Exception {
        Fixture fixture = fixture();
        when(fixture.liveSignalRepository.findByStrategyIdAndCreatedAtAfter(eq(508L), any()))
                .thenReturn(List.of());

        JsonNode report = fixture.mapper.readTree(fixture.service.report(508L, "BTCUSDT", 90));

        assertThat(report.path("status").asText()).isEqualTo("NO_CLOSED_POSITIONS");
        assertThat(report.path("exactProfitClaimAllowed").asBoolean()).isFalse();
        assertThat(report.path("summary").path("exactNetClosedPnlUsdt").isNull()).isTrue();
    }

    @Test
    void usesFullRuntimeEvidenceForPartialFillFeesGrossPnlAndSlippage() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal signal = closedSignal(901L);
        signal.setRealizedPnl(bd("0.05"));
        RuntimeDecisionEvidence evidence = evidence(901L,
                "{\"entryFeeUsdt\":\"0.01\",\"totalExitFeeUsdt\":\"0.03\"," +
                        "\"grossPnlUsdt\":\"0.12\",\"netPnlUsdt\":\"0.08\"," +
                        "\"entryNetQty\":\"0.002\"}");
        when(fixture.liveSignalRepository.findByStrategyIdAndCreatedAtAfter(eq(508L), any()))
                .thenReturn(List.of(signal));
        when(fixture.evidenceRepository.findByLiveSignalIdOrderByEvidenceTimeAsc(901L))
                .thenReturn(List.of(evidence));

        JsonNode report = fixture.mapper.readTree(fixture.service.report(508L, "BTCUSDT", 90));

        JsonNode row = report.path("positions").path(0);
        assertThat(row.path("grossRealizedPnlUsdt").asText()).isEqualTo("0.12000000");
        assertThat(row.path("entryFeeUsdt").asText()).isEqualTo("0.01000000");
        assertThat(row.path("exitFeeUsdt").asText()).isEqualTo("0.03000000");
        assertThat(row.path("netRealizedPnlUsdt").asText()).isEqualTo("0.08000000");
        assertThat(row.path("entrySlippageUsdt").asText()).isEqualTo("0.00200000");
        assertThat(row.path("exitSlippageUsdt").asText()).isEqualTo("0.00200000");
        JsonNode summary = report.path("summary");
        assertThat(summary.path("exactNetClosedPnlUsdt").asText()).isEqualTo("0.08000000");
        assertThat(summary.path("feeCoveragePct").asDouble()).isEqualTo(100.0);
        assertThat(report.path("status").asText()).isEqualTo("COMPLETE_FEE_ATTRIBUTION");
        assertThat(report.path("exactProfitClaimAllowed").asBoolean()).isTrue();
    }

    @Test
    void missingFeeEvidenceFailsClosedInsteadOfEstimatingRealizedProfit() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal signal = closedSignal(902L);
        when(fixture.liveSignalRepository.findByStrategyIdAndCreatedAtAfter(eq(508L), any()))
                .thenReturn(List.of(signal));
        when(fixture.evidenceRepository.findByLiveSignalIdOrderByEvidenceTimeAsc(902L))
                .thenReturn(List.of());

        JsonNode report = fixture.mapper.readTree(fixture.service.report(508L, "BTCUSDT", 90));

        assertThat(report.path("positions").path(0).path("netRealizedPnlUsdt").isNull()).isTrue();
        assertThat(report.path("summary").path("closedWithUnknownFees").asInt()).isEqualTo(1);
        assertThat(report.path("summary").path("exactNetClosedPnlUsdt").isNull()).isTrue();
        assertThat(report.path("status").asText()).isEqualTo("PARTIAL_FEE_ATTRIBUTION_FAIL_CLOSED");
        assertThat(report.path("exactProfitClaimAllowed").asBoolean()).isFalse();
    }

    private Fixture fixture() {
        BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
        RuntimeDecisionEvidenceRepository evidenceRepository = mock(RuntimeDecisionEvidenceRepository.class);
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        when(klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                any(), any(), any(), any())).thenReturn(List.of());
        return new Fixture(new StrategyNetPnlAttributionService(
                liveSignalRepository, evidenceRepository, klineRepository, mapper),
                liveSignalRepository, evidenceRepository, mapper);
    }

    private BtLiveSignal closedSignal(Long id) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setId(id);
        signal.setStrategyId(508L);
        signal.setSymbol("BTCUSDT");
        signal.setIntervalCode("4h");
        signal.setAutoTraded(true);
        signal.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        signal.setEntryPrice(bd("100"));
        signal.setActualEntryPrice(bd("101"));
        signal.setTradedQty(bd("0.002"));
        signal.setExitTime(LocalDateTime.now(ZoneOffset.UTC));
        signal.setExitPrice(bd("105"));
        signal.setExitReason("TP");
        signal.setSuggestedTp(bd("106"));
        signal.setSuggestedSl(bd("88"));
        signal.setRealizedPnl(bd("0.008"));
        return signal;
    }

    private RuntimeDecisionEvidence evidence(Long liveSignalId, String json) {
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setDecisionId(liveSignalId);
        evidence.setEvidenceTime(LocalDateTime.now(ZoneOffset.UTC));
        evidence.setLiveSignalId(liveSignalId);
        evidence.setPolicyInputsJson(json);
        evidence.setSelectedAction("STRATEGY_508_TIME_EXIT_LIVE_EXECUTED");
        evidence.setFinalOutcome("TIME_EXIT_24H");
        return evidence;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record Fixture(StrategyNetPnlAttributionService service,
                           BtLiveSignalRepository liveSignalRepository,
                           RuntimeDecisionEvidenceRepository evidenceRepository,
                           ObjectMapper mapper) {
    }
}
