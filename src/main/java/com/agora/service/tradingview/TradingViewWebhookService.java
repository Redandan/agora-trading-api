package com.agora.service.tradingview;

import com.agora.config.properties.TradingViewWebhookProperties;
import com.agora.dto.tradingview.TradingViewWebhookResponse;
import com.agora.service.meta.DecisionAuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradingViewWebhookService {

    private static final Set<String> EXECUTABLE_ACTIONS = Set.of("BUY", "SELL", "CLOSE");

    private final TradingViewWebhookProperties props;
    private final DecisionAuditWriter auditWriter;
    private final Map<String, Instant> seenIdempotencyKeys = new ConcurrentHashMap<>();

    public HandlingResult handle(JsonNode payload, String remoteAddress) {
        if (payload == null || payload.isNull() || !payload.isObject()) {
            return result(HttpStatus.BAD_REQUEST, "BAD_REQUEST", false, false, false,
                    false, AlertDetails.empty(),
                    List.of("PAYLOAD_NOT_JSON_OBJECT"), "TradingView webhook payload must be a JSON object.");
        }
        if (!props.enabled()) {
            return result(HttpStatus.FORBIDDEN, "DISABLED", false, false, false,
                    false, AlertDetails.empty(),
                    List.of("TRADINGVIEW_WEBHOOK_DISABLED"), "TradingView webhook is disabled.");
        }
        if (!hasText(props.secret())) {
            return result(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", false, false, false,
                    false, AlertDetails.empty(),
                    List.of("TRADINGVIEW_WEBHOOK_SECRET_MISSING"), "TradingView webhook secret is not configured.");
        }
        if (!secretMatches(firstText(payload, "secret", "webhookSecret", "webhook_secret"))) {
            return result(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", false, false, false,
                    false, AlertDetails.empty(),
                    List.of("TRADINGVIEW_WEBHOOK_SECRET_INVALID"), "TradingView webhook secret is invalid.");
        }

        String rawStrategyId = firstText(payload, "strategyId", "strategy_id", "strategyID");
        Long strategyId = parsePositiveLong(rawStrategyId);
        boolean strategyIdInvalid = hasText(rawStrategyId) && strategyId == null;
        String action = normalizeAction(firstText(payload, "action", "signal", "side", "orderAction", "order_action"));
        String chartSymbol = firstText(payload, "symbol", "ticker", "instrument");
        String symbol = normalizeSymbol(chartSymbol);
        String timeframe = normalizeTimeframe(firstText(payload, "timeframe", "interval", "tf"));
        String sourceExchange = normalizeExchange(
                firstText(payload, "exchange", "sourceExchange", "source_exchange"),
                chartSymbol);
        String barTime = firstText(payload, "barTime", "bar_time", "time", "timestamp");
        LocalDateTime barOpenTime = parseBarTime(barTime);
        BigDecimal requestedNotional = firstDecimal(payload, "notionalUsdt", "notional_usdt", "amountUsdt", "amount_usdt", "amount")
                .orElse(props.defaultNotionalUsdt());
        BigDecimal effectiveNotional = requestedNotional.min(props.maxNotionalUsdt());
        String orderLabel = firstText(payload, "orderLabel", "order_label", "label", "orderComment", "comment");
        String orderReason = normalizeOrderReason(
                firstText(payload, "orderReason", "order_reason", "entryReason", "entry_reason", "reason"),
                orderLabel);
        BigDecimal tradingViewQuantity = firstDecimal(payload,
                "tradingViewQty", "tradingviewQty", "orderQty", "order_qty", "qty", "quantity")
                .orElse(null);
        String idempotencyKey = idempotencyKey(payload, action, symbol, timeframe, barTime, orderReason, orderLabel);
        AlertDetails details = new AlertDetails(strategyId, action, symbol, timeframe,
                orderReason, orderLabel, tradingViewQuantity, idempotencyKey, requestedNotional, effectiveNotional);

        List<String> blockers = validate(details, strategyIdInvalid);
        Map<String, Object> context = context(payload, remoteAddress, details, chartSymbol, sourceExchange,
                barTime, blockers, false);

        if (!blockers.isEmpty()) {
            auditSignalAndSkip(strategyId, action, symbol, timeframe, barOpenTime, "TradingViewWebhookGate",
                    String.join(",", blockers), context);
            return result(HttpStatus.UNPROCESSABLE_ENTITY, "REJECTED", false, false, false,
                    false, details,
                    blockers, "TradingView alert rejected by webhook gates.");
        }

        evictExpiredKeys();
        Instant now = Instant.now();
        Instant previous = seenIdempotencyKeys.putIfAbsent(idempotencyKey, now);
        if (previous != null) {
            Map<String, Object> duplicateContext = context(payload, remoteAddress, details, chartSymbol, sourceExchange,
                    barTime, List.of("DUPLICATE_ALERT"), true);
            auditWriter.logEntrySkip(strategyId, symbol, timeframe, barOpenTime,
                    "TradingViewDuplicate", "Duplicate TradingView alert idempotency key", duplicateContext);
            return result(HttpStatus.OK, "DUPLICATE", true, false, false,
                    true, details,
                    List.of("DUPLICATE_ALERT"), "Duplicate TradingView alert ignored.");
        }

        auditWriter.logSignalEval(strategyId, symbol, timeframe, barOpenTime, action, context);

        if (!EXECUTABLE_ACTIONS.contains(action)) {
            return result(HttpStatus.OK, "ACCEPTED_SIGNAL_ONLY", true, false, false,
                    false, details,
                    List.of(), "TradingView signal accepted for audit only.");
        }

        if (props.dryRun()) {
            auditWriter.logEntrySkip(strategyId, symbol, timeframe, barOpenTime,
                    "TradingViewDryRun", "TradingView webhook dry-run; no order sent", context);
            return result(HttpStatus.OK, "ACCEPTED_DRY_RUN", true, true, false,
                    false, details,
                    List.of("TRADINGVIEW_WEBHOOK_DRY_RUN"), "TradingView alert accepted; dry-run prevented order.");
        }

        auditWriter.logEntrySkip(strategyId, symbol, timeframe, barOpenTime,
                "TradingViewExecutionNotWired",
                "TradingView live execution requires BtLiveSignal/OCO integration before enabling orders",
                context);
        return result(HttpStatus.CONFLICT, "LIVE_EXECUTION_NOT_WIRED", true, true, false,
                false, details,
                List.of("LIVE_EXECUTION_REQUIRES_LIVE_SIGNAL_OCO_INTEGRATION"),
                "TradingView alert accepted, but live execution is not wired in this release.");
    }

    public record HandlingResult(HttpStatus httpStatus, TradingViewWebhookResponse body) {
    }

    private void auditSignalAndSkip(Long strategyId, String action, String symbol, String timeframe, LocalDateTime barOpenTime,
                                    String blocker, String reason, Map<String, Object> context) {
        if (hasText(action) && hasText(symbol)) {
            auditWriter.logSignalEval(strategyId, symbol, timeframe, barOpenTime, action, context);
        }
        auditWriter.logEntrySkip(strategyId, symbol, timeframe, barOpenTime, blocker, reason, context);
    }

    private List<String> validate(AlertDetails details, boolean strategyIdInvalid) {
        List<String> blockers = new ArrayList<>();
        if (!hasText(details.action())) {
            blockers.add("ACTION_MISSING_OR_UNSUPPORTED");
        }
        if (!hasText(details.symbol())) {
            blockers.add("SYMBOL_MISSING");
        } else if (!allowedSymbols().contains(details.symbol())) {
            blockers.add("SYMBOL_NOT_ALLOWED");
        }
        if (details.requestedNotionalUsdt() == null || details.requestedNotionalUsdt().signum() <= 0) {
            blockers.add("NOTIONAL_NOT_POSITIVE");
        } else if (details.requestedNotionalUsdt().compareTo(props.maxNotionalUsdt()) > 0) {
            blockers.add("NOTIONAL_ABOVE_CAP");
        }
        if (strategyIdInvalid) {
            blockers.add("STRATEGY_ID_INVALID");
        }
        return blockers;
    }

    private Map<String, Object> context(JsonNode payload, String remoteAddress, AlertDetails details,
                                        String chartSymbol, String sourceExchange, String barTime,
                                        List<String> blockers, boolean duplicate) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "TRADINGVIEW");
        context.put("remoteAddress", safe(remoteAddress));
        context.put("strategyId", details.strategyId() == null ? "" : details.strategyId());
        context.put("strategy", safe(firstText(payload, "strategy", "strategyName", "strategy_name")));
        context.put("exchange", safe(sourceExchange));
        context.put("chartSymbol", safe(chartSymbol));
        context.put("action", safe(details.action()));
        context.put("symbol", safe(details.symbol()));
        context.put("timeframe", safe(details.timeframe()));
        context.put("barTime", safe(barTime));
        context.put("price", safe(firstText(payload, "price", "close")));
        context.put("orderId", safe(firstText(payload, "orderId", "order_id")));
        context.put("orderReason", safe(details.orderReason()));
        context.put("orderLabel", safe(details.orderLabel()));
        context.put("tradingViewQuantity", details.tradingViewQuantity());
        context.put("comment", safe(firstText(payload, "comment", "message")));
        context.put("idempotencyKey", details.idempotencyKey());
        context.put("requestedNotionalUsdt", details.requestedNotionalUsdt());
        context.put("effectiveNotionalUsdt", details.effectiveNotionalUsdt());
        context.put("dryRun", props.dryRun());
        context.put("orderSent", false);
        context.put("duplicate", duplicate);
        context.put("blockers", blockers.isEmpty() ? "" : String.join(",", blockers));
        return context;
    }

    private TradingViewWebhookResponse response(String status, boolean accepted, boolean wouldExecute,
                                                boolean orderSent, boolean duplicate, AlertDetails details,
                                                List<String> blockers, String reason) {
        return new TradingViewWebhookResponse(status, accepted, wouldExecute, orderSent, props.dryRun(), duplicate,
                details.action(), details.symbol(), details.timeframe(), details.strategyId(),
                details.orderReason(), details.orderLabel(), details.tradingViewQuantity(),
                details.idempotencyKey(), details.requestedNotionalUsdt(), details.effectiveNotionalUsdt(),
                blockers, reason);
    }

    private HandlingResult result(HttpStatus httpStatus, String status, boolean accepted, boolean wouldExecute,
                                  boolean orderSent, boolean duplicate, AlertDetails details,
                                  List<String> blockers, String reason) {
        return new HandlingResult(httpStatus, response(status, accepted, wouldExecute, orderSent, duplicate,
                details, blockers, reason));
    }

    private String firstText(JsonNode payload, String... names) {
        for (String name : names) {
            JsonNode node = payload.path(name);
            if (!node.isMissingNode() && !node.isNull()) {
                String value = node.isTextual() ? node.asText() : node.toString();
                if (hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private java.util.Optional<BigDecimal> firstDecimal(JsonNode payload, String... names) {
        for (String name : names) {
            JsonNode node = payload.path(name);
            if (node.isMissingNode() || node.isNull()) {
                continue;
            }
            try {
                if (node.isNumber()) {
                    return java.util.Optional.of(node.decimalValue());
                }
                if (node.isTextual() && hasText(node.asText())) {
                    return java.util.Optional.of(new BigDecimal(node.asText().trim()));
                }
            } catch (NumberFormatException ignored) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.empty();
    }

    private String normalizeAction(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (value.contains("BUY") || value.contains("LONG") || value.contains("ENTRY")) {
            return "BUY";
        }
        if (value.contains("SELL") || value.contains("SHORT")) {
            return "SELL";
        }
        if (value.contains("CLOSE") || value.contains("EXIT") || value.contains("FLAT")) {
            return "CLOSE";
        }
        if (value.contains("HOLD") || value.contains("WATCH")) {
            return "HOLD";
        }
        return null;
    }

    private String normalizeSymbol(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1);
        }
        value = value.replace("-", "").replace("/", "").replace("_", "");
        return hasText(value) ? value : null;
    }

    private String normalizeTimeframe(String raw) {
        if (!hasText(raw)) {
            return "N/A";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if ("D".equals(value)) {
            return "1d";
        }
        if (value.endsWith("D") && value.length() > 1) {
            return value.substring(0, value.length() - 1) + "d";
        }
        if (value.endsWith("H") && value.length() > 1) {
            return value.substring(0, value.length() - 1) + "h";
        }
        if (value.endsWith("W") && value.length() > 1) {
            return value.substring(0, value.length() - 1) + "w";
        }
        if (value.endsWith("M") && value.length() > 1) {
            return value.substring(0, value.length() - 1) + "m";
        }
        if (value.matches("\\d+")) {
            long minutes = Long.parseLong(value);
            if (minutes >= 1440 && minutes % 1440 == 0) {
                return (minutes / 1440) + "d";
            }
            if (minutes >= 60 && minutes % 60 == 0) {
                return (minutes / 60) + "h";
            }
            return minutes + "m";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private Set<String> allowedSymbols() {
        Set<String> symbols = ConcurrentHashMap.newKeySet();
        for (String token : props.allowedSymbols().split(",")) {
            String symbol = normalizeSymbol(token);
            if (hasText(symbol)) {
                symbols.add(symbol);
            }
        }
        return symbols;
    }

    private String idempotencyKey(JsonNode payload, String action, String symbol, String timeframe, String barTime,
                                  String orderReason, String orderLabel) {
        String provided = firstText(payload, "idempotencyKey", "idempotency_key", "alertId", "alert_id");
        if (hasText(provided)) {
            return provided.trim();
        }
        String raw = String.join("|",
                safe(firstText(payload, "strategy", "strategyName", "strategy_name")),
                safe(firstText(payload, "orderId", "order_id")),
                safe(symbol),
                safe(timeframe),
                safe(barTime),
                safe(action),
                safe(orderReason),
                safe(orderLabel),
                safe(firstText(payload, "price", "close")));
        return HexFormat.of().formatHex(sha256(raw)).substring(0, 32);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private LocalDateTime parseBarTime(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.matches("^\\d{10,}$")) {
                long epoch = Long.parseLong(value);
                Instant instant = value.length() > 10 ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
                return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            }
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
        } catch (DateTimeParseException | NumberFormatException ignored) {
            log.debug("[TradingViewWebhook] could not parse bar time: {}", value);
            return null;
        }
    }

    private void evictExpiredKeys() {
        Instant cutoff = Instant.now().minusSeconds(Math.max(1, props.idempotencyTtlHours()) * 3600L);
        seenIdempotencyKeys.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    private boolean secretMatches(String actual) {
        if (!hasText(actual)) {
            return false;
        }
        byte[] expectedBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private Long parsePositiveLong(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeExchange(String explicitExchange, String rawSymbol) {
        if (hasText(explicitExchange)) {
            return explicitExchange.trim().toUpperCase(Locale.ROOT);
        }
        if (!hasText(rawSymbol)) {
            return "";
        }
        String value = rawSymbol.trim();
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return "";
        }
        return value.substring(0, colon).trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOrderReason(String explicitReason, String orderLabel) {
        String combined = (safe(explicitReason) + " " + safe(orderLabel)).toUpperCase(Locale.ROOT);
        if (combined.contains("RELATIVE") || combined.contains("相对") || combined.contains("相對")) {
            return "TRADINGVIEW_RELATIVE_LOW";
        }
        if (combined.contains("POTENTIAL") || combined.contains("潜在") || combined.contains("潛在")) {
            return "TRADINGVIEW_POTENTIAL_LOW";
        }
        if (combined.contains("AI") || combined.contains("BUY_SIGNAL")) {
            return "TRADINGVIEW_AI_BUY_SIGNAL";
        }
        if (hasText(explicitReason)) {
            return explicitReason.trim().toUpperCase(Locale.ROOT);
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record AlertDetails(
            Long strategyId,
            String action,
            String symbol,
            String timeframe,
            String orderReason,
            String orderLabel,
            BigDecimal tradingViewQuantity,
            String idempotencyKey,
            BigDecimal requestedNotionalUsdt,
            BigDecimal effectiveNotionalUsdt) {
        private static AlertDetails empty() {
            return new AlertDetails(null, null, null, null, null, null, null, null, null, null);
        }
    }
}
