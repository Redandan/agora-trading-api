package com.agora.repository.trading.evidence;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.CollectionAppend;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.CollectionRun;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;

import java.util.List;
import java.util.Optional;

/** V3 insert-only boundary. Implementations must never update, replace or upsert evidence. */
public interface ExactTradeFillAppendRepository {
    Optional<CollectionRun> findRun(String runId);

    /** Rebuilds one run through its immutable run-item graph; never trusts source_run_id alone. */
    List<RawFill> findRunFills(String runId);

    Optional<PriorRun> latestCompleteRun(String provider, String accountRefHash,
                                         String instrumentId, String instrumentType, String bindingScopeSha256);
    AppendResult append(CollectionAppend collection);

    enum AppendResult { APPENDED, DUPLICATE_IDENTICAL }
    record PriorRun(String runId, String canonicalFillSetSha256, String bindingScopeSha256) { }
}
