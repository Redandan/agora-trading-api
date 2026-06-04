package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Claude(或 ops)對策略的硬性 TTL 覆蓋。
 *
 * <p>LiveSignalEvaluator 在 evaluateStrategy() 入口查 findActive(strategy, now),
 * 若有 PAUSE 則直接 return(寫 FILTER_BLOCK audit);TWEAK 則 merge config_patch 入 strategy config。
 *
 * <p>過期或被 revoke 的 override 仍保留在表內供 audit,查詢時以
 * {@code expires_at > now AND revoked_at IS NULL} 過濾。
 */
@Data
@Entity
@Table(name = "strategy_override", indexes = {
        @Index(name = "idx_ov_active", columnList = "strategy_id,expires_at,revoked_at"),
        @Index(name = "idx_ov_symbol", columnList = "symbol,expires_at")
})
public class StrategyOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    /** null=此策略所有 symbols */
    @Column(length = 20)
    private String symbol;

    /** null=所有週期 */
    @Column(name = "interval_code", length = 10)
    private String intervalCode;

    /** PAUSE / TWEAK(Phase 2: QUARANTINE / RISK_DOWN) */
    @Column(nullable = false, length = 16)
    private String action;

    /** TWEAK 時的 config 覆蓋(JSON 字串),deep merge 入 strategy config */
    @Column(name = "config_patch", columnDefinition = "JSON")
    private String configPatch;

    @Column(nullable = false, length = 500)
    private String reason;

    /** claude / ops / manual */
    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Phase 1 硬上限 24h */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** Convenience: 是否為當前有效 override。 */
    public boolean isActive(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
