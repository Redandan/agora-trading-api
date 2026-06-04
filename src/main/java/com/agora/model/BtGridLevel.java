package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Grid 每格的獨立狀態機。
 *
 * <p><b>狀態轉換</b>:
 * <ul>
 *   <li>{@code PENDING}:等價格跌到 {@code price} 觸發 market buy</li>
 *   <li>{@code PENDING_OKX}:已呼叫 OKX placeMarketBuy 但尚未確認結果(distributed-tx 防護中介態,#340 Phase 3)</li>
 *   <li>{@code HOLDING}:buy 成交,持有 BTC,等價格漲到 {@code pairedSellPrice} 觸發 market sell</li>
 *   <li>{@code SELLING_OKX}:已呼叫 OKX placeMarketSell 但尚未確認結果(對稱 PENDING_OKX,#373)</li>
 *   <li>{@code CLOSED}:sell 成交,{@code realizedPnl} 計算完成;可重新進入 PENDING</li>
 *   <li>{@code SELL_FAILED}:buy 成交但 sell 失敗(持有 BTC),scanner 自動 retry 最多 3 次</li>
 *   <li>{@code SELL_PARTIAL}:OKX market sell 部分成交(#399),{@code filled_qty} 改存 leftover 待後續 retry/stop-out 處理 —
 *       已賣出部分的 PnL 累計入 {@code realized_pnl} + {@code grid.totalRealizedPnl}</li>
 *   <li>{@code BUY_FAILED}:buy 失敗(不持有 BTC),scanner 自動重置回 PENDING</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "bt_grid_level", indexes = {
        @Index(name = "idx_bt_grid_level_grid", columnList = "grid_id,status"),
        @Index(name = "idx_bt_grid_level_price", columnList = "grid_id,price")
})
public class BtGridLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grid_id", nullable = false)
    private Long gridId;

    /** 0-based,0 = 最低格。 */
    @Column(name = "level_index", nullable = false)
    private Integer levelIndex;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal price;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "filled_qty", precision = 20, scale = 8)
    private BigDecimal filledQty;

    @Column(name = "filled_price", precision = 20, scale = 8)
    private BigDecimal filledPrice;

    @Column(name = "paired_sell_price", precision = 20, scale = 8)
    private BigDecimal pairedSellPrice;

    @Column(name = "realized_pnl", precision = 20, scale = 8)
    private BigDecimal realizedPnl;

    @Column(name = "buy_order_id", length = 50)
    private String buyOrderId;

    @Column(name = "sell_order_id", length = 50)
    private String sellOrderId;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** SELL_FAILED auto-retry 次數;>=3 停止自動 retry,等人工/老化警報。 */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "filled_at")
    private LocalDateTime filledAt;

    /** #340 Phase 3 — 進入 PENDING_OKX 的時間，scanner 用此偵測卡住超過 N 分鐘的中介態。*/
    @Column(name = "intent_at")
    private LocalDateTime intentAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
