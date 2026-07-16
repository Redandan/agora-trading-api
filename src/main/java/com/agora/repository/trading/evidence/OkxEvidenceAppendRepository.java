package com.agora.repository.trading.evidence;

import com.agora.service.trading.evidence.okx.OkxEvidenceModels.AppendCommand;

/** Deliberately exposes only immutable append semantics. */
public interface OkxEvidenceAppendRepository {

    AppendResult append(AppendCommand command);

    enum AppendResult {
        APPENDED,
        DUPLICATE_IDENTICAL
    }
}
