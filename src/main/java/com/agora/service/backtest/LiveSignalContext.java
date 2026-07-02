package com.agora.service.backtest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-local carrier that lets ScoreBuyStrategy expose its computed
 * score / nnOutput / rsi to LiveSignalEvaluator without changing the
 * Strategy.evaluate() return type.
 *
 * <p>Usage pattern:
 * <pre>
 *   LiveSignalContext.clear();                          // before evaluate()
 *   StrategySignal signal = impl.evaluate(context, cfg);
 *   LiveSignalContext.Snapshot snap = LiveSignalContext.get(); // after
 *   Map&lt;String, Object&gt; details = LiveSignalContext.getDetails(); // #398
 * </pre>
 *
 * <p>#398 — strategies may also publish trigger-condition details
 * via {@link #putDetail(String, Object)} at any point during evaluate().
 * LiveSignalEvaluator merges them into the SIGNAL_EVAL audit context_json
 * so HOLD audits surface "why HOLD" without needing to re-read strategy code.
 */
public final class LiveSignalContext {

    public static final class Snapshot {
        public final double score;
        public final double nnOutput;
        public final double rsi;

        Snapshot(double score, double nnOutput, double rsi) {
            this.score    = score;
            this.nnOutput = nnOutput;
            this.rsi      = rsi;
        }
    }

    /**
     * Optional per-bar order intent emitted by strategies that need more detail
     * than the legacy BUY/HOLD/SELL enum can carry.
     */
    public record OrderIntent(String reason, String label, double quantity) {
    }

    private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();
    /** #398 — per-strategy trigger-condition snapshot (mih_indicator value, gate pass/fail, etc.). */
    private static final ThreadLocal<Map<String, Object>> DETAILS = new ThreadLocal<>();
    private static final ThreadLocal<List<OrderIntent>> ORDER_INTENTS = new ThreadLocal<>();

    private LiveSignalContext() {}

    /** Called by ScoreBuyStrategy right before returning any signal. */
    static void set(double score, double nnOutput, double rsi) {
        HOLDER.set(new Snapshot(score, nnOutput, rsi));
    }

    /**
     * Returns the snapshot populated by the last evaluate() on this thread,
     * or {@code null} if the strategy did not publish (e.g. warmup / other strategy).
     */
    public static Snapshot get() {
        return HOLDER.get();
    }

    /**
     * #398 — strategies record a trigger-condition data point.
     * Values must be JSON-serialisable scalars (Number / String / Boolean) — same
     * contract as DecisionAuditWriter context. Use small key names; this map is
     * stored verbatim into bt_decision_audit.context_json under "strategy_decision".
     *
     * <p>Common keys (non-exhaustive — strategies may add their own):
     * <ul>
     *   <li>{@code mih_indicator} — config-bound indicator name (CMI_MIH_THRESHOLD)</li>
     *   <li>{@code mih_value}     — current bar's indicator value</li>
     *   <li>{@code threshold}     — buy_threshold / sell_threshold being checked</li>
     *   <li>{@code hold_reason}   — short label why HOLD ("below_threshold",
     *       "regime_block", "funding_not_improving", "creating_new_low", ...)</li>
     *   <li>{@code passed_gates}  — comma-joined list of gates passed</li>
     * </ul>
     */
    public static void putDetail(String key, Object value) {
        Map<String, Object> map = DETAILS.get();
        if (map == null) {
            map = new LinkedHashMap<>();
            DETAILS.set(map);
        }
        map.put(key, value);
    }

    public static void addOrderIntent(String reason, String label, double quantity) {
        List<OrderIntent> list = ORDER_INTENTS.get();
        if (list == null) {
            list = new ArrayList<>();
            ORDER_INTENTS.set(list);
        }
        list.add(new OrderIntent(reason, label, quantity));
    }

    public static List<OrderIntent> getOrderIntents() {
        List<OrderIntent> list = ORDER_INTENTS.get();
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(list);
    }

    /** #398 — returns the detail map (never null after first putDetail; null if untouched). */
    public static Map<String, Object> getDetails() {
        return DETAILS.get();
    }

    /** Must be called before each evaluate() to avoid stale data from prior calls. */
    public static void clear() {
        HOLDER.remove();
        DETAILS.remove();
        ORDER_INTENTS.remove();
    }
}
