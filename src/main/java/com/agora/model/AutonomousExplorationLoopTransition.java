package com.agora.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "bt_autonomous_exploration_loop_transition", indexes = {
        @Index(name = "idx_auto_explore_loop_generated", columnList = "generated_at"),
        @Index(name = "idx_auto_explore_loop_scope_generated", columnList = "symbol,strategy_id,side,generated_at"),
        @Index(name = "idx_auto_explore_loop_state_generated", columnList = "state,generated_at")
})
public class AutonomousExplorationLoopTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(nullable = false, length = 16)
    private String side;

    @Column(nullable = false, length = 64)
    private String state;

    @Column(name = "previous_state", length = 64)
    private String previousState;

    @Column(length = 500)
    private String reason;

    @Column(name = "blockers_json", columnDefinition = "JSON")
    private String blockersJson;

    @Column(name = "warnings_json", columnDefinition = "JSON")
    private String warningsJson;

    @Column(name = "decision_id")
    private Long decisionId;

    @Column(name = "tiny_live_execution_id")
    private Long tinyLiveExecutionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
