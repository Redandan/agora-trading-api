package com.agora.model;

import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AI 群組對話轉化事件流水帳
 * 記錄每一次 AI 參與對話所產生的事件
 */
@Data
@Entity
@Table(name = "ai_group_conversion_event", indexes = {
    @Index(name = "idx_ai_conv_event_group_created", columnList = "group_id, created_at"),
    @Index(name = "idx_ai_conv_event_created", columnList = "created_at")
})
public class AiGroupConversionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    /**
     * 事件類型：PROACTIVE_CHAT, MENTION_CHAT, SKILL_TRIGGERED,
     *           GENERAL_FALLBACK, BUTTON_CLICKED, KNOWLEDGE_HIT
     */
    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    /**
     * 意圖代碼：MARKET, BUY, RECHARGE, GAME, STORE, PROMO, GENERAL 等
     * 僅 SKILL_TRIGGERED / GENERAL_FALLBACK 時有值
     */
    @Column(name = "intent_code", length = 20)
    private String intentCode;

    /**
     * 觸發此事件的用戶 Telegram ID（可為 null，例如系統主動插話）
     */
    @Column(name = "triggered_by")
    private Long triggeredBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public enum EventType {
        PROACTIVE_CHAT,
        MENTION_CHAT,
        SKILL_TRIGGERED,
        GENERAL_FALLBACK,
        BUTTON_CLICKED,
        KNOWLEDGE_HIT
    }
}
