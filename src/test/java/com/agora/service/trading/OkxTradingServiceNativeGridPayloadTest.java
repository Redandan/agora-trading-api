package com.agora.service.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OkxTradingServiceNativeGridPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createPayloadIsQuoteOnlySpotGridWithNoLeverageOrContractFields() {
        ObjectNode body = OkxTradingService.nativeSpotGridCreateBody(
                mapper, "BTC-USDT", new BigDecimal("60000"), new BigDecimal("70000"),
                10, new BigDecimal("10"), "OKXGRIDTINY001");

        assertThat(body.path("instId").asText()).isEqualTo("BTC-USDT");
        assertThat(body.path("algoOrdType").asText()).isEqualTo("grid");
        assertThat(body.path("runType").asText()).isEqualTo("1");
        assertThat(body.path("quoteSz").decimalValue()).isEqualByComparingTo("10");
        assertThat(body.path("algoClOrdId").asText()).isEqualTo("OKXGRIDTINY001");
        assertThat(body.has("lever")).isFalse();
        assertThat(body.has("direction")).isFalse();
        assertThat(body.has("sz")).isFalse();
        assertThat(body.has("baseSz")).isFalse();
        assertThat(body.has("tdMode")).isFalse();
    }

    @Test
    void minimumInvestmentPayloadRequestsTheExactQuoteOnlyArithmeticPackage() {
        ObjectNode body = OkxTradingService.nativeSpotGridMinimumInvestmentBody(
                mapper, "BTC-USDT", new BigDecimal("60000"), new BigDecimal("70000"), 10);

        assertThat(body.path("instId").asText()).isEqualTo("BTC-USDT");
        assertThat(body.path("algoOrdType").asText()).isEqualTo("grid");
        assertThat(body.path("runType").asText()).isEqualTo("1");
        assertThat(body.path("investmentType").asText()).isEqualTo("quote");
        assertThat(body.path("gridNum").asText()).isEqualTo("10");
        assertThat(body.has("lever")).isFalse();
        assertThat(body.has("direction")).isFalse();
        assertThat(body.has("investmentData")).isFalse();
    }

    @Test
    void stopPayloadIsOneExactSpotBotAndExplicitDisposition() {
        ArrayNode body = OkxTradingService.nativeSpotGridStopBody(mapper, "123456789", "2");

        assertThat(body).hasSize(1);
        assertThat(body.path(0).path("algoId").asText()).isEqualTo("123456789");
        assertThat(body.path(0).path("algoOrdType").asText()).isEqualTo("grid");
        assertThat(body.path(0).path("instId").asText()).isEqualTo("BTC-USDT");
        assertThat(body.path(0).path("stopType").asText()).isEqualTo("2");
    }
}
