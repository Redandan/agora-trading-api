package com.agora.service.trading.evidence.okx;

import com.agora.config.OkxEvidenceProperties;
import com.agora.repository.trading.evidence.OkxEvidenceAppendRepository;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.AppendCommand;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.NormalizationBatch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Guarded all-or-nothing append boundary. No scheduler or automatic invocation exists. */
@Service
@RequiredArgsConstructor
public class OkxEvidenceAppendService {

    private final OkxEvidenceProperties properties;
    private final OkxEvidenceAppendRepository repository;

    @Transactional
    public List<OkxEvidenceAppendRepository.AppendResult> append(NormalizationBatch batch,
                                                                 boolean authenticatedProviderRead) {
        if (!properties.isCollectorEnabled()) {
            throw new EvidenceAppendBlockedException("OKX_EVIDENCE_COLLECTOR_DISABLED");
        }
        if (authenticatedProviderRead && !properties.isAuthenticatedIngestionEnabled()) {
            throw new EvidenceAppendBlockedException("OKX_AUTHENTICATED_INGESTION_DISABLED");
        }
        if (batch == null || !batch.validForAppend()) {
            throw new EvidenceAppendBlockedException("OKX_EVIDENCE_BATCH_NOT_CAUSALLY_COMPLETE");
        }
        List<OkxEvidenceAppendRepository.AppendResult> results = new ArrayList<>();
        for (AppendCommand command : batch.accepted()) {
            results.add(repository.append(command));
        }
        return List.copyOf(results);
    }

    public static final class EvidenceAppendBlockedException extends RuntimeException {
        public EvidenceAppendBlockedException(String message) {
            super(message);
        }
    }
}
