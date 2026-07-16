package com.agora.service.trading.evidence.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Pure local interchange contract. It describes evidence but does not persist, collect,
 * schedule, connect to a provider, or authorize a consumer runtime mode.
 */
public record MinimumCommonEvidenceContract(
        String version,
        String specSha256,
        String sourceCommit,
        DecisionLink decisionLink,
        TimestampChain timestamps,
        ExecutableDepth executableDepth,
        FillLifecycle fill,
        ReconciledSignedAmount signedFee,
        FundingEvidence funding,
        MarginEvidence margin,
        FilterCounterfactualConsumerContract filterCounterfactual,
        MakerFirstLiquidityConsumerContract makerFirst) {

    public static final String VERSION = "MINIMUM_COMMON_EVIDENCE_CONTRACT_NEXT_V1";
    public static final String SPEC_SHA256 = "0a66d2e05b8968d028fc592401b2a488496292a6adb1d5c4272d0d7259441b3a";
    public static final String SOURCE_COMMIT = "93e80abd";

    public record DecisionLink(String decisionId,
                               String interval,
                               Side side,
                               String barId,
                               String action) {
    }

    public record TimestampChain(Instant eventAt,
                                 Instant effectiveAt,
                                 Instant availableAt,
                                 Instant observedAt,
                                 Instant decisionAt,
                                 Instant ingestedAt) {
    }

    public record ExecutableDepth(DepthKind kind,
                                  List<BookLevel> bids,
                                  List<BookLevel> asks) {
        public ExecutableDepth {
            bids = bids == null ? null : List.copyOf(bids);
            asks = asks == null ? null : List.copyOf(asks);
        }
    }

    /** Exact four-column OKX full-depth shape accepted at source commit 93e80abd. */
    public record BookLevel(BigDecimal price,
                            BigDecimal size,
                            Long orderCount,
                            Long liquidationOrderCount) {
    }

    public record FillLifecycle(String orderId,
                                String tradeId,
                                BigDecimal price,
                                BigDecimal quantity,
                                Side side,
                                OrderLifecycle lifecycle,
                                LiquidityRole liquidityRole) {
    }

    public record ReconciledSignedAmount(String identity,
                                         BigDecimal notional,
                                         BigDecimal reconciledNotional,
                                         BigDecimal signedAmount,
                                         String currency) {
    }

    public record FundingEvidence(ReconciledSignedAmount actualBill,
                                  ReconciledSignedAmount counterfactualFunding) {
    }

    public record MarginEvidence(String identity,
                                 BigDecimal equity,
                                 BigDecimal availableBalance,
                                 BigDecimal usedMargin,
                                 BigDecimal maintenanceMargin) {
    }

    public enum DepthKind {
        FULL_DEPTH
    }

    public enum Side {
        BUY,
        SELL
    }

    public enum OrderLifecycle {
        OPEN,
        PARTIALLY_FILLED,
        FILLED,
        CANCELLED
    }

    public enum LiquidityRole {
        MAKER,
        TAKER
    }
}
