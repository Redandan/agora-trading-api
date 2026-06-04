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

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "bt_tiny_live_event_risk_override_token", indexes = {
        @Index(name = "idx_tiny_live_event_override_token_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_tiny_live_event_override_created", columnList = "created_at"),
        @Index(name = "idx_tiny_live_event_override_scope", columnList = "symbol,strategy_id,created_at")
})
public class TinyLiveEventRiskOverrideToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "token_id", nullable = false, length = 80)
    private String tokenId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(nullable = false, length = 16)
    private String side;

    @Column(name = "notional_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal notionalUsdt;

    @Column(name = "preview_hash", nullable = false, length = 128)
    private String previewHash;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;
}
