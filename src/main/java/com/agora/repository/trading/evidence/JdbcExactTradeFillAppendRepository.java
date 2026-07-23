package com.agora.repository.trading.evidence;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.CollectionAppend;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.CollectionRun;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.PageManifest;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RunStatus;
import com.agora.service.trading.evidence.okx.ExactTradeFillHashing;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcExactTradeFillAppendRepository implements ExactTradeFillAppendRepository {
    private final JdbcTemplate jdbc;

    @Override
    public Optional<CollectionRun> findRun(String runId) {
        Optional<CollectionRun> run = queryRun(runId);
        run.ifPresent(this::verifyCommittedRun);
        return run;
    }

    @Override
    public List<RawFill> findRunFills(String runId) {
        CollectionRun run = queryRun(runId)
                .orElseThrow(() -> new ExactFillConflictException("collection run not found"));
        verifyCommittedRun(run);
        return queryRunFills(runId);
    }

    private Optional<CollectionRun> queryRun(String runId) {
        return jdbc.query("""
                SELECT run_id,provider,account_ref_hash,instrument_id,instrument_type,binding_scope_sha256,status,started_at,completed_at,
                       page_count,fill_count,terminal_cursor,canonical_fill_set_sha256,prior_stable_run_id
                FROM exact_trade_fill_collection_run WHERE run_id=?
                """, (rs, n) -> new CollectionRun(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), RunStatus.valueOf(rs.getString(7)),
                rs.getTimestamp(8).toInstant(), rs.getTimestamp(9).toInstant(), rs.getInt(10), rs.getInt(11),
                rs.getString(12), rs.getString(13), rs.getString(14)), runId).stream().findFirst();
    }

    @Override
    public Optional<PriorRun> latestCompleteRun(String provider, String accountRefHash,
                                                String instrumentId, String instrumentType, String bindingScopeSha256) {
        Optional<String> runId = jdbc.query("""
                SELECT run_id FROM exact_trade_fill_collection_run
                WHERE provider=? AND account_ref_hash=? AND instrument_id=? AND instrument_type=? AND binding_scope_sha256=?
                ORDER BY completed_at DESC, id DESC LIMIT 1
                """, (rs, n) -> rs.getString(1),
                provider, accountRefHash, instrumentId, instrumentType, bindingScopeSha256).stream().findFirst();
        return runId.flatMap(this::findRun)
                .map(run -> new PriorRun(run.runId(), run.canonicalFillSetSha256(), run.bindingScopeSha256()));
    }

    @Override
    @Transactional
    public AppendResult append(CollectionAppend c) {
        validateAppend(c);
        try {
            int rows = jdbc.update("""
                    INSERT INTO exact_trade_fill_collection_run
                    (run_id,provider,account_ref_hash,instrument_id,instrument_type,binding_scope_sha256,status,started_at,completed_at,
                     page_count,fill_count,terminal_cursor,canonical_fill_set_sha256,prior_stable_run_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, c.run().runId(), c.run().provider(), c.run().accountRefHash(), c.run().instrumentId(),
                    c.run().instrumentType(), c.run().bindingScopeSha256(), c.run().status().name(), ts(c.run().startedAt()),
                    ts(c.run().completedAt()), c.run().pageCount(), c.run().fillCount(), c.run().terminalCursor(),
                    c.run().canonicalFillSetSha256(), c.run().priorStableRunId());
            if (rows != 1) throw new ExactFillConflictException("collection run append affected " + rows + " rows");
            for (PageManifest p : c.pages()) appendPage(p);
            for (RawFill f : c.fills()) {
                appendFill(c.run().runId(), f);
                jdbc.update("INSERT INTO exact_trade_fill_run_item (run_id,fill_identity_sha256,page_key) VALUES (?,?,?)",
                        c.run().runId(), f.identitySha256(), f.sourcePageKey());
            }
            return AppendResult.APPENDED;
        } catch (DuplicateKeyException duplicate) {
            Optional<CollectionRun> committed = findRun(c.run().runId());
            if (committed.isPresent() && sameScopeAndContent(c.run(), committed.get())
                    && sameCommittedChildren(c)) {
                return AppendResult.DUPLICATE_IDENTICAL;
            }
            throw new ExactFillConflictException("immutable exact-fill identity conflict", duplicate);
        }
    }

    private void validateAppend(CollectionAppend collection) {
        if (collection == null || collection.run() == null
                || collection.pages().size() != collection.run().pageCount()
                || collection.fills().size() != collection.run().fillCount()
                || collection.pages().isEmpty()) {
            conflict("collection append incomplete");
        }
        for (int i = 0; i < collection.pages().size(); i++) {
            PageManifest page = collection.pages().get(i);
            if (!collection.run().runId().equals(page.runId()) || page.pageIndex() != i
                    || page.terminalPage() != (i == collection.pages().size() - 1)
                    || page.terminalPage() && page.fillCount() != 0
                    || page.fillCount() < 0) {
                conflict("collection append page chain mismatch");
            }
        }
        var pageKeys = collection.pages().stream().map(PageManifest::pageKey).collect(java.util.stream.Collectors.toSet());
        if (pageKeys.size() != collection.pages().size()) conflict("collection append duplicate page");
        Map<String, Long> fillCountByPage = collection.fills().stream()
                .collect(java.util.stream.Collectors.groupingBy(RawFill::sourcePageKey,
                        java.util.stream.Collectors.counting()));
        for (PageManifest page : collection.pages()) {
            if (fillCountByPage.getOrDefault(page.pageKey(), 0L) != page.fillCount()) {
                conflict("collection append page fill-count mismatch");
            }
        }
        for (RawFill fill : collection.fills()) {
            if (!Objects.equals(collection.run().provider(), fill.provider())
                    || !Objects.equals(collection.run().accountRefHash(), fill.accountRefHash())
                    || !Objects.equals(collection.run().instrumentId(), fill.instrumentId())
                    || !Objects.equals(collection.run().instrumentType(), fill.instrumentType())
                    || !pageKeys.contains(fill.sourcePageKey())
                    || !Objects.equals(fill.identitySha256(), ExactTradeFillHashing.identity(fill))
                    || !Objects.equals(fill.contentSha256(), ExactTradeFillHashing.content(fill))) {
                conflict("collection append immutable fill mismatch");
            }
        }
        if (!Objects.equals(collection.run().canonicalFillSetSha256(),
                ExactTradeFillHashing.fillSet(collection.fills()))) {
            conflict("collection append canonical fill-set mismatch");
        }
    }

    private boolean sameScopeAndContent(CollectionRun requested, CollectionRun committed) {
        return Objects.equals(requested.provider(), committed.provider())
                && Objects.equals(requested.accountRefHash(), committed.accountRefHash())
                && Objects.equals(requested.instrumentId(), committed.instrumentId())
                && Objects.equals(requested.instrumentType(), committed.instrumentType())
                && Objects.equals(requested.bindingScopeSha256(), committed.bindingScopeSha256())
                && requested.status() == committed.status()
                && requested.pageCount() == committed.pageCount()
                && requested.fillCount() == committed.fillCount()
                && Objects.equals(requested.terminalCursor(), committed.terminalCursor())
                && Objects.equals(requested.canonicalFillSetSha256(), committed.canonicalFillSetSha256())
                && Objects.equals(requested.priorStableRunId(), committed.priorStableRunId());
    }

    /** Compares the complete semantic child graph; collection timestamps are observational, not provenance. */
    private boolean sameCommittedChildren(CollectionAppend requested) {
        List<PageRow> committedPages = jdbc.query("""
                SELECT page_index,request_cursor,next_cursor,page_key,page_sha256,fill_count,terminal_page
                FROM exact_trade_fill_page_manifest WHERE run_id=? ORDER BY page_index
                """, (rs, n) -> new PageRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getBoolean(7)), requested.run().runId());
        List<PageRow> requestedPages = requested.pages().stream()
                .map(p -> new PageRow(p.pageIndex(), p.requestCursor(), p.nextCursor(), p.pageKey(),
                        p.pageSha256(), p.fillCount(), p.terminalPage()))
                .toList();
        if (!requestedPages.equals(committedPages)) return false;

        List<RunItemRow> committedItems = jdbc.query("""
                SELECT i.fill_identity_sha256,i.page_key,f.immutable_content_sha256
                FROM exact_trade_fill_run_item i
                JOIN immutable_trade_fill f ON f.fill_identity_sha256=i.fill_identity_sha256
                WHERE i.run_id=? ORDER BY i.fill_identity_sha256
                """, (rs, n) -> new RunItemRow(rs.getString(1), rs.getString(2), rs.getString(3)),
                requested.run().runId());
        List<RunItemRow> requestedItems = requested.fills().stream()
                .map(f -> new RunItemRow(f.identitySha256(), f.sourcePageKey(), f.contentSha256()))
                .sorted(java.util.Comparator.comparing(RunItemRow::identitySha256))
                .toList();
        return requestedItems.equals(committedItems);
    }

    /** Rebuilds the committed canonical fill-set through run-item; parent hashes are never trusted alone. */
    private void verifyCommittedRun(CollectionRun run) {
        List<PageRow> pages = jdbc.query("""
                SELECT page_index,page_key,terminal_page FROM exact_trade_fill_page_manifest
                WHERE run_id=? ORDER BY page_index
                """, (rs, n) -> new PageRow(rs.getInt(1), null, null, rs.getString(2), null, 0,
                rs.getBoolean(3)), run.runId());
        if (pages.size() != run.pageCount() || pages.isEmpty()) conflict("committed page collection incomplete");
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).index() != i || pages.get(i).terminal() != (i == pages.size() - 1)) {
                conflict("committed page chain incomplete");
            }
        }
        List<RawFill> fills = queryRunFills(run.runId());
        if (fills.size() != run.fillCount()) conflict("committed run-item collection incomplete");
        for (RawFill fill : fills) {
            if (!Objects.equals(run.provider(), fill.provider())
                    || !Objects.equals(run.accountRefHash(), fill.accountRefHash())
                    || !Objects.equals(run.instrumentId(), fill.instrumentId())
                    || !Objects.equals(run.instrumentType(), fill.instrumentType())
                    || !Objects.equals(fill.identitySha256(), ExactTradeFillHashing.identity(fill))
                    || !Objects.equals(fill.contentSha256(), ExactTradeFillHashing.content(fill))) {
                conflict("committed immutable fill mismatch");
            }
        }
        String rebuilt = ExactTradeFillHashing.fillSet(fills);
        if (!Objects.equals(run.canonicalFillSetSha256(), rebuilt)) {
            conflict("committed canonical fill-set mismatch");
        }
    }

    private List<RawFill> queryRunFills(String runId) {
        return jdbc.query("""
                SELECT f.provider,f.account_ref_hash,f.instrument_id,f.instrument_type,f.order_id,f.trade_id,
                       f.bill_id,f.fill_at,f.side,f.fill_price,f.fill_quantity,f.signed_fee_amount,f.fee_currency,
                       f.liquidity_role,f.raw_payload_sha256,i.page_key,f.collected_at,f.cohort_id,
                       f.runtime_decision_id,f.live_signal_id,f.intended_child_order_id,f.actual_child_order_id,
                       f.fill_identity_sha256,f.immutable_content_sha256
                FROM exact_trade_fill_run_item i
                JOIN immutable_trade_fill f ON f.fill_identity_sha256=i.fill_identity_sha256
                JOIN exact_trade_fill_page_manifest p ON p.run_id=i.run_id AND p.page_key=i.page_key
                WHERE i.run_id=? ORDER BY i.fill_identity_sha256
                """, (rs, n) -> new RawFill(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getString(9),
                rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getString(13), rs.getString(14),
                rs.getString(15), rs.getString(16), rs.getTimestamp(17).toInstant(), rs.getString(18),
                nullableLong(rs, 19), nullableLong(rs, 20), rs.getString(21), rs.getString(22), rs.getString(23),
                rs.getString(24)), runId);
    }

    private static Long nullableLong(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void conflict(String message) { throw new ExactFillConflictException(message); }
    private record PageRow(int index, String requestCursor, String nextCursor, String pageKey,
                           String pageSha256, int fillCount, boolean terminal) { }
    private record RunItemRow(String identitySha256, String pageKey, String contentSha256) { }

    private void appendPage(PageManifest p) {
        jdbc.update("""
                INSERT INTO exact_trade_fill_page_manifest
                (run_id,page_index,request_cursor,next_cursor,page_key,page_sha256,fill_count,terminal_page,collected_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, p.runId(), p.pageIndex(), p.requestCursor(), p.nextCursor(), p.pageKey(), p.pageSha256(),
                p.fillCount(), p.terminalPage(), ts(p.collectedAt()));
    }

    private void appendFill(String runId, RawFill f) {
        try {
            jdbc.update("""
                    INSERT INTO immutable_trade_fill
                    (fill_identity_sha256,immutable_content_sha256,provider,account_ref_hash,instrument_id,
                     instrument_type,order_id,trade_id,bill_id,fill_at,side,fill_price,fill_quantity,
                     signed_fee_amount,fee_currency,liquidity_role,source_run_id,source_page_key,collected_at,
                     raw_payload_sha256,cohort_id,runtime_decision_id,live_signal_id,intended_child_order_id,
                     actual_child_order_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, f.identitySha256(), f.contentSha256(), f.provider(), f.accountRefHash(), f.instrumentId(),
                    f.instrumentType(), f.orderId(), f.tradeId(), f.billId(), ts(f.fillAt()), f.side(), f.fillPrice(),
                    f.fillQuantity(), f.signedFeeAmount(), f.feeCurrency(), f.liquidityRole(), runId,
                    f.sourcePageKey(), ts(f.collectedAt()), f.rawPayloadSha256(), f.cohortId(), f.runtimeDecisionId(),
                    f.liveSignalId(), f.intendedChildOrderId(), f.actualChildOrderId());
        } catch (DuplicateKeyException duplicate) {
            var hashes = jdbc.query("""
                    SELECT immutable_content_sha256 FROM immutable_trade_fill
                    WHERE provider=? AND account_ref_hash=? AND order_id=? AND trade_id=?
                    """, (rs, n) -> rs.getString(1), f.provider(), f.accountRefHash(), f.orderId(), f.tradeId());
            if (hashes.size() != 1 || !f.contentSha256().equals(hashes.getFirst())) {
                throw new ExactFillConflictException("same fill identity has different immutable content", duplicate);
            }
        }
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }

    public static final class ExactFillConflictException extends RuntimeException {
        public ExactFillConflictException(String message) { super(message); }
        public ExactFillConflictException(String message, Throwable cause) { super(message, cause); }
    }
}
