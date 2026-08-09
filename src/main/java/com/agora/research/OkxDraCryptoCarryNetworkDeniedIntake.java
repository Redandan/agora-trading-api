package com.agora.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Independent, network-denied, read-only validator for one immutable source day. */
final class OkxDraCryptoCarryNetworkDeniedIntake {

    static final String VALID_STATUS = "VALID_OFFLINE_DROP_NOT_CANONICAL";

    private static final String AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE";
    private static final String SOURCE_LABEL =
            "LAGGED_OKX_BTC_USDT_EXPIRY_FUTURES_BASIS_ATOMS_V1";
    private static final String SOURCE_CONTRACT_SHA256 =
            "0944ab401717360f6eccc31ab967461af7ebc8122f7b77ee7b1f46eaa8fac48e";
    private static final String INVENTORY_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_EXPIRY_FUTURES_INVENTORY_V1";
    private static final String INVENTORY_SCHEMA_SHA256 =
            "8dd38f2ea2e73f236f56416aa1db86f6f82818ed7d8a9f69738b194c1965b340";
    private static final String DAY_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_BASIS_ATOMS_DAY_V1";
    private static final String DAY_SCHEMA_SHA256 =
            "1028d4b6f53cae6ad096038142173b650726fe744d358c2c16c7c57bc32dd8d8";
    private static final String PRODUCER_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_PRODUCER_ENVELOPE_V1";
    private static final String PRODUCER_SCHEMA_SHA256 =
            "e39263edd8362e91722134aefd11ee04f76def8b4d409aa01f396a2a481ea6d5";
    private static final String PRODUCER_ID =
            "OKX_DRA_CRYPTO_CARRY_JAVA_PRODUCER_CORE_V1";
    private static final String DROP_SCHEMA_VERSION =
            "OKX_DRA_CRYPTO_CARRY_DROP_ENVELOPE_V1";
    private static final String DROP_SCHEMA_SHA256 =
            "c3881cfc36f79168efd41a579fdb4f8e207fee0ca33b15b2e0b39c1e6bd43ee0";
    private static final String ZERO_SHA256 = "0".repeat(64);
    private static final int MAX_FILE_BYTES = 2_097_152;

    private static final String INVENTORY_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding inventory_seal";
    private static final String DAY_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding day_seal";
    private static final String PRODUCER_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding envelope_seal";
    private static final String DROP_CANONICALIZATION =
            "UTF-8 compact sorted-key JSON excluding files.drop_envelope and envelope_seal";

    private static final Pattern EXPIRY_INST_ID = Pattern.compile("BTC-USDT-[0-9]{6}");
    private static final Pattern MILLIS = Pattern.compile("(?:0|[1-9][0-9]*)");
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile(
            "(?:[1-9][0-9]*(?:\\.[0-9]+)?|0\\.[0-9]*[1-9][0-9]*)");
    private static final Pattern NONNEGATIVE_DECIMAL = Pattern.compile(
            "(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");

    private static final Set<String> INVENTORY_KEYS = Set.of(
            "schema_version", "document_type", "authorization", "source_label",
            "source_contract_sha256", "target_day", "scheduled_cycle_at",
            "captured_at", "request", "inventory_count", "instruments",
            "inventory_seal");
    private static final Set<String> INSTRUMENT_KEYS = Set.of(
            "instId", "instType", "instFamily", "uly", "ctType", "settleCcy",
            "state", "ruleType", "listTime", "expTime");
    private static final Set<String> DAY_KEYS = Set.of(
            "schema_version", "document_type", "authorization", "source_label",
            "source_contract_sha256", "inventory_schema_sha256", "day_schema_sha256",
            "inventory_sha256", "target_day", "scheduled_cycle_at", "captured_at",
            "first_eligible_utc_decision_day", "requests", "expected_instrument_count",
            "observed_instrument_count", "cache_order_semantics", "futures", "index",
            "eligibility", "day_seal");
    private static final Set<String> ATOM_KEYS = Set.of("instId", "row");
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
    private static final Set<String> DELIVERY_KEYS = Set.of(
            "transport", "atomic_rename", "overwrite", "publisher_read_after_publish",
            "symlinks", "existing_roots_only", "same_filesystem",
            "reservation_required", "intake_network_access", "intake_write_access",
            "canonical_state_access", "retry", "backfill");

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    private OkxDraCryptoCarryNetworkDeniedIntake() {
    }

    static ValidationResult validate(IntakeBinding binding, Path oneWayDropRoot)
            throws Exception {
        requireBinding(binding);
        Path root = validateDropRoot(oneWayDropRoot);
        String prefix = filePrefix(binding.targetDay());
        String inventoryName = prefix + ".inventory.json";
        String dayName = prefix + ".day.json";
        String producerName = prefix + ".producer-envelope.json";
        String dropName = prefix + ".drop-envelope.json";
        Set<String> expectedNames = Set.of(inventoryName, dayName, producerName, dropName);

        Path reservation = root.resolve(reservationName(binding.targetDay()));
        requireRegularFile(reservation, true, "reservation");
        if (Files.size(reservation) != 0L) {
            throw failure("RESERVATION_NOT_ZERO_BYTES");
        }
        Path dayDirectory = root.resolve(binding.targetDay().toString());
        requireDirectory(dayDirectory, "day directory");

        Map<String, Path> children = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(dayDirectory)) {
            stream.forEach(path -> {
                String name = path.getFileName().toString();
                if (children.put(name, path) != null) {
                    throw failure("DUPLICATE_DIRECTORY_ENTRY");
                }
            });
        }
        if (!children.keySet().equals(expectedNames)) {
            throw failure("DAY_DIRECTORY_ENTRY_SET_REJECT");
        }
        for (Map.Entry<String, Path> entry : children.entrySet()) {
            requireRegularFile(entry.getValue(), false, entry.getKey());
        }

        byte[] inventoryBytes = readBounded(children.get(inventoryName), inventoryName);
        byte[] dayBytes = readBounded(children.get(dayName), dayName);
        byte[] producerBytes = readBounded(children.get(producerName), producerName);
        byte[] dropBytes = readBounded(children.get(dropName), dropName);

        InventoryDocument inventory = validateInventory(binding, inventoryBytes);
        DayDocument day = validateDay(binding, inventory, inventoryBytes, dayBytes);
        ProducerDocument producer = validateProducer(
                binding, day, inventoryBytes, dayBytes, producerBytes);
        validateDrop(
                binding,
                producer,
                inventoryName,
                inventoryBytes,
                dayName,
                dayBytes,
                producerName,
                producerBytes,
                dropName,
                dropBytes);

        Map<String, String> hashes = Map.of(
                inventoryName, sha256(inventoryBytes),
                dayName, sha256(dayBytes),
                producerName, sha256(producerBytes),
                dropName, sha256(dropBytes));
        Map<String, Integer> sizes = Map.of(
                inventoryName, inventoryBytes.length,
                dayName, dayBytes.length,
                producerName, producerBytes.length,
                dropName, dropBytes.length);
        return new ValidationResult(
                VALID_STATUS,
                binding.targetDay(),
                binding.firstEligibleUtcDecisionDay(),
                binding.publishedAt(),
                binding.predecessorType(),
                binding.predecessorDay(),
                binding.predecessorDropEnvelopeSha256(),
                hashes,
                sizes);
    }

    private static InventoryDocument validateInventory(
            IntakeBinding binding, byte[] bytes) {
        JsonNode root = parseCanonical(bytes, "inventory");
        requireKeys(root, INVENTORY_KEYS, "inventory");
        requireText(root, "schema_version", INVENTORY_SCHEMA_VERSION);
        requireText(root, "document_type", "PRIOR_CYCLE_EXPIRY_FUTURES_INVENTORY");
        requireCommon(root);
        requireText(root, "target_day", binding.targetDay().toString());
        if (!generic(root.get("request")).equals(inventoryRequestMap())) {
            throw failure("INVENTORY_REQUEST_DRIFT");
        }
        Instant scheduled = requireInstant(root, "scheduled_cycle_at");
        Instant captured = requireInstant(root, "captured_at");
        Instant expectedSchedule = binding.targetDay().minusDays(1)
                .atTime(LocalTime.of(1, 5)).toInstant(ZoneOffset.UTC);
        Instant targetStart = binding.targetDay().atStartOfDay().toInstant(ZoneOffset.UTC);
        if (!scheduled.equals(expectedSchedule)
                || captured.isBefore(scheduled)
                || !captured.isBefore(targetStart)) {
            throw failure("INVENTORY_CLOCK_DRIFT");
        }
        JsonNode instruments = root.get("instruments");
        JsonNode count = root.get("inventory_count");
        if (instruments == null
                || !instruments.isArray()
                || instruments.isEmpty()
                || count == null
                || !count.isIntegralNumber()
                || count.intValue() != instruments.size()) {
            throw failure("INVENTORY_COUNT_REJECT");
        }
        List<String> instIds = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        Instant targetEnd = targetStart.plus(Duration.ofDays(1));
        for (JsonNode instrument : instruments) {
            requireKeys(instrument, INSTRUMENT_KEYS, "inventory instrument");
            requireText(instrument, "instType", "FUTURES");
            requireText(instrument, "instFamily", "BTC-USDT");
            requireText(instrument, "uly", "BTC-USDT");
            requireText(instrument, "ctType", "linear");
            requireText(instrument, "settleCcy", "USDT");
            requireText(instrument, "state", "live");
            requireText(instrument, "ruleType", "normal");
            String instId = requiredText(instrument, "instId");
            if (!EXPIRY_INST_ID.matcher(instId).matches() || !unique.add(instId)) {
                throw failure("INSTRUMENT_ID_REJECT");
            }
            long listTime = parseMillis(requiredText(instrument, "listTime"));
            long expTime = parseMillis(requiredText(instrument, "expTime"));
            if (listTime > captured.toEpochMilli() || expTime <= targetEnd.toEpochMilli()) {
                throw failure("INSTRUMENT_TIME_REJECT");
            }
            instIds.add(instId);
        }
        if (!instIds.equals(instIds.stream().sorted().toList())) {
            throw failure("INVENTORY_NOT_SORTED");
        }
        verifyStandardSeal(
                root, "inventory_seal", INVENTORY_CANONICALIZATION, captured);
        return new InventoryDocument(instIds, captured);
    }

    private static DayDocument validateDay(
            IntakeBinding binding,
            InventoryDocument inventory,
            byte[] inventoryBytes,
            byte[] dayBytes) {
        JsonNode root = parseCanonical(dayBytes, "day");
        requireKeys(root, DAY_KEYS, "day");
        requireText(root, "schema_version", DAY_SCHEMA_VERSION);
        requireText(root, "document_type", "COMPLETE_CONFIRMED_TARGET_DAY_RAW_ATOMS");
        requireCommon(root);
        requireText(root, "inventory_schema_sha256", INVENTORY_SCHEMA_SHA256);
        requireText(root, "day_schema_sha256", DAY_SCHEMA_SHA256);
        requireText(root, "inventory_sha256", sha256(inventoryBytes));
        requireText(root, "target_day", binding.targetDay().toString());
        requireText(
                root,
                "first_eligible_utc_decision_day",
                binding.firstEligibleUtcDecisionDay().toString());
        requireText(
                root,
                "cache_order_semantics",
                "VALIDATE_COMPLETE_SET_THEN_SORT_BY_FROZEN_INST_ID");
        if (!generic(root.get("requests")).equals(dayRequestMap())
                || !generic(root.get("eligibility")).equals(
                        eligibilityMap(binding.firstEligibleUtcDecisionDay()))) {
            throw failure("DAY_FIXED_CONTRACT_DRIFT");
        }
        Instant scheduled = requireInstant(root, "scheduled_cycle_at");
        Instant captured = requireInstant(root, "captured_at");
        Instant expectedSchedule = binding.targetDay().plusDays(1)
                .atTime(LocalTime.of(1, 5)).toInstant(ZoneOffset.UTC);
        if (!scheduled.equals(expectedSchedule)
                || captured.isBefore(scheduled)
                || !captured.isBefore(binding.captureDeadlineUtc())) {
            throw failure("DAY_CLOCK_DRIFT");
        }
        JsonNode futures = root.get("futures");
        int expectedCount = requiredPositiveInt(root, "expected_instrument_count");
        int observedCount = requiredPositiveInt(root, "observed_instrument_count");
        if (futures == null
                || !futures.isArray()
                || expectedCount != inventory.instIds().size()
                || observedCount != futures.size()) {
            throw failure("DAY_COVERAGE_REJECT");
        }
        String targetTimestamp = Long.toString(
                binding.targetDay().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
        List<String> observed = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode atom : futures) {
            requireKeys(atom, ATOM_KEYS, "futures atom");
            String instId = requiredText(atom, "instId");
            if (!unique.add(instId)) {
                throw failure("DUPLICATE_FUTURES_ATOM");
            }
            validateRow(atom.get("row"), 9, targetTimestamp, true, instId);
            observed.add(instId);
        }
        if (!observed.equals(inventory.instIds())) {
            throw failure("DAY_COVERAGE_REJECT");
        }
        JsonNode index = root.get("index");
        requireKeys(index, ATOM_KEYS, "index atom");
        requireText(index, "instId", "BTC-USDT");
        validateRow(index.get("row"), 6, targetTimestamp, false, "BTC-USDT");
        verifyStandardSeal(root, "day_seal", DAY_CANONICALIZATION, captured);
        return new DayDocument(captured);
    }

    private static ProducerDocument validateProducer(
            IntakeBinding binding,
            DayDocument day,
            byte[] inventoryBytes,
            byte[] dayBytes,
            byte[] producerBytes) {
        JsonNode root = parseCanonical(producerBytes, "producer envelope");
        requireKeys(root, PRODUCER_KEYS, "producer envelope");
        requireText(root, "schema_version", PRODUCER_SCHEMA_VERSION);
        requireText(root, "envelope_type", "OFFLINE_JAVA_PRODUCER_CORE_OUTPUT");
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "status", "OFFLINE_DISABLED_NOT_REGISTERED");
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        requireText(root, "producer_envelope_schema_sha256", PRODUCER_SCHEMA_SHA256);
        requireText(root, "producer_id", PRODUCER_ID);
        requireText(root, "target_day", binding.targetDay().toString());
        requireText(
                root,
                "first_eligible_utc_decision_day",
                binding.firstEligibleUtcDecisionDay().toString());
        requireText(
                root,
                "transport_status",
                "NOT_IMPLEMENTED_CREATE_ONLY_HASH_BOUND_ONE_WAY_DROP");
        requireArtifact(root.get("inventory"), inventoryBytes, "producer inventory");
        requireArtifact(root.get("day"), dayBytes, "producer day");
        Instant generatedAt = requireInstant(root, "generated_at");
        if (generatedAt.isBefore(day.capturedAt())
                || !generatedAt.isBefore(binding.captureDeadlineUtc())) {
            throw failure("PRODUCER_CLOCK_DRIFT");
        }
        verifyStandardSeal(
                root, "envelope_seal", PRODUCER_CANONICALIZATION, generatedAt);
        String producerSha256 = sha256(producerBytes);
        if (!producerSha256.equals(binding.producerEnvelopeSha256())) {
            throw failure("PRODUCER_BINDING_DRIFT");
        }
        return new ProducerDocument(generatedAt, producerSha256);
    }

    private static void validateDrop(
            IntakeBinding binding,
            ProducerDocument producer,
            String inventoryName,
            byte[] inventoryBytes,
            String dayName,
            byte[] dayBytes,
            String producerName,
            byte[] producerBytes,
            String dropName,
            byte[] dropBytes) {
        JsonNode root = parseCanonical(dropBytes, "drop envelope");
        requireKeys(root, DROP_KEYS, "drop envelope");
        requireText(root, "schema_version", DROP_SCHEMA_VERSION);
        requireText(root, "envelope_type", "IMMUTABLE_ONE_WAY_DRA_CRYPTO_CARRY_DAY");
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "status", "OFFLINE_DISABLED_NOT_DEPLOYED");
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        requireText(root, "producer_id", PRODUCER_ID);
        requireText(root, "producer_envelope_schema_sha256", PRODUCER_SCHEMA_SHA256);
        requireText(root, "drop_envelope_schema_sha256", DROP_SCHEMA_SHA256);
        requireText(root, "source_identity", "agora-evidence-source");
        requireText(root, "intake_identity", "agora-research");
        requireText(root, "target_day", binding.targetDay().toString());
        requireText(
                root,
                "first_eligible_utc_decision_day",
                binding.firstEligibleUtcDecisionDay().toString());
        requireText(root, "capture_deadline_utc", binding.captureDeadlineUtc().toString());
        requireText(root, "published_at", binding.publishedAt().toString());
        if (binding.publishedAt().isBefore(producer.generatedAt())
                || !binding.publishedAt().isBefore(binding.captureDeadlineUtc())) {
            throw failure("PUBLICATION_CLOCK_DRIFT");
        }
        validatePredecessor(root.get("predecessor"), binding);
        if (!generic(root.get("delivery_semantics")).equals(deliveryMap())) {
            throw failure("DELIVERY_SEMANTICS_DRIFT");
        }
        requireText(
                root,
                "idempotency_key",
                "DRA_CRYPTO_CARRY:" + binding.targetDay() + ":"
                        + producer.sha256() + ":"
                        + binding.predecessorDropEnvelopeSha256());

        JsonNode files = root.get("files");
        requireKeys(files, DROP_FILES_KEYS, "drop files");
        validateFullFile(files.get("inventory"), inventoryName, inventoryBytes);
        validateFullFile(files.get("day"), dayName, dayBytes);
        validateFullFile(files.get("producer_envelope"), producerName, producerBytes);
        JsonNode self = files.get("drop_envelope");
        requireKeys(self, DROP_FILE_KEYS, "drop envelope file");
        requireText(self, "name", dropName);
        requireText(
                self,
                "hash_scope",
                "CANONICAL_PAYLOAD_EXCLUDING_SELF_DESCRIPTOR_AND_ENVELOPE_SEAL");
        JsonNode selfSize = self.get("size_bytes");
        if (selfSize == null
                || !selfSize.isIntegralNumber()
                || selfSize.intValue() != dropBytes.length) {
            throw failure("DROP_FILE_SIZE_DRIFT");
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

    private static void validatePredecessor(JsonNode node, IntakeBinding binding) {
        requireKeys(node, PREDECESSOR_KEYS, "predecessor");
        requireText(node, "type", binding.predecessorType().name());
        JsonNode day = node.get("day");
        if (binding.predecessorType() == PredecessorType.GENESIS) {
            if (day == null || !day.isNull()) {
                throw failure("GENESIS_DAY_REJECT");
            }
        } else {
            requireText(node, "day", binding.predecessorDay().toString());
        }
        requireText(
                node,
                "drop_envelope_sha256",
                binding.predecessorDropEnvelopeSha256());
    }

    private static void validateFullFile(JsonNode node, String name, byte[] bytes) {
        requireKeys(node, DROP_FILE_KEYS, name);
        requireText(node, "name", name);
        requireText(node, "sha256", sha256(bytes));
        requireText(node, "hash_scope", "FULL_FILE_BYTES");
        JsonNode size = node.get("size_bytes");
        if (size == null || !size.isIntegralNumber() || size.intValue() != bytes.length) {
            throw failure("FILE_SIZE_DRIFT:" + name);
        }
    }

    private static Path validateDropRoot(Path supplied) throws Exception {
        if (supplied == null) {
            throw failure("NULL_DROP_ROOT");
        }
        Path root = supplied.toAbsolutePath().normalize();
        requireDirectory(root, "drop root");
        String leaf = root.getFileName() == null
                ? "" : root.getFileName().toString().toLowerCase();
        String full = root.toString().toLowerCase().replace('\\', '/');
        if (!leaf.contains("dra-crypto-carry")
                || !leaf.contains("drop")
                || leaf.equals("drop")
                || full.contains("microstructure")
                || full.contains("candle")
                || full.contains(".research-state")
                || full.matches(".*/(?:state|trading|db|database)(?:/.*)?")) {
            throw failure("DROP_ROOT_SCOPE_REJECT");
        }
        return root;
    }

    private static void requireDirectory(Path path, String label) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("DIRECTORY_REQUIRED:" + label);
        }
        rejectLinkChain(path);
    }

    private static void requireRegularFile(Path path, boolean zeroAllowed, String label)
            throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("FILE_MISSING:" + label);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || (!zeroAllowed && attributes.size() < 1)) {
            throw failure("REGULAR_FILE_REQUIRED:" + label);
        }
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

    private static byte[] readBounded(Path path, String label) throws Exception {
        long size = Files.size(path);
        if (size < 1 || size > MAX_FILE_BYTES || size > Integer.MAX_VALUE) {
            throw failure("FILE_SIZE_REJECT:" + label);
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    throw failure("SHORT_READ:" + label);
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) {
                throw failure("FILE_GREW_DURING_READ:" + label);
            }
        }
        return buffer.array();
    }

    private static void requireBinding(IntakeBinding binding) {
        if (binding == null
                || binding.targetDay() == null
                || binding.predecessorType() == null
                || binding.predecessorDropEnvelopeSha256() == null
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

    private static void validateRow(
            JsonNode row, int expectedSize, String expectedTimestamp, boolean futures,
            String label) {
        if (row == null || !row.isArray() || row.size() != expectedSize) {
            throw failure("ROW_SHAPE_REJECT:" + label);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : row) {
            if (!value.isTextual()) {
                throw failure("ROW_NON_STRING_REJECT:" + label);
            }
            values.add(value.textValue());
        }
        if (!expectedTimestamp.equals(values.get(0))
                || !"1".equals(values.get(expectedSize - 1))) {
            throw failure("ROW_TIME_OR_CONFIRM_REJECT:" + label);
        }
        BigDecimal open = decimal(values.get(1), true);
        BigDecimal high = decimal(values.get(2), true);
        BigDecimal low = decimal(values.get(3), true);
        BigDecimal close = decimal(values.get(4), true);
        if (high.compareTo(open.max(low).max(close)) < 0
                || low.compareTo(open.min(high).min(close)) > 0) {
            throw failure("OHLC_BOUNDS_REJECT:" + label);
        }
        if (futures) {
            decimal(values.get(5), false);
            decimal(values.get(6), false);
            decimal(values.get(7), false);
        }
    }

    private static BigDecimal decimal(String value, boolean positive) {
        Pattern pattern = positive ? POSITIVE_DECIMAL : NONNEGATIVE_DECIMAL;
        if (value == null || !pattern.matcher(value).matches()) {
            throw failure("DECIMAL_REJECT");
        }
        BigDecimal parsed = new BigDecimal(value);
        if ((positive && parsed.signum() <= 0) || (!positive && parsed.signum() < 0)) {
            throw failure("DECIMAL_REJECT");
        }
        return parsed;
    }

    private static long parseMillis(String value) {
        if (!MILLIS.matcher(value).matches()) {
            throw failure("MILLIS_REJECT");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("MILLIS_REJECT", error);
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

    private static byte[] canonicalBytes(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsBytes(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("CANONICAL_JSON_REJECT", error);
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

    private static int requiredPositiveInt(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() < 1) {
            throw failure("POSITIVE_INTEGER_REJECT:" + key);
        }
        return value.intValue();
    }

    private static void requireSha256(String value, boolean nonzero, String label) {
        if (value == null
                || !value.matches("[0-9a-f]{64}")
                || (nonzero && ZERO_SHA256.equals(value))) {
            throw failure("SHA256_REJECT:" + label);
        }
    }

    private static String filePrefix(LocalDate day) {
        return "okx-dra-crypto-carry-" + day;
    }

    private static String reservationName(LocalDate day) {
        return "." + day + ".publish-reserved";
    }

    private static IllegalArgumentException failure(String code) {
        return new IllegalArgumentException(code);
    }

    enum PredecessorType {
        GENESIS,
        CHAIN
    }

    record IntakeBinding(
            LocalDate targetDay,
            PredecessorType predecessorType,
            LocalDate predecessorDay,
            String predecessorDropEnvelopeSha256,
            LocalDate firstEligibleUtcDecisionDay,
            Instant captureDeadlineUtc,
            Instant publishedAt,
            String producerEnvelopeSha256) {
    }

    record ValidationResult(
            String status,
            LocalDate targetDay,
            LocalDate firstEligibleUtcDecisionDay,
            Instant publishedAt,
            PredecessorType predecessorType,
            LocalDate predecessorDay,
            String predecessorDropEnvelopeSha256,
            Map<String, String> fileSha256,
            Map<String, Integer> fileSizeBytes) {

        ValidationResult {
            fileSha256 = Map.copyOf(fileSha256);
            fileSizeBytes = Map.copyOf(fileSizeBytes);
        }
    }

    private record InventoryDocument(List<String> instIds, Instant capturedAt) {
        InventoryDocument {
            instIds = List.copyOf(instIds);
        }
    }

    private record DayDocument(Instant capturedAt) {
    }

    private record ProducerDocument(Instant generatedAt, String sha256) {
    }
}
