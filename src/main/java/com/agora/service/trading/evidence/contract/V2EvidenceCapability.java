package com.agora.service.trading.evidence.contract;

/** One and only one storage/capability classification for every common evidence field. */
public enum V2EvidenceCapability {
    EXECUTABLE_FULL_DEPTH(Capability.V2_DIRECT),
    SIGNED_FILL_FEE(Capability.V2_DIRECT),
    ACTUAL_FUNDING_BILL(Capability.V2_DIRECT),
    MARGIN_SNAPSHOT(Capability.V2_DIRECT),
    DECISION_LINK(Capability.CODE_CONTRACT_ONLY),
    TIMESTAMP_CHAIN(Capability.CODE_CONTRACT_ONLY),
    FILL_PRICE_QUANTITY_SIDE(Capability.CODE_CONTRACT_ONLY),
    ORDER_LIFECYCLE(Capability.CODE_CONTRACT_ONLY),
    LIQUIDITY_ROLE(Capability.CODE_CONTRACT_ONLY),
    FILTER_GUARD_PROOF(Capability.CODE_CONTRACT_ONLY),
    REQUESTED_QUANTITY(Capability.CODE_CONTRACT_ONLY),
    COUNTERFACTUAL_FUNDING(Capability.FUTURE_MIGRATION);

    private final Capability capability;

    V2EvidenceCapability(Capability capability) {
        this.capability = capability;
    }

    public Capability capability() {
        return capability;
    }

    public enum Capability {
        V2_DIRECT,
        CODE_CONTRACT_ONLY,
        FUTURE_MIGRATION
    }
}
