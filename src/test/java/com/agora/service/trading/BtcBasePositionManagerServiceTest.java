package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BtcBasePositionManagerServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void explicitProductionCohortProducesExactAggregateAndRetirementReviewWithoutMutation() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal p260 = position(260L, "62762", "0.00015933", "66551.68", "55250.45", 1260L);
        BtLiveSignal p261 = position(261L, "63979.3", "0.00015630", "67811.8", "56296.59", 1261L);
        BtLiveSignal p262 = position(262L, "64400.2", "0.00015527", "68255.31", "56664.78", 1262L);
        stubPosition(fixture, p260, -0.09, "MODIFY");
        stubPosition(fixture, p261, -0.28, "MODIFY");
        stubPosition(fixture, p262, -0.34, "MODIFY");

        JsonNode output = MAPPER.readTree(fixture.service.previewDisposition("260,260,261,262", 168));

        BigDecimal expectedCost = bd("62762").multiply(bd("0.00015933"))
                .add(bd("63979.3").multiply(bd("0.00015630")))
                .add(bd("64400.2").multiply(bd("0.00015527")));
        assertThat(output.path("requestedPositionIds").size()).isEqualTo(3);
        assertThat(output.path("positions").size()).isEqualTo(3);
        assertThat(output.at("/decision/adoptionEligible").asBoolean()).isTrue();
        assertThat(output.at("/decision/recommendedDisposition").asText()).isEqualTo("RETIRE_CLOSE_REVIEW");
        assertThat(output.path("verdict").asText()).isEqualTo("RETIRE_CLOSE_REVIEW");
        assertThat(output.path("blockers").isEmpty()).isTrue();
        assertThat(output.at("/aggregate/ownedQty").decimalValue()).isEqualByComparingTo("0.00047090");
        assertThat(output.at("/aggregate/costUsdt").decimalValue())
                .isEqualByComparingTo(expectedCost.setScale(8, RoundingMode.HALF_UP));
        assertThat(output.at("/aggregate/weightedEntry").decimalValue())
                .isEqualByComparingTo(expectedCost.divide(bd("0.00047090"), 8, RoundingMode.HALF_UP));
        assertThat(output.at("/aggregate/heuristicCombinedEvUsdt").decimalValue())
                .isEqualByComparingTo("-0.71");
        assertThat(output.at("/aggregate/ownershipComplete").asBoolean()).isTrue();
        assertThat(output.at("/aggregate/currentPriceComplete").asBoolean()).isTrue();
        assertThat(output.at("/aggregate/heuristicEvComplete").asBoolean()).isTrue();
        assertThat(output.at("/safety/orderSent").asBoolean()).isFalse();
        assertThat(output.at("/safety/ocoCancelled").asBoolean()).isFalse();
        assertNoMutation(fixture);
    }

    @Test
    void tradedAndOcoQuantityMismatchFailsClosedAndSuppressesAggregateOwnership() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal position = position(260L, "62762", "0.00015933", "66551.68", "55250.45", 1260L);
        position.setOcoQty(bd("0.00015000"));
        when(fixture.repository.findById(260L)).thenReturn(Optional.of(position));
        stubHealthyOco(fixture, position, "effective", "child-260");

        JsonNode output = MAPPER.readTree(fixture.service.previewAdoption("260", null));

        assertThat(output.at("/decision/adoptionEligible").asBoolean()).isFalse();
        assertThat(output.path("verdict").asText()).isEqualTo("BLOCKED_FAIL_CLOSED");
        assertThat(output.path("blockers").toString()).contains("TRADED_QTY_OCO_QTY_MISMATCH");
        assertThat(output.at("/positions/0/ownedQty").isNull()).isTrue();
        assertThat(output.at("/aggregate/ownedQty").isNull()).isTrue();
        assertThat(output.at("/aggregate/ownershipComplete").asBoolean()).isFalse();
        verify(fixture.outcomes, never()).analyze(anyLong(), any(Integer.class));
        assertNoMutation(fixture);
    }

    @Test
    void filledSecondOcoChildFailsClosedInsteadOfAdoptingStaleProtection() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal position = position(260L, "62762", "0.00015933", "66551.68", "55250.45", 1260L);
        when(fixture.repository.findById(260L)).thenReturn(Optional.of(position));
        when(fixture.okx.getAlgoOrder("BTCUSDT", 1260L))
                .thenReturn(MAPPER.readTree("{\"state\":\"partially_effective\",\"ordIdList\":[\"tp-260\",\"sl-260\"]}"));
        when(fixture.okx.querySpotOrderDetail("BTCUSDT", "tp-260"))
                .thenReturn(MAPPER.readTree("{\"state\":\"live\"}"));
        when(fixture.okx.querySpotOrderDetail("BTCUSDT", "sl-260"))
                .thenReturn(MAPPER.readTree("{\"state\":\"filled\"}"));
        when(fixture.outcomes.analyze(260L, 168)).thenReturn(outcome(position, -0.09, "MODIFY"));

        JsonNode output = MAPPER.readTree(fixture.service.previewDisposition("260", 168));

        assertThat(output.at("/positions/0/ocoState").asText()).isEqualTo("partially_effective");
        assertThat(output.at("/positions/0/ocoHealthConfirmed").asBoolean()).isFalse();
        assertThat(output.path("blockers").toString())
                .contains("OCO_CHILD_FILLED_RECONCILIATION_REQUIRED");
        assertThat(output.path("verdict").asText()).isEqualTo("BLOCKED_FAIL_CLOSED");
        assertNoMutation(fixture);
    }

    @Test
    void invalidExplicitIdsStopBeforeRepositoryOrExchangeQueries() throws Exception {
        Fixture fixture = fixture();

        JsonNode output = MAPPER.readTree(fixture.service.previewAdoption("not-an-id", 168));

        assertThat(output.path("verdict").asText()).isEqualTo("BLOCKED_FAIL_CLOSED");
        assertThat(output.path("blockers").toString())
                .contains("INVALID_POSITION_ID:not-an-id")
                .contains("POSITION_IDS_REQUIRED");
        verify(fixture.repository, never()).findById(anyLong());
        verify(fixture.okx, never()).getLastPrice(anyString());
        verify(fixture.okx, never()).getAlgoOrder(anyString(), anyLong());
        assertNoMutation(fixture);
    }

    @Test
    void statusSeparatesExistingBtcBaseSlicesFromRecordedOcoCandidates() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal candidate = position(260L, "62762", "0.00015933", "66551.68", "55250.45", 1260L);
        BtLiveSignal existing = position(900L, "60000", "0.00010000", "63600", "52800", 1900L);
        existing.setFilterReason("LOCAL_TRADINGVIEW_BTC_BASE:bar=2026-07-01");
        existing.setOcoOrderListId(null);
        when(fixture.repository.findByAutoTradedIsTrueAndExitTimeIsNull())
                .thenReturn(List.of(candidate, existing));

        JsonNode output = MAPPER.readTree(fixture.service.status("BTC-USDT"));

        assertThat(output.at("/inventory/recordedOcoCandidateCount").asInt()).isEqualTo(1);
        assertThat(output.at("/inventory/existingBtcBaseNoOcoSliceCount").asInt()).isEqualTo(1);
        assertThat(output.at("/inventory/recordedOcoCandidateIds/0").asLong()).isEqualTo(260L);
        assertThat(output.at("/inventory/existingBtcBaseSliceIds/0").asLong()).isEqualTo(900L);
        assertThat(output.at("/inventory/walletBalanceUsed").asBoolean()).isFalse();
        assertThat(output.path("liveActionsImplemented").asBoolean()).isFalse();
        verify(fixture.okx, never()).getLastPrice(anyString());
        assertNoMutation(fixture);
    }

    private Fixture fixture() {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        OkxTradingService okx = mock(OkxTradingService.class);
        OcoOutcomeAnalysisService outcomes = mock(OcoOutcomeAnalysisService.class);
        SpotPositionCloseService closeService = mock(SpotPositionCloseService.class);
        when(okx.getLastPrice("BTCUSDT")).thenReturn(bd("62700"));
        when(closeService.isClosing(anyLong())).thenReturn(false);
        BtcBasePositionManagerService service = new BtcBasePositionManagerService(
                repository, okx, outcomes, closeService, new OcoOrderStateInspector(okx), MAPPER);
        return new Fixture(service, repository, okx, outcomes, closeService);
    }

    private void stubPosition(Fixture fixture,
                              BtLiveSignal position,
                              double evUsdt,
                              String suggestion) throws Exception {
        when(fixture.repository.findById(position.getId())).thenReturn(Optional.of(position));
        stubHealthyOco(fixture, position, "effective", "child-" + position.getId());
        when(fixture.outcomes.analyze(position.getId(), 168))
                .thenReturn(outcome(position, evUsdt, suggestion));
    }

    private void stubHealthyOco(Fixture fixture,
                                BtLiveSignal position,
                                String state,
                                String childOrderId) throws Exception {
        when(fixture.okx.getAlgoOrder("BTCUSDT", position.getOcoOrderListId()))
                .thenReturn(MAPPER.readTree("{\"state\":\"" + state + "\",\"ordIdList\":[\""
                        + childOrderId + "\"]}"));
        when(fixture.okx.querySpotOrderDetail("BTCUSDT", childOrderId))
                .thenReturn(MAPPER.readTree("{\"state\":\"live\"}"));
    }

    private OcoOutcomeAnalysisService.Outcome outcome(BtLiveSignal position,
                                                       double evUsdt,
                                                       String suggestion) {
        return new OcoOutcomeAnalysisService.Outcome(
                position.getId(), position.getSymbol(),
                position.getActualEntryPrice().doubleValue(), 62700,
                position.getSuggestedTp().doubleValue(), position.getSuggestedSl().doubleValue(),
                position.getOcoQty().doubleValue(), 0.45, 0.40,
                2.1, "BEARISH", -5, 0, evUsdt, suggestion, "test");
    }

    private BtLiveSignal position(Long id,
                                  String entry,
                                  String qty,
                                  String tp,
                                  String sl,
                                  Long ocoId) {
        BtLiveSignal position = new BtLiveSignal();
        position.setId(id);
        position.setStrategyId(508L);
        position.setSymbol("BTCUSDT");
        position.setIntervalCode("4h");
        position.setSide("LONG");
        position.setAutoTraded(true);
        position.setEntryPrice(bd(entry));
        position.setActualEntryPrice(bd(entry));
        position.setTradedQty(bd(qty));
        position.setOcoQty(bd(qty));
        position.setSuggestedTp(bd(tp));
        position.setSuggestedSl(bd(sl));
        position.setOcoOrderListId(ocoId);
        position.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(100));
        return position;
    }

    private void assertNoMutation(Fixture fixture) {
        verify(fixture.repository, never()).save(any(BtLiveSignal.class));
        verify(fixture.closeService, never()).closeAtMarket(anyLong(), anyString());
        verify(fixture.okx, never()).cancelOco(anyString(), anyLong());
        verify(fixture.okx, never()).placeMarketSellWithFill(anyString(), any(BigDecimal.class));
        verify(fixture.okx, never()).placeOco(
                anyString(), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record Fixture(BtcBasePositionManagerService service,
                           BtLiveSignalRepository repository,
                           OkxTradingService okx,
                           OcoOutcomeAnalysisService outcomes,
                           SpotPositionCloseService closeService) {
    }
}
