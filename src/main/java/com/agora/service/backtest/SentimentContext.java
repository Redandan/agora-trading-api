package com.agora.service.backtest;

/**
 * Thread-local carrier for external sentiment data (Fear & Greed + whale flow).
 *
 * <p>Set by LiveSignalEvaluator BEFORE calling strategy.evaluate().
 * Read by ScoreBuyStrategy to add sentiment bonus to the score.
 * Never set during backtest → sentiment bonus is always 0 in historical simulation.</p>
 */
public final class SentimentContext {

    public static final class Snapshot {
        /** 0 (Extreme Greed) ~ 100 (Extreme Fear). Lower = more bullish market sentiment. */
        public final int fearGreedValue;
        /** Fraction of large-order volume that are taker buys (0.0 ~ 1.0). */
        public final double whaleBuyRatio;

        Snapshot(int fearGreedValue, double whaleBuyRatio) {
            this.fearGreedValue = fearGreedValue;
            this.whaleBuyRatio  = whaleBuyRatio;
        }
    }

    private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();

    private SentimentContext() {}

    public static void set(int fearGreedValue, double whaleBuyRatio) {
        HOLDER.set(new Snapshot(fearGreedValue, whaleBuyRatio));
    }

    public static Snapshot get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
