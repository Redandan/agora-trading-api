package com.agora.service.diagnostic.coverage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, local, read-only coverage analysis. Callers supply already queried rows;
 * this class has no database, provider, scheduler, or Spring runtime dependency.
 */
public final class CoverageProfiler {

    public CoverageGapManifest profile(ProfileInput input) {
        Objects.requireNonNull(input, "input");
        ProfileRequest request = Objects.requireNonNull(input.request(), "request");
        validate(request);

        Map<CoverageDataset, DatasetQuery> queries = new EnumMap<>(CoverageDataset.class);
        if (input.queries() != null) {
            for (DatasetQuery query : input.queries()) {
                if (query != null && query.dataset() != null) {
                    queries.put(query.dataset(), query);
                }
            }
        }

        List<DatasetWork> work = new ArrayList<>();
        for (CoverageDataset dataset : request.requiredDatasets()) {
            Duration cadence = request.cadences().get(dataset);
            DatasetQuery query = queries.getOrDefault(dataset, DatasetQuery.missing(dataset));
            work.add(analyzeDataset(dataset, cadence, request, query));
        }

        Set<Instant> intersection = null;
        for (DatasetWork item : work) {
            if (!item.complete()) {
                intersection = Set.of();
                break;
            }
            if (intersection == null) {
                intersection = new HashSet<>(item.causalSlots());
            } else {
                intersection.retainAll(item.causalSlots());
            }
        }
        int expectedIntersection = expectedSlots(request.requestedStart(), request.requestedEnd(),
                request.intersectionCadence()).size();
        int intersectionCount = intersection == null ? 0 : intersection.size();
        BigDecimal intersectionCoverage = ratio(intersectionCount, expectedIntersection);

        List<DatasetManifest> datasets = work.stream()
                .map(item -> item.manifest().withIntersectionCoverage(intersectionCoverage))
                .toList();
        boolean complete = work.stream().allMatch(item -> item.manifest().complete())
                && intersectionCoverage.compareTo(BigDecimal.ONE) == 0;
        return new CoverageGapManifest(
                request.requestedStart(),
                request.requestedEnd(),
                expectedIntersection,
                intersectionCount,
                intersectionCoverage,
                complete,
                datasets
        );
    }

    private DatasetWork analyzeDataset(CoverageDataset dataset,
                                       Duration cadence,
                                       ProfileRequest request,
                                       DatasetQuery query) {
        List<CoverageRecord> rows = query.records() == null ? List.of() : query.records();
        List<Instant> slots = expectedSlots(request.requestedStart(), request.requestedEnd(), cadence);
        Set<String> duplicateKeys = duplicateKeys(rows);
        long duplicateCount = rows.stream()
                .filter(row -> row != null && present(row.dedupKey()) && duplicateKeys.contains(row.dedupKey()))
                .count() - duplicateKeys.size();
        duplicateCount = Math.max(0L, duplicateCount);

        List<CoverageRecord> cleanRows = rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> isClean(row, duplicateKeys, request))
                .sorted(Comparator.comparing(CoverageRecord::eventTime))
                .toList();
        long futureArrivingCount = rows.stream()
                .filter(Objects::nonNull)
                .filter(CoverageProfiler::isFutureArriving)
                .count();
        List<CoverageRecord> causalRows = cleanRows.stream()
                .filter(CoverageProfiler::isForwardCausalEligible)
                .toList();

        boolean queryComplete = query.querySucceeded() && !query.truncated() && query.pageComplete();
        Set<Instant> cleanSlots = slotSet(cleanRows, request.requestedStart(), cadence);
        Set<Instant> causalSlots = slotSet(causalRows, request.requestedStart(), request.intersectionCadence());
        List<MissingRange> missingRanges = queryComplete
                ? missingRanges(slots, cleanSlots, cadence, "NO_CLEAN_ROW")
                : List.of(new MissingRange(request.requestedStart(), request.requestedEnd(),
                    query.querySucceeded() ? "PAGE_INCOMPLETE" : "QUERY_FAILED"));
        BigDecimal coverage = queryComplete ? ratio(cleanSlots.size(), slots.size()) : BigDecimal.ZERO.setScale(6);
        boolean datasetComplete = queryComplete
                && missingRanges.isEmpty()
                && cleanRows.size() == rows.size()
                && coverage.compareTo(BigDecimal.ONE) == 0;

        DatasetManifest manifest = new DatasetManifest(
                dataset,
                request.requestedStart(),
                request.requestedEnd(),
                slots.size(),
                rows.size(),
                cleanRows.size(),
                duplicateCount,
                futureArrivingCount,
                causalRows.size(),
                missingRanges,
                providerTransitions(cleanRows),
                min(rows, CoverageRecord::eventTime),
                max(rows, CoverageRecord::eventTime),
                min(rows, CoverageRecord::effectiveAt),
                max(rows, CoverageRecord::effectiveAt),
                min(rows, CoverageRecord::availableAt),
                max(rows, CoverageRecord::availableAt),
                min(rows, CoverageRecord::ingestedAt),
                max(rows, CoverageRecord::ingestedAt),
                coverage,
                BigDecimal.ZERO.setScale(6),
                datasetComplete,
                query.querySucceeded(),
                query.queryErrors() == null ? List.of() : List.copyOf(query.queryErrors()),
                query.truncated(),
                query.pageComplete()
        );
        return new DatasetWork(manifest, causalSlots, queryComplete);
    }

    private static boolean isClean(CoverageRecord row,
                                   Set<String> duplicateKeys,
                                   ProfileRequest request) {
        if (!present(row.dedupKey()) || duplicateKeys.contains(row.dedupKey())) {
            return false;
        }
        if (row.eventTime() == null || row.effectiveAt() == null || row.availableAt() == null
                || row.ingestedAt() == null || row.decisionTime() == null) {
            return false;
        }
        if (!present(row.provider()) || row.provenance() == null || row.provenance() == Provenance.UNKNOWN) {
            return false;
        }
        if (row.eventTime().isBefore(request.requestedStart()) || !row.eventTime().isBefore(request.requestedEnd())) {
            return false;
        }
        return row.usage() == null || row.usage() == Usage.FEATURE
                || row.dataKind() != DataKind.HOURLY_SCALAR;
    }

    private static boolean isForwardCausalEligible(CoverageRecord row) {
        return row.provenance() == Provenance.FORWARD
                && !row.effectiveAt().isAfter(row.decisionTime())
                && !row.availableAt().isAfter(row.decisionTime());
    }

    private static boolean isFutureArriving(CoverageRecord row) {
        if (row.decisionTime() == null) {
            return false;
        }
        return (row.effectiveAt() != null && row.effectiveAt().isAfter(row.decisionTime()))
                || (row.availableAt() != null && row.availableAt().isAfter(row.decisionTime()));
    }

    private static Set<String> duplicateKeys(List<CoverageRecord> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (CoverageRecord row : rows) {
            if (row != null && present(row.dedupKey())) {
                counts.merge(row.dedupKey(), 1, Integer::sum);
            }
        }
        Set<String> result = new HashSet<>();
        counts.forEach((key, count) -> {
            if (count > 1) {
                result.add(key);
            }
        });
        return result;
    }

    private static Set<Instant> slotSet(List<CoverageRecord> rows, Instant start, Duration cadence) {
        Set<Instant> result = new HashSet<>();
        for (CoverageRecord row : rows) {
            long offset = Duration.between(start, row.eventTime()).toNanos();
            long cadenceNanos = cadence.toNanos();
            if (offset >= 0 && offset % cadenceNanos == 0) {
                result.add(row.eventTime());
            }
        }
        return result;
    }

    private static List<ProviderTransition> providerTransitions(List<CoverageRecord> rows) {
        List<ProviderTransition> result = new ArrayList<>();
        String previous = null;
        for (CoverageRecord row : rows) {
            if (previous != null && !previous.equals(row.provider())) {
                result.add(new ProviderTransition(previous, row.provider(), row.eventTime()));
            }
            previous = row.provider();
        }
        return result;
    }

    private static List<MissingRange> missingRanges(List<Instant> slots,
                                                    Set<Instant> observed,
                                                    Duration cadence,
                                                    String reason) {
        List<MissingRange> result = new ArrayList<>();
        Instant rangeStart = null;
        Instant previous = null;
        for (Instant slot : slots) {
            if (!observed.contains(slot)) {
                if (rangeStart == null) {
                    rangeStart = slot;
                }
                previous = slot;
            } else if (rangeStart != null) {
                result.add(new MissingRange(rangeStart, previous.plus(cadence), reason));
                rangeStart = null;
                previous = null;
            }
        }
        if (rangeStart != null) {
            result.add(new MissingRange(rangeStart, previous.plus(cadence), reason));
        }
        return result;
    }

    private static List<Instant> expectedSlots(Instant start, Instant end, Duration cadence) {
        List<Instant> result = new ArrayList<>();
        for (Instant cursor = start; cursor.isBefore(end); cursor = cursor.plus(cadence)) {
            result.add(cursor);
        }
        return result;
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(6);
        }
        long bounded = Math.min(numerator, denominator);
        return BigDecimal.valueOf(bounded)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private static Instant min(List<CoverageRecord> rows, TimeSelector selector) {
        return rows.stream().filter(Objects::nonNull).map(selector::select).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
    }

    private static Instant max(List<CoverageRecord> rows, TimeSelector selector) {
        return rows.stream().filter(Objects::nonNull).map(selector::select).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private static void validate(ProfileRequest request) {
        Objects.requireNonNull(request.requestedStart(), "requestedStart");
        Objects.requireNonNull(request.requestedEnd(), "requestedEnd");
        if (!request.requestedStart().isBefore(request.requestedEnd())) {
            throw new IllegalArgumentException("requestedStart must be before requestedEnd");
        }
        if (request.requiredDatasets() == null || request.requiredDatasets().isEmpty()) {
            throw new IllegalArgumentException("requiredDatasets must not be empty");
        }
        requirePositive(request.intersectionCadence(), "intersectionCadence");
        for (CoverageDataset dataset : request.requiredDatasets()) {
            requirePositive(request.cadences().get(dataset), "cadence for " + dataset.value);
        }
    }

    private static void requirePositive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface TimeSelector {
        Instant select(CoverageRecord record);
    }

    private record DatasetWork(DatasetManifest manifest, Set<Instant> causalSlots, boolean complete) {
    }

    public enum CoverageDataset {
        MD_KLINE("md_kline"),
        MARKET_INDICATOR_HISTORY("market_indicator_history"),
        BT_DECISION_AUDIT("bt_decision_audit"),
        BT_LIVE_SIGNAL("bt_live_signal"),
        BT_RUNTIME_DECISION_EVIDENCE("bt_runtime_decision_evidence");

        private final String value;

        CoverageDataset(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static CoverageDataset fromValue(String value) {
            for (CoverageDataset dataset : values()) {
                if (dataset.value.equalsIgnoreCase(value)) {
                    return dataset;
                }
            }
            throw new IllegalArgumentException("Unsupported dataset: " + value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public enum Provenance {
        FORWARD,
        HISTORICAL_BACKFILL,
        UNKNOWN
    }

    public enum DataKind {
        BAR,
        DECISION,
        SIGNAL,
        EVIDENCE,
        HOURLY_SCALAR,
        QUOTE,
        DEPTH
    }

    public enum Usage {
        FEATURE,
        EXECUTABLE_QUOTE,
        EXECUTABLE_DEPTH
    }

    public record ProfileRequest(
            Instant requestedStart,
            Instant requestedEnd,
            Map<CoverageDataset, Duration> cadences,
            Duration intersectionCadence,
            List<CoverageDataset> requiredDatasets
    ) {
        public ProfileRequest {
            cadences = cadences == null ? Map.of() : Map.copyOf(cadences);
            requiredDatasets = requiredDatasets == null ? List.of() : List.copyOf(requiredDatasets);
        }
    }

    public record ProfileInput(ProfileRequest request, List<DatasetQuery> queries) {
        public ProfileInput {
            queries = queries == null ? List.of() : List.copyOf(queries);
        }
    }

    public record DatasetQuery(
            CoverageDataset dataset,
            boolean querySucceeded,
            List<String> queryErrors,
            boolean truncated,
            boolean pageComplete,
            List<CoverageRecord> records
    ) {
        public DatasetQuery {
            queryErrors = queryErrors == null ? List.of() : List.copyOf(queryErrors);
            records = records == null ? List.of() : List.copyOf(records);
        }

        private static DatasetQuery missing(CoverageDataset dataset) {
            return new DatasetQuery(dataset, false, List.of("dataset query missing"), false, false, List.of());
        }
    }

    public record CoverageRecord(
            String dedupKey,
            Instant eventTime,
            Instant effectiveAt,
            Instant availableAt,
            Instant ingestedAt,
            Instant decisionTime,
            String provider,
            Provenance provenance,
            DataKind dataKind,
            Usage usage
    ) {
    }

    public record CoverageGapManifest(
            Instant requestedStart,
            Instant requestedEnd,
            long intersectionExpectedCount,
            long intersectionObservedCount,
            BigDecimal intersectionCoverage,
            boolean complete,
            List<DatasetManifest> datasets
    ) {
    }

    public record DatasetManifest(
            CoverageDataset dataset,
            Instant requestedStart,
            Instant requestedEnd,
            long expectedCount,
            long observedCount,
            long cleanCount,
            long duplicateCount,
            long futureArrivingCount,
            long forwardCausalCount,
            List<MissingRange> missingRanges,
            List<ProviderTransition> providerTransitions,
            Instant oldestEventTime,
            Instant newestEventTime,
            Instant oldestEffectiveTime,
            Instant newestEffectiveTime,
            Instant oldestAvailableTime,
            Instant newestAvailableTime,
            Instant oldestIngestedTime,
            Instant newestIngestedTime,
            BigDecimal coverageRatio,
            BigDecimal intersectionCoverage,
            boolean complete,
            boolean querySucceeded,
            List<String> queryErrors,
            boolean truncated,
            boolean pageComplete
    ) {
        private DatasetManifest withIntersectionCoverage(BigDecimal value) {
            return new DatasetManifest(dataset, requestedStart, requestedEnd, expectedCount, observedCount,
                    cleanCount, duplicateCount, futureArrivingCount, forwardCausalCount, missingRanges,
                    providerTransitions, oldestEventTime, newestEventTime, oldestEffectiveTime,
                    newestEffectiveTime, oldestAvailableTime, newestAvailableTime, oldestIngestedTime,
                    newestIngestedTime, coverageRatio, value, complete, querySucceeded, queryErrors, truncated,
                    pageComplete);
        }
    }

    public record MissingRange(Instant start, Instant end, String reason) {
    }

    public record ProviderTransition(String fromProvider, String toProvider, Instant at) {
    }
}
