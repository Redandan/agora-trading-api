package com.agora.service.trading.execution;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OcoMissingExecutionEventDetectorTest {

    @Test
    void intentionalBtcBaseNoOcoNeverCreatesCriticalMissingEvent() {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        BtLiveSignal position = new BtLiveSignal();
        position.setId(260L);
        position.setStrategyId(508L);
        position.setSymbol("BTCUSDT");
        position.setIntervalCode("4h");
        position.setFilterReason(BtcBasePositionStatePolicy.adoptedMarkerFromPending(
                BtcBasePositionStatePolicy.pendingMarker(1260L, null)));
        when(repository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNull())
                .thenReturn(List.of(position));

        OcoMissingExecutionEventDetector detector = new OcoMissingExecutionEventDetector(
                repository, new ObjectMapper());

        assertThat(detector.detect(LocalDateTime.now())).isEmpty();
    }
}
