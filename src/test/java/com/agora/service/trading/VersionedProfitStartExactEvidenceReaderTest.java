package com.agora.service.trading;

import com.agora.config.OkxEvidenceProperties;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.repository.trading.evidence.ExactTradeFillAppendRepository;
import com.agora.service.trading.evidence.okx.ExactTradeFillCollectionService;
import com.agora.service.trading.evidence.okx.ExactTradeFillCollectionService.FillBinding;
import com.agora.service.trading.evidence.okx.ExactTradeFillHashing;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.CollectionRun;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RunStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionedProfitStartExactEvidenceReaderTest {
    private static final Instant EFFECTIVE = Instant.parse("2026-07-17T00:00:00Z");
    private static final Instant OPENED = Instant.parse("2026-07-17T00:30:00Z");
    private static final String ACCOUNT = "a".repeat(64);
    private static final String COHORT = "VPSTART1-485-BTCUSDT-TEST";
    private static final String CANDIDATE_RUN = "b".repeat(64);
    private static final String STABLE_RUN = "c".repeat(64);
    private final ExactTradeFillAppendRepository repository = mock(ExactTradeFillAppendRepository.class);

    @Test
    void readsOneExplicitCompleteStableEpisodeWithoutTimeWindowJoin() {
        OkxEvidenceProperties properties = properties();
        Map<String, FillBinding> bindings = Map.of(
                "entry-order", binding(ExactTradeFillCollectionService.EpisodeRole.ENTRY, false, null),
                "exit-order", binding(ExactTradeFillCollectionService.EpisodeRole.EXIT, false, null));
        String scope = ExactTradeFillHashing.bindingScope(EFFECTIVE, bindings);
        List<RawFill> fills = List.of(
                fill("entry-order", "trade-entry", "BUY", "100", "1", "-0.10",
                        Instant.parse("2026-07-17T01:00:00Z")),
                fill("exit-order", "trade-exit", "SELL", "110", "1", "-0.10",
                        Instant.parse("2026-07-17T02:00:00Z")));
        String fillSet = ExactTradeFillHashing.fillSet(fills);
        CollectionRun candidate = run(CANDIDATE_RUN, scope, RunStatus.COMPLETE_CANDIDATE, fillSet, null);
        CollectionRun stable = run(STABLE_RUN, scope, RunStatus.COMPLETE_STABLE, fillSet, CANDIDATE_RUN);
        when(repository.latestCompleteRun("okx", ACCOUNT, "BTC-USDT", "SPOT", scope))
                .thenReturn(Optional.of(new ExactTradeFillAppendRepository.PriorRun(STABLE_RUN, fillSet, scope)));
        when(repository.findRun(STABLE_RUN)).thenReturn(Optional.of(stable));
        when(repository.findRun(CANDIDATE_RUN)).thenReturn(Optional.of(candidate));
        when(repository.findRunFills(STABLE_RUN)).thenReturn(fills);

        VersionedProfitStartExactEvidenceReader.Result result =
                new VersionedProfitStartExactEvidenceReader(properties, repository).read(cohort(), episode());

        assertThat(result.exactNetMeasurable()).isTrue();
        assertThat(result.exactNetQuote()).isEqualByComparingTo("9.80");
        assertThat(result.canonicalFillCount()).isEqualTo(2);
        assertThat(result.blockers()).isEmpty();
    }

    @Test
    void candidateOrMissingExplicitRoleNeverBecomesExact() {
        OkxEvidenceProperties properties = properties();
        properties.getExactFillBindings().get(1).setEpisodeRole(null);

        VersionedProfitStartExactEvidenceReader.Result result =
                new VersionedProfitStartExactEvidenceReader(properties, repository).read(cohort(), episode());

        assertThat(result.exactNetMeasurable()).isFalse();
        assertThat(result.blockers()).contains("EXACT_READINESS_BINDING_SCOPE_MISMATCH");
    }

    @Test
    void missingEpisodeProviderOrderNeverBecomesExact() {
        VersionedProfitStartExactEvidenceReader.Result result =
                new VersionedProfitStartExactEvidenceReader(properties(), repository)
                        .read(cohort(), episode(null));

        assertThat(result.exactNetMeasurable()).isFalse();
        assertThat(result.blockers()).containsExactly("EXACT_READINESS_EPISODE_BINDING_INCOMPLETE");
    }

    @Test
    void differentProviderOrderNeverBecomesExact() {
        VersionedProfitStartExactEvidenceReader.Result result =
                new VersionedProfitStartExactEvidenceReader(properties(), repository)
                        .read(cohort(), episode("different-order"));

        assertThat(result.exactNetMeasurable()).isFalse();
        assertThat(result.blockers()).containsExactly(
                "EXACT_READINESS_PROVIDER_ORDER_ENTRY_BINDING_MISMATCH");
    }

    @Test
    void providerOrderMatchingOnlyExitNeverBecomesExact() {
        VersionedProfitStartExactEvidenceReader.Result result =
                new VersionedProfitStartExactEvidenceReader(properties(), repository)
                        .read(cohort(), episode("exit-order"));

        assertThat(result.exactNetMeasurable()).isFalse();
        assertThat(result.blockers()).containsExactly(
                "EXACT_READINESS_PROVIDER_ORDER_ENTRY_BINDING_MISMATCH");
    }

    @Test
    void entryOnlyScopeNeverBecomesExact() {
        OkxEvidenceProperties properties = properties();
        properties.setExactFillBindings(new java.util.ArrayList<>(List.of(
                configured("entry-order", OkxEvidenceProperties.ExactFillEpisodeRole.ENTRY))));

        VersionedProfitStartExactEvidenceReader.Result result =
                new VersionedProfitStartExactEvidenceReader(properties, repository).read(cohort(), episode());

        assertThat(result.exactNetMeasurable()).isFalse();
        assertThat(result.blockers()).contains("EXACT_READINESS_EXPLICIT_ENTRY_EXIT_SCOPE_INCOMPLETE");
    }

    @Test
    void unboundEntryScopeNeverBecomesExact() {
        OkxEvidenceProperties properties = properties();
        properties.getExactFillBindings().getFirst().setLiveSignalId(999L);

        VersionedProfitStartExactEvidenceReader.Result result =
                new VersionedProfitStartExactEvidenceReader(properties, repository).read(cohort(), episode());

        assertThat(result.exactNetMeasurable()).isFalse();
        assertThat(result.blockers()).contains("EXACT_READINESS_BINDING_SCOPE_MISMATCH");
    }

    private OkxEvidenceProperties properties() {
        OkxEvidenceProperties properties = new OkxEvidenceProperties();
        properties.setAccountRefHash(ACCOUNT);
        properties.setInstrumentId("BTC-USDT");
        properties.setInstrumentType("SPOT");
        properties.setExactFillBaseCurrency("BTC");
        properties.setExactFillQuoteCurrency("USDT");
        properties.setExactFillEffectiveFrom(EFFECTIVE);
        properties.setExactFillBindings(new java.util.ArrayList<>(List.of(
                configured("entry-order", OkxEvidenceProperties.ExactFillEpisodeRole.ENTRY),
                configured("exit-order", OkxEvidenceProperties.ExactFillEpisodeRole.EXIT))));
        return properties;
    }

    private OkxEvidenceProperties.ExactFillBinding configured(
            String orderId, OkxEvidenceProperties.ExactFillEpisodeRole role) {
        OkxEvidenceProperties.ExactFillBinding binding = new OkxEvidenceProperties.ExactFillBinding();
        binding.setOrderId(orderId);
        binding.setCohortId(COHORT);
        binding.setRuntimeDecisionId(101L);
        binding.setLiveSignalId(77L);
        binding.setOrderCreatedAt(OPENED);
        binding.setEpisodeRole(role);
        return binding;
    }

    private FillBinding binding(ExactTradeFillCollectionService.EpisodeRole role, boolean oco, String child) {
        return new FillBinding(COHORT, 101L, 77L, OPENED, role, oco, child, child);
    }

    private CollectionRun run(String runId, String scope, RunStatus status,
                              String fillSet, String priorRunId) {
        return new CollectionRun(runId, "okx", ACCOUNT, "BTC-USDT", "SPOT", scope, status,
                Instant.parse("2026-07-17T02:30:00Z"), Instant.parse("2026-07-17T02:31:00Z"),
                2, 2, null, fillSet, priorRunId);
    }

    private RawFill fill(String orderId, String tradeId, String side, String price,
                         String quantity, String fee, Instant fillAt) {
        RawFill draft = new RawFill("okx", ACCOUNT, "BTC-USDT", "SPOT", orderId, tradeId,
                "1" + tradeId.hashCode(), fillAt, side, new BigDecimal(price), new BigDecimal(quantity),
                new BigDecimal(fee), "USDT", null, "d".repeat(64), "e".repeat(64), fillAt,
                COHORT, 101L, 77L, null, null, null, null);
        return new RawFill(draft.provider(), draft.accountRefHash(), draft.instrumentId(), draft.instrumentType(),
                draft.orderId(), draft.tradeId(), draft.billId(), draft.fillAt(), draft.side(), draft.fillPrice(),
                draft.fillQuantity(), draft.signedFeeAmount(), draft.feeCurrency(), draft.liquidityRole(),
                draft.rawPayloadSha256(), draft.sourcePageKey(), draft.collectedAt(), draft.cohortId(),
                draft.runtimeDecisionId(), draft.liveSignalId(), draft.intendedChildOrderId(),
                draft.actualChildOrderId(), ExactTradeFillHashing.identity(draft), ExactTradeFillHashing.content(draft));
    }

    private RuntimeDecisionEvidenceRepository.CanonicalEpisodeBinding episode() {
        return episode("entry-order");
    }

    private RuntimeDecisionEvidenceRepository.CanonicalEpisodeBinding episode(String providerOrderId) {
        RuntimeDecisionEvidenceRepository.CanonicalEpisodeBinding episode =
                mock(RuntimeDecisionEvidenceRepository.CanonicalEpisodeBinding.class);
        when(episode.getDecisionId()).thenReturn(101L);
        when(episode.getLiveSignalId()).thenReturn(77L);
        when(episode.getProviderOrderId()).thenReturn(providerOrderId);
        when(episode.getEvidenceTime()).thenReturn(LocalDateTime.parse("2026-07-17T00:30:00"));
        when(episode.getExitTime()).thenReturn(LocalDateTime.parse("2026-07-17T03:00:00"));
        return episode;
    }

    private VersionedProfitStartCohortService.Snapshot cohort() {
        return new VersionedProfitStartCohortService.Snapshot(
                VersionedProfitStartCohortService.CONTRACT_VERSION,
                "COHORT_IDENTITY_READY_ACTIVATION_BLOCKED", true, true, false, COHORT,
                485L, "SCORE_BUY_V2", "BTCUSDT", "4".repeat(40), "5".repeat(64),
                "local-tradingview-parity-v1", "LOCAL_TRADINGVIEW", "LIVE_MICRO", EFFECTIVE,
                List.of(), List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER),
                List.of(VersionedProfitStartCohortService.EXACT_EVIDENCE_BLOCKER), true, false, false, false);
    }
}
