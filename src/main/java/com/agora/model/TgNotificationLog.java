package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * TG 通知歷史記錄（V094）。
 * 保留 30 天，由 TgNotificationCleanupScheduler 自動清除。
 */
@Entity
@Table(name = "tg_notification_log")
@Data
@NoArgsConstructor
public class TgNotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(length = 20)
    private String level = "INFO";      // INFO / WARN / CRITICAL

    @Column(length = 100)
    private String source;              // 發送方，如 "ShortBuildIndicator"

    @Column(length = 20)
    private String symbol;

    private Long ruleId;                // Attention Rule ID（若適用）

    private Boolean useHtml = true;

    @Column(nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

    public TgNotificationLog(String message, String level, String source,
                              String symbol, Long ruleId, boolean useHtml) {
        this.message = message;
        this.level   = level;
        this.source  = source;
        this.symbol  = symbol;
        this.ruleId  = ruleId;
        this.useHtml = useHtml;
    }
}
