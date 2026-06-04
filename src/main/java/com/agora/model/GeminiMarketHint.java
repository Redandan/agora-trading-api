package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Gemini Market Advisor 給出的市場形態 hint,strategy 動態調參用。
 * 每筆代表一次 advisor 跑(對某個 symbol+timeframe),3 個 persona 投票後的綜合結果。
 *
 * <p>Strategy 在 evaluate() 開頭查 {@code findActiveHint},若 confidence ≥ 門檻則套用調整。
 *
 * <p>Hint 透過 {@link #expiresAt} 控制有效期(預設 created_at + 5h),
 * 過期或不存在 → strategy 用原 config 預設。
 */
@Data
@Entity
@Table(name = "gemini_market_hint", indexes = {
        @Index(name = "idx_gmh_symbol_tf_expires", columnList = "symbol,timeframe,expires_at")
})
public class GeminiMarketHint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String timeframe;

    /** TRENDING_UP / TRENDING_DOWN / SIDEWAYS / VOLATILE / RECOVERY 等 */
    @Column(nullable = false, length = 30)
    private String regime;

    /** TREND / HIGH_FREQ / CONSERVATIVE / DISABLE */
    @Column(name = "style_hint", nullable = false, length = 20)
    private String styleHint;

    /** Strategy 的 adxEntryThreshold 增減值(clamp 在 ±5)。 */
    @Column(name = "adx_adjust", nullable = false, precision = 5, scale = 2)
    private BigDecimal adxAdjust = BigDecimal.ZERO;

    /** SL% 乘數(clamp 在 0.5-2.0)。 */
    @Column(name = "sl_multiplier", nullable = false, precision = 6, scale = 3)
    private BigDecimal slMultiplier = BigDecimal.ONE;

    /** TP% 乘數(clamp 在 0.5-2.0)。 */
    @Column(name = "tp_multiplier", nullable = false, precision = 6, scale = 3)
    private BigDecimal tpMultiplier = BigDecimal.ONE;

    /** 是否允許做空(覆蓋 strategy config 的 allowShort)。 */
    @Column(name = "allow_short", nullable = false)
    private Boolean allowShort = false;

    /** 三 persona 投票一致性,0.00-1.00。strategy 採用門檻通常 ≥ 0.5。 */
    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal confidence;

    /** JSON 字串:{"trend":"TREND","contrarian":"HIGH_FREQ","risk":"CONSERVATIVE"} */
    @Column(name = "persona_votes", nullable = false, columnDefinition = "JSON")
    private String personaVotes;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
