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
                    false, null, null, null, null, null, null,
                    List.of("PAYLOAD_NOT_JSON_OBJECT"), "TradingView webhook payload must be a JSON object.");
        }
        if (!props.enabled()) {
            return result(HttpStatus.FORBIDDEN, "DISABLED", false, false, false,
                    false, null, null, null, null, null, null,
                    List.of("TRADINGVIEW_WEBHOOK_DISABLED"), "TradingView webhook is disabled.");
        }
        if (!hasText(props.secret())) {
            return result(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", false, false, false,
                    false, null, null, null, null, null, null,
                    List.of("TRADINGVIEW_WEBHOOK_SECRET_MISSING"), "TradingView webhook secret is not configured.");
        }
        if (!secretMatches(firstText(payload, "secret", "webhookSecret", "webhook_secret"))) {
            return result(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", false, false, false,
                    false, null, null, null, null, null, null,
                    List.of("TRADINGVIEW_WEBHOOK_SECRET_INVALID"), "TradingView webhook secret is invalid.");
        }

        String action = normalizeAction(firstText(payload, "action", "signal", "side", "orderAction", "order_action"));
        String symbol = normalizeSymbol(firstText(payload, "symbol", "ticker", "instrument"));
        String timeframe = normalizeTimeframe(firstText(payload, "timeframe", "interval", "tf"));
        String barTime = firstText(payload, "barTime", "bar_time", "time", "timestamp");
        LocalDateTime barOpenTime = parseBarTime(barTime);
        BigDecimal requestedNotional = firstDecimal(payload, "notionalUsdt", "notional_usdt", "amountUsdt", "amount_usdt", "amount")
                .orElse(props.defaultNotionalUsdt());
        BigDecimal effectiveNotional = requestedNotional.min(props.maxNotionalUsdt());
        String idempotencyKey = idempotencyKey(payload, action, symbol, timeframe, barTime);

        List<String> blockers = validate(action, symbol, requestedNotional);
        Map<String, Object> context = context(payload, remoteAddress, action, symbol, timeframe, barTime,
                idempotencyKey, requestedNotional, effectiveNotional, blockers, false);

        if (!blockers.isEmpty()) {
            auditSignalAndSkip(action, symbol, timeframe, barOpenTime, "TradingViewWebhookGate",
                    String.join(",", blockers), context);
            return result(HttpStatus.UNPROCESSABLE_ENTITY, "REJECTED", false, false, false,
                    false, action, symbol, timeframe, idempotencyKey, requestedNotional, effectiveNotional,
                    blockers, "TradingView alert rejected by webhook gates.");
        }

        evictExpiredKeys();
        Instant now = Instant.now();
        Instant previous = seenIdempotencyKeys.putIfAbsent(idempotencyKey, now);
        if (previous != null) {
            Map<String, Object> duplicateContext = context(payload, remoteAddress, action, symbol, timeframe, barTime,
                    idempotencyKey, requestedNotional, effectiveNotional, List.of("DUPLICATE_ALERT"), true);
            auditWriter.logEntrySkip(null, symbol, timeframe, barOpenTime,
                    "TradingViewDuplicate", "Duplicate TradingView alert idempotency key", duplicateContext);
            return result(HttpStatus.OK, "DUPLICATE", true, false, false,
                    true, action, symbol, timeframe, idempotencyKey, requestedNotional, effectiveNotional,
                    List.of("DUPLICATE_ALERT"), "Duplicate TradingView alert ignored.");
        }

        auditWriter.logSignalEval(null, symbol, timeframe, barOpenTime, action, context);

        if (!EXECUTABLE_ACTIONS.contains(action)) {
            return result(HttpStatus.OK, "ACCEPTED_SIGNAL_ONLY", true, false, false,
                    false, action, symbol, timeframe, idempotencyKey, requestedNotional, effectiveNotional,
                    List.of(), "TradingView signal accepted for audit only.");
        }

        if (props.dryRun()) {
            auditWriter.logEntrySkip(null, symbol, timeframe, barOpenTime,
                    "TradingViewDryRun", "TradingView webhook dry-run; no order sent", context);
            return result(HttpStatus.OK, "ACCEPTED_DRY_RUN", true, true, false,
                    false, action, symbol, timeframe, idempotencyKey, requestedNotional, effectiveNotional,
                    List.of("TRADINGVIEW_WEBHOOK_DRY_RUN"), "TradingView alert accepted; dry-run prevented order.");
        }

        auditWriter.logEntrySkip(null, symbol, timeframe, barOpenTime,
                "TradingViewExecutionNotWired",
                "TradingView live execution requires BtLiveSignal/OCO integration before enabling orders",
                context);
        return result(HttpStatus.CONFLICT, "LIVE_EXECUTION_NOT_WIRED", true, true, false,
                false, action, symbol, timeframe, idempotencyKey, requestedNotional, effectiveNotional,
                List.of("LIVE_EXECUTION_REQUIRES_LIVE_SIGNAL_OCO_INTEGRATION"),
                "TradingView alert accepted, but live execution is not wired in this release.");
    }

    public record HandlingResult(HttpStatus httpStatus, TradingViewWebhookResponse body) {
    }

    private void auditSignalAndSkip(String action, String symbol, String timeframe, LocalDateTime barOpenTime,
                                    String blocker, String reason, Map<String, Object> context) {
        if (hasText(action) && hasText(symbol)) {
            auditWriter.logSignalEval(null, symbol, timeframe, barOpenTime, action, context);
        }
        auditWriter.logEntrySkip(null, symbol, timeframe, barOpenTime, blocker, reason, context);
    }

    private List<String> validate(String action, String symbol, BigDecimal requestedNotional) {
        List<String> blockers = new ArrayList<>();
        if (!hasText(action)) {
            blockers.add("ACTION_MISSING_OR_UNSUPPORTED");
        }
        if (!hasText(symbol)) {
            blockers.add("SYMBOL_MISSING");
        } else if (!allowedSymbols().contains(symbol)) {
            blockers.add("SYMBOL_NOT_ALLOWED");
        }
        if (requestedNotional == null || requestedNotional.signum() <= 0) {
            blockers.add("NOTIONAL_NOT_POSITIVE");
        } else if (requestedNotional.compareTo(props.maxNotionalUsdt()) > 0) {
            blockers.add("NOTIONAL_ABOVE_CAP");
        }
        return blockers;
    }

    private Map<String, Object> context(JsonNode payload, String remoteAddress, String action, String symbol,
                                        String timeframe, String barTime, String idempotencyKey,
                                        BigDecimal requestedNotional, BigDecimal effectiveNotional,
                                        List<String> blockers, boolean duplicate) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "TRADINGVIEW");
        context.put("remoteAddress", safe(remoteAddress));
        context.put("strategy", safe(firstText(payload, "strategy", "strategyName", "strategy_name")));
        context.put("exchange", safe(firstText(payload, "exchange")));
        context.put("action", safe(action));
        context.put("symbol", safe(symbol));
        context.put("timeframe", safe(timeframe));
        context.put("barTime", safe(barTime));
        context.put("price", safe(firstText(payload, "price", "close")));
        context.put("orderId", safe(firstText(payload, "orderId", "order_id")));
        context.put("comment", safe(firstText(payload, "comment", "message")));
        context.put("idempotencyKey", idempotencyKey);
        context.put("requestedNotionalUsdt", requestedNotional);
        context.put("effectiveNotionalUsdt", effectiveNotional);
        context.put("dryRun", props.dryRun());
        context.put("orderSent", false);
        context.put("duplicate", duplicate);
        context.put("blockers", blockers.isEmpty() ? "" : String.join(",", blockers));
        return context;
    }

    private TradingViewWebhookResponse response(String status, boolean accepted, boolean wouldExecute,
                                                boolean orderSent, boolean duplicate, String action, String symbol,
                                                String timeframe, String idempotencyKey,
                                                BigDecimal requestedNotional, BigDecimal effectiveNotional,
                                                List<String> blockers, String reason) {
        return new TradingViewWebhookResponse(status, accepted, wouldExecute, orderSent, props.dryRun(), duplicate,
                action, symbol, timeframe, idempotencyKey, requestedNotional, effectiveNotional, blockers, reason);
    }

    private HandlingResult result(HttpStatus httpStatus, String status, boolean accepted, boolean wouldExecute,
                                  boolean orderSent, boolean duplicate, String action, String symbol,
                                  String timeframe, String idempotencyKey, BigDecimal requestedNotional,
                                  BigDecimal effectiveNotional, List<String> blockers, String reason) {
        return new HandlingResult(httpStatus, response(status, accepted, wouldExecute, orderSent, duplicate,
                action, symbol, timeframe, idempotencyKey, requestedNotional, effectiveNotional, blockers, reason));
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
        return raw.trim().toUpperCase(Locale.ROOT);
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

    private String idempotencyKey(JsonNode payload, String action, String symbol, String timeframe, String barTime) {
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
