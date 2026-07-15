package com.agora.service.diagnostic.coverage;

import com.agora.service.diagnostic.coverage.CoverageProfiler.CoverageDataset;
import com.agora.service.diagnostic.coverage.CoverageProfiler.CoverageGapManifest;
import com.agora.service.diagnostic.coverage.CoverageProfiler.CoverageRecord;
import com.agora.service.diagnostic.coverage.CoverageProfiler.DataKind;
import com.agora.service.diagnostic.coverage.CoverageProfiler.DatasetManifest;
import com.agora.service.diagnostic.coverage.CoverageProfiler.DatasetQuery;
import com.agora.service.diagnostic.coverage.CoverageProfiler.ProfileInput;
import com.agora.service.diagnostic.coverage.CoverageProfiler.ProfileRequest;
import com.agora.service.diagnostic.coverage.CoverageProfiler.Provenance;
import com.agora.service.diagnostic.coverage.CoverageProfiler.Usage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageProfilerTest {

    private static final Instant START = Instant.parse("2026-07-15T00:00:00Z");
    private static final Instant END = START.plus(Duration.ofHours(2));
    private final CoverageProfiler profiler = new CoverageProfiler();

    @Test
    void completeCoverageIsMachineReadable() {
        DatasetManifest manifest = profileOne(query(CoverageDataset.MD_KLINE,
                row("a", START), row("b", START.plus(Duration.ofHours(1)))));

        assertThat(manifest.expectedCount()).isEqualTo(2);
        assertThat(manifest.observedCount()).isEqualTo(2);
        assertThat(manifest.cleanCount()).isEqualTo(2);
        assertThat(manifest.coverageRatio()).isEqualByComparingTo("1.000000");
        assertThat(manifest.missingRanges()).isEmpty();
        assertThat(manifest.querySucceeded()).isTrue();
        assertThat(manifest.pageComplete()).isTrue();
    }

    @Test
    void reportsContiguousMissingRanges() {
        DatasetManifest manifest = profileOne(query(CoverageDataset.MD_KLINE, row("a", START)));

        assertThat(manifest.coverageRatio()).isEqualByComparingTo("0.500000");
        assertThat(manifest.missingRanges()).singleElement().satisfies(range -> {
            assertThat(range.start()).isEqualTo(START.plus(Duration.ofHours(1)));
            assertThat(range.end()).isEqualTo(END);
            assertThat(range.reason()).isEqualTo("NO_CLEAN_ROW");
        });
    }

    @Test
    void duplicateGroupFailsClosedInsteadOfChoosingAWinner() {
        DatasetManifest manifest = profileOne(query(CoverageDataset.MD_KLINE,
                row("dup", START), row("dup", START)));

        assertThat(manifest.duplicateCount()).isEqualTo(1);
        assertThat(manifest.cleanCount()).isZero();
        assertThat(manifest.coverageRatio()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void futureArrivalIsCleanButNotCausalEligible() {
        CoverageRecord future = record("future", START, START, START.plusSeconds(2),
                START.plusSeconds(3), START.plusSeconds(1), "okx", Provenance.FORWARD,
                DataKind.BAR, Usage.FEATURE);
        DatasetManifest manifest = profileOne(query(CoverageDataset.MD_KLINE, future));

        assertThat(manifest.cleanCount()).isEqualTo(1);
        assertThat(manifest.futureArrivingCount()).isEqualTo(1);
        assertThat(manifest.forwardCausalCount()).isZero();
    }

    @Test
    void queryFailureFailsCompletenessClosed() {
        DatasetQuery failed = new DatasetQuery(CoverageDataset.BT_DECISION_AUDIT, false,
                List.of("SQL timeout"), false, false, List.of());
        DatasetManifest manifest = profileOne(failed);

        assertThat(manifest.querySucceeded()).isFalse();
        assertThat(manifest.queryErrors()).containsExactly("SQL timeout");
        assertThat(manifest.coverageRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(manifest.missingRanges()).singleElement()
                .extracting(CoverageProfiler.MissingRange::reason).isEqualTo("QUERY_FAILED");
    }

    @Test
    void truncationFailsCompletenessClosedEvenWhenRowsExist() {
        DatasetQuery truncated = new DatasetQuery(CoverageDataset.BT_LIVE_SIGNAL, true,
                List.of(), true, false, List.of(row("a", START)));
        DatasetManifest manifest = profileOne(truncated);

        assertThat(manifest.truncated()).isTrue();
        assertThat(manifest.pageComplete()).isFalse();
        assertThat(manifest.cleanCount()).isEqualTo(1);
        assertThat(manifest.coverageRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(manifest.missingRanges()).singleElement()
                .extracting(CoverageProfiler.MissingRange::reason).isEqualTo("PAGE_INCOMPLETE");
    }

    @Test
    void reportsProviderTransitionsInEventOrder() {
        CoverageRecord first = row("a", START);
        CoverageRecord second = record("b", START.plus(Duration.ofHours(1)),
                START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(1)),
                START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(1)),
                "binance", Provenance.FORWARD, DataKind.BAR, Usage.FEATURE);
        DatasetManifest manifest = profileOne(query(CoverageDataset.MD_KLINE, first, second));

        assertThat(manifest.providerTransitions()).singleElement().satisfies(transition -> {
            assertThat(transition.fromProvider()).isEqualTo("okx");
            assertThat(transition.toProvider()).isEqualTo("binance");
            assertThat(transition.at()).isEqualTo(START.plus(Duration.ofHours(1)));
        });
    }

    @Test
    void missingTimestampOrProvenanceNeverCountsAsClean() {
        CoverageRecord missingIngested = record("missing", START, START, START, null, START,
                "okx", Provenance.FORWARD, DataKind.BAR, Usage.FEATURE);
        CoverageRecord unknownProvenance = record("unknown", START.plus(Duration.ofHours(1)),
                START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(1)),
                START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(1)),
                "okx", Provenance.UNKNOWN, DataKind.BAR, Usage.FEATURE);
        DatasetManifest manifest = profileOne(query(CoverageDataset.MD_KLINE,
                missingIngested, unknownProvenance));

        assertThat(manifest.observedCount()).isEqualTo(2);
        assertThat(manifest.cleanCount()).isZero();
    }

    @Test
    void historicalRowsDoNotInflateForwardCausalNumerator() {
        CoverageRecord historical = record("historical", START, START, START, START, START,
                "okx", Provenance.HISTORICAL_BACKFILL, DataKind.BAR, Usage.FEATURE);
        CoverageRecord forward = row("forward", START.plus(Duration.ofHours(1)));
        DatasetManifest manifest = profileOne(query(CoverageDataset.MD_KLINE, historical, forward));

        assertThat(manifest.cleanCount()).isEqualTo(2);
        assertThat(manifest.forwardCausalCount()).isEqualTo(1);
    }

    @Test
    void intersectionCoverageRequiresCausalSlotFromEveryDataset() {
        CoverageRecord future = record("b1", START.plus(Duration.ofHours(1)),
                START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(1)).plusSeconds(2),
                START.plus(Duration.ofHours(1)).plusSeconds(3), START.plus(Duration.ofHours(1)).plusSeconds(1),
                "internal", Provenance.FORWARD, DataKind.DECISION, Usage.FEATURE);
        ProfileRequest request = request(List.of(CoverageDataset.MD_KLINE, CoverageDataset.BT_DECISION_AUDIT));
        CoverageGapManifest manifest = profiler.profile(new ProfileInput(request, List.of(
                query(CoverageDataset.MD_KLINE, row("a0", START), row("a1", START.plus(Duration.ofHours(1)))),
                query(CoverageDataset.BT_DECISION_AUDIT, row("b0", START), future)
        )));

        assertThat(manifest.intersectionExpectedCount()).isEqualTo(2);
        assertThat(manifest.intersectionObservedCount()).isEqualTo(1);
        assertThat(manifest.intersectionCoverage()).isEqualByComparingTo("0.500000");
        assertThat(manifest.datasets()).allSatisfy(dataset ->
                assertThat(dataset.intersectionCoverage()).isEqualByComparingTo("0.500000"));
    }

    @Test
    void hourlyScalarCannotMasqueradeAsExecutableQuoteOrDepth() {
        CoverageRecord scalar = record("scalar", START, START, START, START, START,
                "coinalyze", Provenance.FORWARD, DataKind.HOURLY_SCALAR, Usage.EXECUTABLE_QUOTE);
        DatasetManifest manifest = profileOne(query(CoverageDataset.MARKET_INDICATOR_HISTORY, scalar));

        assertThat(manifest.cleanCount()).isZero();
        assertThat(manifest.forwardCausalCount()).isZero();
    }

    private DatasetManifest profileOne(DatasetQuery query) {
        ProfileRequest request = request(List.of(query.dataset()));
        return profiler.profile(new ProfileInput(request, List.of(query))).datasets().getFirst();
    }

    private ProfileRequest request(List<CoverageDataset> datasets) {
        return new ProfileRequest(START, END,
                datasets.stream().collect(java.util.stream.Collectors.toMap(dataset -> dataset,
                        dataset -> Duration.ofHours(1))),
                Duration.ofHours(1), datasets);
    }

    private DatasetQuery query(CoverageDataset dataset, CoverageRecord... rows) {
        return new DatasetQuery(dataset, true, List.of(), false, true, List.of(rows));
    }

    private CoverageRecord row(String key, Instant time) {
        return record(key, time, time, time, time, time, "okx", Provenance.FORWARD,
                DataKind.BAR, Usage.FEATURE);
    }

    private CoverageRecord record(String key,
                                  Instant event,
                                  Instant effective,
                                  Instant available,
                                  Instant ingested,
                                  Instant decision,
                                  String provider,
                                  Provenance provenance,
                                  DataKind dataKind,
                                  Usage usage) {
        return new CoverageRecord(key, event, effective, available, ingested, decision,
                provider, provenance, dataKind, usage);
    }
}
