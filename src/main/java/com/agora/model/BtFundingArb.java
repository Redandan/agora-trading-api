package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Funding Rate Arbitrage(Layer 2 被動收益)delta-neutral 配對持倉。
 *
 * <p>一筆 position 由 spot long + perp short 兩條腿組成,數量相等,淨市場暴露 ≈ 0。
 * 收益來源為每 8h perp 資金費率(正 funding → 空方收錢)。
 *
 * <p><b>State machine</b>:
 * <pre>
 *   PENDING → OPENING(spot 已下,perp 未下)→ OPEN(兩條腿齊全)
 *           → CLOSING → CLOSED
 *           或 → FAILED(兩條腿原子性失敗,需要人工介入)
 * </pre>
 *
 * <p>類比 {@link BtGrid},但 grid 是自己的 state machine 管多個 level;
 * funding arb 是單一 position 管兩條腿,結構更簡單。
 */
@Data
@Entity
@Table(name = "bt_funding_arb", indexes = {
        @Index(name = "idx_fa_status",      columnList = "status,symbol"),
        @Index(name = "idx_fa_symbol_open", columnList = "symbol,opened_at")
})
public class BtFundingArb {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "notional_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal notionalUsdt;

    // ===== Spot leg =====
    @Column(name = "spot_qty", precision = 20, scale = 8)
    private BigDecimal spotQty;

    @Column(name = "spot_entry_price", precision = 20, scale = 8)
    private BigDecimal spotEntryPrice;

    @Column(name = "spot_exit_price", precision = 20, scale = 8)
    private BigDecimal spotExitPrice;

    @Column(name = "spot_buy_order_id", length = 50)
    private String spotBuyOrderId;

    @Column(name = "spot_sell_order_id", length = 50)
    private String spotSellOrderId;

    // ===== Perp leg =====
    @Column(name = "perp_contract_qty", precision = 20, scale = 8)
    private BigDecimal perpContractQty;

    @Column(name = "perp_entry_price", precision = 20, scale = 8)
    private BigDecimal perpEntryPrice;

    @Column(name = "perp_exit_price", precision = 20, scale = 8)
    private BigDecimal perpExitPrice;

    @Column(name = "perp_open_order_id", length = 50)
    private String perpOpenOrderId;

    @Column(name = "perp_close_order_id", length = 50)
    private String perpCloseOrderId;

    // ===== Config snapshot(進場時鎖定)=====
    @Column(name = "min_funding_rate", nullable = false, precision = 8, scale = 6)
    private BigDecimal minFundingRate;

    @Column(name = "exit_threshold", nullable = false, precision = 8, scale = 6)
    private BigDecimal exitThreshold;

    @Column(name = "target_profit_usdt", precision = 10, scale = 2)
    private BigDecimal targetProfitUsdt;

    /** PENDING / OPENING / OPEN / CLOSING / CLOSED / FAILED */
    @Column(nullable = false, length = 20)
    private String status;

    // ===== 累計 / 結算 =====
    @Column(name = "accumulated_funding", nullable = false, precision = 20, scale = 8)
    private BigDecimal accumulatedFunding = BigDecimal.ZERO;

    @Column(name = "funding_periods", nullable = false)
    private Integer fundingPeriods = 0;

    @Column(name = "realized_pnl", precision = 20, scale = 8)
    private BigDecimal realizedPnl;

    // ===== Claude Meta-Control 整合 =====
    @Column(name = "hint_gated", nullable = false)
    private Boolean hintGated = true;

    @Column(name = "regime_whitelist", nullable = false, length = 200)
    private String regimeWhitelist = "TRENDING_UP,SIDEWAYS,RECOVERY";

    // ===== Timestamps =====
    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "close_reason", length = 200)
    private String closeReason;

    /** Convenience:是否為活躍中 position(OPEN 或 OPENING/CLOSING transitional state)。 */
    public boolean isActive() {
        return "OPEN".equals(status) || "OPENING".equals(status) || "CLOSING".equals(status);
    }

    public boolean isOpen() {
        return "OPEN".equals(status);
    }
}
