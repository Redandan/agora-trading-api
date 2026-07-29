package com.agora.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Durable mechanical state for one spot provider submission attempt.
 *
 * <p>This is execution infrastructure, not strategy authority. Creating a row
 * does not authorize an order; only an atomic {@code RESERVED -> SUBMITTING}
 * claim may elect a provider caller.</p>
 */
@Data
@Entity
@Table(
        name = "bt_spot_execution_attempt",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_spot_exec_attempt_sequence",
                    columnNames = {
                        "live_signal_id",
                        "side",
                        "attempt_sequence"
                    }),
            @UniqueConstraint(
                    name = "uk_spot_exec_attempt_client_order",
                    columnNames = "client_order_id"),
            @UniqueConstraint(
                    name = "uk_spot_exec_attempt_provider_order",
                    columnNames = {"provider", "provider_order_id"})
        },
        indexes = {
            @Index(
                    name = "idx_spot_exec_attempt_state_updated",
                    columnList = "state,updated_at"),
            @Index(
                    name = "idx_spot_exec_attempt_live_signal",
                    columnList = "live_signal_id,created_at")
        })
public class SpotExecutionAttempt {

    public enum Side {
        BUY,
        SELL
    }

    public enum State {
        RESERVED,
        SUBMITTING,
        SUBMISSION_UNKNOWN,
        PROVIDER_ACCEPTED,
        RECONCILED_FILLED,
        RECONCILED_PARTIAL,
        REJECTED
    }

    public enum FeeReconciliationStatus {
        NOT_APPLICABLE,
        PENDING,
        RECONCILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "live_signal_id", nullable = false)
    private Long liveSignalId;

    @Column(name = "strategy_contract", nullable = false, length = 96)
    private String strategyContract;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Side side;

    @Column(name = "attempt_sequence", nullable = false)
    private Integer attemptSequence;

    @Column(name = "signal_bar_open_time", nullable = false)
    private LocalDateTime signalBarOpenTime;

    @Column(name = "trigger_bar_open_time", nullable = false)
    private LocalDateTime triggerBarOpenTime;

    @Column(name = "client_order_id", nullable = false, length = 32)
    private String clientOrderId;

    @Column(nullable = false, length = 16)
    private String provider;

    @Column(name = "provider_order_id", length = 128)
    private String providerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private State state;

    @Column(name = "requested_quote_amount", precision = 30, scale = 12)
    private BigDecimal requestedQuoteAmount;

    @Column(name = "requested_base_quantity", precision = 30, scale = 12)
    private BigDecimal requestedBaseQuantity;

    @Column(name = "average_price", precision = 30, scale = 12)
    private BigDecimal averagePrice;

    @Column(name = "gross_fill_quantity", precision = 30, scale = 12)
    private BigDecimal grossFillQuantity;

    @Column(name = "net_fill_quantity", precision = 30, scale = 12)
    private BigDecimal netFillQuantity;

    @Column(name = "signed_fee_amount", precision = 30, scale = 12)
    private BigDecimal signedFeeAmount;

    @Column(name = "fee_currency", length = 20)
    private String feeCurrency;

    @Column(name = "fee_usdt", precision = 30, scale = 12)
    private BigDecimal feeUsdt;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "fee_reconciliation_status",
            nullable = false,
            length = 24)
    private FeeReconciliationStatus feeReconciliationStatus;

    @Column(
            name = "applied_fill_quantity",
            nullable = false,
            precision = 30,
            scale = 12)
    private BigDecimal appliedFillQuantity = BigDecimal.ZERO;

    @Column(name = "remaining_lot_quantity", precision = 30, scale = 12)
    private BigDecimal remainingLotQuantity;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "provider_accepted_at")
    private LocalDateTime providerAcceptedAt;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    @Column(name = "last_reconciliation_error", length = 500)
    private String lastReconciliationError;

    @Column(name = "provider_receipt_json", columnDefinition = "JSON")
    private String providerReceiptJson;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
