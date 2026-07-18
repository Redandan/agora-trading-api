package com.agora.repository.trading.evidence;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.CollectionAppend;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.CollectionRun;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.PageManifest;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RunStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcExactTradeFillAppendRepository implements ExactTradeFillAppendRepository {
    private final JdbcTemplate jdbc;

    @Override
    public Optional<CollectionRun> findRun(String runId) {
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
        return jdbc.query("""
                SELECT run_id, canonical_fill_set_sha256 FROM exact_trade_fill_collection_run
                WHERE provider=? AND account_ref_hash=? AND instrument_id=? AND instrument_type=? AND binding_scope_sha256=?
                ORDER BY completed_at DESC, id DESC LIMIT 1
                """, (rs, n) -> new PriorRun(rs.getString(1), rs.getString(2)),
                provider, accountRefHash, instrumentId, instrumentType, bindingScopeSha256).stream().findFirst();
    }

    @Override
    public AppendResult append(CollectionAppend c) {
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
            var hashes = jdbc.query("SELECT canonical_fill_set_sha256 FROM exact_trade_fill_collection_run WHERE run_id=?",
                    (rs, n) -> rs.getString(1), c.run().runId());
            if (hashes.size() == 1 && c.run().canonicalFillSetSha256().equals(hashes.getFirst())) {
                return AppendResult.DUPLICATE_IDENTICAL;
            }
            throw new ExactFillConflictException("immutable exact-fill identity conflict", duplicate);
        }
    }

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
