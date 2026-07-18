package com.agora.model.evidence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "immutable_trade_fill")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImmutableTradeFill {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="fill_identity_sha256",nullable=false,updatable=false,length=64,columnDefinition="CHAR(64)") private String fillIdentitySha256;
    @Column(name="immutable_content_sha256",nullable=false,updatable=false,length=64,columnDefinition="CHAR(64)") private String immutableContentSha256;
    @Column(nullable=false,updatable=false,length=32) private String provider;
    @Column(name="account_ref_hash",nullable=false,updatable=false,length=64,columnDefinition="CHAR(64)") private String accountRefHash;
    @Column(name="instrument_id",nullable=false,updatable=false,length=64) private String instrumentId;
    @Column(name="instrument_type",nullable=false,updatable=false,length=32) private String instrumentType;
    @Column(name="order_id",nullable=false,updatable=false) private String orderId;
    @Column(name="trade_id",nullable=false,updatable=false) private String tradeId;
    @Column(name="bill_id",nullable=false,updatable=false) private String billId;
    @Column(name="fill_at",nullable=false,updatable=false) private Instant fillAt;
    @Column(nullable=false,updatable=false,length=8) private String side;
    @Column(name="fill_price",nullable=false,updatable=false,precision=30,scale=12) private BigDecimal fillPrice;
    @Column(name="fill_quantity",nullable=false,updatable=false,precision=30,scale=12) private BigDecimal fillQuantity;
    @Column(name="signed_fee_amount",nullable=false,updatable=false,precision=30,scale=12) private BigDecimal signedFeeAmount;
    @Column(name="fee_currency",nullable=false,updatable=false,length=32) private String feeCurrency;
    @Column(name="liquidity_role",updatable=false,length=32) private String liquidityRole;
    @Column(name="source_run_id",nullable=false,updatable=false,length=64,columnDefinition="CHAR(64)") private String sourceRunId;
    @Column(name="source_page_key",nullable=false,updatable=false,length=64,columnDefinition="CHAR(64)") private String sourcePageKey;
    @Column(name="collected_at",nullable=false,updatable=false) private Instant collectedAt;
    @Column(name="raw_payload_sha256",nullable=false,updatable=false,length=64,columnDefinition="CHAR(64)") private String rawPayloadSha256;
    @Column(name="cohort_id",updatable=false) private String cohortId;
    @Column(name="runtime_decision_id",updatable=false) private Long runtimeDecisionId;
    @Column(name="live_signal_id",updatable=false) private Long liveSignalId;
    @Column(name="intended_child_order_id",updatable=false) private String intendedChildOrderId;
    @Column(name="actual_child_order_id",updatable=false) private String actualChildOrderId;
}
