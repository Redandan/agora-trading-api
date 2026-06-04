package com.agora.repository.trading;

import com.agora.model.BtOcoAdjustmentAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BtOcoAdjustmentAuditRepository extends JpaRepository<BtOcoAdjustmentAudit, Long> {

    List<BtOcoAdjustmentAudit> findByLiveSignalIdOrderByEffectiveAtAsc(Long liveSignalId);

    Optional<BtOcoAdjustmentAudit> findFirstByLiveSignalIdOrderByEffectiveAtDesc(Long liveSignalId);

    Optional<BtOcoAdjustmentAudit> findFirstByLiveSignalIdAndNewOcoOrderListIdOrderByEffectiveAtDesc(
            Long liveSignalId, Long newOcoOrderListId);
}
