package com.agora.service.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcoOrderStateInspectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void secondSpotChildFillOverridesEffectiveParent() throws Exception {
        OkxTradingService okx = mock(OkxTradingService.class);
        when(okx.getAlgoOrder("BTCUSDT", 1260L)).thenReturn(MAPPER.readTree(
                "{\"state\":\"effective\",\"ordIdList\":[\"tp-260\",\"sl-260\"]}"));
        when(okx.querySpotOrderDetail("BTCUSDT", "tp-260"))
                .thenReturn(MAPPER.readTree("{\"state\":\"live\"}"));
        when(okx.querySpotOrderDetail("BTCUSDT", "sl-260"))
                .thenReturn(MAPPER.readTree("{\"state\":\"filled\",\"avgPx\":\"55263.56\"}"));

        OcoOrderStateInspector.Inspection result =
                new OcoOrderStateInspector(okx).inspectSpot("BTCUSDT", 1260L);

        assertThat(result.queryComplete()).isTrue();
        assertThat(result.filled()).isTrue();
        assertThat(result.active()).isFalse();
        assertThat(result.filledChildOrderId()).isEqualTo("sl-260");
        assertThat(result.fillPrice()).isEqualByComparingTo(new BigDecimal("55263.56"));
        assertThat(result.childOrderIds()).containsExactly("tp-260", "sl-260");
        verify(okx).querySpotOrderDetail("BTCUSDT", "tp-260");
        verify(okx).querySpotOrderDetail("BTCUSDT", "sl-260");
    }

    @Test
    void childQueryFailureCannotBeReportedAsActiveOrCanceled() throws Exception {
        OkxTradingService okx = mock(OkxTradingService.class);
        when(okx.getAlgoOrder("BTCUSDT", 1261L)).thenReturn(MAPPER.readTree(
                "{\"state\":\"effective\",\"ordIdList\":[\"tp-261\",\"sl-261\"]}"));
        when(okx.querySpotOrderDetail("BTCUSDT", "tp-261"))
                .thenThrow(new RuntimeException("timeout"));
        when(okx.querySpotOrderDetail("BTCUSDT", "sl-261"))
                .thenReturn(MAPPER.readTree("{\"state\":\"live\"}"));

        OcoOrderStateInspector.Inspection result =
                new OcoOrderStateInspector(okx).inspectSpot("BTCUSDT", 1261L);

        assertThat(result.queryComplete()).isFalse();
        assertThat(result.active()).isFalse();
        assertThat(result.filled()).isFalse();
        assertThat(result.canceled()).isFalse();
        assertThat(result.errors()).singleElement().asString().contains("CHILD_QUERY_FAILED:tp-261");
    }

    @Test
    void laterFilledChildStillWinsWhenEarlierChildLookupFails() throws Exception {
        OkxTradingService okx = mock(OkxTradingService.class);
        when(okx.getAlgoOrder("BTCUSDT", 1262L)).thenReturn(MAPPER.readTree(
                "{\"state\":\"effective\",\"ordIdList\":[\"tp-262\",\"sl-262\"]}"));
        when(okx.querySpotOrderDetail("BTCUSDT", "tp-262"))
                .thenThrow(new RuntimeException("timeout"));
        when(okx.querySpotOrderDetail("BTCUSDT", "sl-262"))
                .thenReturn(MAPPER.readTree("{\"state\":\"filled\",\"avgPx\":\"56664.78\"}"));

        OcoOrderStateInspector.Inspection result =
                new OcoOrderStateInspector(okx).inspectSpot("BTCUSDT", 1262L);

        assertThat(result.queryComplete()).isFalse();
        assertThat(result.filled()).isTrue();
        assertThat(result.filledChildOrderId()).isEqualTo("sl-262");
        assertThat(result.fillPrice()).isEqualByComparingTo("56664.78");
    }

    @Test
    void swapInspectionUsesEverySwapChildAndKeepsActiveState() throws Exception {
        OkxTradingService okx = mock(OkxTradingService.class);
        when(okx.getSwapAlgoOrder("BTCUSDT", 2001L)).thenReturn(MAPPER.readTree(
                "{\"state\":\"partially_effective\",\"ordIdList\":[\"tp-swap\",\"sl-swap\"]}"));
        when(okx.querySwapOrderDetail("BTCUSDT", "tp-swap"))
                .thenReturn(MAPPER.readTree("{\"state\":\"live\"}"));
        when(okx.querySwapOrderDetail("BTCUSDT", "sl-swap"))
                .thenReturn(MAPPER.readTree("{\"state\":\"canceled\"}"));

        OcoOrderStateInspector.Inspection result =
                new OcoOrderStateInspector(okx).inspectSwap("BTCUSDT", 2001L);

        assertThat(result.queryComplete()).isTrue();
        assertThat(result.active()).isTrue();
        assertThat(result.filled()).isFalse();
        verify(okx).querySwapOrderDetail("BTCUSDT", "tp-swap");
        verify(okx).querySwapOrderDetail("BTCUSDT", "sl-swap");
    }

    @Test
    void canceledParentWithAveragePriceIsTreatedAsFilled() throws Exception {
        OkxTradingService okx = mock(OkxTradingService.class);
        when(okx.getAlgoOrder("BTCUSDT", 3001L)).thenReturn(MAPPER.readTree(
                "{\"state\":\"canceled\",\"avgPx\":\"61000\",\"ordIdList\":[]}"));

        OcoOrderStateInspector.Inspection result =
                new OcoOrderStateInspector(okx).inspectSpot("BTCUSDT", 3001L);

        assertThat(result.filled()).isTrue();
        assertThat(result.canceled()).isFalse();
        assertThat(result.fillPrice()).isEqualByComparingTo("61000");
        assertThat(result.effectiveState()).isEqualTo("filled");
    }
}
