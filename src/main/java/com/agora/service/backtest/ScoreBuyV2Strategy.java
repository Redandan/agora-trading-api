package com.agora.service.backtest;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Compatibility type for the owner 508 database row.
 *
 * <p>The former ML branch was removed. SCORE_BUY_V2 now always delegates to the
 * frozen Pine-parity implementation so a database config flag cannot revive a
 * second decision engine.</p>
 */
@Component
@RequiredArgsConstructor
public class ScoreBuyV2Strategy implements Strategy {

    public static final String TYPE = "SCORE_BUY_V2";

    private final ScoreBuyStrategy tradingViewStrategy;

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> defaultExecutionConfig() {
        return tradingViewStrategy.defaultExecutionConfig();
    }

    @Override
    public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
        return tradingViewStrategy.evaluate(context, config);
    }
}
