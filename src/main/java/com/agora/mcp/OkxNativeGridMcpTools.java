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
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only migration bridge for OKX-native Spot Grid bots. */
@Service
@RequiredArgsConstructor
public class OkxNativeGridMcpTools {

    private static final BigDecimal TINY_LIVE_QUOTE_CAP = new BigDecimal("10");
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
        appendProviderRuleEvidence(report, blockers, instrument, priceLower, gridCount, totalQuoteUsdt);

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

        // OKX validates instrument-specific minimum investment when a bot is created. This read-only
        // bridge deliberately does not submit a create request merely to discover that minimum.
        blockers.add("OKX_NATIVE_MINIMUM_INVESTMENT_NOT_YET_PROVIDER_PREFLIGHTED");
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

    private void appendProviderRuleEvidence(ObjectNode report,
                                            ArrayNode blockers,
                                            String instrument,
                                            BigDecimal priceLower,
                                            Integer gridCount,
                                            BigDecimal totalQuoteUsdt) {
        ObjectNode evidence = report.putObject("providerRuleEvidence");
        evidence.put("source", "OKX_PUBLIC_INSTRUMENT_AND_TICKER_READ_ONLY");
        if (!"BTC-USDT".equals(instrument) || priceLower == null || priceLower.signum() <= 0
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
}
