package com.agora.service.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.agora.service.trading.CurrentCohortCanonicalMetricReader.Classification.GROSS_ONLY;
import static com.agora.service.trading.CurrentCohortCanonicalMetricReader.Classification.NOT_MEASURABLE;
import static org.assertj.core.api.Assertions.assertThat;

class CurrentCohortCanonicalMetricReaderTest {

    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-07-17T00:00:00Z");
    private static final CurrentCohortCanonicalMetricReader.CohortIdentity IDENTITY =
            new CurrentCohortCanonicalMetricReader.CohortIdentity(
                    "VPSTART1-485-BTCUSDT-TEST", 485L, "SCORE_BUY_V2", "BTCUSDT",
                    "748a69ea5b9254e9bd79099e460cefc2ab9297dd",
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "local-tradingview-parity-v1", "LOCAL_TRADINGVIEW", "LIVE_MICRO");

    private final CurrentCohortCanonicalMetricReader reader = new CurrentCohortCanonicalMetricReader();

    @Test
    void excludesPreEffectiveLegacyBeforeEvaluatingIdentity() {
        CurrentCohortCanonicalMetricReader.ClosedEpisode legacy = episode(
                "legacy", differentIdentity(), "2026-07-16T22:00:00Z", "2026-07-17T01:00:00Z",
                "10", "-1", null, complete());
        CurrentCohortCanonicalMetricReader.ClosedEpisode current = episode(
                "current", IDENTITY, "2026-07-17T01:00:00Z", "2026-07-17T02:00:00Z",
                "5", "-1", null, complete());

        CurrentCohortCanonicalMetricReader.Result result =
                reader.aggregate(IDENTITY, EFFECTIVE_FROM, List.of(legacy, current));

        assertThat(result.classification()).isEqualTo(GROSS_ONLY);
        assertThat(result.closedEpisodes()).isEqualTo(1);
        assertThat(result.exactFeeEpisodes()).isZero();
        assertThat(result.positiveExactNetEpisodes()).isZero();
        assertThat(result.legacyEpisodesExcluded()).isEqualTo(1);
        assertThat(result.blockers()).isEmpty();
    }

    @Test
    void identityConflictFailsClosedAndCannotEnterCurrentCohortCounts() {
        CurrentCohortCanonicalMetricReader.Result result = reader.aggregate(
                IDENTITY, EFFECTIVE_FROM, List.of(episode(
                        "conflict", differentIdentity(), "2026-07-17T01:00:00Z", "2026-07-17T02:00:00Z",
                        "5", "-1", null, complete())));

        assertThat(result.classification()).isEqualTo(NOT_MEASURABLE);
        assertThat(result.closedEpisodes()).isZero();
        assertThat(result.exactFeeEpisodes()).isZero();
        assertThat(result.positiveExactNetEpisodes()).isZero();
        assertThat(result.blockers()).containsExactly("CURRENT_COHORT_IDENTITY_CONFLICT:conflict");
    }

    @Test
    void partialFillOrFeeGapStaysGrossOnlyAndEstimatedFeeNeverPromotesExactCounts() {
        CurrentCohortCanonicalMetricReader.ClosedEpisode partialFill = episode(
                "partial", IDENTITY, "2026-07-17T01:00:00Z", "2026-07-17T02:00:00Z",
                "5", "-1", null,
                new CurrentCohortCanonicalMetricReader.EvidenceCompleteness(true, false, true));
        CurrentCohortCanonicalMetricReader.ClosedEpisode feeGap = episode(
                "fee-gap", IDENTITY, "2026-07-17T03:00:00Z", "2026-07-17T04:00:00Z",
                "6", null, "0.25",
                new CurrentCohortCanonicalMetricReader.EvidenceCompleteness(true, true, false));

        CurrentCohortCanonicalMetricReader.Result result =
                reader.aggregate(IDENTITY, EFFECTIVE_FROM, List.of(partialFill, feeGap));

        assertThat(result.classification()).isEqualTo(GROSS_ONLY);
        assertThat(result.closedEpisodes()).isEqualTo(2);
        assertThat(result.exactFeeEpisodes()).isZero();
        assertThat(result.positiveExactNetEpisodes()).isZero();
        assertThat(result.episodes()).extracting(CurrentCohortCanonicalMetricReader.EpisodeMetric::classification)
                .containsExactly(GROSS_ONLY, GROSS_ONLY);
        assertThat(result.episodes()).extracting(CurrentCohortCanonicalMetricReader.EpisodeMetric::exactNet)
                .containsOnlyNulls();
    }

    @Test
    void retainsNegativeEpisodeButCannotPromoteItToExactNetBeforeV3() {
        CurrentCohortCanonicalMetricReader.Result result = reader.aggregate(
                IDENTITY, EFFECTIVE_FROM, List.of(episode(
                        "loss", IDENTITY, "2026-07-17T01:00:00Z", "2026-07-17T02:00:00Z",
                        "-2.00", "-0.50", null, complete())));

        assertThat(result.classification()).isEqualTo(GROSS_ONLY);
        assertThat(result.closedEpisodes()).isEqualTo(1);
        assertThat(result.exactFeeEpisodes()).isZero();
        assertThat(result.positiveExactNetEpisodes()).isZero();
        assertThat(result.episodes().getFirst().exactNet()).isNull();
    }

    @Test
    void identicalDuplicateEvidenceCountsEpisodeOnlyOnce() {
        CurrentCohortCanonicalMetricReader.ClosedEpisode evidence = episode(
                "same", IDENTITY, "2026-07-17T01:00:00Z", "2026-07-17T02:00:00Z",
                "3.00", "-0.50", null, complete());

        CurrentCohortCanonicalMetricReader.Result result =
                reader.aggregate(IDENTITY, EFFECTIVE_FROM, List.of(evidence, evidence));

        assertThat(result.classification()).isEqualTo(GROSS_ONLY);
        assertThat(result.closedEpisodes()).isEqualTo(1);
        assertThat(result.exactFeeEpisodes()).isZero();
        assertThat(result.positiveExactNetEpisodes()).isZero();
        assertThat(result.duplicateEvidenceIgnored()).isEqualTo(1);
        assertThat(result.episodes()).hasSize(1);
    }

    @Test
    void startedCohortWithNoClosedEpisodesReturnsZeroCountsWithoutAReadinessBlocker() {
        CurrentCohortCanonicalMetricReader.Result result =
                reader.aggregate(IDENTITY, EFFECTIVE_FROM, List.of());

        assertThat(result.classification()).isEqualTo(NOT_MEASURABLE);
        assertThat(result.closedEpisodes()).isZero();
        assertThat(result.exactFeeEpisodes()).isZero();
        assertThat(result.positiveExactNetEpisodes()).isZero();
        assertThat(result.blockers()).isEmpty();
    }

    @Test
    void conflictingDuplicateEvidenceFailsClosedAndCannotIncreaseExactCounts() {
        CurrentCohortCanonicalMetricReader.ClosedEpisode exact = episode(
                "same", IDENTITY, "2026-07-17T01:00:00Z", "2026-07-17T02:00:00Z",
                "3.00", "-0.50", null, complete());
        CurrentCohortCanonicalMetricReader.ClosedEpisode grossOnly = episode(
                "same", IDENTITY, "2026-07-17T01:00:00Z", "2026-07-17T02:00:00Z",
                "3.00", null, "0.10",
                new CurrentCohortCanonicalMetricReader.EvidenceCompleteness(true, true, false));

        CurrentCohortCanonicalMetricReader.Result result =
                reader.aggregate(IDENTITY, EFFECTIVE_FROM, List.of(exact, grossOnly));

        assertThat(result.classification()).isEqualTo(NOT_MEASURABLE);
        assertThat(result.closedEpisodes()).isEqualTo(1);
        assertThat(result.exactFeeEpisodes()).isZero();
        assertThat(result.positiveExactNetEpisodes()).isZero();
        assertThat(result.blockers()).containsExactly("DUPLICATE_EPISODE_EVIDENCE_CONFLICT:same");
        assertThat(result.episodes().getFirst().classification()).isEqualTo(NOT_MEASURABLE);
    }

    private static CurrentCohortCanonicalMetricReader.ClosedEpisode episode(
            String episodeId,
            CurrentCohortCanonicalMetricReader.CohortIdentity identity,
            String startedAt,
            String closedAt,
            String grossPnl,
            String exactSignedFee,
            String estimatedFee,
            CurrentCohortCanonicalMetricReader.EvidenceCompleteness completeness) {
        return new CurrentCohortCanonicalMetricReader.ClosedEpisode(
                episodeId, identity, Instant.parse(startedAt), Instant.parse(closedAt),
                decimal(grossPnl), decimal(exactSignedFee), decimal(estimatedFee), completeness);
    }

    private static CurrentCohortCanonicalMetricReader.CohortIdentity differentIdentity() {
        return new CurrentCohortCanonicalMetricReader.CohortIdentity(
                "VPSTART1-485-BTCUSDT-OTHER", IDENTITY.strategyId(), IDENTITY.strategyFamily(),
                IDENTITY.symbol(), IDENTITY.codeCommit(), IDENTITY.configSha256(), IDENTITY.modelVersion(),
                IDENTITY.signalSource(), IDENTITY.executionMode());
    }

    private static CurrentCohortCanonicalMetricReader.EvidenceCompleteness complete() {
        return new CurrentCohortCanonicalMetricReader.EvidenceCompleteness(true, true, true);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
