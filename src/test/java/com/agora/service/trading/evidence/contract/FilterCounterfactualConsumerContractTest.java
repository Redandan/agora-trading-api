package com.agora.service.trading.evidence.contract;

import com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static com.agora.service.trading.evidence.contract.CommonEvidenceGapReason.*;
import static com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.NOT_MEASURABLE;
import static com.agora.service.trading.evidence.contract.EvidenceConsumerReadiness.OBSERVED_READY;
import static org.assertj.core.api.Assertions.assertThat;

class FilterCounterfactualConsumerContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MinimumCommonEvidenceValidator validator = new MinimumCommonEvidenceValidator();

    @Test
    void completeProofIsObservedReadyButNeverShadowOrEdgeReady() throws Exception {
        Result result = validator.validateFilterCounterfactual(fixture("complete-common-evidence.json"));

        assertThat(result.readiness()).isEqualTo(OBSERVED_READY);
        assertThat(result.readyForShadowOrEdge()).isFalse();
    }

    @Test
    void everyMissingOrNonPassFilterProofMakesResultNotMeasurable() throws Exception {
        Result result = validator.validateFilterCounterfactual(fixture("filter-missing-guard-proof.json"));

        assertThat(result.readiness()).isEqualTo(NOT_MEASURABLE);
        assertThat(result.gapReasons()).containsExactly(
                MISSING_FILTER_BLOCKER_PROOF,
                EVENT_RISK_NOT_PASS,
                EXPECTED_VALUE_NOT_PASS,
                MISSING_FEAR_GREED_CAUSAL_EVIDENCE);
    }

    private MinimumCommonEvidenceContract fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/common-evidence/" + name)) {
            return mapper.readValue(input, MinimumCommonEvidenceContract.class);
        }
    }
}
