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

/** Independent, network-denied, read-only validation for V2 two-phase drops. */
final class OkxDraCryptoCarryNetworkDeniedIntakeV2 {

    static final String VALID_STATUS = "VALID_OFFLINE_DROP_NOT_CANONICAL";
    static final String SOURCE_CONTRACT_SHA256 =
            "183eeb35dc4729ff91970e4b892f141f58452abfa350591888587ce01035e4ad";
    static final String PRODUCER_SCHEMA_SHA256 =
            "814fbef9722dcdd2a6dac8c56e159c1a34e7c2db559c306709c0c393e05230ee";
    static final String INVENTORY_DROP_SCHEMA_SHA256 =
            "59e85d80aa4d2188af57872b7a2731881c85fd949fd7378c8a75cbff4dcdb196";
    static final String DAY_DROP_SCHEMA_SHA256 =
            "a438ba041e0ac80e3757f842659f2afa14b701c9a94034b1f59dffa5e2aa0563";
    static final String STATE_SCHEMA_SHA256 =
            "2c8af00a076616ffc25b95a2709bde1d4b6b7efb5899240e50d7c9f9322060d8";

    private static final String AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE";
    private static final String SOURCE_LABEL =
            "LAGGED_OKX_BTC_USDT_EXPIRY_FUTURES_BASIS_ATOMS_V1";
    private static final String SOURCE_IDENTITY = "agora-dra-carry-source";
    private static final String SOURCE_GROUP = "agora-dra-carry-publish";
    private static final String INTAKE_IDENTITY = "agora-research";
    private static final String V1_SOURCE_SHA =
            "0944ab401717360f6eccc31ab967461af7ebc8122f7b77ee7b1f46eaa8fac48e";
    private static final String V1_INVENTORY_SCHEMA_SHA =
            "8dd38f2ea2e73f236f56416aa1db86f6f82818ed7d8a9f69738b194c1965b340";
    private static final String V1_DAY_SCHEMA_SHA =
            "1028d4b6f53cae6ad096038142173b650726fe744d358c2c16c7c57bc32dd8d8";
    private static final String V1_PRODUCER_SCHEMA_SHA =
            "e39263edd8362e91722134aefd11ee04f76def8b4d409aa01f396a2a481ea6d5";
    private static final String V1_DROP_SCHEMA_SHA =
            "c3881cfc36f79168efd41a579fdb4f8e207fee0ca33b15b2e0b39c1e6bd43ee0";
    private static final String V1_PRODUCER_ID = "OKX_DRA_CRYPTO_CARRY_JAVA_PRODUCER_CORE_V1";
    private static final String ZERO_SHA = "0".repeat(64);
    private static final int MAX_FILE_BYTES = 2_097_152;
    private static final Pattern INST_ID = Pattern.compile("BTC-USDT-[0-9]{6}");
    private static final Pattern MILLIS = Pattern.compile("(?:0|[1-9][0-9]*)");
    private static final Pattern POSITIVE = Pattern.compile(
            "(?:[1-9][0-9]*(?:\\.[0-9]+)?|0\\.[0-9]*[1-9][0-9]*)");
    private static final Pattern NONNEGATIVE = Pattern.compile(
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
    private static final Set<String> V1_PRODUCER_KEYS = Set.of(
            "schema_version", "envelope_type", "authorization", "status", "source_label",
            "source_contract_sha256", "producer_envelope_schema_sha256", "producer_id",
            "target_day", "generated_at", "inventory", "day",
            "first_eligible_utc_decision_day", "transport_status", "envelope_seal");
    private static final Set<String> PARENT_KEYS = Set.of(
            "schema_version", "envelope_type", "authorization", "status", "phase",
            "source_label", "source_contract_sha256", "producer_envelope_schema_sha256",
            "source_identity", "source_group", "intake_identity", "target_day",
            "generated_at", "v1_child_lineage", "inventory", "day",
            "v1_producer_envelope", "accepted_inventory_state_sha256",
            "first_eligible_utc_decision_day", "envelope_seal");
    private static final Set<String> INVENTORY_DROP_KEYS = Set.of(
            "schema_version", "envelope_type", "authorization", "status", "phase",
            "source_label", "source_contract_sha256", "producer_envelope_schema_sha256",
            "inventory_drop_schema_sha256", "source_identity", "source_group",
            "intake_identity", "target_day", "published_at", "files",
            "delivery_semantics", "reservation_name", "idempotency_key", "envelope_seal");
    private static final Set<String> DAY_DROP_KEYS = Set.of(
            "schema_version", "envelope_type", "authorization", "status", "phase",
            "source_label", "source_contract_sha256", "producer_envelope_schema_sha256",
            "day_drop_schema_sha256", "source_identity", "source_group", "intake_identity",
            "target_day", "accepted_inventory_state_sha256",
            "first_eligible_utc_decision_day", "capture_deadline_utc", "published_at",
            "predecessor", "files", "delivery_semantics", "reservation_name",
            "idempotency_key", "envelope_seal");
    private static final Set<String> STATE_KEYS = Set.of(
            "schema_version", "authorization", "status", "stage",
            "source_contract_sha256", "intake_state_schema_sha256", "source_identity",
            "source_group", "intake_identity", "target_day", "previous_state_sha256",
            "inventory", "inventory_parent_envelope_sha256",
            "inventory_drop_envelope_sha256", "inventory_published_at",
            "inventory_accepted_at", "day", "v1_producer_envelope",
            "day_parent_envelope_sha256", "day_drop_envelope_sha256",
            "day_published_at", "day_accepted_at", "predecessor",
            "first_eligible_utc_decision_day", "state_seal");
    private static final Set<String> FILE_KEYS = Set.of(
            "name", "sha256", "size_bytes", "hash_scope");
    private static final Set<String> PARENT_ARTIFACT_KEYS = Set.of(
            "name", "sha256", "size_bytes", "hash_scope", "schema_version",
            "schema_sha256", "source_contract_sha256");
    private static final Set<String> STATE_ARTIFACT_KEYS = Set.of(
            "name", "sha256", "size_bytes", "hash_scope");
    private static final Set<String> SEAL_KEYS = Set.of(
            "algorithm", "payload_sha256", "canonicalization", "sealed_at");
    private static final Set<String> PREDECESSOR_KEYS = Set.of(
            "type", "day", "day_drop_envelope_sha256");
    private static final Set<String> DELIVERY_KEYS = Set.of(
            "transport", "phase", "atomic_rename", "overwrite",
            "publisher_read_after_publish", "symlinks", "existing_roots_only",
            "same_filesystem", "reservation_required", "intake_network_access",
            "intake_write_access", "canonical_state_access", "retry", "backfill");

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    private OkxDraCryptoCarryNetworkDeniedIntakeV2() {
    }

    static IntakeResult acceptInventory(InventoryBinding binding, Path inventoryDropRoot)
            throws Exception {
        requireInventoryBinding(binding);
        Path root = validateRoot(inventoryDropRoot, "inventory");
        String prefix = prefix(binding.targetDay());
        Path directory = root.resolve(binding.targetDay() + ".inventory-v2");
        Path reservation = root.resolve(
                "." + binding.targetDay() + ".inventory-v2.publish-reserved");
        requireRegular(reservation, true, "inventory reservation");
        requireDirectory(directory, "inventory directory");
        String inventoryName = prefix + ".inventory.json";
        String parentName = prefix + ".inventory-parent-v2.json";
        String dropName = prefix + ".inventory-drop-v2.json";
        requireChildren(directory, Set.of(inventoryName, parentName, dropName));
        byte[] inventoryBytes = read(directory.resolve(inventoryName), "inventory");
        byte[] parentBytes = read(directory.resolve(parentName), "inventory parent");
        byte[] dropBytes = read(directory.resolve(dropName), "inventory drop");
        V1Inventory inventory = validateInventory(inventoryBytes);
        if (!inventory.targetDay().equals(binding.targetDay())) {
            throw failure("INVENTORY_TARGET_REJECT");
        }
        validateParent(
                parentBytes, "INVENTORY_PREPARED", inventoryBytes, null, null, null,
                binding.targetDay());
        Instant publishedAt = validateInventoryDrop(
                dropBytes, inventoryBytes, parentBytes, binding.targetDay());
        if (binding.acceptedAt().isBefore(publishedAt)
                || !binding.acceptedAt().isBefore(binding.targetDay().atStartOfDay()
                .toInstant(ZoneOffset.UTC))) {
            throw failure("INVENTORY_ACCEPTANCE_CLOCK_REJECT");
        }
        byte[] state = inventoryState(
                binding.targetDay(), inventoryName, inventoryBytes, parentBytes, dropBytes,
                publishedAt, binding.acceptedAt());
        return new IntakeResult(Stage.INVENTORY_ACCEPTED, binding.targetDay(), state);
    }

    static IntakeResult acceptDay(
            DayBinding binding, Path dayDropRoot, byte[] acceptedInventoryStateBytes)
            throws Exception {
        requireDayBinding(binding);
        AcceptedInventoryState prior = validateInventoryState(
                acceptedInventoryStateBytes, binding.targetDay());
        if (!sha256(acceptedInventoryStateBytes)
                .equals(binding.acceptedInventoryStateSha256())) {
            throw failure("PRIOR_STATE_HASH_REJECT");
        }
        Path root = validateRoot(dayDropRoot, "day");
        String prefix = prefix(binding.targetDay());
        Path directory = root.resolve(binding.targetDay() + ".day-v2");
        Path reservation = root.resolve("." + binding.targetDay() + ".day-v2.publish-reserved");
        requireRegular(reservation, true, "day reservation");
        requireDirectory(directory, "day directory");
        String inventoryName = prefix + ".inventory.json";
        String dayName = prefix + ".day.json";
        String v1ProducerName = prefix + ".producer-envelope.json";
        String parentName = prefix + ".day-parent-v2.json";
        String dropName = prefix + ".day-drop-v2.json";
        requireChildren(directory,
                Set.of(inventoryName, dayName, v1ProducerName, parentName, dropName));
        byte[] inventoryBytes = read(directory.resolve(inventoryName), "day inventory");
        byte[] dayBytes = read(directory.resolve(dayName), "day atoms");
        byte[] producerBytes = read(directory.resolve(v1ProducerName), "v1 producer");
        byte[] parentBytes = read(directory.resolve(parentName), "day parent");
        byte[] dropBytes = read(directory.resolve(dropName), "day drop");
        if (!sha256(inventoryBytes).equals(prior.inventorySha256())
                || inventoryBytes.length != prior.inventorySize()) {
            throw failure("INVENTORY_REUSE_REJECT");
        }
        V1Inventory inventory = validateInventory(inventoryBytes);
        V1Day day = validateDay(dayBytes, inventoryBytes, inventory);
        validateV1Producer(producerBytes, inventoryBytes, dayBytes, inventory.targetDay());
        validateParent(
                parentBytes, "DAY_FINALIZED", inventoryBytes, dayBytes, producerBytes,
                acceptedInventoryStateBytes, binding.targetDay());
        DayDrop drop = validateDayDrop(
                dropBytes, inventoryBytes, dayBytes, producerBytes, parentBytes,
                acceptedInventoryStateBytes, binding);
        if (binding.acceptedAt().isBefore(drop.publishedAt())
                || !binding.acceptedAt().isBefore(drop.captureDeadline())) {
            throw failure("DAY_ACCEPTANCE_CLOCK_REJECT");
        }
        byte[] state = dayState(
                prior, acceptedInventoryStateBytes, inventoryName, inventoryBytes,
                dayName, dayBytes, v1ProducerName, producerBytes, parentBytes, dropBytes,
                drop, binding.acceptedAt());
        return new IntakeResult(Stage.DAY_ACCEPTED, binding.targetDay(), state);
    }

    private static V1Inventory validateInventory(byte[] bytes) {
        JsonNode root = parse(bytes, "v1 inventory");
        requireKeys(root, INVENTORY_KEYS, "v1 inventory");
        requireText(root, "schema_version",
                "OKX_DRA_CRYPTO_CARRY_EXPIRY_FUTURES_INVENTORY_V1");
        requireText(root, "document_type", "PRIOR_CYCLE_EXPIRY_FUTURES_INVENTORY");
        requireV1Common(root);
        LocalDate day = requireDate(root, "target_day");
        Instant scheduled = requireInstant(root, "scheduled_cycle_at");
        Instant captured = requireInstant(root, "captured_at");
        requireExactObject(root.get("request"), inventoryRequest(), "inventory request");
        if (!scheduled.equals(day.minusDays(1).atTime(LocalTime.of(1, 5))
                .toInstant(ZoneOffset.UTC))
                || captured.isBefore(scheduled)
                || !captured.isBefore(day.atStartOfDay().toInstant(ZoneOffset.UTC))) {
            throw failure("V1_INVENTORY_CLOCK_REJECT");
        }
        JsonNode instruments = root.get("instruments");
        int count = requirePositiveInt(root, "inventory_count");
        if (instruments == null || !instruments.isArray() || instruments.size() != count) {
            throw failure("INVENTORY_COUNT_REJECT");
        }
        List<String> ids = new ArrayList<>();
        String prior = null;
        for (JsonNode instrument : instruments) {
            requireKeys(instrument, INSTRUMENT_KEYS, "instrument");
            String id = requiredText(instrument, "instId");
            if (!INST_ID.matcher(id).matches() || (prior != null && prior.compareTo(id) >= 0)) {
                throw failure("INSTRUMENT_ORDER_REJECT");
            }
            prior = id;
            ids.add(id);
            requireText(instrument, "instType", "FUTURES");
            requireText(instrument, "instFamily", "BTC-USDT");
            requireText(instrument, "uly", "BTC-USDT");
            requireText(instrument, "ctType", "linear");
            requireText(instrument, "settleCcy", "USDT");
            requireText(instrument, "state", "live");
            requireText(instrument, "ruleType", "normal");
            long listTime = parseMillis(requiredText(instrument, "listTime"));
            long expiry = parseMillis(requiredText(instrument, "expTime"));
            if (listTime > captured.toEpochMilli()
                    || expiry <= day.plusDays(1).atStartOfDay()
                    .toInstant(ZoneOffset.UTC).toEpochMilli()) {
                throw failure("INSTRUMENT_TIME_REJECT");
            }
        }
        verifySeal(root, "inventory_seal",
                "UTF-8 compact sorted-key JSON excluding inventory_seal", captured);
        return new V1Inventory(day, captured, ids);
    }

    private static V1Day validateDay(
            byte[] bytes, byte[] inventoryBytes, V1Inventory inventory) {
        JsonNode root = parse(bytes, "v1 day");
        requireKeys(root, DAY_KEYS, "v1 day");
        requireText(root, "schema_version", "OKX_DRA_CRYPTO_CARRY_BASIS_ATOMS_DAY_V1");
        requireText(root, "document_type", "COMPLETE_CONFIRMED_TARGET_DAY_RAW_ATOMS");
        requireV1Common(root);
        requireText(root, "inventory_schema_sha256", V1_INVENTORY_SCHEMA_SHA);
        requireText(root, "day_schema_sha256", V1_DAY_SCHEMA_SHA);
        requireText(root, "inventory_sha256", sha256(inventoryBytes));
        requireText(root, "target_day", inventory.targetDay().toString());
        requireText(root, "first_eligible_utc_decision_day",
                inventory.targetDay().plusDays(2).toString());
        requireText(root, "cache_order_semantics",
                "VALIDATE_COMPLETE_SET_THEN_SORT_BY_FROZEN_INST_ID");
        requireExactObject(root.get("requests"), dayRequests(), "day requests");
        requireExactObject(root.get("eligibility"), eligibility(inventory.targetDay()),
                "day eligibility");
        Instant scheduled = requireInstant(root, "scheduled_cycle_at");
        Instant captured = requireInstant(root, "captured_at");
        Instant expected = inventory.targetDay().plusDays(1)
                .atTime(LocalTime.of(1, 5)).toInstant(ZoneOffset.UTC);
        Instant deadline = inventory.targetDay().plusDays(1)
                .atTime(LocalTime.of(6, 0)).toInstant(ZoneOffset.UTC);
        if (!scheduled.equals(expected) || captured.isBefore(scheduled)
                || !captured.isBefore(deadline)) {
            throw failure("V1_DAY_CLOCK_REJECT");
        }
        int expectedCount = requirePositiveInt(root, "expected_instrument_count");
        int observedCount = requirePositiveInt(root, "observed_instrument_count");
        JsonNode futures = root.get("futures");
        if (expectedCount != inventory.instIds().size() || observedCount != expectedCount
                || futures == null || !futures.isArray() || futures.size() != expectedCount) {
            throw failure("DAY_COVERAGE_REJECT");
        }
        String targetMillis = Long.toString(inventory.targetDay().atStartOfDay()
                .toInstant(ZoneOffset.UTC).toEpochMilli());
        for (int i = 0; i < futures.size(); i++) {
            JsonNode atom = futures.get(i);
            requireKeys(atom, Set.of("instId", "row"), "futures atom");
            requireText(atom, "instId", inventory.instIds().get(i));
            validateRow(atom.get("row"), 9, targetMillis, true);
        }
        JsonNode index = root.get("index");
        requireKeys(index, Set.of("instId", "row"), "index atom");
        requireText(index, "instId", "BTC-USDT");
        validateRow(index.get("row"), 6, targetMillis, false);
        verifySeal(root, "day_seal", "UTF-8 compact sorted-key JSON excluding day_seal",
                captured);
        return new V1Day(captured);
    }

    private static void validateV1Producer(
            byte[] bytes, byte[] inventoryBytes, byte[] dayBytes, LocalDate targetDay) {
        JsonNode root = parse(bytes, "v1 producer");
        requireKeys(root, V1_PRODUCER_KEYS, "v1 producer");
        requireText(root, "schema_version", "OKX_DRA_CRYPTO_CARRY_PRODUCER_ENVELOPE_V1");
        requireText(root, "envelope_type", "OFFLINE_JAVA_PRODUCER_CORE_OUTPUT");
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "status", "OFFLINE_DISABLED_NOT_REGISTERED");
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", V1_SOURCE_SHA);
        requireText(root, "producer_envelope_schema_sha256", V1_PRODUCER_SCHEMA_SHA);
        requireText(root, "producer_id", V1_PRODUCER_ID);
        requireText(root, "target_day", targetDay.toString());
        requireText(root, "first_eligible_utc_decision_day", targetDay.plusDays(2).toString());
        requireText(root, "transport_status",
                "NOT_IMPLEMENTED_CREATE_ONLY_HASH_BOUND_ONE_WAY_DROP");
        requireSimpleArtifact(root.get("inventory"), inventoryBytes);
        requireSimpleArtifact(root.get("day"), dayBytes);
        Instant generated = requireInstant(root, "generated_at");
        verifySeal(root, "envelope_seal",
                "UTF-8 compact sorted-key JSON excluding envelope_seal", generated);
    }

    private static Instant validateInventoryDrop(
            byte[] bytes, byte[] inventoryBytes, byte[] parentBytes, LocalDate targetDay) {
        JsonNode root = parse(bytes, "inventory drop");
        requireKeys(root, INVENTORY_DROP_KEYS, "inventory drop");
        requireDropCommon(root, "OKX_DRA_CRYPTO_CARRY_INVENTORY_DROP_ENVELOPE_V2",
                "IMMUTABLE_ONE_WAY_DRA_CRYPTO_CARRY_INVENTORY_ONLY",
                "PREPARE_INVENTORY");
        requireText(root, "inventory_drop_schema_sha256", INVENTORY_DROP_SCHEMA_SHA256);
        requireText(root, "target_day", targetDay.toString());
        Instant published = requireInstant(root, "published_at");
        Instant start = targetDay.minusDays(1).atTime(LocalTime.of(1, 5))
                .toInstant(ZoneOffset.UTC);
        if (published.isBefore(start)
                || !published.isBefore(targetDay.atStartOfDay().toInstant(ZoneOffset.UTC))) {
            throw failure("INVENTORY_DROP_CLOCK_REJECT");
        }
        String prefix = prefix(targetDay);
        JsonNode files = root.get("files");
        requireKeys(files, Set.of("inventory", "v2_parent_envelope",
                "inventory_drop_envelope"), "inventory files");
        requireFile(files.get("inventory"), prefix + ".inventory.json", inventoryBytes);
        requireFile(files.get("v2_parent_envelope"),
                prefix + ".inventory-parent-v2.json", parentBytes);
        verifySelfDrop(root, bytes, "inventory_drop_envelope",
                prefix + ".inventory-drop-v2.json",
                "UTF-8 compact sorted-key JSON excluding files.inventory_drop_envelope and envelope_seal",
                published);
        requireText(root, "reservation_name",
                "." + targetDay + ".inventory-v2.publish-reserved");
        requireText(root, "idempotency_key", "DRA_CRYPTO_CARRY_V2:INVENTORY:"
                + targetDay + ":" + sha256(inventoryBytes));
        validateDelivery(root.get("delivery_semantics"), "PREPARE_INVENTORY");
        return published;
    }

    private static DayDrop validateDayDrop(
            byte[] bytes,
            byte[] inventoryBytes,
            byte[] dayBytes,
            byte[] producerBytes,
            byte[] parentBytes,
            byte[] stateBytes,
            DayBinding binding) {
        JsonNode root = parse(bytes, "day drop");
        requireKeys(root, DAY_DROP_KEYS, "day drop");
        requireDropCommon(root, "OKX_DRA_CRYPTO_CARRY_DAY_DROP_ENVELOPE_V2",
                "IMMUTABLE_ONE_WAY_DRA_CRYPTO_CARRY_COMPLETE_DAY_V2", "FINALIZE_DAY");
        requireText(root, "day_drop_schema_sha256", DAY_DROP_SCHEMA_SHA256);
        requireText(root, "target_day", binding.targetDay().toString());
        requireText(root, "accepted_inventory_state_sha256", sha256(stateBytes));
        requireText(root, "first_eligible_utc_decision_day",
                binding.targetDay().plusDays(2).toString());
        Instant deadline = requireInstant(root, "capture_deadline_utc");
        Instant expectedDeadline = binding.targetDay().plusDays(1)
                .atTime(LocalTime.of(6, 0)).toInstant(ZoneOffset.UTC);
        Instant published = requireInstant(root, "published_at");
        Instant start = binding.targetDay().plusDays(1)
                .atTime(LocalTime.of(1, 5)).toInstant(ZoneOffset.UTC);
        if (!deadline.equals(expectedDeadline) || published.isBefore(start)
                || !published.isBefore(deadline)) {
            throw failure("DAY_DROP_CLOCK_REJECT");
        }
        validatePredecessor(root.get("predecessor"), binding);
        String prefix = prefix(binding.targetDay());
        JsonNode files = root.get("files");
        requireKeys(files, Set.of("inventory", "day", "v1_producer_envelope",
                "v2_parent_envelope", "day_drop_envelope"), "day files");
        requireFile(files.get("inventory"), prefix + ".inventory.json", inventoryBytes);
        requireFile(files.get("day"), prefix + ".day.json", dayBytes);
        requireFile(files.get("v1_producer_envelope"),
                prefix + ".producer-envelope.json", producerBytes);
        requireFile(files.get("v2_parent_envelope"),
                prefix + ".day-parent-v2.json", parentBytes);
        verifySelfDrop(root, bytes, "day_drop_envelope", prefix + ".day-drop-v2.json",
                "UTF-8 compact sorted-key JSON excluding files.day_drop_envelope and envelope_seal",
                published);
        requireText(root, "reservation_name", "." + binding.targetDay()
                + ".day-v2.publish-reserved");
        requireText(root, "idempotency_key", "DRA_CRYPTO_CARRY_V2:DAY:"
                + binding.targetDay() + ":" + sha256(stateBytes) + ":"
                + binding.predecessorDayDropEnvelopeSha256());
        validateDelivery(root.get("delivery_semantics"), "FINALIZE_DAY");
        return new DayDrop(published, deadline, generic(root.get("predecessor")));
    }

    private static void validateParent(
            byte[] bytes,
            String phase,
            byte[] inventoryBytes,
            byte[] dayBytes,
            byte[] producerBytes,
            byte[] stateBytes,
            LocalDate targetDay) {
        JsonNode root = parse(bytes, "v2 parent");
        requireKeys(root, PARENT_KEYS, "v2 parent");
        requireText(root, "schema_version", "OKX_DRA_CRYPTO_CARRY_PRODUCER_ENVELOPE_V2");
        requireText(root, "envelope_type",
                "OFFLINE_TWO_PHASE_PARENT_OVER_IMMUTABLE_V1_CHILDREN");
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "status", "OFFLINE_DISABLED_NOT_REGISTERED");
        requireText(root, "phase", phase);
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        requireText(root, "producer_envelope_schema_sha256", PRODUCER_SCHEMA_SHA256);
        requireText(root, "source_identity", SOURCE_IDENTITY);
        requireText(root, "source_group", SOURCE_GROUP);
        requireText(root, "intake_identity", INTAKE_IDENTITY);
        requireText(root, "target_day", targetDay.toString());
        validateLineage(root.get("v1_child_lineage"));
        requireParentArtifact(root.get("inventory"), targetDay, "inventory", inventoryBytes,
                "OKX_DRA_CRYPTO_CARRY_EXPIRY_FUTURES_INVENTORY_V1",
                V1_INVENTORY_SCHEMA_SHA);
        Instant generated = requireInstant(root, "generated_at");
        if (phase.equals("INVENTORY_PREPARED")) {
            requireNull(root, "day");
            requireNull(root, "v1_producer_envelope");
            requireNull(root, "accepted_inventory_state_sha256");
            requireNull(root, "first_eligible_utc_decision_day");
        } else {
            requireParentArtifact(root.get("day"), targetDay, "day", dayBytes,
                    "OKX_DRA_CRYPTO_CARRY_BASIS_ATOMS_DAY_V1", V1_DAY_SCHEMA_SHA);
            requireParentArtifact(root.get("v1_producer_envelope"), targetDay,
                    "producer-envelope", producerBytes,
                    "OKX_DRA_CRYPTO_CARRY_PRODUCER_ENVELOPE_V1",
                    V1_PRODUCER_SCHEMA_SHA);
            requireText(root, "accepted_inventory_state_sha256", sha256(stateBytes));
            requireText(root, "first_eligible_utc_decision_day",
                    targetDay.plusDays(2).toString());
        }
        verifySeal(root, "envelope_seal",
                "UTF-8 compact sorted-key JSON excluding envelope_seal", generated);
    }

    private static AcceptedInventoryState validateInventoryState(
            byte[] bytes, LocalDate targetDay) {
        JsonNode root = parse(bytes, "inventory state");
        requireKeys(root, STATE_KEYS, "inventory state");
        requireStateCommon(root, "INVENTORY_ACCEPTED", targetDay);
        requireNull(root, "previous_state_sha256");
        requireNull(root, "day");
        requireNull(root, "v1_producer_envelope");
        requireNull(root, "day_parent_envelope_sha256");
        requireNull(root, "day_drop_envelope_sha256");
        requireNull(root, "day_published_at");
        requireNull(root, "day_accepted_at");
        requireNull(root, "predecessor");
        requireNull(root, "first_eligible_utc_decision_day");
        JsonNode inventory = root.get("inventory");
        requireKeys(inventory, STATE_ARTIFACT_KEYS, "state inventory");
        requireText(inventory, "hash_scope", "FULL_FILE_BYTES");
        String name = requiredText(inventory, "name");
        String hash = requiredText(inventory, "sha256");
        requireSha(hash, false, "state inventory");
        int size = requirePositiveInt(inventory, "size_bytes");
        Instant published = requireInstant(root, "inventory_published_at");
        Instant accepted = requireInstant(root, "inventory_accepted_at");
        String parentSha = requiredText(root, "inventory_parent_envelope_sha256");
        String dropSha = requiredText(root, "inventory_drop_envelope_sha256");
        requireSha(parentSha, true, "inventory parent state");
        requireSha(dropSha, true, "inventory drop state");
        if (accepted.isBefore(published)) {
            throw failure("STATE_CLOCK_REJECT");
        }
        verifySeal(root, "state_seal",
                "UTF-8 compact sorted-key JSON excluding state_seal", accepted);
        return new AcceptedInventoryState(
                name, hash, size,
                parentSha,
                dropSha,
                published, accepted);
    }

    private static byte[] inventoryState(
            LocalDate day,
            String inventoryName,
            byte[] inventoryBytes,
            byte[] parentBytes,
            byte[] dropBytes,
            Instant publishedAt,
            Instant acceptedAt) {
        Map<String, Object> state = stateCommon("INVENTORY_ACCEPTED", day);
        state.put("previous_state_sha256", null);
        state.put("inventory", stateArtifact(inventoryName, inventoryBytes));
        state.put("inventory_parent_envelope_sha256", sha256(parentBytes));
        state.put("inventory_drop_envelope_sha256", sha256(dropBytes));
        state.put("inventory_published_at", publishedAt.toString());
        state.put("inventory_accepted_at", acceptedAt.toString());
        state.put("day", null);
        state.put("v1_producer_envelope", null);
        state.put("day_parent_envelope_sha256", null);
        state.put("day_drop_envelope_sha256", null);
        state.put("day_published_at", null);
        state.put("day_accepted_at", null);
        state.put("predecessor", null);
        state.put("first_eligible_utc_decision_day", null);
        return sealState(state, acceptedAt);
    }

    private static byte[] dayState(
            AcceptedInventoryState prior,
            byte[] priorBytes,
            String inventoryName,
            byte[] inventoryBytes,
            String dayName,
            byte[] dayBytes,
            String producerName,
            byte[] producerBytes,
            byte[] parentBytes,
            byte[] dropBytes,
            DayDrop drop,
            Instant acceptedAt) {
        LocalDate targetDay = LocalDate.parse(inventoryName.substring(21, 31));
        Map<String, Object> state = stateCommon("DAY_ACCEPTED", targetDay);
        state.put("previous_state_sha256", sha256(priorBytes));
        state.put("inventory", stateArtifact(inventoryName, inventoryBytes));
        state.put("inventory_parent_envelope_sha256", prior.parentSha256());
        state.put("inventory_drop_envelope_sha256", prior.dropSha256());
        state.put("inventory_published_at", prior.publishedAt().toString());
        state.put("inventory_accepted_at", prior.acceptedAt().toString());
        state.put("day", stateArtifact(dayName, dayBytes));
        state.put("v1_producer_envelope", stateArtifact(producerName, producerBytes));
        state.put("day_parent_envelope_sha256", sha256(parentBytes));
        state.put("day_drop_envelope_sha256", sha256(dropBytes));
        state.put("day_published_at", drop.publishedAt().toString());
        state.put("day_accepted_at", acceptedAt.toString());
        state.put("predecessor", drop.predecessor());
        state.put("first_eligible_utc_decision_day", targetDay.plusDays(2).toString());
        return sealState(state, acceptedAt);
    }

    private static Map<String, Object> stateCommon(String stage, LocalDate targetDay) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("schema_version", "OKX_DRA_CRYPTO_CARRY_INTAKE_STATE_V2");
        state.put("authorization", AUTHORIZATION);
        state.put("status", VALID_STATUS);
        state.put("stage", stage);
        state.put("source_contract_sha256", SOURCE_CONTRACT_SHA256);
        state.put("intake_state_schema_sha256", STATE_SCHEMA_SHA256);
        state.put("source_identity", SOURCE_IDENTITY);
        state.put("source_group", SOURCE_GROUP);
        state.put("intake_identity", INTAKE_IDENTITY);
        state.put("target_day", targetDay.toString());
        return state;
    }

    private static byte[] sealState(Map<String, Object> state, Instant sealedAt) {
        Map<String, Object> document = new LinkedHashMap<>(state);
        document.put("state_seal", Map.of(
                "algorithm", "SHA-256",
                "payload_sha256", sha256(canonical(state)),
                "canonicalization", "UTF-8 compact sorted-key JSON excluding state_seal",
                "sealed_at", sealedAt.toString()));
        return canonical(document);
    }

    private static Map<String, Object> stateArtifact(String name, byte[] bytes) {
        return Map.of("name", name, "sha256", sha256(bytes), "size_bytes", bytes.length,
                "hash_scope", "FULL_FILE_BYTES");
    }

    private static void requireDropCommon(
            JsonNode root, String version, String type, String phase) {
        requireText(root, "schema_version", version);
        requireText(root, "envelope_type", type);
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "status", "OFFLINE_DISABLED_NOT_DEPLOYED");
        requireText(root, "phase", phase);
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        requireText(root, "producer_envelope_schema_sha256", PRODUCER_SCHEMA_SHA256);
        requireText(root, "source_identity", SOURCE_IDENTITY);
        requireText(root, "source_group", SOURCE_GROUP);
        requireText(root, "intake_identity", INTAKE_IDENTITY);
    }

    private static void requireStateCommon(JsonNode root, String stage, LocalDate targetDay) {
        requireText(root, "schema_version", "OKX_DRA_CRYPTO_CARRY_INTAKE_STATE_V2");
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "status", VALID_STATUS);
        requireText(root, "stage", stage);
        requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        requireText(root, "intake_state_schema_sha256", STATE_SCHEMA_SHA256);
        requireText(root, "source_identity", SOURCE_IDENTITY);
        requireText(root, "source_group", SOURCE_GROUP);
        requireText(root, "intake_identity", INTAKE_IDENTITY);
        requireText(root, "target_day", targetDay.toString());
    }

    private static void validateLineage(JsonNode node) {
        requireKeys(node, Set.of("source_contract_sha256", "inventory_schema_sha256",
                "day_schema_sha256", "producer_envelope_schema_sha256",
                "drop_envelope_schema_sha256", "producer_id", "source_identity"),
                "v1 lineage");
        requireText(node, "source_contract_sha256", V1_SOURCE_SHA);
        requireText(node, "inventory_schema_sha256", V1_INVENTORY_SCHEMA_SHA);
        requireText(node, "day_schema_sha256", V1_DAY_SCHEMA_SHA);
        requireText(node, "producer_envelope_schema_sha256", V1_PRODUCER_SCHEMA_SHA);
        requireText(node, "drop_envelope_schema_sha256", V1_DROP_SCHEMA_SHA);
        requireText(node, "producer_id", V1_PRODUCER_ID);
        requireText(node, "source_identity", "agora-evidence-source");
    }

    private static void requireParentArtifact(
            JsonNode node,
            LocalDate day,
            String suffix,
            byte[] bytes,
            String version,
            String schemaSha) {
        requireKeys(node, PARENT_ARTIFACT_KEYS, "parent artifact");
        requireText(node, "name", prefix(day) + "." + suffix + ".json");
        requireText(node, "sha256", sha256(bytes));
        requireText(node, "hash_scope", "FULL_FILE_BYTES");
        requireText(node, "schema_version", version);
        requireText(node, "schema_sha256", schemaSha);
        requireText(node, "source_contract_sha256", V1_SOURCE_SHA);
        if (requirePositiveInt(node, "size_bytes") != bytes.length) {
            throw failure("PARENT_ARTIFACT_SIZE_REJECT");
        }
    }

    private static void requireFile(JsonNode node, String name, byte[] bytes) {
        requireKeys(node, FILE_KEYS, "drop file");
        requireText(node, "name", name);
        requireText(node, "sha256", sha256(bytes));
        requireText(node, "hash_scope", "FULL_FILE_BYTES");
        if (requirePositiveInt(node, "size_bytes") != bytes.length) {
            throw failure("DROP_FILE_SIZE_REJECT");
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifySelfDrop(
            JsonNode root,
            byte[] fullBytes,
            String selfKey,
            String selfName,
            String canonicalization,
            Instant sealedAt) {
        JsonNode files = root.get("files");
        JsonNode self = files.get(selfKey);
        requireKeys(self, FILE_KEYS, "self drop file");
        requireText(self, "name", selfName);
        requireText(self, "hash_scope",
                "CANONICAL_PAYLOAD_EXCLUDING_SELF_DESCRIPTOR_AND_ENVELOPE_SEAL");
        if (requirePositiveInt(self, "size_bytes") != fullBytes.length) {
            throw failure("SELF_SIZE_REJECT");
        }
        Map<String, Object> payload = asMap(root);
        payload.remove("envelope_seal");
        Map<String, Object> payloadFiles = new LinkedHashMap<>(
                (Map<String, Object>) payload.get("files"));
        payloadFiles.remove(selfKey);
        payload.put("files", payloadFiles);
        String payloadSha = sha256(canonical(payload));
        requireText(self, "sha256", payloadSha);
        JsonNode seal = root.get("envelope_seal");
        requireKeys(seal, SEAL_KEYS, "drop seal");
        requireText(seal, "algorithm", "SHA-256");
        requireText(seal, "payload_sha256", payloadSha);
        requireText(seal, "canonicalization", canonicalization);
        requireText(seal, "sealed_at", sealedAt.toString());
    }

    private static void validateDelivery(JsonNode node, String phase) {
        requireKeys(node, DELIVERY_KEYS, "delivery");
        requireText(node, "transport", "DRA_CRYPTO_CARRY_V2_CREATE_ONLY_ATOMIC_ONE_WAY_DROP");
        requireText(node, "phase", phase);
        requireBoolean(node, "atomic_rename", true);
        requireBoolean(node, "overwrite", false);
        requireBoolean(node, "publisher_read_after_publish", false);
        requireBoolean(node, "symlinks", false);
        requireBoolean(node, "existing_roots_only", true);
        requireBoolean(node, "same_filesystem", true);
        requireBoolean(node, "reservation_required", true);
        requireBoolean(node, "intake_network_access", false);
        requireBoolean(node, "intake_write_access", false);
        requireBoolean(node, "canonical_state_access", false);
        requireBoolean(node, "retry", false);
        requireBoolean(node, "backfill", false);
    }

    private static void validatePredecessor(JsonNode node, DayBinding binding) {
        requireKeys(node, PREDECESSOR_KEYS, "predecessor");
        requireText(node, "type", binding.predecessorType().name());
        if (binding.predecessorType() == PredecessorType.GENESIS) {
            requireNull(node, "day");
        } else {
            requireText(node, "day", binding.predecessorDay().toString());
        }
        requireText(node, "day_drop_envelope_sha256",
                binding.predecessorDayDropEnvelopeSha256());
    }

    private static void requireV1Common(JsonNode root) {
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", V1_SOURCE_SHA);
    }

    private static Map<String, Object> inventoryRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "GET");
        request.put("origin", "https://www.okx.com");
        request.put("path", "/api/v5/public/instruments");
        request.put("query", Map.of("instType", "FUTURES", "instFamily", "BTC-USDT"));
        request.put("credentials", "NONE");
        return request;
    }

    private static Map<String, Object> dayRequests() {
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

    private static Map<String, Object> eligibility(LocalDate targetDay) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("target_day_use", "DENY_LEAKAGE");
        value.put("d_plus_1_use", "DENY_CAPTURE_AFTER_DECISION");
        value.put("first_eligible_utc_decision_day", targetDay.plusDays(2).toString());
        value.put("retroactive_admission", "DENY");
        value.put("late_retry", "DENY");
        value.put("backfill", "DENY");
        value.put("partial_day_salvage", "DENY");
        return value;
    }

    private static void requireExactObject(JsonNode node, Object expected, String label) {
        if (node == null || !node.isObject()
                || !Arrays.equals(canonical(generic(node)), canonical(expected))) {
            throw failure("OBJECT_VALUE_REJECT:" + label);
        }
    }

    private static void requireSimpleArtifact(JsonNode node, byte[] bytes) {
        requireKeys(node, Set.of("sha256", "size_bytes"), "v1 producer artifact");
        requireText(node, "sha256", sha256(bytes));
        if (requirePositiveInt(node, "size_bytes") != bytes.length) {
            throw failure("V1_PRODUCER_ARTIFACT_REJECT");
        }
    }

    private static void validateRow(
            JsonNode node, int size, String expectedMillis, boolean futures) {
        if (node == null || !node.isArray() || node.size() != size) {
            throw failure("ROW_SHAPE_REJECT");
        }
        List<String> row = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()) {
                throw failure("ROW_TEXT_REJECT");
            }
            row.add(value.textValue());
        });
        if (!row.get(0).equals(expectedMillis) || !row.get(size - 1).equals("1")) {
            throw failure("ROW_CLOCK_REJECT");
        }
        BigDecimal open = decimal(row.get(1), true);
        BigDecimal high = decimal(row.get(2), true);
        BigDecimal low = decimal(row.get(3), true);
        BigDecimal close = decimal(row.get(4), true);
        if (high.compareTo(open.max(low).max(close)) < 0
                || low.compareTo(open.min(high).min(close)) > 0) {
            throw failure("OHLC_REJECT");
        }
        if (futures) {
            decimal(row.get(5), false);
            decimal(row.get(6), false);
            decimal(row.get(7), false);
        }
    }

    private static BigDecimal decimal(String text, boolean positive) {
        Pattern pattern = positive ? POSITIVE : NONNEGATIVE;
        if (text == null || !pattern.matcher(text).matches()) {
            throw failure("DECIMAL_REJECT");
        }
        BigDecimal value = new BigDecimal(text);
        if ((positive && value.signum() <= 0) || (!positive && value.signum() < 0)) {
            throw failure("DECIMAL_REJECT");
        }
        return value;
    }

    private static long parseMillis(String text) {
        if (text == null || !MILLIS.matcher(text).matches()) {
            throw failure("MILLIS_REJECT");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("MILLIS_REJECT", error);
        }
    }

    private static void verifySeal(
            JsonNode root, String key, String canonicalization, Instant sealedAt) {
        JsonNode seal = root.get(key);
        requireKeys(seal, SEAL_KEYS, key);
        requireText(seal, "algorithm", "SHA-256");
        requireText(seal, "canonicalization", canonicalization);
        requireText(seal, "sealed_at", sealedAt.toString());
        Map<String, Object> payload = asMap(root);
        payload.remove(key);
        requireText(seal, "payload_sha256", sha256(canonical(payload)));
    }

    private static Path validateRoot(Path supplied, String phase) throws Exception {
        if (supplied == null) {
            throw failure("NULL_DROP_ROOT");
        }
        Path root = supplied.toAbsolutePath().normalize();
        requireDirectory(root, "drop root");
        String leaf = root.getFileName() == null ? "" : root.getFileName().toString().toLowerCase();
        String full = root.toString().toLowerCase().replace('\\', '/');
        if (!leaf.contains("dra-crypto-carry-v2") || !leaf.contains(phase)
                || !leaf.contains("drop") || full.contains("microstructure")
                || full.contains("candle") || full.contains(".research-state")
                || full.matches(".*/(?:state|trading|db|database)(?:/.*)?")) {
            throw failure("DROP_ROOT_SCOPE_REJECT");
        }
        return root;
    }

    private static void requireChildren(Path directory, Set<String> expected) throws Exception {
        Set<String> actual = new HashSet<>();
        try (var stream = Files.list(directory)) {
            stream.forEach(path -> actual.add(path.getFileName().toString()));
        }
        if (!actual.equals(expected)) {
            throw failure("DIRECT_CHILD_SET_REJECT");
        }
        for (String name : expected) {
            requireRegular(directory.resolve(name), false, name);
        }
    }

    private static void requireDirectory(Path path, String label) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("DIRECTORY_REQUIRED:" + label);
        }
        rejectLinkChain(path);
    }

    private static void requireRegular(Path path, boolean zeroAllowed, String label)
            throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("FILE_MISSING:" + label);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || (!zeroAllowed && attributes.size() < 1)
                || (zeroAllowed && attributes.size() != 0)) {
            throw failure("REGULAR_FILE_REQUIRED:" + label);
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

    private static byte[] read(Path path, String label) throws Exception {
        requireRegular(path, false, label);
        long size = Files.size(path);
        if (size < 1 || size > MAX_FILE_BYTES || size > Integer.MAX_VALUE) {
            throw failure("FILE_SIZE_REJECT:" + label);
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw failure("SHORT_READ:" + label);
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) {
                throw failure("FILE_GREW_DURING_READ:" + label);
            }
        }
        return buffer.array();
    }

    private static void requireInventoryBinding(InventoryBinding binding) {
        if (binding == null || binding.targetDay() == null || binding.acceptedAt() == null
                || binding.acceptedAt().getNano() != 0) {
            throw failure("INVENTORY_BINDING_REJECT");
        }
    }

    private static void requireDayBinding(DayBinding binding) {
        if (binding == null || binding.targetDay() == null
                || binding.acceptedInventoryStateSha256() == null
                || binding.acceptedAt() == null || binding.acceptedAt().getNano() != 0
                || binding.predecessorType() == null
                || binding.predecessorDayDropEnvelopeSha256() == null) {
            throw failure("DAY_BINDING_REJECT");
        }
        requireSha(binding.acceptedInventoryStateSha256(), true, "accepted state");
        if (binding.predecessorType() == PredecessorType.GENESIS) {
            if (binding.predecessorDay() != null
                    || !ZERO_SHA.equals(binding.predecessorDayDropEnvelopeSha256())) {
                throw failure("GENESIS_PREDECESSOR_REJECT");
            }
        } else if (!binding.targetDay().minusDays(1).equals(binding.predecessorDay())) {
            throw failure("CHAIN_PREDECESSOR_REJECT");
        } else {
            requireSha(binding.predecessorDayDropEnvelopeSha256(), true, "predecessor");
        }
    }

    private static JsonNode parse(byte[] bytes, String label) {
        if (bytes == null || bytes.length == 0) {
            throw failure("EMPTY_BYTES:" + label);
        }
        try {
            JsonNode root = STRICT_MAPPER.readTree(bytes);
            if (root == null || !root.isObject()
                    || !Arrays.equals(bytes, canonical(generic(root)))) {
                throw failure("NONCANONICAL_JSON:" + label);
            }
            return root;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("STRICT_JSON_REJECT:" + label, error);
        }
    }

    private static byte[] canonical(Object value) {
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

    private static LocalDate requireDate(JsonNode node, String key) {
        try {
            String text = requiredText(node, key);
            LocalDate value = LocalDate.parse(text);
            if (!value.toString().equals(text)) {
                throw failure("DATE_REJECT:" + key);
            }
            return value;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("DATE_REJECT:" + key, error);
        }
    }

    private static Instant requireInstant(JsonNode node, String key) {
        try {
            String text = requiredText(node, key);
            Instant value = Instant.parse(text);
            if (value.getNano() != 0 || !value.toString().equals(text)) {
                throw failure("TIMESTAMP_REJECT:" + key);
            }
            return value;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("TIMESTAMP_REJECT:" + key, error);
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

    private static void requireBoolean(JsonNode node, String key, boolean expected) {
        JsonNode value = node.get(key);
        if (value == null || !value.isBoolean() || value.booleanValue() != expected) {
            throw failure("BOOLEAN_FIELD_REJECT:" + key);
        }
    }

    private static void requireNull(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isNull()) {
            throw failure("NULL_FIELD_REQUIRED:" + key);
        }
    }

    private static void requireSha(String value, boolean nonzero, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")
                || (nonzero && ZERO_SHA.equals(value))) {
            throw failure("SHA256_REJECT:" + label);
        }
    }

    private static String prefix(LocalDate day) {
        return "okx-dra-crypto-carry-" + day;
    }

    private static IllegalArgumentException failure(String code) {
        return new IllegalArgumentException(code);
    }

    enum Stage {
        INVENTORY_ACCEPTED,
        DAY_ACCEPTED
    }

    enum PredecessorType {
        GENESIS,
        CHAIN
    }

    record InventoryBinding(LocalDate targetDay, Instant acceptedAt) {
    }

    record DayBinding(
            LocalDate targetDay,
            String acceptedInventoryStateSha256,
            Instant acceptedAt,
            PredecessorType predecessorType,
            LocalDate predecessorDay,
            String predecessorDayDropEnvelopeSha256) {
    }

    record IntakeResult(Stage stage, LocalDate targetDay, byte[] stateBytes) {
        IntakeResult {
            stateBytes = stateBytes.clone();
        }

        @Override
        public byte[] stateBytes() {
            return stateBytes.clone();
        }
    }

    private record V1Inventory(LocalDate targetDay, Instant capturedAt, List<String> instIds) {
        V1Inventory {
            instIds = List.copyOf(instIds);
        }
    }

    private record V1Day(Instant capturedAt) {
    }

    private record AcceptedInventoryState(
            String inventoryName,
            String inventorySha256,
            int inventorySize,
            String parentSha256,
            String dropSha256,
            Instant publishedAt,
            Instant acceptedAt) {
    }

    private record DayDrop(Instant publishedAt, Instant captureDeadline, Object predecessor) {
    }
}
