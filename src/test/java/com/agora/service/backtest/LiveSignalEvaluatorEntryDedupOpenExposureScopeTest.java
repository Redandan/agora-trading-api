package com.agora.service.backtest;

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
}
