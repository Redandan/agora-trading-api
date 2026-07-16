package com.agora.service.trading.evidence.contract;

import java.util.List;

/** Local observation status only; this contract cannot authorize SHADOW or EDGE use. */
public enum EvidenceConsumerReadiness {
    OBSERVED_READY,
    OBSERVED_GAP,
    OBSERVED_INVALID,
    NOT_MEASURABLE;

    public record Result(EvidenceConsumerReadiness readiness,
                         List<CommonEvidenceGapReason> gapReasons) {
        public Result {
            gapReasons = gapReasons == null ? List.of() : List.copyOf(gapReasons);
        }

        public boolean readyForShadowOrEdge() {
            return false;
        }
    }
}
