package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtLiveSignalRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExposureOptimizerTest {

    private final BtLiveSignalRepository liveSignalRepository = mock(BtLiveSignalRepository.class);
    private final ExposureOptimizer optimizer = new ExposureOptimizer(liveSignalRepository);

    @Test
    void sameStrategySymbolSideIntervalLongExposureBlocksDuplicateEntry() {
        BtStrategy strategy = strategy(485L);
        BtLiveSignal open = openLong(485L, "BTCUSDT", "1h", "100000", "0.001");
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of(open));
        when(liveSignalRepository.countByAutoTradedIsTrueAndCreatedAtAfter(any(LocalDateTime.class))).thenReturn(0L);

        var result = optimizer.evaluateLongEntry(
                strategy,
                Map.of(
                        "entryDedupDecisionMode", "BLOCK",
                        "exposureOptimizerOpenMaxLossCapUsdt", 1000.0),
                "BTCUSDT",
                "1h",
                0.42,
                0.12,
                true);

        assertThat(result.decision()).isEqualTo(ExposureOptimizer.Decision.BLOCK_DUPLICATE);
        assertThat(result.blocksEntry()).isTrue();
        assertThat(result.reason()).contains("same strategy/symbol/interval LONG exposure already exists");
        assertThat(result.context())
                .containsEntry("same_strategy_open_long", true)
                .containsEntry("entry_dedup_decision_mode", "BLOCK");
        assertThat(result.context().get("same_strategy_exposure_usdt")).isEqualTo("100");
    }

    @Test
    void stagedMicroAddStillBlocksWhenSameStrategyExposureBudgetIsExhausted() {
        BtStrategy strategy = strategy(485L);
        BtLiveSignal open = openLong(485L, "BTCUSDT", "1h", "100000", "0.001");
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of(open));
        when(liveSignalRepository.countByAutoTradedIsTrueAndCreatedAtAfter(any(LocalDateTime.class))).thenReturn(0L);

        var result = optimizer.evaluateLongEntry(
                strategy,
                Map.of(
                        "entryDedupDecisionMode", "ALLOW_MICRO_ADD_IF_EV_POSITIVE",
                        "microAddMaxSameStrategyExposureUsdt", 100.0,
                        "microAddNotionalUsdt", 5.0,
                        "exposureOptimizerOpenMaxLossCapUsdt", 1000.0),
                "BTCUSDT",
                "1h",
                0.42,
                0.12,
                true);

        assertThat(result.decision()).isEqualTo(ExposureOptimizer.Decision.BLOCK_DUPLICATE);
        assertThat(result.reason()).contains("same-strategy staged add exposure budget exhausted");
        assertThat(result.context())
                .containsEntry("entry_dedup_decision_mode", "ALLOW_MICRO_ADD_IF_EV_POSITIVE")
                .containsEntry("micro_add_expected_r_positive", true);
        assertThat(result.context().get("same_strategy_exposure_usdt")).isEqualTo("100");
        assertThat(result.context().get("same_strategy_exposure_cap_usdt")).isEqualTo(100.0);
    }

    @Test
    void crossStrategySameSymbolLongExposureBlocksNewAutoEntryByDefault() {
        BtStrategy strategy = strategy(485L);
        BtLiveSignal open = openLong(777L, "BTCUSDT", "4h", "100000", "0.002");
        when(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()).thenReturn(List.of(open));
        when(liveSignalRepository.countByAutoTradedIsTrueAndCreatedAtAfter(any(LocalDateTime.class))).thenReturn(0L);

        var result = optimizer.evaluateLongEntry(
                strategy,
                Map.of(
                        "entryDedupDecisionMode", "BLOCK",
                        "exposureOptimizerOpenMaxLossCapUsdt", 1000.0),
                "BTCUSDT",
                "1h",
                0.42,
                0.12,
                false);

        assertThat(result.decision()).isEqualTo(ExposureOptimizer.Decision.BLOCK_DUPLICATE);
        assertThat(result.blocksEntry()).isTrue();
        assertThat(result.reason()).contains("same-symbol LONG exposure already exists across strategy boundary");
        assertThat(result.context())
                .containsEntry("same_strategy_open_long", false)
                .containsEntry("same_symbol_open_long", true)
                .containsEntry("exposure_optimizer_block_same_symbol_long", true);
        assertThat(result.context().get("same_symbol_long_exposure_usdt")).isEqualTo("200");
    }

    private BtStrategy strategy(long id) {
        BtStrategy strategy = new BtStrategy();
        strategy.setId(id);
        return strategy;
    }

    private BtLiveSignal openLong(long strategyId, String symbol, String interval, String entry, String qty) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setStrategyId(strategyId);
        signal.setSymbol(symbol);
        signal.setIntervalCode(interval);
        signal.setSide("LONG");
        signal.setAutoTraded(true);
        signal.setActualEntryPrice(new BigDecimal(entry));
        signal.setOcoQty(new BigDecimal(qty));
        signal.setSuggestedSl(new BigDecimal(entry).multiply(new BigDecimal("0.88")));
        signal.setCreatedAt(LocalDateTime.parse("2026-06-16T00:00:00"));
        return signal;
    }
}
