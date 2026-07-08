package com.agora.service.backtest;

import com.agora.service.trading.PositionSizingService;
import org.junit.jupiter.api.Test;

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
}
