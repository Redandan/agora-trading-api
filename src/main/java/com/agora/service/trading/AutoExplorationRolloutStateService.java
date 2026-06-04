package com.agora.service.trading;

import com.agora.model.AutoExplorationRolloutTransition;
import com.agora.repository.trading.AutoExplorationRolloutTransitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AutoExplorationRolloutStateService {

    public static final String STAGE_DISABLED = "DISABLED";
    public static final String STAGE_LOOP_DRY_RUN = "LOOP_DRY_RUN";
    public static final String STAGE_PRODUCTION_TINY_LIVE_1_PER_DAY = "PRODUCTION_TINY_LIVE_1_PER_DAY";
    public static final String STAGE_PRODUCTION_TINY_LIVE_2_PER_DAY = "PRODUCTION_TINY_LIVE_2_PER_DAY";
    public static final String STAGE_HALTED = "HALTED";

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 574L;
    private static final String DEFAULT_SIDE = "LONG";

    private final AutoExplorationRolloutTransitionRepository transitionRepository;
    private final Environment env;

    @Transactional(readOnly = true)
    public String currentStage(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        return transitionRepository.findFirstBySymbolAndStrategyIdAndSideOrderByGeneratedAtDesc(sym, sid, normalizedSide)
                .map(AutoExplorationRolloutTransition::getCurrentStage)
                .orElse(STAGE_DISABLED);
    }

    @Transactional(readOnly = true)
    public boolean effectiveLoopEnabled(String symbol, Long strategyId, String side) {
        if (Boolean.parseBoolean(env.getProperty("trading.exploration.loop.enabled", "false"))) {
            return true;
        }
        if (!autoEnabled()) {
            return false;
        }
        String stage = currentStage(symbol, strategyId, side);
        return STAGE_LOOP_DRY_RUN.equals(stage)
                || STAGE_PRODUCTION_TINY_LIVE_1_PER_DAY.equals(stage)
                || STAGE_PRODUCTION_TINY_LIVE_2_PER_DAY.equals(stage);
    }

    @Transactional(readOnly = true)
    public boolean effectiveProductionEnabled(String symbol, Long strategyId, String side) {
        if (Boolean.parseBoolean(env.getProperty("trading.exploration.loop.production-enabled", "false"))) {
            return true;
        }
        if (!autoEnabled() || !allowProductionPromotion()) {
            return false;
        }
        String stage = currentStage(symbol, strategyId, side);
        return STAGE_PRODUCTION_TINY_LIVE_1_PER_DAY.equals(stage)
                || STAGE_PRODUCTION_TINY_LIVE_2_PER_DAY.equals(stage);
    }

    @Transactional(readOnly = true)
    public long effectiveMaxOrdersPerDay(String symbol, Long strategyId, String side) {
        long configured = longProperty("trading.exploration.loop.max-orders-per-day", 1L);
        if (configured > 1L) {
            return configured;
        }
        if (!autoEnabled() || !allowCapIncrease()) {
            return configured;
        }
        return STAGE_PRODUCTION_TINY_LIVE_2_PER_DAY.equals(currentStage(symbol, strategyId, side)) ? 2L : configured;
    }

    public boolean autoEnabled() {
        return Boolean.parseBoolean(env.getProperty("trading.exploration.rollout.auto-enabled", "false"));
    }

    public boolean allowProductionPromotion() {
        return Boolean.parseBoolean(env.getProperty("trading.exploration.rollout.allow-production-promotion", "false"));
    }

    public boolean allowCapIncrease() {
        return Boolean.parseBoolean(env.getProperty("trading.exploration.rollout.allow-cap-increase", "false"));
    }

    private long longProperty(String key, long fallback) {
        try {
            return Long.parseLong(env.getProperty(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) return DEFAULT_SIDE;
        String upper = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(upper) ? DEFAULT_SIDE : upper;
    }
}
