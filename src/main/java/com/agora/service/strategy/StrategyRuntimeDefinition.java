package com.agora.service.strategy;

/**
 * Immutable, versioned runtime contract for one strategy lane.
 */
public record StrategyRuntimeDefinition(
        String key,
        int version,
        String ownerAlias,
        Long databaseStrategyId,
        StrategyLifecycleMode mode,
        String symbol,
        String interval,
        String source,
        String description
) {
    public StrategyRuntimeDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("strategy key is required");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("strategy version must be positive");
        }
        if (mode == null) {
            throw new IllegalArgumentException("strategy lifecycle mode is required");
        }
    }

    public String versionedKey() {
        return key + "@v" + version;
    }
}
