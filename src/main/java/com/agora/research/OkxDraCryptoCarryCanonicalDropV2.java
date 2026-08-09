package com.agora.research;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Disabled create-only two-phase one-way drop library. */
final class OkxDraCryptoCarryCanonicalDropV2 {

    static final String INVENTORY_DROP_VERSION =
            "OKX_DRA_CRYPTO_CARRY_INVENTORY_DROP_ENVELOPE_V2";
    static final String DAY_DROP_VERSION = "OKX_DRA_CRYPTO_CARRY_DAY_DROP_ENVELOPE_V2";
    static final String INVENTORY_DROP_SCHEMA_SHA256 =
            OkxDraCryptoCarryProducerEnvelopeV2.INVENTORY_DROP_SCHEMA_SHA256;
    static final String DAY_DROP_SCHEMA_SHA256 =
            OkxDraCryptoCarryProducerEnvelopeV2.DAY_DROP_SCHEMA_SHA256;
    static final String ZERO_SHA256 = "0".repeat(64);
    private static final String FULL_FILE = "FULL_FILE_BYTES";
    private static final String SELF_HASH =
            "CANONICAL_PAYLOAD_EXCLUDING_SELF_DESCRIPTOR_AND_ENVELOPE_SEAL";
    private static final int MAX_FILE_BYTES = 2_097_152;

    private OkxDraCryptoCarryCanonicalDropV2() {
    }

    static PhaseDocuments createInventoryDrop(
            InventoryBinding binding, byte[] inventoryBytes, byte[] parentEnvelopeBytes) {
        requireInventoryBinding(binding);
        OkxDraCryptoCarryProducerEnvelopeV2.ParsedParent parent =
                OkxDraCryptoCarryProducerEnvelopeV2.validate(
                        parentEnvelopeBytes,
                        OkxDraCryptoCarryProducerEnvelopeV2.Phase.INVENTORY_PREPARED,
                        inventoryBytes, null, null, null);
        if (!parent.targetDay().equals(binding.targetDay())
                || binding.publishedAt().isBefore(parent.generatedAt())) {
            throw failure("INVENTORY_PARENT_BINDING_REJECT");
        }
        String prefix = prefix(binding.targetDay());
        String inventoryName = prefix + ".inventory.json";
        String parentName = prefix + ".inventory-parent-v2.json";
        String dropName = prefix + ".inventory-drop-v2.json";
        String reservation = "." + binding.targetDay() + ".inventory-v2.publish-reserved";
        Map<String, Object> withoutSelf = new LinkedHashMap<>();
        withoutSelf.put("inventory", fullFile(inventoryName, inventoryBytes));
        withoutSelf.put("v2_parent_envelope", fullFile(parentName, parentEnvelopeBytes));
        Map<String, Object> payload = inventoryPayload(binding, withoutSelf, reservation);
        String payloadSha = sha256(canonical(payload));
        byte[] dropBytes = sizedEnvelope(
                payload, withoutSelf, "inventory_drop_envelope", dropName, payloadSha,
                binding.publishedAt(),
                "UTF-8 compact sorted-key JSON excluding files.inventory_drop_envelope and envelope_seal");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(inventoryName, inventoryBytes.clone());
        files.put(parentName, parentEnvelopeBytes.clone());
        files.put(dropName, dropBytes);
        return new PhaseDocuments(
                Phase.PREPARE_INVENTORY, binding.targetDay(),
                binding.targetDay() + ".inventory-v2", reservation, files);
    }

    static PhaseDocuments createDayDrop(
            DayBinding binding,
            byte[] acceptedInventoryStateBytes,
            byte[] inventoryBytes,
            byte[] dayBytes,
            byte[] v1ProducerEnvelopeBytes,
            byte[] parentEnvelopeBytes) {
        requireDayBinding(binding);
        String stateSha = sha256(acceptedInventoryStateBytes);
        if (!stateSha.equals(binding.acceptedInventoryStateSha256())) {
            throw failure("ACCEPTED_INVENTORY_STATE_BINDING_REJECT");
        }
        OkxDraCryptoCarryProducerEnvelopeV2.ParsedParent parent =
                OkxDraCryptoCarryProducerEnvelopeV2.validate(
                        parentEnvelopeBytes,
                        OkxDraCryptoCarryProducerEnvelopeV2.Phase.DAY_FINALIZED,
                        inventoryBytes, dayBytes, v1ProducerEnvelopeBytes,
                        acceptedInventoryStateBytes);
        if (!parent.targetDay().equals(binding.targetDay())
                || binding.publishedAt().isBefore(parent.generatedAt())) {
            throw failure("DAY_PARENT_BINDING_REJECT");
        }
        String prefix = prefix(binding.targetDay());
        String inventoryName = prefix + ".inventory.json";
        String dayName = prefix + ".day.json";
        String v1ProducerName = prefix + ".producer-envelope.json";
        String parentName = prefix + ".day-parent-v2.json";
        String dropName = prefix + ".day-drop-v2.json";
        String reservation = "." + binding.targetDay() + ".day-v2.publish-reserved";
        Map<String, Object> withoutSelf = new LinkedHashMap<>();
        withoutSelf.put("inventory", fullFile(inventoryName, inventoryBytes));
        withoutSelf.put("day", fullFile(dayName, dayBytes));
        withoutSelf.put("v1_producer_envelope",
                fullFile(v1ProducerName, v1ProducerEnvelopeBytes));
        withoutSelf.put("v2_parent_envelope", fullFile(parentName, parentEnvelopeBytes));
        Map<String, Object> payload = dayPayload(binding, withoutSelf, reservation);
        String payloadSha = sha256(canonical(payload));
        byte[] dropBytes = sizedEnvelope(
                payload, withoutSelf, "day_drop_envelope", dropName, payloadSha,
                binding.publishedAt(),
                "UTF-8 compact sorted-key JSON excluding files.day_drop_envelope and envelope_seal");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(inventoryName, inventoryBytes.clone());
        files.put(dayName, dayBytes.clone());
        files.put(v1ProducerName, v1ProducerEnvelopeBytes.clone());
        files.put(parentName, parentEnvelopeBytes.clone());
        files.put(dropName, dropBytes);
        return new PhaseDocuments(
                Phase.FINALIZE_DAY, binding.targetDay(),
                binding.targetDay() + ".day-v2", reservation, files);
    }

    private static Map<String, Object> inventoryPayload(
            InventoryBinding binding, Map<String, Object> files, String reservation) {
        Map<String, Object> value = common(
                INVENTORY_DROP_VERSION,
                "IMMUTABLE_ONE_WAY_DRA_CRYPTO_CARRY_INVENTORY_ONLY",
                "PREPARE_INVENTORY",
                "inventory_drop_schema_sha256",
                INVENTORY_DROP_SCHEMA_SHA256,
                binding.targetDay(), binding.publishedAt());
        value.put("files", files);
        value.put("delivery_semantics", delivery("PREPARE_INVENTORY"));
        value.put("reservation_name", reservation);
        value.put("idempotency_key", "DRA_CRYPTO_CARRY_V2:INVENTORY:"
                + binding.targetDay() + ":" + binding.inventorySha256());
        return value;
    }

    private static Map<String, Object> dayPayload(
            DayBinding binding, Map<String, Object> files, String reservation) {
        Map<String, Object> value = common(
                DAY_DROP_VERSION,
                "IMMUTABLE_ONE_WAY_DRA_CRYPTO_CARRY_COMPLETE_DAY_V2",
                "FINALIZE_DAY", "day_drop_schema_sha256", DAY_DROP_SCHEMA_SHA256,
                binding.targetDay(), binding.publishedAt());
        value.put("accepted_inventory_state_sha256",
                binding.acceptedInventoryStateSha256());
        value.put("first_eligible_utc_decision_day",
                binding.firstEligibleUtcDecisionDay().toString());
        value.put("capture_deadline_utc", binding.captureDeadlineUtc().toString());
        value.put("predecessor", predecessor(binding));
        value.put("files", files);
        value.put("delivery_semantics", delivery("FINALIZE_DAY"));
        value.put("reservation_name", reservation);
        value.put("idempotency_key", "DRA_CRYPTO_CARRY_V2:DAY:"
                + binding.targetDay() + ":" + binding.acceptedInventoryStateSha256()
                + ":" + binding.predecessorDayDropEnvelopeSha256());
        return value;
    }

    private static Map<String, Object> common(
            String version, String type, String phase, String schemaKey,
            String schemaSha, LocalDate targetDay, Instant publishedAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", version);
        value.put("envelope_type", type);
        value.put("authorization", OkxDraCryptoCarryProducerEnvelopeV2.AUTHORIZATION);
        value.put("status", "OFFLINE_DISABLED_NOT_DEPLOYED");
        value.put("phase", phase);
        value.put("source_label", OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_LABEL);
        value.put("source_contract_sha256",
                OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_CONTRACT_SHA256);
        value.put("producer_envelope_schema_sha256",
                OkxDraCryptoCarryProducerEnvelopeV2.PRODUCER_SCHEMA_SHA256);
        value.put(schemaKey, schemaSha);
        value.put("source_identity", OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_IDENTITY);
        value.put("source_group", OkxDraCryptoCarryProducerEnvelopeV2.SOURCE_GROUP);
        value.put("intake_identity", OkxDraCryptoCarryProducerEnvelopeV2.INTAKE_IDENTITY);
        value.put("target_day", targetDay.toString());
        value.put("published_at", publishedAt.toString());
        return value;
    }

    private static Map<String, Object> predecessor(DayBinding binding) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", binding.predecessorType().name());
        value.put("day", binding.predecessorDay() == null
                ? null : binding.predecessorDay().toString());
        value.put("day_drop_envelope_sha256",
                binding.predecessorDayDropEnvelopeSha256());
        return value;
    }

    private static Map<String, Object> delivery(String phase) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("transport", "DRA_CRYPTO_CARRY_V2_CREATE_ONLY_ATOMIC_ONE_WAY_DROP");
        value.put("phase", phase);
        value.put("atomic_rename", true);
        value.put("overwrite", false);
        value.put("publisher_read_after_publish", false);
        value.put("symlinks", false);
        value.put("existing_roots_only", true);
        value.put("same_filesystem", true);
        value.put("reservation_required", true);
        value.put("intake_network_access", false);
        value.put("intake_write_access", false);
        value.put("canonical_state_access", false);
        value.put("retry", false);
        value.put("backfill", false);
        return value;
    }

    private static Map<String, Object> fullFile(String name, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw failure("FILE_SIZE_REJECT:" + name);
        }
        return Map.of(
                "name", name,
                "sha256", sha256(bytes),
                "size_bytes", bytes.length,
                "hash_scope", FULL_FILE);
    }

    @SuppressWarnings("unchecked")
    private static byte[] sizedEnvelope(
            Map<String, Object> basePayload,
            Map<String, Object> filesWithoutSelf,
            String selfKey,
            String selfName,
            String payloadSha,
            Instant sealedAt,
            String canonicalization) {
        int expectedSize = 1;
        for (int attempt = 0; attempt < 8; attempt++) {
            Map<String, Object> files = new LinkedHashMap<>(filesWithoutSelf);
            files.put(selfKey, Map.of(
                    "name", selfName,
                    "sha256", payloadSha,
                    "size_bytes", expectedSize,
                    "hash_scope", SELF_HASH));
            Map<String, Object> document = new LinkedHashMap<>(basePayload);
            document.put("files", files);
            document.put("envelope_seal", Map.of(
                    "algorithm", "SHA-256",
                    "payload_sha256", payloadSha,
                    "canonicalization", canonicalization,
                    "sealed_at", sealedAt.toString()));
            byte[] bytes = canonical(document);
            if (bytes.length == expectedSize) {
                return bytes;
            }
            expectedSize = bytes.length;
        }
        throw failure("DROP_ENVELOPE_SIZE_DID_NOT_CONVERGE");
    }

    private static void requireInventoryBinding(InventoryBinding binding) {
        if (binding == null || binding.targetDay() == null
                || binding.publishedAt() == null || binding.inventorySha256() == null) {
            throw failure("NULL_INVENTORY_BINDING");
        }
        requireSha(binding.inventorySha256(), true, "inventory");
        Instant start = binding.targetDay().minusDays(1)
                .atTime(LocalTime.of(1, 5)).toInstant(ZoneOffset.UTC);
        Instant end = binding.targetDay().atStartOfDay().toInstant(ZoneOffset.UTC);
        if (binding.publishedAt().getNano() != 0
                || binding.publishedAt().isBefore(start)
                || !binding.publishedAt().isBefore(end)) {
            throw failure("INVENTORY_PUBLICATION_CLOCK_REJECT");
        }
    }

    private static void requireDayBinding(DayBinding binding) {
        if (binding == null || binding.targetDay() == null
                || binding.acceptedInventoryStateSha256() == null
                || binding.firstEligibleUtcDecisionDay() == null
                || binding.captureDeadlineUtc() == null
                || binding.publishedAt() == null
                || binding.predecessorType() == null
                || binding.predecessorDayDropEnvelopeSha256() == null) {
            throw failure("NULL_DAY_BINDING");
        }
        requireSha(binding.acceptedInventoryStateSha256(), true, "accepted state");
        if (!binding.firstEligibleUtcDecisionDay().equals(binding.targetDay().plusDays(2))) {
            throw failure("D_PLUS_2_REJECT");
        }
        Instant start = binding.targetDay().plusDays(1)
                .atTime(LocalTime.of(1, 5)).toInstant(ZoneOffset.UTC);
        Instant deadline = binding.targetDay().plusDays(1)
                .atTime(LocalTime.of(6, 0)).toInstant(ZoneOffset.UTC);
        if (!binding.captureDeadlineUtc().equals(deadline)
                || binding.publishedAt().getNano() != 0
                || binding.publishedAt().isBefore(start)
                || !binding.publishedAt().isBefore(deadline)) {
            throw failure("DAY_PUBLICATION_CLOCK_REJECT");
        }
        if (binding.predecessorType() == PredecessorType.GENESIS) {
            if (binding.predecessorDay() != null
                    || !ZERO_SHA256.equals(binding.predecessorDayDropEnvelopeSha256())) {
                throw failure("GENESIS_PREDECESSOR_REJECT");
            }
        } else {
            if (!binding.targetDay().minusDays(1).equals(binding.predecessorDay())) {
                throw failure("CHAIN_DAY_REJECT");
            }
            requireSha(binding.predecessorDayDropEnvelopeSha256(), true,
                    "predecessor day drop");
        }
    }

    private static byte[] canonical(Object value) {
        return OkxDraCryptoCarryProducerEnvelopeV2.canonicalBytes(value);
    }

    private static String sha256(byte[] bytes) {
        return OkxDraCryptoCarryProducerEnvelopeV2.sha256(bytes);
    }

    private static void requireSha(String value, boolean nonzero, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")
                || (nonzero && ZERO_SHA256.equals(value))) {
            throw failure("SHA256_REJECT:" + label);
        }
    }

    private static String prefix(LocalDate day) {
        return "okx-dra-crypto-carry-" + day;
    }

    private static IllegalArgumentException failure(String code) {
        return new IllegalArgumentException(code);
    }

    enum Phase {
        PREPARE_INVENTORY,
        FINALIZE_DAY
    }

    enum PredecessorType {
        GENESIS,
        CHAIN
    }

    record InventoryBinding(LocalDate targetDay, Instant publishedAt, String inventorySha256) {
    }

    record DayBinding(
            LocalDate targetDay,
            String acceptedInventoryStateSha256,
            LocalDate firstEligibleUtcDecisionDay,
            Instant captureDeadlineUtc,
            Instant publishedAt,
            PredecessorType predecessorType,
            LocalDate predecessorDay,
            String predecessorDayDropEnvelopeSha256) {
    }

    record PhaseDocuments(
            Phase phase,
            LocalDate targetDay,
            String directoryName,
            String reservationName,
            Map<String, byte[]> files) {
        PhaseDocuments {
            if (phase == null || targetDay == null || directoryName == null
                    || reservationName == null || files == null) {
                throw failure("NULL_PHASE_DOCUMENTS");
            }
            String expectedDirectory = targetDay
                    + (phase == Phase.PREPARE_INVENTORY ? ".inventory-v2" : ".day-v2");
            String expectedReservation = "." + targetDay
                    + (phase == Phase.PREPARE_INVENTORY
                    ? ".inventory-v2.publish-reserved"
                    : ".day-v2.publish-reserved");
            String expectedPrefix = prefix(targetDay);
            Set<String> expectedNames = phase == Phase.PREPARE_INVENTORY
                    ? Set.of(
                    expectedPrefix + ".inventory.json",
                    expectedPrefix + ".inventory-parent-v2.json",
                    expectedPrefix + ".inventory-drop-v2.json")
                    : Set.of(
                    expectedPrefix + ".inventory.json",
                    expectedPrefix + ".day.json",
                    expectedPrefix + ".producer-envelope.json",
                    expectedPrefix + ".day-parent-v2.json",
                    expectedPrefix + ".day-drop-v2.json");
            if (!directoryName.equals(expectedDirectory)
                    || !reservationName.equals(expectedReservation)
                    || !files.keySet().equals(expectedNames)) {
                throw failure("PHASE_DOCUMENT_SCOPE_REJECT");
            }
            Map<String, byte[]> copy = new LinkedHashMap<>();
            files.forEach((name, bytes) -> {
                if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
                    throw failure("PHASE_DOCUMENT_FILE_REJECT:" + name);
                }
                copy.put(name, bytes.clone());
            });
            files = Map.copyOf(copy);
        }

        @Override
        public Map<String, byte[]> files() {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            files.forEach((name, bytes) -> copy.put(name, bytes.clone()));
            return Map.copyOf(copy);
        }
    }

    static final class FileDropSink {
        private final Path inventoryStagingRoot;
        private final Path inventoryDropRoot;
        private final Path dayStagingRoot;
        private final Path dayDropRoot;
        private final DirectoryForcer directoryForcer;

        FileDropSink(
                Path inventoryStagingRoot,
                Path inventoryDropRoot,
                Path dayStagingRoot,
                Path dayDropRoot) throws Exception {
            this(inventoryStagingRoot, inventoryDropRoot, dayStagingRoot, dayDropRoot,
                    FileDropSink::forceRealDirectory);
        }

        FileDropSink(
                Path inventoryStagingRoot,
                Path inventoryDropRoot,
                Path dayStagingRoot,
                Path dayDropRoot,
                DirectoryForcer directoryForcer) throws Exception {
            this.inventoryStagingRoot = validateRoot(
                    inventoryStagingRoot, "inventory", "staging");
            this.inventoryDropRoot = validateRoot(inventoryDropRoot, "inventory", "drop");
            this.dayStagingRoot = validateRoot(dayStagingRoot, "day", "staging");
            this.dayDropRoot = validateRoot(dayDropRoot, "day", "drop");
            this.directoryForcer = directoryForcer;
            if (directoryForcer == null) {
                throw failure("NULL_DIRECTORY_FORCER");
            }
            List<Path> roots = List.of(this.inventoryStagingRoot, this.inventoryDropRoot,
                    this.dayStagingRoot, this.dayDropRoot);
            if (roots.stream().distinct().count() != roots.size()) {
                throw failure("DISTINCT_ROOTS_REQUIRED");
            }
            FileStore first = Files.getFileStore(roots.get(0));
            for (Path root : roots) {
                if (!first.equals(Files.getFileStore(root))) {
                    throw failure("SAME_FILESYSTEM_REQUIRED");
                }
            }
        }

        Path publish(PhaseDocuments documents) throws Exception {
            if (documents == null) {
                throw failure("NULL_DOCUMENTS");
            }
            Path stagingRoot = documents.phase() == Phase.PREPARE_INVENTORY
                    ? inventoryStagingRoot : dayStagingRoot;
            Path dropRoot = documents.phase() == Phase.PREPARE_INVENTORY
                    ? inventoryDropRoot : dayDropRoot;
            Path reservation = dropRoot.resolve(documents.reservationName());
            Path staging = stagingRoot.resolve(documents.directoryName());
            Path published = dropRoot.resolve(documents.directoryName());
            rejectExisting(reservation);
            rejectExisting(staging);
            rejectExisting(published);
            writeCreateNew(reservation, new byte[0]);
            Files.createDirectory(staging);
            directoryForcer.force(stagingRoot);
            for (Map.Entry<String, byte[]> entry : documents.files().entrySet()) {
                writeCreateNew(staging.resolve(entry.getKey()), entry.getValue());
            }
            directoryForcer.force(staging);
            try {
                Files.move(staging, published, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IllegalStateException("ATOMIC_MOVE_REQUIRED", error);
            }
            directoryForcer.force(dropRoot);
            return published;
        }

        private static Path validateRoot(Path supplied, String phase, String role)
                throws Exception {
            if (supplied == null) {
                throw failure("NULL_ROOT");
            }
            Path root = supplied.toAbsolutePath().normalize();
            requireDirectory(root);
            String leaf = root.getFileName() == null
                    ? "" : root.getFileName().toString().toLowerCase();
            String full = root.toString().toLowerCase().replace('\\', '/');
            if (!leaf.contains("dra-crypto-carry-v2")
                    || !leaf.contains(phase)
                    || !leaf.contains(role)
                    || full.contains("microstructure")
                    || full.contains("candle")
                    || full.contains(".research-state")
                    || full.matches(".*/(?:state|trading|db|database)(?:/.*)?")) {
                throw failure("ROOT_SCOPE_REJECT:" + phase + ":" + role);
            }
            return root;
        }

        private static void requireDirectory(Path path) throws Exception {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("DIRECTORY_REQUIRED");
            }
            rejectLinkChain(path);
        }

        private static void rejectLinkChain(Path path) throws Exception {
            Path current = path.getRoot();
            for (Path part : path) {
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

        private static void rejectExisting(Path path) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("CREATE_ONLY_CONFLICT:" + path.getFileName());
            }
        }

        private static void writeCreateNew(Path path, byte[] bytes) throws Exception {
            try (FileChannel channel = FileChannel.open(
                    path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        }

        private static void forceRealDirectory(Path path) throws Exception {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        @FunctionalInterface
        interface DirectoryForcer {
            void force(Path path) throws Exception;
        }
    }
}
