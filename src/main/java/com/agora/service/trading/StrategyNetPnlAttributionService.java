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

    private final BtLiveSignalRepository liveSignalRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final MdKlineRepository klineRepository;
    private final ObjectMapper objectMapper;

    public String report(Long strategyId, String requestedSymbol, Integer requestedDays) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "getStrategyNetPnlAttribution");
        root.put("boundary", "READ_ONLY");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("strategyId", strategyId == null ? 0 : strategyId);
        String symbol = requestedSymbol == null || requestedSymbol.isBlank()
                ? Strategy508TimeExitPolicy.SYMBOL
                : requestedSymbol.trim().toUpperCase(Locale.ROOT);
        int days = Math.max(1, Math.min(requestedDays == null ? 90 : requestedDays, 730));
        root.put("symbol", symbol);
        root.put("days", days);
        root.put("legacyRealizedPnlSemantics", "GROSS_PRICE_DIFFERENCE; FEES ARE ATTRIBUTED FROM RUNTIME EVIDENCE");
        root.put("slippageSemantics", "INFORMATIONAL_COST_ALREADY_REFLECTED_IN ACTUAL FILL PNL; NOT SUBTRACTED TWICE");
        root.put("livePromotionAllowed", false);
        if (strategyId == null || strategyId <= 0) {
            root.put("status", "INVALID_STRATEGY_ID");
            return pretty(root);
        }

        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
        List<BtLiveSignal> signals = liveSignalRepository.findByStrategyIdAndCreatedAtAfter(strategyId, since).stream()
                .filter(signal -> symbol.equalsIgnoreCase(signal.getSymbol()))
                .filter(signal -> Boolean.TRUE.equals(signal.getAutoTraded()))
                .sorted(Comparator.comparing(BtLiveSignal::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        BigDecimal currentPrice = latestPrice(symbol);

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
        ArrayNode rows = root.putArray("positions");

        for (BtLiveSignal signal : signals) {
            FeeEvidence fees = feeEvidence(signal.getId());
            ObjectNode row = rows.addObject();
            row.put("liveSignalId", signal.getId());
            row.put("intervalCode", signal.getIntervalCode());
            row.put("policyTag", signal.getFilterReason());
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
                if (exitSlippage != null) knownExitSlippage = knownExitSlippage.add(exitSlippage);
                if (fees.complete()) {
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
                    row.put("netPnlStatus", "UNKNOWN_FEE_EVIDENCE");
                }
            } else {
                openCount++;
                row.put("status", "OPEN");
                put(row, "markPrice", currentPrice);
                BigDecimal gross = openPnl(signal, currentPrice);
                openGrossMark = openGrossMark.add(gross);
                put(row, "grossMarkToMarketPnlUsdt", gross);
                BigDecimal exitFeeEstimate = positive(currentPrice) && positive(signal.getTradedQty())
                        ? currentPrice.multiply(signal.getTradedQty()).multiply(ESTIMATED_EXIT_FEE_RATE)
                        : BigDecimal.ZERO;
                BigDecimal entryFee = fees.entryFeeUsdt() == null ? BigDecimal.ZERO : fees.entryFeeUsdt();
                BigDecimal netEstimate = gross.subtract(entryFee).subtract(exitFeeEstimate);
                openNetMarkEstimate = openNetMarkEstimate.add(netEstimate);
                put(row, "estimatedExitFeeUsdt", exitFeeEstimate);
                put(row, "estimatedNetMarkToMarketPnlUsdt", netEstimate);
                row.put("netPnlStatus", fees.entryFeeUsdt() == null
                        ? "ESTIMATE_WITH_UNKNOWN_ENTRY_FEE" : "ESTIMATE_WITH_KNOWN_ENTRY_FEE");
            }
        }

        double coveragePct = closedCount == 0 ? 100.0 : (double) completeFeeCount / closedCount * 100.0;
        ObjectNode summary = root.putObject("summary");
        summary.put("positions", signals.size());
        summary.put("closed", closedCount);
        summary.put("open", openCount);
        summary.put("closedWithCompleteFeeEvidence", completeFeeCount);
        summary.put("closedWithUnknownFees", partialFeeCount);
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
        put(summary, "openGrossMarkToMarketPnlUsdt", openGrossMark);
        put(summary, "openEstimatedNetMarkToMarketPnlUsdt", openNetMarkEstimate);
        root.put("status", closedCount == 0 ? "NO_CLOSED_POSITIONS"
                : exactClosedSample ? "COMPLETE_FEE_ATTRIBUTION"
                : "PARTIAL_FEE_ATTRIBUTION_FAIL_CLOSED");
        root.put("exactProfitClaimAllowed", exactClosedSample);
        return pretty(root);
    }

    private FeeEvidence feeEvidence(Long liveSignalId) {
        if (liveSignalId == null) return FeeEvidence.empty();
        List<RuntimeDecisionEvidence> rows = evidenceRepository
                .findByLiveSignalIdOrderByEvidenceTimeAsc(liveSignalId);
        BigDecimal entry = null;
        BigDecimal exit = null;
        BigDecimal gross = null;
        BigDecimal net = null;
        BigDecimal entryNetQty = null;
        for (RuntimeDecisionEvidence row : rows) {
            for (String json : List.of(nullToEmpty(row.getFeaturesSnapshotJson()),
                    nullToEmpty(row.getPolicyInputsJson()), nullToEmpty(row.getExecutionPreviewJson()))) {
                if (json.isBlank()) continue;
                try {
                    JsonNode node = objectMapper.readTree(json);
                    if (entry == null) entry = firstDecimal(node, "entryFeeUsdt", "fees.entryFeeUsdt");
                    if (exit == null) exit = firstDecimal(node, "totalExitFeeUsdt", "exitFeeUsdt", "fees.exitFeeUsdt");
                    if (gross == null) gross = signedDecimal(node, "grossPnlUsdt");
                    if (net == null) net = signedDecimal(node, "netPnlUsdt");
                    if (entryNetQty == null) entryNetQty = firstDecimal(node, "entryNetQty");
                } catch (Exception ignored) {
                    // Missing or legacy evidence remains explicitly unknown.
                }
            }
        }
        return new FeeEvidence(entry, exit, gross, net, entryNetQty);
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

    private BigDecimal firstDecimal(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = root;
            for (String part : path.split("\\.")) node = node.path(part);
            if (node.isNumber()) return node.decimalValue().abs();
            if (node.isTextual()) {
                try {
                    return new BigDecimal(node.asText()).abs();
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private BigDecimal latestPrice(String symbol) {
        List<MdKline> minute = klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                symbol, "1m", Strategy508TimeExitPolicy.KLINE_SOURCE, PageRequest.of(0, 1));
        if (!minute.isEmpty()) return minute.get(0).getClosePrice();
        List<MdKline> fourHour = klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                symbol, Strategy508TimeExitPolicy.INTERVAL, Strategy508TimeExitPolicy.KLINE_SOURCE,
                PageRequest.of(0, 1));
        return fourHour.isEmpty() ? null : fourHour.get(0).getClosePrice();
    }

    private BigDecimal grossPnl(BtLiveSignal signal) {
        if (!positive(entryPrice(signal)) || !positive(signal.getExitPrice()) || !positive(signal.getTradedQty())) {
            return BigDecimal.ZERO;
        }
        return signal.getExitPrice().subtract(entryPrice(signal)).multiply(signal.getTradedQty());
    }

    private BigDecimal openPnl(BtLiveSignal signal, BigDecimal mark) {
        if (!positive(entryPrice(signal)) || !positive(mark) || !positive(signal.getTradedQty())) {
            return BigDecimal.ZERO;
        }
        return mark.subtract(entryPrice(signal)).multiply(signal.getTradedQty());
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
                               BigDecimal entryNetQty) {
        static FeeEvidence empty() {
            return new FeeEvidence(null, null, null, null, null);
        }

        boolean complete() {
            return entryFeeUsdt != null && exitFeeUsdt != null;
        }
    }
}
