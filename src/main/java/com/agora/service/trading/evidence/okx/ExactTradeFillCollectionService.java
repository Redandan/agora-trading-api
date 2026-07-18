package com.agora.service.trading.evidence.okx;

import com.agora.repository.trading.evidence.ExactTradeFillAppendRepository;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
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
        String bindingScopeHash = bindingScopeHash(request.bindings());
        Optional<CollectionRun> existing = repository.findRun(request.runId());
        if (existing.isPresent()) {
            CollectionRun run = existing.get();
            if (!run.accountRefHash().equals(request.accountRefHash())
                    || !run.instrumentId().equals(request.instrumentId())
                    || !run.instrumentType().equals(request.instrumentType())
                    || !run.bindingScopeSha256().equals(bindingScopeHash)) fail("RUN_ID_SCOPE_CONFLICT");
            return new Result(run, ExactTradeFillAppendRepository.AppendResult.DUPLICATE_IDENTICAL);
        }
        Instant started = clock.instant();
        List<RawPage> pages = new ArrayList<>();
        Map<String, RawFill> unique = new LinkedHashMap<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        boolean terminal = false;
        for (int pageIndex = 0; pageIndex < request.maxPages(); pageIndex++) {
            if (!seenCursors.add(cursor == null ? "<ROOT>" : cursor)) fail("CURSOR_CYCLE");
            RawPage page = readClient.getPage(request.instrumentId(), request.instrumentType(),
                    request.pageLimit(), cursor, request.accountRefHash());
            validatePage(page, cursor, request);
            pages.add(page);
            for (RawFill fill : page.fills()) {
                FillBinding binding = fill == null ? null : request.bindings().get(fill.orderId());
                if (binding != null) merge(unique, bind(fill, binding), request);
            }
            if (page.terminal()) { terminal = true; break; }
            cursor = page.nextCursor();
        }
        if (!terminal) fail("TERMINAL_PAGE_NOT_PROVEN");
        List<RawFill> fills = List.copyOf(unique.values());
        String fillSetHash = ExactTradeFillHashing.fillSet(fills);
        Optional<ExactTradeFillAppendRepository.PriorRun> prior = repository.latestCompleteRun(
                "okx", request.accountRefHash(), request.instrumentId(), request.instrumentType(), bindingScopeHash);
        boolean stable = prior.map(p -> p.canonicalFillSetSha256().equals(fillSetHash)).orElse(false);
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
                || r.bindings() == null || r.bindings().isEmpty()
                || r.bindings().entrySet().stream().anyMatch(e -> blank(e.getKey()) || e.getValue() == null
                || blank(e.getValue().cohortId()) || e.getValue().runtimeDecisionId() == null
                || e.getValue().liveSignalId() == null)) fail("INVALID_REQUEST");
    }

    private static void validatePage(RawPage p, String expectedCursor, Request r) {
        if (p == null || !p.complete() || !Objects.equals(expectedCursor, p.requestCursor())
                || !hash64(p.pageKey()) || !hash64(p.pageSha256()) || p.collectedAt() == null) fail("PARTIAL_PAGE");
        if (p.terminal()) {
            if (!p.fills().isEmpty() || p.nextCursor() != null) fail("INVALID_TERMINAL_PAGE");
        } else if (p.fills().isEmpty() || blank(p.nextCursor()) || Objects.equals(p.requestCursor(), p.nextCursor())) {
            fail("CURSOR_DID_NOT_ADVANCE");
        }
    }

    private static void merge(Map<String, RawFill> unique, RawFill f, Request r) {
        if (f == null || !"okx".equals(f.provider()) || !r.accountRefHash().equals(f.accountRefHash())
                || !r.instrumentId().equals(f.instrumentId()) || !r.instrumentType().equals(f.instrumentType())
                || blank(f.orderId()) || blank(f.tradeId()) || blank(f.billId()) || f.fillAt() == null
                || !("BUY".equals(f.side()) || "SELL".equals(f.side())) || f.fillPrice() == null
                || f.fillPrice().signum() <= 0 || f.fillQuantity() == null || f.fillQuantity().signum() <= 0
                || f.signedFeeAmount() == null || blank(f.feeCurrency()) || !hash64(f.rawPayloadSha256())
                || !hash64(f.identitySha256()) || !hash64(f.contentSha256())
                || !f.identitySha256().equals(ExactTradeFillHashing.identity(f))
                || !f.contentSha256().equals(ExactTradeFillHashing.content(f))) fail("INVALID_FILL");
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
    private static String bindingScopeHash(Map<String, FillBinding> bindings) {
        String[] canonical = bindings.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> ExactTradeFillHashing.hash(e.getKey(), e.getValue().cohortId(),
                        String.valueOf(e.getValue().runtimeDecisionId()), String.valueOf(e.getValue().liveSignalId()),
                        e.getValue().intendedChildOrderId(), e.getValue().actualChildOrderId()))
                .toArray(String[]::new);
        return ExactTradeFillHashing.hash(canonical);
    }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
    private static void fail(String reason) { throw new ExactCollectionException(reason); }

    public record Request(String runId, String accountRefHash, String instrumentId,
                          String instrumentType, int pageLimit, int maxPages,
                          Map<String, FillBinding> bindings) {
        public Request {
            bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
        }
        public Request(String runId, String accountRefHash, String instrumentId,
                       String instrumentType, int pageLimit, int maxPages) {
            this(runId, accountRefHash, instrumentId, instrumentType, pageLimit, maxPages, Map.of());
        }
    }
    public record FillBinding(String cohortId, Long runtimeDecisionId, Long liveSignalId,
                              String intendedChildOrderId, String actualChildOrderId) { }
    public record Result(CollectionRun run, ExactTradeFillAppendRepository.AppendResult appendResult) { }
    public static final class ExactCollectionException extends RuntimeException {
        public ExactCollectionException(String reason) { super(reason); }
    }
}
