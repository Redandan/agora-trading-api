package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BtcBasePositionStatePolicyTest {

    @Test
    void pendingAndFinalMarkersPreserveOriginalReasonAndOcoReference() {
        String pending = BtcBasePositionStatePolicy.pendingMarker(1260L, "legacy-reason|detail");
        assertThat(BtcBasePositionStatePolicy.isAdoptionPendingReason(pending)).isTrue();
        assertThat(BtcBasePositionStatePolicy.originalOcoAlgoId(pending)).isEqualTo(1260L);
        assertThat(BtcBasePositionStatePolicy.previousReason(pending)).isEqualTo("legacy-reason|detail");

        String adopted = BtcBasePositionStatePolicy.adoptedMarkerFromPending(pending);
        assertThat(BtcBasePositionStatePolicy.isAdoptedFromOcoReason(adopted)).isTrue();
        assertThat(BtcBasePositionStatePolicy.originalOcoAlgoId(adopted)).isEqualTo(1260L);
        assertThat(BtcBasePositionStatePolicy.previousReason(adopted)).isEqualTo("legacy-reason|detail");
    }

    @Test
    void intentionalNoOcoRequiresBothBtcBaseMarkerAndMissingOco() {
        BtLiveSignal position = new BtLiveSignal();
        position.setFilterReason(BtcBasePositionStatePolicy.pendingMarker(1260L, null));
        position.setOcoOrderListId(1260L);
        assertThat(BtcBasePositionStatePolicy.isIntentionalNoOco(position)).isFalse();

        position.setOcoOrderListId(null);
        assertThat(BtcBasePositionStatePolicy.isIntentionalNoOco(position)).isTrue();
        assertThat(BtcBasePositionStatePolicy.managementState(position)).isEqualTo("ADOPTION_PENDING");
    }

    @Test
    void oversizedPreviousReasonFailsInsteadOfLosingProvenance() {
        assertThatThrownBy(() -> BtcBasePositionStatePolicy.pendingMarker(1260L, "x".repeat(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("FILTER_REASON_TOO_LONG_TO_PRESERVE");
    }
}
