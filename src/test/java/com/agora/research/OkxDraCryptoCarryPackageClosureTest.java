package com.agora.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxDraCryptoCarryPackageClosureTest {

    private static final LocalDate TARGET_DAY = LocalDate.parse("2026-08-10");
    private static final Instant PREPARE_AT = Instant.parse("2026-08-09T01:05:00Z");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-09T01:06:00Z");
    private static final Instant FINALIZE_AT = Instant.parse("2026-08-11T01:05:00Z");
    private static final String MANIFEST_SHA = "a".repeat(64);
    private static final String CHAIN_SHA = "b".repeat(64);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    @TempDir
    Path temporaryDirectory;

    @Test
    void profileAndEntrypointAreStaticallyNarrow() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        String profile = profile(pom, "dra-crypto-carry-research-dist");
        assertTrue(profile.contains(
                "<directory>${project.build.directory}/dra-crypto-carry-dist</directory>"));
        assertTrue(profile.contains(
                "<outputDirectory>${project.build.directory}/dra-crypto-carry-dist</outputDirectory>"));
        assertTrue(profile.contains(
                "<outputDirectory>${project.build.directory}/dra-crypto-carry-dist/lib</outputDirectory>"));
        assertTrue(profile.contains("<classifier>dra-crypto-carry-research</classifier>"));
        assertTrue(profile.contains("<excludeDefaultDirectories>true</excludeDefaultDirectories>"));
        assertFalse(profile.contains("microstructure-dist"));
        assertFalse(profile.contains("${project.build.directory}/classes"));
        assertFalse(profile.contains("<dependency>"));
        assertFalse(profile.contains("OkxDraCryptoCarry*.class"));
        assertTrue(profile.contains(
                "<includeGroupIds>com.fasterxml.jackson.core</includeGroupIds>"));
        assertTrue(profile.contains(
                "<includeArtifactIds>jackson-annotations,jackson-core,jackson-databind</includeArtifactIds>"));

        Set<String> expectedIncludes = new HashSet<>();
        for (String family : List.of(
                "OkxDraCryptoCarryForwardSource",
                "OkxDraCryptoCarryProducerEnvelopeV2",
                "OkxDraCryptoCarryCanonicalDropV2",
                "OkxDraCryptoCarryNetworkDeniedIntakeV2",
                "OkxDraCryptoCarryPhaseCli")) {
            expectedIncludes.add("com/agora/research/" + family + ".class");
            expectedIncludes.add("com/agora/research/" + family + "$*.class");
        }
        Matcher matcher = Pattern.compile("<include>([^<]+)</include>").matcher(profile);
        Set<String> actualIncludes = new HashSet<>();
        while (matcher.find()) {
            actualIncludes.add(matcher.group(1));
        }
        assertEquals(expectedIncludes, actualIncludes);

        assertTrue(pom.contains(
                "<mainClass>com.agora.trading.TradingApiApplication</mainClass>"));
        assertEquals(1, occurrences(pom, "<id>microstructure-research-dist</id>"));
        assertEquals(1, occurrences(pom, "<id>dra-crypto-carry-research-dist</id>"));

        String cli = Files.readString(Path.of(
                "src/main/java/com/agora/research/OkxDraCryptoCarryPhaseCli.java"));
        for (String fixed : List.of(
                "/etc/agora-research/okx-dra-crypto-carry-source-v2.json",
                "/var/lib/agora-research/dra-crypto-carry-source-request-v2",
                "/var/lib/agora-dra-carry-source/dra-crypto-carry-v2-private",
                "/var/lib/agora-dra-carry-source/dra-crypto-carry-v2-inventory-staging",
                "/var/lib/agora-dra-carry-source/dra-crypto-carry-v2-inventory-drop",
                "/var/lib/agora-dra-carry-source/dra-crypto-carry-v2-day-staging",
                "/var/lib/agora-dra-carry-source/dra-crypto-carry-v2-day-drop")) {
            assertTrue(cli.contains(fixed));
        }
        for (String forbidden : List.of(
                "System.getenv", "ProcessBuilder", "java.sql", "org.springframework",
                ".research-state", "microstructure-drop", "candle", "retry(")) {
            assertFalse(cli.contains(forbidden), forbidden);
        }
        assertTrue(cli.contains("Clock.systemUTC()"));
        assertTrue(cli.contains("FixedHttpClientTransport"));
        assertTrue(cli.contains("CREATE_NEW"));
        assertTrue(cli.contains("SYMLINK_OR_REPARSE_REJECT"));
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.requireNoArguments(
                        new String[]{"caller-path"}));
    }

    @Test
    void canonicalBindingAndRequestsFailClosed() throws Exception {
        byte[] bindingBytes = canonicalLf(binding());
        OkxDraCryptoCarryPhaseCli.SourceBinding binding =
                OkxDraCryptoCarryPhaseCli.SourceBinding.parse(bindingBytes);
        assertEquals("carry-release-v2", binding.producerReleaseId());
        assertEquals(MANIFEST_SHA, binding.producerManifestSha256());

        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceBinding.parse(
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(binding())));
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceBinding.parse(
                        append(bindingBytes, "{}".getBytes(StandardCharsets.UTF_8))));
        Map<String, Object> extraBinding = new LinkedHashMap<>(binding());
        extraBinding.put("extra", "reject");
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceBinding.parse(
                        canonicalLf(extraBinding)));
        String duplicateBinding = new String(bindingBytes, StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\"authorization\":\""
                        + OkxDraCryptoCarryProducerEnvelopeV2.AUTHORIZATION + "\",");
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceBinding.parse(
                        duplicateBinding.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> wrongIdentityBinding = new LinkedHashMap<>(binding());
        wrongIdentityBinding.put("source_identity", "agora-evidence-source");
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceBinding.parse(
                        canonicalLf(wrongIdentityBinding)));
        Map<String, Object> zeroManifestBinding = new LinkedHashMap<>(binding());
        zeroManifestBinding.put("producer_manifest_sha256", "0".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceBinding.parse(
                        canonicalLf(zeroManifestBinding)));

        OkxDraCryptoCarryPhaseCli.SourceRequest prepare =
                OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        canonicalLf(prepareRequest()), binding, PREPARE_AT);
        assertEquals(OkxDraCryptoCarryPhaseCli.Operation.PREPARE_INVENTORY,
                prepare.operation());
        assertEquals(TARGET_DAY, prepare.targetDay());
        assertEquals(TARGET_DAY.minusDays(1), PREPARE_AT.atZone(ZoneOffset.UTC).toLocalDate());

        byte[] prepareBytes = canonicalLf(prepareRequest());
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        MAPPER.writerWithDefaultPrettyPrinter()
                                .writeValueAsBytes(prepareRequest()), binding, PREPARE_AT));
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        append(prepareBytes, "{}".getBytes(StandardCharsets.UTF_8)),
                        binding, PREPARE_AT));
        Map<String, Object> extraRequest = new LinkedHashMap<>(prepareRequest());
        extraRequest.put("path", "/caller/selected");
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        canonicalLf(extraRequest), binding, PREPARE_AT));
        String duplicateRequest = new String(prepareBytes, StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\"operation\":\"PREPARE_INVENTORY\",");
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        duplicateRequest.getBytes(StandardCharsets.UTF_8),
                        binding, PREPARE_AT));

        OkxDraCryptoCarryPhaseCli.SourceRequest genesis =
                OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        canonicalLf(finalizeRequest("c".repeat(64), "d".repeat(64),
                                genesis())), binding, FINALIZE_AT);
        assertEquals(OkxDraCryptoCarryPhaseCli.PredecessorType.GENESIS,
                genesis.predecessor().type());
        assertEquals(TARGET_DAY.plusDays(1),
                FINALIZE_AT.atZone(ZoneOffset.UTC).toLocalDate());
        assertEquals(TARGET_DAY.plusDays(2), genesis.targetDay().plusDays(2));

        OkxDraCryptoCarryPhaseCli.SourceRequest chain =
                OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        canonicalLf(finalizeRequest("c".repeat(64), "d".repeat(64),
                                chain())), binding, FINALIZE_AT);
        assertEquals(OkxDraCryptoCarryPhaseCli.PredecessorType.CHAIN,
                chain.predecessor().type());
        assertEquals(TARGET_DAY.minusDays(1), chain.predecessor().day());

        Map<String, Object> wrongHash = new LinkedHashMap<>(
                finalizeRequest("0".repeat(64), "d".repeat(64), genesis()));
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        canonicalLf(wrongHash), binding, FINALIZE_AT));
        Map<String, Object> wrongContract = new LinkedHashMap<>(prepareRequest());
        wrongContract.put("source_contract_sha256", "e".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        canonicalLf(wrongContract), binding, PREPARE_AT));
        Map<String, Object> wrongClock = new LinkedHashMap<>(prepareRequest());
        wrongClock.put("requested_at", PREPARE_AT.plusSeconds(1).toString());
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        canonicalLf(wrongClock), binding, PREPARE_AT));
        Map<String, Object> wrongChain = new LinkedHashMap<>(
                finalizeRequest("c".repeat(64), "d".repeat(64), chain()));
        @SuppressWarnings("unchecked")
        Map<String, Object> predecessor = new LinkedHashMap<>(
                (Map<String, Object>) wrongChain.get("predecessor"));
        predecessor.put("day", TARGET_DAY.minusDays(2).toString());
        wrongChain.put("predecessor", predecessor);
        assertThrows(IllegalArgumentException.class,
                () -> OkxDraCryptoCarryPhaseCli.SourceRequest.parse(
                        canonicalLf(wrongChain), binding, FINALIZE_AT));
    }

    @Test
    void prepareThenFinalizeUsesExactRetainedStateAndFixedRoots() throws Exception {
        OkxDraCryptoCarryPhaseCli.FixedLayout layout = initializeLayout(temporaryDirectory);
        FakeTransport fake = fixtureTransport();
        writeRequest(layout, prepareRequest());

        OkxDraCryptoCarryPhaseCli.RunResult prepared =
                OkxDraCryptoCarryPhaseCli.runForTest(
                        Clock.fixed(PREPARE_AT, ZoneOffset.UTC), fake, temporaryDirectory);
        assertEquals(OkxDraCryptoCarryPhaseCli.Operation.PREPARE_INVENTORY,
                prepared.operation());
        Path retained = layout.privateInventory(TARGET_DAY);
        byte[] inventory = Files.readAllBytes(retained);
        assertEquals(sha256(inventory), prepared.inventorySha256());
        assertTrue(Files.isDirectory(layout.inventoryDropRoot().resolve(
                TARGET_DAY + ".inventory-v2")));
        assertTrue(Files.isRegularFile(layout.inventoryDropRoot().resolve(
                "." + TARGET_DAY + ".inventory-v2.publish-reserved")));

        OkxDraCryptoCarryNetworkDeniedIntakeV2.IntakeResult accepted =
                OkxDraCryptoCarryNetworkDeniedIntakeV2.acceptInventory(
                        new OkxDraCryptoCarryNetworkDeniedIntakeV2.InventoryBinding(
                                TARGET_DAY, ACCEPTED_AT),
                        layout.inventoryDropRoot());
        byte[] state = accepted.stateBytes();
        Files.write(layout.acceptedStatePath(), state,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.write(layout.requestRoot().resolve("request.json"),
                canonicalLf(finalizeRequest(sha256(inventory), sha256(state), genesis())),
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        OkxDraCryptoCarryPhaseCli.RunResult finalized =
                OkxDraCryptoCarryPhaseCli.runForTest(
                        Clock.fixed(FINALIZE_AT, ZoneOffset.UTC), fake, temporaryDirectory);
        assertEquals(OkxDraCryptoCarryPhaseCli.Operation.FINALIZE_DAY,
                finalized.operation());
        assertEquals(sha256(inventory), finalized.inventorySha256());
        assertArrayEquals(inventory, Files.readAllBytes(retained));
        Path published = layout.dayDropRoot().resolve(TARGET_DAY + ".day-v2");
        assertTrue(Files.isDirectory(published));
        assertTrue(Files.isRegularFile(layout.dayDropRoot().resolve(
                "." + TARGET_DAY + ".day-v2.publish-reserved")));

        String prefix = "okx-dra-crypto-carry-" + TARGET_DAY;
        byte[] parent = Files.readAllBytes(published.resolve(prefix + ".day-parent-v2.json"));
        byte[] day = Files.readAllBytes(published.resolve(prefix + ".day.json"));
        byte[] producer = Files.readAllBytes(
                published.resolve(prefix + ".producer-envelope.json"));
        OkxDraCryptoCarryProducerEnvelopeV2.ParsedParent parsed =
                OkxDraCryptoCarryProducerEnvelopeV2.validate(
                        parent, OkxDraCryptoCarryProducerEnvelopeV2.Phase.DAY_FINALIZED,
                        inventory, day, producer, state);
        assertEquals(TARGET_DAY, parsed.targetDay());
        JsonNode parentJson = MAPPER.readTree(parent);
        assertEquals(TARGET_DAY.plusDays(2).toString(),
                parentJson.get("first_eligible_utc_decision_day").textValue());
        assertEquals(1, fake.count(OkxDraCryptoCarryForwardSource.INVENTORY_URI));
        assertEquals(1, fake.count(candleUri("BTC-USDT-260814")));
        assertEquals(1, fake.count(OkxDraCryptoCarryForwardSource.INDEX_URI));
        assertTrue(finalized.json().contains(
                "\"status\":\"OFFLINE_DROP_PUBLISHED_NOT_REGISTERED\""));
        assertFalse(finalized.json().contains("accepted-inventory-state"));
    }

    @Test
    void createOnceAndRequestScopeFailBeforeTransport() throws Exception {
        OkxDraCryptoCarryPhaseCli.FixedLayout layout = initializeLayout(temporaryDirectory);
        FakeTransport fake = fixtureTransport();
        writeRequest(layout, prepareRequest());
        OkxDraCryptoCarryPhaseCli.runForTest(
                Clock.fixed(PREPARE_AT, ZoneOffset.UTC), fake, temporaryDirectory);
        byte[] retained = Files.readAllBytes(layout.privateInventory(TARGET_DAY));
        int calls = fake.totalCalls();
        assertThrows(Exception.class,
                () -> OkxDraCryptoCarryPhaseCli.runForTest(
                        Clock.fixed(PREPARE_AT, ZoneOffset.UTC), fake, temporaryDirectory));
        assertEquals(calls, fake.totalCalls());
        assertArrayEquals(retained, Files.readAllBytes(layout.privateInventory(TARGET_DAY)));

        Path otherRoot = temporaryDirectory.resolve("extra-request");
        OkxDraCryptoCarryPhaseCli.FixedLayout extraLayout = initializeLayout(otherRoot);
        writeRequest(extraLayout, prepareRequest());
        Files.writeString(extraLayout.requestRoot().resolve("unexpected.json"), "{}\n",
                StandardOpenOption.CREATE_NEW);
        FakeTransport neverCalled = fixtureTransport();
        assertThrows(Exception.class,
                () -> OkxDraCryptoCarryPhaseCli.runForTest(
                        Clock.fixed(PREPARE_AT, ZoneOffset.UTC), neverCalled, otherRoot));
        assertEquals(0, neverCalled.totalCalls());

        Path partialRoot = temporaryDirectory.resolve("partial-request");
        OkxDraCryptoCarryPhaseCli.FixedLayout partialLayout = initializeLayout(partialRoot);
        writeRequest(partialLayout,
                finalizeRequest("c".repeat(64), "d".repeat(64), genesis()));
        FakeTransport partialTransport = fixtureTransport();
        assertThrows(Exception.class,
                () -> OkxDraCryptoCarryPhaseCli.runForTest(
                        Clock.fixed(FINALIZE_AT, ZoneOffset.UTC),
                        partialTransport, partialRoot));
        assertEquals(0, partialTransport.totalCalls());

        Path driftRoot = temporaryDirectory.resolve("path-drift");
        OkxDraCryptoCarryPhaseCli.FixedLayout driftLayout = initializeLayout(driftRoot);
        Files.move(driftLayout.requestRoot(), driftRoot.resolve("caller-selected-request"));
        assertThrows(Exception.class,
                () -> OkxDraCryptoCarryPhaseCli.runForTest(
                        Clock.fixed(PREPARE_AT, ZoneOffset.UTC),
                        fixtureTransport(), driftRoot));
    }

    @Test
    void wrongAcceptedStateAndLinksFailClosedWithoutNetwork() throws Exception {
        Path root = temporaryDirectory.resolve("state-hash");
        OkxDraCryptoCarryPhaseCli.FixedLayout layout = initializeLayout(root);
        byte[] inventory = syntheticInventory(fixtureTransport());
        Files.write(layout.privateInventory(TARGET_DAY), inventory,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        byte[] wrongState = canonicalLf(Map.of("not", "accepted-state"));
        Files.write(layout.acceptedStatePath(), wrongState,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        writeRequest(layout, finalizeRequest(
                sha256(inventory), "d".repeat(64), genesis()));
        FakeTransport fake = fixtureTransport();
        assertThrows(Exception.class,
                () -> OkxDraCryptoCarryPhaseCli.runForTest(
                        Clock.fixed(FINALIZE_AT, ZoneOffset.UTC), fake, root));
        assertEquals(0, fake.totalCalls());
        Files.write(layout.requestRoot().resolve("request.json"),
                canonicalLf(finalizeRequest(
                        sha256(inventory), sha256(wrongState), genesis())),
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        assertThrows(Exception.class,
                () -> OkxDraCryptoCarryPhaseCli.runForTest(
                        Clock.fixed(FINALIZE_AT, ZoneOffset.UTC), fake, root));
        assertEquals(0, fake.totalCalls());

        Path linkRoot = temporaryDirectory.resolve("link-root");
        Path realRoot = temporaryDirectory.resolve("real-root");
        initializeLayout(realRoot);
        try {
            Files.createSymbolicLink(linkRoot, realRoot.toAbsolutePath());
            assertThrows(Exception.class,
                    () -> OkxDraCryptoCarryPhaseCli.runForTest(
                            Clock.fixed(PREPARE_AT, ZoneOffset.UTC),
                            fixtureTransport(), linkRoot));
        } catch (UnsupportedOperationException | FileSystemException error) {
            String source = Files.readString(Path.of(
                    "src/main/java/com/agora/research/OkxDraCryptoCarryPhaseCli.java"));
            assertTrue(source.contains("attributes.isSymbolicLink() || attributes.isOther()"));
            assertTrue(source.contains("LinkOption.NOFOLLOW_LINKS"));
        }
    }

    private static OkxDraCryptoCarryPhaseCli.FixedLayout initializeLayout(Path root)
            throws Exception {
        Files.createDirectories(root);
        OkxDraCryptoCarryPhaseCli.FixedLayout layout =
                OkxDraCryptoCarryPhaseCli.FixedLayout.forTest(root);
        Files.createDirectories(layout.bindingPath().getParent());
        Files.createDirectories(layout.requestRoot());
        Files.createDirectories(layout.privateRoot());
        Files.createDirectories(layout.inventoryStagingRoot());
        Files.createDirectories(layout.inventoryDropRoot());
        Files.createDirectories(layout.dayStagingRoot());
        Files.createDirectories(layout.dayDropRoot());
        Files.write(layout.bindingPath(), canonicalLf(binding()),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return layout;
    }

    private static void writeRequest(
            OkxDraCryptoCarryPhaseCli.FixedLayout layout,
            Map<String, Object> request) throws Exception {
        Files.write(layout.requestRoot().resolve("request.json"), canonicalLf(request),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static Map<String, Object> binding() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", "2");
        value.put("authorization", OkxDraCryptoCarryProducerEnvelopeV2.AUTHORIZATION);
        value.put("source_contract_sha256",
                OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_CONTRACT_SHA256);
        value.put("source_identity", OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_IDENTITY);
        value.put("source_group", OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_GROUP);
        value.put("intake_identity", OkxDraCryptoCarryProducerEnvelopeV2.INTAKE_IDENTITY);
        value.put("producer_release_id", "carry-release-v2");
        value.put("producer_manifest_sha256", MANIFEST_SHA);
        return value;
    }

    private static Map<String, Object> prepareRequest() {
        Map<String, Object> value = requestCommon("PREPARE_INVENTORY", PREPARE_AT);
        value.put("inventory_sha256", null);
        value.put("accepted_inventory_state_sha256", null);
        value.put("predecessor", null);
        return value;
    }

    private static Map<String, Object> finalizeRequest(
            String inventorySha, String stateSha, Map<String, Object> predecessor) {
        Map<String, Object> value = requestCommon("FINALIZE_DAY", FINALIZE_AT);
        value.put("inventory_sha256", inventorySha);
        value.put("accepted_inventory_state_sha256", stateSha);
        value.put("predecessor", predecessor);
        return value;
    }

    private static Map<String, Object> requestCommon(String operation, Instant requestedAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", "OKX_DRA_CRYPTO_CARRY_SOURCE_REQUEST_V2");
        value.put("authorization", OkxDraCryptoCarryProducerEnvelopeV2.AUTHORIZATION);
        value.put("operation", operation);
        value.put("source_contract_sha256",
                OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_CONTRACT_SHA256);
        value.put("request_id", "DRA_CRYPTO_CARRY_V2:" + operation + ":" + TARGET_DAY);
        value.put("target_day", TARGET_DAY.toString());
        value.put("requested_at", requestedAt.toString());
        return value;
    }

    private static Map<String, Object> genesis() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "GENESIS");
        value.put("day", null);
        value.put("day_drop_envelope_sha256", "0".repeat(64));
        return value;
    }

    private static Map<String, Object> chain() {
        return Map.of(
                "type", "CHAIN",
                "day", TARGET_DAY.minusDays(1).toString(),
                "day_drop_envelope_sha256", CHAIN_SHA);
    }

    private static FakeTransport fixtureTransport() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respond(OkxDraCryptoCarryForwardSource.INVENTORY_URI,
                response(ok(List.of(fixtureInstrument()))));
        fake.respond(candleUri("BTC-USDT-260814"),
                response(ok(List.of(futuresRow()))));
        fake.respond(OkxDraCryptoCarryForwardSource.INDEX_URI,
                response(ok(List.of(indexRow()))));
        return fake;
    }

    private static byte[] syntheticInventory(FakeTransport fake) {
        return new OkxDraCryptoCarryForwardSource(fake)
                .captureInventory(TARGET_DAY, PREPARE_AT);
    }

    private static Map<String, String> fixtureInstrument() {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("instId", "BTC-USDT-260814");
        value.put("instType", "FUTURES");
        value.put("instFamily", "BTC-USDT");
        value.put("uly", "BTC-USDT");
        value.put("ctType", "linear");
        value.put("settleCcy", "USDT");
        value.put("state", "live");
        value.put("ruleType", "normal");
        value.put("listTime", millis("2026-08-01T00:00:00Z"));
        value.put("expTime", millis("2026-08-14T08:00:00Z"));
        return value;
    }

    private static List<String> futuresRow() {
        return new ArrayList<>(List.of(
                millis("2026-08-10T00:00:00Z"),
                "100.00", "112.00", "91.00", "105.00",
                "10.0", "1.0", "1000.0", "1"));
    }

    private static List<String> indexRow() {
        return new ArrayList<>(List.of(
                millis("2026-08-10T00:00:00Z"),
                "100.00", "110.00", "90.00", "104.00", "1"));
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

    private static String millis(String value) {
        return Long.toString(Instant.parse(value).toEpochMilli());
    }

    private static String sha256(byte[] bytes) {
        return OkxDraCryptoCarryProducerEnvelopeV2.sha256(bytes);
    }

    private static byte[] canonicalLf(Object value) throws Exception {
        byte[] compact = MAPPER.writeValueAsBytes(value);
        return append(compact, new byte[]{'\n'});
    }

    private static byte[] append(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String profile(String pom, String id) {
        int idIndex = pom.indexOf("<id>" + id + "</id>");
        assertTrue(idIndex >= 0);
        int start = pom.lastIndexOf("<profile>", idIndex);
        int end = pom.indexOf("</profile>", idIndex);
        assertTrue(start >= 0 && end > idIndex);
        return pom.substring(start, end + "</profile>".length());
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static final class FakeTransport
            implements OkxDraCryptoCarryForwardSource.Transport {
        private final Map<URI, OkxDraCryptoCarryForwardSource.ResponseSpec> responses =
                new HashMap<>();
        private final Map<URI, Integer> counts = new HashMap<>();

        void respond(URI uri, OkxDraCryptoCarryForwardSource.ResponseSpec response) {
            responses.put(uri, response);
        }

        int count(URI uri) {
            return counts.getOrDefault(uri, 0);
        }

        int totalCalls() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public OkxDraCryptoCarryForwardSource.ResponseSpec execute(
                OkxDraCryptoCarryForwardSource.RequestSpec request) {
            counts.merge(request.uri(), 1, Integer::sum);
            OkxDraCryptoCarryForwardSource.ResponseSpec response =
                    responses.get(request.uri());
            assertNotNull(response, request.uri().toString());
            return response;
        }
    }
}
