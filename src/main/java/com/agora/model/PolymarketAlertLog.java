package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "polymarket_alert_log", indexes = {
        @Index(name = "idx_pal_notified", columnList = "notified_at"),
        @Index(name = "idx_pal_market",   columnList = "market_id,notified_at")
})
public class PolymarketAlertLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_id", nullable = false, length = 200)
    private String marketId;

    @Column(name = "market_title", nullable = false, length = 500)
    private String marketTitle;

    /** ODDS_SPIKE / VOLUME_SPIKE / LARGE_BET / COMBINED / RESOLVED */
    @Column(name = "alert_type", nullable = false, length = 30)
    private String alertType;

    /** MEDIUM / HIGH / EXTREME */
    @Column(name = "signal_strength", nullable = false, length = 10)
    private String signalStrength;

    @Column(name = "prob_before", precision = 5, scale = 4)
    private BigDecimal probBefore;

    @Column(name = "prob_after", precision = 5, scale = 4)
    private BigDecimal probAfter;

    @Column(name = "prob_delta", precision = 5, scale = 4)
    private BigDecimal probDelta;

    @Column(name = "volume_spike_ratio", precision = 8, scale = 2)
    private BigDecimal volumeSpikeRatio;

    @Column(name = "largest_single_bet", precision = 18, scale = 2)
    private BigDecimal largestSingleBet;

    @Column(name = "btc_price_at_alert", precision = 12, scale = 2)
    private BigDecimal btcPriceAtAlert;

    /** Backfilled ~4h after alert by scheduler — used as ML training label */
    @Column(name = "btc_price_4h_later", precision = 12, scale = 2)
    private BigDecimal btcPrice4hLater;

    /** (btc_price_4h_later - btc_price_at_alert) / btc_price_at_alert × 100 */
    @Column(name = "btc_pct_change_4h", precision = 8, scale = 4)
    private BigDecimal btcPctChange4h;

    @Column(name = "notified_at", nullable = false)
    private LocalDateTime notifiedAt;
}
