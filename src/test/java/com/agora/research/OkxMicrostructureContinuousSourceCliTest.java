package com.agora.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxMicrostructureContinuousSourceCliTest {

    private static final LocalDate START_DAY = LocalDate.of(2026, 8, 7);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void productionBindingsAreFixedAndTransportIsInjectableWithoutNetwork() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        ExclusiveFakeSink sink = new ExclusiveFakeSink();
        ScriptedTransport transport = new ScriptedTransport(List.of(
                acknowledgement("trades"),
                acknowledgement("books5")));
        OkxMicrostructureContinuousSourceCli.Producer producer = producer(clock, transport, sink);

        producer.run();

        assertTrue(transport.ran);
        assertEquals(OkxMicrostructureContinuousSourceCli.ProducerState.ARMED_FOR_FUTURE_START,
                producer.state());
        assertEquals("wss://ws.okx.com:8443/ws/v5/public",
                OkxMicrostructureContinuousSourceCli.ENDPOINT);
        assertEquals("BTC-USDT", OkxMicrostructureContinuousSourceCli.INSTRUMENT);
        assertEquals(List.of("trades", "books5"), OkxMicrostructureContinuousSourceCli.CHANNELS);
        assertEquals(14, OkxMicrostructureContinuousSourceCli.REQUIRED_DAYS);
        assertEquals("agora-evidence-source", OkxMicrostructureContinuousSourceCli.PRODUCER_IDENTITY);
        assertEquals(
                "/etc/agora-research/okx-microstructure-continuous-source-v1.json",
                OkxMicrostructureContinuousSourceCli.FIXED_BINDING_PATH.toString().replace('\\', '/'));
        assertTrue(OkxMicrostructureContinuousSourceCli.PRIVATE_STAGING_ROOT.toString()
                .contains("microstructure"));
        assertTrue(OkxMicrostructureContinuousSourceCli.MICROSTRUCTURE_DROP_ROOT.toString()
                .contains("microstructure"));
        assertFalse(OkxMicrostructureContinuousSourceCli.MICROSTRUCTURE_DROP_ROOT.toString()
                .toLowerCase().contains("candle"));
        assertTrue(sink.documents.isEmpty());
        OkxMicrostructureContinuousSourceCli.requireNoArguments(new String[0]);
        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureContinuousSourceCli.requireNoArguments(
                        new String[]{"--forward-start-day", "2099-01-01"}));
    }

    @Test
    void bindingRejectsExpiredAndNonfutureStartDays() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        Map<String, Object> today = validBindingMap(LocalDate.of(2026, 8, 6));
        Map<String, Object> expired = validBindingMap(LocalDate.of(2026, 8, 5));

        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureContinuousSourceCli.SourceBinding.parse(
                        mapper.writeValueAsBytes(today), clock));
        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureContinuousSourceCli.SourceBinding.parse(
                        mapper.writeValueAsBytes(expired), clock));
    }

    @Test
    void bindingRejectsWrongFrozenHashesMissingExtraAndNonlowercaseFields() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        for (String field : List.of(
                "source_contract_sha256",
                "day_schema_sha256",
                "diagnostic_contract_sha256")) {
            Map<String, Object> wrongHash = validBindingMap(START_DAY);
            wrongHash.put(field, "0".repeat(64));
            assertThrows(IllegalArgumentException.class, () ->
                    OkxMicrostructureContinuousSourceCli.SourceBinding.parse(
                            mapper.writeValueAsBytes(wrongHash), clock));
        }
        Map<String, Object> extraKey = validBindingMap(START_DAY);
        extraKey.put("endpoint", "wss://example.invalid");
        Map<String, Object> missingKey = validBindingMap(START_DAY);
        missingKey.remove("diagnostic_id");
        Map<String, Object> uppercaseManifest = validBindingMap(START_DAY);
        uppercaseManifest.put("producer_manifest_sha256", "A".repeat(64));

        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureContinuousSourceCli.SourceBinding.parse(
                        mapper.writeValueAsBytes(extraKey), clock));
        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureContinuousSourceCli.SourceBinding.parse(
                        mapper.writeValueAsBytes(missingKey), clock));
        assertThrows(IllegalArgumentException.class, () ->
                OkxMicrostructureContinuousSourceCli.SourceBinding.parse(
                        mapper.writeValueAsBytes(uppercaseManifest), clock));
    }

    @Test
    void bindingSuppliesActualReleaseIdentityWithoutSelectingNetworkOrPaths() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        Map<String, Object> value = validBindingMap(START_DAY);

        OkxMicrostructureContinuousSourceCli.SourceBinding binding =
                OkxMicrostructureContinuousSourceCli.SourceBinding.parse(
                        mapper.writeValueAsBytes(value), clock);

        assertEquals(START_DAY, binding.forwardStartDay());
        assertEquals(14, binding.requiredCompleteUtcDays());
        assertEquals("producer-release-fixture", binding.producerReleaseId());
        assertEquals("a".repeat(64), binding.producerManifestSha256());
        assertFalse(value.containsKey("endpoint"));
        assertFalse(value.containsKey("instrument"));
        assertFalse(value.containsKey("channels"));
        assertFalse(value.containsKey("producer_identity"));
        assertFalse(value.containsKey("staging_root"));
        assertFalse(value.containsKey("drop_root"));
    }

    @Test
    void v2ProjectionCanonicalHashesUtcRolloverAndNextDayRetentionAreDeterministic() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        ExclusiveFakeSink sink = new ExclusiveFakeSink();
        OkxMicrostructureContinuousSourceCli.Producer producer = producer(clock, listener -> { }, sink);
        acknowledgeBoth(producer);
        completeDay(producer, START_DAY);

        clock.set(START_DAY.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(1));
        producer.onRaw(tradeMessage(
                clock.instant().toEpochMilli(), "200", "1", "buy", 2_000_000L, 2_000_000L));

        assertEquals(OkxMicrostructureContinuousSourceCli.ProducerState.CAPTURING, producer.state());
        assertEquals(START_DAY.plusDays(1), producer.activeDay());
        assertEquals(1, producer.activeMinuteCount());
        assertEquals(1, producer.published().size());

        OkxMicrostructureCanonicalDrop.DropDocuments documents = producer.published().getFirst();
        assertEquals(documents, sink.documents.get(START_DAY));
        assertEquals(documents.bundleBytes().length, documents.bundleSizeBytes());
        assertEquals(documents.bundleSha256(),
                OkxMicrostructureCanonicalDrop.sha256(documents.bundleBytes()));
        assertEquals(documents.envelopeSha256(),
                OkxMicrostructureCanonicalDrop.sha256(documents.envelopeBytes()));
        assertNotEquals('\n', documents.bundleBytes()[documents.bundleBytes().length - 1]);
        assertNotEquals('\n', documents.envelopeBytes()[documents.envelopeBytes().length - 1]);

        Map<String, Object> bundle = mapper.readValue(
                documents.bundleBytes(), new TypeReference<>() { });
        Map<String, Object> envelope = mapper.readValue(
                documents.envelopeBytes(), new TypeReference<>() { });
        assertArrayEquals(documents.bundleBytes(), OkxMicrostructureCanonicalDrop.canonicalBytes(bundle));
        assertArrayEquals(documents.envelopeBytes(), OkxMicrostructureCanonicalDrop.canonicalBytes(envelope));

        Map<String, Object> payload = new HashMap<>(bundle);
        Map<String, Object> bundleSeal = castMap(payload.remove("seal"));
        assertEquals(OkxMicrostructureCanonicalDrop.sha256(
                        OkxMicrostructureCanonicalDrop.canonicalBytes(payload)),
                bundleSeal.get("payload_sha256"));
        Map<String, Object> envelopePayload = new HashMap<>(envelope);
        Map<String, Object> envelopeSeal = castMap(envelopePayload.remove("envelope_seal"));
        assertEquals(OkxMicrostructureCanonicalDrop.sha256(
                        OkxMicrostructureCanonicalDrop.canonicalBytes(envelopePayload)),
                envelopeSeal.get("payload_sha256"));
        assertEquals(documents.bundleBytes().length,
                ((Number) envelope.get("bundle_size_bytes")).intValue());
        assertEquals(documents.bundleSha256(), envelope.get("bundle_sha256"));
        assertNull(envelope.get("predecessor_day"));
        assertNull(envelope.get("predecessor_bundle_sha256"));

        List<Map<String, Object>> minutes = castList(bundle.get("minutes"));
        assertEquals(1_440, minutes.size());
        Map<String, Object> first = minutes.getFirst();
        assertEquals("305", first.get("buy_quote_notional"));
        assertEquals("110", first.get("sell_quote_notional"));
        assertEquals("415", first.get("total_quote_notional"));
        assertEquals("195", first.get("net_taker_quote_notional"));
        assertEquals("100", first.get("trade_open_price"));
        assertEquals("110", first.get("trade_high_price"));
        assertEquals("100", first.get("trade_low_price"));
        assertEquals("105", first.get("trade_close_price"));
        assertEquals("103.75", first.get("trade_vwap_price"));
        assertEquals(START_DAY + "T00:00:01Z", first.get("first_trade_at"));
        assertEquals(START_DAY + "T00:00:03Z", first.get("last_trade_at"));
        assertEquals("100", first.get("mid_price_start"));
        assertEquals("102", first.get("mid_price_high"));
        assertEquals("100", first.get("mid_price_low"));
        assertEquals("102", first.get("mid_price_end"));
        assertEquals("105", first.get("bid_replenishment_quote_proxy"));
        assertEquals(START_DAY + "T00:00:04Z", first.get("first_book_at"));
        assertEquals(START_DAY + "T00:00:05Z", first.get("last_book_at"));

        assertThrows(Exception.class, () -> sink.publish(documents));
    }

    @Test
    void losslessReconnectClearsStaleAcknowledgementsBeforeContinuing() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        OkxMicrostructureContinuousSourceCli.Producer producer =
                producer(clock, listener -> { }, new ExclusiveFakeSink());
        acknowledgeBoth(producer);
        sendOneCompleteMinute(producer, START_DAY, 1);
        Instant boundary = Instant.parse("2026-08-07T00:00:30Z");

        producer.onDisconnect(boundary);
        assertEquals(0, producer.acknowledgementCount());
        producer.onReconnect(boundary, true);

        assertEquals(OkxMicrostructureContinuousSourceCli.ProducerState.CAPTURING, producer.state());
        assertEquals(0, producer.acknowledgementCount());
        acknowledgeBoth(producer);
        assertEquals(2, producer.acknowledgementCount());
    }

    @Test
    void preStartReconnectCannotReuseStaleAcknowledgements() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        OkxMicrostructureContinuousSourceCli.Producer producer =
                producer(clock, listener -> { }, new ExclusiveFakeSink());
        acknowledgeBoth(producer);
        assertEquals(2, producer.acknowledgementCount());

        producer.onDisconnect(Instant.parse("2026-08-06T10:00:01Z"));
        producer.onReconnect(Instant.parse("2026-08-06T10:00:02Z"), false);

        assertEquals(OkxMicrostructureContinuousSourceCli.ProducerState.ARMED_FOR_FUTURE_START,
                producer.state());
        assertEquals(0, producer.acknowledgementCount());
    }

    @Test
    void elapsedReconnectIntervalFailsClosed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        OkxMicrostructureContinuousSourceCli.Producer producer =
                producer(clock, listener -> { }, new ExclusiveFakeSink());
        acknowledgeBoth(producer);
        sendOneCompleteMinute(producer, START_DAY, 1);

        producer.onDisconnect(Instant.parse("2026-08-07T00:00:30Z"));
        producer.onReconnect(Instant.parse("2026-08-07T00:00:31Z"), false);

        assertEquals(OkxMicrostructureContinuousSourceCli.ProducerState.BLOCKED, producer.state());
        assertEquals("UNPROVED_RECONNECT_INTERVAL", producer.blockedReason());
    }

    @Test
    void missingTradesFailsClosedAtRollover() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        OkxMicrostructureContinuousSourceCli.Producer producer =
                producer(clock, listener -> { }, new ExclusiveFakeSink());
        acknowledgeBoth(producer);
        long start = START_DAY.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        producer.onRaw(bookMessage(start + 2_000, 1));
        clock.set(START_DAY.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(1));

        assertThrows(IllegalStateException.class, () -> producer.onRaw(
                tradeMessage(clock.instant().toEpochMilli(), "100", "1", "buy", 1, 1)));
        assertEquals(OkxMicrostructureContinuousSourceCli.ProducerState.BLOCKED, producer.state());
    }

    @Test
    void missingBooks5FailsClosedAtRollover() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        OkxMicrostructureContinuousSourceCli.Producer producer =
                producer(clock, listener -> { }, new ExclusiveFakeSink());
        acknowledgeBoth(producer);
        long start = START_DAY.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        producer.onRaw(tradeMessage(start + 1_000, "100", "1", "buy", 1, 1));
        clock.set(START_DAY.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(1));

        assertThrows(IllegalStateException.class, () -> producer.onRaw(
                tradeMessage(clock.instant().toEpochMilli(), "100", "1", "buy", 2, 2)));
        assertEquals(OkxMicrostructureContinuousSourceCli.ProducerState.BLOCKED, producer.state());
    }

    @Test
    void malformedAndCrossedBookMessagesFailClosed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        OkxMicrostructureContinuousSourceCli.Producer malformed =
                producer(clock, listener -> { }, new ExclusiveFakeSink());
        assertThrows(IllegalStateException.class, () -> malformed.onRaw("not-json"));
        assertEquals("MALFORMED_MESSAGE", malformed.blockedReason());

        OkxMicrostructureContinuousSourceCli.Producer crossed =
                producer(clock, listener -> { }, new ExclusiveFakeSink());
        acknowledgeBoth(crossed);
        long timestamp = START_DAY.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + 1_000;
        String raw = "{\"arg\":{\"channel\":\"books5\",\"instId\":\"BTC-USDT\"},\"data\":["
                + "{\"asks\":[[\"99\",\"1\",\"0\",\"1\"]],"
                + "\"bids\":[[\"100\",\"1\",\"0\",\"1\"]],"
                + "\"ts\":\"" + timestamp + "\",\"seqId\":\"1\"}]}";
        assertThrows(IllegalStateException.class, () -> crossed.onRaw(raw));
        assertEquals("CROSSED_BOOK", crossed.blockedReason());
    }

    @Test
    void activeWindowRestartFailsClosedWithoutCheckpointRecovery() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        OkxMicrostructureContinuousSourceCli.Producer producer =
                producer(clock, listener -> { }, new ExclusiveFakeSink());
        acknowledgeBoth(producer);
        sendOneCompleteMinute(producer, START_DAY, 1);

        producer.onRestartDetected();

        assertEquals(OkxMicrostructureContinuousSourceCli.ProducerState.BLOCKED, producer.state());
        assertEquals("ACTIVE_WINDOW_PROCESS_RESTART", producer.blockedReason());
    }

    @Test
    void lateInitialProcessStartFailsClosedInsteadOfShiftingTheWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T10:00:00Z"));
        OkxMicrostructureContinuousSourceCli.Producer producer =
                producer(clock, listener -> { }, new ExclusiveFakeSink());
        clock.set(START_DAY.atStartOfDay().toInstant(ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, producer::run);
        assertEquals(OkxMicrostructureContinuousSourceCli.ProducerState.BLOCKED, producer.state());
        assertEquals("BINDING_START_DAY_NOT_STRICTLY_FUTURE_AT_PROCESS_START",
                producer.blockedReason());
    }

    private OkxMicrostructureContinuousSourceCli.Producer producer(
            MutableClock clock,
            OkxMicrostructureContinuousSourceCli.WebSocketTransport transport,
            ExclusiveFakeSink sink) {
        OkxMicrostructureContinuousSourceCli.SourceBinding binding;
        try {
            binding = OkxMicrostructureContinuousSourceCli.SourceBinding.parse(
                    mapper.writeValueAsBytes(validBindingMap(START_DAY)), clock);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return new OkxMicrostructureContinuousSourceCli.Producer(
                mapper, clock, transport, sink, binding);
    }

    private static Map<String, Object> validBindingMap(LocalDate startDay) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", "1");
        value.put("authorization", "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE");
        value.put("forward_start_day", startDay.toString());
        value.put("required_complete_utc_days", 14);
        value.put("diagnostic_id", "okx-microstructure-forward-diagnostic-v2");
        value.put("source_contract_sha256",
                OkxMicrostructureCanonicalDrop.SOURCE_CONTRACT_SHA256);
        value.put("day_schema_sha256", OkxMicrostructureContinuousSourceCli.DAY_SCHEMA_SHA256);
        value.put("diagnostic_contract_sha256",
                OkxMicrostructureContinuousSourceCli.DIAGNOSTIC_CONTRACT_SHA256);
        value.put("producer_release_id", "producer-release-fixture");
        value.put("producer_manifest_sha256", "a".repeat(64));
        return value;
    }

    private static void acknowledgeBoth(OkxMicrostructureContinuousSourceCli.Producer producer) {
        producer.onRaw(acknowledgement("trades"));
        producer.onRaw(acknowledgement("books5"));
    }

    private static String acknowledgement(String channel) {
        return "{\"event\":\"subscribe\",\"arg\":{\"channel\":\"" + channel
                + "\",\"instId\":\"BTC-USDT\"}}";
    }

    private static void completeDay(
            OkxMicrostructureContinuousSourceCli.Producer producer,
            LocalDate day) {
        long dayStart = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        for (int minute = 0; minute < 1_440; minute++) {
            long start = dayStart + minute * 60_000L;
            if (minute == 0) {
                producer.onRaw("{\"arg\":{\"channel\":\"trades\",\"instId\":\"BTC-USDT\"},\"data\":["
                        + tradeRecord(start + 1_000, "100", "2", "buy", 1, 1) + ","
                        + tradeRecord(start + 2_000, "110", "1", "sell", 2, 2) + ","
                        + tradeRecord(start + 3_000, "105", "1", "buy", 3, 3) + "]}");
                producer.onRaw("{\"arg\":{\"channel\":\"books5\",\"instId\":\"BTC-USDT\"},\"data\":["
                        + bookRecord(start + 4_000, "99", "2", "101", "1", 1) + ","
                        + bookRecord(start + 5_000, "101", "3", "103", "1", 2) + "]}");
            } else {
                long sequence = minute + 10L;
                producer.onRaw(tradeMessage(start + 1_000, "100", "1", "buy", sequence, sequence));
                producer.onRaw(bookMessage(start + 2_000, sequence));
            }
        }
    }

    private static void sendOneCompleteMinute(
            OkxMicrostructureContinuousSourceCli.Producer producer,
            LocalDate day,
            long sequence) {
        long start = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        producer.onRaw(tradeMessage(start + 1_000, "100", "1", "buy", sequence, sequence));
        producer.onRaw(bookMessage(start + 2_000, sequence));
    }

    private static String tradeMessage(
            long timestamp,
            String price,
            String size,
            String side,
            long sequence,
            long tradeId) {
        return "{\"arg\":{\"channel\":\"trades\",\"instId\":\"BTC-USDT\"},\"data\":["
                + tradeRecord(timestamp, price, size, side, sequence, tradeId) + "]}";
    }

    private static String tradeRecord(
            long timestamp,
            String price,
            String size,
            String side,
            long sequence,
            long tradeId) {
        return "{\"tradeId\":\"" + tradeId + "\",\"px\":\"" + price
                + "\",\"sz\":\"" + size + "\",\"side\":\"" + side
                + "\",\"ts\":\"" + timestamp + "\",\"count\":\"1\",\"source\":\"0\","
                + "\"seqId\":\"" + sequence + "\"}";
    }

    private static String bookMessage(long timestamp, long sequence) {
        return "{\"arg\":{\"channel\":\"books5\",\"instId\":\"BTC-USDT\"},\"data\":["
                + bookRecord(timestamp, "99", "2", "101", "1", sequence) + "]}";
    }

    private static String bookRecord(
            long timestamp,
            String bid,
            String bidSize,
            String ask,
            String askSize,
            long sequence) {
        return "{\"asks\":[[\"" + ask + "\",\"" + askSize + "\",\"0\",\"1\"]],"
                + "\"bids\":[[\"" + bid + "\",\"" + bidSize + "\",\"0\",\"1\"]],"
                + "\"ts\":\"" + timestamp + "\",\"seqId\":\"" + sequence + "\"}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static final class ScriptedTransport
            implements OkxMicrostructureContinuousSourceCli.WebSocketTransport {
        private final List<String> messages;
        private boolean ran;

        private ScriptedTransport(List<String> messages) {
            this.messages = messages;
        }

        @Override
        public void run(OkxMicrostructureContinuousSourceCli.TransportListener listener) {
            ran = true;
            for (String message : messages) {
                listener.onRaw(message);
            }
        }
    }

    private static final class ExclusiveFakeSink
            implements OkxMicrostructureCanonicalDrop.DropSink {
        private final Map<LocalDate, OkxMicrostructureCanonicalDrop.DropDocuments> documents =
                new LinkedHashMap<>();

        @Override
        public void publish(OkxMicrostructureCanonicalDrop.DropDocuments value) {
            assertEquals(value.bundleSizeBytes(), value.bundleBytes().length);
            assertEquals(value.bundleSha256(),
                    OkxMicrostructureCanonicalDrop.sha256(value.bundleBytes()));
            assertEquals(value.envelopeSha256(),
                    OkxMicrostructureCanonicalDrop.sha256(value.envelopeBytes()));
            if (documents.putIfAbsent(value.day(), value) != null) {
                throw new IllegalStateException("OVERWRITE_REJECT");
            }
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
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
