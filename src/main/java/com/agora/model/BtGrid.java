package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Grid trading 主配置。一筆 BtGrid 擁有 N 個 {@link BtGridLevel},每個 level 獨立狀態機。
 *
 * <p><b>生命週期</b>:
 * <ul>
 *   <li>建立:{@code enabled=true}, {@code paused_at=null}, {@code closed_at=null}</li>
 *   <li>暫停:{@code paused_at=timestamp}(Gemini hint 擋 or 手動),可復活</li>
 *   <li>關閉:{@code closed_at=timestamp}(stop-out 或手動 close),不可再開</li>
 * </ul>
 *
 * <p>Historical custom Grid record retained for immutable attribution after
 * the executable custom Grid runtime was removed.
 */
@Data
@Entity
@Table(name = "bt_grid", indexes = {
        @Index(name = "idx_bt_grid_enabled", columnList = "enabled,closed_at,symbol")
})
public class BtGrid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "price_lower", nullable = false, precision = 20, scale = 8)
    private BigDecimal priceLower;

    @Column(name = "price_upper", nullable = false, precision = 20, scale = 8)
    private BigDecimal priceUpper;

    @Column(name = "grid_count", nullable = false)
    private Integer gridCount;

    @Column(name = "per_level_usdt", nullable = false, precision = 10, scale = 2)
    private BigDecimal perLevelUsdt;

    /** 區間外 % 觸發全平(例如 0.03 = 超區間 3% 全平 + 關閉)。null 則不停損。 */
    @Column(name = "stop_out_pct", precision = 5, scale = 4)
    private BigDecimal stopOutPct;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "hint_gated", nullable = false)
    private Boolean hintGated = true;

    /** CSV 允許的 regime 清單,Gemini hint 不在其內則暫停。 */
    @Column(name = "regime_whitelist", nullable = false, length = 200)
    private String regimeWhitelist = "SIDEWAYS,VOLATILE,RECOVERY";

    @Column(name = "total_realized_pnl", nullable = false, precision = 20, scale = 8)
    private BigDecimal totalRealizedPnl = BigDecimal.ZERO;

    @Column(name = "closed_pair_count", nullable = false)
    private Integer closedPairCount = 0;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "paused_reason", length = 200)
    private String pausedReason;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** 啟用後價格超出範圍觸發自動重建。 */
    @Column(name = "auto_rebalance", nullable = false)
    private Boolean autoRebalance = false;

    /** 超出範圍多少比例觸發重建（預設 1.5%）。 */
    @Column(name = "rebalance_trigger_pct", nullable = false)
    private Double rebalanceTriggerPct = 0.015;

    /** 累計重建次數。 */
    @Column(name = "rebalance_count", nullable = false)
    private Integer rebalanceCount = 0;

    /** 最大重建次數上限，超過需人工確認方向。 */
    @Column(name = "max_rebalance_count", nullable = false)
    private Integer maxRebalanceCount = 5;

    /** 價格需持續在範圍外 N 小時才觸發重建（防短暫穿越）。 */
    @Column(name = "min_hours_outside", nullable = false)
    private Integer minHoursOutside = 4;

    /** 首次偵測到價格超出範圍的時間。 */
    @Column(name = "outside_range_since")
    private java.time.LocalDateTime outsideRangeSince;

    /** 上次自動重建時間（每日最多 1 次）。 */
    @Column(name = "last_rebalance_at")
    private java.time.LocalDateTime lastRebalanceAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Convenience:是否仍在「可執行」狀態(未暫停 + 未關閉 + enabled)。 */
    public boolean isActive() {
        return Boolean.TRUE.equals(enabled) && pausedAt == null && closedAt == null;
    }
}
