package com.agora.service.trading.evidence.okx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** V3 immutable all-fill values. Costs are negative and rebates are positive. */
public final class ExactTradeFillModels {
    private ExactTradeFillModels() { }

    public enum RunStatus { COMPLETE_CANDIDATE, COMPLETE_STABLE }

    public record RawPage(String requestCursor, String nextCursor, String pageKey,
                          String pageSha256, Instant collectedAt, boolean terminal,
                          boolean complete, List<RawFill> fills) {
        public RawPage { fills = fills == null ? List.of() : List.copyOf(fills); }
    }

    public record RawFill(String provider, String accountRefHash, String instrumentId,
                          String instrumentType, String orderId, String tradeId, String billId,
                          Instant fillAt, String side, BigDecimal fillPrice, BigDecimal fillQuantity,
                          BigDecimal signedFeeAmount, String feeCurrency, String liquidityRole,
                          String rawPayloadSha256, String sourcePageKey, Instant collectedAt,
                          String cohortId, Long runtimeDecisionId, Long liveSignalId,
                          String intendedChildOrderId, String actualChildOrderId,
                          String identitySha256, String contentSha256) { }

    public record PageManifest(String runId, int pageIndex, String requestCursor, String nextCursor,
                               String pageKey, String pageSha256, int fillCount,
                               boolean terminalPage, Instant collectedAt) { }

    public record CollectionRun(String runId, String provider, String accountRefHash,
                                String instrumentId, String instrumentType, String bindingScopeSha256, RunStatus status,
                                Instant startedAt, Instant completedAt, int pageCount, int fillCount,
                                String terminalCursor, String canonicalFillSetSha256,
                                String priorStableRunId) { }

    public record CollectionAppend(CollectionRun run, List<PageManifest> pages, List<RawFill> fills) {
        public CollectionAppend {
            pages = pages == null ? List.of() : List.copyOf(pages);
            fills = fills == null ? List.of() : List.copyOf(fills);
        }
    }
}
