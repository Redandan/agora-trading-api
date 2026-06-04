package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Groq AI Token 每日使用量統計（持久化，重啟不歸零）
 */
@Data
@Entity
@Table(name = "ai_token_usage_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_date_model", columnNames = {"stat_date", "model"}))
public class AiTokenUsageDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "req_count", nullable = false)
    private int reqCount;

    @Column(name = "prompt_tok", nullable = false)
    private long promptTok;

    @Column(name = "complete_tok", nullable = false)
    private long completeTok;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
