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

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "bt_tiny_live_execution_audit", indexes = {
        @Index(name = "idx_tiny_live_exec_created", columnList = "created_at"),
        @Index(name = "idx_tiny_live_exec_symbol_strategy", columnList = "symbol,strategy_id,created_at"),
        @Index(name = "idx_tiny_live_exec_status", columnList = "status,created_at")
})
public class TinyLiveExecutionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(nullable = false, length = 64)
    private String status;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(nullable = false, length = 16)
    private String side;

    @Column(name = "preview_token_id", length = 80)
    private String previewTokenId;

    @Column(name = "preview_hash", nullable = false, length = 128)
    private String previewHash;

    @Column(name = "approval_token_hash", nullable = false, unique = true, length = 128)
    private String approvalTokenHash;

    @Column(name = "approval_mode", length = 64)
    private String approvalMode;

    @Column(name = "approval_token_id", length = 100)
    private String approvalTokenId;

    @Column(name = "approval_token_type", length = 40)
    private String approvalTokenType;

    @Column(name = "auto_approval_policy_version", length = 64)
    private String autoApprovalPolicyVersion;

    @Column(name = "event_risk_override_used", nullable = false)
    private Boolean eventRiskOverrideUsed = false;

    @Column(name = "human_reason", length = 500)
    private String humanReason;

    @Column(name = "denial_reason", length = 500)
    private String denialReason;

    @Column(name = "order_id", length = 100)
    private String orderId;

    @Column(name = "oco_algo_id")
    private Long ocoAlgoId;

    @Column(name = "notional_usdt", precision = 20, scale = 8)
    private BigDecimal notionalUsdt;

    @Column(precision = 20, scale = 8)
    private BigDecimal qty;

    @Column(name = "entry_price", precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "tp_price", precision = 20, scale = 8)
    private BigDecimal tpPrice;

    @Column(name = "sl_price", precision = 20, scale = 8)
    private BigDecimal slPrice;

    @Column(name = "max_loss_usdt", precision = 20, scale = 8)
    private BigDecimal maxLossUsdt;

    @Column(name = "policy_mode", length = 64)
    private String policyMode;

    @Column(name = "tqs_band", length = 64)
    private String tqsBand;

    @Column(name = "expected_r", precision = 20, scale = 8)
    private BigDecimal expectedR;

    @Column(name = "order_sent", nullable = false)
    private Boolean orderSent = false;

    @Column(name = "oco_attached", nullable = false)
    private Boolean ocoAttached = false;

    @Column(name = "live_signal_id")
    private Long liveSignalId;

    @Column(name = "runtime_evidence_id")
    private Long runtimeEvidenceId;

    @Column(name = "decision_audit_id")
    private Long decisionAuditId;

    @Column(name = "receipt_json", columnDefinition = "JSON")
    private String receiptJson;

    @Column(name = "warnings_json", columnDefinition = "JSON")
    private String warningsJson;

    @Column(name = "blockers_json", columnDefinition = "JSON")
    private String blockersJson;
}
