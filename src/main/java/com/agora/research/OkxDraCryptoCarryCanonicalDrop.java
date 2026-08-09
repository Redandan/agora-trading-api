package com.agora.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Disabled, create-only canonical publication library for one already-produced source day.
 * This class has no entrypoint and accepts filesystem roots only through its package seam.
 */
final class OkxDraCryptoCarryCanonicalDrop {

    static final String AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE";
    static final String STATUS = "OFFLINE_DISABLED_NOT_DEPLOYED";
    static final String SOURCE_LABEL =
            "LAGGED_OKX_BTC_USDT_EXPIRY_FUTURES_BASIS_ATOMS_V1";
    static final String SOURCE_CONTRACT_SHA256 =
            "0944ab401717360f6eccc31ab967461af7ebc8122f7b77ee7b1f46eaa8fac48e";
    static final String PRODUCER_ID = "OKX_DRA_CRYPTO_CARRY_JAVA_PRODUCER_CORE_V1";
    static final String PRODUCER_ENVELOPE_SCHEMA_SHA256 =
            "e39263edd8362e91722134aefd11ee04f76def8b4d409aa01f396a2a481ea6d5";
    static final String DROP_ENVELOPE_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_DROP_ENVELOPE_V1";
    static final String DROP_ENVELOPE_SCHEMA_SHA256 =
            "c3881cfc36f79168efd41a579fdb4f8e207fee0ca33b15b2e0b39c1e6bd43ee0";
    static final String SOURCE_IDENTITY = "agora-evidence-source";
    static final String INTAKE_IDENTITY = "agora-research";
    static final String DROP_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding files.drop_envelope and envelope_seal";
    static final String FULL_FILE_HASH_SCOPE = "FULL_FILE_BYTES";
    static final String DROP_HASH_SCOPE =
            "CANONICAL_PAYLOAD_EXCLUDING_SELF_DESCRIPTOR_AND_ENVELOPE_SEAL";
    static final String ZERO_SHA256 = "0".repeat(64);

    private static final String INVENTORY_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_EXPIRY_FUTURES_INVENTORY_V1";
    private static final String DAY_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_BASIS_ATOMS_DAY_V1";
    private static final String PRODUCER_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_PRODUCER_ENVELOPE_V1";
    private static final String INVENTORY_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding inventory_seal";
    private static final String DAY_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding day_seal";
    private static final String PRODUCER_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding envelope_seal";

    private static final Set<String> INVENTORY_KEYS = Set.of(
            "schema_version", "document_type", "authorization", "source_label",
            "source_contract_sha256", "target_day", "scheduled_cycle_at",
            "captured_at", "request", "inventory_count", "instruments",
            "inventory_seal");
    private static final Set<String> DAY_KEYS = Set.of(
            "schema_version", "document_type", "authorization", "source_label",
            "source_contract_sha256", "inventory_schema_sha256", "day_schema_sha256",
            "inventory_sha256", "target_day", "scheduled_cycle_at", "captured_at",
            "first_eligible_utc_decision_day", "requests", "expected_instrument_count",
            "observed_instrument_count", "cache_order_semantics", "futures", "index",
            "eligibility", "day_seal");
    private static final Set<String> PRODUCER_KEYS = Set.of(
            "schema_version", "envelope_type", "authorization", "status", "source_label",
            "source_contract_sha256", "producer_envelope_schema_sha256", "producer_id",
            "target_day", "generated_at", "inventory", "day",
            "first_eligible_utc_decision_day", "transport_status", "envelope_seal");
    private static final Set<String> DROP_KEYS = Set.of(
            "schema_version", "envelope_type", "authorization", "status", "source_label",
            "source_contract_sha256", "producer_id", "producer_envelope_schema_sha256",
            "drop_envelope_schema_sha256", "source_identity", "intake_identity",
            "target_day", "first_eligible_utc_decision_day", "capture_deadline_utc",
            "published_at", "predecessor", "files", "delivery_semantics",
            "idempotency_key", "envelope_seal");
    private static final Set<String> ARTIFACT_KEYS = Set.of("sha256", "size_bytes");
    private static final Set<String> DROP_FILE_KEYS =
            Set.of("name", "sha256", "size_bytes", "hash_scope");
    private static final Set<String> DROP_FILES_KEYS =
            Set.of("inventory", "day", "producer_envelope", "drop_envelope");
    private static final Set<String> PREDECESSOR_KEYS =
            Set.of("type", "day", "drop_envelope_sha256");
    private static final Set<String> SEAL_KEYS =
            Set.of("algorithm", "payload_sha256", "canonicalization", "sealed_at");

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    private OkxDraCryptoCarryCanonicalDrop() {
    }

    static DropDocuments create(
            TransportBinding binding,
            byte[] inventoryBytes,
            byte[] dayBytes,
            byte[] producerEnvelopeBytes) {
        requireBinding(binding);
        ProducerArtifacts producer = validateProducerArtifacts(
                binding, inventoryBytes, dayBytes, producerEnvelopeBytes);
        if (binding.publishedAt().isBefore(producer.generatedAt())) {
            throw failure("PUBLICATION_BEFORE_PRODUCER_ENVELOPE");
        }

        String prefix = filePrefix(binding.targetDay());
        String inventoryName = prefix + ".inventory.json";
        String dayName = prefix + ".day.json";
        String producerName = prefix + ".producer-envelope.json";
        String dropName = prefix + ".drop-envelope.json";

        Map<String, Object> filesWithoutSelf = new LinkedHashMap<>();
        filesWithoutSelf.put("inventory", fullFile(inventoryName, inventoryBytes));
        filesWithoutSelf.put("day", fullFile(dayName, dayBytes));
        filesWithoutSelf.put(
                "producer_envelope", fullFile(producerName, producerEnvelopeBytes));

        Map<String, Object> payloadWithoutSelf = basePayload(binding, filesWithoutSelf);
        String payloadSha256 = sha256(canonicalBytes(payloadWithoutSelf));
        byte[] dropEnvelopeBytes = buildSizedEnvelope(
                binding, filesWithoutSelf, dropName, payloadSha256);

        return new DropDocuments(
                binding,
                inventoryName,
                inventoryBytes,
                dayName,
                dayBytes,
                producerName,
                producerEnvelopeBytes,
                dropName,
                dropEnvelopeBytes,
                reservationName(binding.targetDay()));
    }

    private static byte[] buildSizedEnvelope(
            TransportBinding binding,
            Map<String, Object> filesWithoutSelf,
            String dropName,
            String payloadSha256) {
        int expectedSize = 1;
        for (int attempt = 0; attempt < 8; attempt++) {
            Map<String, Object> files = new LinkedHashMap<>(filesWithoutSelf);
            files.put("drop_envelope", Map.of(
                    "name", dropName,
                    "sha256", payloadSha256,
                    "size_bytes", expectedSize,
                    "hash_scope", DROP_HASH_SCOPE));
            Map<String, Object> document = basePayload(binding, files);
            document.put("envelope_seal", Map.of(
                    "algorithm", "SHA-256",
                    "payload_sha256", payloadSha256,
                    "canonicalization", DROP_CANONICALIZATION,
                    "sealed_at", binding.publishedAt().toString()));
            byte[] bytes = canonicalBytes(document);
            if (bytes.length == expectedSize) {
                return bytes;
            }
            expectedSize = bytes.length;
        }
        throw failure("DROP_ENVELOPE_SIZE_DID_NOT_CONVERGE");
    }

    private static Map<String, Object> basePayload(
            TransportBinding binding, Map<String, Object> files) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", DROP_ENVELOPE_SCHEMA_VERSION);
        payload.put("envelope_type", "IMMUTABLE_ONE_WAY_DRA_CRYPTO_CARRY_DAY");
        payload.put("authorization", AUTHORIZATION);
        payload.put("status", STATUS);
        payload.put("source_label", SOURCE_LABEL);
        payload.put("source_contract_sha256", SOURCE_CONTRACT_SHA256);
        payload.put("producer_id", PRODUCER_ID);
        payload.put("producer_envelope_schema_sha256", PRODUCER_ENVELOPE_SCHEMA_SHA256);
        payload.put("drop_envelope_schema_sha256", DROP_ENVELOPE_SCHEMA_SHA256);
        payload.put("source_identity", SOURCE_IDENTITY);
        payload.put("intake_identity", INTAKE_IDENTITY);
        payload.put("target_day", binding.targetDay().toString());
        payload.put(
                "first_eligible_utc_decision_day",
                binding.firstEligibleUtcDecisionDay().toString());
        payload.put("capture_deadline_utc", binding.captureDeadlineUtc().toString());
        payload.put("published_at", binding.publishedAt().toString());
        payload.put("predecessor", predecessorMap(binding));
        payload.put("files", files);
        payload.put("delivery_semantics", deliveryMap());
        payload.put("idempotency_key", idempotencyKey(binding));
        return payload;
    }

    private static Map<String, Object> predecessorMap(TransportBinding binding) {
        Map<String, Object> predecessor = new LinkedHashMap<>();
        predecessor.put("type", binding.predecessorType().name());
        predecessor.put(
                "day",
                binding.predecessorDay() == null ? null : binding.predecessorDay().toString());
        predecessor.put(
                "drop_envelope_sha256", binding.predecessorDropEnvelopeSha256());
        return predecessor;
    }

    private static Map<String, Object> fullFile(String name, byte[] bytes) {
        return Map.of(
                "name", name,
                "sha256", sha256(bytes),
                "size_bytes", bytes.length,
                "hash_scope", FULL_FILE_HASH_SCOPE);
    }

    private static Map<String, Object> deliveryMap() {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("transport", "DRA_CRYPTO_CARRY_CREATE_ONLY_ATOMIC_ONE_WAY_DROP");
        delivery.put("atomic_rename", true);
        delivery.put("overwrite", false);
        delivery.put("publisher_read_after_publish", false);
        delivery.put("symlinks", false);
        delivery.put("existing_roots_only", true);
        delivery.put("same_filesystem", true);
        delivery.put("reservation_required", true);
        delivery.put("intake_network_access", false);
        delivery.put("intake_write_access", false);
        delivery.put("canonical_state_access", false);
        delivery.put("retry", false);
        delivery.put("backfill", false);
        return delivery;
    }

    private static String idempotencyKey(TransportBinding binding) {
        return "DRA_CRYPTO_CARRY:" + binding.targetDay() + ":"
                + binding.producerEnvelopeSha256() + ":"
                + binding.predecessorDropEnvelopeSha256();
    }

    private static ProducerArtifacts validateProducerArtifacts(
            TransportBinding binding,
            byte[] inventoryBytes,
            byte[] dayBytes,
            byte[] producerEnvelopeBytes) {
        JsonNode inventory = parseCanonical(inventoryBytes, "inventory");
        requireKeys(inventory, INVENTORY_KEYS, "inventory");
        requireText(inventory, "schema_version", INVENTORY_SCHEMA_VERSION);
        requireText(inventory, "document_type", "PRIOR_CYCLE_EXPIRY_FUTURES_INVENTORY");
        requireCommon(inventory);
        requireText(inventory, "target_day", binding.targetDay().toString());
        Instant inventoryScheduled = requireInstant(inventory, "scheduled_cycle_at");
        Instant inventoryCaptured = requireInstant(inventory, "captured_at");
        Instant expectedInventorySchedule = binding.targetDay().minusDays(1)
                .atTime(LocalTime.of(1, 5)).toInstant(ZoneOffset.UTC);
        Instant targetStart = binding.targetDay().atStartOfDay().toInstant(ZoneOffset.UTC);
        if (!inventoryScheduled.equals(expectedInventorySchedule)
                || inventoryCaptured.isBefore(inventoryScheduled)
                || !inventoryCaptured.isBefore(targetStart)) {
            throw failure("INVENTORY_CLOCK_DRIFT");
        }
        verifyStandardSeal(
                inventory,
                "inventory_seal",
                INVENTORY_CANONICALIZATION,
                inventoryCaptured);

        JsonNode day = parseCanonical(dayBytes, "day");
        requireKeys(day, DAY_KEYS, "day");
        requireText(day, "schema_version", DAY_SCHEMA_VERSION);
        requireText(day, "document_type", "COMPLETE_CONFIRMED_TARGET_DAY_RAW_ATOMS");
        requireCommon(day);
        requireText(day, "target_day", binding.targetDay().toString());
        requireText(day, "inventory_sha256", sha256(inventoryBytes));
        requireText(
                day,
                "first_eligible_utc_decision_day",
                binding.firstEligibleUtcDecisionDay().toString());
        Instant dayScheduled = requireInstant(day, "scheduled_cycle_at");
        Instant dayCaptured = requireInstant(day, "captured_at");
        Instant expectedDaySchedule = binding.targetDay().plusDays(1)
                .atTime(LocalTime.of(1, 5)).toInstant(ZoneOffset.UTC);
        if (!dayScheduled.equals(expectedDaySchedule)
                || dayCaptured.isBefore(dayScheduled)
                || !dayCaptured.isBefore(binding.captureDeadlineUtc())) {
            throw failure("DAY_CLOCK_DRIFT");
        }
        verifyStandardSeal(day, "day_seal", DAY_CANONICALIZATION, dayCaptured);

        JsonNode producer = parseCanonical(producerEnvelopeBytes, "producer envelope");
        requireKeys(producer, PRODUCER_KEYS, "producer envelope");
        requireText(producer, "schema_version", PRODUCER_SCHEMA_VERSION);
        requireText(producer, "envelope_type", "OFFLINE_JAVA_PRODUCER_CORE_OUTPUT");
        requireText(producer, "authorization", AUTHORIZATION);
        requireText(producer, "status", "OFFLINE_DISABLED_NOT_REGISTERED");
        requireText(producer, "source_label", SOURCE_LABEL);
        requireText(producer, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        requireText(
                producer,
                "producer_envelope_schema_sha256",
                PRODUCER_ENVELOPE_SCHEMA_SHA256);
        requireText(producer, "producer_id", PRODUCER_ID);
        requireText(producer, "target_day", binding.targetDay().toString());
        requireText(
                producer,
                "first_eligible_utc_decision_day",
                binding.firstEligibleUtcDecisionDay().toString());
        requireText(
                producer,
                "transport_status",
                "NOT_IMPLEMENTED_CREATE_ONLY_HASH_BOUND_ONE_WAY_DROP");
        requireArtifact(producer.get("inventory"), inventoryBytes, "producer inventory");
        requireArtifact(producer.get("day"), dayBytes, "producer day");
        Instant generatedAt = requireInstant(producer, "generated_at");
        if (generatedAt.isBefore(dayCaptured)
                || !generatedAt.isBefore(binding.captureDeadlineUtc())) {
            throw failure("PRODUCER_ENVELOPE_CLOCK_DRIFT");
        }
        verifyStandardSeal(
                producer,
                "envelope_seal",
                PRODUCER_CANONICALIZATION,
                generatedAt);
        if (!sha256(producerEnvelopeBytes).equals(binding.producerEnvelopeSha256())) {
            throw failure("PRODUCER_ENVELOPE_BINDING_DRIFT");
        }
        return new ProducerArtifacts(generatedAt);
    }

    private static void requireCommon(JsonNode node) {
        requireText(node, "authorization", AUTHORIZATION);
        requireText(node, "source_label", SOURCE_LABEL);
        requireText(node, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
    }

    private static void requireArtifact(JsonNode node, byte[] bytes, String label) {
        requireKeys(node, ARTIFACT_KEYS, label);
        requireText(node, "sha256", sha256(bytes));
        JsonNode size = node.get("size_bytes");
        if (size == null || !size.isIntegralNumber() || size.intValue() != bytes.length) {
            throw failure("ARTIFACT_SIZE_DRIFT:" + label);
        }
    }

    private static void verifyStandardSeal(
            JsonNode root, String key, String canonicalization, Instant sealedAt) {
        JsonNode seal = root.get(key);
        requireKeys(seal, SEAL_KEYS, key);
        requireText(seal, "algorithm", "SHA-256");
        requireText(seal, "canonicalization", canonicalization);
        requireText(seal, "sealed_at", sealedAt.toString());
        Map<String, Object> payload = asMap(root);
        payload.remove(key);
        requireText(seal, "payload_sha256", sha256(canonicalBytes(payload)));
    }

    private static void requireBinding(TransportBinding binding) {
        if (binding == null
                || binding.targetDay() == null
                || binding.predecessorType() == null
                || binding.firstEligibleUtcDecisionDay() == null
                || binding.captureDeadlineUtc() == null
                || binding.publishedAt() == null
                || binding.producerEnvelopeSha256() == null) {
            throw failure("NULL_BINDING_FIELD");
        }
        requireSha256(binding.producerEnvelopeSha256(), false, "producer envelope");
        if (!binding.firstEligibleUtcDecisionDay().equals(binding.targetDay().plusDays(2))) {
            throw failure("FIRST_ELIGIBLE_DAY_DRIFT");
        }
        Instant expectedDeadline = binding.targetDay().plusDays(1)
                .atTime(LocalTime.of(6, 0)).toInstant(ZoneOffset.UTC);
        Instant earliestPublication = binding.targetDay().plusDays(1)
                .atTime(LocalTime.of(1, 5)).toInstant(ZoneOffset.UTC);
        if (!binding.captureDeadlineUtc().equals(expectedDeadline)
                || binding.publishedAt().isBefore(earliestPublication)
                || !binding.publishedAt().isBefore(expectedDeadline)
                || binding.publishedAt().getNano() != 0) {
            throw failure("PUBLICATION_CLOCK_DRIFT");
        }
        if (binding.predecessorType() == PredecessorType.GENESIS) {
            if (binding.predecessorDay() != null
                    || !ZERO_SHA256.equals(binding.predecessorDropEnvelopeSha256())) {
                throw failure("GENESIS_PREDECESSOR_DRIFT");
            }
        } else {
            if (!binding.targetDay().minusDays(1).equals(binding.predecessorDay())) {
                throw failure("CHAIN_PREDECESSOR_DAY_DRIFT");
            }
            requireSha256(
                    binding.predecessorDropEnvelopeSha256(), true, "predecessor envelope");
        }
    }

    private static String filePrefix(LocalDate targetDay) {
        return "okx-dra-crypto-carry-" + targetDay;
    }

    private static String reservationName(LocalDate targetDay) {
        return "." + targetDay + ".publish-reserved";
    }

    private static byte[] canonicalBytes(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsBytes(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("CANONICAL_JSON_REJECT", error);
        }
    }

    private static JsonNode parseCanonical(byte[] bytes, String label) {
        if (bytes == null || bytes.length == 0) {
            throw failure("EMPTY_BYTES:" + label);
        }
        try {
            JsonNode root = STRICT_MAPPER.readTree(bytes);
            if (root == null
                    || !root.isObject()
                    || !Arrays.equals(bytes, canonicalBytes(generic(root)))) {
                throw failure("NONCANONICAL_JSON:" + label);
            }
            return root;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("STRICT_JSON_REJECT:" + label, error);
        }
    }

    private static void requireKeys(JsonNode node, Set<String> expected, String label) {
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
        JsonNode value = node == null ? null : node.get(key);
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

    private static Instant requireInstant(JsonNode node, String key) {
        try {
            String text = requiredText(node, key);
            Instant value = Instant.parse(text);
            if (value.getNano() != 0 || !value.toString().equals(text)) {
                throw failure("TIMESTAMP_FIELD_REJECT:" + key);
            }
            return value;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("TIMESTAMP_FIELD_REJECT:" + key, error);
        }
    }

    private static void requireSha256(String value, boolean nonzero, String label) {
        if (value == null
                || !value.matches("[0-9a-f]{64}")
                || (nonzero && ZERO_SHA256.equals(value))) {
            throw failure("SHA256_REJECT:" + label);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", error);
        }
    }

    private static Object generic(JsonNode node) {
        return CANONICAL_MAPPER.convertValue(node, Object.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(JsonNode node) {
        return new LinkedHashMap<>((Map<String, Object>) generic(node));
    }

    private static IllegalArgumentException failure(String code) {
        return new IllegalArgumentException(code);
    }

    enum PredecessorType {
        GENESIS,
        CHAIN
    }

    record TransportBinding(
            LocalDate targetDay,
            PredecessorType predecessorType,
            LocalDate predecessorDay,
            String predecessorDropEnvelopeSha256,
            LocalDate firstEligibleUtcDecisionDay,
            Instant captureDeadlineUtc,
            Instant publishedAt,
            String producerEnvelopeSha256) {

        TransportBinding {
            requireBindingFields(
                    targetDay,
                    predecessorType,
                    predecessorDropEnvelopeSha256,
                    firstEligibleUtcDecisionDay,
                    captureDeadlineUtc,
                    publishedAt,
                    producerEnvelopeSha256);
        }

        private static void requireBindingFields(
                LocalDate targetDay,
                PredecessorType predecessorType,
                String predecessorDropEnvelopeSha256,
                LocalDate firstEligibleUtcDecisionDay,
                Instant captureDeadlineUtc,
                Instant publishedAt,
                String producerEnvelopeSha256) {
            if (targetDay == null
                    || predecessorType == null
                    || predecessorDropEnvelopeSha256 == null
                    || firstEligibleUtcDecisionDay == null
                    || captureDeadlineUtc == null
                    || publishedAt == null
                    || producerEnvelopeSha256 == null) {
                throw failure("NULL_BINDING_FIELD");
            }
        }
    }

    record DropDocuments(
            TransportBinding binding,
            String inventoryName,
            byte[] inventoryBytes,
            String dayName,
            byte[] dayBytes,
            String producerEnvelopeName,
            byte[] producerEnvelopeBytes,
            String dropEnvelopeName,
            byte[] dropEnvelopeBytes,
            String reservationName) {

        DropDocuments {
            inventoryBytes = inventoryBytes.clone();
            dayBytes = dayBytes.clone();
            producerEnvelopeBytes = producerEnvelopeBytes.clone();
            dropEnvelopeBytes = dropEnvelopeBytes.clone();
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
        public byte[] producerEnvelopeBytes() {
            return producerEnvelopeBytes.clone();
        }

        @Override
        public byte[] dropEnvelopeBytes() {
            return dropEnvelopeBytes.clone();
        }
    }

    static final class FileDropSink {
        private final Path privateStagingRoot;
        private final Path oneWayDropRoot;
        private final DirectoryForcer directoryForcer;

        FileDropSink(Path privateStagingRoot, Path oneWayDropRoot) {
            this(privateStagingRoot, oneWayDropRoot, FileDropSink::forceDirectory);
        }

        FileDropSink(
                Path privateStagingRoot,
                Path oneWayDropRoot,
                DirectoryForcer directoryForcer) {
            if (privateStagingRoot == null || oneWayDropRoot == null) {
                throw failure("NULL_ROOT");
            }
            if (directoryForcer == null) {
                throw failure("NULL_DIRECTORY_FORCER");
            }
            this.privateStagingRoot = privateStagingRoot.toAbsolutePath().normalize();
            this.oneWayDropRoot = oneWayDropRoot.toAbsolutePath().normalize();
            this.directoryForcer = directoryForcer;
        }

        void publish(DropDocuments documents) throws Exception {
            if (documents == null) {
                throw failure("NULL_DOCUMENTS");
            }
            requireBinding(documents.binding());
            validateRoot(privateStagingRoot, "staging");
            validateRoot(oneWayDropRoot, "drop");
            if (privateStagingRoot.equals(oneWayDropRoot)
                    || privateStagingRoot.startsWith(oneWayDropRoot)
                    || oneWayDropRoot.startsWith(privateStagingRoot)) {
                throw failure("ROOTS_NOT_DISTINCT");
            }
            FileStore stagingStore = Files.getFileStore(privateStagingRoot);
            FileStore dropStore = Files.getFileStore(oneWayDropRoot);
            if (!stagingStore.equals(dropStore)) {
                throw failure("CROSS_FILESYSTEM_REJECT");
            }
            validateDocuments(documents);

            Path stagedDay = privateStagingRoot.resolve(
                    documents.binding().targetDay().toString());
            Path targetDay = oneWayDropRoot.resolve(
                    documents.binding().targetDay().toString());
            Path reservation = oneWayDropRoot.resolve(documents.reservationName());
            rejectExisting(stagedDay, targetDay, reservation);

            Files.createDirectory(stagedDay);
            rejectLink(stagedDay);
            writeAndForce(stagedDay.resolve(documents.inventoryName()), documents.inventoryBytes());
            writeAndForce(stagedDay.resolve(documents.dayName()), documents.dayBytes());
            writeAndForce(
                    stagedDay.resolve(documents.producerEnvelopeName()),
                    documents.producerEnvelopeBytes());
            writeAndForce(
                    stagedDay.resolve(documents.dropEnvelopeName()),
                    documents.dropEnvelopeBytes());
            directoryForcer.force(stagedDay);
            writeAndForce(reservation, new byte[0]);
            directoryForcer.force(oneWayDropRoot);
            if (Files.exists(targetDay, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("OVERWRITE_REJECT");
            }
            try {
                Files.move(stagedDay, targetDay, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IllegalStateException("NON_ATOMIC_DELIVERY", error);
            }
            directoryForcer.force(oneWayDropRoot);
            // One-way boundary: deliberately no target lookup or read after publication.
        }

        private static void validateDocuments(DropDocuments documents) {
            String prefix = filePrefix(documents.binding().targetDay());
            requirePlainName(documents.inventoryName(), prefix + ".inventory.json");
            requirePlainName(documents.dayName(), prefix + ".day.json");
            requirePlainName(
                    documents.producerEnvelopeName(), prefix + ".producer-envelope.json");
            requirePlainName(documents.dropEnvelopeName(), prefix + ".drop-envelope.json");
            requirePlainName(
                    documents.reservationName(), reservationName(documents.binding().targetDay()));
            validateProducerArtifacts(
                    documents.binding(),
                    documents.inventoryBytes(),
                    documents.dayBytes(),
                    documents.producerEnvelopeBytes());
            validateDropEnvelope(documents);
        }

        private static void validateDropEnvelope(DropDocuments documents) {
            TransportBinding binding = documents.binding();
            JsonNode root = parseCanonical(documents.dropEnvelopeBytes(), "drop envelope");
            requireKeys(root, DROP_KEYS, "drop envelope");
            requireText(root, "schema_version", DROP_ENVELOPE_SCHEMA_VERSION);
            requireText(root, "envelope_type", "IMMUTABLE_ONE_WAY_DRA_CRYPTO_CARRY_DAY");
            requireText(root, "authorization", AUTHORIZATION);
            requireText(root, "status", STATUS);
            requireText(root, "source_label", SOURCE_LABEL);
            requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
            requireText(root, "producer_id", PRODUCER_ID);
            requireText(root, "producer_envelope_schema_sha256", PRODUCER_ENVELOPE_SCHEMA_SHA256);
            requireText(root, "drop_envelope_schema_sha256", DROP_ENVELOPE_SCHEMA_SHA256);
            requireText(root, "source_identity", SOURCE_IDENTITY);
            requireText(root, "intake_identity", INTAKE_IDENTITY);
            requireText(root, "target_day", binding.targetDay().toString());
            requireText(
                    root,
                    "first_eligible_utc_decision_day",
                    binding.firstEligibleUtcDecisionDay().toString());
            requireText(root, "capture_deadline_utc", binding.captureDeadlineUtc().toString());
            requireText(root, "published_at", binding.publishedAt().toString());
            requireText(root, "idempotency_key", idempotencyKey(binding));
            if (!generic(root.get("delivery_semantics")).equals(deliveryMap())) {
                throw failure("DELIVERY_SEMANTICS_DRIFT");
            }

            JsonNode predecessor = root.get("predecessor");
            requireKeys(predecessor, PREDECESSOR_KEYS, "predecessor");
            requireText(predecessor, "type", binding.predecessorType().name());
            if (binding.predecessorType() == PredecessorType.GENESIS) {
                if (predecessor.get("day") == null || !predecessor.get("day").isNull()) {
                    throw failure("GENESIS_PREDECESSOR_DAY_DRIFT");
                }
            } else {
                requireText(predecessor, "day", binding.predecessorDay().toString());
            }
            requireText(
                    predecessor,
                    "drop_envelope_sha256",
                    binding.predecessorDropEnvelopeSha256());

            JsonNode files = root.get("files");
            requireKeys(files, DROP_FILES_KEYS, "drop files");
            requireFullFile(
                    files.get("inventory"), documents.inventoryName(), documents.inventoryBytes());
            requireFullFile(files.get("day"), documents.dayName(), documents.dayBytes());
            requireFullFile(
                    files.get("producer_envelope"),
                    documents.producerEnvelopeName(),
                    documents.producerEnvelopeBytes());
            JsonNode self = files.get("drop_envelope");
            requireKeys(self, DROP_FILE_KEYS, "drop envelope file");
            requireText(self, "name", documents.dropEnvelopeName());
            requireText(self, "hash_scope", DROP_HASH_SCOPE);
            if (!self.get("size_bytes").isIntegralNumber()
                    || self.get("size_bytes").intValue()
                    != documents.dropEnvelopeBytes().length) {
                throw failure("DROP_ENVELOPE_SIZE_DRIFT");
            }

            Map<String, Object> payload = asMap(root);
            payload.remove("envelope_seal");
            @SuppressWarnings("unchecked")
            Map<String, Object> payloadFiles = new LinkedHashMap<>(
                    (Map<String, Object>) payload.get("files"));
            payloadFiles.remove("drop_envelope");
            payload.put("files", payloadFiles);
            String payloadSha256 = sha256(canonicalBytes(payload));
            requireText(self, "sha256", payloadSha256);
            JsonNode seal = root.get("envelope_seal");
            requireKeys(seal, SEAL_KEYS, "drop envelope seal");
            requireText(seal, "algorithm", "SHA-256");
            requireText(seal, "payload_sha256", payloadSha256);
            requireText(seal, "canonicalization", DROP_CANONICALIZATION);
            requireText(seal, "sealed_at", binding.publishedAt().toString());
        }

        private static void requireFullFile(JsonNode node, String name, byte[] bytes) {
            requireKeys(node, DROP_FILE_KEYS, name);
            requireText(node, "name", name);
            requireText(node, "sha256", sha256(bytes));
            requireText(node, "hash_scope", FULL_FILE_HASH_SCOPE);
            if (!node.get("size_bytes").isIntegralNumber()
                    || node.get("size_bytes").intValue() != bytes.length) {
                throw failure("FILE_SIZE_DRIFT:" + name);
            }
        }

        private static void validateRoot(Path root, String role) throws Exception {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("ROOT_NOT_EXISTING_DIRECTORY:" + role);
            }
            rejectLinkChain(root);
            String leaf = root.getFileName() == null
                    ? "" : root.getFileName().toString().toLowerCase();
            String full = root.toString().toLowerCase().replace('\\', '/');
            if (!leaf.contains("dra-crypto-carry")
                    || !leaf.contains(role)
                    || leaf.equals("staging")
                    || leaf.equals("drop")
                    || full.contains("microstructure")
                    || full.contains("candle")
                    || full.contains(".research-state")
                    || full.matches(".*/(?:state|trading|db|database)(?:/.*)?")) {
                throw failure("ROOT_SCOPE_REJECT:" + role);
            }
        }

        private static void rejectExisting(Path... paths) {
            for (Path path : paths) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure("OVERWRITE_REJECT");
                }
            }
        }

        private static void requirePlainName(String actual, String expected) {
            if (!expected.equals(actual)
                    || Path.of(actual).isAbsolute()
                    || Path.of(actual).getNameCount() != 1) {
                throw failure("FILE_NAME_DRIFT");
            }
        }

        private static void writeAndForce(Path path, byte[] bytes) throws Exception {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("OVERWRITE_REJECT");
            }
            try (FileChannel channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        }

        private static void forceDirectory(Path directory) throws Exception {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        @FunctionalInterface
        interface DirectoryForcer {
            void force(Path directory) throws Exception;
        }

        private static void rejectLinkChain(Path path) throws Exception {
            Path current = path.getRoot();
            for (Path part : path) {
                current = current == null ? part : current.resolve(part);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    rejectLink(current);
                }
            }
        }

        private static void rejectLink(Path path) throws Exception {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw failure("SYMLINK_OR_REPARSE_REJECT");
            }
        }
    }

    private record ProducerArtifacts(Instant generatedAt) {
    }
}
