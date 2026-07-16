package com.agora.service.trading.evidence.contract;

/** Consumer-only proof that must accompany common evidence for filter counterfactuals. */
public record FilterCounterfactualConsumerContract(Boolean blockerProofPresent,
                                                   GateProof eventRisk,
                                                   GateProof expectedValue,
                                                   Boolean fearGreedCausalEvidencePresent) {
    public enum GateProof {
        PASS,
        FAIL,
        MISSING
    }
}
