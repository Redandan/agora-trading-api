package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "polymarket_odds_snapshot", indexes = {
        @Index(name = "idx_pos_market_time", columnList = "market_id,snapshotted_at"),
        @Index(name = "idx_pos_snapshotted",  columnList = "snapshotted_at")
})
public class PolymarketOddsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_id", nullable = false, length = 200)
    private String marketId;

    @Column(name = "market_title", nullable = false, length = 500)
    private String marketTitle;

    /** HIGH = directly crypto/macro; MEDIUM = geopolitical; LOW = other */
    @Column(name = "relevance_tag", nullable = false, length = 10)
    private String relevanceTag = "MEDIUM";

    @Column(name = "prob", nullable = false, precision = 5, scale = 4)
    private BigDecimal prob;

    @Column(name = "volume_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal volumeTotal;

    /** volume_total minus previous snapshot's volume_total */
    @Column(name = "volume_delta_15m", precision = 18, scale = 2)
    private BigDecimal volumeDelta15m;

    /** 7-day rolling average of volume_delta_15m for this market */
    @Column(name = "rolling_avg_volume_15m", precision = 18, scale = 2)
    private BigDecimal rollingAvgVolume15m;

    /** volume_delta_15m / rolling_avg_volume_15m — null when avg not yet computed */
    @Column(name = "volume_spike_ratio", precision = 8, scale = 2)
    private BigDecimal volumeSpikeRatio;

    @Column(name = "prob_1h_ago", precision = 5, scale = 4)
    private BigDecimal prob1hAgo;

    @Column(name = "prob_delta_1h", precision = 5, scale = 4)
    private BigDecimal probDelta1h;

    /** Largest single trade USDC size from CLOB API (null if conditionId unavailable) */
    @Column(name = "largest_single_bet_usdc", precision = 18, scale = 2)
    private BigDecimal largestSingleBetUsdc;

    @Column(name = "btc_price", precision = 12, scale = 2)
    private BigDecimal btcPrice;

    @Column(name = "is_resolved", nullable = false)
    private Boolean isResolved = false;

    @Column(name = "snapshotted_at", nullable = false)
    private LocalDateTime snapshottedAt;
}
