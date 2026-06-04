package com.agora.service.indicator;

import com.agora.model.IndicatorAlertState;
import com.agora.repository.trading.IndicatorAlertStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * #404 — Hysteresis state machine for {@link CompositeIndicator} alerts.
 *
 * <p>Replaces the simple cooldown-timer approach in
 * {@link com.agora.scheduler.trading.CompositeIndicatorScheduler} that produced the
 * "MEI=70 hover → 22 identical TG / 24h" bug. Decisions now follow state
 * transitions:
 *
 * <ul>
 *   <li>{@link Decision#ENTER} — score crossed up into the warning band</li>
 *   <li>{@link Decision#REMINDER} — stayed elevated longer than the reminder
 *       window (default 12h) since the last fire</li>
 *   <li>{@link Decision#EXIT} — score crossed down below the exit score
 *       (default = alert threshold)</li>
 *   <li>{@link Decision#SUPPRESS} — hovering inside the dead band, or
 *       hovering elevated below the reminder cadence</li>
 * </ul>
 *
 * <p><b>Hysteresis dead zone</b>: once ELEVATED, the score must drop below
 * {@link CompositeIndicator#getElevatedExitScore()} (NOT just below
 * {@code warningThreshold}) before EXIT fires. This is the whole point —
 * avoiding the "score=warningThreshold-1 then warningThreshold+1 then
 * warningThreshold-1…" oscillation that simple cooldowns cannot suppress.
 *
 * <p><b>Persistence</b>: state lives in {@code indicator_alert_state} so a
 * deploy mid-elevated phase resumes from the same state instead of misfiring
 * a stale ENTER on the first post-restart evaluate.
 *
 * <p><b>Severity is orthogonal</b>: this guard only tracks "above warning
 * band or not". The CRITICAL vs WARNING distinction is conveyed by
 * {@link CompositeResult#level()} in the formatted message; the scheduler
 * is responsible for applying CRITICAL-specific behaviour (e.g. bypassing
 * sustained / directional filters).
 */
@Slf4j
@Component
public class HysteresisAlertGuard {

    public enum Decision { ENTER, REMINDER, EXIT, SUPPRESS }

    static final String STATE_NORMAL   = "NORMAL";
    static final String STATE_ELEVATED = "ELEVATED";

    private final IndicatorAlertStateRepository repo;

    public HysteresisAlertGuard(IndicatorAlertStateRepository repo) {
        this.repo = repo;
    }

    /**
     * Evaluate the next state transition for an indicator and persist it.
     *
     * @param indicator source indicator (provides thresholds + reminder cadence)
     * @param score     latest computed score
     * @param now       evaluation timestamp (UTC)
     * @return the alert decision the caller must act on
     */
    public Decision evaluate(CompositeIndicator indicator, int score, LocalDateTime now) {
        return evaluateBoolean(indicator.getName(), score >= indicator.getWarningThreshold(),
                score < indicator.getElevatedExitScore(),
                indicator.getReminderHours(), score, now);
    }

    /**
     * #428 — Boolean-predicate hysteresis for non-score sources (e.g. attention
     * rules where we only know "predicate matched" / "predicate did not match",
     * not a numeric score with a dead-zone). Caller supplies a stable state key
     * (e.g. {@code "attn_rule_42"}) and the truth values for the warning band.
     *
     * @param stateKey         row id in {@code indicator_alert_state}
     * @param crossingUpCond   true ⇒ predicate matches now (eligible to ENTER)
     * @param crossingDownCond true ⇒ predicate clearly out (eligible to EXIT).
     *                         For boolean predicates this is just {@code !crossingUpCond}.
     * @param reminderHours    cadence for REMINDER while elevated
     */
    public Decision evaluateBoolean(String stateKey,
                                    boolean crossingUpCond,
                                    boolean crossingDownCond,
                                    long reminderHours,
                                    Integer scoreForAudit,
                                    LocalDateTime now) {
        IndicatorAlertState s = repo.findById(stateKey).orElseGet(() -> {
            IndicatorAlertState fresh = new IndicatorAlertState();
            fresh.setIndicatorName(stateKey);
            fresh.setState(STATE_NORMAL);
            fresh.setUpdatedAt(now);
            return fresh;
        });

        boolean wasElevated = STATE_ELEVATED.equals(s.getState());
        Decision decision;
        boolean nowElevated = wasElevated;

        if (!wasElevated && crossingUpCond) {
            decision = Decision.ENTER;
            nowElevated = true;
            s.setEnteredAt(now);
            s.setLastFiredAt(now);
        } else if (wasElevated && crossingDownCond) {
            decision = Decision.EXIT;
            nowElevated = false;
            s.setEnteredAt(null);
            s.setLastFiredAt(now);
        } else if (wasElevated) {
            LocalDateTime last = s.getLastFiredAt();
            if (last != null
                    && Duration.between(last, now).toHours() >= reminderHours) {
                decision = Decision.REMINDER;
                s.setLastFiredAt(now);
            } else {
                decision = Decision.SUPPRESS;
            }
        } else {
            decision = Decision.SUPPRESS;
        }

        s.setState(nowElevated ? STATE_ELEVATED : STATE_NORMAL);
        if (scoreForAudit != null) s.setLastScore(scoreForAudit);
        s.setUpdatedAt(now);
        try {
            repo.save(s);
        } catch (Exception e) {
            log.warn("[HysteresisAlertGuard] save failed key={} decision={} err={}",
                    stateKey, decision, e.getMessage());
        }
        return decision;
    }
}
