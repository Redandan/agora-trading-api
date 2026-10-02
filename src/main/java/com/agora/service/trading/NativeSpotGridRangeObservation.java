package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Price/range evidence only. No parameter selection or exchange mutation. */
public final class NativeSpotGridRangeObservation {
    public static final long MAX_TICKER_AGE_MS = 60_000;
    private static final long MAX_FUTURE_SKEW_MS = 5_000;

    private NativeSpotGridRangeObservation() { }

    public static ObjectNode observe(JsonNode bot, JsonNode ticker, Instant observedAt, ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        result.put("schema", "OKX_SPOT_GRID_RANGE_V1");
        result.put("algoId", bot.path("algoId").asText());
        result.put("instId", bot.path("instId").asText());
        result.put("providerState", bot.path("state").asText());
        result.put("observedAtUtc", observedAt.toString());
        result.put("maximumTickerAgeMs", MAX_TICKER_AGE_MS);
        result.put("rangeStatus", "MISSING_PROOF");
        result.putNull("outOfRange");
        result.putNull("deviationFromNearestBoundaryPercent");
        result.put("operatorReviewRequired", true);
        result.put("automaticAdjustmentAllowed", false);

        BigDecimal lower = positive(bot, "minPx");
        BigDecimal upper = positive(bot, "maxPx");
        if (lower == null || upper == null || lower.compareTo(upper) >= 0) {
            return missing(result, "INVALID_PROVIDER_RANGE");
        }
        result.put("lowerPrice", lower);
        result.put("upperPrice", upper);
        if (ticker == null || ticker.isMissingNode() || ticker.isNull()) {
            return missing(result, "TICKER_UNAVAILABLE");
        }
        String instrument = bot.path("instId").asText();
        if (instrument.isBlank() || !instrument.equals(ticker.path("instId").asText())
                || !"SPOT".equals(ticker.path("instType").asText())) {
            return missing(result, "TICKER_INSTRUMENT_MISMATCH");
        }
        BigDecimal price = positive(ticker, "last");
        if (price == null) return missing(result, "INVALID_TICKER_PRICE");
        result.put("lastPrice", price);
        long timestamp;
        try {
            timestamp = Long.parseLong(ticker.path("ts").asText());
            if (timestamp <= 0) return missing(result, "INVALID_TICKER_TIMESTAMP");
        } catch (NumberFormatException invalidTimestamp) {
            return missing(result, "INVALID_TICKER_TIMESTAMP");
        }
        result.put("tickerTimestampUtc", Instant.ofEpochMilli(timestamp).toString());
        long ageMs = observedAt.toEpochMilli() - timestamp;
        result.put("tickerAgeMs", ageMs);
        if (ageMs < -MAX_FUTURE_SKEW_MS) return missing(result, "FUTURE_TICKER_TIMESTAMP");
        if (ageMs > MAX_TICKER_AGE_MS) return missing(result, "STALE_TICKER");

        boolean above = price.compareTo(upper) > 0;
        boolean below = price.compareTo(lower) < 0;
        BigDecimal deviation = above ? percent(price.subtract(upper), upper)
                : below ? percent(lower.subtract(price), lower) : BigDecimal.ZERO;
        result.put("rangeStatus", above ? "ABOVE_RANGE" : below ? "BELOW_RANGE" : "WITHIN_RANGE");
        result.put("outOfRange", above || below);
        result.put("deviationFromNearestBoundaryPercent", deviation);
        result.put("operatorReviewRequired", above || below);
        result.put("reason", above || below ? "PRICE_OUTSIDE_CONFIGURED_GRID" : "PRICE_WITHIN_CONFIGURED_GRID");
        return result;
    }

    private static ObjectNode missing(ObjectNode result, String reason) {
        result.put("reason", reason);
        return result;
    }

    private static BigDecimal positive(JsonNode node, String field) {
        try {
            BigDecimal value = new BigDecimal(node.path(field).asText());
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static BigDecimal percent(BigDecimal distance, BigDecimal boundary) {
        return distance.multiply(BigDecimal.valueOf(100)).divide(boundary, 8, RoundingMode.HALF_UP);
    }
}
