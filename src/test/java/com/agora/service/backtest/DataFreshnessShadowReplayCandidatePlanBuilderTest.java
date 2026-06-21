package com.agora.service.backtest;

import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataFreshnessShadowReplayCandidatePlanBuilderTest {

    private final DataFreshnessShadowReplayCandidatePlanBuilder builder =
            new DataFreshnessShadowReplayCandidatePlanBuilder(new ObjectMapper());

    @Test
    void buildsFixedConfigSnapshotPlan() {
        BtStrategy strategy = strategy("""
                {"fixedStopLossPct":0.03,"fixedTakeProfitPct":0.06,"maxHoldingHours":24}
                """);

        Optional<DataFreshnessShadowReplayCandidatePlanBuilder.CandidatePlan> result =
                builder.build(strategy, kline());

        assertTrue(result.isPresent());
        DataFreshnessShadowReplayCandidatePlanBuilder.CandidatePlan plan = result.get();
        assertTrue(plan.available());
        assertEquals(DataFreshnessShadowReplayCandidatePlanBuilder.FIXED_CONFIG_SNAPSHOT_ONLY, plan.source());
        assertEquals(new BigDecimal("65000.00"), plan.entry());
        assertEquals(new BigDecimal("68900.00"), plan.tp());
        assertEquals(new BigDecimal("63050.00"), plan.sl());
        assertEquals(0.03, plan.stopLossPct());
        assertEquals(0.06, plan.takeProfitPct());
        assertEquals(24, plan.maxHoldingHours());
    }

    @Test
    void refusesDynamicAtrSnapshotPlan() {
        BtStrategy strategy = strategy("""
                {"atrSlMultiplier":1.5,"atrTpMultiplier":3.0,"fixedStopLossPct":0.03}
                """);

        Optional<DataFreshnessShadowReplayCandidatePlanBuilder.CandidatePlan> result =
                builder.build(strategy, kline());

        assertTrue(result.isPresent());
        DataFreshnessShadowReplayCandidatePlanBuilder.CandidatePlan plan = result.get();
        assertFalse(plan.available());
        assertEquals(DataFreshnessShadowReplayCandidatePlanBuilder.NOT_REPLAYABLE_DYNAMIC_ATR_CONFIG, plan.source());
    }

    @Test
    void horizonCapMatchesLiveEvaluatorFixedPath() {
        BtStrategy strategy = strategy("""
                {"fixedStopLossPct":0.20,"fixedTakeProfitPct":0.50,"maxHoldingHours":12}
                """);

        DataFreshnessShadowReplayCandidatePlanBuilder.CandidatePlan plan =
                builder.build(strategy, kline()).orElseThrow();

        assertEquals(new BigDecimal("67600.00"), plan.tp());
        assertEquals(new BigDecimal("63050.00"), plan.sl());
        assertEquals(0.03, plan.stopLossPct());
        assertEquals(0.04, plan.takeProfitPct());
    }

    private BtStrategy strategy(String configJson) {
        BtStrategy strategy = new BtStrategy();
        strategy.setId(574L);
        strategy.setStrategyType("TINY_LIVE");
        strategy.setConfigJson(configJson);
        strategy.setKlineSource("okx");
        return strategy;
    }

    private MdKline kline() {
        MdKline kline = new MdKline();
        kline.setClosePrice(new BigDecimal("65000.00"));
        kline.setOpenTime(LocalDateTime.parse("2026-06-20T23:00:00"));
        return kline;
    }
}
