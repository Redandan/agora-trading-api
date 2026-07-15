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
@Table(name = "fill_fee_ledger")
public class FillFeeLedgerEntry extends AppendOnlyEvidence {

    @Column(name = "account_ref_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)", updatable = false)
    private String accountRefHash;

    @Column(nullable = false, length = 30, updatable = false)
    private String symbol;

    @Column(name = "instrument_type", nullable = false, length = 16, updatable = false)
    private String instrumentType;

    @Column(name = "order_id", nullable = false, length = 128, updatable = false)
    private String orderId;

    @Column(name = "trade_id", nullable = false, length = 128, updatable = false)
    private String tradeId;

    @Column(name = "signed_fee_amount", nullable = false, precision = 30, scale = 12, updatable = false)
    private BigDecimal signedFeeAmount;

    @Column(name = "fee_currency", nullable = false, length = 20, updatable = false)
    private String feeCurrency;

    @Column(name = "fee_sign_semantic", nullable = false, length = 64, updatable = false)
    private String feeSignSemantic;
}
