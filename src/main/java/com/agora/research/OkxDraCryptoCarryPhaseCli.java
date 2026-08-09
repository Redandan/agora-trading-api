package com.agora.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Fixed-input, disabled, non-Spring entrypoint for the two-phase carry source. */
public final class OkxDraCryptoCarryPhaseCli {

    static final Path FIXED_BINDING_PATH = Path.of(
            "/etc/agora-research/okx-dra-crypto-carry-source-v2.json");
    static final Path FIXED_REQUEST_ROOT = Path.of(
            "/var/lib/agora-research/dra-crypto-carry-source-request-v2");
    static final Path FIXED_PRIVATE_ROOT = Path.of(
            "/var/lib/agora-dra-carry-source/dra-crypto-carry-v2-private");
    static final Path FIXED_INVENTORY_STAGING_ROOT = Path.of(
            "/var/lib/agora-dra-carry-source/dra-crypto-carry-v2-inventory-staging");
    static final Path FIXED_INVENTORY_DROP_ROOT = Path.of(
            "/var/lib/agora-dra-carry-source/dra-crypto-carry-v2-inventory-drop");
    static final Path FIXED_DAY_STAGING_ROOT = Path.of(
            "/var/lib/agora-dra-carry-source/dra-crypto-carry-v2-day-staging");
    static final Path FIXED_DAY_DROP_ROOT = Path.of(
            "/var/lib/agora-dra-carry-source/dra-crypto-carry-v2-day-drop");

    static final String BINDING_SCHEMA_VERSION = "2";
    static final String REQUEST_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_SOURCE_REQUEST_V2";
    static final String SUCCESS_STATUS = "OFFLINE_DROP_PUBLISHED_NOT_REGISTERED";
    static final String BLOCKED_STATUS = "DRA_CRYPTO_CARRY_SOURCE_BLOCKED";
    private static final String ZERO_SHA256 = "0".repeat(64);
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern RELEASE_ID = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final int MAX_BINDING_BYTES = 16_384;
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final int MAX_STATE_BYTES = 2_097_152;
    private static final int MAX_PRIVATE_BYTES = 2_097_152;

    private static final Set<String> BINDING_KEYS = Set.of(
            "schema_version", "authorization", "source_contract_sha256",
            "source_identity", "source_group", "intake_identity",
            "producer_release_id", "producer_manifest_sha256");
    private static final Set<String> REQUEST_KEYS = Set.of(
            "schema_version", "authorization", "operation",
            "source_contract_sha256", "request_id", "target_day",
            "requested_at", "inventory_sha256",
            "accepted_inventory_state_sha256", "predecessor");
    private static final Set<String> PREDECESSOR_KEYS = Set.of(
            "type", "day", "day_drop_envelope_sha256");
    private static final Set<String> ACCEPTED_STATE_KEYS = Set.of(
            "schema_version", "authorization", "status", "stage",
            "source_contract_sha256", "intake_state_schema_sha256",
            "source_identity", "source_group", "intake_identity", "target_day",
            "previous_state_sha256", "inventory",
            "inventory_parent_envelope_sha256", "inventory_drop_envelope_sha256",
            "inventory_published_at", "inventory_accepted_at", "day",
            "v1_producer_envelope", "day_parent_envelope_sha256",
            "day_drop_envelope_sha256", "day_published_at", "day_accepted_at",
            "predecessor", "first_eligible_utc_decision_day", "state_seal");

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    private OkxDraCryptoCarryPhaseCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            requireNoArguments(args);
            Clock clock = Clock.systemUTC();
            FixedLayout layout = FixedLayout.production();
            layout.validate();
            SourceBinding binding = SourceBinding.load(layout.bindingPath(), true);
            Instant now = currentSecond(clock);
            SourceRequest request = SourceRequest.load(
                    layout.requestRoot(), binding, now);
            RunResult result = executePrepared(
                    clock, new OkxDraCryptoCarryForwardSource.FixedHttpClientTransport(),
                    layout, binding, request);
            System.out.println(result.json());
        } catch (Exception error) {
            System.err.println("{\"status\":\"" + BLOCKED_STATUS
                    + "\",\"detail\":\"" + jsonEscape(boundedDetail(error)) + "\"}");
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static void requireNoArguments(String[] args) {
        if (args == null || args.length != 0) {
            throw failure("CALLER_SELECTED_ARGUMENT_REJECT");
        }
    }

    static RunResult runForTest(
            Clock clock,
            OkxDraCryptoCarryForwardSource.Transport transport,
            Path temporaryRoot) throws Exception {
        FixedLayout layout = FixedLayout.forTest(temporaryRoot);
        layout.validate();
        SourceBinding binding = SourceBinding.load(layout.bindingPath(), false);
        Instant now = currentSecond(clock);
        SourceRequest request = SourceRequest.load(layout.requestRoot(), binding, now);
        return executePrepared(clock, transport, layout, binding, request);
    }

    private static RunResult executePrepared(
            Clock clock,
            OkxDraCryptoCarryForwardSource.Transport transport,
            FixedLayout layout,
            SourceBinding binding,
            SourceRequest request) throws Exception {
        if (clock == null || transport == null || layout == null
                || binding == null || request == null) {
            throw failure("NULL_EXECUTION_INPUT");
        }
        Instant now = currentSecond(clock);
        if (!now.equals(request.requestedAt())) {
            throw failure("REQUEST_CLOCK_CHANGED");
        }
        preflightPublication(layout, request);
        OkxDraCryptoCarryCanonicalDropV2.FileDropSink sink = layout.newDropSink();
        OkxDraCryptoCarryForwardSource source =
                new OkxDraCryptoCarryForwardSource(transport);
        Path published;
        String inventorySha;
        if (request.operation() == Operation.PREPARE_INVENTORY) {
            byte[] inventory = source.captureInventory(request.targetDay(), now);
            inventorySha = sha256(inventory);
            byte[] parent = OkxDraCryptoCarryProducerEnvelopeV2.prepareInventory(
                    inventory, now);
            OkxDraCryptoCarryCanonicalDropV2.PhaseDocuments documents =
                    OkxDraCryptoCarryCanonicalDropV2.createInventoryDrop(
                            new OkxDraCryptoCarryCanonicalDropV2.InventoryBinding(
                                    request.targetDay(), now, inventorySha),
                            inventory, parent);
            writePrivateInventory(layout.privateInventory(request.targetDay()), inventory);
            published = sink.publish(documents);
        } else {
            byte[] inventory = readRegular(
                    layout.privateInventory(request.targetDay()),
                    MAX_PRIVATE_BYTES, "retained inventory");
            inventorySha = sha256(inventory);
            if (!inventorySha.equals(request.inventorySha256())) {
                throw failure("RETAINED_INVENTORY_HASH_REJECT");
            }
            byte[] acceptedState = readRegular(
                    layout.acceptedStatePath(), MAX_STATE_BYTES,
                    "accepted inventory state");
            if (!sha256(acceptedState).equals(
                    request.acceptedInventoryStateSha256())) {
                throw failure("ACCEPTED_STATE_HASH_REJECT");
            }
            validateAcceptedStateHeader(acceptedState, request);
            byte[] day = source.captureDay(inventory, now);
            byte[] v1Producer = source.createEnvelope(inventory, day, now);
            byte[] parent = OkxDraCryptoCarryProducerEnvelopeV2.finalizeDay(
                    inventory, day, v1Producer, acceptedState, now);
            OkxDraCryptoCarryCanonicalDropV2.DayBinding dayBinding =
                    new OkxDraCryptoCarryCanonicalDropV2.DayBinding(
                            request.targetDay(), request.acceptedInventoryStateSha256(),
                            request.targetDay().plusDays(2),
                            request.targetDay().plusDays(1).atTime(LocalTime.of(6, 0))
                                    .toInstant(ZoneOffset.UTC),
                            now, request.predecessor().type().dropType,
                            request.predecessor().day(),
                            request.predecessor().dayDropEnvelopeSha256());
            OkxDraCryptoCarryCanonicalDropV2.PhaseDocuments documents =
                    OkxDraCryptoCarryCanonicalDropV2.createDayDrop(
                            dayBinding, acceptedState, inventory, day, v1Producer, parent);
            published = sink.publish(documents);
        }
        return new RunResult(
                request.operation(), request.requestId(), request.targetDay(),
                published.getFileName().toString(), inventorySha,
                binding.producerReleaseId(), binding.producerManifestSha256());
    }

    private static void preflightPublication(FixedLayout layout, SourceRequest request)
            throws Exception {
        Path privateInventory = layout.privateInventory(request.targetDay());
        Path stagingRoot = request.operation() == Operation.PREPARE_INVENTORY
                ? layout.inventoryStagingRoot() : layout.dayStagingRoot();
        Path dropRoot = request.operation() == Operation.PREPARE_INVENTORY
                ? layout.inventoryDropRoot() : layout.dayDropRoot();
        String suffix = request.operation() == Operation.PREPARE_INVENTORY
                ? "inventory-v2" : "day-v2";
        String directory = request.targetDay() + "." + suffix;
        String reservation = "." + request.targetDay() + "." + suffix
                + ".publish-reserved";
        rejectExisting(stagingRoot.resolve(directory));
        rejectExisting(dropRoot.resolve(directory));
        rejectExisting(dropRoot.resolve(reservation));
        if (request.operation() == Operation.PREPARE_INVENTORY) {
            rejectExisting(privateInventory);
        } else {
            requireRegular(privateInventory, MAX_PRIVATE_BYTES, "retained inventory");
            requireRegular(layout.acceptedStatePath(), MAX_STATE_BYTES,
                    "accepted inventory state");
        }
    }

    private static void validateAcceptedStateHeader(
            byte[] stateBytes, SourceRequest request) {
        JsonNode root = OkxDraCryptoCarryProducerEnvelopeV2.parseCanonical(
                stateBytes, "accepted inventory state");
        requireKeys(root, ACCEPTED_STATE_KEYS, "accepted inventory state");
        requireText(root, "schema_version", "OKX_DRA_CRYPTO_CARRY_INTAKE_STATE_V2");
        requireText(root, "authorization",
                OkxDraCryptoCarryProducerEnvelopeV2.AUTHORIZATION);
        requireText(root, "status", "VALID_OFFLINE_DROP_NOT_CANONICAL");
        requireText(root, "stage", "INVENTORY_ACCEPTED");
        requireText(root, "source_contract_sha256",
                OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_CONTRACT_SHA256);
        requireText(root, "intake_state_schema_sha256",
                OkxDraCryptoCarryProducerEnvelopeV2.INTAKE_STATE_SCHEMA_SHA256);
        requireText(root, "source_identity",
                OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_IDENTITY);
        requireText(root, "source_group", OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_GROUP);
        requireText(root, "intake_identity",
                OkxDraCryptoCarryProducerEnvelopeV2.INTAKE_IDENTITY);
        requireText(root, "target_day", request.targetDay().toString());
        JsonNode inventory = root.get("inventory");
        if (inventory == null || !inventory.isObject()) {
            throw failure("STATE_INVENTORY_REJECT");
        }
        requireText(inventory, "sha256", request.inventorySha256());
    }

    private static void writePrivateInventory(Path path, byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PRIVATE_BYTES) {
            throw failure("PRIVATE_INVENTORY_SIZE_REJECT");
        }
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static Instant currentSecond(Clock clock) {
        if (clock == null) {
            throw failure("NULL_CLOCK");
        }
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static byte[] readRegular(Path path, int maximum, String label)
            throws Exception {
        requireRegular(path, maximum, label);
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes.length > maximum) {
            throw failure("FILE_SIZE_REJECT:" + label);
        }
        return bytes;
    }

    private static void requireRegular(Path path, int maximum, String label)
            throws Exception {
        rejectLinkChain(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("REGULAR_FILE_REQUIRED:" + label);
        }
        long size = Files.size(path);
        if (size <= 0 || size > maximum) {
            throw failure("FILE_SIZE_REJECT:" + label);
        }
    }

    private static void requireDirectory(Path path, String expectedLeaf) throws Exception {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                || absolute.getFileName() == null
                || !absolute.getFileName().toString().equals(expectedLeaf)) {
            throw failure("FIXED_DIRECTORY_REJECT:" + expectedLeaf);
        }
        rejectLinkChain(absolute);
    }

    private static void requireChildren(Path directory, Set<String> expected)
            throws Exception {
        Set<String> actual = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                actual.add(child.getFileName().toString());
            }
        }
        if (!actual.equals(expected)) {
            throw failure("REQUEST_ROOT_SCOPE_REJECT");
        }
    }

    private static void rejectExisting(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("CREATE_ONLY_CONFLICT:" + path.getFileName());
        }
    }

    private static void rejectLinkChain(Path path) throws Exception {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(
                        current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw failure("SYMLINK_OR_REPARSE_REJECT");
                }
            }
        }
    }

    private static JsonNode parseCanonicalLf(byte[] bytes, int maximum, String label) {
        if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
            throw failure("DOCUMENT_SIZE_REJECT:" + label);
        }
        final JsonNode root;
        try {
            root = STRICT_MAPPER.readTree(bytes);
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON_REJECT:" + label, error);
        }
        if (root == null || !root.isObject()) {
            throw failure("OBJECT_REQUIRED:" + label);
        }
        byte[] canonical = canonicalLf(root);
        if (!Arrays.equals(bytes, canonical)) {
            throw failure("NONCANONICAL_BYTES_REJECT:" + label);
        }
        return root;
    }

    private static byte[] canonicalLf(JsonNode root) {
        try {
            byte[] compact = CANONICAL_MAPPER.writeValueAsBytes(generic(root));
            ByteArrayOutputStream output = new ByteArrayOutputStream(compact.length + 1);
            output.write(compact);
            output.write('\n');
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("CANONICAL_JSON_FAILED", error);
        }
    }

    private static Object generic(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> value = new TreeMap<>();
            node.fields().forEachRemaining(entry ->
                    value.put(entry.getKey(), generic(entry.getValue())));
            return value;
        }
        if (node.isArray()) {
            List<Object> value = new ArrayList<>();
            node.forEach(child -> value.add(generic(child)));
            return value;
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        throw failure("UNSUPPORTED_JSON_VALUE");
    }

    private static void requireKeys(JsonNode node, Set<String> expected, String label) {
        if (node == null || !node.isObject()) {
            throw failure("OBJECT_REQUIRED:" + label);
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw failure("KEYS_REJECT:" + label);
        }
    }

    private static String requiredText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw failure("TEXT_REQUIRED:" + key);
        }
        return value.textValue();
    }

    private static void requireText(JsonNode node, String key, String expected) {
        if (!expected.equals(requiredText(node, key))) {
            throw failure("TEXT_REJECT:" + key);
        }
    }

    private static String nullableText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw failure("NULLABLE_TEXT_REJECT:" + key);
        }
        return value.textValue();
    }

    private static Instant instant(JsonNode node, String key) {
        try {
            Instant value = Instant.parse(requiredText(node, key));
            if (!value.equals(value.truncatedTo(ChronoUnit.SECONDS))) {
                throw failure("TIMESTAMP_PRECISION_REJECT:" + key);
            }
            return value;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("TIMESTAMP_REJECT:" + key, error);
        }
    }

    private static LocalDate date(JsonNode node, String key) {
        try {
            return LocalDate.parse(requiredText(node, key));
        } catch (Exception error) {
            throw new IllegalArgumentException("DATE_REJECT:" + key, error);
        }
    }

    private static void requireSha(String value, boolean nonzero, String label) {
        if (value == null || !SHA256.matcher(value).matches()
                || (nonzero && ZERO_SHA256.equals(value))) {
            throw failure("SHA256_REJECT:" + label);
        }
    }

    private static String sha256(byte[] bytes) {
        return OkxDraCryptoCarryProducerEnvelopeV2.sha256(bytes);
    }

    private static String boundedDetail(Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = error.getClass().getSimpleName();
        }
        detail = detail.replace('\r', ' ').replace('\n', ' ').strip();
        return detail.length() <= 240 ? detail : detail.substring(0, 240);
    }

    private static String jsonEscape(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.toString();
    }

    private static IllegalArgumentException failure(String code) {
        return new IllegalArgumentException(code);
    }

    enum Operation {
        PREPARE_INVENTORY,
        FINALIZE_DAY
    }

    enum PredecessorType {
        GENESIS(OkxDraCryptoCarryCanonicalDropV2.PredecessorType.GENESIS),
        CHAIN(OkxDraCryptoCarryCanonicalDropV2.PredecessorType.CHAIN);

        private final OkxDraCryptoCarryCanonicalDropV2.PredecessorType dropType;

        PredecessorType(OkxDraCryptoCarryCanonicalDropV2.PredecessorType dropType) {
            this.dropType = dropType;
        }
    }

    record SourceBinding(
            String producerReleaseId,
            String producerManifestSha256) {

        static SourceBinding load(Path path, boolean enforceProductionMetadata)
                throws Exception {
            requireRegular(path, MAX_BINDING_BYTES, "source binding");
            if (enforceProductionMetadata) {
                PosixFileAttributes attributes = Files.readAttributes(
                        path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Set<PosixFilePermission> expectedPermissions = Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ);
                if (!"root".equals(attributes.owner().getName())
                        || !OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_GROUP.equals(
                        attributes.group().getName())
                        || !attributes.permissions().equals(expectedPermissions)) {
                    throw failure("BINDING_METADATA_REJECT");
                }
            }
            return parse(Files.readAllBytes(path));
        }

        static SourceBinding parse(byte[] bytes) {
            JsonNode root = parseCanonicalLf(bytes, MAX_BINDING_BYTES, "source binding");
            requireKeys(root, BINDING_KEYS, "source binding");
            requireText(root, "schema_version", BINDING_SCHEMA_VERSION);
            requireText(root, "authorization",
                    OkxDraCryptoCarryProducerEnvelopeV2.AUTHORIZATION);
            requireText(root, "source_contract_sha256",
                    OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_CONTRACT_SHA256);
            requireText(root, "source_identity",
                    OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_IDENTITY);
            requireText(root, "source_group",
                    OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_GROUP);
            requireText(root, "intake_identity",
                    OkxDraCryptoCarryProducerEnvelopeV2.INTAKE_IDENTITY);
            String releaseId = requiredText(root, "producer_release_id");
            if (!RELEASE_ID.matcher(releaseId).matches()) {
                throw failure("PRODUCER_RELEASE_ID_REJECT");
            }
            String manifest = requiredText(root, "producer_manifest_sha256");
            requireSha(manifest, true, "producer manifest");
            return new SourceBinding(releaseId, manifest);
        }
    }

    record Predecessor(
            PredecessorType type,
            LocalDate day,
            String dayDropEnvelopeSha256) {
    }

    record SourceRequest(
            Operation operation,
            String requestId,
            LocalDate targetDay,
            Instant requestedAt,
            String inventorySha256,
            String acceptedInventoryStateSha256,
            Predecessor predecessor) {

        static SourceRequest load(
                Path requestRoot, SourceBinding binding, Instant now) throws Exception {
            requireDirectory(requestRoot, "dra-crypto-carry-source-request-v2");
            Path requestPath = requestRoot.resolve("request.json");
            byte[] bytes = readRegular(
                    requestPath, MAX_REQUEST_BYTES, "source request");
            SourceRequest request = parse(bytes, binding, now);
            Set<String> expected = request.operation() == Operation.PREPARE_INVENTORY
                    ? Set.of("request.json")
                    : Set.of("request.json", "accepted-inventory-state.json");
            requireChildren(requestRoot, expected);
            if (request.operation() == Operation.FINALIZE_DAY) {
                requireRegular(requestRoot.resolve("accepted-inventory-state.json"),
                        MAX_STATE_BYTES, "accepted inventory state");
            }
            return request;
        }

        static SourceRequest parse(
                byte[] bytes, SourceBinding binding, Instant now) {
            if (binding == null || now == null) {
                throw failure("NULL_REQUEST_CONTEXT");
            }
            JsonNode root = parseCanonicalLf(bytes, MAX_REQUEST_BYTES, "source request");
            requireKeys(root, REQUEST_KEYS, "source request");
            requireText(root, "schema_version", REQUEST_SCHEMA_VERSION);
            requireText(root, "authorization",
                    OkxDraCryptoCarryProducerEnvelopeV2.AUTHORIZATION);
            requireText(root, "source_contract_sha256",
                    OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_CONTRACT_SHA256);
            final Operation operation;
            try {
                operation = Operation.valueOf(requiredText(root, "operation"));
            } catch (Exception error) {
                throw new IllegalArgumentException("OPERATION_REJECT", error);
            }
            LocalDate targetDay = date(root, "target_day");
            Instant requestedAt = instant(root, "requested_at");
            if (!requestedAt.equals(now)) {
                throw failure("REQUEST_CLOCK_MISMATCH");
            }
            String requestId = requiredText(root, "request_id");
            String expectedRequestId = "DRA_CRYPTO_CARRY_V2:"
                    + operation.name() + ":" + targetDay;
            if (!expectedRequestId.equals(requestId)) {
                throw failure("REQUEST_ID_REJECT");
            }
            String inventorySha = nullableText(root, "inventory_sha256");
            String stateSha = nullableText(root, "accepted_inventory_state_sha256");
            Predecessor predecessor;
            if (operation == Operation.PREPARE_INVENTORY) {
                if (inventorySha != null || stateSha != null
                        || !root.get("predecessor").isNull()) {
                    throw failure("PREPARE_FIELDS_REJECT");
                }
                Instant start = targetDay.minusDays(1).atTime(LocalTime.of(1, 5))
                        .toInstant(ZoneOffset.UTC);
                Instant end = targetDay.atStartOfDay().toInstant(ZoneOffset.UTC);
                if (now.isBefore(start) || !now.isBefore(end)) {
                    throw failure("PREPARE_CLOCK_REJECT");
                }
                predecessor = null;
            } else {
                requireSha(inventorySha, true, "request inventory");
                requireSha(stateSha, true, "request accepted state");
                predecessor = parsePredecessor(root.get("predecessor"), targetDay);
                Instant start = targetDay.plusDays(1).atTime(LocalTime.of(1, 5))
                        .toInstant(ZoneOffset.UTC);
                Instant end = targetDay.plusDays(1).atTime(LocalTime.of(6, 0))
                        .toInstant(ZoneOffset.UTC);
                if (now.isBefore(start) || !now.isBefore(end)) {
                    throw failure("FINALIZE_CLOCK_REJECT");
                }
            }
            return new SourceRequest(
                    operation, requestId, targetDay, requestedAt,
                    inventorySha, stateSha, predecessor);
        }

        private static Predecessor parsePredecessor(JsonNode node, LocalDate targetDay) {
            requireKeys(node, PREDECESSOR_KEYS, "predecessor");
            final PredecessorType type;
            try {
                type = PredecessorType.valueOf(requiredText(node, "type"));
            } catch (Exception error) {
                throw new IllegalArgumentException("PREDECESSOR_TYPE_REJECT", error);
            }
            String dayText = nullableText(node, "day");
            String hash = requiredText(node, "day_drop_envelope_sha256");
            if (type == PredecessorType.GENESIS) {
                if (dayText != null || !ZERO_SHA256.equals(hash)) {
                    throw failure("GENESIS_PREDECESSOR_REJECT");
                }
                return new Predecessor(type, null, hash);
            }
            requireSha(hash, true, "chain predecessor");
            final LocalDate day;
            try {
                day = LocalDate.parse(dayText);
            } catch (Exception error) {
                throw new IllegalArgumentException("CHAIN_DAY_REJECT", error);
            }
            if (!day.equals(targetDay.minusDays(1))) {
                throw failure("CHAIN_CONTIGUITY_REJECT");
            }
            return new Predecessor(type, day, hash);
        }
    }

    record RunResult(
            Operation operation,
            String requestId,
            LocalDate targetDay,
            String publishedDirectory,
            String inventorySha256,
            String producerReleaseId,
            String producerManifestSha256) {

        String json() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("status", SUCCESS_STATUS);
            value.put("operation", operation.name());
            value.put("request_id", requestId);
            value.put("target_day", targetDay.toString());
            value.put("published_directory", publishedDirectory);
            value.put("inventory_sha256", inventorySha256);
            value.put("producer_release_id", producerReleaseId);
            value.put("producer_manifest_sha256", producerManifestSha256);
            try {
                return new String(CANONICAL_MAPPER.writeValueAsBytes(value),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception error) {
                throw new IllegalStateException("STATUS_JSON_FAILED", error);
            }
        }
    }

    static final class FixedLayout {
        private final Path bindingPath;
        private final Path requestRoot;
        private final Path privateRoot;
        private final Path inventoryStagingRoot;
        private final Path inventoryDropRoot;
        private final Path dayStagingRoot;
        private final Path dayDropRoot;
        private final boolean production;

        private FixedLayout(
                Path bindingPath,
                Path requestRoot,
                Path privateRoot,
                Path inventoryStagingRoot,
                Path inventoryDropRoot,
                Path dayStagingRoot,
                Path dayDropRoot,
                boolean production) {
            this.bindingPath = bindingPath;
            this.requestRoot = requestRoot;
            this.privateRoot = privateRoot;
            this.inventoryStagingRoot = inventoryStagingRoot;
            this.inventoryDropRoot = inventoryDropRoot;
            this.dayStagingRoot = dayStagingRoot;
            this.dayDropRoot = dayDropRoot;
            this.production = production;
        }

        static FixedLayout production() {
            return new FixedLayout(
                    FIXED_BINDING_PATH, FIXED_REQUEST_ROOT, FIXED_PRIVATE_ROOT,
                    FIXED_INVENTORY_STAGING_ROOT, FIXED_INVENTORY_DROP_ROOT,
                    FIXED_DAY_STAGING_ROOT, FIXED_DAY_DROP_ROOT, true);
        }

        static FixedLayout forTest(Path suppliedRoot) {
            if (suppliedRoot == null) {
                throw failure("NULL_TEST_ROOT");
            }
            Path root = suppliedRoot.toAbsolutePath().normalize();
            return new FixedLayout(
                    root.resolve("etc").resolve(
                            "okx-dra-crypto-carry-source-v2.json"),
                    root.resolve("dra-crypto-carry-source-request-v2"),
                    root.resolve("dra-crypto-carry-v2-private"),
                    root.resolve("dra-crypto-carry-v2-inventory-staging"),
                    root.resolve("dra-crypto-carry-v2-inventory-drop"),
                    root.resolve("dra-crypto-carry-v2-day-staging"),
                    root.resolve("dra-crypto-carry-v2-day-drop"), false);
        }

        void validate() throws Exception {
            requireDirectory(requestRoot, "dra-crypto-carry-source-request-v2");
            requireDirectory(privateRoot, "dra-crypto-carry-v2-private");
            requireDirectory(inventoryStagingRoot,
                    "dra-crypto-carry-v2-inventory-staging");
            requireDirectory(inventoryDropRoot, "dra-crypto-carry-v2-inventory-drop");
            requireDirectory(dayStagingRoot, "dra-crypto-carry-v2-day-staging");
            requireDirectory(dayDropRoot, "dra-crypto-carry-v2-day-drop");
            List<Path> roots = List.of(
                    requestRoot.toAbsolutePath().normalize(),
                    privateRoot.toAbsolutePath().normalize(),
                    inventoryStagingRoot.toAbsolutePath().normalize(),
                    inventoryDropRoot.toAbsolutePath().normalize(),
                    dayStagingRoot.toAbsolutePath().normalize(),
                    dayDropRoot.toAbsolutePath().normalize());
            if (roots.stream().distinct().count() != roots.size()) {
                throw failure("DISTINCT_FIXED_ROOTS_REQUIRED");
            }
            FileStore sourceStore = Files.getFileStore(privateRoot);
            for (Path root : roots.subList(1, roots.size())) {
                if (!sourceStore.equals(Files.getFileStore(root))) {
                    throw failure("SOURCE_ROOT_FILESYSTEM_REJECT");
                }
            }
        }

        OkxDraCryptoCarryCanonicalDropV2.FileDropSink newDropSink()
                throws Exception {
            if (production) {
                return new OkxDraCryptoCarryCanonicalDropV2.FileDropSink(
                        inventoryStagingRoot, inventoryDropRoot,
                        dayStagingRoot, dayDropRoot);
            }
            return new OkxDraCryptoCarryCanonicalDropV2.FileDropSink(
                    inventoryStagingRoot, inventoryDropRoot,
                    dayStagingRoot, dayDropRoot, ignored -> { });
        }

        Path privateInventory(LocalDate targetDay) {
            return privateRoot.resolve(
                    "okx-dra-crypto-carry-" + targetDay + ".inventory.json");
        }

        Path acceptedStatePath() {
            return requestRoot.resolve("accepted-inventory-state.json");
        }

        Path bindingPath() {
            return bindingPath;
        }

        Path requestRoot() {
            return requestRoot;
        }

        Path privateRoot() {
            return privateRoot;
        }

        Path inventoryStagingRoot() {
            return inventoryStagingRoot;
        }

        Path inventoryDropRoot() {
            return inventoryDropRoot;
        }

        Path dayStagingRoot() {
            return dayStagingRoot;
        }

        Path dayDropRoot() {
            return dayDropRoot;
        }
    }
}
