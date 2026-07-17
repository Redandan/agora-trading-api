package com.agora.service.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, fail-closed aggregation for VERSIONED_PROFIT_START_ACCEPTANCE_V1.
 *
 * <p>This reader intentionally has no repository or runtime dependency. Callers
 * must supply the expected cohort identity, its effective-from boundary, and
 * already closed episode evidence. Evidence from before the boundary is legacy
 * data and cannot become part of the current cohort.</p>
 */
public final class CurrentCohortCanonicalMetricReader {

    public static final String EXACT_EVIDENCE_BLOCKER =
            "EXACT_IMMUTABLE_ALL_FILL_SIGNED_FEE_BINDING_NOT_IMPLEMENTED";
    private static final boolean EXACT_EVIDENCE_BINDING_IMPLEMENTED = false;

    public Result aggregate(CohortIdentity expectedIdentity,
                            Instant effectiveFrom,
                            Collection<ClosedEpisode> closedEpisodes) {
        List<String> blockers = new ArrayList<>();
        if (expectedIdentity == null || !expectedIdentity.complete()) {
            blockers.add("CURRENT_COHORT_IDENTITY_INCOMPLETE");
        }
        if (effectiveFrom == null) {
            blockers.add("CURRENT_COHORT_EFFECTIVE_FROM_MISSING");
        }
        if (closedEpisodes == null) {
            blockers.add("CURRENT_COHORT_CLOSED_EPISODES_MISSING");
        }
        if (!blockers.isEmpty()) {
            return new Result(Classification.NOT_MEASURABLE, 0, 0, 0,
                    0, 0, List.of(), List.copyOf(blockers));
        }

        Map<String, ClosedEpisode> unique = new LinkedHashMap<>();
        Set<String> conflictingEpisodeIds = new LinkedHashSet<>();
        int legacyEpisodesExcluded = 0;
        int duplicateEvidenceIgnored = 0;

        for (ClosedEpisode episode : closedEpisodes) {
            if (episode == null || blank(episode.episodeId()) || episode.startedAt() == null
                    || episode.closedAt() == null || episode.closedAt().isBefore(episode.startedAt())) {
                blockers.add("CLOSED_EPISODE_INVALID");
                continue;
            }
            if (episode.startedAt().isBefore(effectiveFrom)) {
                legacyEpisodesExcluded++;
                continue;
            }
            if (!expectedIdentity.equals(episode.identity())) {
                blockers.add("CURRENT_COHORT_IDENTITY_CONFLICT:" + episode.episodeId());
                continue;
            }

            ClosedEpisode existing = unique.putIfAbsent(episode.episodeId(), episode);
            if (existing != null) {
                duplicateEvidenceIgnored++;
                if (!existing.equals(episode)) {
                    conflictingEpisodeIds.add(episode.episodeId());
                    blockers.add("DUPLICATE_EPISODE_EVIDENCE_CONFLICT:" + episode.episodeId());
                }
            }
        }

        List<EpisodeMetric> episodeMetrics = new ArrayList<>();
        int exactFeeEpisodes = 0;
        int positiveExactNetEpisodes = 0;
        boolean hasGrossOnly = false;
        boolean hasNotMeasurable = false;

        for (ClosedEpisode episode : unique.values()) {
            Classification classification;
            BigDecimal exactNet = null;
            if (conflictingEpisodeIds.contains(episode.episodeId())
                    || episode.evidenceCompleteness() == null
                    || episode.grossPnl() == null
                    || !episode.evidenceCompleteness().grossPnlComplete()) {
                classification = Classification.NOT_MEASURABLE;
                hasNotMeasurable = true;
            } else if (!EXACT_EVIDENCE_BINDING_IMPLEMENTED
                    || !episode.evidenceCompleteness().allFillsComplete()
                    || !episode.evidenceCompleteness().exactSignedFeesComplete()
                    || episode.exactSignedFee() == null) {
                classification = Classification.GROSS_ONLY;
                hasGrossOnly = true;
            } else {
                classification = Classification.EXACT_NET;
                // Canonical signed fees are adjustments: costs are negative and rebates positive.
                exactNet = episode.grossPnl().add(episode.exactSignedFee());
                exactFeeEpisodes++;
                if (exactNet.compareTo(BigDecimal.ZERO) > 0) {
                    positiveExactNetEpisodes++;
                }
            }
            episodeMetrics.add(new EpisodeMetric(
                    episode.episodeId(), classification, episode.grossPnl(), exactNet));
        }

        Classification classification;
        if (unique.isEmpty() || !blockers.isEmpty() || hasNotMeasurable) {
            classification = Classification.NOT_MEASURABLE;
        } else if (hasGrossOnly) {
            classification = Classification.GROSS_ONLY;
        } else {
            classification = Classification.EXACT_NET;
        }
        return new Result(classification, unique.size(), exactFeeEpisodes,
                positiveExactNetEpisodes, legacyEpisodesExcluded, duplicateEvidenceIgnored,
                List.copyOf(episodeMetrics), List.copyOf(blockers));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum Classification {
        NOT_MEASURABLE,
        GROSS_ONLY,
        EXACT_NET
    }

    public record CohortIdentity(String cohortId,
                                 long strategyId,
                                 String strategyFamily,
                                 String symbol,
                                 String codeCommit,
                                 String configSha256,
                                 String modelVersion,
                                 String signalSource,
                                 String executionMode) {
        public boolean complete() {
            return !blank(cohortId)
                    && strategyId > 0
                    && !blank(strategyFamily)
                    && !blank(symbol)
                    && !blank(codeCommit)
                    && !blank(configSha256)
                    && !blank(modelVersion)
                    && !blank(signalSource)
                    && !blank(executionMode);
        }
    }

    public record EvidenceCompleteness(boolean grossPnlComplete,
                                       boolean allFillsComplete,
                                       boolean exactSignedFeesComplete) {
    }

    public record ClosedEpisode(String episodeId,
                                CohortIdentity identity,
                                Instant startedAt,
                                Instant closedAt,
                                BigDecimal grossPnl,
                                BigDecimal exactSignedFee,
                                BigDecimal estimatedFee,
                                EvidenceCompleteness evidenceCompleteness) {
    }

    public record EpisodeMetric(String episodeId,
                                Classification classification,
                                BigDecimal grossPnl,
                                BigDecimal exactNet) {
        public EpisodeMetric {
            Objects.requireNonNull(episodeId, "episodeId");
            Objects.requireNonNull(classification, "classification");
        }
    }

    public record Result(Classification classification,
                         int closedEpisodes,
                         int exactFeeEpisodes,
                         int positiveExactNetEpisodes,
                         int legacyEpisodesExcluded,
                         int duplicateEvidenceIgnored,
                         List<EpisodeMetric> episodes,
                         List<String> blockers) {
        public Result {
            Objects.requireNonNull(classification, "classification");
            episodes = List.copyOf(episodes);
            blockers = List.copyOf(blockers);
        }
    }
}
