package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/** Bounded read pagination. Exhaustion, not a short/full page, establishes coverage. */
public final class NativeSpotGridSubOrderInventory {
    public static final int MAX_PAGES = 10;

    private NativeSpotGridSubOrderInventory() { }

    public record Result(ArrayNode orders, int pageCount, boolean complete, String reason) { }

    public static Result collect(String algoId, String instrument, Function<String, JsonNode> fetchPage,
                                 ObjectMapper mapper) {
        return collect(algoId, instrument, fetchPage, mapper, null, false);
    }

    /** Live queries may repeat the same snapshot for every cursor. Reconcile its explicit detail count. */
    public static Result collectLive(String algoId, String instrument, JsonNode detail,
                                     Function<String, JsonNode> fetchPage, ObjectMapper mapper) {
        BigInteger expectedCount = null;
        if (detail != null && algoId.equals(detail.path("algoId").asText())
                && instrument.equals(detail.path("instId").asText())
                && "running".equals(detail.path("state").asText())) {
            String count = detail.path("activeOrdNum").asText();
            if (count.matches("[0-9]+")) expectedCount = new BigInteger(count);
        }
        return collect(algoId, instrument, fetchPage, mapper, expectedCount, true);
    }

    private static Result collect(String algoId, String instrument, Function<String, JsonNode> fetchPage,
                                  ObjectMapper mapper, BigInteger expectedCount, boolean live) {
        ArrayNode orders = mapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        BigInteger cursor = null;
        for (int pageNumber = 1; pageNumber <= MAX_PAGES; pageNumber++) {
            JsonNode page;
            try {
                page = fetchPage.apply(cursor == null ? null : cursor.toString());
            } catch (RuntimeException unavailable) {
                var code = java.util.regex.Pattern.compile("^OKX API error \\[code=([0-9]+)\\]")
                        .matcher(String.valueOf(unavailable.getMessage()));
                if (code.find()) return new Result(orders, pageNumber, false, "SUB_ORDER_PROVIDER_ERROR_" + code.group(1));
                return new Result(orders, pageNumber, false, "SUB_ORDER_PAGE_UNAVAILABLE");
            }
            if (page == null || !page.isArray()) {
                return new Result(orders, pageNumber, false, "INVALID_SUB_ORDER_PAGE");
            }
            if (page.isEmpty()) {
                if (expectedCount != null && expectedCount.compareTo(BigInteger.valueOf(orders.size())) != 0)
                    return new Result(orders, pageNumber, false, "LIVE_DETAIL_COUNT_MISMATCH");
                return new Result(orders, pageNumber, true, "EXHAUSTED");
            }
            BigInteger oldest = null;
            for (JsonNode order : page) {
                String id = order.path("ordId").asText();
                BigInteger numericId = id.matches("[0-9]+") ? new BigInteger(id) : null;
                if (numericId == null || numericId.signum() <= 0 || !seen.add(id)
                        || cursor != null && numericId.compareTo(cursor) >= 0
                        || !algoId.equals(order.path("algoId").asText())
                        || !instrument.equals(order.path("instId").asText())
                        || live && !Set.of("live", "partially_filled", "cancelling").contains(order.path("state").asText())) {
                    return new Result(orders, pageNumber, false, "INVALID_OR_NON_ADVANCING_SUB_ORDER_PAGE");
                }
                orders.add(order.deepCopy());
                if (oldest == null || numericId.compareTo(oldest) < 0) oldest = numericId;
            }
            if (expectedCount != null) {
                int comparison = BigInteger.valueOf(orders.size()).compareTo(expectedCount);
                if (comparison > 0) return new Result(orders, pageNumber, false, "LIVE_DETAIL_COUNT_MISMATCH");
                if (comparison == 0) return new Result(orders, pageNumber, true, "LIVE_DETAIL_COUNT_MATCHED");
            }
            cursor = oldest;
        }
        return new Result(orders, MAX_PAGES, false, "SUB_ORDER_PAGE_LIMIT_REACHED");
    }
}
