package com.agora.service.trading.evidence.okx;

import com.agora.service.diagnostic.coverage.CoverageProfiler;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Immutable provider-neutral values accepted by the V2 append path. */
public final class OkxEvidenceModels {

    private OkxEvidenceModels() {
    }

    public enum Dataset {
        EXECUTABLE_QUOTE,
        FILL_FEE,
        FUNDING_BILL,
        MARGIN_SNAPSHOT
    }

    public enum RejectReason {
        INVALID_PROVIDER_RESPONSE,
        MISSING_EVENT_TIMESTAMP,
        TIMESTAMP_ORDER_VIOLATION,
        MISSING_REQUIRED_FIELD,
        INVALID_NUMBER,
        INVALID_BOOK,
        MISSING_SIGNED_FEE,
        MISSING_SIGNED_FUNDING,
        UNSUPPORTED_INSTRUMENT,
        UNSUPPORTED_MARGIN_MODE
    }

    /** Explicitly verified account semantics; never guessed from absent balance fields. */
    public enum AccountMode {
        SIMPLE,
        FUTURES,
        MULTI_CURRENCY,
        PORTFOLIO
    }

    public enum PositionMode {
        NET,
        LONG_SHORT
    }

    public record AccountSemantics(AccountMode accountMode, PositionMode positionMode) {
    }

    public record CaptureContext(Instant decisionAt,
                                 Instant ingestedAt,
                                 CoverageProfiler.Provenance provenance,
                                 String accountOpaqueRef) {
    }

    public record Timestamps(Instant exchangeEventAt,
                             Instant effectiveAt,
                             Instant availableAt,
                             Instant observedAt,
                             Instant decisionAt,
                             Instant ingestedAt) {
    }

    public record Provenance(String provider,
                             CoverageProfiler.Provenance sourceMode,
                             String rawPayloadSha256,
                             String providerCursor,
                             String providerPageKey,
                             String gapManifestId,
                             String gapDataset,
                             Instant gapRangeStart,
                             Instant gapRangeEnd,
                             String retentionClass,
                             Instant retainUntil) {
    }

    public sealed interface AppendCommand permits QuoteAppend, FillAppend, FundingAppend, MarginAppend {
        Dataset dataset();

        String dedupeKey();

        Timestamps timestamps();

        Provenance provenance();

        CoverageProfiler.CoverageRecord coverageRecord();
    }

    public record QuoteAppend(String dedupeKey,
                              Timestamps timestamps,
                              Provenance provenance,
                              String symbol,
                              String instrumentType,
                              String snapshotKind,
                              BigDecimal bestBidPrice,
                              BigDecimal bestBidSize,
                              BigDecimal bestAskPrice,
                              BigDecimal bestAskSize,
                              String depthJson,
                              String providerSequence,
                              CoverageProfiler.CoverageRecord coverageRecord) implements AppendCommand {
        @Override
        public Dataset dataset() {
            return Dataset.EXECUTABLE_QUOTE;
        }
    }

    public record FillAppend(String dedupeKey,
                             Timestamps timestamps,
                             Provenance provenance,
                             String accountRefHash,
                             String symbol,
                             String instrumentType,
                             String orderId,
                             String tradeId,
                             BigDecimal signedFeeAmount,
                             String feeCurrency,
                             CoverageProfiler.CoverageRecord coverageRecord) implements AppendCommand {
        @Override
        public Dataset dataset() {
            return Dataset.FILL_FEE;
        }
    }

    public record FundingAppend(String dedupeKey,
                                Timestamps timestamps,
                                Provenance provenance,
                                String accountRefHash,
                                String symbol,
                                String instrumentType,
                                String billId,
                                String positionRef,
                                BigDecimal signedFundingAmount,
                                String fundingCurrency,
                                CoverageProfiler.CoverageRecord coverageRecord) implements AppendCommand {
        @Override
        public Dataset dataset() {
            return Dataset.FUNDING_BILL;
        }
    }

    public record MarginAppend(String dedupeKey,
                               Timestamps timestamps,
                               Provenance provenance,
                               String accountRefHash,
                               String symbol,
                               String instrumentType,
                               String marginMode,
                               BigDecimal equity,
                               BigDecimal availableBalance,
                               BigDecimal usedMargin,
                               BigDecimal maintenanceMargin,
                               BigDecimal marginRatio,
                               String currency,
                               CoverageProfiler.CoverageRecord coverageRecord) implements AppendCommand {
        @Override
        public Dataset dataset() {
            return Dataset.MARGIN_SNAPSHOT;
        }
    }

    public record RejectedEvidence(Dataset dataset, int itemIndex, RejectReason reason) {
    }

    public record NormalizationBatch(List<AppendCommand> accepted,
                                     List<RejectedEvidence> rejected,
                                     String nextCursor,
                                     boolean pageComplete) {
        public NormalizationBatch {
            accepted = accepted == null ? List.of() : List.copyOf(accepted);
            rejected = rejected == null ? List.of() : List.copyOf(rejected);
        }

        public boolean validForAppend() {
            return pageComplete && rejected.isEmpty();
        }
    }
}
