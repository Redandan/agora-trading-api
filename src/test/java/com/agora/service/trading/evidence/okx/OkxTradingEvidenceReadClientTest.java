package com.agora.service.trading.evidence.okx;

import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OkxTradingEvidenceReadClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkxTradingService trading = mock(OkxTradingService.class);
    private final OkxTradingEvidenceReadClient client = new OkxTradingEvidenceReadClient(trading, mapper);

    @Test
    void mapsAllEconomicFieldsAndUsesOldestBillIdAsAfterCursor() throws Exception {
        when(trading.getFillHistoryPage("SPOT", "BTC-USDT", 100, "900")).thenReturn(mapper.readTree("""
                {"code":"0","data":[
                  {"instId":"BTC-USDT","instType":"SPOT","ordId":"o1","tradeId":"t1","billId":"899",
                   "fillTime":"1710000000000","side":"buy","fillPx":"100","fillSz":"0.5",
                   "fee":"-0.0005","feeCcy":"BTC","execType":"T"},
                  {"instId":"BTC-USDT","instType":"SPOT","ordId":"o2","tradeId":"t2","billId":"898",
                   "fillTime":"1710000001000","side":"sell","fillPx":"101","fillSz":"0.4",
                   "fee":"0.01","feeCcy":"USDT","execType":"M"}]}
                """));

        var page = client.getPage("BTC-USDT", "SPOT", 100, "900", "a".repeat(64));

        assertThat(page.complete()).isTrue();
        assertThat(page.terminal()).isFalse();
        assertThat(page.nextCursor()).isEqualTo("898");
        assertThat(page.fills()).hasSize(2);
        assertThat(page.fills().getFirst().side()).isEqualTo("BUY");
        assertThat(page.fills().getFirst().signedFeeAmount()).isNegative();
        assertThat(page.fills().get(1).signedFeeAmount()).isPositive();
        verify(trading).getFillHistoryPage("SPOT", "BTC-USDT", 100, "900");
        verifyNoMoreInteractions(trading);
    }

    @Test
    void onlyEmptySuccessfulPageProvesTerminal() throws Exception {
        when(trading.getFillHistoryPage("SPOT", "BTC-USDT", 100, "898"))
                .thenReturn(mapper.readTree("{\"code\":\"0\",\"data\":[]}"));
        var page = client.getPage("BTC-USDT", "SPOT", 100, "898", "a".repeat(64));
        assertThat(page.terminal()).isTrue();
        assertThat(page.nextCursor()).isNull();
    }
}
