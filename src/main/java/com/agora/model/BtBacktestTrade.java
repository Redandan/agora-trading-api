package com.agora.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "bt_backtest_trade",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_backtest_idx",
                columnNames = {"backtest_id", "trade_idx"}
        ),
        indexes = {
                @Index(name = "idx_backtest",           columnList = "backtest_id"),
                @Index(name = "idx_entry_time",         columnList = "entry_time"),
                @Index(name = "idx_side_exit_reason",   columnList = "side,exit_reason")
        }
)
public class BtBacktestTrade {

    public enum Side {
        LONG,
        SHORT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = false)
    @JoinColumn(name = "backtest_id", nullable = false)
    private BtBacktestResult backtest;

    @Column(name = "trade_idx", nullable = false)
    private Integer tradeIdx;

    @Column(name = "entry_time", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime entryTime;

    @Column(name = "exit_time", columnDefinition = "DATETIME(6)")
    private LocalDateTime exitTime;

    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "exit_price", precision = 20, scale = 8)
    private BigDecimal exitPrice;

    @Column(name = "quantity", nullable = false, precision = 28, scale = 10)
    private BigDecimal quantity;

    @Column(name = "gross_pnl", precision = 20, scale = 8)
    private BigDecimal grossPnl;

    @Column(name = "net_pnl", precision = 20, scale = 8)
    private BigDecimal netPnl;

    @Column(name = "return_pct", precision = 12, scale = 6)
    private BigDecimal returnPct;

    @Column(name = "exit_reason", length = 32)
    private String exitReason;

    /**
     * MySQL 欄位實為 {@code ENUM('LONG','SHORT')}(V040),但 JPA 層宣告 VARCHAR(8)
     * 是為了讓 Hibernate 的 schema 驗證在 H2 / 其它 dialect 下不會炸。實際 DB-level
     * 限制由 V040 migration 的 ENUM 保證。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    private Side side;

    @Column(name = "borrowing_cost", nullable = false, precision = 20, scale = 8)
    private BigDecimal borrowingCost = BigDecimal.ZERO;

    @Column(name = "released_notional", precision = 20, scale = 8)
    private BigDecimal releasedNotional;

    // ─── V047 indicator snapshot at entry time (for ML signal_scorer) ──────
    // All nullable — pre-V047 backtests have NULLs until backfill, HeatWave
    // ML handles missing inputs without crashing.

    @Column(name = "adx14", precision = 10, scale = 4)
    private BigDecimal adx14;

    @Column(name = "rsi14", precision = 10, scale = 4)
    private BigDecimal rsi14;

    @Column(name = "atr_pct", precision = 12, scale = 8)
    private BigDecimal atrPct;

    @Column(name = "volume_ratio_ma20", precision = 12, scale = 6)
    private BigDecimal volumeRatioMa20;

    @Column(name = "close_vs_ema50_pct", precision = 12, scale = 8)
    private BigDecimal closeVsEma50Pct;

    @Column(name = "ema20_slope_pct", precision = 12, scale = 8)
    private BigDecimal ema20SlopePct;

    @Column(name = "bb_width_pct", precision = 12, scale = 8)
    private BigDecimal bbWidthPct;

    // ─── V049 regime features (rolling, position-in-trend) ─────────────────

    @Column(name = "dd_20bar_pct", precision = 10, scale = 6)
    private BigDecimal dd20barPct;

    @Column(name = "dd_50bar_pct", precision = 10, scale = 6)
    private BigDecimal dd50barPct;

    @Column(name = "momentum_50bar_pct", precision = 12, scale = 8)
    private BigDecimal momentum50barPct;

    @Column(name = "realized_vol_20bar", precision = 12, scale = 8)
    private BigDecimal realizedVol20bar;

    @Column(name = "dist_from_ema200_pct", precision = 12, scale = 8)
    private BigDecimal distFromEma200Pct;

    @Column(name = "range_pct_50bar", precision = 10, scale = 6)
    private BigDecimal rangePct50bar;

    // ─── V050 cross-timeframe (HTF) regime features ────────────────────────
    @Column(name = "htf_momentum_50bar_pct", precision = 12, scale = 8)
    private BigDecimal htfMomentum50barPct;

    @Column(name = "htf_trend_up", columnDefinition = "TINYINT")
    private Integer htfTrendUp;

    @Column(name = "htf_dist_ema50_pct", precision = 12, scale = 8)
    private BigDecimal htfDistEma50Pct;
}
