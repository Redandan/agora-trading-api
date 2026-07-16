package com.agora.service.trading.evidence.okx;

import com.agora.service.diagnostic.coverage.CoverageProfiler;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.AppendCommand;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.Dataset;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.NormalizationBatch;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.RejectedEvidence;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure machine-readable coverage/gap projection over normalized provider pages. */
public final class OkxEvidenceCoverageService {

    private final CoverageProfiler profiler = new CoverageProfiler();

    public EvidenceCoverageReport profile(Instant start,
                                          Instant end,
                                          Map<Dataset, Duration> cadences,
                                          Duration intersectionCadence,
                                          Map<Dataset, List<NormalizationBatch>> pages) {
        List<CoverageProfiler.CoverageDataset> required = List.of(
                CoverageProfiler.CoverageDataset.EXECUTABLE_QUOTE_SNAPSHOT,
                CoverageProfiler.CoverageDataset.FILL_FEE_LEDGER,
                CoverageProfiler.CoverageDataset.FUNDING_BILL_LEDGER,
                CoverageProfiler.CoverageDataset.MARGIN_SNAPSHOT);
        Map<CoverageProfiler.CoverageDataset, Duration> profilerCadences = new EnumMap<>(CoverageProfiler.CoverageDataset.class);
        List<CoverageProfiler.DatasetQuery> queries = new ArrayList<>();
        Map<String, Long> rejectionReasons = new LinkedHashMap<>();
        for (Dataset dataset : Dataset.values()) {
            CoverageProfiler.CoverageDataset profilerDataset = toProfilerDataset(dataset);
            profilerCadences.put(profilerDataset, cadences.get(dataset));
            List<NormalizationBatch> datasetPages = pages == null
                    ? List.of() : pages.getOrDefault(dataset, List.of());
            List<CoverageProfiler.CoverageRecord> records = new ArrayList<>();
            boolean complete = !datasetPages.isEmpty();
            for (NormalizationBatch page : datasetPages) {
                complete &= page != null && page.pageComplete();
                if (page == null) {
                    continue;
                }
                for (AppendCommand command : page.accepted()) {
                    records.add(command.coverageRecord());
                }
                for (RejectedEvidence rejection : page.rejected()) {
                    rejectionReasons.merge(dataset.name() + ":" + rejection.reason().name(), 1L, Long::sum);
                }
            }
            queries.add(new CoverageProfiler.DatasetQuery(profilerDataset, !datasetPages.isEmpty(), List.of(),
                    false, complete, records));
        }
        CoverageProfiler.ProfileRequest request = new CoverageProfiler.ProfileRequest(
                start, end, profilerCadences, intersectionCadence, required);
        CoverageProfiler.CoverageGapManifest manifest = profiler.profile(
                new CoverageProfiler.ProfileInput(request, queries));
        boolean complete = manifest.complete() && rejectionReasons.isEmpty();
        return new EvidenceCoverageReport(manifest, Map.copyOf(rejectionReasons), complete);
    }

    private static CoverageProfiler.CoverageDataset toProfilerDataset(Dataset dataset) {
        return switch (dataset) {
            case EXECUTABLE_QUOTE -> CoverageProfiler.CoverageDataset.EXECUTABLE_QUOTE_SNAPSHOT;
            case FILL_FEE -> CoverageProfiler.CoverageDataset.FILL_FEE_LEDGER;
            case FUNDING_BILL -> CoverageProfiler.CoverageDataset.FUNDING_BILL_LEDGER;
            case MARGIN_SNAPSHOT -> CoverageProfiler.CoverageDataset.MARGIN_SNAPSHOT;
        };
    }

    public record EvidenceCoverageReport(CoverageProfiler.CoverageGapManifest manifest,
                                         Map<String, Long> rejectionReasonCounts,
                                         boolean complete) {
    }
}
