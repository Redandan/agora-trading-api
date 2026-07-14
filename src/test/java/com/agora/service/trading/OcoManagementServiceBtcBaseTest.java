package com.agora.service.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OcoManagementServiceBtcBaseTest {

    @Test
    void managedPositionCannotBeReprotectedOrModifiedThroughGenericOcoPath() {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        TradingService tradingService = mock(TradingService.class);
        OkxTradingService okx = mock(OkxTradingService.class);
        NotificationPort notifications = mock(NotificationPort.class);
        OcoAdjustmentAuditWriter audit = mock(OcoAdjustmentAuditWriter.class);
        OcoManagementService service = new OcoManagementService(
                repository, tradingService, okx, notifications, audit);
        BtLiveSignal position = managedPosition();
        when(repository.findById(260L)).thenReturn(Optional.of(position));

        assertThatThrownBy(() -> service.retryOco(260L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not receive an OCO retry");
        assertThatThrownBy(() -> service.modifyOco(260L,
                new BigDecimal("55000"), new BigDecimal("66000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not receive an OCO modification");

        verifyNoInteractions(tradingService, okx, notifications, audit);
    }

    private BtLiveSignal managedPosition() {
        BtLiveSignal position = new BtLiveSignal();
        position.setId(260L);
        position.setStrategyId(508L);
        position.setSymbol("BTCUSDT");
        position.setIntervalCode("4h");
        position.setSide("LONG");
        position.setAutoTraded(true);
        position.setTradedQty(new BigDecimal("0.00015933"));
        position.setSuggestedTp(new BigDecimal("66551"));
        position.setSuggestedSl(new BigDecimal("55250"));
        position.setFilterReason(BtcBasePositionStatePolicy.adoptedMarkerFromPending(
                BtcBasePositionStatePolicy.pendingMarker(1260L, null)));
        return position;
    }
}
