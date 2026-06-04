package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 伺服器啟動時序紀錄。
 * 每次啟動記一筆，追蹤 Spring 就緒 → WS 連線 → 策略首次評估 的時間差，
 * 方便排查部署後策略空窗期。
 */
@Data
@Entity
@Table(name = "server_startup_log")
public class ServerStartupLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Spring ApplicationReady（subscribeOnStartup 進入時） */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** 所有 WS 訂閱送出完成（連線已建立） */
    @Column(name = "ws_ready_at")
    private LocalDateTime wsReadyAt;

    /** MarketSignalCache 暖機完成（策略首次評估結束） */
    @Column(name = "first_eval_at")
    private LocalDateTime firstEvalAt;

    /** 備注（如訂閱失敗原因） */
    @Column(length = 500)
    private String note;
}
