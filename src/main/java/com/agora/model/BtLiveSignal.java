package com.agora.model;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 持久化 SCORE_BUY 策略產生的即時買入訊號。
 * 以 (strategyId, symbol, intervalCode, barOpenTime) 唯一索引防止重複通知。
 */
@Data
@Entity
@Table(
    name = "bt_live_signal",
    indexes = {
        @Index(name = "idx_bt_live_signal_unique",
               columnList = "strategy_id, symbol, interval_code, bar_open_time",
               unique = true),
        @Index(name = "idx_bt_live_signal_notified_at",
               columnList = "notified_at")
    }
)
public class BtLiveSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "interval_code", nullable = false, length = 10)
    private String intervalCode;

    /** 觸發訊號的 K 線開盤時間（用於去重） */
    @Column(name = "bar_open_time", nullable = false)
    private LocalDateTime barOpenTime;

    /** 收盤價（建議進場價） */
    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    /** 建議止損價（entry × (1 - stopLossPct)） */
    @Column(name = "suggested_sl", precision = 20, scale = 8)
    private BigDecimal suggestedSl;

    /** 建議止盈價（entry × (1 + takeProfitPct)） */
    @Column(name = "suggested_tp", precision = 20, scale = 8)
    private BigDecimal suggestedTp;

    /** 加權分數（0~1） */
    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal score;

    /** Sigmoid 輸出（0~1） */
    @Column(name = "nn_output", nullable = false, precision = 6, scale = 4)
    private BigDecimal nnOutput;

    /**
     * 發送 TG 通知的時間。
     * NULL = 已存 DB 但 TG 尚未成功送出（待 RetryScheduler 補送）。
     */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    /** 實際出場價。NULL = 仍持倉中。 */
    @Column(name = "exit_price", precision = 20, scale = 8)
    private BigDecimal exitPrice;

    /** 出場時間（UTC）。NULL = 仍持倉中。 */
    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    /** 出場原因：SELL_SIGNAL / SL / TP / OCO_FILLED / ORPHAN_CLOSED。NULL = 仍持倉中。 */
    @Column(name = "exit_reason", length = 50)
    private String exitReason;

    /** 已實現損益（USDT）= (exitPrice - actualEntryPrice) * ocoQty（V066 後）或 tradedQty（舊資料）。NULL = 仍持倉或無法計算。 */
    @Column(name = "realized_pnl", precision = 20, scale = 8)
    private BigDecimal realizedPnl;

    // ── 自動交易欄位 ──────────────────────────────────

    /** 是否已自動下單。NULL / false = 純通知模式，true = 已透過 BinanceTradingService 下單。 */
    @Column(name = "auto_traded")
    private Boolean autoTraded;

    /** Binance 買入訂單 ID（自動交易時填入）。 */
    @Column(name = "exchange_order_id", length = 50)
    private String exchangeOrderId;

    /** 實際成交均價（自動交易時填入；與 entryPrice 可能略有差異）。 */
    @Column(name = "actual_entry_price", precision = 20, scale = 8)
    private BigDecimal actualEntryPrice;

    /** 實際買入數量（自動交易時填入；出場賣出時使用此值）。 */
    @Column(name = "traded_qty", precision = 20, scale = 8)
    private BigDecimal tradedQty;

    /**
     * OCO 實際委託數量。
     *
     * <p>LONG：OCO 掛單時的 BTC 數量，可能小於 {@code tradedQty}（Grid HOLDING level
     * 的賣單會佔用部分 OKX availBal，導致 OCO 只能覆蓋剩餘部分）。
     * PnL 計算使用此欄位（而非 {@code tradedQty}），以反映實際出場數量。</p>
     *
     * <p>SHORT：等同 {@code tradedQty}（合約張數，無 Grid 競爭）。</p>
     *
     * <p>NULL：OCO 未成功掛單，或 V066 migration 前的舊資料（回落至 {@code tradedQty}）。</p>
     */
    @Column(name = "oco_qty", precision = 20, scale = 8)
    private BigDecimal ocoQty;

    /** OCO 訂單 ListId（出場前用於取消 OCO）。NULL = OCO 未成功掛單。 */
    @Column(name = "oco_order_list_id")
    private Long ocoOrderListId;

    /**
     * 倉位方向：LONG（現貨買入）或 SHORT（SWAP 合約賣空）。
     * NULL / 缺少欄位視為 LONG（向下相容舊資料）。
     */
    @Column(name = "side", length = 5, columnDefinition = "VARCHAR(5) DEFAULT 'LONG'")
    private String side = "LONG";

    /**
     * AiFilter 攔截原因。NULL = 未被攔截（正常信號）；非 NULL = 該筆信號因此原因未執行自動交易。
     * 搭配 {@code autoTraded=false} 使用。
     */
    @Column(name = "filter_reason", length = 500)
    private String filterReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Persisted last aging alert timestamp — survives restarts (V089). */
    @Column(name = "last_aging_alert_at")
    private LocalDateTime lastAgingAlertAt;

    // ── #439 trailing stop state (carried by the reviewed V1 baseline) ───────
    /**
     * Trailing-stop machine state. ENTERED → BREAKEVEN_LOCKED (price ≥ entry+0.5×ATR)
     * → TRAILING (price ≥ entry+1.0×ATR; SL follows highest price -1×ATR).
     */
    @Column(name = "trailing_state", length = 20)
    private String trailingState = "ENTERED";

    /** ATR fraction snapshot at first trailing tick (e.g. 0.0089 = 0.89%). */
    @Column(name = "trailing_atr", precision = 12, scale = 6)
    private BigDecimal trailingAtr;

    /** Highest price observed since entry; used for trailing SL math. */
    @Column(name = "trailing_high", precision = 20, scale = 8)
    private BigDecimal trailingHigh;

    /** Last trailing-stop state transition time. NULL when the state has never transitioned. */
    @Column(name = "trailing_last_transition_at")
    private LocalDateTime trailingLastTransitionAt;
}
