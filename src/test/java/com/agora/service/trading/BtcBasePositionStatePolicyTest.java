package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BtcBasePositionStatePolicyTest {

    @Test
    void distinguishesLegacyTv509AndDraExitOwnership() {
        assertState(
                BtcBasePositionStatePolicy.ADOPTED_FROM_OCO_PREFIX + "123",
                "ADOPTED_FROM_OCO",
                "NONE",
                "LEGACY_BTC_BASE");
        assertState(
                BtcBasePositionStatePolicy.TV509_POSITION_PREFIX + "OPEN:bar=1",
                "TV509_OWNED",
                "PER_LOT_NET_PROFIT_TARGET",
                "TV509");
        assertState(
                BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX + "OPEN:bar=1",
                "DRA_V1_OWNED",
                "PER_LOT_NET_PROFIT_TARGET",
                "DRA_V1");
        assertState(
                BtcBasePositionStatePolicy.BTC_BASE_PREFIX + "MANUAL",
                "NATIVE_BTC_BASE",
                "NONE",
                "BTC_BASE_OTHER");
    }

    private static void assertState(String filterReason, String managementState,
                                    String automaticExitPolicy, String economicOwner) {
        BtLiveSignal position = new BtLiveSignal();
        position.setFilterReason(filterReason);

        assertEquals(managementState, BtcBasePositionStatePolicy.managementState(position));
        assertEquals(automaticExitPolicy, BtcBasePositionStatePolicy.automaticExitPolicy(position));
        assertEquals(economicOwner, BtcBasePositionStatePolicy.economicOwner(position));
    }
}
