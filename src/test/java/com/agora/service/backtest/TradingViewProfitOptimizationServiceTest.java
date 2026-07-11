package com.agora.service.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradingViewProfitOptimizationServiceTest {

    private final TradingViewProfitOptimizationService service = new TradingViewProfitOptimizationService();

    @Test
    void reportUsesProductionBaselineFixedWindowsAndRejectsUnprofitableDrawdownReductionCandidate() {
        LocalDateTime start = LocalDateTime.parse("2025-01-01T00:00:00");
        List<BtcBaseShadowBacktestSimulator.Bar> bars = new ArrayList<>();
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = new ArrayList<>();
        for (int day = 0; day <= 365; day++) {
            LocalDateTime time = start.plusDays(day);
            bars.add(new BtcBaseShadowBacktestSimulator.Bar(time, 200.0 - day * 0.25));
            if (day % 30 == 0) {
                intents.add(intent(time, "AI_BUY", 5000.0));
                intents.add(intent(time, "RELATIVE_LOW"));
            }
        }

        String report = service.compareCurrentCandidate(
                "BTCUSDT", "binance", 0.001, bars, intents);

        assertThat(report)
                .contains("boundary=READ_ONLY")
                .contains("baseline=LIVE_ONE_ORDER_PER_BAR candidate=LIVE_ONE_ORDER_PER_BAR_WITH_DRAWDOWN_REDUCTION_SHADOW")
                .contains("buyPointPolicy=PRESERVE_ALL_TRADINGVIEW_INTENTS")
                .contains("productionOrderPolicy=FIXED_10_USDT_FULL_SLICE")
                .contains("baselineExitPolicy=HOLD_BTC_BASE_NO_OCO_NO_AUTO_SELL")
                .contains("candidatePolicy=FIXED_10_USDT_ONE_ORDER_PER_BAR_NET_RETURN_LTE_MINUS12_REDUCE_25PCT_REARM_ON_NEW_BUY")
                .contains("candidateExposurePolicy=MAX_CONCURRENT_COST_BASIS_250_REDEPLOY_AFTER_REDUCTION")
                .contains("drawdownGateMetric=MAX_OF_INVENTORY_DRAWDOWN_AND_CAPITAL_LOSS_ON_CUMULATIVE_GROSS_BUYS")
                .contains("candidateLookahead=false candidateAddsBuyPoints=false candidateDeletesBuyPoints=false")
                .contains("window=90d")
                .contains("window=180d")
                .contains("window=270d")
                .contains("window=365d")
                .contains("baselineFeesPaid=")
                .contains("baselineRealized=")
                .contains("baselineUnrealized=")
                .contains("baselineMaxInventoryDrawdown=")
                .contains("baselineMaxCapitalLoss=")
                .contains("candidateUpsizedBars=")
                .contains("candidateMaxInventoryDrawdown=")
                .contains("candidateMaxCapitalLoss=")
                .contains("candidateEmergencyWarnings=")
                .contains("candidateEmergencyReductions=")
                .contains("walkForwardFold=1")
                .contains("walkForwardSummary=baselinePositiveFolds=")
                .contains("stressBaselinePnl=")
                .contains("stressCandidatePnl=")
                .contains("walkForwardPositiveFolds=")
                .contains("candidateVerdict=REJECTED")
                .contains("candidatePromotionAllowed=false")
                .contains("nextCandidate=NONE_NO_PROVEN_EDGE_STOP_TUNING");
    }

    @Test
    void productionBaselineUsesFullTenUsdtSlicesAndNeverAutoSells() {
        LocalDateTime start = LocalDateTime.parse("2025-01-01T00:00:00");
        List<BtcBaseShadowBacktestSimulator.Bar> bars = new ArrayList<>();
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = new ArrayList<>();
        for (int day = 0; day < 366; day++) {
            LocalDateTime time = start.plusDays(day);
            bars.add(new BtcBaseShadowBacktestSimulator.Bar(time, 100.0));
            if (day >= 338) {
                intents.add(intent(time, "FIXED_SLICE"));
            }
        }

        String report = service.compareCurrentCandidate(
                "BTCUSDT", "binance", 0.001, bars, intents);

        assertThat(report)
                .contains("window=365d intents=28 bars=28 baselineInvested=250.00")
                .contains("baselineExecuted=25")
                .contains("baselineSkipped=3")
                .contains("baselineRealized=0.00")
                .contains("baselineTakeProfitReductions=0");
    }

    @Test
    void exitCandidateKeepsFixedTenUsdtSizingInShortWindow() {
        LocalDateTime start = LocalDateTime.parse("2025-01-01T00:00:00");
        List<BtcBaseShadowBacktestSimulator.Bar> bars = new ArrayList<>();
        for (int day = 0; day <= 365; day++) {
            double close = day == 100 ? 200.0 : 100.0;
            bars.add(new BtcBaseShadowBacktestSimulator.Bar(start.plusDays(day), close));
        }
        List<BtcBaseShadowBacktestSimulator.BuyIntent> intents = List.of(
                intent(start.plusDays(300), "IN_90_DAY_WINDOW"));

        String report = service.compareCurrentCandidate(
                "BTCUSDT", "binance", 0.001, bars, intents);

        assertThat(report)
                .contains("window=90d intents=1 bars=1 baselineInvested=10.00")
                .contains("candidateInvested=10.00")
                .contains("candidateExecuted=1")
                .contains("candidateUpsizedBars=0")
                .contains("candidateEmergencyReductions=0");
    }

    private BtcBaseShadowBacktestSimulator.BuyIntent intent(LocalDateTime time, String reason) {
        return intent(time, reason, 1000.0);
    }

    private BtcBaseShadowBacktestSimulator.BuyIntent intent(
            LocalDateTime time, String reason, double quantity) {
        return new BtcBaseShadowBacktestSimulator.BuyIntent(time, quantity, reason, reason, "BUY");
    }
}
