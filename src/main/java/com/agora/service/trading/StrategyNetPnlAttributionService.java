package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Fee-aware strategy PnL attribution without changing legacy realized_pnl semantics. */
@Service
@RequiredArgsConstructor
public class StrategyNetPnlAttributionService {

    private static final BigDecimal ESTIMATED_EXIT_FEE_RATE = new BigDecimal("0.001");
    private static final long MARK_PRICE_MAX_AGE_MINUTES = 5;

    private final BtLiveSignalRepository liveSignalRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final MdKlineRepository klineRepository;
    private final ObjectMapper objectMapper;

    public String report(Long strategyId, String requestedSymbol, Integer requestedDays) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "getStrategyNetPnlAttribution");
        root.put("boundary", "READ_ONLY");
        root.put("generatedAtUtc", now.toString());
        root.put("strategyId", strategyId == null ? 0 : strategyId);
        String symbol = requestedSymbol == null || requestedSymbol.isBlank()
                ? Strategy508TimeExitPolicy.SYMBOL
                : requestedSymbol.trim().toUpperCase(Locale.ROOT);
        int days = Math.max(1, Math.min(requestedDays == null ? 90 : requestedDays, 730));
        root.put("symbol", symbol);
        root.put("days", days);
        root.put("legacyRealizedPnlSemantics", "GROSS_PRICE_DIFFERENCE; FEES ARE ATTRIBUTED FROM RUNTIME EVIDENCE");
        root.put("slippageSemantics", "INFORMATIONAL_COST_ALREADY_REFLECTED_IN ACTUAL FILL PNL; NOT SUBTRACTED TWICE");
        root.put("exitSlippageQuantitySemantics", "ENTRY_NET_QUANTITY_PROXY_UNTIL_IMMUTABLE_EXIT_FILL_LEDGER_EXISTS");
        root.put("exactFeeEvidenceContract",
                "POLICY_DECLARATION_PLUS_ALL_FILLS_AGGREGATED_PLUS_FEE_SIGN_PRESERVED_PLUS_NET_PARITY");
        root.put("feeAmountSemantics", "POSITIVE_COST_NEGATIVE_REBATE");
        root.put("livePromotionAllowed", false);
        if (strategyId == null || strategyId <= 0) {
            root.put("status", "INVALID_STRATEGY_ID");
            return pretty(root);
        }

        LocalDateTime since = now.minusDays(days);
        List<BtLiveSignal> allSignals = liveSignalRepository
                .findByStrategyIdAndCreatedAtAfter(strategyId, since).stream()
                .filter(signal -> symbol.equalsIgnoreCase(signal.getSymbol()))
                .filter(signal -> Boolean.TRUE.equals(signal.getAutoTraded()))
                .sorted(Comparator.comparing(BtLiveSignal::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        boolean strategy508 = strategyId == Strategy508TimeExitPolicy.STRATEGY_ID;
        root.put("exactFillEvidenceProducerStatus", strategy508
                && !Strategy508TimeExitPolicy.EXACT_LIVE_FILL_EVIDENCE_IMPLEMENTED
                ? "BLOCKED_NO_IMMUTABLE_ALL_FILL_SIGNED_FEE_LEDGER"
                : strategy508 ? "REQUIRES_ROW_LEVEL_STRICT_CONTRACT"
                : "EXACT_CLAIM_DISABLED_UNVERSIONED_POLICY");
        List<BtLiveSignal> signals = allSignals;
        int policyTaggedPositions = (int) signals.stream().filter(this::isPolicyPosition).count();
        int nonPolicyPositions = signals.size() - policyTaggedPositions;
        root.put("cohortScope", "ALL_AUTO_TRADED_STRATEGY_POSITIONS");
        root.put("cohortFiltering", "NONE_GENERIC_TOOL_NEVER_HIDES_MATCHING_POSITIONS");
        root.put("exactProfitClaimScope", strategy508
                ? "ROW_LEVEL_STRATEGY_508_4H_24H_V1_ONLY"
                : "DISABLED_FOR_UNVERSIONED_LEGACY_COHORT");
        root.put("policyTaggedPositions", policyTaggedPositions);
        root.put("nonPolicyPositions", nonPolicyPositions);
        root.put("excludedNonPolicyPositions", 0);
        PriceSnapshot mark = latestPrice(symbol);
        long markAgeSeconds = mark.openTime() == null
                ? -1 : Duration.between(mark.openTime(), now).toSeconds();
        boolean markUsable = positive(mark.price())
                && mark.openTime() != null
                && markAgeSeconds >= 0
                && markAgeSeconds <= MARK_PRICE_MAX_AGE_MINUTES * 60;
        BigDecimal currentPrice = markUsable ? mark.price() : null;
        root.put("markPriceSource", mark.source());
        root.put("markPriceIntervalCode", mark.intervalCode());
        root.put("markPriceAsOfUtc", text(mark.openTime()));
        if (markAgeSeconds < 0) root.putNull("markPriceAgeSeconds");
        else root.put("markPriceAgeSeconds", markAgeSeconds);
        root.put("markPriceMaxAgeMinutes", MARK_PRICE_MAX_AGE_MINUTES);
        root.put("markPriceUsable", markUsable);
        root.put("markPriceStatus", markUsable ? "FRESH" : "STALE_OR_MISSING_FAIL_CLOSED");
        BigDecimal grossClosed = BigDecimal.ZERO;
        BigDecimal knownEntryFees = BigDecimal.ZERO;
        BigDecimal knownExitFees = BigDecimal.ZERO;
        BigDecimal knownNetClosed = BigDecimal.ZERO;
        BigDecimal openGrossMark = BigDecimal.ZERO;
        BigDecimal openNetMarkEstimate = BigDecimal.ZERO;
        BigDecimal knownEntrySlippage = BigDecimal.ZERO;
        BigDecimal knownExitSlippage = BigDecimal.ZERO;
        int closedCount = 0;
        int openCount = 0;
        int completeFeeCount = 0;
        int partialFeeCount = 0;
        int openKnownNetEstimateCount = 0;
        int openUnknownEntryFeeCount = 0;
        int openUsableMarkCount = 0;
        int openUnknownMarkCount = 0;
        ArrayNode rows = root.putArray("positions");

        for (BtLiveSignal signal : signals) {
            boolean policyPosition = isPolicyPosition(signal);
            boolean exactProducerAvailable = policyPosition
                    && Strategy508TimeExitPolicy.EXACT_LIVE_FILL_EVIDENCE_IMPLEMENTED;
            FeeEvidence fees = feeEvidence(signal.getId(), policyPosition);
            ObjectNode row = rows.addObject();
            row.put("liveSignalId", signal.getId());
            row.put("intervalCode", signal.getIntervalCode());
            row.put("policyTag", signal.getFilterReason());
            row.put("versionedPolicyPosition", policyPosition);
            row.put("exactProfitClaimEligiblePolicy", exactProducerAvailable);
            row.put("entryTime", text(signal.getCreatedAt()));
            put(row, "entryPrice", entryPrice(signal));
            BigDecimal attributedQty = positive(fees.entryNetQty())
                    ? fees.entryNetQty() : signal.getTradedQty();
            put(row, "quantity", attributedQty);
            put(row, "entryFeeUsdt", fees.entryFeeUsdt());
            put(row, "exitFeeUsdt", fees.exitFeeUsdt());
            row.put("entryFeeKnown", fees.entryFeeUsdt() != null);
            row.put("exitFeeKnown", fees.exitFeeUsdt() != null);
            BigDecimal entrySlippage = entrySlippage(signal, attributedQty);
            put(row, "entrySlippageUsdt", entrySlippage);
            if (entrySlippage != null) knownEntrySlippage = knownEntrySlippage.add(entrySlippage);

            if (signal.getExitTime() != null) {
                closedCount++;
                BigDecimal gross = fees.grossPnlUsdt() != null
                        ? fees.grossPnlUsdt()
                        : signal.getRealizedPnl() == null ? grossPnl(signal) : signal.getRealizedPnl();
                grossClosed = grossClosed.add(zero(gross));
                row.put("status", "CLOSED");
                row.put("exitTime", text(signal.getExitTime()));
                row.put("exitReason", signal.getExitReason());
                put(row, "exitPrice", signal.getExitPrice());
                put(row, "grossRealizedPnlUsdt", gross);
                BigDecimal exitSlippage = exitSlippage(signal, attributedQty);
                put(row, "exitSlippageUsdt", exitSlippage);
                row.put("exitSlippageStatus", exitSlippage == null
                        ? "NOT_APPLICABLE_OR_MISSING_REFERENCE"
                        : "INFORMATIONAL_ENTRY_QUANTITY_PROXY");
                if (exitSlippage != null) knownExitSlippage = knownExitSlippage.add(exitSlippage);
                boolean netEvidenceMatches = netEvidenceMatches(
                        gross, fees.entryFeeUsdt(), fees.exitFeeUsdt(), fees.netPnlUsdt());
                boolean exactFeeEvidence = exactProducerAvailable
                        && fees.complete() && netEvidenceMatches;
                row.put("feeCoverageDeclaredComplete", fees.feeCoverageComplete() != null
                        && fees.feeCoverageComplete());
                row.put("fillAggregationComplete", fees.fillAggregationComplete() != null
                        && fees.fillAggregationComplete());
                row.put("feeSignPreserved", fees.feeSignPreserved() != null
                        && fees.feeSignPreserved());
                if (fees.partialExitFeeCoverageComplete() == null) {
                    row.putNull("partialExitFeeCoverageComplete");
                } else {
                    row.put("partialExitFeeCoverageComplete", fees.partialExitFeeCoverageComplete());
                }
                row.put("netPnlEvidenceMatches", netEvidenceMatches);
                if (exactFeeEvidence) {
                    completeFeeCount++;
                    knownEntryFees = knownEntryFees.add(fees.entryFeeUsdt());
                    knownExitFees = knownExitFees.add(fees.exitFeeUsdt());
                    BigDecimal net = zero(gross).subtract(fees.entryFeeUsdt()).subtract(fees.exitFeeUsdt());
                    knownNetClosed = knownNetClosed.add(net);
                    put(row, "netRealizedPnlUsdt", net);
                    row.put("netPnlStatus", "EXACT_FEE_EVIDENCE");
                } else {
                    partialFeeCount++;
                    row.putNull("netRealizedPnlUsdt");
                    row.put("netPnlStatus", !netEvidenceMatches
                            ? "NET_PNL_EVIDENCE_MISMATCH"
                            : "UNKNOWN_OR_INCOMPLETE_FEE_EVIDENCE");
                }
            } else {
                openCount++;
                row.put("status", "OPEN");
                put(row, "markPrice", currentPrice);
                BigDecimal gross = openPnl(signal, currentPrice, attributedQty);
                if (gross != null) {
                    openGrossMark = openGrossMark.add(gross);
                    openUsableMarkCount++;
                } else {
                    openUnknownMarkCount++;
                }
                put(row, "grossMarkToMarketPnlUsdt", gross);
                BigDecimal exitFeeEstimate = positive(currentPrice) && positive(attributedQty)
                        ? currentPrice.multiply(attributedQty).multiply(ESTIMATED_EXIT_FEE_RATE)
                        : null;
                put(row, "estimatedExitFeeUsdt", exitFeeEstimate);
                if (gross == null || exitFeeEstimate == null) {
                    row.putNull("estimatedNetMarkToMarketPnlUsdt");
                    row.put("netPnlStatus", "UNKNOWN_OR_STALE_MARK_NO_ESTIMATE");
                } else if (fees.entryFeeUsdt() == null) {
                    openUnknownEntryFeeCount++;
                    row.putNull("estimatedNetMarkToMarketPnlUsdt");
                    row.put("netPnlStatus", "UNKNOWN_ENTRY_FEE_NO_NET_ESTIMATE");
                } else {
                    openKnownNetEstimateCount++;
                    BigDecimal netEstimate = gross.subtract(fees.entryFeeUsdt()).subtract(exitFeeEstimate);
                    openNetMarkEstimate = openNetMarkEstimate.add(netEstimate);
                    put(row, "estimatedNetMarkToMarketPnlUsdt", netEstimate);
                    row.put("netPnlStatus", "ESTIMATE_WITH_KNOWN_ENTRY_FEE");
                }
            }
        }

        double coveragePct = closedCount == 0 ? 100.0 : (double) completeFeeCount / closedCount * 100.0;
        ObjectNode summary = root.putObject("summary");
        summary.put("positions", signals.size());
        summary.put("closed", closedCount);
        summary.put("open", openCount);
        summary.put("closedWithCompleteFeeEvidence", completeFeeCount);
        summary.put("closedWithUnknownFees", partialFeeCount);
        summary.put("openWithKnownEntryFeeEstimate", openKnownNetEstimateCount);
        summary.put("openWithUnknownEntryFee", openUnknownEntryFeeCount);
        summary.put("openWithUsableMark", openUsableMarkCount);
        summary.put("openWithUnknownOrStaleMark", openUnknownMarkCount);
        summary.put("feeCoveragePct", round(coveragePct));
        put(summary, "grossClosedPnlUsdt", grossClosed);
        put(summary, "knownEntryFeesUsdt", knownEntryFees);
        put(summary, "knownExitFeesUsdt", knownExitFees);
        put(summary, "knownNetClosedPnlUsdt", knownNetClosed);
        put(summary, "knownEntrySlippageUsdt", knownEntrySlippage);
        put(summary, "knownExitSlippageUsdt", knownExitSlippage);
        boolean exactClosedSample = closedCount > 0 && completeFeeCount == closedCount;
        if (exactClosedSample) put(summary, "exactNetClosedPnlUsdt", knownNetClosed);
        else summary.putNull("exactNetClosedPnlUsdt");
        if (openCount == 0 || openUsableMarkCount == openCount) {
            put(summary, "openGrossMarkToMarketPnlUsdt", openGrossMark);
        } else {
            summary.putNull("openGrossMarkToMarketPnlUsdt");
        }
        if (openCount > 0 && openKnownNetEstimateCount == openCount) {
            put(summary, "openEstimatedNetMarkToMarketPnlUsdt", openNetMarkEstimate);
        } else {
            summary.putNull("openEstimatedNetMarkToMarketPnlUsdt");
        }
        root.put("status", signals.isEmpty() ? "NO_POSITIONS"
                : closedCount == 0 ? "NO_CLOSED_POSITIONS"
                : policyTaggedPositions > 0
                && !Strategy508TimeExitPolicy.EXACT_LIVE_FILL_EVIDENCE_IMPLEMENTED
                ? "EXACT_FEE_ATTRIBUTION_BLOCKED_PRODUCER_UNAVAILABLE"
                : exactClosedSample ? "COMPLETE_FEE_ATTRIBUTION"
                : "PARTIAL_FEE_ATTRIBUTION_FAIL_CLOSED");
        root.put("exactProfitClaimAllowed", exactClosedSample);
        return pretty(root);
    }

    private FeeEvidence feeEvidence(Long liveSignalId, boolean policyPosition) {
        if (liveSignalId == null) return FeeEvidence.empty();
        List<RuntimeDecisionEvidence> rows = evidenceRepository
                .findByLiveSignalIdOrderByEvidenceTimeAsc(liveSignalId);
        BigDecimal entry = null;
        BigDecimal exit = null;
        BigDecimal gross = null;
        BigDecimal net = null;
        BigDecimal entryNetQty = null;
        Boolean feeCoverageComplete = null;
        Boolean partialExitFeeCoverageComplete = null;
        Boolean fillAggregationComplete = null;
        Boolean feeSignPreserved = null;
        boolean malformed = false;
        for (RuntimeDecisionEvidence row : rows) {
            if (policyPosition && !Strategy508TimeExitPolicy.POLICY_MODE.equals(row.getPolicyMode())) continue;
            for (String json : List.of(nullToEmpty(row.getFeaturesSnapshotJson()),
                    nullToEmpty(row.getPolicyInputsJson()), nullToEmpty(row.getExecutionPreviewJson()))) {
                if (json.isBlank()) continue;
                try {
                    JsonNode node = objectMapper.readTree(json);
                    if (entry == null) entry = firstSignedDecimal(
                            node, "entryFeeUsdt", "fees.entryFeeUsdt");
                    if (exit == null) exit = firstSignedDecimal(
                            node, "totalExitFeeUsdt", "exitFeeUsdt", "fees.exitFeeUsdt");
                    if (gross == null) gross = signedDecimal(node, "grossPnlUsdt");
                    if (net == null) net = signedDecimal(node, "netPnlUsdt");
                    if (entryNetQty == null) entryNetQty = firstMagnitudeDecimal(node, "entryNetQty");
                    if (node.has("feeCoverageComplete")) {
                        feeCoverageComplete = node.path("feeCoverageComplete").asBoolean(false);
                    }
                    if (node.has("partialExitFeeCoverageComplete")) {
                        partialExitFeeCoverageComplete = node.path(
                                "partialExitFeeCoverageComplete").asBoolean(false);
                    }
                    if (node.has("fillAggregationComplete")) {
                        fillAggregationComplete = node.path("fillAggregationComplete").asBoolean(false);
                    }
                    if (node.has("feeSignPreserved")) {
                        feeSignPreserved = node.path("feeSignPreserved").asBoolean(false);
                    }
                } catch (Exception ignored) {
                    malformed = true;
                }
            }
        }
        return new FeeEvidence(entry, exit, gross, net, entryNetQty,
                feeCoverageComplete, partialExitFeeCoverageComplete,
                fillAggregationComplete, feeSignPreserved, malformed);
    }

    private BigDecimal signedDecimal(JsonNode root, String path) {
        JsonNode node = root.path(path);
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private BigDecimal firstSignedDecimal(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = root;
            for (String part : path.split("\\.")) node = node.path(part);
            if (node.isNumber()) return node.decimalValue();
            if (node.isTextual()) {
                try {
                    return new BigDecimal(node.asText());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private BigDecimal firstMagnitudeDecimal(JsonNode root, String... paths) {
        BigDecimal value = firstSignedDecimal(root, paths);
        return value == null ? null : value.abs();
    }

    private PriceSnapshot latestPrice(String symbol) {
        List<MdKline> minute = klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                symbol, "1m", Strategy508TimeExitPolicy.KLINE_SOURCE, PageRequest.of(0, 1));
        if (!minute.isEmpty()) {
            MdKline bar = minute.get(0);
            return new PriceSnapshot(bar.getClosePrice(), bar.getOpenTime(),
                    Strategy508TimeExitPolicy.KLINE_SOURCE, "1m");
        }
        List<MdKline> fourHour = klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                symbol, Strategy508TimeExitPolicy.INTERVAL, Strategy508TimeExitPolicy.KLINE_SOURCE,
                PageRequest.of(0, 1));
        if (!fourHour.isEmpty()) {
            MdKline bar = fourHour.get(0);
            return new PriceSnapshot(bar.getClosePrice(), bar.getOpenTime(),
                    Strategy508TimeExitPolicy.KLINE_SOURCE, Strategy508TimeExitPolicy.INTERVAL);
        }
        return PriceSnapshot.empty();
    }

    private BigDecimal grossPnl(BtLiveSignal signal) {
        if (!positive(entryPrice(signal)) || !positive(signal.getExitPrice()) || !positive(signal.getTradedQty())) {
            return BigDecimal.ZERO;
        }
        return signal.getExitPrice().subtract(entryPrice(signal)).multiply(signal.getTradedQty());
    }

    private BigDecimal openPnl(BtLiveSignal signal, BigDecimal mark, BigDecimal quantity) {
        if (!positive(entryPrice(signal)) || !positive(mark) || !positive(quantity)) {
            return null;
        }
        return mark.subtract(entryPrice(signal)).multiply(quantity);
    }

    private BigDecimal entrySlippage(BtLiveSignal signal, BigDecimal quantity) {
        if (!positive(signal.getEntryPrice()) || !positive(signal.getActualEntryPrice()) || !positive(quantity)) {
            return null;
        }
        return signal.getActualEntryPrice().subtract(signal.getEntryPrice())
                .multiply(quantity).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal exitSlippage(BtLiveSignal signal, BigDecimal quantity) {
        if (!positive(signal.getExitPrice()) || !positive(quantity)) return null;
        BigDecimal reference;
        if ("TP".equalsIgnoreCase(signal.getExitReason())) reference = signal.getSuggestedTp();
        else if ("SL".equalsIgnoreCase(signal.getExitReason())) reference = signal.getSuggestedSl();
        else return null;
        if (!positive(reference)) return null;
        return reference.subtract(signal.getExitPrice())
                .multiply(quantity).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal entryPrice(BtLiveSignal signal) {
        return positive(signal.getActualEntryPrice()) ? signal.getActualEntryPrice() : signal.getEntryPrice();
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean netEvidenceMatches(BigDecimal gross,
                                       BigDecimal entryFee,
                                       BigDecimal exitFee,
                                       BigDecimal reportedNet) {
        if (gross == null || entryFee == null || exitFee == null || reportedNet == null) return false;
        BigDecimal calculated = gross.subtract(entryFee).subtract(exitFee);
        return calculated.subtract(reportedNet).abs().compareTo(new BigDecimal("0.00000001")) <= 0;
    }

    private boolean isPolicyPosition(BtLiveSignal signal) {
        return signal != null
                && Strategy508TimeExitPolicy.POLICY_MODE.equals(signal.getFilterReason());
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void put(ObjectNode node, String field, BigDecimal value) {
        if (value == null) node.putNull(field);
        else node.put(field, value.setScale(8, RoundingMode.HALF_UP).toPlainString());
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private String pretty(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return root.toString();
        }
    }

    private record FeeEvidence(BigDecimal entryFeeUsdt,
                               BigDecimal exitFeeUsdt,
                               BigDecimal grossPnlUsdt,
                               BigDecimal netPnlUsdt,
                               BigDecimal entryNetQty,
                               Boolean feeCoverageComplete,
                               Boolean partialExitFeeCoverageComplete,
                               Boolean fillAggregationComplete,
                               Boolean feeSignPreserved,
                               boolean malformed) {
        static FeeEvidence empty() {
            return new FeeEvidence(null, null, null, null, null,
                    null, null, null, null, false);
        }

        boolean complete() {
            boolean feesPresent = entryFeeUsdt != null && exitFeeUsdt != null;
            return feesPresent
                    && grossPnlUsdt != null
                    && netPnlUsdt != null
                    && Boolean.TRUE.equals(feeCoverageComplete)
                    && !Boolean.FALSE.equals(partialExitFeeCoverageComplete)
                    && Boolean.TRUE.equals(fillAggregationComplete)
                    && Boolean.TRUE.equals(feeSignPreserved)
                    && !malformed;
        }
    }

    private record PriceSnapshot(BigDecimal price,
                                 LocalDateTime openTime,
                                 String source,
                                 String intervalCode) {
        static PriceSnapshot empty() {
            return new PriceSnapshot(null, null, null, null);
        }
    }
}
