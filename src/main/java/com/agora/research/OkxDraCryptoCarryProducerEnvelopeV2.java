package com.agora.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Offline-only V2 parent envelopes over immutable V1 child bytes. */
final class OkxDraCryptoCarryProducerEnvelopeV2 {

    static final String AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE";
    static final String STATUS = "OFFLINE_DISABLED_NOT_REGISTERED";
    static final String SOURCE_LABEL =
            "LAGGED_OKX_BTC_USDT_EXPIRY_FUTURES_BASIS_ATOMS_V1";
    static final String SOURCE_IDENTITY = "agora-dra-carry-source";
    static final String SOURCE_GROUP = "agora-dra-carry-publish";
    static final String INTAKE_IDENTITY = "agora-research";
    static final String SOURCE_CONTRACT_SHA256 =
            "183eeb35dc4729ff91970e4b892f141f58452abfa350591888587ce01035e4ad";
    static final String PRODUCER_SCHEMA_SHA256 =
            "814fbef9722dcdd2a6dac8c56e159c1a34e7c2db559c306709c0c393e05230ee";
    static final String INVENTORY_DROP_SCHEMA_SHA256 =
            "59e85d80aa4d2188af57872b7a2731881c85fd949fd7378c8a75cbff4dcdb196";
    static final String DAY_DROP_SCHEMA_SHA256 =
            "a438ba041e0ac80e3757f842659f2afa14b701c9a94034b1f59dffa5e2aa0563";
    static final String INTAKE_STATE_SCHEMA_SHA256 =
            "2c8af00a076616ffc25b95a2709bde1d4b6b7efb5899240e50d7c9f9322060d8";

    static final String V1_SOURCE_CONTRACT_SHA256 =
            "0944ab401717360f6eccc31ab967461af7ebc8122f7b77ee7b1f46eaa8fac48e";
    static final String V1_INVENTORY_SCHEMA_SHA256 =
            "8dd38f2ea2e73f236f56416aa1db86f6f82818ed7d8a9f69738b194c1965b340";
    static final String V1_DAY_SCHEMA_SHA256 =
            "1028d4b6f53cae6ad096038142173b650726fe744d358c2c16c7c57bc32dd8d8";
    static final String V1_PRODUCER_SCHEMA_SHA256 =
            "e39263edd8362e91722134aefd11ee04f76def8b4d409aa01f396a2a481ea6d5";
    static final String V1_DROP_SCHEMA_SHA256 =
            "c3881cfc36f79168efd41a579fdb4f8e207fee0ca33b15b2e0b39c1e6bd43ee0";
    static final String V1_PRODUCER_ID = "OKX_DRA_CRYPTO_CARRY_JAVA_PRODUCER_CORE_V1";

    private static final String ENVELOPE_VERSION =
            "OKX_DRA_CRYPTO_CARRY_PRODUCER_ENVELOPE_V2";
    private static final String CANONICALIZATION =
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
    private static final Set<String> ARTIFACT_KEYS = Set.of(
            "name", "sha256", "size_bytes", "hash_scope", "schema_version",
            "schema_sha256", "source_contract_sha256");
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
    private static final Set<String> SEAL_KEYS = Set.of(
            "algorithm", "payload_sha256", "canonicalization", "sealed_at");

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    private OkxDraCryptoCarryProducerEnvelopeV2() {
    }

    static byte[] prepareInventory(byte[] inventoryBytes, Instant generatedAt) {
        V1Inventory inventory = validateInventory(inventoryBytes);
        requireSecondPrecision(generatedAt, "generated_at");
        if (generatedAt.isBefore(inventory.capturedAt())
                || !generatedAt.isBefore(inventory.targetDay().atStartOfDay()
                .toInstant(ZoneOffset.UTC))) {
            throw failure("INVENTORY_PARENT_CLOCK_REJECT");
        }
        Map<String, Object> payload = basePayload(
                Phase.INVENTORY_PREPARED, inventory.targetDay(), generatedAt,
                artifact(inventory.targetDay(), ChildType.INVENTORY, inventoryBytes),
                null, null, null, null);
        return seal(payload, generatedAt);
    }

    static byte[] finalizeDay(
            byte[] inventoryBytes,
            byte[] dayBytes,
            byte[] v1ProducerEnvelopeBytes,
            byte[] acceptedInventoryStateBytes,
            Instant generatedAt) {
        V1Inventory inventory = validateInventory(inventoryBytes);
        V1Day day = validateDay(dayBytes, inventoryBytes, inventory.targetDay());
        V1Producer producer = validateV1Producer(
                v1ProducerEnvelopeBytes, inventoryBytes, dayBytes, inventory.targetDay());
        validateAcceptedState(acceptedInventoryStateBytes, inventoryBytes, inventory.targetDay());
        requireSecondPrecision(generatedAt, "generated_at");
        Instant deadline = inventory.targetDay().plusDays(1)
                .atTime(LocalTime.of(6, 0)).toInstant(ZoneOffset.UTC);
        if (generatedAt.isBefore(producer.generatedAt())
                || generatedAt.isBefore(day.capturedAt())
                || !generatedAt.isBefore(deadline)) {
            throw failure("DAY_PARENT_CLOCK_REJECT");
        }
        Map<String, Object> payload = basePayload(
                Phase.DAY_FINALIZED, inventory.targetDay(), generatedAt,
                artifact(inventory.targetDay(), ChildType.INVENTORY, inventoryBytes),
                artifact(inventory.targetDay(), ChildType.DAY, dayBytes),
                artifact(inventory.targetDay(), ChildType.V1_PRODUCER, v1ProducerEnvelopeBytes),
                sha256(acceptedInventoryStateBytes), inventory.targetDay().plusDays(2));
        return seal(payload, generatedAt);
    }

    static ParsedParent validate(
            byte[] parentBytes,
            Phase expectedPhase,
            byte[] inventoryBytes,
            byte[] dayBytes,
            byte[] v1ProducerEnvelopeBytes,
            byte[] acceptedInventoryStateBytes) {
        JsonNode root = parseCanonical(parentBytes, "v2 parent envelope");
        requireKeys(root, PARENT_KEYS, "v2 parent envelope");
        requireText(root, "schema_version", ENVELOPE_VERSION);
        requireText(root, "envelope_type",
                "OFFLINE_TWO_PHASE_PARENT_OVER_IMMUTABLE_V1_CHILDREN");
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "status", STATUS);
        requireText(root, "phase", expectedPhase.name());
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        requireText(root, "producer_envelope_schema_sha256", PRODUCER_SCHEMA_SHA256);
        requireText(root, "source_identity", SOURCE_IDENTITY);
        requireText(root, "source_group", SOURCE_GROUP);
        requireText(root, "intake_identity", INTAKE_IDENTITY);
        LocalDate targetDay = requireDate(root, "target_day");
        requireLineage(root.get("v1_child_lineage"));
        requireArtifact(root.get("inventory"),
                artifact(targetDay, ChildType.INVENTORY, inventoryBytes));
        Instant generatedAt = requireInstant(root, "generated_at");
        if (expectedPhase == Phase.INVENTORY_PREPARED) {
            requireNull(root, "day");
            requireNull(root, "v1_producer_envelope");
            requireNull(root, "accepted_inventory_state_sha256");
            requireNull(root, "first_eligible_utc_decision_day");
        } else {
            requireArtifact(root.get("day"), artifact(targetDay, ChildType.DAY, dayBytes));
            requireArtifact(root.get("v1_producer_envelope"),
                    artifact(targetDay, ChildType.V1_PRODUCER, v1ProducerEnvelopeBytes));
            requireText(root, "accepted_inventory_state_sha256",
                    sha256(acceptedInventoryStateBytes));
            requireText(root, "first_eligible_utc_decision_day",
                    targetDay.plusDays(2).toString());
        }
        verifySeal(root, "envelope_seal", CANONICALIZATION, generatedAt);
        return new ParsedParent(expectedPhase, targetDay, generatedAt, sha256(parentBytes));
    }

    private static Map<String, Object> basePayload(
            Phase phase,
            LocalDate targetDay,
            Instant generatedAt,
            Map<String, Object> inventory,
            Map<String, Object> day,
            Map<String, Object> producer,
            String acceptedStateSha256,
            LocalDate eligibleDay) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", ENVELOPE_VERSION);
        value.put("envelope_type", "OFFLINE_TWO_PHASE_PARENT_OVER_IMMUTABLE_V1_CHILDREN");
        value.put("authorization", AUTHORIZATION);
        value.put("status", STATUS);
        value.put("phase", phase.name());
        value.put("source_label", SOURCE_LABEL);
        value.put("source_contract_sha256", SOURCE_CONTRACT_SHA256);
        value.put("producer_envelope_schema_sha256", PRODUCER_SCHEMA_SHA256);
        value.put("source_identity", SOURCE_IDENTITY);
        value.put("source_group", SOURCE_GROUP);
        value.put("intake_identity", INTAKE_IDENTITY);
        value.put("target_day", targetDay.toString());
        value.put("generated_at", generatedAt.toString());
        value.put("v1_child_lineage", lineage());
        value.put("inventory", inventory);
        value.put("day", day);
        value.put("v1_producer_envelope", producer);
        value.put("accepted_inventory_state_sha256", acceptedStateSha256);
        value.put("first_eligible_utc_decision_day",
                eligibleDay == null ? null : eligibleDay.toString());
        return value;
    }

    private static Map<String, Object> lineage() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source_contract_sha256", V1_SOURCE_CONTRACT_SHA256);
        value.put("inventory_schema_sha256", V1_INVENTORY_SCHEMA_SHA256);
        value.put("day_schema_sha256", V1_DAY_SCHEMA_SHA256);
        value.put("producer_envelope_schema_sha256", V1_PRODUCER_SCHEMA_SHA256);
        value.put("drop_envelope_schema_sha256", V1_DROP_SCHEMA_SHA256);
        value.put("producer_id", V1_PRODUCER_ID);
        value.put("source_identity", "agora-evidence-source");
        return value;
    }

    private static Map<String, Object> artifact(
            LocalDate targetDay, ChildType type, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw failure("EMPTY_CHILD_BYTES:" + type);
        }
        String prefix = "okx-dra-crypto-carry-" + targetDay;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", prefix + type.suffix);
        value.put("sha256", sha256(bytes));
        value.put("size_bytes", bytes.length);
        value.put("hash_scope", "FULL_FILE_BYTES");
        value.put("schema_version", type.schemaVersion);
        value.put("schema_sha256", type.schemaSha256);
        value.put("source_contract_sha256", V1_SOURCE_CONTRACT_SHA256);
        return value;
    }

    private static V1Inventory validateInventory(byte[] bytes) {
        JsonNode root = parseCanonical(bytes, "v1 inventory");
        requireKeys(root, INVENTORY_KEYS, "v1 inventory");
        requireText(root, "schema_version",
                "OKX_DRA_CRYPTO_CARRY_EXPIRY_FUTURES_INVENTORY_V1");
        requireText(root, "document_type", "PRIOR_CYCLE_EXPIRY_FUTURES_INVENTORY");
        requireV1Common(root);
        LocalDate day = requireDate(root, "target_day");
        Instant captured = requireInstant(root, "captured_at");
        Instant scheduled = requireInstant(root, "scheduled_cycle_at");
        Instant expected = day.minusDays(1).atTime(LocalTime.of(1, 5))
                .toInstant(ZoneOffset.UTC);
        if (!scheduled.equals(expected) || captured.isBefore(scheduled)
                || !captured.isBefore(day.atStartOfDay().toInstant(ZoneOffset.UTC))) {
            throw failure("V1_INVENTORY_CLOCK_REJECT");
        }
        verifySeal(root, "inventory_seal",
                "UTF-8 compact sorted-key JSON excluding inventory_seal", captured);
        return new V1Inventory(day, captured);
    }

    private static V1Day validateDay(byte[] bytes, byte[] inventoryBytes, LocalDate day) {
        JsonNode root = parseCanonical(bytes, "v1 day");
        requireKeys(root, DAY_KEYS, "v1 day");
        requireText(root, "schema_version", "OKX_DRA_CRYPTO_CARRY_BASIS_ATOMS_DAY_V1");
        requireText(root, "document_type", "COMPLETE_CONFIRMED_TARGET_DAY_RAW_ATOMS");
        requireV1Common(root);
        requireText(root, "target_day", day.toString());
        requireText(root, "inventory_schema_sha256", V1_INVENTORY_SCHEMA_SHA256);
        requireText(root, "day_schema_sha256", V1_DAY_SCHEMA_SHA256);
        requireText(root, "inventory_sha256", sha256(inventoryBytes));
        requireText(root, "first_eligible_utc_decision_day", day.plusDays(2).toString());
        Instant captured = requireInstant(root, "captured_at");
        Instant scheduled = requireInstant(root, "scheduled_cycle_at");
        Instant expected = day.plusDays(1).atTime(LocalTime.of(1, 5))
                .toInstant(ZoneOffset.UTC);
        Instant deadline = day.plusDays(1).atTime(LocalTime.of(6, 0))
                .toInstant(ZoneOffset.UTC);
        if (!scheduled.equals(expected) || captured.isBefore(scheduled)
                || !captured.isBefore(deadline)) {
            throw failure("V1_DAY_CLOCK_REJECT");
        }
        verifySeal(root, "day_seal", "UTF-8 compact sorted-key JSON excluding day_seal",
                captured);
        return new V1Day(captured);
    }

    private static V1Producer validateV1Producer(
            byte[] bytes, byte[] inventoryBytes, byte[] dayBytes, LocalDate day) {
        JsonNode root = parseCanonical(bytes, "v1 producer envelope");
        requireKeys(root, V1_PRODUCER_KEYS, "v1 producer envelope");
        requireText(root, "schema_version", "OKX_DRA_CRYPTO_CARRY_PRODUCER_ENVELOPE_V1");
        requireText(root, "envelope_type", "OFFLINE_JAVA_PRODUCER_CORE_OUTPUT");
        requireV1Common(root);
        requireText(root, "status", "OFFLINE_DISABLED_NOT_REGISTERED");
        requireText(root, "producer_envelope_schema_sha256", V1_PRODUCER_SCHEMA_SHA256);
        requireText(root, "producer_id", V1_PRODUCER_ID);
        requireText(root, "target_day", day.toString());
        requireText(root, "first_eligible_utc_decision_day", day.plusDays(2).toString());
        requireSimpleArtifact(root.get("inventory"), inventoryBytes, "v1 producer inventory");
        requireSimpleArtifact(root.get("day"), dayBytes, "v1 producer day");
        Instant generated = requireInstant(root, "generated_at");
        verifySeal(root, "envelope_seal",
                "UTF-8 compact sorted-key JSON excluding envelope_seal", generated);
        return new V1Producer(generated);
    }

    private static void validateAcceptedState(
            byte[] stateBytes, byte[] inventoryBytes, LocalDate targetDay) {
        JsonNode root = parseCanonical(stateBytes, "inventory accepted state");
        requireKeys(root, STATE_KEYS, "inventory accepted state");
        requireText(root, "schema_version", "OKX_DRA_CRYPTO_CARRY_INTAKE_STATE_V2");
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "status", "VALID_OFFLINE_DROP_NOT_CANONICAL");
        requireText(root, "stage", "INVENTORY_ACCEPTED");
        requireText(root, "source_contract_sha256", SOURCE_CONTRACT_SHA256);
        requireText(root, "intake_state_schema_sha256", INTAKE_STATE_SCHEMA_SHA256);
        requireText(root, "source_identity", SOURCE_IDENTITY);
        requireText(root, "source_group", SOURCE_GROUP);
        requireText(root, "intake_identity", INTAKE_IDENTITY);
        requireText(root, "target_day", targetDay.toString());
        requireNull(root, "previous_state_sha256");
        JsonNode inventory = root.get("inventory");
        requireText(inventory, "sha256", sha256(inventoryBytes));
        JsonNode size = inventory.get("size_bytes");
        if (size == null || !size.isIntegralNumber() || size.intValue() != inventoryBytes.length) {
            throw failure("INVENTORY_STATE_SIZE_REJECT");
        }
        Instant acceptedAt = requireInstant(root, "inventory_accepted_at");
        verifySeal(root, "state_seal", "UTF-8 compact sorted-key JSON excluding state_seal",
                acceptedAt);
    }

    private static byte[] seal(Map<String, Object> payload, Instant sealedAt) {
        Map<String, Object> document = new LinkedHashMap<>(payload);
        document.put("envelope_seal", Map.of(
                "algorithm", "SHA-256",
                "payload_sha256", sha256(canonicalBytes(payload)),
                "canonicalization", CANONICALIZATION,
                "sealed_at", sealedAt.toString()));
        return canonicalBytes(document);
    }

    private static void requireV1Common(JsonNode root) {
        requireText(root, "authorization", AUTHORIZATION);
        requireText(root, "source_label", SOURCE_LABEL);
        requireText(root, "source_contract_sha256", V1_SOURCE_CONTRACT_SHA256);
    }

    private static void requireLineage(JsonNode node) {
        Map<String, Object> expected = lineage();
        if (!Arrays.equals(canonicalBytes(generic(node)), canonicalBytes(expected))) {
            throw failure("V1_LINEAGE_REJECT");
        }
    }

    private static void requireArtifact(JsonNode node, Map<String, Object> expected) {
        requireKeys(node, ARTIFACT_KEYS, "child artifact");
        if (!Arrays.equals(canonicalBytes(generic(node)), canonicalBytes(expected))) {
            throw failure("CHILD_ARTIFACT_REJECT");
        }
    }

    private static void requireSimpleArtifact(JsonNode node, byte[] bytes, String label) {
        requireKeys(node, Set.of("sha256", "size_bytes"), label);
        requireText(node, "sha256", sha256(bytes));
        JsonNode size = node.get("size_bytes");
        if (size == null || !size.isIntegralNumber() || size.intValue() != bytes.length) {
            throw failure("V1_ARTIFACT_SIZE_REJECT:" + label);
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
        if (bytes == null) {
            throw failure("NULL_HASH_BYTES");
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", error);
        }
    }

    static JsonNode parseCanonical(byte[] bytes, String label) {
        if (bytes == null || bytes.length == 0) {
            throw failure("EMPTY_BYTES:" + label);
        }
        try {
            JsonNode root = STRICT_MAPPER.readTree(bytes);
            if (root == null || !root.isObject()
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
                throw failure("DATE_FIELD_REJECT:" + key);
            }
            return value;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("DATE_FIELD_REJECT:" + key, error);
        }
    }

    private static Instant requireInstant(JsonNode node, String key) {
        try {
            String text = requiredText(node, key);
            Instant value = Instant.parse(text);
            requireSecondPrecision(value, key);
            if (!value.toString().equals(text)) {
                throw failure("TIMESTAMP_FIELD_REJECT:" + key);
            }
            return value;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("TIMESTAMP_FIELD_REJECT:" + key, error);
        }
    }

    private static void requireSecondPrecision(Instant value, String label) {
        if (value == null || value.getNano() != 0) {
            throw failure("SECOND_PRECISION_REQUIRED:" + label);
        }
    }

    private static void requireNull(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isNull()) {
            throw failure("NULL_FIELD_REQUIRED:" + key);
        }
    }

    private static IllegalArgumentException failure(String code) {
        return new IllegalArgumentException(code);
    }

    enum Phase {
        INVENTORY_PREPARED,
        DAY_FINALIZED
    }

    private enum ChildType {
        INVENTORY(".inventory.json", "OKX_DRA_CRYPTO_CARRY_EXPIRY_FUTURES_INVENTORY_V1",
                V1_INVENTORY_SCHEMA_SHA256),
        DAY(".day.json", "OKX_DRA_CRYPTO_CARRY_BASIS_ATOMS_DAY_V1",
                V1_DAY_SCHEMA_SHA256),
        V1_PRODUCER(".producer-envelope.json", "OKX_DRA_CRYPTO_CARRY_PRODUCER_ENVELOPE_V1",
                V1_PRODUCER_SCHEMA_SHA256);

        private final String suffix;
        private final String schemaVersion;
        private final String schemaSha256;

        ChildType(String suffix, String schemaVersion, String schemaSha256) {
            this.suffix = suffix;
            this.schemaVersion = schemaVersion;
            this.schemaSha256 = schemaSha256;
        }
    }

    record ParsedParent(Phase phase, LocalDate targetDay, Instant generatedAt, String sha256) {
    }

    private record V1Inventory(LocalDate targetDay, Instant capturedAt) {
    }

    private record V1Day(Instant capturedAt) {
    }

    private record V1Producer(Instant generatedAt) {
    }
}
