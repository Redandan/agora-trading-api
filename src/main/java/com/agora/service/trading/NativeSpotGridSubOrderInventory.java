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
        ArrayNode orders = mapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        BigInteger cursor = null;
        for (int pageNumber = 1; pageNumber <= MAX_PAGES; pageNumber++) {
            JsonNode page;
            try {
                page = fetchPage.apply(cursor == null ? null : cursor.toString());
            } catch (RuntimeException unavailable) {
                return new Result(orders, pageNumber, false, "SUB_ORDER_PAGE_UNAVAILABLE");
            }
            if (page == null || !page.isArray()) {
                return new Result(orders, pageNumber, false, "INVALID_SUB_ORDER_PAGE");
            }
            if (page.isEmpty()) return new Result(orders, pageNumber, true, "EXHAUSTED");
            BigInteger oldest = null;
            for (JsonNode order : page) {
                String id = order.path("ordId").asText();
                BigInteger numericId = id.matches("[0-9]+") ? new BigInteger(id) : null;
                if (numericId == null || numericId.signum() <= 0 || !seen.add(id)
                        || cursor != null && numericId.compareTo(cursor) >= 0
                        || !algoId.equals(order.path("algoId").asText())
                        || !instrument.equals(order.path("instId").asText())) {
                    return new Result(orders, pageNumber, false, "INVALID_OR_NON_ADVANCING_SUB_ORDER_PAGE");
                }
                orders.add(order.deepCopy());
                if (oldest == null || numericId.compareTo(oldest) < 0) oldest = numericId;
            }
            cursor = oldest;
        }
        return new Result(orders, MAX_PAGES, false, "SUB_ORDER_PAGE_LIMIT_REACHED");
    }
}
