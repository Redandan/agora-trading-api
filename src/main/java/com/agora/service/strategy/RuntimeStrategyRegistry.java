package com.agora.service.strategy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed binding between catalog contracts and executable strategy lanes.
 */
@Component
public class RuntimeStrategyRegistry {

    private final List<RuntimeStrategy> evaluationOrder;

    public RuntimeStrategyRegistry(
            List<RuntimeStrategy> strategies,
            StrategyRuntimeCatalog catalog) {
        Map<String, RuntimeStrategy> byKey = new LinkedHashMap<>();
        for (RuntimeStrategy strategy : strategies) {
            if (strategy == null || strategy.key() == null || strategy.key().isBlank()) {
                throw new IllegalStateException("Runtime strategy key is required");
            }
            if (strategy.evaluationOrder() < 0) {
                throw new IllegalStateException(
                        "Runtime strategy order must be non-negative: " + strategy.key());
            }
            catalog.require(strategy.key());
            if (byKey.putIfAbsent(strategy.key(), strategy) != null) {
                throw new IllegalStateException(
                        "Duplicate runtime strategy implementation: " + strategy.key());
            }
        }

        Set<String> expected = new LinkedHashSet<>();
        for (StrategyRuntimeDefinition definition : catalog.definitions()) {
            if (definition.mode().evaluationAllowed()) {
                expected.add(definition.key());
            }
        }
        Set<String> actual = new LinkedHashSet<>(byKey.keySet());
        if (!expected.equals(actual)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalStateException(
                    "Runtime strategy registry/catalog mismatch missing=" + missing
                            + " unexpected=" + unexpected);
        }

        List<RuntimeStrategy> ordered = new ArrayList<>(byKey.values());
        ordered.sort(Comparator
                .comparingInt(RuntimeStrategy::evaluationOrder)
                .thenComparing(RuntimeStrategy::key));
        evaluationOrder = List.copyOf(ordered);
    }

    public List<RuntimeStrategy> evaluationOrder() {
        return evaluationOrder;
    }
}
