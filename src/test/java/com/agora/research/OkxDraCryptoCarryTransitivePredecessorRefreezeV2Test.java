package com.agora.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxDraCryptoCarryTransitivePredecessorRefreezeV2Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate TARGET_DAY = LocalDate.parse("2026-08-10");
    private static final Instant INVENTORY_CAPTURE = Instant.parse("2026-08-09T01:05:02Z");
    private static final Instant INVENTORY_PARENT_AT = Instant.parse("2026-08-09T01:05:03Z");
    private static final Instant INVENTORY_PUBLISHED_AT = Instant.parse("2026-08-09T01:05:04Z");
    private static final Instant INVENTORY_ACCEPTED_AT = Instant.parse("2026-08-09T01:05:05Z");
    private static final Instant DAY_CAPTURE = Instant.parse("2026-08-11T01:05:03Z");
    private static final Instant V1_PRODUCER_AT = Instant.parse("2026-08-11T01:05:04Z");
    private static final Instant DAY_PARENT_AT = Instant.parse("2026-08-11T01:05:05Z");
    private static final Instant DAY_PUBLISHED_AT = Instant.parse("2026-08-11T01:05:06Z");
    private static final Instant DAY_ACCEPTED_AT = Instant.parse("2026-08-11T01:05:07Z");
    private static final Instant DEADLINE = Instant.parse("2026-08-11T06:00:00Z");
    private static final String INVENTORY_SHA =
            "cf73154a45d5ba378b6dd159833d46ade8ad3a0ff54023b5a1da2286747b5b01";
    private static final String DAY_SHA =
            "bc490099bbd10b3994836a3675b18c9aa0a1312057ab6809a71a9e2991cf9359";
    private static final String V1_PRODUCER_SHA =
            "90ee720f8815f65f88f85ff2873fbab26d859340195ff7ea0fca9279ad979ef4";

    @TempDir
    Path temporaryDirectory;

    @Test
    void schemasAndParentContractAreClosedAndHashBound() throws Exception {
        Map<Path, String> schemas = Map.of(
                Path.of("research_pipeline/okx-dra-crypto-carry-producer-envelope.v2.schema.json"),
                OkxDraCryptoCarryProducerEnvelopeV2.PRODUCER_SCHEMA_SHA256,
                Path.of("research_pipeline/okx-dra-crypto-carry-inventory-drop-envelope.v2.schema.json"),
                OkxDraCryptoCarryProducerEnvelopeV2.INVENTORY_DROP_SCHEMA_SHA256,
                Path.of("research_pipeline/okx-dra-crypto-carry-drop-envelope.v2.schema.json"),
                OkxDraCryptoCarryProducerEnvelopeV2.DAY_DROP_SCHEMA_SHA256,
                Path.of("research_pipeline/okx-dra-crypto-carry-intake-state.v2.schema.json"),
                OkxDraCryptoCarryProducerEnvelopeV2.INTAKE_STATE_SCHEMA_SHA256);
        for (Map.Entry<Path, String> entry : schemas.entrySet()) {
            byte[] bytes = Files.readAllBytes(entry.getKey());
            assertEquals(entry.getValue(), sha256(bytes));
            JsonNode schema = MAPPER.readTree(bytes);
            assertEquals("https://json-schema.org/draft/2020-12/schema",
                    schema.get("$schema").textValue());
            assertClosedObjectSchemas(schema);
        }
        Path contractPath = Path.of(
                "research_pipeline/okx-dra-crypto-carry-expiry-futures-source-contract.v2.json");
        byte[] contract = Files.readAllBytes(contractPath);
        assertEquals(OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_CONTRACT_SHA256,
                sha256(contract));
        JsonNode root = MAPPER.readTree(contract);
        assertEquals("OFFLINE_DISABLED_NOT_REGISTERED",
                root.get("document_status").textValue());
        assertEquals("agora-dra-carry-source", root.get("source_identity").textValue());
        assertEquals("agora-dra-carry-publish", root.get("source_group").textValue());
        assertEquals(OkxDraCryptoCarryProducerEnvelopeV2.PRODUCER_SCHEMA_SHA256,
                root.at("/schemas/producer_envelope/sha256").textValue());
        assertEquals(OkxDraCryptoCarryProducerEnvelopeV2.INVENTORY_DROP_SCHEMA_SHA256,
                root.at("/schemas/inventory_drop_envelope/sha256").textValue());
        assertEquals(OkxDraCryptoCarryProducerEnvelopeV2.DAY_DROP_SCHEMA_SHA256,
                root.at("/schemas/day_drop_envelope/sha256").textValue());
        assertEquals(OkxDraCryptoCarryProducerEnvelopeV2.INTAKE_STATE_SCHEMA_SHA256,
                root.at("/schemas/intake_state/sha256").textValue());
    }

    @Test
    void frozenV1ChildrenRemainExactAndAccuratelyLabelled() throws Exception {
        Fixture fixture = fixture();
        assertEquals(1_321, fixture.inventory().length);
        assertEquals(INVENTORY_SHA, sha256(fixture.inventory()));
        assertEquals(2_006, fixture.day().length);
        assertEquals(DAY_SHA, sha256(fixture.day()));
        assertEquals(1_161, fixture.v1Producer().length);
        assertEquals(V1_PRODUCER_SHA, sha256(fixture.v1Producer()));

        byte[] inventoryParent = OkxDraCryptoCarryProducerEnvelopeV2.prepareInventory(
                fixture.inventory(), INVENTORY_PARENT_AT);
        JsonNode parent = MAPPER.readTree(inventoryParent);
        assertEquals("agora-dra-carry-source", parent.get("source_identity").textValue());
        assertEquals("agora-evidence-source",
                parent.at("/v1_child_lineage/source_identity").textValue());
        assertEquals(OkxDraCryptoCarryProducerEnvelopeV2.V1_SOURCE_CONTRACT_SHA256,
                parent.at("/inventory/source_contract_sha256").textValue());
        assertEquals(INVENTORY_SHA, parent.at("/inventory/sha256").textValue());
    }

    @Test
    void twoPhaseDropAndIndependentIntakeCloseExactStatePredecessor() throws Exception {
        Fixture fixture = fixture();
        Roots roots = roots();
        RecordingForcer forcer = new RecordingForcer();
        OkxDraCryptoCarryCanonicalDropV2.FileDropSink sink = sink(roots, forcer);

        byte[] inventoryParent = OkxDraCryptoCarryProducerEnvelopeV2.prepareInventory(
                fixture.inventory(), INVENTORY_PARENT_AT);
        OkxDraCryptoCarryCanonicalDropV2.PhaseDocuments inventoryDocuments =
                OkxDraCryptoCarryCanonicalDropV2.createInventoryDrop(
                        inventoryDropBinding(fixture), fixture.inventory(), inventoryParent);
        Path inventoryPublished = sink.publish(inventoryDocuments);
        assertArrayEquals(fixture.inventory(), Files.readAllBytes(inventoryPublished.resolve(
                "okx-dra-crypto-carry-2026-08-10.inventory.json")));
        OkxDraCryptoCarryNetworkDeniedIntakeV2.IntakeResult inventoryResult =
                OkxDraCryptoCarryNetworkDeniedIntakeV2.acceptInventory(
                        new OkxDraCryptoCarryNetworkDeniedIntakeV2.InventoryBinding(
                                TARGET_DAY, INVENTORY_ACCEPTED_AT), roots.inventoryDrop());
        assertEquals(OkxDraCryptoCarryNetworkDeniedIntakeV2.Stage.INVENTORY_ACCEPTED,
                inventoryResult.stage());
        byte[] acceptedState = inventoryResult.stateBytes();
        JsonNode inventoryState = MAPPER.readTree(acceptedState);
        assertEquals(INVENTORY_SHA, inventoryState.at("/inventory/sha256").textValue());
        assertNull(inventoryState.get("first_eligible_utc_decision_day").textValue());

        byte[] dayParent = OkxDraCryptoCarryProducerEnvelopeV2.finalizeDay(
                fixture.inventory(), fixture.day(), fixture.v1Producer(), acceptedState,
                DAY_PARENT_AT);
        OkxDraCryptoCarryCanonicalDropV2.DayBinding dayBinding = dayDropBinding(acceptedState);
        OkxDraCryptoCarryCanonicalDropV2.PhaseDocuments dayDocuments =
                OkxDraCryptoCarryCanonicalDropV2.createDayDrop(
                        dayBinding, acceptedState, fixture.inventory(), fixture.day(),
                        fixture.v1Producer(), dayParent);
        Path dayPublished = sink.publish(dayDocuments);
        assertArrayEquals(fixture.inventory(), Files.readAllBytes(dayPublished.resolve(
                "okx-dra-crypto-carry-2026-08-10.inventory.json")));
        OkxDraCryptoCarryNetworkDeniedIntakeV2.IntakeResult dayResult =
                OkxDraCryptoCarryNetworkDeniedIntakeV2.acceptDay(
                        dayIntakeBinding(acceptedState), roots.dayDrop(), acceptedState);
        JsonNode dayState = MAPPER.readTree(dayResult.stateBytes());
        assertEquals("DAY_ACCEPTED", dayState.get("stage").textValue());
        assertEquals(sha256(acceptedState), dayState.get("previous_state_sha256").textValue());
        assertEquals(INVENTORY_SHA, dayState.at("/inventory/sha256").textValue());
        assertEquals(DAY_SHA, dayState.at("/day/sha256").textValue());
        assertEquals(V1_PRODUCER_SHA,
                dayState.at("/v1_producer_envelope/sha256").textValue());
        assertEquals("2026-08-12",
                dayState.get("first_eligible_utc_decision_day").textValue());
        assertEquals(6, forcer.paths().size());
    }

    @Test
    void constructionAndStateBytesAreDeterministic() throws Exception {
        Fixture fixture = fixture();
        byte[] firstParent = OkxDraCryptoCarryProducerEnvelopeV2.prepareInventory(
                fixture.inventory(), INVENTORY_PARENT_AT);
        byte[] secondParent = OkxDraCryptoCarryProducerEnvelopeV2.prepareInventory(
                fixture.inventory(), INVENTORY_PARENT_AT);
        assertArrayEquals(firstParent, secondParent);
        OkxDraCryptoCarryCanonicalDropV2.PhaseDocuments first =
                OkxDraCryptoCarryCanonicalDropV2.createInventoryDrop(
                        inventoryDropBinding(fixture), fixture.inventory(), firstParent);
        OkxDraCryptoCarryCanonicalDropV2.PhaseDocuments second =
                OkxDraCryptoCarryCanonicalDropV2.createInventoryDrop(
                        inventoryDropBinding(fixture), fixture.inventory(), secondParent);
        assertArrayEquals(
                first.files().get("okx-dra-crypto-carry-2026-08-10.inventory-drop-v2.json"),
                second.files().get("okx-dra-crypto-carry-2026-08-10.inventory-drop-v2.json"));

        Roots firstRoots = roots("first");
        Roots secondRoots = roots("second");
        sink(firstRoots, new RecordingForcer()).publish(first);
        sink(secondRoots, new RecordingForcer()).publish(second);
        byte[] firstState = OkxDraCryptoCarryNetworkDeniedIntakeV2.acceptInventory(
                new OkxDraCryptoCarryNetworkDeniedIntakeV2.InventoryBinding(
                        TARGET_DAY, INVENTORY_ACCEPTED_AT), firstRoots.inventoryDrop())
                .stateBytes();
        byte[] secondState = OkxDraCryptoCarryNetworkDeniedIntakeV2.acceptInventory(
                new OkxDraCryptoCarryNetworkDeniedIntakeV2.InventoryBinding(
                        TARGET_DAY, INVENTORY_ACCEPTED_AT), secondRoots.inventoryDrop())
                .stateBytes();
        assertArrayEquals(firstState, secondState);
    }

    @Test
    void createOnlyConflictPartialExtraAndBroadRootsFailClosed() throws Exception {
        Fixture fixture = fixture();
        Roots roots = roots();
        OkxDraCryptoCarryCanonicalDropV2.FileDropSink sink =
                sink(roots, new RecordingForcer());
        byte[] parent = OkxDraCryptoCarryProducerEnvelopeV2.prepareInventory(
                fixture.inventory(), INVENTORY_PARENT_AT);
        OkxDraCryptoCarryCanonicalDropV2.PhaseDocuments documents =
                OkxDraCryptoCarryCanonicalDropV2.createInventoryDrop(
                        inventoryDropBinding(fixture), fixture.inventory(), parent);
        sink.publish(documents);
        assertThrows(IllegalArgumentException.class, () -> sink.publish(documents));

        Files.writeString(roots.inventoryDrop().resolve(TARGET_DAY + ".inventory-v2")
                .resolve("extra.json"), "{}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryNetworkDeniedIntakeV2.acceptInventory(
                        new OkxDraCryptoCarryNetworkDeniedIntakeV2.InventoryBinding(
                                TARGET_DAY, INVENTORY_ACCEPTED_AT), roots.inventoryDrop()));

        Path broad = Files.createDirectory(temporaryDirectory.resolve("microstructure-drop"));
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryNetworkDeniedIntakeV2.acceptInventory(
                        new OkxDraCryptoCarryNetworkDeniedIntakeV2.InventoryBinding(
                                TARGET_DAY, INVENTORY_ACCEPTED_AT), broad));

        Roots partialRoots = roots("partial");
        Files.createFile(partialRoots.inventoryDrop().resolve(
                ".2026-08-10.inventory-v2.publish-reserved"));
        Path partial = Files.createDirectory(
                partialRoots.inventoryDrop().resolve("2026-08-10.inventory-v2"));
        Files.write(partial.resolve("okx-dra-crypto-carry-2026-08-10.inventory.json"),
                fixture.inventory());
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryNetworkDeniedIntakeV2.acceptInventory(
                        new OkxDraCryptoCarryNetworkDeniedIntakeV2.InventoryBinding(
                                TARGET_DAY, INVENTORY_ACCEPTED_AT),
                        partialRoots.inventoryDrop()));

        assertThrows(IllegalArgumentException.class,
                () -> new OkxDraCryptoCarryCanonicalDropV2.PhaseDocuments(
                        OkxDraCryptoCarryCanonicalDropV2.Phase.PREPARE_INVENTORY,
                        TARGET_DAY,
                        TARGET_DAY + ".inventory-v2",
                        "." + TARGET_DAY + ".inventory-v2.publish-reserved",
                        Map.of("../escape.json", fixture.inventory())));

        byte[] mutable = documents.files().get(
                "okx-dra-crypto-carry-2026-08-10.inventory.json");
        mutable[0] ^= 1;
        assertArrayEquals(fixture.inventory(), documents.files().get(
                "okx-dra-crypto-carry-2026-08-10.inventory.json"));
    }

    @Test
    void changedInventoryWrongStateLateDayAndEnvelopeTamperFailClosed() throws Exception {
        Published published = publishInventory();
        byte[] changedInventory = published.fixture().inventory().clone();
        changedInventory[changedInventory.length - 2] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryProducerEnvelopeV2.finalizeDay(
                        changedInventory, published.fixture().day(),
                        published.fixture().v1Producer(), published.acceptedState(),
                        DAY_PARENT_AT));

        byte[] stateTamper = published.acceptedState().clone();
        stateTamper[stateTamper.length - 2] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryProducerEnvelopeV2.finalizeDay(
                        published.fixture().inventory(), published.fixture().day(),
                        published.fixture().v1Producer(), stateTamper, DAY_PARENT_AT));

        byte[] trailingState = (new String(published.acceptedState(), StandardCharsets.UTF_8)
                + "{}").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryProducerEnvelopeV2.finalizeDay(
                        published.fixture().inventory(), published.fixture().day(),
                        published.fixture().v1Producer(), trailingState, DAY_PARENT_AT));

        byte[] duplicateState = "{\"schema_version\":\"a\",\"schema_version\":\"b\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryProducerEnvelopeV2.finalizeDay(
                        published.fixture().inventory(), published.fixture().day(),
                        published.fixture().v1Producer(), duplicateState, DAY_PARENT_AT));

        byte[] parent = OkxDraCryptoCarryProducerEnvelopeV2.finalizeDay(
                published.fixture().inventory(), published.fixture().day(),
                published.fixture().v1Producer(), published.acceptedState(), DAY_PARENT_AT);
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryCanonicalDropV2.createDayDrop(
                        newDayBinding(published.acceptedState(), DEADLINE),
                        published.acceptedState(), published.fixture().inventory(),
                        published.fixture().day(), published.fixture().v1Producer(), parent));
        byte[] tampered = parent.clone();
        tampered[tampered.length - 2] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryCanonicalDropV2.createDayDrop(
                        dayDropBinding(published.acceptedState()), published.acceptedState(),
                        published.fixture().inventory(), published.fixture().day(),
                        published.fixture().v1Producer(), tampered));
    }

    @Test
    void chainAndGenesisAreExactAndNoEconomicOrRuntimeFieldsExist() throws Exception {
        Published published = publishInventory();
        byte[] parent = OkxDraCryptoCarryProducerEnvelopeV2.finalizeDay(
                published.fixture().inventory(), published.fixture().day(),
                published.fixture().v1Producer(), published.acceptedState(), DAY_PARENT_AT);
        OkxDraCryptoCarryCanonicalDropV2.PhaseDocuments genesis =
                OkxDraCryptoCarryCanonicalDropV2.createDayDrop(
                        dayDropBinding(published.acceptedState()), published.acceptedState(),
                        published.fixture().inventory(), published.fixture().day(),
                        published.fixture().v1Producer(), parent);
        JsonNode genesisNode = MAPPER.readTree(genesis.files().get(
                "okx-dra-crypto-carry-2026-08-10.day-drop-v2.json"));
        assertEquals("GENESIS", genesisNode.at("/predecessor/type").textValue());

        String chainHash = "1".repeat(64);
        OkxDraCryptoCarryCanonicalDropV2.DayBinding chainBinding =
                new OkxDraCryptoCarryCanonicalDropV2.DayBinding(
                        TARGET_DAY, sha256(published.acceptedState()), TARGET_DAY.plusDays(2),
                        DEADLINE, DAY_PUBLISHED_AT,
                        OkxDraCryptoCarryCanonicalDropV2.PredecessorType.CHAIN,
                        TARGET_DAY.minusDays(1), chainHash);
        JsonNode chainNode = MAPPER.readTree(
                OkxDraCryptoCarryCanonicalDropV2.createDayDrop(
                        chainBinding, published.acceptedState(), published.fixture().inventory(),
                        published.fixture().day(), published.fixture().v1Producer(), parent)
                        .files().get("okx-dra-crypto-carry-2026-08-10.day-drop-v2.json"));
        assertEquals(chainHash,
                chainNode.at("/predecessor/day_drop_envelope_sha256").textValue());

        Set<String> forbidden = Set.of("basis", "carry", "annualization", "funding",
                "tenor", "maturity_preference", "roll", "liquidity", "threshold",
                "signal", "return", "pnl", "drawdown");
        assertFalse(hasForbiddenField(genesisNode, forbidden));
        assertFalse(hasForbiddenField(MAPPER.readTree(parent), forbidden));
    }

    private Published publishInventory() throws Exception {
        Fixture fixture = fixture();
        Roots roots = roots("published");
        byte[] parent = OkxDraCryptoCarryProducerEnvelopeV2.prepareInventory(
                fixture.inventory(), INVENTORY_PARENT_AT);
        sink(roots, new RecordingForcer()).publish(
                OkxDraCryptoCarryCanonicalDropV2.createInventoryDrop(
                        inventoryDropBinding(fixture), fixture.inventory(), parent));
        byte[] state = OkxDraCryptoCarryNetworkDeniedIntakeV2.acceptInventory(
                new OkxDraCryptoCarryNetworkDeniedIntakeV2.InventoryBinding(
                        TARGET_DAY, INVENTORY_ACCEPTED_AT), roots.inventoryDrop())
                .stateBytes();
        return new Published(fixture, roots, state);
    }

    private static OkxDraCryptoCarryCanonicalDropV2.InventoryBinding inventoryDropBinding(
            Fixture fixture) {
        return new OkxDraCryptoCarryCanonicalDropV2.InventoryBinding(
                TARGET_DAY, INVENTORY_PUBLISHED_AT, sha256(fixture.inventory()));
    }

    private static OkxDraCryptoCarryCanonicalDropV2.DayBinding dayDropBinding(byte[] state) {
        return newDayBinding(state, DAY_PUBLISHED_AT);
    }

    private static OkxDraCryptoCarryCanonicalDropV2.DayBinding newDayBinding(
            byte[] state, Instant publishedAt) {
        return new OkxDraCryptoCarryCanonicalDropV2.DayBinding(
                TARGET_DAY, sha256(state), TARGET_DAY.plusDays(2), DEADLINE, publishedAt,
                OkxDraCryptoCarryCanonicalDropV2.PredecessorType.GENESIS,
                null, "0".repeat(64));
    }

    private static OkxDraCryptoCarryNetworkDeniedIntakeV2.DayBinding dayIntakeBinding(
            byte[] state) {
        return new OkxDraCryptoCarryNetworkDeniedIntakeV2.DayBinding(
                TARGET_DAY, sha256(state), DAY_ACCEPTED_AT,
                OkxDraCryptoCarryNetworkDeniedIntakeV2.PredecessorType.GENESIS,
                null, "0".repeat(64));
    }

    private Roots roots() throws Exception {
        return roots("default");
    }

    private Roots roots(String prefix) throws Exception {
        Path base = Files.createDirectory(temporaryDirectory.resolve(prefix));
        return new Roots(
                Files.createDirectory(base.resolve("dra-crypto-carry-v2-inventory-staging")),
                Files.createDirectory(base.resolve("dra-crypto-carry-v2-inventory-drop")),
                Files.createDirectory(base.resolve("dra-crypto-carry-v2-day-staging")),
                Files.createDirectory(base.resolve("dra-crypto-carry-v2-day-drop")));
    }

    private static OkxDraCryptoCarryCanonicalDropV2.FileDropSink sink(
            Roots roots, RecordingForcer forcer) throws Exception {
        return new OkxDraCryptoCarryCanonicalDropV2.FileDropSink(
                roots.inventoryStaging(), roots.inventoryDrop(), roots.dayStaging(),
                roots.dayDrop(), forcer);
    }

    private static Fixture fixture() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respond(OkxDraCryptoCarryForwardSource.INVENTORY_URI,
                response(ok(fixtureInstruments())));
        fake.respond(candleUri("BTC-USDT-260814"), response(ok(List.of(futuresRow("105.00")))));
        fake.respond(candleUri("BTC-USDT-260821"), response(ok(List.of(futuresRow("106.00")))));
        fake.respond(OkxDraCryptoCarryForwardSource.INDEX_URI,
                response(ok(List.of(indexRow()))));
        OkxDraCryptoCarryForwardSource source = new OkxDraCryptoCarryForwardSource(fake);
        byte[] inventory = source.captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
        byte[] day = source.captureDay(inventory, DAY_CAPTURE);
        byte[] producer = source.createEnvelope(inventory, day, V1_PRODUCER_AT);
        return new Fixture(inventory, day, producer);
    }

    private static List<Map<String, String>> fixtureInstruments() {
        return List.of(
                fixtureInstrument("BTC-USDT-260814", "2026-08-14T08:00:00Z"),
                fixtureInstrument("BTC-USDT-260821", "2026-08-21T08:00:00Z"));
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
                millis("2026-08-10T00:00:00Z"), "100.00", "112.00", "91.00", close,
                "10.0", "1.0", "1000.0", "1"));
    }

    private static List<String> indexRow() {
        return new ArrayList<>(List.of(
                millis("2026-08-10T00:00:00Z"),
                "100.00", "110.00", "90.00", "104.00", "1"));
    }

    private static String millis(String value) {
        return Long.toString(Instant.parse(value).toEpochMilli());
    }

    private static URI candleUri(String instId) {
        return URI.create("https://www.okx.com/api/v5/market/candles?instId="
                + instId + "&bar=1Dutc");
    }

    private static byte[] ok(Object data) throws Exception {
        return MAPPER.writeValueAsBytes(Map.of("code", "0", "msg", "", "data", data));
    }

    private static OkxDraCryptoCarryForwardSource.ResponseSpec response(byte[] body) {
        return new OkxDraCryptoCarryForwardSource.ResponseSpec(
                200, "application/json", body);
    }

    private static String sha256(byte[] bytes) {
        return OkxDraCryptoCarryProducerEnvelopeV2.sha256(bytes);
    }

    private static void assertClosedObjectSchemas(JsonNode node) {
        if (node.isObject()) {
            if (node.has("type") && node.get("type").isTextual()
                    && node.get("type").textValue().equals("object")) {
                assertTrue(node.has("additionalProperties"));
                assertFalse(node.get("additionalProperties").booleanValue());
            }
            node.fields().forEachRemaining(entry -> assertClosedObjectSchemas(entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(OkxDraCryptoCarryTransitivePredecessorRefreezeV2Test::assertClosedObjectSchemas);
        }
    }

    private static boolean hasForbiddenField(JsonNode node, Set<String> forbidden) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (forbidden.contains(field.getKey())
                        || hasForbiddenField(field.getValue(), forbidden)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                if (hasForbiddenField(value, forbidden)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record Fixture(byte[] inventory, byte[] day, byte[] v1Producer) {
    }

    private record Roots(
            Path inventoryStaging, Path inventoryDrop, Path dayStaging, Path dayDrop) {
    }

    private record Published(Fixture fixture, Roots roots, byte[] acceptedState) {
    }

    private static final class RecordingForcer
            implements OkxDraCryptoCarryCanonicalDropV2.FileDropSink.DirectoryForcer {
        private final List<Path> paths = new ArrayList<>();

        @Override
        public void force(Path path) {
            paths.add(path);
        }

        List<Path> paths() {
            return List.copyOf(paths);
        }
    }

    private static final class FakeTransport implements OkxDraCryptoCarryForwardSource.Transport {
        private final Map<URI, OkxDraCryptoCarryForwardSource.ResponseSpec> responses =
                new HashMap<>();

        void respond(URI uri, OkxDraCryptoCarryForwardSource.ResponseSpec response) {
            responses.put(uri, response);
        }

        @Override
        public OkxDraCryptoCarryForwardSource.ResponseSpec execute(
                OkxDraCryptoCarryForwardSource.RequestSpec request) {
            OkxDraCryptoCarryForwardSource.ResponseSpec response = responses.get(request.uri());
            if (response == null) {
                throw new IllegalStateException("NO_FAKE_RESPONSE:" + request.uri());
            }
            return response;
        }
    }
}
