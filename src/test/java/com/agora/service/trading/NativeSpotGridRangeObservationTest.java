package com.agora.service.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpotGridRangeObservationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-02T12:34:07Z");

    @Test void runningBotAboveRangeReportsDistanceFromUpperBound() {
        var result = observe("86725.3");
        assertEquals("running", result.path("providerState").asText());
        assertEquals("ABOVE_RANGE", result.path("rangeStatus").asText());
        assertEquals(0, new BigDecimal("28.94229769")
                .compareTo(result.path("deviationFromNearestBoundaryPercent").decimalValue()));
        assertTrue(result.path("operatorReviewRequired").asBoolean());
        assertFalse(result.path("automaticAdjustmentAllowed").asBoolean());
    }

    @Test void lowerAndUpperEdgesAreInsideAndBelowUsesLowerBoundDenominator() {
        for (String price : new String[]{"63978", "65702", "67259"}) {
            var result = observe(price);
            assertEquals("WITHIN_RANGE", result.path("rangeStatus").asText());
            assertFalse(result.path("outOfRange").asBoolean());
        }
        var below = observe("57580.2");
        assertEquals("BELOW_RANGE", below.path("rangeStatus").asText());
        assertEquals(0, BigDecimal.TEN.compareTo(below.path("deviationFromNearestBoundaryPercent").decimalValue()));
    }

    @Test void staleFutureMissingAndWrongInstrumentNeverBecomeHealthy() {
        assertMissing(null, "TICKER_UNAVAILABLE");
        assertMissing(ticker("65000").put("ts", now.minusMillis(60001).toEpochMilli()), "STALE_TICKER");
        assertMissing(ticker("65000").put("ts", now.plusMillis(5001).toEpochMilli()), "FUTURE_TICKER_TIMESTAMP");
        assertMissing(ticker("65000").put("ts", ""), "INVALID_TICKER_TIMESTAMP");
        assertMissing(ticker("65000").put("instId", "ETH-USDT"), "TICKER_INSTRUMENT_MISMATCH");
        assertMissing(ticker("65000").put("instType", "SWAP"), "TICKER_INSTRUMENT_MISMATCH");
    }

    @Test void invalidPricesAndRangesRemainUnknown() {
        for (String price : new String[]{"", "NaN", "0", "-1"}) assertMissing(ticker(price), "INVALID_TICKER_PRICE");
        for (String lower : new String[]{"", "0", "67259", "68000"}) {
            var result = NativeSpotGridRangeObservation.observe(bot().put("minPx", lower), ticker("65000"), now, mapper);
            assertEquals("MISSING_PROOF", result.path("rangeStatus").asText());
            assertEquals("INVALID_PROVIDER_RANGE", result.path("reason").asText());
        }
    }

    @Test void timestampThresholdIsInclusiveAndProviderInputIsUnmodified() {
        ObjectNode bot = bot(), ticker = ticker("86725.3").put("ts", now.minusMillis(60000).toEpochMilli());
        String before = bot.toString();
        var result = NativeSpotGridRangeObservation.observe(bot, ticker, now, mapper);
        assertEquals("ABOVE_RANGE", result.path("rangeStatus").asText());
        assertEquals(before, bot.toString());
    }

    private ObjectNode observe(String price) {
        return NativeSpotGridRangeObservation.observe(bot(), ticker(price), now, mapper);
    }
    private void assertMissing(ObjectNode ticker, String reason) {
        var result = NativeSpotGridRangeObservation.observe(bot(), ticker, now, mapper);
        assertEquals("MISSING_PROOF", result.path("rangeStatus").asText());
        assertEquals(reason, result.path("reason").asText());
        assertTrue(result.path("outOfRange").isNull());
        assertTrue(result.path("deviationFromNearestBoundaryPercent").isNull());
    }
    private ObjectNode bot() {
        return mapper.createObjectNode().put("algoId", "123").put("instId", "BTC-USDT")
                .put("state", "running").put("minPx", "63978").put("maxPx", "67259");
    }
    private ObjectNode ticker(String last) {
        return mapper.createObjectNode().put("instId", "BTC-USDT").put("instType", "SPOT")
                .put("last", last).put("ts", now.minusSeconds(1).toEpochMilli());
    }
}
