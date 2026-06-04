package com.agora.model;

import com.agora.enums.trading.AttributionStatusEnum;
import com.agora.enums.trading.OverrideTypeEnum;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Meta-Control override 的 counterfactual attribution 記錄。
 *
 * <p>每筆 row 對應一次 strategy_override(Phase 1 僅 PAUSE)已結束後的事後評估。
 * {@code actual_pnl} 為 override 生效期間實際發生的 P&L(PAUSE 期間通常 0,因為
 * LiveSignalEvaluator 會短路),{@code counterfactual_pnl} 為對同時段跑 backtest
 * 得到的假想 P&L。兩者差值即 {@code alpha_contribution}:
 *
 * <pre>
 *   alpha_contribution = actual_pnl - counterfactual_pnl
 *        正 → override 加分(避開了虧損 / 保住了 profit)
 *        負 → override 扣分(錯過了 profit / 沒救到什麼)
 * </pre>
 *
 * <p>UNIQUE (override_type, override_id) 保證冪等,scheduler 重算同一 override 不會產生重複列。
 */
@Data
@Entity
@Table(name = "meta_control_attribution",
        uniqueConstraints = @UniqueConstraint(name = "uk_attr_override",
                columnNames = {"override_type", "override_id"}),
        indexes = {
                @Index(name = "idx_attr_strategy_time", columnList = "strategy_id, computed_at"),
                @Index(name = "idx_attr_status", columnList = "computation_status, computed_at")
        })
public class MetaControlAttribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "override_type", nullable = false, length = 32)
    private OverrideTypeEnum overrideType;

    @Column(name = "override_id", nullable = false)
    private Long overrideId;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(name = "interval_code", nullable = false, length = 16)
    private String intervalCode;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    @Column(name = "actual_pnl", nullable = false, precision = 20, scale = 6)
    private BigDecimal actualPnl = BigDecimal.ZERO;

    @Column(name = "actual_trade_count", nullable = false)
    private Integer actualTradeCount = 0;

    @Column(name = "counterfactual_pnl", nullable = false, precision = 20, scale = 6)
    private BigDecimal counterfactualPnl = BigDecimal.ZERO;

    @Column(name = "counterfactual_trade_count", nullable = false)
    private Integer counterfactualTradeCount = 0;

    @Column(name = "alpha_contribution", nullable = false, precision = 20, scale = 6)
    private BigDecimal alphaContribution = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "computation_status", nullable = false, length = 32)
    private AttributionStatusEnum computationStatus;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}
