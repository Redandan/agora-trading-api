package com.agora.service.backtest;

import com.agora.service.trading.PositionSizingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveSignalEvaluatorEntryDedupOpenExposureScopeTest {

    @Test
    void defaultsToAllOpenRows() {
        assertThat(LiveSignalEvaluator.resolveEntryDedupOpenExposureScope(Map.of()))
                .isEqualTo(LiveSignalEvaluator.ENTRY_DEDUP_SCOPE_ALL_OPEN_ROWS);
        assertThat(LiveSignalEvaluator.usesAutoTradedOpenRowsForEntryDedup(
                LiveSignalEvaluator.ENTRY_DEDUP_SCOPE_ALL_OPEN_ROWS))
                .isFalse();
    }

    @Test
    void acceptsExplicitAutoTradedOpenRowsScope() {
        assertThat(LiveSignalEvaluator.resolveEntryDedupOpenExposureScope(Map.of(
                LiveSignalEvaluator.ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_KEY, "auto_traded_open_rows")))
                .isEqualTo(LiveSignalEvaluator.ENTRY_DEDUP_SCOPE_AUTO_TRADED_OPEN_ROWS);
        assertThat(LiveSignalEvaluator.usesAutoTradedOpenRowsForEntryDedup(
                LiveSignalEvaluator.ENTRY_DEDUP_SCOPE_AUTO_TRADED_OPEN_ROWS))
                .isTrue();
    }

    @Test
    void invalidScopeFallsBackToAllOpenRows() {
        assertThat(LiveSignalEvaluator.resolveEntryDedupOpenExposureScope(Map.of(
                LiveSignalEvaluator.ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_KEY, "unexpected")))
                .isEqualTo(LiveSignalEvaluator.ENTRY_DEDUP_SCOPE_ALL_OPEN_ROWS);
        assertThat(LiveSignalEvaluator.usesAutoTradedOpenRowsForEntryDedup("unexpected"))
                .isFalse();
    }

    @Test
    void ensembleShadowDisclaimerNamesPreExecutionRiskGate() {
        assertThat(LiveSignalEvaluator.ENSEMBLE_SHADOW_PRE_EXECUTION_DISCLAIMER)
                .contains("Ensemble 不擋")
                .contains("下單前風控")
                .doesNotContain("此次仍 trade");
    }

    @Test
    void riskSizingSkipNotificationExplainsNoOrderAndMinNotional() {
        PositionSizingService.PositionSizingDecision decision =
                new PositionSizingService.PositionSizingDecision(
                        "BTCUSDT",
                        508L,
                        100.0,
                        20.83,
                        0.0,
                        0.12,
                        0.06,
                        0.5,
                        2.5,
                        451.57,
                        50.0,
                        true,
                        false,
                        true,
                        "below_min_notional_skip",
                        "legacy=100.00 recommended=20.83 slDistance=12.00% riskBudget=2.50USDT rr=0.50 mode=LIVE");

        String message = LiveSignalEvaluator.buildRiskSizingSkipNotification(
                "BTCUSDT", "1h", 508L, 256L, decision);

        assertThat(message)
                .contains("AutoTrade 未買入 BTCUSDT (1H)")
                .contains("$20.83")
                .contains("$50.00")
                .contains("&lt; min")
                .contains("策略: <b>#508</b>")
                .contains("live_signal_id <b>256</b>")
                .contains("不是 Ensemble shadow 攔截")
                .contains("不是漏單");
    }

    @Test
    void bottomCatchQualityGateBlocksWideDisasterStopWithPoorRiskReward() {
        LiveSignalEvaluator.BottomCatchQualityDecision decision =
                LiveSignalEvaluator.evaluateBottomCatchQualityGate(
                        OiFundingDivergenceStrategy.TYPE,
                        Map.of(),
                        0.12,
                        0.06,
                        true,
                        "ULTRA_LOW_DISASTER");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode())
                .contains("risk_reward_below_min")
                .contains("stop_loss_above_max");
        assertThat(decision.riskReward()).isCloseTo(0.50, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(decision.reason())
                .contains("riskReward=0.50")
                .contains("stopLoss=12.00%");
    }

    @Test
    void tradePlanQualitySkipNotificationExplainsNoOrderAndQualityGate() {
        LiveSignalEvaluator.BottomCatchQualityDecision decision =
                LiveSignalEvaluator.evaluateBottomCatchQualityGate(
                        OiFundingDivergenceStrategy.TYPE,
                        Map.of(),
                        0.12000006,
                        0.06,
                        true,
                        "ULTRA_LOW_DISASTER");

        String message = LiveSignalEvaluator.buildTradePlanQualitySkipNotification(
                "BTCUSDT",
                "1h",
                508L,
                new BigDecimal("62897.80"),
                new BigDecimal("55350.06"),
                new BigDecimal("66671.67"),
                decision);

        assertThat(message)
                .contains("AutoTrade 未買入 BTCUSDT (1H)")
                .contains("TradePlanQualityGate")
                .contains("risk_reward_below_min")
                .contains("stop_loss_above_max")
                .contains("RR: <b>0.50</b> / min <b>1.00</b>")
                .contains("SL: <b>12.00%</b> / max <b>8.00%</b>")
                .contains("策略: <b>#508</b>")
                .contains("不是 Ensemble shadow 攔截")
                .contains("不是漏單");
    }

    @Test
    void tradePlanQualityGateBlocksLowQualityReversalPlansByDefault() {
        LiveSignalEvaluator.BottomCatchQualityDecision decision =
                LiveSignalEvaluator.evaluateBottomCatchQualityGate(
                        "CMI_MIH_THRESHOLD",
                        Map.of(),
                        0.12,
                        0.05,
                        true,
                        "ULTRA_LOW_DISASTER");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode())
                .contains("risk_reward_below_min")
                .contains("stop_loss_above_max");
        assertThat(decision.riskReward()).isCloseTo(0.4167, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void bottomCatchQualityGateAllowsNormalTwoRPlan() {
        LiveSignalEvaluator.BottomCatchQualityDecision decision =
                LiveSignalEvaluator.evaluateBottomCatchQualityGate(
                        OiFundingDivergenceStrategy.TYPE,
                        Map.of(),
                        0.03,
                        0.06,
                        false,
                        "DISABLED");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("PASS");
        assertThat(decision.riskReward()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void bottomCatchQualityGateCanBeDisabledByStrategyConfig() {
        LiveSignalEvaluator.BottomCatchQualityDecision decision =
                LiveSignalEvaluator.evaluateBottomCatchQualityGate(
                        OiFundingDivergenceStrategy.TYPE,
                        Map.of("bottomCatchQualityGateEnabled", false),
                        0.12,
                        0.06,
                        true,
                        "ULTRA_LOW_DISASTER");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("DISABLED");
    }

    @Test
    void tradePlanQualityGateCanBeDisabledByNewGenericConfig() {
        LiveSignalEvaluator.BottomCatchQualityDecision decision =
                LiveSignalEvaluator.evaluateBottomCatchQualityGate(
                        "CMI_MIH_THRESHOLD",
                        Map.of(LiveSignalEvaluator.TRADE_PLAN_QUALITY_GATE_ENABLED_KEY, false),
                        0.12,
                        0.05,
                        true,
                        "ULTRA_LOW_DISASTER");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("DISABLED");
    }

    @Test
    void noSlAndPocStrategiesAreForcedOutOfLiveExecution() {
        com.agora.model.BtStrategy strategy = new com.agora.model.BtStrategy();
        strategy.setName("SQI-NoSL-LONG-BTC-1h-POC");

        assertThat(LiveSignalEvaluator.noLiveExecutionOnlyStrategy(strategy, Map.of(
                "fixedStopLossPct", 0.99,
                "fixedTakeProfitPct", 0.005)))
                .isTrue();
    }

    @Test
    void extremeNoSlPlanIsForcedOutOfLiveExecutionEvenWithoutNameMarker() {
        com.agora.model.BtStrategy strategy = new com.agora.model.BtStrategy();
        strategy.setName("Experimental BTC long");

        assertThat(LiveSignalEvaluator.noLiveExecutionOnlyStrategy(strategy, Map.of(
                "fixedStopLossPct", 0.99,
                "fixedTakeProfitPct", 0.005)))
                .isTrue();
    }

    @Test
    void preExecutionTelegramHeadersUseCandidateSemantics() {
        assertThat(LiveSignalEvaluator.resolveLongSignalTelegramHeader(false))
                .contains("買入候選")
                .doesNotContain("買入訊號");
        assertThat(LiveSignalEvaluator.resolveLongSignalTelegramHeader(true))
                .contains("觀察候選")
                .doesNotContain("買入訊號");
        assertThat(LiveSignalEvaluator.resolveShortSignalTelegramHeader())
                .contains("做空候選")
                .doesNotContain("SHORT 訊號");
    }
}
