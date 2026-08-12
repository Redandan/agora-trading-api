package com.agora.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxMicrostructureDiscoveryRecoverySourceCliTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindingRequiresEveryFrozenIdentityAndHash() throws Exception {
        var parsed = OkxMicrostructureDiscoveryRecoverySourceCli.parseBinding(bindingBytes());
        assertEquals(binding(), parsed);

        byte[] changed = new String(bindingBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("a75aea4e247cdc134c441e5de33c2773a984c076eda8f1cdd85a0c3440260fb2",
                        "0".repeat(64))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> OkxMicrostructureDiscoveryRecoverySourceCli.parseBinding(changed));
        assertThrows(IllegalArgumentException.class,
                () -> OkxMicrostructureDiscoveryRecoverySourceCli.requireNoArguments(
                        new String[]{"caller-selected"}));
    }

    @Test
    void exactUpgradeNoticePublishesOneSanitizedRejectionAndResetsStreak()
            throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T23:50:00Z"));
        InMemoryCheckpoint checkpoint = new InMemoryCheckpoint();
        RecordingSink sink = new RecordingSink();
        var producer = producer(clock, checkpoint, sink);
        producer.initialize();
        producer.onRaw(acknowledgement("trades"));
        producer.onRaw(acknowledgement("books5"));
        clock.set(Instant.parse("2026-09-01T00:00:01Z"));
        producer.onRaw(bookMessage(START, 0, 1));
        producer.onRaw(tradeMessage(START, 0, 1));
        clock.set(Instant.parse("2026-09-01T12:00:00Z"));
        producer.onRaw("{\"event\":\"notice\",\"code\":\"64008\","
                + "\"msg\":\"service upgrade\"}");

        assertEquals(1, sink.documents.size());
        var documents = sink.documents.getFirst();
        Map<String, Object> envelope = mapper.readValue(
                documents.envelopeBytes(), new TypeReference<>() { });
        assertEquals("SOURCE_LIVENESS_REJECTED", documents.kind());
        assertEquals("SERVICE_UPGRADE_NOTICE_64008", envelope.get("reason"));
        assertEquals(Map.of("event", "notice", "code", "64008"),
                envelope.get("sanitized_control_event"));
        assertFalse(new String(documents.envelopeBytes(),
                java.nio.charset.StandardCharsets.UTF_8).contains("feature"));
        assertEquals(0, producer.checkpoint().currentCompleteStreakCount());
        assertEquals(OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Phase.BETWEEN_DAYS,
                producer.checkpoint().phase());
        assertNull(producer.checkpoint().pendingRejectionReason());

        producer.onDisconnect(Instant.parse("2026-09-01T12:00:01Z"));
        assertTrue(producer.accepting());
    }

    @Test
    void pendingNoticeIsPublishedWithOriginalReasonAfterProcessRestart() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T12:01:00Z"));
        InMemoryCheckpoint access = new InMemoryCheckpoint();
        var initial = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.initial(
                binding(), "boot-a", Instant.parse("2026-08-31T23:50:00Z"));
        var active = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.active(
                initial, "boot-a", START,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-01T12:00:00Z"),
                List.of("books5", "trades"), 17, 123, 2,
                "1".repeat(64), "2".repeat(64),
                Instant.parse("2026-09-01T12:00:00Z"));
        access.state = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.pendingRejection(
                active,
                "SERVICE_UPGRADE_NOTICE_64008",
                new OkxMicrostructureDiscoveryRecoveryDropV3R1.SanitizedControlEvent(
                        "notice", "64008"),
                Instant.parse("2026-09-01T12:00:01Z"));
        RecordingSink sink = new RecordingSink();

        var producer = producer(clock, access, sink);
        producer.initialize();

        Map<String, Object> envelope = mapper.readValue(
                sink.documents.getFirst().envelopeBytes(), new TypeReference<>() { });
        assertEquals("SERVICE_UPGRADE_NOTICE_64008", envelope.get("reason"));
        assertEquals(Map.of("event", "notice", "code", "64008"),
                envelope.get("sanitized_control_event"));
        assertEquals(START, producer.checkpoint().lastDispositionDay());
    }

    @Test
    void fullCollectorDayPublishesCompleteV3R1WithoutPredecessor() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T23:50:00Z"));
        InMemoryCheckpoint checkpoint = new InMemoryCheckpoint();
        RecordingSink sink = new RecordingSink();
        var producer = producer(clock, checkpoint, sink);
        producer.initialize();
        producer.onRaw(acknowledgement("trades"));
        producer.onRaw(acknowledgement("books5"));
        clock.set(Instant.parse("2026-09-01T00:00:01Z"));
        for (int minute = 0; minute < 1_440; minute++) {
            producer.onRaw(bookMessage(START, minute, minute * 2L + 1L));
            producer.onRaw(tradeMessage(START, minute, minute + 1L));
            producer.onRaw(bookMessage(START, minute, minute * 2L + 2L));
        }
        clock.set(Instant.parse("2026-09-02T00:00:02Z"));
        producer.onRaw(bookMessage(START.plusDays(1), 0, 3_001));

        assertEquals(1, sink.documents.size());
        var documents = sink.documents.getFirst();
        Map<String, Object> envelope = mapper.readValue(
                documents.envelopeBytes(), new TypeReference<>() { });
        Map<String, Object> bundle = mapper.readValue(
                documents.bundleBytes(), new TypeReference<>() { });
        assertEquals("COMPLETE", documents.kind());
        assertFalse(envelope.containsKey("predecessor_day"));
        assertEquals(1_440, ((List<?>) bundle.get("minutes")).size());
        assertEquals(1, producer.checkpoint().currentCompleteStreakCount());
        assertEquals(START.plusDays(1), producer.checkpoint().activeDay());
        assertEquals(1_443, checkpoint.writeCount);
    }

    @Test
    void lateDualAcknowledgementRejectsDayWithoutMarketOutcomeFields() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T23:50:00Z"));
        InMemoryCheckpoint checkpoint = new InMemoryCheckpoint();
        RecordingSink sink = new RecordingSink();
        var producer = producer(clock, checkpoint, sink);
        producer.initialize();
        clock.set(Instant.parse("2026-09-01T00:00:01Z"));
        producer.onRaw(acknowledgement("trades"));
        producer.onRaw(acknowledgement("books5"));
        producer.onRaw(bookMessage(START, 0, 1));

        Map<String, Object> envelope = mapper.readValue(
                sink.documents.getFirst().envelopeBytes(), new TypeReference<>() { });
        assertEquals("DUAL_CHANNEL_NOT_READY_AT_DAY_START", envelope.get("reason"));
        assertNull(envelope.get("sanitized_control_event"));
        assertEquals(0, producer.checkpoint().currentCompleteStreakCount());
    }

    @Test
    void unknownControlEventBlocksGenerationWithoutPublishingDisposition() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T23:50:00Z"));
        InMemoryCheckpoint checkpoint = new InMemoryCheckpoint();
        RecordingSink sink = new RecordingSink();
        var producer = producer(clock, checkpoint, sink);
        producer.initialize();

        assertThrows(IllegalStateException.class, () ->
                producer.onRaw("{\"event\":\"unsubscribe\",\"arg\":{"
                        + "\"channel\":\"trades\",\"instId\":\"BTC-USDT\"}}"));
        assertEquals(OkxMicrostructureDiscoveryRecoverySourceCli.ProducerState.BLOCKED,
                producer.state());
        assertEquals(List.of(), sink.documents);
    }

    @Test
    void activeDisconnectPublishesTransportRejectionBeforeReconnect() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T23:50:00Z"));
        InMemoryCheckpoint checkpoint = new InMemoryCheckpoint();
        RecordingSink sink = new RecordingSink();
        var producer = producer(clock, checkpoint, sink);
        producer.initialize();
        producer.onRaw(acknowledgement("trades"));
        producer.onRaw(acknowledgement("books5"));
        clock.set(Instant.parse("2026-09-01T00:00:01Z"));
        producer.onRaw(bookMessage(START, 0, 1));
        clock.set(Instant.parse("2026-09-01T00:10:00Z"));

        producer.onDisconnect(clock.instant());

        Map<String, Object> envelope = mapper.readValue(
                sink.documents.getFirst().envelopeBytes(), new TypeReference<>() { });
        assertEquals("TRANSPORT_DISCONNECT_UNPROVED_GAP", envelope.get("reason"));
        assertNull(envelope.get("sanitized_control_event"));
        assertEquals(START, producer.checkpoint().lastDispositionDay());
        producer.onReconnect(Instant.parse("2026-09-01T00:10:01Z"), false);
        assertTrue(producer.accepting());
    }

    private OkxMicrostructureDiscoveryRecoverySourceCli.Producer producer(
            MutableClock clock,
            InMemoryCheckpoint checkpoint,
            RecordingSink sink) {
        return new OkxMicrostructureDiscoveryRecoverySourceCli.Producer(
                new ObjectMapper().enable(
                        com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION),
                clock,
                binding(),
                new OkxMicrostructureDiscoveryRecoverySourceCli.HostContext(
                        "boot-a", Instant.parse("2026-08-30T00:00:00Z")),
                checkpoint,
                sink);
    }

    private OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding binding() {
        return new OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding(
                "okx-btcusdt-microstructure-discovery-v3r1-20260901-r3",
                "okx-btcusdt-microstructure-forward-v3r1-20260901-r3",
                "20260812T030000Z",
                "a".repeat(64),
                START,
                START.plusDays(41));
    }

    private byte[] bindingBytes() {
        String value = """
                {
                  "schema_version":"OKX_MICROSTRUCTURE_DISCOVERY_SOURCE_BINDING_V3R1",
                  "authorization":"RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
                  "generation_id":"okx-btcusdt-microstructure-discovery-v3r1-20260901-r3",
                  "diagnostic_id":"okx-btcusdt-microstructure-forward-v3r1-20260901-r3",
                  "recovery_contract_sha256":"6448b47a373dca743df6492593582660461382b639fdb77aa897ffa5a9f604bd",
                  "v3_day_schema_sha256":"205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709",
                  "v3_diagnostic_contract_sha256":"7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a",
                  "complete_envelope_schema_sha256":"a75aea4e247cdc134c441e5de33c2773a984c076eda8f1cdd85a0c3440260fb2",
                  "rejection_envelope_schema_sha256":"833e1cd3a0239987a8bc80caacb0abcecb5f00803816af09334c0674b5a04497",
                  "intake_state_schema_sha256":"12046ee0b3c814522bff6497f7028ae68da70884066e00c16a71d22e9ca5905d",
                  "producer_release_id":"20260812T030000Z",
                  "producer_manifest_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "start_day":"2026-09-01",
                  "end_day":"2026-10-12",
                  "calendar_day_budget":42,
                  "required_consecutive_complete_days":14,
                  "selection_rule":"FIRST_SOURCE_LIVENESS_DEFINED_FOURTEEN_DAY_STREAK"
                }
                """;
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String acknowledgement(String channel) {
        return "{\"event\":\"subscribe\",\"arg\":{\"channel\":\"" + channel
                + "\",\"instId\":\"BTC-USDT\"}}";
    }

    private static String bookMessage(LocalDate day, int minute, long sequence) {
        long timestamp = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                + minute * 60_000L + (sequence % 2 == 0 ? 2_000L : 500L);
        return "{\"arg\":{\"channel\":\"books5\",\"instId\":\"BTC-USDT\"},\"data\":[{"
                + "\"asks\":[[\"101\",\"1\",\"0\",\"1\"]],"
                + "\"bids\":[[\"99\",\"1\",\"0\",\"1\"]],"
                + "\"ts\":\"" + timestamp + "\",\"seqId\":" + sequence + "}]}";
    }

    private static String tradeMessage(LocalDate day, int minute, long sequence) {
        long timestamp = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                + minute * 60_000L + 1_000L;
        return "{\"arg\":{\"channel\":\"trades\",\"instId\":\"BTC-USDT\"},\"data\":[{"
                + "\"tradeId\":\"" + sequence + "\",\"px\":\"100\",\"sz\":\"1\","
                + "\"side\":\"buy\",\"ts\":\"" + timestamp
                + "\",\"count\":\"1\",\"source\":\"0\",\"seqId\":\""
                + sequence + "\"}]}";
    }

    private static final class RecordingSink
            implements OkxMicrostructureDiscoveryRecoveryDropV3R1.DropSink {
        private final List<OkxMicrostructureDiscoveryRecoveryDropV3R1.DispositionDocuments>
                documents = new ArrayList<>();

        @Override
        public void publish(
                OkxMicrostructureDiscoveryRecoveryDropV3R1.DispositionDocuments value) {
            documents.add(value);
        }
    }

    private static final class InMemoryCheckpoint
            implements OkxMicrostructureDiscoveryRecoverySourceCli.CheckpointAccess {
        private OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot state;
        private int writeCount;

        @Override
        public OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot load(
                OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding binding) {
            if (state != null && !state.binding().equals(binding)) {
                throw new IllegalStateException("binding mismatch");
            }
            return state;
        }

        @Override
        public void save(
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot expectedPrevious,
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot next) {
            if (!java.util.Objects.equals(expectedPrevious, state)) {
                throw new IllegalStateException("concurrent checkpoint change");
            }
            state = next;
            writeCount++;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
