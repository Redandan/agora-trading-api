package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BtcBasePositionAdoptionStoreTest {

    @Test
    void pendingCommitsBeforeExchangeActionAndPreservesOriginalReason() {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        BtcBasePositionAdoptionStore store = new BtcBasePositionAdoptionStore(repository);
        BtLiveSignal position = position();
        position.setFilterReason("legacy-source");
        when(repository.findByIdForUpdate(260L)).thenReturn(Optional.of(position));
        when(repository.saveAndFlush(position)).thenReturn(position);

        BtcBasePositionAdoptionStore.TransitionResult result = store.markPending(
                260L, 1260L, new BigDecimal("0.00015933"));

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(BtcBasePositionStatePolicy.isAdoptionPending(position)).isTrue();
        assertThat(BtcBasePositionStatePolicy.originalOcoAlgoId(position)).isEqualTo(1260L);
        assertThat(BtcBasePositionStatePolicy.previousReason(position.getFilterReason()))
                .isEqualTo("legacy-source");
        verify(repository).saveAndFlush(position);
    }

    @Test
    void finalizeClearsOcoFieldsButKeepsOpenPositionAndPurchasedQuantity() {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        BtcBasePositionAdoptionStore store = new BtcBasePositionAdoptionStore(repository);
        BtLiveSignal position = position();
        position.setFilterReason(BtcBasePositionStatePolicy.pendingMarker(1260L, "legacy-source"));
        when(repository.findByIdForUpdate(260L)).thenReturn(Optional.of(position));
        when(repository.saveAndFlush(position)).thenReturn(position);

        BtcBasePositionAdoptionStore.TransitionResult result = store.finalizeManaged(
                260L, 1260L, new BigDecimal("0.00015933"));

        assertThat(result.status()).isEqualTo("ADOPTED");
        assertThat(position.getOcoOrderListId()).isNull();
        assertThat(position.getOcoQty()).isNull();
        assertThat(position.getTradedQty()).isEqualByComparingTo("0.00015933");
        assertThat(position.getExitTime()).isNull();
        assertThat(position.getRealizedPnl()).isNull();
        assertThat(BtcBasePositionStatePolicy.isAdoptedFromOco(position)).isTrue();
    }

    @Test
    void changedQuantityFailsClosedUnderRowLock() {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        BtcBasePositionAdoptionStore store = new BtcBasePositionAdoptionStore(repository);
        BtLiveSignal position = position();
        when(repository.findByIdForUpdate(260L)).thenReturn(Optional.of(position));

        BtcBasePositionAdoptionStore.TransitionResult result = store.markPending(
                260L, 1260L, new BigDecimal("0.00015000"));

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).isEqualTo("EXPECTED_QTY_CHANGED");
    }

    @Test
    void finalizeRechecksQuantityUnderRowLock() {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        BtcBasePositionAdoptionStore store = new BtcBasePositionAdoptionStore(repository);
        BtLiveSignal position = position();
        position.setFilterReason(BtcBasePositionStatePolicy.pendingMarker(1260L, "legacy-source"));
        position.setTradedQty(new BigDecimal("0.00015000"));
        when(repository.findByIdForUpdate(260L)).thenReturn(Optional.of(position));

        BtcBasePositionAdoptionStore.TransitionResult result = store.finalizeManaged(
                260L, 1260L, new BigDecimal("0.00015933"));

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).isEqualTo("TRADED_QTY_OCO_QTY_MISMATCH");
        assertThat(position.getOcoOrderListId()).isEqualTo(1260L);
        assertThat(BtcBasePositionStatePolicy.isAdoptionPending(position)).isTrue();
    }

    @Test
    void inconsistentAdoptedMarkerFailsClosedInsteadOfClaimingIdempotency() {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        BtcBasePositionAdoptionStore store = new BtcBasePositionAdoptionStore(repository);
        BtLiveSignal position = position();
        String pending = BtcBasePositionStatePolicy.pendingMarker(1260L, "legacy-source");
        position.setFilterReason(BtcBasePositionStatePolicy.adoptedMarkerFromPending(pending));
        position.setOcoOrderListId(null);
        when(repository.findByIdForUpdate(260L)).thenReturn(Optional.of(position));

        BtcBasePositionAdoptionStore.TransitionResult result = store.markPending(
                260L, 1260L, new BigDecimal("0.00015933"));

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reason()).isEqualTo("ADOPTED_MARKER_WITH_OCO_QTY");
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
        position.setTradedQty(new BigDecimal("0.00015933"));
        position.setOcoQty(new BigDecimal("0.00015933"));
        position.setOcoOrderListId(1260L);
        return position;
    }
}
