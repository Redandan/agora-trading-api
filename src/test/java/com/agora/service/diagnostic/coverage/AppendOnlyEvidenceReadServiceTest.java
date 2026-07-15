package com.agora.service.diagnostic.coverage;

import com.agora.model.evidence.ExecutableQuoteSnapshot;
import com.agora.repository.trading.evidence.ExecutableQuoteSnapshotRepository;
import com.agora.repository.trading.evidence.FillFeeLedgerRepository;
import com.agora.repository.trading.evidence.FundingBillLedgerRepository;
import com.agora.repository.trading.evidence.MarginSnapshotRepository;
import com.agora.repository.trading.evidence.ReadOnlyEvidenceRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppendOnlyEvidenceReadServiceTest {

    @Test
    void repositoryContractExposesNoMutationMethods() {
        assertThat(Arrays.stream(ReadOnlyEvidenceRepository.class.getMethods())
                .map(Method::getName))
                .noneMatch(name -> name.matches("save|saveAll|delete|deleteAll|flush"));
    }

    @Test
    void summarizesEmptyTablesWithoutClaimingIngestion() {
        ExecutableQuoteSnapshotRepository quote = mock(ExecutableQuoteSnapshotRepository.class);
        FillFeeLedgerRepository fee = mock(FillFeeLedgerRepository.class);
        FundingBillLedgerRepository funding = mock(FundingBillLedgerRepository.class);
        MarginSnapshotRepository margin = mock(MarginSnapshotRepository.class);
        LocalDateTime start = LocalDateTime.parse("2026-07-15T00:00:00");
        LocalDateTime end = start.plusHours(1);
        when(quote.findFirstByOrderByEventAtAsc()).thenReturn(Optional.empty());
        when(quote.findFirstByOrderByEventAtDesc()).thenReturn(Optional.empty());
        when(fee.findFirstByOrderByEventAtAsc()).thenReturn(Optional.empty());
        when(fee.findFirstByOrderByEventAtDesc()).thenReturn(Optional.empty());
        when(funding.findFirstByOrderByEventAtAsc()).thenReturn(Optional.empty());
        when(funding.findFirstByOrderByEventAtDesc()).thenReturn(Optional.empty());
        when(margin.findFirstByOrderByEventAtAsc()).thenReturn(Optional.empty());
        when(margin.findFirstByOrderByEventAtDesc()).thenReturn(Optional.empty());

        AppendOnlyEvidenceReadService service = new AppendOnlyEvidenceReadService(quote, fee, funding, margin);
        var result = service.summarize(start, end);

        assertThat(result).hasSize(4).allSatisfy(table -> {
            assertThat(table.observedCount()).isZero();
            assertThat(table.querySucceeded()).isTrue();
            assertThat(table.ingestionStatus()).isEqualTo("READ_ONLY_NO_PROVIDER_INGESTION");
        });
    }
}
