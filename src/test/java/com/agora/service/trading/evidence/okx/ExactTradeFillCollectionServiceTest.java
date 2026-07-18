package com.agora.service.trading.evidence.okx;

import com.agora.repository.trading.evidence.ExactTradeFillAppendRepository;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ExactTradeFillCollectionServiceTest {
    private static final String ACCOUNT = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void terminalProviderOrderedChainProducesStableCanonicalHash() {
        FakeRepository repo = new FakeRepository();
        RawFill a = fill("o1", "t1", "900", "BUY", "100", "1", "-0.01", "USDT", "p1");
        RawFill b = fill("o2", "t2", "800", "SELL", "110", "1", "-0.01", "USDT", "p2");
        var firstClient = client(page(null, "900", false, a), page("900", "800", false, b), terminal("800"));
        var first = new ExactTradeFillCollectionService(firstClient, repo).collect(request("1".repeat(64)));

        assertThat(first.run().status()).isEqualTo(RunStatus.COMPLETE_CANDIDATE);
        assertThat(first.run().fillCount()).isEqualTo(2);
        assertThat(repo.appended.fills()).extracting(RawFill::tradeId).containsExactly("t1", "t2");

        var secondClient = client(page(null, "900", false, a), page("900", "800", false, b), terminal("800"));
        var second = new ExactTradeFillCollectionService(secondClient, repo).collect(request("2".repeat(64)));

        assertThat(second.run().status()).isEqualTo(RunStatus.COMPLETE_STABLE);
        assertThat(second.run().priorStableRunId()).isEqualTo(first.run().runId());
        assertThat(second.run().canonicalFillSetSha256()).isEqualTo(first.run().canonicalFillSetSha256());
    }

    @Test
    void partialPageAndMissingTerminalFailBeforeAppend() {
        RawPage partial = new RawPage(null, null, "1".repeat(64), "2".repeat(64), NOW,
                false, false, List.of());
        FakeRepository repo = new FakeRepository();
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(partial), repo)
                .collect(request("3".repeat(64)))).hasMessage("PARTIAL_PAGE");
        assertThat(repo.appendCalls).isZero();

        RawFill a = fill("o1", "t1", "900", "BUY", "100", "1", "0", "USDT", "p1");
        var bounded = new ExactTradeFillCollectionService.Request("4".repeat(64), ACCOUNT,
                "BTC-USDT", "SPOT", 100, 2, effectiveFrom(), bindings());
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(
                page(null, "900", false, a), page("900", "800", false,
                        fill("o2", "t2", "800", "SELL", "101", "1", "0", "USDT", "p2"))), repo)
                .collect(bounded)).hasMessage("TERMINAL_PAGE_NOT_PROVEN");
        assertThat(repo.appendCalls).isZero();
    }

    @Test
    void cursorRangeAndConflictingDuplicateFailClosed() {
        RawFill a = fill("o1", "t1", "900", "BUY", "100", "1", "0", "USDT", "p1");
        FakeRepository repo = new FakeRepository();
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(
                page(null, "900", false, a), page("900", "800", false,
                        fill("o2", "t2", "800", "SELL", "101", "1", "0", "USDT", "p2")),
                page("800", "801", false, fill("o1", "t3", "801", "BUY", "100", "1", "0", "USDT", "p3"))), repo)
                .collect(request("5".repeat(64)))).hasMessage("AFTER_CURSOR_RANGE_NOT_PROVEN");

        RawFill conflict = fill("o1", "t1", "899", "BUY", "101", "1", "0", "USDT", "p2");
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(
                page(null, "899", false, a, conflict), terminal("899")), repo)
                .collect(request("6".repeat(64)))).hasMessage("PERMANENT_IDENTITY_CONFLICT");
        assertThat(repo.appendCalls).isZero();
    }

    @Test
    void sameRunIsIdempotentOnlyAfterReprovingIdenticalChildren() {
        FakeRepository repo = new FakeRepository();
        CountingClient reads = new CountingClient(List.of(terminal(null), terminal(null)));
        var service = new ExactTradeFillCollectionService(reads, repo);
        var request = request("7".repeat(64));
        service.collect(request);
        var rerun = service.collect(request);

        assertThat(rerun.appendResult()).isEqualTo(ExactTradeFillAppendRepository.AppendResult.DUPLICATE_IDENTICAL);
        assertThat(reads.calls).isEqualTo(2);
        assertThat(repo.appendCalls).isEqualTo(2);
    }

    @Test
    void retentionLimitCrossOrderDuplicateAndUnboundRowsFailClosed() {
        FakeRepository repo = new FakeRepository();
        var retentionRequest = new ExactTradeFillCollectionService.Request("b".repeat(64), ACCOUNT,
                "BTC-USDT", "SPOT", 100, 10, NOW.atZone(java.time.ZoneOffset.UTC).minusMonths(3).toInstant(),
                bindingsAt(NOW.atZone(java.time.ZoneOffset.UTC).minusMonths(3).toInstant()));
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(terminal(null)), repo)
                .collect(retentionRequest)).hasMessage("EFFECTIVE_FROM_OUTSIDE_PROVEN_RETENTION");

        var limitOne = new ExactTradeFillCollectionService.Request("c".repeat(64), ACCOUNT,
                "BTC-USDT", "SPOT", 1, 10, effectiveFrom(), bindings());
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(page(null, "800", false,
                fill("o1", "t1", "900", "BUY", "100", "1", "0", "USDT", "p1"),
                fill("o2", "t2", "800", "SELL", "101", "1", "0", "USDT", "p1"))), repo)
                .collect(limitOne)).hasMessage("PAGE_LIMIT_EXCEEDED");

        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(page(null, "800", false,
                fill("o1", "same", "900", "BUY", "100", "1", "0", "USDT", "p1"),
                fill("o2", "same", "800", "SELL", "101", "1", "0", "USDT", "p1"))), repo)
                .collect(request("d".repeat(64)))).hasMessage("CROSS_ORDER_DUPLICATE_TRADE_ID");

        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(page(null, "900", false,
                fill("outside", "t9", "900", "BUY", "100", "1", "0", "USDT", "p1"))), repo)
                .collect(request("e".repeat(64)))).hasMessage("UNBOUND_FILL_SCOPE");
        assertThat(repo.appendCalls).isZero();
    }

    @Test
    void fillBeforeBoundOrderCreationFailsClosed() {
        Instant orderCreatedAt = NOW;
        var request = new ExactTradeFillCollectionService.Request("f".repeat(64), ACCOUNT,
                "BTC-USDT", "SPOT", 100, 10, effectiveFrom(), bindingsAt(orderCreatedAt));
        RawFill early = rehashAt(fill("o1", "t1", "900", "BUY", "100", "1", "0", "USDT", "p1"),
                orderCreatedAt.minusSeconds(1));
        FakeRepository repo = new FakeRepository();
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(
                page(null, "900", false, early)), repo).collect(request))
                .hasMessage("FILL_PRECEDES_ORDER_CREATION");
        assertThat(repo.appendCalls).isZero();
    }

    @Test
    void unorderedPaginationAndPreEffectiveEvidenceFailBeforeAppend() {
        FakeRepository repo = new FakeRepository();
        RawFill older = fill("o1", "t1", "800", "BUY", "100", "1", "0", "USDT", "p1");
        RawFill newer = fill("o2", "t2", "900", "SELL", "101", "1", "0", "USDT", "p2");
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(
                page(null, "900", false, older, newer), terminal("900")), repo)
                .collect(request("8".repeat(64))))
                .hasMessage("OKX_NEWEST_FIRST_ORDER_NOT_PROVEN");

        RawFill preEffective = rehashAt(older, effectiveFrom().minusSeconds(1));
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(
                page(null, "800", false, preEffective), terminal("800")), repo)
                .collect(request("9".repeat(64))))
                .hasMessage("FILL_PRECEDES_ORDER_CREATION");

        var oldOrder = new ExactTradeFillCollectionService.FillBinding("cohort", 1L, 2L,
                effectiveFrom().minusSeconds(1), false, null, null);
        var oldOrderRequest = new ExactTradeFillCollectionService.Request("a".repeat(64), ACCOUNT,
                "BTC-USDT", "SPOT", 100, 10, effectiveFrom(), Map.of("o1", oldOrder));
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(terminal(null)), repo)
                .collect(oldOrderRequest)).hasMessage("INVALID_REQUEST_OR_PRE_EFFECTIVE_ORDER");
        assertThat(repo.appendCalls).isZero();
    }

    private static ExactTradeFillCollectionService.Request request(String runId) {
        return new ExactTradeFillCollectionService.Request(runId, ACCOUNT, "BTC-USDT", "SPOT", 100, 10,
                effectiveFrom(), bindings());
    }
    private static Map<String, ExactTradeFillCollectionService.FillBinding> bindings() {
        return bindingsAt(effectiveFrom());
    }
    private static Map<String, ExactTradeFillCollectionService.FillBinding> bindingsAt(Instant orderCreatedAt) {
        var binding = new ExactTradeFillCollectionService.FillBinding("cohort", 1L, 2L,
                orderCreatedAt, false, null, null);
        return Map.of("o1", binding, "o2", binding);
    }
    private static Instant effectiveFrom() { return Instant.parse("2026-07-17T00:00:00Z"); }
    private static CountingClient client(RawPage... pages) { return new CountingClient(List.of(pages)); }
    private static RawPage terminal(String cursor) {
        return new RawPage(cursor, null, hash("page", cursor), hash("empty", cursor), NOW, true, true, List.of());
    }
    private static RawPage page(String cursor, String next, boolean terminal, RawFill... fills) {
        String key = hash("page", cursor, next);
        List<RawFill> rebound = Arrays.stream(fills).map(f -> withPage(f, key)).toList();
        return new RawPage(cursor, next, key, hash("body", key), NOW, terminal, true, rebound);
    }
    private static RawFill withPage(RawFill f, String page) {
        RawFill d = new RawFill(f.provider(), f.accountRefHash(), f.instrumentId(), f.instrumentType(), f.orderId(),
                f.tradeId(), f.billId(), f.fillAt(), f.side(), f.fillPrice(), f.fillQuantity(), f.signedFeeAmount(),
                f.feeCurrency(), f.liquidityRole(), f.rawPayloadSha256(), page, f.collectedAt(), f.cohortId(),
                f.runtimeDecisionId(), f.liveSignalId(), f.intendedChildOrderId(), f.actualChildOrderId(), null, null);
        return hashed(d);
    }
    static RawFill fill(String order, String trade, String bill, String side, String price, String qty,
                        String fee, String feeCcy, String page) {
        RawFill d = new RawFill("okx", ACCOUNT, "BTC-USDT", "SPOT", order, trade, bill, NOW, side,
                new BigDecimal(price), new BigDecimal(qty), new BigDecimal(fee), feeCcy, "T",
                hash("raw", order, trade, price, qty, fee, feeCcy), hash("page", page), NOW,
                "cohort", 1L, 2L, null, null, null, null);
        return hashed(d);
    }
    static RawFill hashed(RawFill d) {
        return new RawFill(d.provider(), d.accountRefHash(), d.instrumentId(), d.instrumentType(), d.orderId(),
                d.tradeId(), d.billId(), d.fillAt(), d.side(), d.fillPrice(), d.fillQuantity(), d.signedFeeAmount(),
                d.feeCurrency(), d.liquidityRole(), d.rawPayloadSha256(), d.sourcePageKey(), d.collectedAt(),
                d.cohortId(), d.runtimeDecisionId(), d.liveSignalId(), d.intendedChildOrderId(),
                d.actualChildOrderId(), ExactTradeFillHashing.identity(d), ExactTradeFillHashing.content(d));
    }
    private static RawFill rehashAt(RawFill fill, Instant at) {
        RawFill draft = new RawFill(fill.provider(), fill.accountRefHash(), fill.instrumentId(),
                fill.instrumentType(), fill.orderId(), fill.tradeId(), fill.billId(), at, fill.side(),
                fill.fillPrice(), fill.fillQuantity(), fill.signedFeeAmount(), fill.feeCurrency(),
                fill.liquidityRole(), fill.rawPayloadSha256(), fill.sourcePageKey(), fill.collectedAt(),
                fill.cohortId(), fill.runtimeDecisionId(), fill.liveSignalId(), fill.intendedChildOrderId(),
                fill.actualChildOrderId(), null, null);
        return hashed(draft);
    }
    private static String hash(String... v) { return ExactTradeFillHashing.hash(v); }

    private static final class CountingClient implements ExactTradeFillReadClient {
        final List<RawPage> pages; int calls;
        CountingClient(List<RawPage> pages) { this.pages = pages; }
        public RawPage getPage(String i, String t, int l, String c, String a) { return pages.get(calls++); }
    }
    private static final class FakeRepository implements ExactTradeFillAppendRepository {
        final Map<String, CollectionRun> runs = new HashMap<>();
        final Map<String, CollectionAppend> collections = new HashMap<>(); CollectionAppend appended; int appendCalls;
        public Optional<CollectionRun> findRun(String id) { return Optional.ofNullable(runs.get(id)); }
        public Optional<PriorRun> latestCompleteRun(String p, String a, String i, String t, String scope) {
            return runs.values().stream().filter(r -> r.bindingScopeSha256().equals(scope))
                    .max(Comparator.comparing(CollectionRun::completedAt))
                    .map(r -> new PriorRun(r.runId(), r.canonicalFillSetSha256(), r.bindingScopeSha256()));
        }
        public AppendResult append(CollectionAppend c) {
            appendCalls++;
            CollectionAppend existing = collections.putIfAbsent(c.run().runId(), c);
            if (existing != null) {
                if (!existing.pages().equals(c.pages()) || !existing.fills().equals(c.fills())) {
                    throw new IllegalStateException("immutable exact-fill identity conflict");
                }
                return AppendResult.DUPLICATE_IDENTICAL;
            }
            appended = c; runs.put(c.run().runId(), c.run()); return AppendResult.APPENDED;
        }
    }
}
