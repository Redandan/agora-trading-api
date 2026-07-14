package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BtcBasePositionAdoptionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal QTY = new BigDecimal("0.00015933");

    @Test
    void disabledExecutionReturnsBothGatesWithoutAnyMutation() throws Exception {
        Fixture fixture = fixture(position());
        when(fixture.inspector.inspectSpot("BTCUSDT", 1260L)).thenReturn(active());
        String confirm = confirmText(fixture.service.previewOrExecute("260", QTY, false, null));

        JsonNode output = json(fixture.service.previewOrExecute("260", QTY, true, confirm));

        assertThat(output.path("executionBlockers").toString())
                .contains("FEATURE_DISABLED")
                .contains("LIVE_ACTION_DISABLED");
        assertThat(output.path("status").asText()).isEqualTo("EXECUTION_BLOCKED_NOT_AUTHORIZED");
        assertThat(output.at("/safety/databaseMutated").asBoolean()).isFalse();
        assertThat(output.at("/safety/marketSellAttempted").asBoolean()).isFalse();
        verify(fixture.store, never()).markPending(anyLong(), anyLong(), any(BigDecimal.class));
        verify(fixture.okx, never()).cancelOco(anyString(), anyLong());
        assertNeverSells(fixture);
    }

    @Test
    void disabledExecutionStatusTakesPrecedenceOverInvalidInputWithoutMutation() throws Exception {
        Fixture fixture = fixture(position());

        JsonNode output = json(fixture.service.previewOrExecute(
                "not-an-id", new BigDecimal("0.0001"), true, "invalid"));

        assertThat(output.path("status").asText()).isEqualTo("EXECUTION_BLOCKED_NOT_AUTHORIZED");
        assertThat(output.path("executionBlockers").toString())
                .contains("PRECHECK_BLOCKED")
                .contains("FEATURE_DISABLED")
                .contains("LIVE_ACTION_DISABLED");
        assertThat(output.at("/safety/databaseMutated").asBoolean()).isFalse();
        assertThat(output.at("/safety/marketSellAttempted").asBoolean()).isFalse();
        verify(fixture.store, never()).markPending(anyLong(), anyLong(), any(BigDecimal.class));
        verify(fixture.okx, never()).cancelOco(anyString(), anyLong());
        assertNeverSells(fixture);
    }

    @Test
    void successfulExecutionPersistsPendingThenConfirmsCancelAndKeepsBtc() throws Exception {
        BtLiveSignal position = position();
        Fixture fixture = armedFixture(position);
        when(fixture.inspector.inspectSpot("BTCUSDT", 1260L))
                .thenReturn(active(), active(), active(), canceled());
        when(fixture.store.markPending(260L, 1260L, QTY))
                .thenReturn(BtcBasePositionAdoptionStore.TransitionResult.pending(pendingPosition()));
        when(fixture.store.finalizeManaged(260L, 1260L, QTY))
                .thenReturn(BtcBasePositionAdoptionStore.TransitionResult.adopted(adoptedPosition()));
        String confirm = confirmText(fixture.service.previewOrExecute("260", QTY, false, null));

        JsonNode output = json(fixture.service.previewOrExecute("260", QTY, true, confirm));

        assertThat(output.path("status").asText()).isEqualTo("COMPLETED_KEEP_BTC");
        assertThat(output.at("/executionOutcomes/0/status").asText()).isEqualTo("ADOPTED");
        assertThat(output.at("/executionOutcomes/0/btcRetainedConfirmed").asBoolean()).isTrue();
        assertThat(output.at("/safety/ocoCancelConfirmed").asBoolean()).isTrue();
        assertThat(output.at("/safety/btcSold").asBoolean()).isFalse();
        verify(fixture.store).markPending(260L, 1260L, QTY);
        verify(fixture.okx).cancelOco("BTCUSDT", 1260L);
        verify(fixture.store).finalizeManaged(260L, 1260L, QTY);
        assertNeverSells(fixture);
    }

    @Test
    void childFillRaceRemainsPendingAndNeverCancelsOrSells() throws Exception {
        Fixture fixture = armedFixture(position());
        when(fixture.inspector.inspectSpot("BTCUSDT", 1260L))
                .thenReturn(active(), active(), filled());
        when(fixture.store.markPending(260L, 1260L, QTY))
                .thenReturn(BtcBasePositionAdoptionStore.TransitionResult.pending(pendingPosition()));
        String confirm = confirmText(fixture.service.previewOrExecute("260", QTY, false, null));

        JsonNode output = json(fixture.service.previewOrExecute("260", QTY, true, confirm));

        assertThat(output.path("status").asText()).isEqualTo("PARTIAL_OR_PENDING_REVIEW_REQUIRED");
        assertThat(output.at("/executionOutcomes/0/status").asText()).isEqualTo("PENDING");
        assertThat(output.at("/executionOutcomes/0/reason").asText())
                .isEqualTo("OCO_FILLED_RECONCILIATION_REQUIRED");
        assertThat(output.at("/safety/ocoCancelConfirmed").asBoolean()).isFalse();
        assertThat(output.at("/safety/btcRetainedConfirmed").asBoolean()).isFalse();
        verify(fixture.okx, never()).cancelOco(anyString(), anyLong());
        verify(fixture.store, never()).finalizeManaged(anyLong(), anyLong(), any(BigDecimal.class));
        assertNeverSells(fixture);
    }

    @Test
    void cancelConfirmationTimeoutLeavesRecoverablePendingMarker() throws Exception {
        Fixture fixture = armedFixture(position());
        when(fixture.inspector.inspectSpot("BTCUSDT", 1260L)).thenReturn(active());
        when(fixture.store.markPending(260L, 1260L, QTY))
                .thenReturn(BtcBasePositionAdoptionStore.TransitionResult.pending(pendingPosition()));
        String confirm = confirmText(fixture.service.previewOrExecute("260", QTY, false, null));

        JsonNode output = json(fixture.service.previewOrExecute("260", QTY, true, confirm));

        assertThat(output.at("/executionOutcomes/0/status").asText()).isEqualTo("PENDING");
        assertThat(output.at("/executionOutcomes/0/reason").asText())
                .isEqualTo("OCO_CANCEL_CONFIRMATION_TIMEOUT");
        verify(fixture.okx).cancelOco("BTCUSDT", 1260L);
        verify(fixture.store, never()).finalizeManaged(anyLong(), anyLong(), any(BigDecimal.class));
        assertNeverSells(fixture);
    }

    @Test
    void restartCanResumeCanceledPendingPositionWithoutSecondCancel() throws Exception {
        BtLiveSignal pending = pendingPosition();
        pending.setOcoOrderListId(null);
        Fixture fixture = armedFixture(pending);
        when(fixture.inspector.inspectSpot("BTCUSDT", 1260L)).thenReturn(canceled());
        when(fixture.store.markPending(260L, 1260L, QTY))
                .thenReturn(BtcBasePositionAdoptionStore.TransitionResult.pending(pending));
        when(fixture.store.finalizeManaged(260L, 1260L, QTY))
                .thenReturn(BtcBasePositionAdoptionStore.TransitionResult.adopted(adoptedPosition()));
        String confirm = confirmText(fixture.service.previewOrExecute("260", QTY, false, null));

        JsonNode output = json(fixture.service.previewOrExecute("260", QTY, true, confirm));

        assertThat(output.path("status").asText()).isEqualTo("COMPLETED_KEEP_BTC");
        verify(fixture.okx, never()).cancelOco(anyString(), anyLong());
        verify(fixture.store).finalizeManaged(260L, 1260L, QTY);
        assertNeverSells(fixture);
    }

    @Test
    void alreadyAdoptedPositionIsIdempotentAndDoesNotTouchExchange() throws Exception {
        Fixture fixture = armedFixture(adoptedPosition());
        String confirm = confirmText(fixture.service.previewOrExecute("260", QTY, false, null));

        JsonNode output = json(fixture.service.previewOrExecute("260", QTY, true, confirm));

        assertThat(output.path("status").asText()).isEqualTo("COMPLETED_KEEP_BTC");
        assertThat(output.at("/executionOutcomes/0/status").asText()).isEqualTo("ALREADY_ADOPTED");
        verify(fixture.store, never()).markPending(anyLong(), anyLong(), any(BigDecimal.class));
        verify(fixture.okx, never()).cancelOco(anyString(), anyLong());
        assertNeverSells(fixture);
    }

    @Test
    void exchangeQuantityMismatchFailsBeforePendingWrite() throws Exception {
        Fixture fixture = armedFixture(position());
        when(fixture.inspector.inspectSpot("BTCUSDT", 1260L))
                .thenReturn(activeWithSize("0.00015000"));

        JsonNode output = json(fixture.service.previewOrExecute("260", QTY, false, null));

        assertThat(output.path("blockers").toString()).contains("DB_OCO_QTY_EXCHANGE_QTY_MISMATCH");
        verify(fixture.store, never()).markPending(anyLong(), anyLong(), any(BigDecimal.class));
        assertNeverSells(fixture);
    }

    @Test
    void nonLongSideFailsClosedBeforePendingWrite() throws Exception {
        BtLiveSignal position = position();
        position.setSide(null);
        Fixture fixture = armedFixture(position);

        JsonNode output = json(fixture.service.previewOrExecute("260", QTY, false, null));

        assertThat(output.path("blockers").toString()).contains("SPOT_LONG_ONLY");
        verify(fixture.store, never()).markPending(anyLong(), anyLong(), any(BigDecimal.class));
        verify(fixture.okx, never()).cancelOco(anyString(), anyLong());
        assertNeverSells(fixture);
    }

    private Fixture fixture(BtLiveSignal position) {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        BtcBasePositionAdoptionStore store = mock(BtcBasePositionAdoptionStore.class);
        OkxTradingService okx = mock(OkxTradingService.class);
        OcoOrderStateInspector inspector = mock(OcoOrderStateInspector.class);
        SpotPositionCloseService closeService = mock(SpotPositionCloseService.class);
        when(repository.findById(260L)).thenReturn(Optional.of(position));
        when(closeService.isClosing(260L)).thenReturn(false);
        BtcBasePositionAdoptionService service = new BtcBasePositionAdoptionService(
                repository, store, okx, inspector, closeService, MAPPER);
        ReflectionTestUtils.setField(service, "confirmAttempts", 2);
        ReflectionTestUtils.setField(service, "confirmIntervalMs", 0L);
        return new Fixture(service, store, okx, inspector, closeService);
    }

    private Fixture armedFixture(BtLiveSignal position) {
        Fixture fixture = fixture(position);
        ReflectionTestUtils.setField(fixture.service, "featureEnabled", true);
        ReflectionTestUtils.setField(fixture.service, "liveActionEnabled", true);
        return fixture;
    }

    private String confirmText(String output) throws Exception {
        return json(output).path("requiredConfirmText").asText();
    }

    private JsonNode json(String output) throws Exception {
        return MAPPER.readTree(output);
    }

    private OcoOrderStateInspector.Inspection active() {
        return activeWithSize(QTY.toPlainString());
    }

    private OcoOrderStateInspector.Inspection activeWithSize(String size) {
        return new OcoOrderStateInspector.Inspection("effective", true, true,
                false, false, null, null, List.of("tp", "sl"), List.of(),
                "1", "66551", "55250", size);
    }

    private OcoOrderStateInspector.Inspection canceled() {
        return new OcoOrderStateInspector.Inspection("canceled", true, false,
                false, true, null, null, List.of("tp", "sl"), List.of(),
                "1", "66551", "55250", QTY.toPlainString());
    }

    private OcoOrderStateInspector.Inspection filled() {
        return new OcoOrderStateInspector.Inspection("effective", true, false,
                true, false, new BigDecimal("55250"), "sl", List.of("tp", "sl"), List.of(),
                "1", "66551", "55250", QTY.toPlainString());
    }

    private BtLiveSignal position() {
        BtLiveSignal position = new BtLiveSignal();
        position.setId(260L);
        position.setStrategyId(508L);
        position.setSymbol("BTCUSDT");
        position.setIntervalCode("4h");
        position.setSide("LONG");
        position.setAutoTraded(true);
        position.setEntryPrice(new BigDecimal("62762"));
        position.setActualEntryPrice(new BigDecimal("62762"));
        position.setTradedQty(QTY);
        position.setOcoQty(QTY);
        position.setOcoOrderListId(1260L);
        return position;
    }

    private BtLiveSignal pendingPosition() {
        BtLiveSignal position = position();
        position.setFilterReason(BtcBasePositionStatePolicy.pendingMarker(1260L, "legacy"));
        return position;
    }

    private BtLiveSignal adoptedPosition() {
        BtLiveSignal position = pendingPosition();
        position.setFilterReason(BtcBasePositionStatePolicy.adoptedMarkerFromPending(
                position.getFilterReason()));
        position.setOcoOrderListId(null);
        position.setOcoQty(null);
        return position;
    }

    private void assertNeverSells(Fixture fixture) {
        verify(fixture.okx, never()).placeMarketSellWithFill(anyString(), any(BigDecimal.class));
        verify(fixture.closeService, never()).closeAtMarket(anyLong(), anyString());
    }

    private record Fixture(BtcBasePositionAdoptionService service,
                           BtcBasePositionAdoptionStore store,
                           OkxTradingService okx,
                           OcoOrderStateInspector inspector,
                           SpotPositionCloseService closeService) {
    }
}
