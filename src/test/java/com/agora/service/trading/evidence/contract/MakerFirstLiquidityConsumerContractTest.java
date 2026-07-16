package com.agora.service.trading.evidence.contract;

import com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.Result;
import com.agora.service.trading.evidence.contract.MinimumCommonEvidenceContract.FillLifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static com.agora.service.trading.evidence.contract.CommonEvidenceGapReason.*;
import static com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.NOT_MEASURABLE;
import static com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.OBSERVED_READY;
import static org.assertj.core.api.Assertions.assertThat;

class MakerFirstLiquidityConsumerContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MinimumCommonEvidenceValidator validator = new MinimumCommonEvidenceValidator();

    @Test
    void completeMakerEvidenceIsObservedReadyOnly() throws Exception {
        Result result = validator.validateMakerFirst(fixture("complete-common-evidence.json"));

        assertThat(result.readiness()).isEqualTo(OBSERVED_READY);
        assertThat(result.readyForShadowOrEdge()).isFalse();
    }

    @Test
    void missingLiquidityRoleIsNotMeasurable() throws Exception {
        Result result = validator.validateMakerFirst(fixture("maker-missing-liquidity-role.json"));

        assertThat(result.readiness()).isEqualTo(NOT_MEASURABLE);
        assertThat(result.gapReasons()).containsExactly(MISSING_LIQUIDITY_ROLE);
    }

    @Test
    void missingLifecycleAndRequestedQuantityAreEachMachineReadable() throws Exception {
        MinimumCommonEvidenceContract noLifecycle = fixture("quote-without-fill-lifecycle.json");
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        MinimumCommonEvidenceContract noRequested = copy(base, base.fill(),
                new MakerFirstLiquidityConsumerContract(null));

        assertThat(validator.validateMakerFirst(noLifecycle).gapReasons()).containsExactly(MISSING_ORDER_LIFECYCLE);
        assertThat(validator.validateMakerFirst(noRequested).gapReasons()).containsExactly(MISSING_REQUESTED_QUANTITY);
    }

    @Test
    void fillPriceQuantityAndSideAreIndependentRequiredInputs() throws Exception {
        MinimumCommonEvidenceContract base = fixture("complete-common-evidence.json");
        FillLifecycle missing = new FillLifecycle(base.fill().orderId(), base.fill().tradeId(), null, null, null,
                base.fill().lifecycle(), base.fill().liquidityRole());

        Result result = validator.validateMakerFirst(copy(base, missing, base.makerFirst()));

        assertThat(result.readiness()).isEqualTo(NOT_MEASURABLE);
        assertThat(result.gapReasons()).containsExactly(
                MISSING_FILL_PRICE,
                MISSING_FILL_QUANTITY,
                MISSING_FILL_SIDE,
                SIGNED_FEE_NOTIONAL_MISMATCH,
                ACTUAL_FUNDING_NOTIONAL_MISMATCH,
                COUNTERFACTUAL_FUNDING_NOTIONAL_MISMATCH);
    }

    private MinimumCommonEvidenceContract fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/common-evidence/" + name)) {
            return mapper.readValue(input, MinimumCommonEvidenceContract.class);
        }
    }

    private MinimumCommonEvidenceContract copy(MinimumCommonEvidenceContract base,
                                               FillLifecycle fill,
                                               MakerFirstLiquidityConsumerContract maker) {
        return new MinimumCommonEvidenceContract(base.version(), base.specSha256(), base.sourceCommit(),
                base.decisionLink(), base.timestamps(), base.executableDepth(), fill, base.signedFee(),
                base.funding(), base.margin(), base.filterCounterfactual(), maker);
    }
}
