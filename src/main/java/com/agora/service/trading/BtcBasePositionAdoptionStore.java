package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Transactional state transitions for the external OCO-cancel adoption saga. */
@Service
@RequiredArgsConstructor
public class BtcBasePositionAdoptionStore {

    private static final String BTCUSDT = "BTCUSDT";

    private final BtLiveSignalRepository liveSignalRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransitionResult markPending(Long positionId,
                                        Long expectedOcoAlgoId,
                                        BigDecimal expectedQty) {
        BtLiveSignal position = liveSignalRepository.findByIdForUpdate(positionId).orElse(null);
        String blocker = validateBase(position, positionId);
        if (blocker != null) return TransitionResult.blocked(positionId, blocker);

        if (BtcBasePositionStatePolicy.isAdoptedFromOco(position)) {
            if (position.getOcoOrderListId() != null) {
                return TransitionResult.blocked(positionId, "ADOPTED_MARKER_WITH_ACTIVE_OCO");
            }
            if (position.getOcoQty() != null) {
                return TransitionResult.blocked(positionId, "ADOPTED_MARKER_WITH_OCO_QTY");
            }
            return TransitionResult.alreadyAdopted(position);
        }
        if (BtcBasePositionStatePolicy.isBtcBase(position)
                && !BtcBasePositionStatePolicy.isAdoptionPending(position)) {
            return TransitionResult.blocked(positionId, "ALREADY_NATIVE_BTC_BASE");
        }

        Long markerAlgoId = BtcBasePositionStatePolicy.originalOcoAlgoId(position);
        if (BtcBasePositionStatePolicy.isAdoptionPending(position)) {
            if (markerAlgoId == null || !markerAlgoId.equals(expectedOcoAlgoId)) {
                return TransitionResult.blocked(positionId, "PENDING_OCO_REFERENCE_MISMATCH");
            }
            if (position.getOcoOrderListId() != null
                    && !expectedOcoAlgoId.equals(position.getOcoOrderListId())) {
                return TransitionResult.blocked(positionId, "OCO_REFERENCE_CHANGED_WHILE_PENDING");
            }
            String qtyBlocker = validateExactQty(position, expectedQty);
            return qtyBlocker == null
                    ? TransitionResult.pending(position)
                    : TransitionResult.blocked(positionId, qtyBlocker);
        }

        if (expectedOcoAlgoId == null || !expectedOcoAlgoId.equals(position.getOcoOrderListId())) {
            return TransitionResult.blocked(positionId, "OCO_REFERENCE_CHANGED_BEFORE_PENDING");
        }
        String qtyBlocker = validateExactQty(position, expectedQty);
        if (qtyBlocker != null) return TransitionResult.blocked(positionId, qtyBlocker);

        String pendingMarker;
        try {
            pendingMarker = BtcBasePositionStatePolicy.pendingMarker(
                    expectedOcoAlgoId, position.getFilterReason());
        } catch (IllegalArgumentException e) {
            return TransitionResult.blocked(positionId, e.getMessage());
        }
        position.setFilterReason(pendingMarker);
        position = liveSignalRepository.saveAndFlush(position);
        return TransitionResult.pending(position);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransitionResult finalizeManaged(Long positionId,
                                            Long expectedOcoAlgoId,
                                            BigDecimal expectedQty) {
        BtLiveSignal position = liveSignalRepository.findByIdForUpdate(positionId).orElse(null);
        String blocker = validateBase(position, positionId);
        if (blocker != null) return TransitionResult.blocked(positionId, blocker);

        if (BtcBasePositionStatePolicy.isAdoptedFromOco(position)) {
            if (position.getOcoOrderListId() != null) {
                return TransitionResult.blocked(positionId, "ADOPTED_MARKER_WITH_ACTIVE_OCO");
            }
            if (position.getOcoQty() != null) {
                return TransitionResult.blocked(positionId, "ADOPTED_MARKER_WITH_OCO_QTY");
            }
            return TransitionResult.alreadyAdopted(position);
        }
        if (!BtcBasePositionStatePolicy.isAdoptionPending(position)) {
            return TransitionResult.blocked(positionId, "ADOPTION_PENDING_MARKER_REQUIRED");
        }
        Long markerAlgoId = BtcBasePositionStatePolicy.originalOcoAlgoId(position);
        if (markerAlgoId == null || !markerAlgoId.equals(expectedOcoAlgoId)) {
            return TransitionResult.blocked(positionId, "PENDING_OCO_REFERENCE_MISMATCH");
        }
        if (position.getOcoOrderListId() != null
                && !expectedOcoAlgoId.equals(position.getOcoOrderListId())) {
            return TransitionResult.blocked(positionId, "OCO_REFERENCE_CHANGED_BEFORE_FINALIZE");
        }
        String qtyBlocker = validateExactQty(position, expectedQty);
        if (qtyBlocker != null) return TransitionResult.blocked(positionId, qtyBlocker);

        position.setFilterReason(BtcBasePositionStatePolicy.adoptedMarkerFromPending(
                position.getFilterReason()));
        position.setOcoOrderListId(null);
        position.setOcoQty(null);
        position = liveSignalRepository.saveAndFlush(position);
        return TransitionResult.adopted(position);
    }

    private String validateBase(BtLiveSignal position, Long positionId) {
        if (positionId == null || position == null) return "POSITION_NOT_FOUND";
        if (!Boolean.TRUE.equals(position.getAutoTraded())) return "NOT_AUTO_TRADED";
        if (position.getExitTime() != null) return "POSITION_ALREADY_CLOSED";
        if (!"LONG".equalsIgnoreCase(position.getSide())) return "SPOT_LONG_ONLY";
        String symbol = position.getSymbol() == null ? ""
                : position.getSymbol().replace("-", "").replace("/", "");
        if (!BTCUSDT.equalsIgnoreCase(symbol)) return "BTCUSDT_ONLY_V1";
        return null;
    }

    private String validateExactQty(BtLiveSignal position, BigDecimal expectedQty) {
        if (!positive(position.getTradedQty())) return "TRADED_QTY_MISSING";
        if (!positive(position.getOcoQty())) return "OCO_QTY_MISSING";
        if (position.getTradedQty().compareTo(position.getOcoQty()) != 0) {
            return "TRADED_QTY_OCO_QTY_MISMATCH";
        }
        if (expectedQty == null || position.getTradedQty().compareTo(expectedQty) != 0) {
            return "EXPECTED_QTY_CHANGED";
        }
        return null;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public record TransitionResult(Long positionId,
                                   String status,
                                   String reason,
                                   Long ocoAlgoId,
                                   BigDecimal tradedQty,
                                   String filterReason) {
        static TransitionResult blocked(Long id, String reason) {
            return new TransitionResult(id, "BLOCKED", reason, null, null, null);
        }

        static TransitionResult pending(BtLiveSignal position) {
            return new TransitionResult(position.getId(), "PENDING", "READY_TO_CANCEL_OR_RESUME",
                    BtcBasePositionStatePolicy.originalOcoAlgoId(position),
                    position.getTradedQty(), position.getFilterReason());
        }

        static TransitionResult adopted(BtLiveSignal position) {
            return new TransitionResult(position.getId(), "ADOPTED", "OCO_CANCELED_BTC_RETAINED",
                    BtcBasePositionStatePolicy.originalOcoAlgoId(position),
                    position.getTradedQty(), position.getFilterReason());
        }

        static TransitionResult alreadyAdopted(BtLiveSignal position) {
            return new TransitionResult(position.getId(), "ALREADY_ADOPTED", "IDEMPOTENT_NO_ACTION",
                    BtcBasePositionStatePolicy.originalOcoAlgoId(position),
                    position.getTradedQty(), position.getFilterReason());
        }

        public boolean success() {
            return "PENDING".equals(status)
                    || "ADOPTED".equals(status)
                    || "ALREADY_ADOPTED".equals(status);
        }
    }
}
