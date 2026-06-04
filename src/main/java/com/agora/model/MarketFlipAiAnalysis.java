package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 單一 AI provider 對 {@link MarketFlipEvent} 的分析結果。
 *
 * <p>一個 event 會有多筆 analysis(Groq + Gemini 各一筆),
 * 由 {@link com.agora.service.meta.MarketFlipConsensusService} 合成共識寫 {@link MarketFlipDecision}。
 */
@Data
@Entity
@Table(name = "market_flip_ai_analysis", indexes = {
        @Index(name = "idx_mfaa_event",              columnList = "event_id"),
        @Index(name = "idx_mfaa_provider_analyzed",  columnList = "provider,analyzed_at")
})
public class MarketFlipAiAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    /** gemini-flash / groq-llama-70b / claude-sonnet */
    @Column(nullable = false, length = 64)
    private String provider;

    /** DISMISS / ALERT / TUNE / CREATE_RULE */
    @Column(nullable = false, length = 32)
    private String decision;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;
}
