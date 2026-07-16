package com.agora.service.trading.evidence.contract;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static com.agora.service.trading.evidence.contract.V2EvidenceCapability.Capability.*;
import static org.assertj.core.api.Assertions.assertThat;

class V2EvidenceCapabilityTest {

    @Test
    void everyFieldHasExactlyOneCanonicalCapabilityWithoutUnknown() {
        Map<V2EvidenceCapability, V2EvidenceCapability.Capability> expected = new EnumMap<>(V2EvidenceCapability.class);
        expected.put(V2EvidenceCapability.EXECUTABLE_FULL_DEPTH, V2_DIRECT);
        expected.put(V2EvidenceCapability.SIGNED_FILL_FEE, V2_DIRECT);
        expected.put(V2EvidenceCapability.ACTUAL_FUNDING_BILL, V2_DIRECT);
        expected.put(V2EvidenceCapability.MARGIN_SNAPSHOT, V2_DIRECT);
        expected.put(V2EvidenceCapability.DECISION_LINK, CODE_CONTRACT_ONLY);
        expected.put(V2EvidenceCapability.TIMESTAMP_CHAIN, CODE_CONTRACT_ONLY);
        expected.put(V2EvidenceCapability.FILL_PRICE_QUANTITY_SIDE, CODE_CONTRACT_ONLY);
        expected.put(V2EvidenceCapability.ORDER_LIFECYCLE, CODE_CONTRACT_ONLY);
        expected.put(V2EvidenceCapability.LIQUIDITY_ROLE, CODE_CONTRACT_ONLY);
        expected.put(V2EvidenceCapability.FILTER_GUARD_PROOF, CODE_CONTRACT_ONLY);
        expected.put(V2EvidenceCapability.REQUESTED_QUANTITY, CODE_CONTRACT_ONLY);
        expected.put(V2EvidenceCapability.COUNTERFACTUAL_FUNDING, FUTURE_MIGRATION);

        assertThat(V2EvidenceCapability.values()).hasSize(expected.size());
        assertThat(V2EvidenceCapability.values()).allSatisfy(field ->
                assertThat(field.capability()).isEqualTo(expected.get(field)));
        assertThat(V2EvidenceCapability.Capability.values())
                .containsExactly(V2_DIRECT, CODE_CONTRACT_ONLY, FUTURE_MIGRATION);
    }
}
