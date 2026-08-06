package com.agora.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OkxMicrostructureForwardSourceCliTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aggregatesTakerFlowAndTopFiveBookSnapshotsWithoutCanonicalEligibility() {
        OkxMicrostructureForwardSourceCli.CollectorState state = stateWithAcks();
        state.acceptRaw("""
                {"arg":{"channel":"trades","instId":"BTC-USDT"},"data":[
                  {"instId":"BTC-USDT","tradeId":"10","px":"100","sz":"2","side":"buy","ts":"1704067201000","count":"2","source":"0","seqId":"50"},
                  {"instId":"BTC-USDT","tradeId":"11","px":"101","sz":"1","side":"sell","ts":"1704067202000","count":"1","source":"0","seqId":"51"}
                ]}
                """);
        state.acceptRaw("""
                {"arg":{"channel":"books5","instId":"BTC-USDT"},"data":[
                  {"asks":[["101","1","0","1"]],"bids":[["99","2","0","1"]],"ts":"1704067203000","seqId":"60"}
                ]}
                """);
        state.acceptRaw("""
                {"arg":{"channel":"books5","instId":"BTC-USDT"},"data":[
                  {"asks":[["102","1","0","1"]],"bids":[["100","3","0","1"]],"ts":"1704067204000","seqId":"61"}
                ]}
                """);

        Map<String, Object> bundle = state.buildBundle(
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-01T00:00:05Z"),
                5,
                null);

        assertEquals("CAPTURE_COMPLETE_RESEARCH_ONLY", bundle.get("status"));
        assertFalse((Boolean) bundle.get("canonical_evidence_eligible"));
        List<Map<String, Object>> minutes = castList(bundle.get("minutes"));
        assertEquals(1, minutes.size());
        Map<String, Object> minute = minutes.getFirst();
        assertEquals(Set.of(
                "minute",
                "trade_record_count",
                "match_count",
                "buy_base_quantity",
                "sell_base_quantity",
                "buy_quote_notional",
                "sell_quote_notional",
                "net_taker_quote_notional",
                "book_sample_count",
                "average_top5_bid_quote_depth",
                "average_top5_ask_quote_depth",
                "average_book_imbalance",
                "average_spread_bps",
                "bid_replenishment_quote_proxy",
                "mid_price_start",
                "mid_price_end"), minute.keySet());
        assertFalse(minute.containsKey("total_quote_notional"));
        assertEquals(2L, minute.get("trade_record_count"));
        assertEquals(3L, minute.get("match_count"));
        assertEquals("200", minute.get("buy_quote_notional"));
        assertEquals("101", minute.get("sell_quote_notional"));
        assertEquals("99", minute.get("net_taker_quote_notional"));
        assertEquals(2L, minute.get("book_sample_count"));
        assertEquals("249", minute.get("average_top5_bid_quote_depth"));
        assertEquals("101.5", minute.get("average_top5_ask_quote_depth"));
        assertEquals("102", minute.get("bid_replenishment_quote_proxy"));

        Map<String, Object> integrity = castMap(bundle.get("integrity"));
        assertEquals("CLEAN", integrity.get("status"));
        assertEquals(5L, integrity.get("raw_message_count"));
        assertNotEquals("0".repeat(64), integrity.get("arrival_chain_sha256"));
    }

    @Test
    void surfacesRegressionsAndMalformedRecordsFailClosed() {
        OkxMicrostructureForwardSourceCli.CollectorState state = stateWithAcks();
        state.acceptRaw("""
                {"arg":{"channel":"trades","instId":"BTC-USDT"},"data":[
                  {"tradeId":"20","px":"100","sz":"1","side":"buy","ts":"1704067202000","seqId":"20"},
                  {"tradeId":"19","px":"100","sz":"1","side":"buy","ts":"1704067201000","seqId":"19"}
                ]}
                """);
        state.acceptRaw("not-json");

        Map<String, Object> bundle = state.buildBundle(
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-01T00:00:05Z"),
                5,
                null);
        Map<String, Object> integrity = castMap(bundle.get("integrity"));

        assertEquals("ANOMALIES_PRESENT", integrity.get("status"));
        assertEquals(1L, integrity.get("malformed_record_count"));
        assertEquals(1L, integrity.get("trade_timestamp_regression_count"));
        assertEquals(1L, integrity.get("trade_sequence_regression_count"));
        assertEquals(1L, integrity.get("trade_id_non_increasing_count"));
        assertFalse((Boolean) bundle.get("canonical_evidence_eligible"));
    }

    @Test
    void preservesBoundedV1PerRecordAcceptanceWhenOneRecordIsMalformed() {
        OkxMicrostructureForwardSourceCli.CollectorState state = stateWithAcks();
        state.acceptRaw("""
                {"arg":{"channel":"trades","instId":"BTC-USDT"},"data":[
                  {"tradeId":"30","px":"100","sz":"1","side":"buy","ts":"1704067201000","seqId":"30"},
                  {"tradeId":"31","px":"bad","sz":"1","side":"buy","ts":"1704067202000","seqId":"31"}
                ]}
                """);

        Map<String, Object> bundle = state.buildBundle(
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-01T00:00:05Z"),
                5,
                null);
        List<Map<String, Object>> minutes = castList(bundle.get("minutes"));
        Map<String, Object> integrity = castMap(bundle.get("integrity"));

        assertEquals(1, minutes.size());
        assertEquals(1L, minutes.getFirst().get("trade_record_count"));
        assertEquals(1L, integrity.get("malformed_record_count"));
    }

    private OkxMicrostructureForwardSourceCli.CollectorState stateWithAcks() {
        OkxMicrostructureForwardSourceCli.CollectorState state =
                new OkxMicrostructureForwardSourceCli.CollectorState(mapper);
        state.acceptRaw("""
                {"event":"subscribe","arg":{"channel":"trades","instId":"BTC-USDT"}}
                """);
        state.acceptRaw("""
                {"event":"subscribe","arg":{"channel":"books5","instId":"BTC-USDT"}}
                """);
        return state;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
