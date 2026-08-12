package com.agora.research;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Canonical V3R1 complete/rejection envelopes and exclusive one-way publication. */
final class OkxMicrostructureDiscoveryRecoveryDropV3R1 {

    static final String AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE";
    static final String RECOVERY_CONTRACT_SHA256 =
            "6448b47a373dca743df6492593582660461382b639fdb77aa897ffa5a9f604bd";
    static final String COMPLETE_SCHEMA_VERSION =
            "OKX_MICROSTRUCTURE_DISCOVERY_COMPLETE_ENVELOPE_V3R1";
    static final String REJECTION_SCHEMA_VERSION =
            "OKX_MICROSTRUCTURE_DISCOVERY_REJECTION_ENVELOPE_V3R1";
    static final String DAY_CANONICALIZATION =
            "UTF-8 compact JSON excluding seal; object keys sorted lexicographically";
    static final String ENVELOPE_CANONICALIZATION =
            "UTF-8 compact JSON excluding envelope_seal; object keys sorted lexicographically";
    static final String BUNDLE_DOCUMENT_CANONICALIZATION =
            "UTF-8 compact JSON including seal; object keys sorted lexicographically";
    static final String ENVELOPE_DOCUMENT_CANONICALIZATION =
            "UTF-8 compact JSON including envelope_seal; object keys sorted lexicographically";
    static final String TRANSPORT = "MICROSTRUCTURE_V3R1_ONE_WAY_DROP";
    static final int CALENDAR_DAY_BUDGET = 42;
    static final Set<String> REJECTION_REASONS = Set.of(
            "SERVICE_UPGRADE_NOTICE_64008",
            "TRANSPORT_DISCONNECT_UNPROVED_GAP",
            "PROCESS_RESTART_BEFORE_DAY_COMPLETE",
            "HOST_REBOOT_BEFORE_DAY_COMPLETE",
            "DUAL_CHANNEL_NOT_READY_AT_DAY_START");

    private static final Pattern GENERATION = Pattern.compile(
            "^okx-btcusdt-microstructure-discovery-v3r1-([0-9]{8})-(r[0-9]+)$");
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "^okx-btcusdt-microstructure-forward-v3r1-([0-9]{8})-(r[0-9]+)$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern RELEASE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

    private OkxMicrostructureDiscoveryRecoveryDropV3R1() {
    }

    record Binding(
            String generationId,
            String diagnosticId,
            String producerReleaseId,
            String producerManifestSha256,
            LocalDate startDay,
            LocalDate endDay) {

        Binding {
            var generation = generationId == null ? null : GENERATION.matcher(generationId);
            var diagnostic = diagnosticId == null ? null : DIAGNOSTIC.matcher(diagnosticId);
            if (generation == null || !generation.matches()
                    || diagnostic == null || !diagnostic.matches()) {
                throw new IllegalArgumentException("WRONG_IDENTITY");
            }
            if (producerReleaseId == null || producerReleaseId.isBlank()
                    || producerReleaseId.length() > 128
                    || !RELEASE.matcher(producerReleaseId).matches()
                    || producerManifestSha256 == null
                    || !SHA256.matcher(producerManifestSha256).matches()) {
                throw new IllegalArgumentException("WRONG_IDENTITY");
            }
            if (startDay == null || endDay == null
                    || !endDay.equals(startDay.plusDays(CALENDAR_DAY_BUDGET - 1L))) {
                throw new IllegalArgumentException("CONTRACT_HASH_MISMATCH");
            }
            String startToken = startDay.toString().replace("-", "");
            if (!generation.group(1).equals(startToken)
                    || !diagnostic.group(1).equals(startToken)
                    || !generation.group(2).equals(diagnostic.group(2))) {
                throw new IllegalArgumentException("WRONG_IDENTITY");
            }
        }

        int calendarIndex(LocalDate day) {
            long index = java.time.temporal.ChronoUnit.DAYS.between(startDay, day) + 1L;
            if (index < 1 || index > CALENDAR_DAY_BUDGET) {
                throw new IllegalArgumentException("WRONG_DAY");
            }
            return Math.toIntExact(index);
        }
    }

    record RejectionObservation(
            Instant startedAt,
            Instant lastObservedAt,
            List<String> acknowledgedChannels,
            int completedMinuteCount,
            long dataMessageCount,
            long controlEventCount,
            String rawArrivalChainSha256,
            String controlEventChainSha256) {

        RejectionObservation {
            if (acknowledgedChannels == null) {
                throw new IllegalArgumentException("CONTRACT_HASH_MISMATCH");
            }
            int suppliedChannelCount = acknowledgedChannels.size();
            acknowledgedChannels = List.copyOf(new java.util.TreeSet<>(acknowledgedChannels));
            if (acknowledgedChannels.size() != suppliedChannelCount
                    || !Set.of("books5", "trades").containsAll(acknowledgedChannels)
                    || completedMinuteCount < 0 || completedMinuteCount > 1439
                    || dataMessageCount < 0 || controlEventCount < 0
                    || rawArrivalChainSha256 == null
                    || !SHA256.matcher(rawArrivalChainSha256).matches()
                    || controlEventChainSha256 == null
                    || !SHA256.matcher(controlEventChainSha256).matches()
                    || (startedAt != null && lastObservedAt != null
                    && lastObservedAt.isBefore(startedAt))) {
                throw new IllegalArgumentException("CONTRACT_HASH_MISMATCH");
            }
        }
    }

    record SanitizedControlEvent(String event, String code) {
    }

    record DispositionDocuments(
            LocalDate day,
            String kind,
            String envelopeName,
            byte[] envelopeBytes,
            String envelopeSha256,
            String bundleName,
            byte[] bundleBytes,
            String bundleSha256) {

        DispositionDocuments {
            envelopeBytes = envelopeBytes.clone();
            bundleBytes = bundleBytes == null ? null : bundleBytes.clone();
        }

        @Override
        public byte[] envelopeBytes() {
            return envelopeBytes.clone();
        }

        @Override
        public byte[] bundleBytes() {
            return bundleBytes == null ? null : bundleBytes.clone();
        }

        PendingPublication pending(Instant dispositionAt) {
            return new PendingPublication(
                    day, kind, envelopeName, envelopeSha256,
                    bundleName, bundleSha256, dispositionAt);
        }
    }

    record PendingPublication(
            LocalDate day,
            String kind,
            String envelopeName,
            String envelopeSha256,
            String bundleName,
            String bundleSha256,
            Instant dispositionAt) {

        PendingPublication {
            if (day == null || dispositionAt == null
                    || !Set.of("COMPLETE", "SOURCE_LIVENESS_REJECTED").contains(kind)
                    || envelopeName == null || envelopeName.contains("/")
                    || envelopeName.contains("\\")
                    || envelopeSha256 == null || !SHA256.matcher(envelopeSha256).matches()
                    || (bundleName == null) != (bundleSha256 == null)
                    || (bundleName != null && (bundleName.contains("/")
                    || bundleName.contains("\\")
                    || !SHA256.matcher(bundleSha256).matches()))
                    || ("COMPLETE".equals(kind) != (bundleName != null))) {
                throw new IllegalArgumentException("PENDING_PUBLICATION_INVALID");
            }
        }
    }

    static DispositionDocuments complete(
            Map<String, Object> dayPayload,
            Binding binding,
            LocalDate day,
            Instant publishedAt) {
        if (dayPayload == null || dayPayload.containsKey("seal") || binding == null
                || day == null || publishedAt == null
                || !"OKX_MICROSTRUCTURE_FORWARD_DAY_V3".equals(
                dayPayload.get("schema_version"))
                || !AUTHORIZATION.equals(dayPayload.get("authorization"))
                || !day.toString().equals(dayPayload.get("day"))
                || publishedAt.isBefore(day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("INCOMPLETE_DAY");
        }
        Map<String, Object> bundle = new LinkedHashMap<>(dayPayload);
        bundle.put("seal", seal(
                OkxMicrostructureCanonicalDrop.sha256(
                        OkxMicrostructureCanonicalDrop.canonicalBytes(bundle)),
                DAY_CANONICALIZATION,
                publishedAt));
        byte[] bundleBytes = OkxMicrostructureCanonicalDrop.canonicalBytes(bundle);
        String bundleHash = OkxMicrostructureCanonicalDrop.sha256(bundleBytes);
        String bundleName = "okx-btc-usdt-microstructure-" + day + ".json";

        Map<String, Object> envelope = identity(binding, day);
        envelope.put("schema_version", COMPLETE_SCHEMA_VERSION);
        envelope.put("envelope_type", "IMMUTABLE_COMPLETE_MICROSTRUCTURE_DAY");
        envelope.put("diagnostic_id", binding.diagnosticId());
        envelope.put("bundle_name", bundleName);
        envelope.put("bundle_size_bytes", bundleBytes.length);
        envelope.put("bundle_sha256", bundleHash);
        envelope.put("published_at", publishedAt.toString());
        envelope.put("idempotency_key", binding.generationId() + ":" + day + ":" + bundleHash);
        envelope.put("delivery_semantics", completeDelivery());
        byte[] envelopeBytes = sealEnvelope(envelope, publishedAt);
        return new DispositionDocuments(
                day,
                "COMPLETE",
                bundleName.replace(".json", ".complete.envelope.json"),
                envelopeBytes,
                OkxMicrostructureCanonicalDrop.sha256(envelopeBytes),
                bundleName,
                bundleBytes,
                bundleHash);
    }

    static DispositionDocuments rejection(
            Binding binding,
            LocalDate day,
            String reason,
            RejectionObservation observation,
            SanitizedControlEvent sanitizedControlEvent,
            Instant rejectedAt) {
        if (binding == null || day == null || !REJECTION_REASONS.contains(reason)
                || observation == null || rejectedAt == null
                || (observation.startedAt() != null
                && rejectedAt.isBefore(observation.startedAt()))
                || (observation.lastObservedAt() != null
                && rejectedAt.isBefore(observation.lastObservedAt()))) {
            throw new IllegalArgumentException("UNKNOWN_EVENT");
        }
        if ("SERVICE_UPGRADE_NOTICE_64008".equals(reason)) {
            if (!new SanitizedControlEvent("notice", "64008").equals(sanitizedControlEvent)) {
                throw new IllegalArgumentException("UNKNOWN_EVENT");
            }
        } else if (sanitizedControlEvent != null) {
            throw new IllegalArgumentException("UNKNOWN_EVENT");
        }
        Map<String, Object> envelope = identity(binding, day);
        envelope.put("schema_version", REJECTION_SCHEMA_VERSION);
        envelope.put("envelope_type", "IMMUTABLE_SOURCE_LIVENESS_DAY_REJECTION");
        envelope.put("reason", reason);
        Map<String, Object> observationValue = new LinkedHashMap<>();
        observationValue.put("started_at", text(observation.startedAt()));
        observationValue.put("last_observed_at", text(observation.lastObservedAt()));
        observationValue.put("acknowledged_channels", observation.acknowledgedChannels());
        observationValue.put("completed_minute_count", observation.completedMinuteCount());
        observationValue.put("data_message_count", observation.dataMessageCount());
        observationValue.put("control_event_count", observation.controlEventCount());
        observationValue.put("raw_arrival_chain_sha256", observation.rawArrivalChainSha256());
        observationValue.put("control_event_chain_sha256", observation.controlEventChainSha256());
        envelope.put("observation", observationValue);
        envelope.put("sanitized_control_event", sanitizedControlEvent == null ? null : Map.of(
                "event", sanitizedControlEvent.event(), "code", sanitizedControlEvent.code()));
        envelope.put("rejected_at", rejectedAt.toString());
        envelope.put("idempotency_key", binding.generationId() + ":" + day
                + ":SOURCE_LIVENESS_REJECTED");
        envelope.put("delivery_semantics", rejectionDelivery());
        byte[] envelopeBytes = sealEnvelope(envelope, rejectedAt);
        String prefix = "okx-btc-usdt-microstructure-" + day;
        return new DispositionDocuments(
                day,
                "SOURCE_LIVENESS_REJECTED",
                prefix + ".rejection.envelope.json",
                envelopeBytes,
                OkxMicrostructureCanonicalDrop.sha256(envelopeBytes),
                null,
                null,
                null);
    }

    private static Map<String, Object> identity(Binding binding, LocalDate day) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("authorization", AUTHORIZATION);
        envelope.put("generation_id", binding.generationId());
        envelope.put("recovery_contract_sha256", RECOVERY_CONTRACT_SHA256);
        envelope.put("producer_release_id", binding.producerReleaseId());
        envelope.put("producer_manifest_sha256", binding.producerManifestSha256());
        envelope.put("producer_identity", "agora-evidence-source");
        envelope.put("day", day.toString());
        envelope.put("calendar_index", binding.calendarIndex(day));
        return envelope;
    }

    private static Map<String, Object> completeDelivery() {
        Map<String, Object> delivery = rejectionDelivery();
        delivery.put("bundle_document_canonicalization", BUNDLE_DOCUMENT_CANONICALIZATION);
        delivery.put("envelope_document_canonicalization", ENVELOPE_DOCUMENT_CANONICALIZATION);
        return delivery;
    }

    private static Map<String, Object> rejectionDelivery() {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("transport", TRANSPORT);
        delivery.put("atomic_rename", true);
        delivery.put("overwrite", false);
        delivery.put("source_read_after_publish", false);
        delivery.put("symlinks", false);
        delivery.put("canonical_state_access", false);
        delivery.put("partial_market_aggregates", false);
        delivery.put("repair_retry_stitch_backfill", false);
        return delivery;
    }

    private static Map<String, Object> seal(
            String payloadSha256, String canonicalization, Instant sealedAt) {
        Map<String, Object> seal = new LinkedHashMap<>();
        seal.put("algorithm", "SHA-256");
        seal.put("payload_sha256", payloadSha256);
        seal.put("canonicalization", canonicalization);
        seal.put("sealed_at", sealedAt.toString());
        return seal;
    }

    private static byte[] sealEnvelope(Map<String, Object> envelope, Instant sealedAt) {
        byte[] payload = OkxMicrostructureCanonicalDrop.canonicalBytes(envelope);
        envelope.put("envelope_seal", seal(
                OkxMicrostructureCanonicalDrop.sha256(payload),
                ENVELOPE_CANONICALIZATION,
                sealedAt));
        return OkxMicrostructureCanonicalDrop.canonicalBytes(envelope);
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }

    interface DropSink {
        void prepare(DispositionDocuments documents) throws Exception;

        void commit(PendingPublication publication) throws Exception;

        default void publish(DispositionDocuments documents) throws Exception {
            prepare(documents);
            commit(documents.pending(envelopeSealedAt(documents.envelopeBytes())));
        }

        private static Instant envelopeSealedAt(byte[] bytes) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> document = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(bytes, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> seal = (Map<String, Object>) document.get("envelope_seal");
                return Instant.parse((String) seal.get("sealed_at"));
            } catch (Exception error) {
                throw new IllegalArgumentException("ENVELOPE_SEAL_TIME_INVALID", error);
            }
        }
    }

    static final class FileDropSink implements DropSink {
        private final Path privateStagingRoot;
        private final Path dropRoot;

        FileDropSink(Path privateStagingRoot, Path dropRoot) {
            this.privateStagingRoot = privateStagingRoot.toAbsolutePath().normalize();
            this.dropRoot = dropRoot.toAbsolutePath().normalize();
            String combined = (this.privateStagingRoot + " " + this.dropRoot).toLowerCase();
            if (!combined.contains("microstructure-v3r1") || combined.contains("candle")) {
                throw new IllegalArgumentException("WRONG_NAMESPACE");
            }
        }

        @Override
        public void prepare(DispositionDocuments documents) throws Exception {
            byte[] envelopeBytes = documents.envelopeBytes();
            byte[] bundleBytes = documents.bundleBytes();
            if (!OkxMicrostructureCanonicalDrop.sha256(envelopeBytes)
                    .equals(documents.envelopeSha256())
                    || (bundleBytes == null) != (documents.bundleName() == null)
                    || (bundleBytes != null && !OkxMicrostructureCanonicalDrop.sha256(bundleBytes)
                    .equals(documents.bundleSha256()))) {
                throw new IllegalStateException("HASH_DRIFT_REJECT");
            }
            rejectSymlinks(privateStagingRoot);
            rejectSymlinks(dropRoot);
            Files.createDirectories(privateStagingRoot);
            Files.createDirectories(dropRoot);
            rejectSymlinks(privateStagingRoot);
            rejectSymlinks(dropRoot);

            Path preparedRoot = privateStagingRoot.resolve(".source-publication-prepared");
            Files.createDirectories(preparedRoot);
            rejectSymlinks(preparedRoot);
            Path stagedDay = preparedRoot.resolve(documents.envelopeSha256());
            Path temporary = preparedRoot.resolve(
                    "." + documents.envelopeSha256() + ".tmp-"
                            + ProcessHandle.current().pid());
            Path targetDay = dropRoot.resolve(documents.day().toString());
            Path reservation = dropRoot.resolve(
                    "." + documents.day() + ".publish-reserved");
            if (Files.exists(targetDay, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(reservation, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("OVERWRITE_REJECT");
            }
            PendingPublication pending = documents.pending(
                    DropSink.envelopeSealedAt(envelopeBytes));
            if (Files.exists(stagedDay, LinkOption.NOFOLLOW_LINKS)) {
                verifyPrepared(stagedDay, pending);
                return;
            }
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("UNSAFE_PREPARE_TEMP");
            }
            Files.createDirectory(temporary);
            rejectSymlinks(temporary);
            if (bundleBytes != null) {
                writeAndForce(temporary.resolve(documents.bundleName()), bundleBytes);
            }
            writeAndForce(temporary.resolve(documents.envelopeName()), envelopeBytes);
            forceDirectory(temporary);
            try {
                Files.move(temporary, stagedDay, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IllegalStateException("NON_ATOMIC_PREPARATION", error);
            }
            forceDirectory(preparedRoot);
        }

        @Override
        public void commit(PendingPublication publication) throws Exception {
            rejectSymlinks(privateStagingRoot);
            rejectSymlinks(dropRoot);
            Path prepared = privateStagingRoot.resolve(".source-publication-prepared")
                    .resolve(publication.envelopeSha256());
            Path target = dropRoot.resolve(publication.day().toString());
            Path reservation = dropRoot.resolve(
                    "." + publication.day() + ".publish-reserved");
            boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            boolean reservationExists = Files.exists(reservation, LinkOption.NOFOLLOW_LINKS);
            boolean preparedExists = Files.exists(prepared, LinkOption.NOFOLLOW_LINKS);
            if (targetExists && reservationExists && !preparedExists
                    && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(target) && !Files.isSymbolicLink(reservation)) {
                return;
            }
            if ((targetExists || reservationExists)
                    && !(reservationExists && !targetExists && preparedExists)) {
                throw new IllegalStateException("PUBLICATION_RECOVERY_CONFLICT");
            }
            if (!preparedExists) {
                throw new IllegalStateException("PREPARED_PUBLICATION_MISSING");
            }
            verifyPrepared(prepared, publication);
            if (!reservationExists) {
                writeAndForce(reservation, new byte[0]);
                forceDirectory(dropRoot);
            }
            try {
                Files.move(prepared, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IllegalStateException("NON_ATOMIC_DELIVERY", error);
            }
            forceDirectory(dropRoot);
            // Deliberately no source read after publication.
        }

        private static void verifyPrepared(
                Path directory, PendingPublication publication) throws Exception {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(directory)) {
                throw new IllegalStateException("UNSAFE_PREPARED_PUBLICATION");
            }
            Set<String> expected = publication.bundleName() == null
                    ? Set.of(publication.envelopeName())
                    : Set.of(publication.envelopeName(), publication.bundleName());
            final Set<String> actual;
            try (var children = Files.list(directory)) {
                actual = children.map(path -> path.getFileName().toString())
                        .collect(java.util.stream.Collectors.toSet());
            }
            if (!actual.equals(expected)) {
                throw new IllegalStateException("PREPARED_PUBLICATION_SHAPE_MISMATCH");
            }
            verifyPreparedFile(
                    directory.resolve(publication.envelopeName()),
                    publication.envelopeSha256());
            if (publication.bundleName() != null) {
                verifyPreparedFile(
                        directory.resolve(publication.bundleName()),
                        publication.bundleSha256());
            }
        }

        private static void verifyPreparedFile(Path path, String expectedHash) throws Exception {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)
                    || !OkxMicrostructureCanonicalDrop.sha256(Files.readAllBytes(path))
                    .equals(expectedHash)) {
                throw new IllegalStateException("PREPARED_PUBLICATION_HASH_MISMATCH");
            }
        }

        private static void writeAndForce(Path path, byte[] bytes) throws Exception {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("OVERWRITE_REJECT");
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
            if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
                return;
            }
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        private static void rejectSymlinks(Path path) throws Exception {
            Path current = path.getRoot();
            for (Path part : path) {
                current = current == null ? part : current.resolve(part);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSymbolicLink(current)) {
                    throw new IllegalStateException("SYMLINK_REJECT");
                }
            }
        }
    }
}
