package com.agora.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Disabled, fixed-origin Java 21 core for one lagged expiry-futures raw-atom source.
 * It has no runtime entrypoint and returns canonical documents only in memory.
 */
public final class OkxDraCryptoCarryForwardSource {

    static final String AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE";
    static final String STATUS = "OFFLINE_DISABLED_NOT_REGISTERED";
    static final String SOURCE_LABEL =
            "LAGGED_OKX_BTC_USDT_EXPIRY_FUTURES_BASIS_ATOMS_V1";
    static final String SOURCE_CONTRACT_SHA256 =
            "0944ab401717360f6eccc31ab967461af7ebc8122f7b77ee7b1f46eaa8fac48e";
    static final String INVENTORY_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_EXPIRY_FUTURES_INVENTORY_V1";
    static final String INVENTORY_SCHEMA_SHA256 =
            "8dd38f2ea2e73f236f56416aa1db86f6f82818ed7d8a9f69738b194c1965b340";
    static final String DAY_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_BASIS_ATOMS_DAY_V1";
    static final String DAY_SCHEMA_SHA256 =
            "1028d4b6f53cae6ad096038142173b650726fe744d358c2c16c7c57bc32dd8d8";
    static final String ENVELOPE_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_PRODUCER_ENVELOPE_V1";
    static final String ENVELOPE_SCHEMA_SHA256 =
            "e39263edd8362e91722134aefd11ee04f76def8b4d409aa01f396a2a481ea6d5";
    static final String PRODUCER_ID = "OKX_DRA_CRYPTO_CARRY_JAVA_PRODUCER_CORE_V1";
    static final String INVENTORY_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding inventory_seal";
    static final String DAY_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding day_seal";
    static final String ENVELOPE_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding envelope_seal";
    static final String TRANSPORT_STATUS =
            "NOT_IMPLEMENTED_CREATE_ONLY_HASH_BOUND_ONE_WAY_DROP";

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_RESPONSE_BYTES = 1_048_576;
    static final String ACCEPT = "application/json";
    static final String USER_AGENT = "agora-research-dra-crypto-carry-producer-core/1";
    static final URI INVENTORY_URI = URI.create(
            "https://www.okx.com/api/v5/public/instruments"
                    + "?instType=FUTURES&instFamily=BTC-USDT");
    static final URI INDEX_URI = URI.create(
            "https://www.okx.com/api/v5/market/index-candles"
                    + "?instId=BTC-USDT&bar=1Dutc");

    private static final Pattern EXPIRY_INST_ID = Pattern.compile("BTC-USDT-[0-9]{6}");
    private static final Pattern MILLIS = Pattern.compile("(?:0|[1-9][0-9]*)");
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile(
            "(?:[1-9][0-9]*(?:\\.[0-9]+)?|0\\.[0-9]*[1-9][0-9]*)");
    private static final Pattern NONNEGATIVE_DECIMAL = Pattern.compile(
            "(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");

    private static final Set<String> PROVIDER_ENVELOPE_KEYS =
            Set.of("code", "msg", "data");
    private static final Set<String> INVENTORY_DOCUMENT_KEYS = Set.of(
            "schema_version", "document_type", "authorization", "source_label",
            "source_contract_sha256", "target_day", "scheduled_cycle_at",
            "captured_at", "request", "inventory_count", "instruments",
            "inventory_seal");
    private static final Set<String> INVENTORY_PAYLOAD_KEYS = Set.of(
            "schema_version", "document_type", "authorization", "source_label",
            "source_contract_sha256", "target_day", "scheduled_cycle_at",
            "captured_at", "request", "inventory_count", "instruments");
    private static final Set<String> INSTRUMENT_KEYS = Set.of(
            "instId", "instType", "instFamily", "uly", "ctType", "settleCcy",
            "state", "ruleType", "listTime", "expTime");
    private static final Set<String> DAY_DOCUMENT_KEYS = Set.of(
            "schema_version", "document_type", "authorization", "source_label",
            "source_contract_sha256", "inventory_schema_sha256", "day_schema_sha256",
            "inventory_sha256", "target_day", "scheduled_cycle_at", "captured_at",
            "first_eligible_utc_decision_day", "requests", "expected_instrument_count",
            "observed_instrument_count", "cache_order_semantics", "futures", "index",
            "eligibility", "day_seal");
    private static final Set<String> ATOM_KEYS = Set.of("instId", "row");
    private static final Set<String> SEAL_KEYS =
            Set.of("algorithm", "payload_sha256", "canonicalization", "sealed_at");
    private static final Set<String> ENVELOPE_KEYS = Set.of(
            "schema_version", "envelope_type", "authorization", "status", "source_label",
            "source_contract_sha256", "producer_envelope_schema_sha256", "producer_id",
            "target_day", "generated_at", "inventory", "day",
            "first_eligible_utc_decision_day", "transport_status", "envelope_seal");

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    private final Transport transport;

    OkxDraCryptoCarryForwardSource(Transport transport) {
        this.transport = requireNonNull(transport, "transport");
    }

    byte[] captureInventory(LocalDate targetDay, Instant capturedAt) {
        requireNonNull(targetDay, "targetDay");
        requireSecondPrecision(capturedAt, "inventory capturedAt");
        Instant targetStart = targetDay.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant scheduled = targetDay.minusDays(1)
                .atTime(LocalTime.of(1, 5))
                .toInstant(ZoneOffset.UTC);
        if (capturedAt.isBefore(scheduled) || !capturedAt.isBefore(targetStart)) {
            throw failure("INVENTORY_CLOCK_DRIFT");
        }

        JsonNode data = executeData(inventoryRequest());
        List<Map<String, Object>> instruments = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Instant targetEnd = targetStart.plus(Duration.ofDays(1));
        for (JsonNode raw : data) {
            if (!raw.isObject()) {
                throw failure("INVENTORY_ROW_NOT_OBJECT");
            }
            Map<String, String> fields = requiredInstrumentFields(raw);
            if (!isEligibleInstrument(fields)) {
                continue;
            }
            long listTime = parseMillis(fields.get("listTime"), "LIST_TIME_INVALID");
            long expTime = parseMillis(fields.get("expTime"), "EXP_TIME_INVALID");
            if (listTime > capturedAt.toEpochMilli()) {
                throw failure("LIST_TIME_AFTER_CAPTURE");
            }
            if (expTime <= targetEnd.toEpochMilli()) {
                throw failure("EXPIRY_NOT_AFTER_TARGET_DAY");
            }
            String instId = fields.get("instId");
            if (!seen.add(instId)) {
                throw failure("DUPLICATE_OR_CONFLICTING_INST_ID");
            }
            Map<String, Object> instrument = new TreeMap<>();
            instrument.putAll(fields);
            instruments.add(instrument);
        }
        if (instruments.isEmpty()) {
            throw failure("EMPTY_ELIGIBLE_INVENTORY");
        }
        instruments.sort(Comparator.comparing(value -> (String) value.get("instId")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", INVENTORY_SCHEMA_VERSION);
        payload.put("document_type", "PRIOR_CYCLE_EXPIRY_FUTURES_INVENTORY");
        payload.put("authorization", AUTHORIZATION);
        payload.put("source_label", SOURCE_LABEL);
        payload.put("source_contract_sha256", SOURCE_CONTRACT_SHA256);
        payload.put("target_day", targetDay.toString());
        payload.put("scheduled_cycle_at", scheduled.toString());
        payload.put("captured_at", capturedAt.toString());
        payload.put("request", inventoryRequestMap());
        payload.put("inventory_count", instruments.size());
        payload.put("instruments", instruments);
        byte[] document = seal(
                payload, "inventory_seal", INVENTORY_CANONICALIZATION, capturedAt);
        validateInventory(document);
        return document;
    }

    byte[] captureDay(byte[] inventoryBytes, Instant capturedAt) {
        InventoryDocument inventory = validateInventory(inventoryBytes);
        requireSecondPrecision(capturedAt, "day capturedAt");
        Instant targetStart = inventory.targetDay()
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        Instant scheduled = inventory.targetDay().plusDays(1)
                .atTime(LocalTime.of(1, 5))
                .toInstant(ZoneOffset.UTC);
        Instant deadline = inventory.targetDay().plusDays(1)
                .atTime(LocalTime.of(6, 0))
                .toInstant(ZoneOffset.UTC);
        if (capturedAt.isBefore(scheduled) || !capturedAt.isBefore(deadline)) {
            throw failure("DAY_CAPTURE_CLOCK_DRIFT");
        }

        String expectedTimestamp = Long.toString(targetStart.toEpochMilli());
        List<Map<String, Object>> futures = new ArrayList<>();
        for (String instId : inventory.instIds()) {
            JsonNode data = executeData(candleRequest(instId));
            List<String> row = requireOneRawRow(data, 9, expectedTimestamp, true, instId);
            Map<String, Object> atom = new LinkedHashMap<>();
            atom.put("instId", instId);
            atom.put("row", row);
            futures.add(atom);
        }
        futures.sort(Comparator.comparing(value -> (String) value.get("instId")));
        JsonNode indexData = executeData(indexRequest());
        List<String> indexRow = requireOneRawRow(
                indexData, 6, expectedTimestamp, false, "BTC-USDT");

        LocalDate eligibleDay = inventory.targetDay().plusDays(2);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", DAY_SCHEMA_VERSION);
        payload.put("document_type", "COMPLETE_CONFIRMED_TARGET_DAY_RAW_ATOMS");
        payload.put("authorization", AUTHORIZATION);
        payload.put("source_label", SOURCE_LABEL);
        payload.put("source_contract_sha256", SOURCE_CONTRACT_SHA256);
        payload.put("inventory_schema_sha256", INVENTORY_SCHEMA_SHA256);
        payload.put("day_schema_sha256", DAY_SCHEMA_SHA256);
        payload.put("inventory_sha256", sha256(inventoryBytes));
        payload.put("target_day", inventory.targetDay().toString());
        payload.put("scheduled_cycle_at", scheduled.toString());
        payload.put("captured_at", capturedAt.toString());
        payload.put("first_eligible_utc_decision_day", eligibleDay.toString());
        payload.put("requests", dayRequestMap());
        payload.put("expected_instrument_count", inventory.instIds().size());
        payload.put("observed_instrument_count", futures.size());
        payload.put(
                "cache_order_semantics",
                "VALIDATE_COMPLETE_SET_THEN_SORT_BY_FROZEN_INST_ID");
        payload.put("futures", futures);
        payload.put("index", Map.of("instId", "BTC-USDT", "row", indexRow));
        payload.put("eligibility", eligibilityMap(eligibleDay));
        byte[] document = seal(payload, "day_seal", DAY_CANONICALIZATION, capturedAt);
        validateDay(document, inventory, inventoryBytes);
        return document;
    }

    byte[] createEnvelope(byte[] inventoryBytes, byte[] dayBytes, Instant generatedAt) {
        InventoryDocument inventory = validateInventory(inventoryBytes);
        DayDocument day = validateDay(dayBytes, inventory, inventoryBytes);
        requireSecondPrecision(generatedAt, "envelope generatedAt");
        if (generatedAt.isBefore(day.capturedAt())) {
            throw failure("ENVELOPE_BEFORE_DAY_CAPTURE");
        }
        LocalDate eligibleDay = inventory.targetDay().plusDays(2);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", ENVELOPE_SCHEMA_VERSION);
        payload.put("envelope_type", "OFFLINE_JAVA_PRODUCER_CORE_OUTPUT");
        payload.put("authorization", AUTHORIZATION);
        payload.put("status", STATUS);
        payload.put("source_label", SOURCE_LABEL);
        payload.put("source_contract_sha256", SOURCE_CONTRACT_SHA256);
        payload.put("producer_envelope_schema_sha256", ENVELOPE_SCHEMA_SHA256);
        payload.put("producer_id", PRODUCER_ID);
        payload.put("target_day", inventory.targetDay().toString());
        payload.put("generated_at", generatedAt.toString());
        payload.put("inventory", artifactMap(inventoryBytes));
        payload.put("day", artifactMap(dayBytes));
        payload.put("first_eligible_utc_decision_day", eligibleDay.toString());
        payload.put("transport_status", TRANSPORT_STATUS);
        byte[] envelope = seal(
                payload, "envelope_seal", ENVELOPE_CANONICALIZATION, generatedAt);
        validateEnvelope(envelope, inventoryBytes, dayBytes);
        return envelope;
    }

    CaptureArtifacts capture(
            LocalDate targetDay,
            Instant inventoryCapturedAt,
            Instant dayCapturedAt,
            Instant envelopeGeneratedAt) {
        byte[] inventory = captureInventory(targetDay, inventoryCapturedAt);
        byte[] day = captureDay(inventory, dayCapturedAt);
        byte[] envelope = createEnvelope(inventory, day, envelopeGeneratedAt);
        return new CaptureArtifacts(inventory, day, envelope);
    }

    private JsonNode executeData(RequestSpec request) {
        validateFixedRequest(request);
        final ResponseSpec response;
        try {
            response = transport.execute(request);
        } catch (Exception error) {
            throw new IllegalStateException("SOURCE_REQUEST_FAILED", error);
        }
        if (response.statusCode() != 200) {
            throw failure("HTTP_STATUS_REJECT");
        }
        String mediaType = response.contentType()
                .split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!"application/json".equals(mediaType)) {
            throw failure("CONTENT_TYPE_REJECT");
        }
        byte[] body = response.body();
        if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
            throw failure("RESPONSE_SIZE_REJECT");
        }
        JsonNode root = parseStrictJson(body, "provider response");
        requireObjectKeys(root, PROVIDER_ENVELOPE_KEYS, "provider response");
        if (!root.get("code").isTextual() || !"0".equals(root.get("code").textValue())) {
            throw failure("OKX_CODE_REJECT");
        }
        if (!root.get("msg").isTextual()) {
            throw failure("OKX_MESSAGE_SHAPE_REJECT");
        }
        JsonNode data = root.get("data");
        if (!data.isArray()) {
            throw failure("OKX_DATA_SHAPE_REJECT");
        }
        return data;
    }

    private static InventoryDocument validateInventory(byte[] rawBytes) {
        JsonNode root = parseCanonicalJson(rawBytes, "inventory document");
        requireObjectKeys(root, INVENTORY_DOCUMENT_KEYS, "inventory document");
        requireText(root, "schema_version", INVENTORY_SCHEMA_VERSION);
        requireText(root, "document_type", "PRIOR_CYCLE_EXPIRY_FUTURES_INVENTORY");
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        if (!generic(root.get("request")).equals(inventoryRequestMap())) {
            throw failure("INVENTORY_REQUEST_DRIFT");
        }
        LocalDate targetDay = requireDate(root, "target_day");
        Instant scheduled = requireInstant(root, "scheduled_cycle_at");
        Instant captured = requireInstant(root, "captured_at");
        Instant expectedSchedule = targetDay.minusDays(1)
                .atTime(LocalTime.of(1, 5))
                .toInstant(ZoneOffset.UTC);
        Instant targetStart = targetDay.atStartOfDay().toInstant(ZoneOffset.UTC);
        if (!scheduled.equals(expectedSchedule)
                || captured.isBefore(scheduled)
                || !captured.isBefore(targetStart)) {
            throw failure("INVENTORY_CLOCK_DRIFT");
        }
        JsonNode instruments = root.get("instruments");
        if (!instruments.isArray() || instruments.isEmpty()) {
            throw failure("EMPTY_ELIGIBLE_INVENTORY");
        }
        JsonNode count = root.get("inventory_count");
        if (!count.isIntegralNumber() || !count.canConvertToInt()
                || count.intValue() != instruments.size()) {
            throw failure("INVENTORY_COUNT_MISMATCH");
        }
        List<String> instIds = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        Instant targetEnd = targetStart.plus(Duration.ofDays(1));
        for (JsonNode instrument : instruments) {
            requireObjectKeys(instrument, INSTRUMENT_KEYS, "inventory instrument");
            Map<String, String> fields = requiredInstrumentFields(instrument);
            if (!isEligibleInstrument(fields)) {
                throw failure("INVENTORY_METADATA_DRIFT");
            }
            if (parseMillis(fields.get("listTime"), "LIST_TIME_INVALID")
                    > captured.toEpochMilli()) {
                throw failure("LIST_TIME_AFTER_CAPTURE");
            }
            if (parseMillis(fields.get("expTime"), "EXP_TIME_INVALID")
                    <= targetEnd.toEpochMilli()) {
                throw failure("EXPIRY_NOT_AFTER_TARGET_DAY");
            }
            String instId = fields.get("instId");
            if (!unique.add(instId)) {
                throw failure("DUPLICATE_OR_CONFLICTING_INST_ID");
            }
            instIds.add(instId);
        }
        List<String> sorted = instIds.stream().sorted().toList();
        if (!instIds.equals(sorted)) {
            throw failure("INVENTORY_NOT_SORTED");
        }
        verifySeal(root, "inventory_seal", INVENTORY_CANONICALIZATION, captured);
        return new InventoryDocument(targetDay, List.copyOf(instIds), captured);
    }

    private static DayDocument validateDay(
            byte[] dayBytes, InventoryDocument inventory, byte[] inventoryBytes) {
        JsonNode root = parseCanonicalJson(dayBytes, "day document");
        requireObjectKeys(root, DAY_DOCUMENT_KEYS, "day document");
        requireText(root, "schema_version", DAY_SCHEMA_VERSION);
        requireText(root, "document_type", "COMPLETE_CONFIRMED_TARGET_DAY_RAW_ATOMS");
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        requireText(root, "inventory_schema_sha256", INVENTORY_SCHEMA_SHA256);
        requireText(root, "day_schema_sha256", DAY_SCHEMA_SHA256);
        requireText(root, "inventory_sha256", sha256(inventoryBytes));
        requireText(
                root,
                "cache_order_semantics",
                "VALIDATE_COMPLETE_SET_THEN_SORT_BY_FROZEN_INST_ID");
        if (!generic(root.get("requests")).equals(dayRequestMap())) {
            throw failure("DAY_REQUEST_DRIFT");
        }
        LocalDate targetDay = requireDate(root, "target_day");
        if (!targetDay.equals(inventory.targetDay())) {
            throw failure("TARGET_DAY_DRIFT");
        }
        Instant scheduled = requireInstant(root, "scheduled_cycle_at");
        Instant captured = requireInstant(root, "captured_at");
        Instant expectedSchedule = targetDay.plusDays(1)
                .atTime(LocalTime.of(1, 5))
                .toInstant(ZoneOffset.UTC);
        Instant deadline = targetDay.plusDays(1)
                .atTime(LocalTime.of(6, 0))
                .toInstant(ZoneOffset.UTC);
        if (!scheduled.equals(expectedSchedule)
                || captured.isBefore(scheduled)
                || !captured.isBefore(deadline)) {
            throw failure("DAY_CAPTURE_CLOCK_DRIFT");
        }
        LocalDate eligibleDay = targetDay.plusDays(2);
        requireText(root, "first_eligible_utc_decision_day", eligibleDay.toString());
        if (!generic(root.get("eligibility")).equals(eligibilityMap(eligibleDay))) {
            throw failure("ELIGIBILITY_DRIFT");
        }
        int expectedCount = requirePositiveInt(root, "expected_instrument_count");
        int observedCount = requirePositiveInt(root, "observed_instrument_count");
        JsonNode futures = root.get("futures");
        if (!futures.isArray()
                || expectedCount != inventory.instIds().size()
                || observedCount != futures.size()) {
            throw failure("INVENTORY_COVERAGE_MISMATCH");
        }
        String expectedTimestamp = Long.toString(
                targetDay.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
        List<String> observedIds = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode atom : futures) {
            requireObjectKeys(atom, ATOM_KEYS, "futures atom");
            String instId = requiredText(atom, "instId");
            if (!unique.add(instId)) {
                throw failure("DUPLICATE_FUTURES_ROW");
            }
            requireRawRow(atom.get("row"), 9, expectedTimestamp, true, instId);
            observedIds.add(instId);
        }
        if (!observedIds.equals(inventory.instIds())) {
            throw failure("INVENTORY_COVERAGE_MISMATCH");
        }
        JsonNode index = root.get("index");
        requireObjectKeys(index, ATOM_KEYS, "index atom");
        requireText(index, "instId", "BTC-USDT");
        requireRawRow(index.get("row"), 6, expectedTimestamp, false, "BTC-USDT");
        verifySeal(root, "day_seal", DAY_CANONICALIZATION, captured);
        return new DayDocument(targetDay, captured, eligibleDay);
    }

    private static void validateEnvelope(
            byte[] envelopeBytes, byte[] inventoryBytes, byte[] dayBytes) {
        InventoryDocument inventory = validateInventory(inventoryBytes);
        DayDocument day = validateDay(dayBytes, inventory, inventoryBytes);
        JsonNode root = parseCanonicalJson(envelopeBytes, "producer envelope");
        requireObjectKeys(root, ENVELOPE_KEYS, "producer envelope");
        requireText(root, "schema_version", ENVELOPE_SCHEMA_VERSION);
        requireText(root, "envelope_type", "OFFLINE_JAVA_PRODUCER_CORE_OUTPUT");
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "status", STATUS);
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        requireText(root, "producer_envelope_schema_sha256", ENVELOPE_SCHEMA_SHA256);
        requireText(root, "producer_id", PRODUCER_ID);
        requireText(root, "target_day", inventory.targetDay().toString());
        requireText(
                root,
                "first_eligible_utc_decision_day",
                inventory.targetDay().plusDays(2).toString());
        requireText(root, "transport_status", TRANSPORT_STATUS);
        Instant generatedAt = requireInstant(root, "generated_at");
        if (generatedAt.isBefore(day.capturedAt())) {
            throw failure("ENVELOPE_BEFORE_DAY_CAPTURE");
        }
        if (!generic(root.get("inventory")).equals(artifactMap(inventoryBytes))
                || !generic(root.get("day")).equals(artifactMap(dayBytes))) {
            throw failure("ENVELOPE_ARTIFACT_HASH_DRIFT");
        }
        verifySeal(root, "envelope_seal", ENVELOPE_CANONICALIZATION, generatedAt);
    }

    private static RequestSpec inventoryRequest() {
        return new RequestSpec(
                INVENTORY_URI,
                "GET",
                REQUEST_TIMEOUT,
                Map.of("Accept", ACCEPT, "User-Agent", USER_AGENT));
    }

    private static RequestSpec candleRequest(String instId) {
        if (instId == null || !EXPIRY_INST_ID.matcher(instId).matches()) {
            throw failure("CANDLE_INST_ID_REJECT");
        }
        return new RequestSpec(
                URI.create("https://www.okx.com/api/v5/market/candles?instId="
                        + instId + "&bar=1Dutc"),
                "GET",
                REQUEST_TIMEOUT,
                Map.of("Accept", ACCEPT, "User-Agent", USER_AGENT));
    }

    private static RequestSpec indexRequest() {
        return new RequestSpec(
                INDEX_URI,
                "GET",
                REQUEST_TIMEOUT,
                Map.of("Accept", ACCEPT, "User-Agent", USER_AGENT));
    }

    private static void validateFixedRequest(RequestSpec request) {
        if (!"GET".equals(request.method())
                || !REQUEST_TIMEOUT.equals(request.timeout())
                || !request.headers().equals(
                        Map.of("Accept", ACCEPT, "User-Agent", USER_AGENT))) {
            throw failure("REQUEST_CONTRACT_DRIFT");
        }
        String value = request.uri().toString();
        boolean allowed = value.equals(INVENTORY_URI.toString())
                || value.equals(INDEX_URI.toString())
                || value.matches(
                        "https://www\\.okx\\.com/api/v5/market/candles"
                                + "\\?instId=BTC-USDT-[0-9]{6}&bar=1Dutc");
        if (!allowed
                || request.uri().getUserInfo() != null
                || request.uri().getFragment() != null
                || request.uri().getPort() != -1) {
            throw failure("REQUEST_URI_REJECT");
        }
    }

    private static Map<String, Object> inventoryRequestMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("method", "GET");
        value.put("origin", "https://www.okx.com");
        value.put("path", "/api/v5/public/instruments");
        value.put("query", Map.of("instType", "FUTURES", "instFamily", "BTC-USDT"));
        value.put("credentials", "NONE");
        return value;
    }

    private static Map<String, Object> dayRequestMap() {
        Map<String, Object> futures = new LinkedHashMap<>();
        futures.put("method", "GET");
        futures.put("origin", "https://www.okx.com");
        futures.put("path", "/api/v5/market/candles");
        futures.put("instId", "INVENTORY_DERIVED_ONLY");
        futures.put("bar", "1Dutc");
        futures.put("credentials", "NONE");
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("method", "GET");
        index.put("origin", "https://www.okx.com");
        index.put("path", "/api/v5/market/index-candles");
        index.put("instId", "BTC-USDT");
        index.put("bar", "1Dutc");
        index.put("credentials", "NONE");
        return Map.of("futures", futures, "index", index);
    }

    private static Map<String, Object> eligibilityMap(LocalDate eligibleDay) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("target_day_use", "DENY_LEAKAGE");
        value.put("d_plus_1_use", "DENY_CAPTURE_AFTER_DECISION");
        value.put("first_eligible_utc_decision_day", eligibleDay.toString());
        value.put("retroactive_admission", "DENY");
        value.put("late_retry", "DENY");
        value.put("backfill", "DENY");
        value.put("partial_day_salvage", "DENY");
        return value;
    }

    private static Map<String, Object> artifactMap(byte[] bytes) {
        return Map.of("sha256", sha256(bytes), "size_bytes", bytes.length);
    }

    private static Map<String, String> requiredInstrumentFields(JsonNode raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String key : INSTRUMENT_KEYS) {
            fields.put(key, requiredText(raw, key));
        }
        return fields;
    }

    private static boolean isEligibleInstrument(Map<String, String> fields) {
        return EXPIRY_INST_ID.matcher(fields.get("instId")).matches()
                && "FUTURES".equals(fields.get("instType"))
                && "BTC-USDT".equals(fields.get("instFamily"))
                && "BTC-USDT".equals(fields.get("uly"))
                && "linear".equals(fields.get("ctType"))
                && "USDT".equals(fields.get("settleCcy"))
                && "live".equals(fields.get("state"))
                && "normal".equals(fields.get("ruleType"));
    }

    private static List<String> requireOneRawRow(
            JsonNode data,
            int size,
            String expectedTimestamp,
            boolean futures,
            String identity) {
        List<String> target = null;
        for (JsonNode raw : data) {
            if (raw == null || !raw.isArray() || raw.size() != size) {
                throw failure("RAW_ROW_SHAPE_REJECT");
            }
            for (JsonNode item : raw) {
                if (!item.isTextual()) {
                    throw failure("RAW_ROW_NON_STRING_REJECT");
                }
            }
            if (!expectedTimestamp.equals(raw.get(0).textValue())) {
                continue;
            }
            if (target != null) {
                throw failure("TARGET_ROW_COUNT_REJECT");
            }
            target = requireRawRow(raw, size, expectedTimestamp, futures, identity);
        }
        if (target == null) {
            throw failure("TARGET_ROW_COUNT_REJECT");
        }
        return target;
    }

    private static List<String> requireRawRow(
            JsonNode raw,
            int size,
            String expectedTimestamp,
            boolean futures,
            String identity) {
        if (raw == null || !raw.isArray() || raw.size() != size) {
            throw failure("RAW_ROW_SHAPE_REJECT");
        }
        List<String> row = new ArrayList<>();
        for (JsonNode item : raw) {
            if (!item.isTextual()) {
                throw failure("RAW_ROW_NON_STRING_REJECT");
            }
            row.add(item.textValue());
        }
        if (!expectedTimestamp.equals(row.get(0)) || !"1".equals(row.get(size - 1))) {
            throw failure("RAW_ROW_TIMESTAMP_OR_CONFIRM_REJECT");
        }
        validateOhlc(row, identity);
        if (futures) {
            for (int index = 5; index <= 7; index++) {
                decimal(row.get(index), false, "VOLUME_REJECT");
            }
        }
        return List.copyOf(row);
    }

    private static void validateOhlc(List<String> row, String identity) {
        BigDecimal open = decimal(row.get(1), true, "OHLC_REJECT");
        BigDecimal high = decimal(row.get(2), true, "OHLC_REJECT");
        BigDecimal low = decimal(row.get(3), true, "OHLC_REJECT");
        BigDecimal close = decimal(row.get(4), true, "OHLC_REJECT");
        BigDecimal maximum = open.max(low).max(close);
        BigDecimal minimum = open.min(high).min(close);
        if (high.compareTo(maximum) < 0 || low.compareTo(minimum) > 0) {
            throw failure("OHLC_BOUNDS_REJECT:" + identity);
        }
    }

    private static BigDecimal decimal(String value, boolean positive, String code) {
        Pattern pattern = positive ? POSITIVE_DECIMAL : NONNEGATIVE_DECIMAL;
        if (value == null || !pattern.matcher(value).matches()) {
            throw failure(code);
        }
        try {
            BigDecimal parsed = new BigDecimal(value);
            if ((positive && parsed.signum() <= 0) || (!positive && parsed.signum() < 0)) {
                throw failure(code);
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(code, error);
        }
    }

    private static long parseMillis(String value, String code) {
        if (value == null || !MILLIS.matcher(value).matches()) {
            throw failure(code);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(code, error);
        }
    }

    private static byte[] seal(
            Map<String, Object> payload,
            String sealKey,
            String canonicalization,
            Instant sealedAt) {
        Map<String, Object> document = new LinkedHashMap<>(payload);
        document.put(sealKey, Map.of(
                "algorithm", "SHA-256",
                "payload_sha256", sha256(canonicalBytes(payload)),
                "canonicalization", canonicalization,
                "sealed_at", sealedAt.toString()));
        return canonicalBytes(document);
    }

    private static void verifySeal(
            JsonNode root, String sealKey, String canonicalization, Instant sealedAt) {
        JsonNode seal = root.get(sealKey);
        requireObjectKeys(seal, SEAL_KEYS, sealKey);
        requireText(seal, "algorithm", "SHA-256");
        requireText(seal, "canonicalization", canonicalization);
        requireText(seal, "sealed_at", sealedAt.toString());
        Map<String, Object> payload = asMap(root);
        payload.remove(sealKey);
        requireText(seal, "payload_sha256", sha256(canonicalBytes(payload)));
    }

    static byte[] canonicalBytes(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsBytes(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("CANONICAL_JSON_REJECT", error);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", error);
        }
    }

    private static JsonNode parseCanonicalJson(byte[] rawBytes, String label) {
        JsonNode root = parseStrictJson(rawBytes, label);
        if (!root.isObject() || !Arrays.equals(rawBytes, canonicalBytes(generic(root)))) {
            throw failure("NONCANONICAL_JSON:" + label);
        }
        return root;
    }

    private static JsonNode parseStrictJson(byte[] rawBytes, String label) {
        if (rawBytes == null) {
            throw failure("NULL_JSON_BYTES:" + label);
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawBytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("INVALID_UTF8:" + label, error);
        }
        try {
            JsonNode root = STRICT_MAPPER.readTree(text);
            if (root == null) {
                throw failure("EMPTY_JSON:" + label);
            }
            return root;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("STRICT_JSON_REJECT:" + label, error);
        }
    }

    private static void requireObjectKeys(JsonNode node, Set<String> expected, String label) {
        if (node == null || !node.isObject()) {
            throw failure("OBJECT_REQUIRED:" + label);
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw failure("OBJECT_KEYS_REJECT:" + label);
        }
    }

    private static String requiredText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw failure("TEXT_FIELD_REJECT:" + key);
        }
        return value.textValue();
    }

    private static void requireText(JsonNode node, String key, String expected) {
        if (!expected.equals(requiredText(node, key))) {
            throw failure("TEXT_FIELD_DRIFT:" + key);
        }
    }

    private static LocalDate requireDate(JsonNode node, String key) {
        String value = requiredText(node, key);
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) {
                throw failure("DATE_FIELD_REJECT:" + key);
            }
            return parsed;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("DATE_FIELD_REJECT:" + key, error);
        }
    }

    private static Instant requireInstant(JsonNode node, String key) {
        String value = requiredText(node, key);
        try {
            Instant parsed = Instant.parse(value);
            requireSecondPrecision(parsed, key);
            if (!parsed.toString().equals(value)) {
                throw failure("TIMESTAMP_FIELD_REJECT:" + key);
            }
            return parsed;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("TIMESTAMP_FIELD_REJECT:" + key, error);
        }
    }

    private static int requirePositiveInt(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 1) {
            throw failure("POSITIVE_INTEGER_REJECT:" + key);
        }
        return value.intValue();
    }

    private static void requireSecondPrecision(Instant value, String label) {
        requireNonNull(value, label);
        if (value.getNano() != 0) {
            throw failure("SECOND_PRECISION_REQUIRED:" + label);
        }
    }

    private static Object generic(JsonNode node) {
        return CANONICAL_MAPPER.convertValue(node, Object.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(JsonNode node) {
        return new LinkedHashMap<>((Map<String, Object>) generic(node));
    }

    private static <T> T requireNonNull(T value, String label) {
        if (value == null) {
            throw failure("NULL_REJECT:" + label);
        }
        return value;
    }

    private static IllegalArgumentException failure(String code) {
        return new IllegalArgumentException(code);
    }

    interface Transport {
        ResponseSpec execute(RequestSpec request) throws Exception;
    }

    record RequestSpec(
            URI uri, String method, Duration timeout, Map<String, String> headers) {
        RequestSpec {
            requireNonNull(uri, "request uri");
            requireNonNull(method, "request method");
            requireNonNull(timeout, "request timeout");
            headers = Map.copyOf(requireNonNull(headers, "request headers"));
        }
    }

    record ResponseSpec(int statusCode, String contentType, byte[] body) {
        ResponseSpec {
            contentType = requireNonNull(contentType, "response contentType");
            body = requireNonNull(body, "response body").clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    record CaptureArtifacts(byte[] inventoryBytes, byte[] dayBytes, byte[] envelopeBytes) {
        CaptureArtifacts {
            inventoryBytes = inventoryBytes.clone();
            dayBytes = dayBytes.clone();
            envelopeBytes = envelopeBytes.clone();
        }

        @Override
        public byte[] inventoryBytes() {
            return inventoryBytes.clone();
        }

        @Override
        public byte[] dayBytes() {
            return dayBytes.clone();
        }

        @Override
        public byte[] envelopeBytes() {
            return envelopeBytes.clone();
        }
    }

    static final class FixedHttpClientTransport implements Transport {
        private final HttpClient client;

        FixedHttpClientTransport() {
            this.client = HttpClient.newBuilder()
                    .connectTimeout(REQUEST_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        @Override
        public ResponseSpec execute(RequestSpec request) throws Exception {
            validateFixedRequest(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(request.uri())
                    .GET()
                    .timeout(request.timeout())
                    .header("Accept", request.headers().get("Accept"))
                    .header("User-Agent", request.headers().get("User-Agent"))
                    .build();
            HttpResponse<InputStream> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream body = response.body()) {
                bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw failure("RESPONSE_SIZE_REJECT");
            }
            return new ResponseSpec(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    bytes);
        }
    }

    private record InventoryDocument(
            LocalDate targetDay, List<String> instIds, Instant capturedAt) {
        InventoryDocument {
            instIds = List.copyOf(instIds);
        }
    }

    private record DayDocument(
            LocalDate targetDay, Instant capturedAt, LocalDate eligibleDay) {
    }
}
