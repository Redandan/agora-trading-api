package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * #404 — Per-indicator hysteresis state.
 *
 * <p>Survives deploys so the {@link com.agora.scheduler.trading.CompositeIndicatorScheduler}
 * can tell ENTER (NORMAL→ELEVATED) from REMINDER (still ELEVATED, 12h since
 * last fire) without re-firing a stale "entering" alert on every restart.
 *
 * <p>Two states only: {@code NORMAL} and {@code ELEVATED}. Severity (WARNING
 * vs CRITICAL) lives in {@link CompositeResult#level()}; this row only tracks
 * "are we currently above the warning band?".
 */
@Entity
@Table(name = "indicator_alert_state")
@Data
@NoArgsConstructor
public class IndicatorAlertState {

    @Id
    @Column(name = "indicator_name", length = 64, nullable = false)
    private String indicatorName;

    @Column(name = "state", length = 16, nullable = false)
    private String state;            // NORMAL / ELEVATED

    @Column(name = "entered_at")
    private LocalDateTime enteredAt;

    @Column(name = "last_fired_at")
    private LocalDateTime lastFiredAt;

    @Column(name = "last_score")
    private Integer lastScore;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
