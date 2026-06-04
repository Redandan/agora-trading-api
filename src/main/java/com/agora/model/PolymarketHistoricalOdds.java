package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "polymarket_historical_odds", uniqueConstraints = {
        @UniqueConstraint(name = "uk_pho_market_event", columnNames = {"market_id", "event_time"})
}, indexes = {
        @Index(name = "idx_pho_event_time", columnList = "event_time"),
        @Index(name = "idx_pho_delta",      columnList = "prob_delta_1h")
})
public class PolymarketHistoricalOdds {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_id", nullable = false, length = 200)
    private String marketId;

    /** CLOB yes-token ID — used to call prices-history endpoint */
    @Column(name = "token_id", length = 200)
    private String tokenId;

    @Column(name = "market_title", nullable = false, length = 500)
    private String marketTitle;

    /** trade-war / crypto / geopolitical / macro */
    @Column(name = "market_category", length = 50)
    private String marketCategory;

    /** UTC, aligned to 1h kline openTime */
    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @Column(name = "prob", nullable = false, precision = 5, scale = 4)
    private BigDecimal prob;

    /** prob minus previous hour's prob */
    @Column(name = "prob_delta_1h", precision = 5, scale = 4)
    private BigDecimal probDelta1h;

    @Column(name = "btc_price", precision = 12, scale = 2)
    private BigDecimal btcPrice;

    /** (btcPrice at T+1h - btcPrice at T) / btcPrice at T × 100 */
    @Column(name = "btc_change_1h", precision = 8, scale = 4)
    private BigDecimal btcChange1h;

    @Column(name = "btc_change_4h", precision = 8, scale = 4)
    private BigDecimal btcChange4h;

    @Column(name = "btc_change_24h", precision = 8, scale = 4)
    private BigDecimal btcChange24h;
}
