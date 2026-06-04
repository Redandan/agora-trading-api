package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 市場指標翻轉偵測的當前生效門檻。
 *
 * <p>{@link com.agora.service.meta.MarketIndicatorFlipDetector} 從此表讀,
 * 取代 hardcoded 門檻。AI (或人類) 可透過 MCP 調整,變更寫入 {@code market_flip_config_audit}。
 *
 * <p>(symbol, indicator) 為 unique key,每組只有一筆代表當前值。
 */
@Data
@Entity
@Table(name = "market_flip_config", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mfc_symbol_indicator", columnNames = {"symbol", "indicator"})
})
public class MarketFlipConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 32)
    private String indicator;

    @Column(name = "threshold_lo", precision = 10, scale = 4)
    private BigDecimal thresholdLo;

    @Column(name = "threshold_hi", precision = 10, scale = 4)
    private BigDecimal thresholdHi;

    @Column(name = "delta_threshold", precision = 10, scale = 4)
    private BigDecimal deltaThreshold;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
