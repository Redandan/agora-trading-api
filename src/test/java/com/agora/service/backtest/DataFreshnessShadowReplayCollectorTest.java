package com.agora.service.backtest;

import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataFreshnessShadowReplayCollectorTest {

    @Test
    void disabledCollectorOnlyAddsSafetyMarkers() {
        DataFreshnessShadowReplayCollector collector = collector();
        ReflectionTestUtils.setField(collector, "enabled", false);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("replayCandidateId", "dfsr1_test");

        collector.enrichAfterHardBlock(context, strategy(), "BTCUSDT", "1h", "okx",
                kline(), LocalDateTime.parse("2026-06-21T01:00:00"),
                LocalDateTime.parse("2026-06-21T00:00:00"),
                180, 135, 60, 300);

        assertEquals("dfsr1_test", context.get("replayCandidateId"));
        assertEquals(false, context.get("shadowReplayCollectorEnabled"));
        assertEquals(DataFreshnessShadowReplayCollector.DISABLED, context.get("shadowReplayCollectorStatus"));
        assertEquals("DataFreshnessGuard", context.get("shadowReplayTerminalDecision"));
        assertEquals(true, context.get("shadowReplayKeepsHardBlock"));
        assertFalse((Boolean) context.get("shadowReplayCreatesLiveSignal"));
        assertFalse((Boolean) context.get("shadowReplaySendsTelegram"));
        assertFalse((Boolean) context.get("shadowReplayPlacesOrder"));
        assertFalse((Boolean) context.get("shadowReplayCreatesOco"));
        assertFalse((Boolean) context.get("shadowReplayMutatesPolicy"));
        assertNull(context.get("shadowReplayMissingCounterfactualFields"));
        assertNull(context.get("candidateEntry"));
        assertNull(context.get("snapshotLatestBarOpen"));
    }

    @Test
    void enabledCollectorCapturesCandidatePlanSnapshotWithoutExecutableEvidence() {
        DataFreshnessShadowReplayCollector collector = collector();
        ReflectionTestUtils.setField(collector, "enabled", true);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("replayCandidateId", "dfsr1_test");

        collector.enrichAfterHardBlock(context, strategy(), "BTCUSDT", "1h", "okx",
                kline(), LocalDateTime.parse("2026-06-21T01:00:00"),
                LocalDateTime.parse("2026-06-21T00:00:00"),
                180, 135, 60, 300);

        assertEquals(true, context.get("shadowReplayCollectorEnabled"));
        assertEquals(DataFreshnessShadowReplayCollector.CANDIDATE_PLAN_SNAPSHOT_NOT_REPLAYABLE,
                context.get("shadowReplayCollectorStatus"));
        assertEquals(DataFreshnessShadowReplayCollector.CANDIDATE_PLAN_SNAPSHOT_NOT_REPLAYABLE,
                context.get("shadowReplayCandidateStatus"));
        assertEquals("AVAILABLE_NOT_REPLAYABLE", context.get("shadowReplayCandidatePlanStatus"));
        assertEquals(DataFreshnessShadowReplayCandidatePlanBuilder.FIXED_CONFIG_SNAPSHOT_ONLY,
                context.get("shadowReplayCandidatePlanSource"));
        assertEquals(new BigDecimal("65000.00"), context.get("candidateEntry"));
        assertEquals(new BigDecimal("71500.00"), context.get("candidateTp"));
        assertEquals(new BigDecimal("61750.00"), context.get("candidateSl"));
        assertFalse(((String) context.get("shadowReplayMissingCounterfactualFields")).contains("candidateEntry"));
        assertTrue(((String) context.get("shadowReplayMissingCounterfactualFields")).contains("oco_preflight"));
        assertTrue(context.containsKey("ev_result"));
        assertTrue(context.containsKey("tqs_result"));
        assertTrue(context.containsKey("oco_preflight"));
        assertTrue(context.containsKey("duplicate_gate"));
        assertTrue(context.containsKey("daily_cap"));
        assertTrue(context.containsKey("exposure_gate"));
        assertTrue(context.containsKey("event_risk"));
        assertTrue(context.containsKey("open_position"));
        assertTrue(context.containsKey("loss_budget"));
        assertEquals(DataFreshnessShadowReplayHardGatePreviewBuilder.PREVIEW_ONLY_NOT_REPLAYABLE,
                context.get("shadowReplayHardGatePreviewStatus"));
        assertEquals(0, context.get("qualityScore"));
        assertEquals("BLOCK", context.get("tqsBand"));
        assertEquals("collect_ev_tqs_oco_and_hard_gate_snapshots_before_policy_review",
                context.get("shadowReplayRequiredNextAction"));
        assertEquals("BTCUSDT", context.get("snapshotSymbol"));
        assertEquals("1h", context.get("snapshotIntervalCode"));
        assertEquals("okx", context.get("snapshotKlineSource"));
        assertEquals("2026-06-20T23:00", context.get("snapshotLatestBarOpen"));
        assertEquals(new BigDecimal("65000.00"), context.get("snapshotClosePrice"));
        assertFalse((Boolean) context.get("shadowReplayCreatesLiveSignal"));
        assertFalse((Boolean) context.get("shadowReplaySendsTelegram"));
        assertFalse((Boolean) context.get("shadowReplayPlacesOrder"));
        assertFalse((Boolean) context.get("shadowReplayCreatesOco"));
        assertFalse((Boolean) context.get("shadowReplayMutatesPolicy"));
    }

    @Test
    void enabledCollectorDoesNotGuessDynamicAtrCandidatePlan() {
        DataFreshnessShadowReplayCollector collector = collector();
        ReflectionTestUtils.setField(collector, "enabled", true);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("replayCandidateId", "dfsr1_test");

        BtStrategy strategy = strategy();
        strategy.setConfigJson("{\"atrSlMultiplier\":1.5,\"atrTpMultiplier\":3.0}");
        collector.enrichAfterHardBlock(context, strategy, "BTCUSDT", "1h", "okx",
                kline(), LocalDateTime.parse("2026-06-21T01:00:00"),
                LocalDateTime.parse("2026-06-21T00:00:00"),
                180, 135, 60, 300);

        assertEquals(DataFreshnessShadowReplayCollector.SNAPSHOT_ONLY_NOT_REPLAYABLE,
                context.get("shadowReplayCollectorStatus"));
        assertEquals(DataFreshnessShadowReplayCandidatePlanBuilder.NOT_REPLAYABLE_DYNAMIC_ATR_CONFIG,
                context.get("shadowReplayCandidatePlanStatus"));
        assertNull(context.get("candidateEntry"));
        assertTrue(((String) context.get("shadowReplayMissingCounterfactualFields")).contains("candidateEntry"));
    }

    private DataFreshnessShadowReplayCollector collector() {
        return new DataFreshnessShadowReplayCollector(
                new DataFreshnessShadowReplayCandidatePlanBuilder(new ObjectMapper()),
                new DataFreshnessShadowReplayHardGatePreviewBuilder());
    }

    private BtStrategy strategy() {
        BtStrategy strategy = new BtStrategy();
        strategy.setId(574L);
        strategy.setStrategyType("TINY_LIVE");
        strategy.setKlineSource("okx");
        strategy.setConfigJson("{}");
        return strategy;
    }

    private MdKline kline() {
        MdKline kline = new MdKline();
        kline.setSymbol("BTCUSDT");
        kline.setIntervalCode("1h");
        kline.setSource("okx");
        kline.setOpenTime(LocalDateTime.parse("2026-06-20T23:00:00"));
        kline.setCloseTime(LocalDateTime.parse("2026-06-20T23:59:59"));
        kline.setOpenPrice(new BigDecimal("64800.00"));
        kline.setHighPrice(new BigDecimal("65200.00"));
        kline.setLowPrice(new BigDecimal("64750.00"));
        kline.setClosePrice(new BigDecimal("65000.00"));
        kline.setVolume(new BigDecimal("12.345"));
        return kline;
    }
}
