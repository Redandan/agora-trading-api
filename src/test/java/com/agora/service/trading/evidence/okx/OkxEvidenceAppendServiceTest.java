package com.agora.service.trading.evidence.okx;

import com.agora.config.OkxEvidenceProperties;
import com.agora.repository.trading.evidence.OkxEvidenceAppendRepository;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.NormalizationBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OkxEvidenceAppendServiceTest {

    @Test
    void collectorAndAuthenticatedIngestionDefaultDisabled() {
        OkxEvidenceProperties properties = new OkxEvidenceProperties();
        OkxEvidenceAppendRepository repository = mock(OkxEvidenceAppendRepository.class);
        OkxEvidenceAppendService service = new OkxEvidenceAppendService(properties, repository);

        assertThatThrownBy(() -> service.append(new NormalizationBatch(List.of(), List.of(), null, true), true))
                .isInstanceOf(OkxEvidenceAppendService.EvidenceAppendBlockedException.class)
                .hasMessage("OKX_EVIDENCE_COLLECTOR_DISABLED");
        verifyNoInteractions(repository);
    }

    @Test
    void authenticatedReadStillBlockedWhenCollectorAloneIsEnabled() {
        OkxEvidenceProperties properties = new OkxEvidenceProperties();
        properties.setCollectorEnabled(true);
        OkxEvidenceAppendRepository repository = mock(OkxEvidenceAppendRepository.class);
        OkxEvidenceAppendService service = new OkxEvidenceAppendService(properties, repository);

        assertThatThrownBy(() -> service.append(new NormalizationBatch(List.of(), List.of(), null, true), true))
                .hasMessage("OKX_AUTHENTICATED_INGESTION_DISABLED");
        verifyNoInteractions(repository);
    }
}
