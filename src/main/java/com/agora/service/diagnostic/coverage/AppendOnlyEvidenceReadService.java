package com.agora.service.diagnostic.coverage;

import com.agora.model.evidence.AppendOnlyEvidence;
import com.agora.repository.trading.evidence.ExecutableQuoteSnapshotRepository;
import com.agora.repository.trading.evidence.FillFeeLedgerRepository;
import com.agora.repository.trading.evidence.FundingBillLedgerRepository;
import com.agora.repository.trading.evidence.MarginSnapshotRepository;
import com.agora.repository.trading.evidence.ReadOnlyEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only visibility for the four append-only evidence tables. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppendOnlyEvidenceReadService {

    private final ExecutableQuoteSnapshotRepository quoteRepository;
    private final FillFeeLedgerRepository feeRepository;
    private final FundingBillLedgerRepository fundingRepository;
    private final MarginSnapshotRepository marginRepository;

    public List<EvidenceTableCoverage> summarize(LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        if (requestedStart == null || requestedEnd == null || !requestedStart.isBefore(requestedEnd)) {
            throw new IllegalArgumentException("requestedStart must be before requestedEnd");
        }
        return List.of(
                summarize("executable_quote_snapshot", quoteRepository, requestedStart, requestedEnd),
                summarize("fill_fee_ledger", feeRepository, requestedStart, requestedEnd),
                summarize("funding_bill_ledger", fundingRepository, requestedStart, requestedEnd),
                summarize("margin_snapshot", marginRepository, requestedStart, requestedEnd)
        );
    }

    private <T extends AppendOnlyEvidence> EvidenceTableCoverage summarize(
            String table,
            ReadOnlyEvidenceRepository<T> repository,
            LocalDateTime start,
            LocalDateTime end) {
        return new EvidenceTableCoverage(
                table,
                start,
                end,
                repository.countByEventAtGreaterThanEqualAndEventAtLessThan(start, end),
                repository.findFirstByOrderByEventAtAsc().map(AppendOnlyEvidence::getEventAt).orElse(null),
                repository.findFirstByOrderByEventAtDesc().map(AppendOnlyEvidence::getEventAt).orElse(null),
                true,
                List.of(),
                "READ_ONLY_NO_PROVIDER_INGESTION"
        );
    }

    public record EvidenceTableCoverage(
            String table,
            LocalDateTime requestedStart,
            LocalDateTime requestedEnd,
            long observedCount,
            LocalDateTime oldestEventTime,
            LocalDateTime newestEventTime,
            boolean querySucceeded,
            List<String> queryErrors,
            String ingestionStatus
    ) {
    }
}
