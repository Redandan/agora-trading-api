package com.agora.model;

import com.agora.enums.system.PersonalityType;
import com.agora.enums.system.ReplyMode;
import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "tg_monitored_group", indexes = {
    @Index(name = "idx_tg_monitored_group_last_message_at", columnList = "last_message_at")
})
public class TgMonitoredGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tg_group_id", nullable = false, unique = true)
    private Long tgGroupId;

    @Column(name = "group_name", length = 255)
    private String groupName;

    @Column(name = "group_type", nullable = false, length = 20)
    private String groupType;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "ai_chat_enabled", nullable = false)
    private Boolean aiChatEnabled = true;

    @Column(name = "ai_manual_prompt_enabled", nullable = false)
    private Boolean aiManualPromptEnabled = false;

    @Lob
    @Column(name = "ai_manual_prompt_text")
    private String aiManualPromptText;

    /** 回覆模式：ACTIVE / PASSIVE / DISABLED */
    @Enumerated(EnumType.STRING)
    @Column(name = "reply_mode", nullable = false, length = 20)
    private ReplyMode replyMode = ReplyMode.ACTIVE;

    /** ACTIVE 模式：累積幾條訊息後現身（預設 10） */
    @Column(name = "message_count_threshold", nullable = false)
    private Integer messageCountThreshold = 10;

    /** ACTIVE 模式：兩次回覆最短間隔（分鐘，預設 5） */
    @Column(name = "min_interval_minutes", nullable = false)
    private Integer minIntervalMinutes = 5;

    /** AI 個性：FRIENDLY / PROFESSIONAL / HUMOROUS / CUSTOM */
    @Enumerated(EnumType.STRING)
    @Column(name = "personality", nullable = false, length = 20)
    private PersonalityType personality = PersonalityType.FRIENDLY;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
