package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only monitoring and economic evidence for provider-managed OKX Spot Grid bots. */
@Service
@RequiredArgsConstructor
public class OkxNativeGridMcpTools {

    private static final int EXACT_FILL_PAGE_LIMIT = 100;
    private static final int EXACT_FILL_MAX_PAGES_PER_ORDER = 100;

    private final OkxTradingService okxTradingService;
    private final ObjectMapper objectMapper;

    @Tool(description = "Read-only inventory of provider-managed OKX Spot Grid bots. Returns active bots and, "
            + "when includeHistory=true, stopped/history bots. The runtime has no Grid create, amend, "
            + "or stop capability and does not read the deprecated local bt_grid state machine. "
            + "param: includeHistory optional default true")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    public String getOkxNativeSpotGridStatus(Boolean includeHistory) {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("tool", "getOkxNativeSpotGridStatus");
        report.put("boundary", "READ_ONLY_OKX_NATIVE_SPOT_GRID_NO_BOT_MUTATION");
        report.put("migrationTarget", "OKX_NATIVE_SPOT_GRID");
        report.put("customGridCreateResumeDeprecated", true);
        report.put("nativeGridCreateAllowed", false);
        report.put("nativeGridStopAllowed", false);
        report.put("orderSent", false);

        ArrayNode active = copyArray(okxTradingService.getNativeSpotGridOrders(false));
        report.set("active", active);
        report.put("activeCount", active.size());

        if (!Boolean.FALSE.equals(includeHistory)) {
            ArrayNode history = copyArray(okxTradingService.getNativeSpotGridOrders(true));
            report.set("history", history);
            report.put("historyCount", history.size());
        }
        return report.toPrettyString();
    }

    @Tool(description = "Read-only acceptance evidence for one OKX-native BTC-USDT Spot Grid bot. "
            + "Reads active/history/detail, filled/live Grid sub-orders, and authenticated BTC-USDT fill history. "
            + "It counts provider groupId buy/sell pairs and computes signed-fee quote cash flow only when "
            + "terminal-state, fill coverage, fee currency, pagination, and residual-base checks are all proven. "
            + "It never creates/stops a bot, sends an order, or changes DB state. param: algoId")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    public String getOkxNativeSpotGridAcceptanceEvidence(String algoId) {
        if (algoId == null || !algoId.matches("[0-9]+")) {
            throw new IllegalArgumentException("algoId must contain digits only");
        }
        ObjectNode report = objectMapper.createObjectNode();
        report.put("tool", "getOkxNativeSpotGridAcceptanceEvidence");
        report.put("boundary", "READ_ONLY_NATIVE_GRID_EXACT_EVIDENCE_NO_MUTATION");
        report.put("algoId", algoId);
        report.put("instrument", "BTC-USDT");
        report.put("orderSent", false);
        report.put("botMutation", false);
        report.put("databaseMutation", false);
        ArrayNode blockers = report.putArray("blockers");

        ArrayNode active = copyArray(okxTradingService.getNativeSpotGridOrders(false));
        ArrayNode history = copyArray(okxTradingService.getNativeSpotGridOrders(true));
        JsonNode activeBot = findByAlgoId(active, algoId);
        JsonNode historyBot = findByAlgoId(history, algoId);
        boolean terminal = activeBot == null && historyBot != null;
        report.put("providerLifecycle", activeBot != null ? "ACTIVE" : historyBot != null ? "HISTORY_TERMINAL" : "NOT_FOUND");
        report.put("terminalProviderStateProven", terminal);
        if (activeBot != null) report.set("activeBot", activeBot.deepCopy());
        if (historyBot != null) report.set("historyBot", historyBot.deepCopy());
        if (activeBot == null && historyBot == null) blockers.add("PROVIDER_BOT_NOT_FOUND");

        ArrayNode detail = copyArray(okxTradingService.getNativeSpotGridOrderDetails(algoId));
        report.set("providerDetail", detail);
        if (detail.isEmpty()) blockers.add("PROVIDER_BOT_DETAIL_MISSING");

        ArrayNode filledSubOrders = copyArray(okxTradingService.getNativeSpotGridSubOrders(algoId, "filled"));
        ArrayNode liveSubOrders = copyArray(okxTradingService.getNativeSpotGridSubOrders(algoId, "live"));
        report.set("filledSubOrders", filledSubOrders);
        report.set("liveSubOrders", liveSubOrders);
        report.put("filledSubOrderCount", filledSubOrders.size());
        report.put("liveSubOrderCount", liveSubOrders.size());
        if (!liveSubOrders.isEmpty()) blockers.add("LIVE_SUB_ORDERS_REMAIN");

        Map<String, Set<String>> groupSides = new HashMap<>();
        Set<String> filledOrderIds = new HashSet<>();
        for (JsonNode order : filledSubOrders) {
            String orderId = order.path("ordId").asText();
            if (!orderId.isBlank()) filledOrderIds.add(orderId);
            String groupId = order.path("groupId").asText();
            String side = order.path("side").asText().toUpperCase(Locale.ROOT);
            if (!groupId.isBlank() && ("BUY".equals(side) || "SELL".equals(side))) {
                groupSides.computeIfAbsent(groupId, ignored -> new HashSet<>()).add(side);
            }
        }
        long completedPairs = groupSides.values().stream()
                .filter(sides -> sides.contains("BUY") && sides.contains("SELL"))
                .count();
        report.put("completedProviderGroupPairCount", completedPairs);
        if (completedPairs < 1) blockers.add("NO_COMPLETED_PROVIDER_BUY_SELL_GROUP_PAIR");

        FillHistoryCoverage fillHistory = collectOrderFillHistory(filledOrderIds);
        ArrayNode allFills = fillHistory.fills();
        boolean pageComplete = fillHistory.complete();
        report.put("fillHistoryPageCount", fillHistory.pageCount());
        report.put("fillHistoryTotalCount", allFills.size());
        report.put("orderDetailSingleFillFallbackCount", fillHistory.orderDetailFallbackCount());
        report.put("fillHistoryCoverageComplete", pageComplete);
        if (!pageComplete) blockers.add("FILL_HISTORY_PAGINATION_INCOMPLETE");

        ArrayNode botFills = report.putArray("providerFills");
        Set<String> coveredSubOrderIds = new HashSet<>();
        BigDecimal baseFlow = BigDecimal.ZERO;
        BigDecimal quoteFlow = BigDecimal.ZERO;
        boolean signedFeeComplete = true;
        for (JsonNode fill : allFills) {
            String fillAlgoId = fill.path("algoId").asText();
            String orderId = fill.path("ordId").asText();
            if (!algoId.equals(fillAlgoId) && !filledOrderIds.contains(orderId)) continue;
            botFills.add(fill.deepCopy());
            if (filledOrderIds.contains(orderId)) coveredSubOrderIds.add(orderId);
            try {
                BigDecimal price = positiveDecimal(fill, "fillPx");
                BigDecimal quantity = positiveDecimal(fill, "fillSz");
                BigDecimal fee = requiredDecimal(fill, "fee");
                String side = fill.path("side").asText().toUpperCase(Locale.ROOT);
                String feeCurrency = fill.path("feeCcy").asText().toUpperCase(Locale.ROOT);
                if ("BUY".equals(side)) {
                    baseFlow = baseFlow.add(quantity);
                    quoteFlow = quoteFlow.subtract(price.multiply(quantity));
                } else if ("SELL".equals(side)) {
                    baseFlow = baseFlow.subtract(quantity);
                    quoteFlow = quoteFlow.add(price.multiply(quantity));
                } else {
                    signedFeeComplete = false;
                }
                if ("BTC".equals(feeCurrency)) baseFlow = baseFlow.add(fee);
                else if ("USDT".equals(feeCurrency)) quoteFlow = quoteFlow.add(fee);
                else signedFeeComplete = false;
            } catch (IllegalArgumentException invalidFill) {
                signedFeeComplete = false;
            }
        }
        report.put("providerFillCount", botFills.size());
        report.put("signedFeeCoverageComplete", signedFeeComplete);
        report.put("filledSubOrderFillCoverageComplete", coveredSubOrderIds.containsAll(filledOrderIds));
        report.put("netBaseFlowBtc", baseFlow);
        report.put("signedFeeNetQuoteCashFlowUsdt", quoteFlow);
        if (botFills.isEmpty()) blockers.add("NO_PROVIDER_FILLS_BOUND_TO_BOT");
        if (!coveredSubOrderIds.containsAll(filledOrderIds)) blockers.add("SUB_ORDER_FILL_COVERAGE_INCOMPLETE");
        if (!signedFeeComplete) blockers.add("SIGNED_FEE_OR_FILL_FIELDS_INCOMPLETE");

        BigDecimal lotSize = okxTradingService.getSpotInstrumentRules("BTC-USDT").lotSize();
        report.put("providerLotSizeBtc", lotSize);
        boolean residualWithinLot = lotSize != null && baseFlow.abs().compareTo(lotSize) <= 0;
        report.put("baseResidualWithinOneLot", residualWithinLot);
        if (!residualWithinLot) blockers.add("BASE_RESIDUAL_EXCEEDS_ONE_LOT");
        if (!terminal) blockers.add("BOT_NOT_TERMINAL_IN_PROVIDER_HISTORY");

        boolean exactNetProven = terminal && liveSubOrders.isEmpty() && completedPairs >= 1
                && pageComplete && !botFills.isEmpty() && coveredSubOrderIds.containsAll(filledOrderIds)
                && signedFeeComplete && residualWithinLot;
        report.put("exactNetPnlProven", exactNetProven);
        if (exactNetProven) report.put("exactNetPnlUsdt", quoteFlow);
        report.put("functionalAcceptance", "NOT_YET_PROVEN");
        report.put("functionalAcceptanceReason",
                "This receipt proves provider trade economics only; an active bot is not exact-net performance evidence.");
        return report.toPrettyString();
    }

    private ArrayNode copyArray(JsonNode value) {
        ArrayNode result = objectMapper.createArrayNode();
        if (value != null && value.isArray()) value.forEach(result::add);
        return result;
    }

    private JsonNode findByAlgoId(ArrayNode bots, String algoId) {
        for (JsonNode bot : bots) if (algoId.equals(bot.path("algoId").asText())) return bot;
        return null;
    }

    private BigDecimal requiredDecimal(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException("missing " + field);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid " + field, error);
        }
    }

    private BigDecimal positiveDecimal(JsonNode node, String field) {
        BigDecimal value = requiredDecimal(node, field);
        if (value.signum() <= 0) throw new IllegalArgumentException("non-positive " + field);
        return value;
    }

    private FillHistoryCoverage collectOrderFillHistory(Set<String> orderIds) {
        ArrayNode fills = objectMapper.createArrayNode();
        int pageCount = 0;
        int orderDetailFallbackCount = 0;
        boolean complete = true;
        Set<String> seenBillIds = new HashSet<>();
        Set<String> seenTradeIds = new HashSet<>();

        for (String orderId : new java.util.TreeSet<>(orderIds)) {
            String cursor = null;
            boolean orderComplete = false;
            Set<String> seenCursors = new LinkedHashSet<>();
            for (int pageIndex = 0; pageIndex < EXACT_FILL_MAX_PAGES_PER_ORDER; pageIndex++) {
                String cursorKey = cursor == null ? "<ROOT>" : cursor;
                if (!seenCursors.add(cursorKey)) {
                    complete = false;
                    break;
                }
                JsonNode response = okxTradingService.getFillHistoryPage(
                        "SPOT", "BTC-USDT", orderId, EXACT_FILL_PAGE_LIMIT, cursor);
                pageCount++;
                JsonNode data = response == null ? null : response.path("data");
                if (response == null || !"0".equals(response.path("code").asText()) || !data.isArray()) {
                    complete = false;
                    break;
                }
                if (data.isEmpty()) {
                    if (cursor != null) {
                        orderComplete = true;
                    } else {
                        JsonNode detail = okxTradingService.getSpotOrderDetail("BTC-USDT", orderId);
                        JsonNode singleFill = provenSingleFillOrderDetail(detail, orderId, seenTradeIds);
                        if (singleFill != null) {
                            fills.add(singleFill);
                            orderDetailFallbackCount++;
                            orderComplete = true;
                        }
                    }
                    break;
                }

                BigInteger priorBillId = null;
                BigInteger requestCursor = cursor == null ? null : numericId(cursor);
                String nextCursor = null;
                boolean validPage = requestCursor != null || cursor == null;
                for (JsonNode fill : data) {
                    String fillOrderId = fill.path("ordId").asText();
                    String billId = fill.path("billId").asText();
                    String tradeId = fill.path("tradeId").asText();
                    BigInteger numericBillId = numericId(billId);
                    if (!orderId.equals(fillOrderId) || numericBillId == null
                            || tradeId.isBlank() || !seenBillIds.add(billId) || !seenTradeIds.add(tradeId)
                            || requestCursor != null && numericBillId.compareTo(requestCursor) >= 0
                            || priorBillId != null && numericBillId.compareTo(priorBillId) >= 0) {
                        validPage = false;
                        break;
                    }
                    priorBillId = numericBillId;
                    nextCursor = billId;
                    fills.add(fill.deepCopy());
                }
                if (!validPage || nextCursor == null || nextCursor.equals(cursor)) {
                    complete = false;
                    break;
                }
                cursor = nextCursor;
            }
            if (!orderComplete) {
                complete = false;
            }
        }
        return new FillHistoryCoverage(fills, pageCount, orderDetailFallbackCount, complete);
    }

    private JsonNode provenSingleFillOrderDetail(JsonNode detail, String orderId, Set<String> seenTradeIds) {
        if (detail == null || !orderId.equals(detail.path("ordId").asText())
                || !"filled".equalsIgnoreCase(detail.path("state").asText())) {
            return null;
        }
        String tradeId = detail.path("tradeId").asText();
        String side = detail.path("side").asText();
        String feeCurrency = detail.path("feeCcy").asText();
        if (tradeId.isBlank() || !("buy".equalsIgnoreCase(side) || "sell".equalsIgnoreCase(side))
                || feeCurrency.isBlank() || !detail.path("fillTime").asText().matches("[0-9]+")) {
            return null;
        }
        try {
            BigDecimal fillQuantity = positiveDecimal(detail, "fillSz");
            BigDecimal accumulatedQuantity = positiveDecimal(detail, "accFillSz");
            BigDecimal fillPrice = positiveDecimal(detail, "fillPx");
            BigDecimal averagePrice = positiveDecimal(detail, "avgPx");
            requiredDecimal(detail, "fee");
            if (fillQuantity.compareTo(accumulatedQuantity) != 0
                    || fillPrice.compareTo(averagePrice) != 0
                    || !seenTradeIds.add(tradeId)) {
                return null;
            }
        } catch (IllegalArgumentException error) {
            return null;
        }
        ObjectNode receipt = detail.deepCopy();
        receipt.put("evidenceSource", "ORDER_DETAIL_PROVEN_SINGLE_FILL");
        return receipt;
    }

    private BigInteger numericId(String value) {
        if (value == null || !value.matches("[0-9]+")) return null;
        try {
            return new BigInteger(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record FillHistoryCoverage(ArrayNode fills, int pageCount,
                                       int orderDetailFallbackCount, boolean complete) { }
}
