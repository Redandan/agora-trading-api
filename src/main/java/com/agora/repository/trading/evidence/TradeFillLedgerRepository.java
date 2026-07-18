package com.agora.repository.trading.evidence;

import com.agora.model.evidence.ImmutableTradeFill;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface TradeFillLedgerRepository extends Repository<ImmutableTradeFill, Long> {
    List<ImmutableTradeFill> findBySourceRunIdOrderByFillAtAscIdAsc(String sourceRunId);
    List<ImmutableTradeFill> findByCohortIdAndRuntimeDecisionIdAndLiveSignalIdOrderByFillAtAscIdAsc(
            String cohortId, Long runtimeDecisionId, Long liveSignalId);
}
