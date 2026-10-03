package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class BtcDraProfitExitPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void tickRoundingNeverLetsExecutionSlipBelowTheNetProfitFloor() {
        for (String fee : new String[]{"0", "0.001", "0.005"}) {
            for (String tick : new String[]{"0.1", "1", "0.00001"}) {
                var cost = new BigDecimal("84763.21948371");
                var rate = new BigDecimal(fee); var step = new BigDecimal(tick);
                var floor = BtcDraProfitExitPolicy.minimumSellPrice(cost, step, rate);
                assertEquals(0, floor.remainder(step).signum());
                assertTrue(floor.multiply(BigDecimal.ONE.subtract(rate)).compareTo(cost.multiply(new BigDecimal("1.05"))) >= 0);
                var expected = cost.multiply(new BigDecimal("1.05"));
                var conservativeNet = BigDecimal.ONE.subtract(rate.max(new BigDecimal("0.001")))
                        .multiply(new BigDecimal("0.9995"));
                assertTrue(floor.subtract(step).multiply(conservativeNet).compareTo(expected) < 0);
            }
        }
    }

    @Test void missingFeesTicksAndInvalidPricesCannotCreateAPriceFloor() {
        assertThrows(IllegalArgumentException.class, () -> BtcDraProfitExitPolicy.minimumSellPrice(BigDecimal.ONE, null, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> BtcDraProfitExitPolicy.minimumSellPrice(BigDecimal.ONE, BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> BtcDraProfitExitPolicy.minimumSellPrice(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> BtcDraProfitExitPolicy.minimumSellPrice(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
    }

    @Test void providerRequestProtectsThePriceAndCancelsUnfilledRemainder() throws Exception {
        var body = mapper.readTree(OkxTradingService.iocSellBody("BTC-USDT", new BigDecimal("0.00015"),
                new BigDecimal("91234.5"), "DRA1S20261003000000"));
        assertEquals("ioc", body.path("ordType").asText());
        assertEquals("91234.5", body.path("px").asText());
        assertEquals("0", body.path("pxAmendType").asText());
        assertEquals("cash", body.path("tdMode").asText());
        assertEquals("sell", body.path("side").asText());
        assertThrows(IllegalArgumentException.class, () -> OkxTradingService.iocSellBody("ETH-USDT", BigDecimal.ONE, BigDecimal.ONE, "DRA1"));
    }

    @Test void feeParserRequiresSpotAndDoesNotRelyOnRebates() throws Exception {
        assertEquals(new BigDecimal("0.002"), OkxTradingService.parseSpotTakerFeeRate(mapper.readTree("{\"instType\":\"SPOT\",\"taker\":\"-0.002\"}")));
        assertEquals(BigDecimal.ZERO, OkxTradingService.parseSpotTakerFeeRate(mapper.readTree("{\"instType\":\"SPOT\",\"taker\":\"0.001\"}")));
        assertThrows(IllegalStateException.class, () -> OkxTradingService.parseSpotTakerFeeRate(mapper.readTree("{\"instType\":\"SPOT\"}")));
    }

    @Test void zeroFillCancellationIsARealReceiptAndWrongInstrumentCannotBeReconciled() throws Exception {
        var parser = new OkxTradingService(new OkxTradingProperties(), mapper);
        var response = mapper.readTree("""
                {"code":"0","data":[{"instId":"BTC-USDT","ordId":"123","clOrdId":"DRA1",
                 "side":"sell","state":"canceled","avgPx":"","accFillSz":"0"}]}
                """);
        var snapshot = parser.parseSpotOrderLookup(response, "DRA1", "BTC-USDT").snapshot();
        assertEquals("canceled", snapshot.providerState());
        assertEquals(0, snapshot.cumulativeGrossQuantity().signum());
        assertThrows(IllegalStateException.class, () -> parser.parseSpotOrderLookup(response, "DRA1", "ETH-USDT"));
    }
}
