package com.agora.model;

import lombok.Data;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "client_logs")
@Data
public class ClientLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String level;        // 日誌級別

    @Column(columnDefinition = "TEXT")
    private String message;      // 日誌消息

    @Column(columnDefinition = "TEXT")
    private String url;          // 發生日誌的頁面 URL

    @Column(columnDefinition = "TEXT")
    private String userAgent;    // 用戶代理字符串

    private String device;       // 設備類型

    private Long userId;         // 用戶ID

    @Column(name = "user_ip")
    private String userIp;       // 用戶IP地址

    @Column(columnDefinition = "JSON")
    private String details;      // 額外詳細信息 (JSON 格式)

    private Long timestamp;      // 日誌時間戳

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
