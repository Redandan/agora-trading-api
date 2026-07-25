package com.agora.service.strategy;

/**
 * Strategy lifecycle modes supported by the strategy-driven runtime.
 *
 * <p>Only {@link #LIVE} may ever reach an exchange order adapter.</p>
 */
public enum StrategyLifecycleMode {
    ARCHIVED(false, false),
    SHADOW(true, false),
    PAPER(true, false),
    LIVE(true, true);

    private final boolean evaluationAllowed;
    private final boolean exchangeOrderAllowed;

    StrategyLifecycleMode(boolean evaluationAllowed, boolean exchangeOrderAllowed) {
        this.evaluationAllowed = evaluationAllowed;
        this.exchangeOrderAllowed = exchangeOrderAllowed;
    }

    public boolean evaluationAllowed() {
        return evaluationAllowed;
    }

    public boolean exchangeOrderAllowed() {
        return exchangeOrderAllowed;
    }
}
