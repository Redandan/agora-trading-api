package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 注意力規則(Phase 1 極簡版,只 LOG_ONLY / NOTIFY)。
 *
 * <p>Predicate JSON(單層 AND):
 * <pre>
 * {
 *   "symbol": "BTCUSDT",        // optional
 *   "interval": "1h",           // optional
 *   "side": "LONG",             // optional
 *   "fg_gt": 80, "fg_lt": 20,   // fear_greed 區間
 *   "rsi_gt": 75, "rsi_lt": 30,
 *   "strategy_id_in": [7, 12]
 * }
 * </pre>
 *
 * <p>Phase 2 的 REQUIRE_REVIEW / BLOCK / ESCALATE 共用同一表,
 * {@code review_timeout_seconds / review_channel / fallback_action} 已預留。
 */
@Data
@Entity
@Table(name = "attention_rule", indexes = {
        @Index(name = "idx_ar_enabled", columnList = "enabled,expires_at")
})
public class AttentionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "predicate_json", nullable = false, columnDefinition = "JSON")
    private String predicateJson;

    /** Phase 1: LOG_ONLY / NOTIFY;Phase 2: REQUIRE_REVIEW / BLOCK / ESCALATE */
    @Column(nullable = false, length = 24)
    private String action;

    /** INFO / WARN / CRITICAL */
    @Column(nullable = false, length = 16)
    private String severity = "INFO";

    @Column(length = 500)
    private String description;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** null=永久 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "hit_count", nullable = false)
    private Integer hitCount = 0;

    @Column(name = "last_hit_at")
    private LocalDateTime lastHitAt;

    // ===== Phase 2 預留(Phase 1 不用,但建表就加,語義擴充無需 migration)=====

    @Column(name = "review_timeout_seconds")
    private Integer reviewTimeoutSeconds;

    /** TG / SLACK / MCP_QUEUE */
    @Column(name = "review_channel", length = 32)
    private String reviewChannel;

    @Column(name = "fallback_action", length = 24)
    private String fallbackAction;
}
