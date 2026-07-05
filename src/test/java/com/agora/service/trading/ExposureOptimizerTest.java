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

    @Test
    void duplicateBlockCarriesShadowRuntimeSnapshotWithoutAllowingMutation() {
        BtStrategy strategy = strategy(508L);
        BtLiveSignal open = openLong(508L, "BTCUSDT", "1h", "100000", "0.001");
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
                0.05,
                true,
                new BigDecimal("101000.00"),
                new BigDecimal("106050.00"),
                new BigDecimal("95950.00"),
                LocalDateTime.parse("2026-06-30T01:00:00"),
                0.20);

        assertThat(result.decision()).isEqualTo(ExposureOptimizer.Decision.BLOCK_DUPLICATE);
        assertThat(result.context())
                .containsEntry("candidateSnapshotCollectorStatus", "SHADOW_RUNTIME_SNAPSHOT_READY_NOT_LIVE")
                .containsEntry("candidateSnapshotCollectorBoundary", "EVIDENCE_ONLY_NO_ORDER_NO_POLICY_CHANGE")
                .containsEntry("entryPrice", new BigDecimal("101000.00"))
                .containsEntry("tpPrice", new BigDecimal("106050.00"))
                .containsEntry("slPrice", new BigDecimal("95950.00"))
                .containsEntry("candidateEntry", new BigDecimal("101000.00"))
                .containsEntry("candidateTp", new BigDecimal("106050.00"))
                .containsEntry("candidateSl", new BigDecimal("95950.00"))
                .containsEntry("min_expected_r", 0.2)
                .containsEntry("ev_reason", "pass")
                .containsEntry("candidateContinuedToEv", true)
                .containsEntry("candidateContinuedToTqs", true)
                .containsEntry("dailyCapUsed", 0L)
                .containsEntry("dailyCapLimit", 1)
                .containsEntry("dailyCapRemaining", 1L)
                .containsEntry("dailyCapScope", "LIVE_AUTO_TRADE")
                .containsEntry("openMaxLoss", "12")
                .containsEntry("openMaxLossUsdt", "12")
                .containsEntry("openMaxLossCapUsdt", 1000.0)
                .containsEntry("candidateMaxLossUsdt", 0.25)
                .containsEntry("maxLossIfWrongUsdt", 0.25)
                .containsEntry("projectedOpenMaxLossUsdt", "12.25")
                .containsEntry("maxLossCapRemainingUsdt", "988")
                .containsEntry("intentCreated", true)
                .containsEntry("ocoPlanCreated", true)
                .containsEntry("orderSent", false)
                .containsEntry("orderAllowed", false)
                .containsEntry("gridMutationAllowed", false)
                .containsEntry("schedulerEnablementAllowed", false)
                .containsEntry("telegramSendAllowed", false)
                .containsEntry("livePolicyRelaxationAllowed", false)
                .containsEntry("suppressionReason", "SHADOW_MODE")
                .containsEntry("runtimeEvidencePolicyMode", "BLOCK")
                .containsEntry("selectedAction", "ENTRY_DEDUP_SHADOW_CANDIDATE_SNAPSHOT");
        assertThat(result.context().get("dailyCapSnapshot")).asString()
                .contains("scope=LIVE_AUTO_TRADE")
                .contains("liveUsed=0")
                .contains("liveLimit=1")
                .contains("liveRemaining=1");
        assertThat(result.context().get("maxLossSnapshot")).asString()
                .contains("open=12")
                .contains("cap=1000")
                .contains("candidate=0.25")
                .contains("projected=12.25")
                .contains("remaining=988");
        assertThat(result.context().get("duplicateCandidateHash")).asString().hasSize(24);
        assertThat(result.context().get("replayCandidateId")).asString().startsWith("edsr1_");
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
