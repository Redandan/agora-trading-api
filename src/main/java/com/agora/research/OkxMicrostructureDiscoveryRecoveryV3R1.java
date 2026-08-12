package com.agora.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Frozen, side-effect-free WebSocket control-event policy for the V3R1 source.
 * This class does not open a socket, start Spring, or write canonical state.
 */
final class OkxMicrostructureDiscoveryRecoveryV3R1 {

    static final String CONTRACT_SHA256 =
            "6448b47a373dca743df6492593582660461382b639fdb77aa897ffa5a9f604bd";
    static final String INSTRUMENT = "BTC-USDT";
    static final Set<String> CHANNELS = Set.of("trades", "books5");
    static final String SERVICE_UPGRADE_REASON = "SERVICE_UPGRADE_NOTICE_64008";
    static final String ZERO_SHA256 = "0".repeat(64);

    private static final Pattern LOWER_SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern ASCII_DIGITS = Pattern.compile("^[0-9]+$");
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private OkxMicrostructureDiscoveryRecoveryV3R1() {
    }

    enum Action {
        ACKNOWLEDGE,
        SEAL_CONTROL_EVENT_AND_CONTINUE,
        REJECT_ACTIVE_DAY
    }

    record SanitizedControlEvent(String event, String code) {
    }

    record Classification(
            Action action,
            String channel,
            String rejectionReason,
            SanitizedControlEvent sanitizedControlEvent) {
    }

    static final class BlockedException extends IllegalArgumentException {
        private final String code;

        BlockedException(String code, String detail) {
            super(code + ": " + detail);
            this.code = code;
        }

        BlockedException(String code, String detail, Throwable cause) {
            super(code + ": " + detail, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    static Classification classify(byte[] rawEventBytes) {
        if (rawEventBytes == null || rawEventBytes.length == 0) {
            throw blocked("UNKNOWN_EVENT", "raw control-event bytes are unavailable");
        }
        final JsonNode root;
        try {
            root = STRICT_MAPPER.readValue(rawEventBytes, JsonNode.class);
        } catch (Exception error) {
            throw new BlockedException(
                    "UNKNOWN_EVENT", "control event is not strict JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw blocked("UNKNOWN_EVENT", "control event must be an object");
        }
        JsonNode eventNode = root.get("event");
        if (eventNode == null || !eventNode.isTextual() || eventNode.textValue().isBlank()) {
            throw blocked("UNKNOWN_EVENT", "control event type is missing");
        }
        String event = eventNode.textValue();
        if ("subscribe".equals(event)) {
            JsonNode argument = root.get("arg");
            if (argument == null || !argument.isObject()) {
                throw blocked("WRONG_IDENTITY", "subscription argument is missing");
            }
            String channel = exactText(argument, "channel", "WRONG_IDENTITY");
            String instrument = exactText(argument, "instId", "WRONG_IDENTITY");
            if (!CHANNELS.contains(channel) || !INSTRUMENT.equals(instrument)) {
                throw blocked("WRONG_IDENTITY", "subscription acknowledgement changed");
            }
            return new Classification(Action.ACKNOWLEDGE, channel, null, null);
        }
        if ("channel-conn-count".equals(event)) {
            String channel = exactText(root, "channel", "UNKNOWN_EVENT");
            String connectionCount = exactText(root, "connCount", "UNKNOWN_EVENT");
            if (!CHANNELS.contains(channel)
                    || !ASCII_DIGITS.matcher(connectionCount).matches()) {
                throw blocked("UNKNOWN_EVENT", "channel connection count is invalid");
            }
            return new Classification(
                    Action.SEAL_CONTROL_EVENT_AND_CONTINUE,
                    channel,
                    null,
                    new SanitizedControlEvent("channel-conn-count", null));
        }
        if ("notice".equals(event)) {
            JsonNode code = root.get("code");
            if (code != null && code.isTextual() && "64008".equals(code.textValue())) {
                return new Classification(
                        Action.REJECT_ACTIVE_DAY,
                        null,
                        SERVICE_UPGRADE_REASON,
                        new SanitizedControlEvent("notice", "64008"));
            }
            throw blocked("UNKNOWN_EVENT", "notice code is not allowlisted");
        }
        throw blocked("UNKNOWN_EVENT", "event " + event + " is not allowlisted");
    }

    static String nextControlEventChain(String previousSha256, byte[] rawEventBytes) {
        if (previousSha256 == null
                || !LOWER_SHA256.matcher(previousSha256).matches()) {
            throw blocked("CONTRACT_HASH_MISMATCH", "prior control-event chain is invalid");
        }
        if (rawEventBytes == null || rawEventBytes.length == 0) {
            throw blocked("UNKNOWN_EVENT", "raw control-event bytes are unavailable");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(previousSha256.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
            digest.update(rawEventBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static String nextRawArrivalChain(String previousSha256, byte[] rawMessageBytes) {
        return nextControlEventChain(previousSha256, rawMessageBytes);
    }

    private static String exactText(JsonNode object, String field, String code) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw blocked(code, field + " is missing");
        }
        return value.textValue();
    }

    private static BlockedException blocked(String code, String detail) {
        return new BlockedException(code, detail);
    }
}
