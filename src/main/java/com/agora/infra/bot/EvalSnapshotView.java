package com.agora.infra.bot;

/**
 * Bot-side projection of MarketSignalCache.EvalSnapshot. Carries only the
 * fields that {@code /diag} actually renders — keeps the internal cache type
 * out of {@code com.agora.bot}.
 */
public record EvalSnapshotView(
        String symbol,
        String intervalCode,
        String signal,
        double nnOutput,
        double rsi
) {}
