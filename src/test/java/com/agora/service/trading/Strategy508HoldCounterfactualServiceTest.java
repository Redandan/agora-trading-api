package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class Strategy508HoldCounterfactualServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Strategy508HoldCounterfactualService service =
            new Strategy508HoldCounterfactualService(mock(JdbcTemplate.class), objectMapper);

    @Test
    void deduplicatesEventChainAndIncludesBothFeesInTimeoutPnl() throws Exception {
        LocalDateTime decision = LocalDateTime.of(2026, 7, 1, 0, 0);
        List<Map<String, Object>> evidence = List.of(
                buyRow(1, decision, decision.minusHours(1), "1h"),
                blockerRow(2, decision.plusSeconds(2), decision.minusHours(1), "1h",
                        "ExpectedValueGate", "expectedR below min threshold", "100"));
        List<Map<String, Object>> bars = constantBars(decision.plusMinutes(1), 1440,
                "105", "99", "104");

        JsonNode root = read(service.analyzeRowsForTest(
                "BTCUSDT", 720, 20, decision.plusHours(25), evidence, bars));

        assertThat(root.path("sampleStatus").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(root.path("liveRelaxationAllowed").asBoolean()).isFalse();
        assertThat(root.path("counts").path("rawEvidenceRows").asInt()).isEqualTo(2);
        assertThat(root.path("counts").path("uniqueMarketEvents").asInt()).isEqualTo(1);
        assertThat(root.path("counts").path("eventChainRowsCollapsed").asInt()).isEqualTo(1);
        assertThat(root.path("counts").path("eligibleUniqueEvents").asInt()).isEqualTo(1);
        assertThat(root.path("counts").path("finalizedUniqueEvents").asInt()).isEqualTo(1);
        assertThat(root.path("metrics").path("timeout24hCount").asInt()).isEqualTo(1);
        assertThat(root.path("metrics").path("return1hSampleCount").asInt()).isEqualTo(1);
        assertThat(root.path("metrics").path("return4hSampleCount").asInt()).isEqualTo(1);
        assertThat(root.path("metrics").path("return24hSampleCount").asInt()).isEqualTo(1);
        assertThat(root.path("metrics").path("totalFeesUsdt").asDouble()).isCloseTo(0.0204, within(0.0000001));
        assertThat(root.path("metrics").path("totalPnlUsdt").asDouble()).isCloseTo(0.3796, within(0.0000001));
    }

    @Test
    void anyHardBlockerExcludesWholeMarketEventEvenWhenSoftGateExists() throws Exception {
        LocalDateTime decision = LocalDateTime.of(2026, 7, 2, 0, 0);
        List<Map<String, Object>> evidence = List.of(
                buyRow(10, decision, decision.minusHours(1), "1h"),
                blockerRow(11, decision.plusSeconds(1), decision.minusHours(1), "1h",
                        "ExpectedValueGate", "expectedR below threshold", "100"),
                blockerRow(12, decision.plusSeconds(2), decision.minusHours(1), "1h",
                        "EntryDedup", "same strategy open exposure", "100"));

        JsonNode root = read(service.analyzeRowsForTest(
                "BTCUSDT", 720, 20, decision.plusHours(25), evidence, List.of()));

        assertThat(root.path("counts").path("eligibleUniqueEvents").asInt()).isZero();
        assertThat(root.path("classificationBreakdown").path("EXCLUDED_HARD_SAFETY_BLOCK").asInt()).isEqualTo(1);
        assertThat(root.path("safety").path("hardSafetyEventsEligible").asInt()).isZero();
        assertThat(root.path("blockerBreakdown").toString()).contains("HARD:EntryDedup");
    }

    @Test
    void ordinaryHoldWithoutAllGatesPassedIsNotAMissedBuy() throws Exception {
        LocalDateTime decision = LocalDateTime.of(2026, 7, 3, 0, 0);
        Map<String, Object> hold = baseRow(20, decision, decision.minusHours(1), "1h");
        hold.put("event_type", "SIGNAL_EVAL");
        hold.put("audit_reason", "HOLD");
        hold.put("context_json", "{\"extras\":{\"strategyDecision\":{\"hold_reason\":\"gate_failed\",\"gate_volume_confirmed\":false}}}");

        JsonNode root = read(service.analyzeRowsForTest(
                "BTCUSDT", 720, 20, decision.plusHours(25), List.of(hold), List.of()));

        assertThat(root.path("counts").path("eligibleUniqueEvents").asInt()).isZero();
        assertThat(root.path("classificationBreakdown").path("EXCLUDED_SIGNAL_NOT_READY").asInt()).isEqualTo(1);
    }

    @Test
    void existingOrderEvidenceExcludesCounterfactual() throws Exception {
        LocalDateTime decision = LocalDateTime.of(2026, 7, 4, 0, 0);
        Map<String, Object> sent = blockerRow(32, decision.plusSeconds(2), decision.minusHours(1), "1h",
                "ExpectedValueGate", "expectedR below threshold", "100");
        sent.put("order_sent", true);

        JsonNode root = read(service.analyzeRowsForTest(
                "BTCUSDT", 720, 20, decision.plusHours(25),
                List.of(buyRow(31, decision, decision.minusHours(1), "1h"), sent), List.of()));

        assertThat(root.path("classificationBreakdown").path("EXCLUDED_ALREADY_ORDERED").asInt()).isEqualTo(1);
        assertThat(root.path("counts").path("eligibleUniqueEvents").asInt()).isZero();
    }

    @Test
    void sameMinuteTpAndSlIsAmbiguousAndNeverFinalized() throws Exception {
        LocalDateTime decision = LocalDateTime.of(2026, 7, 5, 0, 0);
        List<Map<String, Object>> evidence = List.of(
                buyRow(40, decision, decision.minusHours(1), "1h"),
                blockerRow(41, decision.plusSeconds(1), decision.minusHours(1), "1h",
                        "TradePlanQualityGate", "risk reward below min", "100"));
        List<Map<String, Object>> bars = List.of(kline(decision.plusMinutes(1), "107", "87", "100"));

        JsonNode root = read(service.analyzeRowsForTest(
                "BTCUSDT", 720, 20, decision.plusHours(1), evidence, bars));

        assertThat(root.path("counts").path("ambiguousSameMinuteEvents").asInt()).isEqualTo(1);
        assertThat(root.path("counts").path("finalizedUniqueEvents").asInt()).isZero();
        assertThat(root.path("events").get(0).path("outcome").asText()).isEqualTo("AMBIGUOUS_SAME_MINUTE");
    }

    @Test
    void matureNoTouchEventWithMissingOneMinuteCoverageIsNotFinalized() throws Exception {
        LocalDateTime decision = LocalDateTime.of(2026, 7, 5, 12, 0);
        List<Map<String, Object>> evidence = List.of(
                buyRow(50, decision, decision.minusHours(1), "1h"),
                blockerRow(51, decision.plusSeconds(1), decision.minusHours(1), "1h",
                        "ExpectedValueGate", "expectedR below min", "100"));
        List<Map<String, Object>> bars = List.of(kline(decision.plusMinutes(1), "101", "99", "100"));

        JsonNode root = read(service.analyzeRowsForTest(
                "BTCUSDT", 720, 20, decision.plusHours(25), evidence, bars));

        assertThat(root.path("counts").path("insufficientKlineCoverageEvents").asInt()).isEqualTo(1);
        assertThat(root.path("counts").path("finalizedUniqueEvents").asInt()).isZero();
        assertThat(root.path("events").get(0).path("outcome").asText()).isEqualTo("INSUFFICIENT_KLINE_COVERAGE");
    }

    @Test
    void missingCandidateEntryUsesPostDecisionMinuteOpenWithoutCloseLookAhead() throws Exception {
        LocalDateTime decision = LocalDateTime.of(2026, 7, 5, 18, 0);
        List<Map<String, Object>> evidence = List.of(
                buyRow(60, decision, decision.minusHours(1), "1h"),
                blockerRow(61, decision.plusSeconds(1), decision.minusHours(1), "1h",
                        "ExpectedValueGate", "expectedR below min", null));
        List<Map<String, Object>> bars = List.of(kline(decision.plusMinutes(1), "210", "99", "200"));

        JsonNode root = read(service.analyzeRowsForTest(
                "BTCUSDT", 720, 20, decision.plusHours(1), evidence, bars));
        JsonNode event = root.path("events").get(0);

        assertThat(event.path("entryPrice").asDouble()).isEqualTo(100.0);
        assertThat(event.path("entryPriceSource").asText()).isEqualTo("FIRST_POST_DECISION_1M_OPEN");
        assertThat(event.path("outcome").asText()).isEqualTo("TP_HIT");
        assertThat(event.path("finalized").asBoolean()).isTrue();
    }

    @Test
    void thirtyFinalizedEventsPassSampleGateButStillDoNotAuthorizeLive() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 7, 6, 0, 0);
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            LocalDateTime decision = start.plusHours(i * 2L);
            LocalDateTime barOpen = decision.minusHours(1);
            evidence.add(buyRow(100 + i * 2L, decision, barOpen, "1h"));
            evidence.add(blockerRow(101 + i * 2L, decision.plusSeconds(1), barOpen, "1h",
                    "ExpectedValueGate", "expectedR below min threshold", "100"));
            bars.add(kline(decision.plusMinutes(1), "106", "100", "106"));
        }

        JsonNode root = read(service.analyzeRowsForTest(
                "BTCUSDT", 720, 10, start.plusDays(4), evidence, bars));

        assertThat(root.path("counts").path("finalizedUniqueEvents").asInt()).isEqualTo(30);
        assertThat(root.path("sampleStatus").asText()).isEqualTo("SHADOW_SAMPLE_READY_FOR_REVIEW_NOT_LIVE");
        assertThat(root.path("liveRelaxationAllowed").asBoolean()).isFalse();
        assertThat(root.path("safety").path("orderSent").asBoolean()).isFalse();
    }

    private JsonNode read(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private Map<String, Object> buyRow(long id,
                                       LocalDateTime eventTime,
                                       LocalDateTime barOpenTime,
                                       String interval) {
        Map<String, Object> row = baseRow(id, eventTime, barOpenTime, interval);
        row.put("event_type", "SIGNAL_EVAL");
        row.put("outcome", "INFO");
        row.put("audit_reason", "BUY");
        row.put("context_json", """
                {"extras":{"strategyDecision":{
                  "trigger_reason":"all_gates_passed",
                  "gate_funding_low":true,
                  "gate_oi_stable":true,
                  "gate_volume_confirmed":true,
                  "gate_above_sma200":true,
                  "gate_dex_flow":true,
                  "gate_spread_ok":true
                }},"execution":{"decision":"BUY"}}
                """);
        return row;
    }

    private Map<String, Object> blockerRow(long id,
                                           LocalDateTime eventTime,
                                           LocalDateTime barOpenTime,
                                           String interval,
                                           String blocker,
                                           String reason,
                                           String entry) {
        Map<String, Object> row = baseRow(id, eventTime, barOpenTime, interval);
        row.put("event_type", "FILTER_BLOCK");
        row.put("outcome", "BLOCKED");
        row.put("audit_blocker", blocker);
        row.put("audit_reason", reason);
        row.put("terminal_blocker", blocker);
        row.put("blocker_reason", reason);
        row.put("order_sent", false);
        row.put("context_json", entry == null
                ? "{\"orderSent\":false}"
                : "{\"candidateEntry\":" + entry + ",\"orderSent\":false}");
        return row;
    }

    private Map<String, Object> baseRow(long id,
                                        LocalDateTime eventTime,
                                        LocalDateTime barOpenTime,
                                        String interval) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("audit_id", id);
        row.put("event_time", eventTime);
        row.put("strategy_id", 508L);
        row.put("symbol", "BTCUSDT");
        row.put("interval_code", interval);
        row.put("bar_open_time", barOpenTime);
        row.put("order_sent", false);
        row.put("live_signal_auto_traded", false);
        return row;
    }

    private List<Map<String, Object>> constantBars(LocalDateTime start,
                                                   int count,
                                                   String high,
                                                   String low,
                                                   String close) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(kline(start.plusMinutes(i), high, low, close));
        }
        return rows;
    }

    private Map<String, Object> kline(LocalDateTime openTime, String high, String low, String close) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("open_time", openTime);
        row.put("open_price", new BigDecimal("100"));
        row.put("high_price", new BigDecimal(high));
        row.put("low_price", new BigDecimal(low));
        row.put("close_price", new BigDecimal(close));
        return row;
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
