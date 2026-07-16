package com.agora.service.trading.evidence.okx;

import com.agora.service.diagnostic.coverage.CoverageProfiler;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.AppendCommand;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.AccountMode;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.AccountSemantics;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.CaptureContext;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.Dataset;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.FillAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.FundingAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.MarginAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.NormalizationBatch;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.Provenance;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.QuoteAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.RejectReason;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.RejectedEvidence;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.Timestamps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixture-testable OKX response mapper. It has no HTTP, credential or logging dependency. */
@Component
@ConditionalOnBean(OkxEvidenceReadClient.class)
@RequiredArgsConstructor
public class OkxReadOnlyEvidenceAdapter {

    private static final String PROVIDER = "okx";
    private static final String RETENTION = "TRADING_EVIDENCE_LONG";
    private static final Set<String> QUOTE_INSTRUMENTS = Set.of("SPOT", "SWAP", "FUTURES");

    private final OkxEvidenceReadClient client;
    private final ObjectMapper objectMapper;

    public NormalizationBatch executableDepth(String instrumentId,
                                              String instrumentType,
                                              int depth,
                                              String cursor,
                                              CaptureContext capture) {
        String instType = upper(instrumentType);
        if (!QUOTE_INSTRUMENTS.contains(instType)) {
            return rejectedPage(Dataset.EXECUTABLE_QUOTE, RejectReason.UNSUPPORTED_INSTRUMENT);
        }
        OkxEvidenceReadClient.ReadPage page = client.get(new OkxEvidenceReadClient.ReadRequest(
                OkxEvidenceReadClient.Endpoint.EXECUTABLE_BOOKS,
                Map.of("instId", instrumentId, "sz", Integer.toString(depth)), cursor));
        return normalize(page, Dataset.EXECUTABLE_QUOTE, capture,
                (item, index) -> quote(item, instrumentId, instType, page, capture));
    }

    public NormalizationBatch fills(String instrumentType, String cursor, CaptureContext capture) {
        String instType = upper(instrumentType);
        if (!QUOTE_INSTRUMENTS.contains(instType)) {
            return rejectedPage(Dataset.FILL_FEE, RejectReason.UNSUPPORTED_INSTRUMENT);
        }
        OkxEvidenceReadClient.ReadPage page = client.get(new OkxEvidenceReadClient.ReadRequest(
                OkxEvidenceReadClient.Endpoint.FILL_HISTORY,
                Map.of("instType", instType, "limit", "100"), cursor));
        return normalize(page, Dataset.FILL_FEE, capture,
                (item, index) -> fill(item, instType, page, capture));
    }

    public NormalizationBatch fundingBills(String cursor, CaptureContext capture) {
        OkxEvidenceReadClient.ReadPage page = client.get(new OkxEvidenceReadClient.ReadRequest(
                OkxEvidenceReadClient.Endpoint.FUNDING_BILLS,
                Map.of("type", "8", "limit", "100"), cursor));
        return normalize(page, Dataset.FUNDING_BILL, capture,
                (item, index) -> funding(item, page, capture));
    }

    public NormalizationBatch marginSnapshots(AccountSemantics semantics, String cursor, CaptureContext capture) {
        if (semantics == null || semantics.accountMode() == null || semantics.positionMode() == null
                || semantics.accountMode() == AccountMode.PORTFOLIO
                && semantics.positionMode() != OkxEvidenceModels.PositionMode.NET) {
            return rejectedPage(Dataset.MARGIN_SNAPSHOT, RejectReason.UNSUPPORTED_MARGIN_MODE);
        }
        OkxEvidenceReadClient.ReadPage page = client.get(new OkxEvidenceReadClient.ReadRequest(
                OkxEvidenceReadClient.Endpoint.ACCOUNT_BALANCE, Map.of(), cursor));
        return normalize(page, Dataset.MARGIN_SNAPSHOT, capture,
                (item, index) -> margin(item, semantics, page, capture));
    }

    private NormalizationBatch normalize(OkxEvidenceReadClient.ReadPage page,
                                         Dataset dataset,
                                         CaptureContext capture,
                                         ItemMapper mapper) {
        if (page == null || page.body() == null || !"0".equals(page.body().path("code").asText())
                || !page.body().path("data").isArray()) {
            return rejectedPage(dataset, RejectReason.INVALID_PROVIDER_RESPONSE);
        }
        List<AppendCommand> accepted = new ArrayList<>();
        List<RejectedEvidence> rejected = new ArrayList<>();
        int index = 0;
        for (JsonNode item : page.body().path("data")) {
            try {
                accepted.add(mapper.map(item, index));
            } catch (RejectedItem e) {
                rejected.add(new RejectedEvidence(dataset, index, e.reason));
            } catch (RuntimeException e) {
                rejected.add(new RejectedEvidence(dataset, index, RejectReason.INVALID_PROVIDER_RESPONSE));
            }
            index++;
        }
        return new NormalizationBatch(accepted, rejected, page.nextCursor(), page.pageComplete());
    }

    private QuoteAppend quote(JsonNode item,
                              String requestedInstrumentId,
                              String instrumentType,
                              OkxEvidenceReadClient.ReadPage page,
                              CaptureContext capture) {
        String instId = required(item, "instId");
        if (!instId.equals(requestedInstrumentId)) {
            throw reject(RejectReason.MISSING_REQUIRED_FIELD);
        }
        Timestamps times = timestamps(item, "ts", page, capture);
        JsonNode bids = item.path("bids");
        JsonNode asks = item.path("asks");
        if (!bids.isArray() || bids.isEmpty() || !asks.isArray() || asks.isEmpty()) {
            throw reject(RejectReason.INVALID_BOOK);
        }
        BigDecimal bidPx = arrayDecimal(bids.get(0), 0);
        BigDecimal bidSz = arrayDecimal(bids.get(0), 1);
        BigDecimal askPx = arrayDecimal(asks.get(0), 0);
        BigDecimal askSz = arrayDecimal(asks.get(0), 1);
        if (bidPx.signum() <= 0 || askPx.signum() <= 0 || bidPx.compareTo(askPx) > 0
                || bidSz.signum() < 0 || askSz.signum() < 0) {
            throw reject(RejectReason.INVALID_BOOK);
        }
        ObjectNode safe = objectMapper.createObjectNode();
        safe.put("instId", instId);
        safe.set("bids", copyStringMatrix(bids));
        safe.set("asks", copyStringMatrix(asks));
        safe.put("ts", required(item, "ts"));
        putIfText(safe, "seqId", item.path("seqId").asText(null));
        String safeJson = json(safe);
        String rawHash = sha256(safeJson);
        String sequence = textOrNull(item, "seqId");
        String dedupe = sha256(String.join("|", PROVIDER, "quote", instId,
                times.effectiveAt().toString(), sequence == null ? rawHash : sequence));
        Provenance provenance = provenance(capture, page, rawHash);
        CoverageProfiler.CoverageRecord coverage = coverage(dedupe, times, capture,
                CoverageProfiler.DataKind.DEPTH, CoverageProfiler.Usage.EXECUTABLE_DEPTH);
        return new QuoteAppend(dedupe, times, provenance, instId, instrumentType, "DEPTH",
                bidPx, bidSz, askPx, askSz, safeJson, sequence, coverage);
    }

    private FillAppend fill(JsonNode item,
                            String requestedInstrumentType,
                            OkxEvidenceReadClient.ReadPage page,
                            CaptureContext capture) {
        requireAccount(capture);
        String instType = upper(textOrDefault(item, "instType", requestedInstrumentType));
        if (!QUOTE_INSTRUMENTS.contains(instType)) {
            throw reject(RejectReason.UNSUPPORTED_INSTRUMENT);
        }
        String feeText = textOrNull(item, "fee");
        if (feeText == null) {
            throw reject(RejectReason.MISSING_SIGNED_FEE);
        }
        BigDecimal fee = decimal(feeText);
        String accountHash = accountHash(capture.accountOpaqueRef());
        String tradeId = required(item, "tradeId");
        String feeCcy = required(item, "feeCcy");
        Timestamps times = timestamps(item, item.hasNonNull("fillTime") ? "fillTime" : "ts", page, capture);
        ObjectNode safe = allowed(item, "instId", "instType", "ordId", "tradeId", "fillTime", "ts", "fee", "feeCcy");
        String rawHash = sha256(json(safe));
        String dedupe = sha256(String.join("|", PROVIDER, "fill", accountHash, tradeId, feeCcy));
        Provenance provenance = provenance(capture, page, rawHash);
        CoverageProfiler.CoverageRecord coverage = coverage(dedupe, times, capture,
                CoverageProfiler.DataKind.EVIDENCE, CoverageProfiler.Usage.FEATURE);
        return new FillAppend(dedupe, times, provenance, accountHash, required(item, "instId"), instType,
                required(item, "ordId"), tradeId, fee, feeCcy, coverage);
    }

    private FundingAppend funding(JsonNode item,
                                  OkxEvidenceReadClient.ReadPage page,
                                  CaptureContext capture) {
        requireAccount(capture);
        String amountText = textOrNull(item, "balChg");
        if (amountText == null) {
            throw reject(RejectReason.MISSING_SIGNED_FUNDING);
        }
        String instType = upper(textOrDefault(item, "instType", "SWAP"));
        if (!Set.of("SWAP", "FUTURES").contains(instType)) {
            throw reject(RejectReason.UNSUPPORTED_INSTRUMENT);
        }
        String accountHash = accountHash(capture.accountOpaqueRef());
        String billId = required(item, "billId");
        Timestamps times = timestamps(item, "ts", page, capture);
        ObjectNode safe = allowed(item, "billId", "instId", "instType", "posId", "balChg", "ccy", "ts", "type", "subType");
        String rawHash = sha256(json(safe));
        String dedupe = sha256(String.join("|", PROVIDER, "funding", accountHash, billId));
        Provenance provenance = provenance(capture, page, rawHash);
        CoverageProfiler.CoverageRecord coverage = coverage(dedupe, times, capture,
                CoverageProfiler.DataKind.EVIDENCE, CoverageProfiler.Usage.FEATURE);
        return new FundingAppend(dedupe, times, provenance, accountHash, required(item, "instId"), instType,
                billId, textOrNull(item, "posId"), decimal(amountText), required(item, "ccy"), coverage);
    }

    private MarginAppend margin(JsonNode item,
                                AccountSemantics semantics,
                                OkxEvidenceReadClient.ReadPage page,
                                CaptureContext capture) {
        requireAccount(capture);
        String instType = semantics.accountMode() == AccountMode.SIMPLE ? "SPOT" : "PORTFOLIO";
        String marginMode = switch (semantics.accountMode()) {
            case SIMPLE -> "CASH";
            case FUTURES, MULTI_CURRENCY -> "CROSS";
            case PORTFOLIO -> "PORTFOLIO";
        };
        String accountHash = accountHash(capture.accountOpaqueRef());
        Timestamps times = timestamps(item, "uTime", page, capture);
        BigDecimal equity = decimal(requiredOne(item, "adjEq", "totalEq"));
        BigDecimal available = decimal(required(item, "availEq"));
        BigDecimal used = decimal(required(item, "imr"));
        BigDecimal maintenance = decimal(required(item, "mmr"));
        BigDecimal ratio = optionalDecimal(item, "mgnRatio");
        if (equity.signum() < 0 || available.signum() < 0 || used.signum() < 0 || maintenance.signum() < 0
                || ratio != null && ratio.signum() < 0) {
            throw reject(RejectReason.INVALID_NUMBER);
        }
        ObjectNode safe = allowed(item, "adjEq", "totalEq", "availEq", "imr", "mmr", "mgnRatio", "uTime");
        String rawHash = sha256(json(safe));
        String symbol = null;
        String currency = "USD";
        String dedupe = sha256(String.join("|", PROVIDER, "margin", accountHash,
                semantics.accountMode().name(), semantics.positionMode().name(), times.effectiveAt().toString(), currency));
        Provenance provenance = provenance(capture, page, rawHash);
        CoverageProfiler.CoverageRecord coverage = coverage(dedupe, times, capture,
                CoverageProfiler.DataKind.EVIDENCE, CoverageProfiler.Usage.FEATURE);
        return new MarginAppend(dedupe, times, provenance, accountHash, symbol, instType, marginMode,
                equity, available, used, maintenance, ratio, currency, coverage);
    }

    private Timestamps timestamps(JsonNode item,
                                  String eventField,
                                  OkxEvidenceReadClient.ReadPage page,
                                  CaptureContext capture) {
        String raw = textOrNull(item, eventField);
        if (raw == null) {
            throw reject(RejectReason.MISSING_EVENT_TIMESTAMP);
        }
        final Instant event;
        try {
            event = Instant.ofEpochMilli(Long.parseLong(raw));
        } catch (RuntimeException e) {
            throw reject(RejectReason.MISSING_EVENT_TIMESTAMP);
        }
        Timestamps result = new Timestamps(event, event, page.availableAt(), page.observedAt(),
                capture == null ? null : capture.decisionAt(), capture == null ? null : capture.ingestedAt());
        if (result.availableAt() == null || result.observedAt() == null || result.decisionAt() == null
                || result.ingestedAt() == null || result.effectiveAt().isAfter(result.availableAt())
                || result.availableAt().isAfter(result.observedAt())
                || result.observedAt().isAfter(result.decisionAt())
                || result.decisionAt().isAfter(result.ingestedAt())) {
            throw reject(RejectReason.TIMESTAMP_ORDER_VIOLATION);
        }
        return result;
    }

    private Provenance provenance(CaptureContext capture,
                                  OkxEvidenceReadClient.ReadPage page,
                                  String rawHash) {
        if (capture == null || capture.provenance() == null
                || capture.provenance() == CoverageProfiler.Provenance.UNKNOWN) {
            throw reject(RejectReason.MISSING_REQUIRED_FIELD);
        }
        return new Provenance(PROVIDER, capture.provenance(), rawHash, page.nextCursor(), page.pageKey(),
                null, null, null, null, RETENTION, null);
    }

    private CoverageProfiler.CoverageRecord coverage(String dedupe,
                                                     Timestamps times,
                                                     CaptureContext capture,
                                                     CoverageProfiler.DataKind kind,
                                                     CoverageProfiler.Usage usage) {
        return new CoverageProfiler.CoverageRecord(dedupe, times.exchangeEventAt(), times.effectiveAt(),
                times.availableAt(), times.ingestedAt(), times.decisionAt(), PROVIDER,
                capture.provenance(), kind, usage);
    }

    private ObjectNode allowed(JsonNode item, String... fields) {
        ObjectNode safe = objectMapper.createObjectNode();
        for (String field : fields) {
            JsonNode value = item.get(field);
            if (value != null && !value.isNull()) {
                safe.set(field, value.deepCopy());
            }
        }
        return safe;
    }

    private ArrayNode copyStringMatrix(JsonNode source) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode row : source) {
            if (!row.isArray()) {
                throw reject(RejectReason.INVALID_BOOK);
            }
            ArrayNode copied = objectMapper.createArrayNode();
            for (JsonNode value : row) {
                copied.add(value.asText());
            }
            result.add(copied);
        }
        return result;
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw reject(RejectReason.INVALID_PROVIDER_RESPONSE);
        }
    }

    private static String required(JsonNode item, String field) {
        String value = textOrNull(item, field);
        if (value == null) {
            throw reject(RejectReason.MISSING_REQUIRED_FIELD);
        }
        return value;
    }

    private static String requiredOne(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = textOrNull(item, field);
            if (value != null) {
                return value;
            }
        }
        throw reject(RejectReason.MISSING_REQUIRED_FIELD);
    }

    private static String textOrDefault(JsonNode item, String field, String fallback) {
        String value = textOrNull(item, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode item, String field) {
        JsonNode value = item == null ? null : item.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private static BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (RuntimeException e) {
            throw reject(RejectReason.INVALID_NUMBER);
        }
    }

    private static BigDecimal optionalDecimal(JsonNode item, String field) {
        String value = textOrNull(item, field);
        return value == null ? null : decimal(value);
    }

    private static BigDecimal arrayDecimal(JsonNode row, int index) {
        if (row == null || !row.isArray() || row.size() <= index) {
            throw reject(RejectReason.INVALID_BOOK);
        }
        return decimal(row.get(index).asText());
    }

    private static void requireAccount(CaptureContext capture) {
        if (capture == null || capture.accountOpaqueRef() == null || capture.accountOpaqueRef().isBlank()) {
            throw reject(RejectReason.MISSING_REQUIRED_FIELD);
        }
    }

    private static String accountHash(String accountOpaqueRef) {
        return sha256("OKX|" + accountOpaqueRef.trim());
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static void putIfText(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static NormalizationBatch rejectedPage(Dataset dataset, RejectReason reason) {
        return new NormalizationBatch(List.of(), List.of(new RejectedEvidence(dataset, -1, reason)), null, false);
    }

    private static RejectedItem reject(RejectReason reason) {
        return new RejectedItem(reason);
    }

    @FunctionalInterface
    private interface ItemMapper {
        AppendCommand map(JsonNode item, int index);
    }

    private static final class RejectedItem extends RuntimeException {
        private final RejectReason reason;

        private RejectedItem(RejectReason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }
}
