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
@Table(name = "funding_bill_ledger")
public class FundingBillLedgerEntry extends AppendOnlyEvidence {

    @Column(name = "account_ref_hash", nullable = false, length = 64, updatable = false)
    private String accountRefHash;

    @Column(nullable = false, length = 30, updatable = false)
    private String symbol;

    @Column(name = "instrument_type", nullable = false, length = 16, updatable = false)
    private String instrumentType;

    @Column(name = "bill_id", nullable = false, length = 128, updatable = false)
    private String billId;

    @Column(name = "position_ref", length = 128, updatable = false)
    private String positionRef;

    @Column(name = "signed_funding_amount", nullable = false, precision = 30, scale = 12, updatable = false)
    private BigDecimal signedFundingAmount;

    @Column(name = "funding_currency", nullable = false, length = 20, updatable = false)
    private String fundingCurrency;

    @Column(name = "funding_sign_semantic", nullable = false, length = 64, updatable = false)
    private String fundingSignSemantic;
}
