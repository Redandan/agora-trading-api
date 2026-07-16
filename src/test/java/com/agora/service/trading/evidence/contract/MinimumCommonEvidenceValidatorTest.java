package com.agora.service.trading.evidence.contract;

import com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.Result;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.BookLevel;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.DecisionLink;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.DepthCoverage;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.ExecutableDepth;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.ActualFundingBill;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.CounterfactualFunding;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.FillLifecycle;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.FundingEvidence;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.FundingSemantic;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.ReconciledSignedAmount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.agora.service.trading.evidence.contract.CommonEvidenceGapReason.*;
import static com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.*;
import static org.assertj.core.api.Assertions.assertThat;

class MinimumCommonEvidenceValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MinimumCommonEvidenceValidator validator = new MinimumCommonEvidenceValidator();

    @Test
    void completeTypedFixtureIsOnlyObservedReady() throws Exception {
        MinimumCommonEvidenceContract evidence = fixture("complete-common-evidence.json");
        Result result = validator.validateCommon(evidence);

        assertThat(result.readiness()).isEqualTo(OBSERVED_READY);
        assertThat(result.gapReasons()).isEmpty();
        assertThat(result.readyForShadowOrEdge()).isFalse();
        assertThat(evidence.funding().actualBill()).isInstanceOf(ActualFundingBill.class);
        assertThat(evidence.funding().counterfactualFunding()).isInstanceOf(CounterfactualFunding.class);
        assertThat(evidence.funding().actualBill().semantic()).isEqualTo(FundingSemantic.ACTUAL_SETTLED_BILL);
        assertThat(evidence.funding().counterfactualFunding().semantic())
                .isEqualTo(FundingSemantic.DECISION_COUNTERFACTUAL_ESTIMATE);
        assertThat(evidence.executableDepth().coverage().allSourceLevelsCaptured()).isTrue();
        assertThat(evidence.executableDepth().coverage().capturedBidLevelCount())
                .isEqualTo(evidence.executableDepth().bids().size());
        assertThat(evidence.executableDepth().coverage().capturedAskLevelCount())
                .isEqualTo(evidence.executableDepth().asks().size());
    }

    @Test
    void fillNotionalReconcilesAtV2TwelveDecimalPrecision() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        BigDecimal withinPrecision = new BigDecimal("100.5000000000004");
        ReconciledSignedAmount fee = new ReconciledSignedAmount(base.signedFee().identity(), withinPrecision,
                withinPrecision, base.signedFee().signedAmount(), base.signedFee().currency());
        ActualFundingBill actual = new ActualFundingBill(base.funding().actualBill().identity(),
                base.funding().actualBill().semantic(), withinPrecision, withinPrecision,
                base.funding().actualBill().signedAmount(), base.funding().actualBill().currency());
        CounterfactualFunding counterfactual = new CounterfactualFunding(
                base.funding().counterfactualFunding().identity(), base.funding().counterfactualFunding().semantic(),
                withinPrecision, withinPrecision, base.funding().counterfactualFunding().signedAmount(),
                base.funding().counterfactualFunding().currency());

        Result result = validator.validateCommon(copy(base, base.decisionLink(), base.executableDepth(), base.fill(),
                fee, new FundingEvidence(actual, counterfactual)));

        assertThat(result.readiness()).isEqualTo(OBSERVED_READY);
        assertThat(result.gapReasons()).isEmpty();
    }

    @Test
    void missingDecisionLinkIsAStableObservedGap() throws Exception {
        Result result = validator.validateCommon(fixture("missing-decision-link.json"));

        assertThat(result.readiness()).isEqualTo(OBSERVED_GAP);
        assertThat(result.gapReasons()).containsExactly(MISSING_DECISION_LINK);
    }

    @Test
    void anyTimestampOrderingFailureEmitsOneMachineReason() throws Exception {
        Result result = validator.validateCommon(fixture("malformed-timestamp-chain.json"));

        assertThat(result.readiness()).isEqualTo(OBSERVED_INVALID);
        assertThat(result.gapReasons()).containsExactly(TIMESTAMP_CHAIN_ORDER_VIOLATION);
    }

    @Test
    void scalarQuoteCannotPassAsExecutableDepth() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");

        Result result = validator.validateCommon(copy(base, base.decisionLink(), null, base.fill(), base.funding()));

        assertThat(result.readiness()).isEqualTo(OBSERVED_INVALID);
        assertThat(result.gapReasons()).containsExactly(MISSING_EXECUTABLE_FULL_DEPTH);
    }

    @Test
    void fullDepthRequiresExactLevelValuesSortingAndUncrossedTop() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        ExecutableDepth badLevel = new ExecutableDepth(MinimumCommonEvidenceContract.DepthKind.FULL_DEPTH,
                List.of(new BookLevel(BigDecimal.ZERO, BigDecimal.ONE, 1L, 0L), level("99")),
                base.executableDepth().asks(), base.executableDepth().coverage());
        ExecutableDepth unsorted = new ExecutableDepth(MinimumCommonEvidenceContract.DepthKind.FULL_DEPTH,
                List.of(level("99"), level("100")), base.executableDepth().asks(), base.executableDepth().coverage());
        ExecutableDepth crossed = new ExecutableDepth(MinimumCommonEvidenceContract.DepthKind.FULL_DEPTH,
                List.of(level("102"), level("101")), base.executableDepth().asks(), base.executableDepth().coverage());

        assertThat(validator.validateCommon(copy(base, base.decisionLink(), badLevel, base.fill(), base.funding())).gapReasons())
                .containsExactly(INVALID_EXECUTABLE_DEPTH_LEVEL);
        assertThat(validator.validateCommon(copy(base, base.decisionLink(), unsorted, base.fill(), base.funding())).gapReasons())
                .containsExactly(INVALID_EXECUTABLE_DEPTH_SORT_ORDER);
        assertThat(validator.validateCommon(copy(base, base.decisionLink(), crossed, base.fill(), base.funding())).gapReasons())
                .containsExactly(CROSSED_EXECUTABLE_BOOK);
    }

    @Test
    void decisionIntervalSideBarAndActionCannotBeMerged() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        DecisionLink different = new DecisionLink("decision-1", "5m", MinimumCommonEvidenceContract.Side.SELL,
                "bar-2", "EXIT");

        Result result = validator.validateSameDecisionGroup(List.of(base,
                copy(base, different, base.executableDepth(), base.fill(), base.funding())));

        assertThat(result.readiness()).isEqualTo(OBSERVED_INVALID);
        assertThat(result.gapReasons()).containsExactly(MIXED_DECISION_DIMENSIONS);
    }

    @Test
    void actualFundingBillCannotReplaceCounterfactualFunding() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        MinimumCommonEvidenceContract.FundingEvidence actualOnly =
                new MinimumCommonEvidenceContract.FundingEvidence(base.funding().actualBill(), null);

        Result result = validator.validateCommon(copy(base, base.decisionLink(), base.executableDepth(),
                base.fill(), actualOnly));

        assertThat(result.readiness()).isEqualTo(OBSERVED_GAP);
        assertThat(result.gapReasons()).containsExactly(MISSING_COUNTERFACTUAL_FUNDING);
    }

    @Test
    void actualAndCounterfactualFundingHaveDistinctTypesIdentitiesAndSemantics() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        ActualFundingBill actual = base.funding().actualBill();
        CounterfactualFunding alias = new CounterfactualFunding(actual.identity(),
                FundingSemantic.ACTUAL_SETTLED_BILL, actual.notional(), actual.reconciledNotional(),
                actual.signedAmount(), actual.currency());

        Result result = validator.validateCommon(copy(base, base.decisionLink(), base.executableDepth(), base.fill(),
                base.signedFee(), new FundingEvidence(actual, alias)));

        assertThat(actual.getClass()).isNotEqualTo(alias.getClass());
        assertThat(result.readiness()).isEqualTo(OBSERVED_GAP);
        assertThat(result.gapReasons()).containsExactly(
                COUNTERFACTUAL_FUNDING_IDENTITY_MISMATCH,
                COUNTERFACTUAL_FUNDING_SEMANTIC_MISMATCH,
                FUNDING_IDENTITY_ALIAS);
    }

    @Test
    void selfConsistentButUnrelatedFeeAndFundingNotionalsFailAgainstFill() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        ReconciledSignedAmount badFee = new ReconciledSignedAmount("unrelated", new BigDecimal("999"),
                new BigDecimal("999"), new BigDecimal("-1"), "USDT");
        ActualFundingBill badActual = new ActualFundingBill("unrelated-actual", FundingSemantic.ACTUAL_SETTLED_BILL,
                new BigDecimal("999"), new BigDecimal("999"), new BigDecimal("-1"), "USDT");
        CounterfactualFunding badCounterfactual = new CounterfactualFunding("unrelated-counterfactual",
                FundingSemantic.DECISION_COUNTERFACTUAL_ESTIMATE, new BigDecimal("999"), new BigDecimal("999"),
                new BigDecimal("-1"), "USDT");

        Result result = validator.validateCommon(copy(base, base.decisionLink(), base.executableDepth(), base.fill(),
                badFee, new FundingEvidence(badActual, badCounterfactual)));

        assertThat(result.gapReasons()).containsExactly(
                SIGNED_FEE_IDENTITY_MISMATCH,
                SIGNED_FEE_NOTIONAL_MISMATCH,
                ACTUAL_FUNDING_IDENTITY_MISMATCH,
                ACTUAL_FUNDING_NOTIONAL_MISMATCH,
                COUNTERFACTUAL_FUNDING_IDENTITY_MISMATCH,
                COUNTERFACTUAL_FUNDING_NOTIONAL_MISMATCH);
    }

    @Test
    void blankOrderAndTradeIdentifiersFailClosed() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        FillLifecycle blankIdentity = new FillLifecycle(" ", "", base.fill().price(), base.fill().quantity(),
                base.fill().side(), base.fill().lifecycle(), base.fill().liquidityRole());

        Result result = validator.validateCommon(copy(base, base.decisionLink(), base.executableDepth(), blankIdentity,
                base.signedFee(), base.funding()));

        assertThat(result.gapReasons()).contains(MISSING_ORDER_ID, MISSING_TRADE_ID,
                SIGNED_FEE_IDENTITY_MISMATCH, ACTUAL_FUNDING_IDENTITY_MISMATCH,
                COUNTERFACTUAL_FUNDING_IDENTITY_MISMATCH);
    }

    @Test
    void depthCoverageMustBePresentCompleteAndNotTruncated() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        ExecutableDepth missing = new ExecutableDepth(base.executableDepth().kind(), base.executableDepth().bids(),
                base.executableDepth().asks(), null);
        DepthCoverage incompleteProof = new DepthCoverage("okx:s", 2, 2, 2, 2,
                false, true, true);
        ExecutableDepth incomplete = new ExecutableDepth(base.executableDepth().kind(), base.executableDepth().bids(),
                base.executableDepth().asks(), incompleteProof);
        DepthCoverage truncatedProof = new DepthCoverage("okx:s", 3, 2, 2, 2,
                true, false, false);
        ExecutableDepth truncated = new ExecutableDepth(base.executableDepth().kind(), base.executableDepth().bids(),
                base.executableDepth().asks(), truncatedProof);

        assertThat(validateDepth(base, missing).gapReasons()).containsExactly(MISSING_EXECUTABLE_DEPTH_COVERAGE);
        assertThat(validateDepth(base, incomplete).gapReasons()).containsExactly(INCOMPLETE_EXECUTABLE_DEPTH_PAGE);
        assertThat(validateDepth(base, truncated).gapReasons()).containsExactly(TRUNCATED_EXECUTABLE_DEPTH);
    }

    @Test
    void bestLevelOnlyCannotMasqueradeAsFullDepthEvenWithDeclaredCoverage() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        DepthCoverage declared = new DepthCoverage("okx:best", 1, 1, 1, 1, true, true, true);
        ExecutableDepth bestOnly = new ExecutableDepth(base.executableDepth().kind(),
                List.of(base.executableDepth().bids().getFirst()),
                List.of(base.executableDepth().asks().getFirst()), declared);

        Result result = validateDepth(base, bestOnly);

        assertThat(result.readiness()).isEqualTo(OBSERVED_INVALID);
        assertThat(result.gapReasons()).containsExactly(BEST_LEVEL_ONLY_NOT_FULL_DEPTH);
    }

    private Result validateDepth(MinimumCommonEvidenceContract base, ExecutableDepth depth) {
        return validator.validateCommon(copy(base, base.decisionLink(), depth, base.fill(),
                base.signedFee(), base.funding()));
    }

    private BookLevel level(String price) {
        return new BookLevel(new BigDecimal(price), BigDecimal.ONE, 1L, 0L);
    }

    private MinimumCommonEvidenceContract fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/common-evidence/" + name)) {
            return mapper.readValue(input, MinimumCommonEvidenceContract.class);
        }
    }

    private MinimumCommonEvidenceContract copy(MinimumCommonEvidenceContract base,
                                               DecisionLink decision,
                                               ExecutableDepth depth,
                                               FillLifecycle fill,
                                               FundingEvidence funding) {
        return copy(base, decision, depth, fill, base.signedFee(), funding);
    }

    private MinimumCommonEvidenceContract copy(MinimumCommonEvidenceContract base,
                                               DecisionLink decision,
                                               ExecutableDepth depth,
                                               FillLifecycle fill,
                                               ReconciledSignedAmount signedFee,
                                               FundingEvidence funding) {
        return new MinimumCommonEvidenceContract(base.version(), base.specSha256(), base.sourceCommit(), decision,
                base.timestamps(), depth, fill, signedFee, funding, base.margin(),
                base.filterCounterfactual(), base.makerFirst());
    }
}
