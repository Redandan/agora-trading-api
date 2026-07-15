package com.agora.model.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;

@Getter
@Entity
@Immutable
@Table(name = "margin_snapshot")
public class MarginSnapshot extends AppendOnlyEvidence {

    @Column(name = "account_ref_hash", nullable = false, length = 64, updatable = false)
    private String accountRefHash;

    @Column(length = 30, updatable = false)
    private String symbol;

    @Column(name = "instrument_type", nullable = false, length = 16, updatable = false)
    private String instrumentType;

    @Column(name = "margin_mode", nullable = false, length = 16, updatable = false)
    private String marginMode;

    @Column(nullable = false, precision = 30, scale = 12, updatable = false)
    private BigDecimal equity;

    @Column(name = "available_balance", nullable = false, precision = 30, scale = 12, updatable = false)
    private BigDecimal availableBalance;

    @Column(name = "used_margin", nullable = false, precision = 30, scale = 12, updatable = false)
    private BigDecimal usedMargin;

    @Column(name = "maintenance_margin", nullable = false, precision = 30, scale = 12, updatable = false)
    private BigDecimal maintenanceMargin;

    @Column(name = "margin_ratio", precision = 30, scale = 12, updatable = false)
    private BigDecimal marginRatio;

    @Column(nullable = false, length = 20, updatable = false)
    private String currency;
}
