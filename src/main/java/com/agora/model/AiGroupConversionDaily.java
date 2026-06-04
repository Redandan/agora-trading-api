package com.agora.model;

import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 群組對話每日轉化統計
 * 由事件流水帳彙總，每群組每日一筆
 */
@Data
@Entity
@Table(name = "ai_group_conversion_daily",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_ai_conv_daily_group_date",
        columnNames = {"group_id", "stat_date"}
    ),
    indexes = @Index(name = "idx_ai_conv_daily_date", columnList = "stat_date")
)
public class AiGroupConversionDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    // ─── 對話量 ────────────────────────────────
    @Column(name = "proactive_chat", nullable = false)
    private int proactiveChat = 0;

    @Column(name = "mention_chat", nullable = false)
    private int mentionChat = 0;

    // ─── 業務觸發 ──────────────────────────────
    @Column(name = "bet_trigger", nullable = false)
    private int betTrigger = 0;

    @Column(name = "buy_trigger", nullable = false)
    private int buyTrigger = 0;

    @Column(name = "recharge_trigger", nullable = false)
    private int rechargeTrigger = 0;

    @Column(name = "game_trigger", nullable = false)
    private int gameTrigger = 0;

    @Column(name = "store_trigger", nullable = false)
    private int storeTrigger = 0;

    @Column(name = "promo_trigger", nullable = false)
    private int promoTrigger = 0;

    // ─── 品質指標 ──────────────────────────────
    @Column(name = "skill_hit", nullable = false)
    private int skillHit = 0;

    @Column(name = "general_fallback", nullable = false)
    private int generalFallback = 0;

    @Column(name = "button_clicked", nullable = false)
    private int buttonClicked = 0;

    @Column(name = "knowledge_hit", nullable = false)
    private int knowledgeHit = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
