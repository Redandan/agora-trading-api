package com.agora.service.trading.evidence.contract;

import com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.Result;
import com.agora.service.trading.evidence.contract.FilterCounterfactualConsumerContract.GateProof;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.BookLevel;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.DecisionLink;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.ExecutableDepth;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.FillLifecycle;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.ReconciledSignedAmount;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.TimestampChain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import static com.agora.service.trading.evidence.contract.CommonEvidenceGapReason.*;
import static com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.*;

/** Deterministic, dependency-free validation for the local typed contract. */
public final class MinimumCommonEvidenceValidator {

    public Result validateCommon(MinimumCommonEvidenceContract evidence) {
        EnumSet<CommonEvidenceGapReason> invalid = EnumSet.noneOf(CommonEvidenceGapReason.class);
        EnumSet<CommonEvidenceGapReason> gaps = EnumSet.noneOf(CommonEvidenceGapReason.class);
        if (evidence == null) {
            invalid.add(CONTRACT_VERSION_MISMATCH);
            return result(OBSERVED_INVALID, invalid);
        }
        if (!MinimumCommonEvidenceContract.VERSION.equals(evidence.version())) invalid.add(CONTRACT_VERSION_MISMATCH);
        if (!MinimumCommonEvidenceContract.SPEC_SHA256.equals(evidence.specSha256())) invalid.add(SPEC_SHA256_MISMATCH);
        if (!MinimumCommonEvidenceContract.SOURCE_COMMIT.equals(evidence.sourceCommit())) invalid.add(SOURCE_COMMIT_MISMATCH);
        if (!completeDecision(evidence.decisionLink())) gaps.add(MISSING_DECISION_LINK);
        validateTimestamps(evidence.timestamps(), invalid);
        validateDepth(evidence.executableDepth(), invalid);
        validateFill(evidence.fill(), gaps);
        validateAmount(evidence.signedFee(), MISSING_SIGNED_FEE_IDENTITY, SIGNED_FEE_NOTIONAL_MISMATCH, gaps);
        if (evidence.funding() == null) {
            gaps.add(MISSING_ACTUAL_FUNDING_BILL_IDENTITY);
            gaps.add(MISSING_COUNTERFACTUAL_FUNDING);
        } else {
            validateAmount(evidence.funding().actualBill(), MISSING_ACTUAL_FUNDING_BILL_IDENTITY,
                    ACTUAL_FUNDING_NOTIONAL_MISMATCH, gaps);
            if (evidence.funding().counterfactualFunding() == null) gaps.add(MISSING_COUNTERFACTUAL_FUNDING);
            else validateAmount(evidence.funding().counterfactualFunding(), MISSING_COUNTERFACTUAL_FUNDING,
                    ACTUAL_FUNDING_NOTIONAL_MISMATCH, gaps);
        }
        if (!invalid.isEmpty()) return result(OBSERVED_INVALID, invalid);
        if (!gaps.isEmpty()) return result(OBSERVED_GAP, gaps);
        return result(OBSERVED_READY, EnumSet.noneOf(CommonEvidenceGapReason.class));
    }

    public Result validateFilterCounterfactual(MinimumCommonEvidenceContract evidence) {
        Result common = validateCommon(evidence);
        if (common.readiness() == OBSERVED_INVALID) return common;
        EnumSet<CommonEvidenceGapReason> reasons = copy(common.gapReasons());
        FilterCounterfactualConsumerContract filter = evidence == null ? null : evidence.filterCounterfactual();
        if (filter == null || !Boolean.TRUE.equals(filter.blockerProofPresent())) reasons.add(MISSING_FILTER_BLOCKER_PROOF);
        if (filter == null || filter.eventRisk() != GateProof.PASS) reasons.add(EVENT_RISK_NOT_PASS);
        if (filter == null || filter.expectedValue() != GateProof.PASS) reasons.add(EXPECTED_VALUE_NOT_PASS);
        if (filter == null || !Boolean.TRUE.equals(filter.fearGreedCausalEvidencePresent())) {
            reasons.add(MISSING_FEAR_GREED_CAUSAL_EVIDENCE);
        }
        return reasons.isEmpty() ? result(OBSERVED_READY, reasons) : result(NOT_MEASURABLE, reasons);
    }

    public Result validateMakerFirst(MinimumCommonEvidenceContract evidence) {
        Result common = validateCommon(evidence);
        if (common.readiness() == OBSERVED_INVALID) return common;
        EnumSet<CommonEvidenceGapReason> reasons = copy(common.gapReasons());
        MakerFirstLiquidityConsumerContract maker = evidence == null ? null : evidence.makerFirst();
        if (maker == null || !positive(maker.requestedQuantity())) reasons.add(MISSING_REQUESTED_QUANTITY);
        return reasons.isEmpty() ? result(OBSERVED_READY, reasons) : result(NOT_MEASURABLE, reasons);
    }

    public Result validateSameDecisionGroup(Collection<MinimumCommonEvidenceContract> evidence) {
        if (evidence == null || evidence.isEmpty()) return result(OBSERVED_GAP, EnumSet.of(MISSING_DECISION_LINK));
        DecisionLink expected = null;
        for (MinimumCommonEvidenceContract item : evidence) {
            if (item == null || !completeDecision(item.decisionLink())) {
                return result(OBSERVED_GAP, EnumSet.of(MISSING_DECISION_LINK));
            }
            if (expected == null) expected = item.decisionLink();
            else if (!expected.equals(item.decisionLink())) {
                return result(OBSERVED_INVALID, EnumSet.of(MIXED_DECISION_DIMENSIONS));
            }
        }
        return result(OBSERVED_READY, EnumSet.noneOf(CommonEvidenceGapReason.class));
    }

    private static void validateTimestamps(TimestampChain chain, EnumSet<CommonEvidenceGapReason> reasons) {
        if (chain == null || chain.eventAt() == null || chain.effectiveAt() == null || chain.availableAt() == null
                || chain.observedAt() == null || chain.decisionAt() == null || chain.ingestedAt() == null) {
            reasons.add(MISSING_TIMESTAMP_CHAIN);
            return;
        }
        if (chain.eventAt().isAfter(chain.effectiveAt())
                || chain.effectiveAt().isAfter(chain.availableAt())
                || chain.availableAt().isAfter(chain.observedAt())
                || chain.observedAt().isAfter(chain.decisionAt())
                || chain.decisionAt().isAfter(chain.ingestedAt())) {
            reasons.add(TIMESTAMP_CHAIN_ORDER_VIOLATION);
        }
    }

    private static void validateDepth(ExecutableDepth depth, EnumSet<CommonEvidenceGapReason> reasons) {
        if (depth == null || depth.kind() != MinimumCommonEvidenceContract.DepthKind.FULL_DEPTH
                || depth.bids() == null || depth.asks() == null || depth.bids().isEmpty() || depth.asks().isEmpty()) {
            reasons.add(MISSING_EXECUTABLE_FULL_DEPTH);
            return;
        }
        if (!validLevels(depth.bids()) || !validLevels(depth.asks())) {
            reasons.add(INVALID_EXECUTABLE_DEPTH_LEVEL);
            return;
        }
        if (!strictlySorted(depth.bids(), true) || !strictlySorted(depth.asks(), false)) {
            reasons.add(INVALID_EXECUTABLE_DEPTH_SORT_ORDER);
            return;
        }
        if (depth.bids().getFirst().price().compareTo(depth.asks().getFirst().price()) >= 0) {
            reasons.add(CROSSED_EXECUTABLE_BOOK);
        }
    }

    private static boolean validLevels(List<BookLevel> levels) {
        return levels.stream().allMatch(level -> level != null && positive(level.price())
                && nonNegative(level.size()) && level.orderCount() != null && level.orderCount() >= 0
                && level.liquidationOrderCount() != null && level.liquidationOrderCount() >= 0);
    }

    private static boolean strictlySorted(List<BookLevel> levels, boolean descending) {
        for (int index = 1; index < levels.size(); index++) {
            int comparison = levels.get(index - 1).price().compareTo(levels.get(index).price());
            if (descending ? comparison <= 0 : comparison >= 0) return false;
        }
        return true;
    }

    private static void validateFill(FillLifecycle fill, EnumSet<CommonEvidenceGapReason> reasons) {
        if (fill == null || !positive(fill.price())) reasons.add(MISSING_FILL_PRICE);
        if (fill == null || !positive(fill.quantity())) reasons.add(MISSING_FILL_QUANTITY);
        if (fill == null || fill.side() == null) reasons.add(MISSING_FILL_SIDE);
        if (fill == null || fill.lifecycle() == null) reasons.add(MISSING_ORDER_LIFECYCLE);
        if (fill == null || fill.liquidityRole() == null) reasons.add(MISSING_LIQUIDITY_ROLE);
    }

    private static void validateAmount(ReconciledSignedAmount amount,
                                       CommonEvidenceGapReason identityReason,
                                       CommonEvidenceGapReason mismatchReason,
                                       EnumSet<CommonEvidenceGapReason> reasons) {
        if (amount == null || blank(amount.identity()) || amount.signedAmount() == null || blank(amount.currency())) {
            reasons.add(identityReason);
            return;
        }
        if (!positive(amount.notional()) || amount.reconciledNotional() == null
                || amount.notional().compareTo(amount.reconciledNotional()) != 0) reasons.add(mismatchReason);
    }

    private static boolean completeDecision(DecisionLink link) {
        return link != null && !blank(link.decisionId()) && !blank(link.interval()) && link.side() != null
                && !blank(link.barId()) && !blank(link.action());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean nonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static EnumSet<CommonEvidenceGapReason> copy(List<CommonEvidenceGapReason> reasons) {
        return reasons.isEmpty() ? EnumSet.noneOf(CommonEvidenceGapReason.class) : EnumSet.copyOf(reasons);
    }

    private static Result result(EvidenceConsumerReadiness readiness, Collection<CommonEvidenceGapReason> reasons) {
        return new Result(readiness, List.copyOf(reasons));
    }
}
