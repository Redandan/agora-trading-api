package com.agora.service.trading.evidence.okx;

import com.agora.service.trading.evidence.okx.ExactTradeFillCollectionService.FillBinding;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RunStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Pure exact-net assembler. Every persisted hash and scope claim is independently rebuilt. */
public final class ExactTradeFillEpisodeAssembler {

    public Result assemble(Binding b, Collection<RawFill> evidence) {
        List<String> blockers = new ArrayList<>();
        if (incomplete(b, evidence)) return notMeasurable("EXACT_BINDING_INCOMPLETE");

        Set<String> episodeOrders = new HashSet<>(b.entryOrderIds());
        episodeOrders.addAll(b.exitOrderIds());
        if (!episodeOrders.equals(b.orderBindings().keySet())) blockers.add("ORDER_BINDING_SCOPE_MISMATCH");
        for (Map.Entry<String, FillBinding> order : b.orderBindings().entrySet()) {
            boolean entry = b.entryOrderIds().contains(order.getKey());
            boolean exit = b.exitOrderIds().contains(order.getKey());
            if (entry == exit || order.getValue() == null || order.getValue().episodeRole() == null
                    || entry != (order.getValue().episodeRole()
                    == ExactTradeFillCollectionService.EpisodeRole.ENTRY)
                    || exit != (order.getValue().episodeRole()
                    == ExactTradeFillCollectionService.EpisodeRole.EXIT)) {
                blockers.add("EXPLICIT_EPISODE_ROLE_BINDING_MISMATCH");
            }
        }
        String rebuiltScope = ExactTradeFillHashing.bindingScope(b.effectiveFrom(), b.orderBindings());
        if (!b.bindingScopeSha256().equals(rebuiltScope)) blockers.add("BINDING_SCOPE_HASH_MISMATCH");
        if (!b.bindingScopeSha256().equals(b.priorBindingScopeSha256())) {
            blockers.add("PRIOR_CANONICAL_SCOPE_MISMATCH");
        }
        if (!b.collectionHash().equals(b.priorCollectionHash())) {
            blockers.add("PRIOR_CANONICAL_FILL_SET_MISMATCH");
        }
        if (b.openedAt().isBefore(b.effectiveFrom())) blockers.add("PRE_EFFECTIVE_EPISODE");
        if (b.orderBindings().values().stream().anyMatch(binding -> binding.orderCreatedAt() == null
                || binding.orderCreatedAt().isBefore(b.effectiveFrom()))) {
            blockers.add("PRE_EFFECTIVE_ORDER_BOUND_TO_COHORT");
        }

        Map<String, RawFill> unique = new LinkedHashMap<>();
        for (RawFill fill : evidence) {
            if (fill == null || !hash64(fill.identitySha256()) || !hash64(fill.contentSha256())) {
                blockers.add("INVALID_FILL");
                continue;
            }
            String rebuiltIdentity = ExactTradeFillHashing.identity(fill);
            String rebuiltContent = ExactTradeFillHashing.content(fill);
            if (!fill.identitySha256().equals(rebuiltIdentity)) blockers.add("FILL_IDENTITY_HASH_MISMATCH");
            if (!fill.contentSha256().equals(rebuiltContent)) blockers.add("FILL_CONTENT_HASH_MISMATCH");
            RawFill prior = unique.putIfAbsent(rebuiltIdentity, fill);
            if (prior != null && !ExactTradeFillHashing.content(prior).equals(rebuiltContent)) {
                blockers.add("IMMUTABLE_FILL_CONFLICT");
            }
        }
        String actualCollectionHash = ExactTradeFillHashing.fillSet(List.copyOf(unique.values()));
        if (!b.collectionHash().equals(actualCollectionHash)) blockers.add("CANONICAL_FILL_SET_HASH_MISMATCH");

        Set<String> actualTrades = new HashSet<>();
        Map<String, String> tradeOrders = new HashMap<>();
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal quote = BigDecimal.ZERO;
        String currencyInstrument = b.baseCurrency().toUpperCase(Locale.ROOT) + "-"
                + b.quoteCurrency().toUpperCase(Locale.ROOT);
        if (!currencyInstrument.equalsIgnoreCase(b.instrumentId())) blockers.add("INSTRUMENT_CURRENCY_MISMATCH");
        for (RawFill fill : unique.values()) {
            if (!b.provider().equals(fill.provider()) || !b.accountRefHash().equals(fill.accountRefHash())) {
                blockers.add("PROVIDER_OR_ACCOUNT_SCOPE_MISMATCH");
            }
            if (!b.instrumentType().equals(fill.instrumentType())
                    || !b.instrumentId().equalsIgnoreCase(fill.instrumentId())) {
                blockers.add("INSTRUMENT_SCOPE_MISMATCH");
            }
            FillBinding orderBinding = b.orderBindings().get(fill.orderId());
            if (orderBinding == null || !Objects.equals(orderBinding.cohortId(), fill.cohortId())
                    || !Objects.equals(orderBinding.runtimeDecisionId(), fill.runtimeDecisionId())
                    || !Objects.equals(orderBinding.liveSignalId(), fill.liveSignalId())
                    || !Objects.equals(orderBinding.intendedChildOrderId(), fill.intendedChildOrderId())
                    || !Objects.equals(orderBinding.actualChildOrderId(), fill.actualChildOrderId())) {
                blockers.add("COHORT_BINDING_MISMATCH");
            }
            if (orderBinding != null && (fill.fillAt() == null
                    || fill.fillAt().isBefore(orderBinding.orderCreatedAt()))) {
                blockers.add("FILL_PRECEDES_ORDER_CREATION");
            }
            String priorTradeOrder = tradeOrders.putIfAbsent(fill.tradeId(), fill.orderId());
            if (priorTradeOrder != null && !priorTradeOrder.equals(fill.orderId())) {
                blockers.add("CROSS_ORDER_DUPLICATE_TRADE_ID");
            }
            if (fill.fillAt() == null || fill.fillAt().isBefore(b.effectiveFrom())
                    || fill.fillAt().isBefore(b.openedAt()) || fill.fillAt().isAfter(b.closedAt())) {
                blockers.add("PRE_EFFECTIVE_OR_OUT_OF_EPISODE_FILL");
            }
            boolean entry = b.entryOrderIds().contains(fill.orderId());
            boolean exit = b.exitOrderIds().contains(fill.orderId());
            if (entry == exit) blockers.add("ORDER_SIDE_BINDING_MISSING");
            if (entry && !"BUY".equals(fill.side()) || exit && !"SELL".equals(fill.side())) {
                blockers.add("SIDE_MISMATCH");
            }
            if (fill.fillPrice() == null || fill.fillPrice().signum() <= 0
                    || fill.fillQuantity() == null || fill.fillQuantity().signum() <= 0
                    || fill.signedFeeAmount() == null || blank(fill.feeCurrency())) {
                blockers.add("MISSING_PRICE_QTY_OR_SIGNED_FEE");
                continue;
            }
            if (exit && orderBinding != null && orderBinding.ocoRequired()) {
                if (blank(fill.intendedChildOrderId()) || blank(fill.actualChildOrderId())
                        || !fill.intendedChildOrderId().equals(fill.actualChildOrderId())
                        || !fill.orderId().equals(fill.actualChildOrderId())) {
                    blockers.add("OCO_REQUIRED_CHILD_IDENTITY_INCOMPLETE_OR_MISMATCH");
                }
            } else if (exit && (!blank(fill.intendedChildOrderId()) || !blank(fill.actualChildOrderId()))
                    && (!Objects.equals(fill.intendedChildOrderId(), fill.actualChildOrderId())
                    || !Objects.equals(fill.orderId(), fill.actualChildOrderId()))) {
                blockers.add("OCO_CHILD_MISMATCH");
            }
            actualTrades.add(fill.tradeId());
            BigDecimal notional = fill.fillPrice().multiply(fill.fillQuantity());
            if ("BUY".equals(fill.side())) {
                base = base.add(fill.fillQuantity());
                quote = quote.subtract(notional);
            } else if ("SELL".equals(fill.side())) {
                base = base.subtract(fill.fillQuantity());
                quote = quote.add(notional);
            } else {
                blockers.add("SIDE_MISSING");
            }
            if (b.baseCurrency().equalsIgnoreCase(fill.feeCurrency())) base = base.add(fill.signedFeeAmount());
            else if (b.quoteCurrency().equalsIgnoreCase(fill.feeCurrency())) quote = quote.add(fill.signedFeeAmount());
            else blockers.add("UNSUPPORTED_FEE_CURRENCY");
        }
        if (!actualTrades.equals(b.expectedTradeIds())) blockers.add("MISSING_OR_UNEXPECTED_FILL");
        if (base.compareTo(BigDecimal.ZERO) != 0) blockers.add("BASE_BALANCE_NOT_CONSERVED");
        if (!blockers.isEmpty()) return new Result(Classification.EXACT_NET_NOT_MEASURABLE, null, base, quote,
                unique.size(), List.copyOf(blockers.stream().distinct().toList()));
        return new Result(Classification.EXACT_NET, quote, base, quote, unique.size(), List.of());
    }

    private static boolean incomplete(Binding b, Collection<RawFill> evidence) {
        return b == null || b.runStatus() != RunStatus.COMPLETE_STABLE || !hash64(b.collectionHash())
                || !hash64(b.priorCollectionHash()) || !hash64(b.bindingScopeSha256())
                || !hash64(b.priorBindingScopeSha256()) || !hash64(b.priorStableRunId())
                || blank(b.provider()) || !hash64(b.accountRefHash()) || blank(b.instrumentId())
                || blank(b.instrumentType()) || b.effectiveFrom() == null || blank(b.baseCurrency())
                || blank(b.quoteCurrency()) || b.openedAt() == null || b.closedAt() == null
                || b.closedAt().isBefore(b.openedAt()) || empty(b.entryOrderIds()) || empty(b.exitOrderIds())
                || empty(b.expectedTradeIds()) || b.orderBindings() == null || b.orderBindings().isEmpty()
                || evidence == null;
    }

    private static Result notMeasurable(String blocker) {
        return new Result(Classification.EXACT_NET_NOT_MEASURABLE, null, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, List.of(blocker));
    }
    private static boolean hash64(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean empty(Collection<?> value) { return value == null || value.isEmpty(); }

    public enum Classification { EXACT_NET, EXACT_NET_NOT_MEASURABLE }
    public record Binding(RunStatus runStatus, String collectionHash, String priorStableRunId,
                          String priorCollectionHash, String provider, String accountRefHash,
                          String instrumentId, String instrumentType, String bindingScopeSha256,
                          String priorBindingScopeSha256, Instant effectiveFrom,
                          String baseCurrency, String quoteCurrency, Instant openedAt, Instant closedAt,
                          Set<String> entryOrderIds, Set<String> exitOrderIds, Set<String> expectedTradeIds,
                          Map<String, FillBinding> orderBindings) {
        public Binding {
            entryOrderIds = entryOrderIds == null ? Set.of() : Set.copyOf(entryOrderIds);
            exitOrderIds = exitOrderIds == null ? Set.of() : Set.copyOf(exitOrderIds);
            expectedTradeIds = expectedTradeIds == null ? Set.of() : Set.copyOf(expectedTradeIds);
            orderBindings = orderBindings == null ? Map.of() : Map.copyOf(orderBindings);
        }
    }
    public record Result(Classification classification, BigDecimal exactNetQuote,
                         BigDecimal residualBase, BigDecimal quoteBalance,
                         int canonicalFillCount, List<String> blockers) { }
}
