package com.agora.repository.trading.evidence;

import com.agora.service.trading.evidence.okx.OkxEvidenceModels.AppendCommand;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.FillAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.FundingAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.MarginAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.Provenance;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.QuoteAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.Timestamps;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** Plain INSERT implementation. It contains no UPDATE, DELETE, REPLACE or upsert statement. */
@Repository
@RequiredArgsConstructor
public class JdbcOkxEvidenceAppendRepository implements OkxEvidenceAppendRepository {

    private static final String QUOTE_INSERT = """
            INSERT INTO executable_quote_snapshot
            (dedupe_key, provider, symbol, instrument_type, snapshot_kind,
             event_at, provider_at, received_at, ingested_at,
             best_bid_price, best_bid_size, best_ask_price, best_ask_size,
             depth_json, provider_sequence, source_mode, raw_payload_sha256,
             provider_cursor, provider_page_key, gap_manifest_id, gap_dataset,
             gap_range_start, gap_range_end, retention_class, retain_until)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FILL_INSERT = """
            INSERT INTO fill_fee_ledger
            (dedupe_key, provider, account_ref_hash, symbol, instrument_type, order_id, trade_id,
             event_at, provider_at, received_at, ingested_at, signed_fee_amount, fee_currency,
             fee_sign_semantic, source_mode, raw_payload_sha256, provider_cursor, provider_page_key,
             gap_manifest_id, gap_dataset, gap_range_start, gap_range_end, retention_class, retain_until)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'COST_NEGATIVE_REBATE_POSITIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FUNDING_INSERT = """
            INSERT INTO funding_bill_ledger
            (dedupe_key, provider, account_ref_hash, symbol, instrument_type, bill_id, position_ref,
             event_at, provider_at, received_at, ingested_at, signed_funding_amount, funding_currency,
             funding_sign_semantic, source_mode, raw_payload_sha256, provider_cursor, provider_page_key,
             gap_manifest_id, gap_dataset, gap_range_start, gap_range_end, retention_class, retain_until)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'PAID_NEGATIVE_RECEIVED_POSITIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String MARGIN_INSERT = """
            INSERT INTO margin_snapshot
            (dedupe_key, provider, account_ref_hash, symbol, instrument_type, margin_mode,
             event_at, provider_at, received_at, ingested_at, equity, available_balance, used_margin,
             maintenance_margin, margin_ratio, currency, source_mode, raw_payload_sha256,
             provider_cursor, provider_page_key, gap_manifest_id, gap_dataset, gap_range_start,
             gap_range_end, retention_class, retain_until)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    @Override
    public AppendResult append(AppendCommand command) {
        try {
            int rows = switch (command) {
                case QuoteAppend quote -> appendQuote(quote);
                case FillAppend fill -> appendFill(fill);
                case FundingAppend funding -> appendFunding(funding);
                case MarginAppend margin -> appendMargin(margin);
            };
            if (rows != 1) {
                throw new EvidenceAppendConflictException("append affected " + rows + " rows");
            }
            return AppendResult.APPENDED;
        } catch (DuplicateKeyException duplicate) {
            return verifyIdentical(command, duplicate);
        }
    }

    private int appendQuote(QuoteAppend value) {
        Timestamps t = value.timestamps();
        Provenance p = value.provenance();
        return jdbc.update(QUOTE_INSERT, value.dedupeKey(), p.provider(), value.symbol(),
                value.instrumentType(), value.snapshotKind(), ts(t.effectiveAt()), ts(t.availableAt()),
                ts(t.observedAt()), ts(t.ingestedAt()), value.bestBidPrice(), value.bestBidSize(),
                value.bestAskPrice(), value.bestAskSize(), value.depthJson(), value.providerSequence(),
                p.sourceMode().name(), p.rawPayloadSha256(), p.providerCursor(), p.providerPageKey(),
                p.gapManifestId(), p.gapDataset(), ts(p.gapRangeStart()), ts(p.gapRangeEnd()),
                p.retentionClass(), ts(p.retainUntil()));
    }

    private int appendFill(FillAppend value) {
        Timestamps t = value.timestamps();
        Provenance p = value.provenance();
        return jdbc.update(FILL_INSERT, value.dedupeKey(), p.provider(), value.accountRefHash(), value.symbol(),
                value.instrumentType(), value.orderId(), value.tradeId(), ts(t.effectiveAt()), ts(t.availableAt()),
                ts(t.observedAt()), ts(t.ingestedAt()), value.signedFeeAmount(), value.feeCurrency(),
                p.sourceMode().name(), p.rawPayloadSha256(), p.providerCursor(), p.providerPageKey(),
                p.gapManifestId(), p.gapDataset(), ts(p.gapRangeStart()), ts(p.gapRangeEnd()),
                p.retentionClass(), ts(p.retainUntil()));
    }

    private int appendFunding(FundingAppend value) {
        Timestamps t = value.timestamps();
        Provenance p = value.provenance();
        return jdbc.update(FUNDING_INSERT, value.dedupeKey(), p.provider(), value.accountRefHash(), value.symbol(),
                value.instrumentType(), value.billId(), value.positionRef(), ts(t.effectiveAt()), ts(t.availableAt()),
                ts(t.observedAt()), ts(t.ingestedAt()), value.signedFundingAmount(), value.fundingCurrency(),
                p.sourceMode().name(), p.rawPayloadSha256(), p.providerCursor(), p.providerPageKey(),
                p.gapManifestId(), p.gapDataset(), ts(p.gapRangeStart()), ts(p.gapRangeEnd()),
                p.retentionClass(), ts(p.retainUntil()));
    }

    private int appendMargin(MarginAppend value) {
        Timestamps t = value.timestamps();
        Provenance p = value.provenance();
        return jdbc.update(MARGIN_INSERT, value.dedupeKey(), p.provider(), value.accountRefHash(), value.symbol(),
                value.instrumentType(), value.marginMode(), ts(t.effectiveAt()), ts(t.availableAt()),
                ts(t.observedAt()), ts(t.ingestedAt()), value.equity(), value.availableBalance(), value.usedMargin(),
                value.maintenanceMargin(), value.marginRatio(), value.currency(), p.sourceMode().name(),
                p.rawPayloadSha256(), p.providerCursor(), p.providerPageKey(), p.gapManifestId(), p.gapDataset(),
                ts(p.gapRangeStart()), ts(p.gapRangeEnd()), p.retentionClass(), ts(p.retainUntil()));
    }

    private AppendResult verifyIdentical(AppendCommand command, DuplicateKeyException duplicate) {
        String table = switch (command.dataset()) {
            case EXECUTABLE_QUOTE -> "executable_quote_snapshot";
            case FILL_FEE -> "fill_fee_ledger";
            case FUNDING_BILL -> "funding_bill_ledger";
            case MARGIN_SNAPSHOT -> "margin_snapshot";
        };
        var hashes = jdbc.query("SELECT raw_payload_sha256 FROM " + table + " WHERE dedupe_key = ?",
                (rs, rowNum) -> rs.getString(1), command.dedupeKey());
        if (hashes.size() == 1 && command.provenance().rawPayloadSha256().equals(hashes.get(0))) {
            return AppendResult.DUPLICATE_IDENTICAL;
        }
        throw new EvidenceAppendConflictException("duplicate key conflicts with immutable evidence", duplicate);
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public static final class EvidenceAppendConflictException extends RuntimeException {
        public EvidenceAppendConflictException(String message) {
            super(message);
        }

        public EvidenceAppendConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
