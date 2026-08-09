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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxDraCryptoCarryOfflineTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate TARGET_DAY = LocalDate.parse("2026-08-10");
    private static final LocalDate ELIGIBLE_DAY = LocalDate.parse("2026-08-12");
    private static final Instant INVENTORY_CAPTURE = Instant.parse("2026-08-09T01:05:02Z");
    private static final Instant DAY_CAPTURE = Instant.parse("2026-08-11T01:05:03Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-11T01:05:04Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-11T01:05:05Z");
    private static final Instant DEADLINE = Instant.parse("2026-08-11T06:00:00Z");
    private static final String INVENTORY_SHA256 =
            "cf73154a45d5ba378b6dd159833d46ade8ad3a0ff54023b5a1da2286747b5b01";
    private static final String DAY_SHA256 =
            "bc490099bbd10b3994836a3675b18c9aa0a1312057ab6809a71a9e2991cf9359";
    private static final String PRODUCER_SHA256 =
            "90ee720f8815f65f88f85ff2873fbab26d859340195ff7ea0fca9279ad979ef4";
    private static final String CHAIN_SHA256 = "1".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void schemaIsClosedAndHashBound() throws Exception {
        Path schemaPath = Path.of(
                "research_pipeline/okx-dra-crypto-carry-drop-envelope.v1.schema.json");
        byte[] schemaBytes = Files.readAllBytes(schemaPath);
        assertEquals(
                OkxDraCryptoCarryCanonicalDrop.DROP_ENVELOPE_SCHEMA_SHA256,
                sha256(schemaBytes));
        JsonNode schema = MAPPER.readTree(schemaBytes);
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema").textValue());
        assertClosedObjectSchemas(schema);
        assertEquals(
                "^okx-dra-crypto-carry-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.inventory\\.json$",
                schema.at("/$defs/inventory_file/properties/name/pattern").textValue());
        assertEquals(
                "^okx-dra-crypto-carry-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.drop-envelope\\.json$",
                schema.at("/$defs/drop_envelope_file/properties/name/pattern").textValue());

        Fixture fixture = fixture();
        OkxDraCryptoCarryCanonicalDrop.DropDocuments documents =
                OkxDraCryptoCarryCanonicalDrop.create(
                        genesisBinding(fixture),
                        fixture.inventory(),
                        fixture.day(),
                        fixture.producerEnvelope());
        JsonNode envelope = MAPPER.readTree(documents.dropEnvelopeBytes());
        assertEquals(
                sha256(schemaBytes),
                envelope.get("drop_envelope_schema_sha256").textValue());
        assertEquals(
                Set.of(
                        "inventory", "day", "producer_envelope", "drop_envelope"),
                fieldNames(envelope.get("files")));
        assertFalse(containsForbiddenEconomicField(envelope));
    }

    @Test
    void exactFixtureBytesAreTransportedWithoutReserialization() throws Exception {
        Fixture fixture = fixture();
        assertEquals(1_321, fixture.inventory().length);
        assertEquals(INVENTORY_SHA256, sha256(fixture.inventory()));
        assertEquals(2_006, fixture.day().length);
        assertEquals(DAY_SHA256, sha256(fixture.day()));
        assertEquals(1_161, fixture.producerEnvelope().length);
        assertEquals(PRODUCER_SHA256, sha256(fixture.producerEnvelope()));

        OkxDraCryptoCarryCanonicalDrop.DropDocuments documents =
                OkxDraCryptoCarryCanonicalDrop.create(
                        genesisBinding(fixture),
                        fixture.inventory(),
                        fixture.day(),
                        fixture.producerEnvelope());
        assertArrayEquals(fixture.inventory(), documents.inventoryBytes());
        assertArrayEquals(fixture.day(), documents.dayBytes());
        assertArrayEquals(fixture.producerEnvelope(), documents.producerEnvelopeBytes());
        JsonNode envelope = MAPPER.readTree(documents.dropEnvelopeBytes());
        assertEquals(INVENTORY_SHA256, envelope.at("/files/inventory/sha256").textValue());
        assertEquals(DAY_SHA256, envelope.at("/files/day/sha256").textValue());
        assertEquals(PRODUCER_SHA256, envelope.at("/files/producer_envelope/sha256").textValue());
        assertEquals(documents.dropEnvelopeBytes().length,
                envelope.at("/files/drop_envelope/size_bytes").intValue());
    }

    @Test
    void genesisAndChainBindingsAreExactAndMismatchFailsClosed() throws Exception {
        Fixture fixture = fixture();
        OkxDraCryptoCarryCanonicalDrop.DropDocuments genesis =
                OkxDraCryptoCarryCanonicalDrop.create(
                        genesisBinding(fixture), fixture.inventory(), fixture.day(),
                        fixture.producerEnvelope());
        JsonNode genesisEnvelope = MAPPER.readTree(genesis.dropEnvelopeBytes());
        assertEquals("GENESIS", genesisEnvelope.at("/predecessor/type").textValue());
        assertTrue(genesisEnvelope.at("/predecessor/day").isNull());
        assertEquals("0".repeat(64),
                genesisEnvelope.at("/predecessor/drop_envelope_sha256").textValue());

        OkxDraCryptoCarryCanonicalDrop.TransportBinding chainBinding =
                chainBinding(fixture, CHAIN_SHA256);
        OkxDraCryptoCarryCanonicalDrop.DropDocuments chain =
                OkxDraCryptoCarryCanonicalDrop.create(
                        chainBinding, fixture.inventory(), fixture.day(),
                        fixture.producerEnvelope());
        JsonNode chainEnvelope = MAPPER.readTree(chain.dropEnvelopeBytes());
        assertEquals("CHAIN", chainEnvelope.at("/predecessor/type").textValue());
        assertEquals("2026-08-09", chainEnvelope.at("/predecessor/day").textValue());
        assertEquals(CHAIN_SHA256,
                chainEnvelope.at("/predecessor/drop_envelope_sha256").textValue());

        assertThrows(IllegalArgumentException.class, () ->
                OkxDraCryptoCarryCanonicalDrop.create(
                        new OkxDraCryptoCarryCanonicalDrop.TransportBinding(
                                TARGET_DAY,
                                OkxDraCryptoCarryCanonicalDrop.PredecessorType.GENESIS,
                                null,
                                CHAIN_SHA256,
                                ELIGIBLE_DAY,
                                DEADLINE,
                                PUBLISHED_AT,
                                PRODUCER_SHA256),
                        fixture.inventory(), fixture.day(), fixture.producerEnvelope()));
        assertThrows(IllegalArgumentException.class, () ->
                OkxDraCryptoCarryCanonicalDrop.create(
                        new OkxDraCryptoCarryCanonicalDrop.TransportBinding(
                                TARGET_DAY,
                                OkxDraCryptoCarryCanonicalDrop.PredecessorType.CHAIN,
                                TARGET_DAY.minusDays(2),
                                CHAIN_SHA256,
                                ELIGIBLE_DAY,
                                DEADLINE,
                                PUBLISHED_AT,
                                PRODUCER_SHA256),
                        fixture.inventory(), fixture.day(), fixture.producerEnvelope()));
    }

    @Test
    void createOnceAtomicLayoutAndIndependentIntakePass() throws Exception {
        Fixture fixture = fixture();
        Roots roots = roots("valid");
        OkxDraCryptoCarryCanonicalDrop.DropDocuments documents = publish(
                roots, genesisBinding(fixture), fixture);

        Path dayDirectory = roots.drop().resolve(TARGET_DAY.toString());
        assertTrue(Files.isDirectory(dayDirectory));
        assertTrue(Files.isRegularFile(roots.drop().resolve(".2026-08-10.publish-reserved")));
        assertEquals(0L, Files.size(roots.drop().resolve(".2026-08-10.publish-reserved")));
        assertEquals(
                Set.of(
                        documents.inventoryName(),
                        documents.dayName(),
                        documents.producerEnvelopeName(),
                        documents.dropEnvelopeName()),
                childNames(dayDirectory));
        assertArrayEquals(fixture.inventory(), Files.readAllBytes(
                dayDirectory.resolve(documents.inventoryName())));
        assertArrayEquals(fixture.day(), Files.readAllBytes(
                dayDirectory.resolve(documents.dayName())));
        assertArrayEquals(fixture.producerEnvelope(), Files.readAllBytes(
                dayDirectory.resolve(documents.producerEnvelopeName())));

        OkxDraCryptoCarryNetworkDeniedIntake.ValidationResult result =
                OkxDraCryptoCarryNetworkDeniedIntake.validate(
                        intakeGenesisBinding(fixture), roots.drop());
        assertEquals(OkxDraCryptoCarryNetworkDeniedIntake.VALID_STATUS, result.status());
        assertEquals(TARGET_DAY, result.targetDay());
        assertEquals(ELIGIBLE_DAY, result.firstEligibleUtcDecisionDay());
        assertEquals(4, result.fileSha256().size());
        assertThrows(IllegalArgumentException.class, () ->
                new OkxDraCryptoCarryCanonicalDrop.FileDropSink(
                        roots.staging(), roots.drop()).publish(documents));
    }

    @Test
    void existingTargetReservationStagingAndBroadRootsFailBeforeWrite() throws Exception {
        Fixture fixture = fixture();
        OkxDraCryptoCarryCanonicalDrop.DropDocuments documents =
                OkxDraCryptoCarryCanonicalDrop.create(
                        genesisBinding(fixture), fixture.inventory(), fixture.day(),
                        fixture.producerEnvelope());

        Roots targetConflict = roots("target-conflict");
        Files.createDirectory(targetConflict.drop().resolve(TARGET_DAY.toString()));
        assertThrows(IllegalArgumentException.class, () ->
                new OkxDraCryptoCarryCanonicalDrop.FileDropSink(
                        targetConflict.staging(), targetConflict.drop()).publish(documents));

        Roots reservationConflict = roots("reservation-conflict");
        Files.createFile(reservationConflict.drop().resolve(".2026-08-10.publish-reserved"));
        assertThrows(IllegalArgumentException.class, () ->
                new OkxDraCryptoCarryCanonicalDrop.FileDropSink(
                        reservationConflict.staging(), reservationConflict.drop())
                        .publish(documents));

        Roots stagingConflict = roots("staging-conflict");
        Files.createDirectory(stagingConflict.staging().resolve(TARGET_DAY.toString()));
        assertThrows(IllegalArgumentException.class, () ->
                new OkxDraCryptoCarryCanonicalDrop.FileDropSink(
                        stagingConflict.staging(), stagingConflict.drop()).publish(documents));

        Roots forbiddenPath = roots("microstructure");
        assertThrows(IllegalArgumentException.class, () ->
                new OkxDraCryptoCarryCanonicalDrop.FileDropSink(
                        forbiddenPath.staging(), forbiddenPath.drop()).publish(documents));

        Path broadStaging = temporaryDirectory.resolve("staging");
        Path broadDrop = temporaryDirectory.resolve("drop");
        Files.createDirectory(broadStaging);
        Files.createDirectory(broadDrop);
        assertThrows(IllegalArgumentException.class, () ->
                new OkxDraCryptoCarryCanonicalDrop.FileDropSink(broadStaging, broadDrop)
                        .publish(documents));
        assertThrows(IllegalArgumentException.class, () ->
                new OkxDraCryptoCarryCanonicalDrop.FileDropSink(
                        targetConflict.drop(), targetConflict.drop()).publish(documents));
    }

    @Test
    void partialExtraAndConflictingIntakeBindingsFailClosed() throws Exception {
        Fixture fixture = fixture();
        Roots partial = roots("partial");
        Path partialDay = partial.drop().resolve(TARGET_DAY.toString());
        Files.createDirectory(partialDay);
        Files.createFile(partial.drop().resolve(".2026-08-10.publish-reserved"));
        Files.write(partialDay.resolve("okx-dra-crypto-carry-2026-08-10.inventory.json"),
                fixture.inventory());
        assertThrows(IllegalArgumentException.class, () ->
                OkxDraCryptoCarryNetworkDeniedIntake.validate(
                        intakeGenesisBinding(fixture), partial.drop()));

        Roots extra = roots("extra");
        publish(extra, genesisBinding(fixture), fixture);
        Files.writeString(
                extra.drop().resolve(TARGET_DAY.toString()).resolve("unexpected.json"),
                "{}",
                StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
                OkxDraCryptoCarryNetworkDeniedIntake.validate(
                        intakeGenesisBinding(fixture), extra.drop()));

        Roots conflict = roots("binding-conflict");
        publish(conflict, chainBinding(fixture, CHAIN_SHA256), fixture);
        OkxDraCryptoCarryNetworkDeniedIntake.IntakeBinding wrong =
                intakeChainBinding(fixture, "2".repeat(64));
        assertThrows(IllegalArgumentException.class, () ->
                OkxDraCryptoCarryNetworkDeniedIntake.validate(wrong, conflict.drop()));
    }

    @Test
    void symlinkOrReparseEntryIsRejectedWithNoFollowBoundary() throws Exception {
        Fixture fixture = fixture();
        Roots roots = roots("symlink");
        OkxDraCryptoCarryCanonicalDrop.DropDocuments documents = publish(
                roots, genesisBinding(fixture), fixture);
        Path dayDirectory = roots.drop().resolve(TARGET_DAY.toString());
        Path inventory = dayDirectory.resolve(documents.inventoryName());
        Path outside = temporaryDirectory.resolve("outside-inventory.json");
        Files.write(outside, fixture.inventory());
        Files.delete(inventory);
        boolean linked = false;
        try {
            Files.createSymbolicLink(inventory, outside);
            linked = true;
        } catch (UnsupportedOperationException | java.io.IOException denied) {
            String intakeSource = Files.readString(Path.of(
                    "src/main/java/com/agora/research/OkxDraCryptoCarryNetworkDeniedIntake.java"));
            assertTrue(intakeSource.contains("LinkOption.NOFOLLOW_LINKS"));
            assertTrue(intakeSource.contains("attributes.isSymbolicLink()"));
            assertTrue(intakeSource.contains("attributes.isOther()"));
        }
        if (linked) {
            assertThrows(IllegalArgumentException.class, () ->
                    OkxDraCryptoCarryNetworkDeniedIntake.validate(
                            intakeGenesisBinding(fixture), roots.drop()));
        }
    }

    @Test
    void byteTamperAndNoncanonicalMutationAreRejectedIndependently() throws Exception {
        Fixture fixture = fixture();
        Roots byteTamper = roots("byte-tamper");
        OkxDraCryptoCarryCanonicalDrop.DropDocuments documents = publish(
                byteTamper, genesisBinding(fixture), fixture);
        Path dayFile = byteTamper.drop().resolve(TARGET_DAY.toString())
                .resolve(documents.dayName());
        byte[] changed = Files.readAllBytes(dayFile);
        changed[changed.length - 1] ^= 1;
        Files.write(dayFile, changed);
        assertThrows(IllegalArgumentException.class, () ->
                OkxDraCryptoCarryNetworkDeniedIntake.validate(
                        intakeGenesisBinding(fixture), byteTamper.drop()));

        Roots noncanonical = roots("noncanonical");
        OkxDraCryptoCarryCanonicalDrop.DropDocuments second = publish(
                noncanonical, genesisBinding(fixture), fixture);
        Path inventoryFile = noncanonical.drop().resolve(TARGET_DAY.toString())
                .resolve(second.inventoryName());
        byte[] whitespace = (" " + new String(fixture.inventory(), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        Files.write(inventoryFile, whitespace);
        assertThrows(IllegalArgumentException.class, () ->
                OkxDraCryptoCarryNetworkDeniedIntake.validate(
                        intakeGenesisBinding(fixture), noncanonical.drop()));

        Roots dropSealTamper = roots("drop-seal-tamper");
        OkxDraCryptoCarryCanonicalDrop.DropDocuments third = publish(
                dropSealTamper, genesisBinding(fixture), fixture);
        Path dropFile = dropSealTamper.drop().resolve(TARGET_DAY.toString())
                .resolve(third.dropEnvelopeName());
        JsonNode changedDrop = MAPPER.readTree(Files.readAllBytes(dropFile));
        ((com.fasterxml.jackson.databind.node.ObjectNode) changedDrop.get("envelope_seal"))
                .put("payload_sha256", "f".repeat(64));
        Files.write(dropFile, MAPPER.writeValueAsBytes(changedDrop));
        assertThrows(IllegalArgumentException.class, () ->
                OkxDraCryptoCarryNetworkDeniedIntake.validate(
                        intakeGenesisBinding(fixture), dropSealTamper.drop()));
    }

    @Test
    void clockDeadlineTargetAndEligibilityDriftFailBeforePublication() throws Exception {
        Fixture fixture = fixture();
        List<OkxDraCryptoCarryCanonicalDrop.TransportBinding> invalid = List.of(
                new OkxDraCryptoCarryCanonicalDrop.TransportBinding(
                        TARGET_DAY,
                        OkxDraCryptoCarryCanonicalDrop.PredecessorType.GENESIS,
                        null,
                        "0".repeat(64),
                        TARGET_DAY.plusDays(3),
                        DEADLINE,
                        PUBLISHED_AT,
                        PRODUCER_SHA256),
                new OkxDraCryptoCarryCanonicalDrop.TransportBinding(
                        TARGET_DAY,
                        OkxDraCryptoCarryCanonicalDrop.PredecessorType.GENESIS,
                        null,
                        "0".repeat(64),
                        ELIGIBLE_DAY,
                        DEADLINE.plusSeconds(1),
                        PUBLISHED_AT,
                        PRODUCER_SHA256),
                new OkxDraCryptoCarryCanonicalDrop.TransportBinding(
                        TARGET_DAY,
                        OkxDraCryptoCarryCanonicalDrop.PredecessorType.GENESIS,
                        null,
                        "0".repeat(64),
                        ELIGIBLE_DAY,
                        DEADLINE,
                        DEADLINE,
                        PRODUCER_SHA256),
                new OkxDraCryptoCarryCanonicalDrop.TransportBinding(
                        TARGET_DAY.plusDays(1),
                        OkxDraCryptoCarryCanonicalDrop.PredecessorType.GENESIS,
                        null,
                        "0".repeat(64),
                        TARGET_DAY.plusDays(3),
                        Instant.parse("2026-08-12T06:00:00Z"),
                        Instant.parse("2026-08-12T01:05:05Z"),
                        PRODUCER_SHA256));
        for (OkxDraCryptoCarryCanonicalDrop.TransportBinding binding : invalid) {
            assertThrows(IllegalArgumentException.class, () ->
                    OkxDraCryptoCarryCanonicalDrop.create(
                            binding, fixture.inventory(), fixture.day(),
                            fixture.producerEnvelope()));
        }
    }

    @Test
    void producerEnvelopeAndPredecessorHashDriftAreRejected() throws Exception {
        Fixture fixture = fixture();
        byte[] changedProducer = fixture.producerEnvelope();
        changedProducer[changedProducer.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () ->
                OkxDraCryptoCarryCanonicalDrop.create(
                        genesisBinding(fixture), fixture.inventory(), fixture.day(),
                        changedProducer));
        assertThrows(IllegalArgumentException.class, () ->
                OkxDraCryptoCarryCanonicalDrop.create(
                        new OkxDraCryptoCarryCanonicalDrop.TransportBinding(
                                TARGET_DAY,
                                OkxDraCryptoCarryCanonicalDrop.PredecessorType.CHAIN,
                                TARGET_DAY.minusDays(1),
                                "0".repeat(64),
                                ELIGIBLE_DAY,
                                DEADLINE,
                                PUBLISHED_AT,
                                PRODUCER_SHA256),
                        fixture.inventory(), fixture.day(), fixture.producerEnvelope()));
    }

    @Test
    void publisherHasNoPublishedReadBackAndLibrariesExposeNoRuntimeSurface() throws Exception {
        String dropSource = Files.readString(Path.of(
                "src/main/java/com/agora/research/OkxDraCryptoCarryCanonicalDrop.java"));
        String intakeSource = Files.readString(Path.of(
                "src/main/java/com/agora/research/OkxDraCryptoCarryNetworkDeniedIntake.java"));
        for (String source : List.of(dropSource, intakeSource)) {
            assertFalse(source.contains("public static void main"));
            assertFalse(source.contains("org.springframework"));
            assertFalse(source.contains("System.getenv"));
            assertFalse(source.contains("System.getProperty"));
            assertFalse(source.contains("ProcessBuilder"));
            assertFalse(source.contains("Runtime.getRuntime"));
            assertFalse(source.contains("Class.forName"));
            assertFalse(source.contains("java.sql"));
            assertFalse(source.contains("java.net."));
            assertFalse(source.contains("java.net.http"));
            assertFalse(source.contains("Socket"));
        }
        int move = dropSource.indexOf("Files.move(stagedDay, targetDay");
        assertTrue(move > 0);
        int oneWayComment = dropSource.indexOf("// One-way boundary", move);
        assertTrue(oneWayComment > move);
        String afterMove = dropSource.substring(move, oneWayComment);
        assertFalse(afterMove.contains("Files.read"));
        assertFalse(afterMove.contains("Files.size"));
        assertFalse(afterMove.contains("Files.list"));
        assertNotNull(OkxDraCryptoCarryCanonicalDrop.FileDropSink.class);
        assertNotNull(OkxDraCryptoCarryNetworkDeniedIntake.ValidationResult.class);
    }

    private OkxDraCryptoCarryCanonicalDrop.DropDocuments publish(
            Roots roots,
            OkxDraCryptoCarryCanonicalDrop.TransportBinding binding,
            Fixture fixture) throws Exception {
        OkxDraCryptoCarryCanonicalDrop.DropDocuments documents =
                OkxDraCryptoCarryCanonicalDrop.create(
                        binding, fixture.inventory(), fixture.day(),
                        fixture.producerEnvelope());
        RecordingDirectoryForcer directoryForcer = new RecordingDirectoryForcer();
        new OkxDraCryptoCarryCanonicalDrop.FileDropSink(
                roots.staging(), roots.drop(), directoryForcer).publish(documents);
        assertEquals(
                List.of(
                        roots.staging().resolve(TARGET_DAY.toString()),
                        roots.drop(),
                        roots.drop()),
                directoryForcer.forcedDirectories());
        return documents;
    }

    private Roots roots(String label) throws Exception {
        Path parent = Files.createDirectory(temporaryDirectory.resolve(label));
        Path staging = Files.createDirectory(parent.resolve("dra-crypto-carry-staging"));
        Path drop = Files.createDirectory(parent.resolve("dra-crypto-carry-drop"));
        return new Roots(staging, drop);
    }

    private static Fixture fixture() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respond(
                OkxDraCryptoCarryForwardSource.INVENTORY_URI,
                response(ok(fixtureInstruments())));
        fake.respond(candleUri("BTC-USDT-260814"),
                response(ok(List.of(futuresRow("105.00")))));
        fake.respond(candleUri("BTC-USDT-260821"),
                response(ok(List.of(futuresRow("106.00")))));
        fake.respond(OkxDraCryptoCarryForwardSource.INDEX_URI,
                response(ok(List.of(indexRow()))));
        OkxDraCryptoCarryForwardSource source = new OkxDraCryptoCarryForwardSource(fake);
        byte[] inventory = source.captureInventory(TARGET_DAY, INVENTORY_CAPTURE);
        byte[] day = source.captureDay(inventory, DAY_CAPTURE);
        byte[] producer = source.createEnvelope(inventory, day, GENERATED_AT);
        return new Fixture(inventory, day, producer);
    }

    private static OkxDraCryptoCarryCanonicalDrop.TransportBinding genesisBinding(
            Fixture fixture) {
        return new OkxDraCryptoCarryCanonicalDrop.TransportBinding(
                TARGET_DAY,
                OkxDraCryptoCarryCanonicalDrop.PredecessorType.GENESIS,
                null,
                "0".repeat(64),
                ELIGIBLE_DAY,
                DEADLINE,
                PUBLISHED_AT,
                sha256(fixture.producerEnvelope()));
    }

    private static OkxDraCryptoCarryCanonicalDrop.TransportBinding chainBinding(
            Fixture fixture, String predecessorSha256) {
        return new OkxDraCryptoCarryCanonicalDrop.TransportBinding(
                TARGET_DAY,
                OkxDraCryptoCarryCanonicalDrop.PredecessorType.CHAIN,
                TARGET_DAY.minusDays(1),
                predecessorSha256,
                ELIGIBLE_DAY,
                DEADLINE,
                PUBLISHED_AT,
                sha256(fixture.producerEnvelope()));
    }

    private static OkxDraCryptoCarryNetworkDeniedIntake.IntakeBinding intakeGenesisBinding(
            Fixture fixture) {
        return new OkxDraCryptoCarryNetworkDeniedIntake.IntakeBinding(
                TARGET_DAY,
                OkxDraCryptoCarryNetworkDeniedIntake.PredecessorType.GENESIS,
                null,
                "0".repeat(64),
                ELIGIBLE_DAY,
                DEADLINE,
                PUBLISHED_AT,
                sha256(fixture.producerEnvelope()));
    }

    private static OkxDraCryptoCarryNetworkDeniedIntake.IntakeBinding intakeChainBinding(
            Fixture fixture, String predecessorSha256) {
        return new OkxDraCryptoCarryNetworkDeniedIntake.IntakeBinding(
                TARGET_DAY,
                OkxDraCryptoCarryNetworkDeniedIntake.PredecessorType.CHAIN,
                TARGET_DAY.minusDays(1),
                predecessorSha256,
                ELIGIBLE_DAY,
                DEADLINE,
                PUBLISHED_AT,
                sha256(fixture.producerEnvelope()));
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
        return List.of(
                millis("2026-08-10T00:00:00Z"),
                "100.00", "112.00", "91.00", close,
                "10.0", "1.0", "1000.0", "1");
    }

    private static List<String> indexRow() {
        return List.of(
                millis("2026-08-10T00:00:00Z"),
                "100.00", "110.00", "90.00", "104.00", "1");
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
        return OkxDraCryptoCarryForwardSource.sha256(bytes);
    }

    private static Set<String> childNames(Path directory) throws Exception {
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            Set<String> names = new HashSet<>();
            stream.forEach(path -> names.add(path.getFileName().toString()));
            return names;
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertClosedObjectSchemas(JsonNode node) {
        if (node.isObject()) {
            if (node.has("type") && "object".equals(node.get("type").asText())) {
                assertTrue(node.has("additionalProperties"));
                assertFalse(node.get("additionalProperties").asBoolean(true));
            }
            node.fields().forEachRemaining(entry -> assertClosedObjectSchemas(entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(OkxDraCryptoCarryOfflineTransportTest::assertClosedObjectSchemas);
        }
    }

    private static boolean containsForbiddenEconomicField(JsonNode node) {
        Set<String> forbidden = Set.of(
                "basis", "carry", "annualization", "funding", "tenor",
                "maturity_preference", "roll", "liquidity", "threshold", "signal",
                "return", "pnl", "drawdown");
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (forbidden.contains(field.getKey())
                        || containsForbiddenEconomicField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                if (containsForbiddenEconomicField(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record Fixture(byte[] inventory, byte[] day, byte[] producerEnvelope) {
        Fixture {
            inventory = inventory.clone();
            day = day.clone();
            producerEnvelope = producerEnvelope.clone();
        }

        @Override
        public byte[] inventory() {
            return inventory.clone();
        }

        @Override
        public byte[] day() {
            return day.clone();
        }

        @Override
        public byte[] producerEnvelope() {
            return producerEnvelope.clone();
        }
    }

    private record Roots(Path staging, Path drop) {
    }

    private static final class RecordingDirectoryForcer
            implements OkxDraCryptoCarryCanonicalDrop.FileDropSink.DirectoryForcer {
        private final List<Path> forcedDirectories = new ArrayList<>();

        @Override
        public void force(Path directory) {
            forcedDirectories.add(directory.toAbsolutePath().normalize());
        }

        List<Path> forcedDirectories() {
            return List.copyOf(forcedDirectories);
        }
    }

    private static final class FakeTransport
            implements OkxDraCryptoCarryForwardSource.Transport {
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
