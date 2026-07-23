package com.agora.service.trading;

import com.agora.config.OkxEvidenceProperties;
import com.agora.config.OkxEvidenceProperties.ExactFillEpisodeRole;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.repository.trading.evidence.ExactTradeFillAppendRepository;
import com.agora.service.trading.evidence.okx.ExactTradeFillCollectionService;
import com.agora.service.trading.evidence.okx.ExactTradeFillCollectionService.FillBinding;
import com.agora.service.trading.evidence.okx.ExactTradeFillEpisodeAssembler;
import com.agora.service.trading.evidence.okx.ExactTradeFillHashing;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.CollectionRun;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RunStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only bridge from one explicitly configured collection scope to the pure
 * exact-net assembler. It never joins by symbol/time and never calls a provider.
 */
@Service
@RequiredArgsConstructor
public class VersionedProfitStartExactEvidenceReader {

    private final OkxEvidenceProperties properties;
    private final ExactTradeFillAppendRepository repository;
    private final ExactTradeFillEpisodeAssembler assembler = new ExactTradeFillEpisodeAssembler();

    public Result read(VersionedProfitStartCohortService.Snapshot cohort,
                       RuntimeDecisionEvidenceRepository.CanonicalEpisodeBinding episode) {
        List<String> blockers = new ArrayList<>();
        try {
            if (cohort == null || episode == null || cohort.effectiveFrom() == null
                    || episode.getDecisionId() == null || episode.getLiveSignalId() == null
                    || blank(episode.getProviderOrderId())
                    || episode.getEvidenceTime() == null || episode.getExitTime() == null) {
                return blocked("EXACT_READINESS_EPISODE_BINDING_INCOMPLETE");
            }
            if (!Objects.equals(properties.getExactFillEffectiveFrom(), cohort.effectiveFrom())) {
                return blocked("EXACT_READINESS_EFFECTIVE_FROM_MISMATCH");
            }
            if (blank(properties.getAccountRefHash()) || blank(properties.getInstrumentId())
                    || blank(properties.getInstrumentType()) || blank(properties.getExactFillBaseCurrency())
                    || blank(properties.getExactFillQuoteCurrency())) {
                return blocked("EXACT_READINESS_COLLECTION_SCOPE_INCOMPLETE");
            }

            Map<String, FillBinding> bindings = new LinkedHashMap<>();
            Set<String> entries = new LinkedHashSet<>();
            Set<String> exits = new LinkedHashSet<>();
            for (OkxEvidenceProperties.ExactFillBinding configured : properties.getExactFillBindings()) {
                if (configured == null || blank(configured.getOrderId()) || configured.getEpisodeRole() == null
                        || !cohort.cohortId().equals(configured.getCohortId())
                        || !episode.getDecisionId().equals(configured.getRuntimeDecisionId())
                        || !episode.getLiveSignalId().equals(configured.getLiveSignalId())) {
                    blockers.add("EXACT_READINESS_BINDING_SCOPE_MISMATCH");
                    continue;
                }
                FillBinding binding = new FillBinding(configured.getCohortId(), configured.getRuntimeDecisionId(),
                        configured.getLiveSignalId(), configured.getOrderCreatedAt(),
                        ExactTradeFillCollectionService.EpisodeRole.valueOf(configured.getEpisodeRole().name()),
                        configured.isOcoRequired(),
                        configured.getIntendedChildOrderId(), configured.getActualChildOrderId());
                if (bindings.putIfAbsent(configured.getOrderId(), binding) != null) {
                    blockers.add("EXACT_READINESS_DUPLICATE_ORDER_BINDING");
                }
                (configured.getEpisodeRole() == ExactFillEpisodeRole.ENTRY ? entries : exits)
                        .add(configured.getOrderId());
            }
            if (!blockers.isEmpty() || bindings.isEmpty() || entries.isEmpty() || exits.isEmpty()
                    || bindings.size() != properties.getExactFillBindings().size()) {
                blockers.add("EXACT_READINESS_EXPLICIT_ENTRY_EXIT_SCOPE_INCOMPLETE");
                return blocked(blockers);
            }
            if (!entries.contains(episode.getProviderOrderId())) {
                return blocked("EXACT_READINESS_PROVIDER_ORDER_ENTRY_BINDING_MISMATCH");
            }

            String scope = ExactTradeFillHashing.bindingScope(cohort.effectiveFrom(), bindings);
            ExactTradeFillAppendRepository.PriorRun latest = repository.latestCompleteRun(
                            "okx", properties.getAccountRefHash(), properties.getInstrumentId(),
                            properties.getInstrumentType(), scope)
                    .orElse(null);
            if (latest == null) return blocked("EXACT_READINESS_COLLECTION_RUN_MISSING");
            CollectionRun run = repository.findRun(latest.runId()).orElse(null);
            if (run == null || run.status() != RunStatus.COMPLETE_STABLE || blank(run.priorStableRunId())) {
                return blocked("EXACT_READINESS_COLLECTION_NOT_COMPLETE_STABLE");
            }
            CollectionRun prior = repository.findRun(run.priorStableRunId()).orElse(null);
            if (prior == null) return blocked("EXACT_READINESS_PRIOR_RUN_MISSING");
            List<RawFill> fills = repository.findRunFills(run.runId());
            if (fills == null || fills.isEmpty()) return blocked("EXACT_READINESS_STABLE_RUN_FILL_SET_EMPTY");
            if (fills.stream().anyMatch(fill -> fill == null
                    || !cohort.cohortId().equals(fill.cohortId())
                    || !episode.getDecisionId().equals(fill.runtimeDecisionId())
                    || !episode.getLiveSignalId().equals(fill.liveSignalId()))) {
                return blocked("EXACT_READINESS_MULTI_EPISODE_OR_UNBOUND_RUN");
            }

            Set<String> expectedTrades = fills.stream().map(RawFill::tradeId)
                    .filter(value -> !blank(value)).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            ExactTradeFillEpisodeAssembler.Binding assembly = new ExactTradeFillEpisodeAssembler.Binding(
                    run.status(), run.canonicalFillSetSha256(), run.priorStableRunId(),
                    prior.canonicalFillSetSha256(), run.provider(), run.accountRefHash(), run.instrumentId(),
                    run.instrumentType(), run.bindingScopeSha256(), prior.bindingScopeSha256(),
                    cohort.effectiveFrom(), properties.getExactFillBaseCurrency(),
                    properties.getExactFillQuoteCurrency(),
                    episode.getEvidenceTime().toInstant(ZoneOffset.UTC),
                    episode.getExitTime().toInstant(ZoneOffset.UTC), entries, exits, expectedTrades, bindings);
            ExactTradeFillEpisodeAssembler.Result assembled = assembler.assemble(assembly, fills);
            if (assembled.classification() != ExactTradeFillEpisodeAssembler.Classification.EXACT_NET) {
                return blocked(assembled.blockers());
            }
            return new Result(true, assembled.exactNetQuote(), assembled.canonicalFillCount(), List.of());
        } catch (Exception e) {
            return blocked("EXACT_READINESS_READ_OR_ASSEMBLY_FAILED");
        }
    }

    private static Result blocked(String blocker) {
        return blocked(List.of(blocker));
    }

    private static Result blocked(List<String> blockers) {
        return new Result(false, null, 0, blockers.stream().distinct().toList());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Result(boolean exactNetMeasurable, java.math.BigDecimal exactNetQuote,
                         int canonicalFillCount, List<String> blockers) {
        public Result {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }
}
