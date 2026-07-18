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
    void terminalChainDedupeAndReorderProduceStableCanonicalHash() {
        FakeRepository repo = new FakeRepository();
        RawFill a = fill("o1", "t1", "b1", "BUY", "100", "1", "-0.01", "USDT", "p1");
        RawFill b = fill("o2", "t2", "b2", "SELL", "110", "1", "-0.01", "USDT", "p2");
        var firstClient = client(page(null, "b1", false, a), page("b1", "b2", false, b, a), terminal("b2"));
        var first = new ExactTradeFillCollectionService(firstClient, repo).collect(request("1".repeat(64)));

        assertThat(first.run().status()).isEqualTo(RunStatus.COMPLETE_CANDIDATE);
        assertThat(first.run().fillCount()).isEqualTo(2);
        assertThat(repo.appended.fills()).extracting(RawFill::tradeId).containsExactly("t1", "t2");

        var secondClient = client(page(null, "b2", false, b), page("b2", "b1", false, a), terminal("b1"));
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

        RawFill a = fill("o1", "t1", "b1", "BUY", "100", "1", "0", "USDT", "p1");
        var bounded = new ExactTradeFillCollectionService.Request("4".repeat(64), ACCOUNT,
                "BTC-USDT", "SPOT", 100, 2, bindings());
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(
                page(null, "b1", false, a), page("b1", "b2", false, a)), repo)
                .collect(bounded)).hasMessage("TERMINAL_PAGE_NOT_PROVEN");
        assertThat(repo.appendCalls).isZero();
    }

    @Test
    void cursorCycleAndConflictingDuplicateFailClosed() {
        RawFill a = fill("o1", "t1", "b1", "BUY", "100", "1", "0", "USDT", "p1");
        FakeRepository repo = new FakeRepository();
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(
                page(null, "1", false, a), page("1", "2", false, a), page("2", "1", false, a)), repo)
                .collect(request("5".repeat(64)))).hasMessage("CURSOR_CYCLE");

        RawFill conflict = fill("o1", "t1", "b1", "BUY", "101", "1", "0", "USDT", "p2");
        assertThatThrownBy(() -> new ExactTradeFillCollectionService(client(
                page(null, "1", false, a), page("1", "2", false, conflict), terminal("2")), repo)
                .collect(request("6".repeat(64)))).hasMessage("PERMANENT_IDENTITY_CONFLICT");
        assertThat(repo.appendCalls).isZero();
    }

    @Test
    void sameRunIsIdempotentWithoutASecondProviderRead() {
        FakeRepository repo = new FakeRepository();
        CountingClient reads = new CountingClient(List.of(terminal(null)));
        var service = new ExactTradeFillCollectionService(reads, repo);
        var request = request("7".repeat(64));
        service.collect(request);
        var rerun = service.collect(request);

        assertThat(rerun.appendResult()).isEqualTo(ExactTradeFillAppendRepository.AppendResult.DUPLICATE_IDENTICAL);
        assertThat(reads.calls).isEqualTo(1);
        assertThat(repo.appendCalls).isEqualTo(1);
    }

    private static ExactTradeFillCollectionService.Request request(String runId) {
        return new ExactTradeFillCollectionService.Request(runId, ACCOUNT, "BTC-USDT", "SPOT", 100, 10, bindings());
    }
    private static Map<String, ExactTradeFillCollectionService.FillBinding> bindings() {
        var binding = new ExactTradeFillCollectionService.FillBinding("cohort", 1L, 2L, null, null);
        return Map.of("o1", binding, "o2", binding);
    }
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
    private static String hash(String... v) { return ExactTradeFillHashing.hash(v); }

    private static final class CountingClient implements ExactTradeFillReadClient {
        final List<RawPage> pages; int calls;
        CountingClient(List<RawPage> pages) { this.pages = pages; }
        public RawPage getPage(String i, String t, int l, String c, String a) { return pages.get(calls++); }
    }
    private static final class FakeRepository implements ExactTradeFillAppendRepository {
        final Map<String, CollectionRun> runs = new HashMap<>(); CollectionAppend appended; int appendCalls;
        public Optional<CollectionRun> findRun(String id) { return Optional.ofNullable(runs.get(id)); }
        public Optional<PriorRun> latestCompleteRun(String p, String a, String i, String t, String scope) {
            return runs.values().stream().max(Comparator.comparing(CollectionRun::completedAt))
                    .map(r -> new PriorRun(r.runId(), r.canonicalFillSetSha256()));
        }
        public AppendResult append(CollectionAppend c) {
            appendCalls++; appended = c; runs.put(c.run().runId(), c.run()); return AppendResult.APPENDED;
        }
    }
}
