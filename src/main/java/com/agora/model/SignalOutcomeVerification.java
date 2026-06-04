package com.agora.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "signal_outcome_verification")
@Getter @Setter
public class SignalOutcomeVerification {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "live_signal_id", nullable = false, unique = true)
    private Long liveSignalId;

    @Column(length = 20, nullable = false)
    private String symbol;

    @Column(name = "interval_code", length = 10, nullable = false)
    private String intervalCode;

    @Column(length = 10, nullable = false)
    private String side;

    @Column(length = 20, nullable = false)
    private String decision;

    @Column(name = "decision_layer", length = 64, nullable = false)
    private String decisionLayer;

    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "sl_price", precision = 20, scale = 8)
    private BigDecimal slPrice;

    @Column(name = "tp_price", precision = 20, scale = 8)
    private BigDecimal tpPrice;

    @Column(length = 16, nullable = false)
    private String outcome = "WATCHING";

    @Column(name = "last_price", precision = 20, scale = 8)
    private BigDecimal lastPrice;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
