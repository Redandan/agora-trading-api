package com.agora.service.backtest;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class StrategyRegistry {

    private final Map<String, Strategy> strategyMap = new HashMap<String, Strategy>();

    public StrategyRegistry(List<Strategy> strategies) {
        for (Strategy strategy : strategies) {
            strategyMap.put(strategy.getType().toUpperCase(), strategy);
        }
    }

    public Strategy getRequiredStrategy(String strategyType) {
        if (strategyType == null) {
            throw new IllegalArgumentException("strategyType 不可為空");
        }
        Strategy strategy = strategyMap.get(strategyType.toUpperCase());
        if (strategy == null) {
            throw new IllegalArgumentException("不支援的 strategyType: " + strategyType);
        }
        return strategy;
    }
}
