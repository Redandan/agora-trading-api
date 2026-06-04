package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 手動 hint,優先於 gemini_market_hint(per-field 覆蓋)。
 *
 * <p>null 欄位不覆蓋:例如 Claude 只想改 allow_short,其他留空,則最終生效 hint 的
 * 其他欄位仍取自 Gemini(若有)或 strategy config 預設。
 *
 * <p>{@code styleHint == "DISABLE"} 為 kill switch,evaluate() 會短路 return。
 *
 * <p>Wrapper 型別(Boolean / BigDecimal)必要 — 明確區分「未設定」vs「false / 0」。
 */
@Data
@Entity
@Table(name = "hint_override", indexes = {
        @Index(name = "idx_ho_active", columnList = "symbol,timeframe,expires_at,revoked_at")
})
public class HintOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String timeframe;

    /** TREND / HIGH_FREQ / CONSERVATIVE / DISABLE / null(不覆蓋) */
    @Column(name = "style_hint", length = 20)
    private String styleHint;

    @Column(length = 30)
    private String regime;

    @Column(name = "adx_adjust", precision = 5, scale = 2)
    private BigDecimal adxAdjust;

    @Column(name = "sl_multiplier", precision = 6, scale = 3)
    private BigDecimal slMultiplier;

    @Column(name = "tp_multiplier", precision = 6, scale = 3)
    private BigDecimal tpMultiplier;

    /** null=不覆蓋(Gemini hint 的 allow_short 繼續生效) */
    @Column(name = "allow_short")
    private Boolean allowShort;

    /** 100 = Claude 注入預設;> gemini 隱含 0 */
    @Column(nullable = false)
    private Short priority = 100;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Phase 1 硬上限 6h */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public boolean isActive(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
