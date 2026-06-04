package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 市場指標翻轉事件 — 每次 indicator 跨過門檻或大幅變動即寫一筆。
 *
 * <p>Phase 2A (shadow mode): {@link com.agora.service.meta.MarketIndicatorFlipDetector}
 * 仍發即時 TG,但同時寫入此表供後續 AI 分析。
 *
 * <p>Phase 2B: AI (Groq + Gemini) 並行分析後寫 {@code market_flip_ai_analysis},
 * 合成共識寫 {@code market_flip_decision},由 decision 決定要不要發 TG。
 *
 * <p>State machine:
 * <pre>
 *  PENDING → IN_REVIEW → REVIEWED
 *         ↓
 *     AUTO_ESCALATED (scheduler 老化 > 60min 自動升級)
 *         ↓
 *     HUMAN_OVERRIDE (人類透過 TG reply 覆蓋 AI 決策)
 * </pre>
 */
@Data
@Entity
@Table(name = "market_flip_event", indexes = {
        @Index(name = "idx_mfe_status_detected",    columnList = "status,detected_at"),
        @Index(name = "idx_mfe_symbol_detected",    columnList = "symbol,detected_at"),
        @Index(name = "idx_mfe_indicator_detected", columnList = "indicator,detected_at")
})
public class MarketFlipEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    /** fear_greed / whale_buy_ratio / funding_rate / orderbook_imbalance / long_short_ratio */
    @Column(nullable = false, length = 32)
    private String indicator;

    @Column(name = "prev_value", nullable = false, precision = 10, scale = 4)
    private BigDecimal prevValue;

    @Column(name = "current_value", nullable = false, precision = 10, scale = 4)
    private BigDecimal currentValue;

    /** 'fg_25' / 'whale_65' / 'delta_20' 等標籤。null 代表只有 delta 變化沒跨門檻。 */
    @Column(name = "threshold_crossed", length = 32)
    private String thresholdCrossed;

    @Column(name = "delta_value", nullable = false, precision = 10, scale = 4)
    private BigDecimal deltaValue;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    /** PENDING / IN_REVIEW / REVIEWED / AUTO_ESCALATED / HUMAN_OVERRIDE */
    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 事件當下的 snapshot(其他指標值、策略狀態等),供 AI 分析用。 */
    @Column(name = "context_json", columnDefinition = "JSON")
    private String contextJson;
}
