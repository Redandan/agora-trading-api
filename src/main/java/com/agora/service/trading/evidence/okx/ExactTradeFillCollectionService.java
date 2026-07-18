package com.agora.service.trading.evidence.okx;

import com.agora.repository.trading.evidence.ExactTradeFillAppendRepository;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigInteger;
import java.util.*;

/** Collects a full cursor chain before any append. Partial evidence is never persisted. */
@Service
@RequiredArgsConstructor
public class ExactTradeFillCollectionService {
    private final ExactTradeFillReadClient readClient;
    private final ExactTradeFillAppendRepository repository;
    private final Clock clock = Clock.systemUTC();

    @Transactional
    public Result collect(Request request) {
        validateRequest(request);
        String bindingScopeHash = ExactTradeFillHashing.bindingScope(request.effectiveFrom(), request.bindings());
        Instant started = clock.instant();
        List<RawPage> pages = new ArrayList<>();
        Map<String, RawFill> unique = new LinkedHashMap<>();
        Set<String> seenCursors = new HashSet<>();
        Set<String> seenPageKeys = new HashSet<>();
        Set<String> seenBillIds = new HashSet<>();
        Map<String, String> seenTradeOrders = new HashMap<>();
        String cursor = null;
        boolean terminal = false;
        for (int pageIndex = 0; pageIndex < request.maxPages(); pageIndex++) {
            if (!seenCursors.add(cursor == null ? "<ROOT>" : cursor)) fail("CURSOR_CYCLE");
            RawPage page = readClient.getPage(request.instrumentId(), request.instrumentType(),
                    request.pageLimit(), cursor, request.accountRefHash());
            validatePage(page, cursor, request, seenPageKeys, seenBillIds, seenTradeOrders);
            pages.add(page);
            for (RawFill fill : page.fills()) {
                if (fill == null || !page.pageKey().equals(fill.sourcePageKey())) {
                    fail("FILL_PAGE_BINDING_MISMATCH");
                }
                FillBinding binding = fill == null ? null : request.bindings().get(fill.orderId());
                if (binding == null) fail("UNBOUND_FILL_SCOPE");
                if (fill.fillAt() == null || fill.fillAt().isBefore(binding.orderCreatedAt())) {
                    fail("FILL_PRECEDES_ORDER_CREATION");
                }
                merge(unique, bind(fill, binding), request);
            }
            if (page.terminal()) { terminal = true; break; }
            cursor = page.nextCursor();
        }
        if (!terminal) fail("TERMINAL_PAGE_NOT_PROVEN");
        Instant retentionFloor = pages.getLast().collectedAt().atZone(ZoneOffset.UTC).minusMonths(3).toInstant();
        if (!request.effectiveFrom().isAfter(retentionFloor)) fail("EFFECTIVE_FROM_OUTSIDE_PROVEN_RETENTION");
        List<RawFill> fills = List.copyOf(unique.values());
        String fillSetHash = ExactTradeFillHashing.fillSet(fills);
        Optional<ExactTradeFillAppendRepository.PriorRun> prior = repository.latestCompleteRun(
                "okx", request.accountRefHash(), request.instrumentId(), request.instrumentType(), bindingScopeHash);
        boolean stable = prior.map(p -> p.bindingScopeSha256().equals(bindingScopeHash)
                && p.canonicalFillSetSha256().equals(fillSetHash)).orElse(false);
        Instant completed = clock.instant();
        List<PageManifest> manifests = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            RawPage p = pages.get(i);
            manifests.add(new PageManifest(request.runId(), i, p.requestCursor(), p.nextCursor(), p.pageKey(),
                    p.pageSha256(), p.fills().size(), p.terminal(), p.collectedAt()));
        }
        CollectionRun run = new CollectionRun(request.runId(), "okx", request.accountRefHash(),
                request.instrumentId(), request.instrumentType(), bindingScopeHash,
                stable ? RunStatus.COMPLETE_STABLE : RunStatus.COMPLETE_CANDIDATE,
                started, completed, pages.size(), fills.size(), cursor, fillSetHash,
                stable ? prior.orElseThrow().runId() : null);
        var appendResult = repository.append(new CollectionAppend(run, manifests, fills));
        return new Result(run, appendResult);
    }

    private static void validateRequest(Request r) {
        if (r == null || !hash64(r.runId()) || !hash64(r.accountRefHash())
                || blank(r.instrumentId()) || !"SPOT".equals(r.instrumentType())
                || r.pageLimit() < 1 || r.pageLimit() > 100 || r.maxPages() < 2
                || r.effectiveFrom() == null
                || r.bindings() == null || r.bindings().isEmpty()
                || r.bindings().entrySet().stream().anyMatch(e -> blank(e.getKey()) || e.getValue() == null
                || blank(e.getValue().cohortId()) || e.getValue().runtimeDecisionId() == null
                || e.getValue().liveSignalId() == null || e.getValue().orderCreatedAt() == null
                || e.getValue().orderCreatedAt().isBefore(r.effectiveFrom())
                || e.getValue().ocoRequired() && (blank(e.getValue().intendedChildOrderId())
                || blank(e.getValue().actualChildOrderId())
                || !e.getKey().equals(e.getValue().actualChildOrderId())
                || !e.getValue().intendedChildOrderId().equals(e.getValue().actualChildOrderId())))) {
            fail("INVALID_REQUEST_OR_PRE_EFFECTIVE_ORDER");
        }
    }

    private static void validatePage(RawPage p, String expectedCursor, Request r,
                                     Set<String> seenPageKeys, Set<String> seenBillIds,
                                     Map<String, String> seenTradeOrders) {
        if (p == null || !p.complete() || !Objects.equals(expectedCursor, p.requestCursor())
                || !hash64(p.pageKey()) || !hash64(p.pageSha256()) || p.collectedAt() == null) fail("PARTIAL_PAGE");
        if (p.fills().size() > r.pageLimit()) fail("PAGE_LIMIT_EXCEEDED");
        if (!seenPageKeys.add(p.pageKey())) fail("REPEATED_PAGE");
        if (p.terminal()) {
            if (!p.fills().isEmpty() || p.nextCursor() != null) fail("INVALID_TERMINAL_PAGE");
        } else if (p.fills().isEmpty() || blank(p.nextCursor()) || Objects.equals(p.requestCursor(), p.nextCursor())) {
            fail("CURSOR_DID_NOT_ADVANCE");
        } else {
            BigInteger requestCursor = numericCursor(expectedCursor, "NON_NUMERIC_REQUEST_CURSOR");
            BigInteger priorBillId = null;
            for (RawFill fill : p.fills()) {
                if (fill == null || blank(fill.billId()) || !seenBillIds.add(fill.billId())) {
                    fail("DUPLICATE_OR_MISSING_BILL_ID");
                }
                if (blank(fill.tradeId()) || blank(fill.orderId())) fail("UNBOUND_FILL_SCOPE");
                String priorOrder = seenTradeOrders.putIfAbsent(fill.tradeId(), fill.orderId());
                if (priorOrder != null && !priorOrder.equals(fill.orderId())) {
                    fail("CROSS_ORDER_DUPLICATE_TRADE_ID");
                }
                BigInteger billId = numericCursor(fill.billId(), "NON_NUMERIC_BILL_ID");
                if (requestCursor != null && billId.compareTo(requestCursor) >= 0) {
                    fail("AFTER_CURSOR_RANGE_NOT_PROVEN");
                }
                if (priorBillId != null && billId.compareTo(priorBillId) >= 0) {
                    fail("OKX_NEWEST_FIRST_ORDER_NOT_PROVEN");
                }
                priorBillId = billId;
            }
            if (!p.nextCursor().equals(p.fills().getLast().billId())) {
                fail("CURSOR_NOT_OLDEST_PAGE_BILL_ID");
            }
        }
    }

    private static void merge(Map<String, RawFill> unique, RawFill f, Request r) {
        if (f == null || !"okx".equals(f.provider()) || !r.accountRefHash().equals(f.accountRefHash())
                || !r.instrumentId().equals(f.instrumentId()) || !r.instrumentType().equals(f.instrumentType())
                || blank(f.orderId()) || blank(f.tradeId()) || blank(f.billId()) || f.fillAt() == null
                || f.fillAt().isBefore(r.effectiveFrom())
                || !("BUY".equals(f.side()) || "SELL".equals(f.side())) || f.fillPrice() == null
                || f.fillPrice().signum() <= 0 || f.fillQuantity() == null || f.fillQuantity().signum() <= 0
                || f.signedFeeAmount() == null || blank(f.feeCurrency()) || !hash64(f.rawPayloadSha256())
                || !hash64(f.identitySha256()) || !hash64(f.contentSha256())
                || !f.identitySha256().equals(ExactTradeFillHashing.identity(f))
                || !f.contentSha256().equals(ExactTradeFillHashing.content(f))) fail("INVALID_OR_PRE_EFFECTIVE_FILL");
        RawFill old = unique.putIfAbsent(f.identitySha256(), f);
        if (old != null && !old.contentSha256().equals(f.contentSha256())) fail("PERMANENT_IDENTITY_CONFLICT");
    }

    private static RawFill bind(RawFill f, FillBinding b) {
        if (f == null || b == null) return f;
        RawFill draft = new RawFill(f.provider(), f.accountRefHash(), f.instrumentId(), f.instrumentType(),
                f.orderId(), f.tradeId(), f.billId(), f.fillAt(), f.side(), f.fillPrice(), f.fillQuantity(),
                f.signedFeeAmount(), f.feeCurrency(), f.liquidityRole(), f.rawPayloadSha256(), f.sourcePageKey(),
                f.collectedAt(), b.cohortId(), b.runtimeDecisionId(), b.liveSignalId(), b.intendedChildOrderId(),
                b.actualChildOrderId(), null, null);
        return new RawFill(draft.provider(), draft.accountRefHash(), draft.instrumentId(), draft.instrumentType(),
                draft.orderId(), draft.tradeId(), draft.billId(), draft.fillAt(), draft.side(), draft.fillPrice(),
                draft.fillQuantity(), draft.signedFeeAmount(), draft.feeCurrency(), draft.liquidityRole(),
                draft.rawPayloadSha256(), draft.sourcePageKey(), draft.collectedAt(), draft.cohortId(),
                draft.runtimeDecisionId(), draft.liveSignalId(), draft.intendedChildOrderId(),
                draft.actualChildOrderId(), ExactTradeFillHashing.identity(draft), ExactTradeFillHashing.content(draft));
    }

    private static boolean hash64(String v) { return v != null && v.matches("[0-9a-f]{64}"); }
    private static BigInteger numericCursor(String value, String reason) {
        if (value == null) return null;
        try { return new BigInteger(value); }
        catch (NumberFormatException e) { fail(reason); return null; }
    }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
    private static void fail(String reason) { throw new ExactCollectionException(reason); }

    public record Request(String runId, String accountRefHash, String instrumentId,
                          String instrumentType, int pageLimit, int maxPages,
                          Instant effectiveFrom, Map<String, FillBinding> bindings) {
        public Request {
            bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
        }
    }
    public record FillBinding(String cohortId, Long runtimeDecisionId, Long liveSignalId,
                              Instant orderCreatedAt, boolean ocoRequired,
                              String intendedChildOrderId, String actualChildOrderId) { }
    public record Result(CollectionRun run, ExactTradeFillAppendRepository.AppendResult appendResult) { }
    public static final class ExactCollectionException extends RuntimeException {
        public ExactCollectionException(String reason) { super(reason); }
    }
}
