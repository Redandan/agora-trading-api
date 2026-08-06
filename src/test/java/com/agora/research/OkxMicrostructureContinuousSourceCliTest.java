package com.agora.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                "/etc/agora-research/okx-microstructure-continuous-source-v3.json",
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
    void v3ProjectionCanonicalHashesUtcRolloverAndNextDayRetentionAreDeterministic() throws Exception {
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
        assertEquals("OKX_MICROSTRUCTURE_FORWARD_DAY_V3", bundle.get("schema_version"));
        assertEquals(OkxMicrostructureCanonicalDrop.V3_DROP_ENVELOPE_SCHEMA_VERSION,
                envelope.get("schema_version"));
        assertEquals(OkxMicrostructureCanonicalDrop.V3_SOURCE_CONTRACT_SHA256,
                envelope.get("source_contract_sha256"));
        Map<String, Object> source = castMap(bundle.get("source"));
        Map<String, Object> integrity = castMap(bundle.get("integrity"));
        assertEquals(Set.of(
                "venue",
                "instrument",
                "channels",
                "mode",
                "historical_backfill",
                "raw_messages_persisted",
                "aggregation_timezone",
                "midline_formula",
                "midline_reference",
                "unreferenced_trade_disposition"), source.keySet());
        assertEquals(Set.of(
                "status",
                "anomaly_count",
                "raw_message_count",
                "arrival_chain_sha256",
                "midline_unreferenced_trade_count",
                "crossed_book_count"), integrity.keySet());
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
        assertEquals(Set.of(
                "minute",
                "trade_record_count",
                "match_count",
                "midline_reference_count",
                "buy_quote_notional",
                "sell_quote_notional",
                "total_quote_notional",
                "net_taker_quote_notional",
                "above_mid_buy_quote_notional",
                "below_mid_sell_quote_notional",
                "midline_other_quote_notional",
                "trade_open_price",
                "trade_high_price",
                "trade_low_price",
                "trade_close_price",
                "trade_vwap_price",
                "first_trade_at",
                "last_trade_at",
                "book_sample_count",
                "average_top5_bid_quote_depth",
                "average_top5_ask_quote_depth",
                "average_book_imbalance",
                "average_spread_bps",
                "bid_replenishment_quote_proxy",
                "mid_price_start",
                "mid_price_high",
                "mid_price_low",
                "mid_price_end",
                "first_book_at",
                "last_book_at"), first.keySet());
        assertEquals("305", first.get("buy_quote_notional"));
        assertEquals("209", first.get("sell_quote_notional"));
        assertEquals("514", first.get("total_quote_notional"));
        assertEquals("96", first.get("net_taker_quote_notional"));
        assertEquals(4, ((Number) first.get("midline_reference_count")).intValue());
        assertEquals("105", first.get("above_mid_buy_quote_notional"));
        assertEquals("99", first.get("below_mid_sell_quote_notional"));
        assertEquals("310", first.get("midline_other_quote_notional"));
        assertEquals("100", first.get("trade_open_price"));
        assertEquals("110", first.get("trade_high_price"));
        assertEquals("99", first.get("trade_low_price"));
        assertEquals("99", first.get("trade_close_price"));
        assertEquals("102.8", first.get("trade_vwap_price"));
        assertEquals(START_DAY + "T00:00:01Z", first.get("first_trade_at"));
        assertEquals(START_DAY + "T00:00:03.500Z", first.get("last_trade_at"));
        assertEquals("100", first.get("mid_price_start"));
        assertEquals("102", first.get("mid_price_high"));
        assertEquals("100", first.get("mid_price_low"));
        assertEquals("102", first.get("mid_price_end"));
        assertEquals("105", first.get("bid_replenishment_quote_proxy"));
        assertEquals(START_DAY + "T00:00:00.500Z", first.get("first_book_at"));
        assertEquals(START_DAY + "T00:00:04Z", first.get("last_book_at"));

        assertThrows(Exception.class, () -> sink.publish(documents));
    }

    @Test
    void v3SourceContractAndFrozenBindingsMatchExactRepositoryBytes() throws Exception {
        Path contractPath = Path.of(
                "research_pipeline/okx-microstructure-continuous-source-contract.v3.json");
        Path v3EnvelopeSchemaPath = Path.of(
                "research_pipeline/okx-microstructure-drop-envelope.v3.schema.json");
        Path v1EnvelopeSchemaPath = Path.of(
                "research_pipeline/okx-microstructure-drop-envelope.v1.schema.json");
        Path v3IntakeSchemaPath = Path.of(
                "research_pipeline/okx-microstructure-intake-state.v3.schema.json");
        Path v1IntakeSchemaPath = Path.of(
                "research_pipeline/okx-microstructure-intake-state.v1.schema.json");
        byte[] contractBytes = Files.readAllBytes(contractPath);
        byte[] v3EnvelopeSchemaBytes = Files.readAllBytes(v3EnvelopeSchemaPath);
        byte[] v3IntakeSchemaBytes = Files.readAllBytes(v3IntakeSchemaPath);
        byte[] v1IntakeSchemaBytes = Files.readAllBytes(v1IntakeSchemaPath);
        Map<String, Object> contract = mapper.readValue(contractBytes, new TypeReference<>() { });
        Map<String, Object> v3EnvelopeSchema = mapper.readValue(
                v3EnvelopeSchemaBytes, new TypeReference<>() { });
        Map<String, Object> v1EnvelopeSchema = mapper.readValue(
                Files.readAllBytes(v1EnvelopeSchemaPath), new TypeReference<>() { });
        Map<String, Object> v3IntakeSchema = mapper.readValue(
                v3IntakeSchemaBytes, new TypeReference<>() { });
        Map<String, Object> v1IntakeSchema = mapper.readValue(
                v1IntakeSchemaBytes, new TypeReference<>() { });
        Map<String, Object> eventTimeJoin = castMap(contract.get("event_time_join"));
        Map<String, Object> bindings = castMap(contract.get("bindings"));
        Map<String, Object> v3EnvelopeProperties = castMap(v3EnvelopeSchema.get("properties"));
        Map<String, Object> v3IntakeProperties = castMap(v3IntakeSchema.get("properties"));
        Map<String, Object> v3ReadinessProperties = castMap(castMap(
                v3IntakeProperties.get("readiness")).get("properties"));

        assertEquals("8a581cc03eb9381af4bfecddb8f40c7d23759ce239647447bc37351e4f293422",
                OkxMicrostructureCanonicalDrop.V3_SOURCE_CONTRACT_SHA256);
        assertEquals(OkxMicrostructureCanonicalDrop.V3_SOURCE_CONTRACT_SHA256,
                OkxMicrostructureCanonicalDrop.sha256(contractBytes));
        assertEquals("ad6e23797240a9e4a86affff40e801d7d659a8a408ffad65270a42dec2b46418",
                OkxMicrostructureCanonicalDrop.V3_DROP_ENVELOPE_SCHEMA_SHA256);
        assertEquals(OkxMicrostructureCanonicalDrop.V3_DROP_ENVELOPE_SCHEMA_SHA256,
                OkxMicrostructureCanonicalDrop.sha256(v3EnvelopeSchemaBytes));
        assertEquals("935da25d8f5e66bb4ec13625ff2e8eb7480e503f8c4d580abd41514ee90aa7fc",
                OkxMicrostructureCanonicalDrop.sha256(v3IntakeSchemaBytes));
        assertEquals("2a8e42f8e0358dcc84d63a3472860ed956f739990c7c9ecba94764a7be2b1995",
                OkxMicrostructureCanonicalDrop.sha256(v1IntakeSchemaBytes));
        assertEquals("OKX_MICROSTRUCTURE_CONTINUOUS_SOURCE_V3", contract.get("contract_id"));
        assertEquals("LATEST_BOOKS5_AT_OR_BEFORE_TRADE", eventTimeJoin.get("book_reference"));
        assertEquals(10_000, ((Number) eventTimeJoin.get(
                "maximum_unresolved_trade_records")).intValue());
        assertEquals(OkxMicrostructureContinuousSourceCli.DAY_SCHEMA_SHA256,
                bindings.get("day_schema_sha256"));
        assertEquals(OkxMicrostructureContinuousSourceCli.DIAGNOSTIC_CONTRACT_SHA256,
                bindings.get("diagnostic_contract_sha256"));
        assertEquals(OkxMicrostructureCanonicalDrop.V3_DROP_ENVELOPE_SCHEMA_ID,
                v3EnvelopeSchema.get("$id"));
        assertEquals(v3EnvelopeSchema.get("$id"), bindings.get("drop_envelope_schema_id"));
        assertEquals("https://agora.local/research/okx-microstructure-intake-state.v3.schema.json",
                v3IntakeSchema.get("$id"));
        assertEquals(v3IntakeSchema.get("$id"), bindings.get("intake_state_schema_id"));
        assertEquals(OkxMicrostructureCanonicalDrop.V3_DROP_ENVELOPE_SCHEMA_VERSION,
                castMap(v3EnvelopeProperties.get("schema_version")).get("const"));
        assertEquals(OkxMicrostructureCanonicalDrop.V3_SOURCE_CONTRACT_SHA256,
                castMap(v3EnvelopeProperties.get("source_contract_sha256")).get("const"));
        assertEquals("OKX_MICROSTRUCTURE_INTAKE_STATE_V3",
                castMap(v3IntakeProperties.get("schema_version")).get("const"));
        assertEquals("SERVER_CANONICAL_MICROSTRUCTURE_V3_INTAKE",
                castMap(v3IntakeProperties.get("state_type")).get("const"));
        assertEquals(OkxMicrostructureCanonicalDrop.V3_SOURCE_CONTRACT_SHA256,
                castMap(v3IntakeProperties.get("source_contract_sha256")).get("const"));
        assertEquals(OkxMicrostructureCanonicalDrop.V3_DROP_ENVELOPE_SCHEMA_SHA256,
                castMap(v3IntakeProperties.get(
                        "drop_envelope_schema_sha256")).get("const"));
        assertEquals(OkxMicrostructureContinuousSourceCli.DAY_SCHEMA_SHA256,
                castMap(v3IntakeProperties.get("day_schema_sha256")).get("const"));
        assertEquals(OkxMicrostructureContinuousSourceCli.DIAGNOSTIC_CONTRACT_SHA256,
                castMap(v3IntakeProperties.get(
                        "diagnostic_contract_sha256")).get("const"));
        assertEquals(List.of(
                        "NOT_READY",
                        "FROZEN_V3_DISCOVERY_ANALYSIS_ONLY",
                        "INTEGRITY_BLOCKED"),
                castMap(v3ReadinessProperties.get("disposition")).get("enum"));

        Map<String, Object> normalizedV3Schema = mapper.readValue(
                v3EnvelopeSchemaBytes, new TypeReference<>() { });
        normalizedV3Schema.put("$id", v1EnvelopeSchema.get("$id"));
        normalizedV3Schema.put("title", v1EnvelopeSchema.get("title"));
        Map<String, Object> normalizedProperties = castMap(
                normalizedV3Schema.get("properties"));
        castMap(normalizedProperties.get("schema_version")).put(
                "const", OkxMicrostructureCanonicalDrop.DROP_ENVELOPE_SCHEMA_VERSION);
        castMap(normalizedProperties.get("source_contract_sha256")).put(
                "const", OkxMicrostructureCanonicalDrop.SOURCE_CONTRACT_SHA256);
        assertEquals(v1EnvelopeSchema, normalizedV3Schema);

        Map<String, Object> normalizedV3IntakeSchema = mapper.readValue(
                v3IntakeSchemaBytes, new TypeReference<>() { });
        normalizedV3IntakeSchema.put("$id", v1IntakeSchema.get("$id"));
        normalizedV3IntakeSchema.put("title", v1IntakeSchema.get("title"));
        Map<String, Object> normalizedIntakeProperties = castMap(
                normalizedV3IntakeSchema.get("properties"));
        Map<String, Object> v1IntakeProperties = castMap(v1IntakeSchema.get("properties"));
        for (String field : List.of(
                "schema_version",
                "state_type",
                "source_contract_sha256",
                "drop_envelope_schema_sha256",
                "day_schema_sha256",
                "diagnostic_contract_sha256")) {
            castMap(normalizedIntakeProperties.get(field)).put(
                    "const", castMap(v1IntakeProperties.get(field)).get("const"));
        }
        Map<String, Object> normalizedReadinessProperties = castMap(castMap(
                normalizedIntakeProperties.get("readiness")).get("properties"));
        Map<String, Object> v1ReadinessProperties = castMap(castMap(
                v1IntakeProperties.get("readiness")).get("properties"));
        castMap(normalizedReadinessProperties.get("disposition")).put(
                "enum", castMap(v1ReadinessProperties.get("disposition")).get("enum"));
        assertEquals(v1IntakeSchema, normalizedV3IntakeSchema);
    }

    @Test
    void eventTimeJoinUsesLastEqualTimestampBookAndNeverTheLaterBook() {
        OkxMicrostructureCollector collector = collectorWithAcks();
        long base = START_DAY.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        collector.acceptRaw(bookMessage(base + 1_000, "99", "1", "101", "1", 1));
        collector.acceptRaw(tradeMessage(base + 2_000, "103", "1", "buy", 1, 1));
        collector.acceptRaw(tradeMessage(base + 2_000, "101.5", "1", "buy", 2, 2));
        collector.acceptRaw(bookMessage(base + 2_000, "100", "1", "102", "1", 2));
        collector.acceptRaw(bookMessage(base + 2_000, "101", "1", "103", "1", 3));

        assertEquals(2, collector.unresolvedV3TradeCount());
        collector.acceptRaw(bookMessage(base + 3_000, "199", "1", "201", "1", 4));

        Map<String, Object> minute = collector.v3MinuteOutput(
                Instant.ofEpochMilli(base));
        assertEquals(0, collector.unresolvedV3TradeCount());
        assertEquals(0L, collector.midlineUnreferencedTradeCount());
        assertEquals(2L, minute.get("midline_reference_count"));
        assertEquals("103", minute.get("above_mid_buy_quote_notional"));
        assertEquals("0", minute.get("below_mid_sell_quote_notional"));
        assertEquals("101.5", minute.get("midline_other_quote_notional"));
    }

    @Test
    void v3BucketsPutEqualMidAndOppositeSidesInOtherAndReconcileExactly() {
        OkxMicrostructureCollector collector = collectorWithAcks();
        long base = START_DAY.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        collector.acceptRaw(bookMessage(base + 1_000, 1));
        collector.acceptRaw("{\"arg\":{\"channel\":\"trades\",\"instId\":\"BTC-USDT\"},\"data\":["
                + tradeRecord(base + 2_000, "101", "1", "buy", 1, 1) + ","
                + tradeRecord(base + 2_000, "99", "1", "sell", 2, 2) + ","
                + tradeRecord(base + 2_000, "100", "1", "buy", 3, 3) + ","
                + tradeRecord(base + 2_000, "101", "1", "sell", 4, 4) + ","
                + tradeRecord(base + 2_000, "99", "1", "buy", 5, 5) + "]}");
        collector.acceptRaw(bookMessage(base + 3_000, 2));

        Map<String, Object> minute = collector.v3MinuteOutput(Instant.ofEpochMilli(base));
        assertEquals(5L, minute.get("trade_record_count"));
        assertEquals(5L, minute.get("midline_reference_count"));
        assertEquals("500", minute.get("total_quote_notional"));
        assertEquals("101", minute.get("above_mid_buy_quote_notional"));
        assertEquals("99", minute.get("below_mid_sell_quote_notional"));
        assertEquals("300", minute.get("midline_other_quote_notional"));
    }

    @Test
    void preFirstBookTradeAndPerStreamRegressionsRemainFailClosed() {
        long base = START_DAY.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        OkxMicrostructureCollector unreferenced = collectorWithAcks();
        unreferenced.acceptRaw(tradeMessage(base + 1_000, "100", "1", "buy", 1, 1));
        unreferenced.acceptRaw(bookMessage(base + 2_000, 1));
        assertEquals(1L, unreferenced.midlineUnreferencedTradeCount());
        assertEquals("MIDLINE_UNREFERENCED_TRADE", unreferenced.v3IntegrityFailureReason());

        OkxMicrostructureCollector regressed = collectorWithAcks();
        regressed.acceptRaw(bookMessage(base + 2_000, 2));
        regressed.acceptRaw(bookMessage(base + 1_000, 1));
        assertEquals(2L, regressed.anomalyCount());
    }

    @Test
    void unresolvedTradeBufferAcceptsExactly10000ThenBlocksWithoutTruncation() {
        OkxMicrostructureCollector collector = collectorWithAcks();
        long base = START_DAY.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        StringBuilder raw = new StringBuilder(
                "{\"arg\":{\"channel\":\"trades\",\"instId\":\"BTC-USDT\"},\"data\":[");
        for (int index = 0; index < OkxMicrostructureCollector.MAX_UNRESOLVED_TRADES; index++) {
            if (index != 0) {
                raw.append(',');
            }
            raw.append(tradeRecord(
                    base + index + 1L,
                    "100",
                    "1",
                    "buy",
                    index + 1L,
                    index + 1L));
        }
        raw.append("]}");
        collector.acceptRaw(raw.toString());

        assertEquals(10_000, collector.unresolvedV3TradeCount());
        assertFalse(collector.unresolvedV3TradeOverflowed());
        collector.acceptRaw(tradeMessage(
                base + 10_001L, "100", "1", "buy", 10_001L, 10_001L));

        assertEquals(10_000, collector.unresolvedV3TradeCount());
        assertTrue(collector.unresolvedV3TradeOverflowed());
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> collector.buildV3Payload(START_DAY));
        assertTrue(failure.getMessage().contains("UNRESOLVED_TRADE_BUFFER_OVERFLOW"));
    }

    @Test
    void legacyV2PayloadAndDropContractRemainUnchanged() throws Exception {
        OkxMicrostructureCollector collector = collectorWithAcks();
        long dayStart = START_DAY.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        for (int minute = 0; minute < 1_440; minute++) {
            long start = dayStart + minute * 60_000L;
            long sequence = minute + 1L;
            collector.acceptRaw(tradeMessage(
                    start + 1_000, "100", "1", "buy", sequence, sequence));
            collector.acceptRaw(bookMessage(start + 2_000, sequence));
        }

        Map<String, Object> payload = collector.buildV2Payload(START_DAY);
        assertEquals("OKX_MICROSTRUCTURE_FORWARD_DAY_V2", payload.get("schema_version"));
        Map<String, Object> first = castList(payload.get("minutes")).getFirst();
        assertFalse(first.containsKey("midline_reference_count"));
        assertFalse(first.containsKey("above_mid_buy_quote_notional"));
        OkxMicrostructureCanonicalDrop.DropDocuments documents =
                OkxMicrostructureCanonicalDrop.create(
                        payload,
                        START_DAY,
                        null,
                        null,
                        "okx-microstructure-forward-diagnostic-v2",
                        "legacy-release-fixture",
                        "b".repeat(64),
                        START_DAY.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        Map<String, Object> envelope = mapper.readValue(
                documents.envelopeBytes(), new TypeReference<>() { });
        assertEquals(OkxMicrostructureCanonicalDrop.DROP_ENVELOPE_SCHEMA_VERSION,
                envelope.get("schema_version"));
        assertEquals(OkxMicrostructureCanonicalDrop.SOURCE_CONTRACT_SHA256,
                envelope.get("source_contract_sha256"));
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
        value.put("diagnostic_id", "okx-microstructure-forward-diagnostic-v3");
        value.put("source_contract_sha256",
                OkxMicrostructureCanonicalDrop.V3_SOURCE_CONTRACT_SHA256);
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

    private OkxMicrostructureCollector collectorWithAcks() {
        OkxMicrostructureCollector collector = new OkxMicrostructureCollector(mapper);
        collector.acceptRaw(acknowledgement("trades"));
        collector.acceptRaw(acknowledgement("books5"));
        return collector;
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
                producer.onRaw("{\"arg\":{\"channel\":\"books5\",\"instId\":\"BTC-USDT\"},\"data\":["
                        + bookRecord(start + 500, "99", "2", "101", "1", 1) + "]}");
                producer.onRaw("{\"arg\":{\"channel\":\"trades\",\"instId\":\"BTC-USDT\"},\"data\":["
                        + tradeRecord(start + 1_000, "100", "2", "buy", 1, 1) + ","
                        + tradeRecord(start + 2_000, "110", "1", "sell", 2, 2) + ","
                        + tradeRecord(start + 3_000, "105", "1", "buy", 3, 3) + ","
                        + tradeRecord(start + 3_500, "99", "1", "sell", 4, 4) + "]}");
                producer.onRaw("{\"arg\":{\"channel\":\"books5\",\"instId\":\"BTC-USDT\"},\"data\":["
                        + bookRecord(start + 4_000, "101", "3", "103", "1", 2) + "]}");
            } else {
                long bookSequence = minute * 2L + 10L;
                long tradeSequence = minute + 10L;
                producer.onRaw(bookMessage(start + 500, bookSequence));
                producer.onRaw(tradeMessage(
                        start + 1_000,
                        "100",
                        "1",
                        "buy",
                        tradeSequence,
                        tradeSequence));
                producer.onRaw(bookMessage(start + 2_000, bookSequence + 1));
            }
        }
    }

    private static void sendOneCompleteMinute(
            OkxMicrostructureContinuousSourceCli.Producer producer,
            LocalDate day,
        long sequence) {
        long start = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        producer.onRaw(bookMessage(start + 500, sequence * 2));
        producer.onRaw(tradeMessage(start + 1_000, "100", "1", "buy", sequence, sequence));
        producer.onRaw(bookMessage(start + 2_000, sequence * 2 + 1));
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
        return bookMessage(timestamp, "99", "2", "101", "1", sequence);
    }

    private static String bookMessage(
            long timestamp,
            String bid,
            String bidSize,
            String ask,
            String askSize,
            long sequence) {
        return "{\"arg\":{\"channel\":\"books5\",\"instId\":\"BTC-USDT\"},\"data\":["
                + bookRecord(timestamp, bid, bidSize, ask, askSize, sequence) + "]}";
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
