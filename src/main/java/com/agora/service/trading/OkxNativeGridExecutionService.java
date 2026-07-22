package com.agora.service.trading;

import com.agora.config.properties.OkxNativeGridProperties;
import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Protected, disabled-by-default lifecycle for one tiny OKX-native BTC-USDT Spot Grid bot. */
@Service
@RequiredArgsConstructor
public class OkxNativeGridExecutionService {

    private static final String INST_ID = "BTC-USDT";
    private static final BigDecimal QUOTE_CAP = new BigDecimal("10");
    private static final Set<String> UNSAFE_LEGACY_LEVEL_STATES = Set.of(
            "HOLDING", "SELL_FAILED", "SELL_PARTIAL", "PENDING_OKX", "SELLING_OKX");

    private final OkxTradingService okxTradingService;
    private final BtGridRepository gridRepository;
    private final BtGridLevelRepository gridLevelRepository;
    private final OkxNativeGridProperties properties;
    private final ObjectMapper objectMapper;

    public String previewOrCreate(String symbol,
                                  BigDecimal minPx,
                                  BigDecimal maxPx,
                                  Integer gridNum,
                                  BigDecimal quoteSz,
                                  String algoClOrdId,
                                  Boolean execute,
                                  String confirmText) {
        boolean executeRequested = Boolean.TRUE.equals(execute);
        ObjectNode root = base("OKX_NATIVE_SPOT_GRID_CREATE", executeRequested);
        ArrayNode blockersNode = root.putArray("blockers");
        List<String> blockers = new ArrayList<>();
        String instId = normalize(symbol);
        validateCreateInput(instId, minPx, maxPx, gridNum, quoteSz, algoClOrdId, blockers);

        JsonNode active = readActive(blockers);
        root.set("activeNativeBotsBefore", active.deepCopy());
        root.put("activeNativeBotCountBefore", active.size());
        JsonNode matching = findByClientId(active, algoClOrdId);
        boolean idempotentExisting = matching != null && exactCreateMatch(matching, minPx, maxPx, gridNum, quoteSz);
        if (!active.isEmpty() && !idempotentExisting) blockers.add("SINGLE_BOT_LIMIT_REQUIRES_ZERO_OTHER_ACTIVE_BOTS");
        if (matching != null && !idempotentExisting) blockers.add("ALGO_CLIENT_ID_EXISTS_WITH_DIFFERENT_CONFIGURATION");
        JsonNode history = readHistory(blockers);
        root.put("nativeHistoryCount", history.size());
        if (findByClientId(history, algoClOrdId) != null) {
            blockers.add("ALGO_CLIENT_ID_ALREADY_USED_IN_PROVIDER_HISTORY");
        }

        appendLegacyEvidence(root, blockers);
        appendProviderRules(root, instId, minPx, maxPx, gridNum, quoteSz, blockers);
        String requiredConfirmText = createConfirmText(instId, minPx, maxPx, gridNum, quoteSz, algoClOrdId);
        root.put("requiredConfirmText", requiredConfirmText);
        root.put("featureEnabled", properties.enabled());
        root.put("liveActionEnabled", properties.liveActionEnabled());
        boolean okxAutoTradeEnabled = okxTradingService.isAutoTradeEnabled();
        root.put("okxAutoTradeEnabled", okxAutoTradeEnabled);
        root.put("executionArmed", properties.executionArmed() && okxAutoTradeEnabled);
        root.put("idempotentExisting", idempotentExisting);
        new LinkedHashSet<>(blockers).forEach(blockersNode::add);

        if (idempotentExisting) {
            root.put("status", "ALREADY_ACTIVE_IDEMPOTENT_NO_CREATE");
            root.set("providerBot", matching.deepCopy());
            safety(root, false, false);
            return pretty(root);
        }

        List<String> executionBlockers = new ArrayList<>();
        if (!blockers.isEmpty()) executionBlockers.add("PRECHECK_BLOCKED");
        if (executeRequested && !properties.enabled()) executionBlockers.add("FEATURE_DISABLED");
        if (executeRequested && !properties.liveActionEnabled()) executionBlockers.add("LIVE_ACTION_DISABLED");
        if (executeRequested && !okxAutoTradeEnabled) executionBlockers.add("OKX_AUTO_TRADE_MASTER_DISABLED");
        if (executeRequested && !requiredConfirmText.equals(confirmText)) executionBlockers.add("CONFIRM_TEXT_MISMATCH");
        ArrayNode executionBlockersNode = root.putArray("executionBlockers");
        executionBlockers.forEach(executionBlockersNode::add);

        if (!executeRequested || !executionBlockers.isEmpty()) {
            root.put("status", blockers.isEmpty() && !executeRequested
                    ? "READY_FOR_SEPARATE_EXACT_CREATE_AUTHORIZATION"
                    : "CREATE_BLOCKED");
            safety(root, false, false);
            return pretty(root);
        }

        try {
            JsonNode result = okxTradingService.createNativeSpotGrid(
                    INST_ID, minPx, maxPx, gridNum, quoteSz, algoClOrdId);
            root.set("providerCreateResult", result.deepCopy());
            root.put("status", "CREATE_ACCEPTED_REQUIRES_READ_ONLY_RECONCILIATION");
            safety(root, true, false);
            return pretty(root);
        } catch (RuntimeException error) {
            JsonNode reconciled = readActive(new ArrayList<>());
            JsonNode recovered = findByClientId(reconciled, algoClOrdId);
            if (recovered != null && exactCreateMatch(recovered, minPx, maxPx, gridNum, quoteSz)) {
                root.put("status", "CREATE_RESPONSE_UNCERTAIN_BUT_ACTIVE_BOT_RECONCILED");
                root.put("providerError", safeMessage(error));
                root.set("providerBot", recovered.deepCopy());
                safety(root, true, false);
                return pretty(root);
            }
            throw error;
        }
    }

    public String previewOrStop(String algoId,
                                String disposition,
                                Boolean execute,
                                String confirmText) {
        boolean executeRequested = Boolean.TRUE.equals(execute);
        ObjectNode root = base("OKX_NATIVE_SPOT_GRID_STOP", executeRequested);
        List<String> blockers = new ArrayList<>();
        String normalizedDisposition = disposition == null ? "" : disposition.trim().toUpperCase(Locale.ROOT);
        if (algoId == null || !algoId.matches("[0-9]+")) blockers.add("ALGO_ID_DIGITS_REQUIRED");
        if (!Set.of("SELL_BASE", "KEEP_BASE").contains(normalizedDisposition)) {
            blockers.add("DISPOSITION_MUST_BE_SELL_BASE_OR_KEEP_BASE");
        }

        JsonNode active = readActive(blockers);
        JsonNode bot = findByAlgoId(active, algoId);
        JsonNode history = objectMapper.createArrayNode();
        if (bot == null && blockers.isEmpty()) {
            try {
                history = okxTradingService.getNativeSpotGridOrders(true);
            } catch (RuntimeException error) {
                blockers.add("OKX_NATIVE_HISTORY_LOOKUP_FAILED");
            }
            JsonNode stopped = findByAlgoId(history, algoId);
            if (stopped != null) {
                root.put("status", "ALREADY_STOPPED_IDEMPOTENT_NO_ACTION");
                root.set("providerBot", stopped.deepCopy());
                safety(root, false, false);
                return pretty(root);
            }
            blockers.add("ACTIVE_BOT_NOT_FOUND");
        }
        if (active.size() > 1) blockers.add("SINGLE_BOT_INVARIANT_VIOLATED");
        if (bot != null && !INST_ID.equals(bot.path("instId").asText())) blockers.add("BOT_INSTRUMENT_NOT_BTC_USDT");
        JsonNode detail = null;
        if (bot != null) {
            try {
                JsonNode detailData = okxTradingService.getNativeSpotGridOrderDetails(algoId);
                if (detailData != null && detailData.isArray() && !detailData.isEmpty()) {
                    detail = detailData.path(0);
                } else {
                    blockers.add("ACTIVE_BOT_DETAIL_MISSING");
                }
            } catch (RuntimeException error) {
                blockers.add("ACTIVE_BOT_DETAIL_LOOKUP_FAILED");
            }
        }
        String detailHash = detail == null ? "UNAVAILABLE" : sha256(detail.toString());
        String stopType = "SELL_BASE".equals(normalizedDisposition) ? "1" : "2";
        String requiredConfirmText = "AUTHORIZE_OKX_NATIVE_GRID_STOP|algoId=" + algoId
                + "|instId=" + INST_ID + "|disposition=" + normalizedDisposition
                + "|stopType=" + stopType + "|activeBotSha256=" + detailHash;

        root.set("activeNativeBotsBefore", active.deepCopy());
        if (bot != null) root.set("targetBot", bot.deepCopy());
        if (detail != null) root.set("targetBotDetail", detail.deepCopy());
        root.put("activeBotSha256", detailHash);
        root.put("stopType", stopType);
        root.put("disposition", normalizedDisposition);
        root.put("requiredConfirmText", requiredConfirmText);
        root.put("featureEnabled", properties.enabled());
        root.put("liveActionEnabled", properties.liveActionEnabled());
        boolean okxAutoTradeEnabled = okxTradingService.isAutoTradeEnabled();
        root.put("okxAutoTradeEnabled", okxAutoTradeEnabled);
        root.put("executionArmed", properties.executionArmed() && okxAutoTradeEnabled);
        ArrayNode blockersNode = root.putArray("blockers");
        new LinkedHashSet<>(blockers).forEach(blockersNode::add);

        List<String> executionBlockers = new ArrayList<>();
        if (!blockers.isEmpty()) executionBlockers.add("PRECHECK_BLOCKED");
        if (executeRequested && !properties.enabled()) executionBlockers.add("FEATURE_DISABLED");
        if (executeRequested && !properties.liveActionEnabled()) executionBlockers.add("LIVE_ACTION_DISABLED");
        if (executeRequested && !okxAutoTradeEnabled) executionBlockers.add("OKX_AUTO_TRADE_MASTER_DISABLED");
        if (executeRequested && !requiredConfirmText.equals(confirmText)) executionBlockers.add("CONFIRM_TEXT_MISMATCH");
        ArrayNode executionBlockersNode = root.putArray("executionBlockers");
        executionBlockers.forEach(executionBlockersNode::add);
        if (!executeRequested || !executionBlockers.isEmpty()) {
            root.put("status", blockers.isEmpty() && !executeRequested
                    ? "READY_FOR_SEPARATE_EXACT_STOP_AUTHORIZATION"
                    : "STOP_BLOCKED");
            safety(root, false, false);
            return pretty(root);
        }

        JsonNode result = okxTradingService.stopNativeSpotGrid(algoId, stopType);
        root.set("providerStopResult", result.deepCopy());
        root.put("status", "STOP_ACCEPTED_REQUIRES_READ_ONLY_TERMINAL_RECONCILIATION");
        safety(root, false, true);
        return pretty(root);
    }

    private ObjectNode base(String action, boolean executeRequested) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("action", action);
        root.put("boundary", "PROTECTED_PROVIDER_WRITE_DISABLED_BY_DEFAULT");
        root.put("instrument", INST_ID);
        root.put("spotOnly", true);
        root.put("leverage", "1x");
        root.put("singleBot", true);
        root.put("quoteCapUsdt", QUOTE_CAP);
        root.put("executeRequested", executeRequested);
        root.put("dbMutationAllowed", false);
        return root;
    }

    private void validateCreateInput(String instId,
                                     BigDecimal minPx,
                                     BigDecimal maxPx,
                                     Integer gridNum,
                                     BigDecimal quoteSz,
                                     String algoClOrdId,
                                     List<String> blockers) {
        if (!INST_ID.equals(instId)) blockers.add("ONLY_BTC_USDT_IS_ALLOWED");
        if (minPx == null || maxPx == null || minPx.signum() <= 0 || maxPx.signum() <= 0
                || minPx.compareTo(maxPx) >= 0) blockers.add("INVALID_PRICE_RANGE");
        if (gridNum == null || gridNum < 2 || gridNum > 100) blockers.add("GRID_NUM_MUST_BE_2_TO_100");
        if (quoteSz == null || quoteSz.signum() <= 0 || quoteSz.compareTo(QUOTE_CAP) > 0) {
            blockers.add("QUOTE_SIZE_MUST_BE_POSITIVE_AND_AT_MOST_10_USDT");
        }
        if (algoClOrdId == null || !algoClOrdId.matches("[A-Za-z0-9]{1,32}")) {
            blockers.add("ALGO_CLIENT_ID_MUST_BE_1_TO_32_ALPHANUMERIC");
        }
    }

    private void appendLegacyEvidence(ObjectNode root, List<String> blockers) {
        List<BtGrid> open = gridRepository.findBySymbolAndClosedAtIsNull("BTCUSDT");
        root.put("openLegacyGridCount", open.size());
        int unsafe = 0;
        for (BtGrid grid : open) {
            for (BtGridLevel level : gridLevelRepository.findByGridId(grid.getId())) {
                if (UNSAFE_LEGACY_LEVEL_STATES.contains(level.getStatus())) unsafe++;
            }
        }
        root.put("legacyInventoryOrInFlightLevelCount", unsafe);
        if (!open.isEmpty()) blockers.add("OPEN_LEGACY_GRIDS_MUST_BE_RETIRED_FIRST");
        if (unsafe > 0) blockers.add("LEGACY_INVENTORY_OR_IN_FLIGHT_MUST_BE_ZERO");
    }

    private void appendProviderRules(ObjectNode root,
                                     String instId,
                                     BigDecimal minPx,
                                     BigDecimal maxPx,
                                     Integer gridNum,
                                     BigDecimal quoteSz,
                                     List<String> blockers) {
        if (!INST_ID.equals(instId) || minPx == null || maxPx == null || gridNum == null || quoteSz == null) return;
        try {
            OkxTradingService.SpotInstrumentRules rules = okxTradingService.getSpotInstrumentRules(INST_ID);
            BigDecimal last = okxTradingService.getLastPrice(INST_ID);
            root.put("providerLastPrice", last);
            root.put("providerMinSizeBase", rules.minSize());
            root.put("providerLotSizeBase", rules.lotSize());
            root.put("providerTickSizeQuote", rules.tickSize());
            if (rules.tickSize() != null
                    && (minPx.remainder(rules.tickSize()).signum() != 0
                    || maxPx.remainder(rules.tickSize()).signum() != 0)) {
                blockers.add("PRICE_RANGE_NOT_ALIGNED_TO_OKX_TICK_SIZE");
            }
            if (last.compareTo(minPx) < 0 || last.compareTo(maxPx) > 0) blockers.add("LAST_PRICE_OUTSIDE_REQUESTED_RANGE");
            if (rules.minSize() != null) {
                BigDecimal lowerBound = rules.minSize().multiply(minPx).multiply(BigDecimal.valueOf(gridNum));
                root.put("publicMinimumQuoteLowerBound", lowerBound);
                if (quoteSz.compareTo(lowerBound) < 0) blockers.add("QUOTE_SIZE_BELOW_PUBLIC_MINIMUM_LOWER_BOUND");
            }
            root.put("providerCreateMinimumAcceptanceProven", false);
        } catch (RuntimeException error) {
            blockers.add("OKX_PROVIDER_RULE_LOOKUP_FAILED");
            root.put("providerRuleError", safeMessage(error));
        }
    }

    private JsonNode readActive(List<String> blockers) {
        try {
            JsonNode value = okxTradingService.getNativeSpotGridOrders(false);
            return value != null && value.isArray() ? value : objectMapper.createArrayNode();
        } catch (RuntimeException error) {
            blockers.add("OKX_NATIVE_ACTIVE_LOOKUP_FAILED");
            return objectMapper.createArrayNode();
        }
    }

    private JsonNode readHistory(List<String> blockers) {
        try {
            JsonNode value = okxTradingService.getNativeSpotGridOrders(true);
            return value != null && value.isArray() ? value : objectMapper.createArrayNode();
        } catch (RuntimeException error) {
            blockers.add("OKX_NATIVE_HISTORY_LOOKUP_FAILED");
            return objectMapper.createArrayNode();
        }
    }

    private JsonNode findByClientId(JsonNode bots, String algoClOrdId) {
        if (algoClOrdId == null || bots == null || !bots.isArray()) return null;
        for (JsonNode bot : bots) if (algoClOrdId.equals(bot.path("algoClOrdId").asText())) return bot;
        return null;
    }

    private JsonNode findByAlgoId(JsonNode bots, String algoId) {
        if (algoId == null || bots == null || !bots.isArray()) return null;
        for (JsonNode bot : bots) if (algoId.equals(bot.path("algoId").asText())) return bot;
        return null;
    }

    private boolean exactCreateMatch(JsonNode bot,
                                     BigDecimal minPx,
                                     BigDecimal maxPx,
                                     Integer gridNum,
                                     BigDecimal quoteSz) {
        return INST_ID.equals(bot.path("instId").asText())
                && decimalEquals(bot.path("minPx"), minPx)
                && decimalEquals(bot.path("maxPx"), maxPx)
                && Integer.toString(gridNum == null ? -1 : gridNum).equals(bot.path("gridNum").asText())
                && decimalEquals(bot.path("quoteSz"), quoteSz);
    }

    private boolean decimalEquals(JsonNode value, BigDecimal expected) {
        if (expected == null || value == null || value.asText().isBlank()) return false;
        try {
            return new BigDecimal(value.asText()).compareTo(expected) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String createConfirmText(String instId,
                                     BigDecimal minPx,
                                     BigDecimal maxPx,
                                     Integer gridNum,
                                     BigDecimal quoteSz,
                                     String algoClOrdId) {
        return "AUTHORIZE_OKX_NATIVE_GRID_CREATE|instId=" + instId
                + "|algoOrdType=grid|runType=1|minPx=" + plain(minPx)
                + "|maxPx=" + plain(maxPx) + "|gridNum=" + gridNum
                + "|quoteSz=" + plain(quoteSz) + "|algoClOrdId=" + algoClOrdId;
    }

    private String normalize(String symbol) {
        if (symbol == null) return "";
        String value = symbol.trim().toUpperCase(Locale.ROOT).replace("_", "-");
        return "BTCUSDT".equals(value) ? INST_ID : value;
    }

    private String plain(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void safety(ObjectNode root, boolean createAttempted, boolean stopAttempted) {
        root.put("providerCreateAttempted", createAttempted);
        root.put("providerStopAttempted", stopAttempted);
        root.put("databaseMutationAttempted", false);
        root.put("customGridMutationAttempted", false);
    }

    private String safeMessage(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String pretty(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize OKX-native Grid execution packet", error);
        }
    }
}
