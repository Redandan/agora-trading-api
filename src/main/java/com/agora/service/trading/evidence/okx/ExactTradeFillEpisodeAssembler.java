package com.agora.service.trading.evidence.okx;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RunStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Pure exact-net assembler using base/quote conservation and the provider's signed fee currency. */
public final class ExactTradeFillEpisodeAssembler {

    public Result assemble(Binding b, Collection<RawFill> evidence) {
        List<String> blockers = new ArrayList<>();
        if (b == null || b.runStatus() != RunStatus.COMPLETE_STABLE || blank(b.collectionHash())
                || blank(b.priorStableRunId()) || blank(b.cohortId()) || b.runtimeDecisionId() == null
                || b.liveSignalId() == null || blank(b.baseCurrency()) || blank(b.quoteCurrency())
                || b.openedAt() == null || b.closedAt() == null || b.closedAt().isBefore(b.openedAt())
                || empty(b.entryOrderIds()) || empty(b.exitOrderIds()) || empty(b.expectedTradeIds())
                || evidence == null) {
            return notMeasurable("EXACT_BINDING_INCOMPLETE");
        }
        Map<String, RawFill> unique = new LinkedHashMap<>();
        for (RawFill f : evidence) {
            if (f == null || blank(f.identitySha256()) || blank(f.contentSha256())) {
                blockers.add("INVALID_FILL"); continue;
            }
            RawFill prior = unique.putIfAbsent(f.identitySha256(), f);
            if (prior != null && !prior.contentSha256().equals(f.contentSha256())) {
                blockers.add("IMMUTABLE_FILL_CONFLICT");
            }
        }
        String actualCollectionHash = ExactTradeFillHashing.fillSet(List.copyOf(unique.values()));
        if (!b.collectionHash().equals(actualCollectionHash)) blockers.add("CANONICAL_FILL_SET_HASH_MISMATCH");
        Set<String> actualTrades = new HashSet<>();
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal quote = BigDecimal.ZERO;
        String expectedInstrument = b.baseCurrency().toUpperCase(Locale.ROOT) + "-"
                + b.quoteCurrency().toUpperCase(Locale.ROOT);
        for (RawFill f : unique.values()) {
            if (!b.cohortId().equals(f.cohortId()) || !b.runtimeDecisionId().equals(f.runtimeDecisionId())
                    || !b.liveSignalId().equals(f.liveSignalId())) blockers.add("COHORT_BINDING_MISMATCH");
            if (!"SPOT".equals(f.instrumentType()) || !expectedInstrument.equalsIgnoreCase(f.instrumentId())) {
                blockers.add("INSTRUMENT_CURRENCY_MISMATCH");
            }
            if (f.fillAt() == null || f.fillAt().isBefore(b.openedAt()) || f.fillAt().isAfter(b.closedAt())) {
                blockers.add("LATE_OR_OUT_OF_EPISODE_FILL");
            }
            boolean entry = b.entryOrderIds().contains(f.orderId());
            boolean exit = b.exitOrderIds().contains(f.orderId());
            if (entry == exit) blockers.add("ORDER_SIDE_BINDING_MISSING");
            if (entry && !"BUY".equals(f.side()) || exit && !"SELL".equals(f.side())) {
                blockers.add("SIDE_MISMATCH");
            }
            if (f.fillPrice() == null || f.fillPrice().signum() <= 0
                    || f.fillQuantity() == null || f.fillQuantity().signum() <= 0
                    || f.signedFeeAmount() == null || blank(f.feeCurrency())) {
                blockers.add("MISSING_PRICE_QTY_OR_SIGNED_FEE"); continue;
            }
            if (exit && (f.intendedChildOrderId() != null || f.actualChildOrderId() != null)
                    && (!Objects.equals(f.intendedChildOrderId(), f.actualChildOrderId())
                    || !Objects.equals(f.orderId(), f.actualChildOrderId()))) {
                blockers.add("OCO_CHILD_MISMATCH");
            }
            actualTrades.add(f.tradeId());
            BigDecimal notional = f.fillPrice().multiply(f.fillQuantity());
            if ("BUY".equals(f.side())) { base = base.add(f.fillQuantity()); quote = quote.subtract(notional); }
            else if ("SELL".equals(f.side())) { base = base.subtract(f.fillQuantity()); quote = quote.add(notional); }
            else { blockers.add("SIDE_MISSING"); }
            if (b.baseCurrency().equalsIgnoreCase(f.feeCurrency())) base = base.add(f.signedFeeAmount());
            else if (b.quoteCurrency().equalsIgnoreCase(f.feeCurrency())) quote = quote.add(f.signedFeeAmount());
            else blockers.add("UNSUPPORTED_FEE_CURRENCY");
        }
        if (!actualTrades.equals(b.expectedTradeIds())) blockers.add("MISSING_OR_UNEXPECTED_FILL");
        if (base.compareTo(BigDecimal.ZERO) != 0) blockers.add("BASE_BALANCE_NOT_CONSERVED");
        if (!blockers.isEmpty()) return new Result(Classification.EXACT_NET_NOT_MEASURABLE, null, base, quote,
                unique.size(), List.copyOf(blockers.stream().distinct().toList()));
        return new Result(Classification.EXACT_NET, quote, base, quote, unique.size(), List.of());
    }

    private static Result notMeasurable(String blocker) {
        return new Result(Classification.EXACT_NET_NOT_MEASURABLE, null, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, List.of(blocker));
    }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
    private static boolean empty(Collection<?> v) { return v == null || v.isEmpty(); }

    public enum Classification { EXACT_NET, EXACT_NET_NOT_MEASURABLE }
    public record Binding(RunStatus runStatus, String collectionHash, String priorStableRunId,
                          String cohortId, Long runtimeDecisionId, Long liveSignalId,
                          String baseCurrency, String quoteCurrency, Instant openedAt, Instant closedAt,
                          Set<String> entryOrderIds, Set<String> exitOrderIds, Set<String> expectedTradeIds) {
        public Binding {
            entryOrderIds = entryOrderIds == null ? Set.of() : Set.copyOf(entryOrderIds);
            exitOrderIds = exitOrderIds == null ? Set.of() : Set.copyOf(exitOrderIds);
            expectedTradeIds = expectedTradeIds == null ? Set.of() : Set.copyOf(expectedTradeIds);
        }
    }
    public record Result(Classification classification, BigDecimal exactNetQuote,
                         BigDecimal residualBase, BigDecimal quoteBalance,
                         int canonicalFillCount, List<String> blockers) { }
}
