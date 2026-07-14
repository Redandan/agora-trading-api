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
    void explicitProductionCohortProducesExactAggregateAndKeepBtcRiskReviewWithoutMutation() throws Exception {
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
        assertThat(output.at("/decision/recommendedDisposition").asText()).isEqualTo("ADOPT_KEEP_BTC_RISK_REVIEW");
        assertThat(output.path("verdict").asText()).isEqualTo("ADOPT_KEEP_BTC_RISK_REVIEW");
        assertThat(output.at("/decision/executionWouldSellBtc").asBoolean()).isFalse();
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
    void oneSatoshiQuantityDifferenceStillFailsExactOwnership() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal position = position(260L, "62762", "0.00015933", "66551.68", "55250.45", 1260L);
        position.setOcoQty(bd("0.00015932"));
        when(fixture.repository.findById(260L)).thenReturn(Optional.of(position));
        stubHealthyOco(fixture, position, "effective", "child-260");

        JsonNode output = MAPPER.readTree(fixture.service.previewAdoption("260", null));

        assertThat(output.at("/decision/adoptionEligible").asBoolean()).isFalse();
        assertThat(output.path("blockers").toString()).contains("TRADED_QTY_OCO_QTY_MISMATCH");
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
        assertThat(output.at("/managedCostBasis/positionCount").asInt()).isEqualTo(1);
        assertThat(output.at("/managedCostBasis/costBasisComplete").asBoolean()).isTrue();
        assertThat(output.at("/managedCostBasis/managedQty").decimalValue())
                .isEqualByComparingTo("0.00010000");
        assertThat(output.at("/managedCostBasis/managedCostUsdt").decimalValue())
                .isEqualByComparingTo("6.00000000");
        assertThat(output.at("/managedCostBasis/weightedAverageEntry").decimalValue())
                .isEqualByComparingTo("60000.00000000");
        assertThat(output.at("/managedCostBasis/ocoQuantityUsed").asBoolean()).isFalse();
        assertThat(output.at("/managedCostBasis/walletBalanceUsed").asBoolean()).isFalse();
        assertThat(output.path("liveActionsImplemented").asBoolean()).isTrue();
        assertThat(output.path("adoptionExecutionArmed").asBoolean()).isFalse();
        verify(fixture.okx).getLastPrice("BTCUSDT");
        assertNoMutation(fixture);
    }

    @Test
    void statusCalculatesAdoptedManagedCostBasisWithoutOcoDependency() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal p260 = position(260L, "62762", "0.00015933", "66551.68", "55250.45", 1260L);
        BtLiveSignal p261 = position(261L, "63979.3", "0.00015630", "67811.8", "56296.59", 1261L);
        BtLiveSignal p262 = position(262L, "64400.2", "0.00015527", "68255.31", "56664.78", 1262L);
        p262.setIntervalCode("1h");
        markAdopted(p260, 1260L);
        markAdopted(p261, 1261L);
        markAdopted(p262, 1262L);
        when(fixture.repository.findByAutoTradedIsTrueAndExitTimeIsNull())
                .thenReturn(List.of(p260, p261, p262));
        when(fixture.okx.getLastPrice("BTCUSDT")).thenReturn(bd("65000"));

        JsonNode output = MAPPER.readTree(fixture.service.status("BTCUSDT"));

        BigDecimal expectedQty = bd("0.00047090");
        BigDecimal expectedCost = bd("62762").multiply(bd("0.00015933"))
                .add(bd("63979.3").multiply(bd("0.00015630")))
                .add(bd("64400.2").multiply(bd("0.00015527")));
        BigDecimal expectedCurrentValue = bd("65000").multiply(expectedQty);
        BigDecimal expectedGrossPnl = expectedCurrentValue.subtract(expectedCost);
        BigDecimal expectedBreakEven = expectedCost.multiply(bd("1.001"))
                .divide(expectedQty.multiply(bd("0.999")), 8, RoundingMode.HALF_UP);
        BigDecimal expectedExitNowNetPnl = expectedGrossPnl
                .subtract(expectedCost.multiply(bd("0.001")))
                .subtract(expectedCurrentValue.multiply(bd("0.001")));

        assertThat(output.at("/inventory/adoptedFromOcoIds").toString())
                .isEqualTo("[260,261,262]");
        assertThat(output.at("/managedCostBasis/positionIds").toString())
                .isEqualTo("[260,261,262]");
        assertThat(output.at("/managedCostBasis/costBasisComplete").asBoolean()).isTrue();
        assertThat(output.at("/managedCostBasis/markToMarketComplete").asBoolean()).isTrue();
        assertThat(output.at("/managedCostBasis/managedQty").decimalValue())
                .isEqualByComparingTo(expectedQty);
        assertThat(output.at("/managedCostBasis/managedCostUsdt").decimalValue())
                .isEqualByComparingTo(expectedCost.setScale(8, RoundingMode.HALF_UP));
        assertThat(output.at("/managedCostBasis/weightedAverageEntry").decimalValue())
                .isEqualByComparingTo(expectedCost.divide(expectedQty, 8, RoundingMode.HALF_UP));
        assertThat(output.at("/managedCostBasis/estimatedFeeAdjustedBreakEven").decimalValue())
                .isEqualByComparingTo(expectedBreakEven);
        assertThat(output.at("/managedCostBasis/currentValueUsdt").decimalValue())
                .isEqualByComparingTo(expectedCurrentValue.setScale(8, RoundingMode.HALF_UP));
        assertThat(output.at("/managedCostBasis/grossUnrealizedPnlUsdt").decimalValue())
                .isEqualByComparingTo(expectedGrossPnl.setScale(8, RoundingMode.HALF_UP));
        assertThat(output.at("/managedCostBasis/estimatedExitNowNetPnlUsdt").decimalValue())
                .isEqualByComparingTo(expectedExitNowNetPnl.setScale(8, RoundingMode.HALF_UP));
        assertThat(output.at("/managedCostBasis/positions/0/managementState").asText())
                .isEqualTo("ADOPTED_FROM_OCO");
        assertThat(output.at("/managedCostBasis/positions/2/intervalCode").asText()).isEqualTo("1h");
        assertThat(output.at("/managedCostBasis/ocoQuantityUsed").asBoolean()).isFalse();
        assertThat(output.at("/managedCostBasis/ocoStateRequired").asBoolean()).isFalse();
        assertThat(output.at("/managedCostBasis/blockers").isEmpty()).isTrue();
        verify(fixture.okx).getLastPrice("BTCUSDT");
        verify(fixture.okx, never()).getAlgoOrder(anyString(), anyLong());
        verify(fixture.outcomes, never()).analyze(anyLong(), any(Integer.class));
        assertNoMutation(fixture);
    }

    @Test
    void statusFailsClosedOnIncompleteManagedCostBasis() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal existing = position(900L, "60000", "0.00010000", "63600", "52800", 1900L);
        existing.setFilterReason("LOCAL_TRADINGVIEW_BTC_BASE:bar=2026-07-01");
        existing.setOcoOrderListId(null);
        existing.setTradedQty(null);
        when(fixture.repository.findByAutoTradedIsTrueAndExitTimeIsNull())
                .thenReturn(List.of(existing));

        JsonNode output = MAPPER.readTree(fixture.service.status("BTCUSDT"));

        assertThat(output.at("/managedCostBasis/positionCount").asInt()).isEqualTo(1);
        assertThat(output.at("/managedCostBasis/costBasisComplete").asBoolean()).isFalse();
        assertThat(output.at("/managedCostBasis/markToMarketComplete").asBoolean()).isFalse();
        assertThat(output.at("/managedCostBasis/managedQty").isNull()).isTrue();
        assertThat(output.at("/managedCostBasis/managedCostUsdt").isNull()).isTrue();
        assertThat(output.at("/managedCostBasis/weightedAverageEntry").isNull()).isTrue();
        assertThat(output.at("/managedCostBasis/positions/0/includedInAggregate").asBoolean()).isFalse();
        assertThat(output.at("/managedCostBasis/blockers").toString())
                .contains("POSITION_900:TRADED_QTY_MISSING");
        verify(fixture.okx, never()).getLastPrice(anyString());
        assertNoMutation(fixture);
    }

    @Test
    void statusExcludesPendingAdoptionFromManagedAggregate() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal pending = position(260L, "62762", "0.00015933", "66551.68", "55250.45", 1260L);
        pending.setFilterReason(BtcBasePositionStatePolicy.ADOPTION_PENDING_PREFIX
                + "1260|AT=2026-07-14T00:00:00Z|PREV=test");
        pending.setOcoOrderListId(null);
        when(fixture.repository.findByAutoTradedIsTrueAndExitTimeIsNull())
                .thenReturn(List.of(pending));

        JsonNode output = MAPPER.readTree(fixture.service.status("BTCUSDT"));

        assertThat(output.at("/inventory/adoptionPendingIds").toString()).isEqualTo("[260]");
        assertThat(output.at("/managedCostBasis/costBasisComplete").asBoolean()).isFalse();
        assertThat(output.at("/managedCostBasis/positions/0/recordedTradedQty").decimalValue())
                .isEqualByComparingTo("0.00015933");
        assertThat(output.at("/managedCostBasis/positions/0/managedQty").isNull()).isTrue();
        assertThat(output.at("/managedCostBasis/positions/0/includedInAggregate").asBoolean()).isFalse();
        assertThat(output.at("/managedCostBasis/costBasisBlockers").toString())
                .contains("POSITION_260:ADOPTION_PENDING_NOT_FINALIZED");
        verify(fixture.okx, never()).getLastPrice(anyString());
        assertNoMutation(fixture);
    }

    @Test
    void statusKeepsCostBasisWhenMarkPriceUnavailable() throws Exception {
        Fixture fixture = fixture();
        BtLiveSignal adopted = position(260L, "62762", "0.00015933", "66551.68", "55250.45", 1260L);
        markAdopted(adopted, 1260L);
        when(fixture.repository.findByAutoTradedIsTrueAndExitTimeIsNull())
                .thenReturn(List.of(adopted));
        when(fixture.okx.getLastPrice("BTCUSDT")).thenThrow(new IllegalStateException("provider unavailable"));

        JsonNode output = MAPPER.readTree(fixture.service.status("BTCUSDT"));

        assertThat(output.at("/managedCostBasis/costBasisComplete").asBoolean()).isTrue();
        assertThat(output.at("/managedCostBasis/markToMarketComplete").asBoolean()).isFalse();
        assertThat(output.at("/managedCostBasis/managedCostUsdt").decimalValue())
                .isEqualByComparingTo("9.99986946");
        assertThat(output.at("/managedCostBasis/weightedAverageEntry").decimalValue())
                .isEqualByComparingTo("62762.00000000");
        assertThat(output.at("/managedCostBasis/estimatedFeeAdjustedBreakEven").isNumber()).isTrue();
        assertThat(output.at("/managedCostBasis/currentValueUsdt").isNull()).isTrue();
        assertThat(output.at("/managedCostBasis/estimatedExitNowNetPnlUsdt").isNull()).isTrue();
        assertThat(output.at("/managedCostBasis/blockers").toString())
                .contains("CURRENT_PRICE_QUERY_FAILED");
        assertThat(output.at("/managedCostBasis/costBasisBlockers").isEmpty()).isTrue();
        assertThat(output.at("/managedCostBasis/markToMarketBlockers").toString())
                .contains("CURRENT_PRICE_QUERY_FAILED");
        assertNoMutation(fixture);
    }

    private Fixture fixture() {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        OkxTradingService okx = mock(OkxTradingService.class);
        OcoOutcomeAnalysisService outcomes = mock(OcoOutcomeAnalysisService.class);
        SpotPositionCloseService closeService = mock(SpotPositionCloseService.class);
        BtcBasePositionAdoptionService adoptionService = mock(BtcBasePositionAdoptionService.class);
        when(okx.getLastPrice("BTCUSDT")).thenReturn(bd("62700"));
        when(closeService.isClosing(anyLong())).thenReturn(false);
        BtcBasePositionManagerService service = new BtcBasePositionManagerService(
                repository, okx, outcomes, closeService, new OcoOrderStateInspector(okx),
                adoptionService, MAPPER);
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

    private void markAdopted(BtLiveSignal position, Long originalOcoId) {
        position.setFilterReason(BtcBasePositionStatePolicy.ADOPTED_FROM_OCO_PREFIX
                + originalOcoId + "|AT=2026-07-14T00:00:00Z|PREV=test");
        position.setOcoOrderListId(null);
        position.setOcoQty(null);
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
