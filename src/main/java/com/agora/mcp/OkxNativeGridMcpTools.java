package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.OkxNativeGridExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only migration bridge for OKX-native Spot Grid bots. */
@Service
@RequiredArgsConstructor
public class OkxNativeGridMcpTools {

    private static final BigDecimal TINY_LIVE_QUOTE_CAP = new BigDecimal("10");
    private static final int EXACT_FILL_PAGE_LIMIT = 100;
    private static final int EXACT_FILL_MAX_PAGES_PER_ORDER = 100;
    private static final Set<String> INVENTORY_OR_IN_FLIGHT_STATUSES = Set.of(
            "HOLDING", "SELL_FAILED", "SELL_PARTIAL", "PENDING_OKX", "SELLING_OKX");

    private final OkxTradingService okxTradingService;
    private final ObjectMapper objectMapper;
    private final BtGridRepository gridRepository;
    private final BtGridLevelRepository gridLevelRepository;
    private final OkxNativeGridExecutionService executionService;

    @Tool(description = "Read-only inventory of OKX-native Spot Grid bots. Returns active bots and, "
            + "when includeHistory=true, stopped/history bots. This migration tool never creates, amends, "
            + "or stops a bot and does not read the deprecated local bt_grid state machine. "
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

    @Tool(description = "Read-only preflight for migrating one BTC-USDT Spot Grid to OKX native Grid. "
            + "It validates the <=10 USDT, one-bot, spot-only envelope and reports legacy-grid inventory "
            + "or in-flight states. It never creates/stops a bot, closes a legacy holding, or changes DB state. "
            + "params: symbol, priceLower, priceUpper, gridCount, totalQuoteUsdt")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    public String previewOkxNativeSpotGridMigration(String symbol,
                                                     BigDecimal priceLower,
                                                     BigDecimal priceUpper,
                                                     Integer gridCount,
                                                     BigDecimal totalQuoteUsdt) {
        String instrument = normalizeInstrument(symbol);
        ObjectNode report = objectMapper.createObjectNode();
        report.put("tool", "previewOkxNativeSpotGridMigration");
        report.put("boundary", "READ_ONLY_PREFLIGHT_NO_GRID_OR_ORDER_MUTATION");
        report.put("instrument", instrument);
        report.put("spotOnly", true);
        report.put("leverage", "1x");
        report.put("singleBot", true);
        report.put("quoteCapUsdt", TINY_LIVE_QUOTE_CAP);
        report.put("orderSent", false);
        report.put("dbMutation", false);

        ArrayNode blockers = report.putArray("blockers");
        validateRequest(instrument, priceLower, priceUpper, gridCount, totalQuoteUsdt, blockers);
        appendProviderRuleEvidence(report, blockers, instrument, priceLower, priceUpper, gridCount, totalQuoteUsdt);

        ArrayNode activeNative = copyArray(okxTradingService.getNativeSpotGridOrders(false));
        report.set("activeNativeBots", activeNative);
        report.put("activeNativeBotCount", activeNative.size());
        if (!activeNative.isEmpty()) {
            blockers.add("SINGLE_BOT_LIMIT_REQUIRES_ZERO_ACTIVE_OKX_NATIVE_GRID_BOTS");
        }

        String databaseSymbol = instrument.replace("-", "");
        List<BtGrid> legacyGrids = gridRepository.findBySymbolAndClosedAtIsNull(databaseSymbol);
        ArrayNode legacy = report.putArray("openLegacyGrids");
        boolean hasInventoryOrInFlight = false;
        for (BtGrid grid : legacyGrids) {
            ObjectNode item = legacy.addObject();
            item.put("gridId", grid.getId());
            item.put("enabled", Boolean.TRUE.equals(grid.getEnabled()));
            item.put("paused", grid.getPausedAt() != null);
            ArrayNode unsafeLevels = item.putArray("inventoryOrInFlightLevels");
            for (BtGridLevel level : gridLevelRepository.findByGridId(grid.getId())) {
                if (!INVENTORY_OR_IN_FLIGHT_STATUSES.contains(level.getStatus())) continue;
                hasInventoryOrInFlight = true;
                ObjectNode levelItem = unsafeLevels.addObject();
                levelItem.put("levelId", level.getId());
                levelItem.put("status", level.getStatus());
                if (level.getFilledQty() != null) levelItem.put("filledQty", level.getFilledQty());
                if (level.getPairedSellPrice() != null) levelItem.put("pairedSellPrice", level.getPairedSellPrice());
            }
            item.put("inventoryOrInFlightCount", unsafeLevels.size());
        }
        report.put("openLegacyGridCount", legacy.size());
        if (!legacy.isEmpty()) blockers.add("OPEN_LEGACY_GRID_MUST_BE_RETIRED_SEPARATELY");
        if (hasInventoryOrInFlight) {
            blockers.add("LEGACY_INVENTORY_OR_IN_FLIGHT_LEVEL_REQUIRES_SEPARATE_RESOLUTION_AUTHORIZATION");
        }

        report.put("decision", blockers.isEmpty()
                ? "READY_FOR_SEPARATE_EXACT_TRADE_AUTHORIZATION"
                : "NOT_READY_FOR_TRADE_AUTHORIZATION");
        report.put("requiredNextAuthorization",
                "Separate exact authorization for legacy holding resolution, then a separate exact OKX native bot create package");
        return report.toPrettyString();
    }

    @Tool(description = "Protected OKX-native BTC-USDT Spot Grid create workflow. Dry-run by default. "
            + "Hard-limits product to Spot grid, 1x/no leverage, one active bot, arithmetic spacing, and quoteSz <=10 USDT. "
            + "execute=true additionally requires both disabled-by-default server gates, zero open legacy grids/in-flight inventory, "
            + "a unique 1-32 character alphanumeric algoClOrdId, and the exact dynamic confirmText returned by the dry-run. "
            + "No database or custom Grid mutation occurs. params: symbol, minPx, maxPx, gridNum, quoteSz, algoClOrdId, execute, confirmText")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    public String createOkxNativeSpotGrid(
            String symbol,
            BigDecimal minPx,
            BigDecimal maxPx,
            Integer gridNum,
            BigDecimal quoteSz,
            String algoClOrdId,
            @ToolParam(required = false, description = "False/null for dry-run; true requests guarded provider create") Boolean execute,
            @ToolParam(required = false, description = "Exact dynamic confirmation text returned by dry-run") String confirmText) {
        return executionService.previewOrCreate(
                symbol, minPx, maxPx, gridNum, quoteSz, algoClOrdId, execute, confirmText);
    }

    @Tool(description = "Protected OKX-native BTC-USDT Spot Grid stop workflow. Dry-run by default. "
            + "disposition must be SELL_BASE (provider stopType=1, may market-sell bot BTC) or KEEP_BASE "
            + "(stopType=2, leaves attributable BTC). execute=true requires both disabled-by-default server gates, "
            + "the exact active provider algoId, and exact dynamic confirmText containing a hash of current bot state. "
            + "No database or custom Grid mutation occurs. params: algoId, disposition, execute, confirmText")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    public String stopOkxNativeSpotGrid(
            String algoId,
            String disposition,
            @ToolParam(required = false, description = "False/null for dry-run; true requests guarded provider stop") Boolean execute,
            @ToolParam(required = false, description = "Exact dynamic confirmation text returned by dry-run") String confirmText) {
        return executionService.previewOrStop(algoId, disposition, execute, confirmText);
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
                "This receipt proves provider trade economics only; create idempotency, restart rediscovery, duplicate/order safety, and authorized stop receipts remain separate Gate A evidence.");
        return report.toPrettyString();
    }

    @Tool(description = "Read-only Gate A safety evidence for one OKX-native BTC-USDT Spot Grid acceptance window. "
            + "It proves current legacy Grid closure, zero legacy inventory/in-flight state, zero custom Grid order "
            + "activity since windowStartUtc, and exactly one provider-native BTC-USDT Grid bot created in the window "
            + "with the expected algoId/algoClOrdId. It reads provider inventory, account holdings, and legacy rows only; "
            + "it never creates/stops a bot, sends an order, or mutates DB. params: algoId, algoClOrdId, windowStartUtc")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    public String getOkxNativeSpotGridFunctionalSafetyEvidence(String algoId,
                                                                String algoClOrdId,
                                                                String windowStartUtc) {
        if (algoId == null || !algoId.matches("[0-9]+")) {
            throw new IllegalArgumentException("algoId must contain digits only");
        }
        if (algoClOrdId == null || !algoClOrdId.matches("[A-Za-z0-9]{1,32}")) {
            throw new IllegalArgumentException("algoClOrdId must be 1-32 alphanumeric characters");
        }
        Instant windowStart;
        try {
            windowStart = Instant.parse(windowStartUtc);
        } catch (RuntimeException invalidTimestamp) {
            throw new IllegalArgumentException("windowStartUtc must be an ISO-8601 UTC instant", invalidTimestamp);
        }

        ObjectNode report = objectMapper.createObjectNode();
        report.put("tool", "getOkxNativeSpotGridFunctionalSafetyEvidence");
        report.put("boundary", "READ_ONLY_GATE_A_SAFETY_EVIDENCE_NO_MUTATION");
        report.put("algoId", algoId);
        report.put("algoClOrdId", algoClOrdId);
        report.put("windowStartUtc", windowStart.toString());
        report.put("windowStartEpochMs", windowStart.toEpochMilli());
        report.put("orderSent", false);
        report.put("botMutation", false);
        report.put("databaseMutation", false);
        ArrayNode blockers = report.putArray("blockers");

        List<BtGrid> grids = gridRepository.findAll();
        Map<Long, BtGrid> gridsById = new HashMap<>();
        for (BtGrid grid : grids) gridsById.put(grid.getId(), grid);
        long openLegacyGridCount = grids.stream().filter(grid -> grid.getClosedAt() == null).count();
        report.put("legacyGridCount", grids.size());
        report.put("openLegacyGridCount", openLegacyGridCount);
        if (openLegacyGridCount > 0) blockers.add("OPEN_LEGACY_GRIDS_REMAIN");

        ArrayNode unsafeLevels = report.putArray("legacyInventoryOrInFlightLevels");
        ArrayNode customActivity = report.putArray("customGridOrderActivitySinceWindowStart");
        LocalDateTime windowStartDb = LocalDateTime.ofInstant(windowStart, ZoneOffset.UTC);
        for (BtGridLevel level : gridLevelRepository.findAll()) {
            if (INVENTORY_OR_IN_FLIGHT_STATUSES.contains(level.getStatus())) {
                ObjectNode item = unsafeLevels.addObject();
                item.put("gridId", level.getGridId());
                item.put("levelId", level.getId());
                item.put("status", level.getStatus());
                if (level.getFilledQty() != null) item.put("filledQty", level.getFilledQty());
            }
            boolean buyActivity = level.getBuyOrderId() != null && !level.getBuyOrderId().isBlank()
                    && level.getFilledAt() != null && !level.getFilledAt().isBefore(windowStartDb);
            boolean sellActivity = level.getSellOrderId() != null && !level.getSellOrderId().isBlank()
                    && level.getClosedAt() != null && !level.getClosedAt().isBefore(windowStartDb);
            if (buyActivity || sellActivity) {
                ObjectNode item = customActivity.addObject();
                item.put("gridId", level.getGridId());
                item.put("levelId", level.getId());
                if (buyActivity) {
                    item.put("buyOrderId", level.getBuyOrderId());
                    item.put("filledAt", level.getFilledAt().toString());
                }
                if (sellActivity) {
                    item.put("sellOrderId", level.getSellOrderId());
                    item.put("closedAt", level.getClosedAt().toString());
                }
                BtGrid parent = gridsById.get(level.getGridId());
                if (parent != null) item.put("symbol", parent.getSymbol());
            }
        }
        report.put("legacyInventoryOrInFlightLevelCount", unsafeLevels.size());
        report.put("customGridOrderActivityCountSinceWindowStart", customActivity.size());
        if (!unsafeLevels.isEmpty()) blockers.add("LEGACY_INVENTORY_OR_IN_FLIGHT_REMAINS");
        if (!customActivity.isEmpty()) blockers.add("CUSTOM_GRID_ORDER_ACTIVITY_IN_ACCEPTANCE_WINDOW");

        ArrayNode active = copyArray(okxTradingService.getNativeSpotGridOrders(false));
        ArrayNode history = copyArray(okxTradingService.getNativeSpotGridOrders(true));
        report.set("activeNativeBots", active);
        report.set("historyNativeBots", history);
        report.put("activeNativeBotCount", active.size());
        if (active.size() > 1) blockers.add("MULTIPLE_ACTIVE_NATIVE_BOTS");

        Map<String, JsonNode> uniqueBots = new HashMap<>();
        active.forEach(bot -> addUniqueBot(uniqueBots, bot));
        history.forEach(bot -> addUniqueBot(uniqueBots, bot));
        ArrayNode createdInWindow = report.putArray("nativeBtcUsdtBotsCreatedSinceWindowStart");
        int targetIdentityCount = 0;
        boolean targetCreateTimeComplete = true;
        for (JsonNode bot : uniqueBots.values()) {
            if (algoId.equals(bot.path("algoId").asText())
                    && algoClOrdId.equals(bot.path("algoClOrdId").asText())) {
                targetIdentityCount++;
            }
            if (!"BTC-USDT".equals(bot.path("instId").asText())) continue;
            Long createdAtMs = epochMillis(bot.path("cTime"));
            if (algoId.equals(bot.path("algoId").asText()) && createdAtMs == null) {
                targetCreateTimeComplete = false;
            }
            if (createdAtMs != null && createdAtMs >= windowStart.toEpochMilli()) {
                createdInWindow.add(bot.deepCopy());
            }
        }
        report.put("targetProviderIdentityCount", targetIdentityCount);
        report.put("targetCreateTimeComplete", targetCreateTimeComplete);
        report.put("nativeBtcUsdtBotCountCreatedSinceWindowStart", createdInWindow.size());
        if (targetIdentityCount != 1) blockers.add("TARGET_PROVIDER_IDENTITY_MUST_OCCUR_EXACTLY_ONCE");
        if (!targetCreateTimeComplete) blockers.add("TARGET_PROVIDER_CREATE_TIME_MISSING");
        if (createdInWindow.size() != 1) blockers.add("EXACTLY_ONE_NATIVE_BTC_USDT_BOT_MUST_BE_CREATED_IN_WINDOW");

        ArrayNode holdings = report.putArray("currentSpotHoldings");
        for (OkxTradingService.SpotHolding holding : okxTradingService.getFreshSpotHoldings()) {
            if (!Set.of("BTC", "USDT").contains(holding.ccy.toUpperCase(Locale.ROOT))) continue;
            ObjectNode item = holdings.addObject();
            item.put("currency", holding.ccy);
            item.put("available", holding.availBal);
            item.put("cash", holding.cashBal);
            item.put("equityUsd", holding.eqUsd);
        }
        if (holdings.isEmpty()) blockers.add("SPOT_HOLDINGS_SNAPSHOT_EMPTY_OR_UNAVAILABLE");

        report.put("safetyEvidencePass", blockers.isEmpty());
        report.put("gateAComponent", blockers.isEmpty()
                ? "PASS_GATE_A_SAFETY_COMPONENT_ONLY"
                : "FAIL_GATE_A_SAFETY_COMPONENT");
        report.put("overallFunctionalAcceptance", "NOT_YET_PROVEN_BY_THIS_COMPONENT");
        return report.toPrettyString();
    }

    private void addUniqueBot(Map<String, JsonNode> uniqueBots, JsonNode bot) {
        String id = bot.path("algoId").asText();
        if (!id.isBlank()) uniqueBots.putIfAbsent(id, bot);
    }

    private Long epochMillis(JsonNode value) {
        String text = value == null ? "" : value.asText();
        if (text.isBlank()) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private void appendProviderRuleEvidence(ObjectNode report,
                                            ArrayNode blockers,
                                            String instrument,
                                            BigDecimal priceLower,
                                            BigDecimal priceUpper,
                                            Integer gridCount,
                                            BigDecimal totalQuoteUsdt) {
        ObjectNode evidence = report.putObject("providerRuleEvidence");
        evidence.put("source", "OKX_PUBLIC_INSTRUMENT_AND_TICKER_READ_ONLY");
        if (!"BTC-USDT".equals(instrument) || priceLower == null || priceUpper == null
                || priceLower.signum() <= 0 || priceUpper.signum() <= 0
                || gridCount == null || gridCount < 2 || totalQuoteUsdt == null) {
            evidence.put("status", "NOT_COMPUTABLE_INVALID_REQUEST");
            return;
        }
        try {
            OkxTradingService.SpotInstrumentRules rules = okxTradingService.getSpotInstrumentRules(instrument);
            BigDecimal lastPrice = okxTradingService.getLastPrice(instrument);
            evidence.put("instrument", rules.instId());
            putDecimal(evidence, "minSizeBase", rules.minSize());
            putDecimal(evidence, "lotSizeBase", rules.lotSize());
            putDecimal(evidence, "tickSizeQuote", rules.tickSize());
            putDecimal(evidence, "lastPrice", lastPrice);
            if (rules.minSize() == null) {
                evidence.put("status", "MIN_SIZE_MISSING");
                blockers.add("OKX_PUBLIC_INSTRUMENT_MIN_SIZE_MISSING");
                return;
            }
            BigDecimal minimumQuotePerGridOrderLowerBound = rules.minSize().multiply(priceLower);
            BigDecimal minimumTotalQuoteLowerBound = minimumQuotePerGridOrderLowerBound
                    .multiply(BigDecimal.valueOf(gridCount));
            BigDecimal quotePerGrid = totalQuoteUsdt.divide(BigDecimal.valueOf(gridCount), 8, RoundingMode.DOWN);
            putDecimal(evidence, "quotePerGrid", quotePerGrid);
            putDecimal(evidence, "minimumQuotePerGridOrderLowerBound", minimumQuotePerGridOrderLowerBound);
            putDecimal(evidence, "minimumTotalQuoteLowerBound", minimumTotalQuoteLowerBound);
            evidence.put("status", totalQuoteUsdt.compareTo(minimumTotalQuoteLowerBound) >= 0
                    ? "PUBLIC_RULE_LOWER_BOUND_PASSES_NOT_PROVIDER_CREATE_ACCEPTANCE"
                    : "PUBLIC_RULE_LOWER_BOUND_FAILS");
            if (totalQuoteUsdt.compareTo(minimumTotalQuoteLowerBound) < 0) {
                blockers.add("TOTAL_QUOTE_BELOW_OKX_PUBLIC_MIN_SIZE_LOWER_BOUND");
            }
            JsonNode minimumData = okxTradingService.getNativeSpotGridMinimumInvestment(
                    instrument, priceLower, priceUpper, gridCount);
            BigDecimal exactMinimum = findMinimumAmount(minimumData, "USDT");
            if (exactMinimum == null) {
                evidence.put("exactMinimumStatus", "MISSING");
                blockers.add("OKX_EXACT_QUOTE_MINIMUM_MISSING");
            } else {
                evidence.put("exactMinimumQuoteUsdt", exactMinimum);
                evidence.put("exactMinimumStatus", totalQuoteUsdt.compareTo(exactMinimum) >= 0
                        ? "PASSES" : "REQUEST_BELOW_MINIMUM");
                if (exactMinimum.compareTo(TINY_LIVE_QUOTE_CAP) > 0) {
                    blockers.add("OKX_MINIMUM_EXCEEDS_10_USDT_CAP");
                }
                if (totalQuoteUsdt.compareTo(exactMinimum) < 0) {
                    blockers.add("TOTAL_QUOTE_BELOW_OKX_EXACT_MINIMUM");
                }
            }
        } catch (RuntimeException error) {
            evidence.put("status", "PROVIDER_RULE_LOOKUP_FAILED");
            evidence.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            blockers.add("OKX_PUBLIC_INSTRUMENT_RULE_LOOKUP_FAILED");
        }
    }

    private void putDecimal(ObjectNode target, String field, BigDecimal value) {
        if (value == null) target.putNull(field);
        else target.put(field, value);
    }

    private void validateRequest(String instrument,
                                 BigDecimal priceLower,
                                 BigDecimal priceUpper,
                                 Integer gridCount,
                                 BigDecimal totalQuoteUsdt,
                                 ArrayNode blockers) {
        if (!"BTC-USDT".equals(instrument)) blockers.add("ONLY_BTC_USDT_IS_IN_SCOPE");
        if (priceLower == null || priceUpper == null || priceLower.signum() <= 0
                || priceUpper.signum() <= 0 || priceLower.compareTo(priceUpper) >= 0) {
            blockers.add("INVALID_PRICE_RANGE");
        }
        if (gridCount == null || gridCount < 2) blockers.add("GRID_COUNT_MUST_BE_AT_LEAST_2");
        if (totalQuoteUsdt == null || totalQuoteUsdt.signum() <= 0
                || totalQuoteUsdt.compareTo(TINY_LIVE_QUOTE_CAP) > 0) {
            blockers.add("TOTAL_QUOTE_MUST_BE_POSITIVE_AND_AT_MOST_10_USDT");
        }
    }

    private String normalizeInstrument(String symbol) {
        if (symbol == null) return "";
        String normalized = symbol.trim().toUpperCase(Locale.ROOT).replace("_", "-");
        return "BTCUSDT".equals(normalized) ? "BTC-USDT" : normalized;
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

    private BigDecimal findMinimumAmount(JsonNode minimumData, String currency) {
        if (minimumData == null || !minimumData.isArray() || minimumData.isEmpty()) return null;
        JsonNode rows = minimumData.path(0).path("minInvestmentData");
        if (!rows.isArray()) return null;
        for (JsonNode row : rows) {
            if (!currency.equalsIgnoreCase(row.path("ccy").asText())) continue;
            try {
                BigDecimal amount = new BigDecimal(row.path("amt").asText());
                return amount.signum() > 0 ? amount : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
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
