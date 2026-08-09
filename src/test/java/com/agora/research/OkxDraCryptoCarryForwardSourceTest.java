package com.agora.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxDraCryptoCarryForwardSourceTest {

    private static final LocalDate TARGET_DAY = LocalDate.of(2026, 8, 10);
    private static final Instant INVENTORY_CAPTURE =
            Instant.parse("2026-08-09T01:05:02Z");
    private static final Instant DAY_CAPTURE = Instant.parse("2026-08-11T01:05:03Z");
    private static final String EXPECTED_INVENTORY_SHA256 =
            "cf73154a45d5ba378b6dd159833d46ade8ad3a0ff54023b5a1da2286747b5b01";
    private static final String EXPECTED_DAY_SHA256 =
            "bc490099bbd10b3994836a3675b18c9aa0a1312057ab6809a71a9e2991cf9359";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void matchesFrozenPythonInventoryAndDayBytesExactly() throws Exception {
        FakeTransport fake = validTransport(true);
        OkxDraCryptoCarryForwardSource source = new OkxDraCryptoCarryForwardSource(fake);

        byte[] inventory = source.captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
        byte[] day = source.captureDay(inventory, DAY_CAPTURE);

        assertEquals(1_321, inventory.length);
        assertEquals(EXPECTED_INVENTORY_SHA256,
                OkxDraCryptoCarryForwardSource.sha256(inventory));
        assertEquals(2_006, day.length);
        assertEquals(EXPECTED_DAY_SHA256, OkxDraCryptoCarryForwardSource.sha256(day));
    }

    @Test
    void reversedProviderInventoryOrderingProducesIdenticalCanonicalBytes() throws Exception {
        OkxDraCryptoCarryForwardSource forward =
                new OkxDraCryptoCarryForwardSource(validTransport(false));
        OkxDraCryptoCarryForwardSource reverse =
                new OkxDraCryptoCarryForwardSource(validTransport(true));

        byte[] forwardInventory = forward.captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
        byte[] reverseInventory = reverse.captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
        byte[] forwardDay = forward.captureDay(forwardInventory, DAY_CAPTURE);
        byte[] reverseDay = reverse.captureDay(reverseInventory, DAY_CAPTURE);

        assertArrayEquals(forwardInventory, reverseInventory);
        assertArrayEquals(forwardDay, reverseDay);
    }

    @Test
    void requestsAreFixedGetJsonBoundedAndExactlyOnce() throws Exception {
        FakeTransport fake = validTransport(true);
        OkxDraCryptoCarryForwardSource source = new OkxDraCryptoCarryForwardSource(fake);
        byte[] inventory = source.captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
        source.captureDay(inventory, DAY_CAPTURE);

        assertEquals(1, fake.count(OkxDraCryptoCarryForwardSource.INVENTORY_URI));
        assertEquals(1, fake.count(candleUri("BTC-USDT-260814")));
        assertEquals(1, fake.count(candleUri("BTC-USDT-260821")));
        assertEquals(1, fake.count(OkxDraCryptoCarryForwardSource.INDEX_URI));
        assertEquals(4, fake.requests().size());
        for (OkxDraCryptoCarryForwardSource.RequestSpec request : fake.requests()) {
            assertEquals("GET", request.method());
            assertEquals(OkxDraCryptoCarryForwardSource.REQUEST_TIMEOUT, request.timeout());
            assertEquals(
                    Map.of(
                            "Accept", OkxDraCryptoCarryForwardSource.ACCEPT,
                            "User-Agent", OkxDraCryptoCarryForwardSource.USER_AGENT),
                    request.headers());
            assertEquals("https", request.uri().getScheme());
            assertEquals("www.okx.com", request.uri().getHost());
        }
    }

    @Test
    void transportExceptionAndProviderEnvelopeFailuresHaveNoRetry() throws Exception {
        List<FailureCase> cases = List.of(
                new FailureCase("exception", null, new IllegalStateException("offline")),
                new FailureCase("status", response(503, "application/json", ok(List.of())), null),
                new FailureCase("content-type", response(200, "text/plain", ok(List.of())), null),
                new FailureCase("empty", response(200, "application/json", new byte[0]), null),
                new FailureCase(
                        "oversize",
                        response(
                                200,
                                "application/json",
                                new byte[OkxDraCryptoCarryForwardSource.MAX_RESPONSE_BYTES + 1]),
                        null),
                new FailureCase(
                        "invalid-utf8",
                        response(200, "application/json", new byte[]{(byte) 0xc3, (byte) 0x28}),
                        null),
                new FailureCase(
                        "duplicate-key",
                        response(
                                200,
                                "application/json",
                                "{\"code\":\"0\",\"code\":\"0\",\"msg\":\"\",\"data\":[]}"
                                        .getBytes(StandardCharsets.UTF_8)),
                        null),
                new FailureCase(
                        "api-code",
                        response(
                                200,
                                "application/json",
                                json(Map.of("code", "1", "msg", "failed", "data", List.of()))),
                        null),
                new FailureCase(
                        "extra-envelope-key",
                        response(
                                200,
                                "application/json",
                                json(Map.of(
                                        "code", "0", "msg", "", "data", List.of(),
                                        "extra", true))),
                        null),
                new FailureCase(
                        "data-not-array",
                        response(
                                200,
                                "application/json",
                                json(Map.of("code", "0", "msg", "", "data", Map.of()))),
                        null));

        for (FailureCase failureCase : cases) {
            FakeTransport fake = new FakeTransport();
            if (failureCase.failure() != null) {
                fake.fail(OkxDraCryptoCarryForwardSource.INVENTORY_URI, failureCase.failure());
            } else {
                fake.respond(OkxDraCryptoCarryForwardSource.INVENTORY_URI, failureCase.response());
            }
            OkxDraCryptoCarryForwardSource source =
                    new OkxDraCryptoCarryForwardSource(fake);
            assertThrows(
                    RuntimeException.class,
                    () -> source.captureInventory(TARGET_DAY, INVENTORY_CAPTURE),
                    failureCase.label());
            assertEquals(1, fake.count(OkxDraCryptoCarryForwardSource.INVENTORY_URI));
        }
    }

    @Test
    void inventoryFiltersNonContractRowsButRejectsDuplicates() throws Exception {
        List<Map<String, String>> rows = fixtureInstruments(false);
        Map<String, String> suspended = fixtureInstrument(
                "BTC-USDT-260828", "2026-08-28T08:00:00Z");
        suspended.put("state", "suspend");
        rows.add(suspended);
        FakeTransport filtered = validTransport(false);
        filtered.respond(
                OkxDraCryptoCarryForwardSource.INVENTORY_URI,
                response(200, "application/json; charset=utf-8", ok(rows)));
        byte[] inventory = new OkxDraCryptoCarryForwardSource(filtered)
                .captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
        assertEquals(EXPECTED_INVENTORY_SHA256,
                OkxDraCryptoCarryForwardSource.sha256(inventory));

        List<Map<String, String>> duplicateRows = fixtureInstruments(false);
        duplicateRows.add(new LinkedHashMap<>(duplicateRows.get(0)));
        FakeTransport duplicate = validTransport(false);
        duplicate.respond(
                OkxDraCryptoCarryForwardSource.INVENTORY_URI,
                response(200, "application/json", ok(duplicateRows)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OkxDraCryptoCarryForwardSource(duplicate)
                        .captureInventory(TARGET_DAY, INVENTORY_CAPTURE));
    }

    @Test
    void inventoryListExpiryAndClockDriftFailClosedBeforePartialOutput() throws Exception {
        for (String field : List.of("listTime", "expTime")) {
            List<Map<String, String>> rows = fixtureInstruments(false);
            rows.get(0).put(
                    field,
                    field.equals("listTime")
                            ? millis("2026-08-09T02:00:00Z")
                            : millis("2026-08-11T00:00:00Z"));
            FakeTransport fake = validTransport(false);
            fake.respond(
                    OkxDraCryptoCarryForwardSource.INVENTORY_URI,
                    response(200, "application/json", ok(rows)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OkxDraCryptoCarryForwardSource(fake)
                            .captureInventory(TARGET_DAY, INVENTORY_CAPTURE));
        }

        for (Instant drift : List.of(
                Instant.parse("2026-08-09T01:04:59Z"),
                Instant.parse("2026-08-10T00:00:00Z"))) {
            FakeTransport fake = validTransport(false);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OkxDraCryptoCarryForwardSource(fake)
                            .captureInventory(TARGET_DAY, drift));
            assertEquals(0, fake.totalCount());
        }
    }

    @Test
    void dayClockAndInventoryHashDriftFailBeforeNetwork() throws Exception {
        FakeTransport fixture = validTransport(false);
        byte[] inventory = new OkxDraCryptoCarryForwardSource(fixture)
                .captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
        for (Instant drift : List.of(
                Instant.parse("2026-08-11T01:04:59Z"),
                Instant.parse("2026-08-11T06:00:00Z"))) {
            FakeTransport fake = validTransport(false);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OkxDraCryptoCarryForwardSource(fake)
                            .captureDay(inventory, drift));
            assertEquals(0, fake.totalCount());
        }

        byte[] changed = new String(inventory, StandardCharsets.UTF_8)
                .replace(
                        OkxDraCryptoCarryForwardSource.SOURCE_CONTRACT_SHA256,
                        "0".repeat(64))
                .getBytes(StandardCharsets.UTF_8);
        FakeTransport fake = validTransport(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> new OkxDraCryptoCarryForwardSource(fake)
                        .captureDay(changed, DAY_CAPTURE));
        assertEquals(0, fake.totalCount());
    }

    @Test
    void missingDuplicateUnconfirmedAndWrongDayRowsFailClosed() throws Exception {
        byte[] inventory = validInventory();
        List<List<List<String>>> failures = List.of(
                List.of(),
                List.of(futuresRow("105.00"), futuresRow("105.00")),
                List.of(replace(futuresRow("105.00"), 8, "0")),
                List.of(replace(
                        futuresRow("105.00"),
                        0,
                        millis("2026-08-10T00:00:01Z"))));
        for (List<List<String>> data : failures) {
            FakeTransport fake = validTransport(false);
            fake.respond(
                    candleUri("BTC-USDT-260814"),
                    response(200, "application/json", ok(data)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OkxDraCryptoCarryForwardSource(fake)
                            .captureDay(inventory, DAY_CAPTURE));
            assertEquals(1, fake.count(candleUri("BTC-USDT-260814")));
            assertEquals(0, fake.count(candleUri("BTC-USDT-260821")));
        }
    }

    @Test
    void providerHistoryOrderDoesNotImplyFreshnessAndOnlyTargetDayIsPreserved()
            throws Exception {
        byte[] inventory = validInventory();
        FakeTransport fake = validTransport(false);
        List<String> priorFuture = replace(
                futuresRow("99.00"), 0, millis("2026-08-09T00:00:00Z"));
        List<String> currentUnconfirmedFuture = replace(
                replace(futuresRow("107.00"), 0, millis("2026-08-11T00:00:00Z")),
                8,
                "0");
        fake.respond(
                candleUri("BTC-USDT-260814"),
                response(
                        200,
                        "application/json",
                        ok(List.of(currentUnconfirmedFuture, futuresRow("105.00"), priorFuture))));
        List<String> priorIndex = replace(
                indexRow(), 0, millis("2026-08-09T00:00:00Z"));
        List<String> currentUnconfirmedIndex = replace(
                replace(indexRow(), 0, millis("2026-08-11T00:00:00Z")),
                5,
                "0");
        fake.respond(
                OkxDraCryptoCarryForwardSource.INDEX_URI,
                response(
                        200,
                        "application/json",
                        ok(List.of(priorIndex, currentUnconfirmedIndex, indexRow()))));

        byte[] day = new OkxDraCryptoCarryForwardSource(fake)
                .captureDay(inventory, DAY_CAPTURE);

        assertEquals(EXPECTED_DAY_SHA256, OkxDraCryptoCarryForwardSource.sha256(day));
    }

    @Test
    void invalidOhlcVolumeAndNonStringRowsFailClosed() throws Exception {
        byte[] inventory = validInventory();
        List<List<String>> badRows = List.of(
                replace(futuresRow("105.00"), 1, "NaN"),
                replace(futuresRow("105.00"), 2, "80"),
                replace(futuresRow("105.00"), 3, "120"),
                replace(futuresRow("105.00"), 5, "-1"));
        for (List<String> row : badRows) {
            FakeTransport fake = validTransport(false);
            fake.respond(
                    candleUri("BTC-USDT-260814"),
                    response(200, "application/json", ok(List.of(row))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OkxDraCryptoCarryForwardSource(fake)
                            .captureDay(inventory, DAY_CAPTURE));
        }

        List<Object> nonString = new ArrayList<>(futuresRow("105.00"));
        nonString.set(1, 100);
        FakeTransport fake = validTransport(false);
        fake.respond(
                candleUri("BTC-USDT-260814"),
                response(200, "application/json", ok(List.of(nonString))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OkxDraCryptoCarryForwardSource(fake)
                        .captureDay(inventory, DAY_CAPTURE));
    }

    @Test
    void envelopeIsClosedHashBoundDisabledAndInMemoryOnly() throws Exception {
        FakeTransport fake = validTransport(true);
        OkxDraCryptoCarryForwardSource source = new OkxDraCryptoCarryForwardSource(fake);
        byte[] inventory = source.captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
        byte[] day = source.captureDay(inventory, DAY_CAPTURE);
        byte[] envelope = source.createEnvelope(inventory, day, DAY_CAPTURE);
        JsonNode root = MAPPER.readTree(envelope);

        assertEquals(Set.of(
                        "schema_version", "envelope_type", "authorization", "status",
                        "source_label", "source_contract_sha256",
                        "producer_envelope_schema_sha256", "producer_id", "target_day",
                        "generated_at", "inventory", "day",
                        "first_eligible_utc_decision_day", "transport_status",
                        "envelope_seal"),
                fieldNames(root));
        assertEquals(OkxDraCryptoCarryForwardSource.STATUS, root.get("status").textValue());
        assertEquals(
                OkxDraCryptoCarryForwardSource.ENVELOPE_SCHEMA_SHA256,
                root.get("producer_envelope_schema_sha256").textValue());
        assertEquals(
                OkxDraCryptoCarryForwardSource.TRANSPORT_STATUS,
                root.get("transport_status").textValue());
        assertEquals(EXPECTED_INVENTORY_SHA256,
                root.get("inventory").get("sha256").textValue());
        assertEquals(EXPECTED_DAY_SHA256, root.get("day").get("sha256").textValue());
        assertEquals(1_321, root.get("inventory").get("size_bytes").intValue());
        assertEquals(2_006, root.get("day").get("size_bytes").intValue());
    }

    @Test
    void generatedDocumentsAdmitNoDerivedEconomicFields() throws Exception {
        FakeTransport fake = validTransport(false);
        OkxDraCryptoCarryForwardSource source = new OkxDraCryptoCarryForwardSource(fake);
        byte[] inventory = source.captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
        byte[] day = source.captureDay(inventory, DAY_CAPTURE);
        byte[] envelope = source.createEnvelope(inventory, day, DAY_CAPTURE);
        Set<String> forbidden = Set.of(
                "basis", "carry", "annualization", "funding", "tenor",
                "maturity_preference", "roll", "liquidity", "threshold", "signal",
                "return", "pnl", "drawdown");
        for (byte[] document : List.of(inventory, day, envelope)) {
            assertFalse(containsForbiddenField(MAPPER.readTree(document), forbidden));
        }
    }

    @Test
    void coreHasNoMainSpringFilesystemOrRuntimeRegistrationSurface() {
        assertFalse(Arrays.stream(OkxDraCryptoCarryForwardSource.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("main")));
        assertFalse(Arrays.stream(OkxDraCryptoCarryForwardSource.class.getAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getName().startsWith("org.springframework")));
        assertFalse(Arrays.stream(OkxDraCryptoCarryForwardSource.class.getDeclaredFields())
                .anyMatch(field -> field.getType().getName().startsWith("java.nio.file")));
        assertNotNull(OkxDraCryptoCarryForwardSource.FixedHttpClientTransport.class);
    }

    private static byte[] validInventory() throws Exception {
        return new OkxDraCryptoCarryForwardSource(validTransport(false))
                .captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
    }

    private static FakeTransport validTransport(boolean reversedInventory) throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respond(
                OkxDraCryptoCarryForwardSource.INVENTORY_URI,
                response(
                        200,
                        "application/json; charset=utf-8",
                        ok(fixtureInstruments(reversedInventory))));
        fake.respond(
                candleUri("BTC-USDT-260814"),
                response(200, "application/json", ok(List.of(futuresRow("105.00")))));
        fake.respond(
                candleUri("BTC-USDT-260821"),
                response(200, "application/json", ok(List.of(futuresRow("106.00")))));
        fake.respond(
                OkxDraCryptoCarryForwardSource.INDEX_URI,
                response(200, "application/json", ok(List.of(indexRow()))));
        return fake;
    }

    private static List<Map<String, String>> fixtureInstruments(boolean reversed) {
        List<Map<String, String>> rows = new ArrayList<>(List.of(
                fixtureInstrument("BTC-USDT-260814", "2026-08-14T08:00:00Z"),
                fixtureInstrument("BTC-USDT-260821", "2026-08-21T08:00:00Z")));
        if (reversed) {
            java.util.Collections.reverse(rows);
        }
        return rows;
    }

    private static Map<String, String> fixtureInstrument(String instId, String expiry) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("instId", instId);
        value.put("instType", "FUTURES");
        value.put("instFamily", "BTC-USDT");
        value.put("uly", "BTC-USDT");
        value.put("ctType", "linear");
        value.put("settleCcy", "USDT");
        value.put("state", "live");
        value.put("ruleType", "normal");
        value.put("listTime", millis("2026-08-01T00:00:00Z"));
        value.put("expTime", millis(expiry));
        return value;
    }

    private static List<String> futuresRow(String close) {
        return new ArrayList<>(List.of(
                millis("2026-08-10T00:00:00Z"),
                "100.00", "112.00", "91.00", close,
                "10.0", "1.0", "1000.0", "1"));
    }

    private static List<String> indexRow() {
        return new ArrayList<>(List.of(
                millis("2026-08-10T00:00:00Z"),
                "100.00", "110.00", "90.00", "104.00", "1"));
    }

    private static List<String> replace(List<String> source, int index, String value) {
        List<String> changed = new ArrayList<>(source);
        changed.set(index, value);
        return changed;
    }

    private static String millis(String value) {
        return Long.toString(Instant.parse(value).toEpochMilli());
    }

    private static URI candleUri(String instId) {
        return URI.create("https://www.okx.com/api/v5/market/candles?instId="
                + instId + "&bar=1Dutc");
    }

    private static byte[] ok(Object data) throws Exception {
        return json(Map.of("code", "0", "msg", "", "data", data));
    }

    private static byte[] json(Object value) throws Exception {
        return MAPPER.writeValueAsBytes(value);
    }

    private static OkxDraCryptoCarryForwardSource.ResponseSpec response(
            int status, String contentType, byte[] body) {
        return new OkxDraCryptoCarryForwardSource.ResponseSpec(status, contentType, body);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static boolean containsForbiddenField(JsonNode node, Set<String> forbidden) {
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (forbidden.contains(field.getKey())
                        || containsForbiddenField(field.getValue(), forbidden)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                if (containsForbiddenField(value, forbidden)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record FailureCase(
            String label,
            OkxDraCryptoCarryForwardSource.ResponseSpec response,
            RuntimeException failure) {
    }

    private static final class FakeTransport
            implements OkxDraCryptoCarryForwardSource.Transport {
        private final Map<URI, OkxDraCryptoCarryForwardSource.ResponseSpec> responses =
                new HashMap<>();
        private final Map<URI, RuntimeException> failures = new HashMap<>();
        private final Map<URI, Integer> counts = new HashMap<>();
        private final List<OkxDraCryptoCarryForwardSource.RequestSpec> requests =
                new ArrayList<>();

        void respond(URI uri, OkxDraCryptoCarryForwardSource.ResponseSpec response) {
            responses.put(uri, response);
            failures.remove(uri);
        }

        void fail(URI uri, RuntimeException failure) {
            failures.put(uri, failure);
            responses.remove(uri);
        }

        int count(URI uri) {
            return counts.getOrDefault(uri, 0);
        }

        int totalCount() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        List<OkxDraCryptoCarryForwardSource.RequestSpec> requests() {
            return List.copyOf(requests);
        }

        @Override
        public OkxDraCryptoCarryForwardSource.ResponseSpec execute(
                OkxDraCryptoCarryForwardSource.RequestSpec request) {
            requests.add(request);
            counts.merge(request.uri(), 1, Integer::sum);
            RuntimeException failure = failures.get(request.uri());
            if (failure != null) {
                throw failure;
            }
            OkxDraCryptoCarryForwardSource.ResponseSpec response =
                    responses.get(request.uri());
            if (response == null) {
                throw new IllegalStateException("NO_FAKE_RESPONSE:" + request.uri());
            }
            return response;
        }
    }
}
