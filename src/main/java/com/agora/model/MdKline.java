package com.agora.model;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "md_kline", indexes = {
        @Index(name = "idx_md_kline_symbol_interval_open_time", columnList = "symbol,interval_code,open_time"),
        @Index(name = "idx_md_kline_sym_int_src_open", columnList = "symbol,interval_code,source,open_time")
})
public class MdKline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(name = "interval_code", nullable = false, length = 10)
    private String intervalCode;

    @Column(name = "open_time", nullable = false)
    private LocalDateTime openTime;

    @Column(name = "close_time", nullable = false)
    private LocalDateTime closeTime;

    @Column(name = "open_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal closePrice;

    @Column(nullable = false, precision = 28, scale = 10)
    private BigDecimal volume;

    /**
     * K 線資料源：{@code binance}（configured Binance spot provider）或 {@code okx}（OKX v5）。
     * 同 (symbol, interval, openTime) 可同時存在兩個源的紀錄；回測與信號評估可選源。
     * 歷史資料預設 {@code binance}（V026 migration）。
     */
    @Column(nullable = false, length = 10)
    private String source = "binance";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
