package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "bt_oco_adjustment_audit", indexes = {
        @Index(name = "idx_oco_audit_signal_time", columnList = "live_signal_id,effective_at"),
        @Index(name = "idx_oco_audit_symbol_time", columnList = "symbol,effective_at"),
        @Index(name = "idx_oco_audit_new_oco", columnList = "new_oco_order_list_id"),
        @Index(name = "idx_oco_audit_old_oco", columnList = "old_oco_order_list_id")
})
public class BtOcoAdjustmentAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "live_signal_id", nullable = false)
    private Long liveSignalId;

    @Column(name = "strategy_id")
    private Long strategyId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 5)
    private String side = "LONG";

    @Column(nullable = false, length = 32)
    private String action;

    @Column(name = "old_oco_order_list_id")
    private Long oldOcoOrderListId;

    @Column(name = "new_oco_order_list_id")
    private Long newOcoOrderListId;

    @Column(name = "old_tp", precision = 20, scale = 8)
    private BigDecimal oldTp;

    @Column(name = "new_tp", precision = 20, scale = 8)
    private BigDecimal newTp;

    @Column(name = "old_sl", precision = 20, scale = 8)
    private BigDecimal oldSl;

    @Column(name = "new_sl", precision = 20, scale = 8)
    private BigDecimal newSl;

    @Column(name = "old_qty", precision = 20, scale = 8)
    private BigDecimal oldQty;

    @Column(name = "new_qty", precision = 20, scale = 8)
    private BigDecimal newQty;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(length = 500)
    private String reason;

    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
