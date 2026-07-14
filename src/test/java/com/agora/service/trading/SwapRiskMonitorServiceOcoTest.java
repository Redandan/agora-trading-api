package com.agora.service.trading;

import com.agora.infra.notification.NotificationPort;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwapRiskMonitorServiceOcoTest {

    @Test
    void orphanReconciliationUsesFilledSecondChildPrice() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        OkxTradingService okx = mock(OkxTradingService.class);
        when(repository.save(any(BtLiveSignal.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(okx.getAlgoOrder("BTCUSDT", 1260L)).thenReturn(mapper.readTree(
                "{\"state\":\"effective\",\"ordIdList\":[\"tp-260\",\"sl-260\"]}"));
        when(okx.querySpotOrderDetail("BTCUSDT", "tp-260"))
                .thenReturn(mapper.readTree("{\"state\":\"live\"}"));
        when(okx.querySpotOrderDetail("BTCUSDT", "sl-260"))
                .thenReturn(mapper.readTree("{\"state\":\"filled\",\"avgPx\":\"88\"}"));
        SwapRiskMonitorService service = new SwapRiskMonitorService(
                repository,
                okx,
                new OcoOrderStateInspector(okx),
                mock(NotificationPort.class),
                mock(PostTradeReviewService.class),
                mock(BtStrategyRepository.class),
                mock(MdKlineRepository.class),
                mapper);
        BtLiveSignal position = new BtLiveSignal();
        position.setId(260L);
        position.setSymbol("BTCUSDT");
        position.setSide("LONG");
        position.setActualEntryPrice(new BigDecimal("100"));
        position.setTradedQty(BigDecimal.ONE);
        position.setSuggestedTp(new BigDecimal("106"));
        position.setSuggestedSl(new BigDecimal("88"));
        position.setOcoOrderListId(1260L);

        service.autoCloseOrphanPosition(position);

        assertThat(position.getExitPrice()).isEqualByComparingTo("88");
        assertThat(position.getExitReason()).isEqualTo("SL");
        assertThat(position.getRealizedPnl()).isEqualByComparingTo("-12");
        verify(okx).querySpotOrderDetail("BTCUSDT", "tp-260");
        verify(okx).querySpotOrderDetail("BTCUSDT", "sl-260");
        verify(repository).save(position);
    }
}
