package com.agora.service.trading.evidence.contract;

import com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.Result;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.BookLevel;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.DecisionLink;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.ExecutableDepth;
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
        Result result = validator.validateCommon(fixture("complete-common-evidence.json"));

        assertThat(result.readiness()).isEqualTo(OBSERVED_READY);
        assertThat(result.gapReasons()).isEmpty();
        assertThat(result.readyForShadowOrEdge()).isFalse();
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
                List.of(new BookLevel(BigDecimal.ZERO, BigDecimal.ONE, 1L, 0L)), base.executableDepth().asks());
        ExecutableDepth unsorted = new ExecutableDepth(MinimumCommonEvidenceContract.DepthKind.FULL_DEPTH,
                List.of(level("99"), level("100")), base.executableDepth().asks());
        ExecutableDepth crossed = new ExecutableDepth(MinimumCommonEvidenceContract.DepthKind.FULL_DEPTH,
                List.of(level("102"), level("101")), base.executableDepth().asks());

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
                                               MinimumCommonEvidenceContract.FillLifecycle fill,
                                               MinimumCommonEvidenceContract.FundingEvidence funding) {
        return new MinimumCommonEvidenceContract(base.version(), base.specSha256(), base.sourceCommit(), decision,
                base.timestamps(), depth, fill, base.signedFee(), funding, base.margin(),
                base.filterCounterfactual(), base.makerFirst());
    }
}
