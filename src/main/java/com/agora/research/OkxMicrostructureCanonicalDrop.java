package com.agora.research;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical byte construction and exclusive atomic publication for one microstructure day. */
final class OkxMicrostructureCanonicalDrop {

    static final String SOURCE_CONTRACT_SHA256 =
            "f2b353fc211d86755488bb7d9ee63057c6def8b9cd5353b86f7514981cc3e51e";
    static final String V3_SOURCE_CONTRACT_SHA256 =
            "66db4e6b624a6a2e0ee8f444b6e81518054142a6bc30f37123f0e21e7fafe28d";
    static final String DROP_ENVELOPE_SCHEMA_VERSION =
            "OKX_MICROSTRUCTURE_DROP_ENVELOPE_V1";
    static final String V3_DROP_ENVELOPE_SCHEMA_VERSION =
            "OKX_MICROSTRUCTURE_DROP_ENVELOPE_V3";
    static final String V3_DROP_ENVELOPE_SCHEMA_ID =
            "https://agora.local/research/okx-microstructure-drop-envelope.v3.schema.json";
    static final String V3_DROP_ENVELOPE_SCHEMA_SHA256 =
            "695d1e1d9ea89bbfa40ba29088bc1af4703ce6ebbb682739995c66d8dcbf64d3";
    static final String DAY_CANONICALIZATION =
            "UTF-8 compact JSON excluding seal; object keys sorted lexicographically";
    static final String ENVELOPE_CANONICALIZATION =
            "UTF-8 compact JSON excluding envelope_seal; object keys sorted lexicographically";
    static final String BUNDLE_DOCUMENT_CANONICALIZATION =
            "UTF-8 compact JSON including seal; object keys sorted lexicographically";
    static final String ENVELOPE_DOCUMENT_CANONICALIZATION =
            "UTF-8 compact JSON including envelope_seal; object keys sorted lexicographically";

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    private OkxMicrostructureCanonicalDrop() {
    }

    static DropDocuments create(
            Map<String, Object> dayPayload,
            LocalDate day,
            LocalDate predecessorDay,
            String predecessorBundleSha256,
            String diagnosticId,
            String producerReleaseId,
            String producerManifestSha256,
            Instant publishedAt) {
        return createWithSourceContract(
                dayPayload,
                day,
                predecessorDay,
                predecessorBundleSha256,
                diagnosticId,
                producerReleaseId,
                producerManifestSha256,
                publishedAt,
                SOURCE_CONTRACT_SHA256,
                DROP_ENVELOPE_SCHEMA_VERSION);
    }

    static DropDocuments createV3(
            Map<String, Object> dayPayload,
            LocalDate day,
            LocalDate predecessorDay,
            String predecessorBundleSha256,
            String diagnosticId,
            String producerReleaseId,
            String producerManifestSha256,
            Instant publishedAt) {
        return createWithSourceContract(
                dayPayload,
                day,
                predecessorDay,
                predecessorBundleSha256,
                diagnosticId,
                producerReleaseId,
                producerManifestSha256,
                publishedAt,
                V3_SOURCE_CONTRACT_SHA256,
                V3_DROP_ENVELOPE_SCHEMA_VERSION);
    }

    private static DropDocuments createWithSourceContract(
            Map<String, Object> dayPayload,
            LocalDate day,
            LocalDate predecessorDay,
            String predecessorBundleSha256,
            String diagnosticId,
            String producerReleaseId,
            String producerManifestSha256,
            Instant publishedAt,
            String sourceContractSha256,
            String envelopeSchemaVersion) {
        try {
            Map<String, Object> bundle = new LinkedHashMap<>(dayPayload);
            byte[] payloadBytes = canonicalBytes(bundle);
            bundle.put("seal", Map.of(
                    "algorithm", "SHA-256",
                    "payload_sha256", sha256(payloadBytes),
                    "canonicalization", DAY_CANONICALIZATION,
                    "sealed_at", publishedAt.toString()));
            byte[] bundleBytes = canonicalBytes(bundle);
            String bundleSha256 = sha256(bundleBytes);
            String bundleName = "okx-btc-usdt-microstructure-" + day + ".json";

            Map<String, Object> delivery = new LinkedHashMap<>();
            delivery.put("transport", "MICROSTRUCTURE_ONLY_ONE_WAY_DROP");
            delivery.put("bundle_document_canonicalization", BUNDLE_DOCUMENT_CANONICALIZATION);
            delivery.put("envelope_document_canonicalization", ENVELOPE_DOCUMENT_CANONICALIZATION);
            delivery.put("atomic_rename", true);
            delivery.put("overwrite", false);
            delivery.put("source_read_after_publish", false);
            delivery.put("symlinks", false);
            delivery.put("canonical_state_access", false);
            delivery.put("candle_chain_reuse", false);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", envelopeSchemaVersion);
            envelope.put("envelope_type", "IMMUTABLE_ONE_WAY_MICROSTRUCTURE_DAY");
            envelope.put("authorization", OkxMicrostructureCollector.AUTHORIZATION);
            envelope.put("diagnostic_id", diagnosticId);
            envelope.put("source_contract_sha256", sourceContractSha256);
            envelope.put("producer_release_id", producerReleaseId);
            envelope.put("producer_manifest_sha256", producerManifestSha256);
            envelope.put("producer_identity", "agora-evidence-source");
            envelope.put("day", day.toString());
            envelope.put("predecessor_day", predecessorDay == null ? null : predecessorDay.toString());
            envelope.put("predecessor_bundle_sha256", predecessorBundleSha256);
            envelope.put("bundle_name", bundleName);
            envelope.put("bundle_size_bytes", bundleBytes.length);
            envelope.put("bundle_sha256", bundleSha256);
            envelope.put("published_at", publishedAt.toString());
            envelope.put("idempotency_key", diagnosticId + ":" + day + ":" + bundleSha256);
            envelope.put("delivery_semantics", delivery);
            byte[] envelopePayloadBytes = canonicalBytes(envelope);
            envelope.put("envelope_seal", Map.of(
                    "algorithm", "SHA-256",
                    "payload_sha256", sha256(envelopePayloadBytes),
                    "canonicalization", ENVELOPE_CANONICALIZATION,
                    "sealed_at", publishedAt.toString()));
            byte[] envelopeBytes = canonicalBytes(envelope);

            return new DropDocuments(
                    day,
                    bundleName,
                    bundleBytes,
                    bundleSha256,
                    bundleBytes.length,
                    bundleName.replace(".json", ".envelope.json"),
                    envelopeBytes,
                    sha256(envelopeBytes));
        } catch (Exception error) {
            throw new IllegalStateException("CANONICAL_DROP_BUILD_FAILED", error);
        }
    }

    static byte[] canonicalBytes(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsBytes(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("value is not canonical JSON", error);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    interface DropSink {
        void publish(DropDocuments documents) throws Exception;
    }

    record DropDocuments(
            LocalDate day,
            String bundleName,
            byte[] bundleBytes,
            String bundleSha256,
            int bundleSizeBytes,
            String envelopeName,
            byte[] envelopeBytes,
            String envelopeSha256) {

        DropDocuments {
            bundleBytes = bundleBytes.clone();
            envelopeBytes = envelopeBytes.clone();
        }

        @Override
        public byte[] bundleBytes() {
            return bundleBytes.clone();
        }

        @Override
        public byte[] envelopeBytes() {
            return envelopeBytes.clone();
        }
    }

    static final class FileDropSink implements DropSink {
        private final Path privateStagingRoot;
        private final Path microstructureDropRoot;

        FileDropSink(Path privateStagingRoot, Path microstructureDropRoot) {
            this.privateStagingRoot = privateStagingRoot.toAbsolutePath().normalize();
            this.microstructureDropRoot = microstructureDropRoot.toAbsolutePath().normalize();
            String combined = (this.privateStagingRoot + " " + this.microstructureDropRoot).toLowerCase();
            if (!combined.contains("microstructure") || combined.contains("candle")) {
                throw new IllegalArgumentException("CANDLE_CHAIN_REUSE_FORBIDDEN");
            }
        }

        @Override
        public void publish(DropDocuments documents) throws Exception {
            byte[] bundleBytes = documents.bundleBytes();
            byte[] envelopeBytes = documents.envelopeBytes();
            if (bundleBytes.length != documents.bundleSizeBytes()
                    || !sha256(bundleBytes).equals(documents.bundleSha256())
                    || !sha256(envelopeBytes).equals(documents.envelopeSha256())) {
                throw new IllegalStateException("HASH_DRIFT_REJECT");
            }

            rejectSymlinks(privateStagingRoot);
            rejectSymlinks(microstructureDropRoot);
            Files.createDirectories(privateStagingRoot);
            Files.createDirectories(microstructureDropRoot);
            rejectSymlinks(privateStagingRoot);
            rejectSymlinks(microstructureDropRoot);

            Path stagedDay = privateStagingRoot.resolve(documents.day().toString());
            Path targetDay = microstructureDropRoot.resolve(documents.day().toString());
            Path publicationReservation = microstructureDropRoot.resolve(
                    "." + documents.day() + ".publish-reserved");
            if (Files.exists(stagedDay, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(targetDay, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(publicationReservation, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("OVERWRITE_REJECT");
            }
            Files.createDirectory(stagedDay);
            rejectSymlinks(stagedDay);
            writeAndForce(stagedDay.resolve(documents.bundleName()), bundleBytes);
            writeAndForce(stagedDay.resolve(documents.envelopeName()), envelopeBytes);
            forceDirectory(stagedDay);
            writeAndForce(publicationReservation, new byte[0]);
            forceDirectory(microstructureDropRoot);
            if (Files.exists(targetDay, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("OVERWRITE_REJECT");
            }
            try {
                Files.move(stagedDay, targetDay, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IllegalStateException("NON_ATOMIC_DELIVERY", error);
            }
            forceDirectory(microstructureDropRoot);
            // Deliberately no source read after publication.
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
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        private static void rejectSymlinks(Path path) throws Exception {
            Path current = path.getRoot();
            for (Path part : path) {
                current = current == null ? part : current.resolve(part);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                    throw new IllegalStateException("SYMLINK_REJECT");
                }
            }
        }
    }
}
