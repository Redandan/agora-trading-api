package com.agora.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Source-private V3R1 restart state. It records liveness only and never reads or
 * writes server canonical research state.
 */
final class OkxMicrostructureDiscoveryRecoveryCheckpointV3R1 {

    static final String SCHEMA_VERSION =
            "OKX_MICROSTRUCTURE_DISCOVERY_SOURCE_CHECKPOINT_V3R1";
    static final String STATE_TYPE = "SOURCE_PRIVATE_RESTART_CHECKPOINT";
    static final String CANONICALIZATION =
            "UTF-8 compact JSON excluding checkpoint_seal; object keys sorted lexicographically";
    static final String ZERO_SHA256 = "0".repeat(64);
    static final Path FIXED_ROOT = Path.of(
            "/var/lib/agora-evidence-source/microstructure-v3r1-private-staging/.source-state");

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern HOST_BOOT_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Set<String> CHANNELS = Set.of("books5", "trades");
    private static final Set<String> DISPOSITION_KINDS =
            Set.of("COMPLETE", "SOURCE_LIVENESS_REJECTED");
    private static final Set<String> EXACT_KEYS = Set.of(
            "schema_version", "state_type", "authorization", "generation_id",
            "diagnostic_id", "recovery_contract_sha256", "producer_release_id",
            "producer_manifest_sha256", "producer_identity", "state_authority",
            "canonical_state_access", "host_boot_id", "phase", "active_day",
            "current_complete_streak_count", "observation", "last_disposition",
            "pending_rejection", "pending_publication", "updated_at", "checkpoint_seal");
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private OkxMicrostructureDiscoveryRecoveryCheckpointV3R1() {
    }

    enum Phase {
        PRE_START,
        ACTIVE_DAY,
        BETWEEN_DAYS,
        TERMINAL
    }

    record Snapshot(
            OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding binding,
            String hostBootId,
            Phase phase,
            LocalDate activeDay,
            int currentCompleteStreakCount,
            Instant startedAt,
            Instant lastObservedAt,
            List<String> acknowledgedChannels,
            int completedMinuteCount,
            long dataMessageCount,
            long controlEventCount,
            String rawArrivalChainSha256,
            String controlEventChainSha256,
            String pendingRejectionReason,
            OkxMicrostructureDiscoveryRecoveryDropV3R1.SanitizedControlEvent
                    pendingSanitizedControlEvent,
            OkxMicrostructureDiscoveryRecoveryDropV3R1.PendingPublication
                    pendingPublication,
            LocalDate lastDispositionDay,
            String lastDispositionKind,
            String lastDispositionArtifactSha256,
            Instant updatedAt) {

        Snapshot {
            if (binding == null || hostBootId == null
                    || !HOST_BOOT_ID.matcher(hostBootId).matches()
                    || phase == null || acknowledgedChannels == null || updatedAt == null
                    || currentCompleteStreakCount < 0
                    || currentCompleteStreakCount > 14) {
                throw new IllegalArgumentException("CHECKPOINT_FIELD_INVALID");
            }
            int suppliedChannels = acknowledgedChannels.size();
            acknowledgedChannels = List.copyOf(new TreeSet<>(acknowledgedChannels));
            if (acknowledgedChannels.size() != suppliedChannels
                    || !CHANNELS.containsAll(acknowledgedChannels)
                    || completedMinuteCount < 0 || completedMinuteCount > 1439
                    || dataMessageCount < 0 || controlEventCount < 0
                    || !validHash(rawArrivalChainSha256)
                    || !validHash(controlEventChainSha256)) {
                throw new IllegalArgumentException("CHECKPOINT_OBSERVATION_INVALID");
            }
            if ((startedAt == null) != (lastObservedAt == null)
                    || (startedAt != null && (lastObservedAt.isBefore(startedAt)
                    || updatedAt.isBefore(lastObservedAt)))) {
                throw new IllegalArgumentException("CHECKPOINT_TIME_INVALID");
            }
            if (phase == Phase.ACTIVE_DAY) {
                if (activeDay == null || startedAt == null
                        || binding.calendarIndex(activeDay) < 1
                        || !startedAt.atZone(ZoneOffset.UTC).toLocalDate().equals(activeDay)) {
                    throw new IllegalArgumentException("CHECKPOINT_PHASE_INVALID");
                }
            } else if (activeDay != null) {
                throw new IllegalArgumentException("CHECKPOINT_PHASE_INVALID");
            }
            boolean hasDisposition = lastDispositionDay != null
                    || lastDispositionKind != null
                    || lastDispositionArtifactSha256 != null;
            if (hasDisposition && (lastDispositionDay == null
                    || !DISPOSITION_KINDS.contains(lastDispositionKind)
                    || !validHash(lastDispositionArtifactSha256))) {
                throw new IllegalArgumentException("CHECKPOINT_DISPOSITION_INVALID");
            }
            if (!hasDisposition && (lastDispositionKind != null
                    || lastDispositionArtifactSha256 != null)) {
                throw new IllegalArgumentException("CHECKPOINT_DISPOSITION_INVALID");
            }
            if (lastDispositionDay != null) {
                binding.calendarIndex(lastDispositionDay);
                if (activeDay != null && !activeDay.isAfter(lastDispositionDay)) {
                    throw new IllegalArgumentException("CHECKPOINT_DAY_REGRESSION");
                }
            }
            if (pendingRejectionReason != null) {
                if (phase != Phase.ACTIVE_DAY
                        || !Set.of(
                        "SERVICE_UPGRADE_NOTICE_64008",
                        "TRANSPORT_DISCONNECT_UNPROVED_GAP")
                        .contains(pendingRejectionReason)
                        || ("SERVICE_UPGRADE_NOTICE_64008".equals(pendingRejectionReason)
                        && (pendingSanitizedControlEvent == null
                        || !"notice".equals(pendingSanitizedControlEvent.event())
                        || !"64008".equals(pendingSanitizedControlEvent.code())))
                        || ("TRANSPORT_DISCONNECT_UNPROVED_GAP".equals(pendingRejectionReason)
                        && pendingSanitizedControlEvent != null)) {
                    throw new IllegalArgumentException("CHECKPOINT_PENDING_REJECTION_INVALID");
                }
            } else if (pendingSanitizedControlEvent != null) {
                throw new IllegalArgumentException("CHECKPOINT_PENDING_REJECTION_INVALID");
            }
            if (pendingPublication != null) {
                binding.calendarIndex(pendingPublication.day());
                LocalDate expectedPendingDay = lastDispositionDay == null
                        ? binding.startDay() : lastDispositionDay.plusDays(1);
                if (!expectedPendingDay.equals(pendingPublication.day())
                        || phase == Phase.TERMINAL
                        || ("SOURCE_LIVENESS_REJECTED".equals(pendingPublication.kind())
                        && activeDay != null && !activeDay.equals(pendingPublication.day()))
                        || ("COMPLETE".equals(pendingPublication.kind())
                        && (activeDay == null || !activeDay.equals(pendingPublication.day())))) {
                    throw new IllegalArgumentException("CHECKPOINT_PENDING_PUBLICATION_INVALID");
                }
            }
            if ((lastDispositionDay == null && currentCompleteStreakCount != 0)
                    || ("SOURCE_LIVENESS_REJECTED".equals(lastDispositionKind)
                    && currentCompleteStreakCount != 0)
                    || ("COMPLETE".equals(lastDispositionKind)
                    && currentCompleteStreakCount == 0)) {
                throw new IllegalArgumentException("CHECKPOINT_STREAK_INVALID");
            }
            boolean terminalCondition = currentCompleteStreakCount == 14
                    || binding.endDay().equals(lastDispositionDay);
            if ((phase == Phase.TERMINAL) != terminalCondition) {
                throw new IllegalArgumentException("CHECKPOINT_TERMINAL_INVALID");
            }
        }

        OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation observation() {
            return new OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation(
                    startedAt,
                    lastObservedAt,
                    acknowledgedChannels,
                    completedMinuteCount,
                    dataMessageCount,
                controlEventCount,
                rawArrivalChainSha256,
                controlEventChainSha256);
        }
    }

    record PlannedRejection(
            LocalDate day,
            String reason,
            OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation observation,
            OkxMicrostructureDiscoveryRecoveryDropV3R1.SanitizedControlEvent
                    sanitizedControlEvent,
            Instant rejectedAt) {
    }

    static Snapshot initial(
            OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding binding,
            String hostBootId,
            Instant at) {
        return new Snapshot(
                binding, hostBootId, Phase.PRE_START, null, 0, null, null, List.of(),
                0, 0, 0, ZERO_SHA256, ZERO_SHA256,
                null, null,
                null,
                null, null, null, at);
    }

    static Snapshot active(
            Snapshot previous,
            String hostBootId,
            LocalDate day,
            Instant startedAt,
            Instant lastObservedAt,
            List<String> acknowledgedChannels,
            int completedMinuteCount,
            long dataMessageCount,
            long controlEventCount,
            String rawArrivalChainSha256,
            String controlEventChainSha256,
            Instant updatedAt) {
        Snapshot next = new Snapshot(
                previous.binding(), hostBootId, Phase.ACTIVE_DAY, day,
                previous.currentCompleteStreakCount(),
                startedAt, lastObservedAt, acknowledgedChannels, completedMinuteCount,
                dataMessageCount, controlEventCount, rawArrivalChainSha256,
                controlEventChainSha256,
                previous.pendingRejectionReason(), previous.pendingSanitizedControlEvent(),
                previous.pendingPublication(),
                previous.lastDispositionDay(),
                previous.lastDispositionKind(), previous.lastDispositionArtifactSha256(), updatedAt);
        requireTransition(previous, next);
        return next;
    }

    static Snapshot pendingRejection(
            Snapshot previous,
            String reason,
            OkxMicrostructureDiscoveryRecoveryDropV3R1.SanitizedControlEvent sanitizedControlEvent,
            Instant at) {
        Snapshot next = new Snapshot(
                previous.binding(), previous.hostBootId(), Phase.ACTIVE_DAY,
                previous.activeDay(), previous.currentCompleteStreakCount(),
                previous.startedAt(), previous.lastObservedAt(),
                previous.acknowledgedChannels(), previous.completedMinuteCount(),
                previous.dataMessageCount(), previous.controlEventCount(),
                previous.rawArrivalChainSha256(), previous.controlEventChainSha256(),
                reason, sanitizedControlEvent,
                previous.pendingPublication(),
                previous.lastDispositionDay(), previous.lastDispositionKind(),
                previous.lastDispositionArtifactSha256(), at);
        requireTransition(previous, next);
        return next;
    }

    static Snapshot pendingPublication(
            Snapshot previous,
            OkxMicrostructureDiscoveryRecoveryDropV3R1.PendingPublication publication,
            Instant at) {
        Snapshot next = new Snapshot(
                previous.binding(), previous.hostBootId(), previous.phase(),
                previous.activeDay(), previous.currentCompleteStreakCount(),
                previous.startedAt(), previous.lastObservedAt(),
                previous.acknowledgedChannels(), previous.completedMinuteCount(),
                previous.dataMessageCount(), previous.controlEventCount(),
                previous.rawArrivalChainSha256(), previous.controlEventChainSha256(),
                previous.pendingRejectionReason(), previous.pendingSanitizedControlEvent(),
                publication,
                previous.lastDispositionDay(), previous.lastDispositionKind(),
                previous.lastDispositionArtifactSha256(), at);
        requireTransition(previous, next);
        return next;
    }

    static Snapshot afterDisposition(
            Snapshot previous,
            String hostBootId,
            LocalDate day,
            String kind,
            String artifactSha256,
            Instant at) {
        int nextStreak = "COMPLETE".equals(kind)
                ? previous.currentCompleteStreakCount() + 1 : 0;
        Snapshot next = new Snapshot(
                previous.binding(), hostBootId,
                day.equals(previous.binding().endDay()) || nextStreak == 14
                        ? Phase.TERMINAL : Phase.BETWEEN_DAYS,
                null, nextStreak, null, null, List.of(), 0, 0, 0, ZERO_SHA256, ZERO_SHA256,
                null, null,
                null,
                day, kind, artifactSha256, at);
        requireTransition(previous, next);
        return next;
    }

    static List<PlannedRejection> restartPlan(
            Snapshot checkpoint,
            Instant processStartedAt,
            Instant hostStartedAt,
            String currentHostBootId) {
        if (checkpoint == null || processStartedAt == null || hostStartedAt == null
                || currentHostBootId == null
                || !HOST_BOOT_ID.matcher(currentHostBootId).matches()
                || hostStartedAt.isAfter(processStartedAt)
                || processStartedAt.isBefore(checkpoint.updatedAt())) {
            throw new IllegalArgumentException("RESTART_CONTEXT_INVALID");
        }
        if (checkpoint.phase() == Phase.TERMINAL) {
            return List.of();
        }
        boolean bootChanged = !currentHostBootId.equals(checkpoint.hostBootId());
        LocalDate first = checkpoint.lastDispositionDay() == null
                ? checkpoint.binding().startDay()
                : checkpoint.lastDispositionDay().plusDays(1);
        LocalDate currentDay = processStartedAt.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate last = currentDay.isBefore(checkpoint.binding().endDay())
                ? currentDay : checkpoint.binding().endDay();
        if (first.isAfter(last)) {
            return List.of();
        }

        List<PlannedRejection> result = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            Instant dayStart = day.atStartOfDay().toInstant(ZoneOffset.UTC);
            boolean interruptedActiveDay = checkpoint.phase() == Phase.ACTIVE_DAY
                    && day.equals(checkpoint.activeDay());
            if (!interruptedActiveDay && !processStartedAt.isAfter(dayStart)) {
                break;
            }
            String reason;
            OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation observation;
            if (interruptedActiveDay) {
                reason = checkpoint.pendingRejectionReason() != null
                        ? checkpoint.pendingRejectionReason()
                        : bootChanged && hostStartedAt.isAfter(checkpoint.updatedAt())
                        ? "HOST_REBOOT_BEFORE_DAY_COMPLETE"
                        : "PROCESS_RESTART_BEFORE_DAY_COMPLETE";
                observation = checkpoint.observation();
            } else {
                reason = bootChanged && hostStartedAt.isAfter(dayStart)
                        ? "HOST_REBOOT_BEFORE_DAY_COMPLETE"
                        : "DUAL_CHANNEL_NOT_READY_AT_DAY_START";
                observation = emptyObservation();
            }
            result.add(new PlannedRejection(
                    day,
                    reason,
                    observation,
                    interruptedActiveDay ? checkpoint.pendingSanitizedControlEvent() : null,
                    interruptedActiveDay && checkpoint.pendingRejectionReason() != null
                            ? checkpoint.updatedAt() : processStartedAt));
        }
        return List.copyOf(result);
    }

    static byte[] canonicalBytes(Snapshot snapshot) {
        Map<String, Object> payload = payload(snapshot);
        String payloadHash = OkxMicrostructureCanonicalDrop.sha256(
                OkxMicrostructureCanonicalDrop.canonicalBytes(payload));
        payload.put("checkpoint_seal", Map.of(
                "algorithm", "SHA-256",
                "payload_sha256", payloadHash,
                "canonicalization", CANONICALIZATION,
                "sealed_at", snapshot.updatedAt().toString()));
        return OkxMicrostructureCanonicalDrop.canonicalBytes(payload);
    }

    static Snapshot parse(byte[] bytes) {
        try {
            Map<String, Object> document = STRICT_MAPPER.readValue(
                    bytes, new TypeReference<>() { });
            if (!document.keySet().equals(EXACT_KEYS)) {
                throw new IllegalArgumentException("CHECKPOINT_KEYS_MISMATCH");
            }
            Map<String, Object> seal = requiredMap(document, "checkpoint_seal");
            if (!seal.keySet().equals(Set.of(
                    "algorithm", "payload_sha256", "canonicalization", "sealed_at"))
                    || !"SHA-256".equals(seal.get("algorithm"))
                    || !CANONICALIZATION.equals(seal.get("canonicalization"))) {
                throw new IllegalArgumentException("CHECKPOINT_SEAL_INVALID");
            }
            Map<String, Object> unsealed = new LinkedHashMap<>(document);
            unsealed.remove("checkpoint_seal");
            String actualPayloadHash = OkxMicrostructureCanonicalDrop.sha256(
                    OkxMicrostructureCanonicalDrop.canonicalBytes(unsealed));
            if (!actualPayloadHash.equals(seal.get("payload_sha256"))) {
                throw new IllegalArgumentException("CHECKPOINT_HASH_MISMATCH");
            }
            Snapshot snapshot = fromMap(document);
            if (!java.util.Arrays.equals(bytes, canonicalBytes(snapshot))) {
                throw new IllegalArgumentException("CHECKPOINT_NOT_CANONICAL");
            }
            return snapshot;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("CHECKPOINT_PARSE_FAILED", error);
        }
    }

    static final class Store {
        private static final Set<String> INTENT_KEYS = Set.of(
                "schema_version", "previous_sha256", "next_sha256");
        private final Path root;
        private final Path current;
        private final Path next;
        private final Path intent;

        Store(Path root) {
            this.root = root;
            this.current = root.resolve("checkpoint.json");
            this.next = root.resolve("checkpoint.next.json");
            this.intent = root.resolve("checkpoint.intent.json");
        }

        Snapshot loadAndRecover(
                OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding expectedBinding) throws Exception {
            requireSafeRoot();
            recoverTransaction(expectedBinding);
            if (!regularNoLink(current)) {
                return null;
            }
            Snapshot snapshot = parse(Files.readAllBytes(current));
            requireBinding(expectedBinding, snapshot.binding());
            return snapshot;
        }

        void prepare(Snapshot expectedPrevious, Snapshot nextSnapshot) throws Exception {
            if (nextSnapshot == null) {
                throw new IllegalArgumentException("CHECKPOINT_NEXT_MISSING");
            }
            Snapshot currentSnapshot = loadAndRecover(nextSnapshot.binding());
            if (!sameSnapshot(expectedPrevious, currentSnapshot)) {
                throw new IllegalStateException("CHECKPOINT_CONCURRENT_CHANGE");
            }
            if (currentSnapshot != null) {
                requireTransition(currentSnapshot, nextSnapshot);
            }
            byte[] nextBytes = canonicalBytes(nextSnapshot);
            String nextHash = OkxMicrostructureCanonicalDrop.sha256(nextBytes);
            String previousHash = currentSnapshot == null
                    ? null : OkxMicrostructureCanonicalDrop.sha256(canonicalBytes(currentSnapshot));
            if (nextHash.equals(previousHash)) {
                return;
            }
            byte[] intentBytes = OkxMicrostructureCanonicalDrop.canonicalBytes(Map.of(
                    "schema_version", "OKX_MICROSTRUCTURE_CHECKPOINT_WRITE_INTENT_V3R1",
                    "previous_sha256", previousHash == null ? "NONE" : previousHash,
                    "next_sha256", nextHash));
            writeCreateNew(intent, intentBytes);
            forceDirectory(root);
            writeCreateNew(next, nextBytes);
            forceDirectory(root);
        }

        Snapshot commitPrepared(
                OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding expectedBinding) throws Exception {
            return loadAndRecover(expectedBinding);
        }

        void save(Snapshot expectedPrevious, Snapshot nextSnapshot) throws Exception {
            prepare(expectedPrevious, nextSnapshot);
            Snapshot committed = commitPrepared(nextSnapshot.binding());
            if (!sameSnapshot(nextSnapshot, committed)) {
                throw new IllegalStateException("CHECKPOINT_COMMIT_MISMATCH");
            }
        }

        private void recoverTransaction(
                OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding expectedBinding) throws Exception {
            boolean hasIntent = Files.exists(intent, LinkOption.NOFOLLOW_LINKS);
            boolean hasNext = Files.exists(next, LinkOption.NOFOLLOW_LINKS);
            if (!hasIntent) {
                if (hasNext) {
                    throw new IllegalStateException("CHECKPOINT_ORPHAN_NEXT");
                }
                return;
            }
            if (!regularNoLink(intent)) {
                throw new IllegalStateException("CHECKPOINT_UNSAFE_INTENT");
            }
            Map<String, Object> parsedIntent = STRICT_MAPPER.readValue(
                    Files.readAllBytes(intent), new TypeReference<>() { });
            if (!parsedIntent.keySet().equals(INTENT_KEYS)
                    || !"OKX_MICROSTRUCTURE_CHECKPOINT_WRITE_INTENT_V3R1".equals(
                    parsedIntent.get("schema_version"))) {
                throw new IllegalStateException("CHECKPOINT_INTENT_INVALID");
            }
            String previousHash = requiredString(parsedIntent, "previous_sha256");
            String nextHash = requiredString(parsedIntent, "next_sha256");
            if (!("NONE".equals(previousHash) || validHash(previousHash))
                    || !validHash(nextHash)) {
                throw new IllegalStateException("CHECKPOINT_INTENT_HASH_INVALID");
            }
            String currentHash = regularNoLink(current)
                    ? OkxMicrostructureCanonicalDrop.sha256(Files.readAllBytes(current)) : "NONE";
            if (!hasNext) {
                if (currentHash.equals(previousHash) || currentHash.equals(nextHash)) {
                    Files.delete(intent);
                    forceDirectory(root);
                    return;
                }
                throw new IllegalStateException("CHECKPOINT_RECOVERY_CONFLICT");
            }
            if (!regularNoLink(next)) {
                throw new IllegalStateException("CHECKPOINT_UNSAFE_NEXT");
            }
            byte[] nextBytes = Files.readAllBytes(next);
            if (!OkxMicrostructureCanonicalDrop.sha256(nextBytes).equals(nextHash)) {
                throw new IllegalStateException("CHECKPOINT_NEXT_HASH_MISMATCH");
            }
            Snapshot nextSnapshot = parse(nextBytes);
            requireBinding(expectedBinding, nextSnapshot.binding());
            if (currentHash.equals(previousHash)) {
                atomicReplace(next, current);
                forceDirectory(root);
            } else if (currentHash.equals(nextHash)) {
                Files.delete(next);
            } else {
                throw new IllegalStateException("CHECKPOINT_RECOVERY_CONFLICT");
            }
            Files.delete(intent);
            forceDirectory(root);
        }

        private void requireSafeRoot() throws Exception {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(root)) {
                    throw new IllegalStateException("CHECKPOINT_UNSAFE_ROOT");
                }
            } else {
                Files.createDirectories(root);
            }
            for (Path path : List.of(current, next, intent)) {
                if (Files.isSymbolicLink(path)
                        || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
                    throw new IllegalStateException("CHECKPOINT_UNSAFE_PATH");
                }
            }
        }
    }

    private static Map<String, Object> payload(Snapshot snapshot) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("started_at", text(snapshot.startedAt()));
        observation.put("last_observed_at", text(snapshot.lastObservedAt()));
        observation.put("acknowledged_channels", snapshot.acknowledgedChannels());
        observation.put("completed_minute_count", snapshot.completedMinuteCount());
        observation.put("data_message_count", snapshot.dataMessageCount());
        observation.put("control_event_count", snapshot.controlEventCount());
        observation.put("raw_arrival_chain_sha256", snapshot.rawArrivalChainSha256());
        observation.put("control_event_chain_sha256", snapshot.controlEventChainSha256());

        Map<String, Object> lastDisposition = null;
        if (snapshot.lastDispositionDay() != null) {
            lastDisposition = new LinkedHashMap<>();
            lastDisposition.put("day", snapshot.lastDispositionDay().toString());
            lastDisposition.put("kind", snapshot.lastDispositionKind());
            lastDisposition.put("artifact_sha256", snapshot.lastDispositionArtifactSha256());
        }
        Map<String, Object> pendingRejection = null;
        if (snapshot.pendingRejectionReason() != null) {
            pendingRejection = new LinkedHashMap<>();
            pendingRejection.put("reason", snapshot.pendingRejectionReason());
            pendingRejection.put(
                    "sanitized_control_event",
                    snapshot.pendingSanitizedControlEvent() == null ? null : Map.of(
                            "event", snapshot.pendingSanitizedControlEvent().event(),
                            "code", snapshot.pendingSanitizedControlEvent().code()));
        }
        Map<String, Object> pendingPublication = null;
        if (snapshot.pendingPublication() != null) {
            var pending = snapshot.pendingPublication();
            pendingPublication = new LinkedHashMap<>();
            pendingPublication.put("day", pending.day().toString());
            pendingPublication.put("kind", pending.kind());
            pendingPublication.put("envelope_name", pending.envelopeName());
            pendingPublication.put("envelope_sha256", pending.envelopeSha256());
            pendingPublication.put("bundle_name", pending.bundleName());
            pendingPublication.put("bundle_sha256", pending.bundleSha256());
            pendingPublication.put("disposition_at", pending.dispositionAt().toString());
        }

        var binding = snapshot.binding();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("state_type", STATE_TYPE);
        payload.put("authorization", OkxMicrostructureDiscoveryRecoveryDropV3R1.AUTHORIZATION);
        payload.put("generation_id", binding.generationId());
        payload.put("diagnostic_id", binding.diagnosticId());
        payload.put("recovery_contract_sha256",
                OkxMicrostructureDiscoveryRecoveryDropV3R1.RECOVERY_CONTRACT_SHA256);
        payload.put("producer_release_id", binding.producerReleaseId());
        payload.put("producer_manifest_sha256", binding.producerManifestSha256());
        payload.put("producer_identity", "agora-evidence-source");
        payload.put("state_authority", "SOURCE_PRIVATE_NON_AUTHORITATIVE");
        payload.put("canonical_state_access", false);
        payload.put("host_boot_id", snapshot.hostBootId());
        payload.put("phase", snapshot.phase().name());
        payload.put("active_day", snapshot.activeDay() == null
                ? null : snapshot.activeDay().toString());
        payload.put("current_complete_streak_count", snapshot.currentCompleteStreakCount());
        payload.put("observation", observation);
        payload.put("last_disposition", lastDisposition);
        payload.put("pending_rejection", pendingRejection);
        payload.put("pending_publication", pendingPublication);
        payload.put("updated_at", snapshot.updatedAt().toString());
        return payload;
    }

    private static Snapshot fromMap(Map<String, Object> document) {
        if (!SCHEMA_VERSION.equals(document.get("schema_version"))
                || !STATE_TYPE.equals(document.get("state_type"))
                || !OkxMicrostructureDiscoveryRecoveryDropV3R1.AUTHORIZATION.equals(
                document.get("authorization"))
                || !OkxMicrostructureDiscoveryRecoveryDropV3R1.RECOVERY_CONTRACT_SHA256.equals(
                document.get("recovery_contract_sha256"))
                || !"agora-evidence-source".equals(document.get("producer_identity"))
                || !"SOURCE_PRIVATE_NON_AUTHORITATIVE".equals(document.get("state_authority"))
                || !Boolean.FALSE.equals(document.get("canonical_state_access"))) {
            throw new IllegalArgumentException("CHECKPOINT_IDENTITY_INVALID");
        }
        String generationId = requiredString(document, "generation_id");
        String diagnosticId = requiredString(document, "diagnostic_id");
        var generation = java.util.regex.Pattern.compile(
                ".*-([0-9]{8})-(r[0-9]+)$").matcher(generationId);
        if (!generation.matches()) {
            throw new IllegalArgumentException("CHECKPOINT_IDENTITY_INVALID");
        }
        LocalDate start = LocalDate.parse(
                generation.group(1).substring(0, 4) + "-"
                        + generation.group(1).substring(4, 6) + "-"
                        + generation.group(1).substring(6, 8));
        var binding = new OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding(
                generationId,
                diagnosticId,
                requiredString(document, "producer_release_id"),
                requiredString(document, "producer_manifest_sha256"),
                start,
                start.plusDays(OkxMicrostructureDiscoveryRecoveryDropV3R1.CALENDAR_DAY_BUDGET - 1L));
        Map<String, Object> observation = requiredMap(document, "observation");
        if (!observation.keySet().equals(Set.of(
                "started_at", "last_observed_at", "acknowledged_channels",
                "completed_minute_count", "data_message_count", "control_event_count",
                "raw_arrival_chain_sha256", "control_event_chain_sha256"))) {
            throw new IllegalArgumentException("CHECKPOINT_OBSERVATION_KEYS_MISMATCH");
        }
        Map<String, Object> lastDisposition = nullableMap(document.get("last_disposition"));
        if (lastDisposition != null && !lastDisposition.keySet().equals(
                Set.of("day", "kind", "artifact_sha256"))) {
            throw new IllegalArgumentException("CHECKPOINT_DISPOSITION_KEYS_MISMATCH");
        }
        Map<String, Object> pendingRejection = nullableMap(document.get("pending_rejection"));
        if (pendingRejection != null && !pendingRejection.keySet().equals(
                Set.of("reason", "sanitized_control_event"))) {
            throw new IllegalArgumentException("CHECKPOINT_PENDING_REJECTION_KEYS_MISMATCH");
        }
        Map<String, Object> sanitized = pendingRejection == null
                ? null : nullableMap(pendingRejection.get("sanitized_control_event"));
        if (sanitized != null && !sanitized.keySet().equals(Set.of("event", "code"))) {
            throw new IllegalArgumentException("CHECKPOINT_PENDING_CONTROL_KEYS_MISMATCH");
        }
        Map<String, Object> pendingPublication = nullableMap(
                document.get("pending_publication"));
        if (pendingPublication != null && !pendingPublication.keySet().equals(Set.of(
                "day", "kind", "envelope_name", "envelope_sha256",
                "bundle_name", "bundle_sha256", "disposition_at"))) {
            throw new IllegalArgumentException("CHECKPOINT_PENDING_PUBLICATION_KEYS_MISMATCH");
        }
        return new Snapshot(
                binding,
                requiredString(document, "host_boot_id"),
                Phase.valueOf(requiredString(document, "phase")),
                nullableDay(document.get("active_day")),
                requiredInt(document, "current_complete_streak_count"),
                nullableInstant(observation.get("started_at")),
                nullableInstant(observation.get("last_observed_at")),
                requiredStringList(observation, "acknowledged_channels"),
                requiredInt(observation, "completed_minute_count"),
                requiredLong(observation, "data_message_count"),
                requiredLong(observation, "control_event_count"),
                requiredString(observation, "raw_arrival_chain_sha256"),
                requiredString(observation, "control_event_chain_sha256"),
                pendingRejection == null ? null : requiredString(pendingRejection, "reason"),
                sanitized == null ? null
                        : new OkxMicrostructureDiscoveryRecoveryDropV3R1.SanitizedControlEvent(
                        requiredString(sanitized, "event"), requiredString(sanitized, "code")),
                pendingPublication == null ? null
                        : new OkxMicrostructureDiscoveryRecoveryDropV3R1.PendingPublication(
                        LocalDate.parse(requiredString(pendingPublication, "day")),
                        requiredString(pendingPublication, "kind"),
                        requiredString(pendingPublication, "envelope_name"),
                        requiredString(pendingPublication, "envelope_sha256"),
                        nullableString(pendingPublication.get("bundle_name")),
                        nullableString(pendingPublication.get("bundle_sha256")),
                        Instant.parse(requiredString(pendingPublication, "disposition_at"))),
                lastDisposition == null ? null : LocalDate.parse(requiredString(lastDisposition, "day")),
                lastDisposition == null ? null : requiredString(lastDisposition, "kind"),
                lastDisposition == null ? null : requiredString(lastDisposition, "artifact_sha256"),
                Instant.parse(requiredString(document, "updated_at")));
    }

    private static void requireTransition(Snapshot previous, Snapshot next) {
        requireBinding(previous.binding(), next.binding());
        if (next.updatedAt().isBefore(previous.updatedAt())) {
            throw new IllegalArgumentException("CHECKPOINT_TIME_REGRESSION");
        }
        if (previous.phase() == Phase.TERMINAL && !sameSnapshot(previous, next)) {
            throw new IllegalArgumentException("CHECKPOINT_TERMINAL_IMMUTABLE");
        }
        if (previous.lastDispositionDay() != null
                && (next.lastDispositionDay() == null
                || next.lastDispositionDay().isBefore(previous.lastDispositionDay()))) {
            throw new IllegalArgumentException("CHECKPOINT_DAY_REGRESSION");
        }
        if (!java.util.Objects.equals(
                previous.lastDispositionDay(), next.lastDispositionDay())) {
            LocalDate expectedDisposition = previous.lastDispositionDay() == null
                    ? previous.binding().startDay()
                    : previous.lastDispositionDay().plusDays(1);
            if (!expectedDisposition.equals(next.lastDispositionDay())
                    || (previous.phase() == Phase.ACTIVE_DAY
                    && !previous.activeDay().equals(next.lastDispositionDay()))) {
                throw new IllegalArgumentException("CHECKPOINT_DISPOSITION_GAP");
            }
            if ("COMPLETE".equals(next.lastDispositionKind())
                    && (previous.phase() != Phase.ACTIVE_DAY
                    || !previous.activeDay().equals(next.lastDispositionDay()))) {
                throw new IllegalArgumentException("CHECKPOINT_COMPLETE_WITHOUT_ACTIVE_DAY");
            }
            if (previous.pendingRejectionReason() != null
                    && !"SOURCE_LIVENESS_REJECTED".equals(next.lastDispositionKind())) {
                throw new IllegalArgumentException("CHECKPOINT_PENDING_REJECTION_NOT_PUBLISHED");
            }
            if (previous.pendingPublication() == null
                    || !previous.pendingPublication().day().equals(next.lastDispositionDay())
                    || !previous.pendingPublication().kind().equals(next.lastDispositionKind())
                    || !previous.pendingPublication().envelopeSha256().equals(
                    next.lastDispositionArtifactSha256())) {
                throw new IllegalArgumentException("CHECKPOINT_PUBLICATION_NOT_COMMITTED");
            }
        }
        if (next.phase() == Phase.ACTIVE_DAY) {
            LocalDate expected = previous.lastDispositionDay() == null
                    ? previous.binding().startDay()
                    : previous.lastDispositionDay().plusDays(1);
            if (!expected.equals(next.activeDay())) {
                throw new IllegalArgumentException("CHECKPOINT_WRONG_ACTIVE_DAY");
            }
        }
        if (previous.phase() == Phase.ACTIVE_DAY && next.phase() == Phase.ACTIVE_DAY
                && !previous.hostBootId().equals(next.hostBootId())) {
            throw new IllegalArgumentException("CHECKPOINT_HOST_CHANGED_DURING_ACTIVE_DAY");
        }
        if (previous.phase() == Phase.ACTIVE_DAY && next.phase() == Phase.ACTIVE_DAY
                && (!previous.activeDay().equals(next.activeDay())
                || next.completedMinuteCount() < previous.completedMinuteCount()
                || next.dataMessageCount() < previous.dataMessageCount()
                || next.controlEventCount() < previous.controlEventCount())) {
            throw new IllegalArgumentException("CHECKPOINT_OBSERVATION_REGRESSION");
        }
        if (previous.pendingRejectionReason() != null
                && next.phase() == Phase.ACTIVE_DAY
                && (!previous.pendingRejectionReason().equals(next.pendingRejectionReason())
                || !java.util.Objects.equals(
                previous.pendingSanitizedControlEvent(), next.pendingSanitizedControlEvent())
                || next.completedMinuteCount() != previous.completedMinuteCount()
                || next.dataMessageCount() != previous.dataMessageCount()
                || next.controlEventCount() != previous.controlEventCount()
                || !next.rawArrivalChainSha256().equals(previous.rawArrivalChainSha256())
                || !next.controlEventChainSha256().equals(previous.controlEventChainSha256()))) {
            throw new IllegalArgumentException("CHECKPOINT_PENDING_REJECTION_IMMUTABLE");
        }
        if (previous.pendingPublication() != null
                && java.util.Objects.equals(
                previous.lastDispositionDay(), next.lastDispositionDay())
                && !previous.pendingPublication().equals(next.pendingPublication())) {
            throw new IllegalArgumentException("CHECKPOINT_PENDING_PUBLICATION_IMMUTABLE");
        }
        boolean dispositionAdvanced = !java.util.Objects.equals(
                previous.lastDispositionDay(), next.lastDispositionDay());
        int expectedStreak = dispositionAdvanced
                ? ("COMPLETE".equals(next.lastDispositionKind())
                ? previous.currentCompleteStreakCount() + 1 : 0)
                : previous.currentCompleteStreakCount();
        if (next.currentCompleteStreakCount() != expectedStreak
                || (next.phase() == Phase.TERMINAL
                && next.currentCompleteStreakCount() != 14
                && !next.binding().endDay().equals(next.lastDispositionDay()))) {
            throw new IllegalArgumentException("CHECKPOINT_STREAK_INVALID");
        }
    }

    private static OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation emptyObservation() {
        return new OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation(
                null, null, List.of(), 0, 0, 0, ZERO_SHA256, ZERO_SHA256);
    }

    private static void requireBinding(
            OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding expected,
            OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding actual) {
        if (expected == null || !expected.equals(actual)) {
            throw new IllegalArgumentException("CHECKPOINT_BINDING_MISMATCH");
        }
    }

    private static boolean sameSnapshot(Snapshot left, Snapshot right) {
        if (left == null || right == null) {
            return left == right;
        }
        return java.util.Arrays.equals(canonicalBytes(left), canonicalBytes(right));
    }

    private static boolean validHash(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("CHECKPOINT_FIELD_INVALID: " + key);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nullableMap(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("CHECKPOINT_FIELD_INVALID");
        }
        return (Map<String, Object>) value;
    }

    private static String requiredString(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("CHECKPOINT_FIELD_INVALID: " + key);
        }
        return text;
    }

    private static String nullableString(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("CHECKPOINT_FIELD_INVALID");
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredStringList(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof List<?> list)
                || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("CHECKPOINT_FIELD_INVALID: " + key);
        }
        return (List<String>) list;
    }

    private static int requiredInt(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("CHECKPOINT_FIELD_INVALID: " + key);
        }
        long result = number.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE
                || number.doubleValue() != result) {
            throw new IllegalArgumentException("CHECKPOINT_FIELD_INVALID: " + key);
        }
        return (int) result;
    }

    private static long requiredLong(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException("CHECKPOINT_FIELD_INVALID: " + key);
        }
        return number.longValue();
    }

    private static LocalDate nullableDay(Object value) {
        return value == null ? null : LocalDate.parse((String) value);
    }

    private static Instant nullableInstant(Object value) {
        return value == null ? null : Instant.parse((String) value);
    }

    private static boolean regularNoLink(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
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

    private static void atomicReplace(Path source, Path target) throws Exception {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            throw new IllegalStateException("CHECKPOINT_ATOMIC_MOVE_UNSUPPORTED", error);
        }
    }

    private static void forceDirectory(Path directory) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win")) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
