package com.agora.service.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class BtcOrderAdviceOrderCoverageTest {
    final ObjectMapper mapper = new ObjectMapper();
    @Test void partiallyFilledOrdersUseRemainingQuantity() throws Exception {
        var rows = mapper.readTree("[{\"instId\":\"BTC-USDT\",\"ordId\":\"123\",\"side\":\"sell\",\"sz\":\"0.001\",\"accFillSz\":\"0.0004\",\"px\":\"85000\",\"ordType\":\"limit\",\"state\":\"partially_filled\"}]");
        var orders = BtcOrderAdviceSnapshotReader.parseOrders(rows, false);
        assertEquals(0, new BigDecimal("0.0006").compareTo(orders.getFirst().remainingQuantity()));
    }
    @Test void incompletePagesAndMissingFillDataAreNotAnEmptyOrderBook() throws Exception {
        var rows = mapper.createArrayNode(); for (int i = 0; i < 100; i++) rows.addObject();
        assertThrows(IllegalStateException.class, () -> BtcOrderAdviceSnapshotReader.parseOrders(rows, false));
        var malformed = mapper.readTree("[{\"instId\":\"BTC-USDT\",\"ordId\":\"123\",\"side\":\"sell\",\"sz\":\"0.001\",\"px\":\"85000\"}]");
        assertThrows(IllegalStateException.class, () -> BtcOrderAdviceSnapshotReader.parseOrders(malformed, false));
    }
    @Test void quoteSizedMarketBuysAreNeverMisrepresentedAsBtcQuantity() throws Exception {
        var rows = mapper.readTree("[{\"instId\":\"BTC-USDT\",\"ordId\":\"123\",\"side\":\"buy\",\"sz\":\"100\",\"accFillSz\":\"0.0004\",\"px\":\"0\",\"tgtCcy\":\"quote_ccy\",\"ordType\":\"market\"}]");
        assertThrows(IllegalStateException.class, () -> BtcOrderAdviceSnapshotReader.parseOrders(rows, false));
    }
}
