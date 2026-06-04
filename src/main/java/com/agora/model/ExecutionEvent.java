package com.agora.model;

import com.agora.enums.trading.ExecutionActionBoundary;
import com.agora.enums.trading.ExecutionEventSeverity;
import com.agora.enums.trading.ExecutionEventSource;
import com.agora.enums.trading.ExecutionEventStatus;
import com.agora.enums.trading.ExecutionEventType;
import com.agora.enums.trading.ExecutionRecommendation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "bt_execution_event", indexes = {
        @Index(name = "uk_bt_execution_event_fingerprint", columnList = "fingerprint", unique = true),
        @Index(name = "idx_bt_execution_event_active", columnList = "status,symbol,detected_at"),
        @Index(name = "idx_bt_execution_event_position", columnList = "position_id,status,detected_at"),
        @Index(name = "idx_bt_execution_event_type", columnList = "event_type,status,detected_at")
})
public class ExecutionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExecutionEventSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 48)
    private ExecutionEventType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExecutionEventSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExecutionRecommendation recommendation;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_boundary", nullable = false, length = 32)
    private ExecutionActionBoundary actionBoundary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExecutionEventStatus status = ExecutionEventStatus.ACTIVE;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "strategy_id")
    private Long strategyId;

    @Column(name = "interval_code", length = 10)
    private String intervalCode;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 1000)
    private String summary;

    @Column(name = "evidence_json", columnDefinition = "JSON")
    private String evidenceJson;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (detectedAt == null) detectedAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = ExecutionEventStatus.ACTIVE;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
