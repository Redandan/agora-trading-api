package com.agora.repository.trading.evidence;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.CollectionAppend;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.CollectionRun;

import java.util.Optional;

/** V3 insert-only boundary. Implementations must never update, replace or upsert evidence. */
public interface ExactTradeFillAppendRepository {
    Optional<CollectionRun> findRun(String runId);

    Optional<PriorRun> latestCompleteRun(String provider, String accountRefHash,
                                         String instrumentId, String instrumentType, String bindingScopeSha256);
    AppendResult append(CollectionAppend collection);

    enum AppendResult { APPENDED, DUPLICATE_IDENTICAL }
    record PriorRun(String runId, String canonicalFillSetSha256) { }
}
