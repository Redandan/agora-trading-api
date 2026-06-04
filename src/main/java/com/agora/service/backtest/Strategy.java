package com.agora.service.backtest;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public interface Strategy {

    String getType();

    StrategySignal evaluate(StrategyContext context, Map<String, Object> config);

    /** Strategy-specific execution config defaults applied before backtest run. */
    default Map<String, Object> defaultExecutionConfig() {
        return Collections.emptyMap();
    }

    /**
     * #450 — Per-bar exit adjustment hook for hold-state position management.
     *
     * <p>呼叫時機:
     * <ul>
     *   <li>Backtest: 每根 bar(在 SL/TP trigger check 之前)</li>
     *   <li>Live (Phase 2): {@code PositionExitManagerScheduler} 每分鐘 sweep open positions</li>
     * </ul>
     *
     * <p>實作合約:
     * <ul>
     *   <li>讀 ctx / pos / config,**不可 mutate** 任一</li>
     *   <li>**No side effects**:不寫 DB / 不發 TG / 不 logging error</li>
     *   <li>Throw 視為 no-op(caller catch + audit)</li>
     *   <li>Return {@link Optional#empty()} = 用 entry 時 OCO,不調(default 行為)</li>
     *   <li>Return {@link ExitAdjustment#forceClose(String, String)} = 立即 market exit</li>
     *   <li>Return {@link ExitAdjustment#tightenTp / trailingSl / tighten} = 改 OCO</li>
     * </ul>
     *
     * <p>Default no-op:既有 strategy 不需改,backward compatible。
     */
    default Optional<ExitAdjustment> adjustExit(
            StrategyContext context,
            OpenPositionView position,
            Map<String, Object> config) {
        return Optional.empty();
    }
}
