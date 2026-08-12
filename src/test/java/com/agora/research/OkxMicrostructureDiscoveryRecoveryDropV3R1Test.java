package com.agora.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxMicrostructureDiscoveryRecoveryDropV3R1Test {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final String ZERO = "0".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindingIdentityAndFixedCalendarMustAgree() {
        var binding = binding();
        assertEquals(1, binding.calendarIndex(START));
        assertEquals(42, binding.calendarIndex(START.plusDays(41)));
        assertThrows(IllegalArgumentException.class, () -> binding.calendarIndex(START.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> newBinding(
                "okx-btcusdt-microstructure-forward-v3r1-20260902-r3"));
        assertThrows(IllegalArgumentException.class, () ->
                new OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding(
                        binding.generationId(),
                        binding.diagnosticId(),
                        binding.producerReleaseId(),
                        binding.producerManifestSha256(),
                        START,
                        START.plusDays(42)));
    }

    @Test
    void completeEnvelopeHasNoCrossGapPredecessorAndIsCanonicallySealed() throws Exception {
        Instant publishedAt = Instant.parse("2026-09-02T00:00:02Z");
        var documents = OkxMicrostructureDiscoveryRecoveryDropV3R1.complete(
                dayPayload(START), binding(), START, publishedAt);
        Map<String, Object> envelope = mapper.readValue(
                documents.envelopeBytes(), new TypeReference<>() { });
        Map<String, Object> bundle = mapper.readValue(
                documents.bundleBytes(), new TypeReference<>() { });

        assertEquals("COMPLETE", documents.kind());
        assertEquals(
                "okx-btc-usdt-microstructure-2026-09-01.complete.envelope.json",
                documents.envelopeName());
        assertFalse(envelope.containsKey("predecessor_day"));
        assertFalse(envelope.containsKey("predecessor_bundle_sha256"));
        assertEquals("OKX_MICROSTRUCTURE_DISCOVERY_COMPLETE_ENVELOPE_V3R1",
                envelope.get("schema_version"));
        assertEquals(1, envelope.get("calendar_index"));
        assertEquals(documents.bundleSha256(), envelope.get("bundle_sha256"));
        assertEquals(documents.bundleBytes().length, envelope.get("bundle_size_bytes"));
        assertEquals(OkxMicrostructureCanonicalDrop.sha256(documents.envelopeBytes()),
                documents.envelopeSha256());
        assertEquals("OKX_MICROSTRUCTURE_FORWARD_DAY_V3", bundle.get("schema_version"));
        assertEquals(publishedAt.toString(), castMap(bundle.get("seal")).get("sealed_at"));
        assertEnvelopeSeal(envelope);
    }

    @Test
    void completeEnvelopeAcceptsExactFullCollectorDay() throws Exception {
        OkxMicrostructureCollector collector = new OkxMicrostructureCollector(mapper);
        collector.acceptRaw(acknowledgement("trades"));
        collector.acceptRaw(acknowledgement("books5"));
        long dayStart = START.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        for (int minute = 0; minute < 1_440; minute++) {
            long start = dayStart + minute * 60_000L;
            long bookSequence = minute * 2L + 1L;
            long tradeSequence = minute + 1L;
            collector.acceptRaw(bookMessage(start + 500L, bookSequence));
            collector.acceptRaw(tradeMessage(start + 1_000L, tradeSequence));
            collector.acceptRaw(bookMessage(start + 2_000L, bookSequence + 1L));
        }

        Map<String, Object> payload = collector.buildV3Payload(START);
        var documents = OkxMicrostructureDiscoveryRecoveryDropV3R1.complete(
                payload, binding(), START, Instant.parse("2026-09-02T00:00:02Z"));
        Map<String, Object> bundle = mapper.readValue(
                documents.bundleBytes(), new TypeReference<>() { });
        Map<String, Object> envelope = mapper.readValue(
                documents.envelopeBytes(), new TypeReference<>() { });

        assertEquals(1_440, ((List<?>) bundle.get("minutes")).size());
        assertEquals("CLEAN", castMap(bundle.get("integrity")).get("status"));
        assertFalse(envelope.containsKey("predecessor_day"));
        assertEquals(documents.bundleSha256(), envelope.get("bundle_sha256"));
        assertEnvelopeSeal(envelope);
    }

    @Test
    void rejectionContainsOnlyLivenessObservationAndExactNotice() throws Exception {
        Instant rejectedAt = Instant.parse("2026-09-01T09:00:01Z");
        var observation = observation();
        var documents = OkxMicrostructureDiscoveryRecoveryDropV3R1.rejection(
                binding(),
                START,
                "SERVICE_UPGRADE_NOTICE_64008",
                observation,
                new OkxMicrostructureDiscoveryRecoveryDropV3R1.SanitizedControlEvent(
                        "notice", "64008"),
                rejectedAt);
        Map<String, Object> envelope = mapper.readValue(
                documents.envelopeBytes(), new TypeReference<>() { });
        String serialized = new String(documents.envelopeBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertEquals("SOURCE_LIVENESS_REJECTED", documents.kind());
        assertNull(documents.bundleBytes());
        assertEquals(
                "58c86d08da88a51f46b77af934f4b4c03cc469bb688ae601f5ffbdc005817f2a",
                documents.envelopeSha256());
        assertEquals("OKX_MICROSTRUCTURE_DISCOVERY_REJECTION_ENVELOPE_V3R1",
                envelope.get("schema_version"));
        assertEquals(Map.of("event", "notice", "code", "64008"),
                envelope.get("sanitized_control_event"));
        assertFalse(serialized.contains("return"));
        assertFalse(serialized.contains("feature"));
        assertFalse(serialized.contains("direction"));
        assertEnvelopeSeal(envelope);

        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureDiscoveryRecoveryDropV3R1.rejection(
                        binding(), START, "SERVICE_UPGRADE_NOTICE_64008", observation,
                        null, rejectedAt));
        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureDiscoveryRecoveryDropV3R1.rejection(
                        binding(), START, "PROCESS_RESTART_BEFORE_DAY_COMPLETE", observation,
                        new OkxMicrostructureDiscoveryRecoveryDropV3R1.SanitizedControlEvent(
                                "notice", "64008"), rejectedAt));
    }

    @Test
    void fileDropPublishesCompleteAndRejectionOnce(@TempDir Path temp) throws Exception {
        Path staging = temp.resolve("microstructure-v3r1-private-staging");
        Path drop = temp.resolve("microstructure-v3r1-drop");
        var sink = new OkxMicrostructureDiscoveryRecoveryDropV3R1.FileDropSink(staging, drop);
        var complete = OkxMicrostructureDiscoveryRecoveryDropV3R1.complete(
                dayPayload(START), binding(), START, Instant.parse("2026-09-02T00:00:02Z"));
        sink.publish(complete);
        Path completeDay = drop.resolve("2026-09-01");
        assertArrayEquals(
                complete.bundleBytes(),
                Files.readAllBytes(completeDay.resolve(complete.bundleName())));
        assertArrayEquals(
                complete.envelopeBytes(),
                Files.readAllBytes(completeDay.resolve(complete.envelopeName())));
        assertTrue(Files.isRegularFile(drop.resolve(".2026-09-01.publish-reserved")));
        assertFalse(Files.exists(staging.resolve("2026-09-01")));
        assertThrows(IllegalStateException.class, () -> sink.publish(complete));

        var rejected = OkxMicrostructureDiscoveryRecoveryDropV3R1.rejection(
                binding(), START.plusDays(1), "PROCESS_RESTART_BEFORE_DAY_COMPLETE",
                observation(), null, Instant.parse("2026-09-02T09:00:01Z"));
        sink.publish(rejected);
        Path rejectedDay = drop.resolve("2026-09-02");
        try (var paths = Files.list(rejectedDay)) {
            assertEquals(List.of(rejected.envelopeName()),
                    paths.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void invalidCompleteIdentityAndObservationFailBeforeBytesExist() {
        Map<String, Object> changed = dayPayload(START);
        changed.put("day", "2026-09-02");
        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureDiscoveryRecoveryDropV3R1.complete(
                        changed, binding(), START, Instant.parse("2026-09-02T00:00:02Z")));
        assertThrows(IllegalArgumentException.class, () ->
                new OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation(
                        null, null, List.of("trades", "trades"), 0, 0, 0, ZERO, ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation(
                        null, null, List.of(), 1440, 0, 0, ZERO, ZERO));
    }

    private OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding binding() {
        return newBinding("okx-btcusdt-microstructure-forward-v3r1-20260901-r3");
    }

    private static String acknowledgement(String channel) {
        return "{\"event\":\"subscribe\",\"arg\":{\"channel\":\"" + channel
                + "\",\"instId\":\"BTC-USDT\"}}";
    }

    private static String bookMessage(long timestamp, long sequence) {
        return "{\"arg\":{\"channel\":\"books5\",\"instId\":\"BTC-USDT\"},\"data\":[{"
                + "\"asks\":[[\"101\",\"1\",\"0\",\"1\"]],"
                + "\"bids\":[[\"99\",\"1\",\"0\",\"1\"]],"
                + "\"ts\":\"" + timestamp + "\",\"seqId\":" + sequence + "}]}";
    }

    private static String tradeMessage(long timestamp, long sequence) {
        return "{\"arg\":{\"channel\":\"trades\",\"instId\":\"BTC-USDT\"},\"data\":[{"
                + "\"tradeId\":\"" + sequence + "\",\"px\":\"100\",\"sz\":\"1\","
                + "\"side\":\"buy\",\"ts\":\"" + timestamp
                + "\",\"count\":\"1\",\"source\":\"0\",\"seqId\":\""
                + sequence + "\"}]}";
    }

    private OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding newBinding(
            String diagnosticId) {
        return new OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding(
                "okx-btcusdt-microstructure-discovery-v3r1-20260901-r3",
                diagnosticId,
                "20260812T030000Z",
                "a".repeat(64),
                START,
                START.plusDays(41));
    }

    private Map<String, Object> dayPayload(LocalDate day) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", "OKX_MICROSTRUCTURE_FORWARD_DAY_V3");
        value.put("bundle_type", "FORWARD_MICROSTRUCTURE_DAY_RESEARCH_ONLY");
        value.put("authorization", "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE");
        value.put("source", Map.of("fixture", "collector-contract-tested-separately"));
        value.put("day", day.toString());
        value.put("capture", Map.of());
        value.put("integrity", Map.of());
        value.put("minutes", List.of());
        return value;
    }

    private OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation observation() {
        return new OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation(
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-01T09:00:00Z"),
                List.of("trades", "books5"),
                539,
                10_000,
                3,
                ZERO,
                ZERO);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private void assertEnvelopeSeal(Map<String, Object> envelope) {
        Map<String, Object> seal = castMap(envelope.remove("envelope_seal"));
        assertEquals("SHA-256", seal.get("algorithm"));
        assertEquals(
                OkxMicrostructureCanonicalDrop.sha256(
                        OkxMicrostructureCanonicalDrop.canonicalBytes(envelope)),
                seal.get("payload_sha256"));
    }
}
