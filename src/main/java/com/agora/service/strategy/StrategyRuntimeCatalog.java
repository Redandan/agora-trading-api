package com.agora.service.strategy;

import com.agora.model.BtStrategy;
import com.agora.service.trading.BtcDonchianShadowPolicy;
import com.agora.service.trading.BtcMeiDirectionalShadowPolicy;
import com.agora.service.tradingview.TradingViewDailyStrategyContract;
import com.agora.service.tradingview.TradingViewScoreBuyAutoExitStrategyContract;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for strategy runtime eligibility.
 *
 * <p>Database strategy rows remain research artifacts. An enabled database
 * flag alone cannot make a strategy executable. Every database strategy not
 * explicitly listed here is ARCHIVED and therefore cannot enter the runtime
 * evaluation path.</p>
 */
@Component
public class StrategyRuntimeCatalog {

    private final Map<String, StrategyRuntimeDefinition> definitions;

    public StrategyRuntimeCatalog() {
        Map<String, StrategyRuntimeDefinition> catalog = new LinkedHashMap<>();
        register(catalog, new StrategyRuntimeDefinition(
                TradingViewDailyStrategyContract.KEY,
                TradingViewDailyStrategyContract.CONTRACT_VERSION,
                TradingViewDailyStrategyContract.OWNER_ALIAS,
                TradingViewDailyStrategyContract.CURRENT_DATABASE_STRATEGY_ID,
                StrategyLifecycleMode.ARCHIVED,
                TradingViewDailyStrategyContract.SIGNAL_SYMBOL,
                TradingViewDailyStrategyContract.SIGNAL_INTERVAL,
                TradingViewDailyStrategyContract.SIGNAL_SOURCE,
                "Frozen owner 508 V1 entry and accumulation evidence; no longer evaluated"));
        register(catalog, new StrategyRuntimeDefinition(
                TradingViewScoreBuyAutoExitStrategyContract.KEY,
                TradingViewScoreBuyAutoExitStrategyContract.CONTRACT_VERSION,
                TradingViewScoreBuyAutoExitStrategyContract.OWNER_ALIAS,
                TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID,
                StrategyLifecycleMode.LIVE,
                TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL,
                TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL,
                TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SOURCE,
                "Owner 509 V2 LIVE; frozen score-buy entries; per-lot +5% net-profit exits"));
        register(catalog, new StrategyRuntimeDefinition(
                BtcDonchianShadowPolicy.POLICY_MODE,
                1,
                null,
                null,
                StrategyLifecycleMode.SHADOW,
                BtcDonchianShadowPolicy.SYMBOL,
                BtcDonchianShadowPolicy.INTERVAL,
                BtcDonchianShadowPolicy.SOURCE,
                "Frozen Donchian research lane; evidence only; no exchange adapter"));
        register(catalog, new StrategyRuntimeDefinition(
                BtcMeiDirectionalShadowPolicy.POLICY_MODE,
                1,
                null,
                null,
                StrategyLifecycleMode.SHADOW,
                BtcMeiDirectionalShadowPolicy.SYMBOL,
                BtcMeiDirectionalShadowPolicy.INTERVAL,
                BtcMeiDirectionalShadowPolicy.SOURCE,
                "Source-pinned directional MEI candidate; default OFF; evidence only; no exchange adapter"));
        definitions = Map.copyOf(catalog);
    }

    public List<StrategyRuntimeDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    public StrategyRuntimeDefinition require(String key) {
        StrategyRuntimeDefinition definition = definitions.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("Strategy is not registered for runtime use: " + key);
        }
        return definition;
    }

    public boolean isMode(String key, StrategyLifecycleMode mode) {
        StrategyRuntimeDefinition definition = definitions.get(key);
        return definition != null && definition.mode() == mode;
    }

    public StrategyLifecycleMode modeForDatabaseStrategy(Long strategyId) {
        if (strategyId != null
                && strategyId == TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID) {
            return StrategyLifecycleMode.LIVE;
        }
        return StrategyLifecycleMode.ARCHIVED;
    }

    public StrategyRuntimeDefinition describeDatabaseStrategy(BtStrategy strategy) {
        if (strategy != null
                && strategy.getId() != null
                && strategy.getId() == TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID) {
            return require(TradingViewScoreBuyAutoExitStrategyContract.KEY);
        }
        Long id = strategy == null ? null : strategy.getId();
        String type = strategy == null || strategy.getStrategyType() == null
                ? "UNKNOWN"
                : strategy.getStrategyType().trim().toUpperCase();
        String symbol = strategy == null ? null : strategy.getSymbols();
        String source = strategy == null ? null : strategy.getKlineSource();
        return new StrategyRuntimeDefinition(
                "ARCHIVED_DB_STRATEGY_" + (id == null ? "UNSAVED" : id),
                1,
                null,
                id,
                StrategyLifecycleMode.ARCHIVED,
                symbol,
                null,
                source,
                "Archived database strategy type=" + type + "; research/backtest only");
    }

    public void requireMode(String key, StrategyLifecycleMode expected) {
        StrategyRuntimeDefinition definition = require(key);
        if (definition.mode() != expected) {
            throw new IllegalStateException(
                    definition.versionedKey() + " mode is " + definition.mode()
                            + ", expected " + expected);
        }
    }

    private void register(Map<String, StrategyRuntimeDefinition> catalog,
                          StrategyRuntimeDefinition definition) {
        if (catalog.putIfAbsent(definition.key(), definition) != null) {
            throw new IllegalStateException("Duplicate strategy runtime key: " + definition.key());
        }
    }
}
