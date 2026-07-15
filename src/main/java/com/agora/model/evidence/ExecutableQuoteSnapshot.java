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
@Table(name = "executable_quote_snapshot")
public class ExecutableQuoteSnapshot extends AppendOnlyEvidence {

    @Column(nullable = false, length = 30, updatable = false)
    private String symbol;

    @Column(name = "instrument_type", nullable = false, length = 16, updatable = false)
    private String instrumentType;

    @Column(name = "snapshot_kind", nullable = false, length = 16, updatable = false)
    private String snapshotKind;

    @Column(name = "best_bid_price", nullable = false, precision = 30, scale = 12, updatable = false)
    private BigDecimal bestBidPrice;

    @Column(name = "best_bid_size", nullable = false, precision = 30, scale = 12, updatable = false)
    private BigDecimal bestBidSize;

    @Column(name = "best_ask_price", nullable = false, precision = 30, scale = 12, updatable = false)
    private BigDecimal bestAskPrice;

    @Column(name = "best_ask_size", nullable = false, precision = 30, scale = 12, updatable = false)
    private BigDecimal bestAskSize;

    @Column(name = "depth_json", columnDefinition = "JSON", updatable = false)
    private String depthJson;

    @Column(name = "provider_sequence", length = 128, updatable = false)
    private String providerSequence;
}
