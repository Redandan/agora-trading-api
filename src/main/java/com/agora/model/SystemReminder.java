package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系統預約提醒 — 到 {@code fireAt} 時自動發 TG 訊息。
 *
 * <p>State machine: PENDING → FIRED(成功)/ FAILED(發送失敗)/ CANCELLED(手動取消)。
 *
 * <p>由 {@code SystemReminderScheduler} 每分鐘檢查 due 的 PENDING 並發送。
 *
 * <p>用途:Claude 透過 MCP 預約「明天早上 10 點來看 system snapshot」這類待辦,
 * TG 通知到時點醒人類,結合 Meta-Control 的 audit/attention 形成完整的「人機協作」回饋環。
 */
@Data
@Entity
@Table(name = "system_reminder", indexes = {
        @Index(name = "idx_reminder_due", columnList = "status,fire_at"),
        @Index(name = "idx_reminder_tag", columnList = "tag,created_at")
})
public class SystemReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fire_at", nullable = false)
    private LocalDateTime fireAt;

    @Column(nullable = false, length = 2000)
    private String message;

    /** PENDING / FIRED / CANCELLED / FAILED */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(length = 64)
    private String tag;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "fired_at")
    private LocalDateTime firedAt;

    @Column(length = 500)
    private String error;

    public boolean isPending() {
        return "PENDING".equals(status);
    }
}
