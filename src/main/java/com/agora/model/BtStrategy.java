package com.agora.model;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "bt_strategy",
        indexes = @Index(name = "idx_bt_strategy_ai_fingerprint", columnList = "ai_generated, config_fingerprint")
)
public class BtStrategy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "strategy_type", nullable = false, length = 50)
    private String strategyType;

    @Lob
    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    private String configJson;

    @Column(nullable = false)
    private Boolean enabled = false;

    @Column(name = "ai_generated", nullable = false)
    private Boolean aiGenerated = false;

    @Column(name = "discovery_batch", length = 50)
    private String discoveryBatch;

    /**
     * 限定監控的交易對，逗號分隔（如 "BTCUSDT,ETHUSDT"）。
     * 建立與更新時必填，不允許為 NULL。
     */
    @Column(name = "symbols", length = 200)
    private String symbols;

    /**
     * 此策略回測與實時評估共用的 K 線資料源（{@code okx} 或 {@code binance}）。
     * 預設 {@code okx}，與現行交易執行面（OKX）一致；切換後回測/live 兩端統一讀取同一源，
     * 避免「以 binance 歷史訓練、對 okx 實時信號下單」造成的資料分佈偏移。
     * {@code market.signal.source} 全域設定退化為 fallback，只在此欄位讀到 null 時使用。
     */
    @Column(name = "kline_source", nullable = false, length = 16)
    private String klineSource = "okx";

    /**
     * SHA-256 fingerprint of {@code strategyType:configJson}.
     * Used to detect duplicate strategy configurations across discovery runs.
     * Nullable for backward compatibility with pre-existing rows.
     */
    @Column(name = "config_fingerprint", length = 64)
    private String configFingerprint;

    /**
     * 啟用/停用時的說明備註。enableStrategy / disableStrategy 必填,
     * 解釋「為什麼現在啟用/停用」。最新操作覆蓋舊值。
     */
    @Column(name = "notes", length = 1000)
    private String notes;

    /** Alpha 來源分類（V084）：技術面趨勢 / 崩盤底部 / 市場結構(OI+Funding) / 套利 等 */
    @Column(name = "alpha_source", length = 100)
    private String alphaSource;

    /** 結構化觸發條件說明，供快速查閱（V084） */
    @Column(name = "trigger_conditions", columnDefinition = "TEXT")
    private String triggerConditions;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
