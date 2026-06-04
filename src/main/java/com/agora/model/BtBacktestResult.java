package com.agora.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "bt_backtest_result", indexes = {
        @Index(name = "idx_bt_result_strategy_created", columnList = "strategy_id,created_at")
})
public class BtBacktestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strategy_id", nullable = false)
    private BtStrategy strategy;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(name = "interval_code", nullable = false, length = 10)
    private String intervalCode;

    /**
     * 此次回測實際讀取的 K 線資料源（{@code okx} / {@code binance}）。
     * 舊資料（V041 之前建立）可能為 null，無法回溯辨認；新回測由 {@link com.agora.service.BacktestService}
     * 以 strategy.klineSource（優先）或 request.source 推導後填入。
     */
    @Column(name = "kline_source", length = 16)
    private String klineSource;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "initial_capital", nullable = false, precision = 20, scale = 8)
    private BigDecimal initialCapital;

    @Column(name = "final_capital", nullable = false, precision = 20, scale = 8)
    private BigDecimal finalCapital;

    @Column(name = "total_return", nullable = false, precision = 12, scale = 6)
    private BigDecimal totalReturn;

    @Column(name = "max_drawdown", nullable = false, precision = 12, scale = 6)
    private BigDecimal maxDrawdown;

    @Column(name = "win_rate", nullable = false, precision = 12, scale = 6)
    private BigDecimal winRate;

    @Column(name = "sharpe_ratio", nullable = true, precision = 12, scale = 6)
    private BigDecimal sharpeRatio;

    @Column(name = "trade_count", nullable = false)
    private Integer tradeCount;

    @Column(name = "fee_rate", nullable = false, precision = 12, scale = 6)
    private BigDecimal feeRate;

    @Lob
    @Column(name = "trades_json", columnDefinition = "LONGTEXT")
    private String tradesJson;

    @Lob
    @Column(name = "config_snapshot_json", columnDefinition = "LONGTEXT")
    private String configSnapshotJson;

    @Lob
    @Column(name = "diagnostic_logs_json", columnDefinition = "LONGTEXT")
    private String diagnosticLogsJson;

    @Column(name = "market_open_price", nullable = true, precision = 20, scale = 8)
    private BigDecimal marketOpenPrice;

    @Column(name = "market_close_price", nullable = true, precision = 20, scale = 8)
    private BigDecimal marketClosePrice;

    @Column(name = "market_high_price", nullable = true, precision = 20, scale = 8)
    private BigDecimal marketHighPrice;

    @Column(name = "market_low_price", nullable = true, precision = 20, scale = 8)
    private BigDecimal marketLowPrice;

    @Column(name = "market_volatility_pct", nullable = true, precision = 12, scale = 6)
    private BigDecimal marketVolatilityPct;

    @Column(name = "market_price_change_pct", nullable = true, precision = 12, scale = 6)
    private BigDecimal marketPriceChangePct;

    @Column(name = "market_trend", nullable = true, length = 10)
    private String marketTrend;

    @Column(name = "benchmark_return", nullable = true, precision = 12, scale = 6)
    private BigDecimal benchmarkReturn;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 正規化 trades(V040 起)。與 {@link #tradesJson} 並存:
     * JSON 保留為 audit / legacy / rollback,normalized 供分析查詢。
     *
     * <p>Writes go through {@code BtBacktestTradeRepository},非 cascade,以保持
     * BacktestService 顯式雙寫的流程。
     */
    @OneToMany(mappedBy = "backtest", fetch = FetchType.LAZY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<BtBacktestTrade> trades = new ArrayList<>();
}
