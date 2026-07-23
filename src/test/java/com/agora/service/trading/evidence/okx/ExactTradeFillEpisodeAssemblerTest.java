package com.agora.service.trading.evidence.okx;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RunStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExactTradeFillEpisodeAssemblerTest {
    private final ExactTradeFillEpisodeAssembler assembler = new ExactTradeFillEpisodeAssembler();

    @Test
    void baseAndQuoteFeeCostsAndRebatesConserveAndProduceExactNet() {
        RawFill buy = bound(fill("entry", "t1", "BUY", "100", "1", "-0.001", "BTC"));
        RawFill sell = bound(fill("exit", "t2", "SELL", "110", "0.999", "0.10", "USDT"));
        var result = assembler.assemble(binding(Set.of("t1", "t2"), buy, sell), List.of(buy, sell));

        assertThat(result.classification()).isEqualTo(ExactTradeFillEpisodeAssembler.Classification.EXACT_NET);
        assertThat(result.residualBase()).isEqualByComparingTo("0");
        assertThat(result.exactNetQuote()).isEqualByComparingTo("9.99");
    }

    @Test
    void residualThirdCurrencyMissingFillLateAndCurrencyMismatchAreNotMeasurable() {
        RawFill residual = bound(fill("entry", "t1", "BUY", "100", "1", "0", "USDT"));
        RawFill sell = bound(fill("exit", "t2", "SELL", "110", "0.9", "0", "USDT"));
        assertThat(assembler.assemble(binding(Set.of("t1", "t2"), residual, sell), List.of(residual, sell)).blockers())
                .contains("BASE_BALANCE_NOT_CONSERVED");

        RawFill third = bound(fill("entry", "t1", "BUY", "100", "1", "-1", "OKB"));
        assertThat(assembler.assemble(binding(Set.of("t1"), third), List.of(third)).blockers())
                .contains("UNSUPPORTED_FEE_CURRENCY", "BASE_BALANCE_NOT_CONSERVED");

        assertThat(assembler.assemble(binding(Set.of("t1", "missing"), residual), List.of(residual)).blockers())
                .contains("MISSING_OR_UNEXPECTED_FILL");

        RawFill late = rehash(residual, Instant.parse("2026-07-19T00:00:00Z"), residual.feeCurrency());
        assertThat(assembler.assemble(binding(Set.of("t1"), late), List.of(late)).blockers())
                .contains("PRE_EFFECTIVE_OR_OUT_OF_EPISODE_FILL");

        RawFill currencyMismatch = reInstrument(residual, "ETH-USDT");
        assertThat(assembler.assemble(binding(Set.of("t1"), currencyMismatch), List.of(currencyMismatch)).blockers())
                .contains("INSTRUMENT_SCOPE_MISMATCH");
    }

    @Test
    void cohortOcoAndIncompleteStableBindingFailClosed() {
        RawFill wrongCohort = rebind(fill("entry", "t1", "BUY", "100", "1", "0", "USDT"), "other", null, null);
        assertThat(assembler.assemble(binding(Set.of("t1"), wrongCohort), List.of(wrongCohort)).blockers())
                .contains("COHORT_BINDING_MISMATCH");

        RawFill missingOcoIdentity = bound(fill("exit", "t2", "SELL", "110", "1", "0", "USDT"));
        var requiredOco = binding(Set.of("t2"), Map.of(
                "entry", orderBinding(ExactTradeFillCollectionService.EpisodeRole.ENTRY, false),
                "exit", new ExactTradeFillCollectionService.FillBinding("cohort", 1L, 2L, start(),
                        ExactTradeFillCollectionService.EpisodeRole.EXIT, true, "exit", "exit")),
                missingOcoIdentity);
        assertThat(assembler.assemble(requiredOco, List.of(missingOcoIdentity)).blockers())
                .contains("OCO_REQUIRED_CHILD_IDENTITY_INCOMPLETE_OR_MISMATCH");

        var incomplete = new ExactTradeFillEpisodeAssembler.Binding(RunStatus.COMPLETE_CANDIDATE, "h", null,
                null, "okx", "a".repeat(64), "BTC-USDT", "SPOT", null, null, start(),
                "BTC", "USDT", start(), end(), Set.of("entry"), Set.of("exit"), Set.of("t1"), Map.of());
        assertThat(assembler.assemble(incomplete, List.of()).classification())
                .isEqualTo(ExactTradeFillEpisodeAssembler.Classification.EXACT_NET_NOT_MEASURABLE);
    }

    @Test
    void missingSidePriceOrQuantityNeverBecomesExact() {
        RawFill valid = bound(fill("entry", "t1", "BUY", "100", "1", "0", "USDT"));
        RawFill missingSide = mutateEconomics(valid, null, valid.fillPrice(), valid.fillQuantity());
        RawFill missingPrice = mutateEconomics(valid, valid.side(), null, valid.fillQuantity());
        RawFill missingQty = mutateEconomics(valid, valid.side(), valid.fillPrice(), null);
        assertThat(assembler.assemble(binding(Set.of("t1"), missingSide), List.of(missingSide)).classification())
                .isEqualTo(ExactTradeFillEpisodeAssembler.Classification.EXACT_NET_NOT_MEASURABLE);
        assertThat(assembler.assemble(binding(Set.of("t1"), missingPrice), List.of(missingPrice)).blockers())
                .contains("MISSING_PRICE_QTY_OR_SIGNED_FEE");
        assertThat(assembler.assemble(binding(Set.of("t1"), missingQty), List.of(missingQty)).blockers())
                .contains("MISSING_PRICE_QTY_OR_SIGNED_FEE");
    }

    @Test
    void canonicalFillSetHashMismatchNeverBecomesExact() {
        RawFill buy = bound(fill("entry", "t1", "BUY", "100", "1", "0", "USDT"));
        RawFill sell = bound(fill("exit", "t2", "SELL", "110", "1", "0", "USDT"));
        var valid = binding(Set.of("t1", "t2"), buy, sell);
        var mismatched = new ExactTradeFillEpisodeAssembler.Binding(RunStatus.COMPLETE_STABLE, "f".repeat(64),
                "a".repeat(64), valid.priorCollectionHash(), valid.provider(), valid.accountRefHash(),
                valid.instrumentId(), valid.instrumentType(), valid.bindingScopeSha256(),
                valid.priorBindingScopeSha256(), valid.effectiveFrom(), valid.baseCurrency(), valid.quoteCurrency(),
                valid.openedAt(), valid.closedAt(), valid.entryOrderIds(), valid.exitOrderIds(),
                valid.expectedTradeIds(), valid.orderBindings());

        assertThat(assembler.assemble(mismatched, List.of(buy, sell)).blockers())
                .contains("CANONICAL_FILL_SET_HASH_MISMATCH");
    }

    @Test
    void persistedHashesAndPriorCanonicalScopeAreRebuiltFailClosed() {
        RawFill valid = bound(fill("entry", "t1", "BUY", "100", "1", "0", "USDT"));
        RawFill forged = new RawFill(valid.provider(), valid.accountRefHash(), valid.instrumentId(),
                valid.instrumentType(), valid.orderId(), valid.tradeId(), valid.billId(), valid.fillAt(),
                valid.side(), valid.fillPrice(), valid.fillQuantity(), valid.signedFeeAmount(), valid.feeCurrency(),
                valid.liquidityRole(), valid.rawPayloadSha256(), valid.sourcePageKey(), valid.collectedAt(),
                valid.cohortId(), valid.runtimeDecisionId(), valid.liveSignalId(), valid.intendedChildOrderId(),
                valid.actualChildOrderId(), "b".repeat(64), "c".repeat(64));
        assertThat(assembler.assemble(binding(Set.of("t1"), forged), List.of(forged)).blockers())
                .contains("FILL_IDENTITY_HASH_MISMATCH", "FILL_CONTENT_HASH_MISMATCH");

        var validBinding = binding(Set.of("t1"), valid);
        var priorScopeMismatch = new ExactTradeFillEpisodeAssembler.Binding(validBinding.runStatus(),
                validBinding.collectionHash(), validBinding.priorStableRunId(), validBinding.priorCollectionHash(),
                validBinding.provider(), validBinding.accountRefHash(), validBinding.instrumentId(),
                validBinding.instrumentType(), validBinding.bindingScopeSha256(), "d".repeat(64),
                validBinding.effectiveFrom(), validBinding.baseCurrency(), validBinding.quoteCurrency(),
                validBinding.openedAt(), validBinding.closedAt(), validBinding.entryOrderIds(),
                validBinding.exitOrderIds(), validBinding.expectedTradeIds(), validBinding.orderBindings());
        assertThat(assembler.assemble(priorScopeMismatch, List.of(valid)).blockers())
                .contains("PRIOR_CANONICAL_SCOPE_MISMATCH");
    }

    @Test
    void fillBeforeOrderCreationAndCrossOrderTradeReuseFailClosed() {
        RawFill early = bound(rehash(fill("entry", "t1", "BUY", "100", "1", "0", "USDT"),
                start().minusSeconds(1), "USDT"));
        assertThat(assembler.assemble(binding(Set.of("t1"), early), List.of(early)).blockers())
                .contains("FILL_PRECEDES_ORDER_CREATION");

        RawFill entry = bound(fill("entry", "same", "BUY", "100", "1", "0", "USDT"));
        RawFill exit = bound(fill("exit", "same", "SELL", "100", "1", "0", "USDT"));
        assertThat(assembler.assemble(binding(Set.of("same"), entry, exit), List.of(entry, exit)).blockers())
                .contains("CROSS_ORDER_DUPLICATE_TRADE_ID");
    }

    @Test
    void explicitEpisodeRoleIsHashBoundAndMustMatchEntryExitScope() {
        Map<String, ExactTradeFillCollectionService.FillBinding> valid = Map.of(
                "entry", orderBinding(ExactTradeFillCollectionService.EpisodeRole.ENTRY, false),
                "exit", orderBinding(ExactTradeFillCollectionService.EpisodeRole.EXIT, false));
        Map<String, ExactTradeFillCollectionService.FillBinding> reversed = Map.of(
                "entry", orderBinding(ExactTradeFillCollectionService.EpisodeRole.EXIT, false),
                "exit", orderBinding(ExactTradeFillCollectionService.EpisodeRole.ENTRY, false));
        assertThat(ExactTradeFillHashing.bindingScope(start(), valid))
                .isNotEqualTo(ExactTradeFillHashing.bindingScope(start(), reversed));

        RawFill buy = bound(fill("entry", "t1", "BUY", "100", "1", "0", "USDT"));
        RawFill sell = bound(fill("exit", "t2", "SELL", "110", "1", "0", "USDT"));
        assertThat(assembler.assemble(binding(Set.of("t1", "t2"), reversed, buy, sell),
                List.of(buy, sell)).blockers()).contains("EXPLICIT_EPISODE_ROLE_BINDING_MISMATCH");
    }

    private static ExactTradeFillEpisodeAssembler.Binding binding(Set<String> trades, RawFill... fills) {
        return binding(trades, Map.of(
                "entry", orderBinding(ExactTradeFillCollectionService.EpisodeRole.ENTRY, false),
                "exit", orderBinding(ExactTradeFillCollectionService.EpisodeRole.EXIT, false)), fills);
    }
    private static ExactTradeFillEpisodeAssembler.Binding binding(Set<String> trades,
                                                                   Map<String, ExactTradeFillCollectionService.FillBinding> orderBindings,
                                                                   RawFill... fills) {
        String collectionHash = ExactTradeFillHashing.fillSet(List.of(fills));
        String scope = ExactTradeFillHashing.bindingScope(start(), orderBindings);
        return new ExactTradeFillEpisodeAssembler.Binding(RunStatus.COMPLETE_STABLE, collectionHash,
                "a".repeat(64), collectionHash, "okx", "a".repeat(64), "BTC-USDT", "SPOT", scope, scope,
                start(), "BTC", "USDT", start(), end(), Set.of("entry"), Set.of("exit"), trades, orderBindings);
    }
    private static ExactTradeFillCollectionService.FillBinding orderBinding(
            ExactTradeFillCollectionService.EpisodeRole role, boolean ocoRequired) {
        return new ExactTradeFillCollectionService.FillBinding("cohort", 1L, 2L, start(), role,
                ocoRequired, ocoRequired ? "exit" : null, ocoRequired ? "exit" : null);
    }
    private static Instant start() { return Instant.parse("2026-07-17T00:00:00Z"); }
    private static Instant end() { return Instant.parse("2026-07-18T12:00:00Z"); }
    private static RawFill fill(String order, String trade, String side, String px, String qty, String fee, String ccy) {
        return ExactTradeFillCollectionServiceTest.fill(order, trade, trade, side, px, qty, fee, ccy, "p");
    }
    private static RawFill bound(RawFill f) { return rebind(f, "cohort", 1L, 2L); }
    private static RawFill rebind(RawFill f, String cohort, Long decision, Long signal) {
        RawFill d = new RawFill(f.provider(), f.accountRefHash(), f.instrumentId(), f.instrumentType(), f.orderId(),
                f.tradeId(), f.billId(), f.fillAt(), f.side(), f.fillPrice(), f.fillQuantity(), f.signedFeeAmount(),
                f.feeCurrency(), f.liquidityRole(), f.rawPayloadSha256(), f.sourcePageKey(), f.collectedAt(), cohort,
                decision, signal, f.intendedChildOrderId(), f.actualChildOrderId(), null, null);
        return ExactTradeFillCollectionServiceTest.hashed(d);
    }
    private static RawFill rehash(RawFill f, Instant at, String ccy) {
        RawFill d = new RawFill(f.provider(), f.accountRefHash(), f.instrumentId(), f.instrumentType(), f.orderId(),
                f.tradeId(), f.billId(), at, f.side(), f.fillPrice(), f.fillQuantity(), f.signedFeeAmount(), ccy,
                f.liquidityRole(), f.rawPayloadSha256(), f.sourcePageKey(), f.collectedAt(), f.cohortId(),
                f.runtimeDecisionId(), f.liveSignalId(), f.intendedChildOrderId(), f.actualChildOrderId(), null, null);
        return ExactTradeFillCollectionServiceTest.hashed(d);
    }
    private static RawFill reInstrument(RawFill f, String instrumentId) {
        RawFill d = new RawFill(f.provider(), f.accountRefHash(), instrumentId, f.instrumentType(), f.orderId(),
                f.tradeId(), f.billId(), f.fillAt(), f.side(), f.fillPrice(), f.fillQuantity(), f.signedFeeAmount(),
                f.feeCurrency(), f.liquidityRole(), f.rawPayloadSha256(), f.sourcePageKey(), f.collectedAt(),
                f.cohortId(), f.runtimeDecisionId(), f.liveSignalId(), f.intendedChildOrderId(),
                f.actualChildOrderId(), null, null);
        return ExactTradeFillCollectionServiceTest.hashed(d);
    }
    private static RawFill oco(RawFill f, String intended, String actual) {
        RawFill d = new RawFill(f.provider(), f.accountRefHash(), f.instrumentId(), f.instrumentType(), f.orderId(),
                f.tradeId(), f.billId(), f.fillAt(), f.side(), f.fillPrice(), f.fillQuantity(), f.signedFeeAmount(),
                f.feeCurrency(), f.liquidityRole(), f.rawPayloadSha256(), f.sourcePageKey(), f.collectedAt(), f.cohortId(),
                f.runtimeDecisionId(), f.liveSignalId(), intended, actual, null, null);
        return ExactTradeFillCollectionServiceTest.hashed(d);
    }
    private static RawFill mutateEconomics(RawFill f, String side, BigDecimal price, BigDecimal qty) {
        RawFill d = new RawFill(f.provider(), f.accountRefHash(), f.instrumentId(), f.instrumentType(), f.orderId(),
                f.tradeId(), f.billId(), f.fillAt(), side, price, qty, f.signedFeeAmount(), f.feeCurrency(),
                f.liquidityRole(), f.rawPayloadSha256(), f.sourcePageKey(), f.collectedAt(), f.cohortId(),
                f.runtimeDecisionId(), f.liveSignalId(), f.intendedChildOrderId(), f.actualChildOrderId(), null, null);
        return ExactTradeFillCollectionServiceTest.hashed(d);
    }
}
