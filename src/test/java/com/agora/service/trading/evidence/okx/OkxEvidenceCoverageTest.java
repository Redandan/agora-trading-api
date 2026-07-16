package com.agora.service.trading.evidence.okx;

import com.agora.service.diagnostic.coverage.CoverageProfiler;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.Dataset;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.NormalizationBatch;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.RejectReason;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.RejectedEvidence;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OkxEvidenceCoverageTest {

    private static final Instant START = Instant.parse("2026-07-15T00:00:00Z");

    @Test
    void historicalOnlyRowsNeverCountAsForwardCausalEvidence() {
        CoverageProfiler profiler = new CoverageProfiler();
        CoverageProfiler.CoverageGapManifest manifest = profiler.profile(new CoverageProfiler.ProfileInput(
                request(Duration.ofHours(1), Duration.ofHours(1)),
                List.of(query(List.of(record("historical", START, "okx",
                        CoverageProfiler.Provenance.HISTORICAL_BACKFILL))))));

        CoverageProfiler.DatasetManifest dataset = manifest.datasets().getFirst();
        assertThat(dataset.expectedCount()).isEqualTo(1);
        assertThat(dataset.cleanCount()).isEqualTo(1);
        assertThat(dataset.historicalCleanCount()).isEqualTo(1);
        assertThat(dataset.forwardCausalCount()).isZero();
        assertThat(dataset.forwardCausalCoverageRatio()).isEqualByComparingTo("0.000000");
        assertThat(dataset.forwardCausalMissingRanges()).extracting(CoverageProfiler.MissingRange::reason)
                .containsExactly("NO_FORWARD_CAUSAL_ROW");
        assertThat(manifest.complete()).isFalse();
    }

    @Test
    void providerTransitionMakesProviderStableFalseEvenWithFullNumerator() {
        CoverageProfiler profiler = new CoverageProfiler();
        CoverageProfiler.CoverageGapManifest manifest = profiler.profile(new CoverageProfiler.ProfileInput(
                request(Duration.ofSeconds(2), Duration.ofSeconds(1)),
                List.of(query(List.of(
                        record("one", START, "okx", CoverageProfiler.Provenance.FORWARD),
                        record("two", START.plusSeconds(1), "provider-transition", CoverageProfiler.Provenance.FORWARD))))));

        CoverageProfiler.DatasetManifest dataset = manifest.datasets().getFirst();
        assertThat(dataset.expectedCount()).isEqualTo(2);
        assertThat(dataset.forwardCausalCount()).isEqualTo(2);
        assertThat(dataset.forwardCausalCoverageRatio()).isEqualByComparingTo("1.000000");
        assertThat(dataset.providerStable()).isFalse();
        assertThat(dataset.providerTransitions()).hasSize(1);
        assertThat(manifest.providerStable()).isFalse();
        assertThat(manifest.complete()).isFalse();
    }

    @Test
    void gapReportPreservesMachineRejectionReasonsAndNumeratorDenominator() {
        OkxEvidenceCoverageService service = new OkxEvidenceCoverageService();
        Map<Dataset, Duration> cadences = Map.of(
                Dataset.EXECUTABLE_QUOTE, Duration.ofHours(1),
                Dataset.FILL_FEE, Duration.ofHours(1),
                Dataset.FUNDING_BILL, Duration.ofHours(1),
                Dataset.MARGIN_SNAPSHOT, Duration.ofHours(1));
        NormalizationBatch rejected = new NormalizationBatch(List.of(),
                List.of(new RejectedEvidence(Dataset.FILL_FEE, 0, RejectReason.MISSING_SIGNED_FEE)),
                null, true);

        OkxEvidenceCoverageService.EvidenceCoverageReport report = service.profile(
                START, START.plus(Duration.ofHours(1)), cadences, Duration.ofHours(1),
                Map.of(Dataset.FILL_FEE, List.of(rejected)));

        assertThat(report.rejectionReasonCounts()).containsEntry("FILL_FEE:MISSING_SIGNED_FEE", 1L);
        CoverageProfiler.DatasetManifest fill = report.manifest().datasets().stream()
                .filter(item -> item.dataset() == CoverageProfiler.CoverageDataset.FILL_FEE_LEDGER)
                .findFirst().orElseThrow();
        assertThat(fill.expectedCount()).isEqualTo(1);
        assertThat(fill.forwardCausalCount()).isZero();
        assertThat(report.complete()).isFalse();
    }

    private CoverageProfiler.ProfileRequest request(Duration range, Duration cadence) {
        return new CoverageProfiler.ProfileRequest(START, START.plus(range),
                Map.of(CoverageProfiler.CoverageDataset.EXECUTABLE_QUOTE_SNAPSHOT, cadence),
                cadence, List.of(CoverageProfiler.CoverageDataset.EXECUTABLE_QUOTE_SNAPSHOT));
    }

    private CoverageProfiler.DatasetQuery query(List<CoverageProfiler.CoverageRecord> records) {
        return new CoverageProfiler.DatasetQuery(
                CoverageProfiler.CoverageDataset.EXECUTABLE_QUOTE_SNAPSHOT,
                true, List.of(), false, true, records);
    }

    private CoverageProfiler.CoverageRecord record(String key,
                                                   Instant event,
                                                   String provider,
                                                   CoverageProfiler.Provenance provenance) {
        return new CoverageProfiler.CoverageRecord(key, event, event, event.plusMillis(100),
                event.plusMillis(400), event.plusMillis(300), provider, provenance,
                CoverageProfiler.DataKind.DEPTH, CoverageProfiler.Usage.EXECUTABLE_DEPTH);
    }
}
