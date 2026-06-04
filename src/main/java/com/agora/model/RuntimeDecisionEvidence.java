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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "bt_runtime_decision_evidence", indexes = {
        @Index(name = "idx_rt_decision_evidence_time", columnList = "evidence_time"),
        @Index(name = "idx_rt_decision_evidence_symbol_time", columnList = "symbol,evidence_time"),
        @Index(name = "idx_rt_decision_evidence_strategy_time", columnList = "strategy_id,evidence_time"),
        @Index(name = "idx_rt_decision_evidence_action_time", columnList = "selected_action,evidence_time")
})
public class RuntimeDecisionEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "decision_id", nullable = false, unique = true)
    private Long decisionId;

    @Column(name = "evidence_time", nullable = false)
    private LocalDateTime evidenceTime;

    @Column(length = 20)
    private String symbol;

    @Column(length = 16)
    private String side;

    @Column(name = "strategy_id")
    private Long strategyId;

    @Column(name = "interval_code", length = 10)
    private String intervalCode;

    @Column(name = "live_signal_id")
    private Long liveSignalId;

    @Column(name = "signal_source", length = 64)
    private String signalSource;

    @Column(name = "features_snapshot_json", columnDefinition = "JSON")
    private String featuresSnapshotJson;

    @Column(name = "freshness_state", length = 64)
    private String freshnessState;

    @Column(name = "blocker_reason", length = 255)
    private String blockerReason;

    @Column(name = "tqs_json", columnDefinition = "JSON")
    private String tqsJson;

    @Column(name = "policy_mode", length = 64)
    private String policyMode;

    @Column(name = "policy_reason", length = 500)
    private String policyReason;

    @Column(name = "policy_inputs_json", columnDefinition = "JSON")
    private String policyInputsJson;

    @Column(name = "selected_action", nullable = false, length = 64)
    private String selectedAction;

    @Column(length = 500)
    private String reason;

    @Column(name = "exposure_snapshot_json", columnDefinition = "JSON")
    private String exposureSnapshotJson;

    @Column(name = "oco_order_list_id", length = 100)
    private String ocoOrderListId;

    @Column(name = "final_outcome", nullable = false, length = 64)
    private String finalOutcome = "PENDING";

    @Column(name = "score")
    private Double score;

    @Column(name = "threshold_value")
    private Double threshold;

    @Column(name = "decision", length = 64)
    private String decision;

    @Column(name = "warnings_json", columnDefinition = "JSON")
    private String warningsJson;

    @Column(name = "terminal_blocker", length = 128)
    private String terminalBlocker;

    @Column(name = "fear_greed_mode", length = 32)
    private String fearGreedMode;

    @Column(name = "ev_result_json", columnDefinition = "JSON")
    private String evResultJson;

    @Column(name = "tqs_result_json", columnDefinition = "JSON")
    private String tqsResultJson;

    @Column(name = "risk_gate_result_json", columnDefinition = "JSON")
    private String riskGateResultJson;

    @Column(name = "execution_preview_json", columnDefinition = "JSON")
    private String executionPreviewJson;

    @Column(name = "execution_mode", length = 32)
    private String executionMode;

    @Column(name = "order_sent")
    private Boolean orderSent;

    @Column(name = "suppression_reason", length = 128)
    private String suppressionReason;

    @Column(name = "intent_created")
    private Boolean intentCreated;

    @Column(name = "oco_plan_created")
    private Boolean ocoPlanCreated;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
